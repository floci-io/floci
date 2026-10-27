package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
