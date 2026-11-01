package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateSubnetRequest;
import software.amazon.awssdk.services.ec2.model.CreateSubnetResponse;
import software.amazon.awssdk.services.ec2.model.CreateVpcRequest;
import software.amazon.awssdk.services.ec2.model.CreateVpcResponse;
import software.amazon.awssdk.services.ec2.model.DeleteSubnetRequest;
import software.amazon.awssdk.services.ec2.model.DeleteSubnetResponse;
import software.amazon.awssdk.services.ec2.model.DeleteVpcRequest;
import software.amazon.awssdk.services.ec2.model.DeleteVpcResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class Ec2TestNetworkCleanupTest {
    @Test
    void closesSubnetBeforeVpcWithoutClosingBorrowedClient() {
        RecordingEc2 ec2 = new RecordingEc2();
        try (Ec2TestNetwork network = Ec2TestNetwork.create(ec2)) {
            assertThat(network.subnetId()).isEqualTo("subnet-owned");
        }
        assertThat(ec2.calls).containsExactly("createVpc", "createSubnet:vpc-owned",
                "deleteSubnet:subnet-owned", "deleteVpc:vpc-owned");
    }

    @Test
    void failedSubnetCreationCleansVpcAndPreservesAllocationFailure() {
        RecordingEc2 ec2 = new RecordingEc2();
        ec2.createFailure = new IllegalStateException("subnet allocation failed");
        ec2.vpcFailure = new IllegalStateException("VPC cleanup failed");
        Throwable failure = catchThrowable(() -> Ec2TestNetwork.create(ec2));
        assertThat(failure).isSameAs(ec2.createFailure);
        assertThat(failure.getSuppressed()).containsExactly(ec2.vpcFailure);
        assertThat(ec2.calls).containsExactly("createVpc", "createSubnet:vpc-owned", "deleteVpc:vpc-owned");
    }

    @Test
    void cleanupAttemptsBothResourcesAndPreservesFirstFailure() {
        RecordingEc2 ec2 = new RecordingEc2();
        Ec2TestNetwork network = Ec2TestNetwork.create(ec2);
        ec2.subnetFailure = new IllegalStateException("subnet cleanup failed");
        ec2.vpcFailure = new IllegalStateException("VPC cleanup failed");
        Throwable failure = catchThrowable(network::close);
        assertThat(failure).isSameAs(ec2.subnetFailure);
        assertThat(failure.getSuppressed()).containsExactly(ec2.vpcFailure);
        assertThat(ec2.calls).endsWith("deleteSubnet:subnet-owned", "deleteVpc:vpc-owned");
    }

    @Test
    void testFailureKeepsCleanupFailureSuppressed() {
        RecordingEc2 ec2 = new RecordingEc2();
        IllegalStateException testFailure = new IllegalStateException("assertion setup failed");
        ec2.vpcFailure = new IllegalStateException("VPC cleanup failed");
        Throwable failure = catchThrowable(() -> {
            try (Ec2TestNetwork network = Ec2TestNetwork.create(ec2)) {
                assertThat(network.vpcId()).isEqualTo("vpc-owned");
                throw testFailure;
            }
        });
        assertThat(failure).isSameAs(testFailure);
        assertThat(failure.getSuppressed()).containsExactly(ec2.vpcFailure);
    }

    @Test
    void ownsTwoSubnetsInOneVpcAndClosesThemInReverseOrder() {
        RecordingEc2 ec2 = new RecordingEc2();
        ec2.numberSubnets = true;
        try (Ec2TestNetwork network = Ec2TestNetwork.create(ec2, List.of("us-west-2a", "us-west-2b"))) {
            assertThat(network.subnetIds()).containsExactly("subnet-owned-1", "subnet-owned-2");
            assertThat(ec2.zones).containsExactly("us-west-2a", "us-west-2b");
            assertThat(ec2.cidrs).containsExactly("10.254.1.0/24", "10.254.2.0/24");
        }
        assertThat(ec2.calls).containsExactly("createVpc", "createSubnet:vpc-owned", "createSubnet:vpc-owned",
                "deleteSubnet:subnet-owned-2", "deleteSubnet:subnet-owned-1", "deleteVpc:vpc-owned");
    }

    @Test
    void failedSecondAllocationCleansFirstSubnetAndVpcWithoutClosingBorrowedClient() {
        RecordingEc2 ec2 = new RecordingEc2();
        ec2.numberSubnets = true;
        ec2.failAt = 2;
        ec2.createFailure = new IllegalStateException("second subnet allocation failed");
        Throwable failure = catchThrowable(() -> Ec2TestNetwork.create(ec2, List.of("us-east-1a", "us-east-1b")));
        assertThat(failure).isSameAs(ec2.createFailure);
        assertThat(ec2.calls).containsExactly("createVpc", "createSubnet:vpc-owned", "createSubnet:vpc-owned",
                "deleteSubnet:subnet-owned-1", "deleteVpc:vpc-owned");
    }

    @Test
    void failedSecondAllocationRetainsAllRollbackFailures() {
        RecordingEc2 ec2 = new RecordingEc2();
        ec2.numberSubnets = true;
        ec2.failAt = 2;
        ec2.createFailure = new IllegalStateException("second subnet allocation failed");
        ec2.subnetFailure = new IllegalStateException("subnet cleanup failed");
        ec2.vpcFailure = new IllegalStateException("VPC cleanup failed");
        Throwable failure = catchThrowable(() -> Ec2TestNetwork.create(ec2, List.of("us-east-1a", "us-east-1b")));
        assertThat(failure).isSameAs(ec2.createFailure);
        assertThat(failure.getSuppressed()).containsExactly(ec2.subnetFailure);
        assertThat(ec2.subnetFailure.getSuppressed()).containsExactly(ec2.vpcFailure);
        assertThat(ec2.calls).endsWith("deleteSubnet:subnet-owned-1", "deleteVpc:vpc-owned");
    }

    private static final class RecordingEc2 implements Ec2Client {
        private final List<String> calls = new ArrayList<>();
        private final List<String> zones = new ArrayList<>();
        private final List<String> cidrs = new ArrayList<>();
        private boolean numberSubnets;
        private int failAt = 1;
        private int allocations;
        private RuntimeException createFailure;
        private RuntimeException subnetFailure;
        private RuntimeException vpcFailure;

        @Override
        public CreateVpcResponse createVpc(CreateVpcRequest request) {
            calls.add("createVpc");
            return CreateVpcResponse.builder().vpc(vpc -> vpc.vpcId("vpc-owned")).build();
        }

        @Override
        public CreateSubnetResponse createSubnet(CreateSubnetRequest request) {
            calls.add("createSubnet:" + request.vpcId());
            zones.add(request.availabilityZone());
            cidrs.add(request.cidrBlock());
            allocations++;
            if (createFailure != null && allocations == failAt) {
                throw createFailure;
            }
            return CreateSubnetResponse.builder().subnet(subnet -> subnet.subnetId(numberSubnets ? "subnet-owned-" + allocations : "subnet-owned")).build();
        }

        @Override
        public DeleteSubnetResponse deleteSubnet(DeleteSubnetRequest request) {
            calls.add("deleteSubnet:" + request.subnetId());
            if (subnetFailure != null) {
                throw subnetFailure;
            }
            return DeleteSubnetResponse.builder().build();
        }

        @Override
        public DeleteVpcResponse deleteVpc(DeleteVpcRequest request) {
            calls.add("deleteVpc:" + request.vpcId());
            if (vpcFailure != null) {
                throw vpcFailure;
            }
            return DeleteVpcResponse.builder().build();
        }

        @Override
        public String serviceName() {
            return "ec2";
        }

        @Override
        public void close() {
            calls.add("closeClient");
        }
    }
}
