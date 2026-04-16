package io.github.hectorvent.floci.services.ecr.registry;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Volume;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Manages the lifecycle of the shared {@code registry:2} container that backs
 * Floci's emulated ECR. There is one container per Floci instance, started
 * lazily on first use and reused across restarts.
 *
 * <p>Methods that compute URIs ({@link #getRepositoryUri}, {@link #getProxyEndpoint})
 * do not require Docker — they read the configured port and account/region from
 * {@link EmulatorConfig}. Only {@link #ensureStarted()} talks to the daemon.
 */
@ApplicationScoped
public class EcrRegistryManager {

    private static final Logger LOG = Logger.getLogger(EcrRegistryManager.class);
    private static final int CONTAINER_INTERNAL_PORT = 5000;

    private static final String NAMED_VOLUME = "floci-ecr-registry-data";

    private final DockerClient dockerClient;
    private final EmulatorConfig config;
    private final ImageCacheService imageCacheService;
    private final ContainerDetector containerDetector;

    private volatile boolean started;
    private volatile boolean reconciled;
    private volatile int hostPort;
    private volatile String containerId;
    private volatile java.util.function.Consumer<List<String>> reconcileHook;

    @Inject
    public EcrRegistryManager(DockerClient dockerClient,
                              EmulatorConfig config,
                              ImageCacheService imageCacheService,
                              ContainerDetector containerDetector) {
        this.dockerClient = dockerClient;
        this.config = config;
        this.imageCacheService = imageCacheService;
        this.containerDetector = containerDetector;
        this.hostPort = config.services().ecr().registryBasePort();
    }

    /** Returns the docker-pullable repository URI for the given account/region/name. */
    public String getRepositoryUri(String accountId, String region, String repoName) {
        int port = effectivePort();
        String style = config.services().ecr().uriStyle();
        if ("path".equalsIgnoreCase(style)) {
            return "localhost:" + port + "/" + accountId + "/" + region + "/" + repoName;
        }
        return accountId + ".dkr.ecr." + region + ".localhost:" + port + "/" + repoName;
    }

    /** Returns the proxy endpoint a docker daemon should log into for any ECR repo. */
    public String getProxyEndpoint() {
        String scheme = config.services().ecr().tlsEnabled() ? "https" : "http";
        return scheme + "://localhost:" + effectivePort();
    }

    /** Returns the effective registry port. Stable across calls once {@link #ensureStarted} runs. */
    public int effectivePort() {
        return hostPort;
    }

    /** Internal namespace prefix used to isolate cross-account/region repos within the shared registry. */
    public String internalRepoName(String accountId, String region, String repoName) {
        return accountId + "/" + region + "/" + repoName;
    }

    /** Returns a {@link RegistryHttpClient} bound to the current registry endpoint. */
    public RegistryHttpClient httpClient() {
        return new RegistryHttpClient("http://localhost:" + effectivePort());
    }

    /**
     * Registers a callback invoked once on first {@link #ensureStarted()} with the
     * list of repository names known to the backing registry. EcrService uses this
     * to recreate metadata entries for blobs whose metadata is missing (FR-013).
     */
    public void setReconcileHook(java.util.function.Consumer<List<String>> hook) {
        this.reconcileHook = hook;
    }

    /**
     * Lazily starts (or reuses) the {@code registry:2} container. Idempotent and
     * thread-safe. Throws if Docker is unreachable.
     */
    public synchronized void ensureStarted() {
        if (started) {
            return;
        }
        String name = config.services().ecr().registryContainerName();

        Container existing = findExistingContainer(name);
        if (existing != null) {
            adoptExisting(existing);
            runReconcileOnce();
            return;
        }

        int chosenPort = allocatePort();
        String image = config.services().ecr().registryImage();

        // Pull the registry image on demand. CI runners and fresh dev machines
        // will not have it cached locally; without this we'd see
        // "Status 404: No such image: registry:2" from createContainerCmd.
        imageCacheService.ensureImageExists(image);

        ExposedPort exposed = ExposedPort.tcp(CONTAINER_INTERNAL_PORT);
        Ports portBindings = new Ports();
        portBindings.bind(exposed, Ports.Binding.bindPort(chosenPort));

        String hostPersistentPath = config.storage().hostPersistentPath();
        boolean inContainer = containerDetector.isRunningInContainer();
        boolean isExplicitVolumeName = !hostPersistentPath.startsWith("/")
                && !hostPersistentPath.startsWith(".");
        boolean isRelativeDefault = hostPersistentPath.startsWith(".");

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withPortBindings(portBindings);

        List<String> env = new java.util.ArrayList<>(List.of(
                "REGISTRY_STORAGE_DELETE_ENABLED=true",
                "REGISTRY_HTTP_ADDR=0.0.0.0:" + CONTAINER_INTERNAL_PORT
        ));

        if (inContainer && isRelativeDefault) {
            // Zero-config fallback for in-container Floci. A relative host path can't
            // be bind-mounted into a sibling container (Docker Desktop rejects it).
            // Use a dedicated named volume so `docker compose up` works without any
            // FLOCI_STORAGE_HOST_PERSISTENT_PATH configuration.
            hostConfig.withMounts(List.of(new Mount()
                    .withType(MountType.VOLUME)
                    .withSource(NAMED_VOLUME)
                    .withTarget("/var/lib/registry")));
            LOG.infov("Floci in container with relative host-persistent-path ({0}); "
                    + "using named volume {1} for ECR registry data",
                    hostPersistentPath, NAMED_VOLUME);
        } else if (isExplicitVolumeName) {
            // User set hostPersistentPath to a Docker named-volume name — share the
            // volume with the rest of Floci's persistent data and tell the registry
            // to write inside it.
            String internalMountPath = "/app/data";
            hostConfig.withBinds(new Bind(hostPersistentPath, new Volume(internalMountPath)));
            env.add("REGISTRY_STORAGE_FILESYSTEM_ROOTDIRECTORY=" + internalMountPath + "/ecr/registry");
        } else {
            // Host path bind-mount. On host dev, hostPersistentPath matches
            // persistentPath so the replace is a no-op and we bind the absolute
            // container data path. In a container with an absolute host path set,
            // replace rewrites the container-internal path to the host-side path.
            // normalize() eliminates any "./" segments so the replace() matches reliably.
            String dataPath = Paths.get(config.services().ecr().dataPath(), "registry")
                    .toAbsolutePath().normalize().toString();
            String persistentPath = Paths.get(config.storage().persistentPath())
                    .toAbsolutePath().normalize().toString();
            String hostDataPath = dataPath.replace(persistentPath, hostPersistentPath);
            if (!inContainer) {
                ensureDataDir();
            }
            hostConfig.withBinds(new Bind(hostDataPath, new Volume("/var/lib/registry")));
        }

        try {
            CreateContainerResponse created = dockerClient.createContainerCmd(image)
                    .withName(name)
                    .withEnv(env)
                    .withExposedPorts(exposed)
                    .withHostConfig(hostConfig)
                    .exec();
            this.containerId = created.getId();
            dockerClient.startContainerCmd(containerId).exec();
            this.hostPort = chosenPort;
            this.started = true;
            LOG.infov("Started ECR backing registry {0} on host port {1}", name, chosenPort);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start ECR backing registry container: " + e.getMessage(), e);
        }

        // Connect to the configured Docker network after starting so that host port
        // bindings are established first (withNetworkMode during create suppresses
        // port publishing on some Docker runtimes, e.g. Docker Desktop on macOS).
        config.services().ecr().dockerNetwork()
                .or(() -> config.services().dockerNetwork())
                .filter(n -> !n.isBlank())
                .ifPresent(network -> {
                    try {
                        dockerClient.connectToNetworkCmd()
                                .withContainerId(containerId)
                                .withNetworkId(network)
                                .exec();
                        LOG.infov("Connected ECR registry {0} to network {1}", name, network);
                    } catch (Exception e) {
                        LOG.warnv("Could not connect ECR registry to network {0}: {1}", network, e.getMessage());
                    }
                });
        runReconcileOnce();
    }

    private void runReconcileOnce() {
        if (reconciled || reconcileHook == null) {
            return;
        }
        try {
            // Give the registry a moment to be ready on first start
            for (int i = 0; i < 10; i++) {
                if (httpClient().ping()) break;
                try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
            List<String> repos = httpClient().catalog();
            reconcileHook.accept(repos);
            reconciled = true;
        } catch (Exception e) {
            LOG.warnv("ECR reconcile-on-startup failed: {0}", e.getMessage());
        }
    }

    /** Value object returned by {@link #runGarbageCollect}. */
    public record GcResult(String output, long durationMs) {}

    /**
     * Runs {@code registry garbage-collect} inside the running registry container
     * to reclaim disk space after image deletions. Synchronized to prevent concurrent
     * ECR operations during the GC window.
     *
     * @param timeoutSeconds max time to wait for the exec to complete
     * @return captured stdout+stderr output from the GC run
     * @throws IllegalStateException if the registry is not started
     * @throws RuntimeException if the exec fails, exits non-zero, or times out
     */
    public synchronized GcResult runGarbageCollect(int timeoutSeconds) {
        if (!started || containerId == null) {
            throw new IllegalStateException("ECR registry is not started");
        }
        long startMs = System.currentTimeMillis();
        StringBuilder output = new StringBuilder();

        ExecCreateCmdResponse exec = dockerClient
                .execCreateCmd(containerId)
                .withCmd("registry", "garbage-collect", "/etc/docker/registry/config.yml")
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

        try {
            boolean completed = dockerClient.execStartCmd(exec.getId())
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                        }
                    })
                    .awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);

            if (!completed) {
                throw new RuntimeException("garbage-collect timed out after " + timeoutSeconds + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("garbage-collect interrupted", e);
        }

        InspectExecResponse inspect = dockerClient.inspectExecCmd(exec.getId()).exec();
        long durationMs = System.currentTimeMillis() - startMs;
        Long exitCode = inspect.getExitCodeLong();

        if (exitCode == null) {
            throw new RuntimeException("garbage-collect did not exit (still running after await)");
        }
        if (exitCode != 0) {
            LOG.warnv("ECR GC exited with code {0}: {1}", exitCode, output);
            throw new RuntimeException("garbage-collect exited with code " + exitCode + ": " + output);
        }

        LOG.infov("ECR GC completed in {0}ms", durationMs);
        return new GcResult(output.toString(), durationMs);
    }

    /** Stops the container if {@code keepRunningOnShutdown=false}. Called from EmulatorLifecycle hooks. */
    public void shutdown() {
        if (!started || containerId == null) {
            return;
        }
        if (config.services().ecr().keepRunningOnShutdown()) {
            LOG.infov("Leaving ECR backing registry container {0} running for next start-up", containerId);
            return;
        }
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(5).exec();
        } catch (Exception e) {
            LOG.warnv("Error stopping ECR registry container: {0}", e.getMessage());
        }
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (Exception e) {
            LOG.warnv("Error removing ECR registry container: {0}", e.getMessage());
        }
    }

    private Container findExistingContainer(String name) {
        try {
            List<Container> all = dockerClient.listContainersCmd().withShowAll(true).exec();
            for (Container c : all) {
                String[] names = c.getNames();
                if (names == null) continue;
                for (String n : names) {
                    if (n.equals("/" + name) || n.equals(name)) {
                        return c;
                    }
                }
            }
        } catch (Exception e) {
            LOG.debugv("Could not list containers while searching for {0}: {1}", name, e.getMessage());
        }
        return null;
    }

    private void adoptExisting(Container existing) {
        this.containerId = existing.getId();
        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            boolean running = Boolean.TRUE.equals(inspect.getState().getRunning());
            if (!running) {
                dockerClient.startContainerCmd(containerId).exec();
            }
            var bindings = inspect.getNetworkSettings().getPorts().getBindings();
            var binding = bindings.get(ExposedPort.tcp(CONTAINER_INTERNAL_PORT));
            if (binding != null && binding.length > 0) {
                this.hostPort = Integer.parseInt(binding[0].getHostPortSpec());
            }
            this.started = true;
            LOG.infov("Adopted existing ECR registry container {0} on host port {1}",
                    containerId, hostPort);
        } catch (NotFoundException nf) {
            this.containerId = null;
        } catch (Exception e) {
            LOG.warnv("Failed to adopt existing ECR registry container: {0}", e.getMessage());
        }
    }

    private int allocatePort() {
        int base = config.services().ecr().registryBasePort();
        int max = config.services().ecr().registryMaxPort();
        for (int p = base; p <= max; p++) {
            if (isPortFree(p)) {
                return p;
            }
        }
        throw new RuntimeException("No free port available in range "
                + base + "-" + max + " for ECR registry");
    }

    private boolean isPortFree(int port) {
        try (ServerSocket s = new ServerSocket(port)) {
            s.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void ensureDataDir() {
        try {
            Path dir = Paths.get(config.services().ecr().dataPath(), "registry");
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOG.warnv("Could not create ECR data directory: {0}", e.getMessage());
        }
    }

    // Test seam
    boolean isStarted() {
        return started;
    }
}
