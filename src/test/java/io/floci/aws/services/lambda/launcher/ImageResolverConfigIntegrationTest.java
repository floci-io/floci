package io.floci.aws.services.lambda.launcher;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.testing.IndependentServiceSettingsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.floci.aws.testing.IndependentServiceSettingsProfile.LAMBDA_PYTHON_312_IMAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A runtime image set under a quoted YAML key, {@code "python3.12"}, is the image the resolver
 * returns for that runtime, and the other runtimes keep their defaults.
 *
 * <p>The image, {@link IndependentServiceSettingsProfile#LAMBDA_PYTHON_312_IMAGE}, comes from
 * {@link IndependentServiceSettingsProfile}, which this class shares with other classes that each
 * call a different service.
 */
@QuarkusTest
@TestProfile(IndependentServiceSettingsProfile.class)
class ImageResolverConfigIntegrationTest {

    @Inject
    EmulatorConfig config;

    @Inject
    ImageResolver resolver;

    @Test
    void quotedRuntimeKeySelectsExactImageReference() {
        assertEquals(LAMBDA_PYTHON_312_IMAGE,
                config.services().lambda().runtimeImages().get("python3.12"));
        assertEquals(LAMBDA_PYTHON_312_IMAGE, resolver.resolve("python3.12"));
        assertEquals("public.ecr.aws/lambda/python:3.11", resolver.resolve("python3.11"));
    }
}
