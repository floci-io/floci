package io.floci.aws.services.amazonmq.container;

import com.github.dockerjava.api.model.Container;
import com.sun.net.httpserver.HttpServer;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.services.amazonmq.model.Broker;
import io.floci.aws.services.amazonmq.model.BrokerInstance;
import io.floci.aws.services.amazonmq.model.MqUser;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ResourceLock(ParallelLocks.PORTS)
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
        when(builder.build()).thenReturn(new ContainerSpec("test-image"));
        return builder;
    }

    private static RegionResolver regionResolver() {
        return new RegionResolver("us-east-1", "000000000000");
    }

    private Broker broker(String brokerId) {
        return new Broker(brokerId, "arn", "name", "RABBITMQ", "3.13",
                "SINGLE_INSTANCE", "mq.t3.micro");
    }

    /** A manager over {@code portAllocator} whose containers answer on the given host ports. */
    private static RabbitMqManager startingManager(ContainerBuilder containerBuilder, PortAllocator portAllocator,
                                                   int amqpPort, int consolePort) {
        ContainerLifecycleManager lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("container-id", Map.of(
                        5672, new ContainerLifecycleManager.EndpointInfo("localhost", amqpPort),
                        15672, new ContainerLifecycleManager.EndpointInfo("localhost", consolePort))));
        return new RabbitMqManager(containerBuilder, lifecycleManager, Mockito.mock(ContainerLogStreamer.class),
                Mockito.mock(ContainerDetector.class), portAllocator, configWithDefaultPortRanges(), regionResolver());
    }

    /**
     * #5664: every broker container runs as one fixed node name, which names its data directory, so a later
     * container on the volume finds the same node; the broker records it and the host ports it was published on.
     */
    @Test
    void aBrokerContainerRunsAsAFixedNodeName() {
        ContainerBuilder containerBuilder = Mockito.mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = selfReturningBuilder(containerBuilder);
        PortAllocator portAllocator = Mockito.mock(PortAllocator.class);
        when(portAllocator.allocate(5672, 5699)).thenReturn(5680);
        when(portAllocator.allocate(15672, 15699)).thenReturn(15680);
        Broker broker = broker("b-1");
        broker.setUsers(List.of(new MqUser("admin", "AdminPass123", true, null)));

        startingManager(containerBuilder, portAllocator, 5680, 15680).startContainer(broker);

        verify(builder).withEnv("RABBITMQ_NODENAME", "rabbit@localhost");
        verify(builder).withEnv("RABBITMQ_DEFAULT_USER", "admin");
        assertEquals("rabbit@localhost", broker.getNodeName());
        assertEquals(5680, broker.getAmqpHostPort());
        assertEquals(15680, broker.getConsoleHostPort());
    }

    /**
     * A restored broker's node already holds its users, and its admin password was never stored: its container is
     * started without seeding anyone, on the host ports it had.
     */
    @Test
    void aRestoredBrokerSeedsNoUserAndGetsItsHostPortsBack() {
        ContainerBuilder containerBuilder = Mockito.mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = selfReturningBuilder(containerBuilder);
        PortAllocator portAllocator = Mockito.mock(PortAllocator.class);
        when(portAllocator.allocateIfFree(5680)).thenReturn(OptionalInt.of(5680));
        when(portAllocator.allocateIfFree(15680)).thenReturn(OptionalInt.of(15680));
        Broker broker = restoredBroker(5680, 15680);

        startingManager(containerBuilder, portAllocator, 5680, 15680).restoreContainer(broker);

        verify(builder).withEnv("RABBITMQ_NODENAME", "rabbit@localhost");
        verify(builder, never()).withEnv(eq("RABBITMQ_DEFAULT_USER"), anyString());
        verify(builder, never()).withEnv(eq("RABBITMQ_DEFAULT_PASS"), anyString());
        verify(builder).withPortBinding(5672, 5680);
        verify(builder).withPortBinding(15672, 15680);
        verify(portAllocator, never()).allocate(Mockito.anyInt(), Mockito.anyInt());
        assertEquals("amqp://localhost:5680", broker.getBrokerInstances().get(0).getEndpoints().get(0));
    }

    /** A host port taken meanwhile, or outside today's range, is replaced by a new one. */
    @Test
    void aRestoredBrokerGetsNewHostPortsForOnesTakenOrOutOfRange() {
        ContainerBuilder containerBuilder = Mockito.mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = selfReturningBuilder(containerBuilder);
        PortAllocator portAllocator = Mockito.mock(PortAllocator.class);
        when(portAllocator.allocateIfFree(5680)).thenReturn(OptionalInt.empty());
        when(portAllocator.allocate(5672, 5699)).thenReturn(5681);
        when(portAllocator.allocate(15672, 15699)).thenReturn(15681);
        Broker broker = restoredBroker(5680, 25000);

        startingManager(containerBuilder, portAllocator, 5681, 15681).restoreContainer(broker);

        verify(builder).withPortBinding(5672, 5681);
        verify(builder).withPortBinding(15672, 15681);
        verify(portAllocator, never()).allocateIfFree(25000);
        assertEquals(5681, broker.getAmqpHostPort());
        assertEquals(15681, broker.getConsoleHostPort());
    }

    /**
     * A restore runs outside any request, where the resolver answers the default account: the container's label and
     * the log group take the account the broker belongs to.
     */
    @Test
    void aRestoredBrokerOfAnotherAccountIsLabelledAndLoggedInIt() {
        ContainerBuilder containerBuilder = Mockito.mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = selfReturningBuilder(containerBuilder);
        PortAllocator portAllocator = Mockito.mock(PortAllocator.class);
        when(portAllocator.allocateIfFree(Mockito.anyInt())).thenAnswer(free -> OptionalInt.of(free.getArgument(0)));
        ContainerLifecycleManager lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                "container-id", Map.of(5672, new ContainerLifecycleManager.EndpointInfo("localhost", 5680),
                        15672, new ContainerLifecycleManager.EndpointInfo("localhost", 15680))));
        ContainerLogStreamer logStreamer = Mockito.mock(ContainerLogStreamer.class);
        Broker broker = restoredBroker(5680, 15680);
        broker.setAccountId("111111111111");
        broker.setBrokerArn("arn:aws:mq:eu-west-1:111111111111:broker:name:b-1");

        new RabbitMqManager(containerBuilder, lifecycleManager, logStreamer, Mockito.mock(ContainerDetector.class),
                portAllocator, configWithDefaultPortRanges(), regionResolver()).restoreContainer(broker);

        verify(builder).withLabels(Map.of("io.floci", "aws", "io.floci.service", "amazonmq",
                "io.floci.resource-id", "b-1", "io.floci.account", "111111111111", "io.floci.region", "eu-west-1"));
        verify(logStreamer).attachPolledForAccount(eq("111111111111"), eq("container-id"), anyString(), any(),
                eq("eu-west-1"), anyString());
    }

    /**
     * A container that started but could not be wired up is removed and its host ports handed back, and the broker
     * records no node name, so a failed broker is not taken for one that ran.
     */
    @Test
    void aContainerThatCannotBeWiredUpIsRemoved() {
        ContainerBuilder containerBuilder = Mockito.mock(ContainerBuilder.class);
        selfReturningBuilder(containerBuilder);
        PortAllocator portAllocator = Mockito.mock(PortAllocator.class);
        when(portAllocator.allocate(5672, 5699)).thenReturn(5680);
        when(portAllocator.allocate(15672, 15699)).thenReturn(15680);
        ContainerLifecycleManager lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                "container-id", Map.of(5672, new ContainerLifecycleManager.EndpointInfo("localhost", 5680),
                        15672, new ContainerLifecycleManager.EndpointInfo("localhost", 15680))));
        ContainerLogStreamer logStreamer = Mockito.mock(ContainerLogStreamer.class);
        when(logStreamer.attachPolledForAccount(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("log group refused"));
        RabbitMqManager manager = new RabbitMqManager(containerBuilder, lifecycleManager, logStreamer,
                Mockito.mock(ContainerDetector.class), portAllocator, configWithDefaultPortRanges(), regionResolver());
        Broker broker = broker("b-1");

        assertThrows(IllegalStateException.class, () -> manager.startContainer(broker));

        verify(lifecycleManager).stopAndRemove("container-id", null);
        verify(portAllocator).release(5680);
        verify(portAllocator).release(15680);
        assertNull(broker.getNodeName());
        assertNull(broker.getContainerId());
        manager.stopAll();
        verify(lifecycleManager, Mockito.times(1)).stopAndRemove(anyString(), any());
    }

    /**
     * A container that outlived Floci is kept when it is running and answers on the console the broker's record
     * names, and shutdown then removes it; one that is gone, stopped or not answering is not.
     */
    @Test
    void aRunningContainerAnsweringOnTheBrokersConsoleIsKept() throws Exception {
        HttpServer console = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        console.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        console.start();
        try {
            ContainerLifecycleManager lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
            Container running = Mockito.mock(Container.class);
            when(running.getState()).thenReturn("running");
            when(running.getId()).thenReturn("survivor");
            Container stopped = Mockito.mock(Container.class);
            when(stopped.getState()).thenReturn("exited");
            EmulatorConfig config = configWithDefaultPortRanges();
            when(lifecycleManager.findByName(ContainerStorageHelper.dockerName(config, "amazonmq-b-running")))
                    .thenReturn(Optional.of(running));
            when(lifecycleManager.findByName(ContainerStorageHelper.dockerName(config, "amazonmq-b-stopped")))
                    .thenReturn(Optional.of(stopped));
            Container silent = Mockito.mock(Container.class);
            when(silent.getState()).thenReturn("running");
            when(silent.getId()).thenReturn("silent");
            when(lifecycleManager.findByName(ContainerStorageHelper.dockerName(config, "amazonmq-b-silent")))
                    .thenReturn(Optional.of(silent));
            Container legacy = Mockito.mock(Container.class);
            when(legacy.getState()).thenReturn("running");
            when(legacy.getId()).thenReturn("legacy-survivor");
            when(lifecycleManager.findByName(ContainerStorageHelper.legacyDockerName(config,
                    ContainerStorageHelper.dockerName(config, "amazonmq-b-legacy")))).thenReturn(Optional.of(legacy));
            RabbitMqManager manager = new RabbitMqManager(Mockito.mock(ContainerBuilder.class), lifecycleManager,
                    Mockito.mock(ContainerLogStreamer.class), Mockito.mock(ContainerDetector.class),
                    Mockito.mock(PortAllocator.class), config, regionResolver());
            String consoleUrl = "http://127.0.0.1:" + console.getAddress().getPort();

            assertTrue(manager.adoptRunningContainer(brokerAnswering("b-running", consoleUrl)));
            assertFalse(manager.adoptRunningContainer(brokerAnswering("b-stopped", consoleUrl)));
            assertFalse(manager.adoptRunningContainer(brokerAnswering("b-gone", consoleUrl)));
            assertFalse(manager.adoptRunningContainer(brokerAnswering("b-silent", "http://127.0.0.1:" + closedPort())));
            // A broker created before the floci-aws- prefix runs in a container of the old name.
            assertTrue(manager.adoptRunningContainer(brokerAnswering("b-legacy", consoleUrl)));

            manager.stopAll();
            verify(lifecycleManager).stopAndRemove(eq("survivor"), any());
            verify(lifecycleManager).stopAndRemove(eq("legacy-survivor"), any());
            assertFalse(manager.adoptRunningContainer(brokerAnswering("b-running", consoleUrl)),
                    "nothing is adopted once shutdown removed the containers");
        } finally {
            console.stop(0);
        }
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /** Once shutdown has removed the containers, no broker container starts. */
    @Test
    void noContainerStartsAfterShutdown() {
        ContainerBuilder containerBuilder = Mockito.mock(ContainerBuilder.class);
        selfReturningBuilder(containerBuilder);
        ContainerLifecycleManager lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        RabbitMqManager manager = new RabbitMqManager(containerBuilder, lifecycleManager,
                Mockito.mock(ContainerLogStreamer.class), Mockito.mock(ContainerDetector.class),
                Mockito.mock(PortAllocator.class), configWithDefaultPortRanges(), regionResolver());

        manager.stopAll();

        assertThrows(RabbitMqManager.ShuttingDownException.class,
                () -> manager.restoreContainer(restoredBroker(5680, 15680)));
        verify(lifecycleManager, never()).createAndStart(any());
    }

    /**
     * A start still running when shutdown removes the containers, as a restore's can be while its image pulls,
     * removes its own container and hands its host ports back, rather than leave the container behind.
     */
    @Test
    void aStartShutdownOvertakesRemovesItsContainer() {
        ContainerBuilder containerBuilder = Mockito.mock(ContainerBuilder.class);
        selfReturningBuilder(containerBuilder);
        PortAllocator portAllocator = Mockito.mock(PortAllocator.class);
        when(portAllocator.allocateIfFree(Mockito.anyInt())).thenAnswer(free -> OptionalInt.of(free.getArgument(0)));
        ContainerLifecycleManager lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        RabbitMqManager[] manager = new RabbitMqManager[1];
        when(lifecycleManager.createAndStart(any())).thenAnswer(create -> {
            manager[0].stopAll();
            return new ContainerLifecycleManager.ContainerInfo("container-id", Map.of(
                    5672, new ContainerLifecycleManager.EndpointInfo("localhost", 5680),
                    15672, new ContainerLifecycleManager.EndpointInfo("localhost", 15680)));
        });
        manager[0] = new RabbitMqManager(containerBuilder, lifecycleManager, Mockito.mock(ContainerLogStreamer.class),
                Mockito.mock(ContainerDetector.class), portAllocator, configWithDefaultPortRanges(), regionResolver());

        assertThrows(RabbitMqManager.ShuttingDownException.class,
                () -> manager[0].restoreContainer(restoredBroker(5680, 15680)));

        verify(lifecycleManager).stopAndRemove("container-id", null);
        verify(portAllocator).release(5680);
        verify(portAllocator).release(15680);
        manager[0].stopAll();
        verify(lifecycleManager, Mockito.times(1)).stopAndRemove(anyString(), any());
    }

    private Broker brokerAnswering(String brokerId, String consoleUrl) {
        Broker broker = broker(brokerId);
        broker.setBrokerInstances(List.of(new BrokerInstance(consoleUrl, List.of(), "127.0.0.1")));
        return broker;
    }

    /** A broker reloaded from storage: its admin user has no password, and it had the given host ports. */
    private Broker restoredBroker(int amqpPort, int consolePort) {
        Broker broker = broker("b-1");
        broker.setUsers(List.of(new MqUser("admin", null, true, null)));
        broker.setNodeName("rabbit@localhost");
        broker.setAmqpHostPort(amqpPort);
        broker.setConsoleHostPort(consolePort);
        return broker;
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
        assertEquals(List.of("amqp://172.17.0.5:5672"),
                broker.getBrokerInstances().get(0).getEndpoints());
        assertEquals("http://172.17.0.5:15672", broker.getBrokerInstances().get(0).getConsoleURL());
    }

    /** Container logs stream to the log group DescribeBroker names as generalLogGroup. */
    @Test
    void startContainerStreamsLogsToGeneralLogGroup() {
        EmulatorConfig config = configWithDefaultPortRanges();

        ContainerLifecycleManager lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("container-id", Map.of(
                        5672, new ContainerLifecycleManager.EndpointInfo("localhost", 5672),
                        15672, new ContainerLifecycleManager.EndpointInfo("localhost", 15672))));

        ContainerBuilder containerBuilder = Mockito.mock(ContainerBuilder.class);
        selfReturningBuilder(containerBuilder);

        PortAllocator portAllocator = Mockito.mock(PortAllocator.class);
        when(portAllocator.allocate(5672, 5699)).thenReturn(5672);
        when(portAllocator.allocate(15672, 15699)).thenReturn(15672);

        ContainerLogStreamer logStreamer = Mockito.mock(ContainerLogStreamer.class);
        RabbitMqManager manager = new RabbitMqManager(containerBuilder, lifecycleManager,
                logStreamer, Mockito.mock(ContainerDetector.class),
                portAllocator, config, regionResolver());

        Broker broker = broker("b-1");
        manager.startContainer(broker);

        assertEquals("/aws/amazonmq/broker/b-1/general", broker.generalLogGroup());
        verify(logStreamer).attachPolledForAccount(eq("000000000000"), eq("container-id"),
                eq(broker.generalLogGroup()), any(), any(), eq("amazonmq:b-1"));
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

        // Once for the stale-container sweep before create, once for the rollback; the sweep also
        // clears the pre-migration name so a survivor cannot keep the broker's ports.
        verify(lifecycleManager, Mockito.times(2)).removeIfExists("floci-aws-amazonmq-b-1");
        verify(lifecycleManager).removeIfExists("floci-amazonmq-b-1");
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

        verify(lifecycleManager).removeIfExists("floci-aws-amazonmq-b-2");
        verify(lifecycleManager).removeIfExists("floci-amazonmq-b-2");
        verifyNoMoreInteractions(lifecycleManager);
    }
}
