package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider.ResourcePolicy;
import io.github.hectorvent.floci.testing.PartitionMatrix.PartitionCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The bucket-policy lookup is on the authorization path: a bucket ARN whose partition the
 * provider does not recognise finds no policy, which silently flips an allow or a deny.
 */
class S3ResourcePolicyProviderTest {

    @ParameterizedTest
    @MethodSource("io.github.hectorvent.floci.testing.PartitionMatrix#cases")
    void bucketIsExtractedFromAnS3ArnInEveryPartition(PartitionCase partitionCase) {
        String prefix = "arn:" + partitionCase.partition() + ":s3:::";
        assertEquals("audit-source", S3ResourcePolicyProvider.extractBucketName(prefix + "audit-source"));
        assertEquals("audit-source", S3ResourcePolicyProvider.extractBucketName(prefix + "audit-source/documents/hello.txt"));
        assertEquals("*", S3ResourcePolicyProvider.extractBucketName(prefix + "*"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"arn:aws:lambda:us-east-1:000000000000:function:f", "audit-source", "arn:aws:s3"})
    void nonS3ArnsYieldNoBucket(String value) {
        assertNull(S3ResourcePolicyProvider.extractBucketName(value));
    }

    @Test
    void resolveResourceArnNamesAnExistingBucketInItsOwnPartition() {
        S3Service s3Service = mock(S3Service.class);
        when(s3Service.bucketPartition("commercial-bucket")).thenReturn("aws");
        S3ResourcePolicyProvider provider = new S3ResourcePolicyProvider(s3Service);

        assertEquals("arn:aws:s3:::commercial-bucket/documents/hello.txt",
                provider.resolveResourceArn("s3", "arn:aws-cn:s3:::commercial-bucket/documents/hello.txt"));
        assertEquals("arn:aws:s3:::commercial-bucket",
                provider.resolveResourceArn("s3", "arn:aws-cn:s3:::commercial-bucket"));
    }

    @Test
    void resolveResourceArnLeavesWildcardsAndOtherServicesAlone() {
        S3ResourcePolicyProvider provider = new S3ResourcePolicyProvider(mock(S3Service.class));

        assertEquals("arn:aws-cn:s3:::*", provider.resolveResourceArn("s3", "arn:aws-cn:s3:::*"));
        assertEquals("arn:aws-cn:sqs:cn-north-1:000000000000:q",
                provider.resolveResourceArn("sqs", "arn:aws-cn:sqs:cn-north-1:000000000000:q"));
    }

    /**
     * The regional form an S3 Control tagging route names is the caller's own naming of the
     * resource, which the tagging operations' own request pattern and the AWS CLI examples use,
     * so it is kept as written: rewriting it into the global form would deny a policy written
     * against the regional form, and would read its resource part as a bucket named bucket
     * with the real name as its key.
     */
    @Test
    void controlTaggingArnsKeepTheBucketTheyName() {
        assertEquals("audit-source", S3ResourcePolicyProvider.extractBucketName(
                "arn:aws:s3:us-east-1:000000000000:bucket/audit-source"));
        assertEquals("audit-source", S3ResourcePolicyProvider.extractBucketName(
                "arn:aws-cn:s3:cn-north-1:000000000000:bucket/audit-source"));

        S3ResourcePolicyProvider provider = new S3ResourcePolicyProvider(mock(S3Service.class));
        assertEquals("arn:aws:s3:us-east-1:000000000000:bucket/audit-source",
                provider.resolveResourceArn("s3", "arn:aws:s3:us-east-1:000000000000:bucket/audit-source"));
        assertEquals("arn:aws-cn:s3:cn-north-1:000000000000:bucket/audit-source",
                provider.resolveResourceArn("s3", "arn:aws-cn:s3:cn-north-1:000000000000:bucket/audit-source"));
    }

    /**
     * The provider reads ARNs that are already decoded, so it never decodes: an object key
     * carrying a plus sign or a literal percent sequence keeps its bytes before IAM checks it.
     */
    @Test
    void anObjectKeyIsNotDecodedAgainBeforeThePolicyCheck() {
        assertEquals("example", S3ResourcePolicyProvider.extractBucketName(
                "arn:aws:s3:::example/a+b.txt"));
        S3Service s3Service = mock(S3Service.class);
        when(s3Service.bucketPartition("example")).thenReturn("aws");
        S3ResourcePolicyProvider provider = new S3ResourcePolicyProvider(s3Service);
        assertEquals("arn:aws:s3:::example/a+b.txt",
                provider.resolveResourceArn("s3", "arn:aws:s3:::example/a+b.txt"));
        assertEquals("arn:aws:s3:::example/report%20file.txt",
                provider.resolveResourceArn("s3", "arn:aws:s3:::example/report%20file.txt"));
    }

    /**
     * A regional tagging ARN's policy is looked up in the account the ARN names, the same
     * account whose bucket the handler reads and writes, so a caller in one account cannot be
     * governed by the bucket policy of its own same-named bucket when the ARN names another
     * account's.
     */
    @Test
    void controlTaggingArnsLookTheBucketPolicyUpInTheAccountTheyName() {
        S3Service s3Service = mock(S3Service.class);
        when(s3Service.findBucketPolicyInfoForAccount("222222222222", "audit-source"))
                .thenReturn(Optional.of(new S3Service.BucketPolicyInfo("{}", "222222222222")));
        S3ResourcePolicyProvider provider = new S3ResourcePolicyProvider(s3Service);

        List<ResourcePolicy> policies = provider.getResourcePolicies("s3",
                "arn:aws:s3:us-east-1:222222222222:bucket/audit-source");

        assertEquals(1, policies.size());
        assertEquals("222222222222", policies.getFirst().ownerAccountId());
        verify(s3Service).findBucketPolicyInfoForAccount("222222222222", "audit-source");
        verify(s3Service, never()).findBucketPolicyInfo("audit-source");
    }
}
