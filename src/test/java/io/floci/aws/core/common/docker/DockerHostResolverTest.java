package io.floci.aws.core.common.docker;

import io.floci.aws.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DockerHostResolverTest {

    @Test
    void resolve_usesDetectedContainerNetworkIpWhenRunningInContainer() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        CurrentContainerNetworkResolver currentContainerNetworkResolver =
                mock(CurrentContainerNetworkResolver.class);

        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.LambdaServiceConfig lambda = mock(EmulatorConfig.LambdaServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambda);
        when(lambda.dockerHostOverride()).thenReturn(Optional.empty());
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(currentContainerNetworkResolver.resolveContainerIp()).thenReturn(Optional.of("172.24.0.2"));

        DockerHostResolver resolver =
                new DockerHostResolver(config, containerDetector, currentContainerNetworkResolver);

        assertEquals("172.24.0.2", resolver.resolve());
    }
}
