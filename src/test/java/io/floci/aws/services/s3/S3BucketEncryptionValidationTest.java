package io.floci.aws.services.s3;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.s3.model.PutObjectOptions;
import io.floci.aws.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The body {@code PutBucketEncryption} stores: AWS refuses one that is not well-formed XML or holds
 * no {@code Rule}, an {@code ApplyServerSideEncryptionByDefault} without an SSEAlgorithm S3 serves, a
 * {@code KMSMasterKeyID} next to another algorithm, or a KMS key id with a control character, whose
 * line break would otherwise reach a response header and fail every later read of the bucket.
 */
class S3BucketEncryptionValidationTest {

    private static final String BUCKET = "bucket-encryption-validation-bucket";
    private static final String KMS_KEY = "arn:aws:kms:us-east-1:000000000000:key/11111111-2222-3333-4444-555555555555";

    @TempDir
    Path tempDir;

    private InMemoryStorage<String, S3Object> objectStore;
    private S3Service s3Service;

    @BeforeEach
    void setUp() {
        objectStore = new InMemoryStorage<>();
        s3Service = new S3Service(new InMemoryStorage<>(), objectStore, tempDir.resolve("s3"), false);
        s3Service.createBucket(BUCKET, "us-east-1");
    }

    @Test
    void aBodyThatDoesNotParseIsRefusedAsMalformedXml() {
        AwsException error = assertThrows(AwsException.class,
                () -> s3Service.putBucketEncryption(BUCKET, "<ServerSideEncryptionConfiguration>"));
        assertEquals("MalformedXML", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void aBodyWithoutARuleIsRefusedAsMalformedXml() {
        AwsException error = assertThrows(AwsException.class,
                () -> s3Service.putBucketEncryption(BUCKET,
                        "<ServerSideEncryptionConfiguration><NotARule/></ServerSideEncryptionConfiguration>"));
        assertEquals("MalformedXML", error.getErrorCode());
    }

    @Test
    void aDefaultWithoutASupportedAlgorithmIsRefusedAsInvalidArgument() {
        assertRefusedAsInvalidArgument(configuration(null, null));
        assertRefusedAsInvalidArgument(configuration("sse-kms-not-real", null));
    }

    @Test
    void aKmsMasterKeyIdWithoutKmsEncryptionIsRefusedAsInvalidArgument() {
        assertRefusedAsInvalidArgument(configuration("AES256", KMS_KEY));
    }

    @Test
    void aKmsMasterKeyIdWithALineBreakIsRefusedAsInvalidArgument() {
        assertRefusedAsInvalidArgument(configuration("aws:kms", "key\nid"));
    }

    @Test
    void aKmsMasterKeyIdWithACharacterXmlRefusesIsRefusedAsMalformedXml() {
        AwsException error = assertThrows(AwsException.class,
                () -> s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", "key\u0007id")));
        assertEquals("MalformedXML", error.getErrorCode());
    }

    @Test
    void aRuleWithoutADefaultEncryptionIsStored() {
        String body = "<ServerSideEncryptionConfiguration>"
                + "<Rule><BlockedEncryptionTypes><EncryptionType>SSE-C</EncryptionType></BlockedEncryptionTypes>"
                + "</Rule></ServerSideEncryptionConfiguration>";

        s3Service.putBucketEncryption(BUCKET, body);

        assertEquals(body, s3Service.getBucketEncryption(BUCKET));
    }

    @Test
    void aValidKmsConfigurationWithAKeyArnIsStored() {
        s3Service.putBucketEncryption(BUCKET, configuration("aws:kms", KMS_KEY));

        // Since #9414 the read augments the stored rule with BlockedEncryptionTypes, so the
        // stored default elements are asserted rather than the whole document.
        String returned = s3Service.getBucketEncryption(BUCKET);
        assertTrue(returned.contains("<SSEAlgorithm>aws:kms</SSEAlgorithm>"));
        assertTrue(returned.contains("<KMSMasterKeyID>" + KMS_KEY + "</KMSMasterKeyID>"));
    }

    @Test
    void refusalsLeaveTheBucketWritingAndReading() {
        assertThrows(AwsException.class,
                () -> s3Service.putBucketEncryption(BUCKET, configuration("AES256", KMS_KEY)));

        S3Object put = s3Service.putObject(BUCKET, "plain.txt",
                "data".getBytes(StandardCharsets.UTF_8), "text/plain", null, new PutObjectOptions());

        assertEquals("AES256", put.getServerSideEncryption());
    }

    private void assertRefusedAsInvalidArgument(String body) {
        AwsException error = assertThrows(AwsException.class,
                () -> s3Service.putBucketEncryption(BUCKET, body));
        assertEquals("InvalidArgument", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    private static String configuration(String algorithm, String kmsKeyId) {
        return "<ServerSideEncryptionConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Rule>"
                + "<ApplyServerSideEncryptionByDefault>"
                + (algorithm == null ? "" : "<SSEAlgorithm>" + algorithm + "</SSEAlgorithm>")
                + (kmsKeyId == null ? "" : "<KMSMasterKeyID>" + kmsKeyId + "</KMSMasterKeyID>")
                + "</ApplyServerSideEncryptionByDefault></Rule></ServerSideEncryptionConfiguration>";
    }
}
