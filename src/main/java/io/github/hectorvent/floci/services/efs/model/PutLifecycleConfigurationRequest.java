package io.github.hectorvent.floci.services.efs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@RegisterForReflection
public class PutLifecycleConfigurationRequest {
    private String fileSystemId;
    private List<LifecyclePolicy> lifecyclePolicies;
    public String getFileSystemId() { return fileSystemId; }
    public void setFileSystemId(String fileSystemId) { this.fileSystemId = fileSystemId; }
    public List<LifecyclePolicy> getLifecyclePolicies() { return lifecyclePolicies; }
    public void setLifecyclePolicies(List<LifecyclePolicy> lifecyclePolicies) { this.lifecyclePolicies = lifecyclePolicies; }
}
