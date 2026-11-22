package io.floci.aws.services.s3;

import io.floci.aws.testing.S3TestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * S3 encrypts every new object: one uploaded without encryption headers gets the bucket's default
 * encryption, SSE-S3 ({@code AES256}) when the bucket has no configuration. PutObject, HeadObject and
 * GetObject report it through the AWS SDK.
 */
@QuarkusTest
class S3DefaultEncryptionIntegrationTest {

    private static final String KMS_KEY = "arn:aws:kms:us-east-1:000000000000:key/11111111-2222-3333-4444-555555555555";

    private final List<String> otherBuckets = new ArrayList<>();
    private S3Client s3;
    private String bucket;

    @BeforeEach
    void createBucket() {
        s3 = S3TestClients.pathStyle();
        bucket = "default-encryption-" + UUID.randomUUID();
        s3.createBucket(r -> r.bucket(bucket));
    }

    @AfterEach
    void deleteBucketsAndCloseClient() {
        S3TestClients.deleteBucket(s3, bucket);
        otherBuckets.forEach(other -> S3TestClients.deleteBucket(s3, other));
        s3.close();
    }

    @Test
    void aBucketWithoutConfigurationReportsSseS3OnEveryObject() throws Exception {
        PutObjectResponse put = s3.putObject(r -> r.bucket(bucket).key("plain"), RequestBody.fromString("plain"));

        assertEquals(ServerSideEncryption.AES256, put.serverSideEncryption());
        assertEquals(ServerSideEncryption.AES256, head("plain").serverSideEncryption());
        try (ResponseInputStream<GetObjectResponse> got = s3.getObject(r -> r.bucket(bucket).key("plain"))) {
            assertEquals(ServerSideEncryption.AES256, got.response().serverSideEncryption());
        }
    }

    @Test
    void aKmsDefaultIsReportedWithItsKey() {
        useKmsDefault();

        PutObjectResponse put = s3.putObject(r -> r.bucket(bucket).key("kms"), RequestBody.fromString("kms"));

        assertEquals(ServerSideEncryption.AWS_KMS, put.serverSideEncryption());
        assertEquals(KMS_KEY, put.ssekmsKeyId());
        HeadObjectResponse head = head("kms");
        assertEquals(ServerSideEncryption.AWS_KMS, head.serverSideEncryption());
        assertEquals(KMS_KEY, head.ssekmsKeyId());
    }

    @Test
    void aMultipartUploadCompletesIntoAnObjectWithTheDefault() {
        useKmsDefault();

        String uploadId = s3.createMultipartUpload(r -> r.bucket(bucket).key("multipart")).uploadId();
        String eTag = s3.uploadPart(r -> r.bucket(bucket).key("multipart").uploadId(uploadId).partNumber(1),
                RequestBody.fromString("part")).eTag();
        s3.completeMultipartUpload(r -> r.bucket(bucket).key("multipart").uploadId(uploadId)
                .multipartUpload(m -> m.parts(CompletedPart.builder().partNumber(1).eTag(eTag).build())));

        HeadObjectResponse head = head("multipart");
        assertEquals(ServerSideEncryption.AWS_KMS, head.serverSideEncryption());
        assertEquals(KMS_KEY, head.ssekmsKeyId());
    }

    @Test
    void aCopyIntoAKmsDefaultBucketTakesItsKey() {
        s3.putObject(r -> r.bucket(bucket).key("source"), RequestBody.fromString("source"));
        String kmsBucket = otherBucket();
        s3.putBucketEncryption(r -> r.bucket(kmsBucket).serverSideEncryptionConfiguration(c -> c.rules(rule ->
                rule.applyServerSideEncryptionByDefault(d -> d.sseAlgorithm(ServerSideEncryption.AWS_KMS)
                        .kmsMasterKeyID(KMS_KEY)))));

        CopyObjectResponse copy = s3.copyObject(r -> r.sourceBucket(bucket).sourceKey("source")
                .destinationBucket(kmsBucket).destinationKey("copy"));

        assertEquals(ServerSideEncryption.AWS_KMS, copy.serverSideEncryption());
        assertEquals(KMS_KEY, copy.ssekmsKeyId());
        HeadObjectResponse head = s3.headObject(r -> r.bucket(kmsBucket).key("copy"));
        assertEquals(ServerSideEncryption.AWS_KMS, head.serverSideEncryption());
        assertEquals(KMS_KEY, head.ssekmsKeyId());
    }

    @Test
    void aCopyWithAnExplicitHeaderKeepsIt() {
        useKmsDefault();
        String sourceBucket = otherBucket();
        s3.putObject(r -> r.bucket(sourceBucket).key("source"), RequestBody.fromString("source"));

        CopyObjectResponse copy = s3.copyObject(r -> r.sourceBucket(sourceBucket).sourceKey("source")
                .destinationBucket(bucket).destinationKey("copy").serverSideEncryption(ServerSideEncryption.AES256));

        assertEquals(ServerSideEncryption.AES256, copy.serverSideEncryption());
        assertNull(copy.ssekmsKeyId());
        HeadObjectResponse head = head("copy");
        assertEquals(ServerSideEncryption.AES256, head.serverSideEncryption());
        assertNull(head.ssekmsKeyId());
    }

    private void useKmsDefault() {
        s3.putBucketEncryption(r -> r.bucket(bucket).serverSideEncryptionConfiguration(c -> c.rules(rule ->
                rule.applyServerSideEncryptionByDefault(d -> d.sseAlgorithm(ServerSideEncryption.AWS_KMS)
                        .kmsMasterKeyID(KMS_KEY)))));
    }

    private String otherBucket() {
        String other = "default-encryption-other-" + UUID.randomUUID();
        s3.createBucket(r -> r.bucket(other));
        otherBuckets.add(other);
        return other;
    }

    private HeadObjectResponse head(String key) {
        return s3.headObject(r -> r.bucket(bucket).key(key));
    }
}
