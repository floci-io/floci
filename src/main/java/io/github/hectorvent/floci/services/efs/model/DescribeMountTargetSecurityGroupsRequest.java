package io.github.hectorvent.floci.services.efs.model;

public class DescribeMountTargetSecurityGroupsRequest {

    private String mountTargetId;

    public String getMountTargetId() {
        return mountTargetId;
    }

    public void setMountTargetId(String mountTargetId) {
        this.mountTargetId = mountTargetId;
    }
}