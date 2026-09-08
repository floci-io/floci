package io.github.hectorvent.floci.services.dynamodb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImportTableDescription {

    @JsonProperty("ImportArn")
    private String importArn;

    @JsonProperty("ImportStatus")
    private String importStatus;

    @JsonProperty("TableArn")
    private String tableArn;

    @JsonProperty("TableId")
    private String tableId;

    @JsonProperty("ClientToken")
    private String clientToken;

    @JsonProperty("S3BucketSource")
    private JsonNode s3BucketSource;

    @JsonProperty("ErrorCount")
    private Long errorCount = 0L;

    @JsonProperty("CloudWatchLogGroupArn")
    private String cloudWatchLogGroupArn;

    @JsonProperty("InputFormat")
    private String inputFormat;

    @JsonProperty("InputFormatOptions")
    private JsonNode inputFormatOptions;

    @JsonProperty("InputCompressionType")
    private String inputCompressionType;

    @JsonProperty("TableCreationParameters")
    private JsonNode tableCreationParameters;

    @JsonProperty("StartTime")
    private Long startTime;

    @JsonProperty("EndTime")
    private Long endTime;

    @JsonProperty("ProcessedSizeBytes")
    private Long processedSizeBytes = 0L;

    @JsonProperty("ProcessedItemCount")
    private Long processedItemCount = 0L;

    @JsonProperty("ImportedItemCount")
    private Long importedItemCount = 0L;

    @JsonProperty("FailureCode")
    private String failureCode;

    @JsonProperty("FailureMessage")
    private String failureMessage;

    public String getImportArn() { return importArn; }
    public void setImportArn(String importArn) { this.importArn = importArn; }

    public String getImportStatus() { return importStatus; }
    public void setImportStatus(String importStatus) { this.importStatus = importStatus; }

    public String getTableArn() { return tableArn; }
    public void setTableArn(String tableArn) { this.tableArn = tableArn; }

    public String getTableId() { return tableId; }
    public void setTableId(String tableId) { this.tableId = tableId; }

    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }

    public JsonNode getS3BucketSource() { return s3BucketSource; }
    public void setS3BucketSource(JsonNode s3BucketSource) { this.s3BucketSource = s3BucketSource; }

    public Long getErrorCount() { return errorCount; }
    public void setErrorCount(Long errorCount) { this.errorCount = errorCount; }

    public String getCloudWatchLogGroupArn() { return cloudWatchLogGroupArn; }
    public void setCloudWatchLogGroupArn(String cloudWatchLogGroupArn) { this.cloudWatchLogGroupArn = cloudWatchLogGroupArn; }

    public String getInputFormat() { return inputFormat; }
    public void setInputFormat(String inputFormat) { this.inputFormat = inputFormat; }

    public JsonNode getInputFormatOptions() { return inputFormatOptions; }
    public void setInputFormatOptions(JsonNode inputFormatOptions) { this.inputFormatOptions = inputFormatOptions; }

    public String getInputCompressionType() { return inputCompressionType; }
    public void setInputCompressionType(String inputCompressionType) { this.inputCompressionType = inputCompressionType; }

    public JsonNode getTableCreationParameters() { return tableCreationParameters; }
    public void setTableCreationParameters(JsonNode tableCreationParameters) { this.tableCreationParameters = tableCreationParameters; }

    public Long getStartTime() { return startTime; }
    public void setStartTime(Long startTime) { this.startTime = startTime; }

    public Long getEndTime() { return endTime; }
    public void setEndTime(Long endTime) { this.endTime = endTime; }

    public Long getProcessedSizeBytes() { return processedSizeBytes; }
    public void setProcessedSizeBytes(Long processedSizeBytes) { this.processedSizeBytes = processedSizeBytes; }

    public Long getProcessedItemCount() { return processedItemCount; }
    public void setProcessedItemCount(Long processedItemCount) { this.processedItemCount = processedItemCount; }

    public Long getImportedItemCount() { return importedItemCount; }
    public void setImportedItemCount(Long importedItemCount) { this.importedItemCount = importedItemCount; }

    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }

    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }
}
