package io.github.hectorvent.floci.services.autoscaling;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code aws:autoscaling:groupName} tag over a member's lifecycle, as the EC2 Auto Scaling user
 * guide describes it: the group adds it to the instances it launches and attaches, attaching also
 * applies the group's PropagateAtLaunch tags, and detaching removes only that one tag.
 */
@QuarkusTest
class AutoScalingInstanceMembershipTagIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=414141414141/20261022/us-east-1/autoscaling/aws4_request";
    private static final String EC2_AUTH = AUTH.replace("/autoscaling/", "/ec2/");
    private static final String GROUP_NAME_TAG = "aws:autoscaling:groupName";

    @Inject
    AutoScalingReconciler reconciler;

    @Test
    void groupNameTagFollowsLaunchAttachAndDetach() {
        String name = "membership-" + UUID.randomUUID();
        String other = name + "-other";
        given().header("Authorization", EC2_AUTH).formParam("Action", "CreateLaunchTemplate")
                .formParam("LaunchTemplateName", name)
                .formParam("LaunchTemplateData.ImageId", "ami-12345678")
                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                .formParam("LaunchTemplateData.TagSpecification.1.ResourceType", "instance")
                .formParam("LaunchTemplateData.TagSpecification.1.Tag.1.Key", "Source")
                .formParam("LaunchTemplateData.TagSpecification.1.Tag.1.Value", "template")
                .post("/").then().statusCode(200);
        given().header("Authorization", AUTH).formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", name)
                .formParam("LaunchTemplate.LaunchTemplateName", name).formParam("LaunchTemplate.Version", "1")
                .formParam("MinSize", "0").formParam("MaxSize", "3").formParam("DesiredCapacity", "1")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .formParam("Tags.member.1.Key", "Team").formParam("Tags.member.1.Value", "blue")
                .formParam("Tags.member.1.PropagateAtLaunch", "true")
                .formParam("Tags.member.2.Key", "Private").formParam("Tags.member.2.Value", "group-only")
                .formParam("Tags.member.2.PropagateAtLaunch", "false")
                .post("/").then().statusCode(200);
        given().header("Authorization", AUTH).formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", other)
                .formParam("LaunchTemplate.LaunchTemplateName", name).formParam("LaunchTemplate.Version", "1")
                .formParam("MinSize", "0").formParam("MaxSize", "3").formParam("DesiredCapacity", "0")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .post("/").then().statusCode(200);
        String standalone = null;
        try {
            reconciler.reconcileAll();
            String launched = given().header("Authorization", AUTH)
                    .formParam("Action", "DescribeAutoScalingGroups").formParam("AutoScalingGroupNames.member.1", name)
                    .post("/").then().statusCode(200)
                    .body("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult"
                            + ".AutoScalingGroups.member.Instances.member.size()", equalTo(1))
                    .extract().path("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult"
                            + ".AutoScalingGroups.member.Instances.member.InstanceId");
            assertEquals(Map.of("Source", "template", "Team", "blue"), userTags(launched));
            assertEquals(name, instanceTags(launched).get(GROUP_NAME_TAG));
            assertEquals(List.of(launched), instancesTaggedWith(name));

            standalone = given().header("Authorization", EC2_AUTH).formParam("Action", "RunInstances")
                    .formParam("ImageId", "ami-12345678").formParam("InstanceType", "t3.micro")
                    .formParam("MinCount", "1").formParam("MaxCount", "1")
                    .formParam("TagSpecification.1.ResourceType", "instance")
                    .formParam("TagSpecification.1.Tag.1.Key", "Team")
                    .formParam("TagSpecification.1.Tag.1.Value", "red")
                    .formParam("TagSpecification.1.Tag.2.Key", "Owner")
                    .formParam("TagSpecification.1.Tag.2.Value", "me")
                    .post("/").then().statusCode(200)
                    .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
            attach(name, standalone).statusCode(200);
            assertEquals(Map.of("Owner", "me", "Team", "blue"), userTags(standalone));
            assertEquals(name, instanceTags(standalone).get(GROUP_NAME_TAG));

            // An ID EC2 has no instance for gets no tags, which would otherwise outlive it in DescribeTags.
            String unknown = "i-0abcdef1234567890";
            attach(other, unknown);
            given().header("Authorization", EC2_AUTH).formParam("Action", "DescribeTags")
                    .formParam("Filter.1.Name", "resource-id").formParam("Filter.1.Value.1", unknown)
                    .post("/").then().statusCode(200)
                    .body("DescribeTagsResponse.tagSet.item.size()", equalTo(0));

            String alreadyMember = "[The Instance: " + standalone + " is already part of AutoScalingGroup:" + name + "]";
            for (String group : List.of(name, other)) {
                attach(group, standalone).statusCode(400)
                        .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                        .body("ErrorResponse.Error.Message", equalTo(alreadyMember));
            }
            assertEquals(name, instanceTags(standalone).get(GROUP_NAME_TAG));

            given().header("Authorization", AUTH).formParam("Action", "DetachInstances")
                    .formParam("AutoScalingGroupName", name).formParam("InstanceIds.member.1", standalone)
                    .formParam("ShouldDecrementDesiredCapacity", "true")
                    .post("/").then().statusCode(200);
            assertEquals(Map.of("Owner", "me", "Team", "blue"), instanceTags(standalone));
            assertEquals(List.of(launched), instancesTaggedWith(name));
        } finally {
            for (String group : List.of(name, other)) {
                given().header("Authorization", AUTH).formParam("Action", "DeleteAutoScalingGroup")
                        .formParam("AutoScalingGroupName", group).formParam("ForceDelete", "true").post("/");
            }
            if (standalone != null) {
                given().header("Authorization", EC2_AUTH).formParam("Action", "TerminateInstances")
                        .formParam("InstanceId.1", standalone).post("/");
            }
            given().header("Authorization", EC2_AUTH).formParam("Action", "DeleteLaunchTemplate")
                    .formParam("LaunchTemplateName", name).post("/");
        }
    }

    private static ValidatableResponse attach(String group, String instanceId) {
        return given().header("Authorization", AUTH).formParam("Action", "AttachInstances")
                .formParam("AutoScalingGroupName", group).formParam("InstanceIds.member.1", instanceId)
                .post("/").then();
    }

    private static Map<String, String> instanceTags(String instanceId) {
        XmlPath xml = given().header("Authorization", EC2_AUTH).formParam("Action", "DescribeInstances")
                .formParam("InstanceId.1", instanceId).post("/").then().statusCode(200).extract().xmlPath();
        String item = "DescribeInstancesResponse.reservationSet.item.instancesSet.item.tagSet.item";
        List<String> keys = xml.getList(item + ".key");
        List<String> values = xml.getList(item + ".value");
        Map<String, String> tags = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            tags.put(keys.get(i), values.get(i));
        }
        return tags;
    }

    /** The instance's tags without the {@code aws:} ones AWS adds on its own, such as launch template ids. */
    private static Map<String, String> userTags(String instanceId) {
        Map<String, String> tags = instanceTags(instanceId);
        tags.keySet().removeIf(key -> key.startsWith("aws:"));
        return tags;
    }

    private static List<String> instancesTaggedWith(String group) {
        return given().header("Authorization", EC2_AUTH).formParam("Action", "DescribeInstances")
                .formParam("Filter.1.Name", "tag:" + GROUP_NAME_TAG).formParam("Filter.1.Value.1", group)
                .post("/").then().statusCode(200).extract().xmlPath()
                .getList("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceId");
    }
}
