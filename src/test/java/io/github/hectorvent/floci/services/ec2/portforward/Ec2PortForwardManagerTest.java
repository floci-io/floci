package io.github.hectorvent.floci.services.ec2.portforward;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.IpRange;
import io.github.hectorvent.floci.services.ec2.model.Ipv6Range;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.UserIdGroupPair;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
        IpPermission perm = new IpPermission();
        perm.setIpProtocol("tcp");
        perm.setFromPort(80);
        perm.setToPort(80);
        perm.getIpv6Ranges().add(new Ipv6Range("::/0"));

        assertEquals(Set.of(80), extract(sg(perm)));
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
        assertEquals("floci-ec2-fwd-i-123-80",
                Ec2PortForwardManager.forwardContainerName("i-123", 80));
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

        manager.reconcileNow(instance, Set.of(80));

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

        manager.reconcileNow(instance, Set.of(80));

        verify(portAllocator).allocate(30000, 30999);
    }

    private static Set<Integer> extract(SecurityGroup sg) {
        return Ec2PortForwardManager.extractPublishablePorts(List.of(sg), MAX);
    }

    private static SecurityGroup sg(IpPermission... perms) {
        SecurityGroup sg = new SecurityGroup();
        sg.setGroupId("sg-test");
        sg.setIpPermissions(new java.util.ArrayList<>(List.of(perms)));
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
