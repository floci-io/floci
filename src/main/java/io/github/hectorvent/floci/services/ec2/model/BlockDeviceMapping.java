package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class BlockDeviceMapping {

    private String deviceName;
    private EbsBlockDevice ebs;

    public BlockDeviceMapping() {}

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public EbsBlockDevice getEbs() { return ebs; }
    public void setEbs(EbsBlockDevice ebs) { this.ebs = ebs; }
}
