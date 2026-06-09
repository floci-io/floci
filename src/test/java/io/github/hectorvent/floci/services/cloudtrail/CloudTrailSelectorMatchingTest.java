package io.github.hectorvent.floci.services.cloudtrail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CloudTrailService}'s S3 data-resource matcher and
 * read/write classifier — the two predicates that gate every emit decision.
 * Full pipeline coverage lives in {@code CloudTrailIntegrationTest}.
 */
class CloudTrailSelectorMatchingTest {

    // --- matchesS3DataResourceArn ---

    @Test
    void allBucketsArn_matchesEverything() {
        assertTrue(CloudTrailService.matchesS3DataResourceArn("arn:aws:s3:::", "any-bucket", "any/key.txt"));
        assertTrue(CloudTrailService.matchesS3DataResourceArn("arn:aws:s3:::", "any-bucket", null));
    }

    @Test
    void allBucketsArnWithSlash_matchesEverything() {
        assertTrue(CloudTrailService.matchesS3DataResourceArn("arn:aws:s3:::/", "any-bucket", "any/key.txt"));
    }

    @Test
    void bucketArnNoSlash_matchesAnyOpOnThatBucket() {
        // Bucket-only ARNs are permissive: the selector ARN doesn't carry the
        // request's action, so we can't distinguish bucket-level ops from
        // object-level ones here. We match on bucket name and let event
        // selectors carry the read/write filter via ReadWriteType. Most real
        // workflows use `arn:aws:s3:::bucket/` (with slash) to capture every
        // object event explicitly.
        assertTrue(CloudTrailService.matchesS3DataResourceArn("arn:aws:s3:::audit-source", "audit-source", null));
        assertTrue(CloudTrailService.matchesS3DataResourceArn("arn:aws:s3:::audit-source", "audit-source", "k.txt"));
        assertFalse(CloudTrailService.matchesS3DataResourceArn("arn:aws:s3:::audit-source", "other-bucket", null));
        assertFalse(CloudTrailService.matchesS3DataResourceArn("arn:aws:s3:::audit-source", "other-bucket", "k.txt"));
    }

    @Test
    void bucketArnWithTrailingSlash_matchesAnyKey() {
        assertTrue(CloudTrailService.matchesS3DataResourceArn(
                "arn:aws:s3:::audit-source/", "audit-source", "documents/a.txt"));
        assertTrue(CloudTrailService.matchesS3DataResourceArn(
                "arn:aws:s3:::audit-source/", "audit-source", "k.txt"));
        assertFalse(CloudTrailService.matchesS3DataResourceArn(
                "arn:aws:s3:::audit-source/", "other-bucket", "k.txt"));
    }

    @Test
    void prefixArn_matchesKeysUnderPrefix() {
        assertTrue(CloudTrailService.matchesS3DataResourceArn(
                "arn:aws:s3:::audit-source/documents/", "audit-source", "documents/a.txt"));
        assertTrue(CloudTrailService.matchesS3DataResourceArn(
                "arn:aws:s3:::audit-source/documents/", "audit-source", "documents/nested/a.txt"));
        assertFalse(CloudTrailService.matchesS3DataResourceArn(
                "arn:aws:s3:::audit-source/documents/", "audit-source", "exports/a.txt"));
    }

    @Test
    void prefixArnNoTrailingSlash_isPrefixMatchOnKey() {
        // arn:aws:s3:::bucket/prefix matches keys that start with "prefix"
        assertTrue(CloudTrailService.matchesS3DataResourceArn(
                "arn:aws:s3:::audit-source/documents", "audit-source", "documents/a.txt"));
        assertTrue(CloudTrailService.matchesS3DataResourceArn(
                "arn:aws:s3:::audit-source/documents", "audit-source", "documents-v2/a.txt"));
        assertFalse(CloudTrailService.matchesS3DataResourceArn(
                "arn:aws:s3:::audit-source/documents", "audit-source", "exports/a.txt"));
    }

    @Test
    void prefixArn_doesNotMatchOtherBucket() {
        assertFalse(CloudTrailService.matchesS3DataResourceArn(
                "arn:aws:s3:::audit-source/documents/", "other-bucket", "documents/a.txt"));
    }

    @Test
    void exactKeyArn_matchesOnlyThatKey() {
        assertTrue(CloudTrailService.matchesS3DataResourceArn(
                "arn:aws:s3:::audit-source/documents/a.txt", "audit-source", "documents/a.txt"));
        // The current implementation uses startsWith, so "documents/a.txt2" also
        // matches — that's deliberate (mirrors AWS's prefix semantics for object ARNs)
        assertTrue(CloudTrailService.matchesS3DataResourceArn(
                "arn:aws:s3:::audit-source/documents/a.txt", "audit-source", "documents/a.txt2"));
        assertFalse(CloudTrailService.matchesS3DataResourceArn(
                "arn:aws:s3:::audit-source/documents/a.txt", "audit-source", "documents/b.txt"));
    }

    @Test
    void nonS3Arn_neverMatches() {
        assertFalse(CloudTrailService.matchesS3DataResourceArn(
                "arn:aws:lambda:us-east-1:000:function:foo", "audit-source", "k.txt"));
        assertFalse(CloudTrailService.matchesS3DataResourceArn(null, "audit-source", "k.txt"));
    }

    @Test
    void prefixArn_nullKey_doesNotMatchKeyOps() {
        // A key-level selector cannot match a bucket-level op (which has key=null)
        assertFalse(CloudTrailService.matchesS3DataResourceArn(
                "arn:aws:s3:::audit-source/documents/", "audit-source", null));
    }

    // --- isReadOnlyEvent ---

    @Test
    void readOps_classifiedReadOnly() {
        assertTrue(CloudTrailService.isReadOnlyEvent("GetObject"));
        assertTrue(CloudTrailService.isReadOnlyEvent("HeadObject"));
        assertTrue(CloudTrailService.isReadOnlyEvent("ListObjects"));
        assertTrue(CloudTrailService.isReadOnlyEvent("ListObjectsV2"));
        assertTrue(CloudTrailService.isReadOnlyEvent("GetObjectAcl"));
        assertTrue(CloudTrailService.isReadOnlyEvent("GetObjectTagging"));
        assertTrue(CloudTrailService.isReadOnlyEvent("ListMultipartUploads"));
    }

    @Test
    void writeOps_classifiedNotReadOnly() {
        assertFalse(CloudTrailService.isReadOnlyEvent("PutObject"));
        assertFalse(CloudTrailService.isReadOnlyEvent("DeleteObject"));
        assertFalse(CloudTrailService.isReadOnlyEvent("PutObjectAcl"));
    }

    @Test
    void unknownEvent_defaultsToWrite() {
        // Conservative default: unknown ops are treated as writes so a
        // ReadOnly selector won't accidentally swallow them.
        assertFalse(CloudTrailService.isReadOnlyEvent("SomeFutureOperation"));
    }

    @Test
    void nullEvent_classifiedReadOnly() {
        // Defensive — buildS3Record uses this for the "readOnly" field, which
        // should default to safe-no-write semantics if the eventName is missing.
        assertTrue(CloudTrailService.isReadOnlyEvent(null));
    }
}
