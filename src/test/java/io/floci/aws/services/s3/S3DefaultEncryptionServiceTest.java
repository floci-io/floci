package io.floci.aws.services.s3;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.s3.model.CopyObjectOptions;
import io.floci.aws.services.s3.model.MultipartUpload;
import io.floci.aws.services.s3.model.PutObjectOptions;
import io.floci.aws.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An object written without encryption headers is encrypted with its bucket's default encryption:
 * the configured {@code ApplyServerSideEncryptionByDefault}, or SSE-S3 ({@code AES256}) for a bucket
 * with none. An explicit header and an SSE-C key keep taking precedence.
 */
class S3DefaultEncryptionServiceTest {

    private static final String BUCKET = "default-encryption-bucket";
    private static final String SOURCE = "copy-source-bucket";
    private static final String KMS_KEY = "arn:aws:kms:us-east-1:000000000000:key/11111111-2222-3333-4444-555555555555";
    private static final String OTHER_KMS_KEY = "arn:aws:kms:us-east-1:000000000000:key/99999999-8888-7777-6666-555555555555";
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
    void aNewBucketReportsAndEnforcesSseCustomerBlockingByDefault() throws Exception {
        assertTrue(s3Service.getBucketEncryption(BUCKET).contains(
                "<BlockedEncryptionTypes><EncryptionType>SSE-C</EncryptionType></BlockedEncryptionTypes>"));

        AwsException denied = assertThrows(AwsException.class,
                () -> put("default-blocked.txt", customerKey(new PutObjectOptions())));

        assertEquals("AccessDenied", denied.getErrorCode());
        assertEquals(403, denied.getHttpStatus());
    }

    @Test
    void aPlainEncryptionRulePreservesSseCustomerBlocking() throws Exception {
        s3Service.putBucketEncryption(BUCKET, configuration("AES256", null));

        assertTrue(s3Service.getBucketEncryption(BUCKET).contains(
                "<BlockedEncryptionTypes><EncryptionType>SSE-C</EncryptionType></BlockedEncryptionTypes>"));
        AwsException denied = assertThrows(AwsException.class,
                () -> put("still-blocked.txt", customerKey(new PutObjectOptions())));
        assertEquals("AccessDenied", denied.getErrorCode());
        assertEquals(403, denied.getHttpStatus());
    }

    @Test
    void configuredEncryptionReadbackPreservesOtherRuleFields() {
        s3Service.putBucketEncryption(BUCKET, "<ServerSideEncryptionConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                + "<Rule><ApplyServerSideEncryptionByDefault><SSEAlgorithm>aws:kms</SSEAlgorithm>"
                + "<KMSMasterKeyID>alias/R&amp;D</KMSMasterKeyID></ApplyServerSideEncryptionByDefault>"
                + "<BucketKeyEnabled>true</BucketKeyEnabled></Rule></ServerSideEncryptionConfiguration>");

        String readback = s3Service.getBucketEncryption(BUCKET);
        assertTrue(readback.startsWith("<ServerSideEncryptionConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"));
        assertTrue(readback.contains("<SSEAlgorithm>aws:kms</SSEAlgorithm>"));
        assertTrue(readback.contains("<KMSMasterKeyID>alias/R&amp;D</KMSMasterKeyID>"));
        assertTrue(readback.contains("<BucketKeyEnabled>true</BucketKeyEnabled>"));
        assertTrue(readback.contains("<BlockedEncryptionTypes><EncryptionType>SSE-C</EncryptionType></BlockedEncryptionTypes>"));
    }

    @Test
    void bahrainAndUaeBucketsDoNotBlockSseCustomerEncryptionByDefault() throws Exception {
        for (String region : List.of("me-south-1", "me-central-1")) {
            String bucket = "sse-c-" + region;
            s3Service.createBucket(bucket, region);

            assertFalse(s3Service.getBucketEncryption(bucket).contains("BlockedEncryptionTypes"));
            S3Object written = s3Service.putObject(bucket, "allowed.txt", DATA, "text/plain", null,
                    customerKey(new PutObjectOptions()));
            assertEquals("AES256", written.getSseCustomerAlgorithm());

            s3Service.putBucketEncryption(bucket, blockedEncryption("SSE-C"));
            AwsException denied = assertThrows(AwsException.class,
                    () -> s3Service.putObject(bucket, "blocked.txt", DATA, "text/plain", null,
                            customerKey(new PutObjectOptions())));
            assertEquals("AccessDenied", denied.getErrorCode());
        }
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
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", KMS_KEY, "NONE"));

        S3Object put = put("sse-c.txt", customerKey(new PutObjectOptions()));

        assertNull(put.getServerSideEncryption());
        assertNull(put.getSseKmsKeyId());
        assertEquals("AES256", put.getSseCustomerAlgorithm());
    }

