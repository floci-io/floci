package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

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
                IamEnforcementFilter.mapS3ActionToEventName("s3:GetObject", "GET"));
    }

    @Test
    void getObject_overHead_emitsAsHeadObject() {
        assertEquals("HeadObject",
                IamEnforcementFilter.mapS3ActionToEventName("s3:GetObject", "HEAD"));
    }

    @Test
    void putObject_emitsAsPutObject() {
        assertEquals("PutObject",
                IamEnforcementFilter.mapS3ActionToEventName("s3:PutObject", "PUT"));
    }

    @Test
    void deleteObject_emitsAsDeleteObject() {
        assertEquals("DeleteObject",
                IamEnforcementFilter.mapS3ActionToEventName("s3:DeleteObject", "DELETE"));
    }

    @Test
    void listBucket_emitsAsListObjects() {
        assertEquals("ListObjects",
                IamEnforcementFilter.mapS3ActionToEventName("s3:ListBucket", "GET"));
    }

    @Test
    void listAllMyBuckets_emitsAsListBuckets() {
        assertEquals("ListBuckets",
                IamEnforcementFilter.mapS3ActionToEventName("s3:ListAllMyBuckets", "GET"));
    }

    @Test
    void getObjectAcl_emitsAsGetObjectAcl() {
        assertEquals("GetObjectAcl",
                IamEnforcementFilter.mapS3ActionToEventName("s3:GetObjectAcl", "GET"));
    }

    @Test
    void putObjectAcl_emitsAsPutObjectAcl() {
        assertEquals("PutObjectAcl",
                IamEnforcementFilter.mapS3ActionToEventName("s3:PutObjectAcl", "PUT"));
    }

    @Test
    void objectTagging_emitsCorrectEventNames() {
        assertEquals("GetObjectTagging",
                IamEnforcementFilter.mapS3ActionToEventName("s3:GetObjectTagging", "GET"));
        assertEquals("PutObjectTagging",
                IamEnforcementFilter.mapS3ActionToEventName("s3:PutObjectTagging", "PUT"));
        assertEquals("DeleteObjectTagging",
                IamEnforcementFilter.mapS3ActionToEventName("s3:DeleteObjectTagging", "DELETE"));
    }

    @Test
    void unknownAction_returnsNull() {
        assertNull(IamEnforcementFilter.mapS3ActionToEventName("s3:GetBucketAcl", "GET"));
        assertNull(IamEnforcementFilter.mapS3ActionToEventName("dynamodb:Query", "POST"));
        assertNull(IamEnforcementFilter.mapS3ActionToEventName(null, "GET"));
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
