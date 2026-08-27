package io.github.hectorvent.floci.services.amazonmq.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.services.amazonmq.model.Broker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RabbitMqManagerTest {

    private ContainerLifecycleManager lifecycleManager;
    private RabbitMqManager manager;

    @BeforeEach
    void setUp() {
        lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        manager = new RabbitMqManager(
                Mockito.mock(ContainerBuilder.class),
                lifecycleManager,
                Mockito.mock(ContainerLogStreamer.class),
                Mockito.mock(ContainerDetector.class),
                Mockito.mock(EmulatorConfig.class),
                Mockito.mock(RegionResolver.class));
    }

    private Broker broker(String brokerId) {
        return new Broker(brokerId, "arn", "name", "RABBITMQ", "3.13",
                "SINGLE_INSTANCE", "mq.t3.micro");
    }

    @Test
    void startContainerLabelsContainerWithResourceIdentity() {
        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.AmazonMqServiceConfig amazonmq = Mockito.mock(EmulatorConfig.AmazonMqServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.amazonmq()).thenReturn(amazonmq);
        when(services.dockerNetwork()).thenReturn(Optional.empty());
        when(amazonmq.defaultImage()).thenReturn("rabbitmq:3.13-management");
        EmulatorConfig.DockerConfig docker = Mockito.mock(EmulatorConfig.DockerConfig.class);
        when(config.docker()).thenReturn(docker);
        when(docker.logMaxSize()).thenReturn("10m");
        when(docker.logMaxFile()).thenReturn("3");
        EmulatorConfig.StorageConfig storage = Mockito.mock(EmulatorConfig.StorageConfig.class);
        when(config.storage()).thenReturn(storage);
        when(storage.hostPersistentPath()).thenReturn("floci-data");

        ContainerLifecycleManager lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("container-id", Map.of(
                        5672, new ContainerLifecycleManager.EndpointInfo("localhost", 5672),
                        15672, new ContainerLifecycleManager.EndpointInfo("localhost", 15672))));

        ContainerBuilder containerBuilder = Mockito.mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = Mockito.mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(Mockito.mock(ContainerSpec.class));

        RegionResolver regionResolver = Mockito.mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");

        RabbitMqManager manager = new RabbitMqManager(containerBuilder, lifecycleManager,
                Mockito.mock(ContainerLogStreamer.class), Mockito.mock(ContainerDetector.class),
                config, regionResolver);

        manager.startContainer(broker("b-1"));

        verify(builder).withLabels(Map.of(
                "io.floci", "aws",
                "io.floci.service", "amazonmq",
                "io.floci.resource-id", "b-1",
                "io.floci.account", "000000000000",
                "io.floci.region", "us-east-1"));
    }

    @Test
    void stopContainerUsesContainerIdWhenPresent() {
        Broker broker = broker("b-1");
        broker.setContainerId("container-abc");

        manager.stopContainer(broker);

        verify(lifecycleManager).stopAndRemove(Mockito.eq("container-abc"), Mockito.any());
        verifyNoMoreInteractions(lifecycleManager);
    }

    @Test
    void stopContainerFallsBackToDeterministicNameWhenIdMissing() {
        // After an emulator restart containerId is null (not persisted); teardown
        // must still remove the container by its deterministic name.
        Broker broker = broker("b-2");

        manager.stopContainer(broker);

        verify(lifecycleManager).removeIfExists("floci-amazonmq-b-2");
        verifyNoMoreInteractions(lifecycleManager);
    }
}
