package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventBridgeIntegrationTest {

    private static final String SQS_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String EVENT_BRIDGE_CONTENT_TYPE = "application/x-amz-json-1.1";

    private static String sinkQueueUrl;
    private static String transformerQueueUrl;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createSinkQueue() {
        sinkQueueUrl = given()
                .contentType(SQS_CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .body("{\"QueueName\":\"eb-integration-sink-queue\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("QueueUrl");
    }

    @Test
    @Order(2)
    void createTransformerQueue() {
        transformerQueueUrl = given()
                .contentType(SQS_CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .body("{\"QueueName\":\"eb-integration-xform-queue\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("QueueUrl");
    }

    @Test
    @Order(3)
    void createEventBridgeRule() {
        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.PutRule")
                .body("{\"Name\":\"eb-integration-test-rule\",\"EventPattern\":\"{\\\"source\\\":[\\\"com.mycompany.myapp\\\"]}\"}")
                .when().post("/")
                .then().statusCode(200);

        String queueArn = given()
                .contentType(SQS_CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .body("{\"QueueUrl\":\"" + sinkQueueUrl + "\",\"AttributeNames\":[\"All\"]}")
                .when()
                .post("/0000000000/eb-integration-sink-queue")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("Attributes.QueueArn");

        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.PutTargets")
                .body("{\"Rule\":\"eb-integration-test-rule\",\"Targets\":[{\"Id\":\"1\",\"Arn\":\"" + queueArn + "\"}]}")
                .when().post("/")
                .then().statusCode(200);
    }

    @Test
    @Order(4)
    void createInputTransformerTarget() {
        String queueArn = given()
                .contentType(SQS_CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .body("{\"QueueUrl\":\"" + transformerQueueUrl + "\",\"AttributeNames\":[\"All\"]}")
                .when()
                .post("/0000000000/eb-integration-xform-queue")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("Attributes.QueueArn");

        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.PutTargets")
                .body("""
                {
                  "Rule": "eb-integration-test-rule",
                  "Targets": [{
                    "Id": "2",
                    "Arn": "%s",
                    "InputTransformer": {
                      "InputPathsMap": {"src": "$.source", "detail": "$.detail-type"},
                      "InputTemplate": "{\\"source\\":\\"<src>\\",\\"type\\":\\"<detail>\\"}"
                    }
                  }]
                }
                """.formatted(queueArn))
                .when().post("/")
                .then().statusCode(200);
    }

    @Test
    @Order(5)
    void publishEventAndExpectMessageInQueue() {
        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.PutEvents")
                .body("""
                {
                  "Entries" : [
                      {
                        "Source": "com.mycompany.myapp",
                        "Detail": "{ \\"key1\\": \\"value1\\", \\"key2\\": \\"value2\\" }",
                        "Resources": [
                          "resource1",
                          "resource2"
                        ],
                        "DetailType": "myDetailType"
                      }
                  ]
                }
                """)
                .when().post("/")
                .then().statusCode(200);

        String expectedMessage = "\\{\"version\":\"0\",\"id\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\",\"source\":\"com.mycompany.myapp\"," +
                "\"detail-type\":\"myDetailType\",\"account\":\"000000000000\",\"time\":\"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]+Z\"," +
                "\"region\":\"us-east-1\",\"resources\":\\[\"resource1\",\"resource2\"],\"detail\":\\{\"key1\":\"value1\",\"key2\":\"value2\"},\"event-bus-name\":\"default\"}";

        given()
            .contentType(SQS_CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
            .body("{\"QueueUrl\":\"" + sinkQueueUrl + "\",\"MaxNumberOfMessages\":1}")
            .when()
                .post("/0000000000/eb-integration-sink-queue")
            .then()
                .statusCode(200)
                .body("Messages", hasSize(1))
                .body("Messages[0].Body", matchesPattern(expectedMessage));
    }

    @Test
    @Order(6)
    void putEvents_inputTransformer_transformsPayload() {
        // Drain any prior messages from the transformer queue
        given()
                .contentType(SQS_CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                .body("{\"QueueUrl\":\"" + transformerQueueUrl + "\",\"MaxNumberOfMessages\":10}")
                .when()
                .post("/0000000000/eb-integration-xform-queue");

        given()
                .contentType(EVENT_BRIDGE_CONTENT_TYPE)
                .header("X-Amz-Target", "AWSEvents.PutEvents")
                .body("""
                {
                  "Entries": [{
                    "Source": "com.mycompany.myapp",
                    "Detail": "{\\"orderId\\":\\"456\\"}",
                    "DetailType": "OrderPlaced"
                  }]
                }
                """)
                .when().post("/")
                .then().statusCode(200);

        given()
                .contentType(SQS_CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                .body("{\"QueueUrl\":\"" + transformerQueueUrl + "\",\"MaxNumberOfMessages\":1}")
                .when()
                .post("/0000000000/eb-integration-xform-queue")
                .then()
                .statusCode(200)
                .body("Messages", hasSize(1))
                .body("Messages[0].Body", notNullValue());
    }
}
