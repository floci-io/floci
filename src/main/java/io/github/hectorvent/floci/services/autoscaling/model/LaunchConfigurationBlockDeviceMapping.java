package io.github.hectorvent.floci.services.autoscaling.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One entry of a launch configuration's {@code BlockDeviceMappings}. The shape follows the
 * Auto Scaling API rather than EC2's, which has no {@code Throughput}, {@code Iops},
 * {@code VirtualName} or {@code NoDevice} on its launch-time mapping.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LaunchConfigurationBlockDeviceMapping {

    private String deviceName;
    private String virtualName;
    private Boolean noDevice;
    private Ebs ebs;

    public LaunchConfigurationBlockDeviceMapping() {}

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String v) { this.deviceName = v; }

    public String getVirtualName() { return virtualName; }
    public void setVirtualName(String v) { this.virtualName = v; }

    public Boolean getNoDevice() { return noDevice; }
    public void setNoDevice(Boolean v) { this.noDevice = v; }

    public Ebs getEbs() { return ebs; }
    public void setEbs(Ebs v) { this.ebs = v; }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Ebs {

        private String snapshotId;
        private Integer volumeSize;
        private String volumeType;
        private Boolean deleteOnTermination;
        private Integer iops;
        private Integer throughput;
        private Boolean encrypted;

        public Ebs() {}

        public String getSnapshotId() { return snapshotId; }
        public void setSnapshotId(String v) { this.snapshotId = v; }

        public Integer getVolumeSize() { return volumeSize; }
        public void setVolumeSize(Integer v) { this.volumeSize = v; }

        public String getVolumeType() { return volumeType; }
        public void setVolumeType(String v) { this.volumeType = v; }

        public Boolean getDeleteOnTermination() { return deleteOnTermination; }
        public void setDeleteOnTermination(Boolean v) { this.deleteOnTermination = v; }

        public Integer getIops() { return iops; }
        public void setIops(Integer v) { this.iops = v; }

        public Integer getThroughput() { return throughput; }
        public void setThroughput(Integer v) { this.throughput = v; }

        public Boolean getEncrypted() { return encrypted; }
        public void setEncrypted(Boolean v) { this.encrypted = v; }
    }
}
