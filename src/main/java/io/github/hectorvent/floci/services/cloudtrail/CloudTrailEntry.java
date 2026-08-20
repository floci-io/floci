package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.hectorvent.floci.services.cloudtrail.model.EventSelector;
import io.github.hectorvent.floci.services.cloudtrail.model.Trail;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CloudTrailEntry(
        Trail trail,
        List<EventSelector> selectors,
        boolean logging,
        Long startLoggingTime,
        Long stopLoggingTime) {

    public CloudTrailEntry withTrail(Trail updated) {
        return new CloudTrailEntry(updated, selectors, logging, startLoggingTime, stopLoggingTime);
    }

    public CloudTrailEntry withSelectors(List<EventSelector> updated, boolean hasCustomSelectors) {
        Trail updatedTrail = new Trail(
                trail.name(), trail.trailArn(), trail.s3BucketName(), trail.s3KeyPrefix(),
                trail.snsTopicArn(), trail.includeGlobalServiceEvents(), trail.isMultiRegionTrail(),
                trail.homeRegion(), trail.logFileValidationEnabled(), hasCustomSelectors,
                trail.hasInsightSelectors(), trail.isOrganizationTrail());
        return new CloudTrailEntry(updatedTrail, updated, logging, startLoggingTime, stopLoggingTime);
    }

    public CloudTrailEntry startLogging(long time) {
        return new CloudTrailEntry(trail, selectors, true, time, stopLoggingTime);
    }

    public CloudTrailEntry stopLogging(long time) {
        return new CloudTrailEntry(trail, selectors, false, startLoggingTime, time);
    }
}
