package io.github.hectorvent.floci.services.cloudtrail.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Trail(
        @JsonProperty("Name") String name,
        @JsonProperty("TrailARN") String trailArn,
        @JsonProperty("S3BucketName") String s3BucketName,
        @JsonProperty("S3KeyPrefix") String s3KeyPrefix,
        @JsonProperty("SnsTopicARN") String snsTopicArn,
        @JsonProperty("IncludeGlobalServiceEvents") boolean includeGlobalServiceEvents,
        @JsonProperty("IsMultiRegionTrail") boolean isMultiRegionTrail,
        @JsonProperty("HomeRegion") String homeRegion,
        @JsonProperty("LogFileValidationEnabled") boolean logFileValidationEnabled,
        @JsonProperty("HasCustomEventSelectors") boolean hasCustomEventSelectors,
        @JsonProperty("HasInsightSelectors") boolean hasInsightSelectors,
        @JsonProperty("IsOrganizationTrail") boolean isOrganizationTrail,
        @JsonProperty("KmsKeyId") String kmsKeyId,
        @JsonProperty("CloudWatchLogsLogGroupArn") String cloudWatchLogsLogGroupArn,
        @JsonProperty("CloudWatchLogsRoleArn") String cloudWatchLogsRoleArn) {

    /** A trail without encryption or CloudWatch Logs delivery. */
    public Trail(String name, String trailArn, String s3BucketName, String s3KeyPrefix, String snsTopicArn,
                 boolean includeGlobalServiceEvents, boolean isMultiRegionTrail, String homeRegion,
                 boolean logFileValidationEnabled, boolean hasCustomEventSelectors, boolean hasInsightSelectors,
                 boolean isOrganizationTrail) {
        this(name, trailArn, s3BucketName, s3KeyPrefix, snsTopicArn, includeGlobalServiceEvents, isMultiRegionTrail,
                homeRegion, logFileValidationEnabled, hasCustomEventSelectors, hasInsightSelectors,
                isOrganizationTrail, null, null, null);
    }
}
