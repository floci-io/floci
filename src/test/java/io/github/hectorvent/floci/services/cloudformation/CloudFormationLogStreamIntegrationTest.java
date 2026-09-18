package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

@QuarkusTest
class CloudFormationLogStreamIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260918/us-east-1/cloudformation/aws4_request";
    private static final String LOGS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260918/us-east-1/logs/aws4_request";
    private final List<String> stacks = new ArrayList<>();
    private final List<String> groups = new ArrayList<>();

    @InjectSpy
    CloudWatchLogsService logsService;

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (String stack : List.copyOf(stacks)) {
            deleteStack(stack);
        }
        for (String group : groups) {
            logs("DeleteLogGroup", Map.of("logGroupName", group)).then().statusCode(200);
        }
    }

    @Test
    void generatedStreamNameAndEventsSurviveAnUnrelatedStackUpdate() throws Exception {
        String group = createGroup();
        String stack = createStack(template(group, null, "first", false, false), "CREATE_COMPLETE");
        String stream = streamRef(awaitStatus(stack, "CREATE_COMPLETE"));
        assertTrue(stream.startsWith(stack + "-Stream-"), stream);
        assertTrue(stream.length() <= 512);
        assertEquals(List.of(stream), streams(group));
        putEvent(group, stream, "keep this event");

        updateStack(stack, template(group, null, "second", false, false));
        assertEquals(stream, streamRef(awaitStatus(stack, "UPDATE_COMPLETE")));
        assertEquals(List.of("keep this event"), messages(group, stream));

        deleteStack(stack);
        assertTrue(streams(group).isEmpty(), "Deleting the stack must delete the stream in the external group");
        logs("CreateLogStream", Map.of("logGroupName", group, "logStreamName", stream))
                .then().statusCode(200);
        assertTrue(messages(group, stream).isEmpty(), "Deleted stream events must not reappear on recreation");
    }

    @Test
    void aFailedGroupReplacementRestoresTheOriginalStreamAndEvents() throws Exception {
        String originalGroup = createGroup();
        String targetGroup = createGroup();
        String stream = "application";
        String stack = createStack(template(originalGroup, stream, null, false, false), "CREATE_COMPLETE");
        putEvent(originalGroup, stream, "original event");

        updateStack(stack, template(targetGroup, stream, null, false, true));
        assertEquals(stream, streamRef(awaitStatus(stack, "UPDATE_ROLLBACK_COMPLETE")));
        assertEquals(List.of(stream), streams(originalGroup));
        assertEquals(List.of("original event"), messages(originalGroup, stream));
        assertTrue(streams(targetGroup).isEmpty(), "The failed replacement must be removed");

        deleteStack(stack);
        assertTrue(streams(originalGroup).isEmpty());
    }

    @Test
    void retainPolicyKeepsTheDisplacedStreamAndItsEvents() throws Exception {
        String group = createGroup();
        String stack = createStack(template(group, "original", null, true, false), "CREATE_COMPLETE");
        putEvent(group, "original", "retained event");

        updateStack(stack, template(group, "replacement", null, true, false));
        assertEquals("replacement", streamRef(awaitStatus(stack, "UPDATE_COMPLETE")));
        assertEquals(List.of("original", "replacement"), streams(group));
        assertEquals(List.of("retained event"), messages(group, "original"));

        deleteStack(stack);
        assertEquals(List.of("original"), streams(group));
    }

    @Test
    void deleteStackAfterFailedRollbackCleansBothOwnedStreams() throws Exception {
        String originalGroup = createGroup();
        String targetGroup = createGroup();
        String stream = "application";
        String stack = createStack(template(originalGroup, stream, null, false, false), "CREATE_COMPLETE");
        putEvent(originalGroup, stream, "original event");
        doThrow(new AwsException("ServiceUnavailableException", "temporary delete failure", 503))
                .when(logsService).deleteLogStream(targetGroup, stream, "us-east-1");
        try {
            updateStack(stack, template(targetGroup, stream, null, false, true));
            awaitStatus(stack, "UPDATE_ROLLBACK_FAILED");
            assertEquals(List.of("original event"), messages(originalGroup, stream));
            assertEquals(List.of(stream), streams(targetGroup));
        } finally {
            doCallRealMethod().when(logsService).deleteLogStream(targetGroup, stream, "us-east-1");
        }

        deleteStack(stack);
        assertTrue(streams(originalGroup).isEmpty(), "The original stream remains owned after failed rollback");
        assertTrue(streams(targetGroup).isEmpty(), "The failed replacement still needs cleanup");
    }

    @Test
    void aDuplicateStreamDoesNotBecomeOwnedByTheFailedStack() throws Exception {
        String group = createGroup();
        logs("CreateLogStream", Map.of("logGroupName", group, "logStreamName", "external"))
                .then().statusCode(200);
        putEvent(group, "external", "external event");

        String stack = createStack(template(group, "external", null, false, false), "ROLLBACK_COMPLETE");
        assertEquals(List.of("external event"), messages(group, "external"));
        deleteStack(stack);
        assertEquals(List.of("external"), streams(group));
        assertEquals(List.of("external event"), messages(group, "external"));
    }

    @Test
    void deletingAStackToleratesAnAlreadyRemovedStream() throws Exception {
        String group = createGroup();
        String stack = createStack(template(group, "removed", null, false, false), "CREATE_COMPLETE");
        logs("DeleteLogStream", Map.of("logGroupName", group, "logStreamName", "removed"))
                .then().statusCode(200);

        deleteStack(stack);
        assertTrue(streams(group).isEmpty());
    }

    private String createGroup() throws Exception {
        String group = "/cfn/log-stream/" + Long.toString(System.nanoTime(), 36);
        logs("CreateLogGroup", Map.of("logGroupName", group)).then().statusCode(200);
        groups.add(group);
        return group;
    }

    private String createStack(String template, String status) {
        String stack = "cfn-log-stream-" + Long.toString(System.nanoTime(), 36);
        cfn(stack, "CreateStack", template).then().statusCode(200);
        stacks.add(stack);
        awaitStatus(stack, status);
        return stack;
    }

    private static void updateStack(String stack, String template) {
        cfn(stack, "UpdateStack", template).then().statusCode(200);
    }

    private void deleteStack(String stack) {
        cfn(stack, "DeleteStack", null).then().statusCode(200);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Response response = cfn(stack, "DescribeStacks", null);
            assertEquals(400, response.statusCode(), response.asString());
            assertTrue(response.asString().contains("does not exist"), response.asString());
        });
        stacks.remove(stack);
    }

    private static String awaitStatus(String stack, String status) {
        return await().atMost(Duration.ofSeconds(15)).until(
                () -> cfn(stack, "DescribeStacks", null).then().statusCode(200).extract().asString(),
                body -> status.equals(XmlParser.extractFirst(body, "StackStatus", null)));
    }

    private static String streamRef(String body) {
        String stream = XmlParser.extractPairs(body, "Outputs", "OutputKey", "OutputValue").get("StreamName");
        assertFalse(stream == null || stream.isBlank(), body);
        return stream;
    }

    private static Response cfn(String stack, String action, String template) {
        RequestSpecification request = given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH).formParam("Action", action).formParam("StackName", stack);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        return request.post("/");
    }

    private static Response logs(String action, Map<String, ?> body) throws Exception {
        return given().contentType("application/x-amz-json-1.1").header("Authorization", LOGS_AUTH)
                .header("X-Amz-Target", "Logs_20140328." + action).body(MAPPER.writeValueAsString(body)).post("/");
    }

    private static List<String> streams(String group) throws Exception {
        return logs("DescribeLogStreams", Map.of("logGroupName", group)).then().statusCode(200)
                .extract().jsonPath().getList("logStreams.logStreamName", String.class).stream().sorted().toList();
    }

    private static void putEvent(String group, String stream, String message) throws Exception {
        logs("PutLogEvents", Map.of("logGroupName", group, "logStreamName", stream,
                "logEvents", List.of(Map.of("timestamp", System.currentTimeMillis(), "message", message))))
                .then().statusCode(200);
    }

    private static List<String> messages(String group, String stream) throws Exception {
        return logs("GetLogEvents", Map.of("logGroupName", group, "logStreamName", stream, "startFromHead", true))
                .then().statusCode(200).extract().jsonPath().getList("events.message", String.class);
    }

    private static String template(String group, String name, String revision, boolean retain, boolean fail)
            throws Exception {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("LogGroupName", group);
        if (name != null) {
            properties.put("LogStreamName", name);
        }
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("Stream", Map.of("Type", "AWS::Logs::LogStream", "Properties", properties,
                "UpdateReplacePolicy", retain ? "Retain" : "Delete"));
        if (revision != null) {
            resources.put("Revision", Map.of("Type", "AWS::SSM::Parameter",
                    "Properties", Map.of("Type", "String", "Value", revision)));
        }
        if (fail) {
            resources.put("BadSecret", Map.of("Type", "AWS::SecretsManager::Secret", "DependsOn", "Stream",
                    "Properties", Map.of("SecretString", "explicit",
                            "GenerateSecretString", Map.of("PasswordLength", 32))));
        }
        return MAPPER.writeValueAsString(Map.of("Resources", resources,
                "Outputs", Map.of("StreamName", Map.of("Value", Map.of("Ref", "Stream")))));
    }
}
