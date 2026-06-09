package io.github.hectorvent.floci.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

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
}
