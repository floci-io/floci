package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.paginators.ListObjectVersionsIterable;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Compatibility tests for S3 features fixed in issues #119, #236, #237.
 */
@DisplayName("S3 Features — pagination, versioning, public access block")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3FeaturesTest {

    private static S3Client s3;

    // Dedicated buckets per feature group — avoids ordering conflicts with S3Test
    private static final String BUCKET_VERSIONS  = "compat-versions-bucket";
    private static final String BUCKET_PAB       = "compat-pab-bucket";
    private static final String BUCKET_PAGINATE  = "compat-paginate-bucket";

    @BeforeAll
    static void setup() {
        s3 = TestFixtures.s3Client();
        createBucket(BUCKET_VERSIONS);
        createBucket(BUCKET_PAB);
        createBucket(BUCKET_PAGINATE);
    }

    @AfterAll
    static void cleanup() {
        if (s3 == null) return;
        deleteBucketContents(BUCKET_VERSIONS);
        deleteBucketContents(BUCKET_PAB);
        deleteBucketContents(BUCKET_PAGINATE);
        quietDeleteBucket(BUCKET_VERSIONS);
        quietDeleteBucket(BUCKET_PAB);
        quietDeleteBucket(BUCKET_PAGINATE);
        s3.close();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Issue #237 — listObjectVersionsPaginator must not NPE (IsTruncated=null)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Iterating listObjectVersionsPaginator on a versioning-disabled bucket used to throw
     * NullPointerException because IsTruncated was missing from the XML response.
     */
    @Test
    @Order(10)
    @DisplayName("#237 listObjectVersionsPaginator: non-versioned bucket does not NPE")
    void listObjectVersionsPaginatorNonVersionedBucketDoesNotNpe() {
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET_VERSIONS).key("plain.txt").build(),
                RequestBody.fromString("content"));

        // Must not throw NullPointerException
        ListObjectVersionsIterable pages = s3.listObjectVersionsPaginator(
                ListObjectVersionsRequest.builder().bucket(BUCKET_VERSIONS).build());

        assertThatNoException().isThrownBy(() -> {
            for (ListObjectVersionsResponse page : pages) {
                assertThat(page.isTruncated()).isNotNull();
            }
        });
    }

    @Test
    @Order(11)
    @DisplayName("#237 listObjectVersionsPaginator: versioned bucket returns isTruncated=false")
    void listObjectVersionsPaginatorVersionedBucketReturnsTruncatedFlag() {
        // Enable versioning and put two versions of the same key
        s3.putBucketVersioning(PutBucketVersioningRequest.builder()
                .bucket(BUCKET_VERSIONS)
                .versioningConfiguration(VersioningConfiguration.builder()
                        .status(BucketVersioningStatus.ENABLED)
                        .build())
                .build());

        s3.putObject(PutObjectRequest.builder().bucket(BUCKET_VERSIONS).key("versioned.txt").build(),
                RequestBody.fromString("v1"));
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET_VERSIONS).key("versioned.txt").build(),
                RequestBody.fromString("v2"));

        List<ObjectVersion> collected = new ArrayList<>();
        for (ListObjectVersionsResponse page :
                s3.listObjectVersionsPaginator(ListObjectVersionsRequest.builder()
                        .bucket(BUCKET_VERSIONS).build())) {
            assertThat(page.isTruncated()).isNotNull();
            collected.addAll(page.versions());
        }

        assertThat(collected).hasSizeGreaterThanOrEqualTo(2);
        assertThat(collected).anyMatch(v -> "versioned.txt".equals(v.key()));
    }

    @Test
    @Order(12)
    @DisplayName("#237 listObjectVersionsPaginator: paginates correctly with maxKeys")
    void listObjectVersionsPaginatorPaginates() {
        // Put additional versioned objects to exceed a single page
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET_VERSIONS).key("a.txt").build(),
                RequestBody.fromString("a1"));
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET_VERSIONS).key("a.txt").build(),
                RequestBody.fromString("a2"));
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET_VERSIONS).key("b.txt").build(),
                RequestBody.fromString("b1"));

        List<ObjectVersion> allVersions = new ArrayList<>();
        ListObjectVersionsIterable pages = s3.listObjectVersionsPaginator(
                ListObjectVersionsRequest.builder()
                        .bucket(BUCKET_VERSIONS)
                        .maxKeys(2)
                        .build());

        for (ListObjectVersionsResponse page : pages) {
            assertThat(page.isTruncated()).isNotNull();
            allVersions.addAll(page.versions());
        }

        // We put at least 5 versions total — should all be collected across pages
        assertThat(allVersions.size()).isGreaterThanOrEqualTo(5);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Issue #236 — PutPublicAccessBlock must not return BucketAlreadyOwnedByYou
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("#236 putPublicAccessBlock succeeds on existing bucket")
    void putPublicAccessBlockSucceeds() {
        assertThatNoException().isThrownBy(() ->
                s3.putPublicAccessBlock(PutPublicAccessBlockRequest.builder()
                        .bucket(BUCKET_PAB)
                        .publicAccessBlockConfiguration(PublicAccessBlockConfiguration.builder()
                                .blockPublicAcls(true)
                                .ignorePublicAcls(true)
                                .blockPublicPolicy(true)
                                .restrictPublicBuckets(true)
                                .build())
                        .build()));
    }

    @Test
    @Order(21)
    @DisplayName("#236 getPublicAccessBlock returns stored configuration")
    void getPublicAccessBlockReturnsConfig() {
        GetPublicAccessBlockResponse response = s3.getPublicAccessBlock(
                GetPublicAccessBlockRequest.builder().bucket(BUCKET_PAB).build());

        PublicAccessBlockConfiguration config = response.publicAccessBlockConfiguration();
        assertThat(config.blockPublicAcls()).isTrue();
        assertThat(config.ignorePublicAcls()).isTrue();
        assertThat(config.blockPublicPolicy()).isTrue();
        assertThat(config.restrictPublicBuckets()).isTrue();
    }

    @Test
    @Order(22)
    @DisplayName("#236 putPublicAccessBlock can be updated")
    void putPublicAccessBlockCanBeUpdated() {
        s3.putPublicAccessBlock(PutPublicAccessBlockRequest.builder()
                .bucket(BUCKET_PAB)
                .publicAccessBlockConfiguration(PublicAccessBlockConfiguration.builder()
                        .blockPublicAcls(false)
                        .ignorePublicAcls(false)
                        .blockPublicPolicy(false)
                        .restrictPublicBuckets(false)
                        .build())
                .build());

        GetPublicAccessBlockResponse response = s3.getPublicAccessBlock(
                GetPublicAccessBlockRequest.builder().bucket(BUCKET_PAB).build());

        assertThat(response.publicAccessBlockConfiguration().blockPublicAcls()).isFalse();
    }

    @Test
    @Order(23)
    @DisplayName("#236 deletePublicAccessBlock removes the configuration")
    void deletePublicAccessBlockRemovesConfig() {
        s3.deletePublicAccessBlock(DeletePublicAccessBlockRequest.builder()
                .bucket(BUCKET_PAB).build());

        assertThatThrownBy(() -> s3.getPublicAccessBlock(
                GetPublicAccessBlockRequest.builder().bucket(BUCKET_PAB).build()))
                .isInstanceOf(S3Exception.class)
                .satisfies(e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(404));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Issue #119 — ListObjectsV2 pagination fields
    // ─────────────────────────────────────────────────────────────────────────

    @BeforeEach
    void setupPaginateBucket() {
        // Ensure objects are present before pagination tests run.
        // Idempotent — SDK suppresses errors if already exists.
    }

    @Test
    @Order(30)
    @DisplayName("#119 listObjectsV2Paginator collects all objects across pages")
    void listObjectsV2PaginatorCollectsAllObjects() {
        // Put 5 objects
        for (int i = 1; i <= 5; i++) {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(BUCKET_PAGINATE).key("file-" + i + ".txt").build(),
                    RequestBody.fromString("content " + i));
        }

        List<S3Object> collected = new ArrayList<>();
        ListObjectsV2Iterable pages = s3.listObjectsV2Paginator(
                ListObjectsV2Request.builder()
                        .bucket(BUCKET_PAGINATE)
                        .maxKeys(2)
                        .build());

        for (ListObjectsV2Response page : pages) {
            collected.addAll(page.contents());
        }

        assertThat(collected).hasSize(5);
        assertThat(collected.stream().map(S3Object::key).toList())
                .containsExactlyInAnyOrder(
                        "file-1.txt", "file-2.txt", "file-3.txt", "file-4.txt", "file-5.txt");
    }

    @Test
    @Order(31)
    @DisplayName("#119 listObjectsV2 with startAfter skips keys up to and including the marker")
    void listObjectsV2StartAfterSkipsKeys() {
        ListObjectsV2Response response = s3.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(BUCKET_PAGINATE)
                        .startAfter("file-3.txt")
                        .build());

        List<String> keys = response.contents().stream().map(S3Object::key).toList();
        assertThat(keys).doesNotContain("file-1.txt", "file-2.txt", "file-3.txt");
        assertThat(keys).contains("file-4.txt", "file-5.txt");
    }

    @Test
    @Order(32)
    @DisplayName("#119 listObjectsV2 response echoes startAfter when provided")
    void listObjectsV2ResponseEchoesStartAfter() {
        ListObjectsV2Response response = s3.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(BUCKET_PAGINATE)
                        .startAfter("file-2.txt")
                        .build());

        assertThat(response.startAfter()).isEqualTo("file-2.txt");
    }

    @Test
    @Order(33)
    @DisplayName("#119 listObjectsV2 first truncated page contains NextContinuationToken")
    void listObjectsV2TruncatedPageHasNextToken() {
        ListObjectsV2Response firstPage = s3.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(BUCKET_PAGINATE)
                        .maxKeys(2)
                        .build());

        assertThat(firstPage.isTruncated()).isTrue();
        assertThat(firstPage.nextContinuationToken()).isNotNull().isNotEmpty();
    }

    @Test
    @Order(34)
    @DisplayName("#119 listObjectsV2 continuation token resumes from correct position")
    void listObjectsV2ContinuationTokenResumesCorrectly() {
        // Page 1
        ListObjectsV2Response page1 = s3.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(BUCKET_PAGINATE)
                        .maxKeys(3)
                        .build());
        assertThat(page1.isTruncated()).isTrue();
        List<String> page1Keys = page1.contents().stream().map(S3Object::key).toList();

        // Page 2 using token
        ListObjectsV2Response page2 = s3.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(BUCKET_PAGINATE)
                        .maxKeys(3)
                        .continuationToken(page1.nextContinuationToken())
                        .build());

        assertThat(page2.isTruncated()).isFalse();
        assertThat(page2.continuationToken()).isEqualTo(page1.nextContinuationToken());

        // No key should appear on both pages
        List<String> page2Keys = page2.contents().stream().map(S3Object::key).toList();
        assertThat(page2Keys).doesNotContainAnyElementsOf(page1Keys);

        // Together they must cover all 5 objects
        List<String> allKeys = new ArrayList<>(page1Keys);
        allKeys.addAll(page2Keys);
        assertThat(allKeys).containsExactlyInAnyOrder(
                "file-1.txt", "file-2.txt", "file-3.txt", "file-4.txt", "file-5.txt");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static void createBucket(String bucket) {
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (BucketAlreadyOwnedByYouException ignored) {}
    }

    private static void deleteBucketContents(String bucket) {
        try {
            // Delete all ordinary objects
            String token = null;
            do {
                ListObjectsV2Response resp = s3.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucket).continuationToken(token).build());
                for (S3Object obj : resp.contents()) {
                    s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(obj.key()).build());
                }
                token = resp.isTruncated() ? resp.nextContinuationToken() : null;
            } while (token != null);

            // Delete all versions and delete-markers
            String keyMarker = null;
            String versionMarker = null;
            do {
                ListObjectVersionsResponse resp = s3.listObjectVersions(
                        ListObjectVersionsRequest.builder()
                                .bucket(bucket).keyMarker(keyMarker).versionIdMarker(versionMarker).build());
                for (ObjectVersion v : resp.versions()) {
                    s3.deleteObject(DeleteObjectRequest.builder()
                            .bucket(bucket).key(v.key()).versionId(v.versionId()).build());
                }
                for (DeleteMarkerEntry dm : resp.deleteMarkers()) {
                    s3.deleteObject(DeleteObjectRequest.builder()
                            .bucket(bucket).key(dm.key()).versionId(dm.versionId()).build());
                }
                keyMarker = resp.isTruncated() ? resp.nextKeyMarker() : null;
                versionMarker = resp.isTruncated() ? resp.nextVersionIdMarker() : null;
            } while (keyMarker != null);
        } catch (Exception ignored) {}
    }

    private static void quietDeleteBucket(String bucket) {
        try { s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build()); }
        catch (Exception ignored) {}
    }
}
