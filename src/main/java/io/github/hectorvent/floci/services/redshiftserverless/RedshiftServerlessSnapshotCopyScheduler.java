package io.github.hectorvent.floci.services.redshiftserverless;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.SweepScheduler;
import io.github.hectorvent.floci.core.common.SweepWorker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Drives Serverless snapshot copies outside AWS request threads, one sweep at a time. */
@ApplicationScoped
public class RedshiftServerlessSnapshotCopyScheduler extends SweepScheduler {
    @Inject
    public RedshiftServerlessSnapshotCopyScheduler(RedshiftServerlessSnapshotCopyService copies,
                                                   EmulatorConfig config) {
        super(worker(copies, config.services().redshiftServerless()));
    }

    private static SweepWorker worker(RedshiftServerlessSnapshotCopyService copies,
                                      EmulatorConfig.RedshiftServerlessServiceConfig serverless) {
        return new SweepWorker("redshift-serverless-snapshot-copy",
                serverless.enabled() && serverless.snapshotCopyEnabled(),
                serverless.snapshotCopySweepIntervalSeconds(), true,
                copies::runCopySweep);
    }
}
