package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void fallsBackToThisEmulatorsPrefixWhenUnsetOrBlank() {
        assertEquals("floci-aws", ContainerLauncher.resolveContainerNamePrefix(config(null)));
        assertEquals("floci-aws", ContainerLauncher.resolveContainerNamePrefix(config("   ")));
    }

    @Test
    void usesConfiguredPrefixWhenDockerSafe() {
        assertEquals("acme", ContainerLauncher.resolveContainerNamePrefix(config("acme")));
        assertEquals("acme_v1.0", ContainerLauncher.resolveContainerNamePrefix(config(" acme_v1.0 ")));
    }

    @Test
    void fallsBackToThisEmulatorsPrefixWhenNotDockerSafe() {
        // Docker names must start alphanumeric and allow only [A-Za-z0-9_.-] after that.
        assertEquals("floci-aws", ContainerLauncher.resolveContainerNamePrefix(config("-leading-dash")));
        assertEquals("floci-aws", ContainerLauncher.resolveContainerNamePrefix(config("has space")));
        assertEquals("floci-aws", ContainerLauncher.resolveContainerNamePrefix(config("has:colon")));
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
                ContainerLauncher.codeVolumeName(ContainerLauncher.DEFAULT_NAME_PREFIX, fn));
    }

    @Test
    void configCodeVolumeNameIsUnchangedWithoutNamespace() {
        LambdaFunction fn = fn();

        assertEquals(ContainerLauncher.codeVolumeName(fn),
                ContainerLauncher.codeVolumeName(config(null, null), fn));
        assertEquals("floci-aws-code-my-fn-abc123def456",
                ContainerLauncher.codeVolumeName(config(null, "  "), fn));
        assertEquals(ContainerLauncher.codeVolumeName("acme", fn),
                ContainerLauncher.codeVolumeName(config("acme", null), fn));
        assertEquals("floci-aws-code-", ContainerLauncher.codeVolumeNamePrefix(config(null, null)));
        assertEquals("acme-code-", ContainerLauncher.codeVolumeNamePrefix(config("acme", null)));
    }

    @Test
    void codeVolumeNameIncludesResourceNamespace() {
        LambdaFunction fn = fn();

        String name = ContainerLauncher.codeVolumeName(config(null, "ci1"), fn);

        assertEquals("floci-aws-ci1-code-my-fn-abc123def456-490320b73797", name);
        assertTrue(DOCKER_NAME.matcher(name).matches(),
                "must be a docker-volume-safe name, was: " + name);
        assertEquals("floci-aws-ci1-code-", ContainerLauncher.codeVolumeNamePrefix(config(null, "ci1")));
    }

    @Test
    void codeVolumeNameCombinesCustomPrefixAndNamespace() {
        LambdaFunction fn = fn();

        assertEquals("acme-ci1-code-my-fn-abc123def456-490320b73797",
                ContainerLauncher.codeVolumeName(config("acme", "ci1"), fn));
        assertEquals("acme-ci1-code-", ContainerLauncher.codeVolumeNamePrefix(config("acme", "ci1")));
    }

    @Test
    void codeVolumeNameDiffersAcrossNamespacesForSameCode() {
        LambdaFunction fn = fn();

        String a = ContainerLauncher.codeVolumeName(config(null, "ci1"), fn);
        String b = ContainerLauncher.codeVolumeName(config(null, "ci2"), fn);

        assertNotEquals(a, b, "two namespaced instances must not share a code volume");
        assertFalse(b.startsWith(ContainerLauncher.codeVolumeNamePrefix(config(null, "ci1"))),
                "one namespace's marker prefix must not match another namespace's volumes");
    }

    /**
     * Namespaces and function names may both contain dashes, so the prefix and suffix alone can
     * spell one name for two namespaces; the namespace hash keeps them apart.
     */
    @Test
    void codeVolumeNameKeepsDashedNamespacesAndFunctionNamesApart() {
        LambdaFunction fooCodeBar = fn();
        fooCodeBar.setFunctionName("foo-code-bar");
        LambdaFunction bar = fn();
        bar.setFunctionName("bar");

        String a = ContainerLauncher.codeVolumeName(config(null, "ci"), fooCodeBar);
        String b = ContainerLauncher.codeVolumeName(config(null, "ci-code-foo"), bar);

        assertNotEquals(a, b, "two namespaces must never spell the same code volume name");
    }

    private static LambdaFunction fn() {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("my-fn");
        fn.setCodeSha256("abc123def456");
        return fn;
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

    private static EmulatorConfig config(String prefix, String namespace) {
        EmulatorConfig config = config(prefix);
        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);
        when(config.docker()).thenReturn(docker);
        when(docker.resourceNamespace()).thenReturn(Optional.ofNullable(namespace));
        return config;
    }
}
