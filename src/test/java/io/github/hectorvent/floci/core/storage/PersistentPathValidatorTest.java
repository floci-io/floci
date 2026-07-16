package io.github.hectorvent.floci.core.storage;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ResolvedServiceCatalog;
import io.github.hectorvent.floci.core.common.ServiceDescriptor;
import io.github.hectorvent.floci.core.common.ServiceProtocol;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersistentPathValidatorTest {

    @TempDir
    Path tempDir;

    @Mock private ResolvedServiceCatalog catalog;
    @Mock private EmulatorConfig config;
    @Mock private EmulatorConfig.StorageConfig storageConfig;

    private PersistentPathValidator validator() {
        lenient().when(config.storage()).thenReturn(storageConfig);
        return new PersistentPathValidator(catalog, config);
    }

    private static ServiceDescriptor descriptor(String key, boolean enabled, String storageKey, String mode) {
        return new ServiceDescriptor(key, key, enabled, true, storageKey, mode, 0L,
                null, ServiceProtocol.REST_XML, Set.of(ServiceProtocol.REST_XML),
                Set.of(), Set.of(), Set.of(), Set.of());
    }

    @Test
    void memoryOnlyBootDoesNotTouchTheFilesystem() {
        when(catalog.all()).thenReturn(List.of(
                descriptor("s3", true, "s3", "memory"),
                descriptor("sqs", true, "sqs", "memory")));

        PersistentPathValidator validator = validator();
        validator.validateAtBoot();
    }

    @Test
    void nonMemoryModeCreatesPathAndLeavesNoProbeBehind() throws IOException {
        Path root = tempDir.resolve("data");
        when(catalog.all()).thenReturn(List.of(descriptor("sqs", true, "sqs", "hybrid")));
        when(storageConfig.persistentPath()).thenReturn(root.toString());

        validator().validateAtBoot();

        assertTrue(Files.isDirectory(root));
        try (var entries = Files.list(root)) {
            assertEquals(0, entries.count(), "write probe must not be left behind");
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void unwritablePathFailsWithActionableMessage() throws IOException {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")),
                "root ignores directory write permissions");
        Path readOnlyParent = tempDir.resolve("ro");
        Files.createDirectories(readOnlyParent);
        assertTrue(readOnlyParent.toFile().setWritable(false));
        try {
            Path root = readOnlyParent.resolve("data");
            when(catalog.all()).thenReturn(List.of(descriptor("s3", true, "s3", "hybrid")));
            when(storageConfig.persistentPath()).thenReturn(root.toString());

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> validator().validateAtBoot());
            assertTrue(e.getMessage().contains(root.toAbsolutePath().toString()));
            assertTrue(e.getMessage().contains("FLOCI_STORAGE_PERSISTENT_PATH"));
            assertTrue(e.getMessage().contains("s3=hybrid"));
        } finally {
            assertTrue(readOnlyParent.toFile().setWritable(true));
        }
    }

    @Test
    void persistentPathThatIsAFileFailsClearly() throws IOException {
        Path root = tempDir.resolve("data");
        Files.writeString(root, "not a directory");
        when(catalog.all()).thenReturn(List.of(descriptor("s3", true, "s3", "persistent")));
        when(storageConfig.persistentPath()).thenReturn(root.toString());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> validator().validateAtBoot());
        assertTrue(e.getMessage().contains(root.toAbsolutePath().toString()));
    }

    @Test
    void disabledServicesDoNotTriggerValidation() {
        when(catalog.all()).thenReturn(List.of(descriptor("s3", false, "s3", "hybrid")));

        validator().validateAtBoot();
    }
}
