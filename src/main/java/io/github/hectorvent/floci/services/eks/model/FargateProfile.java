package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class FargateProfile {

    @JsonProperty("fargateProfileName")
    private String fargateProfileName;

    @JsonProperty("fargateProfileArn")
    private String fargateProfileArn;

    @JsonProperty("clusterName")
    private String clusterName;

    @JsonProperty("createdAt")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createdAt;

    @JsonProperty("podExecutionRoleArn")
    private String podExecutionRoleArn;

    @JsonProperty("subnets")
    private List<String> subnets;

    @JsonProperty("selectors")
    private List<Selector> selectors;

    @JsonProperty("status")
    private FargateProfileStatus status;

    @JsonProperty("health")
    private Health health;

    @JsonProperty("tags")
    private Map<String, String> tags;

    @JsonIgnore
    private String accountId;

    public FargateProfile() {}

    public String getFargateProfileName() { return fargateProfileName; }
    public void setFargateProfileName(String fargateProfileName) { this.fargateProfileName = fargateProfileName; }

    public String getFargateProfileArn() { return fargateProfileArn; }
    public void setFargateProfileArn(String fargateProfileArn) { this.fargateProfileArn = fargateProfileArn; }

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getPodExecutionRoleArn() { return podExecutionRoleArn; }
    public void setPodExecutionRoleArn(String podExecutionRoleArn) { this.podExecutionRoleArn = podExecutionRoleArn; }

    public List<String> getSubnets() { return subnets; }
    public void setSubnets(List<String> subnets) { this.subnets = subnets; }

    public List<Selector> getSelectors() { return selectors; }
    public void setSelectors(List<Selector> selectors) { this.selectors = selectors; }

    public FargateProfileStatus getStatus() { return status; }
    public void setStatus(FargateProfileStatus status) { this.status = status; }

    public Health getHealth() { return health; }
    public void setHealth(Health health) { this.health = health; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Selector {
        @JsonProperty("namespace")
        private String namespace;

        @JsonProperty("labels")
        private Map<String, String> labels;

        public Selector() {}

        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }

        public Map<String, String> getLabels() { return labels; }
        public void setLabels(Map<String, String> labels) { this.labels = labels; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Health {
        @JsonProperty("issues")
        private List<Issue> issues;

        public Health() {}

        public List<Issue> getIssues() { return issues; }
        public void setIssues(List<Issue> issues) { this.issues = issues; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Issue {
        @JsonProperty("code")
        private String code;

        @JsonProperty("message")
        private String message;

        @JsonProperty("resourceIds")
        private List<String> resourceIds;

        public Issue() {}

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public List<String> getResourceIds() { return resourceIds; }
        public void setResourceIds(List<String> resourceIds) { this.resourceIds = resourceIds; }
    }
}
