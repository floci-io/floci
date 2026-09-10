package io.github.hectorvent.floci.services.amazonmq.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.amazonmq.model.Broker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RabbitMqManagerTest {

    private ContainerLifecycleManager lifecycleManager;
    private PortAllocator portAllocator;
    private RabbitMqManager manager;

    @BeforeEach
    void setUp() {
        lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        portAllocator = Mockito.mock(PortAllocator.class);
        manager = new RabbitMqManager(
                Mockito.mock(ContainerBuilder.class),
                lifecycleManager,
                Mockito.mock(ContainerLogStreamer.class),
                Mockito.mock(ContainerDetector.class),
                portAllocator,
                Mockito.mock(EmulatorConfig.class),
                Mockito.mock(RegionResolver.class));
    }

    private static EmulatorConfig configWithDefaultPortRanges() {
        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.AmazonMqServiceConfig amazonmq = Mockito.mock(EmulatorConfig.AmazonMqServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.amazonmq()).thenReturn(amazonmq);
        when(services.dockerNetwork()).thenReturn(Optional.empty());
        when(amazonmq.defaultImage()).thenReturn("rabbitmq:3.13-management");
        when(amazonmq.amqpHostPortBase()).thenReturn(5672);
        when(amazonmq.amqpHostPortMax()).thenReturn(5699);
        when(amazonmq.consoleHostPortBase()).thenReturn(15672);
        when(amazonmq.consoleHostPortMax()).thenReturn(15699);
        EmulatorConfig.DockerConfig docker = Mockito.mock(EmulatorConfig.DockerConfig.class);
        when(config.docker()).thenReturn(docker);
        when(docker.logMaxSize()).thenReturn("10m");
        when(docker.logMaxFile()).thenReturn("3");
        EmulatorConfig.StorageConfig storage = Mockito.mock(EmulatorConfig.StorageConfig.class);
        when(config.storage()).thenReturn(storage);
        when(storage.hostPersistentPath()).thenReturn("floci-data");
        return config;
    }

    private static ContainerBuilder.Builder selfReturningBuilder(ContainerBuilder containerBuilder) {
        ContainerBuilder.Builder builder = Mockito.mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(Mockito.mock(ContainerSpec.class));
        return builder;
    }

    private static RegionResolver regionResolver() {
        RegionResolver regionResolver = Mockito.mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");
        return regionResolver;
    }

    private Broker broker(String brokerId) {
        return new Broker(brokerId, "arn", "name", "RABBITMQ", "3.13",
                "SINGLE_INSTANCE", "mq.t3.micro");
    }

    @Test
    void startContainerLabelsContainerWithResourceIdentity() {
        EmulatorConfig config = configWithDefaultPortRanges();

        ContainerLifecycleManager lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("container-id", Map.of(
                        5672, new ContainerLifecycleManager.EndpointInfo("localhost", 5672),
                        15672, new ContainerLifecycleManager.EndpointInfo("localhost", 15672))));

        ContainerBuilder containerBuilder = Mockito.mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = selfReturningBuilder(containerBuilder);

        PortAllocator portAllocator = Mockito.mock(PortAllocator.class);
        when(portAllocator.allocate(5672, 5699)).thenReturn(5672);
        when(portAllocator.allocate(15672, 15699)).thenReturn(15672);

        RabbitMqManager manager = new RabbitMqManager(containerBuilder, lifecycleManager,
                Mockito.mock(ContainerLogStreamer.class), Mockito.mock(ContainerDetector.class),
                portAllocator, config, regionResolver());

        manager.startContainer(broker("b-1"));

        verify(builder).withLabels(Map.of(
                "io.floci", "aws",
                "io.floci.service", "amazonmq",
                "io.floci.resource-id", "b-1",
                "io.floci.account", "000000000000",
                "io.floci.region", "us-east-1"));
    }

    /**
     * Regression for #3240: with Floci itself running in Docker, the AMQP and console
     * ports used to be expose-only, so a client on the host (e.g. a local app dialing
     * amqp://localhost:5672) could never reach the broker. No Floci-internal proxy
     * fronts RabbitMQ, so the host-port binding must be published in that topology
     * too, from the configured ranges, while DescribeBroker keeps reporting the
     * container-network address sibling containers use.
     */
    @Test
    void startContainerPublishesHostPortsWhenRunningInContainer() {
        EmulatorConfig config = configWithDefaultPortRanges();

        ContainerLifecycleManager lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("container-id", Map.of(
                        5672, new ContainerLifecycleManager.EndpointInfo("172.17.0.5", 5672),
                        15672, new ContainerLifecycleManager.EndpointInfo("172.17.0.5", 15672))));

        ContainerBuilder containerBuilder = Mockito.mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = selfReturningBuilder(containerBuilder);

        PortAllocator portAllocator = Mockito.mock(PortAllocator.class);
        when(portAllocator.allocate(5672, 5699)).thenReturn(5673);
        when(portAllocator.allocate(15672, 15699)).thenReturn(15673);

        ContainerDetector containerDetector = Mockito.mock(ContainerDetector.class);
        when(containerDetector.isRunningInContainer()).thenReturn(true);

        RabbitMqManager manager = new RabbitMqManager(containerBuilder, lifecycleManager,
                Mockito.mock(ContainerLogStreamer.class), containerDetector,
                portAllocator, config, regionResolver());

        Broker broker = broker("b-1");
        manager.startContainer(broker);

        verify(builder).withPortBinding(5672, 5673);
        verify(builder).withPortBinding(15672, 15673);
        verify(builder, never()).withExposedPort(Mockito.anyInt());
        verify(builder, never()).withDynamicPort(Mockito.anyInt());
        assertEquals(java.util.List.of("amqp://172.17.0.5:5672"),
                broker.getBrokerInstances().get(0).getEndpoints());
        assertEquals("http://172.17.0.5:15672", broker.getBrokerInstances().get(0).getConsoleURL());
    }

    /**
     * The reservation must die with the container: without the release, repeated
     * create/delete cycles exhaust the configured ranges and later brokers can
     * never start.
     */
    @Test
    void stopContainerReleasesReservedHostPorts() {
        EmulatorConfig config = configWithDefaultPortRanges();

        ContainerLifecycleManager lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("container-id", Map.of(
                        5672, new ContainerLifecycleManager.EndpointInfo("localhost", 5674),
                        15672, new ContainerLifecycleManager.EndpointInfo("localhost", 15674))));

        ContainerBuilder containerBuilder = Mockito.mock(ContainerBuilder.class);
        selfReturningBuilder(containerBuilder);

        PortAllocator portAllocator = Mockito.mock(PortAllocator.class);
        when(portAllocator.allocate(5672, 5699)).thenReturn(5674);
        when(portAllocator.allocate(15672, 15699)).thenReturn(15674);

        RabbitMqManager manager = new RabbitMqManager(containerBuilder, lifecycleManager,
                Mockito.mock(ContainerLogStreamer.class), Mockito.mock(ContainerDetector.class),
                portAllocator, config, regionResolver());

        Broker broker = broker("b-1");
        manager.startContainer(broker);
        verify(portAllocator, never()).release(Mockito.anyInt());

        manager.stopContainer(broker);

        verify(portAllocator).release(5674);
        verify(portAllocator).release(15674);
    }

    @Test
    void startContainerReleasesReservedHostPortsWhenContainerFailsToStart() {
        EmulatorConfig config = configWithDefaultPortRanges();

        ContainerLifecycleManager lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenThrow(new RuntimeException("docker down"));

        ContainerBuilder containerBuilder = Mockito.mock(ContainerBuilder.class);
        selfReturningBuilder(containerBuilder);

        PortAllocator portAllocator = Mockito.mock(PortAllocator.class);
        when(portAllocator.allocate(5672, 5699)).thenReturn(5672);
        when(portAllocator.allocate(15672, 15699)).thenReturn(15672);

        RabbitMqManager manager = new RabbitMqManager(containerBuilder, lifecycleManager,
                Mockito.mock(ContainerLogStreamer.class), Mockito.mock(ContainerDetector.class),
                portAllocator, config, regionResolver());

        assertThrows(RuntimeException.class, () -> manager.startContainer(broker("b-1")));

        // Once for the stale-container sweep before create, once for the rollback.
        verify(lifecycleManager, Mockito.times(2)).removeIfExists("floci-amazonmq-b-1");
        verify(portAllocator).release(5672);
        verify(portAllocator).release(15672);
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
