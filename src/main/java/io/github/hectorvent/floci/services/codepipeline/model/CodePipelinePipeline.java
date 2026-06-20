package io.github.hectorvent.floci.services.codepipeline.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CodePipelinePipeline {
    public CodePipelinePipeline() {
    }

    private String accountId;
    private String region;
    private String name;
    private String arn;
    private Integer version;
    private Double created;
    private Double updated;
    private JsonNode declaration;
    private Map<String, String> tags = new LinkedHashMap<>();
    private Map<String, TransitionState> transitions = new LinkedHashMap<>();

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Double getCreated() {
        return created;
    }

    public void setCreated(Double created) {
        this.created = created;
    }

    public Double getUpdated() {
        return updated;
    }

    public void setUpdated(Double updated) {
        this.updated = updated;
    }

    public JsonNode getDeclaration() {
        return declaration;
    }

    public void setDeclaration(JsonNode declaration) {
        this.declaration = declaration;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }

    public Map<String, TransitionState> getTransitions() {
        return transitions;
    }

    public void setTransitions(Map<String, TransitionState> transitions) {
        this.transitions = transitions;
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TransitionState {
        public TransitionState() {
        }

        private boolean enabled = true;
        private String reason;
        private Double lastChangedAt;
        private String lastChangedBy;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public Double getLastChangedAt() {
            return lastChangedAt;
        }

        public void setLastChangedAt(Double lastChangedAt) {
            this.lastChangedAt = lastChangedAt;
        }

        public String getLastChangedBy() {
            return lastChangedBy;
        }

        public void setLastChangedBy(String lastChangedBy) {
            this.lastChangedBy = lastChangedBy;
        }
    }
}
