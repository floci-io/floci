package io.github.hectorvent.floci.services.lakeformation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
@RegisterForReflection
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RegisterResourceRequest {

    private String expectedResourceOwnerAccount;
    private Boolean hybridAccessEnabled;
    private String resourceArn;
    private String roleArn;
    private Boolean useServiceLinkedRole;
    private Boolean withFederation;
    private Boolean withPrivilegedAccess;

    public String getExpectedResourceOwnerAccount() {
        return expectedResourceOwnerAccount;
    }

    public void setExpectedResourceOwnerAccount(String expectedResourceOwnerAccount) {
        this.expectedResourceOwnerAccount = expectedResourceOwnerAccount;
    }

    public Boolean getHybridAccessEnabled() {
        return hybridAccessEnabled;
    }

    public void setHybridAccessEnabled(Boolean hybridAccessEnabled) {
        this.hybridAccessEnabled = hybridAccessEnabled;
    }

    public String getResourceArn() {
        return resourceArn;
    }

    public void setResourceArn(String resourceArn) {
        this.resourceArn = resourceArn;
    }

    public String getRoleArn() {
        return roleArn;
    }

    public void setRoleArn(String roleArn) {
        this.roleArn = roleArn;
    }

    public Boolean getUseServiceLinkedRole() {
        return useServiceLinkedRole;
    }

    public void setUseServiceLinkedRole(Boolean useServiceLinkedRole) {
        this.useServiceLinkedRole = useServiceLinkedRole;
    }

    public Boolean getWithFederation() {
        return withFederation;
    }

    public void setWithFederation(Boolean withFederation) {
        this.withFederation = withFederation;
    }

    public Boolean getWithPrivilegedAccess() {
        return withPrivilegedAccess;
    }

    public void setWithPrivilegedAccess(Boolean withPrivilegedAccess) {
        this.withPrivilegedAccess = withPrivilegedAccess;
    }
}
