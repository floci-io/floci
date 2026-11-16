package io.floci.aws.services.applicationautoscaling;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.SweepScheduler;
import io.floci.aws.core.common.SweepWorker;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.time.Instant;

/** Executes one-off actions after persisted storage and ECS runtime have been restored. */
@ApplicationScoped
public class ApplicationAutoScalingScheduledActionScheduler extends SweepScheduler {
    private final ApplicationAutoScalingService service;
    private final boolean enabled;

    @Inject
    public ApplicationAutoScalingScheduledActionScheduler(ApplicationAutoScalingService service, EmulatorConfig config) {
        super(new SweepWorker("application-autoscaling-scheduled-actions", config.services().applicationautoscaling().enabled(),
                config.services().applicationautoscaling().scheduledActionIntervalSeconds(), false, service::runScheduledActions));
        this.service = service;
        this.enabled = config.services().applicationautoscaling().enabled();
    }

    @Override
    public void onStart(@Observes @Priority(3000) StartupEvent event) {
        if (enabled) {
            service.recoverScheduledActions(Instant.now());
        }
        super.onStart(event);
    }

    @Override
    public void onStop(@Observes @Priority(1000) ShutdownEvent event) {
        // SweepWorker.stop waits at most five seconds. Drain here before lifecycle storage flush.
        beforeReset();
        super.onStop(event);
    }
}
