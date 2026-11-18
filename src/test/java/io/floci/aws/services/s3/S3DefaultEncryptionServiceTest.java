package io.floci.aws.services.s3;

import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.s3.model.CopyObjectOptions;
import io.floci.aws.services.s3.model.MultipartUpload;
import io.floci.aws.services.s3.model.PutObjectOptions;
import io.floci.aws.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * An object written without encryption headers is encrypted with its bucket's default encryption:
 * the configured {@code ApplyServerSideEncryptionByDefault}, or SSE-S3 ({@code AES256}) for a bucket
 * with none. An explicit header and an SSE-C key keep taking precedence.
 */
class S3DefaultEncryptionServiceTest {

    private static final String BUCKET = "default-encryption-bucket";
    private static final String SOURCE = "copy-source-bucket";
    private static final String KMS_KEY = "arn:aws:kms:us-east-1:000000000000:key/11111111-2222-3333-4444-555555555555";
    private static final byte[] DATA = "data".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private InMemoryStorage<String, S3Object> objectStore;
    private S3Service s3Service;

    @BeforeEach
    void setUp() {
        objectStore = new InMemoryStorage<>();
        s3Service = new S3Service(new InMemoryStorage<>(), objectStore, tempDir.resolve("s3"), false);
        s3Service.createBucket(BUCKET, "us-east-1");
        s3Service.createBucket(SOURCE, "us-east-1");
    }

    @Test
    void aBucketWithoutConfigurationEncryptsWithSseS3() {
        S3Object put = put("plain.txt", new PutObjectOptions());

        assertEquals("AES256", put.getServerSideEncryption());
        assertNull(put.getSseKmsKeyId());
        assertEquals("AES256", s3Service.getObject(BUCKET, "plain.txt").getServerSideEncryption());
    }

    @Test
    void aKmsDefaultIsAppliedWithItsKey() {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", KMS_KEY));

        S3Object put = put("kms.txt", new PutObjectOptions());

        assertEquals("aws:kms", put.getServerSideEncryption());
        assertEquals(KMS_KEY, put.getSseKmsKeyId());
        S3Object stored = s3Service.getObject(BUCKET, "kms.txt");
        assertEquals("aws:kms", stored.getServerSideEncryption());
        assertEquals(KMS_KEY, stored.getSseKmsKeyId());
        S3Object posted = s3Service.postObject(BUCKET, "posted.txt", DATA, "text/plain", null);
        assertEquals("aws:kms", posted.getServerSideEncryption());
        assertEquals(KMS_KEY, posted.getSseKmsKeyId());
    }

    @Test
    void aKmsDefaultWithoutAKeyIsAppliedWithoutOne() {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", null));

        S3Object put = put("kms-default-key.txt", new PutObjectOptions());

        assertEquals("aws:kms", put.getServerSideEncryption());
        assertNull(put.getSseKmsKeyId());
    }

    @Test
    void aDualLayerKmsDefaultIsAppliedWithItsKey() {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms:dsse", KMS_KEY));

        S3Object put = put("dsse.txt", new PutObjectOptions());

        assertEquals("aws:kms:dsse", put.getServerSideEncryption());
        assertEquals(KMS_KEY, put.getSseKmsKeyId());
    }

    @Test
    void anExplicitHeaderTakesPrecedenceOverTheDefault() {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", KMS_KEY));

        S3Object put = put("explicit.txt", new PutObjectOptions().withServerSideEncryption("aws:kms"));

        assertEquals("aws:kms", put.getServerSideEncryption());
        assertNotEquals(KMS_KEY, put.getSseKmsKeyId());
    }

    @Test
    void anSseCustomerKeyUploadGetsNoDefault() throws Exception {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", KMS_KEY));

        S3Object put = put("sse-c.txt", customerKey(new PutObjectOptions()));

        assertNull(put.getServerSideEncryption());
        assertNull(put.getSseKmsKeyId());
        assertEquals("AES256", put.getSseCustomerAlgorithm());
    }

    @Test
    void objectsAlreadyStoredKeepTheirEncryption() {
        put("before.txt", new PutObjectOptions());
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", KMS_KEY));
        put("after.txt", new PutObjectOptions());

        assertEquals("AES256", s3Service.getObject(BUCKET, "before.txt").getServerSideEncryption());
        assertEquals("aws:kms", s3Service.getObject(BUCKET, "after.txt").getServerSideEncryption());
    }

