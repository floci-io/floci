package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.lambda.model.ContainerState;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServer;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServerFactory;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private final DockerClient dockerClient;
    private final ImageCacheService imageCacheService;
    private final ImageResolver imageResolver;
    private final RuntimeApiServerFactory runtimeApiServerFactory;
    private final DockerHostResolver dockerHostResolver;
    private final EmulatorConfig config;
    private final CloudWatchLogsService cloudWatchLogsService;

    @Inject
    public ContainerLauncher(DockerClient dockerClient,
                              ImageCacheService imageCacheService,
                              ImageResolver imageResolver,
                              RuntimeApiServerFactory runtimeApiServerFactory,
                              DockerHostResolver dockerHostResolver,
                              EmulatorConfig config,
                              CloudWatchLogsService cloudWatchLogsService) {
        this.dockerClient = dockerClient;
        this.imageCacheService = imageCacheService;
        this.imageResolver = imageResolver;
        this.runtimeApiServerFactory = runtimeApiServerFactory;
        this.dockerHostResolver = dockerHostResolver;
        this.config = config;
        this.cloudWatchLogsService = cloudWatchLogsService;
    }

    public ContainerHandle launch(LambdaFunction fn) {
        LOG.infov("Launching container for function: {0}", fn.getFunctionName());

        // For Zip functions, verify code exists before allocating any resources.
        // Without this check, a container could start with an empty /var/task if the
        // function was deleted (or code was replaced) between the invocation being
        // enqueued and the container being launched.
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

        // Ensure image is available locally
        imageCacheService.ensureImageExists(image);

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
        env.add("_HANDLER=" + fn.getHandler());
        env.add("AWS_DEFAULT_REGION=us-east-1");
        env.add("AWS_REGION=us-east-1");
        if (fn.getEnvironment() != null) {
            fn.getEnvironment().forEach((k, v) -> env.add(k + "=" + v));
        }

        // Build host config — memory limit only, no bind mount
        // (code is copied in via Docker API tar-copy after container creation)
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withMemory(fn.getMemorySize() * 1024 * 1024L);

        // Attach to a specific Docker network if configured
        config.services().lambda().dockerNetwork()
                .or(() -> config.services().dockerNetwork())
                .ifPresent(network -> {
                    if (!network.isBlank()) {
                        hostConfig.withNetworkMode(network);
                        LOG.debugv("Attaching Lambda container to network: {0}", network);
                    }
                });

        // Give the container a human-readable name so it is identifiable in Docker Desktop
        String shortId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String containerName = "floci-" + fn.getFunctionName() + "-" + shortId;

        // Create container — CMD must be the handler name (Lambda entrypoint requires it as first arg)
        // For Image package type without an explicit handler, omit CMD so the image's own CMD is used.
        CreateContainerCmd createCmd = dockerClient.createContainerCmd(image)
                .withName(containerName)
                .withEnv(env)
                .withHostConfig(hostConfig);
        if (fn.getHandler() != null && !fn.getHandler().isBlank()) {
            createCmd.withCmd(fn.getHandler());
        }

        CreateContainerResponse container = createCmd.exec();
        String containerId = container.getId();
        LOG.infov("Created container {0} for function {1}", containerId, fn.getFunctionName());

        // Copy code into container via Docker API tar stream (works inside Docker too)
        if (fn.getCodeLocalPath() != null) {
            Path codePath = Path.of(fn.getCodeLocalPath());
            
            // 1. Always copy all code to /var/task (TASK_DIR)
            copyDirToContainer(containerId, codePath, TASK_DIR, fn.getFunctionName());

            // 2. For provided runtimes, also copy the 'bootstrap' file to /var/runtime (RUNTIME_DIR)
            // matching real AWS Lambda behavior where /var/runtime/bootstrap is the entry point.
            if (isProvidedRuntime(fn.getRuntime())) {
                Path bootstrapPath = codePath.resolve("bootstrap");
                if (Files.exists(bootstrapPath)) {
                    copyFileToContainer(containerId, bootstrapPath, RUNTIME_DIR, "bootstrap", fn.getFunctionName());
                } else {
                    LOG.warnv("Provided runtime function {0} is missing 'bootstrap' file in {1}", 
                            fn.getFunctionName(), fn.getCodeLocalPath());
                }
            }
        }

        // Start container
        dockerClient.startContainerCmd(containerId).exec();
        LOG.infov("Started container {0}", containerId);

        ContainerHandle handle = new ContainerHandle(containerId, fn.getFunctionName(), runtimeApiServer, ContainerState.WARM);

        // Determine CloudWatch Logs destination for this container instance
        String cwLogGroup = "/aws/lambda/" + fn.getFunctionName();
        String region = extractRegionFromArn(fn.getFunctionArn());
        String cwLogStream = LOG_STREAM_DATE_FMT.format(LocalDate.now()) + "/[$LATEST]" + shortId;
        ensureLogGroupAndStream(cwLogGroup, cwLogStream, region);

        // Stream container stdout/stderr to the emulator logger AND to CloudWatch Logs
        try {
            ResultCallback.Adapter<Frame> logCallback = dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true)
                    .withTimestamps(false)
                    .exec(new ResultCallback.Adapter<>() {
                        @Override
                        public void onNext(Frame frame) {
                            String line = new String(frame.getPayload(), StandardCharsets.UTF_8).stripTrailing();
                            if (!line.isEmpty()) {
                                LOG.infov("[lambda:{0}] {1}", fn.getFunctionName(), line);
                                forwardToCloudWatchLogs(cwLogGroup, cwLogStream, region, line);
                            }
                        }
                    });
            handle.setLogStream(logCallback);
        } catch (Exception e) {
            LOG.warnv("Could not attach log stream for container {0}: {1}", containerId, e.getMessage());
        }

        return handle;
    }

    public void stop(ContainerHandle handle) {
        LOG.infov("Stopping container {0}", handle.getContainerId());
        handle.setState(ContainerState.STOPPED);

        // Close log stream first so the streaming thread exits cleanly
        if (handle.getLogStream() != null) {
            try { handle.getLogStream().close(); } catch (Exception ignored) {}
        }

        try {
            dockerClient.stopContainerCmd(handle.getContainerId()).withTimeout(5).exec();
        } catch (Exception e) {
            LOG.warnv("Error stopping container {0}: {1}", handle.getContainerId(), e.getMessage());
        }

        try {
            dockerClient.removeContainerCmd(handle.getContainerId()).withForce(true).exec();
        } catch (Exception e) {
            LOG.warnv("Error removing container {0}: {1}", handle.getContainerId(), e.getMessage());
        }

        handle.getRuntimeApiServer().stop();
    }

    private void ensureLogGroupAndStream(String logGroup, String logStream, String region) {
        try {
            cloudWatchLogsService.createLogGroup(logGroup, null, null, region);
        } catch (AwsException ignored) {
            // Already exists — that's fine
        } catch (Exception e) {
            LOG.warnv("Could not create CW log group {0}: {1}", logGroup, e.getMessage());
        }
        try {
            cloudWatchLogsService.createLogStream(logGroup, logStream, region);
        } catch (AwsException ignored) {
            // Already exists — that's fine
        } catch (Exception e) {
            LOG.warnv("Could not create CW log stream {0}/{1}: {2}", logGroup, logStream, e.getMessage());
        }
    }

    private void forwardToCloudWatchLogs(String logGroup, String logStream, String region, String line) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("timestamp", System.currentTimeMillis());
            event.put("message", line);
            cloudWatchLogsService.putLogEvents(logGroup, logStream, List.of(event), region);
        } catch (Exception e) {
            LOG.debugv("Could not forward log line to CloudWatch Logs: {0}", e.getMessage());
        }
    }

    private void copyDirToContainer(String containerId, Path sourceDir, String remotePath, String functionName) {
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

    private void copyFileToContainer(String containerId, Path sourceFile, String remotePath, String entryName, String functionName) {
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
