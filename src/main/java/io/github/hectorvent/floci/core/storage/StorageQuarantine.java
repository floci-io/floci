package io.github.hectorvent.floci.core.storage;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Shared quarantine handling for storage backends that persist to a JSON file: moves a file that
 * failed to deserialize aside to a sibling {@code .corrupt} path, replacing any prior quarantine
 * there, so the data loss is visible instead of silently starting empty.
 */
final class StorageQuarantine {

    private StorageQuarantine() {
    }

    static void quarantine(Path filePath, IOException cause, Logger log) {
        Path quarantine = filePath.resolveSibling(filePath.getFileName() + ".corrupt");
        try {
            Files.move(filePath, quarantine, StandardCopyOption.REPLACE_EXISTING);
            log.errorv(cause, "Failed to load persisted data from {0}; moved the unreadable file to "
                    + "{1} and started with an empty store. This store's state was lost.",
                    filePath, quarantine);
        } catch (IOException moveError) {
            log.errorv(cause, "Failed to load persisted data from {0}; could not quarantine it ({1}). "
                    + "Starting with an empty store. This store's state was lost.",
                    filePath, moveError.getMessage());
        }
    }
}
