package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.config.source.yaml.YamlConfigSource;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(ImageResolverConfigIntegrationTest.RuntimeImageProfile.class)
class ImageResolverConfigIntegrationTest {

    private static final String DIGEST_IMAGE = "public.ecr.aws/lambda/python:3.12@sha256:" + "a".repeat(64);

    @Inject
    EmulatorConfig config;

    @Inject
    ImageResolver resolver;

    @Test
    void quotedRuntimeKeySelectsExactImageReference() {
        assertEquals(DIGEST_IMAGE, config.services().lambda().runtimeImages().get("python3.12"));
        assertEquals(DIGEST_IMAGE, resolver.resolve("python3.12"));
        assertEquals("public.ecr.aws/lambda/python:3.11", resolver.resolve("python3.11"));
    }

    public static class RuntimeImageProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            YamlConfigSource source = new YamlConfigSource("runtime-images", """
                    floci:
                      services:
                        lambda:
                          runtime-images:
                            "python3.12": "%s"
                    """.formatted(DIGEST_IMAGE));
            return source.getProperties();
        }
    }
}
