package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * A stored SES V2 export job. The request's {@code ExportDataSource} is kept verbatim as JSON
 * because {@code GetExportJob} echoes it back, filters and all, and Floci has no reason to model
 * every filter member just to hand it straight back.
 */
@RegisterForReflection
public class ExportJob {

    public static final String SOURCE_METRICS = "METRICS_DATA";
    public static final String SOURCE_MESSAGE_INSIGHTS = "MESSAGE_INSIGHTS";

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final String FORMAT_CSV = "CSV";
    public static final String FORMAT_JSON = "JSON";

    @JsonProperty("JobId")
    private String jobId;

    @JsonProperty("Region")
    private String region;

    /** The account that created the job, which keeps its export bucket inside that account. */
    @JsonProperty("AccountId")
    private String accountId;

    @JsonProperty("ExportSourceType")
    private String exportSourceType;

    @JsonProperty("JobStatus")
    private String jobStatus;

    @JsonProperty("DataFormat")
    private String dataFormat;

    @JsonProperty("DataSource")
    private String dataSource;

    /** The key of the produced object, once the worker has written it. */
    @JsonProperty("ObjectKey")
    private String objectKey;

    @JsonProperty("CreatedTimestamp")
    private Instant createdTimestamp;

    @JsonProperty("CompletedTimestamp")
    private Instant completedTimestamp;

    @JsonProperty("ErrorMessage")
    private String errorMessage;

    /** How many stored recipient rows the job examined, which is AWS's ProcessedRecordsCount. */
    @JsonProperty("ProcessedRecordsCount")
    private Long processedRecordsCount;

    public ExportJob() {}

    public boolean isTerminal() {
        return STATUS_COMPLETED.equals(jobStatus)
                || STATUS_FAILED.equals(jobStatus)
                || STATUS_CANCELLED.equals(jobStatus);
    }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getExportSourceType() { return exportSourceType; }
    public void setExportSourceType(String exportSourceType) { this.exportSourceType = exportSourceType; }

    public String getJobStatus() { return jobStatus; }
    public void setJobStatus(String jobStatus) { this.jobStatus = jobStatus; }

    public String getDataFormat() { return dataFormat; }
    public void setDataFormat(String dataFormat) { this.dataFormat = dataFormat; }

    public String getDataSource() { return dataSource; }
    public void setDataSource(String dataSource) { this.dataSource = dataSource; }

    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }

    public Instant getCreatedTimestamp() { return createdTimestamp; }
    public void setCreatedTimestamp(Instant createdTimestamp) { this.createdTimestamp = createdTimestamp; }

    public Instant getCompletedTimestamp() { return completedTimestamp; }
    public void setCompletedTimestamp(Instant completedTimestamp) { this.completedTimestamp = completedTimestamp; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Long getProcessedRecordsCount() { return processedRecordsCount; }
    public void setProcessedRecordsCount(Long processedRecordsCount) {
        this.processedRecordsCount = processedRecordsCount;
    }
}
