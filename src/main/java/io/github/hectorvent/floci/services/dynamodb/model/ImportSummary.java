package io.github.hectorvent.floci.services.dynamodb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImportSummary {

    @JsonProperty("ImportArn")
    private String importArn;

    @JsonProperty("ImportStatus")
    private String importStatus;

    @JsonProperty("TableArn")
    private String tableArn;

    @JsonProperty("S3BucketSource")
    private JsonNode s3BucketSource;

    @JsonProperty("CloudWatchLogGroupArn")
    private String cloudWatchLogGroupArn;

    @JsonProperty("InputFormat")
    private String inputFormat;

    @JsonProperty("StartTime")
    private Long startTime;

    @JsonProperty("EndTime")
    private Long endTime;

    public ImportSummary() {}

    public ImportSummary(ImportTableDescription desc) {
        this.importArn = desc.getImportArn();
        this.importStatus = desc.getImportStatus();
        this.tableArn = desc.getTableArn();
        this.s3BucketSource = desc.getS3BucketSource();
        this.cloudWatchLogGroupArn = desc.getCloudWatchLogGroupArn();
        this.inputFormat = desc.getInputFormat();
        this.startTime = desc.getStartTime();
        this.endTime = desc.getEndTime();
    }

    public String getImportArn() { return importArn; }
    public void setImportArn(String importArn) { this.importArn = importArn; }

    public String getImportStatus() { return importStatus; }
    public void setImportStatus(String importStatus) { this.importStatus = importStatus; }

    public String getTableArn() { return tableArn; }
    public void setTableArn(String tableArn) { this.tableArn = tableArn; }

    public JsonNode getS3BucketSource() { return s3BucketSource; }
    public void setS3BucketSource(JsonNode s3BucketSource) { this.s3BucketSource = s3BucketSource; }

    public String getCloudWatchLogGroupArn() { return cloudWatchLogGroupArn; }
    public void setCloudWatchLogGroupArn(String cloudWatchLogGroupArn) { this.cloudWatchLogGroupArn = cloudWatchLogGroupArn; }

    public String getInputFormat() { return inputFormat; }
    public void setInputFormat(String inputFormat) { this.inputFormat = inputFormat; }

    public Long getStartTime() { return startTime; }
    public void setStartTime(Long startTime) { this.startTime = startTime; }

    public Long getEndTime() { return endTime; }
    public void setEndTime(Long endTime) { this.endTime = endTime; }
}
