package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final ContainerReachableEndpoint reachableEndpoint;

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
                             ContainerReachableEndpoint reachableEndpoint) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.imageResolver = imageResolver;
        this.runtimeApiServerFactory = runtimeApiServerFactory;
        this.dockerHostResolver = dockerHostResolver;
        this.config = config;
        this.ecrRegistryManager = ecrRegistryManager;
        this.layerService = layerService;
        this.reachableEndpoint = reachableEndpoint;
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
        String containerName = "floci-" + fn.getFunctionName() + "-" + shortId;

        // CloudWatch log coordinates — computed here so they can be injected as env vars
        String cwLogGroup  = "/aws/lambda/" + fn.getFunctionName();
        String cwLogStream = LOG_STREAM_DATE_FMT.format(LocalDate.now()) + "/[$LATEST]" + shortId;
        String lambdaRegion = extractRegionFromArn(fn.getFunctionArn(), config.defaultRegion());

        // Floci endpoint reachable from inside the container (shared with the CloudFormation
        // custom-resource provisioner, which uses the same address for ResponseURL callbacks).
        String flociEndpoint = reachableEndpoint.baseUrl();
        String flociHostname = java.net.URI.create(flociEndpoint).getHost();

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
        env.add("AWS_DEFAULT_REGION=" + lambdaRegion);
        env.add("AWS_REGION=" + lambdaRegion);
        Optional<String> awsConfigPath = config.services().lambda().awsConfigPath()
                .filter(s -> !s.isBlank());
        if (awsConfigPath.isPresent()) {
            // ~/.aws will be mounted — don't inject credentials, let SDK discover them.
            // Set explicit file paths so discovery works regardless of container HOME.
            env.add("AWS_SHARED_CREDENTIALS_FILE=/opt/aws-config/credentials");
            env.add("AWS_CONFIG_FILE=/opt/aws-config/config");
        } else {
            // Use Floci's own env vars, fallback to test/test/test
            String ak = System.getenv("AWS_ACCESS_KEY_ID");
            String sk = System.getenv("AWS_SECRET_ACCESS_KEY");
            String st = System.getenv("AWS_SESSION_TOKEN");
            env.add("AWS_ACCESS_KEY_ID=" + (ak != null ? ak : "test"));
            env.add("AWS_SECRET_ACCESS_KEY=" + (sk != null ? sk : "test"));
            env.add("AWS_SESSION_TOKEN=" + (st != null ? st : "test"));
        }
        env.add("FLOCI_HOSTNAME=" + flociHostname);
        env.add("FLOCI_ENDPOINT=" + flociEndpoint);
        env.add("AWS_ENDPOINT_URL=" + flociEndpoint);
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

        if (fn.isHotReload()) {
            specBuilder.withBind(fn.getHotReloadHostPath(), TASK_DIR);
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
        String containerId = lifecycleManager.create(spec);
        LOG.infov("Created container {0} for function {1}", containerId, fn.getFunctionName());

        // Copy code into container via Docker API tar stream (works inside Docker too).
        // Hot-reload functions skip the tar-copy — the bind-mount already wires the host path.
        DockerClient dockerClient = lifecycleManager.getDockerClient();
        if (!fn.isHotReload() && fn.getCodeLocalPath() != null) {
            Path codePath = Path.of(fn.getCodeLocalPath());

            // 1. Always copy all code to /var/task (TASK_DIR)
            copyDirToContainer(dockerClient, containerId, codePath, TASK_DIR, fn.getFunctionName());

            // 2. For provided runtimes, also copy the 'bootstrap' file to /var/runtime (RUNTIME_DIR)
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
        flociCaCert.ifPresent(certPath ->
                copyFileToContainer(dockerClient, containerId, certPath,
                        FLOCI_CA_DIR, FLOCI_CA_FILE_NAME, fn.getFunctionName()));

        // Now start the container with code in place
        lifecycleManager.startCreated(containerId, spec);

        ContainerHandle handle = new ContainerHandle(containerId, fn.getFunctionName(), runtimeApiServer, ContainerState.WARM, fn.isHotReload());

        // Attach log streaming
        Closeable logHandle = logStreamer.attach(
                containerId, cwLogGroup, cwLogStream, lambdaRegion, "lambda:" + fn.getFunctionName());
        handle.setLogStream(logHandle);

        return handle;
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

    private void copyDirToContainer(DockerClient dockerClient, String containerId,
                                    Path sourceDir, String remotePath, String functionName) {
        try (java.io.PipedOutputStream pos = new java.io.PipedOutputStream();
             java.io.PipedInputStream pis = new java.io.PipedInputStream(pos)) {

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
            LOG.warnv("Failed to copy directory {0} into container {1}: {2}", sourceDir, containerId, e.getMessage());
        }
    }

    private void copyFileToContainer(DockerClient dockerClient, String containerId,
                                     Path sourceFile, String remotePath, String entryName, String functionName) {
        try (java.io.PipedOutputStream pos = new java.io.PipedOutputStream();
             java.io.PipedInputStream pis = new java.io.PipedInputStream(pos)) {

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
            LOG.warnv("Failed to copy file {0} into container {1}: {2}", sourceFile, containerId, e.getMessage());
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
     */
    static Optional<Path> resolveFlociCaCertPath(boolean tlsEnabled, Optional<String> userCertPath,
                                                 String persistentPath) {
        if (!tlsEnabled) {
            return Optional.empty();
        }
        Path certPath = userCertPath.filter(s -> !s.isBlank())
                .map(Path::of)
                .orElseGet(() -> Path.of(persistentPath, "tls", SELF_SIGNED_CERT_NAME));
        if (!Files.isReadable(certPath)) {
            LOG.warnv("TLS enabled but Floci CA certificate not readable at {0}; "
                    + "Lambda containers will not trust Floci HTTPS callbacks", certPath);
            return Optional.empty();
        }
        return Optional.of(certPath);
    }

    /**
     * Environment entries that make the container trust Floci's CA. {@code NODE_EXTRA_CA_CERTS}
     * <em>adds</em> to Node's built-in CAs (public TLS still works); the {@code *_CA_BUNDLE} /
     * {@code SSL_CERT_FILE} vars are honored by the AWS SDKs, botocore, requests and OpenSSL CLIs.
     * Returns an empty list when no CA cert is available (TLS off).
     */
    static List<String> flociCaEnv(Optional<Path> caCert) {
        if (caCert.isEmpty()) {
            return List.of();
        }
        return List.of(
                "NODE_EXTRA_CA_CERTS=" + FLOCI_CA_CONTAINER_PATH,
                "AWS_CA_BUNDLE=" + FLOCI_CA_CONTAINER_PATH,
                "SSL_CERT_FILE=" + FLOCI_CA_CONTAINER_PATH,
                "REQUESTS_CA_BUNDLE=" + FLOCI_CA_CONTAINER_PATH);
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
