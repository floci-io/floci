package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class IamRole {

    private String roleId;
    private String roleName;
    private String path;
    private String arn;
    private String assumeRolePolicyDocument;
    private String description;
    private int maxSessionDuration = 3600;
    private Instant createDate;
    private Map<String, String> tags = new HashMap<>();
    private List<String> attachedPolicyArns = new ArrayList<>();
    private Map<String, String> inlinePolicies = new HashMap<>();
    private String permissionsBoundaryArn;

    public IamRole() {}

    public IamRole(String roleId, String roleName, String path, String arn,
                   String assumeRolePolicyDocument) {
        this.roleId = roleId;
        this.roleName = roleName;
        this.path = path;
        this.arn = arn;
        this.assumeRolePolicyDocument = assumeRolePolicyDocument;
        this.createDate = Instant.now();
    }

    public String getRoleId() { return roleId; }
    public void setRoleId(String roleId) { this.roleId = roleId; }

    public String getRoleName() { return roleName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getAssumeRolePolicyDocument() { return assumeRolePolicyDocument; }
    public void setAssumeRolePolicyDocument(String assumeRolePolicyDocument) { this.assumeRolePolicyDocument = assumeRolePolicyDocument; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public int getMaxSessionDuration() { return maxSessionDuration; }
    public void setMaxSessionDuration(int maxSessionDuration) { this.maxSessionDuration = maxSessionDuration; }

    public Instant getCreateDate() { return createDate; }
    public void setCreateDate(Instant createDate) { this.createDate = createDate; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public List<String> getAttachedPolicyArns() { return attachedPolicyArns; }
    public void setAttachedPolicyArns(List<String> attachedPolicyArns) { this.attachedPolicyArns = attachedPolicyArns; }

    public Map<String, String> getInlinePolicies() { return inlinePolicies; }
    public void setInlinePolicies(Map<String, String> inlinePolicies) { this.inlinePolicies = inlinePolicies; }

    public String getPermissionsBoundaryArn() { return permissionsBoundaryArn; }
    public void setPermissionsBoundaryArn(String permissionsBoundaryArn) { this.permissionsBoundaryArn = permissionsBoundaryArn; }
}
