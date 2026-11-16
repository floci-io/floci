package io.floci.aws.services.ec2.portforward;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.ListenBacklog;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.services.ec2.SecurityGroupPolicy;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.IpPermission;
import io.floci.aws.services.ec2.model.PublishedPortFamily;
import io.floci.aws.services.ec2.model.SecurityGroup;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Publishes EC2 instance application ports on the host, driven by the TCP ingress
 * rules of the instance's security groups.
 *
 * <p>For each opened TCP port a small {@code alpine/socat} sidecar container is started
 * as a host sibling (created through the mounted Docker socket, exactly like the instance
 * container and its SSH binding). The sidecar publishes an allocated host port and forwards
 * it to the instance container's IP, so {@code curl localhost:<hostPort>} reaches the app.
 *
 * <p>Both socat addresses run with {@code nodelay} (TCP_NODELAY). With Nagle's algorithm left
 * on, socat holds a client's second small write until the first is acknowledged, and a peer
 * with delayed ACK stretches that to roughly 40 ms on the Docker network, so a request the
 * client sends as two writes pays a visible stall on every round trip.
 *
 * <p>The listen address also sets {@code backlog} to {@link ListenBacklog#ACCEPT}, since
 * socat's default accept queue of 5 silently drops the final ACK of a burst's overflow
 * connections, which the client then pays for in retransmit stalls or a reset.
 *
 * <p>Because each forward is a fresh container, ports opened <em>after</em> launch via
 * {@code authorize-security-group-ingress} are handled the same way as ports present at
 * launch. The instance container itself is never modified.
 *
 * <p>With security-group enforcement on, the sidecar's connection reaches the instance through
 * its protected namespace's firewall like any traffic from outside the VPC, so only ports an
 * ingress rule opens to every address are published (see {@link #extractWorldOpenPorts}).
 */
@ApplicationScoped
public class Ec2PortForwardManager {

    private static final Logger LOG = Logger.getLogger(Ec2PortForwardManager.class);

    /** SSH is already published on its own host port by the container manager; never re-forward it. */
    static final int SSH_PORT = 22;

    /** The network mode prefix of a container that joins another container's network namespace. */
    private static final String CONTAINER_NETWORK_MODE = "container:";

    /** Host addresses a forward binds when it admits one family only (Docker's IPv4-only and IPv6-only forms). */
    private static final String IPV4_HOST_ADDRESSES = "0.0.0.0";
    private static final String IPV6_HOST_ADDRESSES = "::";

    private final DockerClient dockerClient;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ec2-port-forward");
        t.setDaemon(true);
        return t;
    });

    // Persists an instance after its publishedPorts change, so the mapping survives a
    // hard restart (not just the periodic/shutdown flush). Set by the owning service.
    private volatile Consumer<Instance> persister;

    @Inject
    public Ec2PortForwardManager(DockerClient dockerClient,
                                 ContainerBuilder containerBuilder,
                                 ContainerLifecycleManager lifecycleManager,
                                 PortAllocator portAllocator,
                                 EmulatorConfig config) {
        this.dockerClient = dockerClient;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.portAllocator = portAllocator;
        this.config = config;
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    /** The application ports to publish, each with the client family it admits. */
    public record DesiredPorts(Map<Integer, PublishedPortFamily> ports) {
        public static final DesiredPorts NONE = new DesiredPorts(Map.of());

        /** Ports that admit every client, as with security-group enforcement off. */
        public static DesiredPorts anyFamily(Set<Integer> ports) {
            Map<Integer, PublishedPortFamily> families = new LinkedHashMap<>();
            ports.forEach(port -> families.put(port, PublishedPortFamily.ANY));
            return new DesiredPorts(families);
        }
    }

    /**
     * Reconciles the instance's live forwards against the desired port set: publishes ports
     * that are newly opened and unpublishes ports that no longer have an ingress rule. Runs
     * asynchronously so it does not block the calling API thread.
     */
    public void reconcile(Instance instance, DesiredPorts desiredPorts) {
        if (!enabled() || instance == null || instance.getDockerContainerId() == null) {
            return;
        }
        executor.submit(() -> reconcileNow(instance, desiredPorts));
    }

    void reconcileNow(Instance instance, DesiredPorts desiredPorts) {
        synchronized (instance) {
            // A reconcile queued before a stop/terminate must not republish against the
            // removed container: stop/terminate flip the state before their async
            // unpublishAll, so a non-running state here means the forwards are (being)
            // torn down and new sidecars would leak.
            String state = instance.getState() != null ? instance.getState().getName() : null;
            if (!"running".equals(state)) {
                return;
            }
            for (Map.Entry<Integer, PublishedPortFamily> desired : desiredPorts.ports().entrySet()) {
                int port = desired.getKey();
                // The rules opening the port changed family: its sidecar binds or dials the wrong addresses.
                if (instance.getPublishedPorts().containsKey(port) && desired.getValue() != family(instance, port)) {
                    unpublish(instance, port);
                }
                if (!instance.getPublishedPorts().containsKey(port)) {
                    publish(instance, port, desired.getValue());
                }
            }
            for (Integer port : new ArrayList<>(instance.getPublishedPorts().keySet())) {
                if (!desiredPorts.ports().containsKey(port)) {
                    unpublish(instance, port);
                }
            }
        }
    }

    /**
     * Removes every forward for the instance and releases its host ports. Called on
     * terminate and stop. Runs on the caller's thread (both callers are already async).
     */
    public void unpublishAll(Instance instance) {
        if (instance == null) {
            return;
        }
        synchronized (instance) {
            for (Integer port : new ArrayList<>(instance.getPublishedPorts().keySet())) {
                unpublish(instance, port);
            }
        }
    }

    boolean enabled() {
        return config.services().ec2().publishSecurityGroupPorts() && !config.services().ec2().mock();
    }

    /** Sets the callback used to persist an instance after its forwards change. */
    public void setPersister(Consumer<Instance> persister) {
        this.persister = persister;
    }

    /**
     * Re-establishes an instance's persisted forwards after a restart: re-reserves each host
     * port so the allocator will not reuse it, and recreates any socat sidecar that did not
     * survive. Sidecars are independent containers, so a surviving one keeps working untouched.
     */
    public void restore(Instance instance) {
        if (!enabled() || instance == null || instance.getDockerContainerId() == null
                || instance.getPublishedPorts().isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, Integer> entry : new LinkedHashMap<>(instance.getPublishedPorts()).entrySet()) {
            int appPort = entry.getKey();
            int hostPort = entry.getValue();
            portAllocator.markReserved(hostPort);
            // A surviving sidecar is adopted by name, so check the pre-migration name too:
            // otherwise a forward created before the rename is recreated while the old container
            // still holds its host port, and the new one cannot bind.
            String name = forwardContainerName(config, instance.getInstanceId(), appPort);
            if (lifecycleManager.findByName(name).isEmpty()
                    && lifecycleManager.findByName(legacyForwardContainerName(
                            config, instance.getInstanceId(), appPort)).isEmpty()) {
                LOG.infov("Recreating missing port-forward sidecar for EC2 instance {0} app port {1}",
                        instance.getInstanceId(), appPort);
                if (!publishOn(instance, appPort, hostPort, family(instance, appPort))) {
                    portAllocator.release(hostPort);
                    // Drop the stale mapping so a later reconcile can re-publish the port
                    // instead of seeing it as already published.
                    instance.getPublishedPorts().remove(appPort);
                    instance.getPublishedPortFamilies().remove(appPort);
                    persist(instance);
                }
            } else {
                LOG.infov("Restored port-forward for EC2 instance {0}: app port {1} -> host port {2}",
                        instance.getInstanceId(), appPort, hostPort);
            }
        }
    }

    void publish(Instance instance, int appPort, PublishedPortFamily family) {
        int hostPort = portAllocator.allocate(
                config.services().ec2().appPortRangeStart(),
                config.services().ec2().appPortRangeEnd());
        if (!publishOn(instance, appPort, hostPort, family)) {
            portAllocator.release(hostPort);
        }
    }

    /** The family a published port admits; a forward persisted before families were kept admits every client. */
    private static PublishedPortFamily family(Instance instance, int appPort) {
        return instance.getPublishedPortFamilies().getOrDefault(appPort, PublishedPortFamily.ANY);
    }

    private boolean publishOn(Instance instance, int appPort, int hostPort, PublishedPortFamily family) {
        String instanceId = instance.getInstanceId();
        boolean ipv6 = family == PublishedPortFamily.IPV6;
        try {
            NetworkTarget target = resolveInstanceTarget(instance, ipv6);
            if (target == null) {
                if (ipv6) {
                    LOG.warnv("EC2 instance {0} has no Docker IPv6 address; not publishing port {1} (::/0 only)",
                            instanceId, appPort);
                } else {
                    LOG.warnv("Could not resolve container IP for EC2 instance {0}; not publishing port {1}",
                            instanceId, appPort);
                }
                return false;
            }
            String address = ipv6 ? "[" + target.ip() + "]" : target.ip();

            String name = forwardContainerName(config, instanceId, appPort);
            ContainerStorageHelper.removeStaleContainer(config, lifecycleManager, name);

            // An IPv6-only host binding can reach the sidecar on its own IPv6 address, so its listener takes both.
            ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(config.services().ec2().socatImage())
                    .withName(name)
                    .withEntrypoint(List.of("socat"))
                    .withCmd(List.of(
                            (ipv6 ? "TCP6-LISTEN:" + appPort + ",ipv6only=0" : "TCP-LISTEN:" + appPort)
                                    + ",fork,reuseaddr,nodelay,backlog=" + ListenBacklog.ACCEPT,
                            (ipv6 ? "TCP6:" : "TCP:") + address + ":" + appPort + ",nodelay"))
                    .withPortBinding(appPort, hostPort, switch (family) {
                        case IPV4 -> IPV4_HOST_ADDRESSES;
                        case IPV6 -> IPV6_HOST_ADDRESSES;
                        case ANY -> null;
                    })
                    .withLogRotation();
            if (target.network() != null) {
                specBuilder.withNetworkMode(target.network());
            }
            ContainerSpec spec = specBuilder.build();

            String containerId = lifecycleManager.create(spec);
            lifecycleManager.startCreated(containerId, spec);

            instance.getPublishedPorts().put(appPort, hostPort);
            if (family == PublishedPortFamily.ANY) {
                instance.getPublishedPortFamilies().remove(appPort);
            } else {
                instance.getPublishedPortFamilies().put(appPort, family);
            }
            persist(instance);
            LOG.infov("Published EC2 instance {0} app port {1} on host port {2} (socat -> {3}:{1})",
                    instanceId, appPort, hostPort, address);
            return true;
        } catch (Exception e) {
            LOG.warnv("Failed to publish EC2 instance {0} app port {1}: {2}", instanceId, appPort, e.getMessage());
            return false;
        }
    }

    void unpublish(Instance instance, int appPort) {
        String name = forwardContainerName(config, instance.getInstanceId(), appPort);
        ContainerStorageHelper.removeStaleContainer(config, lifecycleManager, name);
        Integer hostPort = instance.getPublishedPorts().remove(appPort);
        instance.getPublishedPortFamilies().remove(appPort);
        if (hostPort != null) {
            portAllocator.release(hostPort);
            persist(instance);
            LOG.infov("Unpublished EC2 instance {0} app port {1} (released host port {2})",
                    instance.getInstanceId(), appPort, hostPort);
        }
    }

    private void persist(Instance instance) {
        Consumer<Instance> p = persister;
        if (p != null) {
            try {
                p.accept(instance);
            } catch (Exception e) {
                LOG.debugv("Could not persist EC2 instance {0} after forward change: {1}",
                        instance.getInstanceId(), e.getMessage());
            }
        }
    }

    /** The unprefixed name token for a forward sidecar. */
    static String forwardContainerToken(String instanceId, int appPort) {
        return "ec2-fwd-" + instanceId + "-" + appPort;
    }

    static String forwardContainerName(EmulatorConfig config, String instanceId, int appPort) {
        return ContainerStorageHelper.dockerName(config, forwardContainerToken(instanceId, appPort));
    }

    /** The name a pre-migration version gave the sidecar; only for finding or clearing survivors. */
    static String legacyForwardContainerName(EmulatorConfig config, String instanceId, int appPort) {
        return ContainerStorageHelper.legacyDockerName(config, forwardContainerToken(instanceId, appPort));
    }

    private NetworkTarget resolveInstanceTarget(Instance instance, boolean ipv6) {
        String containerId = instance.getDockerContainerId();
        if (containerId != null) {
            try {
                InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
                // With enforcement on, the instance joins its protected namespace's network, so the
                // namespace's helper holds the addresses and the network to dial them on.
                String mode = inspect.getHostConfig() == null ? null : inspect.getHostConfig().getNetworkMode();
                if (mode != null && mode.startsWith(CONTAINER_NETWORK_MODE)) {
                    inspect = dockerClient.inspectContainerCmd(mode.substring(CONTAINER_NETWORK_MODE.length())).exec();
                }
                if (inspect.getNetworkSettings() != null) {
                    Map<String, ContainerNetwork> networks = inspect.getNetworkSettings().getNetworks();
                    NetworkTarget target = pickTarget(networks,
                            ipv6 ? ContainerNetwork::getGlobalIPv6Address : ContainerNetwork::getIpAddress);
                    if (target != null || ipv6) {
                        return target;
                    }
                    String ip = inspect.getNetworkSettings().getIpAddress();
                    if (ip != null && !ip.isBlank()) {
                        return new NetworkTarget(null, ip);
                    }
                }
            } catch (Exception e) {
                LOG.warnv("Could not inspect EC2 instance {0} container for forward target: {1}",
                        instance.getInstanceId(), e.getMessage());
            }
        }
        if (ipv6) {
            return null;
        }
        String stored = instance.getContainerBridgeIp();
        if (stored != null && !stored.isBlank()) {
            return new NetworkTarget(null, stored);
        }
        return null;
    }

    /**
     * Prefers a user-defined network (so the sidecar can join it and reach the instance by IP);
     * falls back to the default bridge with a null network name (the sidecar stays on the bridge
     * the instance is already on).
     */
    static NetworkTarget pickTarget(Map<String, ContainerNetwork> networks,
                                    Function<ContainerNetwork, String> address) {
        if (networks == null || networks.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, ContainerNetwork> entry : networks.entrySet()) {
            String ip = entry.getValue() != null ? address.apply(entry.getValue()) : null;
            if (!"bridge".equals(entry.getKey()) && ip != null && !ip.isBlank()) {
                return new NetworkTarget(entry.getKey(), ip);
            }
        }
        ContainerNetwork bridge = networks.get("bridge");
        String bridgeIp = bridge != null ? address.apply(bridge) : null;
        if (bridgeIp != null && !bridgeIp.isBlank()) {
            return new NetworkTarget(null, bridgeIp);
        }
        for (ContainerNetwork network : networks.values()) {
            String ip = network != null ? address.apply(network) : null;
            if (ip != null && !ip.isBlank()) {
                return new NetworkTarget(null, ip);
            }
        }
        return null;
    }

    /**
     * Extracts the set of TCP ports to publish for an instance from its security groups while
     * security-group enforcement is off.
     *
     * <p>Only CIDR-sourced TCP rules are published: AWS makes a port opened to a referenced
     * security group reachable from that group's private IPs, not from the host, so rules
     * sourced only by a security-group reference are skipped. Ports are aggregated (deduped)
     * across all of the instance's groups, SSH (22) is never forwarded, and any single rule
     * whose port span exceeds {@code maxPerInstance} is skipped so an allow-all range cannot
     * spawn thousands of sidecars. The total is capped at {@code maxPerInstance}.
     */
    public static Set<Integer> extractPublishablePorts(List<SecurityGroup> securityGroups, int maxPerInstance) {
        return extractPorts(securityGroups, maxPerInstance, Ec2PortForwardManager::hasCidrSource);
    }

    /**
     * The ports to publish while security-group enforcement is on. Host-published access is traffic
     * from outside the VPC whose client address Docker may replace (Docker Desktop shows every host
     * client as its gateway), so a port is published only when an ingress rule opens it to every
     * address of a family, whatever that client is. A client's family is still seen on the host, so
     * a port only {@code 0.0.0.0/0} opens binds the host's IPv4 addresses, one only {@code ::/0}
     * opens binds its IPv6 addresses and is reached on the instance's Docker IPv6 address, which is
     * what that rule admits, and one both open binds every address. A port only a narrower CIDR, a
     * prefix list or a group reference opens stays reachable on the direct container path, where the
     * firewall sees the peer's own ENI. Spans, SSH and the per-instance cap are handled as in
     * {@link #extractPublishablePorts}.
     */
    public static DesiredPorts extractWorldOpenPorts(List<SecurityGroup> securityGroups, int maxPerInstance) {
        Set<Integer> ipv4 = extractPorts(securityGroups, maxPerInstance,
                perm -> opensToEveryAddress(perm.getIpRanges() == null ? Stream.empty()
                        : perm.getIpRanges().stream().map(range -> range.getCidrIp())));
        Set<Integer> ipv6 = extractPorts(securityGroups, maxPerInstance,
                perm -> opensToEveryAddress(perm.getIpv6Ranges() == null ? Stream.empty()
                        : perm.getIpv6Ranges().stream().map(range -> range.getCidrIpv6())));
        Map<Integer, PublishedPortFamily> ports = new LinkedHashMap<>();
        for (Integer port : ipv4) {
            ports.put(port, ipv6.contains(port) ? PublishedPortFamily.ANY : PublishedPortFamily.IPV4);
        }
        for (Integer port : ipv6) {
            if (ports.containsKey(port)) {
                continue;
            }
            if (ports.size() >= maxPerInstance) {
                LOG.warnv("Capping published ports at {0} per instance", maxPerInstance);
                break;
            }
            ports.put(port, PublishedPortFamily.IPV6);
        }
        return new DesiredPorts(ports);
    }

    /** Whether any of the CIDRs is a valid one with a prefix length of 0, which every address of its family matches. */
    static boolean opensToEveryAddress(Stream<String> cidrs) {
        return cidrs.filter(cidr -> cidr != null).map(String::trim)
                .anyMatch(cidr -> cidr.endsWith("/0") && SecurityGroupPolicy.validCidr(cidr));
    }

    private static Set<Integer> extractPorts(List<SecurityGroup> securityGroups, int maxPerInstance,
                                             Predicate<IpPermission> source) {
        Set<Integer> ports = new LinkedHashSet<>();
        for (SecurityGroup sg : securityGroups) {
            if (sg == null || sg.getIpPermissions() == null) {
                continue;
            }
            for (IpPermission perm : sg.getIpPermissions()) {
                if (!isTcp(perm.getIpProtocol()) || !source.test(perm)) {
                    continue;
                }
                Integer from = perm.getFromPort();
                Integer to = perm.getToPort();
                if (from == null || to == null || from < 0 || to < 0) {
                    continue;
                }
                int lo = Math.min(from, to);
                int hi = Math.max(from, to);
                long span = (long) hi - lo + 1;
                if (span > maxPerInstance) {
                    LOG.warnv("Skipping wide security-group ingress range {0}-{1} on {2} (span {3} exceeds max {4})",
                            lo, hi, sg.getGroupId(), span, maxPerInstance);
                    continue;
                }
                for (int p = lo; p <= hi; p++) {
                    if (p != SSH_PORT) {
                        ports.add(p);
                    }
                }
            }
        }
        if (ports.size() > maxPerInstance) {
            LOG.warnv("Capping published ports from {0} to {1} per instance", ports.size(), maxPerInstance);
            Set<Integer> capped = new LinkedHashSet<>();
            for (Integer port : ports) {
                if (capped.size() >= maxPerInstance) {
                    break;
                }
                capped.add(port);
            }
            return capped;
        }
        return ports;
    }

    static boolean isTcp(String ipProtocol) {
        return "tcp".equalsIgnoreCase(ipProtocol) || "6".equals(ipProtocol);
    }

    static boolean hasCidrSource(IpPermission perm) {
        return (perm.getIpRanges() != null && !perm.getIpRanges().isEmpty())
                || (perm.getIpv6Ranges() != null && !perm.getIpv6Ranges().isEmpty());
    }

    /** A resolved forward destination: the instance IP and the network to reach it on (null = default bridge). */
    record NetworkTarget(String network, String ip) {
    }
}
