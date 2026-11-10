package io.floci.aws.services.apigateway;

import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class ApiGatewayAwsStatusSelectionIntegrationTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String LAMBDA_FUNCTION = "aws-status-selection";
    private static final String LAMBDA_ARN = "arn:aws:lambda:us-east-1:" + ACCOUNT_ID + ":function:" + LAMBDA_FUNCTION;
    private static final String MESSAGE = "{\"hello\":\"status-selection\"}";

    @InjectMock
    LambdaService lambdaService;

    private final List<String> apiIds = new ArrayList<>();
    private final List<String> queueUrls = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String apiId : apiIds) {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
        for (String queueUrl : queueUrls) {
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DeleteQueue").formParam("QueueUrl", queueUrl)
                    .post("/").then().statusCode(200);
        }
    }

    @Test
    void sqsErrorStatusSelectsMatchingIntegrationResponse() {
        String queueName = "missing-status-" + UUID.randomUUID();
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "SendMessage").formParam("QueueUrl", queueName)
                .formParam("MessageBody", MESSAGE).post("/")
                .then().statusCode(400).body(containsString("NonExistentQueue"));
        String apiId = sqsApi(queueName, 502, "4\\d{2}");

        given().contentType(ContentType.JSON).body(MESSAGE)
                .post("/execute-api/" + apiId + "/test/send")
                .then().statusCode(502).body("result", equalTo("matched"));
    }

    @Test
    void sqsErrorTextDoesNotSelectIntegrationResponse() {
        String apiId = sqsApi("missing-error-text-" + UUID.randomUUID(), 502, ".*NonExistentQueue.*");

        given().contentType(ContentType.JSON).body(MESSAGE)
                .post("/execute-api/" + apiId + "/test/send")
                .then().statusCode(200).body("result", equalTo("default"));
    }

    @Test
    void sqsSuccessStatusSelectsMatchingIntegrationResponse() {
        String queueName = "success-status-" + UUID.randomUUID();
        String queueUrl = createQueue(queueName);
        String apiId = sqsApi(queueName, 201, "2\\d{2}");

        given().contentType(ContentType.JSON).body(MESSAGE)
                .post("/execute-api/" + apiId + "/test/send")
                .then().statusCode(201).body("result", equalTo("matched"));
        assertMessageQueued(queueUrl);
    }

    @Test
    void sqsSuccessWithoutMatchingPatternUsesDefaultResponse() {
        String queueName = "success-default-" + UUID.randomUUID();
        String queueUrl = createQueue(queueName);
        String apiId = sqsApi(queueName, 502, "4\\d{2}");

        given().contentType(ContentType.JSON).body(MESSAGE)
                .post("/execute-api/" + apiId + "/test/send")
                .then().statusCode(200).body("result", equalTo("default"));
        assertMessageQueued(queueUrl);
    }

    @Test
    void malformedActionRequestDoesNotMatchBackendStatusPattern() {
        // Replacing the default 200 entry leaves only the backend-status pattern.
        String apiId = awsApi(Map.of(
                "type", "AWS",
                "httpMethod", "POST",
                "uri", "arn:aws:apigateway:us-east-1:sqs:action/SendMessage",
                "requestTemplates", Map.of("application/json", "not-json")), 200, "5\\d{2}");

        given().contentType(ContentType.JSON).body(MESSAGE)
                .post("/execute-api/" + apiId + "/test/send")
                .then().statusCode(500).body(containsString("InternalError"));
    }

    @Test
    void lambdaSuccessDoesNotMatchHttpStatusPattern() {
        when(lambdaService.invokeTarget(eq("us-east-1"), eq(LAMBDA_ARN), any(byte[].class),
                eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, null, "{}".getBytes(StandardCharsets.UTF_8), null, "success"));
        String apiId = lambdaApi(201, "2\\d{2}");

        given().contentType(ContentType.JSON).body(MESSAGE)
                .post("/execute-api/" + apiId + "/test/send")
                .then().statusCode(200).body("result", equalTo("default"));
        verify(lambdaService).invokeTarget(eq("us-east-1"), eq(LAMBDA_ARN),
                eq(MESSAGE.getBytes(StandardCharsets.UTF_8)), eq(InvocationType.RequestResponse));
    }

    @Test
    void lambdaFunctionErrorStillMatchesErrorTextPattern() {
        byte[] errorPayload = "{\"errorMessage\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
        when(lambdaService.invokeTarget(eq("us-east-1"), eq(LAMBDA_ARN), any(byte[].class),
                eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, "Unhandled", errorPayload, null, "function-error"));
        String apiId = lambdaApi(502, ".*boom.*");

        given().contentType(ContentType.JSON).body(MESSAGE)
                .post("/execute-api/" + apiId + "/test/send")
                .then().statusCode(502).body("result", equalTo("matched"));
        verify(lambdaService).invokeTarget(eq("us-east-1"), eq(LAMBDA_ARN),
                eq(MESSAGE.getBytes(StandardCharsets.UTF_8)), eq(InvocationType.RequestResponse));
    }

    private String createQueue(String queueName) {
        String queueUrl = given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateQueue").formParam("QueueName", queueName)
                .post("/").then().statusCode(200)
                .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
        queueUrls.add(queueUrl);
        return queueUrl;
    }

    private static void assertMessageQueued(String queueUrl) {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ReceiveMessage").formParam("QueueUrl", queueUrl)
                .formParam("MaxNumberOfMessages", "1").post("/")
                .then().statusCode(200).body(containsString("status-selection"));
    }

    private String sqsApi(String queueName, int matchedStatus, String pattern) {
        return awsApi(Map.of(
                "type", "AWS",
                "httpMethod", "POST",
                "uri", "arn:aws:apigateway:us-east-1:sqs:path/" + ACCOUNT_ID + "/" + queueName,
                "credentials", "arn:aws:iam::" + ACCOUNT_ID + ":role/apigw-sqs",
                "requestParameters", Map.of(
                        "integration.request.header.Content-Type", "'application/x-www-form-urlencoded'"),
                "requestTemplates", Map.of(
                        "application/json", "Action=SendMessage&MessageBody=$util.urlEncode($input.body)")),
                matchedStatus, pattern);
    }

    private String lambdaApi(int matchedStatus, String pattern) {
        return awsApi(Map.of(
                "type", "AWS",
                "httpMethod", "POST",
                "uri", "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/"
                        + "arn:aws:lambda:us-east-1:" + ACCOUNT_ID + ":function:" + LAMBDA_FUNCTION + "/invocations",
                "requestTemplates", Map.of("application/json", "$input.body")), matchedStatus, pattern);
    }

    private String awsApi(Map<String, Object> integrationRequest, int matchedStatus, String pattern) {
        String apiId = given().contentType(ContentType.JSON).body(Map.of("name", "aws-status-selection"))
                .post("/restapis").then().statusCode(201).extract().path("id");
        apiIds.add(apiId);
        String rootId = given().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        String resourceId = given().contentType(ContentType.JSON).body(Map.of("pathPart", "send"))
                .post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201).extract().path("id");
        String methodPath = "/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST";
        given().contentType(ContentType.JSON).body(Map.of("authorizationType", "NONE"))
                .put(methodPath).then().statusCode(201);
        given().contentType(ContentType.JSON).body(integrationRequest)
                .put(methodPath + "/integration").then().statusCode(201);
        integrationResponse(methodPath, 200, "", "default");
        integrationResponse(methodPath, matchedStatus, pattern, "matched");

        String deploymentId = given().contentType(ContentType.JSON).body(Map.of("stageName", "test"))
                .post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201).extract().path("id");
        given().get("/restapis/" + apiId + "/stages/test")
                .then().statusCode(200).body("deploymentId", equalTo(deploymentId));
        return apiId;
    }

    private static void integrationResponse(String methodPath, int status, String pattern, String result) {
        given().contentType(ContentType.JSON).body(Map.of())
                .put(methodPath + "/responses/" + status).then().statusCode(201);
        given().contentType(ContentType.JSON).body(Map.of(
                        "selectionPattern", pattern,
                        "responseTemplates", Map.of("application/json", "{\"result\": \"" + result + "\"}")))
                .put(methodPath + "/integration/responses/" + status).then().statusCode(201);
    }
}