    @Test
    void aBucketBlockingSseCustomerKeysRejectsPutObject() throws Exception {
        s3Service.putBucketEncryption(BUCKET, "<ServerSideEncryptionConfiguration>"
                + "<Rule><BlockedEncryptionTypes><EncryptionType>SSE-C</EncryptionType>"
                + "</BlockedEncryptionTypes></Rule></ServerSideEncryptionConfiguration>");

        AwsException denied = assertThrows(AwsException.class,
                () -> put("blocked-sse-c.txt", customerKey(new PutObjectOptions())));

        assertEquals("AccessDenied", denied.getErrorCode());
        assertEquals(403, denied.getHttpStatus());
        assertEquals("AES256", put("allowed-sse-s3.txt", new PutObjectOptions()).getServerSideEncryption());
    }

    @Test
    void aBlockedStreamingPutFailsBeforeReadingItsBody() throws Exception {
        PutObjectOptions sseC = customerKey(new PutObjectOptions());
        InputStream unread = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("Blocked upload body must not be read");
            }
        };

        AwsException denied = assertThrows(AwsException.class,
                () -> s3Service.putObject(BUCKET, "streamed.txt", unread, UploadChecksums.NONE,
                        "text/plain", null, sseC));

        assertEquals("AccessDenied", denied.getErrorCode());
        assertEquals(403, denied.getHttpStatus());
    }

    @Test
    void aBucketBlockingSseCustomerKeysRejectsPostAndDestinationCopy() throws Exception {
        s3Service.putObject(SOURCE, "source.txt", DATA, "text/plain", null, new PutObjectOptions());
        s3Service.putBucketEncryption(BUCKET, blockedEncryption("SSE-C"));
        PutObjectOptions sseC = customerKey(new PutObjectOptions());

        AwsException post = assertThrows(AwsException.class,
                () -> s3Service.postObject(BUCKET, "posted.txt", DATA, "text/plain", null, sseC));
        AwsException copy = assertThrows(AwsException.class,
                () -> s3Service.copyObject(SOURCE, "source.txt", BUCKET, "copy.txt", null,
                        new CopyObjectOptions()
                                .withSseCustomerAlgorithm(sseC.getSseCustomerAlgorithm())
                                .withSseCustomerKey(sseC.getSseCustomerKey())
                                .withSseCustomerKeyMd5(sseC.getSseCustomerKeyMd5())));

        assertEquals("AccessDenied", post.getErrorCode());
        assertEquals(403, post.getHttpStatus());
        assertEquals("AccessDenied", copy.getErrorCode());
        assertEquals(403, copy.getHttpStatus());
        assertEquals("AES256", s3Service.copyObject(SOURCE, "source.txt", BUCKET, "allowed-copy.txt")
                .getServerSideEncryption());
    }

    @Test
    void aBucketBlockingSseCustomerKeysRejectsMultipartInitiation() throws Exception {
        s3Service.putBucketEncryption(BUCKET, blockedEncryption("SSE-C"));

        AwsException denied = assertThrows(AwsException.class,
                () -> s3Service.initiateMultipartUpload(BUCKET, "blocked.bin", "application/octet-stream",
                        Map.of(), null, customerKey(new PutObjectOptions())));

        assertEquals("AccessDenied", denied.getErrorCode());
        assertEquals(403, denied.getHttpStatus());
        MultipartUpload allowed = s3Service.initiateMultipartUpload(BUCKET, "allowed.bin",
                "application/octet-stream", Map.of(), null, new PutObjectOptions());
        assertEquals("AES256", allowed.effectiveServerSideEncryption());
    }

    @Test
    void existingSseCustomerObjectCanBeCopiedAsAReadableSourceAfterBlocking() throws Exception {
        PutObjectOptions sseC = customerKey(new PutObjectOptions());
        s3Service.putBucketEncryption(BUCKET, blockedEncryption("NONE"));
        put("existing.txt", sseC);
        s3Service.putBucketEncryption(BUCKET, blockedEncryption("SSE-C"));

        S3Object copy = s3Service.copyObject(BUCKET, "existing.txt", SOURCE, "copy.txt", null,
                new CopyObjectOptions()
                        .withCopySourceSseCustomerAlgorithm(sseC.getSseCustomerAlgorithm())
                        .withCopySourceSseCustomerKey(sseC.getSseCustomerKey())
                        .withCopySourceSseCustomerKeyMd5(sseC.getSseCustomerKeyMd5()));

        assertEquals("AES256", s3Service.getObject(BUCKET, "existing.txt").getSseCustomerAlgorithm());
        assertEquals("AES256", copy.getServerSideEncryption());
        assertNull(copy.getSseCustomerAlgorithm());
    }

    @Test
    void explicitNoneDoesNotBlockSseCustomerWrites() throws Exception {
        s3Service.putBucketEncryption(BUCKET, blockedEncryption("NONE"));

        S3Object written = put("allowed-sse-c.txt", customerKey(new PutObjectOptions()));

        assertEquals("AES256", written.getSseCustomerAlgorithm());
        assertNull(written.getServerSideEncryption());
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
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", KMS_KEY, "NONE"));
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
    void aMultipartUploadTakesTheDefaultWhenItIsCreated() {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", KMS_KEY));
        MultipartUpload upload = s3Service.initiateMultipartUpload(BUCKET, "created.bin",
                "application/octet-stream", Map.of(), null, new PutObjectOptions());

        assertEquals("aws:kms", upload.effectiveServerSideEncryption());
        assertEquals(KMS_KEY, upload.effectiveSseKmsKeyId());
        assertNull(upload.getServerSideEncryption());
        assertEquals(Map.of("s3:x-amz-server-side-encryption", List.of("aws:kms"),
                "s3:x-amz-server-side-encryption-aws-kms-key-id", List.of(KMS_KEY)),
                s3Service.multipartEncryptionConditions(BUCKET, "created.bin", upload.getUploadId()));

        s3Service.putBucketEncryption(BUCKET, configuration("AES256", null));
        s3Service.uploadPart(BUCKET, "created.bin", upload.getUploadId(), 1, DATA);
        S3Object completed = s3Service.completeMultipartUpload(BUCKET, "created.bin", upload.getUploadId(),
                List.of(1), null, null);

        assertEquals("aws:kms", completed.getServerSideEncryption());
        assertEquals(KMS_KEY, completed.getSseKmsKeyId());
    }

    @Test
    void aDualLayerKmsDefaultReachesAMultipartUploadWithItsKey() {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms:dsse", KMS_KEY));
        MultipartUpload upload = s3Service.initiateMultipartUpload(BUCKET, "dsse.bin",
                "application/octet-stream", Map.of(), null, new PutObjectOptions());
        s3Service.uploadPart(BUCKET, "dsse.bin", upload.getUploadId(), 1, DATA);
        S3Object completed = s3Service.completeMultipartUpload(BUCKET, "dsse.bin", upload.getUploadId(),
                List.of(1), null, null);

        assertEquals("aws:kms:dsse", upload.effectiveServerSideEncryption());
        assertEquals(KMS_KEY, upload.effectiveSseKmsKeyId());
        assertEquals("aws:kms:dsse", completed.getServerSideEncryption());
        assertEquals(KMS_KEY, completed.getSseKmsKeyId());
    }

    @Test
    void anExplicitDualLayerHeaderKeepsItsKeyId() {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", KMS_KEY));
        MultipartUpload upload = s3Service.initiateMultipartUpload(BUCKET, "explicit-dsse.bin",
                "application/octet-stream", Map.of(), null,
                new PutObjectOptions().withServerSideEncryption("aws:kms:dsse").withSseKmsKeyId(OTHER_KMS_KEY));
        s3Service.uploadPart(BUCKET, "explicit-dsse.bin", upload.getUploadId(), 1, DATA);
        S3Object completed = s3Service.completeMultipartUpload(BUCKET, "explicit-dsse.bin", upload.getUploadId(),
                List.of(1), null, null);

        assertEquals("aws:kms:dsse", upload.effectiveServerSideEncryption());
        assertEquals(OTHER_KMS_KEY, upload.effectiveSseKmsKeyId());
        assertEquals("aws:kms:dsse", completed.getServerSideEncryption());
        assertEquals(OTHER_KMS_KEY, completed.getSseKmsKeyId());
    }

    @Test
    void anUploadThatSendsItsOwnEncryptionRecordsNoDefault() throws Exception {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", KMS_KEY, "NONE"));

        MultipartUpload explicit = s3Service.initiateMultipartUpload(BUCKET, "explicit.bin",
                "application/octet-stream", Map.of(), null, new PutObjectOptions().withServerSideEncryption("AES256"));
        MultipartUpload sseC = s3Service.initiateMultipartUpload(BUCKET, "sse-c.bin",
                "application/octet-stream", Map.of(), null, customerKey(new PutObjectOptions()));

        assertEquals("AES256", explicit.effectiveServerSideEncryption());
        assertNull(explicit.effectiveSseKmsKeyId());
        assertNull(explicit.getDefaultServerSideEncryption());
        assertNull(sseC.effectiveServerSideEncryption());
        assertNull(sseC.effectiveSseKmsKeyId());
        assertNull(sseC.getDefaultServerSideEncryption());
    }

    @Test
    void aConfigurationWithoutAnAlgorithmFallsBackToSseS3() {
        s3Service.putBucketEncryption(BUCKET, "<ServerSideEncryptionConfiguration/>");
        assertEquals("AES256", put("empty-configuration.txt", new PutObjectOptions()).getServerSideEncryption());

        s3Service.putBucketEncryption(BUCKET, configuration("aws:unknown", null));
        assertEquals("AES256", put("unsupported-algorithm.txt", new PutObjectOptions()).getServerSideEncryption());

        s3Service.putBucketEncryption(BUCKET, "not xml");
        assertEquals("AES256", put("malformed-configuration.txt", new PutObjectOptions()).getServerSideEncryption());

        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", KMS_KEY) + "<Broken>");
        assertEquals("AES256", put("truncated-configuration.txt", new PutObjectOptions()).getServerSideEncryption());
    }

    @Test
    void aDefaultOutsideTheRuleIsIgnored() {
        s3Service.putBucketEncryption(BUCKET, "<ServerSideEncryptionConfiguration>"
                + "<Elsewhere><SSEAlgorithm>AES256</SSEAlgorithm><KMSMasterKeyID>other-key</KMSMasterKeyID></Elsewhere>"
                + "<Rule><ApplyServerSideEncryptionByDefault><SSEAlgorithm>aws:kms</SSEAlgorithm>"
                + "<KMSMasterKeyID>" + KMS_KEY + "</KMSMasterKeyID></ApplyServerSideEncryptionByDefault></Rule>"
                + "</ServerSideEncryptionConfiguration>");

        S3Object put = put("rule.txt", new PutObjectOptions());

        assertEquals("aws:kms", put.getServerSideEncryption());
        assertEquals(KMS_KEY, put.getSseKmsKeyId());
    }

    @Test
    void aCopyOfASourceWithNoStoredEncryptionTakesTheDestinationBucketsDefault() throws Exception {
        s3Service.putObject(SOURCE, "old.txt", DATA, "text/plain", null, new PutObjectOptions());
        objectStore.scan(key -> true).forEach(object -> object.setServerSideEncryption(null));
        PutObjectOptions sseC = customerKey(new PutObjectOptions());
        s3Service.putBucketEncryption(SOURCE, blockedEncryption("NONE"));
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

    @Test
    void aCopyTakesTheDestinationBucketsDefaultNotTheSourcesEncryption() {
        s3Service.putBucketEncryption(SOURCE, configuration("aws:kms", KMS_KEY));
        s3Service.putObject(SOURCE, "kms.txt", DATA, "text/plain", null, new PutObjectOptions());
        put("sse-s3.txt", new PutObjectOptions());

        S3Object intoPlainBucket = s3Service.copyObject(SOURCE, "kms.txt", BUCKET, "plain-copy.txt");

        assertEquals("AES256", intoPlainBucket.getServerSideEncryption());
        assertNull(intoPlainBucket.getSseKmsKeyId());

        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", OTHER_KMS_KEY));
        S3Object fromKmsSource = s3Service.copyObject(SOURCE, "kms.txt", BUCKET, "kms-copy.txt");
        S3Object fromSseS3Source = s3Service.copyObject(BUCKET, "sse-s3.txt", BUCKET, "sse-s3-copy.txt");

        for (S3Object copy : List.of(fromKmsSource, fromSseS3Source)) {
            assertEquals("aws:kms", copy.getServerSideEncryption());
            assertEquals(OTHER_KMS_KEY, copy.getSseKmsKeyId());
        }
        assertEquals(OTHER_KMS_KEY, s3Service.getObject(BUCKET, "kms-copy.txt").getSseKmsKeyId());
    }

    @Test
    void aCopyWithAnExplicitHeaderKeepsIt() {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", KMS_KEY));
        put("source.txt", new PutObjectOptions());

        S3Object aes = s3Service.copyObject(BUCKET, "source.txt", BUCKET, "aes-copy.txt", null,
                new CopyObjectOptions().withServerSideEncryption("AES256"));
        S3Object kms = s3Service.copyObject(BUCKET, "source.txt", BUCKET, "kms-copy.txt", null,
                new CopyObjectOptions().withServerSideEncryption("aws:kms").withSseKmsKeyId(OTHER_KMS_KEY));
        S3Object kmsWithoutKey = s3Service.copyObject(BUCKET, "source.txt", BUCKET, "kms-no-key-copy.txt", null,
                new CopyObjectOptions().withServerSideEncryption("aws:kms"));

        assertEquals("AES256", aes.getServerSideEncryption());
        assertNull(aes.getSseKmsKeyId());
        assertEquals("aws:kms", kms.getServerSideEncryption());
        assertEquals(OTHER_KMS_KEY, kms.getSseKmsKeyId());
        assertEquals("aws:kms", kmsWithoutKey.getServerSideEncryption());
        assertNotEquals(KMS_KEY, kmsWithoutKey.getSseKmsKeyId());
        S3Object dsse = s3Service.copyObject(BUCKET, "source.txt", BUCKET, "dsse-copy.txt", null,
                new CopyObjectOptions().withServerSideEncryption("aws:kms:dsse").withSseKmsKeyId(OTHER_KMS_KEY));
        assertEquals(OTHER_KMS_KEY, dsse.getSseKmsKeyId());
    }

    @Test
    void aCopyEncryptedWithACustomerKeyGetsNoDefault() throws Exception {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", KMS_KEY, "NONE"));
        put("source.txt", new PutObjectOptions());
        PutObjectOptions sseC = customerKey(new PutObjectOptions());

        S3Object copy = s3Service.copyObject(BUCKET, "source.txt", BUCKET, "sse-c-copy.txt", null,
                new CopyObjectOptions()
                        .withSseCustomerAlgorithm(sseC.getSseCustomerAlgorithm())
                        .withSseCustomerKey(sseC.getSseCustomerKey())
                        .withSseCustomerKeyMd5(sseC.getSseCustomerKeyMd5()));

        assertNull(copy.getServerSideEncryption());
        assertEquals("AES256", copy.getSseCustomerAlgorithm());
    }

    private S3Object put(String key, PutObjectOptions options) {
        return s3Service.putObject(BUCKET, key, DATA, "text/plain", null, options);
    }

    private static String configuration(String algorithm, String kmsKeyId) {
        return configuration(algorithm, kmsKeyId, null);
    }

    private static String configuration(String algorithm, String kmsKeyId, String blockedType) {
        return "<ServerSideEncryptionConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Rule>"
                + "<ApplyServerSideEncryptionByDefault><SSEAlgorithm>" + algorithm + "</SSEAlgorithm>"
                + (kmsKeyId == null ? "" : "<KMSMasterKeyID>" + kmsKeyId + "</KMSMasterKeyID>")
                + "</ApplyServerSideEncryptionByDefault>"
                + (blockedType == null ? "" : "<BlockedEncryptionTypes><EncryptionType>" + blockedType
                        + "</EncryptionType></BlockedEncryptionTypes>")
                + "</Rule></ServerSideEncryptionConfiguration>";
    }

    private static String blockedEncryption(String type) {
        return "<ServerSideEncryptionConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Rule>"
                + "<ApplyServerSideEncryptionByDefault><SSEAlgorithm>AES256</SSEAlgorithm>"
                + "</ApplyServerSideEncryptionByDefault><BlockedEncryptionTypes><EncryptionType>" + type
                + "</EncryptionType></BlockedEncryptionTypes></Rule></ServerSideEncryptionConfiguration>";
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
