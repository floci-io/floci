package io.github.hectorvent.floci.services.ecr.registry;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.CurrentContainerNetworkResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final CurrentContainerNetworkResolver currentContainerNetworkResolver;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;

    private volatile boolean started;
    private volatile boolean reconciled;
    private volatile int hostPort;
    private volatile String containerId;
    private volatile Closeable logStream;
    private volatile java.util.function.Consumer<List<String>> reconcileHook;

    @Inject
    public EcrRegistryManager(ContainerBuilder containerBuilder,
                              ContainerLifecycleManager lifecycleManager,
                              ContainerLogStreamer logStreamer,
                              ContainerDetector containerDetector,
                              CurrentContainerNetworkResolver currentContainerNetworkResolver,
                              PortAllocator portAllocator,
                              EmulatorConfig config,
                              RegionResolver regionResolver) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.containerDetector = containerDetector;
        this.currentContainerNetworkResolver = currentContainerNetworkResolver;
        this.portAllocator = portAllocator;
        this.config = config;
        this.regionResolver = regionResolver;
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
        if (containerDetector.isRunningInContainer()) {
            return new RegistryHttpClient("http://" + config.services().ecr().registryContainerName()
                    + ":" + CONTAINER_INTERNAL_PORT);
        }
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

        // Check for existing container to adopt
        var existing = lifecycleManager.findByName(name);
        if (existing.isPresent()) {
            adoptExisting(existing.get());
            runReconcileOnce();
            return;
        }

        // Allocate port
        int chosenPort = portAllocator.allocate(
                config.services().ecr().registryBasePort(),
                config.services().ecr().registryMaxPort());

        try {
            String image = config.services().ecr().registryImage();

            // Build environment variables
            List<String> env = new ArrayList<>(List.of(
                    "REGISTRY_STORAGE_DELETE_ENABLED=true",
                    "REGISTRY_HTTP_ADDR=0.0.0.0:" + CONTAINER_INTERNAL_PORT
            ));

            // Build container spec
            ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                    .withName(name)
                    .withEnv(env)
                    .withPortBinding(CONTAINER_INTERNAL_PORT, chosenPort)
                    .withDockerNetwork(resolveRegistryDockerNetwork())
                    .withLogRotation();

            // Handle persistence mounting based on storage configuration
            addPersistenceMounts(specBuilder, env);

            ContainerSpec spec = specBuilder.build();

            ContainerInfo info = lifecycleManager.createAndStart(spec);
            this.containerId = info.containerId();
            this.hostPort = chosenPort;
            this.started = true;
            LOG.infov("Started ECR backing registry {0} on host port {1}", name, String.valueOf(chosenPort));

            // Attach log streaming (new feature)
            attachLogStream();
        } catch (Exception e) {
            // Release the reserved port unless the container actually started, so a
            // failed start (e.g. Docker unreachable) does not permanently exhaust the
            // registry port pool across retries.
            if (!started) {
                portAllocator.release(chosenPort);
            }
            throw new RuntimeException("Failed to start ECR backing registry container: " + e.getMessage(), e);
        }
        runReconcileOnce();
    }

    private void addPersistenceMounts(ContainerBuilder.Builder specBuilder, List<String> env) {
        if (ContainerStorageHelper.isNamedVolumeMode(config)) {
            lifecycleManager.ensureVolume(NAMED_VOLUME);
            specBuilder.withNamedVolume(NAMED_VOLUME, "/var/lib/registry");
            return;
        }

        // Legacy host-path mode: host-persistent-path is an absolute path
        boolean inContainer = containerDetector.isRunningInContainer();
        String dataPath = Paths.get(config.services().ecr().dataPath(), "registry")
                .toAbsolutePath().normalize().toString();
        String persistentPath = Paths.get(config.storage().persistentPath())
                .toAbsolutePath().normalize().toString();
        String hostDataPath = dataPath.replace(persistentPath, config.storage().hostPersistentPath());
        if (!inContainer) {
            ensureDataDir();
        }
        specBuilder.withBind(hostDataPath, "/var/lib/registry");
    }

    private void attachLogStream() {
        String shortId = containerId.length() >= 8 ? containerId.substring(0, 8) : containerId;
        String logGroup = "/aws/ecr/registry";
        String logStreamName = logStreamer.generateLogStreamName(shortId);
        String region = regionResolver.getDefaultRegion();

        this.logStream = logStreamer.attach(
                containerId, logGroup, logStreamName, region, "ecr:registry");
    }

    private java.util.Optional<String> resolveRegistryDockerNetwork() {
        java.util.Optional<String> configured = config.services().ecr().dockerNetwork();
        if (configured.isPresent() && !configured.get().isBlank()) {
            return configured;
        }
        if (containerDetector.isRunningInContainer()) {
            return currentContainerNetworkResolver.resolveNetworkName();
        }
        return java.util.Optional.empty();
    }

    private void runReconcileOnce() {
        if (reconciled || reconcileHook == null) {
            return;
        }
        try {
            // Give the registry a moment to be ready on first start
            for (int i = 0; i < 10; i++) {
                if (httpClient().ping()) {
                    break;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
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

        DockerClient dockerClient = lifecycleManager.getDockerClient();

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
        lifecycleManager.stopAndRemove(containerId, logStream);
    }

    private void adoptExisting(Container existing) {
        this.containerId = existing.getId();
        try {
            ContainerInfo info = lifecycleManager.adopt(containerId, List.of(CONTAINER_INTERNAL_PORT));
            var endpoint = info.getEndpoint(CONTAINER_INTERNAL_PORT);
            if (endpoint != null) {
                this.hostPort = endpoint.port();
            }
            this.started = true;
            LOG.infov("Adopted existing ECR registry container {0} on host port {1}",
                    containerId, String.valueOf(hostPort));

            // Attach log streaming to adopted container
            attachLogStream();
        } catch (Exception e) {
            LOG.warnv("Failed to adopt existing ECR registry container: {0}", e.getMessage());
            this.containerId = null;
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
