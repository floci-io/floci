package io.github.hectorvent.floci.config;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static java.util.stream.StreamSupport.stream;
import static org.junit.jupiter.api.Assertions.*;

class FlociConfigRelocationsInterceptorTest {

    private static SmallRyeConfig config(Map<String, String> properties) {
        return new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withInterceptors(new FlociConfigRelocationsInterceptor())
                .withSources(new PropertiesConfigSource(properties, "test", 250))
                .withDefaultValue("floci.protocols.max-request-size", "2048")
                .withDefaultValue("floci.services.lambda.ecr-base-uri", "public.ecr.aws")
                .build();
    }

    @Test
    void legacyMaxRequestSizeKeyResolvesToTheRelocatedKey() {
        SmallRyeConfig config = config(Map.of("floci.max-request-size", "4096"));

        assertEquals(4096, config.getValue("floci.protocols.max-request-size", Integer.class));
    }

    @Test
    void legacyEcrBaseUriKeyResolvesToTheRelocatedKey() {
        SmallRyeConfig config = config(Map.of("floci.ecr-base-uri", "my.registry.example/mirror"));

        assertEquals("my.registry.example/mirror",
                config.getValue("floci.services.lambda.ecr-base-uri", String.class));
    }

    @Test
    void theRelocatedKeyWinsOverTheLegacyKeyFromTheSameSource() {
        SmallRyeConfig config = config(Map.of(
                "floci.max-request-size", "4096",
                "floci.protocols.max-request-size", "8192"));

        assertEquals(8192, config.getValue("floci.protocols.max-request-size", Integer.class));
    }

    @Test
    void theWithDefaultValueAppliesWhenNeitherKeyIsSet() {
        SmallRyeConfig config = config(Map.of());

        assertEquals(2048, config.getValue("floci.protocols.max-request-size", Integer.class));
        assertEquals("public.ecr.aws",
                config.getValue("floci.services.lambda.ecr-base-uri", String.class));
    }

    @Test
    void nameIterationPresentsOnlyTheRelocatedKey() {
        SmallRyeConfig config = config(Map.of("floci.max-request-size", "4096"));

        var names = stream(config.getPropertyNames().spliterator(), false).toList();
        assertTrue(names.contains("floci.protocols.max-request-size"));
        assertFalse(names.contains("floci.max-request-size"));
    }

    @Test
    void expressionExpansionSeesTheLegacyKeyWhenResolvingTheRelocatedKey() {
        SmallRyeConfig config = config(Map.of(
                "quarkus.http.limits.max-body-size", "${floci.protocols.max-request-size}M",
                "floci.max-request-size", "4096"));
        assertEquals("4096M", config.getValue("quarkus.http.limits.max-body-size", String.class));
    }
}
