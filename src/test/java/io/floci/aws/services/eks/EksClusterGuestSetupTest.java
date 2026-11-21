package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.model.Container;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerExecStubs;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.core.common.docker.UserDataPipeline;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.RouteTable;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.LogSetup;
import io.floci.aws.services.eks.model.Logging;
import io.floci.aws.services.eks.model.ResourcesVpcConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #5389: on a cgroup v2 host, k3s moves the root cgroup's processes into {@code /init} and then enables the
 * cgroup controllers, which the kernel allows only while the root cgroup is empty. An exec landing in between makes
 * kubelet exit for good, so nothing is executed in a started k3s container until its API server, which k3s serves
 * only after that step, has answered.
 */
class EksClusterGuestSetupTest {

    private static final String CONTAINER_ID = "container-id-123456789012345678901234567890";

    private EmulatorConfig config;
    private ContainerLifecycleManager lifecycleManager;
    private ContainerBuilder containerBuilder;
    private PortAllocator portAllocator;
    private DockerClient dockerClient;
    private EksClusterManager manager;

    @BeforeEach
    void setUp() {
        config = Mockito.mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.EksServiceConfig eks = Mockito.mock(EmulatorConfig.EksServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(config.storage()).thenReturn(Mockito.mock(EmulatorConfig.StorageConfig.class));
        when(services.eks()).thenReturn(eks);
        when(eks.defaultImage()).thenReturn("rancher/k3s:v1.30.0-k3s1");
        when(eks.apiServerBasePort()).thenReturn(6440);
        when(eks.apiServerMaxPort()).thenReturn(6499);
        when(eks.dockerNetwork()).thenReturn(Optional.empty());
        when(eks.endpointMode()).thenReturn("host");
        when(eks.vpcRouteProgramming()).thenReturn(true);

        lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        containerBuilder = Mockito.mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = Mockito.mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("test-image"));
        portAllocator = Mockito.mock(PortAllocator.class);
        when(portAllocator.allocate(6440, 6499)).thenReturn(6443);
        dockerClient = Mockito.mock(DockerClient.class, Mockito.RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(lifecycleManager.create(any())).thenReturn(CONTAINER_ID);
        when(lifecycleManager.startCreated(any(), any())).thenReturn(new ContainerInfo(CONTAINER_ID, Map.of()));

        manager = Mockito.spy(new EksClusterManager(containerBuilder, lifecycleManager,
                Mockito.mock(ContainerDetector.class), portAllocator,
                Mockito.mock(DockerHostResolver.class), Mockito.mock(EcrRegistryManager.class),
                config, Mockito.mock(RegionResolver.class)));
    }

    @Test
    void aStartedClusterIsNotExecutedInBeforeItsApiAnswers() {
        Cluster cluster = cluster();
        doReturn(false).when(manager).apiAnswers(anyString());

        manager.startCluster(cluster);
        assertFalse(manager.isReady(cluster));

        verify(manager, never()).configureLinkLocalMetadataEndpoint(any(), any());
        verify(manager, never()).configurePodIdentityForward(any(), any());
        verify(manager, never()).configureVpcRoutes(any(), any());
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void theSetupIsRegisteredBeforeTheContainerStarts() {
        AtomicBoolean awaitingAtStart = new AtomicBoolean();
        when(lifecycleManager.startCreated(any(), any())).thenAnswer(invocation -> {
            awaitingAtStart.set(manager.awaitingGuestSetup(CONTAINER_ID));
            return new ContainerInfo(CONTAINER_ID, Map.of());
        });

        manager.startCluster(cluster());

        assertTrue(awaitingAtStart.get(), "a readiness check must not see the container started without its setup");
    }

    @Test
    void theNodeIsSetUpOnceWhenItsApiFirstAnswers() {
        Cluster cluster = cluster();
        doReturn(false).when(manager).apiAnswers(anyString());
        manager.startCluster(cluster);
        manager.isReady(cluster);

        doReturn(true).when(manager).apiAnswers(anyString());
        assertFalse(manager.isReady(cluster), "a node is ready only once it is set up");
        assertEquals(CONTAINER_ID, manager.awaitSetUpNode(cluster).containerId());
        manager.isReady(cluster);

        InOrder order = inOrder(manager);
        order.verify(manager).configureLinkLocalMetadataEndpoint(cluster, CONTAINER_ID);
        order.verify(manager).configurePodIdentityForward(cluster, CONTAINER_ID);
        order.verify(manager).configureVpcRoutes(cluster, CONTAINER_ID);
        verify(manager, times(1)).configureLinkLocalMetadataEndpoint(any(), any());
    }

    @Test
    void aSlowSetupDoesNotHoldTheReadinessCheck() {
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(manager).configureLinkLocalMetadataEndpoint(any(), any());
        doReturn(true).when(manager).apiAnswers(anyString());
        Cluster cluster = cluster();
        manager.startCluster(cluster);

        assertFalse(assertTimeoutPreemptively(Duration.ofSeconds(5), () -> manager.isReady(cluster)));

        release.countDown();
        assertNull(manager.awaitSetUpNode(cluster).failure());
    }

    @Test
    void anAdoptedContainerWaitsForItsApiToo() {
        // adopt() starts a container a Docker daemon reboot stopped, which boots k3s again.
        when(lifecycleManager.findByName("floci-eks-demo")).thenReturn(Optional.of(survivingContainer()));
        when(lifecycleManager.adopt(CONTAINER_ID, List.of(6443), Optional.empty()))
                .thenReturn(new ContainerInfo(CONTAINER_ID, Map.of(), Map.of(6443, 6512)));
        Cluster cluster = cluster();
        doReturn(false).when(manager).apiAnswers(anyString());

        manager.restoreCluster(cluster);
        manager.isReady(cluster);

        verify(manager, never()).configureLinkLocalMetadataEndpoint(any(), any());
        verify(dockerClient, never()).execCreateCmd(anyString());
        doReturn(true).when(manager).apiAnswers(anyString());
        manager.isReady(cluster);
        manager.awaitSetUpNode(cluster);
        verify(manager).configureLinkLocalMetadataEndpoint(cluster, CONTAINER_ID);
    }

    @Test
    void registrationListenersRunOnceTheApiAnswers() {
        // Restoring an attached volume makes its device node in the container.
        AtomicInteger notified = new AtomicInteger();
        manager.addNodeRegistrationListener(instance -> notified.incrementAndGet());
        Cluster cluster = cluster();

        manager.startCluster(cluster);
        assertEquals(0, notified.get());

        manager.setUpGuest(cluster);
        assertEquals(1, notified.get());
    }

    @Test
    void userDataFailsTheNodeGroupWhenTheApiNeverAnswers() {
        Cluster cluster = cluster();
        manager.startCluster(cluster);
        manager.guestSetupTimeout(Duration.ofMillis(100));

        UserDataPipeline.ExecutionResult result =
                manager.executeUserData(cluster, "workers", "#!/bin/bash\necho joined\n");

        assertFalse(result.isSuccess());
        assertTrue(result.getFailureMessage().contains("did not answer within"), result.getFailureMessage());
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void userDataGoesAheadOnceTheNodeIsSetUp() throws Exception {
        Cluster cluster = cluster();
        manager.startCluster(cluster);
        CompletableFuture<EksClusterManager.SetUpNode> waiting =
                CompletableFuture.supplyAsync(() -> manager.awaitSetUpNode(cluster));

        manager.setUpGuest(cluster);

        assertEquals(new EksClusterManager.SetUpNode(CONTAINER_ID, null), waiting.get(10, TimeUnit.SECONDS));
    }

    // Catches: a node group's user data script, which may run for minutes, holding a control-plane
    // connection for as long as it runs instead of a streaming one.
    @Test
    void userDataOutputStreamsOnTheStreamingClient() {
        DockerClient streaming = Mockito.mock(DockerClient.class);
        when(lifecycleManager.getStreamingDockerClient()).thenReturn(streaming);
        ContainerExecStubs.completeEveryExec(dockerClient, streaming, CONTAINER_ID, 0, "joined\n", "");
        Cluster cluster = cluster();
        manager.startCluster(cluster);
        manager.setUpGuest(cluster);

        UserDataPipeline.ExecutionResult result =
                manager.executeUserData(cluster, "workers", "#!/bin/bash\necho joined\n");

        assertTrue(result.isSuccess(), result.getFailureMessage());
        verify(streaming).execStartCmd("exec-" + CONTAINER_ID);
        verify(dockerClient, never()).execStartCmd("exec-" + CONTAINER_ID);
    }

    @Test
    void userDataFollowsANodeReplacedWhileItWaits() throws Exception {
        Cluster cluster = cluster();
        manager.startCluster(cluster);
        AtomicReference<EksClusterManager.SetUpNode> result = new AtomicReference<>();
        Thread waiting = new Thread(() -> result.set(manager.awaitSetUpNode(cluster)));
        waiting.start();
        // Waiting on the first node's setup.
        await().atMost(Duration.ofSeconds(10)).until(() -> waiting.getState() == Thread.State.TIMED_WAITING);

        // A node capacity swap starts a new container and drops the old one's setup.
        manager.deferGuestSetup("replacement", false);
        cluster.setContainerId("replacement");
        manager.abandonGuestSetup(CONTAINER_ID, "its container was replaced");
        manager.setUpGuest(cluster);

        waiting.join(10_000);
        assertEquals(new EksClusterManager.SetUpNode("replacement", null), result.get());
    }

    @Test
    void aRouteTableUpdateLeavesANodeWhoseApiHasNotAnsweredToItsSetup() {
        Cluster cluster = cluster();
        cluster.setResourcesVpcConfig(vpcConfig("vpc-1"));
        manager.startCluster(cluster);
        RouteTable routeTable = new RouteTable();
        routeTable.setVpcId("vpc-1");

        manager.onRouteTableUpdated("us-east-1", routeTable);
        verify(manager, never()).configureVpcRoutes(any(), any());

        manager.setUpGuest(cluster);
        manager.onRouteTableUpdated("us-east-1", routeTable);
        verify(manager, times(2)).configureVpcRoutes(cluster, CONTAINER_ID);
    }

    @Test
    void anInstanceJoiningBeforeTheApiAnswersIsRecordedForFinalizeToRegister() {
        Cluster cluster = cluster();
        manager.startCluster(cluster);

        manager.joinInstance(cluster, instance("i-0123456789abcdef0"));

        assertTrue(manager.isInstanceJoined(cluster, "i-0123456789abcdef0"));
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void aJoinedInstanceThatFailsToRegisterIsJoinedAgainLaterWithoutHoldingBackTheOthers() {
        Cluster cluster = cluster();
        manager.startCluster(cluster);
        manager.joinInstance(cluster, instance("i-0000000000000000a"));
        manager.joinInstance(cluster, instance("i-0000000000000000b"));
        when(dockerClient.execCreateCmd(anyString())).thenThrow(new RuntimeException("docker exec failed"));

        assertDoesNotThrow(() -> manager.registerAllJoinedNodes(cluster, CONTAINER_ID));

        // Left out of the joined set, so the readiness poller joins them again.
        assertFalse(manager.isInstanceJoined(cluster, "i-0000000000000000a"));
        assertFalse(manager.isInstanceJoined(cluster, "i-0000000000000000b"));
    }

    @Test
    void anAddOnChangeBeforeTheApiAnswersIsLeftToTheSetup() {
        // Terraform sends CreateAddon for eks-pod-identity-agent seconds after CreateCluster.
        EmulatorConfig.TlsConfig tls = Mockito.mock(EmulatorConfig.TlsConfig.class);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(true);
        when(config.services().eks().podIdentityWebhook()).thenReturn(true);
        EksAddonService addons = Mockito.mock(EksAddonService.class);
        when(addons.isInstalled(any(), eq(EksPodIdentityWebhook.POD_IDENTITY_ADDON))).thenReturn(true);
        manager.setAddonService(addons);
        Cluster cluster = cluster();
        manager.startCluster(cluster);

        manager.podIdentityAgentChanged(cluster);

        verify(addons, never()).isInstalled(any(), anyString());
        verify(dockerClient, never()).execCreateCmd(anyString());
    }

    @Test
    void aDeleteWaitsForASetupAlreadyRunningAndReleasesTheLogFollowerItAttached() throws Exception {
        DockerClient docker = Mockito.mock(DockerClient.class);
        when(lifecycleManager.getDockerClient()).thenReturn(docker);
        ExecCreateCmd execCreateCmd = Mockito.mock(ExecCreateCmd.class, Mockito.RETURNS_SELF);
        ExecCreateCmdResponse execCreate = Mockito.mock(ExecCreateCmdResponse.class);
        when(execCreate.getId()).thenReturn("exec-123");
        when(docker.execCreateCmd(anyString())).thenReturn(execCreateCmd);
        when(execCreateCmd.exec()).thenReturn(execCreate);
        ExecStartCmd execStartCmd = Mockito.mock(ExecStartCmd.class, Mockito.RETURNS_SELF);
        // The audit follower runs tail -F for the cluster's lifetime, so it streams on the streaming client.
        DockerClient streamingDocker = Mockito.mock(DockerClient.class);
        when(lifecycleManager.getStreamingDockerClient()).thenReturn(streamingDocker);
        when(streamingDocker.execStartCmd("exec-123")).thenReturn(execStartCmd);
        ResultCallback.Adapter<?> auditFollower = Mockito.mock(ResultCallback.Adapter.class);
        Mockito.doReturn(auditFollower).when(execStartCmd).exec(any());
        RegionResolver regionResolver = Mockito.mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");
        EksClusterManager logging = Mockito.spy(new EksClusterManager(containerBuilder, lifecycleManager,
                Mockito.mock(ContainerDetector.class), portAllocator,
                Mockito.mock(DockerHostResolver.class), Mockito.mock(EcrRegistryManager.class),
                config, regionResolver, null, null, Mockito.mock(ContainerLogStreamer.class)));

        CountDownLatch setupRunning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            setupRunning.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(logging).configureLinkLocalMetadataEndpoint(any(), any());
        doReturn(true).when(logging).apiAnswers(anyString());
        Cluster cluster = cluster();
        cluster.setLogging(new Logging(List.of(new LogSetup(List.of("audit"), true))));
        logging.startCluster(cluster);
        logging.isReady(cluster);
        assertTrue(setupRunning.await(10, TimeUnit.SECONDS));

        Thread delete = new Thread(() -> logging.stopCluster(cluster));
        delete.start();
        await().atMost(Duration.ofSeconds(10)).until(() -> delete.getState() == Thread.State.BLOCKED
                || delete.getState() == Thread.State.TERMINATED);
        assertEquals(Thread.State.BLOCKED, delete.getState(), "the delete waits for the running setup");
        release.countDown();
        delete.join(10_000);

        // The delete hands the follower the setup attached to the container's removal, which closes it.
        verify(lifecycleManager).stopAndRemoveStrict(CONTAINER_ID, auditFollower, 5, true);
        assertNull(logging.getLogHandle(cluster));
    }

    @Test
    void deletingAClusterDropsItsPendingSetup() {
        Cluster cluster = cluster();
        manager.startCluster(cluster);

        manager.stopCluster(cluster);

        assertFalse(manager.awaitingGuestSetup(CONTAINER_ID));
    }

    private static Cluster cluster() {
        Cluster cluster = new Cluster();
        cluster.setName("demo");
        return cluster;
    }

    private static Instance instance(String instanceId) {
        Instance instance = new Instance();
        instance.setInstanceId(instanceId);
        instance.setRegion("us-east-1");
        instance.setImageId("ami-0123456789abcdef0");
        return instance;
    }

    private static ResourcesVpcConfig vpcConfig(String vpcId) {
        ResourcesVpcConfig config = new ResourcesVpcConfig();
        config.setVpcId(vpcId);
        return config;
    }

    private static Container survivingContainer() {
        try {
            return new ObjectMapper().readValue("{\"Id\":\"" + CONTAINER_ID + "\",\"Labels\":{"
                    + "\"io.floci.eks.node-capacity\":\"m5.large:unbounded\",\"io.floci.eks.shared-mounts\":\"true\"}}",
                    Container.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
