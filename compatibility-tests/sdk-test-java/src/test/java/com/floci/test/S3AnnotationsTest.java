package com.floci.test;

import org.junit.jupiter.api.*;

import java.util.logging.Level;
import java.util.logging.Logger;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AnnotationDirective;
import software.amazon.awssdk.services.s3.model.AnnotationEntry;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectAnnotationResponse;
import software.amazon.awssdk.services.s3.model.ListObjectAnnotationsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectAnnotationsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchAnnotationException;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.PutObjectAnnotationRequest;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

import static org.assertj.core.api.Assertions.*;

@DisplayName("S3 Object Annotations")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3AnnotationsTest {

    private static final Logger LOG = Logger.getLogger(S3AnnotationsTest.class.getName());
    private static S3Client s3;
    private static final String BUCKET = "sdk-annotations-bucket";
    private static final String KEY = "docs/annotated.txt";

    @BeforeAll
    static void setup() {
        s3 = TestFixtures.s3Client();
        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        s3.putObject(r -> r.bucket(BUCKET).key(KEY), RequestBody.fromString("annotated object body"));
    }

    @AfterAll
    static void teardown() {
        // Cleanup failures are logged with context instead of swallowed: a leaked object or a
        // bucket that failed to delete would otherwise hide from later test runs.
        for (String key : new String[]{"docs/annotated.txt", "docs/annotated-copy.txt", "docs/annotated-copied.txt"}) {
            try {
                s3.deleteObject(r -> r.bucket(BUCKET).key(key));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to clean up object " + BUCKET + "/" + key, e);
            }
        }
        try {
            s3.deleteBucket(r -> r.bucket(BUCKET));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to clean up bucket " + BUCKET, e);
        }
    }

    @Test
    @Order(1)
    void putObjectAnnotation() {
        var response = s3.putObjectAnnotation(PutObjectAnnotationRequest.builder()
                        .bucket(BUCKET)
                        .key(KEY)
                        .annotationName("classification")
                        .build(),
                RequestBody.fromString("{\"label\": \"report\"}"));

        assertThat(response.key()).isEqualTo(KEY);
        assertThat(response.annotationName()).isEqualTo("classification");
        assertThat(response.eTag()).isNotBlank();
        // CRC64NVME is the AWS default algorithm for annotations without an explicit one.
        // The SDK computes a CRC32 payload checksum client-side by default and expects it echoed.
        assertThat(response.checksumCRC32()).isNotBlank();
        assertThat(response.checksumTypeAsString()).isEqualTo("FULL_OBJECT");
    }

    @Test
    @Order(2)
    void getObjectAnnotation() {
        ResponseBytes<GetObjectAnnotationResponse> response = s3.getObjectAnnotationAsBytes(
                r -> r.bucket(BUCKET).key(KEY).annotationName("classification").build());

        assertThat(response.asUtf8String()).isEqualTo("{\"label\": \"report\"}");
        assertThat(response.response().eTag()).isNotBlank();
        assertThat(response.response().lastModified()).isNotNull();
        assertThat(response.response().contentLength()).isEqualTo(19L);
    }

    @Test
    @Order(3)
    void getObjectAnnotationWithChecksumMode() {
        ResponseBytes<GetObjectAnnotationResponse> response = s3.getObjectAnnotationAsBytes(
                r -> r.bucket(BUCKET).key(KEY)
                        .annotationName("classification")
                        .checksumMode(ChecksumMode.ENABLED)
                        .build());

        assertThat(response.response().checksumCRC32()).isNotBlank();
        assertThat(response.response().checksumTypeAsString()).isEqualTo("FULL_OBJECT");
    }

    @Test
    @Order(4)
    void listObjectAnnotations() {
        s3.putObjectAnnotation(PutObjectAnnotationRequest.builder()
                        .bucket(BUCKET).key(KEY).annotationName("summary")
                        .build(),
                RequestBody.fromString("summary text"));

        ListObjectAnnotationsResponse response = s3.listObjectAnnotations(
                ListObjectAnnotationsRequest.builder().bucket(BUCKET).key(KEY).build());

        assertThat(response.bucket()).isEqualTo(BUCKET);
        assertThat(response.key()).isEqualTo(KEY);
        assertThat(response.annotationCount()).isEqualTo(2);
        assertThat(response.annotations())
                .extracting(AnnotationEntry::annotationName)
                .containsExactly("classification", "summary");
        assertThat(response.annotations())
                .filteredOn(a -> "summary".equals(a.annotationName()))
                .allSatisfy(a -> assertThat(a.size()).isEqualTo(12L));
        assertThat(response.nextContinuationToken()).isNull();
        assertThat(response.maxAnnotationResults()).isEqualTo(1000);
    }

    @Test
    @Order(5)
    void putObjectAnnotationWithChecksum() {
        var response = s3.putObjectAnnotation(PutObjectAnnotationRequest.builder()
                        .bucket(BUCKET).key(KEY).annotationName("hashed")
                        .checksumAlgorithm(software.amazon.awssdk.services.s3.model.ChecksumAlgorithm.SHA256)
                        .build(),
                RequestBody.fromString("checksummed payload"));

        assertThat(response.checksumSHA256()).isNotBlank();
        assertThat(response.eTag()).isNotBlank();
    }

    @Test
    @Order(6)
    void getMissingAnnotationThrows() {
        assertThatThrownBy(() -> s3.getObjectAnnotationAsBytes(r -> r.bucket(BUCKET).key(KEY)
                        .annotationName("missing").build()))
                .isInstanceOf(NoSuchAnnotationException.class);
    }

    @Test
    @Order(7)
    void deleteObjectAnnotationIsIdempotent() {
        s3.deleteObjectAnnotation(r -> r.bucket(BUCKET).key(KEY).annotationName("hashed"));

        assertThatThrownBy(() -> s3.getObjectAnnotationAsBytes(r -> r.bucket(BUCKET).key(KEY)
                        .annotationName("hashed").build()))
                .isInstanceOf(NoSuchAnnotationException.class);

        // Deleting a nonexistent annotation is not an error.
        assertThatCode(() -> s3.deleteObjectAnnotation(r -> r.bucket(BUCKET).key(KEY)
                .annotationName("hashed"))).doesNotThrowAnyException();
    }

    @Test
    @Order(8)
    void copyObjectExcludeDirectiveSkipsAnnotations() {
        s3.copyObject(r -> r.sourceBucket(BUCKET).sourceKey(KEY)
                .destinationBucket(BUCKET).destinationKey("docs/annotated-copy.txt")
                .annotationDirective(AnnotationDirective.EXCLUDE));

        assertThatThrownBy(() -> s3.getObjectAnnotationAsBytes(r -> r.bucket(BUCKET)
                        .key("docs/annotated-copy.txt").annotationName("classification").build()))
                .isInstanceOf(NoSuchAnnotationException.class);
    }

    // ========== Versioned bucket behavior ==========

    @Test
    @Order(10)
    void enableVersioning() {
        s3.putBucketVersioning(r -> r.bucket(BUCKET)
                .versioningConfiguration(VersioningConfiguration.builder()
                        .status(BucketVersioningStatus.ENABLED)
                        .build()));
    }

    @Test
    @Order(11)
    void annotationAttachesToSpecificVersion() {
        s3.putObject(r -> r.bucket(BUCKET).key(KEY), RequestBody.fromString("version one"));
        var v1 = s3.putObjectAnnotation(PutObjectAnnotationRequest.builder()
                        .bucket(BUCKET).key(KEY).annotationName("v1-note")
                        .build(),
                RequestBody.fromString("on version one"));
        assertThat(v1.objectVersionId()).isNotBlank();

        s3.putObject(r -> r.bucket(BUCKET).key(KEY), RequestBody.fromString("version two"));

        // The new version has no annotations; the old one's stay reachable by versionId.
        assertThatThrownBy(() -> s3.getObjectAnnotationAsBytes(r -> r.bucket(BUCKET).key(KEY)
                        .annotationName("v1-note").build()))
                .isInstanceOf(NoSuchAnnotationException.class);

        ResponseBytes<GetObjectAnnotationResponse> fromV1 = s3.getObjectAnnotationAsBytes(
                r -> r.bucket(BUCKET).key(KEY)
                        .annotationName("v1-note")
                        .versionId(v1.objectVersionId())
                        .build());
        assertThat(fromV1.asUtf8String()).isEqualTo("on version one");
        assertThat(fromV1.response().objectVersionId()).isEqualTo(v1.objectVersionId());
    }

    @Test
    @Order(12)
    void copyObjectCopiesAnnotationsByDefault() {
        // The latest version (the copy source) must carry an annotation for the copy to take.
        s3.putObjectAnnotation(PutObjectAnnotationRequest.builder()
                        .bucket(BUCKET).key(KEY).annotationName("latest-note")
                        .build(),
                RequestBody.fromString("copied annotation"));
        s3.copyObject(r -> r.sourceBucket(BUCKET).sourceKey(KEY)
                .destinationBucket(BUCKET).destinationKey("docs/annotated-copied.txt"));

        ResponseBytes<GetObjectAnnotationResponse> copied = s3.getObjectAnnotationAsBytes(
                r -> r.bucket(BUCKET)
                        .key("docs/annotated-copied.txt").annotationName("latest-note")
                        .versionId(s3.headObject(b -> b.bucket(BUCKET).key("docs/annotated-copied.txt"))
                                .versionId())
                        .build());
        assertThat(copied.asUtf8String()).isEqualTo("copied annotation");
        s3.deleteObject(r -> r.bucket(BUCKET).key("docs/annotated-copied.txt"));
    }
}