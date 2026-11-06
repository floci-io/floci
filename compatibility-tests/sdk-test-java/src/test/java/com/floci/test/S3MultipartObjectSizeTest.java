package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumType;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("S3 multipart object size validation")
class S3MultipartObjectSizeTest {

    @ParameterizedTest
    @ValueSource(longs = {-1, Long.MIN_VALUE, 3, 5})
    void invalidObjectSizePreservesTheObjectAndUploadForRetry(long expectedSize) {
        String bucket = TestFixtures.uniqueName("sdk-multipart-size");
        String key = "object";
        try (S3Client s3 = TestFixtures.s3Client()) {
            s3.createBucket(request -> request.bucket(bucket));
            try {
                s3.putObject(request -> request.bucket(bucket).key(key), RequestBody.fromString("prior"));
                String uploadId = s3.createMultipartUpload(request -> request.bucket(bucket).key(key)
                        .checksumAlgorithm(ChecksumAlgorithm.CRC32).checksumType(ChecksumType.FULL_OBJECT)).uploadId();
                String eTag = s3.uploadPart(request -> request.bucket(bucket).key(key).uploadId(uploadId)
                        .partNumber(1), RequestBody.fromString("data")).eTag();
                s3.uploadPart(request -> request.bucket(bucket).key(key).uploadId(uploadId).partNumber(2),
                        RequestBody.fromString("unused"));
                CompletedMultipartUpload parts = CompletedMultipartUpload.builder()
                        .parts(CompletedPart.builder().partNumber(1).eTag(eTag).build()).build();
                assertThatThrownBy(() -> s3.completeMultipartUpload(request -> request.bucket(bucket).key(key)
                        .uploadId(uploadId).multipartUpload(parts).mpuObjectSize(expectedSize)))
                        .isInstanceOfSatisfying(S3Exception.class, error -> {
                            assertThat(error.statusCode()).isEqualTo(400);
                            assertThat(error.awsErrorDetails().errorCode()).isEqualTo("InvalidRequest");
                            String expectedMessage = expectedSize < 0
                                    ? "Value for x-amz-mp-object-size header is less than zero: '" + expectedSize + "'"
                                    : "The provided 'x-amz-mp-object-size' header value " + expectedSize
                                            + " does not match what was computed: 4";
                            assertThat(error.awsErrorDetails().errorMessage()).isEqualTo(expectedMessage);
                        });
                assertThat(s3.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asUtf8String())
                        .isEqualTo("prior");
                assertThat(s3.listParts(request -> request.bucket(bucket).key(key).uploadId(uploadId)).parts())
                        .hasSize(2);
                s3.completeMultipartUpload(request -> request.bucket(bucket).key(key).uploadId(uploadId)
                        .multipartUpload(parts).mpuObjectSize(4L));
                assertThat(s3.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asUtf8String())
                        .isEqualTo("data");
            } finally {
                s3.listMultipartUploads(request -> request.bucket(bucket)).uploads().forEach(upload ->
                        s3.abortMultipartUpload(request -> request.bucket(bucket).key(upload.key())
                                .uploadId(upload.uploadId())));
                s3.deleteObject(request -> request.bucket(bucket).key(key));
                s3.deleteBucket(request -> request.bucket(bucket));
            }
        }
    }
}
