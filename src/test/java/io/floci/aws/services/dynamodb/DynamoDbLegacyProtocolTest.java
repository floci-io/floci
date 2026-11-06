package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.dynamodb.backend.DynamoDbOperations;
import io.floci.aws.services.dynamodb.backend.DynamoDbOperations.Call;
import io.floci.aws.services.dynamodb.backend.DynamoDbOperations.Reply;
import io.floci.aws.services.dynamodb.backend.DynamoDbOperations.Scope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DynamoDbLegacyProtocolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Scope SCOPE = new Scope("123456789012", "eu-west-1");
    private static final String TABLE = """
            {"Table":{"TableName":"metrics","TableStatus":"ACTIVE",
             "KeySchema":[{"AttributeName":"id-type","KeyType":"HASH"},{"AttributeName":"range","KeyType":"RANGE"}],
             "AttributeDefinitions":[{"AttributeName":"id-type","AttributeType":"S"},
                                     {"AttributeName":"range","AttributeType":"N"}]}}
            """;

    @Test
    void describesActualSchemaAndPreservesBackendErrors() throws Exception {
        Reply described = new DynamoDbLegacyProtocol(call -> reply(TABLE), SCOPE)
                .execute("DescribeTable", json("{\"TableName\":\"metrics\"}"));
        assertEquals(json("""
                {"HashKeyElement":{"AttributeName":"id-type","AttributeType":"S"},
                 "RangeKeyElement":{"AttributeName":"range","AttributeType":"N"}}
                """), described.body().path("Table").path("KeySchema"));
        assertEquals("metrics", described.body().path("Table").path("TableName").asText());
        Reply denied = new Reply(400, json("{\"__type\":\"ResourceNotFoundException\"}"),
                Map.of("x-test", List.of("kept")));
        assertSame(denied, new DynamoDbLegacyProtocol(call -> denied, SCOPE)
                .execute("DescribeTable", json("{\"TableName\":\"absent\"}")));
    }

    @Test
    void translatesQueryConditionsPaginationAndCapacityInOneScope() throws Exception {
        List<Call> calls = new ArrayList<>();
        DynamoDbOperations operations = call -> {
            calls.add(call);
            return reply("DescribeTable".equals(call.action()) ? TABLE : """
                    {"Items":[{"range":{"N":"3"}}],"Count":1,
                     "LastEvaluatedKey":{"id-type":{"S":"domain_q"},"range":{"N":"3"}},
                     "ConsumedCapacity":{"TableName":"metrics","CapacityUnits":0.5}}
                    """);
        };
        JsonNode request = json("""
                {"TableName":"metrics","HashKeyValue":{"S":"domain_q"},
                 "RangeKeyCondition":{"ComparisonOperator":"BETWEEN","AttributeValueList":[{"N":"2"},{"N":"9"}]},
                 "ExclusiveStartKey":{"HashKeyElement":{"S":"domain_q"},"RangeKeyElement":{"N":"2"}},
                 "Limit":1,"ConsistentRead":true,"ScanIndexForward":false,"AttributesToGet":["range"]}
                """);
        JsonNode original = request.deepCopy();
        Reply reply = new DynamoDbLegacyProtocol(operations, SCOPE).execute("Query", request);
        assertEquals(original, request);
        assertEquals(List.of("DescribeTable", "Query"), calls.stream().map(Call::action).toList());
        assertTrue(calls.stream().allMatch(call -> call.scope().equals(SCOPE)));
        assertEquals(json("""
                {"id-type":{"ComparisonOperator":"EQ","AttributeValueList":[{"S":"domain_q"}]},
                 "range":{"ComparisonOperator":"BETWEEN","AttributeValueList":[{"N":"2"},{"N":"9"}]}}
                """), calls.getLast().body().path("KeyConditions"));
        assertEquals(json("{\"id-type\":{\"S\":\"domain_q\"},\"range\":{\"N\":\"2\"}}"),
                calls.getLast().body().path("ExclusiveStartKey"));
        assertEquals(1, calls.getLast().body().path("Limit").asInt());
        assertTrue(calls.getLast().body().path("ConsistentRead").asBoolean());
        assertFalse(calls.getLast().body().path("ScanIndexForward").asBoolean());
        assertEquals(json("[\"range\"]"), calls.getLast().body().path("AttributesToGet"));
        assertEquals(json("{\"HashKeyElement\":{\"S\":\"domain_q\"},\"RangeKeyElement\":{\"N\":\"3\"}}"),
                reply.body().path("LastEvaluatedKey"));
        assertEquals(0.5, reply.body().path("ConsumedCapacityUnits").asDouble());
        assertEquals(1, reply.body().path("Count").asInt());
    }

    @Test
    void countUsesBackendCountAndOmitsAbsentContinuation() throws Exception {
        List<Call> calls = new ArrayList<>();
        DynamoDbLegacyProtocol protocol = new DynamoDbLegacyProtocol(call -> {
            calls.add(call);
            return reply("DescribeTable".equals(call.action()) ? TABLE : """
                    {"Count":7,"LastEvaluatedKey":{},"ConsumedCapacity":{"CapacityUnits":2.0}}
                    """);
        }, SCOPE);
        Reply reply = protocol.execute("Query",
                json("{\"TableName\":\"metrics\",\"HashKeyValue\":{\"S\":\"domain_q\"},\"Count\":true}"));
        assertEquals("COUNT", calls.getLast().body().path("Select").asText());
        assertEquals(7, reply.body().path("Count").asInt());
        assertFalse(reply.body().has("Items"));
        assertFalse(reply.body().has("LastEvaluatedKey"));
    }

    @Test
    void batchRetriesKeepLegacyKeysAndRealPerTableCapacity() throws Exception {
        List<Call> calls = new ArrayList<>();
        DynamoDbOperations operations = call -> {
            calls.add(call);
            return reply("DescribeTable".equals(call.action()) ? TABLE : """
                    {"UnprocessedItems":{"metrics":[
                      {"DeleteRequest":{"Key":{"id-type":{"S":"domain_q"},"range":{"N":"3"}}}},
                      {"PutRequest":{"Item":{"id-type":{"S":"domain_d"},"range":{"N":"4"}}}}]},
                     "ConsumedCapacity":[{"TableName":"metrics","CapacityUnits":2.0}]}
                    """);
        };
        JsonNode request = json("""
                {"RequestItems":{"metrics":[
                  {"DeleteRequest":{"Key":{"HashKeyElement":{"S":"domain_q"},"RangeKeyElement":{"N":"3"}}}},
                  {"PutRequest":{"Item":{"id-type":{"S":"domain_d"},"range":{"N":"4"}}}}]}}
                """);
        JsonNode original = request.deepCopy();
        Reply reply = new DynamoDbLegacyProtocol(operations, SCOPE).execute("BatchWriteItem", request);
        assertEquals(original, request);
        assertEquals(2, calls.size());
        assertTrue(calls.stream().allMatch(call -> call.scope().equals(SCOPE)));
        assertEquals(json("{\"id-type\":{\"S\":\"domain_q\"},\"range\":{\"N\":\"3\"}}"),
                calls.getLast().body().path("RequestItems").path("metrics").get(0).path("DeleteRequest").path("Key"));
        assertEquals(request.path("RequestItems"), reply.body().path("UnprocessedItems"));
        assertEquals(2.0, reply.body().path("Responses").path("metrics").path("ConsumedCapacityUnits").asDouble());
        calls.clear();
        new DynamoDbLegacyProtocol(operations, SCOPE).execute("BatchWriteItem",
                MAPPER.createObjectNode().set("RequestItems", reply.body().path("UnprocessedItems")));
        assertEquals(2, calls.size());
    }

    @Test
    void invalidLegacyKeysAndUnsupportedActionsNeverWrite() throws Exception {
        List<Call> calls = new ArrayList<>();
        DynamoDbOperations operations = call -> {
            calls.add(call);
            assertEquals("DescribeTable", call.action());
            return reply(TABLE);
        };
        for (String request : List.of(
                "{\"TableName\":\"metrics\"}",
                "{\"TableName\":\"metrics\",\"HashKeyValue\":\"bad\"}",
                "{\"TableName\":\"metrics\",\"HashKeyValue\":{\"S\":\"x\"},\"ExclusiveStartKey\":{\"HashKeyElement\":{\"S\":\"x\"}}}",
                "{\"TableName\":\"metrics\",\"HashKeyValue\":{\"S\":\"x\"},\"Count\":\"true\"}")) {
            assertThrows(AwsException.class, () -> new DynamoDbLegacyProtocol(operations, SCOPE)
                    .execute("Query", json(request)));
        }
        assertThrows(AwsException.class, () -> new DynamoDbLegacyProtocol(operations, SCOPE)
                .execute("BatchWriteItem", json("""
                        {"RequestItems":{"metrics":[{"DeleteRequest":{"Key":{"HashKeyElement":{"S":"x"}}}}]}}
                        """)));
        calls.clear();
        assertThrows(AwsException.class, () -> new DynamoDbLegacyProtocol(operations, SCOPE)
                .execute("CreateTable", json("{}")));
        assertTrue(calls.isEmpty());
    }

    @Test
    void missingOrDeniedMetadataCannotReachQueryOrWrite() throws Exception {
        for (String action : List.of("Query", "BatchWriteItem")) {
            List<Call> calls = new ArrayList<>();
            DynamoDbOperations operations = call -> {
                calls.add(call);
                return new Reply(400, json("{\"__type\":\"AccessDeniedException\",\"message\":\"denied\"}"), Map.of());
            };
            assertThrows(AwsException.class, () -> new DynamoDbLegacyProtocol(operations, SCOPE).execute(action,
                    json("Query".equals(action)
                            ? "{\"TableName\":\"metrics\",\"HashKeyValue\":{\"S\":\"x\"}}"
                            : "{\"RequestItems\":{\"metrics\":[{\"DeleteRequest\":{\"Key\":{}}}]}}")));
            assertEquals(List.of("DescribeTable"), calls.stream().map(Call::action).toList());
        }
    }

    private static JsonNode json(String value) throws Exception {
        return MAPPER.readTree(value);
    }

    private static Reply reply(String value) throws Exception {
        return new Reply(200, json(value), Map.of());
    }
}
