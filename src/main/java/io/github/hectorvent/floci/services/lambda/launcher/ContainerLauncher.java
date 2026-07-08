package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.lambda.LambdaLayerService;
import io.github.hectorvent.floci.services.lambda.model.ContainerState;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.model.LambdaLayerVersion;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServer;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServerFactory;
import com.github.dockerjava.api.DockerClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Starts and stops Docker containers for Lambda function execution.
 * Always starts the RuntimeApiServer before the container so the runtime
 * can connect immediately when the container boots.
 *
 * Code is injected into the container via the Docker API tar-copy endpoint
 * rather than a bind mount, so it works when Floci itself runs inside Docker.
 */
@ApplicationScoped
public class ContainerLauncher {

    private static final Logger LOG = Logger.getLogger(ContainerLauncher.class);
    private static final String TASK_DIR = "/var/task";
    private static final String RUNTIME_DIR = "/var/runtime";

    /**
     * In-container location of Floci's CA certificate, injected when TLS is enabled so the
     * container trusts Floci's self-signed HTTPS endpoint. {@code /etc} exists in every Lambda
     * base image, so no directory needs to be created.
     */
    private static final String FLOCI_CA_DIR = "/etc";
    private static final String FLOCI_CA_FILE_NAME = "floci-ca.crt";
    private static final String FLOCI_CA_CONTAINER_PATH = FLOCI_CA_DIR + "/" + FLOCI_CA_FILE_NAME;
    /** Self-signed cert filename produced by {@code TlsConfigSource} under {persistent-path}/tls/. */
    private static final String SELF_SIGNED_CERT_NAME = "floci-selfsigned.crt";

    private static final DateTimeFormatter LOG_STREAM_DATE_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ImageResolver imageResolver;
    private final RuntimeApiServerFactory runtimeApiServerFactory;
    private final DockerHostResolver dockerHostResolver;
    private final EmulatorConfig config;
    private final EcrRegistryManager ecrRegistryManager;
    private final LambdaLayerService layerService;
    private final LaunchedContainerAwsEnv awsEnv;

    /** Matches an AWS-shaped ECR image URI: {@code <account>.dkr.ecr.<region>.amazonaws.com/<repo>[:tag]}. */
    private static final java.util.regex.Pattern AWS_ECR_URI =
            java.util.regex.Pattern.compile("^([0-9]{12})\\.dkr\\.ecr\\.([a-z0-9-]+)\\.amazonaws\\.com/(.+)$");

