package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the configurable code-volume populate concurrency
 * ({@code floci.services.lambda.code-volume-populate-concurrency}). Plain {@code mock()} rather
 * than the Mockito extension, for the same strict-stubbing reason as
 * {@link ContainerLauncherNamePrefixTest}.
 */
class ContainerLauncherPopulateConcurrencyTest {

    private static int derivedDefault() {
        return Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    }

    @Test
    void derivesFromCpuCountWhenUnset() {
        assertEquals(derivedDefault(), ContainerLauncher.resolvePopulateConcurrency(config(null)));
    }

    @Test
    void usesConfiguredValueWhenSet() {
        assertEquals(1, ContainerLauncher.resolvePopulateConcurrency(config(1)));
        assertEquals(16, ContainerLauncher.resolvePopulateConcurrency(config(16)));
    }

    @Test
    void configuredValueDecouplesTheCapFromTheCpuCount() {
        // The point of the option: a CPU-constrained container derives 2, which serializes
        // concurrent cold starts of distinct large functions into pairs. An explicit value must
        // win over the derivation whether it is above or below it.
        int derived = derivedDefault();
        assertEquals(derived + 4, ContainerLauncher.resolvePopulateConcurrency(config(derived + 4)));
        assertEquals(1, ContainerLauncher.resolvePopulateConcurrency(config(1)));
    }

    @Test
    void ignoresNonPositiveValues() {
        // Zero permits would deadlock every populate, and a negative Semaphore count would make
        // acquire() block until as many releases arrived. Fall back instead of hanging.
        assertEquals(derivedDefault(), ContainerLauncher.resolvePopulateConcurrency(config(0)));
        assertEquals(derivedDefault(), ContainerLauncher.resolvePopulateConcurrency(config(-1)));
    }

    private static EmulatorConfig config(Integer permits) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.LambdaServiceConfig lambda = mock(EmulatorConfig.LambdaServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambda);
        when(lambda.codeVolumePopulateConcurrency()).thenReturn(Optional.ofNullable(permits));
        return config;
    }
}
