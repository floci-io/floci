package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
class AutoScalingRefreshSuspensionIntegrationTest {
    @Inject
    AutoScalingReconciler reconciler;

    @Inject
    Ec2Service ec2;

    private static final String GROUP = "DescribeAutoScalingGroupsResponse."
            + "DescribeAutoScalingGroupsResult.AutoScalingGroups.member";
    private static final String REFRESH = "DescribeInstanceRefreshesResponse."
            + "DescribeInstanceRefreshesResult.InstanceRefreshes.member";

    @ParameterizedTest
    @ValueSource(strings = {"Launch", "Terminate", "InstanceRefresh", "all"})
    void pausedRefreshKeepsOriginalAndResumesToOneHealthyReplacement(String process) {
        String name = "refresh-pause-" + UUID.randomUUID().toString().substring(0, 8);
        request("CreateLaunchConfiguration").formParam("LaunchConfigurationName", name)
                .formParam("ImageId", "ami-amazonlinux2023").formParam("InstanceType", "t3.micro")
                .post("/").then().statusCode(200);
        try {
            request("CreateAutoScalingGroup").formParam("AutoScalingGroupName", name)
                    .formParam("LaunchConfigurationName", name).formParam("MinSize", 0)
                    .formParam("MaxSize", 1).formParam("DesiredCapacity", 1)
                    .formParam("AvailabilityZones.member.1", "us-east-1a")
                    .post("/").then().statusCode(200);
            try {
                reconcileTwice();
                assertEquals("InService", group(name).xmlPath().getString(GROUP + ".Instances.member.LifecycleState"));
                String originalId = group(name).xmlPath().getString(GROUP + ".Instances.member.InstanceId");
                process("SuspendProcesses", name, process);
                String refreshId = request("StartInstanceRefresh").formParam("AutoScalingGroupName", name)
                        .post("/").then().statusCode(200).extract().xmlPath()
                        .getString("StartInstanceRefreshResponse.StartInstanceRefreshResult.InstanceRefreshId");
                reconcileTwice();
                Response paused = group(name);
                assertEquals(1, paused.xmlPath().getInt(GROUP + ".DesiredCapacity"));
                assertEquals(1, paused.xmlPath().getList(GROUP + ".Instances.member").size());
                assertEquals(originalId, paused.xmlPath().getString(GROUP + ".Instances.member.InstanceId"));
                assertEquals("running", instanceState(originalId));
                Response refresh = refresh(name, refreshId);
                assertEquals("InProgress", refresh.xmlPath().getString(REFRESH + ".Status"));
                assertEquals(1, refresh.xmlPath().getInt(REFRESH + ".InstancesToUpdate"));
                process("ResumeProcesses", name, process);
                reconcileTwice();
                assertEquals("Successful", refresh(name, refreshId).xmlPath().getString(REFRESH + ".Status"));
                Response resumed = group(name);
                assertEquals(1, resumed.xmlPath().getList(GROUP + ".Instances.member").size());
                assertNotEquals(originalId, resumed.xmlPath().getString(GROUP + ".Instances.member.InstanceId"));
                assertEquals("InService", resumed.xmlPath().getString(GROUP + ".Instances.member.LifecycleState"));
                assertEquals("terminated", instanceState(originalId));
            } finally {
                request("DeleteAutoScalingGroup").formParam("AutoScalingGroupName", name)
                        .formParam("ForceDelete", true).post("/").then().statusCode(200);
            }
        } finally {
            request("DeleteLaunchConfiguration").formParam("LaunchConfigurationName", name)
                    .post("/").then().statusCode(200);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"configuration", "template"})
    void replacementRetainsEncodedUserDataAndSelectsTheCurrentSubnet(String source) {
        String name = "refresh-placement-" + UUID.randomUUID().toString().substring(0, 8);
        String encoded = "/w";
        String vpcId = ec2.createVpc("us-east-1", "10.92.0.0/16", false).getVpcId();
        List<String> subnets = new ArrayList<>();
        try {
            subnets.add(ec2.createSubnet("us-east-1", vpcId, "10.92.0.0/28", "us-east-1a").getSubnetId());
            subnets.add(ec2.createSubnet("us-east-1", vpcId, "10.92.1.0/24", "us-east-1b").getSubnetId());
            boolean configuration = "configuration".equals(source);
            RequestSpecification create = given().header("Authorization", auth(configuration ? "autoscaling" : "ec2"))
                    .formParam("Action", configuration ? "CreateLaunchConfiguration" : "CreateLaunchTemplate")
                    .formParam(configuration ? "LaunchConfigurationName" : "LaunchTemplateName", name);
            String prefix = configuration ? "" : "LaunchTemplateData.";
            if (!configuration) {
                create.formParam("LaunchTemplateData.BlockDeviceMapping.1.DeviceName", "/dev/xvda")
                        .formParam("LaunchTemplateData.BlockDeviceMapping.1.Ebs.VolumeType", "gp2");
            }
            create.formParam(prefix + "ImageId", "ami-amazonlinux2023")
                    .formParam(prefix + "InstanceType", "t3.micro").formParam(prefix + "UserData", encoded)
                    .post("/").then().statusCode(200);
            try {
                RequestSpecification createGroup = request("CreateAutoScalingGroup")
                        .formParam("AutoScalingGroupName", name).formParam("MinSize", 0)
                        .formParam("MaxSize", 1).formParam("DesiredCapacity", 1)
                        .formParam("VPCZoneIdentifier", subnets.getFirst())
                        .formParam("AvailabilityZones.member.1", "us-east-1a");
                if (configuration) {
                    createGroup.formParam("LaunchConfigurationName", name);
                } else {
                    createGroup.formParam("LaunchTemplate.LaunchTemplateName", name)
                            .formParam("LaunchTemplate.Version", "1");
                }
                createGroup.post("/").then().statusCode(200);
                try {
                    reconcileTwice();
                    String original = group(name).xmlPath().getString(GROUP + ".Instances.member.InstanceId");
                    assertEquals(subnets.getFirst(), ec2.getInstance("us-east-1", original).orElseThrow().getSubnetId());
                    assertEquals(configuration ? "gp3" : "gp2", rootVolumeType(original));
                    request("UpdateAutoScalingGroup").formParam("AutoScalingGroupName", name)
                            .formParam("VPCZoneIdentifier", String.join(",", subnets))
                            .post("/").then().statusCode(200);
                    String refreshId = request("StartInstanceRefresh").formParam("AutoScalingGroupName", name)
                            .post("/").then().statusCode(200).extract().xmlPath()
                            .getString("StartInstanceRefreshResponse.StartInstanceRefreshResult.InstanceRefreshId");
                    reconcileTwice();
                    Response current = group(name);
                    assertEquals(1, current.xmlPath().getList(GROUP + ".Instances.member").size());
                    String replacement = current.xmlPath().getString(GROUP + ".Instances.member.InstanceId");
                    assertNotEquals(original, replacement);
                    assertEquals(configuration ? "gp3" : "gp2", rootVolumeType(replacement));
                    assertEquals("terminated", instanceState(original));
                    assertEquals(subnets.getLast(), ec2.getInstance("us-east-1", replacement).orElseThrow().getSubnetId());
                    assertEquals("us-east-1b", current.xmlPath().getString(GROUP + ".Instances.member.AvailabilityZone"));
                    assertEquals("Successful", refresh(name, refreshId).xmlPath().getString(REFRESH + ".Status"));
                    assertEquals(encoded, given().header("Authorization", auth("ec2"))
                            .formParam("Action", "DescribeInstanceAttribute").formParam("InstanceId", replacement)
                            .formParam("Attribute", "userData").post("/").then().statusCode(200)
                            .extract().xmlPath().getString("DescribeInstanceAttributeResponse.userData.value"));
                } finally {
                    request("DeleteAutoScalingGroup").formParam("AutoScalingGroupName", name)
                            .formParam("ForceDelete", true).post("/").then().statusCode(200);
                }
            } finally {
                given().header("Authorization", auth(configuration ? "autoscaling" : "ec2"))
                        .formParam("Action", configuration ? "DeleteLaunchConfiguration" : "DeleteLaunchTemplate")
                        .formParam(configuration ? "LaunchConfigurationName" : "LaunchTemplateName", name)
                        .post("/").then().statusCode(200);
            }
        } finally {
            for (String subnet : subnets) {
                ec2.deleteSubnet("us-east-1", subnet);
            }
            ec2.deleteVpc("us-east-1", vpcId);
        }
    }

