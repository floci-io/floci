package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.lambda.model.ContainerState;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
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

    private static final DateTimeFormatter LOG_STREAM_DATE_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ImageResolver imageResolver;
    private final RuntimeApiServerFactory runtimeApiServerFactory;
    private final DockerHostResolver dockerHostResolver;
    private final EmulatorConfig config;
    private final EcrRegistryManager ecrRegistryManager;

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
                             EcrRegistryManager ecrRegistryManager) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.imageResolver = imageResolver;
        this.runtimeApiServerFactory = runtimeApiServerFactory;
        this.dockerHostResolver = dockerHostResolver;
        this.config = config;
        this.ecrRegistryManager = ecrRegistryManager;
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
        int port = ecrRegistryManager.effectivePort();
        String rewritten = account + ".dkr.ecr." + region + ".localhost:" + port + "/" + repoAndTag;
        LOG.infov("Rewriting ECR image URI {0} -> {1}", image, rewritten);
        return rewritten;
    }

    public ContainerHandle launch(LambdaFunction fn) {
        LOG.infov("Launching container for function: {0}", fn.getFunctionName());

        // For Zip functions, verify code exists before allocating any resources.
        if (fn.getCodeLocalPath() != null) {
            Path codePath = Path.of(fn.getCodeLocalPath());
            if (!Files.exists(codePath)) {
                throw new RuntimeException("Code directory not found for function '"
                        + fn.getFunctionName() + "': " + fn.getCodeLocalPath()
                        + " (function may have been deleted or updated)");
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

        // Build env vars
        List<String> env = new ArrayList<>();
        env.add("AWS_LAMBDA_RUNTIME_API=" + runtimeApiEndpoint);
        env.add("AWS_LAMBDA_FUNCTION_NAME=" + fn.getFunctionName());
        env.add("AWS_LAMBDA_FUNCTION_MEMORY_SIZE=" + fn.getMemorySize());
        env.add("AWS_LAMBDA_FUNCTION_TIMEOUT=" + fn.getTimeout());
        env.add("AWS_LAMBDA_FUNCTION_VERSION=$LATEST");
        if (fn.getHandler() != null && !fn.getHandler().isBlank()) {
            env.add("_HANDLER=" + fn.getHandler());
        }
        env.add("AWS_DEFAULT_REGION=us-east-1");
        env.add("AWS_REGION=us-east-1");
        if (fn.getEnvironment() != null) {
            fn.getEnvironment().forEach((k, v) -> env.add(k + "=" + v));
        }

        // Give the container a human-readable name
        String shortId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String containerName = "floci-" + fn.getFunctionName() + "-" + shortId;

        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withEnv(env)
                .withMemoryMb(fn.getMemorySize())
                .withDockerNetwork(config.services().lambda().dockerNetwork())
                .withHostDockerInternalOnLinux()
                .withLogRotation();

        specBuilder.withEmbeddedDns();

        // For Image package type without an explicit handler, omit CMD so the image's own CMD is used
        if (fn.getHandler() != null && !fn.getHandler().isBlank()) {
            specBuilder.withCmd(fn.getHandler());
        }

        ContainerSpec spec = specBuilder.build();

        // Create container without starting — provided.* runtimes exec
        // /var/runtime/bootstrap on start, so code must be copied first.
        String containerId = lifecycleManager.create(spec);
        LOG.infov("Created container {0} for function {1}", containerId, fn.getFunctionName());

        // Copy code into container via Docker API tar stream (works inside Docker too)
        DockerClient dockerClient = lifecycleManager.getDockerClient();
        if (fn.getCodeLocalPath() != null) {
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

        // Now start the container with code in place
        lifecycleManager.startCreated(containerId, spec);

        ContainerHandle handle = new ContainerHandle(containerId, fn.getFunctionName(), runtimeApiServer, ContainerState.WARM);

        // Determine CloudWatch Logs destination for this container instance
        String cwLogGroup = "/aws/lambda/" + fn.getFunctionName();
        String region = extractRegionFromArn(fn.getFunctionArn());
        String cwLogStream = LOG_STREAM_DATE_FMT.format(LocalDate.now()) + "/[$LATEST]" + shortId;

        // Attach log streaming
        Closeable logHandle = logStreamer.attach(
                containerId, cwLogGroup, cwLogStream, region, "lambda:" + fn.getFunctionName());
        handle.setLogStream(logHandle);

        return handle;
    }

    public void stop(ContainerHandle handle) {
        LOG.infov("Stopping container {0}", handle.getContainerId());
        handle.setState(ContainerState.STOPPED);

        handle.getRuntimeApiServer().stop();
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

    private static String extractRegionFromArn(String arn) {
        if (arn == null) {
            return "us-east-1";
        }
        String[] parts = arn.split(":");
        return parts.length >= 4 && !parts[3].isEmpty() ? parts[3] : "us-east-1";
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
