package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * A SES V2 import job: the S3 source, the suppression-list or contact-list destination, and the
 * job's progress. The destination is flattened (type + action + optional contact list name) since
 * a job has exactly one; the controller rebuilds the AWS {@code ImportDestination} shape from it.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImportJob {

    public static final String DESTINATION_SUPPRESSION_LIST = "SUPPRESSION_LIST";
    public static final String DESTINATION_CONTACT_LIST = "CONTACT_LIST";
    public static final String ACTION_PUT = "PUT";
    public static final String ACTION_DELETE = "DELETE";
    public static final String FORMAT_CSV = "CSV";
    public static final String FORMAT_JSON = "JSON";
    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    private String jobId;
    private String region;
    private String destinationType;
    private String importAction;
    private String contactListName;
    private String s3Url;
    private String dataFormat;
    private String jobStatus;
    private Instant createdTimestamp;
    private Instant completedTimestamp;
    private long processedRecordsCount;
    private long failedRecordsCount;
    private String errorMessage;

    public ImportJob() {}

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getDestinationType() { return destinationType; }
    public void setDestinationType(String destinationType) { this.destinationType = destinationType; }

    public String getImportAction() { return importAction; }
    public void setImportAction(String importAction) { this.importAction = importAction; }

    public String getContactListName() { return contactListName; }
    public void setContactListName(String contactListName) { this.contactListName = contactListName; }

    public String getS3Url() { return s3Url; }
    public void setS3Url(String s3Url) { this.s3Url = s3Url; }

    public String getDataFormat() { return dataFormat; }
    public void setDataFormat(String dataFormat) { this.dataFormat = dataFormat; }

    public String getJobStatus() { return jobStatus; }
    public void setJobStatus(String jobStatus) { this.jobStatus = jobStatus; }

    public Instant getCreatedTimestamp() { return createdTimestamp; }
    public void setCreatedTimestamp(Instant createdTimestamp) { this.createdTimestamp = createdTimestamp; }

    public Instant getCompletedTimestamp() { return completedTimestamp; }
    public void setCompletedTimestamp(Instant completedTimestamp) { this.completedTimestamp = completedTimestamp; }

    public long getProcessedRecordsCount() { return processedRecordsCount; }
    public void setProcessedRecordsCount(long processedRecordsCount) {
        this.processedRecordsCount = processedRecordsCount;
    }

    public long getFailedRecordsCount() { return failedRecordsCount; }
    public void setFailedRecordsCount(long failedRecordsCount) { this.failedRecordsCount = failedRecordsCount; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    @JsonIgnore
    public boolean isTerminal() {
        return STATUS_COMPLETED.equals(jobStatus) || STATUS_FAILED.equals(jobStatus);
    }
}
