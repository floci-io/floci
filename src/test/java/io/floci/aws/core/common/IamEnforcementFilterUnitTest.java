package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for the static helpers that drive CloudTrail emission on the
 * IAM-deny path. Full HTTP-level coverage (filter → emit → log file) lives in
 * the SDK compatibility suite where SigV4 signing is available.
 */
class IamEnforcementFilterUnitTest {

    // --- mapS3ActionToEventName ---

    @Test
    void getObject_overGet_emitsAsGetObject() {
        assertEquals("GetObject",
                IamEnforcementFilter.mapS3ActionToEventName("s3:GetObject", "GET", Set.of()));
    }

    @Test
    void getObject_overHead_emitsAsHeadObject() {
        assertEquals("HeadObject",
                IamEnforcementFilter.mapS3ActionToEventName("s3:GetObject", "HEAD", Set.of()));
    }

    @Test
    void putObject_emitsAsPutObject() {
        assertEquals("PutObject",
                IamEnforcementFilter.mapS3ActionToEventName("s3:PutObject", "PUT", Set.of()));
    }

    @Test
    void deleteObject_emitsAsDeleteObject() {
        assertEquals("DeleteObject",
                IamEnforcementFilter.mapS3ActionToEventName("s3:DeleteObject", "DELETE", Set.of()));
    }

    @Test
    void listBucket_emitsAsListObjects() {
        assertEquals("ListObjects",
                IamEnforcementFilter.mapS3ActionToEventName("s3:ListBucket", "GET", Set.of()));
    }

    @Test
    void listAllMyBuckets_emitsAsListBuckets() {
        assertEquals("ListBuckets",
                IamEnforcementFilter.mapS3ActionToEventName("s3:ListAllMyBuckets", "GET", Set.of()));
    }

    @Test
    void getObjectAcl_emitsAsGetObjectAcl() {
        assertEquals("GetObjectAcl",
                IamEnforcementFilter.mapS3ActionToEventName("s3:GetObjectAcl", "GET", Set.of()));
    }

    @Test
    void putObjectAcl_emitsAsPutObjectAcl() {
        assertEquals("PutObjectAcl",
                IamEnforcementFilter.mapS3ActionToEventName("s3:PutObjectAcl", "PUT", Set.of()));
    }

    @Test
    void objectTagging_emitsCorrectEventNames() {
        assertEquals("GetObjectTagging",
                IamEnforcementFilter.mapS3ActionToEventName("s3:GetObjectTagging", "GET", Set.of()));
        assertEquals("PutObjectTagging",
                IamEnforcementFilter.mapS3ActionToEventName("s3:PutObjectTagging", "PUT", Set.of()));
        assertEquals("DeleteObjectTagging",
                IamEnforcementFilter.mapS3ActionToEventName("s3:DeleteObjectTagging", "DELETE", Set.of()));
    }

    @Test
    void versionActions_emitAsTheOperationTheyCheck() {
        assertEquals("GetObject",
                IamEnforcementFilter.mapS3ActionToEventName("s3:GetObjectVersion", "GET", Set.of()));
        assertEquals("HeadObject",
                IamEnforcementFilter.mapS3ActionToEventName("s3:GetObjectVersion", "HEAD", Set.of()));
        assertEquals("DeleteObject",
                IamEnforcementFilter.mapS3ActionToEventName("s3:DeleteObjectVersion", "DELETE", Set.of()));
        assertEquals("GetObjectAcl",
                IamEnforcementFilter.mapS3ActionToEventName("s3:GetObjectVersionAcl", "GET", Set.of()));
        assertEquals("PutObjectAcl",
                IamEnforcementFilter.mapS3ActionToEventName("s3:PutObjectVersionAcl", "PUT", Set.of()));
        assertEquals("GetObjectTagging",
                IamEnforcementFilter.mapS3ActionToEventName("s3:GetObjectVersionTagging", "GET", Set.of()));
        assertEquals("PutObjectTagging",
                IamEnforcementFilter.mapS3ActionToEventName("s3:PutObjectVersionTagging", "PUT", Set.of()));
        assertEquals("DeleteObjectTagging",
                IamEnforcementFilter.mapS3ActionToEventName("s3:DeleteObjectVersionTagging", "DELETE", Set.of()));
    }

    @Test
    void extraActionChecks_emitAsTheOperationTheyRefuse() {
        assertEquals("PutObjectRetention",
                IamEnforcementFilter.mapS3ActionToEventName("s3:PutObjectRetention", "PUT", Set.of()));
        assertEquals("GetObjectAttributes",
                IamEnforcementFilter.mapS3ActionToEventName("s3:GetObjectAttributes", "GET", Set.of()));
        assertEquals("GetObjectAttributes",
                IamEnforcementFilter.mapS3ActionToEventName("s3:GetObjectVersionAttributes", "GET", Set.of()));
    }

