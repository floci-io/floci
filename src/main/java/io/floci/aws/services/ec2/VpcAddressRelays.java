package io.floci.aws.services.ec2;

import com.github.dockerjava.api.model.Container;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.SocatTcpRelay;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Endpoints served at their own address in a subnet, as an AWS service puts a network interface in a subnet for an
 * endpoint of a resource it runs there. A relay container joins the VPC's Docker network at an address EC2 leases
 * from the subnet, listens on the endpoint's port and forwards each connection to the Floci listener that serves the
 * endpoint, so endpoints in one VPC can share a port, each at its own address.
 *
 * <p>A relay holds no state worth keeping: {@link #stopAll} removes every one when Floci stops, and a restart serves
 * each endpoint again at the address it held, which the owning service keeps with the endpoint. The relay also
 * listens on its address on Floci's own network, which no endpoint reports. The leases, and the subnet and VPC
 * dependencies they answer for EC2, are {@link VpcContainerLeases}'.
 *
 * <p>Every relay that may exist is written down in its {@link Placements}, from just before it starts until Docker
 * confirms it removed, so one Docker leaves behind is never lost: its target port stays taken
 * ({@link #targetsPort}), and a restart removes it or, when Docker still does not confirm that, leases its address
 * again ({@link #recoverAll}).
 */
public final class VpcAddressRelays {

    private static final Logger LOG = Logger.getLogger(VpcAddressRelays.class);
    private static final int LOCK_STRIPES = 64;

    private final VpcContainerLeases leases;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;
    private final String service;
    private final String image;
    /** The network the relays share with Floci, or null for Floci's default. */
    private final String network;
    private final Placements placements;
    /** The relay serving each endpoint, by the endpoint's relay id. */
    private final Map<String, Served> served = new ConcurrentHashMap<>();
    /** Locks by endpoint, striped so they stay bounded: a serve and a stop of one endpoint never interleave. */
    private final Object[] locks = new Object[LOCK_STRIPES];

    /**
     * @param leases           leases the relays' addresses
     * @param containerBuilder builds the relay containers
     * @param lifecycleManager starts and removes them
     * @param config           names and labels them
     * @param service          the service whose endpoints they serve, as the {@code io.floci.service} label and the
     *                         first part of a relay's name, such as {@code "rds-endpoint"}
     * @param image            an image whose entrypoint can be set to {@code socat}
     * @param network          the network the relays share with Floci, which they reach their targets on, as a
     *                         service's {@code docker-network} setting names it, or null for Floci's default
     * @param placements       where the relays that may exist are written down, to outlive a restart
     */
    public VpcAddressRelays(VpcContainerLeases leases, ContainerBuilder containerBuilder,
                            ContainerLifecycleManager lifecycleManager, EmulatorConfig config, String service,
                            String image, String network, Placements placements) {
        this.leases = leases;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
        this.service = service;
        this.image = image;
        this.network = network;
        this.placements = placements;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    /**
     * One endpoint to serve: its {@code port} at an address of {@code subnetId}, relayed to {@code targetHost} on
     * {@code targetPort}.
     *
     * @param id    the endpoint's relay id, unique among the service's endpoints and valid in a Docker name
     * @param vpcId the VPC the resource names beside its subnet, or null when it names none
     * @param name  the endpoint, for the log
     */
    @RegisterForReflection
    public record Relay(String id, String accountId, String region, String vpcId, String subnetId, int port,
                        String targetHost, int targetPort, String name) {
    }

    /** A relay that may exist, and the address it was given. */
    @RegisterForReflection
    public record Placement(Relay relay, String address) {
    }

    /**
     * Where the relays that may exist are written down. The owning service keeps them in its own storage, so they
     * outlive a restart.
     */
    public interface Placements {
        void put(Placement placement);

        void remove(Placement placement);

        List<Placement> all();

        /** Placements kept in memory only, gone with the process. */
        static Placements inMemory() {
            Map<String, Placement> kept = new ConcurrentHashMap<>();
            return new Placements() {
                @Override
                public void put(Placement placement) {
                    kept.put(placement.relay().id(), placement);
                }

                @Override
                public void remove(Placement placement) {
                    kept.remove(placement.relay().id(), placement);
                }

                @Override
                public List<Placement> all() {
                    return new ArrayList<>(kept.values());
                }
            };
        }
    }

    private record Served(Relay relay, String containerId, String address) {
    }

    /**
     * Serves {@code relay} and returns its address: at {@code preferredAddress}, the address the endpoint held before,
     * when the subnet still has it to give, and at the next free one otherwise. A relay already serving the same
     * endpoint the same way is kept, and one serving it another way, such as to another target port, is replaced,
     * keeping its address when it can.
     *
     * @throws IllegalStateException when the subnet has no address to give: EC2 does not know it, its VPC has no
     *                               Docker-backed network, or every address is held; or when Docker does not
     *                               confirm the endpoint's earlier relay removed, which stays as it was
     */
    public String serve(Relay relay, String preferredAddress) {
        synchronized (lock(relay.id())) {
            return serveLocked(relay, preferredAddress);
        }
    }

    private String serveLocked(Relay relay, String preferredAddress) {
        Served current = served.get(relay.id());
        if (current != null && current.relay().equals(relay) && running(current.containerId())) {
            return current.address();
        }
        String wanted = current != null ? current.address() : preferredAddress;
        stopLocked(relay.id());
        // Docker refuses a second container under the name, so one it still has, however it came to be, goes.
        lifecycleManager.removeIfExistsStrict(containerName(relay.id()));
        ContainerSpec spec = containerBuilder.newContainer(image)
                .withName(containerName(relay.id()))
                .withDockerNetwork(Optional.ofNullable(network))
                .withHostDockerInternalOnLinux()
                .withEntrypoint(List.of("socat"))
                .withCmd(List.of(SocatTcpRelay.listen(relay.port()),
                        SocatTcpRelay.connect(relay.targetHost(), relay.targetPort())))
                .withLabels(labels(relay))
                .withLogRotation()
                .build();
        AtomicReference<String> joinedContainer = new AtomicReference<>();
        AtomicReference<String> address = new AtomicReference<>();
        try {
            String containerId = lifecycleManager.createAndStart(spec, created -> {
                VpcContainerLeases.Lease lease = leases.join(relay.accountId(), relay.region(), relay.vpcId(),
                                relay.subnetId(), created, relay.name(), wanted)
                        .orElseThrow(() -> new IllegalStateException("Subnet " + relay.subnetId()
                                + " has no address to serve " + relay.name() + " at"));
                joinedContainer.set(created);
                address.set(lease.address());
                placements.put(new Placement(relay, lease.address()));
            }).containerId();
            served.put(relay.id(), new Served(relay, containerId, address.get()));
            LOG.infov("{0} served at {1}:{2,number,#}, relayed to {3}:{4,number,#}", relay.name(), address.get(),
                    relay.port(), relay.targetHost(), relay.targetPort());
            return address.get();
        } catch (RuntimeException e) {
            String joined = joinedContainer.get();
            if (joined != null) {
                try {
                    lifecycleManager.removeIfExistsStrict(joined);
                    leases.releaseIfGone(joined);
                    placements.remove(new Placement(relay, address.get()));
                } catch (RuntimeException removal) {
                    // Docker may run the relay all the same: tracked, it keeps its address, its owner keeps what it
                    // forwards to, and a later stop removes it.
                    served.put(relay.id(), new Served(relay, joined, address.get()));
                    e.addSuppressed(removal);
                }
            }
            throw e;
        }
    }

    /**
     * Stops serving the endpoint: its relay is removed, by the container it is and by its name, and its address is
     * given back. Nothing happens, and Docker is not asked, for an endpoint no relay serves and none is written down
     * for.
     *
     * @throws IllegalStateException when Docker does not confirm the relay removed: it is still tracked, keeping its
     *                               address, so the caller keeps what the relay forwards to, and a later stop or
     *                               serve tries again
     */
    public void stop(String id) {
        if (id == null) {
            return;
        }
        synchronized (lock(id)) {
            stopLocked(id);
        }
    }

    private void stopLocked(String id) {
        Served current = served.get(id);
        List<Placement> placed = placements.all().stream().filter(placement -> placement.relay().id().equals(id))
                .toList();
        if (current == null && placed.isEmpty()) {
            return;
        }
        try {
            if (current != null) {
                lifecycleManager.removeIfExistsStrict(current.containerId());
            }
            lifecycleManager.removeIfExistsStrict(containerName(id));
        } catch (RuntimeException e) {
            throw new IllegalStateException("Docker did not confirm the relay of endpoint " + id + " removed", e);
        }
        if (current != null) {
            served.remove(id, current);
            leases.releaseIfGone(current.containerId());
        }
        placed.forEach(placements::remove);
    }

    /**
     * Removes every relay, as Floci stops or resets: a restart serves each endpoint again at the address it held. A
     * relay Docker does not confirm removed is logged and stays tracked and written down, which the next start takes
     * up ({@link #recoverAll}).
     */
    public void stopAll() {
        for (String id : List.copyOf(served.keySet())) {
            try {
                stop(id);
            } catch (RuntimeException e) {
                LOG.warnv(e, "Could not remove the relay of endpoint {0}", id);
            }
        }
    }

    /**
     * As Floci starts, takes up every relay an earlier Floci left. One written down is removed when Docker confirms
     * it, and otherwise tracked with its address leased again, so its subnet and VPC stay in use and nothing else is
     * given the address, until a later stop or serve removes it; its target port stays taken either way until then.
     * One of this Floci's relays Docker still has that is not written down, as a crash before its placement was
     * written leaves, holds no address and is removed.
     */
    public void recoverAll() {
        Set<String> placed = new HashSet<>();
        for (Placement placement : placements.all()) {
            String id = placement.relay().id();
            placed.add(id);
            synchronized (lock(id)) {
                if (served.containsKey(id)) {
                    continue;
                }
                String name = containerName(id);
                try {
                    lifecycleManager.removeIfExistsStrict(name);
                    placements.remove(placement);
                } catch (RuntimeException e) {
                    LOG.warnv(e, "{0} keeps the relay an earlier Floci left, at {1}, until it can be removed",
                            placement.relay().name(), placement.address());
                    leases.reclaim(placement.relay().accountId(), placement.relay().region(),
                                    placement.relay().subnetId(), name, List.of(placement.address()),
                                    placement.relay().name())
                            .ifPresent(lease -> served.put(id, new Served(placement.relay(), name, lease.address())));
                }
            }
        }
        for (Container survivor : ownRelays()) {
            String id = ContainerStorageHelper.labelValue(survivor.getLabels(), ContainerStorageHelper.RESOURCE_ID_LABEL);
            if (id == null || (!placed.contains(id) && !served.containsKey(id))) {
                lifecycleManager.removeIfExists(survivor.getId());
            }
        }
    }

    /**
     * Writes down again every relay still tracked, after a reset wiped the service's storage: one Docker did not
     * confirm removed as the reset stopped it keeps its target port taken, and the next start takes it up.
     */
    public void rewritePlacements() {
        for (Served current : List.copyOf(served.values())) {
            placements.put(new Placement(current.relay(), current.address()));
        }
    }

    private List<Container> ownRelays() {
        try {
            return ContainerStorageHelper.CONTAINER_LABEL_ALIASES.listByLabels(
                    Map.of(ContainerStorageHelper.SERVICE_LABEL, service,
                            ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config)),
                    filter -> lifecycleManager.getDockerClient().listContainersCmd().withShowAll(true)
                            .withLabelFilter(filter).exec(),
                    Container::getId);
        } catch (RuntimeException e) {
            LOG.warnv("Could not list the {0} relays an earlier Floci may have left: {1}", service, e.getMessage());
            return List.of();
        }
    }

    /**
     * The Docker network a relay joins besides its VPC's: the service's own, else the global one, else Floci's own, as
     * {@link ContainerBuilder#resolveDockerNetwork} picks it. A relay reaches its target over this network.
     */
    public Optional<String> network() {
        return containerBuilder.resolveDockerNetwork(Optional.ofNullable(network));
    }

    /** Whether a relay that may exist forwards to {@code port}, which must then not be given to anything else. */
    public boolean targetsPort(int port) {
        return placements.all().stream().anyMatch(placement -> placement.relay().targetPort() == port);
    }

    /** The address the endpoint is served at, while a relay serves it. */
    public Optional<String> address(String id) {
        Served current = id == null ? null : served.get(id);
        return Optional.ofNullable(current == null ? null : current.address());
    }

    /** Whether a relay of the account holds an address in the subnet, as {@link VpcContainerLeases#holdsSubnet}. */
    public boolean holdsSubnet(String accountId, String region, String subnetId) {
        return leases.holdsSubnet(accountId, region, subnetId);
    }

    /** Whether a relay of the account is on the VPC's network, as {@link VpcContainerLeases#holdsVpc}. */
    public boolean holdsVpc(String accountId, String region, String vpcId) {
        return leases.holdsVpc(accountId, region, vpcId);
    }

    private Object lock(String id) {
        return locks[Math.floorMod(id.hashCode(), LOCK_STRIPES)];
    }

    String containerName(String id) {
        return ContainerStorageHelper.dockerName(config, service + "-" + id);
    }

    private Map<String, String> labels(Relay relay) {
        Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.resourceIdentityLabels(
                service, relay.id(), relay.accountId(), relay.region()));
        labels.put(ContainerStorageHelper.OWNER_LABEL, ContainerStorageHelper.ownerIdentity(config));
        return labels;
    }

    private boolean running(String containerId) {
        try {
            return Boolean.TRUE.equals(lifecycleManager.getDockerClient().inspectContainerCmd(containerId).exec()
                    .getState().getRunning());
        } catch (RuntimeException e) {
            LOG.debugv("Relay container {0} could not be inspected: {1}", containerId, e.getMessage());
            return false;
        }
    }
}
