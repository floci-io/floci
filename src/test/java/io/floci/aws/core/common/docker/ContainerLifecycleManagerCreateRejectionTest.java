package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.exception.BadRequestException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A daemon that refuses a create with a client error, such as an authorization plugin denying
 * {@code HostConfig.Mounts}, refuses every retry of it too. The create reports that as a
 * {@link ContainerRejectedException} so services can answer with a non-retryable error.
 */
@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerCreateRejectionTest {

    @Mock
    DockerClient dockerClient;

    @Mock
    ImageCacheService imageCacheService;

    @Mock
    ContainerDetector containerDetector;

    @Mock
    EmulatorConfig config;

    @Mock
    EmulatorConfig.DockerConfig dockerConfig;

    @Mock
    EmulatorConfig.TlsConfig tlsConfig;

    @BeforeEach
    void setUp() {
        lenient().when(config.docker()).thenReturn(dockerConfig);
        lenient().when(config.tls()).thenReturn(tlsConfig);
        lenient().when(imageCacheService.ensureImageExists("busybox:stable")).thenReturn("busybox:stable");
        lenient().when(dockerConfig.resourceNamespace()).thenReturn(Optional.empty());
    }

    @Test
    void createReportsAnAuthorizationPluginDenialAsARejection() {
        DockerException denied = new DockerException(
                "{\"message\":\"authorization denied by plugin: --mounts is not allowed\"}", 403);
        failCreateWith(denied);

        ContainerRejectedException rejected = assertThrows(ContainerRejectedException.class,
                () -> manager().create(spec()));

        assertSame(denied, rejected.getCause());
        assertTrue(rejected.getMessage().contains("--mounts is not allowed"), rejected.getMessage());
    }

    @Test
    void createReportsABadRequestAsARejection() {
        failCreateWith(new BadRequestException("invalid mount config"));

        assertThrows(ContainerRejectedException.class, () -> manager().create(spec()));
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429})
    void createKeepsATimeoutOrThrottlingRetryable(int status) {
        DockerException temporary = new DockerException("try again later", status);
        failCreateWith(temporary);

        assertSame(temporary, assertThrows(DockerException.class, () -> manager().create(spec())));
    }

    @Test
    void createKeepsADaemonErrorRetryable() {
        InternalServerErrorException daemonError = new InternalServerErrorException("daemon failure");
        failCreateWith(daemonError);

        assertSame(daemonError, assertThrows(InternalServerErrorException.class,
                () -> manager().create(spec())));
    }

    private void failCreateWith(DockerException failure) {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        when(createCmd.exec()).thenThrow(failure);
    }

    private static ContainerSpec spec() {
        return new ContainerSpec(
                "busybox:stable", "emulator-fixed-name", List.of(), null, null, null, Map.of(),
                List.of(), null, List.of(), List.of(), List.of(), Map.of(), null, false, null,
                List.of(), null, null, List.of());
    }

    private ContainerLifecycleManager manager() {
        return new ContainerLifecycleManager(
                dockerClient, dockerClient, imageCacheService, containerDetector, config);
    }
}
