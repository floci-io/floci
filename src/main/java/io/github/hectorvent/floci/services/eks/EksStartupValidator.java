package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

/**
 * Refuses to start Floci with an invalid {@code floci.services.eks.endpoint-host}. Checked once here, before
 * persisted clusters are restored, so a typo stops the boot instead of failing every cluster on restore.
 * Metadata-only EKS (disabled or mock) never uses the setting, so it is not checked there.
 */
@ApplicationScoped
public class EksStartupValidator {

    private final EmulatorConfig config;

    @Inject
    public EksStartupValidator(EmulatorConfig config) {
        this.config = config;
    }

    void onStart(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) StartupEvent ignored) {
        if (config.services().eks().enabled() && !config.services().eks().mock()) {
            EksClusterManager.endpointHostFrom(config.services().eks().endpointHost());
        }
    }
}
