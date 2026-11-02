package io.github.hectorvent.floci.testing;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;

/** One EMR Serverless REST JSON request, signed for a named Region. */
public final class EmrServerlessRequests {

    private static final Set<String> FINAL_JOB_RUN_STATES = Set.of("SUCCESS", "FAILED", "CANCELLED");
    private static final Duration JOB_RUN_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private EmrServerlessRequests() {}

    public static RequestSpecification emrServerless(String region) {
        return given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=TEST/20260819/" + region
                        + "/emr-serverless/aws4_request, SignedHeaders=host;x-amz-date, Signature=test")
                .contentType("application/json");
    }

    /** Creates a SPARK application on {@code releaseLabel} in {@code region} and returns its id. */
    public static String createSparkApplication(String region, String releaseLabel) {
        return emrServerless(region)
                .body("""
                        {"name": "app-%s", "releaseLabel": "%s", "type": "SPARK", "clientToken": "%s"}
                        """.formatted(UUID.randomUUID(), releaseLabel, UUID.randomUUID()))
                .when().post("/applications")
                .then().statusCode(200).extract().path("applicationId");
    }

    /** Starts a job run of {@code body} on the application and returns its id. */
    public static String startJobRun(String region, String applicationId, String body) {
        return emrServerless(region).body(body)
                .when().post("/applications/" + applicationId + "/jobruns")
                .then().statusCode(200).extract().path("jobRunId");
    }

    /**
     * Polls GetJobRun until the run is SUCCESS, FAILED or CANCELLED and returns that response, whatever the state:
     * asserting which one is the caller's business. A GetJobRun that fails ends the wait there. A run in the
     * {@code docker} runner mode starts a container of the release's image, so it is given minutes rather than
     * seconds.
     */
    public static Response awaitJobRun(String region, String applicationId, String jobRunId) {
        return await("job run " + jobRunId + " to end")
                .atMost(JOB_RUN_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> emrServerless(region).when()
                                .get("/applications/" + applicationId + "/jobruns/" + jobRunId)
                                .then().statusCode(200).extract().response(),
                        response -> FINAL_JOB_RUN_STATES.contains(response.jsonPath().getString("jobRun.state")));
    }

    /**
     * The tagging path for an ARN, sent as the SDK sends it: one path segment with ':' and '/'
     * percent-encoded. Send it with {@code urlEncodingEnabled(false)}, or it is encoded twice.
     */
    public static String tagsPath(String arn) {
        return "/tags/" + URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }
}
