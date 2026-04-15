package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Volume {

    private String volumeId;
    private String volumeType;        // gp2, gp3, io1, io2, st1, sc1, standard
    private int size;                  // GiB
    private String state;             // creating, available, in-use, deleting, deleted, error
    private String availabilityZone;
    private boolean encrypted;
    private int iops;
    private String snapshotId;
    private Instant createTime;
    private String region;
    private List<Tag> tags = new ArrayList<>();
    private List<VolumeAttachment> attachments = new ArrayList<>();

    public Volume() {}

    public String getVolumeId() { return volumeId; }
    public void setVolumeId(String volumeId) { this.volumeId = volumeId; }

    public String getVolumeType() { return volumeType; }
    public void setVolumeType(String volumeType) { this.volumeType = volumeType; }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }

    public boolean isEncrypted() { return encrypted; }
    public void setEncrypted(boolean encrypted) { this.encrypted = encrypted; }

    public int getIops() { return iops; }
    public void setIops(int iops) { this.iops = iops; }

    public String getSnapshotId() { return snapshotId; }
    public void setSnapshotId(String snapshotId) { this.snapshotId = snapshotId; }

    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }

    public List<VolumeAttachment> getAttachments() { return attachments; }
    public void setAttachments(List<VolumeAttachment> attachments) { this.attachments = attachments; }
}
