package io.floci.aws.services.ec2;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.InstanceIpv6Address;
import software.amazon.awssdk.services.ec2.model.NetworkInterface;

import java.math.BigInteger;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The original launch allocator properties, exercised through the shared SDK allocation path. */
@QuarkusTest
class Ec2LaunchIpv6AddressTest {

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void addressesStayInsideTheBlockOutsideTheReservesAndAreCanonical() throws Exception {
        Fixture fixture = fixture(64);
        BigInteger network = new BigInteger(1, InetAddress.getByName(fixture.network()).getAddress());
        BigInteger last = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String address = allocate(fixture).ipv6Addresses().getFirst().ipv6Address();
            BigInteger value = new BigInteger(1, InetAddress.getByName(address).getAddress());
            BigInteger host = value.subtract(network);
            assertEquals(network, value.shiftRight(64).shiftLeft(64), address);
            assertTrue(host.compareTo(BigInteger.valueOf(4)) >= 0, address);
            assertTrue(host.compareTo(last) < 0, address);
            assertEquals(address.toLowerCase(Locale.ROOT), address);
            assertFalse(address.matches(".*:0[0-9a-f]+(:|$).*"), "leading zero in " + address);
            assertTrue(seen.add(address), "handed out twice: " + address);
        }
    }

    @Test
    void anAddressAlreadyHeldIsNeverReturned() {
        Fixture fixture = fixture(64);
        String first = allocate(fixture).ipv6Addresses().getFirst().ipv6Address();
        String uppercase = first.toUpperCase(Locale.ROOT);
        Ec2Exception duplicate = assertThrows(Ec2Exception.class, () -> fixture.client()
                .createNetworkInterface(request -> request.subnetId(fixture.subnet())
                        .ipv6Addresses(InstanceIpv6Address.builder().ipv6Address(uppercase).build())));
        assertEquals("InvalidIPAddress.InUse", duplicate.awsErrorDetails().errorCode());
        for (int i = 0; i < 100; i++) {
            String address = allocate(fixture).ipv6Addresses().getFirst().ipv6Address();
            assertFalse(first.equals(address), "held address handed out again: " + address);
        }
    }

    @Test
    void aSmallerBlockKeepsTheLastFreeHostAndRefusesReservedOrExhaustedAddresses() throws Exception {
        Fixture fixture = fixture(124);
        NetworkInterface held = fixture.client().createNetworkInterface(request -> request
                .subnetId(fixture.subnet()).ipv6AddressCount(10)).networkInterface();
        cleanup.register(() -> fixture.client().deleteNetworkInterface(request -> request
                .networkInterfaceId(held.networkInterfaceId())));
        Set<String> seen = new HashSet<>(held.ipv6Addresses().stream()
                .map(address -> address.ipv6Address()).toList());
        BigInteger network = new BigInteger(1, InetAddress.getByName(fixture.network()).getAddress());
        assertEquals(10, seen.size());
        for (String address : seen) {
            BigInteger host = new BigInteger(1, InetAddress.getByName(address).getAddress()).subtract(network);
            assertTrue(host.compareTo(BigInteger.valueOf(4)) >= 0, address);
            assertTrue(host.compareTo(BigInteger.valueOf(14)) < 0, address);
        }

        String lastFree = allocate(fixture).ipv6Addresses().getFirst().ipv6Address();
        assertEquals(network.add(BigInteger.valueOf(14)),
                new BigInteger(1, InetAddress.getByName(lastFree).getAddress()));
        assertTrue(seen.add(lastFree), "held address handed out twice: " + lastFree);
        String reserved = fixture.network() + "f";
        Ec2Exception reservedError = assertThrows(Ec2Exception.class, () -> fixture.client()
                .createNetworkInterface(request -> request.subnetId(fixture.subnet())
                        .ipv6Addresses(InstanceIpv6Address.builder().ipv6Address(reserved).build())));
        assertEquals("InvalidParameterValue", reservedError.awsErrorDetails().errorCode());
        Ec2Exception exhausted = assertThrows(Ec2Exception.class, () -> allocate(fixture));
        assertEquals("InsufficientFreeAddressesInSubnet", exhausted.awsErrorDetails().errorCode());
        assertEquals(11, seen.size());
    }

    private NetworkInterface allocate(Fixture fixture) {
        NetworkInterface created = fixture.client().createNetworkInterface(request -> request
                .subnetId(fixture.subnet()).ipv6AddressCount(1)).networkInterface();
        cleanup.register(() -> fixture.client().deleteNetworkInterface(request -> request
                .networkInterfaceId(created.networkInterfaceId())));
        assertEquals(1, created.ipv6Addresses().size());
        return created;
    }

    private Fixture fixture(int prefixLength) {
        Ec2Client client = AwsTestClients.create(Ec2Client.builder(), "555566667791", Region.US_WEST_2);
        cleanup.register(client::close);
        String vpc = client.createVpc(request -> request.cidrBlock("10.234.0.0/16")
                .amazonProvidedIpv6CidrBlock(true)).vpc().vpcId();
        cleanup.register(() -> client.deleteVpc(request -> request.vpcId(vpc)));
        String block = client.describeVpcs(request -> request.vpcIds(vpc)).vpcs().getFirst()
                .ipv6CidrBlockAssociationSet().getFirst().ipv6CidrBlock();
        String network = block.substring(0, block.indexOf('/'));
        String subnet = client.createSubnet(request -> request.vpcId(vpc).cidrBlock("10.234.0.0/24")
                .ipv6CidrBlock(network + "/" + prefixLength)).subnet().subnetId();
        cleanup.register(() -> client.deleteSubnet(request -> request.subnetId(subnet)));
        return new Fixture(client, subnet, network);
    }

    private record Fixture(Ec2Client client, String subnet, String network) { }
}
