package io.floci.aws.services.ec2;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateNetworkInterfaceRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.NetworkInterface;
import software.amazon.awssdk.services.ec2.model.NetworkInterfacePrivateIpAddress;
import software.amazon.awssdk.services.ec2.model.PrivateIpAddressSpecification;

import java.util.HashSet;
import java.util.List;

import static io.floci.aws.testing.AwsSdkErrors.assertAwsError;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2CreateNetworkInterfaceSecondaryCountIntegrationTest {
    private Ec2Client ec2;
    private String vpcId;
    private String subnetId;

    @BeforeEach
    void createNetwork() {
        ec2 = AwsTestClients.create(Ec2Client.builder(), "850700000001", Region.US_EAST_1);
        vpcId = ec2.createVpc(r -> r.cidrBlock("10.85.0.0/16")).vpc().vpcId();
        subnetId = ec2.createSubnet(r -> r.vpcId(vpcId).cidrBlock("10.85.1.0/28")).subnet().subnetId();
    }

    @AfterEach
    void deleteNetwork() {
        try {
            for (NetworkInterface eni : interfaces()) {
                ec2.deleteNetworkInterface(r -> r.networkInterfaceId(eni.networkInterfaceId()));
            }
            ec2.deleteSubnet(r -> r.subnetId(subnetId));
            ec2.deleteVpc(r -> r.vpcId(vpcId));
        } finally {
            ec2.close();
        }
    }

    @Test
    void automaticAddressesRoundTripAndSupportBothExplicitPrimaryForms() {
        for (int primaryForm = 0; primaryForm < 3; primaryForm++) {
            CreateNetworkInterfaceRequest.Builder request = request(2);
            if (primaryForm == 1) {
                request.privateIpAddress("10.85.1.4");
            } else if (primaryForm == 2) {
                request.privateIpAddresses(PrivateIpAddressSpecification.builder()
                        .privateIpAddress("10.85.1.4").primary(true).build());
            }
            NetworkInterface eni = ec2.createNetworkInterface(request.build()).networkInterface();
            assertAddresses(eni, 3);
            if (primaryForm != 0) {
                assertEquals("10.85.1.4", eni.privateIpAddress());
            }
            NetworkInterface described = ec2.describeNetworkInterfaces(r -> r
                    .networkInterfaceIds(eni.networkInterfaceId())).networkInterfaces().getFirst();
            assertEquals(new HashSet<>(eni.privateIpAddresses()), new HashSet<>(described.privateIpAddresses()));
            ec2.deleteNetworkInterface(r -> r.networkInterfaceId(eni.networkInterfaceId()));
        }
        assertAddresses(ec2.createNetworkInterface(request(0).build()).networkInterface(), 1);
    }

    @Test
    void invalidCountsAndExplicitSecondariesFailBeforeAllocation() {
        assertAwsError("InvalidParameterValue", 400, () -> ec2.createNetworkInterface(request(-1).build()));
        assertAwsError("InvalidParameterCombination", 400, () -> ec2.createNetworkInterface(request(2).privateIpAddresses(
                PrivateIpAddressSpecification.builder().privateIpAddress("10.85.1.5").primary(false).build()).build()));
        assertTrue(interfaces().isEmpty());
        assertAddresses(ec2.createNetworkInterface(request(10).build()).networkInterface(), 11);
    }

    @Test
    void exhaustionRollsBackAndDeletionMakesAllAddressesReusable() {
        for (boolean explicitPrimary : List.of(false, true)) {
            CreateNetworkInterfaceRequest.Builder exhausted = request(11);
            if (explicitPrimary) {
                exhausted.privateIpAddress("10.85.1.4");
            }
            assertAwsError("InsufficientFreeAddressesInSubnet", 400,
                    () -> ec2.createNetworkInterface(exhausted.build()));
            assertTrue(interfaces().isEmpty());
            for (int attempt = 0; attempt < 2; attempt++) {
                NetworkInterface eni = ec2.createNetworkInterface(request(10).build()).networkInterface();
                assertAddresses(eni, 11);
                ec2.deleteNetworkInterface(r -> r.networkInterfaceId(eni.networkInterfaceId()));
            }
        }
    }

    private CreateNetworkInterfaceRequest.Builder request(int count) {
        return CreateNetworkInterfaceRequest.builder().subnetId(subnetId).secondaryPrivateIpAddressCount(count);
    }

    private List<NetworkInterface> interfaces() {
        return ec2.describeNetworkInterfaces(r -> r.filters(Filter.builder()
                .name("subnet-id").values(subnetId).build())).networkInterfaces();
    }

    private static void assertAddresses(NetworkInterface eni, int count) {
        List<NetworkInterfacePrivateIpAddress> addresses = eni.privateIpAddresses();
        assertEquals(count, addresses.size());
        assertEquals(count, addresses.stream().map(NetworkInterfacePrivateIpAddress::privateIpAddress).distinct().count());
        assertEquals(1L, addresses.stream().filter(NetworkInterfacePrivateIpAddress::primary).count());
        assertEquals(eni.privateIpAddress(), addresses.stream().filter(NetworkInterfacePrivateIpAddress::primary)
                .findFirst().orElseThrow().privateIpAddress());
    }
}
