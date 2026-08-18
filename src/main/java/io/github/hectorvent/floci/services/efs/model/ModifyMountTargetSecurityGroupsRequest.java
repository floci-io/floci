package io.github.hectorvent.floci.services.efs.model;

import java.util.List;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class ModifyMountTargetSecurityGroupsRequest {

    private String mountTargetId;
    private List<String> securityGroups;

    public String getMountTargetId() {
        return mountTargetId;
    }

    public void setMountTargetId(String mountTargetId) {
        this.mountTargetId = mountTargetId;
    }

    public List<String> getSecurityGroups() {
        return securityGroups;
    }

    public void setSecurityGroups(List<String> securityGroups) {
        this.securityGroups = securityGroups;
    }
}