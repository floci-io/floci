package io.github.hectorvent.floci.services.s3vectors.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RegisterForReflection
public class VectorIndex {
    private Instant creationTime = Instant.now();
    private Map<String, String> tags = new ConcurrentHashMap<>();
    private String indexName;
    private String indexArn;
    private String vectorBucketName;
    private Object encryptionConfiguration;
    private List<String> nonFilterableMetadataKeys = List.of();
    private int dimension;
    private String dataType;
    private String distanceMetric;
    private Map<String, VectorData> vectors = new ConcurrentHashMap<>();

    public VectorIndex() {}

    public VectorIndex(String indexName, String indexArn, String vectorBucketName, int dimension, String dataType, String distanceMetric) {
        this.indexName = indexName;
        this.indexArn = indexArn;
        this.vectorBucketName = vectorBucketName;
        this.dimension = dimension;
        this.dataType = dataType;
        this.distanceMetric = distanceMetric;
    }

    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }

    public String getIndexArn() { return indexArn; }
    public void setIndexArn(String indexArn) { this.indexArn = indexArn; }

    public String getVectorBucketName() { return vectorBucketName; }
    public void setVectorBucketName(String vectorBucketName) { this.vectorBucketName = vectorBucketName; }

    public Object getEncryptionConfiguration() { return encryptionConfiguration; }
    public void setEncryptionConfiguration(Object encryptionConfiguration) { this.encryptionConfiguration = encryptionConfiguration; }

    public List<String> getNonFilterableMetadataKeys() { return nonFilterableMetadataKeys; }
    public void setNonFilterableMetadataKeys(List<String> keys) { this.nonFilterableMetadataKeys = keys; }

    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }

    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }

    public String getDistanceMetric() { return distanceMetric; }
    public void setDistanceMetric(String distanceMetric) { this.distanceMetric = distanceMetric; }

    public Map<String, VectorData> getVectors() { return vectors; }
    public void setVectors(Map<String, VectorData> vectors) { this.vectors = vectors; }
}
