package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.github.hectorvent.floci.testing.EmrRequests.emr;
import static io.github.hectorvent.floci.testing.StepFunctionsExecutions.historyInRegion;
import static io.github.hectorvent.floci.testing.StepFunctionsExecutions.runInRegion;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code elasticmapreduce:} optimized integrations against Floci's EMR, from a state machine in a
 * Region other than the default, so every call has to reach the clusters of the state machine's own.
 */
@QuarkusTest
class StepFunctionsEmrIntegrationTest {

    private static final String REGION = "eu-west-3";
    private static final String RESOURCE = "arn:aws:states:::elasticmapreduce:";
    private static final String GROUPS_CLUSTER = """
            {"Name": "sfn-emr", "ReleaseLabel": "emr-7.5.0",
             "Instances": {"KeepJobFlowAliveWhenNoSteps": true, "InstanceGroups": [
               {"Name": "Primary", "InstanceRole": "MASTER", "InstanceType": "m5.xlarge", "InstanceCount": 1},
               {"Name": "Core", "InstanceRole": "CORE", "InstanceType": "m5.xlarge", "InstanceCount": 2}]}}
            """;
    private static final String FLEETS_CLUSTER = """
            {"Name": "sfn-emr-fleets", "ReleaseLabel": "emr-7.5.0",
             "Instances": {"KeepJobFlowAliveWhenNoSteps": true, "InstanceFleets": [
               {"Name": "Primary", "InstanceFleetType": "MASTER", "TargetOnDemandCapacity": 1,
                "InstanceTypeConfigs": [{"InstanceType": "m5.xlarge"}]},
               {"Name": "Core", "InstanceFleetType": "CORE", "TargetOnDemandCapacity": 2,
                "InstanceTypeConfigs": [{"InstanceType": "m5.xlarge"}]}]}}
            """;
    private static final String STEP = """
            {"Name": "job", "ActionOnFailure": "CONTINUE",
             "HadoopJarStep": {"Jar": "command-runner.jar", "Args": ["spark-submit", "s3://bucket/job.py"]}}
            """;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aClusterIsCreatedGivenAStepResizedAndTerminated() throws Exception {
        String definition = """
                {"StartAt": "Create", "States": {
                  "Create": {"Type": "Task", "Resource": "%1$screateCluster.sync", "Parameters": %2$s,
                             "ResultPath": "$.cluster", "Next": "Run"},
                  "Run": {"Type": "Task", "Resource": "%1$saddStep.sync",
                          "Parameters": {"ClusterId.$": "$.cluster.ClusterId", "Step": %3$s},
                          "ResultPath": "$.step", "Next": "Queue"},
                  "Queue": {"Type": "Task", "Resource": "%1$saddStep",
                            "Parameters": {"ClusterId.$": "$.cluster.ClusterId", "Step": %3$s},
                            "ResultPath": "$.queued", "Next": "Cancel"},
                  "Cancel": {"Type": "Task", "Resource": "%1$scancelStep",
                             "Parameters": {"ClusterId.$": "$.cluster.ClusterId", "StepId.$": "$.queued.StepId"},
                             "ResultPath": "$.cancel", "Next": "Resize"},
                  "Resize": {"Type": "Task", "Resource": "%1$smodifyInstanceGroupByName",
                             "Parameters": {"ClusterId.$": "$.cluster.ClusterId", "InstanceGroupName": "Core",
                                            "InstanceGroup": {"InstanceCount": 4}},
                             "ResultPath": "$.resize", "Next": "Terminate"},
                  "Terminate": {"Type": "Task", "Resource": "%1$sterminateCluster.sync",
                                "Parameters": {"ClusterId.$": "$.cluster.ClusterId"},
                                "ResultPath": "$.terminated", "End": true}}}
                """.formatted(RESOURCE, GROUPS_CLUSTER, STEP);

        Response execution = run(definition);

        assertEquals("SUCCEEDED", execution.jsonPath().getString("status"), execution.asString());
        JsonNode output = MAPPER.readTree(execution.jsonPath().getString("output"));
        JsonNode cluster = output.path("cluster");
        String clusterId = cluster.path("ClusterId").asText();
        assertEquals(List.of("Cluster", "ClusterId"), fieldNames(cluster));
        assertEquals(clusterId, cluster.path("Cluster").path("Id").asText());
        assertEquals("WAITING", cluster.path("Cluster").path("Status").path("State").asText());
        JsonNode step = output.path("step");
        assertEquals(List.of("Step", "StepId"), fieldNames(step));
        assertEquals(step.path("StepId").asText(), step.path("Step").path("Id").asText());
        assertEquals("COMPLETED", step.path("Step").path("Status").path("State").asText());
        String queuedId = output.path("queued").path("StepId").asText();
        assertEquals(List.of("StepId"), fieldNames(output.path("queued")));
        assertTrue(queuedId.startsWith("s-"), queuedId);
        JsonNode cancelled = output.path("cancel").path("CancelStepsInfo");
        assertEquals(queuedId, cancelled.path("StepId").asText());
        assertEquals("FAILED", cancelled.path("Status").asText());
        assertEquals(MAPPER.createObjectNode(), output.path("resize"));
        assertEquals("TERMINATED", output.path("terminated").path("Cluster").path("Status").path("State").asText());

        emr(REGION, "ListInstanceGroups", "{\"ClusterId\":\"" + clusterId + "\"}").then().statusCode(200)
                .body("InstanceGroups.find { it.Name == 'Core' }.RequestedInstanceCount", equalTo(4));
        emr("DescribeCluster", "{\"ClusterId\":\"" + clusterId + "\"}").then().statusCode(400);

        JsonNode submitted = history(execution).stream()
                .filter(event -> "TaskSubmitted".equals(event.path("type").asText()))
                .findFirst().orElseThrow().path("taskSubmittedEventDetails");
        assertEquals("elasticmapreduce", submitted.path("resourceType").asText());
        assertEquals("createCluster.sync", submitted.path("resource").asText());
        assertEquals(clusterId, MAPPER.readTree(submitted.path("output").asText()).path("ClusterId").asText());
    }

