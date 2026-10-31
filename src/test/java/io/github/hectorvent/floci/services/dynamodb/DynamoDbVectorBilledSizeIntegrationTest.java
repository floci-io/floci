package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Collections;

import static io.github.hectorvent.floci.testing.DynamoDbRequests.ddb;
import static org.hamcrest.Matchers.equalTo;

/**
 * A read or write of a base table item bills an attribute a vector index reads at its name
 * plus four bytes per dimension. A GSI copy of the item still counts the numbers. Every vector
 * here holds 38-digit integers, 20 bytes each as numbers, so billing by the numbers would land
 * a unit higher.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DynamoDbVectorBilledSizeIntegrationTest {

    private static final String TABLE = "vector-billed-size";
    private static final String WIDE = "12345678901234567890123456789012345678";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void writesBillTheVectorAtFourBytesPerDimension() {
        ddb("CreateTable", """
                {
                  "TableName": "%s",
                  "AttributeDefinitions": [
                    {"AttributeName":"pk","AttributeType":"S"},
                    {"AttributeName":"gk","AttributeType":"S"}
                  ],
                  "KeySchema": [{"AttributeName":"pk","KeyType":"HASH"}],
                  "GlobalSecondaryIndexes": [{
                    "IndexName": "gsi",
                    "KeySchema": [{"AttributeName":"gk","KeyType":"HASH"}],
                    "Projection": {"ProjectionType":"ALL"}
                  }],
                  "VectorIndexes": [
                    {"IndexName":"vix","VectorAttribute":{"AttributeName":"embedding"},"Dimensions":3,
                     "DistanceFunction":"COSINE","Projection":{"ProjectionType":"ALL"}},
                    {"IndexName":"vkeys","VectorAttribute":{"AttributeName":"embedding"},"Dimensions":3,
                     "DistanceFunction":"COSINE","Projection":{"ProjectionType":"KEYS_ONLY"}},
                    {"IndexName":"vdims","VectorAttribute":{"AttributeName":"sixdimvec"},"Dimensions":6,
                     "DistanceFunction":"COSINE","Projection":{"ProjectionType":"ALL"}}
                  ],
                  "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(TABLE))
            .statusCode(200);

        put(item(1024, "a3", "embedding", 3, null)).body("ConsumedCapacity.CapacityUnits", equalTo(1.0f));
        put(item(1025, "b3", "embedding", 3, null)).body("ConsumedCapacity.CapacityUnits", equalTo(2.0f));
        put(item(1024, "a6", "sixdimvec", 6, null)).body("ConsumedCapacity.CapacityUnits", equalTo(1.0f));

        put(item(1000, "u3", "embedding", 3, null));
        ddb("UpdateItem", """
                {"TableName":"%s","Key":{"pk":{"S":"u3"}},"UpdateExpression":"SET q = :q",
                 "ExpressionAttributeValues":{":q":{"S":"%s"}},"ReturnConsumedCapacity":"TOTAL"}
                """.formatted(TABLE, "x".repeat(23)))
            .statusCode(200)
            .body("ConsumedCapacity.CapacityUnits", equalTo(1.0f));
        ddb("DeleteItem", """
                {"TableName":"%s","Key":{"pk":{"S":"a3"}},"ReturnConsumedCapacity":"TOTAL"}
                """.formatted(TABLE))
            .statusCode(200)
            .body("ConsumedCapacity.CapacityUnits", equalTo(1.0f));
        ddb("TransactWriteItems", """
                {"TransactItems":[{"Put":{"TableName":"%s","Item":%s}}],"ReturnConsumedCapacity":"TOTAL"}
                """.formatted(TABLE, item(1024, "t3", "embedding", 3, null)))
            .statusCode(200)
            .body("ConsumedCapacity[0].CapacityUnits", equalTo(2.0f));
    }

    @Test
    @Order(2)
    void aGsiCopyStillCountsTheNumbers() {
        ddb("PutItem", """
                {"TableName":"%s","Item":%s,"ReturnConsumedCapacity":"INDEXES"}
                """.formatted(TABLE, item(1024, "g3", "embedding", 3, "k")))
            .statusCode(200)
            .body("ConsumedCapacity.Table.CapacityUnits", equalTo(1.0f))
            .body("ConsumedCapacity.GlobalSecondaryIndexes.gsi.CapacityUnits", equalTo(2.0f));
    }

    @Test
    @Order(3)
    void readsBillTheVectorAtFourBytesPerDimension() {
        put(item(4096, "r6", "sixdimvec", 6, null));

        ddb("GetItem", """
                {"TableName":"%s","Key":{"pk":{"S":"r6"}},"ConsistentRead":true,"ReturnConsumedCapacity":"TOTAL"}
                """.formatted(TABLE))
            .statusCode(200)
            .body("ConsumedCapacity.CapacityUnits", equalTo(1.0f));
        ddb("Query", """
                {"TableName":"%s","KeyConditionExpression":"pk = :p","ExpressionAttributeValues":{":p":{"S":"r6"}},
                 "ConsistentRead":true,"ReturnConsumedCapacity":"TOTAL"}
                """.formatted(TABLE))
            .statusCode(200)
            .body("ConsumedCapacity.CapacityUnits", equalTo(1.0f));
        ddb("TransactGetItems", """
                {"TransactItems":[{"Get":{"TableName":"%s","Key":{"pk":{"S":"r6"}}}}],"ReturnConsumedCapacity":"TOTAL"}
                """.formatted(TABLE))
            .statusCode(200)
            .body("ConsumedCapacity[0].CapacityUnits", equalTo(2.0f));
        ddb("BatchGetItem", """
                {"RequestItems":{"%s":{"Keys":[{"pk":{"S":"r6"}}],"ConsistentRead":true}},"ReturnConsumedCapacity":"TOTAL"}
                """.formatted(TABLE))
            .statusCode(200)
            .body("ConsumedCapacity[0].CapacityUnits", equalTo(1.0f));
    }

    @Test
    @Order(4)
    void anIndexAddedLaterBillsWhateverTheAttributeHoldsOnceActive() {
        String table = TABLE + "-later";
        ddb("CreateTable", """
                {"TableName":"%s","AttributeDefinitions":[{"AttributeName":"pk","AttributeType":"S"}],
                 "KeySchema":[{"AttributeName":"pk","KeyType":"HASH"}],"BillingMode":"PAY_PER_REQUEST"}
                """.formatted(table))
            .statusCode(200);
        // 4084 bytes besides the string, so 4096 once it costs 12 bytes as a 3 dimension vector.
        ddb("PutItem", """
                {"TableName":"%s","Item":{"pk":{"S":"s"},"embedding":{"S":"%s"},"pad":{"S":"%s"}}}
                """.formatted(table, "x".repeat(2000), "x".repeat(4084 - 2 - 1 - 9 - 3)))
            .statusCode(200);
        String get = """
                {"TableName":"%s","Key":{"pk":{"S":"s"}},"ConsistentRead":true,"ReturnConsumedCapacity":"TOTAL"}
                """.formatted(table);

        ddb("UpdateTable", """
                {"TableName":"%s","VectorIndexUpdates":[{"Create":{"IndexName":"vix",
                 "VectorAttribute":{"AttributeName":"embedding"},"Dimensions":3,"DistanceFunction":"COSINE",
                 "Projection":{"ProjectionType":"KEYS_ONLY"}}}]}
                """.formatted(table))
            .statusCode(200);
        ddb("GetItem", get).statusCode(200).body("ConsumedCapacity.CapacityUnits", equalTo(2.0f));

        ddb("DescribeTable", """
                {"TableName":"%s"}
                """.formatted(table))
            .statusCode(200)
            .body("Table.VectorIndexes[0].IndexStatus", equalTo("ACTIVE"));
        ddb("GetItem", get).statusCode(200).body("ConsumedCapacity.CapacityUnits", equalTo(1.0f));
    }

    private static ValidatableResponse put(String item) {
        return ddb("PutItem", """
                {"TableName":"%s","Item":%s,"ReturnConsumedCapacity":"TOTAL"}
                """.formatted(TABLE, item))
            .statusCode(200);
    }

    /** An item measuring {@code billedBytes} with its vector at four bytes per dimension. */
    private static String item(int billedBytes, String key, String attribute, int dims, String gk) {
        String vector = String.join(",", Collections.nCopies(dims, "{\"N\":\"" + WIDE + "\"}"));
        String gsiKey = gk == null ? "" : ",\"gk\":{\"S\":\"" + gk + "\"}";
        return """
                {"pk":{"S":"%s"},"%s":{"L":[%s]}%s,"pad":{"S":"%s"}}"""
                .formatted(key, attribute, vector, gsiKey, padFor(billedBytes, key, attribute, dims, gk));
    }

    private static String padFor(int billedBytes, String key, String attribute, int dims, String gk) {
        int fixed = "pk".length() + key.length() + attribute.length() + 4 * dims + "pad".length()
                + (gk == null ? 0 : "gk".length() + gk.length());
        return "x".repeat(billedBytes - fixed);
    }
}
