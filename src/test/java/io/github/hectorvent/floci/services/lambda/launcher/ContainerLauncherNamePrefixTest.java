package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the configurable Lambda container/code-volume name prefix
 * ({@code floci.services.lambda.container-name-prefix}). Plain {@code mock()} rather than the
 * Mockito extension, for the same strict-stubbing reason as
 * {@link ContainerLauncherVolumeNamingTest}.
 */
class ContainerLauncherNamePrefixTest {

    private static final Pattern DOCKER_NAME = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_.-]*$");

    @Test
    void fallsBackToFlociWhenPrefixUnsetOrBlank() {
        assertEquals("floci", ContainerLauncher.resolveContainerNamePrefix(config(null)));
        assertEquals("floci", ContainerLauncher.resolveContainerNamePrefix(config("   ")));
    }

    @Test
    void usesConfiguredPrefixWhenDockerSafe() {
        assertEquals("acme", ContainerLauncher.resolveContainerNamePrefix(config("acme")));
        assertEquals("acme_v1.0", ContainerLauncher.resolveContainerNamePrefix(config(" acme_v1.0 ")));
    }

    @Test
    void fallsBackToFlociWhenPrefixIsNotDockerSafe() {
        // Docker names must start alphanumeric and allow only [A-Za-z0-9_.-] after that.
        assertEquals("floci", ContainerLauncher.resolveContainerNamePrefix(config("-leading-dash")));
        assertEquals("floci", ContainerLauncher.resolveContainerNamePrefix(config("has space")));
        assertEquals("floci", ContainerLauncher.resolveContainerNamePrefix(config("has:colon")));
    }

    @Test
    void codeVolumeNameHonorsConfiguredPrefix() {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("my-fn");
        fn.setCodeSha256("abc123def456");

        String name = ContainerLauncher.codeVolumeName("acme", fn);

        assertTrue(name.startsWith("acme-code-my-fn-"),
                "should be <prefix>-code-<functionName>-<hash> shaped, was: " + name);
        assertTrue(DOCKER_NAME.matcher(name).matches(),
                "must be a docker-volume-safe name, was: " + name);
        // The one-arg overload stays on the default prefix (used by pre-existing callers/tests).
        assertEquals(ContainerLauncher.codeVolumeName(fn),
                ContainerLauncher.codeVolumeName("floci", fn));
    }

    private static EmulatorConfig config(String prefix) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.LambdaServiceConfig lambda = mock(EmulatorConfig.LambdaServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambda);
        when(lambda.containerNamePrefix()).thenReturn(Optional.ofNullable(prefix));
        return config;
    }
}
