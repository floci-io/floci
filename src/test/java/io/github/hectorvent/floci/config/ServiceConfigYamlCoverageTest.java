package io.github.hectorvent.floci.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.smallrye.config.common.utils.StringUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the rule that {@code application.yml} — not an interface {@code @WithDefault} —
 * is the source of truth for which services run. A service reachable only through its
 * fallback default is invisible to anyone reading the config and cannot be toggled from
 * the file, which is how six services drifted out of both YAMLs before this test existed.
 */
class ServiceConfigYamlCoverageTest {

    private static final List<Path> CONFIGS = List.of(
            Path.of("src/main/resources/application.yml"),
            Path.of("src/test/resources/application.yml"));

    @Test
    void everyToggleableServiceDeclaresEnabledInBothConfigs() throws IOException {
        List<String> missing = new ArrayList<>();

        for (Path config : CONFIGS) {
            JsonNode services = new YAMLMapper().readTree(config.toFile())
                    .path("floci").path("services");

            for (Method accessor : EmulatorConfig.ServicesConfig.class.getDeclaredMethods()) {
                if (!declaresEnabledToggle(accessor.getReturnType())) {
                    continue;
                }
                String key = StringUtil.skewer(accessor.getName());
                if (!declaresEnabled(services.path(key))) {
                    missing.add(config + " -> floci.services." + key + ".enabled");
                }
            }
        }

        assertTrue(missing.isEmpty(),
                "Every service config with an enabled() toggle must declare it explicitly in both "
                        + "application.yml files. Note a bare 'service-name:' key with no child does "
                        + "not count, since it binds to nothing. Missing: " + missing);
    }

    @Test
    void explicitBooleanAndEnvironmentExpressionDeclareTheToggle() throws IOException {
        for (String value : List.of("true", "false", "${FLOCI_SERVICES_RESOURCE_GROUPS_ENABLED:true}")) {
            JsonNode service = new YAMLMapper().readTree("enabled: " + value);
            assertTrue(declaresEnabled(service), value);
        }
    }

    @Test
    void missingBareNullAndEmptyDeclarationsRemainMissing() throws IOException {
        for (String yaml : List.of("{}", "service:", "service: {}", "service: {enabled: null}",
                "service: {enabled: ''}", "service: {enabled: '   '}",
                "service: {enabled: {}}", "service: {enabled: []}")) {
            JsonNode service = new YAMLMapper().readTree(yaml).path("service");
            assertFalse(declaresEnabled(service), yaml);
        }
    }

    private static boolean declaresEnabled(JsonNode service) {
        JsonNode enabled = service.path("enabled");
        // Config binding validates Boolean values after resolving expressions. This guard only
        // checks that the YAML declares a value instead of relying on the interface fallback.
        return enabled.isValueNode() && !enabled.isNull()
                && (!enabled.isTextual() || !enabled.textValue().isBlank());
    }

    /**
     * Config records without an {@code enabled()} toggle (for example {@code DuckConfig},
     * which only carries an image and an optional URL) are not services that can be turned
     * off, so they are exempt.
     */
    private static boolean declaresEnabledToggle(Class<?> configInterface) {
        for (Method method : configInterface.getDeclaredMethods()) {
            if (method.getName().equals("enabled")
                    && method.getParameterCount() == 0
                    && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
                return true;
            }
        }
        return false;
    }
}
