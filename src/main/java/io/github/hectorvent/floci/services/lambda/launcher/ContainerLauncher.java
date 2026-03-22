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
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
        // Skip this check for hot-reload functions — the codeLocalPath is a Docker
        // HOST path (for bind mounts) that isn’t visible inside this container.
        if (fn.getCodeLocalPath() != null && !fn.isHotReload()) {
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

        // Hot-reload: bind-mount host code directory directly into the container.
        // The codeLocalPath is a Docker HOST path — the Docker daemon resolves it
        // on the host filesystem when creating the bind mount. This means file edits
        // on the host are instantly visible inside the Lambda container.
        if (fn.isHotReload() && fn.getCodeLocalPath() != null) {
            hostConfig.withBinds(new Bind(
                    fn.getCodeLocalPath(),
                    new Volume(TASK_DIR),
                    com.github.dockerjava.api.model.AccessMode.ro));
            LOG.infov("Hot-reload bind mount: {0} -> {1}", fn.getCodeLocalPath(), TASK_DIR);
        }

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
        CreateContainerCmd createCmd = dockerClient.createContainerCmd(image)
                .withName(containerName)
                .withEnv(env)
                .withCmd(fn.getHandler())
                .withHostConfig(hostConfig);

        CreateContainerResponse container = createCmd.exec();
        String containerId = container.getId();
        LOG.infov("Created container {0} for function {1}", containerId, fn.getFunctionName());

        // Copy code into container via Docker API tar stream (works inside Docker too).
        // For hot-reload functions the code is already bind-mounted, so skip the TAR copy.
        if (!fn.isHotReload()) {
        if (fn.getCodeLocalPath() != null) {
            Path codePath = Path.of(fn.getCodeLocalPath());
            try (java.io.PipedOutputStream pos = new java.io.PipedOutputStream();
                 java.io.PipedInputStream pis = new java.io.PipedInputStream(pos)) {
                
                // Use a separate thread to feed the pipe to avoid deadlock
                new Thread(() -> {
                    try (pos) {
                        createTar(codePath, pos);
                    } catch (IOException e) {
                        LOG.errorv("Failed to stream tar for function {0}: {1}", fn.getFunctionName(), e.getMessage());
                    }
                }, "tar-streamer-" + fn.getFunctionName()).start();

                dockerClient.copyArchiveToContainerCmd(containerId)
                        .withRemotePath(TASK_DIR)
                        .withTarInputStream(pis)
                        .exec();
                LOG.debugv("Copied code into container {0} at {1}", containerId, TASK_DIR);
            } catch (java.nio.file.NoSuchFileException e) {
                // Race condition: code directory deleted between the pre-launch check and the copy
                // (e.g. function deleted while the image was being pulled).
                // Abort cleanly — remove the container and stop the Runtime API server.
                LOG.debugv("Code directory removed during image pull for function {0}, aborting launch",
                        fn.getFunctionName());
                try { dockerClient.removeContainerCmd(containerId).withForce(true).exec(); } catch (Exception ignored) {}
                runtimeApiServer.stop();
                throw new RuntimeException("Function code was deleted during container launch: "
                        + fn.getFunctionName());
            } catch (Exception e) {
                LOG.warnv("Failed to copy code into container {0}: {1}", containerId, e.getMessage());
            }
        }
        } // end !hotReload

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

    private static String extractRegionFromArn(String arn) {
        if (arn == null) {
            return "us-east-1";
        }
        String[] parts = arn.split(":");
        return parts.length >= 4 && !parts[3].isEmpty() ? parts[3] : "us-east-1";
    }

    /**
     * Creates a minimal POSIX TAR archive from all files in {@code sourceDir}.
     * Streams content to the OutputStream to avoid loading entire files into memory.
     */
    private static void createTar(Path sourceDir, java.io.OutputStream out) throws IOException {
        try (var stream = Files.walk(sourceDir)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(path)) continue;

                String entryName = sourceDir.relativize(path).toString();
                long size = Files.size(path);

                writeTarHeader(out, entryName, size);
                try (java.io.InputStream fis = Files.newInputStream(path)) {
                    fis.transferTo(out);
                }
                
                // Pad data to 512-byte boundary
                int pad = (int) ((512 - (size % 512)) % 512);
                if (pad > 0) out.write(new byte[pad]);
            }
        }

        // End-of-archive: two 512-byte zero blocks
        out.write(new byte[1024]);
    }

    private static void writeTarHeader(java.io.OutputStream out, String name, long size) throws IOException {
        byte[] header = new byte[512];

        // Filename (max 100 bytes)
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 99));

        // File mode: 0000755
        putOctal(header, 100, 8, 0755);
        // UID / GID: 0
        putOctal(header, 108, 8, 0);
        putOctal(header, 116, 8, 0);
        // File size
        putOctal(header, 124, 12, size);
        // Modification time
        putOctal(header, 136, 12, System.currentTimeMillis() / 1000);
        // Type flag: '0' = regular file
        header[156] = '0';

        // Checksum: fill checksum field with spaces first, then compute
        for (int i = 148; i < 156; i++) header[i] = ' ';
        int checksum = 0;
        for (byte b : header) checksum += b & 0xFF;
        putOctal(header, 148, 8, checksum);

        out.write(header);
    }

    /** Writes {@code value} as a null-terminated octal string into {@code buf[offset..offset+length)}. */
    private static void putOctal(byte[] buf, int offset, int length, long value) {
        String octal = String.format("%0" + (length - 1) + "o", value);
        byte[] bytes = octal.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, buf, offset, Math.min(bytes.length, length - 1));
        buf[offset + length - 1] = 0;
    }
}
