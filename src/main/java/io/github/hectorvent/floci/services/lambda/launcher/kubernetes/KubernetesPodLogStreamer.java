package io.github.hectorvent.floci.services.lambda.launcher.kubernetes;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Streams a Lambda pod's runtime-container logs to CloudWatch Logs and the console,
 * matching what {@link ContainerLogStreamer} does for Docker containers. The CloudWatch
 * write path is delegated to {@code ContainerLogStreamer} so both executors produce
 * identical log groups, streams and events.
 */
@ApplicationScoped
public class KubernetesPodLogStreamer {

    private static final Logger LOG = Logger.getLogger(KubernetesPodLogStreamer.class);

    private final KubernetesClient client;
    private final ContainerLogStreamer cloudWatchWriter;

    @Inject
    public KubernetesPodLogStreamer(KubernetesClient client, ContainerLogStreamer cloudWatchWriter) {
        this.client = client;
        this.cloudWatchWriter = cloudWatchWriter;
    }

    /** CloudWatch log stream name for one execution environment, same shape as the docker path. */
    public String logStreamName(String shortId) {
        return cloudWatchWriter.generateLogStreamName("[$LATEST]" + shortId);
    }

    public Closeable attach(String namespace, String podName, String logGroup,
                            String logStream, String region, String logPrefix) {
        cloudWatchWriter.ensureLogGroupAndStream(logGroup, logStream, region);

        var watch = client.pods().inNamespace(namespace).withName(podName)
                .inContainer("runtime")
                .watchLog();

        // Virtual thread: one blocking reader per warm pod is near-free this way.
        var reader = Thread.ofVirtual().name("lambda-pod-logs-" + podName).start(() -> {
            try (var lines = new BufferedReader(
                    new InputStreamReader(watch.getOutput(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    // Match the docker log path: trim trailing whitespace and drop blank
                    // lines so both executors write identical CloudWatch events.
                    var trimmed = line.stripTrailing();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    LOG.infov("[{0}] {1}", logPrefix, trimmed);
                    cloudWatchWriter.streamToCloudWatchLogs(logGroup, logStream, region, trimmed);
                }
            } catch (IOException e) {
                // Normal on pod deletion: the log stream just ends.
                LOG.debugv("Log stream for pod {0} ended: {1}", podName, e.getMessage());
            }
        });

        return () -> {
            try {
                watch.close();
            } catch (Exception e) {
                LOG.debugv("Closing log watch for pod {0} failed: {1}", podName, e.getMessage());
            }
            reader.interrupt();
        };
    }
}
