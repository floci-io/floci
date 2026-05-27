package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class NetworkInterfaceAttachment {

    private String attachmentId;
    private int deviceIndex;
    private String status = "attached";
    private String instanceId;
    private String instanceOwnerId;
    private String attachTime;
    private boolean deleteOnTermination = true;

    public NetworkInterfaceAttachment() {}

    public String getAttachmentId() { return attachmentId; }
    public void setAttachmentId(String attachmentId) { this.attachmentId = attachmentId; }

    public int getDeviceIndex() { return deviceIndex; }
    public void setDeviceIndex(int deviceIndex) { this.deviceIndex = deviceIndex; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public String getInstanceOwnerId() { return instanceOwnerId; }
    public void setInstanceOwnerId(String instanceOwnerId) { this.instanceOwnerId = instanceOwnerId; }

    public String getAttachTime() { return attachTime; }
    public void setAttachTime(String attachTime) { this.attachTime = attachTime; }

    public boolean isDeleteOnTermination() { return deleteOnTermination; }
    public void setDeleteOnTermination(boolean deleteOnTermination) { this.deleteOnTermination = deleteOnTermination; }
}
