package io.github.hectorvent.floci.services.efs.model;

import java.util.List;

public class UntagResourceRequest {

    private String resourceId;
    private List<String> tagKeys;

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public List<String> getTagKeys() {
        return tagKeys;
    }

    public void setTagKeys(List<String> tagKeys) {
        this.tagKeys = tagKeys;
    }
}