package io.github.hectorvent.floci.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * File and scheduling steps the file-backed storage backends share: replacing a file atomically
 * and running a periodic flush or compaction on a daemon thread.
 */
final class StorageFiles {

    private static final long SHUTDOWN_WAIT_SECONDS = 5;

    /** Writes the content of a file at the given path. */
    @FunctionalInterface
    interface ContentWriter {
        void write(Path file) throws IOException;
    }

    private StorageFiles() {
    }

    /**
     * Writes {@code file} through a sibling {@code .tmp} file that is then moved over it, so a
     * reader or a crash sees either the old content or the new, never a partial file.
     */
    static void writeAtomically(Path file, ContentWriter writer) throws IOException {
        Files.createDirectories(file.getParent());
        Path tempFile = file.resolveSibling(file.getFileName() + ".tmp");
        writer.write(tempFile);
        Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Runs {@code task} every {@code intervalMs} on one daemon thread named {@code threadName}. */
    static ScheduledExecutorService scheduleEvery(String threadName, long intervalMs, Runnable task) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(task, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        return scheduler;
    }

    /** Stops {@code scheduler}, letting a run in progress finish for a few seconds first. */
    static void stop(ScheduledExecutorService scheduler) {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
