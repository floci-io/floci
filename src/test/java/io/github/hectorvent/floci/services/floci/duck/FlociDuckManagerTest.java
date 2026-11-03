package io.github.hectorvent.floci.services.floci.duck;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FlociDuckManagerTest {

    @Test
    void configuredUrlDoesNotStartAContainer() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().duck().url()).thenReturn(Optional.of("http://duck.example.test"));
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        FlociDuckManager manager = new FlociDuckManager(
                builder, lifecycle, mock(ContainerDetector.class), config);

        assertEquals("http://duck.example.test", manager.ensureReady());
        verifyNoInteractions(builder, lifecycle);
    }

    @Test
    void containerUsesInitSoSigtermReachesTheServer() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().duck().url()).thenReturn(Optional.empty());
        when(config.services().duck().defaultImage()).thenReturn("duck-test-image");
        when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.docker().logMaxSize()).thenReturn("10m");
        when(config.docker().logMaxFile()).thenReturn("3");
        ContainerBuilder builder = new ContainerBuilder(
                config, mock(DockerHostResolver.class), mock(EmbeddedDnsServer.class));
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        IllegalStateException unavailable = new IllegalStateException("no Docker in this unit test");
        when(lifecycle.createAndStart(any())).thenThrow(unavailable);
        FlociDuckManager manager = new FlociDuckManager(
                builder, lifecycle, mock(ContainerDetector.class), config);

        assertSame(unavailable, assertThrows(IllegalStateException.class, manager::ensureReady));

        ArgumentCaptor<ContainerSpec> spec = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycle).createAndStart(spec.capture());
        assertTrue(spec.getValue().init());
    }
}
