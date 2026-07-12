package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class EbsBlockDevice {

    private String snapshotId;
    private Integer volumeSize;
    private String volumeType;
    private Boolean deleteOnTermination;
    private Boolean encrypted;

    public EbsBlockDevice() {}

    public String getSnapshotId() { return snapshotId; }
    public void setSnapshotId(String snapshotId) { this.snapshotId = snapshotId; }

    public Integer getVolumeSize() { return volumeSize; }
    public void setVolumeSize(Integer volumeSize) { this.volumeSize = volumeSize; }

    public String getVolumeType() { return volumeType; }
    public void setVolumeType(String volumeType) { this.volumeType = volumeType; }

    public Boolean getDeleteOnTermination() { return deleteOnTermination; }
    public void setDeleteOnTermination(Boolean deleteOnTermination) { this.deleteOnTermination = deleteOnTermination; }

    public Boolean getEncrypted() { return encrypted; }
    public void setEncrypted(Boolean encrypted) { this.encrypted = encrypted; }
}
