package io.floci.aws.services.ec2;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceNetworkInterface;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.NetworkInterface;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

@QuarkusTest
class Ec2LaunchIpv6MetadataIntegrationTest {

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Inject
    Ec2Service service;

    @Test
    void implicitLaunchMacIdentifiesTheSameIpv6MetadataAfterDescribe() throws Exception {
        Fixture fixture = fixture("555566668101", true, 64);
        List<String> ids = launch(fixture, 2);
        Instance first = service.findInstanceById(fixture.account(), ids.getFirst());
        Instance second = service.findInstanceById(fixture.account(), ids.get(1));
        String mac = first.getNetworkInterfaces().getFirst().getMacAddress();
        assertNotNull(mac);
        assertNotEquals(mac, second.getNetworkInterfaces().getFirst().getMacAddress());
        String describedMac = fixture.client().describeInstances(request -> request.instanceIds(ids.getFirst()))
                .reservations().getFirst().instances().getFirst().networkInterfaces().getFirst().macAddress();
        assertEquals(mac, describedMac);
        assertEquals(mac + "/", Ec2MetadataServer.macListing(first));
        assertMetadata(fixture, first, mac);
        assertNotEquals(Ec2MetadataServer.primaryIpv6(first), Ec2MetadataServer.primaryIpv6(second));
    }

    @Test
    void aLaterAssignmentHasSubnetAndVpcBlocksWithoutAutomaticAssignment() throws Exception {
        Fixture fixture = fixture("555566668102", false, 64);
        String id = launch(fixture, 1).getFirst();
        Instance before = service.findInstanceById(fixture.account(), id);
        InstanceNetworkInterface eni = before.getNetworkInterfaces().getFirst();
        assertTrue(eni.getIpv6Addresses().isEmpty());
        // The stored blocks are independent of a MAC, so this catches the block gap on its own.
        assertEquals(List.of(fixture.subnetBlock()), eni.getSubnetIpv6CidrBlocks());
        assertEquals(List.of(fixture.vpcBlock()), eni.getVpcIpv6CidrBlocks());
        fixture.client().assignIpv6Addresses(request -> request.networkInterfaceId(eni.getNetworkInterfaceId())
                .ipv6AddressCount(1));
        Instance after = service.findInstanceById(fixture.account(), id);
        assertMetadata(fixture, after, after.getNetworkInterfaces().getFirst().getMacAddress());
    }

    @Test
    void exhaustedMultiInstanceLaunchLeavesNoResourcesOrIpv4Leases() {
        Fixture fixture = fixture("555566668103", true, 124);
        hold(fixture, 10);
        int available = availableIpv4(fixture);
        Ec2Exception failure = assertThrows(Ec2Exception.class, () -> launch(fixture, 2));
        assertEquals("InsufficientFreeAddressesInSubnet", failure.awsErrorDetails().errorCode());
        assertTrue(fixture.client().describeInstances().reservations().stream()
                .flatMap(reservation -> reservation.instances().stream()).findAny().isEmpty());
        assertTrue(fixture.client().describeVolumes().volumes().isEmpty());
        assertTrue(fixture.client().describeTags(request -> request.filters(Filter.builder()
                .name("key").values("ipv6-launch-review").build())).tags().isEmpty());
        assertEquals(1, fixture.client().describeNetworkInterfaces().networkInterfaces().size());
        assertEquals(available, availableIpv4(fixture));
        String id = launch(fixture, 1).getFirst();
        assertEquals(1, service.findInstanceById(fixture.account(), id).getNetworkInterfaces()
                .getFirst().getIpv6Addresses().size());
    }

