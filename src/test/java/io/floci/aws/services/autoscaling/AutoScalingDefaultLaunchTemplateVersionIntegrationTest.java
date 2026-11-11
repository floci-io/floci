package io.floci.aws.services.autoscaling;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class AutoScalingDefaultLaunchTemplateVersionIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=796200000000/20261111/us-east-1/autoscaling/aws4_request";
    private static final String EC2_AUTH = AUTH.replace("/autoscaling/", "/ec2/");

    @Inject
    AutoScalingReconciler reconciler;

    @Test
    void omittedVersionUsesDefaultWhenLatestVersionHasNoImage() {
        String name = "default-lt-" + UUID.randomUUID();
        boolean groupCreated = false;
        given().header("Authorization", EC2_AUTH)
                .formParam("Action", "CreateLaunchTemplate")
                .formParam("LaunchTemplateName", name)
                .formParam("LaunchTemplateData.ImageId", "ami-12345678")
                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                .post("/").then().statusCode(200);
        try {
            given().header("Authorization", EC2_AUTH)
                    .formParam("Action", "CreateLaunchTemplateVersion")
                    .formParam("LaunchTemplateName", name)
                    .formParam("LaunchTemplateData.InstanceType", "t3.small")
                    .post("/").then().statusCode(200);

            ValidatableResponse created = given().header("Authorization", AUTH)
                    .formParam("Action", "CreateAutoScalingGroup")
                    .formParam("AutoScalingGroupName", name)
                    .formParam("LaunchTemplate.LaunchTemplateName", name)
                    .formParam("MinSize", "0")
                    .formParam("MaxSize", "1")
                    .formParam("DesiredCapacity", "0")
                    .formParam("AvailabilityZones.member.1", "us-east-1a")
                    .post("/").then().statusCode(200);
            groupCreated = true;
            created.body(containsString("CreateAutoScalingGroupResponse"));
        } finally {
            if (groupCreated) {
                given().header("Authorization", AUTH)
                        .formParam("Action", "DeleteAutoScalingGroup")
                        .formParam("AutoScalingGroupName", name)
                        .formParam("ForceDelete", "true")
                        .post("/").then().statusCode(200);
            }
            given().header("Authorization", EC2_AUTH)
                    .formParam("Action", "DeleteLaunchTemplate")
                    .formParam("LaunchTemplateName", name)
                    .post("/").then().statusCode(200);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void omittedVersionLaunchesFromDefaultVersion(boolean mixedInstancesPolicy) {
        String name = "default-launch-" + UUID.randomUUID();
        boolean groupCreated = false;
        given().header("Authorization", EC2_AUTH)
                .formParam("Action", "CreateLaunchTemplate")
                .formParam("LaunchTemplateName", name)
                .formParam("LaunchTemplateData.ImageId", "ami-amazonlinux2023")
                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                .post("/").then().statusCode(200);
        try {
            given().header("Authorization", EC2_AUTH)
                    .formParam("Action", "CreateLaunchTemplateVersion")
                    .formParam("LaunchTemplateName", name)
                    .formParam("LaunchTemplateData.ImageId", "ami-amazonlinux2023")
                    .formParam("LaunchTemplateData.InstanceType", "t3.small")
                    .post("/").then().statusCode(200);

            String prefix = mixedInstancesPolicy
                    ? "MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification." : "LaunchTemplate.";
            given().header("Authorization", AUTH)
                    .formParam("Action", "CreateAutoScalingGroup")
                    .formParam("AutoScalingGroupName", name)
                    .formParam(prefix + "LaunchTemplateName", name)
                    .formParam("MinSize", "0")
                    .formParam("MaxSize", "1")
                    .formParam("DesiredCapacity", "1")
                    .formParam("AvailabilityZones.member.1", "us-east-1a")
                    .post("/").then().statusCode(200);
            groupCreated = true;
            reconciler.reconcileAll();

            String instance = "DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult"
                    + ".AutoScalingGroups.member.Instances.member";
            given().header("Authorization", AUTH)
                    .formParam("Action", "DescribeAutoScalingGroups")
                    .formParam("AutoScalingGroupNames.member.1", name)
                    .post("/").then().statusCode(200)
                    .body(instance + ".size()", equalTo(1))
                    .body(instance + ".LaunchTemplate.Version", equalTo("1"));
        } finally {
            if (groupCreated) {
                given().header("Authorization", AUTH)
                        .formParam("Action", "DeleteAutoScalingGroup")
                        .formParam("AutoScalingGroupName", name)
                        .formParam("ForceDelete", "true")
                        .post("/").then().statusCode(200);
            }
            given().header("Authorization", EC2_AUTH)
                    .formParam("Action", "DeleteLaunchTemplate")
                    .formParam("LaunchTemplateName", name)
                    .post("/").then().statusCode(200);
        }
    }
}
