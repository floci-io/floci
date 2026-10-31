package io.github.hectorvent.floci.services.redshiftserverless;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.SweepScheduler;
import io.github.hectorvent.floci.core.common.SweepWorker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Drives Serverless scheduled actions outside AWS request threads, one sweep at a time. */
@ApplicationScoped
public class RedshiftServerlessScheduledActionScheduler extends SweepScheduler {
    @Inject
    public RedshiftServerlessScheduledActionScheduler(RedshiftServerlessScheduledActionService service,
                                                      EmulatorConfig config) {
        super(worker(service, config.services().redshiftServerless()));
    }

    private static SweepWorker worker(RedshiftServerlessScheduledActionService service,
                                      EmulatorConfig.RedshiftServerlessServiceConfig serverless) {
        return new SweepWorker("redshift-serverless-scheduled-actions",
                serverless.enabled() && serverless.scheduledActionsEnabled(),
                serverless.scheduledActionSweepIntervalSeconds(), false, service::runScheduledActions);
    }
}
