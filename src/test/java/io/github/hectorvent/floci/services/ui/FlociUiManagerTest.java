package io.github.hectorvent.floci.services.ui;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.CurrentContainerNetworkResolver;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlociUiManagerTest {

    private final ContainerDetector containerDetector = mock(ContainerDetector.class);
    private final DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
    private final EmulatorConfig config = mock(EmulatorConfig.class);
    private final EmulatorConfig.TlsConfig tls = mock(EmulatorConfig.TlsConfig.class);

    private FlociUiManager newManager() {
        return new FlociUiManager(
                mock(ContainerBuilder.class),
                mock(ContainerLifecycleManager.class),
                mock(ContainerLogStreamer.class),
                containerDetector,
                mock(CurrentContainerNetworkResolver.class),
                dockerHostResolver,
                config,
                mock(RegionResolver.class));
    }

    @Test
    void containerizedUsesResolvedContainerIpNotHostDockerInternal() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(config.hostname()).thenReturn(Optional.empty());
        when(config.port()).thenReturn(4566);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(false);
        when(dockerHostResolver.resolve()).thenReturn("172.24.0.2");

        assertEquals("http://172.24.0.2:4566", newManager().resolveFlociEndpoint());
    }

    @Test
    void explicitHostnameWinsWhenContainerized() {
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(config.hostname()).thenReturn(Optional.of("floci"));
        when(config.effectiveBaseUrl()).thenReturn("http://floci:4566");

        assertEquals("http://floci:4566", newManager().resolveFlociEndpoint());
    }

    @Test
    void onHostFallsBackToHostDockerInternal() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(config.port()).thenReturn(4566);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(false);
        when(dockerHostResolver.resolve()).thenReturn("host.docker.internal");

        assertEquals("http://host.docker.internal:4566", newManager().resolveFlociEndpoint());
    }

    @Test
    void usesHttpsSchemeWhenTlsEnabled() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(config.port()).thenReturn(4566);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(true);
        when(dockerHostResolver.resolve()).thenReturn("host.docker.internal");

        assertEquals("https://host.docker.internal:4566", newManager().resolveFlociEndpoint());
    }
}
