package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class VpcCidrBlockAssociation {

    private String associationId;
    private String cidrBlock;
    private String cidrBlockState = "associated";

    public VpcCidrBlockAssociation() {}

    public VpcCidrBlockAssociation(String associationId, String cidrBlock) {
        this.associationId = associationId;
        this.cidrBlock = cidrBlock;
    }

    public String getAssociationId() { return associationId; }
    public void setAssociationId(String associationId) { this.associationId = associationId; }

    public String getCidrBlock() { return cidrBlock; }
    public void setCidrBlock(String cidrBlock) { this.cidrBlock = cidrBlock; }

    public String getCidrBlockState() { return cidrBlockState; }
    public void setCidrBlockState(String cidrBlockState) { this.cidrBlockState = cidrBlockState; }
}
