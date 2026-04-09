package io.github.hectorvent.floci.services.dynamodb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.ArrayList;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class GlobalSecondaryIndex {

    private String indexName;
    private List<KeySchemaElement> keySchema;
    private String indexArn;
    private String projectionType;
    private ProvisionedThroughput provisionedThroughput;
    private long itemCount;
    private long indexSizeBytes;
    private List<String> nonKeyAttributes;

    public GlobalSecondaryIndex() {
        this.provisionedThroughput = new ProvisionedThroughput(0, 0);
        this.nonKeyAttributes = new ArrayList<String>();
    }

    public GlobalSecondaryIndex(String indexName, List<KeySchemaElement> keySchema,
                                 String indexArn, String projectionType, List<String> nonKeyAttributes) {
        this.indexName = indexName;
        this.keySchema = keySchema;
        this.indexArn = indexArn;
        this.projectionType = projectionType != null ? projectionType : "ALL";
        if ("INCLUDE".equals(this.projectionType) && nonKeyAttributes != null){
            this.nonKeyAttributes = nonKeyAttributes;
        }
        else {
            this.nonKeyAttributes = new ArrayList<String>();
        }
        this.provisionedThroughput = new ProvisionedThroughput(0, 0);
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

    public ProvisionedThroughput getProvisionedThroughput() { return provisionedThroughput; }
    public void setProvisionedThroughput(ProvisionedThroughput provisionedThroughput) { this.provisionedThroughput = provisionedThroughput; }

    public long getItemCount() { return itemCount; }
    public void setItemCount(long itemCount) { this.itemCount = itemCount; }

    public long getIndexSizeBytes() { return indexSizeBytes; }
    public void setIndexSizeBytes(long indexSizeBytes) { this.indexSizeBytes = indexSizeBytes; }

    public String getPartitionKeyName() {
        return keySchema.stream()
                .filter(k -> "HASH".equals(k.getKeyType()))
                .map(KeySchemaElement::getAttributeName)
                .findFirst()
                .orElseThrow();
    }

    public String getSortKeyName() {
        return keySchema.stream()
                .filter(k -> "RANGE".equals(k.getKeyType()))
                .map(KeySchemaElement::getAttributeName)
                .findFirst()
                .orElse(null);
    }
}
