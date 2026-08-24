package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class BlockDeviceMapping {

    private String deviceName;
    private EbsBlockDevice ebs;
    private String virtualName;
    private String noDevice;

    public BlockDeviceMapping() {}

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public EbsBlockDevice getEbs() { return ebs; }
    public void setEbs(EbsBlockDevice ebs) { this.ebs = ebs; }

    public String getVirtualName() { return virtualName; }
    public void setVirtualName(String virtualName) { this.virtualName = virtualName; }

    public String getNoDevice() { return noDevice; }
    public void setNoDevice(String noDevice) { this.noDevice = noDevice; }
}
