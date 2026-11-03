package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * DeleteStack on a stack whose create is still provisioning deletes the stack once the create has
 * stopped: no resource is created after the delete, and nothing the create built survives it.
 *
 * <p>The custom resource's ServiceToken Lambda is mocked and holds its Create request until the
 * test releases it, so the delete arrives while that resource is being created.
 */
@QuarkusTest
class CloudFormationDeleteDuringCreateIntegrationTest {

    private static final String SERVICE_TOKEN =
            "arn:aws:lambda:us-east-1:000000000000:function:delete-during-create";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch createReceived = new CountDownLatch(1);
    private final CountDownLatch releaseCreate = new CountDownLatch(1);
    private final CountDownLatch deleteReceived = new CountDownLatch(1);

    @InjectMock
    LambdaService lambdaService;

    @Inject
    CloudFormationService cloudFormationService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void stubHandler() {
        when(lambdaService.invoke(any(), eq(SERVICE_TOKEN), any(), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    JsonNode event = MAPPER.readTree((byte[]) invocation.getArgument(2));
                    String requestType = event.get("RequestType").asText();
                    requests.add(requestType);
                    if ("Create".equals(requestType)) {
                        createReceived.countDown();
                        assertTrue(releaseCreate.await(10, TimeUnit.SECONDS), "the test never released the Create");
                    } else if ("Delete".equals(requestType)) {
                        deleteReceived.countDown();
                    }
                    respond(event);
                    return new InvokeResult(200, null, "null".getBytes(StandardCharsets.UTF_8), null, "req-1");
                });
    }

    @AfterEach
    void releaseHeldCreate() {
        releaseCreate.countDown();
    }

    @Test
    void deleteDuringCreateRemovesWhatTheCreateBuiltAndCreatesNothingAfterIt() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "delete-during-create-" + suffix;
        String topicName = "after-probe-" + suffix;
        String exportName = "delete-during-create-export-" + suffix;
        cfn(Map.of("Action", "CreateChangeSet", "StackName", stackName, "ChangeSetName", "create",
                "ChangeSetType", "CREATE", "TemplateBody", template(topicName, exportName)));
        String stackId = stackId(stackName);
        cfn(Map.of("Action", "ExecuteChangeSet", "StackName", stackName, "ChangeSetName", "create"));
        assertTrue(createReceived.await(10, TimeUnit.SECONDS), "the custom resource was never created");

        cfn(Map.of("Action", "DeleteStack", "StackName", stackName));
        releaseCreate.countDown();

        assertTrue(deleteReceived.await(10, TimeUnit.SECONDS),
                "the custom resource the create built was never deleted: " + requests);
        CfnStackWaits.awaitStatus(stackId, "DELETE_COMPLETE");
        assertEquals(List.of("Create", "Delete"), requests);
        assertFalse(topicExists(topicName), "the resource after the custom resource was created");
        assertFalse(cfn(Map.of("Action", "ListExports")).contains(exportName), "the export was registered");

        String otherStack = "delete-during-create-other-" + suffix;
        cfn(Map.of("Action", "CreateStack", "StackName", otherStack,
                "TemplateBody", exportingTemplate(exportName)));
        try {
            CfnStackWaits.awaitStatus(stackId(otherStack), "CREATE_COMPLETE");
        } finally {
            cfn(Map.of("Action", "DeleteStack", "StackName", otherStack));
        }
        assertTrue(cfn(Map.of("Action", "DescribeStacks", "StackName", stackId))
                .contains("<StackStatus>DELETE_COMPLETE</StackStatus>"));
    }

    @Test
    void deleteDuringCreateThatFailsFailsTheDeleteItsCallerAwaits() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "delete-during-create-failing-" + suffix;
        String bucketName = "delete-during-create-" + suffix;
        cfn(Map.of("Action", "CreateChangeSet", "StackName", stackName, "ChangeSetName", "create",
                "ChangeSetType", "CREATE", "TemplateBody", bucketTemplate(bucketName)));
        String stackId = stackId(stackName);
        cfn(Map.of("Action", "ExecuteChangeSet", "StackName", stackName, "ChangeSetName", "create"));
        assertTrue(createReceived.await(10, TimeUnit.SECONDS), "the custom resource was never created");
        given().body("keeps the bucket").when().put("/" + bucketName + "/object.txt").then().statusCode(200);

        Future<?> delete = cloudFormationService.deleteStack(stackName, "us-east-1", "000000000000");
        releaseCreate.countDown();

        try {
            assertThrows(ExecutionException.class, () -> delete.get(10, TimeUnit.SECONDS));
            CfnStackWaits.awaitStatus(stackId, "DELETE_FAILED");
        } finally {
            given().when().delete("/" + bucketName + "/object.txt");
            cfn(Map.of("Action", "DeleteStack", "StackName", stackName));
        }
    }

    private static String bucketTemplate(String bucketName) {
        return """
                {
                  "Resources": {
                    "Bucket": {
                      "Type": "AWS::S3::Bucket",
                      "Properties": {"BucketName": "%s"}
                    },
                    "Probe": {
                      "Type": "Custom::DeleteDuringCreate",
                      "DependsOn": "Bucket",
                      "Properties": {"ServiceToken": "%s"}
                    }
                  }
                }
                """.formatted(bucketName, SERVICE_TOKEN);
    }

    private static String template(String topicName, String exportName) {
        return """
                {
                  "Resources": {
                    "Probe": {
                      "Type": "Custom::DeleteDuringCreate",
                      "Properties": {"ServiceToken": "%s"}
                    },
                    "Topic": {
                      "Type": "AWS::SNS::Topic",
                      "DependsOn": "Probe",
                      "Properties": {"TopicName": "%s"}
                    }
                  },
                  "Outputs": {
                    "TopicArn": {"Value": {"Ref": "Topic"}, "Export": {"Name": "%s"}}
                  }
                }
                """.formatted(SERVICE_TOKEN, topicName, exportName);
    }

    private static String exportingTemplate(String exportName) {
        return """
                {
                  "Resources": {"Topic": {"Type": "AWS::SNS::Topic"}},
                  "Outputs": {
                    "TopicArn": {"Value": {"Ref": "Topic"}, "Export": {"Name": "%s"}}
                  }
                }
                """.formatted(exportName);
    }

    private static void respond(JsonNode event) {
        ObjectNode response = MAPPER.createObjectNode()
                .put("Status", "SUCCESS")
                .put("RequestId", event.get("RequestId").asText())
                .put("StackId", event.get("StackId").asText())
                .put("LogicalResourceId", event.get("LogicalResourceId").asText())
                .put("PhysicalResourceId", "delete-during-create-probe");
        given().body(response.toString()).when().put(URI.create(event.get("ResponseURL").asText()).getPath())
                .then().statusCode(200);
    }

    private static boolean topicExists(String topicName) {
        return cfn(Map.of("Action", "ListTopics")).contains(":" + topicName + "</TopicArn>");
    }

    private static String stackId(String stackName) {
        return XmlParser.extractFirst(cfn(Map.of("Action", "DescribeStacks", "StackName", stackName)), "StackId", null);
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