    @Test
    void aMultipartUploadCompletesIntoAnObjectWithTheDefault() {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", KMS_KEY));

        MultipartUpload upload = s3Service.initiateMultipartUpload(BUCKET, "multipart.bin",
                "application/octet-stream", Map.of(), null, new PutObjectOptions());
        s3Service.uploadPart(BUCKET, "multipart.bin", upload.getUploadId(), 1, DATA);
        S3Object completed = s3Service.completeMultipartUpload(BUCKET, "multipart.bin", upload.getUploadId(),
                List.of(1), null, null);

        assertEquals("aws:kms", completed.getServerSideEncryption());
        assertEquals(KMS_KEY, completed.getSseKmsKeyId());
        assertEquals("aws:kms", s3Service.getObject(BUCKET, "multipart.bin").getServerSideEncryption());
    }

    @Test
    void anSseCustomerKeyMultipartUploadGetsNoDefault() throws Exception {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", KMS_KEY));
        PutObjectOptions sseC = customerKey(new PutObjectOptions());
        MultipartUpload upload = s3Service.initiateMultipartUpload(BUCKET, "multipart-sse-c.bin",
                "application/octet-stream", Map.of(), null, sseC);
        s3Service.uploadPart(BUCKET, "multipart-sse-c.bin", upload.getUploadId(), 1, DATA,
                sseC.getSseCustomerAlgorithm(), sseC.getSseCustomerKey(), sseC.getSseCustomerKeyMd5());
        S3Object completed = s3Service.completeMultipartUpload(BUCKET, "multipart-sse-c.bin", upload.getUploadId(),
                List.of(1), null, null);

        assertNull(completed.getServerSideEncryption());
        assertNull(completed.getSseKmsKeyId());
        assertEquals("AES256", completed.getSseCustomerAlgorithm());
        assertNull(s3Service.getObject(BUCKET, "multipart-sse-c.bin").getServerSideEncryption());
    }

    @Test
    void aConfigurationWithoutAnAlgorithmFallsBackToSseS3() {
        s3Service.putBucketEncryption(BUCKET, "<ServerSideEncryptionConfiguration/>");
        assertEquals("AES256", put("empty-configuration.txt", new PutObjectOptions()).getServerSideEncryption());

        s3Service.putBucketEncryption(BUCKET, configuration("aws:unknown", null));
        assertEquals("AES256", put("unsupported-algorithm.txt", new PutObjectOptions()).getServerSideEncryption());

        s3Service.putBucketEncryption(BUCKET, "not xml");
        assertEquals("AES256", put("malformed-configuration.txt", new PutObjectOptions()).getServerSideEncryption());
    }

    @Test
    void aCopyOfASourceWithNoStoredEncryptionTakesTheDestinationBucketsDefault() throws Exception {
        s3Service.putObject(SOURCE, "old.txt", DATA, "text/plain", null, new PutObjectOptions());
        objectStore.scan(key -> true).forEach(object -> object.setServerSideEncryption(null));
        PutObjectOptions sseC = customerKey(new PutObjectOptions());
        s3Service.putObject(SOURCE, "sse-c.txt", DATA, "text/plain", null, sseC);
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", KMS_KEY));

        S3Object fromOld = s3Service.copyObject(SOURCE, "old.txt", BUCKET, "old-copy.txt");
        S3Object fromSseC = s3Service.copyObject(SOURCE, "sse-c.txt", BUCKET, "sse-c-copy.txt", null,
                new CopyObjectOptions()
                        .withCopySourceSseCustomerAlgorithm(sseC.getSseCustomerAlgorithm())
                        .withCopySourceSseCustomerKey(sseC.getSseCustomerKey())
                        .withCopySourceSseCustomerKeyMd5(sseC.getSseCustomerKeyMd5()));

        for (S3Object copy : List.of(fromOld, fromSseC)) {
            assertEquals("aws:kms", copy.getServerSideEncryption());
            assertEquals(KMS_KEY, copy.getSseKmsKeyId());
            assertNull(copy.getSseCustomerAlgorithm());
        }
    }

    private S3Object put(String key, PutObjectOptions options) {
        return s3Service.putObject(BUCKET, key, DATA, "text/plain", null, options);
    }

    private static String configuration(String algorithm, String kmsKeyId) {
        return "<ServerSideEncryptionConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Rule>"
                + "<ApplyServerSideEncryptionByDefault><SSEAlgorithm>" + algorithm + "</SSEAlgorithm>"
                + (kmsKeyId == null ? "" : "<KMSMasterKeyID>" + kmsKeyId + "</KMSMasterKeyID>")
                + "</ApplyServerSideEncryptionByDefault></Rule></ServerSideEncryptionConfiguration>";
    }

    private static PutObjectOptions customerKey(PutObjectOptions options) throws Exception {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        byte[] md5 = MessageDigest.getInstance("MD5").digest(key);
        return options.withSseCustomerAlgorithm("AES256")
                .withSseCustomerKey(Base64.getEncoder().encodeToString(key))
                .withSseCustomerKeyMd5(Base64.getEncoder().encodeToString(md5));
    }
}
