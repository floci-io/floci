package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * A stack update that changes a custom resource and then fails on a later resource rolls the custom
 * resource back the way CloudFormation does: the handler is sent a second {@code Update} carrying
 * the old properties, so it can undo what the first one applied.
 *
 * <p>The ServiceToken Lambda is mocked, as in {@code CloudFormationCustomResourceStackIdIntegrationTest},
 * so no Docker is needed. The mock answers like a real handler, by a PUT of its result to the
 * event's {@code ResponseURL}.
 */
@QuarkusTest
class CloudFormationCustomResourceRollbackIntegrationTest {

    private static final String SERVICE_TOKEN =
            "arn:aws:lambda:us-east-1:000000000000:function:rollback-probe";

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> events = new CopyOnWriteArrayList<>();
    private volatile String failingRequest;
    private volatile String failureReason;

    @InjectMock
    LambdaService lambdaService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void stubHandler() {
        when(lambdaService.invoke(any(), eq(SERVICE_TOKEN), any(), eq(InvocationType.RequestResponse)))
                .thenAnswer(inv -> {
                    JsonNode event = mapper.readTree((byte[]) inv.getArgument(2));
                    events.add(event);
                    putResponse(event);
                    return new InvokeResult(200, null, "null".getBytes(StandardCharsets.UTF_8), null, "req-1");
                });
    }

    @Test
    void laterFailureSendsTheCustomResourceItsOldPropertiesBack() throws InterruptedException {
        String stackName = "cr-rollback-" + Long.toString(System.nanoTime(), 36);
        String stackId = createStack(stackName, template("1", false));
        awaitStackStatus(stackId, "CREATE_COMPLETE");
        String physicalId = probePhysicalId(stackName);

        updateStack(stackName, template("2", true));
        awaitStackStatus(stackId, "UPDATE_ROLLBACK_COMPLETE");

        assertEquals(List.of("Create:1", "Update:2", "Update:1"), requests());
        assertProperties(events.get(0), "1", null);
        assertProperties(events.get(1), "2", "1");
        assertProperties(events.get(2), "1", "2");
        assertEquals(physicalId, events.get(2).get("PhysicalResourceId").asText());
        assertEquals(physicalId, probePhysicalId(stackName));
        assertFalse(describeStackEvents(stackId).contains("Rollback is not implemented"));
    }

    @Test
    void handlerThatFailsTheRollbackLeavesTheStackUpdateRollbackFailed() throws InterruptedException {
        failOn("Update:1", "cannot restore revision 1");
        String stackName = "cr-rollback-failed-" + Long.toString(System.nanoTime(), 36);
        String stackId = createStack(stackName, template("1", false));
        awaitStackStatus(stackId, "CREATE_COMPLETE");

        updateStack(stackName, template("2", true));
        awaitStackStatus(stackId, "UPDATE_ROLLBACK_FAILED");

        assertEquals(List.of("Create:1", "Update:2", "Update:1"), requests());
        List<Map<String, String>> probeFailures = XmlParser.extractGroups(describeStackEvents(stackId), "member")
                .stream()
                .filter(e -> "Probe".equals(e.get("LogicalResourceId")))
                .filter(e -> "UPDATE_FAILED".equals(e.get("ResourceStatus")))
                .toList();
        assertEquals(1, probeFailures.size(), probeFailures.toString());
        String reason = probeFailures.get(0).get("ResourceStatusReason");
        assertTrue(reason.contains("cannot restore revision 1"), reason);
    }

    @Test
    void customResourceWhoseOwnUpdateFailsIsSentItsOldPropertiesBack() throws InterruptedException {
        failOn("Update:3", "forced failure for Update v3");
        String stackName = "cr-own-update-failed-" + Long.toString(System.nanoTime(), 36);
        String stackId = createStack(stackName, template("1", false));
        awaitStackStatus(stackId, "CREATE_COMPLETE");
        String physicalId = probePhysicalId(stackName);

        updateStack(stackName, template("3", false));
        awaitStackStatus(stackId, "UPDATE_ROLLBACK_COMPLETE");

        assertEquals(List.of("Create:1", "Update:3", "Update:1"), requests());
        assertProperties(events.get(0), "1", null);
        assertProperties(events.get(1), "3", "1");
        assertProperties(events.get(2), "1", "3");
        assertEquals(physicalId, events.get(2).get("PhysicalResourceId").asText());
        assertEquals(physicalId, probePhysicalId(stackName));
        List<String> probeStatuses = XmlParser.extractGroups(describeStackEvents(stackId), "member")
                .reversed()
                .stream()
                .filter(e -> "Probe".equals(e.get("LogicalResourceId")))
                .map(e -> e.get("ResourceStatus"))
                .toList();
        assertEquals(List.of("UPDATE_FAILED", "UPDATE_IN_PROGRESS", "UPDATE_COMPLETE"),
                probeStatuses.subList(probeStatuses.size() - 3, probeStatuses.size()));
    }

