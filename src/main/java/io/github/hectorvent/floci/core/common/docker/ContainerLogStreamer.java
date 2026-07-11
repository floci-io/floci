package io.github.hectorvent.floci.core.common.docker;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Streams Docker container logs to both the Floci console logger and CloudWatch Logs.
 * Consolidates the log streaming pattern used across container managers.
 */
@ApplicationScoped
public class ContainerLogStreamer {

    private static final Logger LOG = Logger.getLogger(ContainerLogStreamer.class);
    private static final DateTimeFormatter LOG_STREAM_DATE_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final DockerClient dockerClient;
    private final CloudWatchLogsService cloudWatchLogsService;

    @Inject
    public ContainerLogStreamer(DockerClient dockerClient, CloudWatchLogsService cloudWatchLogsService) {
        this.dockerClient = dockerClient;
        this.cloudWatchLogsService = cloudWatchLogsService;
    }

    /**
     * Attaches a log stream to a container and forwards logs to CloudWatch Logs.
     * Returns a Closeable handle that should be closed when the container is stopped.
     *
     * @param containerId Docker container ID
     * @param logGroup CloudWatch log group name (e.g., "/aws/lambda/myFunction")
     * @param logStream CloudWatch log stream name (e.g., "2024/01/15/[$LATEST]abc123")
     * @param region AWS region for CloudWatch Logs
     * @param logPrefix prefix for console logging (e.g., "lambda:myFunction")
     * @return Closeable handle to stop the log stream
     */
    public Closeable attach(String containerId, String logGroup, String logStream,
                            String region, String logPrefix) {
        ensureLogGroupAndStream(logGroup, logStream, region);

        try {
            return dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true)
                    .withTimestamps(false)
                    .exec(new ResultCallback.Adapter<>() {
                        @Override
                        public void onNext(Frame frame) {
                            String line = new String(frame.getPayload(), StandardCharsets.UTF_8).stripTrailing();
                            if (!line.isEmpty()) {
                                LOG.infov("[{0}] {1}", logPrefix, line);
                                forwardToCloudWatchLogs(logGroup, logStream, region, line);
                            }
                        }
                    });
        } catch (Exception e) {
            LOG.warnv("Could not attach log stream for container {0}: {1}", containerId, e.getMessage());
            return null;
        }
    }

    /**
     * Creates a CloudWatch log group and stream if they don't already exist.
     */
    public void ensureLogGroupAndStream(String logGroup, String logStream, String region) {
        try {
            cloudWatchLogsService.createLogGroup(logGroup, null, null, region);
        } catch (AwsException ignored) {
            // Already exists
        } catch (Exception e) {
            LOG.warnv("Could not create CloudWatch log group {0}: {1}", logGroup, e.getMessage());
        }

        try {
            cloudWatchLogsService.createLogStream(logGroup, logStream, region);
        } catch (AwsException ignored) {
            // Already exists
        } catch (Exception e) {
            LOG.warnv("Could not create CloudWatch log stream {0}/{1}: {2}", logGroup, logStream, e.getMessage());
        }
    }

    /**
     * Generates a date-prefixed log stream name in the standard AWS format.
     *
     * @param suffix the suffix to append (e.g., "[$LATEST]abc123" or "containerId")
     * @return log stream name like "2024/01/15/suffix"
     */
    public String generateLogStreamName(String suffix) {
        return LOG_STREAM_DATE_FMT.format(LocalDate.now()) + "/" + suffix;
    }

    public void streamToCloudWatchLogs(String logGroup, String logStream, String region, String line) {
        forwardToCloudWatchLogs(logGroup, logStream, region, line);
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
}
