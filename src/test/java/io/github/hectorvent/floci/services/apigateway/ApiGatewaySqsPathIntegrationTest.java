package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewaySqsPathIntegrationTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static String apiId;
    private static String rootId;
    private static String sqsResourceId;
    private static String deploymentId;
    private static final String QUEUE_NAME = "apigw-sqs-proxy-test-queue";
    private static final String ACCOUNT_ID = "000000000000";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test @Order(1)
    void setup_createSqsQueue() {
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateQueue")
                .formParam("QueueName", QUEUE_NAME)
                .when().post("/")
                .then().statusCode(200);
    }

    @Test @Order(2)
    void setup_createRestApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"aws-sqs-integration-test","description":"Test AWS SQS path integration"}
                        """)
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");
        assertNotNull(apiId);
    }

    @Test @Order(3)
    void setup_getRootResource() {
        rootId = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200)
                .extract().path("item[0].id");
        assertNotNull(rootId);
    }

    @Test @Order(4)
    void setup_createSqsResource() {
        sqsResourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"send\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201)
                .extract().path("id");
    }

    @Test @Order(5)
    void setup_configureSqsMethod() throws Exception {
        // PUT method
        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + sqsResourceId + "/methods/POST")
                .then().statusCode(201);

        // PUT integration
        var integrationNode = mapper.createObjectNode();
        integrationNode.put("type", "AWS");
        integrationNode.put("httpMethod", "POST");
        integrationNode.put("uri", "arn:aws:apigateway:eu-west-1:sqs:path/" + ACCOUNT_ID + "/" + QUEUE_NAME);
        
        var requestParameters = mapper.createObjectNode();
        requestParameters.put("integration.request.header.Content-Type", "'application/x-www-form-urlencoded'");
        integrationNode.set("requestParameters", requestParameters);

        var reqTemplates = mapper.createObjectNode();
        String vtl = "Action=SendMessage&QueueUrl=$util.urlEncode(\"http://localhost:4566/" + ACCOUNT_ID + "/" + QUEUE_NAME + "\")&MessageBody=$util.urlEncode($input.body)";
        reqTemplates.put("application/json", vtl);
        integrationNode.set("requestTemplates", reqTemplates);

        String integrationBody = mapper.writeValueAsString(integrationNode);

        given()
                .contentType(ContentType.JSON)
                .body(integrationBody)
                .when().put("/restapis/" + apiId + "/resources/" + sqsResourceId + "/methods/POST/integration")
                .then().statusCode(201);

        // PUT integration response (200 default)
        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + sqsResourceId
                        + "/methods/POST/integration/responses/200")
                .then().statusCode(201);
    }

    @Test @Order(6)
    void setup_deployAndCreateStage() {
        deploymentId = given()
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

    @Test @Order(10)
    void pathStyleSqsIntegration_proxiesMessageToQueue() throws Exception {
        String messageBody = "{\"hello\":\"world\"}";
        
        given()
                .contentType(ContentType.JSON)
                .body(messageBody)
                .when().post("/execute-api/" + apiId + "/test/send")
                .then()
                .statusCode(200);

        // Verify message is in queue
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", "http://localhost:4566/" + ACCOUNT_ID + "/" + QUEUE_NAME)
                .formParam("MaxNumberOfMessages", "1")
                .formParam("VisibilityTimeout", "10")
                .when().post("/")
                .then().statusCode(200)
                .body(containsString("&quot;hello&quot;:&quot;world&quot;")); // JSON quotes are XML-escaped
    }

    @Test @Order(11)
    void pathStyleSqsIntegration_handlesAmpersandAndEqualsInBody() throws Exception {
        String messageBody = "{\"query\":\"a=1&b=2\",\"note\":\"contains & and = chars\"}";
        
        given()
                .contentType(ContentType.JSON)
                .body(messageBody)
                .when().post("/execute-api/" + apiId + "/test/send")
                .then()
                .statusCode(200);

        // Verify message is in queue
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", "http://localhost:4566/" + ACCOUNT_ID + "/" + QUEUE_NAME)
                .formParam("MaxNumberOfMessages", "1")
                .formParam("VisibilityTimeout", "10")
                .when().post("/")
                .then().statusCode(200)
                .body(containsString("a=1&amp;b=2")); // JSON value XML-escaped
    }
}
