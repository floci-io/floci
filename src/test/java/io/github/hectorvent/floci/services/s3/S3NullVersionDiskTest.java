package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.CopyObjectOptions;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request naming {@code versionId=null} in disk-storage mode, where the null version's bytes are
 * the object file at the plain key: GetObject streams it and CopyObject pins it, and a key without
 * a null version has neither.
 */
class S3NullVersionDiskTest {

    @TempDir
    Path tempDir;

    private S3Service s3Service;

    @BeforeEach
    void setUp() {
        s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), tempDir.resolve("s3"), false);
        s3Service.createBucket("bucket", "us-east-1");
        s3Service.putObject("bucket", "old.txt", bytes("null version"), "text/plain", Map.of());
        s3Service.putBucketVersioning("bucket", "Enabled");
        s3Service.putObject("bucket", "new.txt", bytes("current version"), "text/plain", Map.of());
    }

    @Test
    void theNullVersionIsStreamedAndCopiedFromTheObjectFile() throws IOException {
        try (S3Service.ObjectRead read = s3Service.openObject("bucket", "old.txt", "null")) {
            assertArrayEquals(bytes("null version"), read.body().readAllBytes());
        }
        try (InputStream stream = s3Service.openObjectStream("bucket", "old.txt", "null")) {
            assertArrayEquals(bytes("null version"), stream.readAllBytes());
        }

        s3Service.copyObject("bucket", "old.txt", "bucket", "copy.txt", "null", new CopyObjectOptions());

        assertArrayEquals(bytes("null version"), s3Service.getObject("bucket", "copy.txt").getData());
    }

    @Test
    void aKeyWithoutANullVersionIsNeitherStreamedNorCopied() {
        for (Executable request : List.<Executable>of(
                () -> s3Service.openObject("bucket", "new.txt", "null").close(),
                () -> s3Service.copyObject("bucket", "new.txt", "bucket", "copy.txt", "null",
                        new CopyObjectOptions()))) {
            AwsException e = assertThrows(AwsException.class, request);
            assertEquals("NoSuchVersion", e.getErrorCode());
        }
    }

    @Test
    void anArchivedNullVersionUsesTheVersionedFileAndCanBePromoted() throws IOException {
        String currentVersionId = s3Service.putObject(
                "bucket", "old.txt", bytes("versioned replacement"), "text/plain", Map.of()).getVersionId();
        Path archived = tempDir.resolve("s3").resolve(".accounts").resolve("000000000000")
                .resolve(".versions").resolve("bucket").resolve("old.txt").resolve("null.s3data");

        assertTrue(Files.exists(archived));
        assertArrayEquals(bytes("null version"), Files.readAllBytes(archived));
        try (S3Service.ObjectRead read = s3Service.openObject("bucket", "old.txt", "null")) {
            assertArrayEquals(bytes("null version"), read.body().readAllBytes());
        }
        s3Service.copyObject("bucket", "old.txt", "bucket", "archived-copy.txt", "null",
                new CopyObjectOptions());
        assertArrayEquals(bytes("null version"), s3Service.getObject("bucket", "archived-copy.txt").getData());

        s3Service.deleteObject("bucket", "old.txt", currentVersionId);

        assertArrayEquals(bytes("null version"), s3Service.getObject("bucket", "old.txt").getData());
        assertEquals(1, s3Service.listObjectVersions("bucket", "old.txt", 1000, null).versions().size());
        s3Service.deleteObject("bucket", "old.txt", "null");
        AwsException missing = assertThrows(AwsException.class,
                () -> s3Service.getObject("bucket", "old.txt"));
        assertEquals("NoSuchKey", missing.getErrorCode());
    }

    @Test
    void archivedNullReadsRetryWhenASuspendedPutReplacesTheHistoryFile() throws IOException {
        ArchivedNullReplacementStore objectStore = new ArchivedNullReplacementStore();
        S3Service service = new S3Service(
                new InMemoryStorage<>(), objectStore, tempDir.resolve("racing-s3"), false);
        service.createBucket("racing-bucket", "us-east-1");
        List<String> keys = List.of("get.txt", "stream.txt", "copy.txt");
        for (String key : keys) {
            service.putObject("racing-bucket", key, bytes("original"), "text/plain", Map.of());
        }
        service.putBucketVersioning("racing-bucket", "Enabled");
        for (String key : keys) {
            service.putObject("racing-bucket", key, bytes("numbered"), "text/plain", Map.of());
        }
        service.putBucketVersioning("racing-bucket", "Suspended");

        objectStore.arm(service, "get.txt");
        assertArrayEquals(bytes("replacement"),
                service.getObject("racing-bucket", "get.txt", "null").getData());

        objectStore.arm(service, "stream.txt");
        try (S3Service.ObjectRead read = service.openObject("racing-bucket", "stream.txt", "null")) {
            assertArrayEquals(bytes("replacement"), read.body().readAllBytes());
        }

        objectStore.arm(service, "copy.txt");
        service.copyObject("racing-bucket", "copy.txt", "racing-bucket", "copied.txt", "null",
                new CopyObjectOptions());
        assertArrayEquals(bytes("replacement"), service.getObject("racing-bucket", "copied.txt").getData());
    }

    private static final class ArchivedNullReplacementStore extends InMemoryStorage<String, S3Object> {
        private S3Service service;
        private String armedKey;

        private void arm(S3Service service, String key) {
            this.service = service;
            armedKey = key;
        }

        @Override
        public Optional<S3Object> get(String key) {
            Optional<S3Object> result = super.get(key);
            String target = "racing-bucket/\0null-version/" + armedKey;
            if (armedKey != null && target.equals(key)) {
                String replacing = armedKey;
                armedKey = null;
                service.putObject("racing-bucket", replacing,
                        bytes("replacement"), "text/plain", Map.of());
            }
            return result;
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
