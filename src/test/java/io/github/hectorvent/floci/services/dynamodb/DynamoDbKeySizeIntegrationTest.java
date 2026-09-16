package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class DynamoDbKeySizeIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @CsvSource({"S,pk,2048", "S,sk,1024", "B,pk,2048", "B,sk,1024"})
    void oversizedPrimaryKeyReturnsValidationExceptionWithoutStoringItem(String type, String keyName, int limit) {
        String table = "KeySizes-" + UUID.randomUUID();
        request("CreateTable", Map.of(
                "TableName", table,
                "KeySchema", List.of(
                        Map.of("AttributeName", "pk", "KeyType", "HASH"),
                        Map.of("AttributeName", "sk", "KeyType", "RANGE")),
                "AttributeDefinitions", List.of(
                        Map.of("AttributeName", "pk", "AttributeType", type),
                        Map.of("AttributeName", "sk", "AttributeType", type)),
                "BillingMode", "PAY_PER_REQUEST")).statusCode(200);
        try {
            request("PutItem", Map.of("TableName", table, "Item", Map.of(
                    "pk", value(type, "pk".equals(keyName) ? limit : 1),
                    "sk", value(type, "sk".equals(keyName) ? limit : 1)))).statusCode(200);

            request("PutItem", Map.of("TableName", table, "Item", Map.of(
                    "pk", value(type, "pk".equals(keyName) ? limit + 1 : 1),
                    "sk", value(type, "sk".equals(keyName) ? limit + 1 : 1))))
                    .statusCode(400)
                    .body("__type", equalTo("ValidationException"))
                    .body("message", equalTo("One or more parameter values were invalid: Size of "
                            + ("pk".equals(keyName) ? "hashkey" : "rangekey")
                            + " has exceeded the maximum size limit of " + limit + " bytes"));

            request("Scan", Map.of("TableName", table, "Select", "COUNT"))
                    .statusCode(200).body("Count", equalTo(1));
        } finally {
            request("DeleteTable", Map.of("TableName", table)).statusCode(200);
        }
    }

    private static Map<String, String> value(String type, int size) {
        return Map.of(type, "B".equals(type)
                ? Base64.getEncoder().encodeToString(new byte[size]) : "x".repeat(size));
    }

    private static ValidatableResponse request(String action, Map<String, ?> body) {
        return given()
                .header("X-Amz-Target", "DynamoDB_20120810." + action)
                .contentType("application/x-amz-json-1.0")
                .body(body)
                .post("/")
                .then();
    }
}
