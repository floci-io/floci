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
    void tagAndUntagDeliveryStream() {
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.TagDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + STREAM_NAME + "\", \"Tags\": [ { \"Key\": \"env\", \"Value\": \"prod\" }, { \"Key\": \"owner\", \"Value\": \"team-a\" } ] }")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.ListTagsForDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + STREAM_NAME + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(2))
            .body("Tags.find { it.Key == 'env' }.Value", equalTo("prod"))
            .body("Tags.find { it.Key == 'owner' }.Value", equalTo("team-a"))
            .body("HasMoreTags", equalTo(false));

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.UntagDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + STREAM_NAME + "\", \"TagKeys\": [ \"env\" ] }")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "Firehose_20150804.ListTagsForDeliveryStream")
            .body("{ \"DeliveryStreamName\": \"" + STREAM_NAME + "\" }")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("owner"))
            .body("Tags[0].Value", equalTo("team-a"));
    }

    @Test
    @Order(4)
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
    @Order(5)
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
