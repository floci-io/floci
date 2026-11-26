package io.floci.aws.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.HostConfig;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.dns.DnsClientVpcSource.ClientVpc;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.SecurityGroupFirewallManager;
import io.floci.aws.services.ec2.model.GroupIdentifier;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceState;
import io.floci.aws.services.ec2.model.NetworkInterface;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.services.ecs.model.AwsVpcConfiguration;
import io.floci.aws.services.ecs.model.Container;
import io.floci.aws.services.ecs.model.ContainerDefinition;
import io.floci.aws.services.ecs.model.EcsTask;
import io.floci.aws.services.ecs.model.NetworkConfiguration;
import io.floci.aws.services.ecs.model.NetworkMode;
import io.floci.aws.services.ecs.model.PortMapping;
import io.floci.aws.services.ecs.model.TaskDefinition;
import io.floci.aws.services.lambda.launcher.ImageCacheService.LaunchImage;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.ssm.SsmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * An awsvpc task reports its ENI address, so one of its containers has to hold that address on
 * the VPC's Docker network. Peers that reach the task through Cloud Map or ELBv2 keep using the
 * address on the network the container was created on.
 */
class EcsContainerManagerTaskAddressTest {

    private static final String REGION = "us-east-1";

    private ContainerLifecycleManager lifecycleManager;
    private DockerClient dockerClient;
    private Ec2Service ec2Service;
    private SecurityGroupFirewallManager firewallManager;
    private ContainerBuilder containerBuilder;
    private EcsContainerManager manager;

    @BeforeEach
    void setUp() {
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(containerBuilder.resolveImage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.resolveImageForLaunch(any(), any(), any()))
                .thenAnswer(invocation -> new LaunchImage(invocation.getArgument(0), null));
        when(lifecycleManager.createAndStartEphemeral(any(), any()))
                .thenReturn(new ContainerInfo("sidecar-id", Map.of()), new ContainerInfo("web-id", Map.of()));
        dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);

        ContainerDetector containerDetector = mock(ContainerDetector.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        LaunchedContainerAwsEnv awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(any(), any())).thenReturn(List.of());
        EcrRegistryManager ecrRegistryManager = mock(EcrRegistryManager.class);
        when(ecrRegistryManager.rewriteImageUri(anyString())).thenAnswer(inv -> inv.getArgument(0));

        ec2Service = mock(Ec2Service.class);
        Subnet subnet = new Subnet();
        subnet.setSubnetId("subnet-1");
        subnet.setVpcId("vpc-1");
        when(ec2Service.describeSubnets(eq(REGION), eq(List.of("subnet-1")), any())).thenReturn(List.of(subnet));
        firewallManager = mock(SecurityGroupFirewallManager.class);

