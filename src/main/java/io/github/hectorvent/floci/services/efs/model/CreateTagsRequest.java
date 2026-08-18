package io.github.hectorvent.floci.services.efs.model;

import java.util.List;

public class CreateTagsRequest {

    private String fileSystemId;
    private List<Tag> tags;

    public String getFileSystemId() {
        return fileSystemId;
    }

    public void setFileSystemId(String fileSystemId) {
        this.fileSystemId = fileSystemId;
    }

    public List<Tag> getTags() {
        return tags;
    }

    public void setTags(List<Tag> tags) {
        this.tags = tags;
    }
}