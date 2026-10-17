package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.CopyObjectOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
