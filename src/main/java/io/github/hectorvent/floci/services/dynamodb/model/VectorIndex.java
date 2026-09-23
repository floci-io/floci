package io.github.hectorvent.floci.services.dynamodb.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class VectorIndex {

    private String indexName;
    private String vectorAttributeName;
    private List<SearchSchemaElement> searchSchema;
    private String projectionType;
    private List<String> nonKeyAttributes;
    private Long dimensions;
    private String distanceFunction;
    private String indexArn;
    private String indexStatus;
    // Set only when the index was added by UpdateTable, which reports the allocation and
    // backfilling phases; null on the CreateTable path, which reports an ACTIVE index at once.
    private Instant creationStartedAt;

    public VectorIndex() {
        this.searchSchema = new ArrayList<>();
        this.nonKeyAttributes = new ArrayList<>();
        this.indexStatus = "ACTIVE";
    }

    public VectorIndex(String indexName, String vectorAttributeName,
                       List<SearchSchemaElement> searchSchema, String projectionType,
                       List<String> nonKeyAttributes, Long dimensions, String distanceFunction) {
        this.indexName = indexName;
        this.vectorAttributeName = vectorAttributeName;
        this.searchSchema = searchSchema != null ? searchSchema : new ArrayList<>();
        // Left null when the request omitted Projection, which is a required member the
        // CreateTable and UpdateTable validation rejects.
        this.projectionType = projectionType;
        if ("INCLUDE".equals(this.projectionType) && nonKeyAttributes != null) {
            this.nonKeyAttributes = nonKeyAttributes;
        } else {
            this.nonKeyAttributes = new ArrayList<>();
        }
        this.dimensions = dimensions;
        this.distanceFunction = distanceFunction;
        this.indexStatus = "ACTIVE";
    }

    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }

    public String getVectorAttributeName() { return vectorAttributeName; }
    public void setVectorAttributeName(String vectorAttributeName) { this.vectorAttributeName = vectorAttributeName; }

    public List<SearchSchemaElement> getSearchSchema() { return searchSchema; }
    public void setSearchSchema(List<SearchSchemaElement> searchSchema) {
        this.searchSchema = searchSchema != null ? searchSchema : new ArrayList<>();
    }

    public String getProjectionType() { return projectionType; }
    public void setProjectionType(String projectionType) { this.projectionType = projectionType; }

    public List<String> getNonKeyAttributes() { return nonKeyAttributes; }
    public void setNonKeyAttributes(List<String> nonKeyAttributes) {
        this.nonKeyAttributes = nonKeyAttributes != null ? nonKeyAttributes : new ArrayList<>();
    }

    public Long getDimensions() { return dimensions; }
    public void setDimensions(Long dimensions) { this.dimensions = dimensions; }

    public String getDistanceFunction() { return distanceFunction; }
    public void setDistanceFunction(String distanceFunction) { this.distanceFunction = distanceFunction; }

    public String getIndexArn() { return indexArn; }
    public void setIndexArn(String indexArn) { this.indexArn = indexArn; }

    public String getIndexStatus() { return indexStatus; }
    public void setIndexStatus(String indexStatus) { this.indexStatus = indexStatus; }

    public Instant getCreationStartedAt() { return creationStartedAt; }
    public void setCreationStartedAt(Instant creationStartedAt) { this.creationStartedAt = creationStartedAt; }

    /**
     * The attribute names the index filters on, in search-schema order.
     *
     * <p>{@code @JsonIgnore}d for the reason {@code TableDefinition.getSortKeyNames()} documents:
     * it is derived from {@code searchSchema}, and without a backing setter Jackson's
     * getter-as-setter fallback appends into the immutable list this method returns.
     */
    @JsonIgnore
    public List<String> getSearchSchemaAttributeNames() {
        return searchSchema.stream()
                .map(SearchSchemaElement::getAttributeName)
                .toList();
    }
}