    @Test
    void sameNameRefreshesInDifferentAccountsResumeIndependently() {
        String name = "refresh-accounts-" + UUID.randomUUID().toString().substring(0, 8);
        List<String> accounts = List.of("111122223333", "444455556666");
        List<String> configurations = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        List<String> originals = new ArrayList<>();
        try {
            for (String account : accounts) {
                accountRequest(account, "CreateLaunchConfiguration")
                        .formParam("LaunchConfigurationName", name).formParam("ImageId", "ami-amazonlinux2023")
                        .formParam("InstanceType", "t3.micro").post("/").then().statusCode(200);
                configurations.add(account);
                accountRequest(account, "CreateAutoScalingGroup").formParam("AutoScalingGroupName", name)
                        .formParam("LaunchConfigurationName", name).formParam("MinSize", 0)
                        .formParam("MaxSize", 1).formParam("DesiredCapacity", 1)
                        .formParam("AvailabilityZones.member.1", "us-east-1a").post("/").then().statusCode(200);
                groups.add(account);
            }
            reconcileTwice();
            for (String account : accounts) {
                originals.add(accountGroup(account, name).xmlPath().getString(GROUP + ".Instances.member.InstanceId"));
                accountRequest(account, "SuspendProcesses").formParam("AutoScalingGroupName", name)
                        .formParam("ScalingProcesses.member.1", "InstanceRefresh").post("/").then().statusCode(200);
                accountRequest(account, "StartInstanceRefresh").formParam("AutoScalingGroupName", name)
                        .post("/").then().statusCode(200);
            }
            assertNotEquals(originals.getFirst(), originals.getLast());
            reconcileTwice();
            for (int index = 0; index < accounts.size(); index++) {
                assertEquals(originals.get(index), accountGroup(accounts.get(index), name)
                        .xmlPath().getString(GROUP + ".Instances.member.InstanceId"));
            }
            accountRequest(accounts.getFirst(), "ResumeProcesses").formParam("AutoScalingGroupName", name)
                    .formParam("ScalingProcesses.member.1", "InstanceRefresh").post("/").then().statusCode(200);
            reconcileTwice();
            assertNotEquals(originals.getFirst(), accountGroup(accounts.getFirst(), name)
                    .xmlPath().getString(GROUP + ".Instances.member.InstanceId"));
            assertEquals("Successful", accountRefreshStatus(accounts.getFirst(), name));
            assertEquals(originals.getLast(), accountGroup(accounts.getLast(), name)
                    .xmlPath().getString(GROUP + ".Instances.member.InstanceId"));
            assertEquals("InProgress", accountRefreshStatus(accounts.getLast(), name));
            accountRequest(accounts.getLast(), "ResumeProcesses").formParam("AutoScalingGroupName", name)
                    .formParam("ScalingProcesses.member.1", "InstanceRefresh").post("/").then().statusCode(200);
            reconcileTwice();
            assertNotEquals(originals.getLast(), accountGroup(accounts.getLast(), name)
                    .xmlPath().getString(GROUP + ".Instances.member.InstanceId"));
            assertEquals("Successful", accountRefreshStatus(accounts.getLast(), name));
        } finally {
            try {
                for (String account : groups) {
                    accountRequest(account, "DeleteAutoScalingGroup").formParam("AutoScalingGroupName", name)
                            .formParam("ForceDelete", true).post("/").then().statusCode(200);
                }
            } finally {
                for (String account : configurations) {
                    accountRequest(account, "DeleteLaunchConfiguration").formParam("LaunchConfigurationName", name)
                            .post("/").then().statusCode(200);
                }
            }
        }
    }

