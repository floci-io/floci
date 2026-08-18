package io.github.hectorvent.floci.services.efs.model;

import java.util.List;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class DeleteTagsRequest {

    private String fileSystemId;
    private List<String> tagKeys;

    public String getFileSystemId() {
        return fileSystemId;
    }

    public void setFileSystemId(String fileSystemId) {
        this.fileSystemId = fileSystemId;
    }

    public List<String> getTagKeys() {
        return tagKeys;
    }

    public void setTagKeys(List<String> tagKeys) {
        this.tagKeys = tagKeys;
    }
}