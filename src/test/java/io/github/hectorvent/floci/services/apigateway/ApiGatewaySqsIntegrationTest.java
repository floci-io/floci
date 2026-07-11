package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewaySqsIntegrationTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String QUEUE_NAME = "apigw-sqs-test";

    private static String apiId;
    private static String rootId;
    private static String resourceId;
    private static String queueUrl;

    // ──────────────── Setup: SQS queue ────────────────

    @Test @Order(0)
    void setup_createQueue() {
        queueUrl = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateQueue")
                .formParam("QueueName", QUEUE_NAME)
                .when().post("/")
                .then().statusCode(200)
                .body(containsString("<QueueUrl>"))
                .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
        assertNotNull(queueUrl);
    }

    // ──────────────── Setup: API Gateway REST API ────────────────

    @Test @Order(1)
    void setup_createRestApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"sqs-integration-test\",\"description\":\"APIGW → SQS\"}")
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");
        assertNotNull(apiId);
    }

    @Test @Order(2)
    void setup_getRootResource() {
        rootId = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200)
                .extract().path("item[0].id");
        assertNotNull(rootId);
    }

    @Test @Order(3)
    void setup_createResource() {
        resourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"send\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201)
                .extract().path("id");
        assertNotNull(resourceId);
    }

    @Test @Order(4)
    void setup_configureMethod() throws Exception {
        // POST method, no auth.
        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST")
                .then().statusCode(201);

        // AWS integration targeting SQS via the path-style URI. The request template renders
        // the SQS query protocol; the Content-Type override marks the body form-urlencoded.
        var integrationNode = mapper.createObjectNode();
        integrationNode.put("type", "AWS");
        integrationNode.put("httpMethod", "POST");
        integrationNode.put("uri", "arn:aws:apigateway:us-east-1:sqs:path/000000000000/" + QUEUE_NAME);
        integrationNode.put("passthroughBehavior", "NEVER");

        var reqParams = mapper.createObjectNode();
        reqParams.put("integration.request.header.Content-Type", "'application/x-www-form-urlencoded'");
        integrationNode.set("requestParameters", reqParams);

        var reqTemplates = mapper.createObjectNode();
        String vtl = "Action=SendMessage"
                + "&QueueUrl=$util.urlEncode(\"" + queueUrl + "\")"
                + "&MessageBody=$util.urlEncode($input.body)";
        reqTemplates.put("application/json", vtl);
        integrationNode.set("requestTemplates", reqTemplates);

        given()
                .contentType(ContentType.JSON)
                .body(mapper.writeValueAsString(integrationNode))
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST/integration")
                .then().statusCode(201);

        // Default 200 integration response (no response template — pass the SQS XML through).
        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId
                        + "/methods/POST/integration/responses/200")
                .then().statusCode(201);
    }

    @Test @Order(5)
    void setup_deployAndCreateStage() {
        String deploymentId = given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"test\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);
    }

    // ──────────────── Test: APIGW → SQS SendMessage ────────────────

    @Test @Order(10)
    void sqsIntegration_sendMessage() {
        // The integration returns the SQS query-protocol XML response.
        given()
                .contentType(ContentType.JSON)
                .body("{\"hello\":\"world\"}")
                .when().post("/execute-api/" + apiId + "/test/send")
                .then()
                .statusCode(200)
                .body(containsString("<SendMessageResponse"))
                .body(containsString("<MessageId>"))
                .body(containsString("<MD5OfMessageBody>"));
    }

    @Test @Order(11)
    void sqsIntegration_messageLandsOnQueue() {
        // The body the producer POSTed should be the MessageBody now sitting on the queue.
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", queueUrl)
                .formParam("MaxNumberOfMessages", "1")
                .when().post("/")
                .then().statusCode(200)
                .body(containsString("<Body>{&quot;hello&quot;:&quot;world&quot;}</Body>"));
    }
}
