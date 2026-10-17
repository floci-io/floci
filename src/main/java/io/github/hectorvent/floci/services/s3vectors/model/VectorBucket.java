package io.github.hectorvent.floci.services.s3vectors.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RegisterForReflection
public class VectorBucket {
    private Instant creationTime = Instant.now();
    private Map<String, String> tags = new ConcurrentHashMap<>();
    private String vectorBucketName;
    private String vectorBucketArn;
    private Object encryptionConfiguration;
    private Map<String, VectorIndex> indexes = new ConcurrentHashMap<>();

    public VectorBucket() {}

    public VectorBucket(String vectorBucketName, String vectorBucketArn, Object encryptionConfiguration) {
        this.vectorBucketName = vectorBucketName;
        this.vectorBucketArn = vectorBucketArn;
        this.encryptionConfiguration = encryptionConfiguration;
    }

    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public String getVectorBucketName() { return vectorBucketName; }
    public void setVectorBucketName(String vectorBucketName) { this.vectorBucketName = vectorBucketName; }

    public String getVectorBucketArn() { return vectorBucketArn; }
    public void setVectorBucketArn(String vectorBucketArn) { this.vectorBucketArn = vectorBucketArn; }

    public Object getEncryptionConfiguration() { return encryptionConfiguration; }
    public void setEncryptionConfiguration(Object encryptionConfiguration) { this.encryptionConfiguration = encryptionConfiguration; }

    public Map<String, VectorIndex> getIndexes() { return indexes; }
    public void setIndexes(Map<String, VectorIndex> indexes) { this.indexes = indexes; }
}
