package io.github.hectorvent.floci.services.dynamodb.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbFacade;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Api;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Call;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Scope;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader.CheckpointLifetime;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader.Cursor;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader.Position;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader.RecordsPage;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader.Shard;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader.Stream;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The one DynamoDB backend CDI selects, and the one Resource Explorer provider in front of it. */
@QuarkusTest
class DynamoDbBackendSelectionTest {

    private static final String REGION = "us-east-1";
    private static final Scope ACCOUNT_A = new Scope("210987654321", REGION);
    private static final Scope ACCOUNT_B = new Scope("321098765432", REGION);

    @Inject
    DynamoDbOperations operations;

    @Inject
    DynamoDbItemAccess items;

    @Inject
    DynamoDbTableAccess tables;

    @Inject
    DynamoDbStreamReader streamReader;

    @Inject
    DynamoDbBackendLifecycle lifecycle;

    // Collected exactly as ResourceExplorer2Service collects its providers.
    @Inject
    Instance<ResourceProvider> providers;

    @Inject
    ObjectMapper mapper;

    @Inject
    DynamoDbBackendSelector selector;

    @Test
    void everyCapabilityIsTheOneNativeBackend() {
        Object backend = ClientProxy.unwrap(operations);

        assertEquals("native", selector.selected());
        assertInstanceOf(NativeDynamoDbBackend.class, backend);
        assertSame(backend, ClientProxy.unwrap(items));
        assertSame(backend, ClientProxy.unwrap(tables));
        assertSame(backend, ClientProxy.unwrap(lifecycle));
    }

    @Test
    void theUnselectedLocalBackendIsNeverConstructed() {
        assertFalse(Arc.container().getActiveContext(ApplicationScoped.class).getState().getContextualInstances()
                .keySet().stream().anyMatch(bean -> bean.getBeanClass() == DynamoDbLocalBackend.class));
    }

    @Test
    void nativeServesEverySeamWithProcessCheckpoints() {
        NativeDynamoDbBackend nativeBackend = mock(NativeDynamoDbBackend.class);
        DynamoDbLocalBackend localBackend = mock(DynamoDbLocalBackend.class);
        DynamoDbBackendSelector nativeSelector = new DynamoDbBackendSelector(config("native"), nativeBackend, localBackend);

        assertEquals("native", nativeSelector.selected());
        assertServesEverySeam(nativeBackend, nativeSelector);
        assertEquals(CheckpointLifetime.PROCESS, nativeSelector.streamReader().checkpointLifetime());
        verifyNoInteractions(localBackend);
    }

    @Test
    void localServesEverySeamWithStreamCheckpoints() {
        NativeDynamoDbBackend nativeBackend = mock(NativeDynamoDbBackend.class);
        DynamoDbLocalBackend localBackend = mock(DynamoDbLocalBackend.class);
        DynamoDbBackendSelector localSelector = new DynamoDbBackendSelector(config("local"), nativeBackend, localBackend);

        assertEquals("local", localSelector.selected());
        assertServesEverySeam(localBackend, localSelector);
        assertEquals(CheckpointLifetime.STREAM, localSelector.streamReader().checkpointLifetime());
        verifyNoInteractions(nativeBackend);
    }

    @Test
    void theBackendNameIsTrimmedAndCaseInsensitive() {
        DynamoDbLocalBackend localBackend = mock(DynamoDbLocalBackend.class);
        DynamoDbBackendSelector localSelector =
                new DynamoDbBackendSelector(config(" LOCAL "), mock(NativeDynamoDbBackend.class), localBackend);

        assertEquals("local", localSelector.selected());
        assertSame(localBackend, localSelector.operations());
    }

