package io.floci.aws.services.rds;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsEndpoints;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.VpcAddressRelays;
import io.floci.aws.services.ec2.VpcContainerLeases;
import io.floci.aws.services.ec2.VpcDependencyProvider;
import io.floci.aws.services.rds.model.DbEndpoint;
import io.floci.aws.services.rds.model.DbInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DB instance endpoints served in their VPC, with {@code endpoints-in-vpc} on. As RDS places an instance in a subnet
 * of its DB subnet group, an instance's endpoint gets its own address in the subnet of its Availability Zone, where it
 * serves the Port it was created with, or its engine's default, under the name RDS gives it,
 * {@code <instance>.<identifier>.<region>.rds.<dnsSuffix>}. A relay at that address forwards each connection to the
 * instance's auth proxy, so IAM authentication, TLS and the master login work as they do on the proxy port, and two
 * instances can both serve 5432.
 *
 * <p>The endpoint keeps its relay id, subnet and address on the instance record: a restart serves it again at the
 * same address, and a blue/green switchover moves them with the endpoint, as it moves the proxy port. Without
 * Docker-backed VPC networks, in mock mode, or with the setting off, instances keep the proxy endpoint on Floci's
 * address.
 */
@ApplicationScoped
public class RdsEndpointAddresses implements VpcDependencyProvider, ContainerTeardown, Resettable {

    static final String SERVICE = "rds-endpoint";
    private static final Logger LOG = Logger.getLogger(RdsEndpointAddresses.class);

    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final DockerHostResolver dockerHostResolver;
    private final VpcAddressRelays relays;
    /**
     * The relay id of each endpoint name Floci knows, lowercase: Floci's DNS server answers from it, an instance's
     * name staying known while it is stopped, until it is deleted or taken out of its VPC.
     */
    private final Map<String, String> relayIdsByHost = new ConcurrentHashMap<>();

    @Inject
    public RdsEndpointAddresses(EmulatorConfig config, Ec2Service ec2Service, RegionResolver regionResolver,
                                ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                DockerHostResolver dockerHostResolver, StorageFactory storageFactory) {
        this(config, regionResolver, dockerHostResolver, new VpcAddressRelays(
                new VpcContainerLeases(ec2Service, "RDS endpoint", lifecycleManager::getDockerClient),
                containerBuilder, lifecycleManager, config, SERVICE, config.services().ec2().socatImage(),
                config.services().rds().dockerNetwork().orElse(null),
                stored(storageFactory.create("rds", "rds-endpoint-relays.json",
                        new TypeReference<Map<String, VpcAddressRelays.Placement>>() {}))));
    }

    /** The relays that may exist, kept with RDS's records so a restart finds every one. */
    private static VpcAddressRelays.Placements stored(AccountAwareStorageBackend<VpcAddressRelays.Placement> store) {
        return new VpcAddressRelays.Placements() {
            @Override
            public void put(VpcAddressRelays.Placement placement) {
                store.putForAccount(placement.relay().accountId(), placement.relay().id(), placement);
            }

            @Override
            public void remove(VpcAddressRelays.Placement placement) {
                store.deleteForAccount(placement.relay().accountId(), placement.relay().id());
            }

            @Override
            public List<VpcAddressRelays.Placement> all() {
                return store.scanAllAccounts();
            }
        };
    }

    RdsEndpointAddresses(EmulatorConfig config, RegionResolver regionResolver, DockerHostResolver dockerHostResolver,
                         VpcAddressRelays relays) {
        this.config = config;
        this.regionResolver = regionResolver;
        this.dockerHostResolver = dockerHostResolver;
        this.relays = relays;
    }

    /** Whether new endpoints go in their VPC: the setting on, outside mock mode, with Docker-backed VPC networks. */
    public boolean enabled() {
        return config.services().rds().endpointsInVpc() && !config.services().rds().mock()
                && config.services().ec2().vpcNetworks().enabled() && !config.services().ec2().mock();
    }

