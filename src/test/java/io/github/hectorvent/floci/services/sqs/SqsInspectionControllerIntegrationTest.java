package io.github.hectorvent.floci.services.sqs;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class SqsInspectionControllerIntegrationTest {

    private static final String QUEUE_NAME = "inspection-test-queue";
    private static final String CONTENT_TYPE = "application/x-www-form-urlencoded";

    private String queueUrl;

    @BeforeEach
    void setUp() {
        queueUrl = given()
            .contentType(CONTENT_TYPE)
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", QUEUE_NAME)
        .when().post("/")
        .then().statusCode(200)
            .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");

        given()
            .contentType(CONTENT_TYPE)
            .formParam("Action", "PurgeQueue")
            .formParam("QueueUrl", queueUrl)
        .when().post("/").then().statusCode(200);
    }

    @Test
    @DisplayName("GET /_aws/sqs/messages returns empty list for empty queue")
    void shouldReturnEmptyListForEmptyQueue() {
        given()
            .queryParam("QueueUrl", queueUrl)
        .when().get("/_aws/sqs/messages")
        .then()
            .statusCode(200)
            .body("messages", hasSize(0));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"us-east-1"})
    @DisplayName("GET /_aws/sqs/messages returns all messages without consuming them")
    void shouldReturnMessagesWithoutConsuming(String region) {
        given().contentType(CONTENT_TYPE)
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MessageBody", "hello world")
        .when().post("/").then().statusCode(200);

        given().contentType(CONTENT_TYPE)
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MessageBody", "second message")
        .when().post("/").then().statusCode(200);

        RequestSpecification requestSpecification = given();
        String authorization = String.format("AWS4-HMAC-SHA256 Credential=AKID/20260101/%s/textract/aws4_request", region);
        if (region != null) {
          requestSpecification.header("Authorization", authorization);
        }
        requestSpecification
            .queryParam("QueueUrl", queueUrl)
        .when().get("/_aws/sqs/messages")
        .then()
            .statusCode(200)
            .body("messages", hasSize(2))
            .body("messages[0].Body", notNullValue())
            .body("messages[0].MessageId", notNullValue())
            .body("messages[0].MD5OfBody", notNullValue())
            .body("messages[0].Attributes.SentTimestamp", notNullValue())
            .body("messages[0].Attributes.ApproximateReceiveCount", equalTo("0"));

        // messages must still be there after peek
        requestSpecification = given();
        if (region != null) {
          requestSpecification.header("Authorization", authorization);
        }
        requestSpecification
            .queryParam("QueueUrl", queueUrl)
        .when().get("/_aws/sqs/messages")
        .then()
            .statusCode(200)
            .body("messages", hasSize(2));
    }

    @Test
    @DisplayName("DELETE /_aws/sqs/messages purges the queue")
    void shouldPurgeQueueOnDelete() {
        given().contentType(CONTENT_TYPE)
            .formParam("Action", "SendMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MessageBody", "to be purged")
        .when().post("/").then().statusCode(200);

        given()
            .queryParam("QueueUrl", queueUrl)
        .when().delete("/_aws/sqs/messages")
        .then()
            .statusCode(200);

        given()
            .queryParam("QueueUrl", queueUrl)
        .when().get("/_aws/sqs/messages")
        .then()
            .statusCode(200)
            .body("messages", hasSize(0));
    }

    @Test
    @DisplayName("GET /_aws/sqs/messages returns 400 when QueueUrl is missing")
    void shouldReturn400WhenQueueUrlMissing() {
        given()
        .when().get("/_aws/sqs/messages")
        .then()
            .statusCode(400);
    }

    @Test
    @DisplayName("GET /_aws/sqs/messages returns 400 when retrieving messages from a queue in a different region")
    void shouldReturn400WhenRetrievingMessagesFromQueueInDifferentRegion() {
        given()
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/eu-west-1/textract/aws4_request")
            .queryParam("QueueUrl", queueUrl)
        .when().get("/_aws/sqs/messages")
        .then()
            .statusCode(400);
    }

    @Test
    @DisplayName("DELETE /_aws/sqs/messages returns 400 retrieving messages from a queue in a different region")
    void shouldReturn400WhenPurgingMessagesFromQueueInDifferentRegion() {
        given()
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/eu-west-1/textract/aws4_request")
            .queryParam("QueueUrl", queueUrl)
        .when().delete("/_aws/sqs/messages")
        .then()
            .statusCode(400);
    }
}
