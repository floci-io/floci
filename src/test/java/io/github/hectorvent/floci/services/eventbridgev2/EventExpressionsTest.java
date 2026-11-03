package io.github.hectorvent.floci.services.eventbridgev2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventExpressionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void aPathIntoTheEventResolvesToItsValue() throws Exception {
        PublishedEvent event = new PublishedEvent(
                MAPPER.readTree("{\"detail-type\":\"Order Placed\",\"detail\":{\"customerId\":\"c-1\",\"total\":7}}"),
                MAPPER.createObjectNode(),
                (ObjectNode) MAPPER.readTree("{\"EventGroupId\":\"g1\",\"aws:EventId\":\"e-1\"}"));

        assertEquals(Optional.of("literal"), EventExpressions.resolve("literal", event));
        assertEquals(Optional.of("g1"), EventExpressions.resolve("{% $events.SystemMetadata.EventGroupId %}", event));
        assertEquals(Optional.of("e-1"), EventExpressions.resolve("{% $events.SystemMetadata.\"aws:EventId\" %}", event));
        assertEquals(Optional.of("Order Placed"), EventExpressions.resolve("{% $events.Data.`detail-type` %}", event));
        assertEquals(Optional.of("7"), EventExpressions.resolve("{%$events.Data.detail.total%}", event));
        assertEquals(Optional.empty(), EventExpressions.resolve("{% $events.Data.detail.missing %}", event));
    }

    @Test
    void onlyALiteralOrAPathIsSupported() {
        assertTrue(EventExpressions.isSupported("group-1"));
        assertTrue(EventExpressions.isSupported("{% $events.Data.id %}"));
        assertFalse(EventExpressions.isSupported("{% $uuid() %}"));
        assertFalse(EventExpressions.isSupported("{% $events.Data.a & $events.Data.b %}"));
    }
}
