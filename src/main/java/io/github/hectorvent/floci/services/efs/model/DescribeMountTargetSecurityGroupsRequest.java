package io.github.hectorvent.floci.services.efs.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class DescribeMountTargetSecurityGroupsRequest {

    private String mountTargetId;

    public String getMountTargetId() {
        return mountTargetId;
    }

    public void setMountTargetId(String mountTargetId) {
        this.mountTargetId = mountTargetId;
    }
}