package io.github.hectorvent.floci.services.dynamodb.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbItemAccess.ScanPage;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Api;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Call;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Reply;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Scope;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.GlobalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.LocalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The DynamoDB Local engine: identity translation, Floci-held tags, typed operations and lifecycle. */
class DynamoDbLocalBackendTest {

    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "eu-west-1";
    private static final Scope SCOPE = new Scope(ACCOUNT, REGION);
    private static final String LOCAL = "arn:aws:dynamodb:ddblocal:000000000000:";
    private static final String PUBLIC = "arn:aws:dynamodb:eu-west-1:123456789012:";
    private static final String ORDERS_ARN = PUBLIC + "table/orders";
    private static final String STREAM_RESOURCE = "table/orders/stream/2026-09-27T00:03:07.048";
    private static final String REPLICAS_UNSUPPORTED = "Replicas are not supported by the DynamoDB Local backend";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DynamoDbLocalClient client = mock(DynamoDbLocalClient.class);
    private final AccountAwareStorageBackend<Map<String, String>> tags =
            AccountAwareStorageBackend.inMemory("000000000000");
    private DynamoDbLocalBackend backend;

    @BeforeEach
    void setUp() {
        when(client.endpoint()).thenReturn(URI.create("http://local:8000"));
        backend = new DynamoDbLocalBackend(client, tags, mapper, Duration.ofSeconds(5));
    }

    private JsonNode json(String text) throws IOException {
        return mapper.readTree(text);
    }

    private JsonNode normalized(JsonNode node) throws IOException {
        return mapper.readTree(mapper.writeValueAsBytes(node));
    }

    private void answer(Api api, String action, int status, String body) throws Exception {
        when(client.send(any(), eq(api), eq(action), any()))
                .thenAnswer(invocation -> new Reply(status, json(body), Map.of()));
    }

    private void answer(String action, int status, String body) throws Exception {
        answer(Api.DYNAMODB, action, status, body);
    }

    private List<JsonNode> sentBodies(Api api, String action) throws Exception {
        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(client, atLeastOnce()).send(any(), eq(api), eq(action), body.capture());
        List<JsonNode> bodies = new ArrayList<>();
        for (JsonNode value : body.getAllValues()) {
            bodies.add(normalized(value));
        }
        return bodies;
    }

    private JsonNode sent(Api api, String action) throws Exception {
        List<JsonNode> bodies = sentBodies(api, action);
        return bodies.get(bodies.size() - 1);
    }

    private JsonNode sent(String action) throws Exception {
        return sent(Api.DYNAMODB, action);
    }

    private void neverSent(String action) throws Exception {
        verify(client, never()).send(any(), any(), eq(action), any());
    }

    private Reply execute(Api api, String action, String body) throws Exception {
        return backend.execute(new Call(SCOPE, api, action, json(body)));
    }

    private Reply execute(String action, String body) throws Exception {
        return execute(Api.DYNAMODB, action, body);
    }

    private void answerOrdersTable() throws Exception {
        answer("DescribeTable", 200, "{\"Table\":{\"TableName\":\"orders\",\"TableArn\":\"" + LOCAL
                + "table/orders\",\"TableStatus\":\"ACTIVE\"}}");
    }

    @Test
    void tableArnTableNameBecomesShortName() throws Exception {
        answer("GetItem", 200, "{}");

        execute("GetItem", "{\"TableName\":\"" + ORDERS_ARN + "\",\"Key\":{\"pk\":{\"S\":\"a\"}}}");

        assertEquals(json("{\"TableName\":\"orders\",\"Key\":{\"pk\":{\"S\":\"a\"}}}"), sent("GetItem"));
    }

    @Test
    void requestItemsArnKeysBecomeShortNames() throws Exception {
        answer("BatchWriteItem", 200, "{\"UnprocessedItems\":{}}");

        execute("BatchWriteItem", "{\"RequestItems\":{\"" + ORDERS_ARN + "\":[{\"PutRequest\":{\"Item\":"
                + "{\"pk\":{\"S\":\"a\"}}}}],\"users\":[{\"DeleteRequest\":{\"Key\":{\"pk\":{\"S\":\"b\"}}}}]}}");

        JsonNode requestItems = sent("BatchWriteItem").path("RequestItems");
        List<String> names = new ArrayList<>();
        requestItems.fieldNames().forEachRemaining(names::add);
        assertEquals(List.of("orders", "users"), names);
        assertEquals(json("[{\"PutRequest\":{\"Item\":{\"pk\":{\"S\":\"a\"}}}}]"), requestItems.path("orders"));
    }

