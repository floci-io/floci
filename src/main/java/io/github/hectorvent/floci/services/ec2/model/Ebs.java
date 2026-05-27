package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Ebs {

    private String availabilityZone = null;
    private String availabilityZoneId = null;
    private boolean deleteOnTermination = true;
    private int ebsCardIndex = 0;
    private boolean encrypted = false;
    private int iops = 0;
    private String kmsKeyId = null;
    private String outpostArn = null;
    private String snapshotId = null;
    private int throughput = 0;
    private int volumeInitializationRate = 0;
    private int volumeSize = 0;
    private String volumeType = null;

    public Ebs() {}

	public String getAvailabilityZone() { return availabilityZone; }
	public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }

	public String getAvailabilityZoneId() { return availabilityZoneId; }
	public void setAvailabilityZoneId(String availabilityZoneId) { this.availabilityZoneId = availabilityZoneId; }

	public boolean isDeleteOnTermination() { return deleteOnTermination; }
	public void setDeleteOnTermination(boolean deleteOnTermination) { this.deleteOnTermination = deleteOnTermination; }

	public int getEbsCardIndex() { return ebsCardIndex; }
	public void setEbsCardIndex(int ebsCardIndex) { this.ebsCardIndex = ebsCardIndex; }

	public boolean isEncrypted() { return encrypted; }
	public void setEncrypted(boolean encrypted) { this.encrypted = encrypted; }

	public int getIops() { return iops; }
	public void setIops(int iops) { this.iops = iops; }

	public String getKmsKeyId() { return kmsKeyId; }
	public void setKmsKeyId(String kmsKeyId) { this.kmsKeyId = kmsKeyId; }

	public String getOutpostArn() { return outpostArn; }
	public void setOutpostArn(String outpostArn) { this.outpostArn = outpostArn; }

	public String getSnapshotId() { return snapshotId; }
	public void setSnapshotId(String snapshotId) { this.snapshotId = snapshotId; }

	public int getThroughput() { return throughput; }
	public void setThroughput(int throughput) { this.throughput = throughput; }

	public int getVolumeInitializationRate() { return volumeInitializationRate; }
	public void setVolumeInitializationRate(int volumeInitializationRate) { this.volumeInitializationRate = volumeInitializationRate; }

	public int getVolumeSize() { return volumeSize; }
	public void setVolumeSize(int volumeSize) { this.volumeSize = volumeSize; }

	public String getVolumeType() { return volumeType; }
	public void setVolumeType(String volumeType) { this.volumeType = volumeType; }

}