    @Test
    void anUnknownBackendFailsSelection() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> new DynamoDbBackendSelector(
                config("sqlite"), mock(NativeDynamoDbBackend.class), mock(DynamoDbLocalBackend.class)));

        assertEquals("floci.services.dynamodb.backend must be 'native' or 'local', got 'sqlite'", failure.getMessage());
    }

    private static EmulatorConfig config(String backend) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().dynamodb().backend()).thenReturn(backend);
        return config;
    }

    private static void assertServesEverySeam(DynamoDbBackend expected, DynamoDbBackendSelector selector) {
        assertSame(expected, selector.operations());
        assertSame(expected, selector.itemAccess());
        assertSame(expected, selector.tableAccess());
        assertSame(expected, selector.lifecycle());
    }

    @Test
    void startupSchedulesTheTtlSweep() {
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && "dynamodb-ttl-sweeper".equals(thread.getName())));
    }

    @Test
    void theFacadeIsTheOnlyDynamoDbTableProvider() {
        List<ResourceProvider> dynamoDbProviders = providers.stream()
                .filter(provider -> provider.getSupportedResourceTypes().stream()
                        .anyMatch(type -> "dynamodb:table".equals(type.resourceType())))
                .toList();

        assertEquals(1, dynamoDbProviders.size());
        assertInstanceOf(DynamoDbFacade.class, ClientProxy.unwrap(dynamoDbProviders.get(0)));
    }

    @Test
    void callsRunUnderTheScopeAccount() throws Exception {
        String tableName = "backend-scope-" + UUID.randomUUID();
        tables.createTable(ACCOUNT_A, tableName, List.of(new KeySchemaElement("id", "HASH")),
                List.of(new AttributeDefinition("id", "S")), 5L, 5L, List.of(), List.of());
        try {
            assertTrue(tables.findTable(ACCOUNT_B, tableName).isEmpty());
            assertTrue(tables.findTable(ACCOUNT_A, tableName).isPresent());

            ObjectNode body = mapper.createObjectNode().put("TableName", tableName);
            AwsException notFound = assertThrows(AwsException.class,
                    () -> operations.execute(new Call(ACCOUNT_B, Api.DYNAMODB, "DescribeTable", body)));
            assertEquals("ResourceNotFoundException", notFound.getErrorCode());
            assertEquals(200, operations.execute(new Call(ACCOUNT_A, Api.DYNAMODB, "DescribeTable", body)).status());
        } finally {
            tables.deleteTable(ACCOUNT_A, tableName);
        }
    }

    private void createTable(Scope scope, String tableName) {
        tables.createTable(scope, tableName, List.of(new KeySchemaElement("id", "HASH")),
                List.of(new AttributeDefinition("id", "S")), 5L, 5L, List.of(), List.of());
    }

    @Test
    void theStreamReaderReadsTheSelectedBackendAsTheStreamOwner() {
        String tableName = "backend-stream-" + UUID.randomUUID();
        createTable(ACCOUNT_A, tableName);
        try {
            TableDefinition table = tables.enableStream(ACCOUNT_A, tableName, "NEW_AND_OLD_IMAGES");
            ObjectNode item = mapper.createObjectNode();
            item.putObject("id").put("S", "streamed");
            items.putItem(ACCOUNT_A, tableName, item, null, null, null);

            Stream stream = Stream.of(table.getStreamArn());
            List<Shard> shards = streamReader.shards(stream);
            Cursor cursor = streamReader.getShardIterator(stream, shards.get(0).shardId(), Position.TRIM_HORIZON, null);
            RecordsPage page = streamReader.getRecords(cursor, 10);

            assertEquals(ACCOUNT_A, stream.scope());
            assertEquals(1, shards.size());
            assertEquals(1, page.records().size());
            assertEquals(item, page.records().get(0).awsRecord().path("dynamodb").path("Keys"));
            assertEquals(CheckpointLifetime.PROCESS, streamReader.checkpointLifetime());
        } finally {
            tables.deleteTable(ACCOUNT_A, tableName);
        }
    }

    @Test
    void listStreamsAnswersOnlyTheScopeAccount() throws Exception {
        String tableName = "backend-list-streams-" + UUID.randomUUID();
        createTable(ACCOUNT_A, tableName);
        createTable(ACCOUNT_B, tableName);
        try {
            String streamA = tables.enableStream(ACCOUNT_A, tableName, "KEYS_ONLY").getStreamArn();
            String streamB = tables.enableStream(ACCOUNT_B, tableName, "KEYS_ONLY").getStreamArn();
            assertNotEquals(streamA, streamB);

            assertEquals(List.of(streamA), listedStreamArns(ACCOUNT_A, tableName));
            assertEquals(List.of(streamB), listedStreamArns(ACCOUNT_B, tableName));
        } finally {
            tables.deleteTable(ACCOUNT_A, tableName);
            tables.deleteTable(ACCOUNT_B, tableName);
        }
    }

    private List<String> listedStreamArns(Scope scope, String tableName) throws Exception {
        ObjectNode body = mapper.createObjectNode().put("TableName", tableName);
        JsonNode reply = operations.execute(new Call(scope, Api.DYNAMODB_STREAMS, "ListStreams", body)).body();
        return reply.path("Streams").findValuesAsText("StreamArn");
    }

    @Test
    void aStreamOutsideTheScopeIsNotFound() throws Exception {
        String tableName = "backend-foreign-stream-" + UUID.randomUUID();
        createTable(ACCOUNT_A, tableName);
        try {
            String streamArn = tables.enableStream(ACCOUNT_A, tableName, "KEYS_ONLY").getStreamArn();
            ObjectNode item = mapper.createObjectNode();
            item.putObject("id").put("S", "private");
            items.putItem(ACCOUNT_A, tableName, item, null, null, null);
            ObjectNode stream = mapper.createObjectNode().put("StreamArn", streamArn);
            String shardId = streams(ACCOUNT_A, "DescribeStream", stream)
                    .path("StreamDescription").path("Shards").path(0).path("ShardId").asText();
            ObjectNode fromStart = stream.deepCopy().put("ShardId", shardId).put("ShardIteratorType", "TRIM_HORIZON");
            String iterator = streams(ACCOUNT_A, "GetShardIterator", fromStart).path("ShardIterator").asText();
            ObjectNode read = mapper.createObjectNode().put("ShardIterator", iterator);

            for (Scope outside : List.of(ACCOUNT_B, new Scope(ACCOUNT_A.accountId(), "eu-west-1"))) {
                assertStreamNotFound(outside, "DescribeStream", stream, streamArn);
                assertStreamNotFound(outside, "GetShardIterator", fromStart, streamArn);
                assertStreamNotFound(outside, "GetRecords", read, streamArn);
            }
            assertEquals(1, streams(ACCOUNT_A, "GetRecords", read).path("Records").size());
        } finally {
            tables.deleteTable(ACCOUNT_A, tableName);
        }
    }

    private JsonNode streams(Scope scope, String action, ObjectNode body) throws Exception {
        return operations.execute(new Call(scope, Api.DYNAMODB_STREAMS, action, body)).body();
    }

    private void assertStreamNotFound(Scope scope, String action, ObjectNode body, String streamArn) {
        AwsException notFound = assertThrows(AwsException.class, () -> streams(scope, action, body));
        assertEquals("ResourceNotFoundException", notFound.getErrorCode());
        assertEquals("Stream not found: " + streamArn, notFound.getMessage());
    }
}