    @Test
    void transactItemsNestedTableNamesTranslated() throws Exception {
        answer("TransactWriteItems", 200, "{}");

        execute("TransactWriteItems", "{\"TransactItems\":["
                + "{\"Put\":{\"TableName\":\"" + ORDERS_ARN + "\",\"Item\":{\"pk\":{\"S\":\"a\"}}}},"
                + "{\"ConditionCheck\":{\"TableName\":\"" + PUBLIC + "table/users\",\"Key\":{\"pk\":{\"S\":\"b\"}},"
                + "\"ConditionExpression\":\"attribute_exists(pk)\"}},"
                + "{\"Delete\":{\"TableName\":\"audit\",\"Key\":{\"pk\":{\"S\":\"c\"}}}}]}");

        JsonNode items = sent("TransactWriteItems").path("TransactItems");
        assertEquals("orders", items.path(0).path("Put").path("TableName").asText());
        assertEquals("users", items.path(1).path("ConditionCheck").path("TableName").asText());
        assertEquals("audit", items.path(2).path("Delete").path("TableName").asText());
    }

    @Test
    void foreignAccountOrPartitionArnNotFound() throws Exception {
        for (String foreign : List.of("arn:aws:dynamodb:eu-west-1:999999999999:table/orders",
                "arn:aws-cn:dynamodb:eu-west-1:123456789012:table/orders")) {
            AwsException error = assertThrows(AwsException.class,
                    () -> execute("GetItem", "{\"TableName\":\"" + foreign + "\",\"Key\":{}}"));

            assertEquals("ResourceNotFoundException", error.getErrorCode());
            assertEquals("Requested resource not found: Table: " + foreign + " not found", error.getMessage());
        }
        neverSent("GetItem");
    }

    @Test
    void requestItemsNamingOneTableTwiceRejected() throws Exception {
        AwsException error = assertThrows(AwsException.class, () -> execute("BatchWriteItem",
                "{\"RequestItems\":{\"" + ORDERS_ARN + "\":[],\"orders\":[]}}"));

        assertEquals("ValidationException", error.getErrorCode());
        neverSent("BatchWriteItem");
    }

    @Test
    void foreignRegionArnRejected() throws Exception {
        AwsException error = assertThrows(AwsException.class, () -> execute("GetItem",
                "{\"TableName\":\"arn:aws:dynamodb:us-east-1:123456789012:table/orders\",\"Key\":{}}"));

        assertEquals("ValidationException", error.getErrorCode());
        assertEquals("Region 'us-east-1' in ARN does not match request region 'eu-west-1'", error.getMessage());
        neverSent("GetItem");
    }

    @Test
    void createTableArnNameLeftForLocal() throws Exception {
        answer("CreateTable", 400, "{\"__type\":\"com.amazonaws.dynamodb.v20120810#ValidationException\","
                + "\"message\":\"Invalid table name\"}");

        Reply reply = execute("CreateTable", "{\"TableName\":\"" + ORDERS_ARN + "\"}");

        assertEquals(ORDERS_ARN, sent("CreateTable").path("TableName").asText());
        assertEquals(400, reply.status());
    }

    @Test
    void streamArnTranslatedToLocalPrefix() throws Exception {
        answer(Api.DYNAMODB_STREAMS, "DescribeStream", 200, "{\"StreamDescription\":{}}");

        execute(Api.DYNAMODB_STREAMS, "DescribeStream", "{\"StreamArn\":\"" + PUBLIC + STREAM_RESOURCE + "\"}");

        assertEquals(LOCAL + STREAM_RESOURCE, sent(Api.DYNAMODB_STREAMS, "DescribeStream").path("StreamArn").asText());
    }

    @Test
    void listStreamsPagesWithPublicStreamArns() throws Exception {
        answer(Api.DYNAMODB_STREAMS, "ListStreams", 200,
                "{\"LastEvaluatedStreamArn\":\"" + LOCAL + STREAM_RESOURCE + "\"}");

        Reply reply = execute(Api.DYNAMODB_STREAMS, "ListStreams",
                "{\"ExclusiveStartStreamArn\":\"" + PUBLIC + STREAM_RESOURCE + "\"}");

        assertEquals(LOCAL + STREAM_RESOURCE,
                sent(Api.DYNAMODB_STREAMS, "ListStreams").path("ExclusiveStartStreamArn").asText());
        assertEquals(PUBLIC + STREAM_RESOURCE, reply.body().path("LastEvaluatedStreamArn").asText());
    }

    @Test
    void foreignStreamArnNotFound() throws Exception {
        for (String foreign : List.of("arn:aws:dynamodb:eu-west-1:999999999999:" + STREAM_RESOURCE,
                "arn:aws:dynamodb:us-east-1:123456789012:" + STREAM_RESOURCE,
                "arn:aws-cn:dynamodb:eu-west-1:123456789012:" + STREAM_RESOURCE)) {
            AwsException error = assertThrows(AwsException.class,
                    () -> execute(Api.DYNAMODB_STREAMS, "DescribeStream", "{\"StreamArn\":\"" + foreign + "\"}"));

            assertEquals("ResourceNotFoundException", error.getErrorCode());
            assertEquals("Requested resource not found: Stream: " + foreign + " not found", error.getMessage());
            assertEquals(400, error.getHttpStatus());
        }
        neverSent("DescribeStream");
    }

