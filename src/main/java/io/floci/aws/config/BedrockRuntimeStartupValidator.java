package io.floci.aws.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class BedrockRuntimeStartupValidator {

    private final EmulatorConfig config;

    @Inject
    public BedrockRuntimeStartupValidator(EmulatorConfig config) {
        this.config = config;
    }

    void onStart(@Observes StartupEvent ignored) {
        validateErrorBodyTimeoutMillis(config.services().bedrockRuntime().proxy().errorBodyTimeoutMillis());
    }

    public static void validateErrorBodyTimeoutMillis(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "floci.services.bedrock-runtime.proxy.error-body-timeout-millis must be positive");
        }
    }
}
