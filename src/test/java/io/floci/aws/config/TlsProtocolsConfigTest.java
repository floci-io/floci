package io.floci.aws.config;

import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.source.yaml.YamlConfigSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TlsProtocolsConfigTest {

    @Test
    void defaultsToTls13AndTls12Only() throws IOException {
        assertEquals(List.of("TLSv1.3", "TLSv1.2"), protocols(Map.of(), Map.of()));
    }

    @Test
    void preservesTheLegacyHttpProtocolRestriction() throws IOException {
        assertEquals(List.of("TLSv1.3"),
                protocols(Map.of("quarkus.http.ssl.protocols", "TLSv1.3"), Map.of()));
    }

    @Test
    void preservesTheLegacyHttpEnvironmentRestriction() throws IOException {
        assertEquals(List.of("TLSv1.3"),
                protocols(Map.of(), Map.of("QUARKUS_HTTP_SSL_PROTOCOLS", "TLSv1.3")));
    }

    @Test
    void preservesTheRegistryEnvironmentRestriction() throws IOException {
        assertEquals(List.of("TLSv1.3"),
                protocols(Map.of(), Map.of("QUARKUS_TLS_PROTOCOLS", "TLSv1.3")));
    }

    @Test
    void explicitRegistryProtocolsTakePrecedenceOverTheLegacySetting() throws IOException {
        assertEquals(List.of("TLSv1.3"), protocols(Map.of(
                "quarkus.tls.protocols", "TLSv1.3",
                "quarkus.http.ssl.protocols", "TLSv1.3,TLSv1.2"), Map.of()));
    }

    @Test
    void anActiveProfileCanRestrictTheRegistryProtocols() throws IOException {
        assertEquals(List.of("TLSv1.3"),
                protocols(Map.of("%strict.quarkus.tls.protocols", "TLSv1.3"), Map.of()));
    }

    private static List<String> protocols(Map<String, String> overrides, Map<String, String> environment)
            throws IOException {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withProfile("strict")
                .withSources(new YamlConfigSource(Path.of("src/main/resources/application.yml").toUri().toURL()))
                .withSources(new EnvConfigSource(environment, 300))
                .withSources(new PropertiesConfigSource(overrides, "test-overrides", 400))
                .build();
        return config.getOptionalValues("quarkus.tls.protocols", String.class).orElse(List.of());
    }
}