    @Test
    void publicArnsInReply() throws Exception {
        answer("DescribeTable", 200, "{\"Table\":{\"TableName\":\"orders\",\"TableArn\":\"" + LOCAL + "table/orders\","
                + "\"GlobalSecondaryIndexes\":[{\"IndexName\":\"g\","
                + "\"IndexArn\":\"" + LOCAL + "table/orders/index/g\"}],"
                + "\"LatestStreamArn\":\"" + LOCAL + STREAM_RESOURCE + "\"}}");

        JsonNode table = execute("DescribeTable", "{\"TableName\":\"orders\"}").body().path("Table");

        assertEquals(ORDERS_ARN, table.path("TableArn").asText());
        assertEquals(ORDERS_ARN + "/index/g", table.path("GlobalSecondaryIndexes").path(0).path("IndexArn").asText());
        assertEquals(PUBLIC + STREAM_RESOURCE, table.path("LatestStreamArn").asText());
    }

    @Test
    void errorMessageArnRewritten() throws Exception {
        answer("DescribeTable", 400, "{\"__type\":\"com.amazonaws.dynamodb.v20120810#ResourceNotFoundException\","
                + "\"message\":\"Requested resource not found: Table: " + LOCAL + "table/orders not found\"}");

        Reply reply = execute("DescribeTable", "{\"TableName\":\"orders\"}");

        assertEquals("Requested resource not found: Table: " + ORDERS_ARN + " not found",
                reply.body().path("message").asText());
    }

    @Test
    void recordAwsRegionRewritten() throws Exception {
        answer(Api.DYNAMODB_STREAMS, "GetRecords", 200,
                "{\"Records\":[{\"eventName\":\"INSERT\",\"awsRegion\":\"ddblocal\",\"dynamodb\":{}}]}");

        Reply reply = execute(Api.DYNAMODB_STREAMS, "GetRecords", "{\"ShardIterator\":\"it\"}");

        assertEquals(REGION, reply.body().path("Records").path(0).path("awsRegion").asText());
    }

    @Test
    void arnLookingItemAttributeUntouched() throws Exception {
        String item = "{\"TableArn\":{\"S\":\"" + LOCAL + "table/x\"},"
                + "\"StreamArn\":{\"S\":\"arn:aws:dynamodb:us-east-1:999999999999:" + STREAM_RESOURCE + "\"},"
                + "\"TableName\":{\"S\":\"" + PUBLIC + "table/other\"},"
                + "\"awsRegion\":{\"S\":\"ddblocal\"}}";
        answer("PutItem", 200, "{}");
        answer("GetItem", 200, "{\"Item\":" + item + "}");

        execute("PutItem", "{\"TableName\":\"orders\",\"Item\":" + item + "}");
        Reply reply = execute("GetItem", "{\"TableName\":\"orders\",\"Key\":{\"pk\":{\"S\":\"a\"}}}");

        assertEquals(json(item), sent("PutItem").path("Item"));
        assertEquals(json(item), reply.body().path("Item"));
    }

    @Test
    void callerBodyNotMutated() throws Exception {
        answer("GetItem", 200, "{}");
        JsonNode body = json("{\"TableName\":\"" + ORDERS_ARN + "\",\"Key\":{\"pk\":{\"S\":\"a\"}}}");
        JsonNode original = body.deepCopy();

        backend.execute(new Call(SCOPE, Api.DYNAMODB, "GetItem", body));

        assertEquals(original, body);
    }

    @Test
    void sendsUnderCallerScope() throws Exception {
        answer("DescribeTable", 200, "{\"Table\":{}}");
        Scope caller = new Scope("210987654321", "ap-south-1");

        backend.execute(new Call(caller, Api.DYNAMODB, "DescribeTable", json("{\"TableName\":\"orders\"}")));

        ArgumentCaptor<Scope> scope = ArgumentCaptor.forClass(Scope.class);
        verify(client).send(scope.capture(), eq(Api.DYNAMODB), eq("DescribeTable"), any());
        assertEquals(caller, scope.getValue());
    }

    @Test
    void ioFailureIsInternalServerError() throws Exception {
        when(client.send(any(), any(), any(), any())).thenThrow(new IOException("boom"));

        AwsException error = assertThrows(AwsException.class,
                () -> execute("DescribeTable", "{\"TableName\":\"orders\"}"));

        assertEquals("InternalServerError", error.getErrorCode());
        assertEquals(500, error.getHttpStatus());
        assertEquals("DynamoDB Local at http://local:8000 did not answer: java.io.IOException: boom",
                error.getMessage());
    }