    @Test
    void postOperations_areNamedByTheSubResourceThatChoseTheirAction() {
        // CreateMultipartUpload and CompleteMultipartUpload are both checked as s3:PutObject, and
        // SelectObjectContent as s3:GetObject, so the sub-resource tells which operation ran.
        assertEquals("CreateMultipartUpload",
                IamEnforcementFilter.mapS3ActionToEventName("s3:PutObject", "POST", Set.of("uploads")));
        assertEquals("CompleteMultipartUpload",
                IamEnforcementFilter.mapS3ActionToEventName("s3:PutObject", "POST", Set.of("uploadId")));
        // S3Controller runs ?uploads ahead of an uploadId, so a request naming both creates an upload.
        assertEquals("CreateMultipartUpload",
                IamEnforcementFilter.mapS3ActionToEventName("s3:PutObject", "POST", Set.of("uploads", "uploadId")));
        assertEquals("RestoreObject",
                IamEnforcementFilter.mapS3ActionToEventName("s3:RestoreObject", "POST", Set.of("restore")));
        assertEquals("SelectObjectContent", IamEnforcementFilter.mapS3ActionToEventName("s3:GetObject", "POST",
                Set.of("select", "select-type")));
        assertEquals("SelectObjectContent", IamEnforcementFilter.mapS3ActionToEventName("s3:GetObjectVersion",
                "POST", Set.of("select", "select-type", "versionId")));
    }

    @Test
    void postOperations_withoutTheirSubResource_returnNullRatherThanTheWrongName() {
        assertNull(IamEnforcementFilter.mapS3ActionToEventName("s3:PutObject", "POST", Set.of()));
        assertNull(IamEnforcementFilter.mapS3ActionToEventName("s3:GetObject", "POST", Set.of()));
        assertNull(IamEnforcementFilter.mapS3ActionToEventName("s3:RestoreObject", "POST", Set.of()));
        assertNull(IamEnforcementFilter.mapS3ActionToEventName("s3:DeleteObject", "POST", Set.of("delete")));
    }

    @Test
    void unknownAction_returnsNull() {
        assertNull(IamEnforcementFilter.mapS3ActionToEventName("s3:GetBucketAcl", "GET", Set.of()));
        assertNull(IamEnforcementFilter.mapS3ActionToEventName("dynamodb:Query", "POST", Set.of()));
        assertNull(IamEnforcementFilter.mapS3ActionToEventName(null, "GET", Set.of()));
    }

    // --- parseS3Resource ---

    @Test
    void parseObjectArn_splitsBucketAndKey() {
        assertArrayEquals(new String[] { "audit-source", "documents/hello.txt" },
                IamEnforcementFilter.parseS3Resource("arn:aws:s3:::audit-source/documents/hello.txt"));
    }

    @Test
    void parseBucketArn_returnsBucketWithNullKey() {
        assertArrayEquals(new String[] { "audit-source", null },
                IamEnforcementFilter.parseS3Resource("arn:aws:s3:::audit-source"));
    }

    @Test
    void parseBucketArnWithTrailingSlash_returnsBucketWithNullKey() {
        assertArrayEquals(new String[] { "audit-source", null },
                IamEnforcementFilter.parseS3Resource("arn:aws:s3:::audit-source/"));
    }

    @Test
    void parseKeyWithSlashes_keepsFullPath() {
        assertArrayEquals(new String[] { "b", "a/b/c.txt" },
                IamEnforcementFilter.parseS3Resource("arn:aws:s3:::b/a/b/c.txt"));
    }

    @Test
    void parseObjectArn_acceptsEveryPartition() {
        assertArrayEquals(new String[] { "audit-source", "documents/hello.txt" },
                IamEnforcementFilter.parseS3Resource("arn:aws-cn:s3:::audit-source/documents/hello.txt"));
        assertArrayEquals(new String[] { "audit-source", null },
                IamEnforcementFilter.parseS3Resource("arn:aws-us-gov:s3:::audit-source"));
        assertArrayEquals(new String[] { "b", "k" },
                IamEnforcementFilter.parseS3Resource("arn:aws-iso-b:s3:::b/k"));
    }

    @Test
    void parseStarArn_returnsNulls() {
        assertArrayEquals(new String[] { null, null },
                IamEnforcementFilter.parseS3Resource("arn:aws:s3:::*"));
    }

    @Test
    void parseEmptyArn_returnsNulls() {
        assertArrayEquals(new String[] { null, null },
                IamEnforcementFilter.parseS3Resource("arn:aws:s3:::"));
    }

    @Test
    void parseNonS3Arn_returnsNulls() {
        assertArrayEquals(new String[] { null, null },
                IamEnforcementFilter.parseS3Resource("arn:aws:lambda:us-east-1:000000000000:function:foo"));
        assertArrayEquals(new String[] { null, null },
                IamEnforcementFilter.parseS3Resource(null));
    }
}