    @Test
    void anInstanceFleetIsResizedByName() throws Exception {
        String definition = """
                {"StartAt": "Create", "States": {
                  "Create": {"Type": "Task", "Resource": "%1$screateCluster", "Parameters": %2$s,
                             "ResultPath": "$.cluster", "Next": "Resize"},
                  "Resize": {"Type": "Task", "Resource": "%1$smodifyInstanceFleetByName",
                             "Parameters": {"ClusterId.$": "$.cluster.ClusterId", "InstanceFleetName": "Core",
                                            "InstanceFleet": {"TargetOnDemandCapacity": 5}},
                             "ResultPath": "$.resize", "End": true}}}
                """.formatted(RESOURCE, FLEETS_CLUSTER);

        Response execution = run(definition);

        assertEquals("SUCCEEDED", execution.jsonPath().getString("status"), execution.asString());
        JsonNode output = MAPPER.readTree(execution.jsonPath().getString("output"));
        assertEquals(List.of("ClusterId"), fieldNames(output.path("cluster")));
        emr(REGION, "ListInstanceFleets", "{\"ClusterId\":\"" + output.path("cluster").path("ClusterId").asText() + "\"}")
                .then().statusCode(200)
                .body("InstanceFleets.find { it.Name == 'Core' }.TargetOnDemandCapacity", equalTo(5));
    }

    @Test
    void createClusterRefusesWhatTheGuideRulesOut() {
        assertFails(task("createCluster", "{\"Name\": \"transient\", \"Instances\": {}}"), "States.TaskFailed",
                "The field Instances.KeepJobFlowAliveWhenNoSteps is mandatory, and must have the Boolean value TRUE.");
        assertFails(task("createCluster", """
                {"Name": "with-steps", "Instances": {"KeepJobFlowAliveWhenNoSteps": true}, "Steps": [%s]}
                """.formatted(STEP)), "States.TaskFailed", "The field Steps is not allowed.");
    }

    @Test
    void aTaskWithoutTheFieldsItsApiActsOnFails() {
        assertFails(task("terminateCluster", "{}"), "States.TaskFailed", "The field ClusterId is required.");
        assertFails(task("addStep.sync", "{\"ClusterId\": \" \", \"Step\": " + STEP + "}"), "States.TaskFailed",
                "The field ClusterId is required.");
        assertFails(task("cancelStep", "{\"ClusterId\": \"j-ANY\"}"), "States.TaskFailed",
                "The field StepId is required.");
        assertFails(task("setClusterTerminationProtection", "{\"ClusterId\": \"j-ANY\"}"),
                "EMR.AmazonElasticMapReduceException", "1 validation error detected: Value null at "
                        + "'terminationProtected' failed to satisfy constraint: Member must not be null");
        assertFails(task("modifyInstanceGroupByName", "{\"ClusterId\": \"j-ANY\", \"InstanceGroup\": {}}"),
                "States.TaskFailed", "The field InstanceGroupName is required.");
        assertFails(task("modifyInstanceFleetByName", "{\"ClusterId\": \"j-ANY\", \"InstanceFleet\": {}}"),
                "States.TaskFailed", "The field InstanceFleetName is required.");
    }

