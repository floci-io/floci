package io.floci.aws.services.ec2.portforward;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceState;
import io.floci.aws.services.ec2.model.IpPermission;
import io.floci.aws.services.ec2.model.IpRange;
import io.floci.aws.services.ec2.model.PublishedPortFamily;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ec2.model.UserIdGroupPair;
import io.floci.aws.testing.IpPermissions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Ec2PortForwardManagerTest {

    private static final int MAX = 20;

    @Test
    void extractsTcpPortsFromCidrSourcedRules() {
        SecurityGroup sg = sg(
                tcpCidr(80, 80, "0.0.0.0/0"),
                tcpCidr(8080, 8080, "10.0.0.0/8"));

        assertEquals(Set.of(80, 8080), extract(sg));
    }

    @Test
    void treatsNumericProtocolSixAsTcp() {
        IpPermission perm = new IpPermission();
        perm.setIpProtocol("6");
        perm.setFromPort(443);
        perm.setToPort(443);
        perm.getIpRanges().add(new IpRange("0.0.0.0/0"));

        assertEquals(Set.of(443), extract(sg(perm)));
    }

    @Test
    void publishesEveryPortInARange() {
        assertEquals(Set.of(8000, 8001, 8002), extract(sg(tcpCidr(8000, 8002, "0.0.0.0/0"))));
    }

    @Test
    void publishesIpv6SourcedRules() {
        assertEquals(Set.of(80), extract(sg(IpPermissions.tcp(80, "::/0"))));
    }

    @Test
    void aggregatesAndDedupesAcrossSecurityGroups() {
        SecurityGroup a = sg(tcpCidr(80, 80, "0.0.0.0/0"));
        SecurityGroup b = sg(tcpCidr(80, 80, "0.0.0.0/0"), tcpCidr(443, 443, "0.0.0.0/0"));

        assertEquals(Set.of(80, 443),
                Ec2PortForwardManager.extractPublishablePorts(List.of(a, b), MAX));
    }

    @Test
    void skipsNonTcpProtocols() {
        assertTrue(extract(sg(protoCidr("udp", 53, 53, "0.0.0.0/0"))).isEmpty());
        assertTrue(extract(sg(protoCidr("17", 53, 53, "0.0.0.0/0"))).isEmpty());
        assertTrue(extract(sg(protoCidr("icmp", -1, -1, "0.0.0.0/0"))).isEmpty());
    }

    @Test
    void skipsAllProtocolsRule() {
        // ipProtocol "-1" means all protocols/all ports; fromPort/toPort come as -1.
        assertTrue(extract(sg(protoCidr("-1", -1, -1, "0.0.0.0/0"))).isEmpty());
    }

    @Test
    void skipsRulesSourcedOnlyBySecurityGroupReference() {
        IpPermission perm = new IpPermission();
        perm.setIpProtocol("tcp");
        perm.setFromPort(80);
        perm.setToPort(80);
        UserIdGroupPair pair = new UserIdGroupPair();
        pair.setGroupId("sg-web");
        perm.getUserIdGroupPairs().add(pair);

        assertTrue(extract(sg(perm)).isEmpty());
    }

    @Test
    void neverForwardsSsh() {
        assertTrue(extract(sg(tcpCidr(22, 22, "0.0.0.0/0"))).isEmpty());
        // A range spanning 22 keeps its other ports but drops 22.
        assertEquals(Set.of(21, 23), extract(sg(tcpCidr(21, 23, "0.0.0.0/0"))));
    }

    @Test
    void skipsRuleWhoseSpanExceedsMax() {
        assertTrue(extract(sg(tcpCidr(0, 65535, "0.0.0.0/0"))).isEmpty());
    }

    @Test
    void capsTotalPublishedPortsPerInstance() {
        Set<Integer> ports = Ec2PortForwardManager.extractPublishablePorts(
                List.of(sg(tcpCidr(3000, 3000, "0.0.0.0/0"),
                        tcpCidr(3001, 3001, "0.0.0.0/0"),
                        tcpCidr(3002, 3002, "0.0.0.0/0"))),
                2);

        assertEquals(2, ports.size());
    }

    @Test
    void protocolHelpersRecognizeTcp() {
        assertTrue(Ec2PortForwardManager.isTcp("tcp"));
        assertTrue(Ec2PortForwardManager.isTcp("TCP"));
        assertTrue(Ec2PortForwardManager.isTcp("6"));
        assertFalse(Ec2PortForwardManager.isTcp("udp"));
        assertFalse(Ec2PortForwardManager.isTcp("17"));
        assertFalse(Ec2PortForwardManager.isTcp(null));
    }

    @Test
    void forwardContainerNameIsDeterministic() {
        assertEquals("floci-aws-ec2-fwd-i-123-80",
                Ec2PortForwardManager.forwardContainerName(null, "i-123", 80));
    }

    @Test
    void legacyForwardContainerNameKeepsThePreMigrationShape() {
        // Frozen: it is how a sidecar created before the rename is found and cleared.
        assertEquals("floci-ec2-fwd-i-123-80",
                Ec2PortForwardManager.legacyForwardContainerName(null, "i-123", 80));
    }

    @Test
    void restoreDropsMappingWhenSidecarRecreationFails() {
        DockerClient dockerClient = mock(DockerClient.class);
        when(dockerClient.inspectContainerCmd(anyString())).thenThrow(new RuntimeException("docker down"));
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.findByName(anyString())).thenReturn(Optional.empty());
        PortAllocator portAllocator = mock(PortAllocator.class);

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.publishSecurityGroupPorts()).thenReturn(true);
        when(ec2.mock()).thenReturn(false);

        Ec2PortForwardManager manager = new Ec2PortForwardManager(
                dockerClient, mock(ContainerBuilder.class), lifecycleManager, portAllocator, config);
        List<String> persisted = new ArrayList<>();
        manager.setPersister(inst -> persisted.add(inst.getInstanceId()));

        Instance instance = new Instance();
        instance.setInstanceId("i-1");
        instance.setDockerContainerId("c1");
        instance.getPublishedPorts().put(8080, 30000);

        manager.restore(instance);

        assertTrue(instance.getPublishedPorts().isEmpty(),
                "failed recreation must not leave a stale published-port entry");
        verify(portAllocator).markReserved(30000);
        verify(portAllocator).release(30000);
        assertEquals(List.of("i-1"), persisted);
    }

    @Test
    void reconcileNowSkipsNonRunningInstance() {
        PortAllocator portAllocator = mock(PortAllocator.class);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        Ec2PortForwardManager manager = new Ec2PortForwardManager(
                mock(DockerClient.class), mock(ContainerBuilder.class), lifecycleManager,
                portAllocator, mock(EmulatorConfig.class));

        Instance instance = new Instance();
        instance.setInstanceId("i-1");
        instance.setDockerContainerId("c1");
        instance.setState(InstanceState.shuttingDown());

        manager.reconcileNow(instance, Ec2PortForwardManager.DesiredPorts.anyFamily(Set.of(80)));

        assertTrue(instance.getPublishedPorts().isEmpty(),
                "a reconcile racing a terminate must not republish against the removed container");
        verifyNoInteractions(portAllocator, lifecycleManager);
    }

    @Test
    void reconcileNowPublishesForRunningInstance() {
        DockerClient dockerClient = mock(DockerClient.class);
        when(dockerClient.inspectContainerCmd(anyString())).thenThrow(new RuntimeException("docker down"));
        PortAllocator portAllocator = mock(PortAllocator.class);
        when(portAllocator.allocate(anyInt(), anyInt())).thenReturn(30000);

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.appPortRangeStart()).thenReturn(30000);
        when(ec2.appPortRangeEnd()).thenReturn(30999);

        Ec2PortForwardManager manager = new Ec2PortForwardManager(
                dockerClient, mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
                portAllocator, config);

        Instance instance = new Instance();
        instance.setInstanceId("i-1");
        instance.setDockerContainerId("c1");
        instance.setState(InstanceState.running());

        manager.reconcileNow(instance, Ec2PortForwardManager.DesiredPorts.anyFamily(Set.of(80)));

        verify(portAllocator).allocate(30000, 30999);
    }

    @Test
    void publishedForwardDisablesNagleAndBindsABurstTolerantAcceptBacklog() {
        DockerClient dockerClient = mock(DockerClient.class);
        when(dockerClient.inspectContainerCmd(anyString())).thenThrow(new RuntimeException("docker down"));
        Forwarding forwarding = forwarding(dockerClient);
        Instance instance = running();
        instance.setContainerBridgeIp("172.17.0.3");

        forwarding.manager().reconcileNow(instance, Ec2PortForwardManager.DesiredPorts.anyFamily(Set.of(80)));

        ContainerSpec spec = forwarding.created().getFirst();
        assertEquals("alpine/socat", spec.image());
        assertEquals(List.of("socat"), spec.entrypoint());
        assertEquals(List.of("TCP-LISTEN:80,fork,reuseaddr,nodelay,backlog=4096",
                "TCP:172.17.0.3:80,nodelay"), spec.cmd());
        assertEquals(Map.of(80, 30000), spec.portBindings());
    }

    // Catches: enforcement publishing a port only a narrower CIDR, a group reference or SSH opens, judged on a host
    // client address Docker may have replaced; each port admits the family whose world-open rule opens it.
    @Test
    void underEnforcementOnlyPortsOpenToEveryAddressArePublished() {
        IpPermission groupOnly = new IpPermission();
        groupOnly.setIpProtocol("tcp");
        groupOnly.setFromPort(9000);
        groupOnly.setToPort(9000);
        UserIdGroupPair pair = new UserIdGroupPair();
        pair.setGroupId("sg-web");
        groupOnly.getUserIdGroupPairs().add(pair);
        SecurityGroup sg = sg(IpPermissions.tcp(80, "0.0.0.0/0"), IpPermissions.tcp(80, "::/0"),
                IpPermissions.tcp(8000, "0.0.0.0/0"), IpPermissions.tcp(8080, "10.0.0.0/8"),
                IpPermissions.tcp(443, "::/0"), IpPermissions.tcp(8443, "2600:1f18::/56"),
                IpPermissions.tcp(22, "0.0.0.0/0"), groupOnly);

        Ec2PortForwardManager.DesiredPorts desired = Ec2PortForwardManager.extractWorldOpenPorts(List.of(sg), MAX);

        assertEquals(Map.of(80, PublishedPortFamily.ANY, 8000, PublishedPortFamily.IPV4,
                443, PublishedPortFamily.IPV6), desired.ports());
    }

    // Catches: the publisher still refusing to run while enforcement is on.
    @Test
    void thePublisherRunsWithEnforcementOn() {
        Forwarding forwarding = forwarding(mock(DockerClient.class));
        when(forwarding.config().services().ec2().publishSecurityGroupPorts()).thenReturn(true);
        when(forwarding.config().network().securityGroupEnforcement().enabled()).thenReturn(true);

        assertTrue(forwarding.manager().enabled());
    }

    // Catches: a sidecar dialling the instance container, which under enforcement holds no address of its own, rather
    // than its namespace's helper on the helper's network; a port only ::/0 opens dialled over IPv4, which that rule
    // never admits; and a port bound on every host address, which lets a client of the other family through.
    @Test
    void underEnforcementTheSidecarBindsAndDialsTheFamilyItsRuleOpens() {
        Forwarding forwarding = forwarding(protectedNamespace("172.18.0.5", "fd00:d0c::5"));
        Instance instance = running();

        forwarding.manager().reconcileNow(instance, new Ec2PortForwardManager.DesiredPorts(Map.of(
                80, PublishedPortFamily.IPV4, 443, PublishedPortFamily.IPV6, 8080, PublishedPortFamily.ANY)));

        Map<Integer, ContainerSpec> specs = forwarding.created().stream()
                .collect(Collectors.toMap(spec -> spec.portBindings().keySet().iterator().next(), spec -> spec));
        assertEquals(List.of("TCP-LISTEN:80,fork,reuseaddr,nodelay,backlog=4096", "TCP:172.18.0.5:80,nodelay"),
                specs.get(80).cmd());
        assertEquals(Map.of(80, "0.0.0.0"), specs.get(80).portBindingHostIps());
        assertEquals(List.of("TCP6-LISTEN:443,ipv6only=0,fork,reuseaddr,nodelay,backlog=4096",
                "TCP6:[fd00:d0c::5]:443,nodelay"), specs.get(443).cmd());
        assertEquals(Map.of(443, "::"), specs.get(443).portBindingHostIps());
        assertEquals("TCP:172.18.0.5:8080,nodelay", specs.get(8080).cmd().get(1));
        assertEquals(Map.of(), specs.get(8080).portBindingHostIps());
        assertEquals(Set.of("floci-net"), specs.values().stream().map(ContainerSpec::networkMode)
                .collect(Collectors.toSet()));
        assertEquals(Map.of(80, PublishedPortFamily.IPV4, 443, PublishedPortFamily.IPV6),
                instance.getPublishedPortFamilies());
    }

    // Catches: a port whose opening rules moved family keeping a sidecar that binds or dials the old one, which
    // admits the wrong clients or which the firewall now drops.
    @Test
    void aPortWhoseRulesMoveFamilyIsRepublished() {
        Forwarding forwarding = forwarding(protectedNamespace("172.18.0.5", "fd00:d0c::5"));
        Instance instance = running();
        forwarding.manager().reconcileNow(instance, Ec2PortForwardManager.DesiredPorts.anyFamily(Set.of(80)));

        forwarding.manager().reconcileNow(instance,
                new Ec2PortForwardManager.DesiredPorts(Map.of(80, PublishedPortFamily.IPV6)));
        forwarding.manager().reconcileNow(instance, Ec2PortForwardManager.DesiredPorts.anyFamily(Set.of(80)));

        assertEquals(List.of("TCP:172.18.0.5:80,nodelay", "TCP6:[fd00:d0c::5]:80,nodelay", "TCP:172.18.0.5:80,nodelay"),
                forwarding.created().stream().map(spec -> spec.cmd().get(1)).toList());
        assertEquals(Map.of(80, 30000), instance.getPublishedPorts());
        assertEquals(Map.of(), instance.getPublishedPortFamilies());
    }

    // Catches: a port only ::/0 opens published on a Docker network without IPv6, where its rule admits nothing, or
    // dialled on the stored IPv4 address instead.
    @Test
    void aPortOnlyIpv6OpensIsNotPublishedWithoutAnIpv6Address() {
        Forwarding forwarding = forwarding(protectedNamespace("172.18.0.5", null));
        Instance instance = running();
        instance.setContainerBridgeIp("172.18.0.5");

        forwarding.manager().reconcileNow(instance,
                new Ec2PortForwardManager.DesiredPorts(Map.of(443, PublishedPortFamily.IPV6)));

        assertTrue(forwarding.created().isEmpty());
        assertTrue(instance.getPublishedPorts().isEmpty());
        verify(forwarding.portAllocator()).release(30000);
    }

    @Test
    void failedCloseOfRunningRelayRetainsBindingForRetry() {
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        Forwarding forwarding = forwarding(docker);
        when(forwarding.config().network().securityGroupEnforcement().enabled()).thenReturn(true);
        Instance instance = running();
        instance.getPublishedPorts().put(8080, 30000);
        InspectContainerResponse relay = docker.inspectContainerCmd("floci-aws-ec2-fwd-i-1-8080").exec();
        when(relay.getId()).thenReturn("relay");
        when(relay.getHostConfig().getAutoRemove()).thenReturn(true);
        when(docker.execCreateCmd("relay")).thenThrow(new IllegalStateException("Docker unavailable"));
        when(docker.inspectContainerCmd("relay").exec().getState().getRunning()).thenReturn(true);

        assertThrows(IllegalStateException.class,
                () -> forwarding.manager().reconcileNow(instance, Ec2PortForwardManager.DesiredPorts.NONE));
        assertEquals(Map.of(8080, 30000), instance.getPublishedPorts());
        verifyNoInteractions(forwarding.lifecycle(), forwarding.portAllocator());
    }

    /** A manager that builds real sidecar specs, allocating host port 30000 for every forward. */
    private record Forwarding(Ec2PortForwardManager manager, ContainerLifecycleManager lifecycle,
                              PortAllocator portAllocator, EmulatorConfig config) {
        List<ContainerSpec> created() {
            ArgumentCaptor<ContainerSpec> spec = ArgumentCaptor.forClass(ContainerSpec.class);
            verify(lifecycle, atLeast(0)).create(spec.capture());
            return spec.getAllValues();
        }
    }

    private static Forwarding forwarding(DockerClient dockerClient) {
        PortAllocator portAllocator = mock(PortAllocator.class);
        when(portAllocator.allocate(anyInt(), anyInt())).thenReturn(30000);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.create(any())).thenReturn("fwd-1");

        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().socatImage()).thenReturn("alpine/socat");
        when(config.services().ec2().appPortRangeStart()).thenReturn(30000);
        when(config.services().ec2().appPortRangeEnd()).thenReturn(30999);
        when(config.docker().logMaxSize()).thenReturn("10m");
        when(config.docker().logMaxFile()).thenReturn("3");

        Ec2PortForwardManager manager = new Ec2PortForwardManager(
                dockerClient,
                new ContainerBuilder(config, mock(DockerHostResolver.class), mock(EmbeddedDnsServer.class)),
                lifecycleManager, portAllocator, config);
        return new Forwarding(manager, lifecycleManager, portAllocator, config);
    }

    private static Instance running() {
        Instance instance = new Instance();
        instance.setInstanceId("i-1");
        instance.setDockerContainerId("c1");
        instance.setState(InstanceState.running());
        return instance;
    }

    /** Instance container {@code c1} joins {@code helper-1}'s namespace, which holds the addresses on floci-net. */
    private static DockerClient protectedNamespace(String ipv4, String ipv6) {
        InspectContainerResponse instance = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(instance.getHostConfig().getNetworkMode()).thenReturn("container:helper-1");
        ContainerNetwork network = mock(ContainerNetwork.class);
        when(network.getIpAddress()).thenReturn(ipv4);
        when(network.getGlobalIPv6Address()).thenReturn(ipv6);
        InspectContainerResponse helper = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
        when(helper.getNetworkSettings().getNetworks()).thenReturn(Map.of("floci-net", network));
        DockerClient docker = mock(DockerClient.class);
        InspectContainerCmd instanceCmd = mock(InspectContainerCmd.class);
        when(instanceCmd.exec()).thenReturn(instance);
        when(docker.inspectContainerCmd("c1")).thenReturn(instanceCmd);
        InspectContainerCmd helperCmd = mock(InspectContainerCmd.class);
        when(helperCmd.exec()).thenReturn(helper);
        when(docker.inspectContainerCmd("helper-1")).thenReturn(helperCmd);
        return docker;
    }

    private static Set<Integer> extract(SecurityGroup sg) {
        return Ec2PortForwardManager.extractPublishablePorts(List.of(sg), MAX);
    }

    private static SecurityGroup sg(IpPermission... perms) {
        SecurityGroup sg = new SecurityGroup();
        sg.setGroupId("sg-test");
        sg.setIpPermissions(new ArrayList<>(List.of(perms)));
        return sg;
    }

    private static IpPermission tcpCidr(int from, int to, String cidr) {
        return protoCidr("tcp", from, to, cidr);
    }

    private static IpPermission protoCidr(String proto, int from, int to, String cidr) {
        IpPermission perm = new IpPermission();
        perm.setIpProtocol(proto);
        perm.setFromPort(from);
        perm.setToPort(to);
        perm.getIpRanges().add(new IpRange(cidr));
        return perm;
    }
}
