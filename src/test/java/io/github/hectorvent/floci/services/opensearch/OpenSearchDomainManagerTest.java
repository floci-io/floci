package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.opensearch.model.Domain;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenSearchDomainManagerTest {

    @Test
    void startDomainLabelsContainerWithResourceIdentity() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.OpenSearchServiceConfig opensearch = mock(EmulatorConfig.OpenSearchServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.opensearch()).thenReturn(opensearch);
        when(services.dockerNetwork()).thenReturn(Optional.empty());
        when(opensearch.proxyBasePort()).thenReturn(9400);
        when(opensearch.proxyMaxPort()).thenReturn(9499);
        when(opensearch.defaultImage()).thenReturn(Optional.of("opensearchproject/opensearch:2.11.0"));
        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);
        when(config.docker()).thenReturn(docker);
        lenient().when(docker.logMaxSize()).thenReturn("10m");
        lenient().when(docker.logMaxFile()).thenReturn("3");
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        when(config.storage()).thenReturn(storage);
        when(storage.hostPersistentPath()).thenReturn("floci-data");
        when(storage.persistentPath()).thenReturn("/data");

        PortAllocator portAllocator = mock(PortAllocator.class);
        when(portAllocator.allocate(9400, 9499)).thenReturn(9400);

        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("container-id", Map.of(
                        9200, new ContainerLifecycleManager.EndpointInfo("172.17.0.5", 9200))));

        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(mock(ContainerSpec.class));

        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");

        OpenSearchDomainManager manager = new OpenSearchDomainManager(containerBuilder, lifecycleManager,
                mock(ContainerDetector.class), portAllocator, config, regionResolver);

        Domain domain = new Domain();
        domain.setDomainName("my-domain");
        domain.setEngineVersion("OpenSearch_2.11");

        manager.startDomain(domain);

        verify(builder).withLabels(Map.of(
                "io.floci", "aws",
                "io.floci.service", "opensearch",
                "io.floci.resource-id", "my-domain",
                "io.floci.account", "000000000000",
                "io.floci.region", "us-east-1"));
    }
}
