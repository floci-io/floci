package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.BucketLocationConstraint;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.CreateBucketConfiguration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketTaggingRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetBucketAclRequest;
import software.amazon.awssdk.services.s3.model.GetBucketAclResponse;
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest;
import software.amazon.awssdk.services.s3.model.GetBucketLocationResponse;
import software.amazon.awssdk.services.s3.model.GetBucketTaggingRequest;
import software.amazon.awssdk.services.s3.model.GetBucketTaggingResponse;
import software.amazon.awssdk.services.s3.model.GetObjectAclRequest;
import software.amazon.awssdk.services.s3.model.GetObjectAclResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.Permission;
import software.amazon.awssdk.services.s3.model.PutBucketTaggingRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3.model.Type;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.*;

@DisplayName("S3 Simple Storage Service")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3Test {

    private static S3Client s3;
    private static final String BUCKET = "sdk-test-bucket";
    private static final String LOCATED_BUCKET = "sdk-test-bucket-located";
    // Only the us-east-1 endpoint creates a bucket in another region; any other endpoint takes its own.
    // A string, not the SDK enum: the enum has no value for the ISO and EUSC regions, and an unknown
    // one would be sent as the literal "null".
    private static final String LOCATION = TestFixtures.region().id().equals("us-east-1")
            ? "eu-central-1"
            : TestFixtures.region().id();
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
                s3.deleteBucket(DeleteBucketRequest.builder().bucket(LOCATED_BUCKET).build());
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
                .bucket(LOCATED_BUCKET)
                .createBucketConfiguration(CreateBucketConfiguration.builder()
                        .locationConstraint(LOCATION)
                        .build())
                .build());
    }

    @Test
    @Order(3)
    void getBucketLocationReturnsTheConstraint() {
        GetBucketLocationResponse response = s3.getBucketLocation(
                GetBucketLocationRequest.builder().bucket(LOCATED_BUCKET).build());

        assertThat(response.locationConstraintAsString()).isEqualTo(LOCATION);
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
        ResponseInputStream<GetObjectResponse> response = s3.getObject(GetObjectRequest.builder()
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

        assertThatThrownBy(() -> s3.getBucketTagging(
                GetBucketTaggingRequest.builder().bucket(BUCKET).build()))
                .isInstanceOfSatisfying(S3Exception.class,
                        e -> assertThat(e.awsErrorDetails().errorCode()).isEqualTo("NoSuchTagSet"));
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
            ResponseInputStream<GetObjectResponse> getResponse = s3.getObject(GetObjectRequest.builder()
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
            ResponseInputStream<GetObjectResponse> getResponse = s3.getObject(GetObjectRequest.builder()
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
    void deleteLocatedBucket() {
        s3.deleteBucket(DeleteBucketRequest.builder().bucket(LOCATED_BUCKET).build());
    }

    @Test
    @Order(25)
    void deleteBucket() {
        s3.deleteBucket(DeleteBucketRequest.builder().bucket(BUCKET).build());
    }

    @Test
    @Order(26)
    void putObjectWithContentEncodingDoesNotBreakSdkDecompression() throws IOException {
        String bucket = TestFixtures.uniqueName("gzip-bucket");
        String key = "gzipped.json";
        byte[] body;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write("{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8));
            gz.finish();
            body = baos.toByteArray();
        }
        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        try {
            PutObjectResponse putResponse = s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket).key(key)
                            .contentEncoding("gzip")
                            .contentType("application/json")
                            .build(),
                    RequestBody.fromBytes(body));

            assertThat(putResponse.eTag()).isNotBlank();

            // HEAD response has a body, so Content-Encoding belongs there.
            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket).key(key).build());
            assertThat(head.contentEncoding()).isEqualTo("gzip");
        } finally {
            try {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            } catch (Exception ignored) {}
            try {
                s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
            } catch (Exception ignored) {}
        }
    }

    @Test
    @Order(27)
    void putObjectPersistsInlineTaggingHeader() {
        String bucket = TestFixtures.uniqueName("inline-tag-bucket");
        String key = "inline-tagged.txt";
        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket).key(key)
                            .tagging(Tagging.builder()
                                    .tagSet(
                                            software.amazon.awssdk.services.s3.model.Tag.builder().key("env").value("test").build(),
                                            software.amazon.awssdk.services.s3.model.Tag.builder().key("project").value("floci").build()
                                    )
                                    .build())
                            .build(),
                    RequestBody.fromString("inline-tagged content"));

            GetObjectTaggingResponse response = s3.getObjectTagging(
                    GetObjectTaggingRequest.builder().bucket(bucket).key(key).build());

            assertThat(response.tagSet()).hasSize(2);
            assertThat(response.tagSet())
                    .anyMatch(t -> "env".equals(t.key()) && "test".equals(t.value()))
                    .anyMatch(t -> "project".equals(t.key()) && "floci".equals(t.value()));
        } finally {
            try {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            } catch (Exception ignored) {}
            try {
                s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
            } catch (Exception ignored) {}
        }
    }

    @Test
    @Order(28)
    void putObjectInlineTaggingRejectsMalformedPair() {
        assertInlineTaggingHeaderRejected("nokeyhasvalue", 400, "InvalidArgument");
    }

    @Test
    @Order(29)
    void putObjectInlineTaggingRejectsEmptyKey() {
        assertInlineTaggingHeaderRejected("=value", 400, "InvalidArgument");
    }

    @Test
    @Order(30)
    void putObjectInlineTaggingRejectsDuplicateKey() {
        assertInlineTaggingHeaderRejected("env=a&env=b", 400, "InvalidArgument");
    }

    @Test
    @Order(31)
    void putObjectInlineTaggingRejectsTooManyTags() {
        StringBuilder header = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            if (i > 0) header.append('&');
            header.append("k").append(i).append("=v").append(i);
        }
        assertInlineTaggingHeaderRejected(header.toString(), 400, "BadRequest");
    }

    @Test
    @Order(32)
    void putObjectInlineTaggingRejectsOversizedHeader() {
        // The size check runs before pair parsing, so a single huge value trips it
        // without first being rejected by the >10-tag rule.
        String huge = "k=" + "v".repeat(8 * 1024 + 1);
        assertInlineTaggingHeaderRejected(huge, 400, "InvalidArgument");
    }

    @Test
    @Order(33)
    void getObjectRangeRoundTripsThroughSdk() {
        String bucket = TestFixtures.uniqueName("range-sdk-bucket");
        String key = "range.txt";
        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket).key(key).build(),
                    RequestBody.fromString(CONTENT));

            ResponseBytes<GetObjectResponse> response = s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .range("bytes=6-13")
                    .build());

            assertThat(response.asUtf8String()).isEqualTo("from AWS");
            assertThat(response.response().contentLength()).isEqualTo(8);
            assertThat(response.response().contentRange()).isEqualTo("bytes 6-13/" + CONTENT.length());

            ResponseBytes<GetObjectResponse> multiRangeResponse = s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .range("bytes=0-1,3-4,7-9")
                    .build());

            assertThat(multiRangeResponse.response().sdkHttpResponse().statusCode()).isEqualTo(200);
            assertThat(multiRangeResponse.asUtf8String()).isEqualTo(CONTENT);
            assertThat(multiRangeResponse.response().contentLength()).isEqualTo(CONTENT.length());
            assertThat(multiRangeResponse.response().contentRange()).isNull();
            assertThat(multiRangeResponse.response().sdkHttpResponse().firstMatchingHeader("Content-Range")).isEmpty();
        } finally {
            try {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            } catch (Exception ignored) {}
            try {
                s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
            } catch (Exception ignored) {}
        }
    }

    @Test
    @Order(34)
    void getObjectPartRoundTripsThroughSdk() throws Exception {
        String bucket = TestFixtures.uniqueName("part-sdk-bucket");
        String key = "parts.bin";
        byte[] firstPart = new byte[5 * 1024 * 1024];
        Arrays.fill(firstPart, (byte) 'a');
        String secondPart = "test content 123";
        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        // Closed in reverse order: the object, then its bucket.
        try (AutoCloseable deleteBucket = () ->
                    s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
                AutoCloseable deleteObject = () ->
                    s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())) {
            String uploadId = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                    .bucket(bucket).key(key).build()).uploadId();
            String firstETag = s3.uploadPart(UploadPartRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId).partNumber(1).build(),
                    RequestBody.fromBytes(firstPart)).eTag();
            String secondETag = s3.uploadPart(UploadPartRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId).partNumber(2).build(),
                    RequestBody.fromString(secondPart)).eTag();
            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(
                            CompletedPart.builder().partNumber(1).eTag(firstETag).build(),
                            CompletedPart.builder().partNumber(2).eTag(secondETag).build()).build())
                    .build());
            long size = firstPart.length + secondPart.length();

            ResponseBytes<GetObjectResponse> part = s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket).key(key).partNumber(2).build());

            assertThat(part.asUtf8String()).isEqualTo(secondPart);
            assertThat(part.response().partsCount()).isEqualTo(2);
            assertThat(part.response().contentLength()).isEqualTo(16);
            assertThat(part.response().contentRange()).isEqualTo("bytes 5242880-5242895/" + size);

            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket).key(key).partNumber(1).build());

            assertThat(head.partsCount()).isEqualTo(2);
            assertThat(head.contentLength()).isEqualTo(firstPart.length);

            assertThatThrownBy(() -> s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket).key(key).partNumber(3).build()))
                    .isInstanceOfSatisfying(S3Exception.class, e -> {
                        assertThat(e.statusCode()).isEqualTo(416);
                        assertThat(e.awsErrorDetails().errorCode()).isEqualTo("InvalidPartNumber");
                    });
        }
    }

    @Test
    @Order(35)
    void ownerAndGranteesAreTheAccountsCanonicalUserId() {
        // S3 identifies the owner of buckets and objects, and CanonicalUser ACL grantees, by the
        // account's 64-hex canonical user ID, the same value in ListBuckets, both ACLs and the
        // multipart listing. The value itself is opaque on AWS, so the SDK test pins the form
        // and the cross-response identity, not a specific derivation.
        String bucket = TestFixtures.uniqueName("canonical-owner-bucket");
        String key = "owned.txt";
        String multipartKey = "owned.bin";
        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        try {
            String ownerId = s3.listBuckets().owner().id();
            assertThat(ownerId).matches("[0-9a-f]{64}");

            GetBucketAclResponse bucketAcl = s3.getBucketAcl(
                    GetBucketAclRequest.builder().bucket(bucket).build());
            assertThat(bucketAcl.owner().id()).isEqualTo(ownerId);
            assertThat(bucketAcl.grants()).singleElement().satisfies(grant -> {
                assertThat(grant.grantee().id()).isEqualTo(ownerId);
                assertThat(grant.grantee().type()).isEqualTo(Type.CANONICAL_USER);
                assertThat(grant.permission()).isEqualTo(Permission.FULL_CONTROL);
            });

            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromString(CONTENT));
            GetObjectAclResponse objectAcl = s3.getObjectAcl(
                    GetObjectAclRequest.builder().bucket(bucket).key(key).build());
            assertThat(objectAcl.owner().id()).isEqualTo(ownerId);
            assertThat(objectAcl.grants()).singleElement().satisfies(grant -> {
                assertThat(grant.grantee().id()).isEqualTo(ownerId);
                assertThat(grant.grantee().type()).isEqualTo(Type.CANONICAL_USER);
                assertThat(grant.permission()).isEqualTo(Permission.FULL_CONTROL);
            });

            String uploadId = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                    .bucket(bucket).key(multipartKey).build()).uploadId();
            s3.uploadPart(UploadPartRequest.builder().bucket(bucket).key(multipartKey)
                    .uploadId(uploadId).partNumber(1).build(), RequestBody.fromString("part"));
            ListPartsResponse parts = s3.listParts(ListPartsRequest.builder()
                    .bucket(bucket).key(multipartKey).uploadId(uploadId).build());
            assertThat(parts.initiator().id()).isEqualTo(ownerId);
            assertThat(parts.owner().id()).isEqualTo(ownerId);
            s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket).key(multipartKey).uploadId(uploadId).build());
        } finally {
            deleteObjectAndBucketQuietly(bucket, key);
        }
    }

    @Test
    @Order(36)
    void multipartUploadKeepsContentEncodingAndCacheControl() throws IOException {
        String bucket = TestFixtures.uniqueName("mpu-encoding-bucket");
        String key = "encoded.bin";
        byte[] body;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write("version: 1.0.0".getBytes(StandardCharsets.UTF_8));
            gz.finish();
            body = baos.toByteArray();
        }
        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        try {
            String uploadId = s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                    .bucket(bucket).key(key)
                    .contentType("text/plain")
                    .contentEncoding("gzip")
                    .cacheControl("no-cache")
                    .build()).uploadId();
            String eTag = s3.uploadPart(UploadPartRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId).partNumber(1).build(),
                    RequestBody.fromBytes(body)).eTag();
            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder()
                            .parts(CompletedPart.builder().partNumber(1).eTag(eTag).build()).build())
                    .build());

            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket).key(key).build());

            assertThat(head.contentEncoding()).isEqualTo("gzip");
            assertThat(head.cacheControl()).isEqualTo("no-cache");
            assertThat(head.contentType()).isEqualTo("text/plain");
        } finally {
            deleteObjectAndBucketQuietly(bucket, key);
        }
    }

    private void assertInlineTaggingHeaderRejected(String rawTaggingHeader, int expectedStatus, String expectedErrorCode) {
        String bucket = TestFixtures.uniqueName("inline-tag-reject");
        String key = "rejected.txt";
        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        try {
            assertThatThrownBy(() -> s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket).key(key)
                            .overrideConfiguration(o -> o.putHeader("x-amz-tagging", rawTaggingHeader))
                            .build(),
                    RequestBody.fromString("content")))
                    .isInstanceOf(S3Exception.class)
                    .satisfies(e -> {
                        S3Exception s3e = (S3Exception) e;
                        assertThat(s3e.statusCode()).isEqualTo(expectedStatus);
                        assertThat(s3e.awsErrorDetails().errorCode()).isEqualTo(expectedErrorCode);
                    });
        } finally {
            deleteObjectAndBucketQuietly(bucket, key);
        }
    }

    private void deleteObjectAndBucketQuietly(String bucket, String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (Exception ignored) {
            // cleanup only: an already-deleted object must not mask the assertion
        }
        try {
            s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
        } catch (Exception ignored) {
            // cleanup only: an already-emptied bucket must not mask the assertion
        }
    }
}
