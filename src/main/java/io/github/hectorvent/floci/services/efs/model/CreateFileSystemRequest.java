package io.github.hectorvent.floci.services.efs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@RegisterForReflection
public class CreateFileSystemRequest {

    private String availabilityZoneName;
    private Boolean backup;
    private String creationToken;
    private Boolean encrypted;
    private String kmsKeyId;
    private PerformanceMode performanceMode;
    private Double provisionedThroughputInMibps;
    private List<Tag> tags;
    private ThroughputMode throughputMode;

    public String getAvailabilityZoneName() {
        return availabilityZoneName;
    }

    public void setAvailabilityZoneName(String availabilityZoneName) {
        this.availabilityZoneName = availabilityZoneName;
    }

    public Boolean getBackup() {
        return backup;
    }

    public void setBackup(Boolean backup) {
        this.backup = backup;
    }

    public String getCreationToken() {
        return creationToken;
    }

    public void setCreationToken(String creationToken) {
        this.creationToken = creationToken;
    }

    public Boolean getEncrypted() {
        return encrypted;
    }

    public void setEncrypted(Boolean encrypted) {
        this.encrypted = encrypted;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    public PerformanceMode getPerformanceMode() {
        return performanceMode;
    }

    public void setPerformanceMode(PerformanceMode performanceMode) {
        this.performanceMode = performanceMode;
    }

    public Double getProvisionedThroughputInMibps() {
        return provisionedThroughputInMibps;
    }

    public void setProvisionedThroughputInMibps(Double provisionedThroughputInMibps) {
        this.provisionedThroughputInMibps = provisionedThroughputInMibps;
    }

    public List<Tag> getTags() {
        return tags;
    }

    public void setTags(List<Tag> tags) {
        this.tags = tags;
    }

    public ThroughputMode getThroughputMode() {
        return throughputMode;
    }

    public void setThroughputMode(ThroughputMode throughputMode) {
        this.throughputMode = throughputMode;
    }
}