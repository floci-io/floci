package io.github.hectorvent.floci.services.efs.model;

public class CreationInfo {

    private Long ownerGid;
    private Long ownerUid;
    private String permissions;

    public Long getOwnerGid() {
        return ownerGid;
    }

    public void setOwnerGid(Long ownerGid) {
        this.ownerGid = ownerGid;
    }

    public Long getOwnerUid() {
        return ownerUid;
    }

    public void setOwnerUid(Long ownerUid) {
        this.ownerUid = ownerUid;
    }

    public String getPermissions() {
        return permissions;
    }

    public void setPermissions(String permissions) {
        this.permissions = permissions;
    }
}