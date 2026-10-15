package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Since April 2023 AWS creates every bucket with all four Block Public Access settings on, so
 * GetPublicAccessBlock on a bucket nobody configured returns them rather than a 404. Only a bucket
 * whose configuration was deleted answers NoSuchPublicAccessBlockConfiguration.
 */
class S3DefaultPublicAccessBlockTest {

    @TempDir
    Path tempDir;

    private S3Service s3Service;

    @BeforeEach
    void setUp() {
        s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), tempDir.resolve("s3"), false);
    }

    @Test
    void newBucketReportsAllFourSettingsBlocked() {
        s3Service.createBucket("fresh", "us-east-1");

        S3BlockPublicAccessSettings settings = S3BlockPublicAccessSettings.parse(s3Service.getPublicAccessBlock("fresh"));

        assertEquals(new S3BlockPublicAccessSettings(true, true, true, true), settings);
    }

    @Test
    void deletingTheConfigurationLeavesNothingToRead() {
        s3Service.createBucket("deleted", "us-east-1");
        s3Service.deletePublicAccessBlock("deleted");

        AwsException e = assertThrows(AwsException.class, () -> s3Service.getPublicAccessBlock("deleted"));

        assertEquals("NoSuchPublicAccessBlockConfiguration", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void anExplicitConfigurationReplacesTheDefault() {
        s3Service.createBucket("custom", "us-east-1");
        String xml = "<PublicAccessBlockConfiguration><BlockPublicAcls>false</BlockPublicAcls>"
                + "<IgnorePublicAcls>false</IgnorePublicAcls><BlockPublicPolicy>false</BlockPublicPolicy>"
                + "<RestrictPublicBuckets>false</RestrictPublicBuckets></PublicAccessBlockConfiguration>";
        s3Service.putPublicAccessBlock("custom", xml);

        assertEquals(xml, s3Service.getPublicAccessBlock("custom"));
    }

    @Test
    void theDefaultIsReportedNotStoredSoEnforcementIsUnchanged() {
        Bucket bucket = s3Service.createBucket("report-only", "us-east-1");

        assertEquals(null, bucket.getPublicAccessBlockConfiguration());
    }

    @Test
    void repeatingCreateBucketKeepsAChangedConfiguration() {
        s3Service.createBucket("again", "us-east-1");
        s3Service.deletePublicAccessBlock("again");
        s3Service.createBucket("again", "us-east-1");

        assertThrows(AwsException.class, () -> s3Service.getPublicAccessBlock("again"));
    }
}
