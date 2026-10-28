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

/**
 * {@code DescribeAutoScalingInstances} as the EC2 Auto Scaling API reference shows it: every member
 * names the group it belongs to, a required member of {@code AutoScalingInstanceDetails}, and spells
 * its health in capitals ({@code HEALTHY}). The same instances keep the {@code Healthy} spelling in the
 * instance list of {@code DescribeAutoScalingGroups}, which is also what {@code SetInstanceHealth} accepts.
 */
@QuarkusTest
class AutoScalingInstanceDetailsIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=434343434343/20261028/us-east-1/autoscaling/aws4_request";
    private static final String EC2_AUTH = AUTH.replace("/autoscaling/", "/ec2/");
    private static final String DETAILS =
            "DescribeAutoScalingInstancesResponse.DescribeAutoScalingInstancesResult.AutoScalingInstances.member";
    private static final String GROUP_INSTANCES =
            "DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member.Instances.member";

    @Inject
    AutoScalingReconciler reconciler;

    @Test
    void eachInstanceNamesItsGroupAndReportsHealthInCapitals() {
        String name = "instance-details-" + UUID.randomUUID();
        String other = name + "-other";
        given().header("Authorization", EC2_AUTH).formParam("Action", "CreateLaunchTemplate")
                .formParam("LaunchTemplateName", name)
                .formParam("LaunchTemplateData.ImageId", "ami-12345678")
                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                .post("/").then().statusCode(200);
        createGroup(name, name, 2);
        createGroup(other, name, 1);
        try {
            // The first pass launches the members and the second puts them in service. Floci's
            // reconciler overwrites health with Healthy when it promotes a Pending member, so a
            // member is marked unhealthy only once both are in service.
            reconciler.reconcileAll();
            reconciler.reconcileAll();
            XmlPath launched = groupInstances(name);
            assertEquals(List.of("InService", "InService"), launched.getList(GROUP_INSTANCES + ".LifecycleState"));
            List<String> members = launched.getList(GROUP_INSTANCES + ".InstanceId");
            String healthy = members.get(0);
            String unhealthy = members.get(1);
            String otherMember = groupInstances(other).getString(GROUP_INSTANCES + ".InstanceId");
            // AWS replaces an unhealthy member; the group keeps it here for the assertions.
            given().header("Authorization", AUTH).formParam("Action", "SuspendProcesses")
                    .formParam("AutoScalingGroupName", name)
                    .formParam("ScalingProcesses.member.1", "ReplaceUnhealthy")
                    .post("/").then().statusCode(200);
            given().header("Authorization", AUTH).formParam("Action", "SetInstanceHealth")
                    .formParam("InstanceId", unhealthy).formParam("HealthStatus", "Unhealthy")
                    .formParam("ShouldRespectGracePeriod", "false")
                    .post("/").then().statusCode(200);

            XmlPath details = given().header("Authorization", AUTH)
                    .formParam("Action", "DescribeAutoScalingInstances")
                    .post("/").then().statusCode(200).extract().xmlPath();
            assertEquals(3, details.getList(DETAILS + ".InstanceId").size());
            assertAll(
                    () -> assertEquals(3, details.getList(DETAILS + ".AutoScalingGroupName").size(),
                            "AutoScalingGroupName is a required member of every AutoScalingInstanceDetails"),
                    () -> assertEquals(name, member(details, healthy, "AutoScalingGroupName")),
                    () -> assertEquals(name, member(details, unhealthy, "AutoScalingGroupName")),
                    () -> assertEquals(other, member(details, otherMember, "AutoScalingGroupName")),
                    () -> assertEquals("HEALTHY", member(details, healthy, "HealthStatus")),
                    () -> assertEquals("UNHEALTHY", member(details, unhealthy, "HealthStatus")),
                    () -> assertEquals("HEALTHY", member(details, otherMember, "HealthStatus")));

            XmlPath filtered = given().header("Authorization", AUTH)
                    .formParam("Action", "DescribeAutoScalingInstances")
                    .formParam("InstanceIds.member.1", otherMember)
                    .post("/").then().statusCode(200).extract().xmlPath();
            assertEquals(List.of(other), filtered.getList(DETAILS + ".AutoScalingGroupName"));

            XmlPath group = groupInstances(name);
            assertEquals("Healthy", member(group, GROUP_INSTANCES, healthy, "HealthStatus"));
            assertEquals("Unhealthy", member(group, GROUP_INSTANCES, unhealthy, "HealthStatus"));
        } finally {
            for (String group : List.of(name, other)) {
                given().header("Authorization", AUTH).formParam("Action", "DeleteAutoScalingGroup")
                        .formParam("AutoScalingGroupName", group).formParam("ForceDelete", "true").post("/");
            }
            given().header("Authorization", EC2_AUTH).formParam("Action", "DeleteLaunchTemplate")
                    .formParam("LaunchTemplateName", name).post("/");
        }
    }

    private static void createGroup(String group, String launchTemplate, int desiredCapacity) {
        given().header("Authorization", AUTH).formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", group)
                .formParam("LaunchTemplate.LaunchTemplateName", launchTemplate)
                .formParam("LaunchTemplate.Version", "1")
                .formParam("MinSize", "0").formParam("MaxSize", "3")
                .formParam("DesiredCapacity", String.valueOf(desiredCapacity))
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .post("/").then().statusCode(200);
    }

    private static XmlPath groupInstances(String group) {
        return given().header("Authorization", AUTH).formParam("Action", "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", group)
                .post("/").then().statusCode(200).extract().xmlPath();
    }

    private static String member(XmlPath xml, String instanceId, String field) {
        return member(xml, DETAILS, instanceId, field);
    }

    private static String member(XmlPath xml, String path, String instanceId, String field) {
        return xml.getString(path + ".find { it.InstanceId == '" + instanceId + "' }." + field);
    }
}
