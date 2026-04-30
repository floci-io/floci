package io.github.hectorvent.floci.services.dynamodb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExportDescription {

    @JsonProperty("ExportArn")
    private String exportArn;

    @JsonProperty("ExportStatus")
    private String exportStatus;

    @JsonProperty("TableArn")
    private String tableArn;

    @JsonProperty("TableId")
    private String tableId;

    @JsonProperty("S3Bucket")
    private String s3Bucket;

    @JsonProperty("S3Prefix")
    private String s3Prefix;

    @JsonProperty("ExportFormat")
    private String exportFormat;

    @JsonProperty("ExportType")
    private String exportType;

    @JsonProperty("ExportTime")
    private Long exportTime;

    @JsonProperty("StartTime")
    private Long startTime;

    @JsonProperty("EndTime")
    private Long endTime;

    @JsonProperty("ItemCount")
    private Long itemCount;

    @JsonProperty("BilledSizeBytes")
    private Long billedSizeBytes;

    @JsonProperty("ExportManifest")
    private String exportManifest;

    @JsonProperty("ClientToken")
    private String clientToken;

    @JsonProperty("S3SseAlgorithm")
    private String s3SseAlgorithm;

    @JsonProperty("S3BucketOwner")
    private String s3BucketOwner;

    @JsonProperty("FailureCode")
    private String failureCode;

    @JsonProperty("FailureMessage")
    private String failureMessage;

    public String getExportArn() { return exportArn; }
    public void setExportArn(String exportArn) { this.exportArn = exportArn; }

    public String getExportStatus() { return exportStatus; }
    public void setExportStatus(String exportStatus) { this.exportStatus = exportStatus; }

    public String getTableArn() { return tableArn; }
    public void setTableArn(String tableArn) { this.tableArn = tableArn; }

    public String getTableId() { return tableId; }
    public void setTableId(String tableId) { this.tableId = tableId; }

    public String getS3Bucket() { return s3Bucket; }
    public void setS3Bucket(String s3Bucket) { this.s3Bucket = s3Bucket; }

    public String getS3Prefix() { return s3Prefix; }
    public void setS3Prefix(String s3Prefix) { this.s3Prefix = s3Prefix; }

    public String getExportFormat() { return exportFormat; }
    public void setExportFormat(String exportFormat) { this.exportFormat = exportFormat; }

    public String getExportType() { return exportType; }
    public void setExportType(String exportType) { this.exportType = exportType; }

    public Long getExportTime() { return exportTime; }
    public void setExportTime(Long exportTime) { this.exportTime = exportTime; }

    public Long getStartTime() { return startTime; }
    public void setStartTime(Long startTime) { this.startTime = startTime; }

    public Long getEndTime() { return endTime; }
    public void setEndTime(Long endTime) { this.endTime = endTime; }

    public Long getItemCount() { return itemCount; }
    public void setItemCount(Long itemCount) { this.itemCount = itemCount; }

    public Long getBilledSizeBytes() { return billedSizeBytes; }
    public void setBilledSizeBytes(Long billedSizeBytes) { this.billedSizeBytes = billedSizeBytes; }

    public String getExportManifest() { return exportManifest; }
    public void setExportManifest(String exportManifest) { this.exportManifest = exportManifest; }

    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }

    public String getS3SseAlgorithm() { return s3SseAlgorithm; }
    public void setS3SseAlgorithm(String s3SseAlgorithm) { this.s3SseAlgorithm = s3SseAlgorithm; }

    public String getS3BucketOwner() { return s3BucketOwner; }
    public void setS3BucketOwner(String s3BucketOwner) { this.s3BucketOwner = s3BucketOwner; }

    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }

    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }
}