    /**
     * Gives a standalone instance an endpoint in its VPC, served in the subnet of its Availability Zone, or the
     * first subnet of its DB subnet group when none is in it. False, leaving the instance as it was, when endpoints
     * do not go in their VPC or the instance has no subnet.
     */
    public boolean assign(DbInstance instance) {
        String subnetId = enabled() ? subnetFor(instance) : null;
        if (subnetId == null) {
            return false;
        }
        instance.setVpcEndpointId(newRelayId());
        instance.setVpcEndpointSubnetId(subnetId);
        instance.setVpcEndpointAddress(null);
        return true;
    }

    /** A relay id no endpoint has, valid in a Docker name. */
    public static String newRelayId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    /**
     * Stops the relay of an endpoint that has moved on to another relay, as a move to another subnet does once the
     * new relay serves.
     *
     * @throws IllegalStateException when Docker does not confirm the relay removed
     */
    public void stopRelay(String relayId) {
        relays.stop(relayId);
    }

    /**
     * Takes an instance's endpoint out of its VPC: its relay goes, and with it the address and the name.
     *
     * @throws IllegalStateException when Docker does not confirm the relay removed; the instance is left as it was
     */
    public void unassign(DbInstance instance) {
        forget(instance);
        instance.setVpcEndpointId(null);
        instance.setVpcEndpointSubnetId(null);
        instance.setVpcEndpointAddress(null);
    }

