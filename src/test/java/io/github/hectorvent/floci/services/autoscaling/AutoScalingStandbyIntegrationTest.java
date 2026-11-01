package io.github.hectorvent.floci.services.autoscaling;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The standby round trip a client stopping a node's traffic makes, over the Query API: lower MinSize,
 * EnterStandby with ShouldDecrementDesiredCapacity, wait for Standby, later ExitStandby and wait for
 * InService. On the way the instance leaves and rejoins the group's target group and Classic load balancer,
 * and the group launches no replacement for it.
 */
@QuarkusTest
class AutoScalingStandbyIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=616161616161/20261028/us-east-1/autoscaling/aws4_request";
    private static final String EC2_AUTH = AUTH.replace("/autoscaling/", "/ec2/");
    private static final String ELB_AUTH = AUTH.replace("/autoscaling/", "/elasticloadbalancing/");
    private static final String GROUP_PATH = "DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult"
            + ".AutoScalingGroups.member";
    private static final String ENTERED = "EnterStandbyResponse.EnterStandbyResult.Activities.member";
    private static final String EXITED = "ExitStandbyResponse.ExitStandbyResult.Activities.member";

    @Inject
    AutoScalingReconciler reconciler;

    @Test
    void anInstanceLeavesServiceForStandbyAndComesBack() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "standby-" + suffix;
        String loadBalancer = "sb-" + suffix;
        given().header("Authorization", EC2_AUTH).formParam("Action", "CreateLaunchTemplate")
                .formParam("LaunchTemplateName", name)
                .formParam("LaunchTemplateData.ImageId", "ami-12345678")
                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                .post("/").then().statusCode(200);
        String targetGroup = given().header("Authorization", ELB_AUTH).formParam("Action", "CreateTargetGroup")
                .formParam("Version", "2015-12-01").formParam("Name", loadBalancer)
                .formParam("Protocol", "HTTP").formParam("Port", "80").formParam("VpcId", "vpc-00000001")
                .formParam("TargetType", "instance")
                .post("/").then().statusCode(200).extract().xmlPath()
                .getString("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
        given().header("Authorization", ELB_AUTH).formParam("Action", "CreateLoadBalancer")
                .formParam("Version", "2012-06-01").formParam("LoadBalancerName", loadBalancer)
                .formParam("Listeners.member.1.Protocol", "TCP")
                .formParam("Listeners.member.1.LoadBalancerPort", "80")
                .formParam("Listeners.member.1.InstancePort", "80")
                .post("/").then().statusCode(200);
        given().header("Authorization", AUTH).formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", name)
                .formParam("LaunchTemplate.LaunchTemplateName", name)
                .formParam("LaunchTemplate.Version", "1")
                .formParam("MinSize", "2").formParam("MaxSize", "2").formParam("DesiredCapacity", "2")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .formParam("TargetGroupARNs.member.1", targetGroup)
                .formParam("LoadBalancerNames.member.1", loadBalancer)
                .post("/").then().statusCode(200);
        try {
            reconciler.reconcileAll();
            reconciler.reconcileAll();
            XmlPath launched = group(name);
            assertEquals(List.of("InService", "InService"), launched.getList(GROUP_PATH + ".Instances.member.LifecycleState"));
            List<String> members = launched.getList(GROUP_PATH + ".Instances.member.InstanceId");
            String standby = members.get(0);
            String serving = members.get(1);
            assertEquals(List.copyOf(members).stream().sorted().toList(), targets(targetGroup).stream().sorted().toList());

            // MinSize 2 leaves no room to decrement, so the client lowers it first.
            XmlPath refused = given().header("Authorization", AUTH).formParam("Action", "EnterStandby")
                    .formParam("AutoScalingGroupName", name).formParam("InstanceIds.member.1", standby)
                    .formParam("ShouldDecrementDesiredCapacity", "true")
                    .post("/").then().statusCode(400).extract().xmlPath();
            assertEquals("ValidationError", refused.getString("ErrorResponse.Error.Code"));
            assertEquals("AutoScalingGroup " + name + " has min-size=2, max-size=2, and desired-size=2. To place"
                    + " into standby 1 instances, please update the AutoScalingGroup sizes appropriately.",
                    refused.getString("ErrorResponse.Error.Message"));
            given().header("Authorization", AUTH).formParam("Action", "UpdateAutoScalingGroup")
                    .formParam("AutoScalingGroupName", name).formParam("MinSize", "0")
                    .post("/").then().statusCode(200);

            XmlPath entered = given().header("Authorization", AUTH).formParam("Action", "EnterStandby")
                    .formParam("AutoScalingGroupName", name).formParam("InstanceIds.member.1", standby)
                    .formParam("ShouldDecrementDesiredCapacity", "true")
                    .post("/").then().statusCode(200).extract().xmlPath();
            String enterActivity = entered.getString(ENTERED + ".ActivityId");
            assertAll(
                    () -> assertEquals(name, entered.getString(ENTERED + ".AutoScalingGroupName")),
                    () -> assertEquals("Moving EC2 instance to Standby: " + standby,
                            entered.getString(ENTERED + ".Description")),
                    () -> assertTrue(entered.getString(ENTERED + ".Cause").endsWith(" instance " + standby
                            + " was moved to standby in response to a user request, shrinking the capacity"
                            + " from 2 to 1.")),
                    () -> assertEquals("InProgress", entered.getString(ENTERED + ".StatusCode")),
                    () -> assertEquals("50", entered.getString(ENTERED + ".Progress")));

            reconciler.reconcileAll();
            assertEquals("Standby", given().header("Authorization", AUTH)
                    .formParam("Action", "DescribeAutoScalingInstances").formParam("InstanceIds.member.1", standby)
                    .post("/").then().statusCode(200).extract().xmlPath().getString(
                            "DescribeAutoScalingInstancesResponse.DescribeAutoScalingInstancesResult"
                                    + ".AutoScalingInstances.member.LifecycleState"));
            XmlPath inStandby = group(name);
            assertEquals("1", inStandby.getString(GROUP_PATH + ".DesiredCapacity"));
            assertEquals("Standby", member(inStandby, standby, "LifecycleState"));
            assertEquals("InService", member(inStandby, serving, "LifecycleState"));
            assertEquals(List.of(serving), targets(targetGroup));
            assertEquals(List.of(serving), classicInstances(loadBalancer));
            assertEquals("Successful", activity(name, enterActivity, "StatusCode"));
            assertEquals("The instance " + serving + " is not in Standby.", given().header("Authorization", AUTH)
                    .formParam("Action", "ExitStandby")
                    .formParam("AutoScalingGroupName", name).formParam("InstanceIds.member.1", serving)
                    .post("/").then().statusCode(400).extract().xmlPath().getString("ErrorResponse.Error.Message"));

            reconciler.reconcileAll();
            assertEquals(2, group(name).getList(GROUP_PATH + ".Instances.member.InstanceId").size(),
                    "The group launches no replacement for an instance whose capacity it gave up");

            XmlPath exited = given().header("Authorization", AUTH).formParam("Action", "ExitStandby")
                    .formParam("AutoScalingGroupName", name).formParam("InstanceIds.member.1", standby)
                    .post("/").then().statusCode(200).extract().xmlPath();
            String exitActivity = exited.getString(EXITED + ".ActivityId");
            assertAll(
                    () -> assertEquals("Moving EC2 instance out of Standby: " + standby,
                            exited.getString(EXITED + ".Description")),
                    () -> assertTrue(exited.getString(EXITED + ".Cause").endsWith(" instance " + standby
                            + " was moved out of standby in response to a user request, increasing the capacity"
                            + " from 1 to 2.")),
                    () -> assertEquals("PreInService", exited.getString(EXITED + ".StatusCode")),
                    () -> assertEquals("30", exited.getString(EXITED + ".Progress")));

            reconciler.reconcileAll();
            XmlPath back = group(name);
            assertEquals("2", back.getString(GROUP_PATH + ".DesiredCapacity"));
            assertEquals(List.of("InService", "InService"), back.getList(GROUP_PATH + ".Instances.member.LifecycleState"));
            assertEquals(List.of(serving, standby).stream().sorted().toList(), targets(targetGroup).stream().sorted().toList());
            assertEquals(List.of(serving, standby).stream().sorted().toList(),
                    classicInstances(loadBalancer).stream().sorted().toList());
            assertEquals("Successful", activity(name, exitActivity, "StatusCode"));
        } finally {
            given().header("Authorization", AUTH).formParam("Action", "DeleteAutoScalingGroup")
                    .formParam("AutoScalingGroupName", name).formParam("ForceDelete", "true").post("/");
            given().header("Authorization", EC2_AUTH).formParam("Action", "DeleteLaunchTemplate")
                    .formParam("LaunchTemplateName", name).post("/");
            given().header("Authorization", ELB_AUTH).formParam("Action", "DeleteLoadBalancer")
                    .formParam("Version", "2012-06-01").formParam("LoadBalancerName", loadBalancer).post("/");
            given().header("Authorization", ELB_AUTH).formParam("Action", "DeleteTargetGroup")
                    .formParam("Version", "2015-12-01").formParam("TargetGroupArn", targetGroup).post("/");
        }
    }

    @Test
    void errorsUseTheQueryErrorShape() {
        XmlPath missing = given().header("Authorization", AUTH).formParam("Action", "EnterStandby")
                .formParam("AutoScalingGroupName", "no-such-standby-group")
                .formParam("InstanceIds.member.1", "i-0123456789abcdef0")
                .formParam("ShouldDecrementDesiredCapacity", "false")
                .post("/").then().statusCode(400).extract().xmlPath();
        assertEquals("ValidationError", missing.getString("ErrorResponse.Error.Code"));
        assertEquals("Sender", missing.getString("ErrorResponse.Error.Type"));

        XmlPath noDecrement = given().header("Authorization", AUTH).formParam("Action", "EnterStandby")
                .formParam("AutoScalingGroupName", "no-such-standby-group")
                .post("/").then().statusCode(400).extract().xmlPath();
        assertEquals("1 validation error detected: Value null at 'shouldDecrementDesiredCapacity' failed to"
                + " satisfy constraint: Member must not be null", noDecrement.getString("ErrorResponse.Error.Message"));
    }

    private static XmlPath group(String name) {
        return given().header("Authorization", AUTH).formParam("Action", "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", name)
                .post("/").then().statusCode(200).extract().xmlPath();
    }

    private static String member(XmlPath group, String instanceId, String field) {
        return group.getString(GROUP_PATH + ".Instances.member.find { it.InstanceId == '" + instanceId + "' }."
                + field);
    }

    private static List<String> targets(String targetGroup) {
        return given().header("Authorization", ELB_AUTH).formParam("Action", "DescribeTargetHealth")
                .formParam("Version", "2015-12-01").formParam("TargetGroupArn", targetGroup)
                .post("/").then().statusCode(200).extract().xmlPath()
                .getList("DescribeTargetHealthResponse.DescribeTargetHealthResult.TargetHealthDescriptions"
                        + ".member.Target.Id", String.class);
    }

    private static List<String> classicInstances(String loadBalancer) {
        return given().header("Authorization", ELB_AUTH).formParam("Action", "DescribeLoadBalancers")
                .formParam("Version", "2012-06-01").formParam("LoadBalancerNames.member.1", loadBalancer)
                .post("/").then().statusCode(200).extract().xmlPath()
                .getList("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancerDescriptions"
                        + ".member.Instances.member.InstanceId", String.class);
    }

    private static String activity(String group, String activityId, String field) {
        return given().header("Authorization", AUTH).formParam("Action", "DescribeScalingActivities")
                .formParam("AutoScalingGroupName", group)
                .post("/").then().statusCode(200).extract().xmlPath()
                .getString("DescribeScalingActivitiesResponse.DescribeScalingActivitiesResult.Activities.member"
                        + ".find { it.ActivityId == '" + activityId + "' }." + field);
    }
}