    @Test
    void emrErrorsAreNamedAfterTheSdkExceptionClass() {
        assertFails(task("addStep.sync", "{\"ClusterId\": \"j-NOSUCHCLUSTER\", \"Step\": " + STEP + "}"),
                "EMR.AmazonElasticMapReduceException", "Specified job flow ID does not exist.");
        assertFails(task("cancelStep", "{\"ClusterId\": \"j-NOSUCHCLUSTER\", \"StepId\": \"s-NOSUCHSTEP\"}"),
                "EMR.InvalidRequestException", "Cluster id 'j-NOSUCHCLUSTER' is not valid.");
        String protectedCluster = """
                {"StartAt": "Create", "States": {
                  "Create": {"Type": "Task", "Resource": "%1$screateCluster", "Parameters": %2$s,
                             "ResultPath": "$.cluster", "Next": "Protect"},
                  "Protect": {"Type": "Task", "Resource": "%1$ssetClusterTerminationProtection",
                              "Parameters": {"ClusterId.$": "$.cluster.ClusterId", "TerminationProtected": true},
                              "ResultPath": null, "Next": "Terminate"},
                  "Terminate": {"Type": "Task", "Resource": "%1$sterminateCluster",
                                "Parameters": {"ClusterId.$": "$.cluster.ClusterId"}, "End": true}}}
                """.formatted(RESOURCE, GROUPS_CLUSTER);
        assertFails(protectedCluster, "EMR.AmazonElasticMapReduceException",
                "Could not shut down one or more job flows since they are termination protected.");
    }

    @Test
    void aResizeAfterTheClusterTerminatedFailsAsOnAws() {
        assertFails(resizeAfterTermination(GROUPS_CLUSTER, "modifyInstanceGroupByName",
                        "\"InstanceGroupName\": \"Core\", \"InstanceGroup\": {\"InstanceCount\": 4}"),
                "EMR.AmazonElasticMapReduceException",
                "An instance group may only be modified when the cluster is running or waiting.");
        assertFails(resizeAfterTermination(FLEETS_CLUSTER, "modifyInstanceFleetByName",
                        "\"InstanceFleetName\": \"Core\", \"InstanceFleet\": {\"TargetOnDemandCapacity\": 5}"),
                "EMR.InvalidRequestException",
                "A job flow that is shutting down, terminated, or finished may not be modified.");
    }

    @Test
    void aByNameLookupNeedsOneMatchAndAnUnsupportedPatternFails() {
        String unknownGroup = """
                {"StartAt": "Create", "States": {
                  "Create": {"Type": "Task", "Resource": "%1$screateCluster", "Parameters": %2$s,
                             "ResultPath": "$.cluster", "Next": "Resize"},
                  "Resize": {"Type": "Task", "Resource": "%1$smodifyInstanceGroupByName",
                             "Parameters": {"ClusterId.$": "$.cluster.ClusterId", "InstanceGroupName": "Task",
                                            "InstanceGroup": {"InstanceCount": 1}}, "End": true}}}
                """.formatted(RESOURCE, GROUPS_CLUSTER);
        assertFails(unknownGroup, "States.TaskFailed", "The cluster has no instance group named 'Task'.");
        assertFails(task("cancelStep.sync", "{\"ClusterId\": \"j-ANY\", \"StepId\": \"s-ANY\"}"),
                "States.TaskFailed", "Unsupported resource: " + RESOURCE + "cancelStep.sync");
    }

    private static String resizeAfterTermination(String cluster, String api, String resize) {
        return """
                {"StartAt": "Create", "States": {
                  "Create": {"Type": "Task", "Resource": "%1$screateCluster", "Parameters": %2$s,
                             "ResultPath": "$.cluster", "Next": "Terminate"},
                  "Terminate": {"Type": "Task", "Resource": "%1$sterminateCluster.sync",
                                "Parameters": {"ClusterId.$": "$.cluster.ClusterId"},
                                "ResultPath": null, "Next": "Resize"},
                  "Resize": {"Type": "Task", "Resource": "%1$s%3$s",
                             "Parameters": {"ClusterId.$": "$.cluster.ClusterId", %4$s}, "End": true}}}
                """.formatted(RESOURCE, cluster, api, resize);
    }

    private static String task(String api, String parameters) {
        return """
                {"StartAt": "Emr", "States": {"Emr": {"Type": "Task", "Resource": "%s%s",
                  "Parameters": %s, "End": true}}}
                """.formatted(RESOURCE, api, parameters);
    }

    private void assertFails(String definition, String error, String cause) {
        Response execution = run(definition);
        assertEquals("FAILED", execution.jsonPath().getString("status"), execution.asString());
        assertEquals(error, execution.jsonPath().getString("error"));
        assertEquals(cause, execution.jsonPath().getString("cause"));
    }

    private static Response run(String definition) {
        return runInRegion(REGION, definition);
    }

    private static List<JsonNode> history(Response execution) throws Exception {
        return historyInRegion(REGION, execution);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
