package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.iam.model.SessionCreds;
import io.github.hectorvent.floci.services.lambda.LambdaLayerService;
import io.github.hectorvent.floci.services.lambda.model.ContainerState;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.model.LambdaLayerVersion;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServer;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServerFactory;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Starts and stops Docker containers for Lambda function execution.
 * Always starts the RuntimeApiServer before the container so the runtime
 * can connect immediately when the container boots.
 *
 * Code is injected into the container via the Docker API tar-copy endpoint
 * rather than a bind mount, so it works when Floci itself runs inside Docker.
 */
@ApplicationScoped
@Typed(ContainerLauncher.class)
public class ContainerLauncher implements LambdaRuntimeLauncher {

    private static final Logger LOG = Logger.getLogger(ContainerLauncher.class);
    private static final String TASK_DIR = "/var/task";
    private static final String RUNTIME_DIR = "/var/runtime";

    /** Default base prefix for the containers and code volumes Lambda spawns. */
    static final String DEFAULT_NAME_PREFIX = "floci";
    /** A prefix must be a legal Docker name on its own: names must start alphanumeric. */
    private static final java.util.regex.Pattern SAFE_NAME_PREFIX =
            java.util.regex.Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]*$");

    /**
     * The base name prefix for Lambda containers and code volumes:
     * {@code floci.services.lambda.container-name-prefix} when set and Docker-safe,
     * otherwise the default {@code floci}.
     */
    static String resolveContainerNamePrefix(EmulatorConfig config) {
        String configured = config.services().lambda().containerNamePrefix()
                .map(String::trim).orElse("");
        if (configured.isEmpty()) {
            return DEFAULT_NAME_PREFIX;
        }
        if (!SAFE_NAME_PREFIX.matcher(configured).matches()) {
            LOG.warnv("Ignoring floci.services.lambda.container-name-prefix \"{0}\": not a valid "
                    + "Docker name prefix ([A-Za-z0-9][A-Za-z0-9_.-]*); using \"{1}\"",
                    configured, DEFAULT_NAME_PREFIX);
            return DEFAULT_NAME_PREFIX;
        }
        return configured;
    }

    /**
     * In-container location of Floci's CA certificate, injected when TLS is enabled so the
     * container trusts Floci's self-signed HTTPS endpoint. {@code /etc} exists in every Lambda
     * base image, so no directory needs to be created.
     */
    private static final String FLOCI_CA_DIR = "/etc";
    private static final String FLOCI_CA_FILE_NAME = "floci-ca.crt";
    /** Shared with the kubernetes executor, which mounts the CA ConfigMap at the same path. */
    public static final String FLOCI_CA_CONTAINER_PATH = FLOCI_CA_DIR + "/" + FLOCI_CA_FILE_NAME;
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
    private final LambdaExecutionRoleCredentials executionRoleCredentials;

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
                             LaunchedContainerAwsEnv awsEnv,
                             LambdaExecutionRoleCredentials executionRoleCredentials) {
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
        this.executionRoleCredentials = executionRoleCredentials;
        this.populateSemaphore = new java.util.concurrent.Semaphore(resolvePopulateConcurrency(config));
    }

    @PostConstruct
    void init() {
        volumeCleanupScheduler.scheduleAtFixedRate(this::cleanupSupersededVolumes,
                VOLUME_CLEANUP_GRACE_MS, VOLUME_CLEANUP_GRACE_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        volumeCleanupScheduler.shutdownNow();
    }

    public ContainerHandle launch(LambdaFunction fn) {
        LOG.infov("Launching container for function: {0}", fn.getFunctionName());

        // For Zip functions, verify code exists before allocating any resources.
        // Hot-reload functions use a bind-mount; the Docker daemon validates the path at start.
        // Image functions carry an imageUri rather than a local path.
        if (!fn.isHotReload() && !"Image".equals(fn.getPackageType())) {
            if (fn.getCodeLocalPath() == null) {
                // A null path used to skip validation entirely, which made the one state that
                // always means "no code" the one state never checked: the container started
                // empty, the runtime logged an ImportModuleError nobody saw, and the caller
                // waited out the whole function timeout. Failing here surfaces a dropped code
                // field at its cause instead of as a timeout somewhere else (#1987).
                throw new RuntimeException("No code location for function '"
                        + fn.getFunctionName() + "'"
                        + ("$LATEST".equals(fn.getVersion()) ? "" : " version " + fn.getVersion())
                        + " (function has no deployed code)");
            }
            Path codePath = Path.of(fn.getCodeLocalPath());
            if (!Files.exists(codePath)) {
                throw new RuntimeException("Code directory not found for function '"
                        + fn.getFunctionName() + "': " + fn.getCodeLocalPath()
                        + " (function may have been deleted or updated)");
            }
        }

        // Start Runtime API server first so container can connect on boot
        RuntimeApiServer runtimeApiServer = runtimeApiServerFactory.create();
        runtimeApiServer.setFunctionMetadata(fn.getFunctionName(), fn.getVersion(), fn.getHandler(),
                fn.getAccountId());

        // Everything after the runtime-api server is allocated runs inside one try/catch: a failure
        // ANYWHERE below — image/host resolve, the code-volume populate (ensureCodeVolume), the spec
        // build, or the create/copy/start — must release that runtime-api port (and reap any
        // half-built container). Otherwise a cold-start burst that trips the Docker daemon leaks one
        // runtime-api port per failed attempt and eventually exhausts the pool, so launches keep
        // failing even after the daemon recovers.
        String containerId = null;
        // Hoisted alongside containerId, for the same reason: the resolved volume name (if any)
        // must be visible in the catch block below to release its in-flight reference on any
        // failure path, not just the one where useCodeVolume's block itself throws.
        String reservedCodeVolume = null;
        Optional<SessionCreds> roleCredentials = Optional.empty();
        try {

        // Resolve image
        String image = "Image".equals(fn.getPackageType()) && fn.getImageUri() != null
                ? fn.getImageUri()
                : imageResolver.resolve(fn.getRuntime());

        // If this is an AWS-shaped ECR URI, rewrite it to Floci's loopback registry
        image = ecrRegistryManager.rewriteImageUri(image);

        // Determine host address reachable from container
        String hostAddress = dockerHostResolver.resolve();
        String runtimeApiEndpoint = hostAddress + ":" + runtimeApiServer.getPort();

        // Give the container a human-readable name (needed for log stream name below)
        String shortId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String containerName = ContainerStorageHelper.prefixedDockerName(config,
                resolveContainerNamePrefix(config), fn.getFunctionName() + "-" + shortId);

        // CloudWatch log coordinates — computed here so they can be injected as env vars
        String cwLogGroup  = "/aws/lambda/" + fn.getFunctionName();
        String cwLogStream = LOG_STREAM_DATE_FMT.format(LocalDate.now()) + "/[$LATEST]" + shortId;
        String lambdaRegion = extractRegionFromArn(fn.getFunctionArn(), config.defaultRegion());
        String lambdaAccountId = AwsArnUtils.accountOrDefault(fn.getFunctionArn(), config.defaultAccountId());

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
        // Region, credentials and the Floci endpoint the SDK should target: the same baseline
        // Floci gives every launched container. When the host ~/.aws is mounted (awsConfigPath),
        // the SDK discovers credentials from /opt/aws-config instead of injected credentials.
        Optional<String> awsConfigPath = config.services().lambda().awsConfigPath()
                .filter(s -> !s.isBlank());
        if (awsConfigPath.isEmpty()) {
            roleCredentials = executionRoleCredentials.forFunction(fn);
        }
        env.addAll(awsEnv.sdkBaselineEnv(lambdaRegion,
                awsConfigPath.isPresent() ? Optional.of("/opt/aws-config") : Optional.empty(),
                roleCredentials, lambdaAccountId));
        env.addAll(flociCaEnv(flociCaCert));
        if (fn.getEnvironment() != null) {
            boolean hasExecutionRoleCredentials = roleCredentials.isPresent();
            boolean userDefinesFullCredentialTriad = definesFullCredentialTriad(fn.getEnvironment());
            fn.getEnvironment().forEach((k, v) -> {
                if (isAwsCredentialVariable(k)) {
                    // Credential injection is all-or-nothing: a partial override (e.g. only
                    // AWS_ACCESS_KEY_ID set) must never join the baseline's other two values —
                    // that pairs a user-chosen key with the owner-account/execution-role secret
                    // and session token, a tuple nothing can verify. Only let the user's triad
                    // through when it is complete, and only when there is no execution role
                    // (which is already the authoritative credential source).
                    if (!hasExecutionRoleCredentials && userDefinesFullCredentialTriad) {
                        env.add(k + "=" + v);
                    }
                } else {
                    env.add(k + "=" + v);
                }
            });
        }

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withEnv(env)
                .withMemoryMb(fn.getMemorySize())
                .withDockerNetwork(config.services().lambda().dockerNetwork())
                .withHostDockerInternalOnLinux()
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                        "lambda", fn.getFunctionName(), lambdaAccountId, lambdaRegion));

        specBuilder.withEmbeddedDns();

        // Inject extra hosts entries into the container if present. Split on the FIRST
        // colon, mirroring docker --add-host: hostnames cannot contain colons, but IPv6
        // addresses do (e.g. "db.internal:2001:db8::1").
        config.services().lambda().extraHosts().ifPresent(hosts -> hosts.forEach(entry -> {
            int sep = entry.indexOf(':');
            if (sep <= 0 || sep == entry.length() - 1) {
                LOG.warnv("Ignoring malformed lambda extra-hosts entry (expected hostname:ip): {0}", entry);
                return;
            }
            specBuilder.withExtraHost(entry.substring(0, sep), entry.substring(sep + 1));
        }));

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
                reservedCodeVolume = ensureCodeVolume(fn, image);
                specBuilder.withNamedVolume(reservedCodeVolume, TASK_DIR, true);
            }
            // Small code takes the original per-container direct copy below (no volume): it's fast
            // enough that a shared volume's helper round-trip would only add cold-start latency.
        }

        if (fn.getFileSystemConfigs() != null && !fn.getFileSystemConfigs().isEmpty()) {
            var efsCfg = config.storage().efs();
            fn.getFileSystemConfigs().forEach(fileSystem -> {
                String volumeName = efsVolumeName(fileSystem.getArn());
                lifecycleManager.ensureSharedVolume(volumeName,
                        efsCfg.ownerUid(), efsCfg.ownerGid(), efsCfg.rootPermissions(),
                        efsCfg.initImage());
                specBuilder.withNamedVolume(volumeName, fileSystem.getLocalMountPath(), false);
            });
            efsCfg.mountUser().ifPresent(user -> {
                if (!user.matches("^\\d+(:\\d+)?$")) {
                    throw new IllegalArgumentException(
                            "floci.storage.efs.mount-user must be \"uid\" or \"uid:gid\": " + user);
                }
                specBuilder.withUser(user);
            });
            efsCfg.mountGroupAdd().ifPresent(gid -> specBuilder.withGroupAdd(String.valueOf(gid)));
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
        containerId = createContainer(spec, fn);
        LOG.infov("Created container {0} for function {1}", containerId, fn.getFunctionName());
        // Docker now holds the real container-to-volume reference, which removeVolume's own in-use
        // check protects from here on - release the in-flight marker that stood in for it before
        // this point, and null it out so the catch block below doesn't release it a second time.
        releaseCodeVolumeReference(reservedCodeVolume);
        reservedCodeVolume = null;

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

        // Extensions can log as soon as they start, which is before the container's own log stream
        // is attached below. Create the group/stream up front so those early lines are not dropped
        // by CloudWatch; the call is idempotent, so attach() repeating it is harmless.
        LogDestination logDestination = new LogDestination(cwLogGroup, cwLogStream, lambdaRegion);
        logStreamer.ensureLogGroupAndStream(cwLogGroup, cwLogStream, lambdaRegion);

        // Real AWS's runtime interface client discovers and launches every binary under
        // /opt/extensions/ as a sibling process to the main entrypoint before the runtime is
        // considered ready; Floci only runs the image's own ENTRYPOINT/CMD, so extensions
        // (e.g. aws-lambda-web-adapter) never start without this. Best-effort: an extension
        // launch failure shouldn't fail the whole container launch, since a function with no
        // extensions is the common case and this must be a no-op for it.
        launchExtensions(dockerClient, containerId, fn.getFunctionName(), runtimeApiServer, logDestination);

        // Init-readiness barrier: the execs above are detached, so without waiting here the caller
        // could enqueue the first invocation before an extension is ready to receive it — the
        // adapter would silently miss that invoke. Real AWS likewise holds the environment out of
        // service until every extension is init-ready, which it defines as the extension's first
        // /extension/event/next rather than its register call. A no-extensions function (the
        // common case) does not wait at all; a timeout is non-fatal, since a container that serves
        // invocations without a slow extension is strictly better than failing the launch.
        awaitExtensionReadiness(runtimeApiServer, fn.getFunctionName());

        ContainerHandle handle = new ContainerHandle(
                containerId, fn.getFunctionName(), runtimeApiServer, ContainerState.WARM, fn.isHotReload(),
                roleCredentials.map(SessionCreds::accessKeyId).orElse(null),
                LambdaExecutionRoleCredentials.sessionAccountId(fn));

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
                try {
                    lifecycleManager.stopAndRemove(containerId, null);
                } catch (Exception cleanupError) {
                    LOG.warnv(cleanupError, "Could not remove failed Lambda container {0}", containerId);
                }
            }
            if (roleCredentials.isPresent()) {
                executionRoleCredentials.unregister(
                        LambdaExecutionRoleCredentials.sessionAccountId(fn),
                        roleCredentials.get().accessKeyId());
            }
            // No-op if create() already succeeded and released this above (reservedCodeVolume is
            // null by then); otherwise the failure happened before Docker ever saw the volume, so
            // its in-flight reference must be released here or cleanup would wait on it forever.
            releaseCodeVolumeReference(reservedCodeVolume);
            // Stop the server before releasing its port, or the still-listening Vert.x server
            // makes a later cold start that reuses the port serve this runtime too.
            try {
                runtimeApiServer.stop().get(5, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception stopFailure) {
                LOG.debugv("Runtime API server stop failed during launch cleanup: {0}",
                        stopFailure.getMessage());
            }
            try {
                runtimeApiServerFactory.release(runtimeApiServer);
            } catch (Exception cleanupError) {
                LOG.warnv(cleanupError, "Could not release Runtime API server for function {0}",
                        fn.getFunctionName());
            }
            throw e;
        }
    }

    private static Optional<String> dockerPlatform(LambdaFunction fn) {
        List<String> architectures = fn.getArchitectures();
        if (architectures == null) {
            return Optional.of("linux/amd64");
        }
        if (architectures.size() != 1) {
            return Optional.empty();
        }
        return switch (architectures.getFirst()) {
            case "arm64" -> Optional.of("linux/arm64");
            case "x86_64" -> Optional.of("linux/amd64");
            default -> Optional.empty();
        };
    }

    private String createContainer(ContainerSpec spec, LambdaFunction fn) {
        if (!config.services().lambda().honourArchitectures()) {
            return lifecycleManager.create(spec);
        }
        Optional<String> platform = dockerPlatform(fn);
        if (platform.isEmpty()) {
            throw new IllegalStateException("Invalid persisted architectures "
                    + fn.getArchitectures() + " for function '" + fn.getFunctionName() + "'");
        }
        return lifecycleManager.create(spec, platform.get());
    }

    public void stop(ContainerHandle handle) {
        LOG.infov("Stopping container {0}", handle.getContainerId());
        handle.setState(ContainerState.STOPPED);

        RuntimeApiServer server = handle.getRuntimeApiServer();

        // Quiesce first: stop accepting new work, notify extensions, complete pending
        // invocations. Existing runtime pollers on /invocation/next stay parked.
        server.quiesce();

        // Then SIGTERM the container. The runtime library's default SIGTERM handler exits
        // the process while the runtime API socket is still up, matching real AWS Lambda's
        // shutdown flow.
        //
        // stopTimeoutSeconds is the ceiling docker waits for PID 1 to exit before SIGKILL,
        // not a grace floor: the runtime typically exits promptly, so `docker stop`
        // returns shortly after and this value rarely matters end-to-end. Sized against
        // AWS's documented Shutdown-phase tiers as a defensive upper bound only — 2s
        // when at least one extension is registered (matching AWS's 2000ms
        // external-extensions budget), else 1s. NOTE: extensions run as sibling
        // `docker exec`s (not children of PID 1) and are killed when the container
        // exits; the 2s ceiling does not provide a guaranteed grace window for
        // extension shutdown work — only that we will not preempt PID 1 for at least
        // that long if it happens to be slow.
        int stopTimeoutSeconds = server.hasRegisteredExtensions() ? 2 : 1;
        lifecycleManager.stopAndRemove(handle.getContainerId(), handle.getLogStream(),
                stopTimeoutSeconds);

        // Only now close the runtime API socket. Any pollers that were still parked when
        // the container exited get terminated by their end of the connection closing;
        // this call releases the port for reuse.
        try {
            server.close().get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | CancellationException e) {
            // CancellationException is unchecked; catch it so a cancelled close future
            // is logged here instead of propagating out of stop().
            LOG.warnv(e, "RuntimeApiServer did not close cleanly for container {0}",
                    handle.getContainerId());
        } finally {
            try {
                runtimeApiServerFactory.release(server);
            } finally {
                // The session is only reachable while the container is alive, so it is retired
                // here rather than on the launch path's failure branch alone. Runs even if the
                // release above throws, otherwise a failed release strands the registration and
                // its credentials stay authorizable for the rest of the process lifetime.
                executionRoleCredentials.unregister(
                        handle.getExecutionRoleSessionAccountId(), handle.getExecutionRoleAccessKeyId());
            }
        }
    }

    public static boolean isAwsCredentialVariable(String name) {
        return "AWS_ACCESS_KEY_ID".equals(name)
                || "AWS_SECRET_ACCESS_KEY".equals(name)
                || "AWS_SESSION_TOKEN".equals(name);
    }

    /**
     * Whether a Lambda's own Environment config defines all three AWS credential variables,
     * the only condition under which any of them may override the baseline env — a partial
     * set must never leak through and split the baseline's credential tuple. Public: both the
     * Docker and Kubernetes launchers append the function's Environment after the same baseline
     * and must apply this rule identically.
     */
    public static boolean definesFullCredentialTriad(java.util.Map<String, String> environment) {
        return environment.containsKey("AWS_ACCESS_KEY_ID")
                && environment.containsKey("AWS_SECRET_ACCESS_KEY")
                && environment.containsKey("AWS_SESSION_TOKEN");
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
    //
    // Per-instance rather than static so the permit count can come from configuration; Floci runs a
    // single @ApplicationScoped launcher, so this is still one gate for the whole emulator.
    private final java.util.concurrent.Semaphore populateSemaphore;

    /**
     * Permits for {@link #populateSemaphore}: the configured value when set and positive,
     * otherwise {@code max(2, availableProcessors() / 2)}.
     *
     * <p>The derived default reads the JVM's view of the cgroup CPU quota, so a CPU-constrained
     * Floci container collapses it to 2 and concurrent cold starts of distinct functions
     * serialize into pairs. The populate itself is IO-bound (streaming a tar into a helper
     * container), so CPU count is a weak proxy for how many the daemon can take — hence the
     * override. The default is unchanged, since the daemon overload the cap exists to prevent is
     * real and its safe ceiling is host-specific.
     */
    static int resolvePopulateConcurrency(EmulatorConfig config) {
        int derived = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        Optional<Integer> configured = config.services().lambda().codeVolumePopulateConcurrency();
        if (configured.isEmpty()) {
            return derived;
        }
        int permits = configured.get();
        if (permits < 1) {
            LOG.warnv("Ignoring floci.services.lambda.code-volume-populate-concurrency {0}: must be "
                    + "at least 1; using {1}", permits, derived);
            return derived;
        }
        return permits;
    }

    private void acquirePopulatePermit(String functionName) {
        try {
            populateSemaphore.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting to populate code volume for " + functionName, ie);
        }
    }

    // Per-function-version code volumes: populated once, then mounted read-only into every
    // container so cold starts skip the ~95s node_modules copy. Tracks which volumes are already
    // populated and a per-volume lock so a concurrent cold-start storm populates only once - and so
    // ensureCodeVolume's bookkeeping and cleanupSupersededVolumes' claim-and-delete for the same
    // volume name can never interleave (see both call sites).
    //
    // This in-memory bookkeeping is a cache of Docker's actual state, not a source of truth: a
    // volume manually removed mid-session (docker volume rm) or reclaimed by an out-of-band
    // `docker volume prune` would otherwise still read as "populated" here even though Docker has
    // nothing under that name, silently mounting an empty /var/task into the next container.
    // ensureCodeVolume re-checks lifecycleManager.volumeExists() rather than trusting this alone.
    private static final String CODE_VOLUME_MARKER_DIR = "lambda-codevol-markers";
    private final java.util.Set<String> populatedCodeVolumes = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Deliberately never pruned: removing an entry while a caller elsewhere still held a reference
    // to its lock object let a third caller's computeIfAbsent create a replacement lock for the same
    // volume name, so the waiter (once granted the old lock) and the new caller (holding the new
    // one) could run their supposedly-exclusive sections concurrently - the exact race this map
    // exists to prevent. Bounded in practice by the number of distinct function+code-version
    // combinations ever launched, each entry a single Object; negligible next to the disk space the
    // rest of this fix reclaims.
    private final java.util.concurrent.ConcurrentHashMap<String, Object> codeVolumeLocks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Object codeVolumeMarkerIoLock = new Object();

    /** Each function's current code volume, so a redeploy can queue the superseded one for cleanup. */
    private final java.util.concurrent.ConcurrentHashMap<String, String> functionCurrentVolume =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Serializes the functionCurrentVolume/volumesPendingCleanup transition below, keyed by function
     * name rather than volume name. Two code versions of the same function resolve to two different
     * volume names, so they hold two different codeVolumeLocks entries and can run that per-volume
     * critical section concurrently - which is fine for the populate work, but not for this
     * transition: without a shared lock, a rapid back-to-back redeploy (v2 then v3) can have v2's
     * functionCurrentVolume.put land after v3's, leaving v3 - the actually-current volume - recorded
     * as superseded and queued for cleanup while stale v2 is left marked current. Never pruned, for
     * the same lock-identity reason as codeVolumeLocks, and bounded the same way (one entry per
     * distinct function name ever launched).
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Object> functionVolumeTransitionLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Superseded code volumes queued for cleanup, mapped to the time they were superseded (millis
     * since epoch). Deletion is deferred rather than immediate: Docker auto-creates an empty named
     * volume for a container mount that doesn't reference an existing one rather than failing, so a
     * launch that resolved the old volume name just before a redeploy, but hasn't created its
     * container yet, could otherwise be handed an empty /var/task by an immediate delete. Waiting
     * out a grace period comfortably longer than a single container launch takes crosses that
     * window safely; removeVolume() also no-ops if the volume is still genuinely in use by then.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> volumesPendingCleanup =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Counts launches that hold a resolved volume name but haven't yet handed it to Docker via
     * {@code create()} - i.e. no real container-to-volume reference exists yet for Docker's own
     * in-use check to protect. The grace period in {@link #volumesPendingCleanup} assumes a launch
     * completes create() well within it, but create() itself has no proven upper bound (observed
     * up to ~80s under daemon load, longer than the default 60s grace period), so a slow launch can
     * still be mid-flight when a sweep would otherwise delete its volume. cleanupSupersededVolumes
     * re-queues rather than deletes while a volume's count here is nonzero, regardless of elapsed
     * time. Entries are removed once their count returns to zero, so this stays bounded like the
     * other per-volume maps.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> volumesInFlight =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Non-final and package-private, like {@link #CODE_VOLUME_MIN_BYTES}, so tests can shrink it
     *  instead of waiting out a real 60s window (restore it in a finally). */
    static long VOLUME_CLEANUP_GRACE_MS = 60_000L;
    private final java.util.concurrent.ScheduledExecutorService volumeCleanupScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    r -> { Thread t = new Thread(r, "floci-code-volume-cleanup"); t.setDaemon(true); return t; });

    /**
     * Returns the name of a read-only Docker volume holding this function's {@code /var/task} code,
     * populating it once (per code version) on first use. Populating streams the code into a
     * throwaway helper container that has the volume mounted read-write; every real container then
     * just mounts the volume read-only, turning a ~95s per-container copy into a ~0.2s mount.
     */
    String ensureCodeVolume(LambdaFunction fn, String image) {
        String volName = codeVolumeName(resolveContainerNamePrefix(config), fn);
        // Held for the whole resolve-and-reconcile, not just the populate branch: this is the same
        // lock cleanupSupersededVolumes acquires before claiming a volume for deletion, so a launch
        // that resolves a volume can never race a sweep that's about to delete that exact volume out
        // from under it - whichever gets the lock first, the other reliably observes the result
        // (rescued-from-cleanup, or actually-gone-and-needs-repopulating) rather than acting on
        // stale state. Population itself is the only expensive part, and it happens at most once
        // per code version regardless, so holding this for cheap map bookkeeping too costs nothing
        // meaningful. codeVolumeLocks is never pruned (see the comment on that field): removing an
        // entry while a waiter still held a reference to its lock object let a third caller create a
        // replacement lock for the same name and run concurrently with the waiter once released,
        // defeating this exclusion entirely.
        Object lock = codeVolumeLocks.computeIfAbsent(volName, k -> new Object());
        synchronized (lock) {
            if (!(populatedCodeVolumes.contains(volName) && lifecycleManager.volumeExists(volName))) {
                // Either never populated in this process, or the bookkeeping says populated but
                // Docker disagrees (removed out of band). A completion marker from an earlier run
                // is authoritative only while its independently persisted volume still exists.
                if (reusePersistedCodeVolumeOrRemoveStaleMarker(volName)) {
                    LOG.infov("Reusing code volume {0} populated by a previous run (marker present)",
                            volName);
                } else {
                    long t0 = System.currentTimeMillis();
                    LOG.infov("Populating code volume {0} for function {1} (one-time per code version)",
                            volName, fn.getFunctionName());
                    populateCodeVolume(volName, fn, image);
                    writeCodeVolumeMarkerAndPruneOrphans(volName);
                    LOG.infov("Populated code volume {0} in {1}ms; future cold starts mount it instead of copying",
                            volName, System.currentTimeMillis() - t0);
                }
                populatedCodeVolumes.add(volName);
            }
            // Reconcile bookkeeping for the resolved volume even when the check above already found
            // it populated: a redeploy rolled back to an older code version resolves a volume name
            // that's already populated but may have been queued as superseded by the deploy this
            // just rolled back. Without pulling it back out of volumesPendingCleanup and marking it
            // current again, the next sweep would delete the volume this function is actively using.
            //
            // Under the per-function lock too: this specific read-modify-write (put, then queue
            // whatever was previously current) must be atomic across different code versions of the
            // same function, or two concurrent resolves can interleave their put()/queue steps and
            // leave the actually-current volume queued for cleanup instead of the stale one - see the
            // comment on functionVolumeTransitionLocks.
            Object functionLock = functionVolumeTransitionLocks.computeIfAbsent(
                    fn.getFunctionName(), k -> new Object());
            synchronized (functionLock) {
                volumesPendingCleanup.remove(volName);
                String previous = functionCurrentVolume.put(fn.getFunctionName(), volName);
                if (previous != null && !previous.equals(volName)) {
                    volumesPendingCleanup.put(previous, System.currentTimeMillis());
                }
            }
            // Mark this volume as having an unconfirmed reference before releasing the lock: the
            // caller now holds this name but hasn't handed it to Docker yet, so a sweep that acquires
            // this same lock next must see the increment and skip deleting it. The caller releases
            // this via releaseCodeVolumeReference once create() succeeds (or the launch fails).
            volumesInFlight.computeIfAbsent(volName, k -> new java.util.concurrent.atomic.AtomicInteger())
                    .incrementAndGet();
        }
        return volName;
    }

    /**
     * Releases the in-flight reference {@link #ensureCodeVolume} placed on {@code volName}. Safe to
     * call with {@code null} (nothing was reserved) and idempotent bookkeeping-wise: the counter
     * only ever reflects reservations actually made, since callers null out their local reference
     * after releasing it (see {@link #launch}).
     */
    private void releaseCodeVolumeReference(String volName) {
        if (volName == null) {
            return;
        }
        volumesInFlight.computeIfPresent(volName, (k, count) -> count.decrementAndGet() > 0 ? count : null);
    }

    /**
     * Removes superseded code volumes whose grace period has elapsed. Scheduled at the same
     * interval as the grace period itself, so a volume is deleted within roughly one to two
     * intervals of becoming superseded. Not a hard deadline, since this is best-effort cleanup,
     * not a correctness requirement (a volume that outlives a few extra sweeps is still reclaimed by
     * `docker volume prune --filter label=floci` same as before this existed).
     */
    void cleanupSupersededVolumes() {
        long cutoff = System.currentTimeMillis() - VOLUME_CLEANUP_GRACE_MS;
        for (String volName : new java.util.ArrayList<>(volumesPendingCleanup.keySet())) {
            Long queuedAt = volumesPendingCleanup.get(volName);
            if (queuedAt == null || queuedAt > cutoff) {
                continue;
            }
            // Same lock ensureCodeVolume holds for this volume name: claiming and deleting it here
            // must not interleave with a concurrent launch resolving (and possibly rescuing) it.
            Object lock = codeVolumeLocks.computeIfAbsent(volName, k -> new Object());
            synchronized (lock) {
                // Re-check under the lock: a concurrent ensureCodeVolume may have rescued this
                // volume (removed it from volumesPendingCleanup) or requeued it with a fresher
                // timestamp between the check above and acquiring this lock.
                Long stillQueuedAt = volumesPendingCleanup.get(volName);
                if (stillQueuedAt == null || stillQueuedAt > cutoff) {
                    continue;
                }
                java.util.concurrent.atomic.AtomicInteger inFlight = volumesInFlight.get(volName);
                if (inFlight != null && inFlight.get() > 0) {
                    // A launch has resolved this volume but hasn't handed it to Docker yet, so no
                    // real container-to-volume reference exists for removeVolume's own in-use check
                    // to catch. The grace period alone isn't a safe upper bound on that launch's
                    // duration (create() has been observed taking ~80s under daemon load), so requeue
                    // for a later sweep instead of trusting elapsed time here.
                    volumesPendingCleanup.put(volName, System.currentTimeMillis());
                    continue;
                }
                volumesPendingCleanup.remove(volName, stillQueuedAt);
                if (lifecycleManager.removeVolume(volName)) {
                    populatedCodeVolumes.remove(volName);
                    LOG.debugv("Removed superseded code volume {0}", volName);
                } else {
                    // Not confirmed gone: still in use (e.g. a slow-draining in-flight container
                    // outlived the grace period), or the removal attempt itself failed for some
                    // other reason (e.g. a transient daemon error). Either way, without this the
                    // entry would be lost with no later sweep ever retrying it. Leave
                    // populatedCodeVolumes alone too, since for all we know it's still there and
                    // still valid.
                    volumesPendingCleanup.put(volName, System.currentTimeMillis());
                }
            }
        }
    }

    /** Test-only: whether a per-volume lock object has ever been created for this volume name. */
    boolean hasCodeVolumeLock(String volName) {
        return codeVolumeLocks.containsKey(volName);
    }

    /**
     * Test-only: the same lock object ensureCodeVolume synchronizes on for this function's
     * current-volume transition, so a test can hold it directly to prove a concurrent launch blocks
     * on it rather than racing past it.
     */
    Object functionVolumeTransitionLockFor(String functionName) {
        return functionVolumeTransitionLocks.computeIfAbsent(functionName, k -> new Object());
    }

    /** Test-only: the current in-flight reference count for this volume name (0 if none). */
    int inFlightCount(String volName) {
        java.util.concurrent.atomic.AtomicInteger count = volumesInFlight.get(volName);
        return count == null ? 0 : count.get();
    }

    void populateCodeVolume(String volName, LambdaFunction fn, String image) {
        lifecycleManager.ensureVolume(volName);
        String shortId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        // A minimal helper container (sleep) with the volume mounted read-write at /var/task; we
        // tar-copy the code into it, then discard it — the data persists in the volume.
        ContainerBuilder.Builder helperBuilder = containerBuilder.newContainer(image)
                .withName(resolveContainerNamePrefix(config) + "-codevol-" + fn.getFunctionName() + "-" + shortId)
                .withEnv(java.util.List.of())
                .withEntrypoint(java.util.List.of("sleep"))
                .withCmd(java.util.List.of("3600"))
                .withNamedVolume(volName, TASK_DIR, false);
        ContainerSpec helperSpec = helperBuilder.build();
        // Gate the heavy work (helper create + ~90MB tar copy) so a burst of first-time populates
        // doesn't thrash the Docker daemon. Only populates are serialized — plain cold starts aren't.
        acquirePopulatePermit(fn.getFunctionName());
        String helperId = null;
        try {
            helperId = createContainer(helperSpec, fn);
            lifecycleManager.startCreated(helperId, helperSpec);
            copyDirToContainerStrict(lifecycleManager.getDockerClient(), helperId,
                    Path.of(fn.getCodeLocalPath()), TASK_DIR, fn.getFunctionName());
        } finally {
            if (helperId != null) {
                try {
                    lifecycleManager.stopAndRemove(helperId, null);
                } catch (Exception e) {
                    LOG.warnv("Could not remove code-volume helper {0}: {1}", helperId, e.getMessage());
                }
            }
            populateSemaphore.release();
        }
    }

    // A code volume survives an emulator restart, but the in-process populatedCodeVolumes set does not.
    // A persisted completion marker lets a later run recognize an already-populated, content-addressed
    // volume and skip re-copying its files. The marker is stored under storage.persistent-path
    // regardless of storage mode; reuse still requires the independently persisted Docker volume.
    private Path codeVolumeMarkerPath(String volName) {
        return Path.of(config.storage().persistentPath(), CODE_VOLUME_MARKER_DIR, volName);
    }

    /**
     * Reuses a completed volume when both marker and volume exist. If the volume is absent or cannot
     * be verified, removes the marker before repopulation so a failed copy cannot leave the old
     * completion signal beside partial data.
     */
    private boolean reusePersistedCodeVolumeOrRemoveStaleMarker(String volName) {
        synchronized (codeVolumeMarkerIoLock) {
            Path marker = codeVolumeMarkerPath(volName);
            if (!Files.isRegularFile(marker, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            java.util.Optional<java.util.Set<String>> volumeNames = lifecycleManager.tryListVolumeNames();
            boolean inventoryUnavailable = volumeNames.isEmpty();
            if (!inventoryUnavailable && volumeNames.get().contains(volName)) {
                return true;
            }
            try {
                Files.deleteIfExists(marker);
                if (inventoryUnavailable) {
                    LOG.warnv("Could not verify backing volume {0}; removed its marker and re-populating",
                            volName);
                } else {
                    LOG.debugv("Removed stale code-volume marker {0}; backing volume is absent", marker);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Could not invalidate code-volume marker " + marker, e);
            }
            return false;
        }
    }

    private void writeCodeVolumeMarkerAndPruneOrphans(String volName) {
        synchronized (codeVolumeMarkerIoLock) {
            Path marker = codeVolumeMarkerPath(volName);
            try {
                Path markerDir = marker.getParent();
                Files.createDirectories(markerDir);
                Path tempMarker = Files.createTempFile(markerDir, marker.getFileName().toString(), ".tmp");
                try {
                    Files.writeString(tempMarker, "");
                    try {
                        Files.move(tempMarker, marker, StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(tempMarker, marker, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException | RuntimeException e) {
                    try {
                        Files.deleteIfExists(tempMarker);
                    } catch (IOException cleanupError) {
                        e.addSuppressed(cleanupError);
                    }
                    throw e;
                }
            } catch (IOException e) {
                // A missing marker only costs one re-populate on the next boot; never fail a launch over it.
                LOG.warnv("Could not write code-volume marker {0}: {1}", marker, e.getMessage());
            }
            pruneOrphanCodeVolumeMarkers(marker.getParent());
        }
    }

    private void pruneOrphanCodeVolumeMarkers(Path markerDir) {
        java.util.Optional<java.util.Set<String>> volumeNames = lifecycleManager.tryListVolumeNames();
        if (volumeNames.isEmpty() || !Files.isDirectory(markerDir)) {
            return;
        }
        // Markers are named after their volume, so the prune filter must track the configured
        // name prefix. Markers written under a previously configured prefix are left alone —
        // an orphaned marker file is harmless, and pruning only what this configuration could
        // have written can never delete a concurrent process's live markers.
        String markerPrefix = resolveContainerNamePrefix(config) + "-code-";
        try (java.util.stream.Stream<Path> markers = Files.list(markerDir)) {
            markers.filter(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().startsWith(markerPrefix))
                    .filter(path -> !volumeNames.get().contains(path.getFileName().toString()))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                            LOG.debugv("Pruned orphan code-volume marker {0}", path);
                        } catch (IOException e) {
                            LOG.warnv("Could not prune orphan code-volume marker {0}: {1}",
                                    path, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            LOG.warnv("Could not scan code-volume marker directory {0}: {1}", markerDir, e.getMessage());
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
        return codeVolumeName(DEFAULT_NAME_PREFIX, fn);
    }

    /** {@link #codeVolumeName(LambdaFunction)} with a configured base prefix in place of {@code floci}. */
    static String codeVolumeName(String namePrefix, LambdaFunction fn) {
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
        return namePrefix + "-code-" + fname + "-" + h;
    }

    static String efsVolumeName(String accessPointArn) {
        int separator = Math.max(accessPointArn.lastIndexOf('/'), accessPointArn.lastIndexOf(':'));
        String resourceId = separator >= 0 ? accessPointArn.substring(separator + 1) : accessPointArn;
        if (resourceId.isBlank()) {
            throw new IllegalArgumentException("File system access point ARN must include a resource id");
        }
        return "floci-efs-" + resourceId + "-" + sha256Hex(accessPointArn);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but not available", e);
        }
    }

    /**
     * Buffer for the tar-streaming pipe. The default {@link java.io.PipedInputStream} buffer is
     * only 1KB, which forces a writer/reader thread hand-off (wait/notify) every 1KB. Streaming a
     * ~90MB node_modules through that ran at ~0.5MB/s (≈3 min per cold start) — pure synchronization
     * thrash, not I/O. A large buffer lets the tar writer stream ahead so throughput is bound by the
     * Docker daemon, not the pipe.
     */
    private static final int TAR_PIPE_BUFFER_BYTES = 16 * 1024 * 1024;

    @FunctionalInterface
    interface DirectoryTarWriter {
        void write(Path sourceDir, OutputStream out) throws IOException;
    }

    private void copyDirToContainer(DockerClient dockerClient, String containerId,
                                    Path sourceDir, String remotePath, String functionName) {
        copyDirToContainer(dockerClient, containerId, sourceDir, remotePath, functionName,
                ContainerLauncher::createTarFromDir);
    }

    static void copyDirToContainer(DockerClient dockerClient, String containerId,
                                   Path sourceDir, String remotePath, String functionName,
                                   DirectoryTarWriter tarWriter) {
        copyDirToContainer(dockerClient, containerId, sourceDir, remotePath, functionName,
                tarWriter, false);
    }

    private void copyDirToContainerStrict(DockerClient dockerClient, String containerId,
                                          Path sourceDir, String remotePath, String functionName) {
        copyDirToContainerStrict(dockerClient, containerId, sourceDir, remotePath, functionName,
                ContainerLauncher::createTarFromDir);
    }

    static void copyDirToContainerStrict(DockerClient dockerClient, String containerId,
                                         Path sourceDir, String remotePath, String functionName,
                                         DirectoryTarWriter tarWriter) {
        copyDirToContainer(dockerClient, containerId, sourceDir, remotePath, functionName,
                tarWriter, true);
    }

    private static void copyDirToContainer(DockerClient dockerClient, String containerId,
                                           Path sourceDir, String remotePath, String functionName,
                                           DirectoryTarWriter tarWriter, boolean failOnTarFailure) {
        // No per-copy gating here: the heavy /var/task populate for large code already holds a
        // populateSemaphore permit; small-code direct copies and layer copies are light enough
        // to run unthrottled.
        try (java.io.PipedOutputStream pos = new java.io.PipedOutputStream();
             java.io.PipedInputStream pis = new java.io.PipedInputStream(pos, TAR_PIPE_BUFFER_BYTES)) {

            AtomicReference<IOException> tarFailure = new AtomicReference<>();
            Thread tarStreamer = new Thread(() -> {
                try (pos) {
                    tarWriter.write(sourceDir, pos);
                } catch (IOException e) {
                    if (failOnTarFailure) {
                        tarFailure.set(e);
                    } else {
                        LOG.errorv("Failed to stream directory tar for function {0}: {1}",
                                functionName, e.getMessage());
                    }
                }
            }, "tar-streamer-dir-" + functionName);
            tarStreamer.start();

            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withRemotePath(remotePath)
                    .withTarInputStream(pis)
                    .exec();
            if (failOnTarFailure) {
                waitForTarStreamer(tarStreamer, tarFailure, functionName, sourceDir);
            }
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

    private static void waitForTarStreamer(Thread tarStreamer, AtomicReference<IOException> tarFailure,
                                           String functionName, Path sourcePath) throws IOException {
        try {
            tarStreamer.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while streaming tar for function " + functionName
                    + " from " + sourcePath, e);
        }
        IOException failure = tarFailure.get();
        if (failure != null) {
            throw new IOException("Failed to stream tar for function " + functionName
                    + " from " + sourcePath, failure);
        }
    }

    private static boolean isProvidedRuntime(String runtime) {
        return runtime != null && runtime.startsWith("provided");
    }

    /** Directory Lambda's real init system scans for extension binaries. */
    private static final String EXTENSIONS_DIR = "/opt/extensions";

    /**
     * How long a launch waits for extensions to call {@code /extension/register} before giving up
     * and serving invocations without them. Real AWS allows extensions the function's full init
     * budget (10s); this is deliberately shorter, since exceeding it here is non-fatal and the
     * cost of the wait is paid on every cold start of a function that has extensions.
     */
    private static final long EXTENSION_REGISTRATION_TIMEOUT_MS = 5000;

    /**
     * Discovers binaries under {@link #EXTENSIONS_DIR} inside the (already started) container and
     * launches each as a detached process — the piece of real AWS's init system (which starts every
     * registered extension alongside the runtime) that Floci otherwise has no equivalent for. Uses
     * {@code docker exec} rather than baking a wrapper entrypoint into the image, since the image is
     * user-supplied and unmodified.
     *
     * <p>Extensions inherit the container's env (already set on the container itself, including
     * {@code AWS_LAMBDA_RUNTIME_API}) automatically — {@code docker exec} does not need it re-supplied.
     * Best-effort throughout: a function with no {@code /opt/extensions} directory (the common case)
     * must be a silent no-op, and a failure launching one extension must not prevent the container
     * (or other extensions) from running.
     */
    /** Where a container's output is sent: the CloudWatch log group/stream and its region. */
    private record LogDestination(String logGroup, String logStream, String region) { }

    private void launchExtensions(DockerClient dockerClient, String containerId, String functionName,
                                  RuntimeApiServer runtimeApiServer, LogDestination logDestination) {
        List<String> extensionNames = listExtensionBinaries(dockerClient, containerId, functionName);
        // Arm the readiness barrier before starting any extension process, so the latch already
        // exists when the first one starts polling /extension/event/next — otherwise a
        // fast-starting extension could become ready before there is anything to count it down.
        runtimeApiServer.expectExtensions(extensionNames.size());
        for (String name : extensionNames) {
            try {
                String path = EXTENSIONS_DIR + "/" + name;
                var create = dockerClient.execCreateCmd(containerId)
                        .withCmd(path)
                        .withAttachStdout(true)
                        .withAttachStderr(true);
                String execId = create.exec().getId();
                // Detached: an extension runs for the container's whole lifetime, so this must not
                // block the launch waiting for it to exit.
                //
                // The callback forwards the exec's output to the function's CloudWatch log group,
                // the same destination the container's PID 1 output goes to. That has to be done
                // explicitly: `docker logs` only covers PID 1, so an exec's stream never reaches
                // the container log and an observability extension's output would vanish entirely.
                // Draining is also required in its own right — an unread exec pipe fills up and
                // stalls the extension process.
                dockerClient.execStartCmd(execId).exec(logStreamer.execLogCallback(
                        logDestination.logGroup(), logDestination.logStream(), logDestination.region(),
                        "lambda:" + functionName + ":" + name));
                LOG.infov("Launched extension {0} for function {1} (container {2})",
                        name, functionName, containerId);
            } catch (Exception e) {
                LOG.warnv(e, "Failed to launch extension {0} for function {1}; continuing without it",
                        name, functionName);
            }
        }
    }

    /**
     * Waits for every launched extension to become init-ready — its first
     * {@code /extension/event/next}, the point at which AWS considers an extension initialised —
     * before the container is handed to the caller for its first invocation.
     *
     * <p>Best-effort by design: a timeout logs and proceeds rather than failing the launch. An
     * extension that is slow or crashes on startup should degrade to "invocations run without it"
     * — the same outcome as before this barrier existed — not take the whole function down. A
     * genuinely broken extension reports {@code init/error} instead, which condemns the
     * environment through {@code RuntimeApiServer}'s fault path.
     */
    private void awaitExtensionReadiness(RuntimeApiServer runtimeApiServer, String functionName) {
        try {
            if (!runtimeApiServer.awaitExtensionsReady(EXTENSION_REGISTRATION_TIMEOUT_MS)) {
                LOG.warnv("Not all extensions for function {0} became ready within {1}ms; "
                                + "continuing without them",
                        functionName, String.valueOf(EXTENSION_REGISTRATION_TIMEOUT_MS));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.debugv("Interrupted waiting for extensions of function {0} to become ready", functionName);
        }
    }

    /**
     * Lists file names directly under {@link #EXTENSIONS_DIR} inside the container, or an empty
     * list if the directory doesn't exist or the probe fails (most functions have no extensions).
     *
     * <p>Uses Docker's archive API ({@code copyArchiveFromContainerCmd}) rather than {@code exec}ing
     * a shell/{@code find}/{@code basename} pipeline inside the container: a minimal or distroless
     * function image can have a valid extension binary under {@code /opt/extensions} without any of
     * those utilities present, which would silently look like "no extensions" to a shell-based probe.
     * The archive API only talks to the Docker daemon, so it works regardless of the image contents.
     */
    private List<String> listExtensionBinaries(DockerClient dockerClient, String containerId, String functionName) {
        try (InputStream tarStream = dockerClient.copyArchiveFromContainerCmd(containerId, EXTENSIONS_DIR).exec();
             TarArchiveInputStream tar = new TarArchiveInputStream(tarStream)) {

            List<String> names = new ArrayList<>();
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!tar.canReadEntryData(entry) || entry.isDirectory()) {
                    continue;
                }
                // Docker wraps the requested directory itself as a leading path component (e.g.
                // "extensions/lambda-adapter"); only direct children count as extension binaries,
                // matching the real init system's non-recursive scan of the directory.
                String name = entry.getName();
                int slash = name.indexOf('/');
                if (slash < 0 || name.indexOf('/', slash + 1) >= 0) {
                    continue;
                }
                if ((entry.getMode() & 0111) == 0) {
                    continue;
                }
                names.add(name.substring(slash + 1));
            }
            return names;
        } catch (NotFoundException e) {
            return List.of();
        } catch (Exception e) {
            LOG.debugv("Could not list {0} for function {1} ({2}); assuming no extensions",
                    EXTENSIONS_DIR, functionName, e.getMessage());
            return List.of();
        }
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
    public static Optional<Path> resolveFlociCaCertPath(boolean tlsEnabled, Optional<String> userCertPath,
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
    public static List<String> flociCaEnv(Optional<Path> caCert) {
        if (caCert.isEmpty()) {
            return List.of();
        }
        return List.of(
                "NODE_EXTRA_CA_CERTS=" + FLOCI_CA_CONTAINER_PATH,
                "AWS_CA_BUNDLE=" + FLOCI_CA_CONTAINER_PATH);
    }

    private static String extractRegionFromArn(String arn, String defaultRegion) {
        return AwsArnUtils.regionOrDefault(arn, defaultRegion);
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
