package io.github.hectorvent.floci.services.dynamodb.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbStreamService;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbStreamsJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.KinesisStreamingForwarder;
import io.github.hectorvent.floci.services.dynamodb.NativeDynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.NativeDynamoDbStreamsJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.NativeDynamoDbTableService;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Api;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Call;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Reply;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Scope;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The native engine behind the DynamoDB seam, and the thin public handlers in front of it. */
class DynamoDbNativeBackendTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final Scope SCOPE = new Scope(ACCOUNT, REGION);
    private static final String TABLE = "orders";

    private final ObjectMapper mapper = new ObjectMapper();
    private NativeDynamoDbBackend backend;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory(ACCOUNT));
        DynamoDbStreamService streams = new DynamoDbStreamService(mapper, storageFactory);
        DynamoDbService service = new DynamoDbService(storageFactory, new RegionResolver(REGION, ACCOUNT), streams,
                mock(KinesisStreamingForwarder.class), null, mapper, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS));
        NativeDynamoDbTableService tables = new NativeDynamoDbTableService(service, streams, mock(KinesisService.class));
        backend = new NativeDynamoDbBackend(new NativeDynamoDbJsonHandler(service, tables, mapper),
                new NativeDynamoDbStreamsJsonHandler(streams, service, mapper), service, mapper);
    }

    private Reply call(Api api, String action, JsonNode body) throws Exception {
        return backend.execute(new Call(SCOPE, api, action, body));
    }

    private Reply createTable(String extraFields) throws Exception {
        return call(Api.DYNAMODB, "CreateTable", json("""
                {"TableName":"%s",
                 "KeySchema":[{"AttributeName":"id","KeyType":"HASH"}],
                 "AttributeDefinitions":[{"AttributeName":"id","AttributeType":"S"}],
                 "BillingMode":"PAY_PER_REQUEST"%s}
                """.formatted(TABLE, extraFields)));
    }

    private JsonNode json(String text) throws Exception {
        return mapper.readTree(text);
    }

    @Test
    void rawDeleteTableHonoursDeletionProtectionWhileTypedDeleteDoesNot() throws Exception {
        createTable(",\"DeletionProtectionEnabled\":true");

        AwsException refused = assertThrows(AwsException.class,
                () -> call(Api.DYNAMODB, "DeleteTable", json("{\"TableName\":\"" + TABLE + "\"}")));
        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(backend.findTable(SCOPE, TABLE).isPresent());

        backend.deleteTable(SCOPE, TABLE);

        assertTrue(backend.findTable(SCOPE, TABLE).isEmpty());
    }

    @Test
    void typedUpdateItemReturnsOnlyTheRequestedImage() throws Exception {
        backend.createTable(SCOPE, TABLE, List.of(new KeySchemaElement("id", "HASH")),
                List.of(new AttributeDefinition("id", "S")), 5L, 5L, List.of(), List.of());
        JsonNode key = json("{\"id\":{\"S\":\"k\"}}");
        backend.putItem(SCOPE, TABLE, json("{\"id\":{\"S\":\"k\"},\"n\":{\"N\":\"1\"}}"), null, null, null);
        JsonNode names = json("{\"#n\":\"n\"}");

        JsonNode newImage = backend.updateItem(SCOPE, TABLE, key, null, "SET #n = :v", names,
                json("{\":v\":{\"N\":\"2\"}}"), "ALL_NEW", null);
        JsonNode oldImage = backend.updateItem(SCOPE, TABLE, key, null, "SET #n = :v", names,
                json("{\":v\":{\"N\":\"3\"}}"), "ALL_OLD", null);
        JsonNode none = backend.updateItem(SCOPE, TABLE, key, null, "SET #n = :v", names,
                json("{\":v\":{\"N\":\"4\"}}"), "NONE", null);
        JsonNode updatedNew = backend.updateItem(SCOPE, TABLE, key, null, "SET #n = :v", names,
                json("{\":v\":{\"N\":\"5\"}}"), "UPDATED_NEW", null);

        assertEquals(json("{\"id\":{\"S\":\"k\"},\"n\":{\"N\":\"2\"}}"), newImage);
        assertEquals(json("{\"id\":{\"S\":\"k\"},\"n\":{\"N\":\"2\"}}"), oldImage);
        assertNull(none);
        assertNull(updatedNew);
        assertEquals(json("{\"id\":{\"S\":\"k\"},\"n\":{\"N\":\"5\"}}"), backend.getItem(SCOPE, TABLE, key));
    }

    @Test
    void unknownActionRepliesWithAJsonErrorBody() throws Exception {
        Reply reply = call(Api.DYNAMODB, "NoSuchAction", mapper.createObjectNode());

        assertEquals(400, reply.status());
        assertEquals("UnknownOperationException", reply.body().path("__type").asText());
        assertEquals("Operation NoSuchAction is not supported.", reply.body().path("message").asText());
    }

    @Test
    void rawCallsRoundTripAnItem() throws Exception {
        JsonNode item = json("{\"id\":{\"S\":\"a\"},\"v\":{\"S\":\"x\"}}");

        assertEquals(200, createTable("").status());
        ObjectNode put = mapper.createObjectNode().put("TableName", TABLE);
        put.set("Item", item);
        assertEquals(200, call(Api.DYNAMODB, "PutItem", put).status());
        ObjectNode get = mapper.createObjectNode().put("TableName", TABLE);
        get.set("Key", json("{\"id\":{\"S\":\"a\"}}"));
        Reply reply = call(Api.DYNAMODB, "GetItem", get);

        assertEquals(200, reply.status());
        assertEquals(item, reply.body().path("Item"));
    }

    @Test
    void streamsCallsReachTheStreamsHandler() throws Exception {
        createTable(",\"StreamSpecification\":{\"StreamEnabled\":true,\"StreamViewType\":\"NEW_IMAGE\"}");

        Reply reply = call(Api.DYNAMODB_STREAMS, "ListStreams", mapper.createObjectNode());

        assertEquals(200, reply.status());
        assertTrue(reply.body().path("Streams").isArray());
        assertEquals(TABLE, reply.body().path("Streams").path(0).path("TableName").asText());
    }

    @Test
    void scopeRejectsBlankAccountAndRegion() {
        IllegalArgumentException account = assertThrows(IllegalArgumentException.class, () -> new Scope(" ", REGION));
        IllegalArgumentException region = assertThrows(IllegalArgumentException.class, () -> new Scope(ACCOUNT, ""));

        assertTrue(account.getMessage().contains("accountId"));
        assertTrue(region.getMessage().contains("region"));
        assertThrows(IllegalArgumentException.class, () -> new Scope(null, REGION));
        assertThrows(IllegalArgumentException.class, () -> new Scope(ACCOUNT, null));
    }

    @Test
    void thinHandlersPassTheCallersScopeAndRebuildTheReply() throws Exception {
        List<Call> calls = new ArrayList<>();
        JsonNode replyBody = json("{\"ok\":true}");
        DynamoDbOperations recording = call -> {
            calls.add(call);
            return new Reply(201, replyBody, Map.of("X-Test", List.of("a", "b")));
        };
        RegionResolver resolver = new RegionResolver("eu-west-1", "111122223333");
        JsonNode request = mapper.createObjectNode();

        Response response = new DynamoDbJsonHandler(recording, resolver).handle("GetItem", request, "ap-south-1");
        new DynamoDbJsonHandler(recording, resolver).handle("GetItem", request, null);
        new DynamoDbStreamsJsonHandler(recording, resolver).handle("ListStreams", request, "ap-south-1");

        assertEquals(new Call(new Scope("111122223333", "ap-south-1"), Api.DYNAMODB, "GetItem", request),
                calls.get(0));
        assertSame(request, calls.get(0).body());
        assertEquals(new Scope("111122223333", "eu-west-1"), calls.get(1).scope());
        assertEquals(Api.DYNAMODB_STREAMS, calls.get(2).api());
        assertEquals(201, response.getStatus());
        assertSame(replyBody, response.getEntity());
        assertEquals(List.of("a", "b"), response.getStringHeaders().get("X-Test"));
    }
}
