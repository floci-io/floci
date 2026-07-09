package io.github.hectorvent.floci.services.scheduler.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
public class AwsVpcConfiguration {

    private List<String> subnets = new ArrayList<>();
    private List<String> securityGroups = new ArrayList<>();
    private String assignPublicIp;

    public List<String> getSubnets() { return subnets; }
    public void setSubnets(List<String> subnets) { this.subnets = subnets; }

    public List<String> getSecurityGroups() { return securityGroups; }
    public void setSecurityGroups(List<String> securityGroups) { this.securityGroups = securityGroups; }

    public String getAssignPublicIp() { return assignPublicIp; }
    public void setAssignPublicIp(String assignPublicIp) { this.assignPublicIp = assignPublicIp; }
}
