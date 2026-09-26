package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.testing.PartitionMatrix.PartitionCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
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
}
