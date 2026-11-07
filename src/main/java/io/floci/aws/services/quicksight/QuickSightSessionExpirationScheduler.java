package io.floci.aws.services.quicksight;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.SweepScheduler;
import io.floci.aws.core.common.SweepWorker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class QuickSightSessionExpirationScheduler extends SweepScheduler {
    private static final long SWEEP_INTERVAL_SECONDS = 60;

    @Inject
    public QuickSightSessionExpirationScheduler(QuickSightService service, EmulatorConfig config) {
        super(new SweepWorker("quicksight-session-expiration", config.services().quicksight().enabled(),
                SWEEP_INTERVAL_SECONDS, true, service::cleanupExpiredSessions));
    }
}
