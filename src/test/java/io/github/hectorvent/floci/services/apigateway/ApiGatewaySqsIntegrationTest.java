package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Integration tests for the API Gateway → SQS AWS integration using the path-style
 * integration URI ({@code arn:aws:apigateway:{region}:sqs:path/{account}/{queue}}) and an
 * {@code application/x-www-form-urlencoded} request template that renders the SQS query
 * protocol ({@code Action=SendMessage&QueueUrl=...&MessageBody=...}).
 *
 * <p>This mirrors the canonical AWS recipe for exposing an SQS queue through API Gateway —
 * the same pattern real services and LocalStack use.
 */
@QuarkusTest
class ApiGatewaySqsIntegrationTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String QUEUE_NAME = "apigw-sqs-test";
    private static final String PATH_QUEUE_NAME = "apigw-sqs-path-queue";
    private static final String EXPLICIT_QUEUE_NAME = "apigw-sqs-explicit-queue";
    private static final String STAGE_NAME = "test";

    private String queueUrl;
    private String apiId;

    private String pathQueueUrl;
    private String explicitQueueUrl;
    private String explicitApiId;

    @BeforeEach
    void setUp() throws Exception {
        setupPathDerivedQueueUrl();
        setupExplicitQueueUrl();
    }

    @Test
    void sqsIntegrationSendMessage() {
        // The integration returns the SQS query-protocol XML response.
        given()
                .contentType(ContentType.JSON)
                .body("{\"hello\":\"world\"}")
                .when().post("/execute-api/" + apiId + "/" + STAGE_NAME + "/send")
                .then()
                .statusCode(200)
                .body(containsString("<SendMessageResponse"))
                .body(containsString("<MessageId>"))
                .body(containsString("<MD5OfMessageBody>"));
    }

    @Test
    void sqsIntegrationMessageLandsOnQueue() {
        // The body the producer POSTed should be the MessageBody now sitting on the queue.
        given()
                .contentType(ContentType.JSON)
                .body("{\"hello\":\"world\"}")
                .when().post("/execute-api/" + apiId + "/" + STAGE_NAME + "/send")
                .then().statusCode(200);

        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", queueUrl)
                .formParam("MaxNumberOfMessages", "1")
                .when().post("/")
                .then().statusCode(200)
                .body(containsString("<Body>{&quot;hello&quot;:&quot;world&quot;}</Body>"));
    }

    @Test
    void explicitQueueUrlSendMessage() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"target\":\"explicit-queue\"}")
                .when().post("/execute-api/" + explicitApiId + "/" + STAGE_NAME + "/send")
                .then()
                .statusCode(200)
                .body(containsString("<SendMessageResponse"))
                .body(containsString("<MessageId>"))
                .body(containsString("<MD5OfMessageBody>"));
    }

    @Test
    void explicitQueueUrlMessageLandsOnExplicitQueue() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"target\":\"explicit-queue\"}")
                .when().post("/execute-api/" + explicitApiId + "/" + STAGE_NAME + "/send")
                .then().statusCode(200);

        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", explicitQueueUrl)
                .formParam("MaxNumberOfMessages", "1")
                .when().post("/")
                .then().statusCode(200)
                .body(containsString("<Body>{&quot;target&quot;:&quot;explicit-queue&quot;}</Body>"));
    }

    @Test
    void explicitQueueUrlPathQueueRemainsEmpty() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"target\":\"explicit-queue\"}")
                .when().post("/execute-api/" + explicitApiId + "/" + STAGE_NAME + "/send")
                .then().statusCode(200);

        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", pathQueueUrl)
                .formParam("MaxNumberOfMessages", "1")
                .when().post("/")
                .then().statusCode(200)
                .body(not(containsString("<Body>")));
    }

    private void setupPathDerivedQueueUrl() throws Exception {
        queueUrl = createQueue(QUEUE_NAME);
        apiId = createRestApi("sqs-integration-test", "APIGW → SQS");

        String rootId = getRootResourceId(apiId);
        String resourceId = createResource(apiId, rootId, "send");

        String vtl = "Action=SendMessage"
                + "&MessageBody=$util.urlEncode($input.body)";
        configurePostSqsIntegration(apiId, resourceId, QUEUE_NAME, vtl);
        deployStage(apiId, STAGE_NAME);
    }

    private void setupExplicitQueueUrl() throws Exception {
        pathQueueUrl = createQueue(PATH_QUEUE_NAME);
        explicitQueueUrl = createQueue(EXPLICIT_QUEUE_NAME);
        explicitApiId = createRestApi("sqs-explicit-queueurl-test", "APIGW → SQS explicit QueueUrl");

        String rootId = getRootResourceId(explicitApiId);
        String resourceId = createResource(explicitApiId, rootId, "send");

        String vtl = "Action=SendMessage"
                + "&QueueUrl=$util.urlEncode(\"" + explicitQueueUrl + "\")"
                + "&MessageBody=$util.urlEncode($input.body)";
        configurePostSqsIntegration(explicitApiId, resourceId, PATH_QUEUE_NAME, vtl);
        deployStage(explicitApiId, STAGE_NAME);
    }

    private String createQueue(String queueName) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateQueue")
                .formParam("QueueName", queueName)
                .when().post("/")
                .then().statusCode(200)
                .body(containsString("<QueueUrl>"))
                .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
    }

    private String createRestApi(String name, String description) {
        return given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\",\"description\":\"" + description + "\"}")
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");
    }

    private String getRootResourceId(String apiId) {
        return given()
                .when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200)
                .extract().path("item[0].id");
    }

    private String createResource(String apiId, String parentId, String pathPart) {
        return given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"" + pathPart + "\"}")
                .when().post("/restapis/" + apiId + "/resources/" + parentId)
                .then().statusCode(201)
                .extract().path("id");
    }

    private void configurePostSqsIntegration(String apiId, String resourceId, String uriQueueName, String vtl)
            throws Exception {
        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST")
                .then().statusCode(201);

        var integrationNode = mapper.createObjectNode();
        integrationNode.put("type", "AWS");
        integrationNode.put("httpMethod", "POST");
        integrationNode.put("uri", "arn:aws:apigateway:us-east-1:sqs:path/000000000000/" + uriQueueName);
        integrationNode.put("passthroughBehavior", "NEVER");

        var reqParams = mapper.createObjectNode();
        reqParams.put("integration.request.header.Content-Type", "'application/x-www-form-urlencoded'");
        integrationNode.set("requestParameters", reqParams);

        var reqTemplates = mapper.createObjectNode();
        reqTemplates.put("application/json", vtl);
        integrationNode.set("requestTemplates", reqTemplates);

        given()
                .contentType(ContentType.JSON)
                .body(mapper.writeValueAsString(integrationNode))
                .when().put("/restapis/" + apiId + "/resources/" + resourceId
                        + "/methods/POST/integration")
                .then().statusCode(201);

        // Default 200 integration response (no response template — pass the SQS XML through).
        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId
                        + "/methods/POST/integration/responses/200")
                .then().statusCode(201);
    }

    private void deployStage(String apiId, String stageName) {
        String deploymentId = given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"test\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"" + stageName + "\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);
    }
}
