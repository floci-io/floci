package io.github.hectorvent.floci.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.smallrye.config.WithDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationDefaultsTest {

    @Test
    void productionConfigEnablesCloudTrailByDefault() throws IOException {
        try (InputStream configStream = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertNotNull(configStream, "application.yml should be available on the test classpath");
            JsonNode config = new YAMLMapper().readTree(configStream);

            assertTrue(config.path("floci")
                            .path("services")
                            .path("cloudtrail")
                            .path("enabled")
                            .asBoolean(false),
                    "application.yml should enable CloudTrail by default");
        }
    }

    @Test
    void productionConfigUsesExpectedRequestSizeLimit() throws IOException {
        JsonNode config = new YAMLMapper().readTree(Path.of("src/main/resources/application.yml").toFile());

        assertEquals(2048,
                config.path("floci").path("protocols").path("max-request-size").asInt(),
                "production application.yml should allow 2048 MB request bodies by default");
    }

    @Test
    void productionConfigUsesTheAwsSqsMaximumMessageSize() throws IOException, NoSuchMethodException {
        JsonNode config = new YAMLMapper().readTree(Path.of("src/main/resources/application.yml").toFile());

        assertEquals(1048576,
                config.path("floci").path("services").path("sqs").path("max-message-size").asInt(),
                "production application.yml should use the AWS SQS maximum of 1048576 bytes");

        WithDefault fallback = EmulatorConfig.SqsServiceConfig.class
                .getMethod("maxMessageSize")
                .getAnnotation(WithDefault.class);
        assertNotNull(fallback, "maxMessageSize should declare a fallback default");
        assertEquals("1048576", fallback.value(),
                "the EmulatorConfig fallback should match the AWS SQS maximum too");
    }

    @Test
    void productionConfigListensOnLoopbackWithoutNetworkExposureConsent() throws IOException, NoSuchMethodException {
        JsonNode config = new YAMLMapper().readTree(Path.of("src/main/resources/application.yml").toFile());

        assertEquals("127.0.0.1", config.path("quarkus").path("http").path("host").asText(),
                "production application.yml should listen on loopback by default");
        assertFalse(config.path("floci").path("security").path("allow-unsafe-network-exposure").asBoolean(true),
                "production application.yml should not allow a non-loopback listener by default");

        WithDefault fallback = EmulatorConfig.SecurityConfig.class
                .getMethod("allowUnsafeNetworkExposure")
                .getAnnotation(WithDefault.class);
        assertNotNull(fallback, "allowUnsafeNetworkExposure should declare a fallback default");
        assertEquals("false", fallback.value(), "the EmulatorConfig fallback should refuse exposure too");
    }

    @Test
    void productionConfigDoesNotSeedIamDeployerPrincipalByDefault() throws IOException {
        JsonNode config = new YAMLMapper().readTree(Path.of("src/main/resources/application.yml").toFile());

        assertFalse(config.path("floci")
                        .path("services")
                        .path("iam")
                        .path("seed-deployer-principal")
                        .asBoolean(true),
                "production application.yml should not create default admin credentials unless enabled");
    }
}
