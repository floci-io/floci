package io.github.hectorvent.floci.services.efs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@RegisterForReflection
public class FileSystem {

    private String availabilityZoneId;
    private String availabilityZoneName;
    private Long creationTime;
    private String creationToken;
    private Boolean encrypted;
    private String fileSystemArn;
    private String fileSystemId;
    private FileSystemProtectionDescription fileSystemProtection;
    private String kmsKeyId;
    private String lifeCycleState;
    private String name;
    private Integer numberOfMountTargets;
    private String ownerId;
    private String performanceMode;
    private Double provisionedThroughputInMibps;
    private FileSystemSize sizeInBytes;
    private List<Tag> tags;
    private String throughputMode;



    public String getAvailabilityZoneId() {
        return availabilityZoneId;
    }

    public void setAvailabilityZoneId(String availabilityZoneId) {
        this.availabilityZoneId = availabilityZoneId;
    }

    public String getAvailabilityZoneName() {
        return availabilityZoneName;
    }

    public void setAvailabilityZoneName(String availabilityZoneName) {
        this.availabilityZoneName = availabilityZoneName;
    }

    public Long getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(Long creationTime) {
        this.creationTime = creationTime;
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

    public String getFileSystemArn() {
        return fileSystemArn;
    }

    public void setFileSystemArn(String fileSystemArn) {
        this.fileSystemArn = fileSystemArn;
    }

    public String getFileSystemId() {
        return fileSystemId;
    }

    public void setFileSystemId(String fileSystemId) {
        this.fileSystemId = fileSystemId;
    }

    public FileSystemProtectionDescription getFileSystemProtectionDescription() {
        return fileSystemProtection;
    }

    public void setFileSystemProtectionDescription(FileSystemProtectionDescription fileSystemProtection) {
        this.fileSystemProtection = fileSystemProtection;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    public String getLifeCycleState() {
        return lifeCycleState;
    }

    public void setLifeCycleState(String lifeCycleState) {
        this.lifeCycleState = lifeCycleState;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getNumberOfMountTargets() {
        return numberOfMountTargets;
    }

    public void setNumberOfMountTargets(Integer numberOfMountTargets) {
        this.numberOfMountTargets = numberOfMountTargets;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getPerformanceMode() {
        return performanceMode;
    }

    public void setPerformanceMode(String performanceMode) {
        this.performanceMode = performanceMode;
    }

    public Double getProvisionedThroughputInMibps() {
        return provisionedThroughputInMibps;
    }

    public void setProvisionedThroughputInMibps(Double provisionedThroughputInMibps) {
        this.provisionedThroughputInMibps = provisionedThroughputInMibps;
    }

    public FileSystemSize getSizeInBytes() {
        return sizeInBytes;
    }

    public void setSizeInBytes(FileSystemSize sizeInBytes) {
        this.sizeInBytes = sizeInBytes;
    }

    public List<Tag> getTags() {
        return tags;
    }

    public void setTags(List<Tag> tags) {
        this.tags = tags;
    }

    public String getThroughputMode() {
        return throughputMode;
    }

    public void setThroughputMode(String throughputMode) {
        this.throughputMode = throughputMode;
    }
}