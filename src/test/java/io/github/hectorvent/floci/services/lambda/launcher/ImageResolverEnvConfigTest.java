package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ImageResolverEnvConfigTest {

    @Test
    void dottedRuntimeKeyBindsFromEnvironment() {
        String image = "registry.example/lambda-python:3.12";
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .withSources(new EnvConfigSource(Map.of(
                        "FLOCI_SERVICES_LAMBDA_RUNTIME_IMAGES__PYTHON3_12__", image), 300))
                .withMapping(EmulatorConfig.class)
                .build();

        assertEquals(image, config.getConfigMapping(EmulatorConfig.class)
                .services().lambda().runtimeImages().get("python3.12"));
    }

    @Test
    void unquotedRuntimeKeyDoesNotBind() {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .withSources(new EnvConfigSource(Map.of(
                        "FLOCI_SERVICES_LAMBDA_RUNTIME_IMAGES_PYTHON3_12",
                        "registry.example/lambda-python:3.12"), 300))
                .withMapping(EmulatorConfig.class)
                .build();

        assertNull(config.getConfigMapping(EmulatorConfig.class)
                .services().lambda().runtimeImages().get("python3.12"));
    }
}