    @Test
    void untaggedTableListsNoTags() throws Exception {
        answerOrdersTable();

        assertEquals(Map.of(), backend.listTagsOfResource(SCOPE, ORDERS_ARN));
    }

    @Test
    void tagAbsentTableFails() throws Exception {
        answer("DescribeTable", 400, "{\"__type\":\"com.amazonaws.dynamodb.v20120810#ResourceNotFoundException\","
                + "\"message\":\"Requested resource not found\"}");

        AwsException error = assertThrows(AwsException.class,
                () -> backend.tagResource(SCOPE, ORDERS_ARN, Map.of("env", "dev")));

        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertTrue(tags.keysForAccount(ACCOUNT).isEmpty());
    }

    @Test
    void wireListTagsOfAMissingTableIsAccessDenied() throws Exception {
        answer("DescribeTable", 400, "{\"__type\":\"com.amazonaws.dynamodb.v20120810#ResourceNotFoundException\","
                + "\"message\":\"Requested resource not found\"}");

        AwsException error = assertThrows(AwsException.class,
                () -> execute("ListTagsOfResource", "{\"ResourceArn\":\"" + ORDERS_ARN + "\"}"));

        assertEquals("AccessDeniedException", error.getErrorCode());
        assertEquals("User is not authorized to perform: dynamodb:ListTagsOfResource on resource: " + ORDERS_ARN,
                error.getMessage());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void tagArnOfAnUnknownPartitionIsValidation() throws Exception {
        String arn = "arn:notaws:dynamodb:eu-west-1:123456789012:table/orders";

        AwsException error = assertThrows(AwsException.class,
                () -> execute("TagResource", "{\"ResourceArn\":\"" + arn + "\",\"Tags\":[]}"));

        assertEquals("ValidationException", error.getErrorCode());
        neverSent("DescribeTable");
    }

    @Test
    void tagArnOfAnotherRegionOrPartitionIsNotFound() throws Exception {
        for (String foreign : List.of("arn:aws:dynamodb:us-east-1:123456789012:table/orders",
                "arn:aws-cn:dynamodb:cn-north-1:123456789012:table/orders")) {
            AwsException error = assertThrows(AwsException.class,
                    () -> execute("UntagResource", "{\"ResourceArn\":\"" + foreign + "\",\"TagKeys\":[]}"));

            assertEquals("ResourceNotFoundException", error.getErrorCode());
        }
        neverSent("DescribeTable");
    }

    @Test
    void createTableRecordsRequestTags() throws Exception {
        answer("CreateTable", 200, "{\"TableDescription\":{\"TableName\":\"orders\",\"TableArn\":\"" + LOCAL
                + "table/orders\"}}");
        answerOrdersTable();

        execute("CreateTable", "{\"TableName\":\"orders\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"dev\"}]}");

        assertEquals(Map.of("env", "dev"), backend.listTagsOfResource(SCOPE, ORDERS_ARN));
    }

    @Test
    void deleteTableDropsTags() throws Exception {
        tags.putForAccount(ACCOUNT, REGION + "/orders", Map.of("env", "dev"));
        answer("DeleteTable", 200, "{\"TableDescription\":{\"TableName\":\"orders\",\"TableStatus\":\"DELETING\"}}");

        execute("DeleteTable", "{\"TableName\":\"" + ORDERS_ARN + "\"}");

        assertTrue(tags.getForAccount(ACCOUNT, REGION + "/orders").isEmpty());
    }

    @Test
    void directDeleteLiftsDeletionProtectionFirst() throws Exception {
        answer("DescribeTable", 200, "{\"Table\":{\"TableName\":\"orders\",\"DeletionProtectionEnabled\":true}}");
        answer("UpdateTable", 200, "{\"TableDescription\":{\"TableName\":\"orders\"}}");
        answer("DeleteTable", 200, "{\"TableDescription\":{\"TableName\":\"orders\"}}");

        backend.deleteTable(SCOPE, "orders");

        InOrder order = inOrder(client);
        order.verify(client).send(any(), eq(Api.DYNAMODB), eq("UpdateTable"),
                eq(json("{\"TableName\":\"orders\",\"DeletionProtectionEnabled\":false}")));
        order.verify(client).send(any(), eq(Api.DYNAMODB), eq("DeleteTable"), eq(json("{\"TableName\":\"orders\"}")));
    }

    @Test
    void directDeleteOfAnUnprotectedTableSendsOnlyDeleteTable() throws Exception {
        answer("DescribeTable", 200, "{\"Table\":{\"TableName\":\"orders\",\"DeletionProtectionEnabled\":false}}");
        answer("DeleteTable", 200, "{\"TableDescription\":{\"TableName\":\"orders\"}}");

        backend.deleteTable(SCOPE, "orders");

        neverSent("UpdateTable");
        assertEquals(json("{\"TableName\":\"orders\"}"), sent("DeleteTable"));
    }

    @Test
    void wireTagRoundTrip() throws Exception {
        answerOrdersTable();

        Reply tagged = execute("TagResource", "{\"ResourceArn\":\"" + ORDERS_ARN + "\",\"Tags\":["
                + "{\"Key\":\"env\",\"Value\":\"dev\"},{\"Key\":\"team\",\"Value\":\"a\"}]}");
        JsonNode listed = execute("ListTagsOfResource", "{\"ResourceArn\":\"" + ORDERS_ARN + "\"}").body();
        Reply untagged = execute("UntagResource", "{\"ResourceArn\":\"" + ORDERS_ARN + "\",\"TagKeys\":[\"env\"]}");
        JsonNode remaining = execute("ListTagsOfResource", "{\"ResourceArn\":\"" + ORDERS_ARN + "\"}").body();

        assertEquals(200, tagged.status());
        assertEquals(json("{}"), tagged.body());
        assertEquals(json("{\"Tags\":[{\"Key\":\"env\",\"Value\":\"dev\"},"
                + "{\"Key\":\"team\",\"Value\":\"a\"}]}"), listed);
        assertEquals(200, untagged.status());
        assertEquals(json("{\"Tags\":[{\"Key\":\"team\",\"Value\":\"a\"}]}"), remaining);
        neverSent("TagResource");
        neverSent("UntagResource");
        neverSent("ListTagsOfResource");
    }

    @Test
    void replicaUpdatesRejected() throws Exception {
        AwsException wire = assertThrows(AwsException.class, () -> execute("UpdateTable",
                "{\"TableName\":\"orders\",\"ReplicaUpdates\":[{\"Create\":{\"RegionName\":\"us-east-1\"}}]}"));
        AwsException typed = assertThrows(AwsException.class,
                () -> backend.applyReplicaUpdates(SCOPE, "orders", List.of("us-east-1"), List.of()));

        for (AwsException error : List.of(wire, typed)) {
            assertEquals("ValidationException", error.getErrorCode());
            assertEquals(REPLICAS_UNSUPPORTED, error.getMessage());
            assertEquals(400, error.getHttpStatus());
        }
        neverSent("UpdateTable");
    }

    @Test
    void ensureGlobalTableDescribes() throws Exception {
        answerOrdersTable();

        TableDefinition table = backend.ensureGlobalTable(SCOPE, "orders");

        assertEquals("orders", table.getTableName());
        assertEquals(ORDERS_ARN, table.getTableArn());
        assertEquals("", table.getTableId());
    }

    @Test
    void createTableTypedProvisionedJson() throws Exception {
        answer("CreateTable", 200, "{\"TableDescription\":{\"TableName\":\"orders\",\"TableArn\":\"" + LOCAL
                + "table/orders\"}}");

        TableDefinition table = backend.createTable(SCOPE, "orders",
                List.of(new KeySchemaElement("pk", "HASH"), new KeySchemaElement("sk", "RANGE")),
                List.of(new AttributeDefinition("pk", "S"), new AttributeDefinition("sk", "S"),
                        new AttributeDefinition("g", "S"), new AttributeDefinition("l", "N")),
                3L, 4L,
                List.of(new GlobalSecondaryIndex("gsi1", List.of(new KeySchemaElement("g", "HASH")), null,
                        "INCLUDE", List.of("a"))),
                List.of(new LocalSecondaryIndex("lsi1", List.of(new KeySchemaElement("pk", "HASH"),
                        new KeySchemaElement("l", "RANGE")), null, "KEYS_ONLY")));

        assertEquals(json("""
                {"TableName":"orders",
                 "KeySchema":[{"AttributeName":"pk","KeyType":"HASH"},{"AttributeName":"sk","KeyType":"RANGE"}],
                 "AttributeDefinitions":[{"AttributeName":"pk","AttributeType":"S"},
                   {"AttributeName":"sk","AttributeType":"S"},{"AttributeName":"g","AttributeType":"S"},
                   {"AttributeName":"l","AttributeType":"N"}],
                 "BillingMode":"PROVISIONED",
                 "ProvisionedThroughput":{"ReadCapacityUnits":3,"WriteCapacityUnits":4},
                 "GlobalSecondaryIndexes":[{"IndexName":"gsi1","KeySchema":[{"AttributeName":"g","KeyType":"HASH"}],
                   "Projection":{"ProjectionType":"INCLUDE","NonKeyAttributes":["a"]},
                   "ProvisionedThroughput":{"ReadCapacityUnits":3,"WriteCapacityUnits":4}}],
                 "LocalSecondaryIndexes":[{"IndexName":"lsi1","KeySchema":[{"AttributeName":"pk","KeyType":"HASH"},
                   {"AttributeName":"l","KeyType":"RANGE"}],"Projection":{"ProjectionType":"KEYS_ONLY"}}]}
                """), sent("CreateTable"));
        assertEquals(ORDERS_ARN, table.getTableArn());
        assertEquals(List.of(REGION + "/orders"), List.copyOf(tags.keysForAccount(ACCOUNT)));
    }

    @Test
    void createTableTypedOnDemandJson() throws Exception {
        answer("CreateTable", 200, "{\"TableDescription\":{\"TableName\":\"orders\"}}");
        GlobalSecondaryIndex gsi = new GlobalSecondaryIndex();
        gsi.setIndexName("gsi1");
        gsi.setKeySchema(List.of(new KeySchemaElement("g", "HASH")));

        backend.createTable(SCOPE, "orders", List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S"), new AttributeDefinition("g", "S")),
                null, null, List.of(gsi), List.of());

        assertEquals(json("""
                {"TableName":"orders",
                 "KeySchema":[{"AttributeName":"pk","KeyType":"HASH"}],
                 "AttributeDefinitions":[{"AttributeName":"pk","AttributeType":"S"},
                   {"AttributeName":"g","AttributeType":"S"}],
                 "BillingMode":"PAY_PER_REQUEST",
                 "GlobalSecondaryIndexes":[{"IndexName":"gsi1","KeySchema":[{"AttributeName":"g","KeyType":"HASH"}],
                   "Projection":{"ProjectionType":"ALL"}}]}
                """), sent("CreateTable"));
    }

    @Test
    void describeTableMapsStreamFields() throws Exception {
        answer("DescribeTable", 200, "{\"Table\":{\"TableName\":\"orders\",\"TableArn\":\"" + LOCAL + "table/orders\","
                + "\"CreationDateTime\":1.790463342979E9,"
                + "\"StreamSpecification\":{\"StreamEnabled\":true,\"StreamViewType\":\"KEYS_ONLY\"},"
                + "\"LatestStreamArn\":\"" + LOCAL + STREAM_RESOURCE + "\"}}");

        TableDefinition table = backend.describeTable(SCOPE, "orders");

        assertTrue(table.isStreamEnabled());
        assertEquals("KEYS_ONLY", table.getStreamViewType());
        assertEquals(PUBLIC + STREAM_RESOURCE, table.getStreamArn());
        assertEquals(Instant.ofEpochMilli(1790463342979L), table.getCreationDateTime());
    }

    @Test
    void findTableAbsentIsEmpty() throws Exception {
        answer("DescribeTable", 400, "{\"__type\":\"com.amazonaws.dynamodb.v20120810#ResourceNotFoundException\","
                + "\"message\":\"Requested resource not found\"}");

        assertEquals(Optional.empty(), backend.findTable(SCOPE, "orders"));
    }

    private void answerStreamingOrdersTable(String viewType) throws Exception {
        answer("DescribeTable", 200, "{\"Table\":{\"TableName\":\"orders\","
                + "\"StreamSpecification\":{\"StreamEnabled\":true,\"StreamViewType\":\"" + viewType + "\"},"
                + "\"LatestStreamArn\":\"" + LOCAL + STREAM_RESOURCE + "\"}}");
    }

    @Test
    void enableStreamIdempotent() throws Exception {
        answerStreamingOrdersTable("NEW_AND_OLD_IMAGES");

        TableDefinition unspecified = backend.enableStream(SCOPE, "orders", null);
        TableDefinition same = backend.enableStream(SCOPE, "orders", "NEW_AND_OLD_IMAGES");

        assertEquals(PUBLIC + STREAM_RESOURCE, unspecified.getStreamArn());
        assertEquals("NEW_AND_OLD_IMAGES", same.getStreamViewType());
        neverSent("UpdateTable");
    }

    @Test
    void enableStreamWithNewViewTypeRestartsStream() throws Exception {
        answerStreamingOrdersTable("NEW_AND_OLD_IMAGES");
        answer("UpdateTable", 200, "{\"TableDescription\":{\"TableName\":\"orders\","
                + "\"StreamSpecification\":{\"StreamEnabled\":true,\"StreamViewType\":\"KEYS_ONLY\"},"
                + "\"LatestStreamArn\":\"" + LOCAL + STREAM_RESOURCE + "\"}}");

        TableDefinition table = backend.enableStream(SCOPE, "orders", "KEYS_ONLY");

        assertEquals(List.of(
                json("{\"TableName\":\"orders\",\"StreamSpecification\":{\"StreamEnabled\":false}}"),
                json("{\"TableName\":\"orders\",\"StreamSpecification\":{\"StreamEnabled\":true,"
                        + "\"StreamViewType\":\"KEYS_ONLY\"}}")),
                sentBodies(Api.DYNAMODB, "UpdateTable"));
        assertEquals("KEYS_ONLY", table.getStreamViewType());
    }

    @Test
    void disableStreamIdempotent() throws Exception {
        answerOrdersTable();

        TableDefinition table = backend.disableStream(SCOPE, "orders");

        assertFalse(table.isStreamEnabled());
        assertNull(table.getStreamArn());
        neverSent("UpdateTable");
    }

    @Test
    void updateItemReturnsOnlyImages() throws Exception {
        answer("UpdateItem", 200, "{\"Attributes\":{\"pk\":{\"S\":\"a\"},\"n\":{\"N\":\"2\"}}}");
        JsonNode key = json("{\"pk\":{\"S\":\"a\"}}");

        JsonNode updatedOnly = backend.updateItem(SCOPE, "orders", key, null, "SET n = :n", null,
                json("{\":n\":{\"N\":\"2\"}}"), "UPDATED_NEW", null);
        JsonNode allNew = backend.updateItem(SCOPE, "orders", key, null, "SET n = :n", null,
                json("{\":n\":{\"N\":\"2\"}}"), "ALL_NEW", null);

        assertNull(updatedOnly);
        assertEquals(json("{\"pk\":{\"S\":\"a\"},\"n\":{\"N\":\"2\"}}"), allNew);
        assertEquals(json("{\"TableName\":\"orders\",\"Key\":{\"pk\":{\"S\":\"a\"}},"
                + "\"UpdateExpression\":\"SET n = :n\","
                + "\"ExpressionAttributeValues\":{\":n\":{\"N\":\"2\"}},\"ReturnValues\":\"ALL_NEW\"}"),
                sent("UpdateItem"));
    }

    @Test
    void scanPage() throws Exception {
        answer("Scan", 200, "{\"Items\":[{\"pk\":{\"S\":\"a\"}},{\"pk\":{\"S\":\"b\"}}],\"Count\":2,"
                + "\"ScannedCount\":5,\"LastEvaluatedKey\":{\"pk\":{\"S\":\"b\"}}}");

        ScanPage page = backend.scan(SCOPE, "orders", null, null, null, null, 2, json("{\"pk\":{\"S\":\"0\"}}"));

        assertEquals(List.of(json("{\"pk\":{\"S\":\"a\"}}"), json("{\"pk\":{\"S\":\"b\"}}")), page.items());
        assertEquals(5, page.scannedCount());
        assertEquals(json("{\"pk\":{\"S\":\"b\"}}"), page.lastEvaluatedKey());
        assertEquals(json("{\"TableName\":\"orders\",\"Limit\":2,\"ExclusiveStartKey\":{\"pk\":{\"S\":\"0\"}}}"),
                sent("Scan"));
    }

    @Test
    void resourcesListsTablesOfKnownRegions() throws Exception {
        tags.putForAccount(ACCOUNT, REGION + "/orders", Map.of("env", "dev"));
        tags.putForAccount(ACCOUNT, "us-east-1/users", Map.of());
        when(client.send(any(), eq(Api.DYNAMODB), eq("ListTables"), any())).thenAnswer(invocation -> {
            Scope scope = invocation.getArgument(0);
            JsonNode body = invocation.getArgument(3);
            String page;
            if ("us-east-1".equals(scope.region())) {
                page = "{\"TableNames\":[\"users\"]}";
            } else if (body.has("ExclusiveStartTableName")) {
                page = "{\"TableNames\":[\"direct\"]}";
            } else {
                page = "{\"TableNames\":[\"orders\"],\"LastEvaluatedTableName\":\"orders\"}";
            }
            return new Reply(200, json(page), Map.of());
        });

        List<ExplorerResource> resources = backend.resources(ACCOUNT);

        Map<String, ExplorerResource> byArn = resources.stream()
                .collect(Collectors.toMap(ExplorerResource::arn, resource -> resource));
        assertEquals(Set.of(ORDERS_ARN, PUBLIC + "table/direct", "arn:aws:dynamodb:us-east-1:123456789012:table/users"),
                byArn.keySet());
        ExplorerResource orders = byArn.get(ORDERS_ARN);
        assertEquals("dynamodb:table", orders.resourceType());
        assertEquals(REGION, orders.region());
        assertEquals(ACCOUNT, orders.owningAccountId());
        assertEquals(Map.of("env", "dev"), orders.tags());
        assertEquals(Map.of(), byArn.get(PUBLIC + "table/direct").tags());
        assertTrue(sentBodies(Api.DYNAMODB, "ListTables").contains(json("{\"ExclusiveStartTableName\":\"orders\"}")));
    }

    @Test
    void resetRefusedWith409() {
        AwsException error = assertThrows(AwsException.class, backend::checkReset);

        assertEquals("UnsupportedOperation", error.getErrorCode());
        assertEquals(409, error.getHttpStatus());
    }

    @Test
    void startRetriesConnectionFailuresThenSucceeds() throws Exception {
        Scope probe = new Scope("000000000000", "us-east-1");
        when(client.send(eq(probe), eq(Api.DYNAMODB), eq("ListTables"), any(), any()))
                .thenThrow(new ConnectException("refused"))
                .thenReturn(new Reply(200, json("{\"TableNames\":[]}"), Map.of()));

        backend.start();

        verify(client, times(2)).send(eq(probe), eq(Api.DYNAMODB), eq("ListTables"), eq(json("{\"Limit\":1}")), any());
    }

    @Test
    void startRetriesAConnectionClosedWithoutAnswer() throws Exception {
        Scope probe = new Scope("000000000000", "us-east-1");
        when(client.send(eq(probe), eq(Api.DYNAMODB), eq("ListTables"), any(), any()))
                .thenThrow(new IOException("HTTP/1.1 header parser received no bytes"))
                .thenReturn(new Reply(200, json("{\"TableNames\":[]}"), Map.of()));

        backend.start();

        verify(client, times(2)).send(eq(probe), eq(Api.DYNAMODB), eq("ListTables"), any(), any());
    }

    @Test
    void startCapsEachProbeAtTheRemainingBudget() throws Exception {
        ArgumentCaptor<Duration> budget = ArgumentCaptor.forClass(Duration.class);
        when(client.send(any(), any(), any(), any(), budget.capture()))
                .thenReturn(new Reply(200, json("{\"TableNames\":[]}"), Map.of()));

        backend.start();

        assertTrue(budget.getValue().isPositive());
        assertTrue(budget.getValue().compareTo(Duration.ofSeconds(5)) <= 0);
    }

    @Test
    void startFailsWhenBudgetExpires() throws Exception {
        backend = new DynamoDbLocalBackend(client, tags, mapper, Duration.ZERO);
        when(client.send(any(), any(), any(), any(), any())).thenThrow(new ConnectException("refused"));

        IllegalStateException error = assertThrows(IllegalStateException.class, backend::start);

        assertEquals("DynamoDB Local at http://local:8000 is not reachable after 0s", error.getMessage());
    }

    @Test
    void startFailsOnNon2xx() throws Exception {
        when(client.send(any(), any(), any(), any(), any())).thenReturn(new Reply(500, json("{\"message\":\"x\"}"), Map.of()));

        IllegalStateException error = assertThrows(IllegalStateException.class, backend::start);

        assertEquals("DynamoDB Local at http://local:8000 answered ListTables with status 500: {\"message\":\"x\"}",
                error.getMessage());
    }

    @Test
    void validatedEndpointMissing() {
        for (Optional<String> missing : List.of(Optional.<String>empty(), Optional.of("  "))) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> DynamoDbLocalBackend.validatedEndpoint(missing));

            assertEquals("floci.services.dynamodb.local-endpoint is required when floci.services.dynamodb.backend "
                    + "is local", error.getMessage());
        }
    }

    @Test
    void validatedEndpointFtpScheme() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> DynamoDbLocalBackend.validatedEndpoint(Optional.of("ftp://localhost:8000")));

        assertTrue(error.getMessage().contains("ftp://localhost:8000"), error.getMessage());
    }

    @Test
    void validatedEndpointAwsHost() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> DynamoDbLocalBackend.validatedEndpoint(Optional.of("https://dynamodb.us-east-1.amazonaws.com")));

        assertTrue(error.getMessage().endsWith("must point at DynamoDB Local, not an AWS endpoint"),
                error.getMessage());
    }

    @Test
    void validatedEndpointAwsHostInAnyPartitionOrWithTrailingDot() {
        for (String endpoint : List.of("https://dynamodb.us-east-1.amazonaws.com.",
                "https://dynamodb.cn-north-1.amazonaws.com.cn", "https://dynamodb.eu-isoe-west-1.cloud.adc-e.uk")) {
            assertThrows(IllegalStateException.class,
                    () -> DynamoDbLocalBackend.validatedEndpoint(Optional.of(endpoint)), endpoint);
        }
    }

    @Test
    void validatedEndpointUserInfoRejected() {
        assertThrows(IllegalStateException.class,
                () -> DynamoDbLocalBackend.validatedEndpoint(Optional.of("http://user:secret@localhost:8000")));
    }

    @Test
    void validatedEndpointValid() {
        assertEquals(URI.create("http://localhost:8000"),
                DynamoDbLocalBackend.validatedEndpoint(Optional.of("http://localhost:8000")));
    }
}
