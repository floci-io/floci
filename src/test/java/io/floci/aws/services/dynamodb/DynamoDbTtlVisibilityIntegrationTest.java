package io.floci.aws.services.dynamodb;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class DynamoDbTtlVisibilityIntegrationTest {

    private static final String REGION = "us-east-1";

    @Inject
    DynamoDbService service;

    @Inject
    DynamoDbTtlService ttlService;

    private String tableName;
    private long now;

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createTtlTable() {
        ttlService.pause();
        tableName = "TtlVisibility" + UUID.randomUUID().toString().replace("-", "");
        now = Instant.now().getEpochSecond();
        call("CreateTable", """
                {"TableName":"%s","KeySchema":[{"AttributeName":"pk","KeyType":"HASH"}],
                 "AttributeDefinitions":[{"AttributeName":"pk","AttributeType":"S"}],
                 "BillingMode":"PAY_PER_REQUEST"}
                """.formatted(tableName)).statusCode(200);
        call("UpdateTimeToLive", """
                {"TableName":"%s","TimeToLiveSpecification":{"Enabled":true,"AttributeName":"ttl"}}
                """.formatted(tableName)).statusCode(200);
        putItem("expired", now - 3600);
        putItem("future", now + 3600);
    }

    @AfterEach
    void cleanUp() {
        try {
            service.deleteTable(tableName, REGION);
        } finally {
            ttlService.resume();
        }
    }

    @Test
    void getItemReturnsExpiredItemsUntilTheSweepDeletesThem() {
        getExpired().statusCode(200).body("Item.pk.S", equalTo("expired"));

        service.deleteExpiredItems();

        getExpired().statusCode(200).body("Item", nullValue());
        call("Scan", "{\"TableName\":\"%s\"}".formatted(tableName))
                .statusCode(200).body("Count", equalTo(1)).body("Items[0].pk.S", equalTo("future"));
    }

    @Test
    void queryReturnsExpiredItemsUnlessTheCallerFiltersThem() {
        call("Query", """
                {"TableName":"%s","KeyConditionExpression":"pk = :pk",
                 "ExpressionAttributeValues":{":pk":{"S":"expired"}}}
                """.formatted(tableName))
                .statusCode(200).body("Count", equalTo(1)).body("Items[0].pk.S", equalTo("expired"));
        call("Query", """
                {"TableName":"%s","KeyConditionExpression":"pk = :pk","FilterExpression":"#ttl > :now",
                 "ExpressionAttributeNames":{"#ttl":"ttl"},
                 "ExpressionAttributeValues":{":pk":{"S":"expired"},":now":{"N":"%d"}}}
                """.formatted(tableName, now))
                .statusCode(200).body("Count", equalTo(0)).body("ScannedCount", equalTo(1));
    }

    @Test
    void legacyTtlQueryFilterUsesTheFilteredCount() {
        String request = """
                {"TableName":"%s","KeyConditions":{"pk":{"ComparisonOperator":"EQ",
                 "AttributeValueList":[{"S":"expired"}]}},
                 "QueryFilter":{"ttl":{"ComparisonOperator":"GT","AttributeValueList":[{"N":"%d"}]}},
                 "Select":"COUNT"}
                """.formatted(tableName, now);
        call("Query", request).statusCode(200)
                .body("Count", equalTo(0)).body("ScannedCount", equalTo(1)).body("Items", nullValue());
    }

    @Test
    void scanReturnsExpiredItemsAndPreservesExplicitFiltersAndPagination() {
        call("Scan", "{\"TableName\":\"%s\"}".formatted(tableName))
                .statusCode(200).body("Count", equalTo(2)).body("ScannedCount", equalTo(2));
        call("Scan", """
                {"TableName":"%s","FilterExpression":"#ttl > :now",
                 "ExpressionAttributeNames":{"#ttl":"ttl"},"ExpressionAttributeValues":{":now":{"N":"%d"}}}
                """.formatted(tableName, now))
                .statusCode(200).body("Count", equalTo(1)).body("ScannedCount", equalTo(2))
                .body("Items[0].pk.S", equalTo("future"));
        call("Scan", "{\"TableName\":\"%s\",\"Limit\":1}".formatted(tableName))
                .statusCode(200).body("Count", equalTo(1)).body("Items[0].pk.S", equalTo("expired"))
                .body("LastEvaluatedKey.pk.S", equalTo("expired"));
        call("Scan", """
                {"TableName":"%s","ExclusiveStartKey":{"pk":{"S":"expired"}}}
                """.formatted(tableName))
                .statusCode(200).body("Count", equalTo(1)).body("Items[0].pk.S", equalTo("future"));
    }

    @Test
    void batchTransactionalAndPartiQlReadsReturnPendingTtlItems() {
        call("BatchGetItem", """
                {"RequestItems":{"%s":{"Keys":[{"pk":{"S":"expired"}}]}}}
                """.formatted(tableName))
                .statusCode(200).body("Responses." + tableName + "[0].pk.S", equalTo("expired"));
        call("TransactGetItems", """
                {"TransactItems":[{"Get":{"TableName":"%s","Key":{"pk":{"S":"expired"}}}}]}
                """.formatted(tableName))
                .statusCode(200).body("Responses[0].Item.pk.S", equalTo("expired"));
        call("ExecuteStatement", """
                {"Statement":"SELECT * FROM \\\"%s\\\" WHERE pk = 'expired'"}
                """.formatted(tableName))
                .statusCode(200).body("Items[0].pk.S", equalTo("expired"));
    }

    @Test
    void wholeTableReadsIncludePendingTtlItems() {
        Set<String> keys = service.liveItems(service.settledTable(tableName, REGION), REGION).stream()
                .map(item -> item.path("pk").path("S").asText())
                .collect(Collectors.toSet());
        assertEquals(Set.of("expired", "future"), keys);
    }

    private ValidatableResponse getExpired() {
        return call("GetItem", """
                {"TableName":"%s","Key":{"pk":{"S":"expired"}},"ConsistentRead":true}
                """.formatted(tableName));
    }

    private void putItem(String key, long ttl) {
        call("PutItem", """
                {"TableName":"%s","Item":{"pk":{"S":"%s"},"ttl":{"N":"%d"}}}
                """.formatted(tableName, key, ttl)).statusCode(200);
    }

    private ValidatableResponse call(String action, String body) {
        return given().header("X-Amz-Target", "DynamoDB_20120810." + action)
                .contentType("application/x-amz-json-1.0").body(body)
                .when().post("/").then();
    }
}
