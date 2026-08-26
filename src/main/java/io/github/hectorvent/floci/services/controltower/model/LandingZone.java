package io.github.hectorvent.floci.services.controltower.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * A Control Tower landing zone. Serialized with AWS's lowerCamelCase member names
 * (Jackson's default naming — unlike RUM's UpperCamelCase), so no {@code @JsonNaming} override.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LandingZone {
    private String arn;
    private String version;
    private String latestAvailableVersion;
    private String status;
    private String driftStatus;
    private JsonNode manifest;
    private List<String> remediationTypes;

    public LandingZone() {
    }

    public LandingZone(
            String arn,
            String version,
            String latestAvailableVersion,
            String status,
            String driftStatus,
            JsonNode manifest,
            List<String> remediationTypes) {
        this.arn = arn;
        this.version = version;
        this.latestAvailableVersion = latestAvailableVersion;
        this.status = status;
        this.driftStatus = driftStatus;
        setManifest(manifest);
        setRemediationTypes(remediationTypes);
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getLatestAvailableVersion() {
        return latestAvailableVersion;
    }

    public void setLatestAvailableVersion(String latestAvailableVersion) {
        this.latestAvailableVersion = latestAvailableVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDriftStatus() {
        return driftStatus;
    }

    public void setDriftStatus(String driftStatus) {
        this.driftStatus = driftStatus;
    }

    public JsonNode getManifest() {
        return copy(manifest);
    }

    public void setManifest(JsonNode manifest) {
        this.manifest = copy(manifest);
    }

    public List<String> getRemediationTypes() {
        return remediationTypes;
    }

    public void setRemediationTypes(List<String> remediationTypes) {
        this.remediationTypes = remediationTypes == null ? null : List.copyOf(remediationTypes);
    }

    private static JsonNode copy(JsonNode value) {
        return value == null ? null : value.deepCopy();
    }
}
