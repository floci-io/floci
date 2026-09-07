package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.ChecksumType;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectAttributesRequest;
import software.amazon.awssdk.services.s3.model.GetObjectAttributesResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectAttributes;
import software.amazon.awssdk.services.s3.model.ObjectPart;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.UploadRequest;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.*;

/**
 * Low-level multipart uploads with the Java SDK v2, the flow of the "checksum of checksums" example
 * in the AWS integrity documentation. S3 stores a COMPOSITE checksum for SHA algorithms (the algorithm
 * applied to the concatenated part checksums, suffixed with "-<parts>"), a FULL_OBJECT one for CRC
 * algorithms when requested on CreateMultipartUpload, and writes a copy as a single full object.
 */
@DisplayName("S3 multipart checksums")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3MultipartChecksumTest {

    private static final int MIB = 1024 * 1024;
    private static final String BUCKET = "sdk-test-multipart-checksums";
    private static final byte[] PART_1 = payload(5 * MIB, 1);
    private static final byte[] PART_2 = payload(1 * MIB, 2);
    private static final List<String> KEYS = List.of("sha256.bin", "crc32-full-object.bin", "sha256-copy.bin",
            "tm-sha256.bin", "tm-default.bin");

    private static S3Client s3;

    @BeforeAll
    static void setup() {
        s3 = TestFixtures.s3Client();
        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @AfterAll
    static void cleanup() {
        if (s3 == null) {
            return;
        }
        for (String key : KEYS) {
            try {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(key).build());
            } catch (Exception e) {
                System.err.printf("cleanup: could not delete s3://%s/%s: %s%n", BUCKET, key, e.getMessage());
            }
        }
        try {
            s3.deleteBucket(DeleteBucketRequest.builder().bucket(BUCKET).build());
        } catch (Exception e) {
            System.err.printf("cleanup: could not delete bucket %s: %s%n", BUCKET, e.getMessage());
        }
    }

    @Test
    @Order(1)
    @DisplayName("SHA256 multipart upload returns the composite checksum")
    void sha256MultipartUploadReturnsCompositeChecksum() throws NoSuchAlgorithmException {
        String key = KEYS.get(0);
        CreateMultipartUploadResponse created = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(BUCKET).key(key).checksumAlgorithm(ChecksumAlgorithm.SHA256).build());
        assertThat(created.checksumAlgorithm()).isEqualTo(ChecksumAlgorithm.SHA256);
        assertThat(created.checksumType()).isEqualTo(ChecksumType.COMPOSITE);

        List<CompletedPart> completed = new ArrayList<>();
        byte[][] parts = {PART_1, PART_2};
        for (int i = 0; i < parts.length; i++) {
            UploadPartResponse uploaded = s3.uploadPart(UploadPartRequest.builder()
                    .bucket(BUCKET).key(key).uploadId(created.uploadId()).partNumber(i + 1)
                    .checksumAlgorithm(ChecksumAlgorithm.SHA256).build(), RequestBody.fromBytes(parts[i]));
            // UploadPart echoes the checksum of the part, which the SDK carries into the Complete body
            assertThat(uploaded.checksumSHA256()).isEqualTo(base64(sha256(parts[i])));
            completed.add(CompletedPart.builder().partNumber(i + 1).eTag(uploaded.eTag())
                    .checksumSHA256(uploaded.checksumSHA256()).build());
        }

        String composite = compositeSha256(parts);
        CompleteMultipartUploadResponse done = s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(BUCKET).key(key).uploadId(created.uploadId())
                .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build()).build());
        assertThat(done.checksumSHA256()).isEqualTo(composite);
        assertThat(done.checksumType()).isEqualTo(ChecksumType.COMPOSITE);

        HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                .bucket(BUCKET).key(key).checksumMode(ChecksumMode.ENABLED).build());
        assertThat(head.checksumSHA256()).isEqualTo(composite);
        assertThat(head.checksumType()).isEqualTo(ChecksumType.COMPOSITE);
        assertThat(head.eTag()).endsWith("-2\"");

        GetObjectAttributesResponse attributes = s3.getObjectAttributes(GetObjectAttributesRequest.builder()
                .bucket(BUCKET).key(key)
                .objectAttributes(ObjectAttributes.CHECKSUM, ObjectAttributes.OBJECT_PARTS, ObjectAttributes.E_TAG)
                .build());
        assertThat(attributes.checksum().checksumSHA256()).isEqualTo(composite.replace("-2", ""))
                .describedAs("GetObjectAttributes omits the part-count suffix");
        assertThat(attributes.checksum().checksumType()).isEqualTo(ChecksumType.COMPOSITE);
        assertThat(attributes.objectParts().totalPartsCount()).isEqualTo(2);
        assertThat(attributes.objectParts().parts()).extracting("checksumSHA256")
                .containsExactly(base64(sha256(PART_1)), base64(sha256(PART_2)));
        assertThat(attributes.eTag()).isEqualTo(head.eTag().replace("\"", ""))
                .describedAs("GetObjectAttributes returns the ETag without quotes");
    }

    @Test
    @Order(2)
    @DisplayName("Complete without a part checksum is rejected on a composite upload")
    void completeWithoutPartChecksumsIsRejected() {
        String key = "strict.bin";
        CreateMultipartUploadResponse created = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(BUCKET).key(key).checksumAlgorithm(ChecksumAlgorithm.SHA256).build());
        UploadPartResponse uploaded = s3.uploadPart(UploadPartRequest.builder()
                .bucket(BUCKET).key(key).uploadId(created.uploadId()).partNumber(1)
                .checksumAlgorithm(ChecksumAlgorithm.SHA256).build(), RequestBody.fromBytes(PART_2));

        assertThatThrownBy(() -> s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(BUCKET).key(key).uploadId(created.uploadId())
                .multipartUpload(CompletedMultipartUpload.builder()
                        .parts(CompletedPart.builder().partNumber(1).eTag(uploaded.eTag()).build()).build())
                .build()))
                .isInstanceOf(S3Exception.class)
                .satisfies(e -> {
                    S3Exception s3e = (S3Exception) e;
                    assertThat(s3e.statusCode()).isEqualTo(400);
                    assertThat(s3e.awsErrorDetails().errorCode()).isEqualTo("InvalidRequest");
                    assertThat(s3e.awsErrorDetails().errorMessage())
                            .contains("must include the checksum for each part");
                });
        s3.abortMultipartUpload(b -> b.bucket(BUCKET).key(key).uploadId(created.uploadId()));
    }

    @Test
    @Order(3)
    @DisplayName("CRC32 upload with FULL_OBJECT type keeps the whole-object checksum")
    void crc32FullObjectUpload() {
        String key = KEYS.get(1);
        CreateMultipartUploadResponse created = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                .bucket(BUCKET).key(key).checksumAlgorithm(ChecksumAlgorithm.CRC32)
                .checksumType(ChecksumType.FULL_OBJECT).build());
        assertThat(created.checksumType()).isEqualTo(ChecksumType.FULL_OBJECT);

        List<CompletedPart> completed = new ArrayList<>();
        byte[][] parts = {PART_1, PART_2};
        for (int i = 0; i < parts.length; i++) {
            UploadPartResponse uploaded = s3.uploadPart(UploadPartRequest.builder()
                    .bucket(BUCKET).key(key).uploadId(created.uploadId()).partNumber(i + 1)
                    .checksumAlgorithm(ChecksumAlgorithm.CRC32).build(), RequestBody.fromBytes(parts[i]));
            completed.add(CompletedPart.builder().partNumber(i + 1).eTag(uploaded.eTag())
                    .checksumCRC32(uploaded.checksumCRC32()).build());
        }

        byte[] whole = concat(PART_1, PART_2);
        String fullCrc32 = base64(crc32(whole));
        CompleteMultipartUploadResponse done = s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(BUCKET).key(key).uploadId(created.uploadId())
                .checksumType(ChecksumType.FULL_OBJECT).checksumCRC32(fullCrc32)
                .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build()).build());
        assertThat(done.checksumCRC32()).isEqualTo(fullCrc32);
        assertThat(done.checksumType()).isEqualTo(ChecksumType.FULL_OBJECT);

        HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                .bucket(BUCKET).key(key).checksumMode(ChecksumMode.ENABLED).build());
        assertThat(head.checksumCRC32()).isEqualTo(fullCrc32);
        assertThat(head.checksumType()).isEqualTo(ChecksumType.FULL_OBJECT);

        GetObjectAttributesResponse attributes = s3.getObjectAttributes(GetObjectAttributesRequest.builder()
                .bucket(BUCKET).key(key).objectAttributes(ObjectAttributes.CHECKSUM, ObjectAttributes.OBJECT_PARTS)
                .build());
        assertThat(attributes.objectParts().totalPartsCount()).isEqualTo(2);
        assertThat(attributes.objectParts().parts()).describedAs("no part checksums for FULL_OBJECT objects").isEmpty();
    }

    @Test
    @Order(4)
    @DisplayName("A copy of the multipart object is a single full object")
    void copyOfMultipartObjectIsASingleFullObject() throws NoSuchAlgorithmException {
        HeadObjectResponse source = s3.headObject(HeadObjectRequest.builder()
                .bucket(BUCKET).key(KEYS.get(0)).checksumMode(ChecksumMode.ENABLED).build());
        CopyObjectResponse copied = s3.copyObject(CopyObjectRequest.builder()
                .sourceBucket(BUCKET).sourceKey(KEYS.get(0)).destinationBucket(BUCKET).destinationKey(KEYS.get(2))
                .build());

        byte[] whole = concat(PART_1, PART_2);
        String expectedETag = "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(whole)) + "\"";
        assertThat(copied.copyObjectResult().checksumSHA256()).isEqualTo(base64(sha256(whole)));
        assertThat(copied.copyObjectResult().checksumType()).isEqualTo(ChecksumType.FULL_OBJECT);
        assertThat(copied.copyObjectResult().eTag()).isEqualTo(expectedETag).isNotEqualTo(source.eTag());

        HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                .bucket(BUCKET).key(KEYS.get(2)).checksumMode(ChecksumMode.ENABLED).build());
        assertThat(head.checksumSHA256()).isEqualTo(base64(sha256(whole)));
        assertThat(head.checksumType()).isEqualTo(ChecksumType.FULL_OBJECT);
        assertThat(head.eTag()).isEqualTo(expectedETag);
    }

    @Test
    @Order(5)
    @DisplayName("Transfer manager upload with SHA256 reports the composite checksum")
    void transferManagerSha256UploadReportsCompositeChecksum() throws NoSuchAlgorithmException {
        String key = KEYS.get(3);
        byte[] whole = concat(PART_1, PART_2);
        transferManagerUpload(key, whole, ChecksumAlgorithm.SHA256);

        HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                .bucket(BUCKET).key(key).checksumMode(ChecksumMode.ENABLED).build());
        GetObjectAttributesResponse attributes = s3.getObjectAttributes(GetObjectAttributesRequest.builder()
                .bucket(BUCKET).key(key).objectAttributes(ObjectAttributes.CHECKSUM, ObjectAttributes.OBJECT_PARTS).build());
        assertThat(attributes.objectParts().totalPartsCount()).isGreaterThan(1);

        // Cut the payload at the part sizes S3 reports, as the AWS validation example does, so the test
        // does not depend on the part size the SDK chose.
        byte[][] parts = sliceByReportedParts(whole, attributes.objectParts().parts());
        String composite = compositeSha256(parts);
        assertThat(head.checksumSHA256()).isEqualTo(composite);
        assertThat(head.checksumType()).isEqualTo(ChecksumType.COMPOSITE);
        assertThat(attributes.checksum().checksumSHA256()).isEqualTo(composite.substring(0, composite.lastIndexOf('-')));
    }

    @Test
    @Order(6)
    @DisplayName("Transfer manager upload without an algorithm stores what the SDK declares by default")
    void transferManagerDefaultChecksum() {
        String key = KEYS.get(4);
        byte[] whole = concat(PART_1, PART_2);
        transferManagerUpload(key, whole, null);

        // Which algorithm and type the SDK picks depends on its version; whatever it sent, the stored
        // value must be the one S3 would compute for that combination.
        HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                .bucket(BUCKET).key(key).checksumMode(ChecksumMode.ENABLED).build());
        if (head.checksumCRC32() != null && head.checksumType() == ChecksumType.COMPOSITE) {
            GetObjectAttributesResponse attributes = s3.getObjectAttributes(GetObjectAttributesRequest.builder()
                    .bucket(BUCKET).key(key).objectAttributes(ObjectAttributes.OBJECT_PARTS).build());
            byte[][] parts = sliceByReportedParts(whole, attributes.objectParts().parts());
            assertThat(head.checksumCRC32()).isEqualTo(compositeCrc32(parts));
        } else if (head.checksumCRC32() != null) {
            assertThat(head.checksumType()).isEqualTo(ChecksumType.FULL_OBJECT);
            assertThat(head.checksumCRC32()).isEqualTo(base64(crc32(whole)));
        } else {
            assertThat(head.checksumType()).isEqualTo(ChecksumType.FULL_OBJECT);
            assertThat(head.checksumCRC64NVME()).isEqualTo(base64(crc64Nvme(whole)));
        }
        System.out.printf("transfer manager default: CRC32=%s CRC64NVME=%s type=%s%n",
                head.checksumCRC32(), head.checksumCRC64NVME(), head.checksumType());
    }

    private static void transferManagerUpload(String key, byte[] data, ChecksumAlgorithm algorithm) {
        try (S3AsyncClient async = TestFixtures.s3MultipartAsyncClient();
             S3TransferManager transferManager = S3TransferManager.builder().s3Client(async).build()) {
            transferManager.upload(UploadRequest.builder()
                    .putObjectRequest(b -> {
                        b.bucket(BUCKET).key(key);
                        if (algorithm != null) {
                            b.checksumAlgorithm(algorithm);
                        }
                    })
                    .requestBody(AsyncRequestBody.fromBytes(data))
                    .build()).completionFuture().join();
        }
    }

    private static byte[][] sliceByReportedParts(byte[] whole, List<ObjectPart> reported) {
        byte[][] parts = new byte[reported.size()][];
        int offset = 0;
        for (int i = 0; i < reported.size(); i++) {
            int size = reported.get(i).size().intValue();
            parts[i] = java.util.Arrays.copyOfRange(whole, offset, offset + size);
            offset += size;
        }
        assertThat(offset).isEqualTo(whole.length);
        return parts;
    }

    private static String compositeCrc32(byte[]... parts) {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            joined.writeBytes(crc32(part));
        }
        return base64(crc32(joined.toByteArray())) + "-" + parts.length;
    }

    /** CRC-64/NVME (reflected polynomial 0x9a6c9329ac4bc9b5), what S3 attaches when no algorithm is declared. */
    private static byte[] crc64Nvme(byte[] data) {
        long[] table = new long[256];
        for (int i = 0; i < 256; i++) {
            long c = i;
            for (int j = 0; j < 8; j++) {
                c = (c & 1) != 0 ? (c >>> 1) ^ 0x9a6c9329ac4bc9b5L : c >>> 1;
            }
            table[i] = c;
        }
        long c = -1L;
        for (byte b : data) {
            c = table[(int) ((c ^ b) & 0xff)] ^ (c >>> 8);
        }
        c = ~c;
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (c >>> (56 - 8 * i));
        }
        return out;
    }

    private static byte[] payload(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static byte[] sha256(byte[] data) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static String compositeSha256(byte[]... parts) throws NoSuchAlgorithmException {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            joined.writeBytes(sha256(part));
        }
        return base64(sha256(joined.toByteArray())) + "-" + parts.length;
    }

    private static byte[] crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        long value = crc.getValue();
        return new byte[]{(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    private static String base64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }
}
