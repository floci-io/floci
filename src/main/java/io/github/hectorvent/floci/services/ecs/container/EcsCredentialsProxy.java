package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.LocallyBuiltHelperImage;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the small proxy container, one per Docker network, that holds the ECS container-credentials
 * address (169.254.170.2) and forwards to {@link EcsTaskRoleCredentialsServer}. A task container
 * needs its own address in 169.254.0.0/16 to reach this proxy at all (see the follow-up wiring
 * that gives it one): without a connected route for that range, the kernel routes toward the
 * default gateway instead of resolving the peer directly, and the connection is refused before it
 * reaches this proxy's socket.
 *
 * <p>Docker accepts a link-local address request even on a runtime that then does not honour it
 * (see the review discussion on #4063), so a bind is never trusted from the API call alone: after
 * start, the address is confirmed inside the container's own network namespace, and the container
 * is torn down and the network refused rather than silently issuing credentials task containers
 * cannot reach.
 */
@ApplicationScoped
public class EcsCredentialsProxy implements ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(EcsCredentialsProxy.class);
    private static final String CREDENTIALS_ADDRESS = "169.254.170.2";
    private static final String OWNS_NETWORK_LABEL = "floci.ecs-task-role-credentials-proxy";
    private static final int VERIFY_ATTEMPTS = 30;
    private static final long VERIFY_INTERVAL_MILLIS = 200;

    private final DockerClient dockerClient;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final DockerHostResolver dockerHostResolver;
    private final EmulatorConfig config;
    private final Map<String, String> proxyIdByNetwork = new ConcurrentHashMap<>();

    @Inject
    public EcsCredentialsProxy(DockerClient dockerClient, ContainerBuilder containerBuilder,
                               ContainerLifecycleManager lifecycleManager, DockerHostResolver dockerHostResolver,
                               EmulatorConfig config) {
        this.dockerClient = dockerClient;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.dockerHostResolver = dockerHostResolver;
        this.config = config;
    }

    /**
     * No proxy container survives a Floci restart owning a meaningful state (its only job is to
     * forward to this process), and Docker does not reject a second container claiming the same
     * link-local address on one network: it silently lets both hold it, which would make
     * credential routing depend on which one wins ARP. Remove any surviving one this exact process
     * owned before minting new ones.
     *
     * <p>Scoped to this instance's own {@code floci_owner_port}, the same convention {@code
     * SecurityGroupFirewallManager} uses: several Floci processes can share one Docker daemon, and
     * a label match alone would let one instance's restart tear down another's still-running,
     * still-in-use proxy.
     */
    @PostConstruct
    void reapSurvivingProxies() {
        try {
            dockerClient.listContainersCmd().withShowAll(true)
                    .withLabelFilter(Map.of(OWNS_NETWORK_LABEL, "true"))
                    .exec().stream()
                    .filter(container -> owner().equals(container.getLabels().get("floci_owner_port")))
                    .forEach(container -> lifecycleManager.removeIfExists(container.getId()));
        } catch (Exception e) {
            LOG.warnv("Could not reap surviving ECS credentials proxy containers: {0}", e.getMessage());
        }
    }

    /** Identifies this Floci process among others that may share the same Docker daemon. */
    String owner() {
        String namespace = config.docker().resourceNamespace().orElse("");
        return namespace.isBlank() ? String.valueOf(config.port()) : namespace + "/" + config.port();
    }

    /**
     * {@link #owner()}, valid as a Docker name component. Two Floci instances that share a daemon,
     * a resource namespace, and a network would otherwise derive the exact same container name:
     * the ownership check in {@link #removeStaleContainerNamed} then correctly refuses to steal
     * the other instance's proxy, but leaves the second instance unable to ever create its own
     * under that name. Folding this into the name itself, not just the label, means the two never
     * collide in the first place.
     */
    String ownerNameSegment() {
        return owner().replaceAll("[^a-zA-Z0-9_.-]", "-");
    }

    /**
     * Ensures the credentials proxy is running on the given network, starting and verifying one
     * if this is the first task on it. Idempotent per network for the life of the process.
     *
     * @throws IllegalStateException if the address cannot be confirmed bound; the caller must not
     *         hand out credentials whose endpoint a task cannot actually reach.
     */
    public synchronized void ensureProxyOn(String network) {
        if (network == null || network.isBlank()) {
            throw new IllegalStateException(
                    "ECS task-role credentials need a user-defined Docker network, not '" + network + "'");
        }
        String existing = proxyIdByNetwork.get(network);
        if (existing != null && isRunning(existing)) {
            return;
        }

        LocallyBuiltHelperImage.ensureBuilt(dockerClient, containerBuilder,
                config.services().ecs().taskRoleCredentials().proxyImage(), "floci/network-helper:local",
                "/docker/network-helper.Dockerfile");
        String name = ContainerStorageHelper.resourceName(
                config, "ecs-credentials-proxy", null, network + "-" + ownerNameSegment());
        // A stale proxy from an earlier launch attempt or a crashed process can still hold this
        // exact name; Docker refuses to create a new container under a name a stopped one still
        // owns, which would otherwise leave this network's proxy stuck until someone cleans it up
        // by hand. removeStaleContainerNamed is a no-op when nothing by that name exists, and does
        // not touch a same-named container another Floci instance owns.
        removeStaleContainerNamed(name);
        // Floci itself may be containerized, in which case host.docker.internal names the Docker
        // host, not Floci's own container: resolve() returns Floci's actual reachable address
        // either way, the same helper EC2's own IMDS proxy uses for this.
        String flociHost = dockerHostResolver.resolve();
        int port = config.services().ecs().taskRoleCredentials().port();
        ContainerSpec spec = containerBuilder.newContainer(config.services().ecs().taskRoleCredentials().proxyImage())
                .withName(name)
                .withDockerNetwork(Optional.of(network))
                .withLinkLocalIp(CREDENTIALS_ADDRESS)
                .withHostDockerInternalOnLinux()
                .withEntrypoint(List.of("socat"))
                .withCmd(List.of(
                        "TCP-LISTEN:80,bind=" + CREDENTIALS_ADDRESS + ",fork,reuseaddr",
                        "TCP:" + flociHost + ":" + port))
                .withLabels(Map.of(OWNS_NETWORK_LABEL, "true", "floci_owner_port", owner()))
                .build();

        String proxyId = null;
        try {
            proxyId = lifecycleManager.createAndStart(spec).containerId();
            requireEndpointReachable(proxyId);
            proxyIdByNetwork.put(network, proxyId);
            LOG.infov("ECS task-role credentials proxy {0} holding {1} on network {2}",
                    proxyId, CREDENTIALS_ADDRESS, network);
        } catch (RuntimeException e) {
            if (proxyId != null) {
                lifecycleManager.removeIfExists(proxyId);
            }
            throw new IllegalStateException(
                    "Cannot bind the ECS container-credentials address on network " + network
                            + "; refusing to issue task-role credentials tasks there could not reach", e);
        }
    }

    /**
     * Removes every proxy this process launched, on shutdown and on a state reset: nothing about
     * a proxy container is worth keeping across either, and a reset in particular wipes the same
     * task/network state a leftover proxy would otherwise have nothing left to serve.
     */
    @Override
    public void stopManagedContainers() {
        for (String proxyId : proxyIdByNetwork.values()) {
            lifecycleManager.removeIfExists(proxyId);
        }
        proxyIdByNetwork.clear();
    }

    /**
     * Removes a container by this exact name only when it is safe to: it must actually be one of
     * this label's proxy containers, and it must be either unowned (predates the {@code
     * floci_owner_port} convention) or owned by this same Floci instance. A same-named proxy
     * belonging to a different instance sharing this daemon is left alone; removing it would
     * interrupt that instance's tasks mid-flight, which a name collision here does not justify.
     */
    private void removeStaleContainerNamed(String name) {
        try {
            for (Container candidate : dockerClient.listContainersCmd().withShowAll(true)
                    .withNameFilter(List.of(name)).exec()) {
                String[] names = candidate.getNames();
                if (names == null || List.of(names).stream().noneMatch(("/" + name)::equals)) {
                    continue;
                }
                Map<String, String> labels = candidate.getLabels();
                boolean isOurProxy = labels != null && "true".equals(labels.get(OWNS_NETWORK_LABEL));
                String candidateOwner = labels == null ? null : labels.get("floci_owner_port");
                if (isOurProxy && (candidateOwner == null || owner().equals(candidateOwner))) {
                    lifecycleManager.removeIfExists(candidate.getId());
                }
            }
        } catch (Exception e) {
            LOG.warnv("Could not check for a stale container named {0}: {1}", name, e.getMessage());
        }
    }

    private boolean isRunning(String containerId) {
        try {
            return Boolean.TRUE.equals(dockerClient.inspectContainerCmd(containerId).exec()
                    .getState().getRunning());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Confirms the credentials endpoint actually answers through this proxy, end to end, rather
     * than just checking that {@link #CREDENTIALS_ADDRESS} is configured on its interface. The two
     * are not the same thing: a runtime that accepts the connect call but drops {@code
     * LinkLocalIPs} would leave the address missing entirely, but even a proxy that gets the
     * address has a brief window, confirmed while writing this, where the interface is up before
     * socat has finished binding its listener. Only an actual HTTP round trip through the address,
     * socat, and the resolved Floci host to the real server proves a task can reach it.
     */
    private void requireEndpointReachable(String containerId) {
        for (int attempt = 0; attempt < VERIFY_ATTEMPTS; attempt++) {
            String status = execCapture(containerId, "curl", "-s", "-o", "/dev/null",
                    "-w", "%{http_code}", "--max-time", "1", "http://" + CREDENTIALS_ADDRESS + "/");
            // Any HTTP status, including a 404 for a path nothing serves, means the request made
            // it all the way through; curl reports "000" when it could not connect at all.
            if (status != null && !status.isBlank() && !status.contains("000")) {
                return;
            }
            try {
                Thread.sleep(VERIFY_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException(
                "The credentials proxy never answered on " + CREDENTIALS_ADDRESS + "; the Docker runtime may be "
                        + "ignoring LinkLocalIPs on this network, or the forward to Floci itself is not reachable");
    }

    private String execCapture(String containerId, String... cmd) {
        try {
            String execId = dockerClient.execCreateCmd(containerId)
                    .withAttachStdout(true).withAttachStderr(true).withCmd(cmd).exec().getId();
            StringBuilder output = new StringBuilder();
            try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
                @Override
                public void onNext(Frame frame) {
                    output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                }
            }) {
                dockerClient.execStartCmd(execId).exec(callback).awaitCompletion();
            }
            return output.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
