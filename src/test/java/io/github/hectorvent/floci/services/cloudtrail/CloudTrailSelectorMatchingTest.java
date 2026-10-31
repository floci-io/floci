package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudtrail.model.DataResource;
import io.github.hectorvent.floci.services.cloudtrail.model.EventSelector;
import io.github.hectorvent.floci.services.iam.IamService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CloudTrailService}'s S3 data-resource matcher and
 * read/write classifier — the two predicates that gate every emit decision.
 * Full pipeline coverage lives in {@code CloudTrailIntegrationTest}.
 */
class CloudTrailSelectorMatchingTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    // --- matchesS3DataResourceArn ---

    @Test
    void allBucketsArn_matchesEverything() {
        assertTrue(CloudTrailService.matchesS3DataResourceArn("arn:aws:s3:::", "any-bucket", "any/key.txt"));
        assertTrue(CloudTrailService.matchesS3DataResourceArn("arn:aws:s3:::", "any-bucket", null));
    }

    @Test
    void chinaAndGovCloudArns_matchLikeCommercialOnes() {
        assertTrue(CloudTrailService.matchesS3DataResourceArn("arn:aws-cn:s3", "any-bucket", "any/key.txt"));
        assertTrue(CloudTrailService.matchesS3DataResourceArn("arn:aws-cn:s3:::", "any-bucket", null));
        assertTrue(CloudTrailService.matchesS3DataResourceArn("arn:aws-cn:s3:::b/logs/", "b", "logs/1.txt"));
        assertFalse(CloudTrailService.matchesS3DataResourceArn("arn:aws-us-gov:s3:::b/logs/", "b", "other/1.txt"));
        assertFalse(CloudTrailService.matchesS3DataResourceArn("arn:aws-us-gov:s3:::other/", "b", "logs/1.txt"));
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
    void bareS3Arn_matchesEverything() {
        // "arn:aws:s3" without ":::" is the AWS Console / CLI "all objects" shorthand
        assertTrue(CloudTrailService.matchesS3DataResourceArn("arn:aws:s3", "any-bucket", "any/key.txt"));
        assertTrue(CloudTrailService.matchesS3DataResourceArn("arn:aws:s3", "any-bucket", null));
    }

    @Test
    void wildcardBucketArn_matchesAnyBucket() {
        // arn:aws:s3:::* matches all buckets (moto-style)
        assertTrue(CloudTrailService.matchesS3DataResourceArn("arn:aws:s3:::*", "any-bucket", null));
        assertTrue(CloudTrailService.matchesS3DataResourceArn("arn:aws:s3:::*", "another-bucket", "k.txt"));
    }

    @Test
    void wildcardObjectArn_matchesAnyNonNullKey() {
        // arn:aws:s3:::*/* matches all objects across all buckets (value moto uses in its tests)
        assertTrue(CloudTrailService.matchesS3DataResourceArn("arn:aws:s3:::*/*", "any-bucket", "k.txt"));
        assertTrue(CloudTrailService.matchesS3DataResourceArn("arn:aws:s3:::*/*", "another-bucket", "a/b/c.json"));
        assertFalse(CloudTrailService.matchesS3DataResourceArn("arn:aws:s3:::*/*", "any-bucket", null));
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
        assertTrue(CloudTrailService.isReadOnlyEvent("GetObjectAttributes"));
        assertTrue(CloudTrailService.isReadOnlyEvent("SelectObjectContent"));
    }

    @Test
    void writeOps_classifiedNotReadOnly() {
        assertFalse(CloudTrailService.isReadOnlyEvent("PutObject"));
        assertFalse(CloudTrailService.isReadOnlyEvent("DeleteObject"));
        assertFalse(CloudTrailService.isReadOnlyEvent("PutObjectAcl"));
        assertFalse(CloudTrailService.isReadOnlyEvent("PutObjectRetention"));
        assertFalse(CloudTrailService.isReadOnlyEvent("CreateMultipartUpload"));
        assertFalse(CloudTrailService.isReadOnlyEvent("CompleteMultipartUpload"));
        assertFalse(CloudTrailService.isReadOnlyEvent("RestoreObject"));
    }

    // --- trail matching ---

    @ParameterizedTest
    @CsvSource({
            "us-east-1,us-east-1,us-east-1,false,true",
            "us-east-1,us-east-1,eu-west-1,false,false",
            "us-east-1,us-east-1,eu-west-1,true,true",
            "us-east-1,us-east-1,cn-north-1,true,false",
            "us-east-1,us-east-1,us-gov-west-1,true,false",
            "cn-north-1,cn-north-1,cn-northwest-1,true,true",
            "cn-north-1,cn-north-1,us-east-1,true,false",
            "us-gov-west-1,us-gov-west-1,us-gov-east-1,true,true",
            "us-gov-west-1,us-gov-west-1,us-east-1,true,false",
            "cn-north-1,cn-north-1,xx-nowhere-9,true,true",
            "cn-north-1,xx-nowhere-9,cn-northwest-1,true,true",
            "cn-north-1,xx-nowhere-9,us-east-1,true,false",
            "us-gov-west-1,us-gov-west-1,xx-nowhere-9,true,true",
            "us-gov-west-1,xx-nowhere-9,us-gov-east-1,true,true",
            "us-gov-west-1,xx-nowhere-9,cn-north-1,true,false"
    })
    void multiRegionTrailsMatchOnlyWithinTheirPartition(String defaultRegion, String homeRegion, String eventRegion,
                                                       boolean multiRegion, boolean expected) {
        CloudTrailService service = cloudTrailService(defaultRegion);
        service.createTrail(homeRegion, "partition-trail", "trail-logs", null, null,
                false, multiRegion, false, false);
        service.putEventSelectors(homeRegion, "partition-trail", List.of(new EventSelector("All", false,
                List.of(new DataResource("AWS::S3::Object", List.of("arn:aws:s3:::source/"))), List.of())));
        service.startLogging(homeRegion, "partition-trail");

        service.emitS3DataEvent(CloudTrailService.S3EventInput.builder()
                .region(eventRegion).eventName("PutObject").bucketName("source").key("example.txt").build());

        assertEquals(expected ? 1 : 0, service.pendingRecordCount(
                new CloudTrailService.TrailKey(homeRegion, "partition-trail", eventRegion)));
    }

    // --- a ReadOnly trail ---

    /**
     * A denial is recorded under the name of the operation refused, so a read IAM denies reaches a
     * ReadOnly trail and is marked read-only, while a denied write stays out of it.
     */
    @Test
    void readOnlyTrail_keepsDeniedReadsAsReadOnly_andLeavesOutDeniedWrites() {
        CloudTrailService service = cloudTrailService(REGION);
        service.createTrail(REGION, "reads", "trail-logs", null, null, true, false, false, false);
        service.putEventSelectors(REGION, "reads", List.of(new EventSelector("ReadOnly", false,
                List.of(new DataResource("AWS::S3::Object", List.of("arn:aws:s3:::source/"))), null)));
        service.startLogging(REGION, "reads");

        for (String operation : List.of("SelectObjectContent", "GetObjectAttributes", "CreateMultipartUpload")) {
            service.emitS3DataEvent(CloudTrailService.S3EventInput.builder()
                    .region(REGION)
                    .eventName(operation)
                    .bucketName("source")
                    .key("data.csv")
                    .errorCode("AccessDenied")
                    .errorMessage("Access Denied")
                    .build());
        }

        List<ObjectNode> records = service.drainPendingRecords(
                new CloudTrailService.TrailKey(REGION, "reads", REGION));
        assertEquals(List.of("SelectObjectContent", "GetObjectAttributes"),
                records.stream().map(record -> record.path("eventName").asText()).toList());
        for (ObjectNode record : records) {
            assertTrue(record.path("readOnly").asBoolean(), record::toString);
        }
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

    private CloudTrailService cloudTrailService(String defaultRegion) {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenReturn(AccountAwareStorageBackend.inMemory(ACCOUNT));
        return new CloudTrailService(storageFactory, new RegionResolver(defaultRegion, ACCOUNT),
                mock(IamService.class), new ObjectMapper());
    }
}
