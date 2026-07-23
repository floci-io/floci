package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reproduces issue #1921: two components request the same storage file, the factory hands out
 * two independent backends, and on graceful shutdown the stale duplicate flushes last and
 * clobbers the persisted state written by the active instance.
 */
@QuarkusTest
@TestProfile(StorageFactoryDuplicateBackendTest.PersistentProfile.class)
class StorageFactoryDuplicateBackendTest {

    private static final String STORAGE_PATH = "/tmp/floci-dedupe-backend-test";
    private static final String FILE_NAME = "dynamodb-tables.json";

    @Inject
    StorageFactory storageFactory;

    @BeforeEach
    void cleanFile() throws IOException {
        Files.deleteIfExists(Path.of(STORAGE_PATH, FILE_NAME));
    }

    @Test
    void duplicateBackendsForSameFileDoNotClobberOnFlush() {
        TypeReference<Map<String, String>> typeRef = new TypeReference<>() {};

        // Active component (e.g. DynamoDbService) opens the tables file.
        StorageBackend<String, String> active = storageFactory.create("dynamodb", FILE_NAME, typeRef);
        // A second component (e.g. DynamoDbStreamService) opens the same file later.
        storageFactory.create("dynamodb", FILE_NAME, typeRef);

        // Active component writes tables; persistent mode write-throughs to disk.
        for (int i = 0; i < 60; i++) {
            active.put("table-" + i, "definition-" + i);
        }

        // Graceful shutdown flushes every registered backend. The stale duplicate must not
        // overwrite the active instance's data.
        storageFactory.flushAll();

        // Read the persisted file back with an independent reader.
        PersistentStorage<String, String> reader =
                new PersistentStorage<>(Path.of(STORAGE_PATH, FILE_NAME), typeRef);
        reader.load();

        assertEquals(60, reader.keys().size(),
                "persisted table definitions should survive flushAll despite the duplicate backend");
    }

    public static final class PersistentProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.storage.mode", "memory",
                    "floci.storage.services.dynamodb.mode", "persistent",
                    "floci.storage.persistent-path", STORAGE_PATH
            );
        }
    }
}
