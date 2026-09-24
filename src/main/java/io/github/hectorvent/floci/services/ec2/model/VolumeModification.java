package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class VolumeModification {

    private String volumeId;
    private String modificationState;
    private Integer targetSize;
    private Integer targetIops;
    private String targetVolumeType;
    private Integer targetThroughput;
    private Boolean targetMultiAttachEnabled;
    private Integer originalSize;
    private Integer originalIops;
    private String originalVolumeType;
    private Integer originalThroughput;
    private Boolean originalMultiAttachEnabled;
    private Long progress;
    private String statusMessage;
    private Instant startTime;
    private Instant endTime;
    private String region;

    public VolumeModification() {}

    public String getVolumeId() { return volumeId; }
    public void setVolumeId(String volumeId) { this.volumeId = volumeId; }

    public String getModificationState() { return modificationState; }
    public void setModificationState(String modificationState) { this.modificationState = modificationState; }

    public Integer getTargetSize() { return targetSize; }
    public void setTargetSize(Integer targetSize) { this.targetSize = targetSize; }

    public Integer getTargetIops() { return targetIops; }
    public void setTargetIops(Integer targetIops) { this.targetIops = targetIops; }

    public String getTargetVolumeType() { return targetVolumeType; }
    public void setTargetVolumeType(String targetVolumeType) { this.targetVolumeType = targetVolumeType; }

    public Integer getTargetThroughput() { return targetThroughput; }
    public void setTargetThroughput(Integer targetThroughput) { this.targetThroughput = targetThroughput; }

    public Boolean getTargetMultiAttachEnabled() { return targetMultiAttachEnabled; }
    public void setTargetMultiAttachEnabled(Boolean targetMultiAttachEnabled) { this.targetMultiAttachEnabled = targetMultiAttachEnabled; }

    public Integer getOriginalSize() { return originalSize; }
    public void setOriginalSize(Integer originalSize) { this.originalSize = originalSize; }

    public Integer getOriginalIops() { return originalIops; }
    public void setOriginalIops(Integer originalIops) { this.originalIops = originalIops; }

    public String getOriginalVolumeType() { return originalVolumeType; }
    public void setOriginalVolumeType(String originalVolumeType) { this.originalVolumeType = originalVolumeType; }

    public Integer getOriginalThroughput() { return originalThroughput; }
    public void setOriginalThroughput(Integer originalThroughput) { this.originalThroughput = originalThroughput; }

    public Boolean getOriginalMultiAttachEnabled() { return originalMultiAttachEnabled; }
    public void setOriginalMultiAttachEnabled(Boolean originalMultiAttachEnabled) { this.originalMultiAttachEnabled = originalMultiAttachEnabled; }

    public Long getProgress() { return progress; }
    public void setProgress(Long progress) { this.progress = progress; }

    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }

    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }

    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
}
