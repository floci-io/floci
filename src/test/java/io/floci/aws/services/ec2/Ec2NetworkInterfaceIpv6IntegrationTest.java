package io.floci.aws.services.ec2;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AttributeBooleanValue;
import software.amazon.awssdk.services.ec2.model.CreateNetworkInterfaceRequest;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.InstanceIpv6Address;
import software.amazon.awssdk.services.ec2.model.InstanceNetworkInterfaceSpecification;
import software.amazon.awssdk.services.ec2.model.NetworkInterface;
import software.amazon.awssdk.services.ec2.model.NetworkInterfaceIpv6Address;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** IPv6 addresses on network interfaces, driven through the SDK so the wire names are the SDK's own. */
@QuarkusTest
class Ec2NetworkInterfaceIpv6IntegrationTest {
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    // Catches: an Ipv6AddressCount that assigns nothing, or addresses outside the subnet's IPv6 block.
    @Test
    void aCountDrawsDistinctAddressesFromTheSubnetBlock() throws UnknownHostException {
        Fixture fixture = fixture();

        NetworkInterface created = fixture.client().createNetworkInterface(request -> request
                .subnetId(fixture.subnet()).ipv6AddressCount(2)).networkInterface();
        cleanup.register(() -> fixture.client().deleteNetworkInterface(request -> request
                .networkInterfaceId(created.networkInterfaceId())));

        List<String> addresses = addresses(created);
        assertEquals(2, addresses.size());
        assertNotEquals(addresses.get(0), addresses.get(1));
        for (String address : addresses) {
            assertTrue(inSubnet(fixture, address), address);
        }
        assertEquals(addresses, addresses(describe(fixture, created.networkInterfaceId())));
    }

    // Catches: an explicit Ipv6AddressCount of 0 read as "not given", so the subnet's AssignIpv6AddressOnCreation
    // still hands out an address, where the API reference says 0 overrides it.
    @Test
    void anExplicitZeroOverridesTheSubnetsAssignOnCreation() {
        Fixture fixture = fixture();
        fixture.client().modifySubnetAttribute(request -> request.subnetId(fixture.subnet())
                .assignIpv6AddressOnCreation(AttributeBooleanValue.builder().value(true).build()));

        String defaulted = create(fixture, request -> { });
        String zero = create(fixture, request -> request.ipv6AddressCount(0));

        assertEquals(1, addresses(describe(fixture, defaulted)).size());
        assertEquals(List.of(), addresses(describe(fixture, zero)));
    }

    // Catches: AssignIpv6Addresses reporting addresses the interface already held, or UnassignIpv6Addresses
    // reporting ones it never held; each reports only what the call changed.
    @Test
    void assignAndUnassignReportOnlyWhatTheyChanged() {
        Fixture fixture = fixture();
        String eni = create(fixture, request -> { });
        String named = fixture.prefix() + "abcd";

        assertEquals(List.of(named), fixture.client().assignIpv6Addresses(request -> request
                .networkInterfaceId(eni).ipv6Addresses(named)).assignedIpv6Addresses());
        assertEquals(List.of(), fixture.client().assignIpv6Addresses(request -> request
                .networkInterfaceId(eni).ipv6Addresses(named)).assignedIpv6Addresses());
        List<String> drawn = fixture.client().assignIpv6Addresses(request -> request
                .networkInterfaceId(eni).ipv6AddressCount(1)).assignedIpv6Addresses();
        assertEquals(1, drawn.size());
        assertEquals(List.of(named, drawn.getFirst()), addresses(describe(fixture, eni)));

        assertEquals(List.of(named), fixture.client().unassignIpv6Addresses(request -> request
                .networkInterfaceId(eni).ipv6Addresses(named, fixture.prefix() + "ffff")).unassignedIpv6Addresses());
        assertEquals(drawn, addresses(describe(fixture, eni)));
    }

    @Test
    void assignNeedsEitherAListOrACount() {
        Fixture fixture = fixture();
        String eni = create(fixture, request -> { });

        Ec2Exception neither = assertThrows(Ec2Exception.class, () -> fixture.client()
                .assignIpv6Addresses(request -> request.networkInterfaceId(eni)));
        Ec2Exception both = assertThrows(Ec2Exception.class, () -> fixture.client()
                .assignIpv6Addresses(request -> request.networkInterfaceId(eni)
                        .ipv6Addresses(fixture.prefix() + "1234").ipv6AddressCount(1)));

        Ec2Exception listAndZero = assertThrows(Ec2Exception.class, () -> fixture.client()
                .assignIpv6Addresses(request -> request.networkInterfaceId(eni)
                        .ipv6Addresses(fixture.prefix() + "1234").ipv6AddressCount(0)));
        Ec2Exception createListAndZero = assertThrows(Ec2Exception.class, () -> fixture.client()
                .createNetworkInterface(request -> request.subnetId(fixture.subnet()).ipv6AddressCount(0)
                        .ipv6Addresses(InstanceIpv6Address.builder().ipv6Address(fixture.prefix() + "1234").build())));

        assertEquals("MissingParameter", neither.awsErrorDetails().errorCode());
        for (Ec2Exception error : List.of(both, listAndZero, createListAndZero)) {
            assertEquals("InvalidParameterCombination", error.awsErrorDetails().errorCode());
        }
        assertEquals(List.of(), addresses(describe(fixture, eni)));
    }

