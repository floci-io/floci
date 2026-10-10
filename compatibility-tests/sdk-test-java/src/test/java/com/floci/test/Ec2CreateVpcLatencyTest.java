package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateVpcRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.Vpc;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Ec2CreateVpcLatencyTest {

    @Test
    @DisplayName("CreateVpc returns promptly with default SDK retries and keeps intentional creates distinct")
    void createVpcWithDefaultRetriesCreatesExactlyOneVpcPerCall() {
        String name = TestFixtures.uniqueName("create-vpc-latency");
        Filter filter = Filter.builder().name("tag:Name").values(name).build();
        CreateVpcRequest request = CreateVpcRequest.builder()
                .cidrBlock("42.0.0.0/16")
                .tagSpecifications(TagSpecification.builder()
                        .resourceType(ResourceType.VPC)
                        .tags(Tag.builder().key("Name").value(name).build())
                        .build())
                .build();

        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            try {
                long start = System.nanoTime();
                String first = ec2.createVpc(request).vpc().vpcId();
                Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

                assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
                assertThat(ec2.describeVpcs(describe -> describe.filters(filter)).vpcs())
                        .extracting(Vpc::vpcId).containsExactly(first);

                String second = ec2.createVpc(request).vpc().vpcId();
                assertThat(second).isNotEqualTo(first);
                assertThat(ec2.describeVpcs(describe -> describe.filters(filter)).vpcs())
                        .extracting(Vpc::vpcId).containsExactlyInAnyOrder(first, second);
            } finally {
                List<Vpc> created = ec2.describeVpcs(describe -> describe.filters(filter)).vpcs();
                for (Vpc vpc : created) {
                    ec2.deleteVpc(delete -> delete.vpcId(vpc.vpcId()));
                }
            }
        }
    }

    @Test
    @DisplayName("DescribeVpcs exposes a fresh account's default VPC before CreateVpc")
    void describeVpcsSeedsDefaultsBeforeAnyMutation() {
        try (Ec2Client ec2 = TestFixtures.ec2Client("517500000001")) {
            assertThat(ec2.describeVpcs().vpcs()).filteredOn(Vpc::isDefault).hasSize(1);
        }
    }
}
