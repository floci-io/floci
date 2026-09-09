package io.github.hectorvent.floci.services.efs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@RegisterForReflection
public class PutFileSystemPolicyRequest {
    private String fileSystemId;
    private String policy;
    private Boolean bypassPolicyLockoutSafetyCheck;
    public String getFileSystemId() { return fileSystemId; }
    public void setFileSystemId(String fileSystemId) { this.fileSystemId = fileSystemId; }
    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }
    public Boolean getBypassPolicyLockoutSafetyCheck() { return bypassPolicyLockoutSafetyCheck; }
    public void setBypassPolicyLockoutSafetyCheck(Boolean bypassPolicyLockoutSafetyCheck) { this.bypassPolicyLockoutSafetyCheck = bypassPolicyLockoutSafetyCheck; }
}
