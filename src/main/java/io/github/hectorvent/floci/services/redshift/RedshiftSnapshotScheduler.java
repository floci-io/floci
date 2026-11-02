package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.SweepScheduler;
import io.github.hectorvent.floci.core.common.SweepWorker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Drives automated snapshots outside AWS request threads. */
@ApplicationScoped
public class RedshiftSnapshotScheduler extends SweepScheduler {
    @Inject
    public RedshiftSnapshotScheduler(RedshiftService service, EmulatorConfig config) {
        super(worker(service, config.services().redshift()));
    }

    private static SweepWorker worker(RedshiftService service, EmulatorConfig.RedshiftServiceConfig redshift) {
        return new SweepWorker("redshift-snapshot-scheduler",
                redshift.enabled() && redshift.automatedSnapshotsEnabled(),
                redshift.snapshotSweepIntervalSeconds(), false, service::runSnapshotSchedules);
    }
}
