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
    private Integer iops;
    private Integer throughput;
    private String kmsKeyId;

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

    public Integer getIops() { return iops; }
    public void setIops(Integer iops) { this.iops = iops; }

    public Integer getThroughput() { return throughput; }
    public void setThroughput(Integer throughput) { this.throughput = throughput; }

    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String kmsKeyId) { this.kmsKeyId = kmsKeyId; }
}
