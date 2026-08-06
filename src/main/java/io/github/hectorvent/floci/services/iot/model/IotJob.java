package io.github.hectorvent.floci.services.iot.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
public class IotJob {

    private String jobId;
    private String jobArn;
    private String document;
    private String documentSource;
    private String description;
    private String targetSelection = "SNAPSHOT";
    private String status = "IN_PROGRESS";
    private List<String> targets = new ArrayList<>();
    private Instant createdAt;
    private Instant lastUpdatedAt;

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getJobArn() { return jobArn; }
    public void setJobArn(String jobArn) { this.jobArn = jobArn; }

    public String getDocument() { return document; }
    public void setDocument(String document) { this.document = document; }

    public String getDocumentSource() { return documentSource; }
    public void setDocumentSource(String documentSource) { this.documentSource = documentSource; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getTargetSelection() { return targetSelection; }
    public void setTargetSelection(String targetSelection) { this.targetSelection = targetSelection; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public List<String> getTargets() { return targets; }
    public void setTargets(List<String> targets) { this.targets = targets == null ? new ArrayList<>() : new ArrayList<>(targets); }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
    public void setLastUpdatedAt(Instant lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; }
}
