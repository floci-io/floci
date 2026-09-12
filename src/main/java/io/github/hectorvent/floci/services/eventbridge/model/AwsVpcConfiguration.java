package io.github.hectorvent.floci.services.eventbridge.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class AwsVpcConfiguration {

    private List<String> subnets;
    private List<String> securityGroups;
    private String assignPublicIp;

    public AwsVpcConfiguration() {
    }

    @JsonProperty("Subnets")
    public List<String> getSubnets() {
        return subnets;
    }

    @JsonProperty("Subnets")
    public void setSubnets(List<String> subnets) {
        this.subnets = subnets;
    }

    @JsonProperty("SecurityGroups")
    public List<String> getSecurityGroups() {
        return securityGroups;
    }

    @JsonProperty("SecurityGroups")
    public void setSecurityGroups(List<String> securityGroups) {
        this.securityGroups = securityGroups;
    }

    @JsonProperty("AssignPublicIp")
    public String getAssignPublicIp() {
        return assignPublicIp;
    }

    @JsonProperty("AssignPublicIp")
    public void setAssignPublicIp(String assignPublicIp) {
        this.assignPublicIp = assignPublicIp;
    }
}
