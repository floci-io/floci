package io.github.hectorvent.floci.services.eventbridge;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventBridgeIntegrationTest {

    private static final String SQS_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String EVENT_BRIDGE_CONTENT_TYPE = "application/x-amz-json-1.1";

    private static String sinkQueueUrl;

    @BeforeAll
    static void configureRestAssured() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig()
                        .encodeContentTypeAs(SQS_CONTENT_TYPE, ContentType.TEXT)
                        .encodeContentTypeAs(EVENT_BRIDGE_CONTENT_TYPE, ContentType.TEXT)
        );
    }

    @Test
    @Order(1)
    void createSinkQueue() {
        sinkQueueUrl =  given()
                .contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .body("{\"QueueName\":\"integration-test-queue\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("QueueUrl");
    }

    @Test
    @Order(2)
    void createEventBridgeRule() {
        given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AWSEvents.PutRule")
                .body("{\"Name\":\"integration-test-rule\"}")
                .when().post("/")
                .then().statusCode(200);

        String queueArn = given()
                .contentType(SQS_CONTENT_TYPE)
                .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .body("{\"QueueUrl\":\"" + sinkQueueUrl + "\",\"AttributeNames\":[\"All\"]}")
                .when()
                .post("/0000000000/integration-test-queue")
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("Attributes.QueueArn");

        given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AWSEvents.PutTargets")
                .body("{\"Rule\":\"integration-test-rule\",\"Targets\":[{\"Arn\":\"" + queueArn+"\"}]}")
                .when().post("/")
                .then().statusCode(200);
    }

    @Test
    @Order(3)
    void publishEventAndExpectMessageInQueue() {
        given()
                .contentType("application/x-amz-json-1.1")
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
                "\"detail-type\":\"myDetailType\",\"account\":\"000000000000\",\"time\":\"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}.[0-9]{9}Z\"," +
                "\"region\":\"us-east-1\",\"resources\":\\[\"resource1\",\"resource2\"],\"detail\":\\{\"key1\":\"value1\",\"key2\":\"value2\"},\"event-bus-name\":\"default\"}";

        given()
            .contentType(SQS_CONTENT_TYPE)
            .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
            .body("{\"QueueUrl\":\"" + sinkQueueUrl + "\",\"MaxNumberOfMessages\":1}")
            .when()
                .post("/0000000000/integration-test-queue")
            .then()
                .statusCode(200)
                .body("Messages", hasSize(1))
                .body("Messages[0].Body", matchesPattern(expectedMessage));
    }
}
