package io.github.hectorvent.floci.services.redshift;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Copies a snapshot dump into another directory without ever exposing a half-written file. */
public final class SnapshotDumpCopier {
    private SnapshotDumpCopier() {
    }

    /**
     * Copies {@code stored}, which must be a regular file inside {@code sourceDirectory}, into
     * {@code destinationDirectory} under a fresh name. Each copy gets its own file, so replacing an earlier
     * copy never touches the dump it still points to; the snapshot name is left out because a 255-character
     * name plus a suffix would exceed filesystem filename limits.
     */
    public static Path copy(Path stored, Path sourceDirectory, Path destinationDirectory) throws IOException {
        Path from = stored.toAbsolutePath().normalize();
        if (!from.startsWith(sourceDirectory) || from.equals(sourceDirectory) || !Files.isRegularFile(from)) {
            throw new IOException("Snapshot dump is missing or outside its directory: " + from);
        }
        String fileName = UUID.randomUUID().toString();
        Path to = destinationDirectory.resolve(fileName + ".sql");
        Path staging = destinationDirectory.resolve(fileName + ".sql.copying");
        Files.createDirectories(destinationDirectory);
        try {
            Files.copy(from, staging, StandardCopyOption.REPLACE_EXISTING);
            Files.move(staging, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(staging);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
        return to;
    }
}
