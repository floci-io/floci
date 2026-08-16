package io.github.hectorvent.floci.services.kinesisanalytics.container;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Thin client for the Apache Flink JobManager REST API, used to deploy an application's JAR and drive
 * its job lifecycle: upload the JAR ({@code POST /jars/upload}), run it ({@code POST /jars/:id/run}),
 * poll job state ({@code GET /jobs/:id}) and cancel it ({@code PATCH /jobs/:id?mode=cancel}).
 *
 * <p>Uses the JDK {@link HttpClient} (as the rest of Floci does). Read-only probes swallow errors and
 * degrade to "not ready" so the readiness poller can retry; the deploy calls surface failures.
 */
@ApplicationScoped
public class FlinkRestClient {

    private static final Logger LOG = Logger.getLogger(FlinkRestClient.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** True once the JobManager REST API answers on {@code /config} (the cluster is up). */
    public boolean isRestUp(String restBase) {
        try {
            HttpResponse<Void> resp = http.send(
                    HttpRequest.newBuilder(URI.create(restBase + "/config"))
                            .timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            // Expected while the JobManager is still booting.
            LOG.debugf("Flink REST %s not up yet: %s", restBase, e.toString());
            return false;
        }
    }

    /** Total task slots the cluster advertises (TaskManagers registered); 0 if the probe fails. */
    public int totalSlots(String restBase) {
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(restBase + "/overview"))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return 0;
            }
            return mapper.readTree(resp.body()).path("slots-total").asInt(0);
        } catch (Exception e) {
            LOG.debugf("Flink /overview probe on %s failed: %s", restBase, e.toString());
            return 0;
        }
    }

    /**
     * Uploads a JAR via {@code POST /jars/upload} (multipart form field {@code jarfile}) and returns
     * the jar id (the basename of the returned {@code filename}), used by {@link #runJob}.
     */
    public String uploadJar(String restBase, byte[] jar) throws IOException, InterruptedException {
        String boundary = "flociFlinkBoundary" + System.nanoTime();
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"jarfile\"; filename=\"application.jar\"\r\n"
                + "Content-Type: application/x-java-archive\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(head.getBytes(StandardCharsets.UTF_8));
        body.write(jar);
        body.write(tail.getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(restBase + "/jars/upload"))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("Flink /jars/upload failed: HTTP " + resp.statusCode() + " " + resp.body());
        }
        String filename = mapper.readTree(resp.body()).path("filename").asText("");
        int slash = filename.lastIndexOf('/');
        return slash >= 0 ? filename.substring(slash + 1) : filename;
    }

    /** Runs an uploaded jar via {@code POST /jars/:id/run} and returns the assigned Flink job id. */
    public String runJob(String restBase, String jarId, int parallelism) throws IOException, InterruptedException {
        String requestBody = "{\"parallelism\":" + Math.max(1, parallelism) + "}";
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(restBase + "/jars/" + jarId + "/run"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("Flink /jars/" + jarId + "/run failed: HTTP " + resp.statusCode()
                    + " " + resp.body());
        }
        return mapper.readTree(resp.body()).path("jobid").asText(null);
    }

    /** Current job state (e.g. {@code RUNNING}, {@code FINISHED}, {@code FAILED}); null if unknown. */
    public String jobState(String restBase, String jobId) {
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(restBase + "/jobs/" + jobId))
                            .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }
            return mapper.readTree(resp.body()).path("state").asText(null);
        } catch (Exception e) {
            LOG.debugf("Flink /jobs/%s probe on %s failed: %s", jobId, restBase, e.toString());
            return null;
        }
    }

    /** Best-effort cancel via {@code PATCH /jobs/:id?mode=cancel}. */
    public void cancelJob(String restBase, String jobId) {
        try {
            http.send(
                    HttpRequest.newBuilder(URI.create(restBase + "/jobs/" + jobId + "?mode=cancel"))
                            .timeout(Duration.ofSeconds(10))
                            .method("PATCH", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            // Best-effort: the container is about to be removed anyway.
            LOG.debugf("Flink cancel of job %s on %s failed: %s", jobId, restBase, e.toString());
        }
    }

    /**
     * Triggers a savepoint via {@code POST /jobs/:id/savepoints} and returns the async operation's
     * {@code request-id}, used to poll completion via {@link #savepointStatus}.
     */
    public String triggerSavepoint(String restBase, String jobId, String targetDirectory)
            throws IOException, InterruptedException {
        String requestBody = "{\"target-directory\":\"" + targetDirectory + "\",\"cancel-job\":false}";
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(restBase + "/jobs/" + jobId + "/savepoints"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 202 && resp.statusCode() != 200) {
            throw new IOException("Flink /jobs/" + jobId + "/savepoints trigger failed: HTTP "
                    + resp.statusCode() + " " + resp.body());
        }
        return mapper.readTree(resp.body()).path("request-id").asText(null);
    }

    /**
     * Polls {@code GET /jobs/:id/savepoints/:requestId}. {@code statusId} is Flink's async-operation
     * status ({@code IN_PROGRESS}/{@code COMPLETED}); null if the probe itself failed (network/parse
     * error, e.g. the JobManager isn't reachable — different from a Flink-reported failure).
     */
    public SavepointStatus savepointStatus(String restBase, String jobId, String requestId) {
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(restBase + "/jobs/" + jobId + "/savepoints/" + requestId))
                            .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return null;
            }
            var root = mapper.readTree(resp.body());
            String statusId = root.path("status").path("id").asText(null);
            var operation = root.path("operation");
            boolean failed = operation.has("failure-cause");
            String location = operation.path("location").asText(null);
            return new SavepointStatus(statusId, location, failed);
        } catch (Exception e) {
            LOG.debugf("Flink savepoint status probe (job %s, request %s) on %s failed: %s",
                    jobId, requestId, restBase, e.toString());
            return null;
        }
    }

    /** {@code statusId}: Flink's async-operation status ({@code IN_PROGRESS}/{@code COMPLETED}).
     *  {@code location}: the savepoint's path once completed successfully. {@code failed}: true when
     *  Flink reports a {@code failure-cause} (the savepoint itself failed, as opposed to still running). */
    public record SavepointStatus(String statusId, String location, boolean failed) {}
}
