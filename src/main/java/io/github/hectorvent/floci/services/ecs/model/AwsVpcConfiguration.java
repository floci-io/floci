package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * The VPC subnets and security groups associated with a task or service that uses
 * the {@code awsvpc} network mode ({@code CreateService}'s
 * {@code networkConfiguration.awsvpcConfiguration} block).
 */
@RegisterForReflection
public class AwsVpcConfiguration {

    private List<String> subnets = new ArrayList<>();
    private List<String> securityGroups = new ArrayList<>();
    private String assignPublicIp;

    public AwsVpcConfiguration() {}

    public List<String> getSubnets() { return subnets; }
    public void setSubnets(List<String> subnets) {
        this.subnets = subnets != null ? subnets : new ArrayList<>();
    }

    public List<String> getSecurityGroups() { return securityGroups; }
    public void setSecurityGroups(List<String> securityGroups) {
        this.securityGroups = securityGroups != null ? securityGroups : new ArrayList<>();
    }

    public String getAssignPublicIp() { return assignPublicIp; }
    public void setAssignPublicIp(String assignPublicIp) { this.assignPublicIp = assignPublicIp; }
}
