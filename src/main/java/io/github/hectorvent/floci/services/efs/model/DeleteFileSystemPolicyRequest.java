package io.github.hectorvent.floci.services.efs.model;

public class DeleteFileSystemPolicyRequest {

    private String fileSystemId;

    public String getFileSystemId() {
        return fileSystemId;
    }

    public void setFileSystemId(String fileSystemId) {
        this.fileSystemId = fileSystemId;
    }
}