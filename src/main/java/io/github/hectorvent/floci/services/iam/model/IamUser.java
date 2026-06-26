package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class IamUser {

    private String userId;
    private String userName;
    private String path;
    private String arn;
    private Instant createDate;
    private Instant passwordLastUsed;
    private Map<String, String> tags = new ConcurrentHashMap<>();
    private List<String> groupNames = new CopyOnWriteArrayList<>();
    private List<String> attachedPolicyArns = new CopyOnWriteArrayList<>();
    private Map<String, String> inlinePolicies = new ConcurrentHashMap<>();
    private String permissionsBoundaryArn;

    public IamUser() {}

    public IamUser(String userId, String userName, String path, String arn) {
        this.userId = userId;
        this.userName = userName;
        this.path = path;
        this.arn = arn;
        this.createDate = Instant.now();
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public Instant getCreateDate() { return createDate; }
    public void setCreateDate(Instant createDate) { this.createDate = createDate; }

    public Instant getPasswordLastUsed() { return passwordLastUsed; }
    public void setPasswordLastUsed(Instant passwordLastUsed) { this.passwordLastUsed = passwordLastUsed; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = new ConcurrentHashMap<>(tags);
    }

    public List<String> getGroupNames() { return groupNames; }
    public void setGroupNames(List<String> groupNames) {
        this.groupNames = new CopyOnWriteArrayList<>(groupNames);
    }

    public List<String> getAttachedPolicyArns() { return attachedPolicyArns; }
    public void setAttachedPolicyArns(List<String> attachedPolicyArns) {
        this.attachedPolicyArns = new CopyOnWriteArrayList<>(attachedPolicyArns);
    }

    public Map<String, String> getInlinePolicies() { return inlinePolicies; }
    public void setInlinePolicies(Map<String, String> inlinePolicies) {
        this.inlinePolicies = new ConcurrentHashMap<>(inlinePolicies);
    }

    public String getPermissionsBoundaryArn() { return permissionsBoundaryArn; }
    public void setPermissionsBoundaryArn(String permissionsBoundaryArn) { this.permissionsBoundaryArn = permissionsBoundaryArn; }
}
