package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * Represents a VPC Flow Log configuration created via {@code CreateFlowLogs}.
 *
 * <p>The emulator delivers synthetic VPC flow-log records to an S3 bucket in the
 * exact record format and S3 key layout that AWS uses
 * ({@code AWSLogs/{account}/vpcflowlogs/{region}/...}, gzipped, space-delimited
 * default fields). This lets any tool that consumes VPC flow logs — log
 * pipelines, SIEMs, traffic-analysis software — be exercised locally against
 * realistic data with no real cloud spend.</p>
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class FlowLog {

    private String flowLogId;
    private String resourceId;       // the VPC (or subnet/eni) the flow log is attached to
    private String resourceType;     // VPC | Subnet | NetworkInterface
    private String trafficType;      // ALL | ACCEPT | REJECT
    private String logDestinationType; // s3 | cloud-watch-logs
    private String logDestination;   // S3 bucket ARN, e.g. arn:aws:s3:::flow-logs-bucket
    private String bucketName;       // resolved bucket name from the ARN
    private String logFormat;        // optional custom format string (default format used if null)
    private int maxAggregationInterval = 600; // seconds (60 or 600)
    private String region;
    private String accountId;
    private String flowLogStatus = "ACTIVE";
    private String deliverLogsStatus = "SUCCESS";
    private Instant creationTime = Instant.now();

    public String getFlowLogId() { return flowLogId; }
    public void setFlowLogId(String flowLogId) { this.flowLogId = flowLogId; }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public String getTrafficType() { return trafficType; }
    public void setTrafficType(String trafficType) { this.trafficType = trafficType; }

    public String getLogDestinationType() { return logDestinationType; }
    public void setLogDestinationType(String logDestinationType) { this.logDestinationType = logDestinationType; }

    public String getLogDestination() { return logDestination; }
    public void setLogDestination(String logDestination) { this.logDestination = logDestination; }

    public String getBucketName() { return bucketName; }
    public void setBucketName(String bucketName) { this.bucketName = bucketName; }

    public String getLogFormat() { return logFormat; }
    public void setLogFormat(String logFormat) { this.logFormat = logFormat; }

    public int getMaxAggregationInterval() { return maxAggregationInterval; }
    public void setMaxAggregationInterval(int maxAggregationInterval) { this.maxAggregationInterval = maxAggregationInterval; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getFlowLogStatus() { return flowLogStatus; }
    public void setFlowLogStatus(String flowLogStatus) { this.flowLogStatus = flowLogStatus; }

    public String getDeliverLogsStatus() { return deliverLogsStatus; }
    public void setDeliverLogsStatus(String deliverLogsStatus) { this.deliverLogsStatus = deliverLogsStatus; }

    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }
}
