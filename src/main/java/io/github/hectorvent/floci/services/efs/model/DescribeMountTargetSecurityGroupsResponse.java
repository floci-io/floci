package io.github.hectorvent.floci.services.efs.model;

import java.util.List;

public class DescribeMountTargetSecurityGroupsResponse {

    private List<String> securityGroups;

    public List<String> getSecurityGroups() {
        return securityGroups;
    }

    public void setSecurityGroups(List<String> securityGroups) {
        this.securityGroups = securityGroups;
    }
}