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
        @JsonProperty("IsOrganizationTrail") boolean isOrganizationTrail) {
}
