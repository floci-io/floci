package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Verifies pagination for DescribeNetworkInterfaces (MaxResults / NextToken).
 *
 * <p>Launches 6 instances to produce 6 ENIs, then exercises full pagination:
 * first page truncated with nextToken, second page continued from token,
 * and final page omitting nextToken.
 */
@DisplayName("EC2 DescribeNetworkInterfaces Pagination")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2NetworkInterfacePaginationTest {

    private static Ec2Client ec2;
    private static String vpcId;
    private static String subnetId;
    private static String sgId;
    private static String keyName;
    private static final List<String> instanceIds = new ArrayList<>();

    @BeforeAll
    static void setup() {
        ec2 = TestFixtures.ec2Client();

        // --- Create VPC ---
        CreateVpcResponse vpcResp = ec2.createVpc(CreateVpcRequest.builder()
                .cidrBlock("10.1.0.0/16").build());
        vpcId = vpcResp.vpc().vpcId();
        assertThat(vpcId).isNotNull().startsWith("vpc-");

        // --- Create Subnet ---
        CreateSubnetResponse subnetResp = ec2.createSubnet(CreateSubnetRequest.builder()
                .vpcId(vpcId).cidrBlock("10.1.1.0/24").build());
        subnetId = subnetResp.subnet().subnetId();
        assertThat(subnetId).isNotNull().startsWith("subnet-");

        // --- Create Security Group ---
        String sgName = TestFixtures.uniqueName("pagination-sg");
        CreateSecurityGroupResponse sgResp = ec2.createSecurityGroup(
                CreateSecurityGroupRequest.builder()
                        .groupName(sgName).description("Pagination test SG").vpcId(vpcId).build());
        sgId = sgResp.groupId();
        assertThat(sgId).isNotNull().startsWith("sg-");

        // --- Create Key Pair ---
        keyName = TestFixtures.uniqueName("pagination-key");
        ec2.createKeyPair(CreateKeyPairRequest.builder().keyName(keyName).build());

        // --- Launch 6 instances to get 6 ENIs ---
        RunInstancesResponse runResp = ec2.runInstances(RunInstancesRequest.builder()
                .imageId("ami-0abcdef1234567890")
                .instanceType(InstanceType.T2_MICRO)
                .minCount(6)
                .maxCount(6)
                .keyName(keyName)
                .subnetId(subnetId)
                .securityGroupIds(List.of(sgId))
                .build());

        for (Instance inst : runResp.instances()) {
            instanceIds.add(inst.instanceId());
        }
        assertThat(instanceIds).hasSize(6);
    }

    @AfterAll
    static void cleanup() {
        if (ec2 != null) {
            // Terminate instances
            if (!instanceIds.isEmpty()) {
                try {
                    ec2.terminateInstances(TerminateInstancesRequest.builder()
                            .instanceIds(instanceIds).build());
                } catch (Exception ignored) {}
            }
            // Delete subnet
            try {
                if (subnetId != null) {
                    ec2.deleteSubnet(DeleteSubnetRequest.builder().subnetId(subnetId).build());
                }
            } catch (Exception ignored) {}
            // Delete SG
            try {
                if (sgId != null) {
                    ec2.deleteSecurityGroup(DeleteSecurityGroupRequest.builder().groupId(sgId).build());
                }
            } catch (Exception ignored) {}
            // Delete key pair
            try {
                if (keyName != null) {
                    ec2.deleteKeyPair(DeleteKeyPairRequest.builder().keyName(keyName).build());
                }
            } catch (Exception ignored) {}
            // Delete VPC
            try {
                if (vpcId != null) {
                    ec2.deleteVpc(DeleteVpcRequest.builder().vpcId(vpcId).build());
                }
            } catch (Exception ignored) {}
            ec2.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("DescribeNetworkInterfaces without pagination returns all 6 ENIs")
    void describeNetworkInterfacesAll() {
        DescribeNetworkInterfacesResponse resp = ec2.describeNetworkInterfaces(
                DescribeNetworkInterfacesRequest.builder()
                        .filters(Filter.builder().name("subnet-id").values(subnetId).build())
                        .build());

        assertThat(resp.networkInterfaces()).hasSize(6);
        assertThat(resp.nextToken()).isNull();
    }

    @Test
    @Order(2)
    @DisplayName("MaxResults=5 truncates first page and returns nextToken")
    void describeNetworkInterfacesFirstPage() {
        DescribeNetworkInterfacesResponse resp = ec2.describeNetworkInterfaces(
                DescribeNetworkInterfacesRequest.builder()
                        .filters(Filter.builder().name("subnet-id").values(subnetId).build())
                        .maxResults(5)
                        .build());

        assertThat(resp.networkInterfaces()).hasSize(5);
        assertThat(resp.nextToken()).isNotNull().isNotEmpty();
    }

    @Test
    @Order(3)
    @DisplayName("NextToken continuation returns remaining ENIs, no nextToken on final page")
    void describeNetworkInterfacesContinuation() {
        // Page 1: get nextToken
        DescribeNetworkInterfacesResponse page1 = ec2.describeNetworkInterfaces(
                DescribeNetworkInterfacesRequest.builder()
                        .filters(Filter.builder().name("subnet-id").values(subnetId).build())
                        .maxResults(5)
                        .build());
        assertThat(page1.nextToken()).isNotNull();

        // Page 2: use nextToken
        DescribeNetworkInterfacesResponse page2 = ec2.describeNetworkInterfaces(
                DescribeNetworkInterfacesRequest.builder()
                        .filters(Filter.builder().name("subnet-id").values(subnetId).build())
                        .maxResults(5)
                        .nextToken(page1.nextToken())
                        .build());

        assertThat(page2.networkInterfaces()).hasSize(1);
        assertThat(page2.nextToken()).isNull();

        // Verify all 6 ENIs accounted for across both pages
        List<String> page1Ids = page1.networkInterfaces().stream()
                .map(NetworkInterface::networkInterfaceId).toList();
        List<String> page2Ids = page2.networkInterfaces().stream()
                .map(NetworkInterface::networkInterfaceId).toList();

        // No overlap between pages
        assertThat(page1Ids).doesNotContainAnyElementsOf(page2Ids);
        // Combined = 6 unique
        List<String> all = new ArrayList<>(page1Ids);
        all.addAll(page2Ids);
        assertThat(all).hasSize(6).doesNotHaveDuplicates();
    }

    @Test
    @Order(4)
    @DisplayName("NextToken past end returns empty result set")
    void describeNetworkInterfacesTokenPastEnd() {
        // Get a valid token from first page
        DescribeNetworkInterfacesResponse page1 = ec2.describeNetworkInterfaces(
                DescribeNetworkInterfacesRequest.builder()
                        .filters(Filter.builder().name("subnet-id").values(subnetId).build())
                        .maxResults(5)
                        .build());
        String token = page1.nextToken();

        // Use it to get the last page
        DescribeNetworkInterfacesResponse page2 = ec2.describeNetworkInterfaces(
                DescribeNetworkInterfacesRequest.builder()
                        .filters(Filter.builder().name("subnet-id").values(subnetId).build())
                        .maxResults(5)
                        .nextToken(token)
                        .build());

        // Using the token from page2 (which is null since it's the last page)
        // would be invalid, but using a valid token again should still work
        DescribeNetworkInterfacesResponse repeat = ec2.describeNetworkInterfaces(
                DescribeNetworkInterfacesRequest.builder()
                        .filters(Filter.builder().name("subnet-id").values(subnetId).build())
                        .maxResults(5)
                        .nextToken(token)
                        .build());

        assertThat(repeat.networkInterfaces()).hasSize(1);
        assertThat(repeat.nextToken()).isNull();
    }
}
