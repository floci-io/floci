package io.floci.aws.services.kinesis;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class KinesisListStreamsIntegrationTest {

    private final List<String> streams = new ArrayList<>();

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void deleteOwnedStreams() {
        for (String stream : streams) {
            kinesis("DeleteStream", Map.of("StreamName", stream)).then().statusCode(200);
        }
    }

    @Test
    void followsNameBasedPagesOverTheAwsJsonEndpoint() {
        String prefix = "page-" + UUID.randomUUID();
        for (String suffix : List.of("-a", "-b")) {
            String stream = prefix + suffix;
            kinesis("CreateStream", Map.of("StreamName", stream, "ShardCount", 1)).then().statusCode(200);
            streams.add(stream);
        }
        Response first = kinesis("ListStreams", Map.of("Limit", 1, "ExclusiveStartStreamName", prefix))
                .then().statusCode(200).extract().response();
        assertEquals(List.of(streams.get(0)), first.jsonPath().getList("StreamNames", String.class));
        assertTrue(first.jsonPath().getBoolean("HasMoreStreams"));
        String token = first.jsonPath().getString("NextToken");
        assertTrue(token != null && !token.isEmpty());
        Response tokenPage = kinesis("ListStreams", Map.of("Limit", 1, "NextToken", token))
                .then().statusCode(200).extract().response();
        assertEquals(List.of(streams.get(1)), tokenPage.jsonPath().getList("StreamNames", String.class));
        Response last = kinesis("ListStreams", Map.of("Limit", 1, "ExclusiveStartStreamName", streams.get(0)))
                .then().statusCode(200).extract().response();
        assertEquals(List.of(streams.get(1)), last.jsonPath().getList("StreamNames", String.class));
    }

    private Response kinesis(String action, Map<String, ?> body) {
        return given().contentType("application/x-amz-json-1.1")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261021/eu-central-2/kinesis/aws4_request")
                .header("X-Amz-Target", "Kinesis_20131202." + action).body(body).post("/");
    }
}
