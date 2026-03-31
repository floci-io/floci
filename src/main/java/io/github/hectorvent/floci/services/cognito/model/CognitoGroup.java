package io.github.hectorvent.floci.services.cognito.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CognitoGroup {
    private String groupName;
    private String userPoolId;
    private String description;
    private Integer precedence;
    private String roleArn;
    private long creationDate;
    private long lastModifiedDate;
    private List<String> userNames;

    public CognitoGroup() {
        long now = System.currentTimeMillis() / 1000L;
        this.creationDate = now;
        this.lastModifiedDate = now;
        this.userNames = new ArrayList<>();
    }

    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public String getUserPoolId() { return userPoolId; }
    public void setUserPoolId(String userPoolId) { this.userPoolId = userPoolId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Integer getPrecedence() { return precedence; }
    public void setPrecedence(Integer precedence) { this.precedence = precedence; }

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }

    public long getLastModifiedDate() { return lastModifiedDate; }
    public void setLastModifiedDate(long lastModifiedDate) { this.lastModifiedDate = lastModifiedDate; }

    public List<String> getUserNames() { return Collections.unmodifiableList(userNames); }
    public void setUserNames(List<String> userNames) { this.userNames = userNames == null ? new ArrayList<>() : new ArrayList<>(userNames); }

    public boolean addUserName(String name) {
        if (userNames.contains(name)) return false;
        return userNames.add(name);
    }

    public boolean removeUserName(String name) {
        return userNames.remove(name);
    }
}
