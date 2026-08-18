package io.github.hectorvent.floci.services.efs.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class DescribeFileSystemPolicyResponse {
    private String fileSystemId;
    private String policy;
    public String getFileSystemId() { return fileSystemId; }
    public void setFileSystemId(String fileSystemId) { this.fileSystemId = fileSystemId; }
    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }
}
