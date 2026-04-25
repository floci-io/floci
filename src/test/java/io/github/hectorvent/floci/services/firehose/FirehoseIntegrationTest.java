package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirehoseIntegrationTest {

    private static final String STREAM_NAME = "test-delivery-stream";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createDeliveryStream() {
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.CreateDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + STREAM_NAME + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamARN", notNullValue());
    }

    @Test
    @Order(2)
    void describeDeliveryStream() {
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + STREAM_NAME + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeliveryStreamDescription.DeliveryStreamName", equalTo(STREAM_NAME));
    }

    @Test
    @Order(3)
    void deleteDeliveryStream() {
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.DeleteDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + STREAM_NAME + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(4)
    void describeDeletedDeliveryStreamReturnsNotFound() {
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.DescribeDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + STREAM_NAME + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
