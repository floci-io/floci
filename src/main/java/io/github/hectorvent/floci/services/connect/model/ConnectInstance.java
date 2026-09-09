package io.github.hectorvent.floci.services.connect.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class ConnectInstance {

    private String id;
    private String arn;
    private String identityManagementType;
    private String instanceAlias;
    private String directoryId;
    private Instant createdTime;
    private String serviceRole;
    private boolean inboundCallsEnabled;
    private boolean outboundCallsEnabled;
    private String instanceAccessUrl;
    private Map<String, String> tags = new LinkedHashMap<>();
    private Map<String, String> attributes = new LinkedHashMap<>();
    private String accountId;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getIdentityManagementType() {
        return identityManagementType;
    }

    public void setIdentityManagementType(String identityManagementType) {
        this.identityManagementType = identityManagementType;
    }

    public String getInstanceAlias() {
        return instanceAlias;
    }

    public void setInstanceAlias(String instanceAlias) {
        this.instanceAlias = instanceAlias;
    }

    public String getDirectoryId() {
        return directoryId;
    }

    public void setDirectoryId(String directoryId) {
        this.directoryId = directoryId;
    }

    public Instant getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(Instant createdTime) {
        this.createdTime = createdTime;
    }

    public String getServiceRole() {
        return serviceRole;
    }

    public void setServiceRole(String serviceRole) {
        this.serviceRole = serviceRole;
    }

    public boolean isInboundCallsEnabled() {
        return inboundCallsEnabled;
    }

    public void setInboundCallsEnabled(boolean inboundCallsEnabled) {
        this.inboundCallsEnabled = inboundCallsEnabled;
    }

    public boolean isOutboundCallsEnabled() {
        return outboundCallsEnabled;
    }

    public void setOutboundCallsEnabled(boolean outboundCallsEnabled) {
        this.outboundCallsEnabled = outboundCallsEnabled;
    }

    public String getInstanceAccessUrl() {
        return instanceAccessUrl;
    }

    public void setInstanceAccessUrl(String instanceAccessUrl) {
        this.instanceAccessUrl = instanceAccessUrl;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }
}
