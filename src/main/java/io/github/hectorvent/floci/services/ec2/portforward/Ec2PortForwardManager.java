package io.github.hectorvent.floci.services.ec2.portforward;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
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

/**
 * Publishes EC2 instance application ports on the host, driven by the TCP ingress
 * rules of the instance's security groups.
 *
 * <p>For each opened TCP port a small {@code alpine/socat} sidecar container is started
 * as a host sibling (created through the mounted Docker socket, exactly like the instance
 * container and its SSH binding). The sidecar publishes an allocated host port and forwards
 * it to the instance container's IP, so {@code curl localhost:<hostPort>} reaches the app.
 *
 * <p>Because each forward is a fresh container, ports opened <em>after</em> launch via
 * {@code authorize-security-group-ingress} are handled the same way as ports present at
 * launch. The instance container itself is never modified.
 */
@ApplicationScoped
public class Ec2PortForwardManager {

    private static final Logger LOG = Logger.getLogger(Ec2PortForwardManager.class);

    /** SSH is already published on its own host port by the container manager; never re-forward it. */
    static final int SSH_PORT = 22;

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

    /**
     * Reconciles the instance's live forwards against the desired port set: publishes ports
     * that are newly opened and unpublishes ports that no longer have an ingress rule. Runs
     * asynchronously so it does not block the calling API thread.
     */
    public void reconcile(Instance instance, Set<Integer> desiredPorts) {
        if (!enabled() || instance == null || instance.getDockerContainerId() == null) {
            return;
        }
        executor.submit(() -> reconcileNow(instance, desiredPorts));
    }

    void reconcileNow(Instance instance, Set<Integer> desiredPorts) {
        synchronized (instance) {
            // A reconcile queued before a stop/terminate must not republish against the
            // removed container: stop/terminate flip the state before their async
            // unpublishAll, so a non-running state here means the forwards are (being)
            // torn down and new sidecars would leak.
            String state = instance.getState() != null ? instance.getState().getName() : null;
            if (!"running".equals(state)) {
                return;
            }
            for (int port : desiredPorts) {
                if (!instance.getPublishedPorts().containsKey(port)) {
                    publish(instance, port);
                }
            }
            for (Integer port : new ArrayList<>(instance.getPublishedPorts().keySet())) {
                if (!desiredPorts.contains(port)) {
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
            String name = forwardContainerName(instance.getInstanceId(), appPort);
            if (lifecycleManager.findByName(name).isEmpty()) {
                LOG.infov("Recreating missing port-forward sidecar for EC2 instance {0} app port {1}",
                        instance.getInstanceId(), appPort);
                if (!publishOn(instance, appPort, hostPort)) {
                    portAllocator.release(hostPort);
                    // Drop the stale mapping so a later reconcile can re-publish the port
                    // instead of seeing it as already published.
                    instance.getPublishedPorts().remove(appPort);
                    persist(instance);
                }
            } else {
                LOG.infov("Restored port-forward for EC2 instance {0}: app port {1} -> host port {2}",
                        instance.getInstanceId(), appPort, hostPort);
            }
        }
    }

    void publish(Instance instance, int appPort) {
        int hostPort = portAllocator.allocate(
                config.services().ec2().appPortRangeStart(),
                config.services().ec2().appPortRangeEnd());
        if (!publishOn(instance, appPort, hostPort)) {
            portAllocator.release(hostPort);
        }
    }

    private boolean publishOn(Instance instance, int appPort, int hostPort) {
        String instanceId = instance.getInstanceId();
        try {
            NetworkTarget target = resolveInstanceTarget(instance);
            if (target == null) {
                LOG.warnv("Could not resolve container IP for EC2 instance {0}; not publishing port {1}",
                        instanceId, appPort);
                return false;
            }

            String name = forwardContainerName(instanceId, appPort);
            lifecycleManager.removeIfExists(name);

            ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(config.services().ec2().socatImage())
                    .withName(name)
                    .withEntrypoint(List.of("socat"))
                    .withCmd(List.of(
                            "TCP-LISTEN:" + appPort + ",fork,reuseaddr",
                            "TCP:" + target.ip() + ":" + appPort))
                    .withPortBinding(appPort, hostPort)
                    .withLogRotation();
            if (target.network() != null) {
                specBuilder.withNetworkMode(target.network());
            }
            ContainerSpec spec = specBuilder.build();

            String containerId = lifecycleManager.create(spec);
            lifecycleManager.startCreated(containerId, spec);

            instance.getPublishedPorts().put(appPort, hostPort);
            persist(instance);
            LOG.infov("Published EC2 instance {0} app port {1} on host port {2} (socat -> {3}:{1})",
                    instanceId, appPort, hostPort, target.ip());
            return true;
        } catch (Exception e) {
            LOG.warnv("Failed to publish EC2 instance {0} app port {1}: {2}", instanceId, appPort, e.getMessage());
            return false;
        }
    }

    void unpublish(Instance instance, int appPort) {
        String name = forwardContainerName(instance.getInstanceId(), appPort);
        lifecycleManager.removeIfExists(name);
        Integer hostPort = instance.getPublishedPorts().remove(appPort);
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

    static String forwardContainerName(String instanceId, int appPort) {
        return "floci-ec2-fwd-" + instanceId + "-" + appPort;
    }

    private NetworkTarget resolveInstanceTarget(Instance instance) {
        String containerId = instance.getDockerContainerId();
        if (containerId != null) {
            try {
                InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
                if (inspect.getNetworkSettings() != null) {
                    Map<String, ContainerNetwork> networks = inspect.getNetworkSettings().getNetworks();
                    NetworkTarget target = pickTarget(networks);
                    if (target != null) {
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
    static NetworkTarget pickTarget(Map<String, ContainerNetwork> networks) {
        if (networks == null || networks.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, ContainerNetwork> entry : networks.entrySet()) {
            String ip = entry.getValue() != null ? entry.getValue().getIpAddress() : null;
            if (!"bridge".equals(entry.getKey()) && ip != null && !ip.isBlank()) {
                return new NetworkTarget(entry.getKey(), ip);
            }
        }
        ContainerNetwork bridge = networks.get("bridge");
        if (bridge != null && bridge.getIpAddress() != null && !bridge.getIpAddress().isBlank()) {
            return new NetworkTarget(null, bridge.getIpAddress());
        }
        for (ContainerNetwork network : networks.values()) {
            if (network != null && network.getIpAddress() != null && !network.getIpAddress().isBlank()) {
                return new NetworkTarget(null, network.getIpAddress());
            }
        }
        return null;
    }

    /**
     * Extracts the set of TCP ports to publish for an instance from its security groups.
     *
     * <p>Only CIDR-sourced TCP rules are published: AWS makes a port opened to a referenced
     * security group reachable from that group's private IPs, not from the host, so rules
     * sourced only by a security-group reference are skipped. Ports are aggregated (deduped)
     * across all of the instance's groups, SSH (22) is never forwarded, and any single rule
     * whose port span exceeds {@code maxPerInstance} is skipped so an allow-all range cannot
     * spawn thousands of sidecars. The total is capped at {@code maxPerInstance}.
     */
    public static Set<Integer> extractPublishablePorts(List<SecurityGroup> securityGroups, int maxPerInstance) {
        Set<Integer> ports = new LinkedHashSet<>();
        for (SecurityGroup sg : securityGroups) {
            if (sg == null || sg.getIpPermissions() == null) {
                continue;
            }
            for (IpPermission perm : sg.getIpPermissions()) {
                if (!isTcp(perm.getIpProtocol()) || !hasCidrSource(perm)) {
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
