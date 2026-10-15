package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumType;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("S3 multipart minimum part size")
class S3MultipartMinimumPartSizeTest {

    @Test
    void undersizedPartPreservesTheObjectAndUploadForRetry() {
        String bucket = TestFixtures.uniqueName("sdk-multipart-minimum");
        String key = "object";
        try (S3Client s3 = TestFixtures.s3Client()) {
            s3.createBucket(request -> request.bucket(bucket));
            try {
                s3.putObject(request -> request.bucket(bucket).key(key), RequestBody.fromString("prior"));
                String uploadId = s3.createMultipartUpload(request -> request.bucket(bucket).key(key)
                        .checksumAlgorithm(ChecksumAlgorithm.CRC32).checksumType(ChecksumType.FULL_OBJECT)).uploadId();
                String firstETag = s3.uploadPart(request -> request.bucket(bucket).key(key).uploadId(uploadId)
                        .partNumber(1), RequestBody.fromString("data")).eTag();
                String lastETag = s3.uploadPart(request -> request.bucket(bucket).key(key).uploadId(uploadId)
                        .partNumber(2), RequestBody.fromString("last")).eTag();
                assertThatThrownBy(() -> s3.completeMultipartUpload(request -> request.bucket(bucket).key(key)
                        .uploadId(uploadId).multipartUpload(parts(firstETag, lastETag))))
                        .isInstanceOfSatisfying(S3Exception.class, error -> {
                            assertThat(error.statusCode()).isEqualTo(400);
                            assertThat(error.awsErrorDetails().errorCode()).isEqualTo("EntityTooSmall");
                            assertThat(error.awsErrorDetails().errorMessage())
                                    .isEqualTo("Your proposed upload is smaller than the minimum allowed size");
                        });
                assertThat(s3.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asUtf8String())
                        .isEqualTo("prior");
                assertThat(s3.listParts(request -> request.bucket(bucket).key(key).uploadId(uploadId)).parts())
                        .hasSize(2);

                String replacementETag = s3.uploadPart(request -> request.bucket(bucket).key(key).uploadId(uploadId)
                        .partNumber(1), RequestBody.fromBytes(new byte[5242880])).eTag();
                s3.completeMultipartUpload(request -> request.bucket(bucket).key(key).uploadId(uploadId)
                        .multipartUpload(parts(replacementETag, lastETag)));
                assertThat(s3.headObject(request -> request.bucket(bucket).key(key)).contentLength()).isEqualTo(5242884);
            } finally {
                s3.listMultipartUploads(request -> request.bucket(bucket)).uploads().forEach(upload ->
                        s3.abortMultipartUpload(request -> request.bucket(bucket).key(upload.key())
                                .uploadId(upload.uploadId())));
                s3.deleteObject(request -> request.bucket(bucket).key(key));
                s3.deleteBucket(request -> request.bucket(bucket));
            }
        }
    }

    private static CompletedMultipartUpload parts(String firstETag, String lastETag) {
        return CompletedMultipartUpload.builder().parts(
                CompletedPart.builder().partNumber(1).eTag(firstETag).build(),
                CompletedPart.builder().partNumber(2).eTag(lastETag).build()).build();
    }
}
