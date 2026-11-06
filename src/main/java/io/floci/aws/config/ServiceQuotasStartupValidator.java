package io.floci.aws.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class ServiceQuotasStartupValidator {

    private final EmulatorConfig config;

    @Inject
    public ServiceQuotasStartupValidator(EmulatorConfig config) {
        this.config = config;
    }

    void onStart(@Observes StartupEvent ignored) {
        validateStandardOnDemandVcpus(config.services().servicequotas().standardOnDemandVcpus());
    }

    public static void validateStandardOnDemandVcpus(int value) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "floci.services.servicequotas.standard-on-demand-vcpus must be nonnegative");
        }
    }
}
