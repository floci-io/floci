package io.github.hectorvent.floci.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
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
                config.path("floci").path("max-request-size").asInt(),
                "production application.yml should allow 2048 MB request bodies by default");
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