    static String subnetFor(DbInstance instance) {
        Map<String, String> subnets = instance.getSubnetAvailabilityZones();
        if (subnets == null || subnets.isEmpty()) {
            return null;
        }
        return subnets.entrySet().stream()
                .filter(subnet -> Objects.equals(subnet.getValue(), instance.getAvailabilityZone()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(subnets.keySet().iterator().next());
    }

    /** Whether the instance's endpoint is served in its VPC. */
    public static boolean inVpc(DbInstance instance) {
        return instance.getVpcEndpointId() != null;
    }

    /** The endpoint an instance in its VPC reports: its RDS name, and the port it serves. */
    public static DbEndpoint endpoint(DbInstance instance, String accountId, String region) {
        return new DbEndpoint(AwsEndpoints.rdsInstanceHost(instance.getDbInstanceIdentifier(), accountId, region),
                servedPort(instance));
    }

    /** The Port the instance was created with, or its engine's default. */
    public static int servedPort(DbInstance instance) {
        return instance.getRequestedPort() != null ? instance.getRequestedPort() : instance.getEngine().defaultPort();
    }

    /**
     * Serves the instance's endpoint at its address, relayed to its auth proxy on {@code proxyPort}, records the
     * address on the instance, the one it held before when the subnet still has it, and makes its name known.
     *
     * @throws IllegalStateException when the subnet has no address to give, or Docker does not confirm an earlier
     *                               relay of the endpoint removed
     */
    public void serve(DbInstance instance, String accountId, String region, int proxyPort) {
        removeStaleRelays(instance);
        String address = relays.serve(relay(instance, accountId, region, proxyPort), instance.getVpcEndpointAddress());
        instance.setVpcEndpointAddress(address);
        remember(instance, accountId, region);
    }

    /**
     * As Floci starts, takes up every relay an earlier Floci left: removed, or held with its address when Docker does
     * not confirm that ({@link VpcAddressRelays#recoverAll}).
     */
    public void recoverRelays() {
        relays.recoverAll();
    }

    /** Whether a relay that may exist forwards to the proxy port {@code port}, which no other database may get. */
    public boolean targetsPort(int port) {
        return relays.targetsPort(port);
    }

    // The relay reaches the proxy at Floci's address on the network it joins besides its VPC's, as RDS's database
    // containers on that network are reached.
    private VpcAddressRelays.Relay relay(DbInstance instance, String accountId, String region, int proxyPort) {
        return new VpcAddressRelays.Relay(instance.getVpcEndpointId(), accountId, region, instance.getVpcId(),
                instance.getVpcEndpointSubnetId(), servedPort(instance),
                dockerHostResolver.resolveOn(relays.network().orElse(null)), proxyPort,
                "DB instance " + instance.getDbInstanceIdentifier());
    }

    /**
     * Makes the instance's endpoint name known under its identifier now, for one not served yet, such as a stopped
     * instance after a restart, or one whose identifier a blue/green switchover changed.
     */
    public void remember(DbInstance instance, String accountId, String region) {
        String id = instance.getVpcEndpointId();
        relayIdsByHost.values().removeIf(id::equals);
        relayIdsByHost.put(endpoint(instance, accountId, region).address().toLowerCase(Locale.ROOT), id);
    }

    /**
     * Stops serving the instance's endpoint; the record keeps its address for the next {@link #serve}, and its name
     * stays known.
     *
     * @throws IllegalStateException when Docker does not confirm the relay removed: it still serves, and a later stop
     *                               tries again
     */
    public void stop(DbInstance instance) {
        relays.stop(instance.getVpcEndpointId());
    }

    /**
     * Stops serving the instance's endpoint for good, as the instance goes: its relay, the relays it moved off, and
     * then its name.
     *
     * @throws IllegalStateException when Docker does not confirm one of the relays removed; the name stays known, and
     *                               the relays not removed stay with the instance
     */
    public void forget(DbInstance instance) {
        stop(instance);
        removeStaleRelays(instance);
        if (!instance.getStaleVpcEndpointIds().isEmpty()) {
            throw new IllegalStateException("Docker did not confirm the relays " + instance.getStaleVpcEndpointIds()
                    + " of DB instance " + instance.getDbInstanceIdentifier() + " removed");
        }
        String id = instance.getVpcEndpointId();
        relayIdsByHost.values().removeIf(id::equals);
    }

    /**
     * Removes the relays the instance's endpoint moved off, as a serve, a delete and a restart do. Those Docker does
     * not confirm removed stay with the instance for the next try.
     */
    public void removeStaleRelays(DbInstance instance) {
        instance.getStaleVpcEndpointIds().removeIf(relayId -> {
            try {
                relays.stop(relayId);
                return true;
            } catch (RuntimeException e) {
                LOG.warnv(e, "DB instance {0} keeps relay {1} until it can be removed",
                        instance.getDbInstanceIdentifier(), relayId);
                return false;
            }
        });
    }

    /** Whether a relay is tracked under {@code relayId}, one that serves or one whose start Docker left unclear. */
    public boolean tracks(String relayId) {
        return relays.address(relayId).isPresent();
    }

    /** Whether a relay serves the instance's endpoint now. */
    public boolean serving(DbInstance instance) {
        return relays.address(instance.getVpcEndpointId()).isPresent();
    }

    /** Whether {@code host} is the name of an endpoint in its VPC Floci knows, served or stopped. */
    public boolean names(String host) {
        return relayIdsByHost.containsKey(host.toLowerCase(Locale.ROOT));
    }

    /** The address the endpoint named {@code host} is served at now; empty while it is stopped, or unknown. */
    public Optional<String> addressOf(String host) {
        return relays.address(relayIdsByHost.get(host.toLowerCase(Locale.ROOT)));
    }

    @Override
    public boolean hasSubnetDependency(String region, String subnetId) {
        return relays.holdsSubnet(regionResolver.getAccountId(), region, subnetId);
    }

    @Override
    public boolean hasVpcDependency(String region, String vpcId) {
        return relays.holdsVpc(regionResolver.getAccountId(), region, vpcId);
    }

    @Override
    public void stopManagedContainers() {
        relays.stopAll();
    }

    /** A reset wipes the instances whose names the index holds. */
    @Override
    public void clear() {
        relayIdsByHost.clear();
    }

    /** Writes down again the relays a reset could not remove, whose placements the storage wipe took. */
    @Override
    public void afterReset() {
        relays.rewritePlacements();
    }
}
