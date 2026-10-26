package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.testing.DynamoDbPartitionedWalStorageProfile;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static io.github.hectorvent.floci.testing.DynamoDbPartitionedWalStorageProfile.STORAGE_DIR;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code per-table} item layout in {@code wal} mode, end to end through the application:
 * the configuration selects the partitioned item store, a write lands in its journal rather than
 * in a single items file, and a compaction moves it into shard files, one set per account.
 */
@QuarkusTest
@TestProfile(DynamoDbPartitionedWalStorageProfile.class)
class DynamoDbPartitionedWalStorageIntegrationTest {

    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String AUTH_ACCOUNT_1 =
            "AWS4-HMAC-SHA256 Credential=000000000001/20260215/us-east-1/dynamodb/aws4_request, SignedHeaders=host, Signature=abc";
    private static final String AUTH_ACCOUNT_2 =
            "AWS4-HMAC-SHA256 Credential=000000000002/20260215/us-east-1/dynamodb/aws4_request, SignedHeaders=host, Signature=abc";

    @Inject
    StorageFactory storageFactory;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void itemsGoThroughTheJournalIntoPerAccountShardFiles() throws IOException {
        // The directory outlives a run, so each run uses its own table name.
        String table = "partitioned-wal-" + UUID.randomUUID();
        Path itemsDirectory = Path.of(STORAGE_DIR, "dynamodb-items");
        Path journal = itemsDirectory.resolve("journal.wal");
        try {
            for (String auth : List.of(AUTH_ACCOUNT_1, AUTH_ACCOUNT_2)) {
                call(auth, "CreateTable", """
                        {"TableName": "%s", "BillingMode": "PAY_PER_REQUEST",
                         "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                         "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}]}
                        """.formatted(table));
            }
            call(AUTH_ACCOUNT_1, "PutItem", item(table, "one"));
            call(AUTH_ACCOUNT_2, "PutItem", item(table, "two"));

            assertTrue(Files.size(journal) > 0, "the writes are in the journal");
            assertFalse(Files.exists(Path.of(STORAGE_DIR, "dynamodb-items.json")));

            storageFactory.flushAll();

            assertEquals(0, Files.size(journal), "compaction empties the journal");
            assertEquals(2, shardFilesOf(itemsDirectory, table), "one shard file per account's table");
            getItem(AUTH_ACCOUNT_1, table, "one");
            getItem(AUTH_ACCOUNT_2, table, "two");
        } finally {
            for (String auth : List.of(AUTH_ACCOUNT_1, AUTH_ACCOUNT_2)) {
                given()
                    .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
                    .header("Authorization", auth)
                    .contentType(DYNAMODB_CONTENT_TYPE)
                    .body("{\"TableName\": \"%s\"}".formatted(table))
                .when().post("/");
            }
        }
    }

    private static String item(String table, String value) {
        return """
                {"TableName": "%s", "Item": {"pk": {"S": "item"}, "v": {"S": "%s"}}}
                """.formatted(table, value);
    }

    private static void call(String auth, String action, String body) {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810." + action)
            .header("Authorization", auth)
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body(body)
        .when().post("/")
        .then().statusCode(200);
    }

    private static void getItem(String auth, String table, String expected) {
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
            .header("Authorization", auth)
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("{\"TableName\": \"%s\", \"Key\": {\"pk\": {\"S\": \"item\"}}}".formatted(table))
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Item.v.S", equalTo(expected));
    }

    /** Shard files whose readable name prefix carries the table name, under any account. */
    private static long shardFilesOf(Path directory, String table) throws IOException {
        String safeName = table.replaceAll("[^A-Za-z0-9._-]", "_");
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(f -> f.getFileName().toString())
                    .filter(name -> name.endsWith(".json") && name.contains(safeName))
                    .count();
        }
    }
}
