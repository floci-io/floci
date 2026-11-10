package io.floci.aws.services.codeartifact;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.testing.DockerArchiveUploadFixture;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerdaccioConfigRetryTest {

    private final DockerClient docker = mock(DockerClient.class);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final DockerArchiveUploadFixture uploads = new DockerArchiveUploadFixture(docker, "container");
    private VerdaccioSidecarManager manager;

    @BeforeEach
    void setUp() {
        manager = new VerdaccioSidecarManager(mock(ContainerBuilder.class), lifecycle, mock(EmulatorConfig.class));
        when(lifecycle.getDockerClient()).thenReturn(docker);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 73, 8192})
    void transientFailureRebuildsTheCompleteConfigArchive(int consumed) throws Exception {
        uploads.failures = 1;
        uploads.consumedBytes = consumed;
        uploads.failure = new RuntimeException(new SocketException("Connection reset"));

        manager.copyConfig("container", 0);

        assertEquals(2, uploads.commands.size());
        assertNotSame(uploads.streams.get(0), uploads.streams.get(1));
        for (CopyArchiveToContainerCmd command : uploads.commands) {
            verify(command).withRemotePath("/verdaccio/conf");
        }
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new ByteArrayInputStream(uploads.completed.getFirst()))) {
            TarArchiveEntry entry = tar.getNextEntry();
            assertEquals("config.yaml", entry.getName());
            assertEquals(0644, entry.getMode());
            String yaml = new String(tar.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(yaml.contains("uplinks: {}"));
            assertTrue(yaml.contains("publish: $all"));
            assertTrue(yaml.contains("listen: 0.0.0.0:4873"));
            assertNull(tar.getNextEntry());
        }
    }

    @Test
    void exhaustedTransportFailurePropagatesAfterTheRetryBudget() {
        uploads.failures = 10;
        uploads.failure = new RuntimeException(new SocketException("Connection reset"));
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> manager.copyConfig("container", 0));
        assertEquals(6, uploads.commands.size());
        assertSame(uploads.failure, thrown.getCause());
    }

    @Test
    void daemonRejectionDoesNotRetry() {
        uploads.failures = 10;
        uploads.failure = new NotFoundException("container missing");
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> manager.copyConfig("container", 0));
        assertEquals(1, uploads.commands.size());
        assertTrue(thrown.getMessage().contains("container missing"));
    }

    @Test
    void failedConfigurationUploadRemovesTheUnstartedContainer() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().codeartifact().npmImage()).thenReturn("verdaccio/verdaccio:test");
        when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.docker().logMaxSize()).thenReturn("10m");
        when(config.docker().logMaxFile()).thenReturn("3");
        ContainerBuilder builder = new ContainerBuilder(config,
                mock(DockerHostResolver.class), mock(EmbeddedDnsServer.class));
        manager = new VerdaccioSidecarManager(builder, lifecycle, config);
        when(lifecycle.create(any(ContainerSpec.class))).thenReturn("container");
        uploads.failures = 1;
        uploads.failure = new NotFoundException("container missing");

        assertThrows(RuntimeException.class, () -> manager.ensureReady("npm-repo", "http://localhost:4566"));

        verify(lifecycle).stopAndRemove("container", null);
        verify(lifecycle, never()).startCreated(any(), any());
        assertEquals(1, uploads.commands.size());
    }
}
