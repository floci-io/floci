package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class InternetGatewayAttachment {

    private String vpcId;
    private String state;

    public InternetGatewayAttachment() {}

    public InternetGatewayAttachment(String vpcId, String state) {
        this.vpcId = vpcId;
        this.state = state;
    }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
}
