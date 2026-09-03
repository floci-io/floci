package io.github.hectorvent.floci.services.cloudtrail;

import io.github.hectorvent.floci.services.cloudtrail.model.AdvancedFieldSelector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CloudTrailService}'s advanced-selector field matcher, the building
 * block for AdvancedEventSelectors evaluation. Full capture/exclusion pipeline coverage lives
 * in {@code CloudTrailAdvancedSelectorsIntegrationTest}.
 */
class CloudTrailAdvancedSelectorMatchingTest {

    private static final String OTHER_BUCKET_ARN = "arn:aws:s3:::other-bucket/key";
    private static final String LOG_BUCKET_ARN = "arn:aws:s3:::log-bucket/some-key";
    private static final String OBJECT_TYPE = "AWS::S3::Object";
    private static final String BUCKET_TYPE = "AWS::S3::Bucket";

    @Test
    void eventCategoryEquals_matchesDataEvents() {
        AdvancedFieldSelector fs = new AdvancedFieldSelector(
                "eventCategory", List.of("Data"), null, null, null, null, null);
        assertTrue(CloudTrailService.matchesAdvancedFieldSelector(fs, OTHER_BUCKET_ARN, OBJECT_TYPE));
    }

    @Test
    void eventCategoryEquals_rejectsNonDataValue() {
        AdvancedFieldSelector fs = new AdvancedFieldSelector(
                "eventCategory", List.of("Management"), null, null, null, null, null);
        assertFalse(CloudTrailService.matchesAdvancedFieldSelector(fs, OTHER_BUCKET_ARN, OBJECT_TYPE));
    }

    @Test
    void resourcesTypeEquals_matchesS3Object() {
        AdvancedFieldSelector fs = new AdvancedFieldSelector(
                "resources.type", List.of("AWS::S3::Object"), null, null, null, null, null);
        assertTrue(CloudTrailService.matchesAdvancedFieldSelector(fs, OTHER_BUCKET_ARN, OBJECT_TYPE));
    }

    @Test
    void resourcesTypeEquals_rejectsBucketLevelOperation() {
        // Bucket-level calls (ListObjects, no object key) are AWS::S3::Bucket resources:
        // an AWS::S3::Object DataResource selector must never match them.
        AdvancedFieldSelector fs = new AdvancedFieldSelector(
                "resources.type", List.of("AWS::S3::Object"), null, null, null, null, null);
        assertFalse(CloudTrailService.matchesAdvancedFieldSelector(fs, "arn:aws:s3:::other-bucket", BUCKET_TYPE));
    }

    @Test
    void resourcesArnNotStartsWith_excludesMatchingPrefix() {
        AdvancedFieldSelector fs = new AdvancedFieldSelector(
                "resources.ARN", null, null, null, List.of("arn:aws:s3:::log-bucket/"), null, null);
        assertFalse(CloudTrailService.matchesAdvancedFieldSelector(fs, LOG_BUCKET_ARN, OBJECT_TYPE));
        assertTrue(CloudTrailService.matchesAdvancedFieldSelector(fs, OTHER_BUCKET_ARN, OBJECT_TYPE));
    }

    @Test
    void resourcesArnStartsWith_onlyMatchesGivenPrefix() {
        AdvancedFieldSelector fs = new AdvancedFieldSelector(
                "resources.ARN", null, null, List.of("arn:aws:s3:::log-bucket/"), null, null, null);
        assertTrue(CloudTrailService.matchesAdvancedFieldSelector(fs, LOG_BUCKET_ARN, OBJECT_TYPE));
        assertFalse(CloudTrailService.matchesAdvancedFieldSelector(fs, OTHER_BUCKET_ARN, OBJECT_TYPE));
    }

    @Test
    void resourcesArnEndsWithAndNotEndsWith() {
        AdvancedFieldSelector endsWith = new AdvancedFieldSelector(
                "resources.ARN", null, null, null, null, List.of("some-key"), null);
        assertTrue(CloudTrailService.matchesAdvancedFieldSelector(endsWith, LOG_BUCKET_ARN, OBJECT_TYPE));

        AdvancedFieldSelector notEndsWith = new AdvancedFieldSelector(
                "resources.ARN", null, null, null, null, null, List.of("some-key"));
        assertFalse(CloudTrailService.matchesAdvancedFieldSelector(notEndsWith, LOG_BUCKET_ARN, OBJECT_TYPE));
    }

    @Test
    void unsupportedField_neverMatches() {
        AdvancedFieldSelector fs = new AdvancedFieldSelector(
                "readOnly", List.of("true"), null, null, null, null, null);
        assertFalse(CloudTrailService.matchesAdvancedFieldSelector(fs, OTHER_BUCKET_ARN, OBJECT_TYPE));
    }
}
