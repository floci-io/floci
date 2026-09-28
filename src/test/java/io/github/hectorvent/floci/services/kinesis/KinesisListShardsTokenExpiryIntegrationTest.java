package io.github.hectorvent.floci.services.kinesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Proves the ListShards NextToken expiry is driven by the
 * {@code floci.services.kinesis.list-shards-next-token-ttl-millis} setting: with the TTL lowered to
 * zero, the second page is rejected instead of waiting out AWS's 300 second window.
 */
@QuarkusTest
@TestProfile(KinesisListShardsTokenExpiryIntegrationTest.ZeroTtlProfile.class)
class KinesisListShardsTokenExpiryIntegrationTest {

    private static final String KINESIS_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listShardsRejectsAnExpiredNextToken() {
        given()
            .header("X-Amz-Target", "Kinesis_20131202.CreateStream")
            .contentType(KINESIS_CONTENT_TYPE)
            .body("{\"StreamName\": \"kinesis-expiry-test\", \"ShardCount\": 2}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        ObjectNode first = MAPPER.createObjectNode();
        first.put("StreamName", "kinesis-expiry-test");
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

    public static final class ZeroTtlProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.kinesis.list-shards-next-token-ttl-millis", "0");
        }
    }
}
