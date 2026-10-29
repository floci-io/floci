package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3UriTest {

    @Test
    void splitsTheBucketFromTheKey() {
        assertEquals(new S3Uri("logs", "emr/j-1/steps/s-1/stderr.gz"),
                S3Uri.parse("s3://logs/emr/j-1/steps/s-1/stderr.gz"));
    }

    @Test
    void keepsAPrefixsTrailingSlash() {
        assertEquals(new S3Uri("logs", "emr/"), S3Uri.parse("s3://logs/emr/"));
    }

    @Test
    void aBucketAloneHasAnEmptyKey() {
        assertEquals(new S3Uri("logs", ""), S3Uri.parse("s3://logs"));
        assertEquals(new S3Uri("logs", ""), S3Uri.parse("s3://logs/"));
    }

    @Test
    void refusesAnotherSchemeOrNoBucket() {
        assertThrows(IllegalArgumentException.class, () -> S3Uri.parse("https://logs/emr/"));
        assertThrows(IllegalArgumentException.class, () -> S3Uri.parse("s3:///emr/"));
        assertThrows(IllegalArgumentException.class, () -> S3Uri.parse(null));
    }

    @Test
    void hadoopsSchemesNameTheSameBuckets() {
        assertEquals(new S3Uri("logs", "emr/"), S3Uri.parseAnyScheme("s3n://logs/emr/"));
        assertEquals(new S3Uri("logs", "emr/"), S3Uri.parseAnyScheme("s3a://logs/emr/"));
        assertEquals(new S3Uri("logs", "emr/"), S3Uri.parseAnyScheme("s3://logs/emr/"));
        assertTrue(S3Uri.isS3("s3n://logs/emr/"));
        assertFalse(S3Uri.isS3("file:/usr/share/aws/emr/scripts/install"));
        assertFalse(S3Uri.isS3(null));
        assertThrows(IllegalArgumentException.class, () -> S3Uri.parseAnyScheme("hdfs://logs/emr/"));
    }
}
