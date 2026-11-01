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

    private static final class RecordingEc2 implements Ec2Client {
        private final List<String> calls = new ArrayList<>();
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
            if (createFailure != null) {
                throw createFailure;
            }
            return CreateSubnetResponse.builder().subnet(subnet -> subnet.subnetId("subnet-owned")).build();
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
