package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.KinesisStreamingDestination;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KinesisStreamingForwarderTest {

    private KinesisService kinesisService;
    private KinesisStreamingForwarder forwarder;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        kinesisService = mock(KinesisService.class);
        objectMapper = new ObjectMapper();
        forwarder = new KinesisStreamingForwarder(kinesisService, objectMapper);
    }

    private TableDefinition createTable(String tableName) {
        return new TableDefinition(tableName,
                List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S")));
    }

    private ObjectNode createItem(String pk) {
        ObjectNode item = objectMapper.createObjectNode();
        ObjectNode pkValue = objectMapper.createObjectNode();
        pkValue.put("S", pk);
        item.set("pk", pkValue);
        return item;
    }

    @Test
    void forwardsToActiveDestination() {
        TableDefinition table = createTable("test-table");
        KinesisStreamingDestination dest = new KinesisStreamingDestination(
                "arn:aws:kinesis:us-east-1:000000000000:stream/test-stream");
        table.getKinesisStreamingDestinations().add(dest);

        when(kinesisService.putRecord(anyString(), any(byte[].class), anyString(), anyString()))
                .thenReturn("seq-1");

        forwarder.forward("INSERT", null, createItem("k1"), table, "us-east-1");

        verify(kinesisService).putRecord(eq("test-stream"), any(byte[].class), eq("k1"), eq("us-east-1"));
    }

    @Test
    void skipsDisabledDestination() {
        TableDefinition table = createTable("test-table");
        KinesisStreamingDestination dest = new KinesisStreamingDestination(
                "arn:aws:kinesis:us-east-1:000000000000:stream/test-stream");
        dest.setDestinationStatus("DISABLED");
        table.getKinesisStreamingDestinations().add(dest);

        forwarder.forward("INSERT", null, createItem("k1"), table, "us-east-1");

        verifyNoInteractions(kinesisService);
    }

    @Test
    void skipsWhenNoDestinations() {
        TableDefinition table = createTable("test-table");

        forwarder.forward("INSERT", null, createItem("k1"), table, "us-east-1");

        verifyNoInteractions(kinesisService);
    }

    @Test
    void continuesOnPutRecordFailure() {
        TableDefinition table = createTable("test-table");
        KinesisStreamingDestination dest1 = new KinesisStreamingDestination(
                "arn:aws:kinesis:us-east-1:000000000000:stream/stream-1");
        KinesisStreamingDestination dest2 = new KinesisStreamingDestination(
                "arn:aws:kinesis:us-east-1:000000000000:stream/stream-2");
        table.getKinesisStreamingDestinations().add(dest1);
        table.getKinesisStreamingDestinations().add(dest2);

        when(kinesisService.putRecord(eq("stream-1"), any(byte[].class), anyString(), anyString()))
                .thenThrow(new RuntimeException("stream-1 failed"));
        when(kinesisService.putRecord(eq("stream-2"), any(byte[].class), anyString(), anyString()))
                .thenReturn("seq-1");

        forwarder.forward("INSERT", null, createItem("k1"), table, "us-east-1");

        verify(kinesisService).putRecord(eq("stream-1"), any(byte[].class), anyString(), anyString());
        verify(kinesisService).putRecord(eq("stream-2"), any(byte[].class), anyString(), anyString());
    }
}
