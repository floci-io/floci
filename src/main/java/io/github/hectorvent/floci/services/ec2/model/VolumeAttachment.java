package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class VolumeAttachment {

    private String instanceId;
    private String device;
    private String state;   // attaching, attached, detaching, detached
    private Instant attachTime;
    private boolean deleteOnTermination;
    private String volumeId;

    public VolumeAttachment() {}

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public String getDevice() { return device; }
    public void setDevice(String device) { this.device = device; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public Instant getAttachTime() { return attachTime; }
    public void setAttachTime(Instant attachTime) { this.attachTime = attachTime; }

    public boolean isDeleteOnTermination() { return deleteOnTermination; }
    public void setDeleteOnTermination(boolean deleteOnTermination) { this.deleteOnTermination = deleteOnTermination; }

    public String getVolumeId() { return volumeId; }
    public void setVolumeId(String volumeId) { this.volumeId = volumeId; }
}