        manager = new EcsContainerManager(
                containerBuilder, lifecycleManager, mock(ContainerLogStreamer.class),
                containerDetector, config, regionResolver, awsEnv,
                mock(SsmService.class), mock(SecretsManagerService.class), mock(S3Service.class),
                ecrRegistryManager, mock(HostVolumePolicy.class),
                ec2Service, firewallManager,
                mock(EcsTaskRoleCredentials.class), mock(EcsCredentialsProxy.class),
                new EcsTaskLinkLocalAddresses());
    }

    @ParameterizedTest
    @ValueSource(strings = {"bridge", "host"})
    void registeredHostVpcScopesTaskAddresses(String mode) {
        dnsAttachments("sidecar-id", "172.17.0.5", "2001:db8::5");
        EcsTask task = taskWithEni();
        task.setNetworkInterfaceId(null);
        ClientVpc origin = new ClientVpc("000000000000", REGION, "vpc-host");
        EcsTaskHandle handle = manager.startTask(task, taskDefinition(NetworkMode.valueOf(mode),
                container("web", List.of())), List.of(), REGION, origin);
        assertEquals(Optional.of(origin), manager.vpcForClient("172.17.0.5"));
        manager.cleanupStoppedTask(handle);
        assertTrue(manager.vpcForClient("172.17.0.5").isEmpty());
    }

    @Test
    void registeredHostLookupRequiresMatchingAccountRegionAndVpc() {
        Instance instance = new Instance();
        instance.setOwnerId("111111111111");
        instance.setState(InstanceState.running());
        instance.setRegion(REGION);
        instance.setVpcId("vpc-host");
        when(ec2Service.findInstanceForAccount("111111111111", REGION, "i-host"))
                .thenReturn(Optional.of(instance));
        assertEquals(Optional.of(new ClientVpc("111111111111", REGION, "vpc-host")),
                manager.vpcForInstance("111111111111", REGION, "i-host"));
        assertTrue(manager.vpcForInstance("222222222222", REGION, "i-host").isEmpty());
        instance.setState(InstanceState.stopped());
        assertTrue(manager.vpcForInstance("111111111111", REGION, "i-host").isEmpty());
        instance.setState(InstanceState.running());
        instance.setRegion("us-west-2");
        assertTrue(manager.vpcForInstance("111111111111", REGION, "i-host").isEmpty());
        instance.setRegion(REGION);
        instance.setVpcId(null);
        assertTrue(manager.vpcForInstance("111111111111", REGION, "i-host").isEmpty());
    }

    @Test
    void foreignHostContextCannotGiveATaskPrivateDnsAccess() {
        dnsAttachments("sidecar-id", "172.17.0.5", "2001:db8::5");
        EcsTask task = taskWithEni();
        task.setNetworkInterfaceId(null);
        manager.startTask(task, taskDefinition(NetworkMode.bridge, container("web", List.of())), List.of(), REGION,
                new ClientVpc("222222222222", REGION, "vpc-host"));
        assertTrue(manager.vpcForClient("172.17.0.5").isEmpty());
    }

    @Test
    void awsvpcDnsUsesActualAttachmentsAndRevokesOnCleanup() {
        dnsAttachments("sidecar-id", "172.17.0.5", "2001:db8::5");
        dnsAttachments("web-id", "172.17.0.6", "2001:db8::6");
        EcsTask task = taskWithEni();
        EcsTaskHandle handle = manager.startTask(task, taskDefinition(NetworkMode.awsvpc,
                container("sidecar", List.of()), container("web", List.of())), List.of(), REGION);
        ClientVpc expected = new ClientVpc("000000000000", REGION, "vpc-1");
        clearInvocations(dockerClient);
        for (String address : List.of("172.17.0.5", "172.17.0.6", "2001:db8::5", "2001:db8::6")) {
            assertEquals(Optional.of(expected), manager.vpcForClient(address));
        }
        assertTrue(manager.vpcForClient("10.0.0.10").isEmpty(), "An ENI address is not proof of a Docker attachment");
        assertTrue(manager.vpcForClient(null).isEmpty());
        verifyNoInteractions(dockerClient);
        manager.cleanupStoppedTask(handle);
        assertTrue(manager.vpcForClient("172.17.0.5").isEmpty());
        assertTrue(manager.vpcForClient("2001:db8::6").isEmpty());
    }

    @Test
    void failedLaunchRevokesAlreadyRegisteredDnsAddresses() {
        dnsAttachments("sidecar-id", "172.17.0.5", "2001:db8::5");
        when(lifecycleManager.createAndStartEphemeral(any(), any()))
                .thenReturn(new ContainerInfo("sidecar-id", Map.of()))
                .thenThrow(new IllegalStateException("second container failed"));
        assertThrows(RuntimeException.class, () -> manager.startTask(taskWithEni(),
                taskDefinition(NetworkMode.awsvpc, container("sidecar", List.of()), container("web", List.of())),
                List.of(), REGION));
        assertTrue(manager.vpcForClient("172.17.0.5").isEmpty());
        assertTrue(manager.vpcForClient("2001:db8::5").isEmpty());
    }

    @Test
    void ambiguousTaskAddressDoesNotChooseAnotherVpcAndCleanupPreservesTheOtherTask() {
        dnsAttachments("sidecar-id", "172.17.0.5", "2001:db8::5");
        dnsAttachments("web-id", "172.17.0.5", "2001:db8::5");
        EcsTask first = taskWithEni();
        manager.startTask(first, taskDefinition(NetworkMode.awsvpc, container("web", List.of())), List.of(), REGION);
        EcsTask second = taskWithEni();
        second.setTaskArn("arn:aws:ecs:us-east-1:222222222222:task/test-cluster/second");
        manager.startTask(second, taskDefinition(NetworkMode.awsvpc, container("web", List.of())), List.of(), REGION);
        assertTrue(manager.vpcForClient("172.17.0.5").isEmpty());
        manager.releaseTaskNetwork(first, REGION);
        assertEquals(Optional.of(new ClientVpc("222222222222", REGION, "vpc-1")),
                manager.vpcForClient("172.17.0.5"));
        manager.releaseTaskNetwork(second, REGION);
        assertTrue(manager.vpcForClient("172.17.0.5").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"bridge", "host", "none"})
    void nonVpcNetworkDoesNotBorrowAnUnusedAwsvpcConfiguration(String mode) {
        dnsAttachments("sidecar-id", "172.17.0.5", "2001:db8::5");
        manager.startTask(taskWithEni(), taskDefinition(NetworkMode.valueOf(mode), container("web", List.of())),
                List.of(), REGION);
        assertTrue(manager.vpcForClient("172.17.0.5").isEmpty());
    }

    private void dnsAttachments(String dockerId, String ipv4, String ipv6) {
        InspectContainerResponse inspect = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        ContainerNetwork network = mock(ContainerNetwork.class);
        when(network.getIpAddress()).thenReturn(ipv4);
        when(network.getGlobalIPv6Address()).thenReturn(ipv6);
        when(inspect.getNetworkSettings().getNetworks()).thenReturn(Map.of("floci-net", network));
        when(dockerClient.inspectContainerCmd(dockerId).exec()).thenReturn(inspect);
    }

    @Test
    void awsvpcTaskGivesItsEniAddressToTheFirstContainerThatMapsAPort() {
        manager.startTask(taskWithEni(), taskDefinition(NetworkMode.awsvpc,
                container("sidecar", List.of()), container("web", List.of(new PortMapping(80, 80, "tcp")))),
                List.of(), REGION);

        verify(ec2Service).attachContainerToVpc("000000000000", REGION, "vpc-1", "subnet-1", "web-id", "10.0.0.10");
    }

    @Test
    void awsvpcTaskWithoutPortMappingsGivesItsEniAddressToItsFirstContainer() {
        manager.startTask(taskWithEni(), taskDefinition(NetworkMode.awsvpc,
                container("sidecar", List.of()), container("web", List.of())), List.of(), REGION);

        verify(ec2Service).attachContainerToVpc("000000000000", REGION, "vpc-1", "subnet-1", "sidecar-id", "10.0.0.10");
    }

    @Test
    void bridgeTaskJoinsNoVpcNetwork() {
        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123");

        manager.startTask(task, taskDefinition(NetworkMode.bridge,
                container("web", List.of(new PortMapping(80, 80, "tcp")))), List.of(), REGION);

        verify(ec2Service, never()).attachContainerToVpc(any(), any(), any(), any(), any(), any());
    }

    @Test
    void securityGroupProtectedTaskKeepsItsOwnNamespace() {
        when(firewallManager.enabled()).thenReturn(true);
        when(firewallManager.createNamespace(eq("ecs"), eq("abc123"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new SecurityGroupFirewallManager.Namespace("helper-id", "10.0.0.5"));
        NetworkInterface eni = new NetworkInterface();
        eni.setNetworkInterfaceId("eni-1");
        eni.setVpcId("vpc-1");
        eni.setPrivateIpAddress("10.0.0.10");
        eni.setGroups(List.of(new GroupIdentifier("sg-1", "default")));
        when(ec2Service.createNetworkInterface(any(), eq("subnet-1"), any(), any(), any(), any(), any()))
                .thenReturn(eni);
        SecurityGroup sg = new SecurityGroup();
        sg.setGroupId("sg-1");
        when(ec2Service.describeSecurityGroups(any(), eq(List.of("sg-1")), any(), any())).thenReturn(List.of(sg));
        EcsTask task = taskWithEni();
        task.setNetworkInterfaceId(null);

        manager.startTask(task, taskDefinition(NetworkMode.awsvpc,
                container("web", List.of(new PortMapping(80, 80, "tcp")))), List.of(), REGION);

        assertEquals("eni-1", task.getNetworkInterfaceId());
        assertEquals(Optional.of(new ClientVpc("000000000000", REGION, "vpc-1")),
                manager.vpcForClient("10.0.0.5"));
        manager.releaseTaskNetwork(task, REGION);
        assertTrue(manager.vpcForClient("10.0.0.5").isEmpty());
        verify(ec2Service, never()).attachContainerToVpc(any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"bridge", "default"})
    void peerAddressStaysOnTheNetworkTheContainerWasCreatedOn(String networkMode) {
        InspectContainerResponse inspect = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(inspect.getHostConfig()).thenReturn(new HostConfig().withNetworkMode(networkMode));
        Map<String, ContainerNetwork> networks = new LinkedHashMap<>();
        networks.put("floci-vpc-4566-us-east-1-vpc-1", new ContainerNetwork().withIpv4Address("10.0.0.10"));
        networks.put("bridge", new ContainerNetwork().withIpv4Address("172.17.0.5"));
        when(inspect.getNetworkSettings().getNetworks()).thenReturn(networks);
        when(dockerClient.inspectContainerCmd("web-id").exec()).thenReturn(inspect);
        Container container = new Container();
        container.setDockerId("web-id");

        assertEquals(Optional.of("172.17.0.5"), manager.resolvePeerAddress(container));
    }

    @Test
    void peerAddressPrefersTheNetworkEcsContainersJoin() {
        when(containerBuilder.resolveDockerNetwork(any())).thenReturn(Optional.of("floci-net"));
        InspectContainerResponse inspect = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        // Created on the default bridge to publish its ports, then connected to Floci's network.
        when(inspect.getHostConfig()).thenReturn(new HostConfig().withNetworkMode("default"));
        Map<String, ContainerNetwork> networks = new LinkedHashMap<>();
        networks.put("bridge", new ContainerNetwork().withIpv4Address("172.17.0.5"));
        networks.put("floci-net", new ContainerNetwork().withIpv4Address("172.20.0.5"));
        when(inspect.getNetworkSettings().getNetworks()).thenReturn(networks);
        when(dockerClient.inspectContainerCmd("web-id").exec()).thenReturn(inspect);
        Container container = new Container();
        container.setDockerId("web-id");

        assertEquals(Optional.of("172.20.0.5"), manager.resolvePeerAddress(container));
    }

    private static EcsTask taskWithEni() {
        AwsVpcConfiguration awsvpc = new AwsVpcConfiguration();
        awsvpc.setSubnets(List.of("subnet-1"));
        awsvpc.setSecurityGroups(List.of("sg-1"));
        NetworkConfiguration network = new NetworkConfiguration();
        network.setAwsvpcConfiguration(awsvpc);
        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123");
        task.setNetworkConfiguration(network);
        task.setNetworkInterfaceId("eni-1");
        task.setPrivateIpAddress("10.0.0.10");
        return task;
    }

    private static ContainerDefinition container(String name, List<PortMapping> ports) {
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName(name);
        definition.setImage(name + ":latest");
        definition.setPortMappings(ports);
        return definition;
    }

    private static TaskDefinition taskDefinition(NetworkMode mode, ContainerDefinition... containers) {
        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("test-family");
        taskDef.setNetworkMode(mode);
        taskDef.setContainerDefinitions(List.of(containers));
        return taskDef;
    }
}
