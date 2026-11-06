package io.floci.aws.services.redshiftserverless;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.SweepScheduler;
import io.floci.aws.core.common.SweepWorker;
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
