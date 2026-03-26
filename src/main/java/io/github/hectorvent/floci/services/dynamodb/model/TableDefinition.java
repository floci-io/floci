package io.github.hectorvent.floci.services.dynamodb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Represents a DynamoDB table definition (metadata, not items).
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class TableDefinition {

    private String tableName;
    private List<KeySchemaElement> keySchema;
    private List<AttributeDefinition> attributeDefinitions;
    private String tableStatus;
    private Instant creationDateTime;
    private long itemCount;
    private long tableSizeBytes;
    private ProvisionedThroughput provisionedThroughput;
    private String tableArn;
    private Map<String, String> tags;
    private List<GlobalSecondaryIndex> globalSecondaryIndexes;
    private List<LocalSecondaryIndex> localSecondaryIndexes;
    private String billingMode; // "PROVISIONED" or "PAY_PER_REQUEST"
    private String ttlAttributeName;
    private boolean ttlEnabled;
    private boolean streamEnabled;
    private String streamArn;
    private String streamViewType;

    public TableDefinition() {
        this.keySchema = new ArrayList<>();
        this.attributeDefinitions = new ArrayList<>();
        this.tags = new HashMap<>();
        this.globalSecondaryIndexes = new ArrayList<>();
        this.localSecondaryIndexes = new ArrayList<>();
    }

    public TableDefinition(String tableName,
                            List<KeySchemaElement> keySchema,
                            List<AttributeDefinition> attributeDefinitions) {
        this(tableName, keySchema, attributeDefinitions, "us-east-1", "000000000000");
    }

    public TableDefinition(String tableName,
                            List<KeySchemaElement> keySchema,
                            List<AttributeDefinition> attributeDefinitions,
                            String region, String accountId) {
        this.tableName = tableName;
        this.keySchema = keySchema;
        this.attributeDefinitions = attributeDefinitions;
        this.tableStatus = "ACTIVE";
        this.creationDateTime = Instant.now();
        this.itemCount = 0;
        this.tableSizeBytes = 0;
        this.tableArn = "arn:aws:dynamodb:" + region + ":" + accountId + ":table/" + tableName;
        this.provisionedThroughput = new ProvisionedThroughput(5, 5);
        this.tags = new HashMap<>();
        this.globalSecondaryIndexes = new ArrayList<>();
        this.localSecondaryIndexes = new ArrayList<>();
    }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public List<KeySchemaElement> getKeySchema() { return keySchema; }
    public void setKeySchema(List<KeySchemaElement> keySchema) { this.keySchema = keySchema; }

    public List<AttributeDefinition> getAttributeDefinitions() { return attributeDefinitions; }
    public void setAttributeDefinitions(List<AttributeDefinition> attributeDefinitions) { this.attributeDefinitions = attributeDefinitions; }

    public String getTableStatus() { return tableStatus; }
    public void setTableStatus(String tableStatus) { this.tableStatus = tableStatus; }

    public Instant getCreationDateTime() { return creationDateTime; }
    public void setCreationDateTime(Instant creationDateTime) { this.creationDateTime = creationDateTime; }

    public long getItemCount() { return itemCount; }
    public void setItemCount(long itemCount) { this.itemCount = itemCount; }

    public long getTableSizeBytes() { return tableSizeBytes; }
    public void setTableSizeBytes(long tableSizeBytes) { this.tableSizeBytes = tableSizeBytes; }

    public ProvisionedThroughput getProvisionedThroughput() { return provisionedThroughput; }
    public void setProvisionedThroughput(ProvisionedThroughput provisionedThroughput) { this.provisionedThroughput = provisionedThroughput; }

    public String getTableArn() { return tableArn; }
    public void setTableArn(String tableArn) { this.tableArn = tableArn; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public List<GlobalSecondaryIndex> getGlobalSecondaryIndexes() { return globalSecondaryIndexes; }
    public void setGlobalSecondaryIndexes(List<GlobalSecondaryIndex> globalSecondaryIndexes) {
        this.globalSecondaryIndexes = globalSecondaryIndexes != null ? globalSecondaryIndexes : new ArrayList<>();
    }

    public List<LocalSecondaryIndex> getLocalSecondaryIndexes() { return localSecondaryIndexes; }
    public void setLocalSecondaryIndexes(List<LocalSecondaryIndex> localSecondaryIndexes) {
        this.localSecondaryIndexes = localSecondaryIndexes != null ? localSecondaryIndexes : new ArrayList<>();
    }

    public String getBillingMode() { return billingMode; }
    public void setBillingMode(String billingMode) { this.billingMode = billingMode; }

    public String getTtlAttributeName() { return ttlAttributeName; }
    public void setTtlAttributeName(String ttlAttributeName) { this.ttlAttributeName = ttlAttributeName; }

    public boolean isTtlEnabled() { return ttlEnabled; }
    public void setTtlEnabled(boolean ttlEnabled) { this.ttlEnabled = ttlEnabled; }

    public boolean isStreamEnabled() { return streamEnabled; }
    public void setStreamEnabled(boolean streamEnabled) { this.streamEnabled = streamEnabled; }

    public String getStreamArn() { return streamArn; }
    public void setStreamArn(String streamArn) { this.streamArn = streamArn; }

    public String getStreamViewType() { return streamViewType; }
    public void setStreamViewType(String streamViewType) { this.streamViewType = streamViewType; }

    /** Returns the partition key attribute name. */
    public String getPartitionKeyName() {
        return keySchema.stream()
                .filter(k -> "HASH".equals(k.getKeyType()))
                .map(KeySchemaElement::getAttributeName)
                .findFirst()
                .orElseThrow();
    }

    /** Returns the sort key attribute name, or null if none. */
    public String getSortKeyName() {
        return keySchema.stream()
                .filter(k -> "RANGE".equals(k.getKeyType()))
                .map(KeySchemaElement::getAttributeName)
                .findFirst()
                .orElse(null);
    }

    public Optional<GlobalSecondaryIndex> findGsi(String indexName) {
        if (globalSecondaryIndexes == null) {
            return Optional.empty();
        }
        return globalSecondaryIndexes.stream()
                .filter(g -> indexName.equals(g.getIndexName()))
                .findFirst();
    }

    public Optional<LocalSecondaryIndex> findLsi(String indexName) {
        if (localSecondaryIndexes == null) {
            return Optional.empty();
        }
        return localSecondaryIndexes.stream()
                .filter(l -> indexName.equals(l.getIndexName()))
                .findFirst();
    }
}
