package io.github.hectorvent.floci.services.quicksight;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.SweepScheduler;
import io.github.hectorvent.floci.core.common.SweepWorker;
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
