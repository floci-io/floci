package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * An event on an EventBridge V2 bus, in the three parts filters, transformers and target parameters address:
 * {@code Data}, the payload (for {@code PutEvents}, the EventBridge envelope); {@code Metadata}, the producer's
 * keys; and {@code SystemMetadata}, the string fields EventBridge assigns. A webhook event also keeps the request
 * body as it was sent, which a {@code RAW} delivery passes on.
 */
record PublishedEvent(JsonNode data, ObjectNode metadata, ObjectNode systemMetadata, String rawData) {

    PublishedEvent(JsonNode data, ObjectNode metadata, ObjectNode systemMetadata) {
        this(data, metadata, systemMetadata, null);
    }

    /** The event as {@code $events} and a {@code WITH_METADATA} delivery hold it. */
    ObjectNode withMetadata() {
        ObjectNode event = JsonNodeFactory.instance.objectNode();
        event.set("Data", data);
        event.set("Metadata", metadata);
        event.set("SystemMetadata", systemMetadata);
        return event;
    }
}
