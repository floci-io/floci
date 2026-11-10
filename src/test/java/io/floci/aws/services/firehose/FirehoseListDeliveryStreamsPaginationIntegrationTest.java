package io.floci.aws.services.firehose;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class FirehoseListDeliveryStreamsPaginationIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "Firehose_20150804.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void limitCapsThePageAndReportsMoreDeliveryStreams() {
        String prefix = "page-" + UUID.randomUUID() + "-";
        List<String> created = new ArrayList<>();
        try {
            for (int index = 2; index >= 0; index--) {
                String name = prefix + index;
                given()
                        .header("X-Amz-Target", TARGET_PREFIX + "CreateDeliveryStream")
                        .contentType(CONTENT_TYPE)
                        .body(Map.of("DeliveryStreamName", name))
                        .post("/")
                        .then().statusCode(200);
                created.add(name);
            }

            Response page = given()
                    .header("X-Amz-Target", TARGET_PREFIX + "ListDeliveryStreams")
                    .contentType(CONTENT_TYPE)
                    .body(Map.of("Limit", 1))
                    .post("/")
                    .then().statusCode(200).extract().response();
            assertEquals(1, page.jsonPath().getList("DeliveryStreamNames").size());
            assertTrue(page.jsonPath().getBoolean("HasMoreDeliveryStreams"));

            Response firstPageFromCursor = given()
                    .header("X-Amz-Target", TARGET_PREFIX + "ListDeliveryStreams")
                    .contentType(CONTENT_TYPE)
                    .body(Map.of("Limit", 1, "ExclusiveStartDeliveryStreamName", prefix))
                    .post("/")
                    .then().statusCode(200).extract().response();
            assertEquals(List.of(prefix + "0"), firstPageFromCursor.jsonPath().getList("DeliveryStreamNames"));
            assertTrue(firstPageFromCursor.jsonPath().getBoolean("HasMoreDeliveryStreams"));

            Response secondPage = given()
                    .header("X-Amz-Target", TARGET_PREFIX + "ListDeliveryStreams")
                    .contentType(CONTENT_TYPE)
                    .body(Map.of("Limit", 1, "ExclusiveStartDeliveryStreamName", prefix + "0"))
                    .post("/")
                    .then().statusCode(200).extract().response();
            assertEquals(List.of(prefix + "1"), secondPage.jsonPath().getList("DeliveryStreamNames"));

            Response rest = given()
                    .header("X-Amz-Target", TARGET_PREFIX + "ListDeliveryStreams")
                    .contentType(CONTENT_TYPE)
                    .body(Map.of("Limit", 10000, "ExclusiveStartDeliveryStreamName", prefix + "1"))
                    .post("/")
                    .then().statusCode(200).extract().response();
            assertTrue(rest.jsonPath().getList("DeliveryStreamNames", String.class).contains(prefix + "2"));

            Response terminal = given()
                    .header("X-Amz-Target", TARGET_PREFIX + "ListDeliveryStreams")
                    .contentType(CONTENT_TYPE)
                    .body(Map.of("Limit", 1, "ExclusiveStartDeliveryStreamName", "z".repeat(64)))
                    .post("/")
                    .then().statusCode(200).extract().response();
            assertEquals(List.of(), terminal.jsonPath().getList("DeliveryStreamNames"));
            assertFalse(terminal.jsonPath().getBoolean("HasMoreDeliveryStreams"));
        } finally {
            for (String name : created) {
                given()
                        .header("X-Amz-Target", TARGET_PREFIX + "DeleteDeliveryStream")
                        .contentType(CONTENT_TYPE)
                        .body(Map.of("DeliveryStreamName", name))
                        .post("/")
                        .then().statusCode(200);
            }
        }
    }

    @Test
    void rejectsLimitsOutsideAwsModelRange() {
        for (long limit : List.of(0L, 10001L, 4294967297L)) {
            given()
                    .header("X-Amz-Target", TARGET_PREFIX + "ListDeliveryStreams")
                    .contentType(CONTENT_TYPE)
                    .body(Map.of("Limit", limit))
                    .post("/")
                    .then().statusCode(400).body("__type", equalTo("ValidationException"));
        }
    }

    @Test
    void rejectsInvalidStartDeliveryStreamNames() {
        for (String name : List.of("", "bad/name", "x".repeat(65))) {
            given()
                    .header("X-Amz-Target", TARGET_PREFIX + "ListDeliveryStreams")
                    .contentType(CONTENT_TYPE)
                    .body(Map.of("ExclusiveStartDeliveryStreamName", name))
                    .post("/")
                    .then().statusCode(400).body("__type", equalTo("ValidationException"));
        }
    }
}
