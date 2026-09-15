package io.github.hectorvent.floci.core.common.docker;

import java.util.Map;
import java.util.Optional;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerConfig;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An image's labels are advisory metadata: the web console reads them to learn the console's shape.
 * A failure to read them must therefore never break the start they were being read for.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContainerLifecycleManagerImageLabelsTest {

    @Mock
    DockerClient dockerClient;

    @Mock
    ImageCacheService imageCacheService;

    @Mock
    ContainerDetector containerDetector;

    @Mock
    PortAllocator portAllocator;

    @Mock
    EmulatorConfig config;

    private ContainerLifecycleManager manager() {
        return new ContainerLifecycleManager(dockerClient, imageCacheService, containerDetector,
                portAllocator, config);
    }

    private void imageInspectReturns(Map<String, String> labels) {
        ContainerConfig containerConfig = new ContainerConfig().withLabels(labels);
        InspectImageResponse response = mock(InspectImageResponse.class);
        when(response.getConfig()).thenReturn(containerConfig);
        InspectImageCmd cmd = mock(InspectImageCmd.class);
        when(cmd.exec()).thenReturn(response);
        when(dockerClient.inspectImageCmd("acme/console:1")).thenReturn(cmd);
    }

    @Test
    void labelsAreReadFromTheImageThePullResolvedTo() {
        when(imageCacheService.ensureImageExists("acme/console")).thenReturn("acme/console:1");
        imageInspectReturns(Map.of("io.floci.console.contract", "1"));

        assertEquals(Optional.of(Map.of("io.floci.console.contract", "1")),
                manager().imageLabels("acme/console"));
    }

    @Test
    void anImageWithNoLabelsReadsAsEmptyRatherThanAsUnreadable() {
        // "This image declares nothing" and "I could not ask" are different answers: only the
        // second should send the caller looking for another source.
        when(imageCacheService.ensureImageExists("acme/console")).thenReturn("acme/console:1");
        imageInspectReturns(null);

        assertEquals(Optional.of(Map.of()), manager().imageLabels("acme/console"));
    }

    @Test
    void anUnavailableImageReportsUnreadableLabelsRatherThanThrowing() {
        when(imageCacheService.ensureImageExists("acme/console"))
                .thenThrow(new NotFoundException("no such image"));

        assertTrue(manager().imageLabels("acme/console").isEmpty(),
                "a failed pull must leave the caller to its own defaults, not fail the start");
    }

    @Test
    void anUnreachableRuntimeReportsUnreadableLabelsRatherThanThrowing() {
        when(imageCacheService.ensureImageExists("acme/console")).thenReturn("acme/console:1");
        when(dockerClient.inspectImageCmd("acme/console:1"))
                .thenThrow(new RuntimeException("Cannot connect to the Docker daemon"));

        assertTrue(manager().imageLabels("acme/console").isEmpty());
    }
}
