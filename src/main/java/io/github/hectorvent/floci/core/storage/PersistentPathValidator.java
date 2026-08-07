package io.github.hectorvent.floci.core.storage;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ResolvedServiceCatalog;
import io.github.hectorvent.floci.core.common.ServiceDescriptor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Validates at boot that the persistent storage path is usable whenever any enabled service
 * runs with a non-memory storage mode. Without this check the failure surfaces lazily and
 * confusingly: S3 answers every request with an opaque 500 (its service bean creates its data
 * directory in the constructor), while other services silently lose data at flush time.
 */
@ApplicationScoped
public class PersistentPathValidator {

    private final ResolvedServiceCatalog catalog;
    private final EmulatorConfig config;

    @Inject
    public PersistentPathValidator(ResolvedServiceCatalog catalog, EmulatorConfig config) {
        this.catalog = catalog;
        this.config = config;
    }

    /**
     * @throws IllegalStateException when persistence is enabled but the path is unusable
     */
    public void validateAtBoot() {
        List<ServiceDescriptor> persistent = catalog.all().stream()
                .filter(d -> d.enabled() && d.supportsStorage() && !"memory".equals(d.storageMode()))
                .toList();
        if (persistent.isEmpty()) {
            return;
        }

        Path root = Path.of(config.storage().persistentPath());
        try {
            probeWritable(root);
            Path s3Root = root.resolve("s3");
            boolean s3Persistent = persistent.stream().anyMatch(d -> "s3".equals(d.storageKey()));
            if (s3Persistent && Files.isDirectory(s3Root)) {
                probeWritable(s3Root);
            }
        } catch (IOException | SecurityException e) {
            String services = persistent.stream()
                    .map(d -> d.storageKey() + "=" + d.storageMode())
                    .distinct()
                    .limit(8)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            throw new IllegalStateException(
                    "Persistent storage path '" + root.toAbsolutePath()
                            + "' is not writable, but non-memory storage is enabled (" + services
                            + "). Fix the volume mount permissions (it may be read-only or root-owned),"
                            + " or point FLOCI_STORAGE_PERSISTENT_PATH at a writable directory.", e);
        }
    }

    static void probeWritable(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path probe = Files.createTempFile(dir, ".floci-write-probe", null);
        try {
            Files.deleteIfExists(probe);
        } catch (IOException e) {
            // The write itself succeeded, so the path is writable; a failed cleanup
            // must not abort boot as a false "not writable".
            probe.toFile().deleteOnExit();
        }
    }
}
