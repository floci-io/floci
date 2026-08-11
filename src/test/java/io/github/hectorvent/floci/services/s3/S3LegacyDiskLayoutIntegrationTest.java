package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Before account-scoped object storage, every disk-mode S3 object lived at
 * {@code dataRoot/bucketName/key.s3data}, with no account segment. An existing Floci
 * installation upgrading past that change must not lose access to objects already on disk
 * in that legacy layout — object *metadata* (in {@code objectStore}) was already
 * account-scoped before this change and is unaffected by an upgrade, but the physical bytes
 * were not, so a naive account-scoped path resolver alone would 404 on every pre-existing
 * object.
 */
class S3LegacyDiskLayoutIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void existingObjectWrittenUnderTheLegacyUnscopedPathIsStillReadable() throws Exception {
        Path dataRoot = tempDir.resolve("s3");
        S3Service s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), dataRoot, false);

        s3Service.createBucket("legacy-bucket", "us-east-1");
        byte[] data = "pre-upgrade-content".getBytes(StandardCharsets.UTF_8);
        // Establishes correct objectStore metadata (unaffected by the account-scoping change)
        // and writes bytes at the new, account-scoped path — then that file is relocated to
        // simulate what an object written before this change actually looks like on disk.
        s3Service.putObject("legacy-bucket", "legacy-key.txt", data, "text/plain", null);

        Path newLayoutFile = dataRoot.resolve("000000000000").resolve("legacy-bucket").resolve("legacy-key.txt.s3data");
        Path legacyLayoutFile = dataRoot.resolve("legacy-bucket").resolve("legacy-key.txt.s3data");
        assertTrue(Files.exists(newLayoutFile), "test setup: object should initially be written at the new path");

        Files.createDirectories(legacyLayoutFile.getParent());
        Files.move(newLayoutFile, legacyLayoutFile, StandardCopyOption.REPLACE_EXISTING);

        S3Object retrieved = s3Service.getObject("legacy-bucket", "legacy-key.txt");
        assertArrayEquals(data, retrieved.getData(),
                "an object physically stored under the pre-upgrade unscoped layout must still be readable");
    }
}
