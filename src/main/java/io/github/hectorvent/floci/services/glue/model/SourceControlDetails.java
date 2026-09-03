package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class SourceControlDetails {
    @JsonProperty("AuthStrategy")
    private String authStrategy;

    @JsonProperty("AuthToken")
    private String authToken;

    @JsonProperty("Branch")
    private String branch;

    @JsonProperty("Folder")
    private String folder;

    @JsonProperty("LastCommitId")
    private String lastCommitId;

    @JsonProperty("Owner")
    private String owner;

    @JsonProperty("Provider")
    private String provider;

    @JsonProperty("Repository")
    private String repository;

    public SourceControlDetails() {}

    public String getAuthStrategy() { return authStrategy; }
    public void setAuthStrategy(String authStrategy) { this.authStrategy = authStrategy; }

    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public String getFolder() { return folder; }
    public void setFolder(String folder) { this.folder = folder; }

    public String getLastCommitId() { return lastCommitId; }
    public void setLastCommitId(String lastCommitId) { this.lastCommitId = lastCommitId; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getRepository() { return repository; }
    public void setRepository(String repository) { this.repository = repository; }
}
