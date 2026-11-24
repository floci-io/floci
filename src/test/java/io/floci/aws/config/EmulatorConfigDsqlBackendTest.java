package io.floci.aws.config;

import io.floci.aws.testing.ParallelLocks;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.source.yaml.YamlConfigSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock(ParallelLocks.TLS_CONFIG)
@ResourceLock(ParallelLocks.CONFIG_MAPPING)
class EmulatorConfigDsqlBackendTest {

    @Test
    void theDsqlBackendDefaultsToDocker() throws IOException {
        assertEquals(EmulatorConfig.DsqlServiceConfig.Backend.DOCKER, backend(Map.of()));
    }

    @Test
    void theDsqlBackendAcceptsLowerAndUpperCaseNames() throws IOException {
        assertEquals(EmulatorConfig.DsqlServiceConfig.Backend.EXTERNAL,
                backend(Map.of("floci.services.dsql.backend", "external")));
        assertEquals(EmulatorConfig.DsqlServiceConfig.Backend.EXTERNAL,
                backend(Map.of("floci.services.dsql.backend", "EXTERNAL")));
        assertEquals(EmulatorConfig.DsqlServiceConfig.Backend.DOCKER,
                backend(Map.of("floci.services.dsql.backend", "docker")));
    }

    @Test
    void anUnknownDsqlBackendFailsNamingTheSettingAndTheValue() {
        RuntimeException refused = assertThrows(RuntimeException.class,
                () -> backend(Map.of("floci.services.dsql.backend", "kubernetes")));

        String message = describe(refused);
        assertTrue(message.contains("floci.services.dsql.backend"), message);
        assertTrue(message.contains("kubernetes"), message);
        assertTrue(message.contains("allowed values: external,docker"), message);
    }

    private static EmulatorConfig.DsqlServiceConfig.Backend backend(Map<String, String> overrides)
            throws IOException {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withMapping(EmulatorConfig.class)
                .withSources(new YamlConfigSource(Path.of("src/main/resources/application.yml").toUri().toURL()))
                .withSources(new PropertiesConfigSource(overrides, "test-overrides", 400))
                .build();
        return config.getConfigMapping(EmulatorConfig.class).services().dsql().backend();
    }

    private static String describe(Throwable failure) {
        StringBuilder message = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            message.append(cause.getMessage()).append('\n');
        }
        return message.toString();
    }
}
