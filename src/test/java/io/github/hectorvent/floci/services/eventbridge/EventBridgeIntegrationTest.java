package io.github.hectorvent.floci.services.eventbridge;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventBridgeIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET = "AWSEvents.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig()
                        .encodeContentTypeAs(CONTENT_TYPE, ContentType.TEXT));
    }

    private static String ruleArn;
    private static String sqsQueueUrl;
    private static String transformerQueueUrl;

    @Test
    @Order(1)
    void createQueue_forEventDelivery() {
        sqsQueueUrl = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "eb-test-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
    }

    @Test
    @Order(2)
    void createQueue_forTransformerDelivery() {
        transformerQueueUrl = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "eb-transformer-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
    }

    @Test
    @Order(3)
    void putRule() {
        ruleArn = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET + "PutRule")
            .body("""
                {
                    "Name": "test-rule",
                    "EventPattern": "{\\"source\\":[\\"my.app\\"]}",
                    "State": "ENABLED"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RuleArn", notNullValue())
            .extract().path("RuleArn");
    }

    @Test
    @Order(4)
    void putTargets_sqsTarget() {
        String queueArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueAttributes")
            .formParam("QueueUrl", sqsQueueUrl)
            .formParam("AttributeName.1", "QueueArn")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("**.find { it.Name == 'QueueArn' }.Value");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET + "PutTargets")
            .body("""
                {
                    "Rule": "test-rule",
                    "Targets": [{"Id": "1", "Arn": "%s"}]
                }
                """.formatted(queueArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FailedEntryCount", equalTo(0));
    }

    @Test
    @Order(5)
    void putTargets_withInputTransformer() {
        String queueArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueAttributes")
            .formParam("QueueUrl", transformerQueueUrl)
            .formParam("AttributeName.1", "QueueArn")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("**.find { it.Name == 'QueueArn' }.Value");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET + "PutTargets")
            .body("""
                {
                    "Rule": "test-rule",
                    "Targets": [{
                        "Id": "2",
                        "Arn": "%s",
                        "InputTransformer": {
                            "InputPathsMap": {
                                "src": "$.source",
                                "detail": "$.detail-type"
                            },
                            "InputTemplate": "{\\"source\\":\\"<src>\\",\\"type\\":\\"<detail>\\"}"
                        }
                    }]
                }
                """.formatted(queueArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FailedEntryCount", equalTo(0));
    }

    @Test
    @Order(6)
    void listTargetsByRule_includesInputTransformer() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET + "ListTargetsByRule")
            .body("""
                {"Rule": "test-rule"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Targets.size()", equalTo(2))
            .body("Targets.find { it.Id == '2' }.InputTransformer.InputPathsMap.src", equalTo("$.source"))
            .body("Targets.find { it.Id == '2' }.InputTransformer.InputTemplate", containsString("<src>"));
    }

    @Test
    @Order(7)
    void putEvents_deliveredToSqsTarget() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET + "PutEvents")
            .body("""
                {
                    "Entries": [{
                        "Source": "my.app",
                        "DetailType": "OrderPlaced",
                        "Detail": "{\\"orderId\\":\\"123\\"}"
                    }]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FailedEntryCount", equalTo(0))
            .body("Entries[0].EventId", notNullValue());

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", sqsQueueUrl)
            .formParam("MaxNumberOfMessages", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("my.app"))
            .body(containsString("OrderPlaced"));
    }

    @Test
    @Order(8)
    void putEvents_inputTransformer_transformsPayload() {
        // Drain the transformer queue from the previous event
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", transformerQueueUrl)
            .formParam("MaxNumberOfMessages", "10")
        .when()
            .post("/");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET + "PutEvents")
            .body("""
                {
                    "Entries": [{
                        "Source": "my.app",
                        "DetailType": "OrderPlaced",
                        "Detail": "{\\"orderId\\":\\"456\\"}"
                    }]
                }
                """)
        .when()
            .post("/");

        String transformedBody = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", transformerQueueUrl)
            .formParam("MaxNumberOfMessages", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("ReceiveMessageResponse.ReceiveMessageResult.Message.Body");

        assert transformedBody != null : "Expected a message in the transformer queue";
        assert transformedBody.contains("my.app") : "Expected source my.app in: " + transformedBody;
        assert transformedBody.contains("OrderPlaced") : "Expected type OrderPlaced in: " + transformedBody;
        assert !transformedBody.contains("orderId") : "Expected raw orderId to be absent in: " + transformedBody;
    }

    @Test
    @Order(9)
    void putEvents_noMatchingRule_notDelivered() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET + "PutEvents")
            .body("""
                {
                    "Entries": [{
                        "Source": "other.app",
                        "DetailType": "Ignored",
                        "Detail": "{}"
                    }]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FailedEntryCount", equalTo(0));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", sqsQueueUrl)
            .formParam("MaxNumberOfMessages", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("other.app")));
    }

    @Test
    @Order(100)
    void cleanup() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET + "RemoveTargets")
            .body("""
                {"Rule": "test-rule", "Ids": ["1", "2"]}
                """)
        .when()
            .post("/");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", TARGET + "DeleteRule")
            .body("""
                {"Name": "test-rule"}
                """)
        .when()
            .post("/");

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteQueue")
            .formParam("QueueUrl", sqsQueueUrl)
        .when()
            .post("/");

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteQueue")
            .formParam("QueueUrl", transformerQueueUrl)
        .when()
            .post("/");
    }
}
