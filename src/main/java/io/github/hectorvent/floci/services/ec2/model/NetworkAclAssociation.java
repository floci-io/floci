package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class NetworkAclAssociation {

    private String networkAclAssociationId;
    private String networkAclId;
    private String subnetId;

    public NetworkAclAssociation() {}

    public String getNetworkAclAssociationId() { return networkAclAssociationId; }
    public void setNetworkAclAssociationId(String networkAclAssociationId) { this.networkAclAssociationId = networkAclAssociationId; }

    public String getNetworkAclId() { return networkAclId; }
    public void setNetworkAclId(String networkAclId) { this.networkAclId = networkAclId; }

    public String getSubnetId() { return subnetId; }
    public void setSubnetId(String subnetId) { this.subnetId = subnetId; }
}