    private String rootVolumeType(String instanceId) {
        String volumeId = ec2.getInstance("us-east-1", instanceId).orElseThrow().getRootVolumeId();
        return given().header("Authorization", auth("ec2")).formParam("Action", "DescribeVolumes")
                .formParam("VolumeId.1", volumeId).post("/").then().statusCode(200)
                .extract().xmlPath().getString("DescribeVolumesResponse.volumeSet.item.volumeType");
    }

    private static String accountRefreshStatus(String account, String name) {
        return accountRequest(account, "DescribeInstanceRefreshes").formParam("AutoScalingGroupName", name)
                .post("/").then().statusCode(200).extract().xmlPath().getString(REFRESH + ".Status");
    }

    private static Response accountGroup(String account, String name) {
        return accountRequest(account, "DescribeAutoScalingGroups").formParam("AutoScalingGroupNames.member.1", name)
                .post("/").then().statusCode(200).extract().response();
    }

    private static RequestSpecification accountRequest(String account, String action) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                + "/20260215/us-east-1/autoscaling/aws4_request, SignedHeaders=host, Signature=abc")
                .formParam("Action", action);
    }

    private void reconcileTwice() {
        reconciler.reconcileAll();
        reconciler.reconcileAll();
    }

    private static Response group(String name) {
        return request("DescribeAutoScalingGroups").formParam("AutoScalingGroupNames.member.1", name)
                .post("/").then().statusCode(200).extract().response();
    }

    private static Response refresh(String name, String refreshId) {
        return request("DescribeInstanceRefreshes").formParam("AutoScalingGroupName", name)
                .formParam("InstanceRefreshIds.member.1", refreshId)
                .post("/").then().statusCode(200).extract().response();
    }

    private static String instanceState(String id) {
        return given().header("Authorization", auth("ec2"))
                .formParam("Action", "DescribeInstances").formParam("InstanceId.1", id)
                .post("/").then().statusCode(200).extract().xmlPath()
                .getString("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceState.name");
    }

    private static void process(String action, String name, String process) {
        RequestSpecification request = request(action).formParam("AutoScalingGroupName", name);
        if (!"all".equals(process)) {
            request.formParam("ScalingProcesses.member.1", process);
        }
        request.post("/").then().statusCode(200);
    }

    private static RequestSpecification request(String action) {
        return given().header("Authorization", auth("autoscaling")).formParam("Action", action);
    }

    private static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260215/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
