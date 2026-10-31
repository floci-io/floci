package io.github.hectorvent.floci.services.redshiftserverless;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.SweepScheduler;
import io.github.hectorvent.floci.core.common.SweepWorker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Drives Serverless snapshot expirations outside AWS request threads, one sweep at a time. */
@ApplicationScoped
public class RedshiftServerlessSnapshotExpirationScheduler extends SweepScheduler {
    @Inject
    public RedshiftServerlessSnapshotExpirationScheduler(RedshiftServerlessService service,
                                                      RedshiftServerlessResourcePolicyService policies,
                                                      EmulatorConfig config) {
        super(worker(service, policies, config.services().redshiftServerless()));
    }

    private static SweepWorker worker(RedshiftServerlessService service,
                                      RedshiftServerlessResourcePolicyService policies,
                                      EmulatorConfig.RedshiftServerlessServiceConfig serverless) {
        return new SweepWorker("redshift-serverless-snapshot-expiration",
                serverless.enabled() && serverless.snapshotExpirationEnabled(),
                serverless.snapshotExpirationSweepIntervalSeconds(), true,
                now -> service.expireSnapshots(now, policies::deleteExpiredSnapshotPolicy));
    }
}
