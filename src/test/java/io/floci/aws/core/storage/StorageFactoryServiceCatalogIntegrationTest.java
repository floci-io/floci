package io.floci.aws.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.testing.PerServicePersistentStorageProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.floci.aws.testing.PerServicePersistentStorageProfile.STORAGE_DIR;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A per-service storage mode takes effect: with the test default, memory, for storage as a whole
 * and ACM's own mode set to persistent, an ACM store writes through to its file.
 *
 * <p>The ACM override comes from {@link PerServicePersistentStorageProfile}, which also puts
 * DynamoDB and S3 on disk for the other classes on it. This class creates only the ACM store, so
 * those two never reach it. It creates that store on a factory of its own, built from the
 * application's configuration, so the application never holds it and no later flush of the
 * application's stores writes the file back after the test deletes it.
 */
@QuarkusTest
@TestProfile(PerServicePersistentStorageProfile.class)
class StorageFactoryServiceCatalogIntegrationTest {

    private static final String FILE_NAME = "acm-test.json";

    @Inject
    EmulatorConfig config;

    @Inject
    ServiceConfigAccess serviceConfigAccess;

    @Test
    void acmStorageOverrideIsApplied() throws IOException {
        Path file = Path.of(STORAGE_DIR, FILE_NAME);
        Files.deleteIfExists(file);
        StorageFactory factory = new StorageFactory(config, serviceConfigAccess);
        StorageBackend<String, String> backend = factory.create(
                "acm",
                FILE_NAME,
                new TypeReference<Map<String, String>>() {}
        );
        try {
            backend.put("probe", "persisted");

            // A store in the test default mode, memory, never writes the file.
            assertTrue(Files.exists(file), "the ACM store should write through to " + file);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
