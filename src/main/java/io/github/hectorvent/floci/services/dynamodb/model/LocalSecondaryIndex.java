package io.github.hectorvent.floci.services.dynamodb.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Represents a Local Secondary Index (LSI).
 * LSIs share the same partition key (HASH) as the base table,
 * but have a different sort key (RANGE).
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LocalSecondaryIndex {

    private String indexName;
    private List<KeySchemaElement> keySchema;
    private String indexArn;
    private String projectionType;
    private List<String> nonKeyAttributes;
    private long indexSizeBytes;
    private long itemCount;

    public LocalSecondaryIndex() {
        this.nonKeyAttributes = new java.util.ArrayList<>();
    }

    public LocalSecondaryIndex(String indexName, List<KeySchemaElement> keySchema,
                                String indexArn, String projectionType) {
        this(indexName, keySchema, indexArn, projectionType, null);
    }

    public LocalSecondaryIndex(String indexName, List<KeySchemaElement> keySchema,
                                String indexArn, String projectionType, List<String> nonKeyAttributes) {
        this.indexName = indexName;
        this.keySchema = keySchema;
        this.indexArn = indexArn;
        this.projectionType = projectionType != null ? projectionType : "ALL";
        this.nonKeyAttributes = "INCLUDE".equals(this.projectionType) && nonKeyAttributes != null
                ? nonKeyAttributes : new java.util.ArrayList<>();
    }

    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }

    public List<KeySchemaElement> getKeySchema() { return keySchema; }
    public void setKeySchema(List<KeySchemaElement> keySchema) { this.keySchema = keySchema; }

    public String getIndexArn() { return indexArn; }
    public void setIndexArn(String indexArn) { this.indexArn = indexArn; }

    public String getProjectionType() { return projectionType; }
    public void setProjectionType(String projectionType) { this.projectionType = projectionType; }

    public List<String> getNonKeyAttributes() { return nonKeyAttributes; }
    public void setNonKeyAttributes(List<String> nonKeyAttributes) { this.nonKeyAttributes = nonKeyAttributes; }

    public long getIndexSizeBytes() { return indexSizeBytes; }
    public void setIndexSizeBytes(long indexSizeBytes) { this.indexSizeBytes = indexSizeBytes; }

    public long getItemCount() { return itemCount; }
    public void setItemCount(long itemCount) { this.itemCount = itemCount; }

    @JsonIgnore
    public String getPartitionKeyName() {
        return keySchema.stream()
                .filter(k -> "HASH".equals(k.getKeyType()))
                .map(KeySchemaElement::getAttributeName)
                .findFirst()
                .orElseThrow();
    }

    @JsonIgnore
    public String getSortKeyName() {
        return keySchema.stream()
                .filter(k -> "RANGE".equals(k.getKeyType()))
                .map(KeySchemaElement::getAttributeName)
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns all sort key attribute names in key-schema order. For a composite sort key this
     * contains more than one element; ordering must consider all of them, not just the first.
     *
     * <p>{@code @JsonIgnore}d: it is derived from {@code keySchema} (redundant with
     * {@link #getSortKeyName()}), and without a backing setter Jackson's getter-as-setter
     * fallback tries to append into the immutable list this method returns, throwing
     * {@code UnsupportedOperationException} on deserialization whenever the index has a sort key.
     */
    @JsonIgnore
    public List<String> getSortKeyNames() {
        return keySchema.stream()
                .filter(k -> "RANGE".equals(k.getKeyType()))
                .map(KeySchemaElement::getAttributeName)
                .toList();
    }
}
