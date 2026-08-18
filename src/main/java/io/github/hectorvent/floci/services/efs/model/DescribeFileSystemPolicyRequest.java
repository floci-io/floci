package io.github.hectorvent.floci.services.efs.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class DescribeFileSystemPolicyRequest {
    private String fileSystemId;
    public String getFileSystemId() { return fileSystemId; }
    public void setFileSystemId(String fileSystemId) { this.fileSystemId = fileSystemId; }
}
