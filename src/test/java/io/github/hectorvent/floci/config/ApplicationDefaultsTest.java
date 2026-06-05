package io.github.hectorvent.floci.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationDefaultsTest {

    @Test
    void productionConfigEnablesCloudTrailByDefault() throws IOException {
        JsonNode config = new YAMLMapper().readTree(Path.of("src/main/resources/application.yml").toFile());

        assertTrue(config.path("floci")
                        .path("services")
                        .path("cloudtrail")
                        .path("enabled")
                        .asBoolean(false),
                "production application.yml should enable CloudTrail by default");
    }
}