    // Catches: a count no interface could hold sizing a list or looping under the region's locks; an interface
    // holds at most as many IPv6 addresses as private IPv4 ones.
    @Test
    void aCountBeyondWhatAnInterfaceHoldsIsRefused() {
        Fixture fixture = fixture();
        String eni = create(fixture, request -> { });

        Ec2Exception error = assertThrows(Ec2Exception.class, () -> fixture.client()
                .assignIpv6Addresses(request -> request.networkInterfaceId(eni).ipv6AddressCount(100000)));

        assertEquals("InvalidParameterValue", error.awsErrorDetails().errorCode());
        assertEquals(List.of(), addresses(describe(fixture, eni)));
    }

    // Catches: a count larger than a small block's free hosts looping forever, where it should be refused.
    @Test
    void aBlockWithoutEnoughFreeAddressesRefusesTheCount() {
        Fixture fixture = fixture(124);
        // A /124 has sixteen hosts, five of them reserved.
        String full = create(fixture, request -> request.ipv6AddressCount(11));

        Ec2Exception error = assertThrows(Ec2Exception.class, () -> fixture.client()
                .createNetworkInterface(request -> request.subnetId(fixture.subnet()).ipv6AddressCount(1)));

        assertEquals(11, addresses(describe(fixture, full)).size());
        assertEquals("InsufficientFreeAddressesInSubnet", error.awsErrorDetails().errorCode());
    }

    // Catches: a named address the subnet reserves (its first four and its last) being assigned, where the
    // allocator never hands one out.
    @Test
    void aReservedAddressIsRefused() {
        Fixture fixture = fixture();
        String eni = create(fixture, request -> { });

        for (String reserved : List.of(fixture.network(), fixture.prefix() + "1", fixture.prefix() + "3")) {
            Ec2Exception error = assertThrows(Ec2Exception.class, () -> fixture.client()
                    .assignIpv6Addresses(request -> request.networkInterfaceId(eni).ipv6Addresses(reserved)));
            assertEquals("InvalidParameterValue", error.awsErrorDetails().errorCode(), reserved);
        }
        assertEquals(List.of(), addresses(describe(fixture, eni)));
    }

    // Catches: a supplied interface handed to RunInstances keeping its IPv6 addresses on its own record while the
    // instance's copy, which DescribeInstances reports, has none.
    @Test
    void aSuppliedInterfaceKeepsItsAddressesOnTheInstance() {
        Fixture fixture = fixture();
        String eni = create(fixture, request -> request.ipv6AddressCount(1));
        String token = UUID.randomUUID().toString();
        cleanup.register(() -> fixture.client().describeInstances(request -> request.filters(
                Filter.builder().name("client-token").values(token).build())).reservations().forEach(reservation ->
                        reservation.instances().forEach(instance -> fixture.client().terminateInstances(
                                request -> request.instanceIds(instance.instanceId())))));

        String instance = fixture.client().runInstances(request -> request.imageId("ami-amazonlinux2023")
                .instanceType("t2.micro").minCount(1).maxCount(1).clientToken(token)
                .networkInterfaces(InstanceNetworkInterfaceSpecification.builder().networkInterfaceId(eni)
                        .deviceIndex(0).build())).instances().getFirst().instanceId();

        assertEquals(addresses(describe(fixture, eni)), fixture.client().describeInstances(request -> request
                .instanceIds(instance)).reservations().getFirst().instances().getFirst().networkInterfaces()
                .getFirst().ipv6Addresses().stream().map(InstanceIpv6Address::ipv6Address).toList());
    }

    @Test
    void anAddressOutsideTheSubnetOrHeldElsewhereIsRefused() {
        Fixture fixture = fixture();
        String first = create(fixture, request -> request.ipv6Addresses(InstanceIpv6Address.builder()
                .ipv6Address(fixture.prefix() + "beef").build()));
        String second = create(fixture, request -> { });

        Ec2Exception outside = assertThrows(Ec2Exception.class, () -> fixture.client()
                .assignIpv6Addresses(request -> request.networkInterfaceId(second).ipv6Addresses("2001:db8::1")));
        Ec2Exception inUse = assertThrows(Ec2Exception.class, () -> fixture.client()
                .assignIpv6Addresses(request -> request.networkInterfaceId(second)
                        .ipv6Addresses(fixture.prefix() + "beef")));

        assertEquals("InvalidParameterValue", outside.awsErrorDetails().errorCode());
        assertEquals("InvalidIPAddress.InUse", inUse.awsErrorDetails().errorCode());
        assertEquals(List.of(fixture.prefix() + "beef"), addresses(describe(fixture, first)));
        assertEquals(List.of(), addresses(describe(fixture, second)));
    }

