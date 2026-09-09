package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.hectorvent.floci.services.cloudtrail.model.AdvancedEventSelector;
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
        List<AdvancedEventSelector> advancedSelectors,
        boolean logging,
        Long startLoggingTime,
        Long stopLoggingTime,
        Map<String, String> tags,
        Long latestDeliveryTime,
        String latestDeliveryError) {

    public CloudTrailEntry(Trail trail, List<EventSelector> selectors,
                           List<AdvancedEventSelector> advancedSelectors, boolean logging,
                           Long startLoggingTime, Long stopLoggingTime,
                           Map<String, String> tags) {
        this(trail, selectors, advancedSelectors, logging, startLoggingTime, stopLoggingTime,
                tags, null, null);
    }

    // Not Map.copyOf: AWS allows a tag with a null value (AddTags omitting "Value"),
    // and Map.copyOf/Map.of reject null values.
    public CloudTrailEntry {
        tags = tags == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tags));
    }

    public CloudTrailEntry withTrail(Trail updated) {
        return new CloudTrailEntry(updated, selectors, advancedSelectors, logging,
                startLoggingTime, stopLoggingTime, tags,
                latestDeliveryTime, latestDeliveryError);
    }

    /** Sets basic selectors, clearing any advanced selectors — a trail holds one kind or the other. */
    public CloudTrailEntry withSelectors(List<EventSelector> updated, boolean hasCustomSelectors) {
        Trail updatedTrail = withHasCustomSelectors(hasCustomSelectors);
        return new CloudTrailEntry(updatedTrail, updated, List.of(), logging,
                startLoggingTime, stopLoggingTime, tags,
                latestDeliveryTime, latestDeliveryError);
    }

    /** Sets advanced selectors, clearing any basic selectors — a trail holds one kind or the other. */
    public CloudTrailEntry withAdvancedSelectors(List<AdvancedEventSelector> updated, boolean hasCustomSelectors) {
        Trail updatedTrail = withHasCustomSelectors(hasCustomSelectors);
        return new CloudTrailEntry(updatedTrail, List.of(), updated, logging,
                startLoggingTime, stopLoggingTime, tags,
                latestDeliveryTime, latestDeliveryError);
    }

    public CloudTrailEntry withTags(Map<String, String> updated) {
        return new CloudTrailEntry(trail, selectors, advancedSelectors, logging,
                startLoggingTime, stopLoggingTime, updated,
                latestDeliveryTime, latestDeliveryError);
    }

    public CloudTrailEntry startLogging(long time) {
        return new CloudTrailEntry(trail, selectors, advancedSelectors, true, time,
                stopLoggingTime, tags,
                latestDeliveryTime, latestDeliveryError);
    }

    public CloudTrailEntry stopLogging(long time) {
        return new CloudTrailEntry(trail, selectors, advancedSelectors, false, startLoggingTime,
                time, tags,
                latestDeliveryTime, latestDeliveryError);
    }

    public CloudTrailEntry withDeliveryFailure(String error) {
        return new CloudTrailEntry(trail, selectors, advancedSelectors, logging,
                startLoggingTime, stopLoggingTime, tags, latestDeliveryTime, error);
    }

    public CloudTrailEntry withDeliverySuccess(long time) {
        return new CloudTrailEntry(trail, selectors, advancedSelectors, logging,
                startLoggingTime, stopLoggingTime, tags, time, null);
    }

    private Trail withHasCustomSelectors(boolean hasCustomSelectors) {
        return new Trail(
                trail.name(), trail.trailArn(), trail.s3BucketName(), trail.s3KeyPrefix(),
                trail.snsTopicArn(), trail.includeGlobalServiceEvents(), trail.isMultiRegionTrail(),
                trail.homeRegion(), trail.logFileValidationEnabled(), hasCustomSelectors,
                trail.hasInsightSelectors(), trail.isOrganizationTrail());
    }

    /** Returns a mutable copy suitable for merging in new tags. */
    public Map<String, String> mutableTags() {
        return new LinkedHashMap<>(tags);
    }
}