    @Test
    void deleteAfterAFailedRollbackSendsTheOldProperties() throws InterruptedException {
        failOn("Update:1", "cannot restore revision 1");
        String stackName = "cr-delete-after-failed-" + Long.toString(System.nanoTime(), 36);
        String stackId = createStack(stackName, template("1", false));
        awaitStackStatus(stackId, "CREATE_COMPLETE");
        String physicalId = probePhysicalId(stackName);
        updateStack(stackName, template("2", true));
        awaitStackStatus(stackId, "UPDATE_ROLLBACK_FAILED");

        deleteStack(stackName);
        awaitStackStatus(stackId, "DELETE_COMPLETE");

        assertEquals(List.of("Create:1", "Update:2", "Update:1", "Delete:1"), requests());
        assertProperties(events.get(3), "1", null);
        assertEquals(physicalId, events.get(3).get("PhysicalResourceId").asText());
    }

    /**
     * Asserts the request's complete ResourceProperties, and its OldResourceProperties or, when
     * {@code oldRevision} is null, that it carries none.
     */
    private void assertProperties(JsonNode event, String revision, String oldRevision) {
        assertEquals(properties(revision), event.get("ResourceProperties"), event.toString());
        if (oldRevision == null) {
            assertFalse(event.has("OldResourceProperties"), event.toString());
        } else {
            assertEquals(properties(oldRevision), event.get("OldResourceProperties"), event.toString());
        }
    }

    private ObjectNode properties(String revision) {
        ObjectNode properties = mapper.createObjectNode();
        properties.put("ServiceToken", SERVICE_TOKEN);
        properties.put("Revision", revision);
        return properties;
    }

    private void failOn(String request, String reason) {
        failingRequest = request;
        failureReason = reason;
    }

    private static String template(String revision, boolean withFailingBucket) {
        String failingBucket = withFailingBucket
                ? """
                  ,
                  "Bad": {
                    "Type": "AWS::S3::Bucket",
                    "DependsOn": "Probe",
                    "Properties": {"BucketName": "bad/name"}
                  }
                  """
                : "";
        return """
                {
                  "Resources": {
                    "Probe": {
                      "Type": "Custom::RollbackProbe",
                      "Properties": {
                        "ServiceToken": "%s",
                        "Revision": "%s"
                      }
                    }%s
                  }
                }
                """.formatted(SERVICE_TOKEN, revision, failingBucket);
    }

    /** Each request the handler saw, as its RequestType and the Revision it was asked to apply. */
    private List<String> requests() {
        return events.stream().map(CloudFormationCustomResourceRollbackIntegrationTest::request).toList();
    }

    private static String request(JsonNode event) {
        return event.get("RequestType").asText() + ":" + event.get("ResourceProperties").get("Revision").asText();
    }

    /**
     * Answers the request the way a handler does: a PUT of its result document to ResponseURL. The
     * request the test named with {@link #failOn} is answered FAILED.
     */
    private void putResponse(JsonNode event) {
        ObjectNode response = mapper.createObjectNode();
        if (request(event).equals(failingRequest)) {
            response.put("Status", "FAILED");
            response.put("Reason", failureReason);
        } else {
            response.put("Status", "SUCCESS");
        }
        response.put("RequestId", event.get("RequestId").asText());
        response.put("StackId", event.get("StackId").asText());
        response.put("LogicalResourceId", event.get("LogicalResourceId").asText());
        response.put("PhysicalResourceId", "rollback-probe");
        String path = URI.create(event.get("ResponseURL").asText()).getPath();
        given().body(response.toString()).when().put(path).then().statusCode(200);
    }

    private static String createStack(String stackName, String template) {
        String xml = cfn(Map.of("Action", "CreateStack", "StackName", stackName, "TemplateBody", template));
        return XmlParser.extractFirst(xml, "StackId", null);
    }

    private static void updateStack(String stackName, String template) {
        cfn(Map.of("Action", "UpdateStack", "StackName", stackName, "TemplateBody", template));
    }

    private static void deleteStack(String stackName) {
        cfn(Map.of("Action", "DeleteStack", "StackName", stackName));
    }

    private static String probePhysicalId(String stackName) {
        String xml = cfn(Map.of("Action", "DescribeStackResource", "StackName", stackName,
                "LogicalResourceId", "Probe"));
        return XmlParser.extractFirst(xml, "PhysicalResourceId", null);
    }

    private static String describeStackEvents(String stackId) {
        return cfn(Map.of("Action", "DescribeStackEvents", "StackName", stackId));
    }

    /** Polls DescribeStacks by stack id and returns its XML once the stack reaches {@code status}. */
    private static String awaitStackStatus(String stackId, String status) throws InterruptedException {
        String xml = "";
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            xml = cfn(Map.of("Action", "DescribeStacks", "StackName", stackId));
            if (xml.contains("<StackStatus>" + status + "</StackStatus>")) {
                return xml;
            }
            Thread.sleep(50);
        }
        fail("stack " + stackId + " never reached " + status + ": " + xml);
        return xml;
    }

    private static String cfn(Map<String, String> params) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .formParams(params)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().asString();
    }
}
