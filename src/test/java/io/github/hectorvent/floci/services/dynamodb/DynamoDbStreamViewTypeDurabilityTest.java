package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.dynamodb.model.StreamDescription;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stream configuration written through the JSON handler must survive a restart.
 *
 * <p>A persistent backend serializes on write rather than holding the live table object, and both
 * CreateTable and UpdateTable mutate the table AFTER the service has stored it. Without a flush the
 * on-disk copy keeps the previous stream settings, and because startup rebuilds every stream from
 * the persisted table, a restart resurrects the old view type and records resume the old image
 * shape — with nothing in the running system reporting the stale value.
 */
class DynamoDbStreamViewTypeDurabilityTest {

    private static final String TABLE = "durable-stream";
    private static final String REGION = "us-east-1";

    private final ObjectMapper mapper = new ObjectMapper();

    /** A store backed by a real file, so a "restart" is just reading it again. */
    private StorageBackend<String, TableDefinition> diskStore(Path file) {
        PersistentStorage<String, TableDefinition> store =
                new PersistentStorage<>(file, new TypeReference<Map<String, TableDefinition>>() { });
        store.load();
        return store;
    }

    private DynamoDbJsonHandler handlerFor(StorageBackend<String, TableDefinition> store,
                                           DynamoDbStreamService streams) {
        DynamoDbService service = new DynamoDbService(
                store, null, new RegionResolver(REGION, "000000000000"), streams, null);
        return new DynamoDbJsonHandler(service, streams, null, mapper);
    }

    private ObjectNode createTableRequest(String viewType) {
        ObjectNode req = mapper.createObjectNode();
        req.put("TableName", TABLE);
        req.putArray("KeySchema").addObject().put("AttributeName", "id").put("KeyType", "HASH");
        req.putArray("AttributeDefinitions").addObject()
                .put("AttributeName", "id").put("AttributeType", "S");
        req.putObject("StreamSpecification")
                .put("StreamEnabled", true).put("StreamViewType", viewType);
        return req;
    }

    private ObjectNode updateViewTypeRequest(String viewType) {
        ObjectNode req = mapper.createObjectNode();
        req.put("TableName", TABLE);
        req.putObject("StreamSpecification")
                .put("StreamEnabled", true).put("StreamViewType", viewType);
        return req;
    }

    /** The stream settings a restart would read back off disk. */
    private TableDefinition reloadFromDisk(Path file) {
        return diskStore(file).get(REGION + "::" + TABLE).orElseThrow();
    }

    @Test
    void createTableStreamSettingsSurviveARestart(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("tables.json");
        StorageBackend<String, TableDefinition> store = diskStore(file);
        DynamoDbStreamService streams = new DynamoDbStreamService(mapper, store);

        handlerFor(store, streams).handle("CreateTable",
                createTableRequest("NEW_IMAGE"), REGION);

        TableDefinition persisted = reloadFromDisk(file);
        assertTrue(persisted.isStreamEnabled(), "a restart must still see the stream as enabled");
        assertEquals("NEW_IMAGE", persisted.getStreamViewType());
    }

    @Test
    void updateTableViewTypeRetargetSurvivesARestart(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("tables.json");
        StorageBackend<String, TableDefinition> store = diskStore(file);
        DynamoDbStreamService streams = new DynamoDbStreamService(mapper, store);
        DynamoDbJsonHandler handler = handlerFor(store, streams);

        handler.handle("CreateTable", createTableRequest("NEW_AND_OLD_IMAGES"), REGION);
        handler.handle("UpdateTable", updateViewTypeRequest("KEYS_ONLY"), REGION);

        assertEquals("KEYS_ONLY", reloadFromDisk(file).getStreamViewType(),
                "the retargeted view type must be on disk, not only in the live stream");

        // The restart itself: a fresh stream service rebuilds streams from the persisted table.
        StorageBackend<String, TableDefinition> reopened = diskStore(file);
        DynamoDbStreamService afterRestart = new DynamoDbStreamService(mapper, reopened);
        StreamDescription sd = afterRestart.listStreams(TABLE, REGION).get(0);
        assertEquals("KEYS_ONLY", sd.getStreamViewType(),
                "a restarted stream must not resume the old image shape");
    }
}
