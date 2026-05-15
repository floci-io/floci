package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies S3 virtual-hosted style addressing via s3.localhost.localstack.cloud.
 *
 * The SDK sends requests to {@code <bucket>.s3.localhost.localstack.cloud:<port>},
 * which resolves to 127.0.0.1 via LocalStack's public wildcard DNS. Floci's
 * S3VirtualHostFilter extracts the bucket name from the Host header and rewrites
 * the request to path-style internally.
 *
 * All tests are skipped when s3.localhost.localstack.cloud is not resolvable
 * (e.g., no DNS / offline environment).
 */
@DisplayName("S3 Virtual-Hosted Style Addressing")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3VirtualHostStyleTest {

    private static S3Client s3;
    private static final String BUCKET = "vhost-test-bucket";
    private static final String KEY = "test-object.txt";
    private static final String COPIED_KEY = "copied-object.txt";
    private static final String CONTENT = "Hello from virtual-hosted style!";

    @BeforeAll
    static void setup() {
        assumeTrue(TestFixtures.isS3VirtualHostResolvable(),
                "Skipping: S3 virtual-host DNS is not resolvable (set FLOCI_S3_VHOST_ENDPOINT or ensure s3.localhost.localstack.cloud resolves)");
        s3 = TestFixtures.s3VirtualHostClient();
    }

    @AfterAll
    static void cleanup() {
        if (s3 != null) {
            try {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(KEY).build());
            } catch (Exception ignored) {}
            try {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(COPIED_KEY).build());
            } catch (Exception ignored) {}
            try {
                s3.deleteBucket(DeleteBucketRequest.builder().bucket(BUCKET).build());
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
    void headBucket() {
        HeadBucketResponse response = s3.headBucket(HeadBucketRequest.builder()
                .bucket(BUCKET).build());

        assertThat(response.sdkHttpResponse().isSuccessful()).isTrue();
    }

    @Test
    @Order(3)
    void headBucketNonExistent() {
        assertThatThrownBy(() -> s3.headBucket(HeadBucketRequest.builder()
                .bucket("vhost-nonexistent-xyz").build()))
                .satisfiesAnyOf(
                        e -> assertThat(e).isInstanceOf(NoSuchBucketException.class),
                        e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(404)
                );
    }

    @Test
    @Order(4)
    void putObject() {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(BUCKET).key(KEY).contentType("text/plain").build(),
                RequestBody.fromString(CONTENT));
    }

    @Test
    @Order(5)
    void headObject() {
        HeadObjectResponse response = s3.headObject(HeadObjectRequest.builder()
                .bucket(BUCKET).key(KEY).build());

        assertThat(response.contentLength()).isEqualTo(CONTENT.length());
        assertThat(response.lastModified()).isNotNull();
    }

    @Test
    @Order(6)
    void getObject() throws Exception {
        var response = s3.getObject(GetObjectRequest.builder()
                .bucket(BUCKET).key(KEY).build());
        String downloaded = new String(response.readAllBytes(), StandardCharsets.UTF_8);

        assertThat(downloaded).isEqualTo(CONTENT);
    }

    @Test
    @Order(7)
    void listObjectsV2() {
        ListObjectsV2Response response = s3.listObjectsV2(
                ListObjectsV2Request.builder().bucket(BUCKET).build());

        assertThat(response.contents())
                .anyMatch(o -> KEY.equals(o.key()));
    }

    @Test
    @Order(8)
    void copyObject() throws Exception {
        CopyObjectResponse response = s3.copyObject(CopyObjectRequest.builder()
                .sourceBucket(BUCKET).sourceKey(KEY)
                .destinationBucket(BUCKET).destinationKey(COPIED_KEY)
                .build());

        assertThat(response.copyObjectResult().eTag()).isNotNull();

        var getResponse = s3.getObject(GetObjectRequest.builder()
                .bucket(BUCKET).key(COPIED_KEY).build());
        String downloaded = new String(getResponse.readAllBytes(), StandardCharsets.UTF_8);
        assertThat(downloaded).isEqualTo(CONTENT);
    }

    @Test
    @Order(9)
    void deleteObject() {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(KEY).build());
        s3.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(COPIED_KEY).build());

        ListObjectsV2Response response = s3.listObjectsV2(
                ListObjectsV2Request.builder().bucket(BUCKET).build());
        assertThat(response.contents()).isEmpty();
    }

    @Test
    @Order(10)
    void deleteBucket() {
        s3.deleteBucket(DeleteBucketRequest.builder().bucket(BUCKET).build());
    }

}
