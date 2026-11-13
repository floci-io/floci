package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DynamoDbMalformedAttributeValueIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void configureJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void putItemRejectsMalformedNestedValueWithoutChangingStoredItems() throws Exception {
        String table = createTable();
        try {
            put(table, item("existing", true), 200);

            assertValidation(put(table, item("existing", false), 400));
            assertValidation(put(table, item("new", false), 400));

            assertEquals(item("existing", true), get(table, "existing").path("Item"));
            assertTrue(get(table, "new").path("Item").isMissingNode());
        } finally {
            deleteTable(table);
        }
    }

    @Test
    void transactWriteRejectsMalformedNestedValueWithoutApplyingOtherPuts() throws Exception {
        String table = createTable();
        try {
            ObjectNode request = JSON.createObjectNode();
            ArrayNode writes = request.putArray("TransactItems");
            writes.addObject().putObject("Put").put("TableName", table).set("Item", item("valid", true));
            writes.addObject().putObject("Put").put("TableName", table).set("Item", item("invalid", false));

            assertValidation(call("TransactWriteItems", request, 400));
            assertTrue(get(table, "valid").path("Item").isMissingNode());
            assertTrue(get(table, "invalid").path("Item").isMissingNode());

            writes.remove(1);
            call("TransactWriteItems", request, 200);
            assertEquals(item("valid", true), get(table, "valid").path("Item"));
        } finally {
            deleteTable(table);
        }
    }

    @Test
    void batchWriteRejectsMalformedNestedValueWithoutApplyingOtherPuts() throws Exception {
        String table = createTable();
        try {
            ObjectNode request = JSON.createObjectNode();
            ArrayNode writes = request.putObject("RequestItems").putArray(table);
            writes.addObject().putObject("PutRequest").set("Item", item("valid", true));
            writes.addObject().putObject("PutRequest").set("Item", item("invalid", false));

            assertValidation(call("BatchWriteItem", request, 400));
            assertTrue(get(table, "valid").path("Item").isMissingNode());
            assertTrue(get(table, "invalid").path("Item").isMissingNode());

            writes.remove(1);
            call("BatchWriteItem", request, 200);
            assertEquals(item("valid", true), get(table, "valid").path("Item"));
        } finally {
            deleteTable(table);
        }
    }

    @Test
    void updateExpressionRejectsMalformedNestedValueWithoutChangingStoredItem() throws Exception {
        String table = createTable();
        try {
            ObjectNode original = item("existing", true);
            put(table, original, 200);
            ObjectNode update = JSON.createObjectNode().put("TableName", table)
                    .put("UpdateExpression", "SET m = :m");
            update.set("Key", key("existing"));
            ObjectNode values = update.putObject("ExpressionAttributeValues");
            values.set(":m", nestedValue(false));

            assertValidation(call("UpdateItem", update, 400));
            assertEquals(original, get(table, "existing").path("Item"));

            ObjectNode replacement = updatedValue();
            values.set(":m", replacement);
            call("UpdateItem", update, 200);
            original.set("m", replacement);
            assertEquals(original, get(table, "existing").path("Item"));
        } finally {
            deleteTable(table);
        }
    }

    @Test
    void legacyAttributeUpdateRejectsMalformedNestedValueAndAllowsDeleteWithoutValue() throws Exception {
        String table = createTable();
        try {
            ObjectNode original = item("existing", true);
            original.putObject("remove").put("S", "old");
            put(table, original, 200);
            ObjectNode update = JSON.createObjectNode().put("TableName", table);
            update.set("Key", key("existing"));
            ObjectNode change = update.putObject("AttributeUpdates").putObject("m").put("Action", "PUT");
            change.set("Value", nestedValue(false));

            assertValidation(call("UpdateItem", update, 400));
            assertEquals(original, get(table, "existing").path("Item"));

            ObjectNode replacement = updatedValue();
            change.set("Value", replacement);
            call("UpdateItem", update, 200);
            original.set("m", replacement);
            assertEquals(original, get(table, "existing").path("Item"));

            update.with("AttributeUpdates").remove("m");
            update.with("AttributeUpdates").putObject("remove").put("Action", "DELETE");
            call("UpdateItem", update, 200);
            original.remove("remove");
            assertEquals(original, get(table, "existing").path("Item"));
        } finally {
            deleteTable(table);
        }
    }

    @Test
    void transactUpdateRejectsMalformedNestedValueWithoutApplyingOtherWrites() throws Exception {
        String table = createTable();
        try {
            ObjectNode original = item("existing", true);
            put(table, original, 200);
            ObjectNode request = JSON.createObjectNode();
            ArrayNode writes = request.putArray("TransactItems");
            writes.addObject().putObject("Put").put("TableName", table).set("Item", item("new", true));
            ObjectNode update = writes.addObject().putObject("Update").put("TableName", table)
                    .put("UpdateExpression", "SET m = :m");
            update.set("Key", key("existing"));
            ObjectNode values = update.putObject("ExpressionAttributeValues");
            values.set(":m", nestedValue(false));

            assertValidation(call("TransactWriteItems", request, 400));
            assertEquals(original, get(table, "existing").path("Item"));
            assertTrue(get(table, "new").path("Item").isMissingNode());

            ObjectNode replacement = updatedValue();
            values.set(":m", replacement);
            call("TransactWriteItems", request, 200);
            original.set("m", replacement);
            assertEquals(original, get(table, "existing").path("Item"));
            assertEquals(item("new", true), get(table, "new").path("Item"));
        } finally {
            deleteTable(table);
        }
    }

    private static String createTable() throws Exception {
        String table = "malformed-" + UUID.randomUUID();
        ObjectNode request = JSON.createObjectNode().put("TableName", table).put("BillingMode", "PAY_PER_REQUEST");
        request.putArray("KeySchema").addObject().put("AttributeName", "pk").put("KeyType", "HASH");
        request.putArray("AttributeDefinitions").addObject().put("AttributeName", "pk").put("AttributeType", "S");
        call("CreateTable", request, 200);
        return table;
    }

    private static void deleteTable(String table) throws Exception {
        call("DeleteTable", JSON.createObjectNode().put("TableName", table), 200);
    }

    private static JsonNode put(String table, ObjectNode item, int status) throws Exception {
        ObjectNode request = JSON.createObjectNode().put("TableName", table);
        request.set("Item", item);
        return call("PutItem", request, status);
    }

    private static JsonNode get(String table, String key) throws Exception {
        ObjectNode request = JSON.createObjectNode().put("TableName", table);
        request.set("Key", key(key));
        return call("GetItem", request, 200);
    }

    private static ObjectNode key(String value) {
        return JSON.createObjectNode().set("pk", JSON.createObjectNode().put("S", value));
    }

    private static ObjectNode item(String key, boolean valid) {
        ObjectNode item = JSON.createObjectNode();
        item.putObject("pk").put("S", key);
        item.set("m", nestedValue(valid));
        return item;
    }

    private static ObjectNode nestedValue(boolean valid) {
        ObjectNode value = JSON.createObjectNode();
        ObjectNode map = value.putObject("M");
        map.putObject("S").put(valid ? "S" : "0", "v");
        if (valid) {
            map.putObject("list").putArray("L").addObject().putObject("M").putObject("leaf").put("S", "v");
        }
        return value;
    }

    private static ObjectNode updatedValue() {
        ObjectNode value = nestedValue(true);
        ((ObjectNode) value.path("M").path("S")).put("S", "updated");
        return value;
    }

    private static void assertValidation(JsonNode response) {
        assertTrue(response.path("__type").asText().endsWith("ValidationException"), response.toString());
    }

    private static JsonNode call(String action, JsonNode request, int status) throws Exception {
        String response = given().contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", "DynamoDB_20120810." + action)
                .body(request.toString())
                .post("/").then().statusCode(status).extract().asString();
        return JSON.readTree(response);
    }
}
