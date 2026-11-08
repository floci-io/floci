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
import software.amazon.awssdk.services.ec2.model.NatGateway;
import software.amazon.awssdk.services.ec2.model.NatGatewayAddress;
import software.amazon.awssdk.services.ec2.model.ReleaseAddressRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
    @DisplayName("CreateNatGateway gives a gateway its secondary addresses, reported after the primary one")
    void secondaryAddressesAreReported() {
        // SecondaryAllocationId.N and SecondaryPrivateIpAddress.N are the list names the SDK sends.
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            String subnetId = TestFixtures.defaultVpcSubnets().get(0);
            AllocateAddressResponse primaryEip = ec2.allocateAddress(AllocateAddressRequest.builder()
                    .domain(DomainType.VPC).build());
            AllocateAddressResponse secondaryEip = ec2.allocateAddress(AllocateAddressRequest.builder()
                    .domain(DomainType.VPC).build());
            String publicId = null;
            String privateId = null;
            try {
                publicId = ec2.createNatGateway(CreateNatGatewayRequest.builder()
                        .subnetId(subnetId)
                        .allocationId(primaryEip.allocationId())
                        .secondaryAllocationIds(secondaryEip.allocationId())
                        .build()).natGateway().natGatewayId();
                List<NatGatewayAddress> publicAddresses = describe(ec2, publicId).natGatewayAddresses();
                assertThat(publicAddresses).hasSize(2);
                assertThat(publicAddresses.get(0).isPrimary()).isTrue();
                NatGatewayAddress secondary = publicAddresses.get(1);
                assertThat(secondary.isPrimary()).isFalse();
                assertThat(secondary.allocationId()).isEqualTo(secondaryEip.allocationId());
                assertThat(secondary.publicIp()).isEqualTo(secondaryEip.publicIp());
                assertThat(secondary.privateIp()).isNotBlank().isNotEqualTo(publicAddresses.get(0).privateIp());
                assertThat(secondary.networkInterfaceId()).isEqualTo(publicAddresses.get(0).networkInterfaceId());

                privateId = ec2.createNatGateway(CreateNatGatewayRequest.builder()
                        .subnetId(subnetId)
                        .connectivityType(ConnectivityType.PRIVATE)
                        .secondaryPrivateIpAddressCount(2)
                        .build()).natGateway().natGatewayId();
                List<NatGatewayAddress> privateAddresses = describe(ec2, privateId).natGatewayAddresses();
                assertThat(privateAddresses).hasSize(3);
                assertThat(privateAddresses).extracting(NatGatewayAddress::isPrimary).containsExactly(true, false, false);
                assertThat(privateAddresses).extracting(NatGatewayAddress::privateIp).doesNotHaveDuplicates();
                assertThat(privateAddresses).extracting(NatGatewayAddress::publicIp).containsOnlyNulls();
            } finally {
                for (String id : new String[] {publicId, privateId}) {
                    if (id != null) {
                        ec2.deleteNatGateway(DeleteNatGatewayRequest.builder().natGatewayId(id).build());
                    }
                }
                ec2.releaseAddress(ReleaseAddressRequest.builder().allocationId(primaryEip.allocationId()).build());
                ec2.releaseAddress(ReleaseAddressRequest.builder().allocationId(secondaryEip.allocationId()).build());
            }
        }
    }

    private static NatGateway describe(Ec2Client ec2, String natGatewayId) {
        return ec2.describeNatGateways(DescribeNatGatewaysRequest.builder().natGatewayIds(natGatewayId).build())
                .natGateways().get(0);
    }
}
