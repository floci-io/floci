package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * A stored partition index, as returned by {@code GetPartitionIndexes}.
 *
 * <p>{@code IndexStatus} is always {@code ACTIVE} here. Real Glue creates an index asynchronously
 * and reports {@code CREATING} until the backfill finishes, but an emulator that settles
 * immediately keeps tests free of polling and lets a client that waits for {@code ACTIVE} (the
 * Terraform provider does) complete on the first read.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PartitionIndexDescriptor {
    @JsonProperty("IndexName")
    private String indexName;
    @JsonProperty("IndexStatus")
    private String indexStatus;
    @JsonProperty("Keys")
    private List<KeySchemaElement> keys;
    @JsonProperty("BackfillErrors")
    private List<Object> backfillErrors;

    public PartitionIndexDescriptor() {}

    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }
    public String getIndexStatus() { return indexStatus; }
    public void setIndexStatus(String indexStatus) { this.indexStatus = indexStatus; }
    public List<KeySchemaElement> getKeys() { return keys == null ? null : new ArrayList<>(keys); }
    public void setKeys(List<KeySchemaElement> keys) { this.keys = keys == null ? null : new ArrayList<>(keys); }
    public List<Object> getBackfillErrors() { return backfillErrors == null ? null : new ArrayList<>(backfillErrors); }
    public void setBackfillErrors(List<Object> backfillErrors) {
        this.backfillErrors = backfillErrors == null ? null : new ArrayList<>(backfillErrors);
    }
}
