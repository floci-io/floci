package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.ChecksumAlgorithm;
import io.github.hectorvent.floci.services.s3.model.ObjectAnnotation;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class S3ServiceAnnotationsTest {

    private static final String BUCKET = "annotations-bucket";

    @TempDir
    Path tempDir;

    private S3Service s3Service;

    @BeforeEach
    void setUp() {
        s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(),
                tempDir.resolve("s3"), false);
        s3Service.createBucket(BUCKET, "us-east-1");
        s3Service.putObject(BUCKET, "docs/readme.txt",
                "object body".getBytes(StandardCharsets.UTF_8), "text/plain", null, null);
    }

    private ObjectAnnotation putAnnotation(String name, String payload) {
        return s3Service.putObjectAnnotation(BUCKET, "docs/readme.txt", name, null,
                payload.getBytes(StandardCharsets.UTF_8), null, null);
    }

    private String readPayload(String name) {
        ObjectAnnotation annotation = s3Service.getObjectAnnotation(BUCKET, "docs/readme.txt", name, null);
        return new String(s3Service.readObjectAnnotationPayload(annotation), StandardCharsets.UTF_8);
    }

    // ========== Validation ==========

    @Test
    void putAndGetRoundTripPreservesPayloadAndMetadata() {
        ObjectAnnotation annotation = putAnnotation("classification", "{\"label\": \"doc\"}");
        assertEquals("classification", annotation.getAnnotationName());
        assertEquals(16, annotation.getSize());
        assertEquals("{\"label\": \"doc\"}", readPayload("classification"));
        assertEquals("CRC64NVME", annotation.getChecksumAlgorithm());
        assertNotNull(annotation.getChecksumValue());
        assertTrue(annotation.getETag().startsWith("\"") && annotation.getETag().endsWith("\""));
        assertNotNull(annotation.getLastModified());
    }

    @Test
    void blankNameIsRejected() {
        assertThrowsAws("InvalidAnnotationName", () -> putAnnotation("   ", "x"));
        assertThrowsAws("InvalidAnnotationName", () ->
                s3Service.putObjectAnnotation(BUCKET, "docs/readme.txt", null, null,
                        new byte[]{1}, null, null));
    }

    @Test
    void nameLongerThan512BytesIsRejected() {
        assertThrowsAws("AnnotationNameTooLong", () -> putAnnotation("a".repeat(513), "x"));
        // Exactly 512 bytes is accepted.
        putAnnotation("a".repeat(512), "x");
    }

    @Test
    void invalidNameCharactersAreRejected() {
        assertThrowsAws("InvalidAnnotationName", () -> putAnnotation("bad/name", "x"));
        assertThrowsAws("InvalidAnnotationName", () -> putAnnotation("bad name", "x"));
        assertThrowsAws("InvalidAnnotationName", () -> putAnnotation("bad@name", "x"));
    }

    @Test
    void reservedPrefixesAreRejectedCaseInsensitively() {
        assertThrowsAws("InvalidAnnotationName", () -> putAnnotation("aws-thing", "x"));
        assertThrowsAws("InvalidAnnotationName", () -> putAnnotation("AWS-thing", "x"));
        assertThrowsAws("InvalidAnnotationName", () -> putAnnotation("s3-result", "x"));
        assertThrowsAws("InvalidAnnotationName", () -> putAnnotation("S3-result", "x"));
    }

    @Test
    void unicodeNameIsAccepted() {
        putAnnotation("分类", "x");
        assertEquals("x", readPayload("分类"));
    }

    @Test
    void emptyPayloadIsRejected() {
        assertThrowsAws("InvalidRequest", () ->
                s3Service.putObjectAnnotation(BUCKET, "docs/readme.txt", "name", null,
                        new byte[0], null, null));
        assertThrowsAws("InvalidRequest", () ->
                s3Service.putObjectAnnotation(BUCKET, "docs/readme.txt", "name", null,
                        null, null, null));
    }

    @Test
    void oversizedPayloadIsRejected() {
        byte[] tooBig = new byte[1_048_577];
        assertThrowsAws("InvalidRequest", () ->
                s3Service.putObjectAnnotation(BUCKET, "docs/readme.txt", "name", null, tooBig, null, null));
        // Exactly 1 MiB is accepted.
        byte[] maxPayload = new byte[1_048_576];
        s3Service.putObjectAnnotation(BUCKET, "docs/readme.txt", "name", null, maxPayload, null, null);
    }

    @Test
    void nonUtf8PayloadIsRejectedWithUnsupportedMediaType() {
        byte[] invalidUtf8 = new byte[]{(byte) 0xff, (byte) 0xfe, (byte) 0xfd};
        assertThrowsAws("UnsupportedMediaType", () ->
                s3Service.putObjectAnnotation(BUCKET, "docs/readme.txt", "name", null, invalidUtf8, null, null));
    }

    @Test
    void thousandAnnotationLimitIsEnforcedButUpdateDoesNotCount() {
        for (int i = 0; i < 1000; i++) {
            putAnnotation("ann-" + i, "v");
        }
        assertThrowsAws("AnnotationLimitExceeded", () -> putAnnotation("ann-new", "v"));
        // Updating an existing name is an update, not a new entry.
        putAnnotation("ann-500", "updated");
        assertEquals("updated", readPayload("ann-500"));
    }

    @Test
    void missingBucketOrObjectYieldsAwsErrors() {
        assertThrowsAws("NoSuchBucket", () ->
                s3Service.putObjectAnnotation("no-such-bucket", "k", "name", null,
                        new byte[]{1}, null, null));
        assertThrowsAws("NoSuchKey", () ->
                s3Service.getObjectAnnotation(BUCKET, "missing-key", "name", null));
        assertThrowsAws("NoSuchAnnotation", () ->
                s3Service.getObjectAnnotation(BUCKET, "docs/readme.txt", "missing-ann", null));
    }

    @Test
    void objectIfMatchValidatesParentETag() {
        S3Object object = s3Service.getObject(BUCKET, "docs/readme.txt", null);
        putAnnotation("guarded", "v1");
        // Matching the parent ETag succeeds (idempotent update).
        s3Service.putObjectAnnotation(BUCKET, "docs/readme.txt", "guarded",
                null, "v2".getBytes(StandardCharsets.UTF_8), object.getETag(), null);
        assertEquals("v2", readPayload("guarded"));
        // A wrong ETag is a 412.
        assertThrows(S3PreconditionFailedException.class, () ->
                s3Service.putObjectAnnotation(BUCKET, "docs/readme.txt", "guarded",
                        null, "v3".getBytes(StandardCharsets.UTF_8), "\"beef\"", null));
        assertThrows(S3PreconditionFailedException.class, () ->
                s3Service.deleteObjectAnnotation(BUCKET, "docs/readme.txt", "guarded", null, "\"beef\"", false));
    }

    @Test
    void annotationPutDoesNotChangeParentETagOrLastModified() {
        S3Object before = s3Service.getObject(BUCKET, "docs/readme.txt", null);
        putAnnotation("meta-neutral", "payload");
        S3Object after = s3Service.getObject(BUCKET, "docs/readme.txt", null);
        assertEquals(before.getETag(), after.getETag());
        assertEquals(before.getLastModified(), after.getLastModified());
    }

    // ========== Delete semantics ==========

    @Test
    void deleteAnnotationIsIdempotentForMissingAnnotation() {
        assertDoesNotThrow(() ->
                s3Service.deleteObjectAnnotation(BUCKET, "docs/readme.txt", "never-existed", null, null, false));
    }

    @Test
    void deleteAnnotationRemovesMetadataAndPayload() {
        putAnnotation("doomed", "payload");
        s3Service.deleteObjectAnnotation(BUCKET, "docs/readme.txt", "doomed", null, null, false);
        assertThrowsAws("NoSuchAnnotation", () -> readPayload("doomed"));
    }

    // ========== Versioning ==========

    @Test
    void newVersionDoesNotInheritAnnotationsButOldVersionKeepsItsOwn() {
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        s3Service.putObject(BUCKET, "docs/readme.txt",
                "body v1".getBytes(StandardCharsets.UTF_8), "text/plain", null, null);
        putAnnotation("v1-only", "first");
        String versionId = s3Service.getObject(BUCKET, "docs/readme.txt", null).getVersionId();
        s3Service.putObject(BUCKET, "docs/readme.txt",
                "body v2".getBytes(StandardCharsets.UTF_8), "text/plain", null, null);

        // The new version has no annotations.
        assertThrowsAws("NoSuchAnnotation", () ->
                s3Service.getObjectAnnotation(BUCKET, "docs/readme.txt", "v1-only", null));
        // The old version's annotations remain reachable by versionId.
        ObjectAnnotation annotation = s3Service.getObjectAnnotation(BUCKET, "docs/readme.txt",
                "v1-only", versionId);
        assertEquals("first", new String(
                s3Service.readObjectAnnotationPayload(annotation), StandardCharsets.UTF_8));
        assertEquals(versionId, annotation.getVersionId());
    }

    @Test
    void deleteMarkerPreservesAnnotations() {
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        s3Service.putObject(BUCKET, "docs/readme.txt",
                "body v1".getBytes(StandardCharsets.UTF_8), "text/plain", null, null);
        putAnnotation("under-marker", "kept");
        String versionId = s3Service.getObject(BUCKET, "docs/readme.txt", null).getVersionId();
        s3Service.deleteObject(BUCKET, "docs/readme.txt");
        assertThrowsAws("NoSuchKey", () ->
                s3Service.getObjectAnnotation(BUCKET, "docs/readme.txt", "under-marker", null));
        ObjectAnnotation annotation = s3Service.getObjectAnnotation(BUCKET, "docs/readme.txt",
                "under-marker", versionId);
        assertEquals("kept", new String(
                s3Service.readObjectAnnotationPayload(annotation), StandardCharsets.UTF_8));
    }

    @Test
    void deletingASpecificVersionDeletesItsAnnotations() {
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        s3Service.putObject(BUCKET, "docs/readme.txt",
                "body v1".getBytes(StandardCharsets.UTF_8), "text/plain", null, null);
        putAnnotation("version-bound", "payload");
        String versionId = s3Service.getObject(BUCKET, "docs/readme.txt", null).getVersionId();
        s3Service.deleteObject(BUCKET, "docs/readme.txt", versionId);
        assertThrowsAws("NoSuchKey", () ->
                s3Service.getObjectAnnotation(BUCKET, "docs/readme.txt", "version-bound", versionId));
    }

    @Test
    void nonVersionedOverwriteDropsAnnotations() {
        putAnnotation("before-overwrite", "payload");
        s3Service.putObject(BUCKET, "docs/readme.txt",
                "new body".getBytes(StandardCharsets.UTF_8), "text/plain", null, null);
        assertThrowsAws("NoSuchAnnotation", () ->
                s3Service.getObjectAnnotation(BUCKET, "docs/readme.txt", "before-overwrite", null));
    }

    @Test
    void nonVersionedDeleteDropsAnnotations() {
        putAnnotation("before-delete", "payload");
        s3Service.deleteObject(BUCKET, "docs/readme.txt");
        assertThrowsAws("NoSuchKey", () ->
                s3Service.getObjectAnnotation(BUCKET, "docs/readme.txt", "before-delete", null));
    }

    // ========== List ==========

    @Test
    void listReturnsSortedAnnotationsWithCount() {
        putAnnotation("b-second", "1");
        putAnnotation("a-first", "2");
        S3Service.ListObjectAnnotationsResult result =
                s3Service.listObjectAnnotations(BUCKET, "docs/readme.txt", null, null, null, null);
        assertEquals(2, result.annotations().size());
        assertEquals("a-first", result.annotations().get(0).getAnnotationName());
        assertEquals("b-second", result.annotations().get(1).getAnnotationName());
        assertFalse(result.isTruncated());
        assertNull(result.nextContinuationToken());
    }

    @Test
    void listPaginatesWithContinuationToken() {
        for (int i = 0; i < 5; i++) {
            putAnnotation("ann-" + i, String.valueOf(i));
        }
        S3Service.ListObjectAnnotationsResult page1 =
                s3Service.listObjectAnnotations(BUCKET, "docs/readme.txt", null, 2, null, null);
        assertEquals(2, page1.annotations().size());
        assertTrue(page1.isTruncated());
        assertEquals("ann-0", page1.annotations().get(0).getAnnotationName());
        assertEquals("ann-1", page1.annotations().get(1).getAnnotationName());
        assertNotNull(page1.nextContinuationToken());

        S3Service.ListObjectAnnotationsResult page2 =
                s3Service.listObjectAnnotations(BUCKET, "docs/readme.txt", null, 2,
                        page1.nextContinuationToken(), null);
        assertEquals(2, page2.annotations().size());
        assertEquals("ann-2", page2.annotations().get(0).getAnnotationName());
        assertEquals("ann-3", page2.annotations().get(1).getAnnotationName());

        S3Service.ListObjectAnnotationsResult page3 =
                s3Service.listObjectAnnotations(BUCKET, "docs/readme.txt", null, 2,
                        page2.nextContinuationToken(), null);
        assertEquals(1, page3.annotations().size());
        assertFalse(page3.isTruncated());
    }

    @Test
    void listFiltersByPrefix() {
        putAnnotation("label-a", "1");
        putAnnotation("label-b", "2");
        putAnnotation("other", "3");
        S3Service.ListObjectAnnotationsResult result =
                s3Service.listObjectAnnotations(BUCKET, "docs/readme.txt", "label-", null, null, null);
        assertEquals(2, result.annotations().size());
        assertEquals("label-a", result.annotations().get(0).getAnnotationName());
        assertEquals("label-b", result.annotations().get(1).getAnnotationName());
    }

    @Test
    void listRejectsInvalidPrefixLimitAndToken() {
        assertThrowsAws("InvalidPrefix", () ->
                s3Service.listObjectAnnotations(BUCKET, "docs/readme.txt", "bad!", null, null, null));
        assertThrowsAws("InvalidArgument", () ->
                s3Service.listObjectAnnotations(BUCKET, "docs/readme.txt", null, 0, null, null));
        assertThrowsAws("InvalidArgument", () ->
                s3Service.listObjectAnnotations(BUCKET, "docs/readme.txt", null, 1001, null, null));
        assertThrowsAws("InvalidArgument", () ->
                s3Service.listObjectAnnotations(BUCKET, "docs/readme.txt", null, null, "not-base64!", null));
    }

    // ========== Checksums ==========

    @Test
    void annotationChecksumAlgorithmDefaultsToCrc64NvmeAndHonorsRequested() {
        ObjectAnnotation defaultChecksum = putAnnotation("defaulted", "payload");
        assertEquals("CRC64NVME", defaultChecksum.getChecksumAlgorithm());
        assertEquals(ChecksumAlgorithm.CRC64NVME.compute("payload".getBytes(StandardCharsets.UTF_8)),
                defaultChecksum.getChecksumValue());

        ObjectAnnotation sha256 = s3Service.putObjectAnnotation(BUCKET, "docs/readme.txt", "sha256-ed",
                null, "payload".getBytes(StandardCharsets.UTF_8), null, ChecksumAlgorithm.SHA256);
        assertEquals("SHA256", sha256.getChecksumAlgorithm());
        assertEquals(ChecksumAlgorithm.SHA256.compute("payload".getBytes(StandardCharsets.UTF_8)),
                sha256.getChecksumValue());
    }

    @Test
    void sha512IsRejectedForAnnotationsAndObjects() {
        assertThrowsAws("InvalidRequest", () ->
                s3Service.putObjectAnnotation(BUCKET, "docs/readme.txt", "sha512-ed", null,
                        "payload".getBytes(StandardCharsets.UTF_8), null,
                        ChecksumAlgorithm.fromWireValue("SHA512")));
        assertThrowsAws("InvalidRequest", () -> ChecksumAlgorithm.fromWireValue("SHA512"));
    }

    // ========== CopyObject ==========

    @Test
    void copyObjectCopiesAnnotationsByDefault() {
        putAnnotation("copied", "payload");
        s3Service.copyObject(BUCKET, "docs/readme.txt", BUCKET, "docs/copy.txt",
                new io.github.hectorvent.floci.services.s3.model.CopyObjectOptions());
        ObjectAnnotation annotation = s3Service.getObjectAnnotation(BUCKET, "docs/copy.txt", "copied", null);
        assertEquals("payload", new String(
                s3Service.readObjectAnnotationPayload(annotation), StandardCharsets.UTF_8));
        assertEquals("payload", new String(s3Service.readObjectAnnotationPayload(
                s3Service.getObjectAnnotation(BUCKET, "docs/readme.txt", "copied", null)),
                StandardCharsets.UTF_8));
    }

    @Test
    void copyObjectWithExcludeDirectiveSkipsAnnotations() {
        putAnnotation("excluded", "payload");
        s3Service.copyObject(BUCKET, "docs/readme.txt", BUCKET, "docs/copy2.txt",
                new io.github.hectorvent.floci.services.s3.model.CopyObjectOptions()
                        .withAnnotationDirective("EXCLUDE"));
        assertThrowsAws("NoSuchAnnotation", () ->
                s3Service.getObjectAnnotation(BUCKET, "docs/copy2.txt", "excluded", null));
    }

    @Test
    void crossBucketCopyCarriesAnnotationsByDefaultAndHonorsExclude() {
        putAnnotation("cross-bucket", "payload");
        s3Service.createBucket("cross-bucket-dest", "us-east-1");
        s3Service.copyObject(BUCKET, "docs/readme.txt", "cross-bucket-dest", "docs/copy.txt",
                new io.github.hectorvent.floci.services.s3.model.CopyObjectOptions());
        ObjectAnnotation annotation = s3Service.getObjectAnnotation("cross-bucket-dest",
                "docs/copy.txt", "cross-bucket", null);
        assertEquals("payload", new String(
                s3Service.readObjectAnnotationPayload(annotation), StandardCharsets.UTF_8));

        s3Service.copyObject(BUCKET, "docs/readme.txt", "cross-bucket-dest", "docs/copy-excluded.txt",
                new io.github.hectorvent.floci.services.s3.model.CopyObjectOptions()
                        .withAnnotationDirective("EXCLUDE"));
        assertThrowsAws("NoSuchAnnotation", () ->
                s3Service.getObjectAnnotation("cross-bucket-dest", "docs/copy-excluded.txt", "cross-bucket", null));
    }

    // ========== Reset ==========

    @Test
    void resetRemovesAnnotationPayloadFiles() throws Exception {
        putAnnotation("reset-me", "payload");
        s3Service.clear();
        // The metadata was erased and the .s3ann payload files must not leak: the .annotations
        // root is empty for every account partition.
        Path accountsRoot = tempDir.resolve("s3").resolve(".accounts");
        if (Files.isDirectory(accountsRoot)) {
            try (var accounts = Files.list(accountsRoot)) {
                for (Path account : accounts.toList()) {
                    Path annotationsRoot = account.resolve(".annotations");
                    assertFalse(Files.exists(annotationsRoot),
                            "annotation payloads survived reset: " + annotationsRoot);
                }
            }
        }
        assertThrowsAws("NoSuchAnnotation", () ->
                s3Service.readObjectAnnotationPayload(
                        s3Service.getObjectAnnotation(BUCKET, "docs/readme.txt", "reset-me", null)));
    }

    // ========== Review regressions ==========

    @Test
    void annotationKeysDoNotCollideAcrossObjectKeysContainingSeparators() {
        s3Service.putObject(BUCKET, "weird@ann@key", "body".getBytes(StandardCharsets.UTF_8), null, null, null);
        s3Service.putObjectAnnotation(BUCKET, "weird@ann@key", "note", null,
                "other object".getBytes(StandardCharsets.UTF_8), null, null);
        putAnnotation("plain", "this object");
        putAnnotation("also-plain", "also here");

        // Object "docs/readme.txt" must not see the other object's annotations.
        S3Service.ListObjectAnnotationsResult result =
                s3Service.listObjectAnnotations(BUCKET, "docs/readme.txt", null, null, null, null);
        assertEquals(2, result.annotations().size());
        assertEquals("also-plain", result.annotations().get(0).getAnnotationName());
        assertEquals("plain", result.annotations().get(1).getAnnotationName());

        // The other object's annotations are independent and unaffected.
        ObjectAnnotation annotation = s3Service.getObjectAnnotation(BUCKET, "weird@ann@key", "note", null);
        assertEquals("other object", new String(
                s3Service.readObjectAnnotationPayload(annotation), StandardCharsets.UTF_8));

        // Deleting one object's annotations leaves the other object's intact.
        s3Service.deleteObjectAnnotation(BUCKET, "docs/readme.txt", "plain", null, null, false);
        assertEquals("other object", new String(s3Service.readObjectAnnotationPayload(
                s3Service.getObjectAnnotation(BUCKET, "weird@ann@key", "note", null)),
                StandardCharsets.UTF_8));
    }

    @Test
    void annotationKeysDoNotCollideAcrossKeysContainingVMarker() {
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        s3Service.putObject(BUCKET, "docs/readme.txt", "v1".getBytes(StandardCharsets.UTF_8), null, null, null);
        String versionId = s3Service.getObject(BUCKET, "docs/readme.txt", null).getVersionId();
        putAnnotation("note", "on version");

        // A different object whose key embeds the same "#v#" text must not see the annotation.
        s3Service.putObject(BUCKET, "docs/readme.txt#v#" + versionId,
                "tricky".getBytes(StandardCharsets.UTF_8), null, null, null);
        S3Service.ListObjectAnnotationsResult result = s3Service.listObjectAnnotations(
                BUCKET, "docs/readme.txt#" + "v#" + versionId, null, null, null, null);
        assertEquals(0, result.annotations().size());
        assertEquals(1, s3Service.listObjectAnnotations(
                BUCKET, "docs/readme.txt", null, null, null, versionId).annotations().size());
    }

    @Test
    void selfCopyPreservesAnnotations() {
        putAnnotation("kept-through-copy", "payload");
        s3Service.copyObject(BUCKET, "docs/readme.txt", BUCKET, "docs/readme.txt",
                new io.github.hectorvent.floci.services.s3.model.CopyObjectOptions());
        assertEquals("payload", readPayload("kept-through-copy"));
        ObjectAnnotation annotation = s3Service.getObjectAnnotation(BUCKET, "docs/readme.txt",
                "kept-through-copy", null);
        assertEquals("payload", new String(
                s3Service.readObjectAnnotationPayload(annotation), StandardCharsets.UTF_8));
    }

    @Test
    void deleteMarkerOnPreVersioningObjectRemovesItsAnnotations() throws Exception {
        putAnnotation("pre-versioning", "payload");
        s3Service.putBucketVersioning(BUCKET, "Enabled");
        s3Service.deleteObject(BUCKET, "docs/readme.txt");

        // The annotations are gone, including the payload files on disk.
        try (var stream = Files.walk(tempDir.resolve("s3").resolve(".accounts")
                .resolve("000000000000").resolve(".annotations"))) {
            List<java.nio.file.Path> files = stream.filter(Files::isRegularFile).toList();
            assertTrue(files.isEmpty(), "annotation payload files should be removed, found: " + files);
        }
    }

    @Test
    void failedOverwriteDoesNotDropAnnotations() {
        putAnnotation("survivor", "payload");
        // A key that escapes the bucket directory fails the body write (InvalidKey): the
        // annotation cleanup runs only after the write succeeds, so the annotation survives.
        assertThrowsAws("InvalidKey", () -> s3Service.putObject(BUCKET, "../docs/readme.txt",
                "body".getBytes(StandardCharsets.UTF_8), null, null, null));
        assertEquals("payload", readPayload("survivor"));
    }

    @Test
    void governanceRetentionBlocksAnnotationDeleteWithoutBypass() {
        s3Service.putObject(BUCKET, "locked.txt", "body".getBytes(StandardCharsets.UTF_8),
                null, null, new io.github.hectorvent.floci.services.s3.model.PutObjectOptions()
                        .withObjectLockMode("GOVERNANCE")
                        .withRetainUntilDate(java.time.Instant.now().plusSeconds(3600)));
        // Put is symmetric with delete: a retention-protected version cannot receive annotations.
        assertThrowsAws("AccessDenied", () ->
                s3Service.putObjectAnnotation(BUCKET, "locked.txt", "locked-ann", null,
                        "payload".getBytes(StandardCharsets.UTF_8), null, null));
        s3Service.putObject(BUCKET, "governed-plain.txt", "body".getBytes(StandardCharsets.UTF_8),
                null, null, null);
        s3Service.putObjectAnnotation(BUCKET, "governed-plain.txt", "ann", null,
                "payload".getBytes(StandardCharsets.UTF_8), null, null);
        s3Service.putObject(BUCKET, "governed-plain.txt", "body v2".getBytes(StandardCharsets.UTF_8),
                null, null, new io.github.hectorvent.floci.services.s3.model.PutObjectOptions()
                        .withObjectLockMode("GOVERNANCE")
                        .withRetainUntilDate(java.time.Instant.now().plusSeconds(3600)));
        // Replacing the annotation via a re-put is blocked too: the protection cannot be
        // circumvented by a put.
        assertThrowsAws("AccessDenied", () ->
                s3Service.putObjectAnnotation(BUCKET, "governed-plain.txt", "ann", null,
                        "changed".getBytes(StandardCharsets.UTF_8), null, null));
        assertThrowsAws("AccessDenied", () ->
                s3Service.deleteObjectAnnotation(BUCKET, "governed-plain.txt", "ann", null, null, false));
        // With the bypass flag the delete succeeds.
        assertDoesNotThrow(() ->
                s3Service.deleteObjectAnnotation(BUCKET, "governed-plain.txt", "ann", null, null, true));
    }

    @Test
    void complianceRetentionBlocksAnnotationPutAndDelete() {
        s3Service.putObject(BUCKET, "compliance.txt", "body".getBytes(StandardCharsets.UTF_8),
                null, null, new io.github.hectorvent.floci.services.s3.model.PutObjectOptions()
                        .withObjectLockMode("COMPLIANCE")
                        .withRetainUntilDate(java.time.Instant.now().plusSeconds(3600)));
        // Put is blocked outright on a COMPLIANCE-protected version (no bypass exists for put).
        assertThrowsAws("AccessDenied", () ->
                s3Service.putObjectAnnotation(BUCKET, "compliance.txt", "compliance-ann", null,
                        "payload".getBytes(StandardCharsets.UTF_8), null, null));
        // Delete is blocked even with the governance bypass.
        assertThrowsAws("AccessDenied", () ->
                s3Service.deleteObjectAnnotation(BUCKET, "compliance.txt", "compliance-ann", null, null, true));
    }

    @Test
    void literalNullVersionIdAddressesPreVersioningObject() {
        putAnnotation("pre-versioning", "payload");
        // ListObjectVersions reports pre-versioning objects with VersionId "null"; echoing it
        // back must address the same annotation as omitting versionId.
        ObjectAnnotation annotation = s3Service.getObjectAnnotation(BUCKET, "docs/readme.txt",
                "pre-versioning", "null");
        assertEquals("payload", new String(
                s3Service.readObjectAnnotationPayload(annotation), StandardCharsets.UTF_8));
        s3Service.deleteObjectAnnotation(BUCKET, "docs/readme.txt", "pre-versioning", "null", null, false);
        assertThrowsAws("NoSuchAnnotation", () ->
                s3Service.getObjectAnnotation(BUCKET, "docs/readme.txt", "pre-versioning", null));
    }

    // ========== Persistence ==========

    @Test
    void payloadsAreStoredOnDiskNotInMetadataStore() throws Exception {
        putAnnotation("disk-backed", "on disk");
        ObjectAnnotation annotation = s3Service.getObjectAnnotation(BUCKET, "docs/readme.txt",
                "disk-backed", null);
        assertEquals("on disk", readPayload("disk-backed"));
        // The .s3ann file exists under the account-scoped .annotations root.
        try (var stream = Files.walk(tempDir.resolve("s3").resolve(".accounts")
                .resolve("000000000000").resolve(".annotations"))) {
            List<java.nio.file.Path> files = stream.filter(Files::isRegularFile).toList();
            assertEquals(1, files.size());
            assertTrue(files.get(0).getFileName().toString().endsWith(".s3ann"));
            assertEquals("on disk", Files.readString(files.get(0), StandardCharsets.UTF_8));
        }
        assertEquals(annotation.getSize(), "on disk".getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void clearRemovesInMemoryAnnotationPayloads() {
        S3Service inMemoryService = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(),
                tempDir, true);
        inMemoryService.createBucket(BUCKET, "us-east-1");
        inMemoryService.putObject(BUCKET, "k", "body".getBytes(StandardCharsets.UTF_8), null, null, null);
        inMemoryService.putObjectAnnotation(BUCKET, "k", "ann", null,
                "payload".getBytes(StandardCharsets.UTF_8), null, null);
        inMemoryService.clear();
        ObjectAnnotation annotation = inMemoryService.getObjectAnnotation(BUCKET, "k", "ann", null);
        // Metadata survives clear() (it lives in the store); the payload is gone.
        assertThrows(AwsException.class, () -> inMemoryService.readObjectAnnotationPayload(annotation));
    }

    private static void assertThrowsAws(String errorCode, Runnable action) {
        AwsException exception = assertThrows(AwsException.class, action::run);
        assertEquals(errorCode, exception.getErrorCode());
    }
}