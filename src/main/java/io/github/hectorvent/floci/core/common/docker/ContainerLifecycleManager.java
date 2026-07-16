package io.github.hectorvent.floci.core.common.docker;

import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.ListVolumesResponse;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages Docker container lifecycle operations including create, start, stop, and remove.
 * Consolidates common container management patterns used across Floci services.
 */
@ApplicationScoped
public class ContainerLifecycleManager {

    private static final Logger LOG = Logger.getLogger(ContainerLifecycleManager.class);

    private final DockerClient dockerClient;
    private final ImageCacheService imageCacheService;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;

    /** Volumes whose shared-ownership root has already been initialised this process (run-once guard). */
    private final ConcurrentHashMap<String, Boolean> initializedSharedVolumes = new ConcurrentHashMap<>();

    @Inject
    public ContainerLifecycleManager(DockerClient dockerClient,
                                     ImageCacheService imageCacheService,
                                     ContainerDetector containerDetector,
                                     PortAllocator portAllocator) {
        this.dockerClient = dockerClient;
        this.imageCacheService = imageCacheService;
        this.containerDetector = containerDetector;
        this.portAllocator = portAllocator;
    }

    /**
     * Creates and immediately starts a container. Delegates to
     * {@link #create} and {@link #startCreated}. Suitable when no
     * filesystem modifications are needed between creation and start.
     *
     * @param spec the container specification
     * @return information about the created container including resolved endpoints
     */
    public ContainerInfo createAndStart(ContainerSpec spec) {
        String containerId = create(spec);
        try {
            return startCreated(containerId, spec);
        } catch (Exception e) {
            // A failed start (e.g. host-port conflict) must not leak the created
            // container: retrying callers would accumulate Created containers and
            // fixed-name callers would hit name conflicts on the next attempt.
            removeIfExists(containerId);
            throw e;
        }
    }

    /**
     * Creates a container without starting it. Use {@link #startCreated} to
     * start it after any pre-start setup (e.g. copying files into the
     * container filesystem).
     *
     * @param spec the container specification
     * @return the container ID
     */
    public String create(ContainerSpec spec) {
        LOG.debugv("Creating container from spec: image={0}, name={1}", spec.image(), spec.name());

        imageCacheService.ensureImageExists(spec.image());

        HostConfig hostConfig = buildHostConfig(spec);

        CreateContainerCmd createCmd = dockerClient.createContainerCmd(spec.image())
                .withHostConfig(hostConfig);

        if (spec.name() != null) {
            createCmd.withName(spec.name());
        }
        if (spec.user() != null && !spec.user().isBlank()) {
            createCmd.withUser(spec.user());
        }
        if (spec.env() != null && !spec.env().isEmpty()) {
            createCmd.withEnv(spec.env());
        }
        if (spec.cmd() != null && !spec.cmd().isEmpty()) {
            createCmd.withCmd(spec.cmd());
        }
        if (spec.entrypoint() != null && !spec.entrypoint().isEmpty()) {
            createCmd.withEntrypoint(spec.entrypoint());
        }
        if (spec.workingDir() != null && !spec.workingDir().isBlank()) {
            createCmd.withWorkingDir(spec.workingDir());
        }
        if (spec.exposedPorts() != null && !spec.exposedPorts().isEmpty()) {
            ExposedPort[] exposed = spec.exposedPorts().stream()
                    .map(ExposedPort::tcp)
                    .toArray(ExposedPort[]::new);
            createCmd.withExposedPorts(exposed);
        }

        CreateContainerResponse response = createCmd.exec();
        String containerId = response.getId();
        LOG.infov("Created container {0} (name={1}, not yet started)", containerId, spec.name());
        return containerId;
    }

    /**
     * Starts a previously created container and resolves its endpoints.
     *
     * @param containerId the container ID returned by {@link #create}
     * @param spec the original spec (needed for network and endpoint resolution)
     * @return information about the running container including resolved endpoints
     */
    public ContainerInfo startCreated(String containerId, ContainerSpec spec) {
        dockerClient.startContainerCmd(containerId).exec();
        LOG.infov("Started container {0}", containerId);

        if (spec.networkMode() != null && !spec.networkMode().isBlank() && spec.hasPortBindings()) {
            try {
                dockerClient.connectToNetworkCmd()
                        .withContainerId(containerId)
                        .withNetworkId(spec.networkMode())
                        .exec();
                LOG.debugv("Connected container {0} to network {1}", containerId, spec.networkMode());
            } catch (Exception e) {
                LOG.warnv("Could not connect container {0} to network {1}: {2}",
                        containerId, spec.networkMode(), e.getMessage());
            }
        }

        Map<Integer, EndpointInfo> endpoints = resolveEndpoints(containerId, spec);
        return new ContainerInfo(containerId, endpoints);
    }

