package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the static helper that reads the bucket and key of a denied S3 request's resource
 * for its CloudTrail event. Full HTTP-level coverage (filter → emit → log file) lives in
 * the SDK compatibility suite where SigV4 signing is available.
 */
class IamEnforcementFilterUnitTest {

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