    @Inject
    public ContainerLauncher(ContainerBuilder containerBuilder,
                             ContainerLifecycleManager lifecycleManager,
                             ContainerLogStreamer logStreamer,
                             ImageResolver imageResolver,
                             RuntimeApiServerFactory runtimeApiServerFactory,
                             DockerHostResolver dockerHostResolver,
                             EmulatorConfig config,
                             EcrRegistryManager ecrRegistryManager,
                             LambdaLayerService layerService,
                             LaunchedContainerAwsEnv awsEnv) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.imageResolver = imageResolver;
        this.runtimeApiServerFactory = runtimeApiServerFactory;
        this.dockerHostResolver = dockerHostResolver;
        this.config = config;
        this.ecrRegistryManager = ecrRegistryManager;
        this.layerService = layerService;
        this.awsEnv = awsEnv;
    }

    /**
     * Rewrites real-AWS-shaped ECR image URIs to point at Floci's loopback registry.
     * Stored ImageUri is preserved (so describe-function returns the original);
     * the rewrite is only applied immediately before the docker pull.
     */
    private String rewriteForEmulatedRegistry(String image) {
        if (image == null) {
            return null;
        }
        java.util.regex.Matcher m = AWS_ECR_URI.matcher(image);
        if (!m.matches()) {
            return image;
        }
        String account = m.group(1);
        String region = m.group(2);
        String repoAndTag = m.group(3);
        ecrRegistryManager.ensureStarted();
        String rewritten = ecrRegistryManager.getRepositoryUri(account, region, repoAndTag);
        LOG.infov("Rewriting ECR image URI {0} -> {1}", image, rewritten);
        return rewritten;
    }

    public ContainerHandle launch(LambdaFunction fn) {
        LOG.infov("Launching container for function: {0}", fn.getFunctionName());

        // For Zip functions, verify code exists before allocating any resources.
        // Hot-reload functions use a bind-mount; the Docker daemon validates the path at start.
        if (!fn.isHotReload()) {
            if (fn.getCodeLocalPath() != null) {
                Path codePath = Path.of(fn.getCodeLocalPath());
                if (!Files.exists(codePath)) {
                    throw new RuntimeException("Code directory not found for function '"
                            + fn.getFunctionName() + "': " + fn.getCodeLocalPath()
                            + " (function may have been deleted or updated)");
                }
            }
        }

        // Start Runtime API server first so container can connect on boot
        RuntimeApiServer runtimeApiServer = runtimeApiServerFactory.create();

        // Everything after the runtime-api server is allocated runs inside one try/catch: a failure
        // ANYWHERE below — image/host resolve, the code-volume populate (ensureCodeVolume), the spec
        // build, or the create/copy/start — must release that runtime-api port (and reap any
        // half-built container). Otherwise a cold-start burst that trips the Docker daemon leaks one
        // runtime-api port per failed attempt and eventually exhausts the pool, so launches keep
        // failing even after the daemon recovers.
        String containerId = null;
        try {

        // Resolve image
        String image = "Image".equals(fn.getPackageType()) && fn.getImageUri() != null
                ? fn.getImageUri()
                : imageResolver.resolve(fn.getRuntime());

        // If this is an AWS-shaped ECR URI, rewrite it to Floci's loopback registry
        image = rewriteForEmulatedRegistry(image);

        // Determine host address reachable from container
        String hostAddress = dockerHostResolver.resolve();
        String runtimeApiEndpoint = hostAddress + ":" + runtimeApiServer.getPort();

        // Give the container a human-readable name (needed for log stream name below)
        String shortId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String containerName = ContainerStorageHelper.dockerName(config, "floci-" + fn.getFunctionName() + "-" + shortId);

        // CloudWatch log coordinates — computed here so they can be injected as env vars
        String cwLogGroup  = "/aws/lambda/" + fn.getFunctionName();
        String cwLogStream = LOG_STREAM_DATE_FMT.format(LocalDate.now()) + "/[$LATEST]" + shortId;
        String lambdaRegion = extractRegionFromArn(fn.getFunctionArn(), config.defaultRegion());

        // When TLS is on, the container must trust Floci's self-signed cert so HTTPS callbacks
        // to Floci succeed (e.g. a CDK custom resource's cfn-response, which hardcodes https://).
        // Short-circuit when TLS is off so cert-path/storage config isn't read needlessly.
        Optional<Path> flociCaCert = config.tls().enabled()
                ? resolveFlociCaCertPath(true, config.tls().certPath(), config.storage().persistentPath())
                : Optional.empty();

        // Build env vars
        List<String> env = new ArrayList<>();
        env.add("AWS_LAMBDA_RUNTIME_API=" + runtimeApiEndpoint);
        env.add("AWS_LAMBDA_FUNCTION_NAME=" + fn.getFunctionName());
        env.add("AWS_LAMBDA_FUNCTION_MEMORY_SIZE=" + fn.getMemorySize());
        env.add("AWS_LAMBDA_FUNCTION_TIMEOUT=" + fn.getTimeout());
        env.add("AWS_LAMBDA_FUNCTION_VERSION=$LATEST");
        env.add("AWS_LAMBDA_LOG_GROUP_NAME=" + cwLogGroup);
        env.add("AWS_LAMBDA_LOG_STREAM_NAME=" + cwLogStream);
        if (fn.getHandler() != null && !fn.getHandler().isBlank()) {
            env.add("_HANDLER=" + fn.getHandler());
        }
        // Region, credentials and the Floci endpoint the SDK should target — the same baseline
        // Floci gives every launched container. When the host ~/.aws is mounted (awsConfigPath)
        // the SDK discovers credentials from /opt/aws-config instead of the placeholders.
        Optional<String> awsConfigPath = config.services().lambda().awsConfigPath()
                .filter(s -> !s.isBlank());
        env.addAll(awsEnv.sdkBaselineEnv(lambdaRegion,
                awsConfigPath.isPresent() ? Optional.of("/opt/aws-config") : Optional.empty()));
        env.addAll(flociCaEnv(flociCaCert));
        if (fn.getEnvironment() != null) {
            fn.getEnvironment().forEach((k, v) -> env.add(k + "=" + v));
        }

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withEnv(env)
                .withMemoryMb(fn.getMemorySize())
                .withDockerNetwork(config.services().lambda().dockerNetwork())
                .withHostDockerInternalOnLinux()
                .withLogRotation();

        specBuilder.withEmbeddedDns();

        // Whether /var/task is served from a shared read-only volume (large code) or copied
        // directly into this container (small code). Decided once here so both the spec (below)
        // and the create->start copy block agree.
        boolean useCodeVolume = false;
        if (fn.isHotReload()) {
            specBuilder.withBind(fn.getHotReloadHostPath(), TASK_DIR);
        } else if (fn.getCodeLocalPath() != null) {
            useCodeVolume = shouldUseCodeVolume(Path.of(fn.getCodeLocalPath()));
            if (useCodeVolume) {
                // Large code: mount /var/task from a read-only volume that is populated ONCE per
                // code version, instead of tar-copying its code (e.g. ~34k node_modules files) into
                // every container. That copy cost ~95s per cold start on Docker Desktop (small-file
                // overlay I/O) and, run many-at-once, saturated the daemon so even `docker create`
                // ballooned to ~80s. A pre-populated volume mounts in ~0.2s and is shared read-only
                // by all containers of the function — matching AWS, where /var/task is read-only.
                String codeVolume = ensureCodeVolume(fn, image);
                specBuilder.withNamedVolume(codeVolume, TASK_DIR, true);
            }
            // Small code takes the original per-container direct copy below (no volume): it's fast
            // enough that a shared volume's helper round-trip would only add cold-start latency.
        }

        // For Image package type use ImageConfig.Command/EntryPoint/WorkingDirectory if set, otherwise fall back to Handler (Zip-style)
        if ("Image".equals(fn.getPackageType())) {
            if (fn.getImageConfigEntryPoint() != null && !fn.getImageConfigEntryPoint().isEmpty()) {
                specBuilder.withEntrypoint(fn.getImageConfigEntryPoint());
            }
            if (fn.getImageConfigCommand() != null && !fn.getImageConfigCommand().isEmpty()) {
                specBuilder.withCmd(fn.getImageConfigCommand());
            }
            if (fn.getImageConfigWorkingDirectory() != null && !fn.getImageConfigWorkingDirectory().isBlank()) {
                specBuilder.withWorkingDir(fn.getImageConfigWorkingDirectory());
            }
        } else if (fn.getHandler() != null && !fn.getHandler().isBlank()) {
            specBuilder.withCmd(fn.getHandler());
        }

        // Mount host AWS config into Lambda container (read-only) for SDK credential discovery
        awsConfigPath.ifPresent(hostPath -> {
            if (!Files.isDirectory(Path.of(hostPath))) {
                LOG.warnv("awsConfigPath '{0}' does not exist or is not a directory; "
                        + "Lambda containers may fail to discover credentials", hostPath);
            }
            specBuilder.withReadOnlyBind(hostPath, "/opt/aws-config");
        });

        ContainerSpec spec = specBuilder.build();

        // Create container without starting — provided.* runtimes exec
        // /var/runtime/bootstrap on start, so code must be copied first.
        containerId = lifecycleManager.create(spec);
        LOG.infov("Created container {0} for function {1}", containerId, fn.getFunctionName());

        // Copy code into container via Docker API tar stream (works inside Docker too).
        // Hot-reload functions skip the tar-copy — the bind-mount already wires the host path.
        DockerClient dockerClient = lifecycleManager.getDockerClient();
        if (!fn.isHotReload() && fn.getCodeLocalPath() != null) {
            Path codePath = Path.of(fn.getCodeLocalPath());

            // Large code (useCodeVolume): /var/task is supplied by the read-only named volume
            // mounted on the spec above (populated once per code version) — no per-container copy.
            // Small code: copy it directly into /var/task on this container (the original fast path,
            // cheap enough that a shared volume's helper round-trip would only add cold-start latency).
            if (!useCodeVolume) {
                copyDirToContainer(dockerClient, containerId, codePath, TASK_DIR, fn.getFunctionName());
            }

            // For provided runtimes, also copy the 'bootstrap' file to /var/runtime (RUNTIME_DIR)
            if (isProvidedRuntime(fn.getRuntime())) {
                Path bootstrapPath = codePath.resolve("bootstrap");
                if (Files.exists(bootstrapPath)) {
                    copyFileToContainer(dockerClient, containerId, bootstrapPath, RUNTIME_DIR, "bootstrap", fn.getFunctionName());
                } else {
                    LOG.warnv("Provided runtime function {0} is missing 'bootstrap' file in {1}",
                            fn.getFunctionName(), fn.getCodeLocalPath());
                }
            }
        }

        // 3. Copy layer contents into /opt (layers are merged in order)
        if (fn.getLayers() != null && !fn.getLayers().isEmpty()) {
            for (String layerArn : fn.getLayers()) {
                LambdaLayerVersion layer = layerService.resolveLayerByArn(layerArn);
                if (layer != null && layer.getCodeLocalPath() != null) {
                    Path layerPath = Path.of(layer.getCodeLocalPath());
                    if (Files.exists(layerPath)) {
                        copyDirToContainer(dockerClient, containerId, layerPath, "/opt", fn.getFunctionName());
                        LOG.debugv("Copied layer {0} into container {1} at /opt", layerArn, containerId);
                    } else {
                        LOG.warnv("Layer code path not found for {0}: {1}", layerArn, layer.getCodeLocalPath());
                    }
                } else {
                    LOG.warnv("Could not resolve layer ARN: {0} for function {1}", layerArn, fn.getFunctionName());
                }
            }
        }

        // 4. Copy Floci's CA cert so the container trusts Floci's HTTPS endpoint (TLS mode).
        //    Placed before start so NODE_EXTRA_CA_CERTS et al. resolve at runtime init.
        //    An if-block rather than ifPresent(...) because containerId is assigned along the
        //    code-volume path and so is not effectively final for a lambda capture.
        if (flociCaCert.isPresent()) {
            copyFileToContainer(dockerClient, containerId, flociCaCert.get(),
                    FLOCI_CA_DIR, FLOCI_CA_FILE_NAME, fn.getFunctionName());
        }

        // Now start the container with code in place
        lifecycleManager.startCreated(containerId, spec);

        ContainerHandle handle = new ContainerHandle(containerId, fn.getFunctionName(), runtimeApiServer, ContainerState.WARM, fn.isHotReload());

        // Attach log streaming
        Closeable logHandle = logStreamer.attach(
                containerId, cwLogGroup, cwLogStream, lambdaRegion, "lambda:" + fn.getFunctionName());
        handle.setLogStream(logHandle);

        return handle;
        } catch (RuntimeException e) {
            // Launch failed somewhere after the runtime-api server was allocated — image/host
            // resolve, the code-volume populate, the spec build, a create/copy/start under Docker
            // load, or the log-stream attach. Free the runtime-api port (else a cold-start burst
            // leaks one per attempt and exhausts the pool) and reap any half-built container (leaked
            // "Created" containers bog the daemon and starve reuse). containerId is null when we
            // failed before the container was created (e.g. an ensureCodeVolume populate failure).
            LOG.errorv("Container launch failed for function {0}; cleaning up: {1}",
                    fn.getFunctionName(), e.getMessage());
            if (containerId != null) {
                try { lifecycleManager.stopAndRemove(containerId, null); } catch (Exception ignore) { /* best effort */ }
            }
            try { runtimeApiServerFactory.release(runtimeApiServer); } catch (Exception ignore) { /* best effort */ }
            throw e;
        }
    }

    public void stop(ContainerHandle handle) {
        LOG.infov("Stopping container {0}", handle.getContainerId());
        handle.setState(ContainerState.STOPPED);

        try {
            handle.getRuntimeApiServer().stop().get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOG.warnv(e, "RuntimeApiServer did not close cleanly for container {0}",
                    handle.getContainerId());
        } finally {
            runtimeApiServerFactory.release(handle.getRuntimeApiServer());
        }
        lifecycleManager.stopAndRemove(handle.getContainerId(), handle.getLogStream());
    }

    /**
     * Probes whether the handle's underlying container is still running.
     *
     * @param handle the warm-pool handle to probe
     * @return true if the container is still running
     */
    public boolean isAlive(ContainerHandle handle) {
        return lifecycleManager.isContainerRunning(handle.getContainerId());
    }

    // Cap concurrent code-volume POPULATES. Populating a large function's volume creates a helper
    // container and streams ~90MB of node_modules into it; a burst of first-time populates run at
    // once (e.g. a UI page-load firing 6-8 parallel API calls against never-before-seen functions)
    // overwhelmed the Docker daemon, so copies hung/failed and left half-built "Created" containers.
    // This gates ONLY the heavy populate — not every launch — so ordinary cold starts (volume mounts
    // for already-populated large code, or the small-code direct copy) are never serialized.
    private static final java.util.concurrent.Semaphore POPULATE_SEMAPHORE =
            new java.util.concurrent.Semaphore(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));

    private static void acquirePopulatePermit(String functionName) {
        try {
            POPULATE_SEMAPHORE.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting to populate code volume for " + functionName, ie);
        }
    }

    // Per-function-version code volumes: populated once, then mounted read-only into every
    // container so cold starts skip the ~95s node_modules copy. Tracks which volumes are already
    // populated and a per-volume lock so a concurrent cold-start storm populates only once. The
    // lock entry is dropped after a successful populate so this map only ever holds in-flight
    // populates (see ensureCodeVolume) rather than growing across redeploys.
    private final java.util.Set<String> populatedCodeVolumes = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ConcurrentHashMap<String, Object> codeVolumeLocks = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Returns the name of a read-only Docker volume holding this function's {@code /var/task} code,
     * populating it once (per code version) on first use. Populating streams the code into a
     * throwaway helper container that has the volume mounted read-write; every real container then
     * just mounts the volume read-only, turning a ~95s per-container copy into a ~0.2s mount.
     */
    private String ensureCodeVolume(LambdaFunction fn, String image) {
        String volName = codeVolumeName(fn);
        if (populatedCodeVolumes.contains(volName)) {
            return volName;
        }
        Object lock = codeVolumeLocks.computeIfAbsent(volName, k -> new Object());
        synchronized (lock) {
            if (populatedCodeVolumes.contains(volName)) {
                return volName;
            }
            long t0 = System.currentTimeMillis();
            LOG.infov("Populating code volume {0} for function {1} (one-time per code version)",
                    volName, fn.getFunctionName());
            populateCodeVolume(volName, fn, image);
            populatedCodeVolumes.add(volName);
            // Bound codeVolumeLocks: drop the lock now that the volume is populated, so the map only
            // ever holds in-flight populates rather than growing across redeploys. Safe under the
            // lock: a late thread that computeIfAbsent's a fresh lock object will still hit the
            // populatedCodeVolumes.contains check inside its synchronized block and return without
            // re-populating.
            codeVolumeLocks.remove(volName);
            LOG.infov("Populated code volume {0} in {1}ms; future cold starts mount it instead of copying",
                    volName, System.currentTimeMillis() - t0);
        }
        // We do NOT eagerly delete a function's previous code-version volume here: a concurrent
        // in-flight launch may have already resolved that name and be about to mount it, so deleting
        // it would give that container an empty /var/task. Stale volumes are labeled floci=true (see
        // ContainerLifecycleManager.ensureVolume) and reclaimed by `docker volume prune --filter label=floci`.
        return volName;
    }

    private void populateCodeVolume(String volName, LambdaFunction fn, String image) {
        lifecycleManager.ensureVolume(volName);
        String shortId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        // A minimal helper container (sleep) with the volume mounted read-write at /var/task; we
        // tar-copy the code into it, then discard it — the data persists in the volume.
        ContainerSpec helperSpec = containerBuilder.newContainer(image)
                .withName("floci-codevol-" + fn.getFunctionName() + "-" + shortId)
                .withEnv(java.util.List.of())
                .withEntrypoint(java.util.List.of("sleep"))
                .withCmd(java.util.List.of("3600"))
                .withNamedVolume(volName, TASK_DIR, false)
                .build();
        // Gate the heavy work (helper create + ~90MB tar copy) so a burst of first-time populates
        // doesn't thrash the Docker daemon. Only populates are serialized — plain cold starts aren't.
        acquirePopulatePermit(fn.getFunctionName());
        String helperId = null;
        try {
            helperId = lifecycleManager.create(helperSpec);
            lifecycleManager.startCreated(helperId, helperSpec);
            copyDirToContainer(lifecycleManager.getDockerClient(), helperId,
                    Path.of(fn.getCodeLocalPath()), TASK_DIR, fn.getFunctionName());
        } finally {
            if (helperId != null) {
                try { lifecycleManager.stopAndRemove(helperId, null); } catch (Exception ignore) { /* best effort */ }
            }
            POPULATE_SEMAPHORE.release();
        }
    }

    /**
     * Total code size (bytes) at or above which a function's {@code /var/task} is served from a
     * shared read-only volume rather than copied into every container. This is roughly the point
     * where per-container small-file overlay copies get slow enough (many-file node_modules trees)
     * that populating a volume once and mounting it read-only wins overall. Below it, the direct
     * per-container copy is faster than the volume's populate-helper round-trip, so we keep it —
     * which is what tiny handlers (e.g. WebSocket-route Lambdas) need to avoid cold-start latency.
     * Non-final and package-private so tests can override it (restore it in a finally).
     */
    static long CODE_VOLUME_MIN_BYTES = 32L * 1024 * 1024;

    /**
     * Returns true iff the total size of files under {@code codeDir} meets or exceeds
     * {@link #CODE_VOLUME_MIN_BYTES}. Walks the tree short-circuiting as soon as the running total
     * crosses the threshold (so huge trees aren't fully summed). Any IO error returns false so the
     * caller falls back to the direct per-container copy.
     */
    static boolean shouldUseCodeVolume(Path codeDir) {
        final long threshold = CODE_VOLUME_MIN_BYTES;
        final long[] total = {0L};
        try (var stream = Files.walk(codeDir)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (Files.isRegularFile(path)) {
                    total[0] += Files.size(path);
                    if (total[0] >= threshold) {
                        return true;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.debugv("Could not size code dir {0} ({1}); using direct copy", codeDir, e.getMessage());
            return false;
        }
        return false;
    }

    /**
     * Docker-volume-safe name keyed by function + code version, so a redeploy yields a new volume.
     * Prefers the code SHA-256; falls back to last-modified when the SHA is unavailable.
     */
    static String codeVolumeName(LambdaFunction fn) {
        String key = fn.getCodeSha256();
        if (key == null || key.isBlank()) {
            key = Long.toString(fn.getLastModified());
        }
        String h = key.replaceAll("[^a-zA-Z0-9]", "");
        if (h.length() > 20) {
            h = h.substring(0, 20);
        }
        if (h.isEmpty()) {
            h = "0";
        }
        String fname = fn.getFunctionName().replaceAll("[^a-zA-Z0-9_.-]", "-");
        return "floci-code-" + fname + "-" + h;
    }

    /**
     * Buffer for the tar-streaming pipe. The default {@link java.io.PipedInputStream} buffer is
     * only 1KB, which forces a writer/reader thread hand-off (wait/notify) every 1KB. Streaming a
     * ~90MB node_modules through that ran at ~0.5MB/s (≈3 min per cold start) — pure synchronization
     * thrash, not I/O. A large buffer lets the tar writer stream ahead so throughput is bound by the
     * Docker daemon, not the pipe.
     */
    private static final int TAR_PIPE_BUFFER_BYTES = 16 * 1024 * 1024;

    private void copyDirToContainer(DockerClient dockerClient, String containerId,
                                    Path sourceDir, String remotePath, String functionName) {
        // No per-copy gating here: the heavy /var/task populate for large code already holds a
        // POPULATE_SEMAPHORE permit; small-code direct copies and layer copies are light enough
        // to run unthrottled.
        try (java.io.PipedOutputStream pos = new java.io.PipedOutputStream();
             java.io.PipedInputStream pis = new java.io.PipedInputStream(pos, TAR_PIPE_BUFFER_BYTES)) {

            new Thread(() -> {
                try (pos) {
                    createTarFromDir(sourceDir, pos);
                } catch (IOException e) {
                    LOG.errorv("Failed to stream tar for function {0}: {1}", functionName, e.getMessage());
                }
            }, "tar-streamer-dir-" + functionName).start();

            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withRemotePath(remotePath)
                    .withTarInputStream(pis)
                    .exec();
            LOG.debugv("Copied directory {0} into container {1} at {2}", sourceDir, containerId, remotePath);
        } catch (Exception e) {
            // Fail loudly so launch() cleans up the half-built container instead of leaking it.
            throw new RuntimeException("Failed to copy directory " + sourceDir + " into container "
                    + containerId + " for function " + functionName + ": " + e.getMessage(), e);
        }
    }

    private void copyFileToContainer(DockerClient dockerClient, String containerId,
                                     Path sourceFile, String remotePath, String entryName, String functionName) {
        try (java.io.PipedOutputStream pos = new java.io.PipedOutputStream();
             java.io.PipedInputStream pis = new java.io.PipedInputStream(pos, TAR_PIPE_BUFFER_BYTES)) {

            new Thread(() -> {
                try (TarArchiveOutputStream tar = newTarStream(pos)) {
                    TarArchiveEntry entry = new TarArchiveEntry(entryName);
                    entry.setSize(Files.size(sourceFile));
                    entry.setMode(0755);
                    tar.putArchiveEntry(entry);
                    try (var fis = Files.newInputStream(sourceFile)) {
                        fis.transferTo(tar);
                    }
                    tar.closeArchiveEntry();
                } catch (IOException e) {
                    LOG.errorv("Failed to stream file tar for function {0}: {1}", functionName, e.getMessage());
                }
            }, "tar-streamer-file-" + functionName).start();

            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withRemotePath(remotePath)
                    .withTarInputStream(pis)
                    .exec();
            LOG.debugv("Copied file {0} as {1} into container {2} at {3}", sourceFile, entryName, containerId, remotePath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to copy file " + sourceFile + " into container "
                    + containerId + " for function " + functionName + ": " + e.getMessage(), e);
        }
    }

    private static boolean isProvidedRuntime(String runtime) {
        return runtime != null && runtime.startsWith("provided");
    }

    /**
     * Resolves the host path of Floci's CA certificate to inject into Lambda containers, or
     * empty when TLS is disabled or no readable certificate exists. Mirrors {@code TlsConfigSource}:
     * a user-provided {@code floci.tls.cert-path} wins; otherwise the self-signed cert under
     * {@code {persistent-path}/tls/}.
     *
     * <p>The resolved certificate is injected into containers as a <em>trust anchor</em> (CA), so it
     * should be a self-signed CA certificate. The auto-generated Floci cert is one; a user-supplied
     * {@code floci.tls.cert-path} that points at a leaf/server certificate is accepted but only pins
     * that exact certificate (it cannot validate a chain it signs), so a warning is logged.
     */
    static Optional<Path> resolveFlociCaCertPath(boolean tlsEnabled, Optional<String> userCertPath,
                                                 String persistentPath) {
        if (!tlsEnabled) {
            return Optional.empty();
        }
        Optional<String> trimmedUserPath = userCertPath.filter(s -> !s.isBlank());
        Path certPath = trimmedUserPath
                .map(Path::of)
                .orElseGet(() -> Path.of(persistentPath, "tls", SELF_SIGNED_CERT_NAME));
        if (!Files.isReadable(certPath)) {
            LOG.warnv("TLS enabled but Floci CA certificate not readable at {0}; "
                    + "Lambda containers will not trust Floci HTTPS callbacks", certPath);
            return Optional.empty();
        }
        if (trimmedUserPath.isPresent() && !isSelfSignedCaCertificate(certPath)) {
            LOG.warnv("Configured floci.tls.cert-path {0} is not a self-signed CA certificate; it is "
                    + "injected into Lambda containers as a trust anchor (CA), which only validates "
                    + "this exact certificate and not a chain it signs. Provide a self-signed CA "
                    + "certificate for reliable HTTPS callbacks.", certPath);
        }
        return Optional.of(certPath);
    }

    /**
     * Returns {@code true} only if {@code certPath} holds a genuinely self-signed CA certificate
     * (issuer == subject and BasicConstraints {@code CA:true}) — the form usable as a trust anchor.
     * A leaf/server certificate, or one that cannot be read/parsed as X.509, returns {@code false}.
     */
    static boolean isSelfSignedCaCertificate(Path certPath) {
        try (InputStream in = Files.newInputStream(certPath)) {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(in);
            boolean selfSigned = cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal());
            boolean isCa = cert.getBasicConstraints() >= 0; // -1 == not a CA
            return selfSigned && isCa;
        } catch (Exception e) {
            LOG.debugv("Could not inspect TLS certificate {0} for CA suitability: {1}",
                    certPath, e.getMessage());
            return false;
        }
    }

    /**
     * Environment entries that make the container <em>add</em> Floci's CA to its trust, without
     * replacing the system trust store (which would break the Lambda's external HTTPS calls):
     * <ul>
     *   <li>{@code NODE_EXTRA_CA_CERTS} appends Floci's cert to Node's built-in CAs, so public TLS
     *       from the Lambda still works; and</li>
     *   <li>{@code AWS_CA_BUNDLE} is scoped to AWS SDK/CLI traffic, which Floci redirects to its own
     *       endpoint via {@code AWS_ENDPOINT_URL} — so pointing it at Floci's cert only affects
     *       calls that already target Floci.</li>
     * </ul>
     * {@code SSL_CERT_FILE} and {@code REQUESTS_CA_BUNDLE} are deliberately <em>not</em> set: each
     * <em>replaces</em> the entire OpenSSL / Python-requests trust store with only Floci's cert,
     * which breaks every external HTTPS call (curl, openssl, requests/botocore) the Lambda makes.
     * Returns an empty list when no CA cert is available (TLS off).
     */
    static List<String> flociCaEnv(Optional<Path> caCert) {
        if (caCert.isEmpty()) {
            return List.of();
        }
        return List.of(
                "NODE_EXTRA_CA_CERTS=" + FLOCI_CA_CONTAINER_PATH,
                "AWS_CA_BUNDLE=" + FLOCI_CA_CONTAINER_PATH);
    }

    private static String extractRegionFromArn(String arn, String defaultRegion) {
        if (arn == null) {
            return defaultRegion;
        }
        String[] parts = arn.split(":");
        return parts.length >= 4 && !parts[3].isEmpty() ? parts[3] : defaultRegion;
    }

    /**
     * Creates a TAR archive from all files in {@code sourceDir}, streaming to {@code out}.
     * Uses GNU long-name extension (via Commons Compress) so file paths of any length
     * are preserved without truncation.
     */
    private static void createTarFromDir(Path sourceDir, OutputStream out) throws IOException {
        try (TarArchiveOutputStream tar = newTarStream(out);
             var stream = Files.walk(sourceDir)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(path)) {
                    continue;
                }
                String entryName = sourceDir.relativize(path).toString();
                TarArchiveEntry entry = new TarArchiveEntry(entryName);
                entry.setSize(Files.size(path));
                entry.setMode(0755);
                tar.putArchiveEntry(entry);
                try (var fis = Files.newInputStream(path)) {
                    fis.transferTo(tar);
                }
                tar.closeArchiveEntry();
            }
        }
    }

    private static TarArchiveOutputStream newTarStream(OutputStream out) {
        TarArchiveOutputStream tar = new TarArchiveOutputStream(out);
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
        tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR);
        return tar;
    }
}
