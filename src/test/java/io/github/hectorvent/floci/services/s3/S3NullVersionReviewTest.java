package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.CopyObjectOptions;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class S3NullVersionReviewTest {

    @TempDir
    Path tempDir;

    /** The bucket store of the service {@link #service} last built. */
    private InMemoryStorage<String, Bucket> buckets;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rollbackThenPutListsTheReplacementNull(boolean memory) {
        S3Service service = rolledBackService(memory);
        S3Object replacement = service.putObject("bucket", "key", bytes("replacement"), "text/plain", Map.of());
        assertArrayEquals(bytes("replacement"), service.getObject("bucket", "key", "null").getData());
        List<S3Object> versions = service.listObjectVersions("bucket", "key", 100, null).versions();
        assertEquals(1, versions.size());
        assertEquals(replacement.getETag(), versions.getFirst().getETag(),
                "listing must describe the retrievable null version");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rollbackThenDeleteDoesNotResurrectNull(boolean memory) {
        S3Service service = rolledBackService(memory);
        service.deleteObject("bucket", "key");
        assertAll(
                () -> assertTrue(service.listObjectVersions("bucket", "key", 100, null).versions().isEmpty(),
                        "plain DELETE must remove the only null version"),
                () -> assertFalse(service.objectExists("bucket", "key")),
                () -> assertEquals("NoSuchVersion", assertThrows(AwsException.class,
                        () -> service.getObject("bucket", "key", "null")).getErrorCode()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rollbackCannotEraseAnnotationsOfLockedArchivedNull(boolean memory) {
        S3Service service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), tempDir, memory);
        service.createBucket("bucket", "us-east-1");
        service.putObject("bucket", "key", bytes("original"), "text/plain", Map.of());
        service.putObjectAnnotation("bucket", "key", "note", null, bytes("annotation"), null, null);
        service.setBucketObjectLockEnabled("bucket");
        service.putObjectLegalHold("bucket", "key", "null", "ON");
        String configuration = service.snapshotBucketConfiguration("bucket");
        service.putBucketVersioning("bucket", "Enabled");
        service.putObject("bucket", "key", bytes("numbered"), "text/plain", Map.of());
        service.restoreBucketConfiguration("bucket", configuration);

        assertAll(
                () -> assertEquals("AccessDenied", assertThrows(AwsException.class,
                        () -> service.putObject("bucket", "key", bytes("replacement"), "text/plain", Map.of())).getErrorCode()),
                () -> assertEquals("AccessDenied", assertThrows(AwsException.class,
                        () -> service.deleteObject("bucket", "key")).getErrorCode()),
                () -> assertArrayEquals(bytes("annotation"), service.readObjectAnnotationPayload(
                        service.getObjectAnnotation("bucket", "key", "note", "null"))),
                () -> assertArrayEquals(bytes("numbered"), service.getObject("bucket", "key").getData()));
    }

    private S3Service rolledBackService(boolean memory) {
        S3Service service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), tempDir, memory);
        service.createBucket("bucket", "us-east-1");
        service.putObject("bucket", "key", bytes("original"), "text/plain", Map.of());
        String configuration = service.snapshotBucketConfiguration("bucket");
        service.putBucketVersioning("bucket", "Enabled");
        String version = service.putObject("bucket", "key", bytes("numbered"), "text/plain", Map.of()).getVersionId();
        service.deleteObject("bucket", "key", version);
        service.restoreBucketConfiguration("bucket", configuration);
        return service;
    }


    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void neverVersionedBodyReadsDoNotHoldTheBucketMonitor(boolean explicitNull) throws Exception {
        InMemoryStorage<String, Bucket> buckets = new InMemoryStorage<>();
        S3Service service = new S3Service(buckets, new InMemoryStorage<>(), tempDir, true);
        service.createBucket("bucket", "us-east-1");
        service.putObject("bucket", "key", bytes("original"), "text/plain", Map.of());
        Bucket bucket = buckets.get("bucket").orElseThrow();
        AtomicInteger reads = new AtomicInteger();

        readSnapshot(service, explicitNull, ignored -> {
            assertFalse(Thread.holdsLock(bucket), "body reads must allow independent writes in never-versioned buckets");
            reads.incrementAndGet();
            return "body";
        });

        assertEquals(1, reads.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aReadSpanningFirstEnableAndRollbackRetriesThePromotedNull(boolean explicitNull) throws Exception {
        S3Service service = service(true, new InMemoryStorage<>());
        service.putObject("bucket", "key", bytes("original"), "text/plain", Map.of());
        String snapshot = service.snapshotBucketConfiguration("bucket");
        AtomicInteger reads = new AtomicInteger();

        Object result = readSnapshot(service, explicitNull, ignored -> {
            if (reads.getAndIncrement() == 0) {
                service.putBucketVersioning("bucket", "Enabled");
                String version = service.putObject("bucket", "key", bytes("replacement"), "text/plain", Map.of())
                        .getVersionId();
                service.deleteObject("bucket", "key", version);
                service.restoreBucketConfiguration("bucket", snapshot);
                return "replacement";
            }
            return "original";
        });

        Method body = result.getClass().getDeclaredMethod("body");
        body.setAccessible(true);
        assertEquals("original", body.invoke(result));
        assertEquals(2, reads.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void suspendedWritesAndDeletesPreserveLockedArchivedNulls(boolean memory) {
        for (String lock : List.of("ON", "COMPLIANCE", "GOVERNANCE")) {
            S3Service service = service(memory, new InMemoryStorage<>());
            service.putObject("bucket", "key", bytes("original"), "text/plain", Map.of());
            service.putBucketVersioning("bucket", "Enabled");
            service.putObject("bucket", "key", bytes("numbered"), "text/plain", Map.of());
            String source = service.putObject("bucket", "source", bytes("copy source"), "text/plain", Map.of())
                    .getVersionId();
            service.setBucketObjectLockEnabled("bucket");
            if ("ON".equals(lock)) {
                service.putObjectLegalHold("bucket", "key", "null", "ON");
            } else {
                service.putObjectRetention("bucket", "key", "null", lock, Instant.now().plusSeconds(3600), false);
            }
            suspendLockedBucket();

            assertDenied(() -> service.putObject("bucket", "key", bytes("replacement"), "text/plain", Map.of()));
            assertPreservedBodies(service);
            assertDenied(() -> service.copyObject("bucket", "source", "bucket", "key", source, new CopyObjectOptions()));
            assertPreservedBodies(service);
            assertDenied(() -> service.deleteObject("bucket", "key", null, false));
            assertPreservedBodies(service);
            if ("GOVERNANCE".equals(lock)) {
                service.deleteObject("bucket", "key", null, true);
                S3Object nullEntry = service.listObjectVersions("bucket", "key", 100, null).versions().stream()
                        .filter(version -> version.getVersionId() == null)
                        .findFirst().orElseThrow();
                assertTrue(nullEntry.isDeleteMarker());
            } else {
                assertDenied(() -> service.deleteObject("bucket", "key", null, true));
                assertPreservedBodies(service);
            }
        }
    }

    private void assertPreservedBodies(S3Service service) {
        assertArrayEquals(bytes("numbered"), service.getObject("bucket", "key").getData());
        assertArrayEquals(bytes("original"), service.getObject("bucket", "key", "null").getData());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void reservedKeysCannotReadOrMutateArchivedMetadata(boolean memory) throws IOException {
        S3Service service = service(memory, new InMemoryStorage<>());
        service.putObject("bucket", "key", bytes("original"), "text/plain", Map.of());
        service.putBucketVersioning("bucket", "Enabled");
        service.putObject("bucket", "key", bytes("numbered"), "text/plain", Map.of());
        String reserved = "\0null-version/key";
        List<Runnable> requests = List.of(
                () -> service.putObject("bucket", reserved, bytes("bad"), "text/plain", Map.of()),
                () -> service.getObject("bucket", reserved),
                () -> service.getObject("bucket", reserved, "null"),
                () -> service.headObject("bucket", reserved),
                () -> service.deleteObject("bucket", reserved),
                () -> service.deleteObject("bucket", reserved, "null"),
                () -> service.putObjectTagging("bucket", reserved, Map.of("bad", "tag")),
                () -> service.copyObject("bucket", "key", "bucket", reserved),
                () -> service.initiateMultipartUpload("bucket", reserved, "text/plain"),
                () -> service.putObjectFromStream("bucket", reserved, new ByteArrayInputStream(bytes("bad")),
                        "text/plain", Map.of()));
        for (Runnable request : requests) {
            AwsException error = assertThrows(AwsException.class, request::run);
            assertEquals("InvalidArgument", error.getErrorCode());
        }
        assertArrayEquals(bytes("original"), service.getObject("bucket", "key", "null").getData());
        assertEquals(2, service.listObjectVersions("bucket", null, 1000, null).versions().size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void readsSpanningFailedPublicationRetryTheRestoredCurrentVersion(boolean explicitNull) throws Exception {
        PublicationFailureStore objects = new PublicationFailureStore();
        S3Service service = service(true, objects);
        service.putObject("bucket", "key", bytes("original"), "text/plain", Map.of());
        String snapshot = service.snapshotBucketConfiguration("bucket");
        service.putBucketVersioning("bucket", "Enabled");
        String numbered = service.putObject("bucket", "key", bytes("numbered"), "text/plain", Map.of())
                .getVersionId();
        service.deleteObject("bucket", "key", numbered);
        service.restoreBucketConfiguration("bucket", snapshot);
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<String> observed = new AtomicReference<>();
        objects.remaining = 1;
        objects.afterPut = true;
        objects.beforeFailure = () -> observed.set(
                new String(service.getObject("bucket", "key").getData(), StandardCharsets.UTF_8));

        Object result = readSnapshot(service, explicitNull, ignored -> {
            if (reads.getAndIncrement() == 0) {
                assertThrows(IllegalStateException.class, () -> service.putObject(
                        "bucket", "key", bytes("replacement"), "text/plain", Map.of()));
                return observed.get();
            }
            return new String(service.getObject("bucket", "key").getData(), StandardCharsets.UTF_8);
        });

        Method body = result.getClass().getDeclaredMethod("body");
        body.setAccessible(true);
        assertEquals("replacement", observed.get());
        assertEquals("original", body.invoke(result));
        assertEquals(2, reads.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedNonEnabledPublicationRetainsArchivedNullHistory(boolean memory) throws Exception {
        for (String current : List.of("numbered", "promoted", "marker")) {
            for (boolean rollback : List.of(true, false)) {
                for (boolean afterPut : List.of(false, true)) {
                    PublicationFailureStore objects = new PublicationFailureStore();
                    S3Service service = service(memory, objects);
                    S3Object original = service.putObject("bucket", "key", bytes("original"),
                            "text/plain", Map.of("original", "metadata"));
                    service.putObjectAnnotation("bucket", "key", "note", null, bytes("annotation"), null, null);
                    service.putObjectTagging("bucket", "key", Map.of("original", "tag"));
                    String snapshot = service.snapshotBucketConfiguration("bucket");
                    service.putBucketVersioning("bucket", "Enabled");
                    String numbered = service.putObject("bucket", "key", bytes("numbered"), "text/plain", Map.of())
                            .getVersionId();
                    if ("promoted".equals(current)) {
                        service.deleteObject("bucket", "key", numbered);
                    } else if ("marker".equals(current)) {
                        service.deleteObject("bucket", "key");
                    }
                    if (rollback) {
                        service.restoreBucketConfiguration("bucket", snapshot);
                    } else {
                        service.putBucketVersioning("bucket", "Suspended");
                    }
                    objects.remaining = 1;
                    objects.afterPut = afterPut;

                    assertThrows(IllegalStateException.class, () -> service.putObject(
                            "bucket", "key", bytes("replacement"), "text/plain", Map.of()));

                    S3Object retained = service.getObject("bucket", "key", "null");
                    assertArrayEquals(bytes("original"), retained.getData());
                    assertEquals(original.getETag(), retained.getETag());
                    assertEquals(Map.of("original", "metadata"), retained.getMetadata());
                    assertEquals(Map.of("original", "tag"), service.getObjectTagging("bucket", "key", "null"));
                    assertArrayEquals(bytes("annotation"), service.readObjectAnnotationPayload(
                            service.getObjectAnnotation("bucket", "key", "note", "null")));
                    if ("marker".equals(current)) {
                        assertEquals("NoSuchKey", assertThrows(AwsException.class,
                                () -> service.getObject("bucket", "key")).getErrorCode());
                        if (memory) {
                            assertFalse(memoryBodies(service).values().stream()
                                    .anyMatch(body -> body instanceof byte[] payload
                                            && Arrays.equals(payload, bytes("replacement"))),
                                    "failed marker replacement must not retain canonical bytes");
                        } else {
                            assertFalse(Files.exists(tempDir.resolve("s3/.accounts/000000000000/bucket/key.s3data")),
                                    "failed marker replacement must remove the canonical body");
                        }
                    } else {
                        String previous = "promoted".equals(current) ? "original" : "numbered";
                        assertArrayEquals(bytes(previous), service.getObject("bucket", "key").getData());
                    }

                    service.putObject("bucket", "key", bytes("retry"), "text/plain", Map.of());
                    assertArrayEquals(bytes("retry"), service.getObject("bucket", "key", "null").getData());
                    assertFalse(objects.get("bucket/\0null-version/key").isPresent());
                    assertEquals("NoSuchAnnotation", assertThrows(AwsException.class,
                            () -> service.getObjectAnnotation("bucket", "key", "note", "null")).getErrorCode());
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedNullReplacementRestoresBodyMetadataAndLockAtEachPublication(boolean memory) throws Exception {
        for (boolean marker : List.of(false, true)) {
            for (boolean afterPut : List.of(false, true)) {
                for (int failAt = 1; failAt <= 3; failAt++) {
                    PublicationFailureStore objects = new PublicationFailureStore();
                    S3Service service = service(memory, objects);
                    service.putObject("bucket", "key", bytes("original"), "text/plain", Map.of("original", "metadata"));
                    service.putObjectAnnotation("bucket", "key", "note", null, bytes("annotation"), null, null);
                    service.setBucketObjectLockEnabled("bucket");
                    service.putObjectLegalHold("bucket", "key", "null", "ON");
                    service.putObjectTagging("bucket", "key", Map.of("original", "tag"));
                    service.putBucketVersioning("bucket", "Enabled");
                    objects.remaining = failAt;
                    objects.afterPut = afterPut;

                    assertThrows(IllegalStateException.class, () -> {
                        if (marker) {
                            service.deleteObject("bucket", "key");
                        } else {
                            service.putObject("bucket", "key", bytes("replacement"), "text/plain", Map.of());
                        }
                    });

                    S3Object original = service.getObject("bucket", "key");
                    assertArrayEquals(bytes("original"), original.getData());
                    assertEquals(Map.of("original", "metadata"), original.getMetadata());
                    assertEquals("ON", original.getLegalHoldStatus());
                    assertArrayEquals(bytes("annotation"), service.readObjectAnnotationPayload(
                            service.getObjectAnnotation("bucket", "key", "note", "null")));
                    assertEquals(Map.of("original", "tag"), service.getObjectTagging("bucket", "key", "null"));
                    assertEquals(1, service.listObjectVersions("bucket", null, 1000, null).versions().size());
                    assertFalse(objects.get("bucket/\0null-version/key").isPresent());
                    if (!memory) {
                        try (Stream<Path> files = Files.walk(tempDir.resolve("s3"))) {
                            assertEquals(1, files.filter(Files::isRegularFile)
                                    .filter(file -> file.toString().endsWith(".s3data")).count());
                        }
                    }
                    if (memory) {
                        assertEquals(1, memoryBodies(service).size());
                    }
                    service.putObjectLegalHold("bucket", "key", "null", "OFF");
                    service.deleteObject("bucket", "key", "null");
                    if (!memory) {
                        try (Stream<Path> files = Files.walk(tempDir.resolve("s3"))) {
                            assertEquals(0, files.filter(Files::isRegularFile).count());
                        }
                    } else {
                        assertEquals(0, memoryBodies(service).size());
                    }
                }
            }
        }
    }

    @Test
    void failedStreamBodyWriteRemovesUnpublishedNullLink() throws IOException {
        MissingStagedBodyStore objects = new MissingStagedBodyStore(tempDir.resolve("s3"));
        S3Service service = service(false, objects);
        service.putObject("bucket", "key", bytes("original"), "text/plain", Map.of());
        service.putBucketVersioning("bucket", "Enabled");
        objects.armed = true;

        assertThrows(UncheckedIOException.class, () -> service.putObjectFromStream("bucket", "key",
                new ByteArrayInputStream(bytes("replacement")), "text/plain", Map.of()));

        assertArrayEquals(bytes("original"), service.getObject("bucket", "key").getData());
        service.deleteObject("bucket", "key", "null");
        try (Stream<Path> files = Files.walk(tempDir.resolve("s3"))) {
            assertEquals(0, files.filter(Files::isRegularFile).count());
        }
        service.putObject("bucket", "key", bytes("retry"), "text/plain", Map.of());
        assertArrayEquals(bytes("retry"), service.getObject("bucket", "key").getData());
    }

    @Test
    void deletingNullHistoryUnderAMarkerRemovesAllBodyFiles() throws IOException {
        S3Service service = service(false, new InMemoryStorage<>());
        service.putObject("bucket", "key", bytes("original"), "text/plain", Map.of());
        service.putBucketVersioning("bucket", "Enabled");
        String marker = service.deleteObject("bucket", "key").getVersionId();
        Path canonical = tempDir.resolve("s3/.accounts/000000000000/bucket/key.s3data");
        assertFalse(Files.exists(canonical));
        assertArrayEquals(bytes("original"), service.getObject("bucket", "key", "null").getData());

        service.deleteObject("bucket", "key", "null");

        assertTrue(service.listObjectVersions("bucket", "key", 1000, null).versions().stream()
                .anyMatch(version -> marker.equals(version.getVersionId()) && version.isDeleteMarker()));
        try (Stream<Path> files = Files.walk(tempDir.resolve("s3"))) {
            assertEquals(0, files.filter(Files::isRegularFile).count());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"get", "open", "copy"})
    void readsRetryWhenADeleteMarkerUnlinksTheCanonicalBody(String operation) {
        DeleteDuringReadStore objects = new DeleteDuringReadStore();
        S3Service service = service(false, objects);
        service.putBucketVersioning("bucket", "Enabled");
        service.putObject("bucket", "key", bytes("numbered"), "text/plain", Map.of());
        objects.service = service;
        objects.remaining = "copy".equals(operation) ? 2 : 1;

        AwsException missing = assertThrows(AwsException.class, () -> {
            switch (operation) {
                case "get" -> service.getObject("bucket", "key");
                case "open" -> {
                    try (S3Service.ObjectRead ignored = service.openObject("bucket", "key", null)) {
                        fail("the concurrent marker must make the key absent");
                    }
                }
                case "copy" -> service.copyObject("bucket", "key", "bucket", "destination");
                default -> throw new IllegalArgumentException(operation);
            }
        });
        assertEquals("NoSuchKey", missing.getErrorCode());
        assertEquals(0, objects.remaining);
    }

    @Test
    void unchangedMissingCanonicalBodyRemainsAStorageError() throws IOException {
        S3Service service = service(false, new InMemoryStorage<>());
        service.putBucketVersioning("bucket", "Enabled");
        service.putObject("bucket", "key", bytes("numbered"), "text/plain", Map.of());
        Files.delete(tempDir.resolve("s3/.accounts/000000000000/bucket/key.s3data"));
        assertThrows(UncheckedIOException.class, () -> service.getObject("bucket", "key"));
        assertThrows(UncheckedIOException.class, () -> service.openObject("bucket", "key", null));
        assertThrows(UncheckedIOException.class, () -> service.copyObject("bucket", "key", "bucket", "destination"));
    }

    @Test
    void enabledMarkersNeverOverrideGovernanceButSuspendedArchivedBypassFailsClosed() {
        S3Service service = service(true, new InMemoryStorage<>());
        service.putObject("bucket", "key", bytes("original"), "text/plain", Map.of());
        service.setBucketObjectLockEnabled("bucket");
        service.putObjectRetention("bucket", "key", "null", "GOVERNANCE", Instant.now().plusSeconds(3600), false);
        service.putBucketVersioning("bucket", "Enabled");
        assertFalse(service.deleteOverridesGovernanceRetention("bucket", "key", null));
        service.putObject("bucket", "key", bytes("numbered"), "text/plain", Map.of());
        suspendLockedBucket();
        boolean overrides = service.deleteOverridesGovernanceRetention("bucket", "key", null);
        assertFalse(overrides);
        for (boolean s3Enforced : List.of(false, true)) {
            boolean bypass = S3Controller.effectiveBypass(true, overrides, s3Enforced, false);
            assertFalse(bypass);
            assertDenied(() -> service.deleteObject("bucket", "key", null, bypass));
        }
    }

    private static Map<?, ?> memoryBodies(S3Service service) throws Exception {
        Field bodies = S3Service.class.getDeclaredField("memoryDataStore");
        bodies.setAccessible(true);
        return (Map<?, ?>) bodies.get(service);
    }

    private static final class DeleteDuringReadStore extends InMemoryStorage<String, S3Object> {
        private S3Service service;
        private int remaining;

        @Override
        public Optional<S3Object> get(String key) {
            Optional<S3Object> object = super.get(key);
            if ("bucket/key".equals(key) && remaining > 0 && --remaining == 0) {
                service.deleteObject("bucket", "key");
            }
            return object;
        }
    }

    private S3Service service(boolean memory, InMemoryStorage<String, S3Object> objects) {
        buckets = new InMemoryStorage<>();
        S3Service service = new S3Service(buckets, objects, tempDir.resolve("s3"), memory);
        service.createBucket("bucket", "us-east-1");
        return service;
    }

    /**
     * Suspends versioning on a bucket that already has Object Lock. PutBucketVersioning refuses
     * this, but a bucket stored before it did can still be in this state.
     */
    private void suspendLockedBucket() {
        Bucket bucket = buckets.get("bucket").orElseThrow();
        bucket.setVersioningStatus("Suspended");
        buckets.put("bucket", bucket);
    }

    private static Object readSnapshot(S3Service service, boolean explicitNull, Function<Object, String> reader)
            throws Exception {
        Method method = explicitNull
                ? S3Service.class.getDeclaredMethod("readNullVersionSnapshot", String.class, String.class,
                        Function.class, Consumer.class)
                : S3Service.class.getDeclaredMethod("readLatestSnapshot", String.class, String.class, String.class,
                        Function.class, Consumer.class);
        method.setAccessible(true);
        Consumer<String> discard = ignored -> { };
        return explicitNull ? method.invoke(service, "bucket", "key", reader, discard)
                : method.invoke(service, "bucket", "key", null, reader, discard);
    }

    private static void assertDenied(Runnable request) {
        assertEquals("AccessDenied", assertThrows(AwsException.class, request::run).getErrorCode());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static final class PublicationFailureStore extends InMemoryStorage<String, S3Object> {
        private int remaining;
        private boolean afterPut;
        private Runnable beforeFailure;

        @Override
        public void put(String key, S3Object value) {
            boolean fail = remaining > 0 && --remaining == 0;
            if (fail && !afterPut) {
                throw new IllegalStateException("injected metadata publication failure");
            }
            super.put(key, value);
            if (fail) {
                if (beforeFailure != null) {
                    beforeFailure.run();
                }
                throw new IllegalStateException("injected failure after metadata publication");
            }
        }
    }

    private static final class MissingStagedBodyStore extends InMemoryStorage<String, S3Object> {
        private final Path root;
        private boolean armed;

        private MissingStagedBodyStore(Path root) {
            this.root = root;
        }

        @Override
        public Optional<S3Object> get(String key) {
            if (armed && "bucket/key".equals(key)) {
                armed = false;
                try (Stream<Path> staged = Files.walk(root.resolve(".multipart"))) {
                    for (Path file : staged.filter(Files::isRegularFile).toList()) {
                        Files.delete(file);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return super.get(key);
        }
    }
}
