package io.github.hectorvent.floci.services.ec2.net;

import io.github.hectorvent.floci.config.EmulatorConfig;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ConnectToNetworkCmd;
import com.github.dockerjava.api.command.CreateNetworkCmd;
import com.github.dockerjava.api.command.CreateNetworkResponse;
import com.github.dockerjava.api.command.DisconnectFromNetworkCmd;
import com.github.dockerjava.api.command.InspectNetworkCmd;
import com.github.dockerjava.api.command.ListNetworksCmd;
import com.github.dockerjava.api.command.RemoveNetworkCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Network;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VpcNetworkManagerTest {

    private static final String REGION = "us-east-1";

    private DockerClient docker;
    private EmulatorConfig config;
    private VpcNetworkManager manager;
    private List<Network> existingNetworks;
    private List<String> removedNetworks;

    @BeforeEach
    void setUp() {
        existingNetworks = new ArrayList<>();
        removedNetworks = new ArrayList<>();
        docker = mock(DockerClient.class);
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.port()).thenReturn(4650);
        when(config.services().ec2().mock()).thenReturn(false);
        when(config.services().ec2().vpcNetworks().enabled()).thenReturn(true);
        when(config.services().ec2().vpcNetworks().fallbackPool()).thenReturn("10.240.0.0/12");
        when(config.services().ec2().vpcNetworks().fallbackPrefixLength()).thenReturn(16);
        when(config.services().ec2().vpcNetworks().reconcileOnStartup()).thenReturn(true);
        when(config.services().ec2().vpcNetworks().driver()).thenReturn("bridge");
        when(config.docker().extraLabels()).thenReturn(List.of());
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());

        ListNetworksCmd list = mock(ListNetworksCmd.class, RETURNS_SELF);
        when(list.exec()).thenAnswer(invocation -> List.copyOf(existingNetworks));
        when(docker.listNetworksCmd()).thenReturn(list);

        // Inspect resolves against the same fixture list, so a network with live endpoints can be
        // told apart from one that does not exist yet.
        when(docker.inspectNetworkCmd()).thenAnswer(listCall -> {
            InspectNetworkCmd inspect = mock(InspectNetworkCmd.class);
            String[] requested = new String[1];
            when(inspect.withNetworkId(anyString())).thenAnswer(call -> {
                requested[0] = call.getArgument(0);
                return inspect;
            });
            when(inspect.exec()).thenAnswer(call -> existingNetworks.stream()
                    .filter(n -> n.getId().equals(requested[0]) || n.getName().equals(requested[0]))
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException("no such network")));
            return inspect;
        });

        CreateNetworkCmd create = mock(CreateNetworkCmd.class, RETURNS_SELF);
        when(create.exec()).thenReturn(new CreateNetworkResponse());
        when(docker.createNetworkCmd()).thenReturn(create);

        ConnectToNetworkCmd connect = mock(ConnectToNetworkCmd.class, RETURNS_SELF);
        when(docker.connectToNetworkCmd()).thenReturn(connect);

        when(docker.disconnectFromNetworkCmd())
                .thenReturn(mock(DisconnectFromNetworkCmd.class, RETURNS_SELF));

        when(docker.removeNetworkCmd(anyString())).thenAnswer(invocation -> {
            removedNetworks.add(invocation.getArgument(0));
            return mock(RemoveNetworkCmd.class, RETURNS_SELF);
        });

        manager = new VpcNetworkManager(config, docker);
    }

    private void existingNetwork(String name, String subnet, Map<String, String> labels) {
        existingNetwork(name, subnet, labels, Map.of());
    }

    private void existingNetwork(String name, String subnet, Map<String, String> labels,
                                 Map<String, Network.ContainerNetworkConfig> containers) {
        Network network = mock(Network.class);
        when(network.getContainers()).thenReturn(containers);
        when(network.getName()).thenReturn(name);
        when(network.getId()).thenReturn(name);
        when(network.getLabels()).thenReturn(labels);
        when(network.getIpam()).thenReturn(new Network.Ipam()
                .withConfig(new Network.Ipam.Config().withSubnet(subnet)));
        existingNetworks.add(network);
    }

    // ─── The ordinary case: the declared CIDR is what the instance gets ───────

    @Test
    void usesTheDeclaredCidrAndAllocatesInsideTheDeclaredSubnet() {
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-a", "10.0.1.0/24");

        assertFalse(manager.isSubstituted(REGION, "vpc-1"));
        assertEquals("10.0.0.0/16", manager.effectiveVpcCidr(REGION, "vpc-1").orElseThrow());
        assertEquals("10.0.1.0/24", manager.effectiveSubnetCidr(REGION, "subnet-a").orElseThrow());

        Cidr4 declared = Cidr4.parse("10.0.1.0/24").orElseThrow();
        for (int i = 0; i < 5; i++) {
            String address = manager.allocatePrivateIp(REGION, "subnet-a").orElseThrow();
            long value = Cidr4.parse(address + "/32").orElseThrow().network();
            assertTrue(declared.containsAddress(value), address + " should be inside 10.0.1.0/24");
            assertNotEquals("10.0.1.0", address, "never the network address");
            assertNotEquals("10.0.1.1", address, "never the Docker gateway");
        }
    }

    @Test
    void handsOutADistinctAddressEachTime() {
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-a", "10.0.1.0/24");
        String first = manager.allocatePrivateIp(REGION, "subnet-a").orElseThrow();
        String second = manager.allocatePrivateIp(REGION, "subnet-a").orElseThrow();
        assertNotEquals(first, second);
    }

    @Test
    void aReleasedAddressIsHandedOutAgainRatherThanExhaustingTheSubnet() {
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-tiny", "10.0.9.0/28");

        List<String> allocated = new ArrayList<>();
        Optional<String> next;
        while ((next = manager.allocatePrivateIp(REGION, "subnet-tiny")).isPresent()) {
            allocated.add(next.get());
        }
        assertTrue(allocated.size() >= 4, "a /28 should yield several usable addresses");
        assertTrue(manager.allocatePrivateIp(REGION, "subnet-tiny").isEmpty(), "and then be exhausted");

        manager.releasePrivateIp(REGION, "subnet-tiny", allocated.get(0));
        assertEquals(allocated.get(0), manager.allocatePrivateIp(REGION, "subnet-tiny").orElseThrow(),
                "a terminated instance's address must come back into circulation");
    }

    // ─── Restart: addresses persisted instances still hold ───────────────────

    @Test
    void aReservedAddressIsNeverHandedOutAgain() {
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-a", "10.0.1.0/24");

        // What a restart looks like: the lease table is empty, but a live container holds .10.
        assertTrue(manager.reservePrivateIp(REGION, "subnet-a", "10.0.1.10"));

        for (int i = 0; i < 20; i++) {
            assertNotEquals("10.0.1.10", manager.allocatePrivateIp(REGION, "subnet-a").orElseThrow(),
                    "the address a restored instance still holds must not be re-allocated");
        }
    }

    @Test
    void reservingTheSameAddressTwiceRefusesTheSecondClaimWithoutThrowing() {
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-a", "10.0.1.0/24");

        assertTrue(manager.reservePrivateIp(REGION, "subnet-a", "10.0.1.11"));
        assertFalse(manager.reservePrivateIp(REGION, "subnet-a", "10.0.1.11"),
                "two instances claiming one address is reported, not thrown");
    }

    @Test
    void reservingAnAddressOutsideTheSubnetRangeRefusesWithoutThrowing() {
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-a", "10.0.1.0/24");

        assertFalse(manager.reservePrivateIp(REGION, "subnet-a", "172.31.4.9"),
                "an address from a range this subnet no longer uses cannot be reserved");
        assertFalse(manager.reservePrivateIp(REGION, "subnet-a", "not-an-address"));
        assertFalse(manager.reservePrivateIp(REGION, "subnet-unknown", "10.0.1.10"),
                "a subnet with no Docker-backed range has nothing to reserve in");
        // None of that poisoned the pool.
        assertTrue(manager.allocatePrivateIp(REGION, "subnet-a").isPresent());
    }

    @Test
    void aReservedAddressComesBackAfterRelease() {
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-tiny", "10.0.9.0/28");

        assertTrue(manager.reservePrivateIp(REGION, "subnet-tiny", "10.0.9.10"));
        manager.releasePrivateIp(REGION, "subnet-tiny", "10.0.9.10");

        List<String> allocated = new ArrayList<>();
        Optional<String> next;
        while ((next = manager.allocatePrivateIp(REGION, "subnet-tiny")).isPresent()) {
            allocated.add(next.get());
        }
        assertTrue(allocated.contains("10.0.9.10"),
                "a terminated restored instance's address must return to circulation");
    }

    @Test
    void subnetsInTheSameVpcGetDisjointRangesOnOneNetwork() {
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-a", "10.0.1.0/24");
        manager.declareSubnet(REGION, "vpc-1", "subnet-b", "10.0.2.0/24");

        assertEquals("10.0.1.0/24", manager.effectiveSubnetCidr(REGION, "subnet-a").orElseThrow());
        assertEquals("10.0.2.0/24", manager.effectiveSubnetCidr(REGION, "subnet-b").orElseThrow());
        // Same VPC, so the same Docker network: subnets inside a VPC route to each other in AWS.
        assertEquals(manager.networkNameFor(REGION, "vpc-1"), manager.networkNameFor(REGION, "vpc-1"));
    }

    // ─── The three substitution triggers ─────────────────────────────────────

    @Test
    void substitutesWhenTheDeclaredCidrIsOutsideRfc1918() {
        manager.declareVpc(REGION, "vpc-public", "54.0.0.0/16");
        assertTrue(manager.isSubstituted(REGION, "vpc-public"));
        Cidr4 effective = Cidr4.parse(manager.effectiveVpcCidr(REGION, "vpc-public").orElseThrow()).orElseThrow();
        assertTrue(effective.isRfc1918());
        assertTrue(Cidr4.parse("10.240.0.0/12").orElseThrow().contains(effective));
    }

    @Test
    void substitutesWhenNoCidrIsDeclared() {
        manager.declareVpc(REGION, "vpc-blank", null);
        assertTrue(manager.isSubstituted(REGION, "vpc-blank"));
        assertTrue(manager.effectiveVpcCidr(REGION, "vpc-blank").isPresent());
    }

    @Test
    void substitutesWhenTheDeclaredCidrCollidesWithAnExistingDockerNetwork() {
        // OrbStack's default bridge on this machine.
        existingNetwork("orbstack", "192.168.215.0/24", Map.of());

        manager.declareVpc(REGION, "vpc-clash", "192.168.215.0/24");

        assertTrue(manager.isSubstituted(REGION, "vpc-clash"));
        Cidr4 effective = Cidr4.parse(manager.effectiveVpcCidr(REGION, "vpc-clash").orElseThrow()).orElseThrow();
        assertFalse(effective.overlaps(Cidr4.parse("192.168.215.0/24").orElseThrow()));
    }

    @Test
    void twoVpcsMayDeclareTheSameCidrAndBothKeepWorking() {
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-1", "10.0.1.0/24");
        manager.declareVpc(REGION, "vpc-2", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-2", "subnet-2", "10.0.1.0/24");

        assertFalse(manager.isSubstituted(REGION, "vpc-1"), "the first claim keeps the declared range");
        assertTrue(manager.isSubstituted(REGION, "vpc-2"), "the second is substituted, not rejected");

        Cidr4 first = Cidr4.parse(manager.effectiveVpcCidr(REGION, "vpc-1").orElseThrow()).orElseThrow();
        Cidr4 second = Cidr4.parse(manager.effectiveVpcCidr(REGION, "vpc-2").orElseThrow()).orElseThrow();
        assertFalse(first.overlaps(second));

        // Both still allocate: a duplicate CIDR must not leave a VPC unusable.
        String a = manager.allocatePrivateIp(REGION, "subnet-1").orElseThrow();
        String b = manager.allocatePrivateIp(REGION, "subnet-2").orElseThrow();
        assertTrue(first.containsAddress(Cidr4.parse(a + "/32").orElseThrow().network()));
        assertTrue(second.containsAddress(Cidr4.parse(b + "/32").orElseThrow().network()));
    }

    @Test
    void aSubnetOutsideItsVpcRangeIsRemappedInsideIt() {
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-stray", "172.16.5.0/24");

        Cidr4 effective = Cidr4.parse(manager.effectiveSubnetCidr(REGION, "subnet-stray").orElseThrow()).orElseThrow();
        assertTrue(Cidr4.parse("10.0.0.0/16").orElseThrow().contains(effective),
                "an address must be routable on the VPC's own network to mean anything");
    }

    // ─── Materialisation and attachment ──────────────────────────────────────

    @Test
    void createsTheNetworkWithTheEffectiveCidrOnFirstAttach() {
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-a", "10.0.1.0/24");
        String address = manager.allocatePrivateIp(REGION, "subnet-a").orElseThrow();

        assertTrue(manager.attach(REGION, "vpc-1", "subnet-a", "container-1", address).isPresent());

        ArgumentCaptor<Network.Ipam> ipam = ArgumentCaptor.forClass(Network.Ipam.class);
        verify(docker.createNetworkCmd()).withIpam(ipam.capture());
        assertEquals("10.0.0.0/16", ipam.getValue().getConfig().get(0).getSubnet());
        verify(docker.connectToNetworkCmd()).withContainerId("container-1");
    }

    @Test
    void refusesToAttachAnAddressItDidNotPlan() {
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-a", "10.0.1.0/24");

        // The synthesised address Ec2Service falls back to: outside the network's IPAM pool.
        assertTrue(manager.attach(REGION, "vpc-1", "subnet-a", "container-1", "172.31.0.11").isEmpty());
        verify(docker, never()).connectToNetworkCmd();
    }

    @Test
    void adoptsALeftoverNetworkOnlyWhenItsSubnetIsTheOneThisVpcAllocatesFrom() {
        // A Floci restart: the bridge for vpc-1 survived, on the range vpc-1 still plans from.
        existingNetwork(manager.networkName(REGION, "vpc-1"), "10.0.0.0/16", ourLabels("vpc-1", "4650"));
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-a", "10.0.1.0/24");
        String address = manager.allocatePrivateIp(REGION, "subnet-a").orElseThrow();

        assertTrue(manager.attach(REGION, "vpc-1", "subnet-a", "container-1", address).isPresent());

        verify(docker, never()).createNetworkCmd();
        assertTrue(removedNetworks.isEmpty(), "a matching network is reused, not churned");
    }

    @Test
    void recreatesALeftoverNetworkWhoseSubnetIsNotTheRangeThisVpcAllocatesFrom() {
        // What a previous run with a different declaration leaves behind: this VPC's own network
        // name, carrying a range that has nothing to do with the addresses now being handed out.
        String networkName = manager.networkName(REGION, "vpc-1");
        existingNetwork(networkName, "172.20.0.0/16", ourLabels("vpc-1", "4650"));

        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-a", "10.0.1.0/24");
        String address = manager.allocatePrivateIp(REGION, "subnet-a").orElseThrow();
        assertTrue(Cidr4.parse("10.0.1.0/24").orElseThrow()
                .containsAddress(Cidr4.parse(address + "/32").orElseThrow().network()));

        assertTrue(manager.attach(REGION, "vpc-1", "subnet-a", "container-1", address).isPresent());

        assertEquals(List.of(networkName), removedNetworks,
                "a network routing 172.20/16 cannot carry a 10.0.1.x address, so it is not adopted");
        ArgumentCaptor<Network.Ipam> ipam = ArgumentCaptor.forClass(Network.Ipam.class);
        verify(docker.createNetworkCmd()).withIpam(ipam.capture());
        assertEquals("10.0.0.0/16", ipam.getValue().getConfig().get(0).getSubnet(),
                "the network is recreated on the range the addresses actually come from");
    }

    @Test
    void keepsInstancesOnTheBridgeWhenAStaleNetworkCannotBeRemoved() {
        String networkName = manager.networkName(REGION, "vpc-1");
        existingNetwork(networkName, "172.20.0.0/16", ourLabels("vpc-1", "4650"));
        when(docker.removeNetworkCmd(anyString())).thenThrow(new RuntimeException("has active endpoints"));

        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-a", "10.0.1.0/24");
        String address = manager.allocatePrivateIp(REGION, "subnet-a").orElseThrow();

        assertTrue(manager.attach(REGION, "vpc-1", "subnet-a", "container-1", address).isEmpty(),
                "an unreachable private IP is a documented degradation; an address the network "
                        + "does not route is a wrong answer");
        verify(docker, never()).connectToNetworkCmd();
    }

    // ─── Small blocks: usable, or refused out loud ───────────────────────────

    @Test
    void aSubnetTooSmallForTenAddressesOfHeadroomStillAllocates() {
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        // A /29 holds eight addresses in total, so the usual .10 offset falls off the end of it.
        manager.declareSubnet(REGION, "vpc-1", "subnet-tiny", "10.0.9.0/29");

        assertEquals("10.0.9.0/29", manager.effectiveSubnetCidr(REGION, "subnet-tiny").orElseThrow());
        List<String> allocated = new ArrayList<>();
        Optional<String> next;
        while ((next = manager.allocatePrivateIp(REGION, "subnet-tiny")).isPresent()) {
            allocated.add(next.get());
        }
        assertEquals(List.of("10.0.9.2", "10.0.9.3", "10.0.9.4", "10.0.9.5", "10.0.9.6"), allocated,
                "past the network address and the Docker gateway, and short of the broadcast address");
    }

    @Test
    void aSubnetWithNoRoomForAnyHostIsRefusedRatherThanAllocatingNothing() {
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-nothing", "10.0.9.0/31");

        assertTrue(manager.effectiveSubnetCidr(REGION, "subnet-nothing").isEmpty(),
                "a subnet that can never hand out an address must not claim a Docker-backed range: "
                        + "the caller has to fall back rather than read empty as exhaustion");
        assertTrue(manager.allocatePrivateIp(REGION, "subnet-nothing").isEmpty());
    }

    @Test
    void anUnusableFallbackPrefixLengthIsClampedToOneThatCanServeHosts() {
        // Nothing bounds this config value, and a /30 block has no room for an instance at all.
        when(config.services().ec2().vpcNetworks().fallbackPrefixLength()).thenReturn(30);

        manager.declareVpc(REGION, "vpc-public", "54.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-public", "subnet-a", "54.0.1.0/24");

        Cidr4 effective = Cidr4.parse(manager.effectiveVpcCidr(REGION, "vpc-public").orElseThrow()).orElseThrow();
        assertTrue(effective.prefix() <= VpcNetworkManager.MAX_ALLOCATABLE_PREFIX,
                "a configured-but-unusable prefix must not leave every substituted VPC empty");
        assertTrue(manager.allocatePrivateIp(REGION, "subnet-a").isPresent(),
                "the substituted VPC has to be able to hand out an address");
    }

    @Test
    void doesNothingWhenDisabled() {
        when(config.services().ec2().vpcNetworks().enabled()).thenReturn(false);
        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");
        manager.declareSubnet(REGION, "vpc-1", "subnet-a", "10.0.1.0/24");
        assertTrue(manager.allocatePrivateIp(REGION, "subnet-a").isEmpty());
        assertTrue(manager.effectiveVpcCidr(REGION, "vpc-1").isEmpty());
    }

    // ─── Startup reconciliation ──────────────────────────────────────────────

    @Test
    void reconcileRemovesOnlyThisEmulatorsOwnOrphans() {
        existingNetwork("floci-vpc-4650-us-east-1-vpc-dead", "10.7.0.0/16", ourLabels("vpc-dead", "4650"));
        existingNetwork("floci-vpc-4650-us-east-1-vpc-live", "10.8.0.0/16", ourLabels("vpc-live", "4650"));
        existingNetwork("floci-vpc-4620-us-east-1-vpc-other", "10.9.0.0/16", ourLabels("vpc-other", "4620"));
        existingNetwork("some-users-network", "10.10.0.0/16", Map.of());

        manager.reconcileOrphans((region, vpcId) -> "vpc-live".equals(vpcId));

        assertEquals(List.of("floci-vpc-4650-us-east-1-vpc-dead"), removedNetworks,
                "a live VPC keeps its network, another emulator's networks are never touched, "
                        + "and networks Floci did not create are out of scope entirely");
    }

    @Test
    void reconcileDetachesAContainerTheCrashedRunLeftOnTheNetwork() {
        Map<String, Network.ContainerNetworkConfig> attached =
                Map.of("container-from-a-dead-run", new Network.ContainerNetworkConfig());
        existingNetwork("floci-vpc-4650-us-east-1-vpc-dead", "10.7.0.0/16",
                ourLabels("vpc-dead", "4650"), attached);

        manager.reconcileOrphans((region, vpcId) -> false);

        // Without the disconnect Docker refuses removal with "has active endpoints", and the
        // stale IPAM reservation then pushes the next run's identical CIDR into the fallback pool.
        verify(docker).disconnectFromNetworkCmd();
        assertEquals(List.of("floci-vpc-4650-us-east-1-vpc-dead"), removedNetworks);
    }

    @Test
    void reconcileIsSkippedWhenTurnedOff() {
        when(config.services().ec2().vpcNetworks().reconcileOnStartup()).thenReturn(false);
        existingNetwork("floci-vpc-4650-us-east-1-vpc-dead", "10.7.0.0/16", ourLabels("vpc-dead", "4650"));
        manager.reconcileOrphans((region, vpcId) -> false);
        assertTrue(removedNetworks.isEmpty());
    }

    @Test
    void aSurvivingNetworkForTheSameVpcIsNotReadAsACollision() {
        // What a Floci restart sees: its own bridge for vpc-1 is still up.
        existingNetwork(manager.networkName(REGION, "vpc-1"), "10.0.0.0/16", ourLabels("vpc-1", "4650"));

        manager.declareVpc(REGION, "vpc-1", "10.0.0.0/16");

        assertFalse(manager.isSubstituted(REGION, "vpc-1"),
                "re-planning a VPC must not collide with that VPC's own surviving network");
        assertEquals("10.0.0.0/16", manager.effectiveVpcCidr(REGION, "vpc-1").orElseThrow());
    }

    private static Map<String, String> ourLabels(String vpcId, String ownerPort) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(VpcNetworkManager.LABEL_COMPONENT, VpcNetworkManager.COMPONENT_VALUE);
        labels.put(VpcNetworkManager.LABEL_VPC_ID, vpcId);
        labels.put(VpcNetworkManager.LABEL_VPC_REGION, REGION);
        labels.put(VpcNetworkManager.LABEL_OWNER_PORT, ownerPort);
        return labels;
    }

    @Test
    void subBlockAllocationSkipsTakenRanges() {
        Cidr4 parent = Cidr4.parse("10.0.0.0/16").orElseThrow();
        Cidr4 taken = Cidr4.parse("10.0.0.0/24").orElseThrow();
        assertEquals("10.0.1.0/24",
                VpcNetworkManager.allocateSubBlock(parent, 24, List.of(taken)).toString());
        assertEquals(null,
                VpcNetworkManager.allocateSubBlock(parent, 16, List.of(parent)),
                "a fully claimed parent yields nothing rather than an overlapping block");
    }
}
