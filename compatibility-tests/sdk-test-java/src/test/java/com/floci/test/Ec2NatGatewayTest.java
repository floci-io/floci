package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AllocateAddressRequest;
import software.amazon.awssdk.services.ec2.model.AllocateAddressResponse;
import software.amazon.awssdk.services.ec2.model.AvailabilityMode;
import software.amazon.awssdk.services.ec2.model.ConnectivityType;
import software.amazon.awssdk.services.ec2.model.CreateNatGatewayRequest;
import software.amazon.awssdk.services.ec2.model.DeleteNatGatewayRequest;
import software.amazon.awssdk.services.ec2.model.DescribeNatGatewaysRequest;
import software.amazon.awssdk.services.ec2.model.DomainType;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.NatGateway;
import software.amazon.awssdk.services.ec2.model.NatGatewayAddress;
import software.amazon.awssdk.services.ec2.model.ReleaseAddressRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Ec2NatGatewayTest {

    @Test
    @DisplayName("CreateNatGateway and DescribeNatGateways report a zonal gateway with its primary address")
    void natGatewayIsZonalWithItsPrimaryAddress() {
        // The Terraform AWS provider reads network_interface_id, private_ip and public_ip only from
        // a gateway whose AvailabilityMode is zonal, so an unset mode leaves all three null.
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String subnetId = TestFixtures.defaultVpcSubnets().get(0);
            AllocateAddressResponse address = ec2.allocateAddress(AllocateAddressRequest.builder()
                    .domain(DomainType.VPC)
                    .build());
            String natGatewayId = null;
            try {
                NatGateway created = ec2.createNatGateway(CreateNatGatewayRequest.builder()
                                .subnetId(subnetId)
                                .allocationId(address.allocationId())
                                .build())
                        .natGateway();
                natGatewayId = created.natGatewayId();
                assertThat(created.availabilityMode()).isEqualTo(AvailabilityMode.ZONAL);

                NatGateway described = ec2.describeNatGateways(DescribeNatGatewaysRequest.builder()
                                .natGatewayIds(natGatewayId)
                                .build())
                        .natGateways().get(0);
                assertThat(described.availabilityMode()).isEqualTo(AvailabilityMode.ZONAL);
                assertThat(described.natGatewayAddresses()).hasSize(1);
                NatGatewayAddress primary = described.natGatewayAddresses().get(0);
                assertThat(primary.isPrimary()).isTrue();
                assertThat(primary.networkInterfaceId()).startsWith("eni-");
                assertThat(primary.privateIp()).isNotBlank();
                assertThat(primary.publicIp()).isEqualTo(address.publicIp());
            } finally {
                if (natGatewayId != null) {
                    ec2.deleteNatGateway(DeleteNatGatewayRequest.builder().natGatewayId(natGatewayId).build());
                }
                ec2.releaseAddress(ReleaseAddressRequest.builder().allocationId(address.allocationId()).build());
            }
        }
    }

    @Test
    @DisplayName("CreateNatGateway refuses secondary addresses rather than creating a gateway without them")
    void secondaryAddressesAreRefused() {
        // SecondaryAllocationId.N and SecondaryPrivateIpAddress.N are the list names the SDK sends.
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String subnetId = TestFixtures.defaultVpcSubnets().get(0);
            for (CreateNatGatewayRequest request : List.of(
                    CreateNatGatewayRequest.builder()
                            .subnetId(subnetId)
                            .allocationId("eipalloc-0123456789abcdef0")
                            .secondaryAllocationIds("eipalloc-0fedcba9876543210")
                            .build(),
                    CreateNatGatewayRequest.builder()
                            .subnetId(subnetId)
                            .connectivityType(ConnectivityType.PRIVATE)
                            .secondaryPrivateIpAddresses("172.31.0.200")
                            .build(),
                    CreateNatGatewayRequest.builder()
                            .subnetId(subnetId)
                            .connectivityType(ConnectivityType.PRIVATE)
                            .secondaryPrivateIpAddressCount(2)
                            .build())) {
                Ec2Exception error = assertThrows(Ec2Exception.class, () -> ec2.createNatGateway(request));
                assertThat(error.awsErrorDetails().errorCode()).isEqualTo("UnsupportedOperation");
            }
        }
    }
}
