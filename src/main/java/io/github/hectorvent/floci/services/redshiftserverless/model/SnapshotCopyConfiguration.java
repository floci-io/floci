package io.github.hectorvent.floci.services.redshiftserverless.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * A Redshift Serverless snapshot copy configuration. The copy sweep copies the namespace's snapshots
 * into the destination Region.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SnapshotCopyConfiguration {

    private String snapshotCopyConfigurationId;
    private String snapshotCopyConfigurationArn;
    private String namespaceName;
    private String destinationRegion;
    private String destinationKmsKeyId;
    private Integer snapshotRetentionPeriod;
    private String ownerId;
    private Instant createdAt;

    public SnapshotCopyConfiguration() {
    }

    public SnapshotCopyConfiguration(SnapshotCopyConfiguration other) {
        this.ownerId = other.ownerId;
        this.createdAt = other.createdAt;
        this.snapshotCopyConfigurationId = other.snapshotCopyConfigurationId;
        this.snapshotCopyConfigurationArn = other.snapshotCopyConfigurationArn;
        this.namespaceName = other.namespaceName;
        this.destinationRegion = other.destinationRegion;
        this.destinationKmsKeyId = other.destinationKmsKeyId;
        this.snapshotRetentionPeriod = other.snapshotRetentionPeriod;
    }

    public String getSnapshotCopyConfigurationId() {
        return snapshotCopyConfigurationId;
    }

    public void setSnapshotCopyConfigurationId(String snapshotCopyConfigurationId) {
        this.snapshotCopyConfigurationId = snapshotCopyConfigurationId;
    }

    public String getSnapshotCopyConfigurationArn() {
        return snapshotCopyConfigurationArn;
    }

    public void setSnapshotCopyConfigurationArn(String snapshotCopyConfigurationArn) {
        this.snapshotCopyConfigurationArn = snapshotCopyConfigurationArn;
    }

    public String getNamespaceName() {
        return namespaceName;
    }

    public void setNamespaceName(String namespaceName) {
        this.namespaceName = namespaceName;
    }

    public String getDestinationRegion() {
        return destinationRegion;
    }

    public void setDestinationRegion(String destinationRegion) {
        this.destinationRegion = destinationRegion;
    }

    public String getDestinationKmsKeyId() {
        return destinationKmsKeyId;
    }

    public void setDestinationKmsKeyId(String destinationKmsKeyId) {
        this.destinationKmsKeyId = destinationKmsKeyId;
    }

    public Integer getSnapshotRetentionPeriod() {
        return snapshotRetentionPeriod;
    }

    public void setSnapshotRetentionPeriod(Integer snapshotRetentionPeriod) {
        this.snapshotRetentionPeriod = snapshotRetentionPeriod;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    /** When the configuration was created; only snapshots taken from then on are copied. Never in a response. */
    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