    // Catches: an instance's launch-created primary interface, which has no standalone record, answering
    // InvalidNetworkInterfaceID.NotFound, or taking addresses that DescribeInstances does not report.
    @Test
    void theLaunchCreatedPrimaryInterfaceTakesAddresses() {
        Fixture fixture = fixture();
        String token = UUID.randomUUID().toString();
        cleanup.register(() -> fixture.client().describeInstances(request -> request.filters(
                Filter.builder().name("client-token").values(token).build())).reservations().forEach(reservation ->
                        reservation.instances().forEach(instance -> fixture.client().terminateInstances(
                                request -> request.instanceIds(instance.instanceId())))));
        String instance = fixture.client().runInstances(request -> request.imageId("ami-amazonlinux2023")
                .instanceType("t2.micro").minCount(1).maxCount(1).subnetId(fixture.subnet()).clientToken(token))
                .instances().getFirst().instanceId();
        String primary = fixture.client().describeInstances(request -> request.instanceIds(instance))
                .reservations().getFirst().instances().getFirst().networkInterfaces().getFirst().networkInterfaceId();

        List<String> assigned = fixture.client().assignIpv6Addresses(request -> request.networkInterfaceId(primary)
                .ipv6AddressCount(1)).assignedIpv6Addresses();

        assertEquals(assigned, addresses(describe(fixture, primary)));
        assertEquals(assigned, fixture.client().describeInstances(request -> request.instanceIds(instance))
                .reservations().getFirst().instances().getFirst().networkInterfaces().getFirst().ipv6Addresses()
                .stream().map(InstanceIpv6Address::ipv6Address).toList());
    }

    @Test
    void prefixDelegationIsRefused() {
        Fixture fixture = fixture();
        String eni = create(fixture, request -> { });

        Ec2Exception error = assertThrows(Ec2Exception.class, () -> fixture.client()
                .assignIpv6Addresses(request -> request.networkInterfaceId(eni).ipv6PrefixCount(1)));

        assertEquals("UnsupportedOperation", error.awsErrorDetails().errorCode());
    }

    private Fixture fixture() {
        return fixture(64);
    }

    private Fixture fixture(int ipv6PrefixLength) {
        Ec2Client client = AwsTestClients.create(Ec2Client.builder(), "555566667790", Region.US_WEST_2);
        cleanup.register(client::close);
        String vpc = client.createVpc(request -> request.cidrBlock("10.233.0.0/16").amazonProvidedIpv6CidrBlock(true))
                .vpc().vpcId();
        cleanup.register(() -> client.deleteVpc(request -> request.vpcId(vpc)));
        String vpcBlock = client.describeVpcs(request -> request.vpcIds(vpc)).vpcs().getFirst()
                .ipv6CidrBlockAssociationSet().getFirst().ipv6CidrBlock();
        // The /56's network address is also the network address of its first smaller block.
        String subnetBlock = vpcBlock.substring(0, vpcBlock.indexOf('/')) + "/" + ipv6PrefixLength;
        String subnet = client.createSubnet(request -> request.vpcId(vpc).cidrBlock("10.233.0.0/24")
                .ipv6CidrBlock(subnetBlock)).subnet().subnetId();
        cleanup.register(() -> client.deleteSubnet(request -> request.subnetId(subnet)));
        String network = subnetBlock.substring(0, subnetBlock.indexOf('/'));
        return new Fixture(client, subnet, network, network.substring(0, network.indexOf("::") + 2));
    }

    private String create(Fixture fixture, Consumer<CreateNetworkInterfaceRequest.Builder> extra) {
        String eni = fixture.client().createNetworkInterface(request -> {
            request.subnetId(fixture.subnet());
            extra.accept(request);
        }).networkInterface().networkInterfaceId();
        cleanup.register(() -> fixture.client().deleteNetworkInterface(request -> request.networkInterfaceId(eni)));
        return eni;
    }

    private static NetworkInterface describe(Fixture fixture, String eni) {
        return fixture.client().describeNetworkInterfaces(request -> request.networkInterfaceIds(eni))
                .networkInterfaces().getFirst();
    }

    /** Whether {@code address} shares the subnet's /64 network part. */
    private static boolean inSubnet(Fixture fixture, String address) throws UnknownHostException {
        byte[] candidate = InetAddress.getByName(address).getAddress();
        byte[] network = InetAddress.getByName(fixture.network()).getAddress();
        return Arrays.equals(candidate, 0, 8, network, 0, 8);
    }

    private static List<String> addresses(NetworkInterface ni) {
        return ni.ipv6Addresses().stream().map(NetworkInterfaceIpv6Address::ipv6Address).toList();
    }

    /**
     * {@code network} is the subnet's /64 network address; {@code prefix} is it written up to and including
     * {@code ::}, so a short host part can follow it.
     */
    private record Fixture(Ec2Client client, String subnet, String network, String prefix) { }
}
