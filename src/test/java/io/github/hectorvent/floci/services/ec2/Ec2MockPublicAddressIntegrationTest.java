package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingReconciler;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingService;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(Ec2MockPublicAddressIntegrationTest.DocumentationAddresses.class)
class Ec2MockPublicAddressIntegrationTest {
    private static final AtomicLong ACCOUNTS = new AtomicLong(722000000000L);
    private static final String DOCUMENTATION_ADDRESS = "203\\.0\\.113\\.([1-9]|[1-9][0-9]|1[0-9][0-9]|2[0-4][0-9]|25[0-4])";
    private static final String REGION = "us-east-1";
    private static final String INSTANCE = "DescribeInstancesResponse.reservationSet.item.instancesSet.item.";
    private static final String ADDRESS = "DescribeAddressesResponse.addressesSet.item.";
    private static final String INTERFACE_ADDRESS =
            "DescribeNetworkInterfacesResponse.networkInterfaceSet.item.privateIpAddressesSet.item.";

    @Inject AutoScalingReconciler reconciler;
    @Inject AutoScalingService groups;

    @ParameterizedTest
    @CsvSource({
            "direct,false,true,true", "direct,true,false,false",
            "direct,true,unset,true", "direct,false,unset,false",
            "template,false,true,true", "template,true,false,false",
            "template,true,unset,true", "template,false,unset,false",
            "asg,false,true,true", "asg,true,false,false",
            "asg,true,unset,true", "asg,false,unset,false",
            "fleet,false,true,true", "fleet,true,false,false",
            "fleet,true,unset,true", "fleet,false,unset,false"
    })
    void publicIpUsesTheSelectedLaunchValueAndSurvivesOnlyTheRunningLifetime(
            String source, boolean subnetDefault, String override, boolean publicExpected) {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        String vpc = request("CreateVpc", owner).formParam("CidrBlock", "10.86.0.0/16")
                .post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        String subnet = request("CreateSubnet", owner).formParam("VpcId", vpc)
                .formParam("CidrBlock", "10.86.1.0/24").formParam("AvailabilityZone", REGION + "a")
                .post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
        request("ModifySubnetAttribute", owner).formParam("SubnetId", subnet)
                .formParam("MapPublicIpOnLaunch.Value", subnetDefault).post("/").then().statusCode(200);
        String image = "ami-amazonlinux2023";
        String template = null;
        if (!"direct".equals(source)) {
            RequestSpecification create = request("CreateLaunchTemplate", owner)
                    .formParam("LaunchTemplateName", "public-address")
                    .formParam("LaunchTemplateData.ImageId", image)
                    .formParam("LaunchTemplateData.InstanceType", "t3.micro");
            if (!"unset".equals(override)) {
                create.formParam("LaunchTemplateData.NetworkInterface.1.DeviceIndex", 0)
                        .formParam("LaunchTemplateData.NetworkInterface.1.AssociatePublicIpAddress",
                                !Boolean.parseBoolean(override));
            }
            template = create.post("/").then().statusCode(200)
                    .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
            RequestSpecification version = request("CreateLaunchTemplateVersion", owner)
                    .formParam("LaunchTemplateId", template).formParam("SourceVersion", "1")
                    .formParam("LaunchTemplateData.InstanceType", "t3.micro");
            if (!"unset".equals(override)) {
                version.formParam("LaunchTemplateData.NetworkInterface.1.DeviceIndex", 0)
                        .formParam("LaunchTemplateData.NetworkInterface.1.AssociatePublicIpAddress", override);
            }
            if (!"asg".equals(source) && !"unset".equals(override)) {
                version.formParam("LaunchTemplateData.NetworkInterface.1.SubnetId", subnet);
            }
            version.post("/").then().statusCode(200);
        }
        String instance;
        if ("fleet".equals(source)) {
            RequestSpecification fleet = request("CreateFleet", owner).formParam("Type", "instant")
                    .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.LaunchTemplateId", template)
                    .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.Version", "2")
                    .formParam("TargetCapacitySpecification.TotalTargetCapacity", 1)
                    .formParam("TargetCapacitySpecification.DefaultTargetCapacityType", "on-demand");
            if ("unset".equals(override)) {
                fleet.formParam("LaunchTemplateConfig.1.Overrides.1.SubnetId", subnet);
            }
            instance = fleet.post("/").then().statusCode(200)
                    .extract().path("CreateFleetResponse.fleetInstanceSet.item.instanceIds.item");
        } else if ("asg".equals(source)) {
            request("CreateAutoScalingGroup", owner).formParam("AutoScalingGroupName", "public-address")
                    .formParam("MinSize", 0).formParam("MaxSize", 1).formParam("DesiredCapacity", 1)
                    .formParam("VPCZoneIdentifier", subnet)
                    .formParam("LaunchTemplate.LaunchTemplateId", template).formParam("LaunchTemplate.Version", "2")
                    .post("/").then().statusCode(200);
            RequestScopes.runAs(owner, () -> reconciler.reconcile(
                    groups.describeAutoScalingGroups(REGION, List.of("public-address")).getFirst()));
            instance = request("DescribeAutoScalingGroups", owner).post("/").then().statusCode(200).extract()
                    .path("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member.Instances.member.InstanceId");
        } else {
            RequestSpecification launch = request("RunInstances", owner)
                    .formParam("MinCount", 1).formParam("MaxCount", 1);
            if (template != null) {
                launch.formParam("LaunchTemplate.LaunchTemplateId", template).formParam("LaunchTemplate.Version", "2");
                if ("unset".equals(override)) {
                    launch.formParam("SubnetId", subnet);
                }
            } else {
                launch.formParam("ImageId", image).formParam("InstanceType", "t3.micro");
                if (!"unset".equals(override)) {
                    launch.formParam("NetworkInterface.1.DeviceIndex", 0)
                            .formParam("NetworkInterface.1.SubnetId", subnet)
                            .formParam("NetworkInterface.1.AssociatePublicIpAddress", override);
                } else {
                    launch.formParam("SubnetId", subnet);
                }
            }
            instance = launch.post("/").then().statusCode(200)
                    .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        }
        ValidatableResponse first = describe(owner, instance).body(INSTANCE + "subnetId", equalTo(subnet));
        String privateIp = first.extract().path(INSTANCE + "privateIpAddress");
        String publicIp = first.extract().xmlPath().getString(INSTANCE + "ipAddress");
        assertEquals(publicExpected, !publicIp.isEmpty());
        if (publicExpected) {
            assertTrue(publicIp.matches(DOCUMENTATION_ADDRESS));
        }
        assertEquals(publicIp, describe(owner, instance).extract().xmlPath().getString(INSTANCE + "ipAddress"));
        // The provider derives associate_public_ip_address from the interface's association element.
        assertAssociation(owner, instance, publicExpected ? publicIp : null, "amazon");
        if (!"asg".equals(source)) {
            request("StopInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
            assertEquals("", describe(owner, instance).extract().xmlPath().getString(INSTANCE + "ipAddress"));
            assertAssociation(owner, instance, null, null);
            request("StartInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
            ValidatableResponse restarted = describe(owner, instance);
            assertEquals(privateIp, restarted.extract().path(INSTANCE + "privateIpAddress"));
            String replacement = restarted.extract().xmlPath().getString(INSTANCE + "ipAddress");
            assertEquals(publicExpected, !replacement.isEmpty());
            if (publicExpected) {
                assertNotEquals(publicIp, replacement);
            }
        } else {
            request("UpdateAutoScalingGroup", owner).formParam("AutoScalingGroupName", "public-address")
                    .formParam("DesiredCapacity", 0).post("/").then().statusCode(200);
            request("DeleteAutoScalingGroup", owner).formParam("AutoScalingGroupName", "public-address")
                    .formParam("ForceDelete", true).post("/").then().statusCode(200);
        }
        request("TerminateInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        assertEquals("", describe(owner, instance).extract().xmlPath().getString(INSTANCE + "ipAddress"));
        if (template != null) {
            request("DeleteLaunchTemplate", owner).formParam("LaunchTemplateId", template).post("/").then().statusCode(200);
        }
        request("DeleteSubnet", owner).formParam("SubnetId", subnet).post("/").then().statusCode(200);
        request("DeleteVpc", owner).formParam("VpcId", vpc).post("/").then().statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void networkInterfacesRejectAnInstanceLevelSubnet(boolean fromTemplate) {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        // A real subnet, so a check made only after the launch would have created something.
        String subnet = request("DescribeSubnets", owner).formParam("Filter.1.Name", "default-for-az")
                .formParam("Filter.1.Value.1", true).post("/").then().statusCode(200).extract().xmlPath()
                .getString("DescribeSubnetsResponse.subnetSet.item[0].subnetId");
        RequestSpecification launch = request("RunInstances", owner).formParam("MinCount", 1)
                .formParam("MaxCount", 1).formParam("SubnetId", subnet);
        String template = null;
        if (fromTemplate) {
            template = request("CreateLaunchTemplate", owner)
                    .formParam("LaunchTemplateName", "invalid-subnet-combination")
                    .formParam("LaunchTemplateData.ImageId", "ami-amazonlinux2023")
                    .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                    .formParam("LaunchTemplateData.NetworkInterface.1.DeviceIndex", 0)
                    .formParam("LaunchTemplateData.NetworkInterface.1.AssociatePublicIpAddress", true)
                    .post("/").then().statusCode(200)
                    .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
            launch.formParam("LaunchTemplate.LaunchTemplateId", template);
        } else {
            launch.formParam("ImageId", "ami-amazonlinux2023").formParam("InstanceType", "t3.micro")
                    .formParam("NetworkInterface.1.DeviceIndex", 0)
                    .formParam("NetworkInterface.1.AssociatePublicIpAddress", true);
        }
        launch.post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"));
        // The refusal leaves nothing behind: no instance and no interface in the caller's account.
        request("DescribeInstances", owner).post("/").then().statusCode(200)
                .body("DescribeInstancesResponse.reservationSet.item.size()", equalTo(0));
        request("DescribeNetworkInterfaces", owner).post("/").then().statusCode(200)
                .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.size()", equalTo(0));
        if (template != null) {
            request("DeleteLaunchTemplate", owner).formParam("LaunchTemplateId", template)
                    .post("/").then().statusCode(200);
        }
    }

    @Test
    void describeInstanceStatusCompletesTheLaunchWithItsAutomaticAddress() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        String instance = request("RunInstances", owner).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro").formParam("MinCount", 1).formParam("MaxCount", 1)
                .post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        request("DescribeInstanceStatus", owner).formParam("InstanceId.1", instance).post("/").then()
                .statusCode(200)
                .body("DescribeInstanceStatusResponse.instanceStatusSet.item.instanceState.name", equalTo("running"));

        String publicIp = describe(owner, instance).extract().xmlPath().getString(INSTANCE + "ipAddress");
        assertTrue(publicIp.matches(DOCUMENTATION_ADDRESS), publicIp);
        request("TerminateInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
    }

    @Test
    void aTerminatedInstanceStaysTerminatedWithoutAnAddress() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        String instance = request("RunInstances", owner).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro").formParam("MinCount", 1).formParam("MaxCount", 1)
                .post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        assertTrue(publicIp(owner, instance).matches(DOCUMENTATION_ADDRESS));
        request("TerminateInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);

        // Whatever StopInstances answers, it must not bring the instance back to be started again.
        request("StopInstances", owner).formParam("InstanceId.1", instance).post("/");
        request("StartInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("IncorrectInstanceState"));
        describe(owner, instance).body(INSTANCE + "instanceState.name", equalTo("terminated"));
        assertEquals("", publicIp(owner, instance));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void elasticIpReplacesTheAutomaticAddressUntilItIsDisassociated(boolean automatic) {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        String vpc = request("CreateVpc", owner).formParam("CidrBlock", "10.87.0.0/16")
                .post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        String subnet = request("CreateSubnet", owner).formParam("VpcId", vpc)
                .formParam("CidrBlock", "10.87.1.0/24").formParam("AvailabilityZone", REGION + "a")
                .post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
        request("ModifySubnetAttribute", owner).formParam("SubnetId", subnet)
                .formParam("MapPublicIpOnLaunch.Value", automatic).post("/").then().statusCode(200);
        String instance = request("RunInstances", owner).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro").formParam("MinCount", 1).formParam("MaxCount", 1)
                .formParam("SubnetId", subnet).post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        String launched = publicIp(owner, instance);
        assertEquals(automatic, !launched.isEmpty());
        assertAssociation(owner, instance, automatic ? launched : null, "amazon");

        ValidatableResponse allocated = request("AllocateAddress", owner).formParam("Domain", "vpc")
                .post("/").then().statusCode(200);
        String allocation = allocated.extract().path("AllocateAddressResponse.allocationId");
        String elastic = allocated.extract().path("AllocateAddressResponse.publicIp");
        String association = request("AssociateAddress", owner).formParam("AllocationId", allocation)
                .formParam("InstanceId", instance).post("/").then().statusCode(200)
                .extract().path("AssociateAddressResponse.associationId");
        assertEquals(elastic, publicIp(owner, instance));
        // An Elastic IP is owned by the account, and DescribeNetworkInterfaces says the same.
        assertAssociation(owner, instance, elastic, owner);
        String eni = describe(owner, instance).extract().xmlPath()
                .getString(INSTANCE + "networkInterfaceSet.item.networkInterfaceId");
        XmlPath interfaceView = request("DescribeNetworkInterfaces", owner).formParam("NetworkInterfaceId.1", eni)
                .post("/").then().statusCode(200).extract().xmlPath();
        assertEquals(elastic, interfaceView.getString("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.association.publicIp"));
        assertEquals(owner, interfaceView.getString("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.association.ipOwnerId"));
        request("StopInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        assertEquals(elastic, publicIp(owner, instance));
        assertAssociation(owner, instance, elastic, owner);
        request("StartInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        assertEquals(elastic, publicIp(owner, instance));

        request("DisassociateAddress", owner).formParam("AssociationId", association).post("/").then()
                .statusCode(200);
        String replacement = publicIp(owner, instance);
        assertEquals(automatic, !replacement.isEmpty());
        assertNotEquals(elastic, replacement);
        assertAssociation(owner, instance, automatic ? replacement : null, "amazon");

        request("AssociateAddress", owner).formParam("AllocationId", allocation)
                .formParam("InstanceId", instance).post("/").then().statusCode(200);
        // A second address associated with the same instance replaces the first, which stays
        // allocated, so a later DescribeAddresses cannot flip the instance back to it.
        ValidatableResponse second = request("AllocateAddress", owner).formParam("Domain", "vpc")
                .post("/").then().statusCode(200);
        String secondAllocation = second.extract().path("AllocateAddressResponse.allocationId");
        String secondElastic = second.extract().path("AllocateAddressResponse.publicIp");
        request("AssociateAddress", owner).formParam("AllocationId", secondAllocation)
                .formParam("InstanceId", instance).post("/").then().statusCode(200);
        request("DescribeAddresses", owner).formParam("AllocationId.1", allocation).post("/").then()
                .statusCode(200)
                .body("DescribeAddressesResponse.addressesSet.item.associationId.size()", equalTo(0));
        assertEquals(secondElastic, publicIp(owner, instance));

        request("TerminateInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        assertEquals("", publicIp(owner, instance));
        // Whatever the association answers, a terminated instance takes no public address.
        String lateAssociation = request("AssociateAddress", owner).formParam("AllocationId", allocation)
                .formParam("InstanceId", instance).post("/").then()
                .extract().path("AssociateAddressResponse.associationId");
        assertEquals("", publicIp(owner, instance));
        if (lateAssociation != null) {
            request("DisassociateAddress", owner).formParam("AssociationId", lateAssociation).post("/").then()
                    .statusCode(200);
        }
        request("ReleaseAddress", owner).formParam("AllocationId", allocation).post("/").then().statusCode(200);
        request("ReleaseAddress", owner).formParam("AllocationId", secondAllocation).post("/").then().statusCode(200);
        request("DeleteSubnet", owner).formParam("SubnetId", subnet).post("/").then().statusCode(200);
        request("DeleteVpc", owner).formParam("VpcId", vpc).post("/").then().statusCode(200);
    }

    @Test
    void secondAddressDisplacesTheOneARetainedInterfaceCarried() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        String vpc = request("CreateVpc", owner).formParam("CidrBlock", "10.89.0.0/16")
                .post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        String subnet = request("CreateSubnet", owner).formParam("VpcId", vpc)
                .formParam("CidrBlock", "10.89.1.0/24").formParam("AvailabilityZone", REGION + "a")
                .post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
        String eni = request("CreateNetworkInterface", owner).formParam("SubnetId", subnet)
                .post("/").then().statusCode(200)
                .extract().path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");
        String first = launchOnInterface(owner, eni);
        ValidatableResponse carried = request("AllocateAddress", owner).formParam("Domain", "vpc")
                .post("/").then().statusCode(200);
        String carriedAllocation = carried.extract().path("AllocateAddressResponse.allocationId");
        request("AssociateAddress", owner).formParam("AllocationId", carriedAllocation)
                .formParam("InstanceId", first).post("/").then().statusCode(200);
        // The interface outlives the instance and keeps the address associated with it.
        request("TerminateInstances", owner).formParam("InstanceId.1", first).post("/").then().statusCode(200);

        String second = launchOnInterface(owner, eni);
        ValidatableResponse replacement = request("AllocateAddress", owner).formParam("Domain", "vpc")
                .post("/").then().statusCode(200);
        String replacementAllocation = replacement.extract().path("AllocateAddressResponse.allocationId");
        String replacementIp = replacement.extract().path("AllocateAddressResponse.publicIp");
        String replacementAssociation = request("AssociateAddress", owner)
                .formParam("AllocationId", replacementAllocation).formParam("InstanceId", second)
                .post("/").then().statusCode(200).extract().path("AssociateAddressResponse.associationId");

        request("DescribeAddresses", owner).formParam("AllocationId.1", carriedAllocation).post("/").then()
                .statusCode(200)
                .body("DescribeAddressesResponse.addressesSet.item.associationId.size()", equalTo(0));
        assertEquals(replacementIp, publicIp(owner, second));

        request("TerminateInstances", owner).formParam("InstanceId.1", second).post("/").then().statusCode(200);
        request("DisassociateAddress", owner).formParam("AssociationId", replacementAssociation).post("/").then()
                .statusCode(200);
        request("ReleaseAddress", owner).formParam("AllocationId", replacementAllocation).post("/").then()
                .statusCode(200);
        request("ReleaseAddress", owner).formParam("AllocationId", carriedAllocation).post("/").then().statusCode(200);
        request("DeleteNetworkInterface", owner).formParam("NetworkInterfaceId", eni).post("/").then().statusCode(200);
        request("DeleteSubnet", owner).formParam("SubnetId", subnet).post("/").then().statusCode(200);
        request("DeleteVpc", owner).formParam("VpcId", vpc).post("/").then().statusCode(200);
    }

    @Test
    void elasticIpOnASecondaryAddressLeavesThePrimaryAddressAndTheInstanceAlone() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        String vpc = request("CreateVpc", owner).formParam("CidrBlock", "10.90.0.0/16")
                .post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        String subnet = request("CreateSubnet", owner).formParam("VpcId", vpc)
                .formParam("CidrBlock", "10.90.1.0/24").formParam("AvailabilityZone", REGION + "a")
                .post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
        String eni = request("CreateNetworkInterface", owner).formParam("SubnetId", subnet)
                .formParam("PrivateIpAddress", "10.90.1.10")
                .formParam("PrivateIpAddresses.1.PrivateIpAddress", "10.90.1.11")
                .post("/").then().statusCode(200)
                .extract().path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");
        String instance = launchOnInterface(owner, eni);
        ValidatableResponse primary = allocateAddress(owner);
        String primaryAllocation = primary.extract().path("AllocateAddressResponse.allocationId");
        String primaryIp = primary.extract().path("AllocateAddressResponse.publicIp");
        ValidatableResponse secondary = allocateAddress(owner);
        String secondaryAllocation = secondary.extract().path("AllocateAddressResponse.allocationId");
        String secondaryIp = secondary.extract().path("AllocateAddressResponse.publicIp");
        request("AssociateAddress", owner).formParam("AllocationId", primaryAllocation)
                .formParam("InstanceId", instance).post("/").then().statusCode(200);
        request("AssociateAddress", owner).formParam("AllocationId", secondaryAllocation)
                .formParam("InstanceId", instance).formParam("PrivateIpAddress", "10.90.1.11")
                .post("/").then().statusCode(200);

        // Each address stays on its own private address, and the instance shows the primary's.
        address(owner, primaryAllocation).body(ADDRESS + "instanceId", equalTo(instance))
                .body(ADDRESS + "privateIpAddress", equalTo("10.90.1.10"));
        address(owner, secondaryAllocation).body(ADDRESS + "instanceId", equalTo(instance))
                .body(ADDRESS + "privateIpAddress", equalTo("10.90.1.11"));
        request("DescribeNetworkInterfaces", owner).formParam("NetworkInterfaceId.1", eni).post("/").then()
                .statusCode(200)
                .body(INTERFACE_ADDRESS + "find { it.privateIpAddress == '10.90.1.10' }.association.publicIp",
                        equalTo(primaryIp))
                .body(INTERFACE_ADDRESS + "find { it.privateIpAddress == '10.90.1.11' }.association.publicIp",
                        equalTo(secondaryIp));
        assertEquals(primaryIp, publicIp(owner, instance));
        assertAssociation(owner, instance, primaryIp, owner);
        // The secondary address's Elastic IP is not the instance's, at either level.
        String instanceXml = describe(owner, instance).extract().asString();
        assertFalse(instanceXml.contains(secondaryIp), instanceXml);

        // A new address for the primary private address displaces only the address already there.
        ValidatableResponse replacement = allocateAddress(owner);
        String replacementAllocation = replacement.extract().path("AllocateAddressResponse.allocationId");
        String replacementIp = replacement.extract().path("AllocateAddressResponse.publicIp");
        request("AssociateAddress", owner).formParam("AllocationId", replacementAllocation)
                .formParam("InstanceId", instance).post("/").then().statusCode(200);
        address(owner, primaryAllocation).body(ADDRESS + "associationId.size()", equalTo(0));
        address(owner, secondaryAllocation).body(ADDRESS + "privateIpAddress", equalTo("10.90.1.11"));
        assertEquals(replacementIp, publicIp(owner, instance));

        request("TerminateInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        // The retained interface keeps its associations, which must go before the release.
        for (String allocation : List.of(secondaryAllocation, replacementAllocation)) {
            String association = address(owner, allocation).extract().path(ADDRESS + "associationId");
            request("DisassociateAddress", owner).formParam("AssociationId", association).post("/").then()
                    .statusCode(200);
        }
        for (String allocation : List.of(primaryAllocation, secondaryAllocation, replacementAllocation)) {
            request("ReleaseAddress", owner).formParam("AllocationId", allocation).post("/").then().statusCode(200);
        }
        request("DeleteNetworkInterface", owner).formParam("NetworkInterfaceId", eni).post("/").then().statusCode(200);
        request("DeleteSubnet", owner).formParam("SubnetId", subnet).post("/").then().statusCode(200);
        request("DeleteVpc", owner).formParam("VpcId", vpc).post("/").then().statusCode(200);
    }

    @Test
    void anExistingInterfaceTakesNoAutomaticAddressButKeepsTheElasticIpItCarries() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        String vpc = request("CreateVpc", owner).formParam("CidrBlock", "10.91.0.0/16")
                .post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        String subnet = request("CreateSubnet", owner).formParam("VpcId", vpc)
                .formParam("CidrBlock", "10.91.1.0/24").formParam("AvailabilityZone", REGION + "a")
                .post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
        request("ModifySubnetAttribute", owner).formParam("SubnetId", subnet)
                .formParam("MapPublicIpOnLaunch.Value", true).post("/").then().statusCode(200);
        String eni = request("CreateNetworkInterface", owner).formParam("SubnetId", subnet)
                .post("/").then().statusCode(200)
                .extract().path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");
        // AWS assigns an automatic public address only to an interface the launch creates.
        String first = launchOnInterface(owner, eni);
        assertEquals("", publicIp(owner, first));
        ValidatableResponse carried = allocateAddress(owner);
        String allocation = carried.extract().path("AllocateAddressResponse.allocationId");
        String elastic = carried.extract().path("AllocateAddressResponse.publicIp");
        String association = request("AssociateAddress", owner).formParam("AllocationId", allocation)
                .formParam("InstanceId", first).post("/").then().statusCode(200)
                .extract().path("AssociateAddressResponse.associationId");
        assertEquals(elastic, publicIp(owner, first));
        request("TerminateInstances", owner).formParam("InstanceId.1", first).post("/").then().statusCode(200);

        // The interface outlives the instance and brings its address to the next one launched on it.
        String second = launchOnInterface(owner, eni);
        assertEquals(elastic, publicIp(owner, second));
        address(owner, allocation).body(ADDRESS + "associationId", equalTo(association))
                .body(ADDRESS + "networkInterfaceId", equalTo(eni));
        request("StopInstances", owner).formParam("InstanceId.1", second).post("/").then().statusCode(200);
        assertEquals(elastic, publicIp(owner, second));
        request("StartInstances", owner).formParam("InstanceId.1", second).post("/").then().statusCode(200);
        assertEquals(elastic, publicIp(owner, second));
        request("DisassociateAddress", owner).formParam("AssociationId", association).post("/").then()
                .statusCode(200);
        assertEquals("", publicIp(owner, second));

        request("TerminateInstances", owner).formParam("InstanceId.1", second).post("/").then().statusCode(200);
        request("ReleaseAddress", owner).formParam("AllocationId", allocation).post("/").then().statusCode(200);
        request("DeleteNetworkInterface", owner).formParam("NetworkInterfaceId", eni).post("/").then().statusCode(200);
        request("DeleteSubnet", owner).formParam("SubnetId", subnet).post("/").then().statusCode(200);
        request("DeleteVpc", owner).formParam("VpcId", vpc).post("/").then().statusCode(200);
    }

    @Test
    void aTemplateInterfaceNamingAnExistingInterfaceLaunchesOnIt() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        String vpc = request("CreateVpc", owner).formParam("CidrBlock", "10.93.0.0/16")
                .post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        String subnet = request("CreateSubnet", owner).formParam("VpcId", vpc)
                .formParam("CidrBlock", "10.93.1.0/24").formParam("AvailabilityZone", REGION + "a")
                .post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
        request("ModifySubnetAttribute", owner).formParam("SubnetId", subnet)
                .formParam("MapPublicIpOnLaunch.Value", true).post("/").then().statusCode(200);
        String eni = request("CreateNetworkInterface", owner).formParam("SubnetId", subnet)
                .post("/").then().statusCode(200)
                .extract().path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");
        String template = request("CreateLaunchTemplate", owner).formParam("LaunchTemplateName", "existing-interface")
                .formParam("LaunchTemplateData.ImageId", "ami-amazonlinux2023")
                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                .formParam("LaunchTemplateData.NetworkInterface.1.DeviceIndex", 0)
                .formParam("LaunchTemplateData.NetworkInterface.1.NetworkInterfaceId", eni)
                .post("/").then().statusCode(200)
                .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
        String instance = request("RunInstances", owner).formParam("MinCount", 1).formParam("MaxCount", 1)
                .formParam("LaunchTemplate.LaunchTemplateId", template).post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");

        // The interface the template names becomes the primary, so the launch lands in its subnet
        // and, being on an existing interface, takes no automatic address.
        describe(owner, instance).body(INSTANCE + "networkInterfaceSet.item.networkInterfaceId", equalTo(eni))
                .body(INSTANCE + "subnetId", equalTo(subnet));
        assertEquals("", publicIp(owner, instance));

        request("TerminateInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        request("DeleteLaunchTemplate", owner).formParam("LaunchTemplateId", template).post("/").then().statusCode(200);
        request("DeleteNetworkInterface", owner).formParam("NetworkInterfaceId", eni).post("/").then().statusCode(200);
        request("DeleteSubnet", owner).formParam("SubnetId", subnet).post("/").then().statusCode(200);
        request("DeleteVpc", owner).formParam("VpcId", vpc).post("/").then().statusCode(200);
    }

    @Test
    void anElasticIpForAnUnknownPrivateAddressIsRefusedWithoutDisplacingAnything() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        ValidatableResponse launched = request("RunInstances", owner).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro").formParam("MinCount", 1).formParam("MaxCount", 1)
                .post("/").then().statusCode(200);
        String instance = launched.extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        String privateIp = launched.extract().path("RunInstancesResponse.instancesSet.item.privateIpAddress");
        ValidatableResponse primary = allocateAddress(owner);
        String primaryAllocation = primary.extract().path("AllocateAddressResponse.allocationId");
        String primaryIp = primary.extract().path("AllocateAddressResponse.publicIp");
        String primaryAssociation = request("AssociateAddress", owner).formParam("AllocationId", primaryAllocation)
                .formParam("InstanceId", instance).post("/").then().statusCode(200)
                .extract().path("AssociateAddressResponse.associationId");
        ValidatableResponse other = allocateAddress(owner);
        String otherAllocation = other.extract().path("AllocateAddressResponse.allocationId");
        String otherIp = other.extract().path("AllocateAddressResponse.publicIp");

        request("AssociateAddress", owner).formParam("AllocationId", otherAllocation)
                .formParam("InstanceId", instance).formParam("PrivateIpAddress", "10.99.99.99")
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
        address(owner, primaryAllocation).body(ADDRESS + "associationId", equalTo(primaryAssociation));
        address(owner, otherAllocation).body(ADDRESS + "associationId.size()", equalTo(0));
        assertEquals(primaryIp, publicIp(owner, instance));

        // Naming the primary private address itself is the same as naming none.
        request("AssociateAddress", owner).formParam("AllocationId", otherAllocation)
                .formParam("InstanceId", instance).formParam("PrivateIpAddress", privateIp)
                .post("/").then().statusCode(200);
        address(owner, primaryAllocation).body(ADDRESS + "associationId.size()", equalTo(0));
        assertEquals(otherIp, publicIp(owner, instance));

        request("TerminateInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        request("ReleaseAddress", owner).formParam("AllocationId", primaryAllocation).post("/").then().statusCode(200);
        request("ReleaseAddress", owner).formParam("AllocationId", otherAllocation).post("/").then().statusCode(200);
    }

    @Test
    void aSecondInterfaceWithholdsTheReplacementAutomaticAddress() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        String vpc = request("CreateVpc", owner).formParam("CidrBlock", "10.92.0.0/16")
                .post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        String subnet = request("CreateSubnet", owner).formParam("VpcId", vpc)
                .formParam("CidrBlock", "10.92.1.0/24").formParam("AvailabilityZone", REGION + "a")
                .post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
        request("ModifySubnetAttribute", owner).formParam("SubnetId", subnet)
                .formParam("MapPublicIpOnLaunch.Value", true).post("/").then().statusCode(200);
        String instance = request("RunInstances", owner).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro").formParam("MinCount", 1).formParam("MaxCount", 1)
                .formParam("SubnetId", subnet).post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        assertTrue(publicIp(owner, instance).matches(DOCUMENTATION_ADDRESS));
        // AssociateAddress by InstanceId needs exactly one interface, so the address goes on first.
        ValidatableResponse allocated = allocateAddress(owner);
        String allocation = allocated.extract().path("AllocateAddressResponse.allocationId");
        String elastic = allocated.extract().path("AllocateAddressResponse.publicIp");
        String association = request("AssociateAddress", owner).formParam("AllocationId", allocation)
                .formParam("InstanceId", instance).post("/").then().statusCode(200)
                .extract().path("AssociateAddressResponse.associationId");
        assertEquals(elastic, publicIp(owner, instance));
        String extra = request("CreateNetworkInterface", owner).formParam("SubnetId", subnet)
                .post("/").then().statusCode(200)
                .extract().path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");
        String attachment = request("AttachNetworkInterface", owner).formParam("NetworkInterfaceId", extra)
                .formParam("InstanceId", instance).formParam("DeviceIndex", 1)
                .post("/").then().statusCode(200).extract().path("AttachNetworkInterfaceResponse.attachmentId");

        // A released public address is not replaced while a second interface is attached.
        request("DisassociateAddress", owner).formParam("AssociationId", association).post("/").then()
                .statusCode(200);
        assertEquals("", publicIp(owner, instance));
        request("StopInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        request("StartInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        assertEquals("", publicIp(owner, instance));

        // With the second interface gone, starting the still-running instance changes nothing, and
        // the next real start assigns an address again.
        request("DetachNetworkInterface", owner).formParam("AttachmentId", attachment).post("/").then()
                .statusCode(200);
        request("StartInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        assertEquals("", publicIp(owner, instance));
        request("StopInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        request("StartInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        assertTrue(publicIp(owner, instance).matches(DOCUMENTATION_ADDRESS));

        request("TerminateInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        request("ReleaseAddress", owner).formParam("AllocationId", allocation).post("/").then().statusCode(200);
        request("DeleteNetworkInterface", owner).formParam("NetworkInterfaceId", extra).post("/").then().statusCode(200);
        request("DeleteSubnet", owner).formParam("SubnetId", subnet).post("/").then().statusCode(200);
        request("DeleteVpc", owner).formParam("VpcId", vpc).post("/").then().statusCode(200);
    }

    private ValidatableResponse allocateAddress(String owner) {
        return request("AllocateAddress", owner).formParam("Domain", "vpc").post("/").then().statusCode(200);
    }

    private ValidatableResponse address(String owner, String allocation) {
        return request("DescribeAddresses", owner).formParam("AllocationId.1", allocation).post("/").then()
                .statusCode(200);
    }

    private String launchOnInterface(String owner, String networkInterface) {
        return request("RunInstances", owner).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro").formParam("MinCount", 1).formParam("MaxCount", 1)
                .formParam("NetworkInterface.1.DeviceIndex", 0)
                .formParam("NetworkInterface.1.NetworkInterfaceId", networkInterface)
                .post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
    }

    @Test
    void fleetLaunchesIntoThePrimaryInterfaceSubnetWhateverTheInterfaceOrder() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        String vpc = request("CreateVpc", owner).formParam("CidrBlock", "10.88.0.0/16")
                .post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        String secondary = request("CreateSubnet", owner).formParam("VpcId", vpc)
                .formParam("CidrBlock", "10.88.1.0/24").formParam("AvailabilityZone", REGION + "b")
                .post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
        String primary = request("CreateSubnet", owner).formParam("VpcId", vpc)
                .formParam("CidrBlock", "10.88.2.0/24").formParam("AvailabilityZone", REGION + "a")
                .post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
        String template = request("CreateLaunchTemplate", owner).formParam("LaunchTemplateName", "fleet-primary")
                .formParam("LaunchTemplateData.ImageId", "ami-amazonlinux2023")
                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                .formParam("LaunchTemplateData.NetworkInterface.1.DeviceIndex", 1)
                .formParam("LaunchTemplateData.NetworkInterface.1.SubnetId", secondary)
                .formParam("LaunchTemplateData.NetworkInterface.2.DeviceIndex", 0)
                .formParam("LaunchTemplateData.NetworkInterface.2.SubnetId", primary)
                .formParam("LaunchTemplateData.NetworkInterface.2.AssociatePublicIpAddress", true)
                .post("/").then().statusCode(200)
                .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
        String instance = request("CreateFleet", owner).formParam("Type", "instant")
                .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.LaunchTemplateId", template)
                .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.Version", "1")
                .formParam("TargetCapacitySpecification.TotalTargetCapacity", 1)
                .formParam("TargetCapacitySpecification.DefaultTargetCapacityType", "on-demand")
                .post("/").then().statusCode(200)
                .extract().path("CreateFleetResponse.fleetInstanceSet.item.instanceIds.item");

        describe(owner, instance).body(INSTANCE + "subnetId", equalTo(primary))
                .body(INSTANCE + "placement.availabilityZone", equalTo(REGION + "a"));
        assertTrue(publicIp(owner, instance).matches(DOCUMENTATION_ADDRESS));
        request("TerminateInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        request("DeleteLaunchTemplate", owner).formParam("LaunchTemplateId", template).post("/").then().statusCode(200);
        request("DeleteSubnet", owner).formParam("SubnetId", primary).post("/").then().statusCode(200);
        request("DeleteSubnet", owner).formParam("SubnetId", secondary).post("/").then().statusCode(200);
        request("DeleteVpc", owner).formParam("VpcId", vpc).post("/").then().statusCode(200);
    }

    /**
     * Reads the primary interface's association back through DescribeInstances, at the interface
     * and again on its primary private address, where AWS repeats it; null expects none at either.
     */
    private void assertAssociation(String owner, String instance, String publicIp, String ipOwnerId) {
        XmlPath xml = describe(owner, instance).extract().xmlPath();
        String onInterface = INSTANCE + "networkInterfaceSet.item.association";
        String onPrimary = INSTANCE + "networkInterfaceSet.item.privateIpAddressesSet.item"
                + ".find { it.primary == 'true' }.association";
        List<Object> items = xml.getList(onInterface);
        List<Object> primaryItems = xml.getList(INSTANCE
                + "networkInterfaceSet.item.privateIpAddressesSet.item.association");
        if (publicIp == null) {
            assertEquals(0, items.size(), "unexpected association " + items);
            assertEquals(0, primaryItems.size(), "unexpected private address association " + primaryItems);
        } else {
            assertEquals(1, items.size());
            assertEquals(1, primaryItems.size());
            assertEquals(publicIp, xml.getString(onInterface + ".publicIp"));
            assertEquals(ipOwnerId, xml.getString(onInterface + ".ipOwnerId"));
            for (String field : List.of("publicIp", "publicDnsName", "ipOwnerId")) {
                assertEquals(xml.getString(onInterface + "." + field), xml.getString(onPrimary + "." + field),
                        field + " differs between the interface and its primary private address");
            }
        }
    }

    @Test
    void onlyThePrimaryInterfaceCarriesTheInstanceAssociation() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        String instance = request("RunInstances", owner).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro").formParam("MinCount", 1).formParam("MaxCount", 1)
                .post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        String subnet = describe(owner, instance).extract().xmlPath().getString(INSTANCE + "subnetId");
        String second = request("CreateNetworkInterface", owner).formParam("SubnetId", subnet)
                .post("/").then().statusCode(200)
                .extract().path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");
        request("AttachNetworkInterface", owner).formParam("NetworkInterfaceId", second)
                .formParam("InstanceId", instance).formParam("DeviceIndex", 1).post("/").then().statusCode(200);
        XmlPath xml = describe(owner, instance).extract().xmlPath();
        assertEquals(2, xml.getList(INSTANCE + "networkInterfaceSet.item").size());
        assertEquals(1, xml.getList(INSTANCE + "networkInterfaceSet.item.association").size());
        assertEquals(0, xml.getList(INSTANCE + "networkInterfaceSet.item.find { it.networkInterfaceId == '"
                + second + "' }.association").size());
        assertEquals(1, xml.getList(INSTANCE + "networkInterfaceSet.item.privateIpAddressesSet.item.association").size());
        assertEquals(0, xml.getList(INSTANCE + "networkInterfaceSet.item.find { it.networkInterfaceId == '"
                + second + "' }.privateIpAddressesSet.item.association").size());
        request("TerminateInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
    }

    private String publicIp(String owner, String instance) {
        return describe(owner, instance).extract().xmlPath().getString(INSTANCE + "ipAddress");
    }

    private ValidatableResponse describe(String owner, String instance) {
        return request("DescribeInstances", owner).formParam("InstanceId.1", instance)
                .post("/").then().statusCode(200);
    }

    private RequestSpecification request(String action, String owner) {
        String service = action.contains("AutoScaling") ? "autoscaling" : "ec2";
        return given().formParam("Action", action).header("Authorization", EnforcementFixtures.auth(owner, service));
    }

    /** Mock instances due an automatic address take one from 203.0.113.0/24. */
    public static class DocumentationAddresses implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ec2.mock-public-addresses", "documentation");
        }
    }
}