    @Test
    void aBatchCanConsumeTheLastTwoDistinctIpv6Addresses() {
        Fixture fixture = fixture("555566668104", true, 124);
        hold(fixture, 9);
        List<String> ids = launch(fixture, 2);
        String first = Ec2MetadataServer.primaryIpv6(service.findInstanceById(fixture.account(), ids.getFirst()));
        String second = Ec2MetadataServer.primaryIpv6(service.findInstanceById(fixture.account(), ids.get(1)));
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first, second);
        Ec2Exception exhausted = assertThrows(Ec2Exception.class, () -> launch(fixture, 1));
        assertEquals("InsufficientFreeAddressesInSubnet", exhausted.awsErrorDetails().errorCode());
    }

    @Test
    void createCountConflictsUseSharedValidationBeforeAnyMutation() {
        Fixture fixture = fixture("555566668105", false, 64);
        int available = availableIpv4(fixture);
        Ec2Exception addresses = assertThrows(Ec2Exception.class, () -> fixture.client().createNetworkInterface(
                request -> request.subnetId(fixture.subnet()).ipv6AddressCount(1)
                        .ipv6Addresses(address -> address.ipv6Address("2001:db8::10"))));
        assertIpv6SourceConflict(addresses, "Ipv6Addresses");
        Ec2Exception prefixes = assertThrows(Ec2Exception.class, () -> fixture.client().createNetworkInterface(
                request -> request.subnetId(fixture.subnet()).ipv6AddressCount(1)
                        .ipv6Prefixes(prefix -> prefix.ipv6Prefix("2001:db8::/80"))));
        assertIpv6SourceConflict(prefixes, "Ipv6Prefix");
        Ec2Exception prefixCount = assertThrows(Ec2Exception.class, () -> fixture.client().createNetworkInterface(
                request -> request.subnetId(fixture.subnet()).ipv6AddressCount(1).ipv6PrefixCount(1)));
        assertIpv6SourceConflict(prefixCount, "Ipv6PrefixCount");
        assertTrue(fixture.client().describeNetworkInterfaces().networkInterfaces().isEmpty());
        assertEquals(available, availableIpv4(fixture));
    }

    private static void assertIpv6SourceConflict(Ec2Exception failure, String source) {
        assertEquals(400, failure.statusCode());
        assertEquals("InvalidParameterCombination", failure.awsErrorDetails().errorCode());
        assertEquals("Specify either " + source + " or Ipv6AddressCount, not both",
                failure.awsErrorDetails().errorMessage());
    }

    private void assertMetadata(Fixture fixture, Instance instance, String mac) throws Exception {
        assertEquals(Ec2MetadataServer.primaryIpv6(instance), Ec2MetadataServer.interfaceValues(
                instance, mac, InstanceNetworkInterface::getIpv6Addresses).orElseThrow());
        assertEquals(fixture.subnetBlock(), Ec2MetadataServer.interfaceValues(
                instance, mac, InstanceNetworkInterface::getSubnetIpv6CidrBlocks).orElseThrow());
        assertEquals(fixture.vpcBlock(), Ec2MetadataServer.interfaceValues(
                instance, mac, InstanceNetworkInterface::getVpcIpv6CidrBlocks).orElseThrow());
        try (Ec2MetadataTestFixture metadata = new Ec2MetadataTestFixture()) {
            metadata.start(null, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), instance);
            HttpResponse<String> token = metadata.putToken("60");
            assertEquals(200, token.statusCode());
            String prefix = "/latest/meta-data/network/interfaces/macs/";
            for (Map.Entry<String, String> expected : Map.of(
                    "/latest/meta-data/mac", mac,
                    prefix, mac + "/",
                    prefix + mac + "/ipv6s", Ec2MetadataServer.primaryIpv6(instance),
                    prefix + mac + "/subnet-ipv6-cidr-blocks", fixture.subnetBlock(),
                    prefix + mac + "/vpc-ipv6-cidr-blocks", fixture.vpcBlock()).entrySet()) {
                HttpResponse<String> response = metadata.get(expected.getKey(), token.body());
                assertEquals(200, response.statusCode(), expected.getKey());
                assertEquals(expected.getValue(), response.body(), expected.getKey());
            }
        }
    }

    private List<String> launch(Fixture fixture, int count) {
        return fixture.client().runInstances(request -> request.imageId("ami-ipv6-review")
                .instanceType("t3.micro").minCount(count).maxCount(count).subnetId(fixture.subnet())
                .tagSpecifications(TagSpecification.builder().resourceType(ResourceType.INSTANCE)
                        .tags(Tag.builder().key("ipv6-launch-review").value("owned").build()).build()))
                .instances().stream().map(instance -> instance.instanceId()).toList();
    }

    private int availableIpv4(Fixture fixture) {
        return fixture.client().describeSubnets(request -> request.subnetIds(fixture.subnet()))
                .subnets().getFirst().availableIpAddressCount();
    }

    private void hold(Fixture fixture, int count) {
        NetworkInterface held = fixture.client().createNetworkInterface(request -> request
                .subnetId(fixture.subnet()).ipv6AddressCount(count)).networkInterface();
        cleanup.register(() -> fixture.client().deleteNetworkInterface(request -> request
                .networkInterfaceId(held.networkInterfaceId())));
    }

    private Fixture fixture(String account, boolean automatic, int prefix) {
        Ec2Client client = AwsTestClients.create(Ec2Client.builder(), account, Region.US_WEST_2);
        cleanup.register(client::close);
        String vpc = client.createVpc(request -> request.cidrBlock("10.235.0.0/16")
                .amazonProvidedIpv6CidrBlock(true)).vpc().vpcId();
        cleanup.register(() -> client.deleteVpc(request -> request.vpcId(vpc)));
        String block = client.describeVpcs(request -> request.vpcIds(vpc)).vpcs().getFirst()
                .ipv6CidrBlockAssociationSet().getFirst().ipv6CidrBlock();
        String subnetBlock = block.substring(0, block.indexOf('/')) + "/" + prefix;
        String subnet = client.createSubnet(request -> request.vpcId(vpc).cidrBlock("10.235.0.0/28")
                .ipv6CidrBlock(subnetBlock)).subnet().subnetId();
        cleanup.register(() -> client.deleteSubnet(request -> request.subnetId(subnet)));
        // Include resources an old failed launch might have stored without returning their IDs.
        cleanup.register(() -> {
            List<String> ids = client.describeInstances().reservations().stream()
                    .flatMap(reservation -> reservation.instances().stream()).map(instance -> instance.instanceId()).toList();
            if (!ids.isEmpty()) {
                client.terminateInstances(request -> request.instanceIds(ids));
            }
            client.describeVolumes().volumes().forEach(volume -> client.deleteVolume(request ->
                    request.volumeId(volume.volumeId())));
        });
        client.modifySubnetAttribute(request -> request.subnetId(subnet)
                .assignIpv6AddressOnCreation(value -> value.value(automatic)));
        return new Fixture(client, account, subnet, subnetBlock, block);
    }

    private record Fixture(Ec2Client client, String account, String subnet, String subnetBlock, String vpcBlock) { }
}
