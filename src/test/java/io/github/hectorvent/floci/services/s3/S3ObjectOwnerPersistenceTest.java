package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class S3ObjectOwnerPersistenceTest {

    private static final String WRITER_ACCOUNT = "000000000012";

    @Test
    void objectOwnerSurvivesMetadataReloadWhileLegacyRecordsRemainReadable(@TempDir Path dir) {
        Path file = dir.resolve("s3-objects.json");
        PersistentStorage<String, S3Object> first = storage(file);
        S3Object writerObject = new S3Object("bucket", "writer.txt", new byte[0], null);
        writerObject.setOwnerAccountId(WRITER_ACCOUNT);
        first.put("bucket/writer.txt", writerObject);
        first.put("bucket/legacy.txt", new S3Object("bucket", "legacy.txt", new byte[0], null));

        PersistentStorage<String, S3Object> restarted = storage(file);
        restarted.load();

        assertEquals(WRITER_ACCOUNT, restarted.get("bucket/writer.txt").orElseThrow().getOwnerAccountId());
        assertNull(restarted.get("bucket/legacy.txt").orElseThrow().getOwnerAccountId());
    }

    private static PersistentStorage<String, S3Object> storage(Path file) {
        return new PersistentStorage<>(file, new TypeReference<Map<String, S3Object>>() {});
    }
}
