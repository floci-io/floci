package io.github.hectorvent.floci.services.cur.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * AWS CUR (Cost and Usage Report) {@code ReportDefinition} record.
 *
 * @see <a href="https://docs.aws.amazon.com/aws-cost-management/latest/APIReference/API_cur_ReportDefinition.html">ReportDefinition</a>
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReportDefinition {

    private String reportName;
    private String timeUnit;        // HOURLY | DAILY | MONTHLY
    private String format;          // textORcsv | Parquet
    private String compression;     // ZIP | GZIP | Parquet
    private List<String> additionalSchemaElements = new ArrayList<>();
    private String s3Bucket;
    private String s3Prefix;
    private String s3Region;
    private List<String> additionalArtifacts = new ArrayList<>();
    private boolean refreshClosedReports;
    private String reportVersioning;    // CREATE_NEW_REPORT | OVERWRITE_REPORT
    private String billingViewArn;
    private Instant createdDate;
    private Instant lastUpdatedDate;
    private String reportStatus;        // SUCCESS | ERROR | PENDING
    /**
     * Snapshot of the account that owned the request that created this report.
     * Used by the background emission scheduler to locate the report under the
     * right account partition without inheriting the scheduler thread's
     * (default) request scope. Not part of the AWS wire response.
     */
    private String ownerAccountId;

    public ReportDefinition() {
    }

    public String getReportName() { return reportName; }
    public void setReportName(String reportName) { this.reportName = reportName; }

    public String getTimeUnit() { return timeUnit; }
    public void setTimeUnit(String timeUnit) { this.timeUnit = timeUnit; }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public String getCompression() { return compression; }
    public void setCompression(String compression) { this.compression = compression; }

    public List<String> getAdditionalSchemaElements() { return additionalSchemaElements; }
    public void setAdditionalSchemaElements(List<String> v) { this.additionalSchemaElements = v; }

    public String getS3Bucket() { return s3Bucket; }
    public void setS3Bucket(String s3Bucket) { this.s3Bucket = s3Bucket; }

    public String getS3Prefix() { return s3Prefix; }
    public void setS3Prefix(String s3Prefix) { this.s3Prefix = s3Prefix; }

    public String getS3Region() { return s3Region; }
    public void setS3Region(String s3Region) { this.s3Region = s3Region; }

    public List<String> getAdditionalArtifacts() { return additionalArtifacts; }
    public void setAdditionalArtifacts(List<String> v) { this.additionalArtifacts = v; }

    public boolean isRefreshClosedReports() { return refreshClosedReports; }
    public void setRefreshClosedReports(boolean v) { this.refreshClosedReports = v; }

    public String getReportVersioning() { return reportVersioning; }
    public void setReportVersioning(String v) { this.reportVersioning = v; }

    public String getBillingViewArn() { return billingViewArn; }
    public void setBillingViewArn(String v) { this.billingViewArn = v; }

    public Instant getCreatedDate() { return createdDate; }
    public void setCreatedDate(Instant v) { this.createdDate = v; }

    public Instant getLastUpdatedDate() { return lastUpdatedDate; }
    public void setLastUpdatedDate(Instant v) { this.lastUpdatedDate = v; }

    public String getReportStatus() { return reportStatus; }
    public void setReportStatus(String v) { this.reportStatus = v; }

    public String getOwnerAccountId() { return ownerAccountId; }
    public void setOwnerAccountId(String v) { this.ownerAccountId = v; }
}
