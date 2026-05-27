package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class BlockDevice {

    private String deviceName;
    private Ebs ebs = null;
    private String noDevice = null;
    private String virtualName = null;

    public BlockDevice() {}

	public String getDeviceName() { return deviceName; }
	public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

	public Ebs getEbs() { return ebs; }
	public void setEbs(Ebs ebs) { this.ebs = ebs; }

	public String getNoDevice() { return noDevice; }
	public void setNoDevice(String noDevice) { this.noDevice = noDevice; }

	public String getVirtualName() { return virtualName; }

	public void setVirtualName(String virtualName) { this.virtualName = virtualName; }

}
