package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.hectorvent.floci.services.cloudtrail.model.EventSelector;
import io.github.hectorvent.floci.services.cloudtrail.model.Trail;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CloudTrailEntry(
        Trail trail,
        List<EventSelector> selectors,
        boolean logging,
        Long startLoggingTime,
        Long stopLoggingTime,
        Map<String, String> tags) {

    // Not Map.copyOf: AWS allows a tag with a null value (AddTags omitting "Value"),
    // and Map.copyOf/Map.of reject null values.
    public CloudTrailEntry {
        tags = tags == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tags));
    }

    public CloudTrailEntry withTrail(Trail updated) {
        return new CloudTrailEntry(updated, selectors, logging, startLoggingTime, stopLoggingTime, tags);
    }

    public CloudTrailEntry withSelectors(List<EventSelector> updated, boolean hasCustomSelectors) {
        Trail updatedTrail = new Trail(
                trail.name(), trail.trailArn(), trail.s3BucketName(), trail.s3KeyPrefix(),
                trail.snsTopicArn(), trail.includeGlobalServiceEvents(), trail.isMultiRegionTrail(),
                trail.homeRegion(), trail.logFileValidationEnabled(), hasCustomSelectors,
                trail.hasInsightSelectors(), trail.isOrganizationTrail());
        return new CloudTrailEntry(updatedTrail, updated, logging, startLoggingTime, stopLoggingTime, tags);
    }

    public CloudTrailEntry startLogging(long time) {
        return new CloudTrailEntry(trail, selectors, true, time, stopLoggingTime, tags);
    }

    public CloudTrailEntry stopLogging(long time) {
        return new CloudTrailEntry(trail, selectors, false, startLoggingTime, time, tags);
    }

    public CloudTrailEntry withTags(Map<String, String> updated) {
        return new CloudTrailEntry(trail, selectors, logging, startLoggingTime, stopLoggingTime, updated);
    }

    /** Returns a mutable copy suitable for merging in new tags. */
    public Map<String, String> mutableTags() {
        return new LinkedHashMap<>(tags);
    }
}
