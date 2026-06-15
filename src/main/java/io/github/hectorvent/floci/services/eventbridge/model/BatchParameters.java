package io.github.hectorvent.floci.services.eventbridge.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class BatchParameters {

    private String jobDefinition;
    private String jobName;
    private Map<String, Object> arrayProperties;
    private JsonNode retryStrategy;

    public BatchParameters() {
    }

    @JsonProperty("JobDefinition")
    public String getJobDefinition() {
        return jobDefinition;
    }

    @JsonProperty("JobDefinition")
    public void setJobDefinition(String jobDefinition) {
        this.jobDefinition = jobDefinition;
    }

    @JsonProperty("JobName")
    public String getJobName() {
        return jobName;
    }

    @JsonProperty("JobName")
    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    @JsonProperty("ArrayProperties")
    public Map<String, Object> getArrayProperties() {
        return arrayProperties;
    }

    @JsonProperty("ArrayProperties")
    public void setArrayProperties(Map<String, Object> arrayProperties) {
        this.arrayProperties = arrayProperties;
    }

    @JsonProperty("RetryStrategy")
    public JsonNode getRetryStrategy() {
        return retryStrategy;
    }

    @JsonProperty("RetryStrategy")
    public void setRetryStrategy(JsonNode retryStrategy) {
        if (retryStrategy == null || retryStrategy.isNull()) {
            this.retryStrategy = null;
            return;
        }
        ObjectNode allowed = JsonNodeFactory.instance.objectNode();
        if (retryStrategy.has("Attempts")) {
            allowed.set("Attempts", retryStrategy.get("Attempts"));
        }
        this.retryStrategy = allowed.isEmpty() ? null : allowed;
    }
}
