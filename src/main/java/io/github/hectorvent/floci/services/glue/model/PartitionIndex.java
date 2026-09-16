package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * The partition index as supplied to {@code CreatePartitionIndex}.
 *
 * <p>Note the asymmetry with {@link PartitionIndexDescriptor}: a request carries key <em>names</em>
 * only, while a read returns each key's name and type, resolved from the table's partition keys.
 */
@RegisterForReflection
public class PartitionIndex {
    @JsonProperty("IndexName")
    private String indexName;
    @JsonProperty("Keys")
    private List<String> keys;

    public PartitionIndex() {}

    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }
    public List<String> getKeys() { return keys == null ? null : new ArrayList<>(keys); }
    public void setKeys(List<String> keys) { this.keys = keys == null ? null : new ArrayList<>(keys); }
}
