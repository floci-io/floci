package io.github.hectorvent.floci.services.bedrockagentcorecontrol.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An AgentCore harness: a managed agent loop configuration. Metadata only; {@code InvokeHarness}
 * does not read it. Its configuration lives in {@link #getVersions() versions}, the last of which is
 * current.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Harness {

    private String harnessId;
    private String harnessName;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private String clientToken;
    private Map<String, String> tags = new HashMap<>();
    private List<HarnessVersion> versions = new ArrayList<>();

    public String getHarnessId() {
        return harnessId;
    }

    public void setHarnessId(String harnessId) {
        this.harnessId = harnessId;
    }

    public String getHarnessName() {
        return harnessName;
    }

    public void setHarnessName(String harnessName) {
        this.harnessName = harnessName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new HashMap<>();
    }

    public List<HarnessVersion> getVersions() {
        return versions;
    }

    public void setVersions(List<HarnessVersion> versions) {
        this.versions = versions != null ? versions : new ArrayList<>();
    }

    /** The current version: the one written last. */
    @JsonIgnore
    public HarnessVersion getCurrentVersion() {
        return versions.getLast();
    }

    /** The version with this number, empty when there is none. */
    public Optional<HarnessVersion> findVersion(String version) {
        return versions.stream().filter(candidate -> candidate.getVersion().equals(version)).findFirst();
    }
}