    /**
     * Stops and removes a container, closing any associated log stream.
     *
     * @param containerId the container ID to stop and remove
     * @param logStream optional log stream to close (may be null)
     */
    public void stopAndRemove(String containerId, Closeable logStream) {
        LOG.infov("Stopping container {0}", containerId);

        // Close log stream first
        if (logStream != null) {
            try {
                logStream.close();
            } catch (Exception e) {
                LOG.debugv("Error closing log stream: {0}", e.getMessage());
            }
        }

        // Stop container
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(5).exec();
        } catch (NotFoundException e) {
            LOG.debugv("Container {0} not found (already removed)", containerId);
            return;
        } catch (Exception e) {
            LOG.warnv("Error stopping container {0}: {1}", containerId, e.getMessage());
        }

        // Remove container
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            LOG.debugv("Removed container {0}", containerId);
        } catch (NotFoundException e) {
            // Already gone
        } catch (Exception e) {
            LOG.warnv("Error removing container {0}: {1}", containerId, e.getMessage());
        }
    }

    /**
     * Creates a named volume if it does not already exist. Idempotent — safe to call on every
     * container start. Labels the volume {@code floci=true} so
     * {@code docker volume prune --filter label=floci} works.
     */
    public void ensureVolume(String volumeName) {
        if (!volumeExists(volumeName)) {
            dockerClient.createVolumeCmd()
                    .withName(volumeName)
                    .withLabels(Map.of("floci", "true"))
                    .exec();
            LOG.debugv("Created volume {0}", volumeName);
        }
    }

    /**
     * Ensures the named volume exists (see {@link #ensureVolume}) and, when POSIX ownership is
     * requested, initialises the volume root once to emulate an Amazon EFS access point's
     * {@code RootDirectory.CreationInfo}. A Docker named volume is created {@code root:root 0755},
     * so a container whose image runs as a non-root {@code USER} (as ECS tasks and
     * access-point-mounted workloads typically do) cannot create files on it. This chowns/chmods
     * the mount root inside a short-lived helper container. A 4-digit octal
     * {@code rootPermissions} (e.g. {@code "2775"}) carries the setgid bit, so subdirectories
     * inherit the owner gid — matching {@code CreationInfo.Permissions} exactly.
     *
     * <p>The initialisation runs at most once per volume name per process. When no ownership is
     * configured (all of {@code ownerUid}/{@code ownerGid}/{@code rootPermissions} empty) this
     * degrades to a plain {@link #ensureVolume}, so the default behaviour is unchanged.
     *
     * @param volumeName      the named volume
     * @param ownerUid        owner uid for the volume root (EFS {@code CreationInfo.OwnerUid})
     * @param ownerGid        owner gid for the volume root (EFS {@code CreationInfo.OwnerGid})
     * @param rootPermissions octal permissions for the volume root (e.g. {@code "0777"}); empty skips init
     * @param initImage       lightweight image used for the one-off chown/chmod helper
     */
    public void ensureSharedVolume(String volumeName, OptionalInt ownerUid, OptionalInt ownerGid,
                                   Optional<String> rootPermissions, String initImage) {
        ensureVolume(volumeName);
        if (rootPermissions.isEmpty() && ownerUid.isEmpty() && ownerGid.isEmpty()) {
            return;
        }
        // An EFS access point's CreationInfo requires OwnerUid and OwnerGid together; reject a
        // partial ownership config rather than emitting a malformed `chown uid:` (whose trailing
        // colon makes chown resolve the login group and fail in busybox for an unknown uid).
        if (ownerUid.isPresent() != ownerGid.isPresent()) {
            throw new IllegalArgumentException(
                    "floci.storage.efs owner-uid and owner-gid must be set together");
        }
        // Validate before splicing into the helper's `sh -c`, matching CreationInfo.Permissions
        // (^[0-7]{3,4}$), so a typo can't produce a mangled script that soft-fails.
        rootPermissions.ifPresent(p -> {
            if (!p.matches("^[0-7]{3,4}$")) {
                throw new IllegalArgumentException(
                        "floci.storage.efs root-permissions must be 3-4 octal digits (e.g. \"0777\","
                                + " or \"2775\" for setgid): " + p);
            }
        });
        // computeIfAbsent runs the one-off init under a per-volume lock, so a concurrent launch for
        // the same volume waits for it to finish rather than mounting a still root:root 0755 root.
        // Returning null on failure leaves the volume unmemoised, so the next launch retries.
        initializedSharedVolumes.computeIfAbsent(volumeName, k -> {
            try {
                initSharedVolumeRoot(volumeName, ownerUid, ownerGid, rootPermissions, initImage);
                return Boolean.TRUE;
            } catch (RuntimeException e) {
                LOG.warnv("Failed to initialise shared volume {0} ownership: {1}", volumeName, e.getMessage());
                return null;
            }
        });
    }

    private void initSharedVolumeRoot(String volumeName, OptionalInt ownerUid, OptionalInt ownerGid,
                                      Optional<String> rootPermissions, String initImage) {
        String mount = "/floci-shared-volume";
        StringBuilder script = new StringBuilder();
        // ownerUid and ownerGid are validated to be present together by the caller, so the chown
        // always has both operands (no trailing colon). setgid is expressed via a 4-digit octal
        // rootPermissions (e.g. "2775"), matching CreationInfo.Permissions.
        if (ownerUid.isPresent() && ownerGid.isPresent()) {
            script.append("chown ").append(ownerUid.getAsInt()).append(':').append(ownerGid.getAsInt())
                    .append(' ').append(mount).append(" && ");
        }
        rootPermissions.ifPresent(p -> script.append("chmod ").append(p).append(' ').append(mount).append(" && "));
        script.append("true");

        String image = (initImage != null && !initImage.isBlank()) ? initImage : "busybox:stable";
        imageCacheService.ensureImageExists(image);

        HostConfig hostConfig = HostConfig.newHostConfig().withMounts(List.of(
                new Mount().withType(MountType.VOLUME).withSource(volumeName).withTarget(mount)));
        CreateContainerResponse created = dockerClient.createContainerCmd(image)
                .withHostConfig(hostConfig)
                .withCmd("sh", "-c", script.toString())
                .exec();
        String helperId = created.getId();
        try {
            dockerClient.startContainerCmd(helperId).exec();
            Integer status = dockerClient.waitContainerCmd(helperId)
                    .exec(new WaitContainerResultCallback())
                    .awaitStatusCode(60, TimeUnit.SECONDS);
            if (status == null || status != 0) {
                // Throw so the caller leaves the volume unmemoised and retries on the next launch,
                // rather than leaving it root:root 0755 with no further attempt.
                throw new IllegalStateException("shared-volume init for " + volumeName
                        + " exited with status " + status + " (cmd: " + script + ")");
            }
            LOG.infov("Initialised shared volume {0} root (cmd: {1})", volumeName, script);
        } finally {
            try {
                dockerClient.removeContainerCmd(helperId).withForce(true).exec();
            } catch (Exception ignore) {
                // best-effort cleanup of the one-off helper
            }
        }
    }

    /**
     * Removes a named Docker volume, ignoring errors if it does not exist or is still in use.
     */
    public void removeVolume(String volumeName) {
        try {
            dockerClient.removeVolumeCmd(volumeName).exec();
            LOG.debugv("Removed volume {0}", volumeName);
        } catch (NotFoundException e) {
            // Already gone — nothing to do
        } catch (Exception e) {
            LOG.warnv("Error removing volume {0}: {1}", volumeName, e.getMessage());
        }
    }

    /**
     * Finds an existing container by name.
     *
     * @param name the container name to search for
     * @return the container if found
     */
    public Optional<Container> findByName(String name) {
        try {
            List<Container> containers = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .exec();

            for (Container c : containers) {
                String[] names = c.getNames();
                if (names == null) {
                    continue;
                }
                for (String n : names) {
                    // Docker prefixes names with /
                    if (n.equals("/" + name) || n.equals(name)) {
                        return Optional.of(c);
                    }
                }
            }
        } catch (Exception e) {
            LOG.debugv("Error searching for container {0}: {1}", name, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Adopts an existing container, starting it if stopped.
     * Useful for services like ECR that reuse containers across restarts.
     *
     * @param containerId the container ID to adopt
     * @param ports the container ports to resolve endpoints for
     * @return information about the adopted container
     */
    public ContainerInfo adopt(String containerId, List<Integer> ports) {
        LOG.infov("Adopting existing container {0}", containerId);

        InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
        boolean running = Boolean.TRUE.equals(inspect.getState().getRunning());

        if (!running) {
            dockerClient.startContainerCmd(containerId).exec();
            LOG.infov("Started adopted container {0}", containerId);
            inspect = dockerClient.inspectContainerCmd(containerId).exec();
        }

        Map<Integer, EndpointInfo> endpoints = new HashMap<>();
        Map<Integer, Integer> publishedHostPorts = new HashMap<>();
        for (int port : ports) {
            endpoints.put(port, resolveEndpoint(inspect, port));
            OptionalInt published = readPublishedHostPort(inspect, port);
            if (published.isPresent()) {
                publishedHostPorts.put(port, published.getAsInt());
            }
        }

        return new ContainerInfo(containerId, endpoints, publishedHostPorts);
    }

    /**
     * Reads the host port a container's internal port is published on, independent of
     * whether Floci itself runs inside a container. Unlike {@link #resolveEndpoint} —
     * which switches to container-IP + internal port in container mode — this always
     * reads the port binding, for URIs consumed by the host-side Docker daemon.
     */
    private static OptionalInt readPublishedHostPort(InspectContainerResponse inspect, int containerPort) {
        Ports ports = inspect.getNetworkSettings().getPorts();
        if (ports != null) {
            Ports.Binding[] binding = ports.getBindings().get(ExposedPort.tcp(containerPort));
            if (binding != null && binding.length > 0) {
                try {
                    return OptionalInt.of(Integer.parseInt(binding[0].getHostPortSpec()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Removes a container by name if it exists. Useful for cleaning up stale containers
     * from previous runs before creating a new one.
     *
     * @param name the container name to remove
     */
    public void removeIfExists(String name) {
        try {
            dockerClient.removeContainerCmd(name).withForce(true).exec();
            LOG.infov("Removed stale container {0}", name);
        } catch (NotFoundException e) {
            // Not found - normal case
        } catch (Exception e) {
            LOG.debugv("Could not remove container {0}: {1}", name, e.getMessage());
        }
    }

    /**
     * Returns whether the container is currently running. A missing container is treated as
     * not-running; any other Docker error (e.g. an inspect timeout under daemon overload) is also
     * treated as not-running, so a hung/dead container is not reused from the warm pool — a false
     * negative merely triggers a clean cold-start, which is far cheaper than blocking until the
     * function timeout.
     *
     * @param containerId the container ID to inspect
     * @return true only if the container exists and is reported as running; false on any error
     */
    public boolean isContainerRunning(String containerId) {
        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            return Boolean.TRUE.equals(inspect.getState().getRunning());
        } catch (NotFoundException e) {
            return false;
        } catch (Exception e) {
            // Treat an inspect failure/timeout as NOT running. Under Docker-daemon overload,
            // returning true here caused the warm pool to "reuse" dead/hung containers, so the
            // invocation blocked until the function timeout (~20-30s) every time. A false
            // negative merely triggers a clean cold-start, which is far cheaper than a hang.
            LOG.warnv("Liveness check failed for container {0}; treating as not running: {1}",
                    containerId, e.getMessage());
            return false;
        }
    }

    /**
     * Resolves the endpoint (host and port) to connect to a specific container port.
     *
     * @param containerId the container ID
     * @param containerPort the container port to resolve
     * @return the endpoint information
     */
    public EndpointInfo resolveEndpoint(String containerId, int containerPort) {
        InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
        return resolveEndpoint(inspect, containerPort);
    }

    /**
     * Returns the underlying DockerClient for operations not covered by this manager.
     * Prefer using manager methods when available.
     */
    public DockerClient getDockerClient() {
        return dockerClient;
    }

    /**
     * Returns {@code true} if the container runtime (Docker, Moby, or Podman) has a volume
     * with the given name. The volume does not need to be attached to the current container.
     * <p>
     * This method uses the Docker Engine API ({@code /volumes/{name}}) which is supported
     * by Docker, Moby, and Podman runtimes on all operating systems.
     *
     * @param name the volume name to look up
     * @return {@code true} if the volume exists, {@code false} otherwise
     */
    public boolean volumeExists(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        // Is a Unix absolute or relative path (e.g. "/var/lib/data", "./data", "../data")
        if (name.startsWith("/") || name.startsWith(".")) {
            return false;
        }
        // Is a Windows absolute path (e.g. "C:\Users\data", "D:/sources/data")
        if (name.length() >= 3 && Character.isLetter(name.charAt(0))
                && name.charAt(1) == ':' && (name.charAt(2) == '\\' || name.charAt(2) == '/')) {
            return false;
        }
        try {
            dockerClient.inspectVolumeCmd(name).exec();
            LOG.debugv("Volume ''{0}'' exists in the container runtime", name);
            return true;
        } catch (NotFoundException e) {
            LOG.debugv("Volume ''{0}'' not found in the container runtime", name);
            return false;
        } catch (DockerException e) {
            LOG.warnv("Failed to inspect volume ''{0}'': {1}", name, e.getMessage());
            return false;
        }
    }

    /**
     * Attempts to take an authoritative snapshot of all named volumes in the container runtime.
     * An empty optional means the runtime could not be queried; it is intentionally distinct from
     * a successful query that returned an empty set so cleanup callers fail closed.
     */
    public Optional<Set<String>> tryListVolumeNames() {
        try {
            ListVolumesResponse response = dockerClient.listVolumesCmd().exec();
            if (response == null || response.getVolumes() == null) {
                return Optional.of(Set.of());
            }
            Set<String> names = response.getVolumes().stream()
                    .map(InspectVolumeResponse::getName)
                    .filter(name -> name != null && !name.isBlank())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return Optional.of(names);
        } catch (RuntimeException e) {
            LOG.warnv("Failed to list container-runtime volumes: {0}", e.getMessage());
            return Optional.empty();
        }
    }

    private HostConfig buildHostConfig(ContainerSpec spec) {
        HostConfig hostConfig = HostConfig.newHostConfig();

        // Privileged mode (required for e.g. k3s containers)
        if (spec.privileged()) {
            hostConfig.withPrivileged(true);
        }

        if (spec.cgroupnsMode() != null && !spec.cgroupnsMode().isBlank()) {
            hostConfig.withCgroupnsMode(spec.cgroupnsMode());
        }

        // Supplementary groups (Docker --group-add), e.g. to give a process access to a
        // group-shared volume without changing its primary uid/gid.
        if (spec.groupAdd() != null && !spec.groupAdd().isEmpty()) {
            hostConfig.withGroupAdd(spec.groupAdd());
        }

        // Memory limit
        if (spec.hasMemoryLimit()) {
            hostConfig.withMemory(spec.memoryBytes());
        }

        // Port bindings — services decide whether to request them based on their
        // own in-container-vs-native logic. When Floci runs inside Docker, most
        // backends are reached via the docker network IP, so services omit the
        // binding. ECR's sibling registry is the exception: it always publishes
        // its port because host-side docker clients (and CDK in compat tests)
        // connect via localhost:<hostPort>.
        if (spec.hasPortBindings()) {
            Ports ports = new Ports();
            for (Map.Entry<Integer, Integer> entry : spec.portBindings().entrySet()) {
                int containerPort = entry.getKey();
                int hostPort = entry.getValue();

                // 0 means dynamic allocation
                if (hostPort == 0) {
                    hostPort = portAllocator.allocateAny();
                }

                ports.bind(ExposedPort.tcp(containerPort), Ports.Binding.bindPort(hostPort));
                LOG.debugv("Port binding: {0} -> {1}", String.valueOf(containerPort), String.valueOf(hostPort));
            }
            hostConfig.withPortBindings(ports);
        }

        // Network mode: only set during creation when there are no host port bindings.
        // withNetworkMode() + port bindings suppresses port publishing on macOS Docker Desktop,
        // so containers with port bindings (e.g. ECR registry) connect to the network
        // after start via connectToNetworkCmd() instead.
        if (spec.networkMode() != null && !spec.networkMode().isBlank() && !spec.hasPortBindings()) {
            hostConfig.withNetworkMode(spec.networkMode());
        }

        // Mounts (named volumes, bind mounts)
        if (spec.mounts() != null && !spec.mounts().isEmpty()) {
            hostConfig.withMounts(spec.mounts());
        }

        // Legacy binds
        if (spec.binds() != null && !spec.binds().isEmpty()) {
            hostConfig.withBinds(spec.binds().toArray(new Bind[0]));
        }

        // Extra hosts (e.g., host.docker.internal on Linux)
        if (spec.extraHosts() != null && !spec.extraHosts().isEmpty()) {
            hostConfig.withExtraHosts(spec.extraHosts().toArray(new String[0]));
        }

        // Log configuration (log rotation)
        if (spec.hasLogConfig()) {
            hostConfig.withLogConfig(spec.logConfig());
        }

        // DNS servers — used to inject Floci's embedded DNS so spawned containers
        // can resolve *.localhost.floci.io to Floci's Docker network IP.
        if (spec.dnsServers() != null && !spec.dnsServers().isEmpty()) {
            hostConfig.withDns(spec.dnsServers().toArray(new String[0]));
        }

        return hostConfig;
    }

    private Map<Integer, EndpointInfo> resolveEndpoints(String containerId, ContainerSpec spec) {
        if (spec.exposedPorts() == null || spec.exposedPorts().isEmpty()) {
            return Map.of();
        }

        InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
        Map<Integer, EndpointInfo> endpoints = new HashMap<>();

        for (int containerPort : spec.exposedPorts()) {
            endpoints.put(containerPort, resolveEndpoint(inspect, containerPort, spec.networkMode()));
        }

        return endpoints;
    }

    private EndpointInfo resolveEndpoint(InspectContainerResponse inspect, int containerPort) {
        return resolveEndpoint(inspect, containerPort, null);
    }

    private EndpointInfo resolveEndpoint(InspectContainerResponse inspect, int containerPort, String preferredNetwork) {
        if (!containerDetector.isRunningInContainer()) {
            // Native mode: use localhost and the bound host port
            var bindings = inspect.getNetworkSettings().getPorts().getBindings();
            var binding = bindings.get(ExposedPort.tcp(containerPort));

            if (binding != null && binding.length > 0) {
                int hostPort = Integer.parseInt(binding[0].getHostPortSpec());
                return new EndpointInfo("localhost", hostPort);
            }
            // Fallback to container port
            return new EndpointInfo("localhost", containerPort);
        } else {
            // Container mode: use container IP on the docker network.
            // Prefer the configured network's IP — the container may be on multiple
            // networks (bridge + the configured network) when connectToNetworkCmd()
            // is used instead of withNetworkMode() during creation.
            String containerIp = resolveContainerIp(inspect, preferredNetwork);
            return new EndpointInfo(containerIp, containerPort);
        }
    }

    private String resolveContainerIp(InspectContainerResponse inspect, String preferredNetwork) {
        var networks = inspect.getNetworkSettings().getNetworks();
        if (networks != null) {
            // Prefer the configured network so that when the container is on both
            // bridge (default) and the service network, we return the right IP.
            if (preferredNetwork != null && networks.containsKey(preferredNetwork)) {
                String ip = networks.get(preferredNetwork).getIpAddress();
                if (ip != null && !ip.isBlank()) {
                    return ip;
                }
            }
            // Fall back to any network
            for (Map.Entry<String, ContainerNetwork> entry : networks.entrySet()) {
                String ip = entry.getValue().getIpAddress();
                if (ip != null && !ip.isBlank()) {
                    return ip;
                }
            }
        }
        // Fallback to the global IP
        return inspect.getNetworkSettings().getIpAddress();
    }

    /**
     * Information about a created or adopted container.
     *
     * @param containerId the Docker container ID
     * @param endpoints map of container port to resolved endpoint (host:port for connection)
     * @param publishedHostPorts map of container port to the host port it is published on;
     *                           a port without a binding is absent
     */
    public record ContainerInfo(
            String containerId,
            Map<Integer, EndpointInfo> endpoints,
            Map<Integer, Integer> publishedHostPorts
    ) {
        public ContainerInfo(String containerId, Map<Integer, EndpointInfo> endpoints) {
            this(containerId, endpoints, Map.of());
        }

        /**
         * Gets the endpoint for a specific container port.
         */
        public EndpointInfo getEndpoint(int containerPort) {
            return endpoints.get(containerPort);
        }

        /**
         * Gets the host port a container port is published on, regardless of whether
         * Floci itself runs inside a container. Empty when the port has no binding.
         */
        public OptionalInt publishedHostPort(int containerPort) {
            Integer published = publishedHostPorts.get(containerPort);
            return published != null ? OptionalInt.of(published) : OptionalInt.empty();
        }
    }

    /**
     * Network endpoint information for connecting to a container.
     *
     * @param host the host to connect to (localhost in native mode, container IP in Docker mode)
     * @param port the port to connect to
     */
    public record EndpointInfo(String host, int port) {
        @Override
        public String toString() {
            return host + ":" + port;
        }
    }
}
