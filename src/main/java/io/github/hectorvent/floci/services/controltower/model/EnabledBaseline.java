package io.github.hectorvent.floci.services.controltower.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A Control Tower enabled baseline (a baseline applied to an OU or the landing zone itself).
 * Serialized with AWS's lowerCamelCase member names (Jackson's default naming).
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EnabledBaseline {
    private String arn;
    private String baselineIdentifier;
    private String baselineVersion;
    private String targetIdentifier;
    private String status;
    private JsonNode parameters;
    private String parentIdentifier;
    private String driftStatus;
    private String lastOperationIdentifier;

    public EnabledBaseline() {
    }

    public EnabledBaseline(
            String arn,
            String baselineIdentifier,
            String baselineVersion,
            String targetIdentifier,
            String status,
            JsonNode parameters) {
        this.arn = arn;
        this.baselineIdentifier = baselineIdentifier;
        this.baselineVersion = baselineVersion;
        this.targetIdentifier = targetIdentifier;
        this.status = status;
        setParameters(parameters);
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getBaselineIdentifier() {
        return baselineIdentifier;
    }

    public void setBaselineIdentifier(String baselineIdentifier) {
        this.baselineIdentifier = baselineIdentifier;
    }

    public String getBaselineVersion() {
        return baselineVersion;
    }

    public void setBaselineVersion(String baselineVersion) {
        this.baselineVersion = baselineVersion;
    }

    public String getTargetIdentifier() {
        return targetIdentifier;
    }

    public void setTargetIdentifier(String targetIdentifier) {
        this.targetIdentifier = targetIdentifier;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public JsonNode getParameters() {
        return copy(parameters);
    }

    public void setParameters(JsonNode parameters) {
        this.parameters = copy(parameters);
    }

    public String getParentIdentifier() {
        return parentIdentifier;
    }

    public void setParentIdentifier(String parentIdentifier) {
        this.parentIdentifier = parentIdentifier;
    }

    public String getDriftStatus() {
        return driftStatus;
    }

    public void setDriftStatus(String driftStatus) {
        this.driftStatus = driftStatus;
    }

    public String getLastOperationIdentifier() {
        return lastOperationIdentifier;
    }

    public void setLastOperationIdentifier(String lastOperationIdentifier) {
        this.lastOperationIdentifier = lastOperationIdentifier;
    }

    private static JsonNode copy(JsonNode value) {
        return value == null ? null : value.deepCopy();
    }
}
