package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class NetworkInterfacePrivateIpAddress {

    private String privateIpAddress;
    private String privateDnsName;
    private boolean primary;
    private NetworkInterfaceAssociation association;

    public NetworkInterfacePrivateIpAddress() {}

    public String getPrivateIpAddress() { return privateIpAddress; }
    public void setPrivateIpAddress(String privateIpAddress) { this.privateIpAddress = privateIpAddress; }

    public String getPrivateDnsName() { return privateDnsName; }
    public void setPrivateDnsName(String privateDnsName) { this.privateDnsName = privateDnsName; }

    public boolean isPrimary() { return primary; }
    public void setPrimary(boolean primary) { this.primary = primary; }

    public NetworkInterfaceAssociation getAssociation() { return association; }
    public void setAssociation(NetworkInterfaceAssociation association) { this.association = association; }
}
