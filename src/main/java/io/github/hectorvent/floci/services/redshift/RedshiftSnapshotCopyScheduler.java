package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.SweepScheduler;
import io.github.hectorvent.floci.core.common.SweepWorker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Drives cross-Region snapshot copies outside AWS request threads, one sweep at a time. */
@ApplicationScoped
public class RedshiftSnapshotCopyScheduler extends SweepScheduler {
    @Inject
    public RedshiftSnapshotCopyScheduler(RedshiftService service, EmulatorConfig config) {
        super(worker(service, config.services().redshift()));
    }

    private static SweepWorker worker(RedshiftService service, EmulatorConfig.RedshiftServiceConfig redshift) {
        return new SweepWorker("redshift-snapshot-copy",
                redshift.enabled() && redshift.snapshotCopyEnabled(),
                redshift.snapshotCopySweepIntervalSeconds(), true, service::runSnapshotCopies);
    }
}
