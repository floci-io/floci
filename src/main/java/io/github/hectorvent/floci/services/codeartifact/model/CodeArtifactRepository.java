package io.github.hectorvent.floci.services.codeartifact.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A CodeArtifact repository and its resource policy. */
@RegisterForReflection
public class CodeArtifactRepository {

    private String name;
    private String administratorAccount;
    private String domainName;
    private String domainOwner;
    private String arn;
    private String description;
    private List<String> upstreams = new ArrayList<>();
    private List<RepositoryExternalConnection> externalConnections = new ArrayList<>();
    private Instant createdTime;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String policyDocument;
    private String policyRevision;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAdministratorAccount() {
        return administratorAccount;
    }

    public void setAdministratorAccount(String administratorAccount) {
        this.administratorAccount = administratorAccount;
    }

    public String getDomainName() {
        return domainName;
    }

    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }

    public String getDomainOwner() {
        return domainOwner;
    }

    public void setDomainOwner(String domainOwner) {
        this.domainOwner = domainOwner;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getUpstreams() {
        return upstreams;
    }

    public void setUpstreams(List<String> upstreams) {
        this.upstreams = upstreams;
    }

    public List<RepositoryExternalConnection> getExternalConnections() {
        return externalConnections;
    }

    public void setExternalConnections(List<RepositoryExternalConnection> externalConnections) {
        this.externalConnections = externalConnections;
    }

    public Instant getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(Instant createdTime) {
        this.createdTime = createdTime;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }

    public String getPolicyDocument() {
        return policyDocument;
    }

    public void setPolicyDocument(String policyDocument) {
        this.policyDocument = policyDocument;
    }

    public String getPolicyRevision() {
        return policyRevision;
    }

    public void setPolicyRevision(String policyRevision) {
        this.policyRevision = policyRevision;
    }
}
