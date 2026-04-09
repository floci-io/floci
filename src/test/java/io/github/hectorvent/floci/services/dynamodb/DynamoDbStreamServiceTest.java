package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.StreamDescription;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DynamoDbStreamServiceTest {

    private DynamoDbStreamService service;
    private ObjectMapper mapper;
    private StorageBackend<String, TableDefinition> storage;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        storage =  new InMemoryStorage<>();
        TableDefinition table = createTestTableWithStream();
        storage.put("us-east-1::" + table.getTableName(), table);
        service = new DynamoDbStreamService(mapper, storage);
    }

    private TableDefinition createTestTableWithStream() {
        var tableDef = new TableDefinition("TestTable", 
                List.of(new KeySchemaElement("userId", "HASH")),
                List.of(new AttributeDefinition("userId", "S")),
                "us-east-1", "000000000000");
        tableDef.setStreamEnabled(true);
        tableDef.setStreamArn("arn:aws:dynamodb:us-west-2:000000000000:table/TestTable/stream/2026-04-08T15:24:10.801");
        return tableDef;
    }

    @Test
    void loadsStreamOnStartup() {
        var streams = service.listStreams(null, null);
        assertEquals(1, streams.size());
        StreamDescription stream = streams.get(0);
        assertEquals("TestTable", stream.getTableName());
        assertEquals("ENABLED", stream.getStreamStatus());
    }

}
