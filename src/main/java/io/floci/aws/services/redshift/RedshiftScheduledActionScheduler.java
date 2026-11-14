package io.floci.aws.services.redshift;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.SweepScheduler;
import io.floci.aws.core.common.SweepWorker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Drives provisioned scheduled actions outside AWS request threads, one sweep at a time. */
@ApplicationScoped
public class RedshiftScheduledActionScheduler extends SweepScheduler {
    @Inject
    public RedshiftScheduledActionScheduler(RedshiftScheduledActionService service, EmulatorConfig config) {
        super(worker(service, config.services().redshift()));
    }

    private static SweepWorker worker(RedshiftScheduledActionService service,
                                      EmulatorConfig.RedshiftServiceConfig redshift) {
        return new SweepWorker("redshift-scheduled-actions",
                redshift.enabled() && redshift.scheduledActionsEnabled(),
                redshift.scheduledActionSweepIntervalSeconds(), false, service::runScheduledActions);
    }
}
