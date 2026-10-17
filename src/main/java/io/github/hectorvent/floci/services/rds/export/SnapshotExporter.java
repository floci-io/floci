package io.github.hectorvent.floci.services.rds.export;

import io.github.hectorvent.floci.services.rds.model.ExportTask;

import java.time.Instant;
import java.util.List;

/** Writes one export task's data to its bucket. */
public interface SnapshotExporter {

    /** How the exporter reports back to the task it runs for. */
    interface Progress {

        /** The snapshot is restored and tables are being written; {@code percent} of them are done. */
        void extracting(int percent);

        /** True once the task was canceled, which stops the export before its next table. */
        boolean canceled();

        boolean abandonedByReset();
    }

    /**
     * What an export wrote: its size, a warning for each table it had to leave out, and when it
     * finished, which is also the end time its {@code export_info} file records.
     */
    record Outcome(long bytesExtracted, List<String> warnings, Instant finishedAt) {}

    Outcome export(ExportTask task, SnapshotExportSource source, Progress progress);

    /** Removes whatever an export that never finished left behind, after a restart. */
    default void discard(ExportTask task) {}
}
