package io.floci.aws.services.kinesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.IndependentServiceSettingsProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Proves the ListShards NextToken expiry is driven by the
 * {@code floci.services.kinesis.list-shards-next-token-ttl-millis} setting: with the TTL lowered to
 * zero, the second page is rejected instead of waiting out AWS's 300 second window.
 *
 * <p>The setting comes from {@link IndependentServiceSettingsProfile}, which this class shares
 * with other classes that each call a different service.
 */
@QuarkusTest
@TestProfile(IndependentServiceSettingsProfile.class)
class KinesisListShardsTokenExpiryIntegrationTest {

    private static final String KINESIS_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String STREAM_NAME = "kinesis-expiry-test";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listShardsRejectsAnExpiredNextToken() {
        given()
            .header("X-Amz-Target", "Kinesis_20131202.CreateStream")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"StreamName\": \"" + STREAM_NAME + "\", \"ShardCount\": 2}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
        cleanup.register(() -> given()
            .header("X-Amz-Target", "Kinesis_20131202.DeleteStream")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"StreamName\": \"" + STREAM_NAME + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200));

        ObjectNode first = MAPPER.createObjectNode();
        first.put("StreamName", STREAM_NAME);
        first.put("MaxResults", 1);
        Response firstPage = given()
            .header("X-Amz-Target", "Kinesis_20131202.ListShards")
            .contentType(KINESIS_CONTENT_TYPE)
            .body(first.toString())
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().response();
        String token = firstPage.jsonPath().getString("NextToken");

        ObjectNode second = MAPPER.createObjectNode();
        second.put("NextToken", token);
        given()
            .header("X-Amz-Target", "Kinesis_20131202.ListShards")
            .contentType(KINESIS_CONTENT_TYPE)
            .body(second.toString())
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ExpiredNextTokenException"));
    }
}
