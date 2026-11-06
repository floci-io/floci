package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateImageRequest;
import software.amazon.awssdk.services.ec2.model.CreateSubnetRequest;
import software.amazon.awssdk.services.ec2.model.CreateVpcRequest;
import software.amazon.awssdk.services.ec2.model.DeleteSubnetRequest;
import software.amazon.awssdk.services.ec2.model.DeleteVpcRequest;
import software.amazon.awssdk.services.ec2.model.DeregisterImageRequest;
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * EC2 instances backed by real containers. Floci runs EC2 in mock mode for the regular native
 * suites, so this class only runs where the workflow starts Floci with container-backed EC2 and
 * sets FLOCI_EC2_CONTAINERS. An instance under security group enforcement lives in its firewall
 * helper's network, never on a VPC network, so the workflow runs the class once per mode:
 * {@code security-groups} with enforcement on, {@code vpc-networks} with it off.
 */
@DisplayName("EC2 instances backed by containers")
class Ec2ContainerTest {

    // The CI job runs on arm64, where an arm64 guest needs no emulation.
    private static final String AMI = "ami-amazonlinux2023-arm64";
    private static final String MODE = System.getenv("FLOCI_EC2_CONTAINERS");

    private static Ec2Client ec2;

    @BeforeAll
    static void setup() {
        assumeTrue(MODE != null, "Skipping container-backed EC2 test: FLOCI_EC2_CONTAINERS is not set");
        ec2 = TestFixtures.ec2Client();
    }

    @AfterAll
    static void cleanup() {
        if (ec2 != null) {
            ec2.close();
        }
    }

    @Test
    @DisplayName("An instance under security group enforcement runs and is captured as an AMI")
    void instanceUnderSecurityGroupEnforcementIsCaptured() throws InterruptedException {
        assumeTrue("security-groups".equals(MODE), "Runs with security group enforcement on");
        String instanceId = runInstance(null);
        awaitState(instanceId, InstanceStateName.RUNNING);

        String imageId = ec2.createImage(CreateImageRequest.builder()
                .instanceId(instanceId).name("container-test-" + System.currentTimeMillis()).build()).imageId();
        assertThat(awaitImage(imageId).stateAsString()).isEqualTo("available");
        ec2.deregisterImage(DeregisterImageRequest.builder().imageId(imageId).build());
        assertThat(ec2.describeImages(DescribeImagesRequest.builder().imageIds(imageId).build()).images()).isEmpty();

        ec2.terminateInstances(TerminateInstancesRequest.builder().instanceIds(instanceId).build());
        awaitState(instanceId, InstanceStateName.TERMINATED);
    }

    @Test
    @DisplayName("An instance in a VPC gets an address from its subnet and is torn down with its VPC")
    void instanceInAVpcIsTornDownWithItsVpc() throws InterruptedException {
        assumeTrue("vpc-networks".equals(MODE), "Runs with security group enforcement off");
        String vpcId = ec2.createVpc(CreateVpcRequest.builder().cidrBlock("10.87.0.0/16").build()).vpc().vpcId();
        String subnetId = ec2.createSubnet(CreateSubnetRequest.builder()
                .vpcId(vpcId).cidrBlock("10.87.1.0/24").build()).subnet().subnetId();
        String instanceId = runInstance(subnetId);

        Instance running = awaitState(instanceId, InstanceStateName.RUNNING);
        assertThat(running.privateIpAddress()).startsWith("10.87.1.");

        ec2.terminateInstances(TerminateInstancesRequest.builder().instanceIds(instanceId).build());
        awaitState(instanceId, InstanceStateName.TERMINATED);
        ec2.deleteSubnet(DeleteSubnetRequest.builder().subnetId(subnetId).build());
        ec2.deleteVpc(DeleteVpcRequest.builder().vpcId(vpcId).build());
    }

    /**
     * Leaves an instance running in its own VPC on purpose. The workflow then kills and restarts
     * Floci, and the restarted Floci must disconnect the instance and remove the orphaned network.
     */
    @Test
    @DisplayName("An instance is left running in a VPC for the restart check")
    void instanceLeftRunningForTheRestartCheck() throws InterruptedException {
        assumeTrue("vpc-networks".equals(MODE), "Runs with security group enforcement off");
        String vpcId = ec2.createVpc(CreateVpcRequest.builder().cidrBlock("10.88.0.0/16").build()).vpc().vpcId();
        String subnetId = ec2.createSubnet(CreateSubnetRequest.builder()
                .vpcId(vpcId).cidrBlock("10.88.1.0/24").build()).subnet().subnetId();

        Instance running = awaitState(runInstance(subnetId), InstanceStateName.RUNNING);

        assertThat(running.privateIpAddress()).startsWith("10.88.1.");
    }

    private static String runInstance(String subnetId) {
        return ec2.runInstances(RunInstancesRequest.builder()
                .imageId(AMI)
                .instanceType(InstanceType.T4_G_MICRO)
                .subnetId(subnetId)
                .minCount(1)
                .maxCount(1)
                .build()).instances().get(0).instanceId();
    }

    private static Instance awaitState(String instanceId, InstanceStateName target) throws InterruptedException {
        Instance instance = null;
        for (int i = 0; i < 180; i++) {
            instance = ec2.describeInstances(DescribeInstancesRequest.builder().instanceIds(instanceId).build())
                    .reservations().get(0).instances().get(0);
            if (instance.state().name() == target) {
                return instance;
            }
            if (instance.state().name() == InstanceStateName.TERMINATED) {
                break;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("Instance " + instanceId + " stayed " + instance.state().name()
                + " instead of reaching " + target);
    }

    private static Image awaitImage(String imageId) throws InterruptedException {
        Image image = null;
        for (int i = 0; i < 180; i++) {
            List<Image> images = ec2.describeImages(DescribeImagesRequest.builder().imageIds(imageId).build()).images();
            image = images.isEmpty() ? null : images.get(0);
            if (image != null && !"pending".equals(image.stateAsString())) {
                return image;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("Image " + imageId + " stayed pending");
    }
}
