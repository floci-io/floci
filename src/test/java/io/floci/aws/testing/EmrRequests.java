package io.floci.aws.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.services.emr.EmrService;
import io.floci.aws.services.emr.model.EmrCluster;
import io.restassured.response.Response;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;

/** One EMR JSON-protocol call, in the default Region or a named one. */
public final class EmrRequests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> UNFINISHED_STEP_STATES = Set.of("PENDING", "RUNNING");
    private static final Set<String> TERMINATED_CLUSTER_STATES = Set.of("TERMINATED", "TERMINATED_WITH_ERRORS");
    private static final Duration STEP_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private EmrRequests() {}

    public static Response emr(String action, String body) {
        return given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "ElasticMapReduce." + action)
                .body(body).when().post("/");
    }

    public static Response emr(String region, String action, String body) {
        return given().contentType("application/x-amz-json-1.1")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260215/" + region
                        + "/elasticmapreduce/aws4_request, SignedHeaders=host, Signature=abc")
                .header("X-Amz-Target", "ElasticMapReduce." + action)
                .body(body).when().post("/");
    }

    /**
     * Adds one {@code CONTINUE} step named {@code name} to the cluster, running {@code jar} with {@code args}, and returns
     * its id.
     */
    public static String addStep(String clusterId, String name, String jar, String... args) {
        ObjectNode request = JSON.createObjectNode().put("JobFlowId", clusterId);
        ObjectNode step = request.putArray("Steps").addObject().put("Name", name).put("ActionOnFailure", "CONTINUE");
        ObjectNode hadoopJarStep = step.putObject("HadoopJarStep").put("Jar", jar);
        List.of(args).forEach(hadoopJarStep.putArray("Args")::add);
        return emr("AddJobFlowSteps", request.toString()).then().statusCode(200).extract().jsonPath()
                .getString("StepIds[0]");
    }

    /**
     * Polls DescribeStep until the step leaves PENDING and RUNNING and returns that response, whatever the state it
     * ended in: asserting which one is the caller's business. A DescribeStep that fails ends the wait there. A step in
     * the {@code docker} runner mode starts a container of the release's image, so it is given minutes rather than
     * seconds.
     */
    public static Response awaitStep(String clusterId, String stepId) {
        return await("step " + stepId + " to leave PENDING and RUNNING")
                .atMost(STEP_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> emr("DescribeStep",
                                "{\"ClusterId\":\"" + clusterId + "\",\"StepId\":\"" + stepId + "\"}")
                                .then().statusCode(200).extract().response(),
                        response -> !UNFINISHED_STEP_STATES.contains(
                                response.jsonPath().getString("Step.Status.State")));
    }

    /**
     * Ends a test's default-account/default-region cluster and waits for its owned storage and bootstrap image
     * to be released. TerminateJobFlows accepts the request before that background cleanup has completed.
     */
    public static void terminateClusterAndAwaitCleanup(String clusterId, EmrService service) {
        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + clusterId + "\"]}").then().statusCode(200);
        await("cluster " + clusterId + " to terminate and release its storage and bootstrap image")
                .atMost(CLEANUP_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> {
                    EmrCluster cluster = service.describeCluster(clusterId);
                    return TERMINATED_CLUSTER_STATES.contains(cluster.getState())
                            && !cluster.isStorageCreated() && cluster.getBootstrapImage() == null;
                });
    }
}
