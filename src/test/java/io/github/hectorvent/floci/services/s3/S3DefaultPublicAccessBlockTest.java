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
    void theDefaultIsReportedNotStored() {
        Bucket bucket = s3Service.createBucket("report-only", "us-east-1");

        assertEquals(null, bucket.getPublicAccessBlockConfiguration());
    }

    @Test
    void aPublicPolicyIsRefusedOnABucketNobodyConfigured() {
        s3Service.createBucket("fresh-policy", "us-east-1");

        AwsException e = assertThrows(AwsException.class,
                () -> s3Service.putBucketPolicy("fresh-policy", publicPolicy("fresh-policy")));

        assertEquals("AccessDenied", e.getErrorCode());
        assertThrows(AwsException.class, () -> s3Service.getBucketPolicy("fresh-policy"));
    }

    @Test
    void aPolicyLimitedToTheAccountIsAcceptedOnAFreshBucket() {
        s3Service.createBucket("private-policy", "us-east-1");

        s3Service.putBucketPolicy("private-policy", policyFor("{\"AWS\":\"arn:aws:iam::000000000000:root\"}",
                "private-policy"));

        assertEquals(true, s3Service.getBucketPolicy("private-policy").contains("root"));
    }

    @Test
    void aPublicPolicyIsAcceptedOnceTheConfigurationIsExplicitlyOff() {
        s3Service.createBucket("explicit-off", "us-east-1");
        s3Service.putPublicAccessBlock("explicit-off", "<PublicAccessBlockConfiguration>"
                + "<BlockPublicAcls>false</BlockPublicAcls><IgnorePublicAcls>false</IgnorePublicAcls>"
                + "<BlockPublicPolicy>false</BlockPublicPolicy><RestrictPublicBuckets>false</RestrictPublicBuckets>"
                + "</PublicAccessBlockConfiguration>");

        s3Service.putBucketPolicy("explicit-off", publicPolicy("explicit-off"));

        assertEquals(true, s3Service.getBucketPolicy("explicit-off").contains("GetObject"));
    }

    @Test
    void aPublicPolicyIsAcceptedOnceTheConfigurationIsDeleted() {
        s3Service.createBucket("deleted-policy", "us-east-1");
        s3Service.deletePublicAccessBlock("deleted-policy");

        s3Service.putBucketPolicy("deleted-policy", publicPolicy("deleted-policy"));

        assertEquals(true, s3Service.getBucketPolicy("deleted-policy").contains("GetObject"));
    }

    @Test
    void aPublicBucketAclIsRefusedOnAnObjectWriterBucketNobodyConfigured() {
        s3Service.createBucket("object-writer", "us-east-1");
        s3Service.putBucketOwnershipControls("object-writer",
                "<OwnershipControls><Rule><ObjectOwnership>ObjectWriter</ObjectOwnership></Rule></OwnershipControls>");

        AwsException e = assertThrows(AwsException.class, () -> s3Service.putBucketAcl("object-writer", null,
                "public-read", null, null, null, null, null));

        assertEquals("AccessDenied", e.getErrorCode());
        assertEquals(403, e.getHttpStatus());
    }

    @Test
    void aPublicBucketAclIsAcceptedOnceTheConfigurationIsDeleted() {
        s3Service.createBucket("object-writer-open", "us-east-1");
        s3Service.putBucketOwnershipControls("object-writer-open",
                "<OwnershipControls><Rule><ObjectOwnership>ObjectWriter</ObjectOwnership></Rule></OwnershipControls>");
        s3Service.deletePublicAccessBlock("object-writer-open");

        s3Service.putBucketAcl("object-writer-open", null, "public-read", null, null, null, null, null);
    }

    private static String publicPolicy(String bucket) {
        return policyFor("\"*\"", bucket);
    }

    private static String policyFor(String principal, String bucket) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":" + principal
                + ",\"Action\":\"s3:GetObject\",\"Resource\":\"arn:aws:s3:::" + bucket + "/*\"}]}";
    }

    @Test
    void repeatingCreateBucketKeepsAChangedConfiguration() {
        s3Service.createBucket("again", "us-east-1");
        s3Service.deletePublicAccessBlock("again");
        s3Service.createBucket("again", "us-east-1");

        assertThrows(AwsException.class, () -> s3Service.getPublicAccessBlock("again"));
    }
}
