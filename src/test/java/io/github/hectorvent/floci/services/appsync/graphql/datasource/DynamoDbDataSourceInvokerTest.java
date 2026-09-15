package io.github.hectorvent.floci.services.appsync.graphql.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.DataSourceType;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The AppSync-to-DynamoDB translation, in both directions. The direction that matters is outward:
 * AppSync hands a resolver plain JSON, never attribute values.
 */
class DynamoDbDataSourceInvokerTest {

    private final DynamoDbJsonHandler handler = mock(DynamoDbJsonHandler.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final DynamoDbDataSourceInvoker invoker = new DynamoDbDataSourceInvoker(handler, mapper);

    private DataSource dataSource() {
        DataSource ds = new DataSource();
        ds.setName("authKeys");
        ds.setType(DataSourceType.AMAZON_DYNAMODB);
        ds.setDynamodbConfig(Map.of("tableName", "auth-keys", "awsRegion", "eu-west-1"));
        return ds;
    }

    private void answers(String json) throws Exception {
        when(handler.handle(anyString(), any(), anyString())).thenReturn(Response.ok(json).build());
    }

    private JsonNode captureRequest(String action) throws Exception {
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        org.mockito.Mockito.verify(handler).handle(eq(action), captor.capture(), anyString());
        return captor.getValue();
    }

    private Map<String, Object> request(String json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void getItemUnmarshalsTheItemIntoPlainJson() throws Exception {
        answers("""
            {"Item": {"regNo": {"S": "556677"}, "calls": {"N": "42"}, "active": {"BOOL": true},
                      "scopes": {"L": [{"S": "read"}, {"S": "write"}]},
                      "meta": {"M": {"tier": {"S": "small"}}}, "gone": {"NULL": true}}}
            """);

        Object result = invoker.invoke(dataSource(),
                request("{\"operation\": \"GetItem\", \"key\": {\"regNo\": {\"S\": \"556677\"}}}"),
                "eu-west-1");

        // A GraphQL field cannot be built from {"S": …}; AppSync resolvers see plain values.
        assertEquals(Map.of("regNo", "556677", "calls", 42L, "active", true,
                        "scopes", List.of("read", "write"), "meta", Map.of("tier", "small")),
                stripNulls(result));
        JsonNode sent = captureRequest("GetItem");
        assertEquals("auth-keys", sent.path("TableName").asText());
        assertEquals("556677", sent.path("Key").path("regNo").path("S").asText());
    }

    @Test
    void getItemMissAnswersNullRatherThanAnEmptyMap() throws Exception {
        answers("{}");

        Object result = invoker.invoke(dataSource(),
                request("{\"operation\": \"GetItem\", \"key\": {\"regNo\": {\"S\": \"nope\"}}}"),
                "eu-west-1");

        assertNull(result);
    }

    @Test
    void putItemMergesKeyAndAttributeValuesAndAnswersTheWrittenItem() throws Exception {
        answers("{}");

        Object result = invoker.invoke(dataSource(), request("""
                {"operation": "PutItem",
                 "key": {"regNo": {"S": "556677"}},
                 "attributeValues": {"name": {"S": "Acme"}},
                 "condition": {"expression": "attribute_not_exists(#r)",
                               "expressionNames": {"#r": "regNo"}}}
                """), "eu-west-1");

        JsonNode sent = captureRequest("PutItem");
        // The key is part of the item on the way to DynamoDB, and wins over an attribute of the
        // same name, as AppSync documents.
        assertEquals("556677", sent.path("Item").path("regNo").path("S").asText());
        assertEquals("Acme", sent.path("Item").path("name").path("S").asText());
        assertEquals("attribute_not_exists(#r)", sent.path("ConditionExpression").asText());
        assertEquals("regNo", sent.path("ExpressionAttributeNames").path("#r").asText());
        assertEquals(Map.of("regNo", "556677", "name", "Acme"), result);
    }

    @Test
    void updateItemAsksForTheNewImageAndUnmarshalsIt() throws Exception {
        answers("{\"Attributes\": {\"regNo\": {\"S\": \"556677\"}, \"calls\": {\"N\": \"43\"}}}");

        Object result = invoker.invoke(dataSource(), request("""
                {"operation": "UpdateItem",
                 "key": {"regNo": {"S": "556677"}},
                 "update": {"expression": "ADD calls :one",
                            "expressionValues": {":one": {"N": "1"}}}}
                """), "eu-west-1");

        JsonNode sent = captureRequest("UpdateItem");
        assertEquals("ADD calls :one", sent.path("UpdateExpression").asText());
        assertEquals("1", sent.path("ExpressionAttributeValues").path(":one").path("N").asText());
        // ALL_NEW: an AppSync UpdateItem resolver returns the updated item.
        assertEquals("ALL_NEW", sent.path("ReturnValues").asText());
        assertEquals(Map.of("regNo", "556677", "calls", 43L), result);
    }

    @Test
    void queryMergesKeyConditionAndFilterPlaceholdersIntoOneMap() throws Exception {
        answers("""
            {"Items": [{"regNo": {"S": "1"}}, {"regNo": {"S": "2"}}],
             "ScannedCount": 7,
             "LastEvaluatedKey": {"regNo": {"S": "2"}}}
            """);

        Object result = invoker.invoke(dataSource(), request("""
                {"operation": "Query",
                 "query": {"expression": "#r = :r", "expressionNames": {"#r": "regNo"},
                           "expressionValues": {":r": {"S": "1"}}},
                 "filter": {"expression": "active = :a", "expressionValues": {":a": {"BOOL": true}}},
                 "index": "byRegNo", "limit": 25, "scanIndexForward": false}
                """), "eu-west-1");

        JsonNode sent = captureRequest("Query");
        assertEquals("#r = :r", sent.path("KeyConditionExpression").asText());
        assertEquals("active = :a", sent.path("FilterExpression").asText());
        // Both the key condition's and the filter's placeholders live in one shared map; setting
        // rather than merging them would drop whichever came first.
        assertEquals("1", sent.path("ExpressionAttributeValues").path(":r").path("S").asText());
        assertTrue(sent.path("ExpressionAttributeValues").path(":a").path("BOOL").asBoolean());
        assertEquals("byRegNo", sent.path("IndexName").asText());
        assertEquals(25, sent.path("Limit").asInt());
        assertTrue(sent.has("ScanIndexForward"));

        Map<?, ?> answered = (Map<?, ?>) result;
        assertEquals(List.of(Map.of("regNo", "1"), Map.of("regNo", "2")), answered.get("items"));
        assertEquals(7, answered.get("scannedCount"));
        assertTrue(answered.get("nextToken") instanceof String, "a page with more rows carries a nextToken");
    }

    @Test
    void aQueryPageWithNoMoreRowsCarriesNoNextToken() throws Exception {
        answers("{\"Items\": [], \"ScannedCount\": 0}");

        Map<?, ?> result = (Map<?, ?>) invoker.invoke(dataSource(), request("""
                {"operation": "Query", "query": {"expression": "regNo = :r",
                 "expressionValues": {":r": {"S": "1"}}}}
                """), "eu-west-1");

        assertNull(result.get("nextToken"));
        assertEquals(List.of(), result.get("items"));
    }

    @Test
    void aNextTokenRoundTripsBackIntoExclusiveStartKey() throws Exception {
        answers("{\"Items\": [], \"LastEvaluatedKey\": {\"regNo\": {\"S\": \"2\"}}}");
        Map<?, ?> first = (Map<?, ?>) invoker.invoke(dataSource(), request("""
                {"operation": "Scan"}
                """), "eu-west-1");
        String nextToken = (String) first.get("nextToken");

        org.mockito.Mockito.reset(handler);
        answers("{\"Items\": []}");
        invoker.invoke(dataSource(), request("""
                {"operation": "Scan", "nextToken": "%s"}
                """.formatted(nextToken)), "eu-west-1");

        JsonNode sent = captureRequest("Scan");
        assertEquals("2", sent.path("ExclusiveStartKey").path("regNo").path("S").asText());
    }

    @Test
    void aTokenFlociDidNotIssueIsRefused() {
        AwsException e = assertThrows(AwsException.class, () -> invoker.invoke(dataSource(),
                request("{\"operation\": \"Scan\", \"nextToken\": \"not-a-token!!\"}"), "eu-west-1"));

        assertTrue(e.getMessage().contains("nextToken"), e.getMessage());
    }

    @Test
    void anUnimplementedOperationSaysSoByName() {
        AwsException e = assertThrows(AwsException.class, () -> invoker.invoke(dataSource(),
                request("{\"operation\": \"TransactWriteItems\"}"), "eu-west-1"));

        assertTrue(e.getMessage().contains("TransactWriteItems"), e.getMessage());
    }

    @Test
    void aDataSourceWithNoTableNameFailsClearly() {
        DataSource ds = dataSource();
        ds.setDynamodbConfig(Map.of("awsRegion", "eu-west-1"));

        AwsException e = assertThrows(AwsException.class, () -> invoker.invoke(ds,
                request("{\"operation\": \"GetItem\", \"key\": {}}"), "eu-west-1"));

        assertTrue(e.getMessage().contains("tableName"), e.getMessage());
    }

    /** Drops the null-valued entries a NULL attribute produces, for a tidy comparison. */
    private Object stripNulls(Object value) {
        if (value instanceof Map<?, ?> map) {
            java.util.Map<Object, Object> copy = new java.util.LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (v != null) {
                    copy.put(k, stripNulls(v));
                }
            });
            return copy;
        }
        return value;
    }
}
