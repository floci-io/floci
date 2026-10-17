package io.github.hectorvent.floci.services.rds.export;

import io.github.hectorvent.floci.services.rds.model.ExportTask;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class UnsupportedSnapshotExporter implements SnapshotExporter {

    static final String FAILURE_CAUSE = "Floci does not write snapshot data to S3 yet: "
            + "export tasks complete only in mock mode (FLOCI_SERVICES_RDS_MOCK=true)";

    @Override
    public Outcome export(ExportTask task, SnapshotExportSource source, Progress progress) {
        throw new UnsupportedOperationException(FAILURE_CAUSE);
    }
}
