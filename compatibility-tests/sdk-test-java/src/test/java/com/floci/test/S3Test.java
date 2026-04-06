package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketLocationConstraint;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.CreateBucketConfiguration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketTaggingRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest;
import software.amazon.awssdk.services.s3.model.GetBucketLocationResponse;
import software.amazon.awssdk.services.s3.model.GetBucketTaggingRequest;
import software.amazon.awssdk.services.s3.model.GetBucketTaggingResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutBucketTaggingRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.Tagging;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

@DisplayName("S3 Simple Storage Service")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3Test {

    private static S3Client s3;
    private static final String BUCKET = "sdk-test-bucket";
    private static final String EU_BUCKET = "sdk-test-bucket-eu";
    private static final String KEY = "test-file.txt";
    private static final String CONTENT = "Hello from AWS SDK v2!";

    @BeforeAll
    static void setup() {
        s3 = TestFixtures.s3Client();
    }

    @AfterAll
    static void cleanup() {
        if (s3 != null) {
            try {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(KEY).build());
            } catch (Exception ignored) {}
            try {
                s3.deleteBucket(DeleteBucketRequest.builder().bucket(BUCKET).build());
            } catch (Exception ignored) {}
            try {
                s3.deleteBucket(DeleteBucketRequest.builder().bucket(EU_BUCKET).build());
            } catch (Exception ignored) {}
            s3.close();
        }
    }

    @Test
    @Order(1)
    void createBucket() {
        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @Test
    @Order(2)
    void createBucketWithLocationConstraint() {
        s3.createBucket(CreateBucketRequest.builder()
                .bucket(EU_BUCKET)
                .createBucketConfiguration(CreateBucketConfiguration.builder()
                        .locationConstraint(BucketLocationConstraint.EU_CENTRAL_1)
                        .build())
                .build());
    }

    @Test
    @Order(3)
    void getBucketLocationEuCentral1() {
        GetBucketLocationResponse response = s3.getBucketLocation(
                GetBucketLocationRequest.builder().bucket(EU_BUCKET).build());

        assertThat(response.locationConstraint()).isEqualTo(BucketLocationConstraint.EU_CENTRAL_1);
    }

    @Test
    @Order(4)
    void listBuckets() {
        ListBucketsResponse response = s3.listBuckets();

        assertThat(response.buckets())
                .anyMatch(b -> BUCKET.equals(b.name()));
    }

    @Test
    @Order(5)
    void putObject() {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(BUCKET).key(KEY).contentType("text/plain").build(),
                RequestBody.fromString(CONTENT));
    }

    @Test
    @Order(6)
    void listObjects() {
        ListObjectsV2Response response = s3.listObjectsV2(
                ListObjectsV2Request.builder().bucket(BUCKET).build());

        assertThat(response.contents())
                .anyMatch(o -> KEY.equals(o.key()));
    }

    @Test
    @Order(7)
    void getObject() throws Exception {
        var response = s3.getObject(GetObjectRequest.builder()
                .bucket(BUCKET).key(KEY).build());
        byte[] data = response.readAllBytes();
        String downloaded = new String(data, StandardCharsets.UTF_8);

        assertThat(downloaded).isEqualTo(CONTENT);
    }

    @Test
    @Order(8)
    void headObject() {
        HeadObjectResponse response = s3.headObject(HeadObjectRequest.builder()
                .bucket(BUCKET).key(KEY).build());

        assertThat(response.contentLength()).isEqualTo(CONTENT.length());
    }

    @Test
    @Order(9)
    void headObjectLastModifiedSecondPrecision() {
        HeadObjectResponse response = s3.headObject(HeadObjectRequest.builder()
                .bucket(BUCKET).key(KEY).build());

        assertThat(response.lastModified()).isNotNull();
        assertThat(response.lastModified().getNano()).isZero();
    }

    @Test
    @Order(10)
    void headBucket() {
        HeadBucketResponse response = s3.headBucket(HeadBucketRequest.builder()
                .bucket(BUCKET).build());

        assertThat(response.sdkHttpResponse().isSuccessful()).isTrue();
    }

    @Test
    @Order(11)
    void headBucketNonExistent() {
        assertThatThrownBy(() -> s3.headBucket(HeadBucketRequest.builder()
                .bucket("non-existent-bucket-xyz").build()))
                .satisfiesAnyOf(
                        e -> assertThat(e).isInstanceOf(NoSuchBucketException.class),
                        e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(404)
                );
    }

    @Test
    @Order(12)
    void getBucketLocation() {
        GetBucketLocationResponse response = s3.getBucketLocation(
                GetBucketLocationRequest.builder().bucket(BUCKET).build());

        // Either locationConstraint or locationConstraintAsString should be non-null
        assertThat(response.locationConstraint() != null || response.locationConstraintAsString() != null).isTrue();
    }

    @Test
    @Order(13)
    void putObjectTagging() {
        s3.putObjectTagging(PutObjectTaggingRequest.builder()
                .bucket(BUCKET).key(KEY)
                .tagging(Tagging.builder()
                        .tagSet(
                                software.amazon.awssdk.services.s3.model.Tag.builder().key("env").value("test").build(),
                                software.amazon.awssdk.services.s3.model.Tag.builder().key("project").value("floci").build()
                        )
                        .build())
                .build());
    }

    @Test
    @Order(14)
    void getObjectTagging() {
        GetObjectTaggingResponse response = s3.getObjectTagging(
                GetObjectTaggingRequest.builder().bucket(BUCKET).key(KEY).build());

        assertThat(response.tagSet()).hasSize(2);
        assertThat(response.tagSet())
                .anyMatch(t -> "env".equals(t.key()) && "test".equals(t.value()))
                .anyMatch(t -> "project".equals(t.key()) && "floci".equals(t.value()));
    }

    @Test
    @Order(15)
    void deleteObjectTagging() {
        s3.deleteObjectTagging(DeleteObjectTaggingRequest.builder()
                .bucket(BUCKET).key(KEY).build());

        GetObjectTaggingResponse response = s3.getObjectTagging(
                GetObjectTaggingRequest.builder().bucket(BUCKET).key(KEY).build());

        assertThat(response.tagSet()).isEmpty();
    }

    @Test
    @Order(16)
    void putBucketTagging() {
        s3.putBucketTagging(PutBucketTaggingRequest.builder()
                .bucket(BUCKET)
                .tagging(Tagging.builder()
                        .tagSet(
                                software.amazon.awssdk.services.s3.model.Tag.builder().key("team").value("backend").build(),
                                software.amazon.awssdk.services.s3.model.Tag.builder().key("cost-center").value("123").build()
                        )
                        .build())
                .build());
    }

    @Test
    @Order(17)
    void getBucketTagging() {
        GetBucketTaggingResponse response = s3.getBucketTagging(
                GetBucketTaggingRequest.builder().bucket(BUCKET).build());

        assertThat(response.tagSet()).hasSize(2);
        assertThat(response.tagSet())
                .anyMatch(t -> "team".equals(t.key()) && "backend".equals(t.value()))
                .anyMatch(t -> "cost-center".equals(t.key()) && "123".equals(t.value()));
    }

    @Test
    @Order(18)
    void deleteBucketTagging() {
        s3.deleteBucketTagging(DeleteBucketTaggingRequest.builder().bucket(BUCKET).build());

        GetBucketTaggingResponse response = s3.getBucketTagging(
                GetBucketTaggingRequest.builder().bucket(BUCKET).build());

        assertThat(response.tagSet()).isEmpty();
    }

    @Test
    @Order(19)
    void copyObjectCrossBucket() throws Exception {
        String destBucket = "sdk-test-bucket-copy";
        String destKey = "copied-file.txt";

        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(destBucket).build());

            CopyObjectResponse response = s3.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(BUCKET).sourceKey(KEY)
                    .destinationBucket(destBucket).destinationKey(destKey)
                    .build());

            assertThat(response.copyObjectResult().eTag()).isNotNull();

            // Verify copied content
            var getResponse = s3.getObject(GetObjectRequest.builder()
                    .bucket(destBucket).key(destKey).build());
            String downloaded = new String(getResponse.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(downloaded).isEqualTo(CONTENT);
        } finally {
            try {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(destBucket).key(destKey).build());
                s3.deleteBucket(DeleteBucketRequest.builder().bucket(destBucket).build());
            } catch (Exception ignored) {}
        }
    }

    @Test
    @Order(19)
    void copyObjectNonAsciiKey() throws Exception {
        String srcKey = "src/テスト画像.png";
        String dstKey = "dst/テスト画像.png";
        String srcBucket = BUCKET;
        String dstBucket = "sdk-test-bucket-copy-unicode";

        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(dstBucket).build());

            // Put source object with non-ASCII key
            s3.putObject(PutObjectRequest.builder()
                            .bucket(srcBucket).key(srcKey).build(),
                    RequestBody.fromString("non-ascii content"));

            // Copy with non-ASCII key
            CopyObjectResponse response = s3.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(srcBucket).sourceKey(srcKey)
                    .destinationBucket(dstBucket).destinationKey(dstKey)
                    .build());

            assertThat(response.copyObjectResult().eTag()).isNotNull();

            // Verify copied content
            var getResponse = s3.getObject(GetObjectRequest.builder()
                    .bucket(dstBucket).key(dstKey).build());
            String downloaded = new String(getResponse.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(downloaded).isEqualTo("non-ascii content");
        } finally {
            try {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(srcBucket).key(srcKey).build());
                s3.deleteObject(DeleteObjectRequest.builder().bucket(dstBucket).key(dstKey).build());
                s3.deleteBucket(DeleteBucketRequest.builder().bucket(dstBucket).build());
            } catch (Exception ignored) {}
        }
    }

    @Test
    @Order(20)
    void deleteObjectsBatch() {
        // Create batch objects
        for (int i = 1; i <= 3; i++) {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(BUCKET).key("batch-" + i + ".txt").build(),
                    RequestBody.fromString("batch content " + i));
        }

        DeleteObjectsResponse response = s3.deleteObjects(DeleteObjectsRequest.builder()
                .bucket(BUCKET)
                .delete(Delete.builder()
                        .objects(
                                ObjectIdentifier.builder().key("batch-1.txt").build(),
                                ObjectIdentifier.builder().key("batch-2.txt").build(),
                                ObjectIdentifier.builder().key("batch-3.txt").build()
                        )
                        .build())
                .build());

        assertThat(response.deleted()).hasSize(3);
    }

    @Test
    @Order(21)
    void verifyBatchDelete() {
        ListObjectsV2Response response = s3.listObjectsV2(
                ListObjectsV2Request.builder().bucket(BUCKET).build());

        assertThat(response.contents()).hasSize(1);
        assertThat(response.contents().get(0).key()).isEqualTo(KEY);
    }

    @Test
    @Order(22)
    void deleteObject() {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(KEY).build());
    }

    @Test
    @Order(23)
    void verifyObjectDeleted() {
        ListObjectsV2Response response = s3.listObjectsV2(
                ListObjectsV2Request.builder().bucket(BUCKET).build());

        assertThat(response.contents()).isEmpty();
    }

    @Test
    @Order(24)
    void deleteEuBucket() {
        s3.deleteBucket(DeleteBucketRequest.builder().bucket(EU_BUCKET).build());
    }

    @Test
    @Order(25)
    void deleteBucket() {
        s3.deleteBucket(DeleteBucketRequest.builder().bucket(BUCKET).build());
    }
}
