package io.github.hectorvent.floci.services.appsync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SourceApiAssociation {
    private String associationArn;
    private String associationId;
    private String description;
    private Long lastSuccessfulMergeDate;
    private String mergedApiArn;
    private String mergedApiId;
    private String sourceApiArn;
    private SourceApiAssociationConfig sourceApiAssociationConfig;
    private String sourceApiAssociationStatus;
    private String sourceApiAssociationStatusDetail;
    private String sourceApiId;

    public String getAssociationArn() { return associationArn; }
    public void setAssociationArn(String associationArn) { this.associationArn = associationArn; }

    public String getAssociationId() { return associationId; }
    public void setAssociationId(String associationId) { this.associationId = associationId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Long getLastSuccessfulMergeDate() { return lastSuccessfulMergeDate; }
    public void setLastSuccessfulMergeDate(Long lastSuccessfulMergeDate) { this.lastSuccessfulMergeDate = lastSuccessfulMergeDate; }

    public String getMergedApiArn() { return mergedApiArn; }
    public void setMergedApiArn(String mergedApiArn) { this.mergedApiArn = mergedApiArn; }

    public String getMergedApiId() { return mergedApiId; }
    public void setMergedApiId(String mergedApiId) { this.mergedApiId = mergedApiId; }

    public String getSourceApiArn() { return sourceApiArn; }
    public void setSourceApiArn(String sourceApiArn) { this.sourceApiArn = sourceApiArn; }

    public SourceApiAssociationConfig getSourceApiAssociationConfig() { return sourceApiAssociationConfig; }
    public void setSourceApiAssociationConfig(SourceApiAssociationConfig config) { this.sourceApiAssociationConfig = config; }

    public String getSourceApiAssociationStatus() { return sourceApiAssociationStatus; }
    public void setSourceApiAssociationStatus(String sourceApiAssociationStatus) { this.sourceApiAssociationStatus = sourceApiAssociationStatus; }

    public String getSourceApiAssociationStatusDetail() { return sourceApiAssociationStatusDetail; }
    public void setSourceApiAssociationStatusDetail(String sourceApiAssociationStatusDetail) { this.sourceApiAssociationStatusDetail = sourceApiAssociationStatusDetail; }

    public String getSourceApiId() { return sourceApiId; }
    public void setSourceApiId(String sourceApiId) { this.sourceApiId = sourceApiId; }
}
