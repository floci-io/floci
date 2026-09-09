package io.github.hectorvent.floci.services.codegurureviewer.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** An association between CodeGuru Reviewer and a source repository. */
@RegisterForReflection
public class RepositoryAssociation {

    private String associationId;
    private String associationArn;
    private String connectionArn;
    private String name;
    private String owner;
    private String providerType;
    private String state;
    private String stateReason;
    private Instant createdTimeStamp;
    private Instant lastUpdatedTimeStamp;
    private String kmsKeyId;
    private String encryptionOption;
    private String s3BucketName;
    private String sourceCodeArtifactsObjectKey;
    private String buildArtifactsObjectKey;
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getAssociationId() {
        return associationId;
    }

    public void setAssociationId(String associationId) {
        this.associationId = associationId;
    }

    public String getAssociationArn() {
        return associationArn;
    }

    public void setAssociationArn(String associationArn) {
        this.associationArn = associationArn;
    }

    public String getConnectionArn() {
        return connectionArn;
    }

    public void setConnectionArn(String connectionArn) {
        this.connectionArn = connectionArn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getProviderType() {
        return providerType;
    }

    public void setProviderType(String providerType) {
        this.providerType = providerType;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getStateReason() {
        return stateReason;
    }

    public void setStateReason(String stateReason) {
        this.stateReason = stateReason;
    }

    public Instant getCreatedTimeStamp() {
        return createdTimeStamp;
    }

    public void setCreatedTimeStamp(Instant createdTimeStamp) {
        this.createdTimeStamp = createdTimeStamp;
    }

    public Instant getLastUpdatedTimeStamp() {
        return lastUpdatedTimeStamp;
    }

    public void setLastUpdatedTimeStamp(Instant lastUpdatedTimeStamp) {
        this.lastUpdatedTimeStamp = lastUpdatedTimeStamp;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    public String getEncryptionOption() {
        return encryptionOption;
    }

    public void setEncryptionOption(String encryptionOption) {
        this.encryptionOption = encryptionOption;
    }

    public String getS3BucketName() {
        return s3BucketName;
    }

    public void setS3BucketName(String s3BucketName) {
        this.s3BucketName = s3BucketName;
    }

    public String getSourceCodeArtifactsObjectKey() {
        return sourceCodeArtifactsObjectKey;
    }

    public void setSourceCodeArtifactsObjectKey(String sourceCodeArtifactsObjectKey) {
        this.sourceCodeArtifactsObjectKey = sourceCodeArtifactsObjectKey;
    }

    public String getBuildArtifactsObjectKey() {
        return buildArtifactsObjectKey;
    }

    public void setBuildArtifactsObjectKey(String buildArtifactsObjectKey) {
        this.buildArtifactsObjectKey = buildArtifactsObjectKey;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }
}
