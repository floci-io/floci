package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationService.S3TemplateRef;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Bucket/key extraction from a {@code TemplateURL}, which decides whether the URL is
 * virtual-hosted or path-style.
 */
class CloudFormationTemplateUrlTest {

    private static final String SUFFIX = EmbeddedDnsServer.DEFAULT_SUFFIX;

    @Test
    void pathStyleAgainstTheLocalS3ServiceHost_readsBucketFromThePath() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://s3.localhost.floci.io:4566/bucket/key", SUFFIX);

        assertThat(ref.bucket(), equalTo("bucket"));
        assertThat(ref.key(), equalTo("key"));
    }

    @Test
    void pathStyleAgainstTheRegionalLocalS3ServiceHost_readsBucketFromThePath() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://s3.us-east-1.localhost.floci.io:4566/cdk-hnb659fds-assets-000000000000-us-east-1/d05ac.json",
                SUFFIX);

        assertThat(ref.bucket(), equalTo("cdk-hnb659fds-assets-000000000000-us-east-1"));
        assertThat(ref.key(), equalTo("d05ac.json"));
    }

    @Test
    void pathStyleAgainstTheBareLocalhostS3ServiceHost_readsBucketFromThePath() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://s3.localhost:4566/bucket/nested/key.json", SUFFIX);

        assertThat(ref.bucket(), equalTo("bucket"));
        assertThat(ref.key(), equalTo("nested/key.json"));
    }

    @Test
    void virtualHostedAgainstTheConfiguredSuffix_readsBucketFromTheHost() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://bucket.localhost.floci.io:4566/key", SUFFIX);

        assertThat(ref.bucket(), equalTo("bucket"));
        assertThat(ref.key(), equalTo("key"));
    }

    @Test
    void virtualHostedAgainstTheS3QualifiedLocalHost_readsBucketFromTheHost() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://bucket.s3.localhost.floci.io:4566/nested/key.json", SUFFIX);

        assertThat(ref.bucket(), equalTo("bucket"));
        assertThat(ref.key(), equalTo("nested/key.json"));
    }

    @Test
    void virtualHostedAgainstBareLocalhost_readsBucketFromTheHost() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://bucket.localhost:4566/key", SUFFIX);

        assertThat(ref.bucket(), equalTo("bucket"));
        assertThat(ref.key(), equalTo("key"));
    }

    @Test
    void pathStyleAgainstAws_readsBucketFromThePath() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "https://s3.us-east-1.amazonaws.com/bucket/key", SUFFIX);

        assertThat(ref.bucket(), equalTo("bucket"));
        assertThat(ref.key(), equalTo("key"));
    }

    @Test
    void virtualHostedAgainstAws_readsBucketFromTheHost() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "https://bucket.s3.us-east-1.amazonaws.com/nested/key.json", SUFFIX);

        assertThat(ref.bucket(), equalTo("bucket"));
        assertThat(ref.key(), equalTo("nested/key.json"));
    }

    @Test
    void pathStyleAgainstAnUnknownHost_readsBucketFromThePath() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://127.0.0.1:4566/bucket/key", SUFFIX);

        assertThat(ref.bucket(), equalTo("bucket"));
        assertThat(ref.key(), equalTo("key"));
    }

    @Test
    void pathStyleWithoutAKey_readsTheBucketAndAnEmptyKey() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://s3.localhost.floci.io:4566/bucket", SUFFIX);

        assertThat(ref.bucket(), equalTo("bucket"));
        assertThat(ref.key(), equalTo(""));
    }

    @Test
    void virtualHostedForABucketNamedS3_onTheConfiguredSuffix_keepsTheBucket() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://s3.s3.localhost.floci.io:4566/key.json", SUFFIX);

        assertThat(ref.bucket(), equalTo("s3"));
        assertThat(ref.key(), equalTo("key.json"));
    }

    @Test
    void virtualHostedForABucketNamedS3_onAws_keepsTheBucket() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "https://s3.s3.us-east-1.amazonaws.com/nested/key.json", SUFFIX);

        assertThat(ref.bucket(), equalTo("s3"));
        assertThat(ref.key(), equalTo("nested/key.json"));
    }

    @Test
    void virtualHostedForABucketNamedS3_onBareLocalhost_keepsTheBucket() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://s3.s3.localhost:4566/key.json", SUFFIX);

        assertThat(ref.bucket(), equalTo("s3"));
        assertThat(ref.key(), equalTo("key.json"));
    }

    @Test
    void theRegionalServiceHost_readsBucketFromThePath() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://s3.us-east-1.localhost.floci.io:4566/bucket/key.json", SUFFIX);

        assertThat(ref.bucket(), equalTo("bucket"));
        assertThat(ref.key(), equalTo("key.json"));
    }

    @Test
    void theDualstackServiceHost_readsBucketFromThePath() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://s3.dualstack.us-east-1.localhost.floci.io:4566/bucket/key.json", SUFFIX);

        assertThat(ref.bucket(), equalTo("bucket"));
        assertThat(ref.key(), equalTo("key.json"));
    }

    @Test
    void theFipsDualstackServiceHost_readsBucketFromThePath() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://s3-fips.dualstack.us-west-2.localhost.floci.io:4566/bucket/key.json", SUFFIX);

        assertThat(ref.bucket(), equalTo("bucket"));
        assertThat(ref.key(), equalTo("key.json"));
    }

    @Test
    void theLegacyDashRegionServiceHost_readsBucketFromThePath() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://s3-us-east-1.localhost.floci.io:4566/bucket/key.json", SUFFIX);

        assertThat(ref.bucket(), equalTo("bucket"));
        assertThat(ref.key(), equalTo("key.json"));
    }

    @Test
    void theWebsiteServiceHost_readsBucketFromThePath() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://s3-website-us-east-1.localhost.floci.io:4566/bucket/key.json", SUFFIX);

        assertThat(ref.bucket(), equalTo("bucket"));
        assertThat(ref.key(), equalTo("key.json"));
    }

    @Test
    void aBucketWhoseNameMerelyStartsWithS3_staysVirtualHosted() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://s3-logs.localhost.floci.io:4566/key.json", SUFFIX);

        assertThat(ref.bucket(), equalTo("s3-logs"));
        assertThat(ref.key(), equalTo("key.json"));
    }

    @Test
    void aBucketWithARegionShapedS3Name_staysVirtualHosted() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://s3-eu-team-2.localhost.floci.io:4566/key.json", SUFFIX);

        assertThat(ref.bucket(), equalTo("s3-eu-team-2"));
        assertThat(ref.key(), equalTo("key.json"));
    }

    @Test
    void aBucketWithARegionShapedLabelBeforeTheSuffix_staysVirtualHosted() {
        S3TemplateRef ref = CloudFormationService.parseTemplateUrl(
                "http://s3.eu-team-2.localhost.floci.io:4566/key.json", SUFFIX);

        assertThat(ref.bucket(), equalTo("s3"));
        assertThat(ref.key(), equalTo("key.json"));
    }
}
