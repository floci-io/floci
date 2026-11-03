package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.notNullValue;

/**
 * A PartiQL INSERT checks that its key is new with a condition of its own, which the caller did not write, so a
 * partition key's name has to work there whatever an expression would make of it: a reserved word, a name a token
 * cannot start or hold.
 */
@QuarkusTest
class DynamoDbPartiQLInsertKeyNameIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"_id", "status", "my-key", "a.b"})
    void anInsertWorksWhateverThePartitionKeyIsNamed(String keyName) {
        String table = table(keyName);
        String first = "INSERT INTO \\\"" + table + "\\\" VALUE {'" + keyName + "':'first'}";
        String second = "INSERT INTO \\\"" + table + "\\\" VALUE {'" + keyName + "':'second'}";

        request("DynamoDB_20120810.ExecuteStatement", "{\"Statement\":\"" + first + "\"}").statusCode(200);
        getItem(table, keyName, "first").body("Item", notNullValue());
        request("DynamoDB_20120810.ExecuteStatement", "{\"Statement\":\"" + first + "\"}")
            .statusCode(400)
            .body("__type", endsWith("DuplicateItemException"));

        String transaction = "{\"TransactStatements\":[{\"Statement\":\"" + second + "\"}]}";
        request("DynamoDB_20120810.ExecuteTransaction", transaction).statusCode(200);
        getItem(table, keyName, "second").body("Item", notNullValue());
        request("DynamoDB_20120810.ExecuteTransaction", transaction)
            .statusCode(400)
            .body("__type", endsWith("TransactionCanceledException"));
    }

    private String table(String keyName) {
        String table = "partiql-key-" + UUID.randomUUID().toString().substring(0, 8);
        request("DynamoDB_20120810.CreateTable", """
                {
                  "TableName": "%s",
                  "AttributeDefinitions": [{"AttributeName":"%s","AttributeType":"S"}],
                  "KeySchema": [{"AttributeName":"%s","KeyType":"HASH"}],
                  "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(table, keyName, keyName))
            .statusCode(200);
        cleanup.register(() -> request("DynamoDB_20120810.DeleteTable", "{\"TableName\":\"" + table + "\"}")
            .statusCode(200));
        return table;
    }

    private static ValidatableResponse getItem(String table, String keyName, String key) {
        return request("DynamoDB_20120810.GetItem", """
                {"TableName":"%s","Key":{"%s":{"S":"%s"}}}
                """.formatted(table, keyName, key))
            .statusCode(200);
    }

    private static ValidatableResponse request(String target, String body) {
        return given()
                .header("X-Amz-Target", target)
                .contentType(CONTENT_TYPE)
                .body(body)
            .when()
                .post("/")
            .then();
    }
}
