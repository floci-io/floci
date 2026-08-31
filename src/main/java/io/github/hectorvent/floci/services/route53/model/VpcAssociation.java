package io.github.hectorvent.floci.services.route53.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class VpcAssociation {

    private String vpcId;
    private String vpcRegion;

    public VpcAssociation() {}

    public VpcAssociation(String vpcId, String vpcRegion) {
        this.vpcId = vpcId;
        this.vpcRegion = vpcRegion;
    }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getVpcRegion() { return vpcRegion; }
    public void setVpcRegion(String vpcRegion) { this.vpcRegion = vpcRegion; }
}
