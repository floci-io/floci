package io.github.hectorvent.floci.services.batch;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BatchDockerRunnerTest {

    @Test
    void resolveEndpointHostnameUsesHostGatewayWhenFlociRunsNatively() {
        ContainerDetector detector = mock(ContainerDetector.class);
        when(detector.isRunningInContainer()).thenReturn(false);

        assertEquals("host.docker.internal", runner(config(Optional.empty()), detector).resolveEndpointHostname());
    }

    @Test
    void resolveEndpointHostnameUsesConfiguredHostnameWhenFlociRunsInContainer() {
        ContainerDetector detector = mock(ContainerDetector.class);
        when(detector.isRunningInContainer()).thenReturn(true);

        assertEquals("floci.internal", runner(config(Optional.of("floci.internal")), detector).resolveEndpointHostname());
    }

    @Test
    void resolveEndpointHostnameUsesEmbeddedDnsSuffixWhenContainerizedWithoutHostname() {
        ContainerDetector detector = mock(ContainerDetector.class);
        when(detector.isRunningInContainer()).thenReturn(true);

        assertEquals(EmbeddedDnsServer.DEFAULT_SUFFIX, runner(config(Optional.empty()), detector).resolveEndpointHostname());
    }

    private BatchDockerRunner runner(EmulatorConfig config, ContainerDetector detector) {
        return new BatchDockerRunner(
                mock(ContainerBuilder.class),
                mock(ContainerLifecycleManager.class),
                mock(ContainerLogStreamer.class),
                config,
                detector);
    }

    private EmulatorConfig config(Optional<String> hostname) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.hostname()).thenReturn(hostname);
        return config;
    }
}
