package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.KinesisStreamingDestination;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class KinesisStreamingForwarder {

    private static final Logger LOG = Logger.getLogger(KinesisStreamingForwarder.class);

    private final KinesisService kinesisService;
    private final ObjectMapper objectMapper;

    @Inject
    public KinesisStreamingForwarder(KinesisService kinesisService, ObjectMapper objectMapper) {
        this.kinesisService = kinesisService;
        this.objectMapper = objectMapper;
    }

    public void forward(String eventName, JsonNode oldItem, JsonNode newItem,
                        TableDefinition table, String region) {
        List<KinesisStreamingDestination> destinations = table.getKinesisStreamingDestinations();
        if (destinations == null || destinations.isEmpty()) return;

        Instant now = Instant.now();
        JsonNode sourceItem = newItem != null ? newItem : oldItem;
        ObjectNode keys = buildKeys(sourceItem, table);

        for (KinesisStreamingDestination dest : destinations) {
            if (!"ACTIVE".equals(dest.getDestinationStatus())) continue;

            try {
                ObjectNode payload = buildPayload(eventName, keys, newItem, oldItem,
                        table.getTableName(), region, now);
                byte[] data = objectMapper.writeValueAsBytes(payload);

                String partitionKey = extractPartitionKey(keys, table);
                String streamName = extractStreamName(dest.getStreamArn());

                kinesisService.putRecord(streamName, data, partitionKey, region);
                LOG.debugv("Forwarded DynamoDB event to Kinesis stream {0}: {1} on {2}",
                        streamName, eventName, table.getTableName());
            } catch (Exception e) {
                LOG.warnv("Failed to forward DynamoDB event to Kinesis destination {0}: {1}",
                        dest.getStreamArn(), e.getMessage());
            }
        }
    }

    private ObjectNode buildPayload(String eventName, JsonNode keys,
                                     JsonNode newImage, JsonNode oldImage,
                                     String tableName, String region, Instant timestamp) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("awsRegion", region);
        payload.put("eventID", UUID.randomUUID().toString());
        payload.put("eventName", eventName);
        payload.putNull("userIdentity");
        payload.put("recordFormat", "application/json");
        payload.put("tableName", tableName);
        payload.put("eventSource", "aws:dynamodb");

        ObjectNode dynamodb = objectMapper.createObjectNode();
        dynamodb.put("ApproximateCreationDateTime", timestamp.toEpochMilli());
        if (keys != null) {
            dynamodb.set("Keys", keys);
        }
        if (newImage != null) {
            dynamodb.set("NewImage", newImage);
        }
        if (oldImage != null) {
            dynamodb.set("OldImage", oldImage);
        }
        dynamodb.put("SizeBytes", 0);
        dynamodb.put("ApproximateCreationDateTimePrecision", "MILLISECOND");
        payload.set("dynamodb", dynamodb);

        return payload;
    }

    private ObjectNode buildKeys(JsonNode item, TableDefinition table) {
        ObjectNode keys = objectMapper.createObjectNode();
        if (item == null) return keys;
        for (KeySchemaElement ks : table.getKeySchema()) {
            String attrName = ks.getAttributeName();
            if (item.has(attrName)) {
                keys.set(attrName, item.get(attrName));
            }
        }
        return keys;
    }

    private String extractPartitionKey(JsonNode keys, TableDefinition table) {
        if (keys == null || keys.isEmpty()) return "default";
        String pkName = table.getPartitionKeyName();
        JsonNode pkValue = keys.get(pkName);
        if (pkValue == null) return "default";
        if (pkValue.has("S")) return pkValue.get("S").asText();
        if (pkValue.has("N")) return pkValue.get("N").asText();
        if (pkValue.has("B")) return pkValue.get("B").asText();
        return pkValue.toString();
    }

    private String extractStreamName(String streamArn) {
        int idx = streamArn.lastIndexOf('/');
        if (idx >= 0) return streamArn.substring(idx + 1);
        return streamArn;
    }
}
