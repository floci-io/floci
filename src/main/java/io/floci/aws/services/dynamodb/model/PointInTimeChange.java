package io.floci.aws.services.dynamodb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * One item change to a table with point-in-time recovery enabled. {@code oldItem} is what the change
 * replaced, null when it created the item.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class PointInTimeChange {

    private long sequence;
    private Instant time;
    private String itemKey;
    private JsonNode oldItem;

    public PointInTimeChange() {
    }

    public PointInTimeChange(long sequence, Instant time, String itemKey, JsonNode oldItem) {
        this.sequence = sequence;
        this.time = time;
        this.itemKey = itemKey;
        this.oldItem = oldItem;
    }

    public long getSequence() { return sequence; }
    public void setSequence(long sequence) { this.sequence = sequence; }

    public Instant getTime() { return time; }
    public void setTime(Instant time) { this.time = time; }

    public String getItemKey() { return itemKey; }
    public void setItemKey(String itemKey) { this.itemKey = itemKey; }

    public JsonNode getOldItem() { return oldItem; }
    // Jackson reads a stored null into a JsonNode as NullNode.
    public void setOldItem(JsonNode oldItem) { this.oldItem = oldItem != null && oldItem.isNull() ? null : oldItem; }
}
