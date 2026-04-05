package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Vpc {

    private String vpcId;
    private String cidrBlock;
    private String state;
    private String dhcpOptionsId = "dopt-default";
    private boolean isDefault;
    private String instanceTenancy = "default";
    private String ownerId;
    private String region;
    private List<VpcCidrBlockAssociation> cidrBlockAssociationSet = new ArrayList<>();
    private List<Tag> tags = new ArrayList<>();

    public Vpc() {}

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getCidrBlock() { return cidrBlock; }
    public void setCidrBlock(String cidrBlock) { this.cidrBlock = cidrBlock; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getDhcpOptionsId() { return dhcpOptionsId; }
    public void setDhcpOptionsId(String dhcpOptionsId) { this.dhcpOptionsId = dhcpOptionsId; }

    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean aDefault) { isDefault = aDefault; }

    public String getInstanceTenancy() { return instanceTenancy; }
    public void setInstanceTenancy(String instanceTenancy) { this.instanceTenancy = instanceTenancy; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<VpcCidrBlockAssociation> getCidrBlockAssociationSet() { return cidrBlockAssociationSet; }
    public void setCidrBlockAssociationSet(List<VpcCidrBlockAssociation> cidrBlockAssociationSet) { this.cidrBlockAssociationSet = cidrBlockAssociationSet; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
