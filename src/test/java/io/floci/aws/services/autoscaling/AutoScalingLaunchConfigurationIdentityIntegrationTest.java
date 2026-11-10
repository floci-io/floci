package io.floci.aws.services.autoscaling;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class AutoScalingLaunchConfigurationIdentityIntegrationTest {
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=222222222222/20261012/us-east-1/autoscaling/aws4_request";
    @Inject
    AutoScalingReconciler reconciler;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void launchResolvesProfile(boolean useArn) {
        String name = "launch-identity-" + UUID.randomUUID();
        XmlPath profile = given().header("Authorization", AUTH.replace("/autoscaling/", "/iam/"))
                .formParam("Action", "CreateInstanceProfile").formParam("InstanceProfileName", name)
                .post("/").then().statusCode(200).extract().xmlPath();
        String arn = profile.getString("CreateInstanceProfileResponse.CreateInstanceProfileResult.InstanceProfile.Arn");
        String profileId = profile.getString(
                "CreateInstanceProfileResponse.CreateInstanceProfileResult.InstanceProfile.InstanceProfileId");
        given().header("Authorization", AUTH).formParam("Action", "CreateLaunchConfiguration")
                .formParam("LaunchConfigurationName", name).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro").formParam("IamInstanceProfile", useArn ? arn : name)
                .post("/").then().statusCode(200);
        given().header("Authorization", AUTH).formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", name).formParam("LaunchConfigurationName", name)
                .formParam("MinSize", "1").formParam("MaxSize", "1").formParam("DesiredCapacity", "1")
                .formParam("AvailabilityZones.member.1", "us-east-1a").post("/").then().statusCode(200);
        reconciler.reconcileAll();
        String instance = given().header("Authorization", AUTH)
                .formParam("Action", "DescribeAutoScalingGroups").formParam("AutoScalingGroupNames.member.1", name)
                .post("/").then().statusCode(200)
                .body("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult"
                        + ".AutoScalingGroups.member.Instances.member.size()", equalTo(1))
                .extract().path("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult"
                        + ".AutoScalingGroups.member.Instances.member.InstanceId");
        given().header("Authorization", AUTH.replace("/autoscaling/", "/ec2/")).formParam("Action", "DescribeInstances")
                .formParam("InstanceId.1", instance).post("/").then().statusCode(200)
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.iamInstanceProfile.arn", equalTo(arn))
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.iamInstanceProfile.id", equalTo(profileId));
    }
    @Test
    void refreshReplacementResolvesProfile() {
        String name = "refresh-identity-" + UUID.randomUUID();
        String arn = given().header("Authorization", AUTH.replace("/autoscaling/", "/iam/"))
                .formParam("Action", "CreateInstanceProfile").formParam("InstanceProfileName", name)
                .post("/").then().statusCode(200).extract().path(
                        "CreateInstanceProfileResponse.CreateInstanceProfileResult.InstanceProfile.Arn");
        given().header("Authorization", AUTH).formParam("Action", "CreateLaunchConfiguration")
                .formParam("LaunchConfigurationName", name).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro").formParam("IamInstanceProfile", name)
                .post("/").then().statusCode(200);
        given().header("Authorization", AUTH).formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", name).formParam("LaunchConfigurationName", name)
                .formParam("MinSize", "1").formParam("MaxSize", "1").formParam("DesiredCapacity", "1")
                .formParam("AvailabilityZones.member.1", "us-east-1a").post("/").then().statusCode(200);
        String instancePath = "DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult"
                + ".AutoScalingGroups.member.Instances.member.InstanceId";
        reconciler.reconcileAll();
        reconciler.reconcileAll();
        String original = given().header("Authorization", AUTH).formParam("Action", "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", name).post("/").then().statusCode(200)
                .extract().path(instancePath);
        String refreshId = given().header("Authorization", AUTH).formParam("Action", "StartInstanceRefresh")
                .formParam("AutoScalingGroupName", name).post("/").then().statusCode(200).extract().path(
                        "StartInstanceRefreshResponse.StartInstanceRefreshResult.InstanceRefreshId");
        reconciler.reconcileAll();
        reconciler.reconcileAll();
        // The refresh completes once its replacement is in service.
        given().header("Authorization", AUTH).formParam("Action", "DescribeInstanceRefreshes")
                .formParam("AutoScalingGroupName", name).formParam("InstanceRefreshIds.member.1", refreshId)
                .post("/").then().statusCode(200)
                .body("DescribeInstanceRefreshesResponse.DescribeInstanceRefreshesResult"
                        + ".InstanceRefreshes.member.Status", equalTo("Successful"));
        String replacement = given().header("Authorization", AUTH).formParam("Action", "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", name).post("/").then().statusCode(200)
                .extract().path(instancePath);
        assertThat(replacement, not(equalTo(original)));
        String item = "DescribeInstancesResponse.reservationSet.item.instancesSet.item";
        given().header("Authorization", AUTH.replace("/autoscaling/", "/ec2/")).formParam("Action", "DescribeInstances")
                .formParam("InstanceId.1", replacement).post("/").then().statusCode(200)
                .body(item + ".iamInstanceProfile.arn", equalTo(arn));
        given().header("Authorization", AUTH).formParam("Action", "DeleteAutoScalingGroup")
                .formParam("AutoScalingGroupName", name).formParam("ForceDelete", "true")
                .post("/").then().statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "mixed", "update"})
    void groupRefusesALaunchTemplateNamingBothProfileSelectors(String request) {
        String name = "pair-template-" + UUID.randomUUID();
        String ec2 = AUTH.replace("/autoscaling/", "/ec2/");
        String arn = given().header("Authorization", AUTH.replace("/autoscaling/", "/iam/"))
                .formParam("Action", "CreateInstanceProfile").formParam("InstanceProfileName", name)
                .post("/").then().statusCode(200).extract().path(
                        "CreateInstanceProfileResponse.CreateInstanceProfileResult.InstanceProfile.Arn");
        String template = given().header("Authorization", ec2)
                .formParam("Action", "CreateLaunchTemplate").formParam("LaunchTemplateName", name)
                .formParam("LaunchTemplateData.ImageId", "ami-amazonlinux2023")
                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                .formParam("LaunchTemplateData.IamInstanceProfile.Name", name)
                .post("/").then().statusCode(200)
                .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
        // Version 2 adds the ARN beside the name; version 1 names the profile once.
        given().header("Authorization", ec2).formParam("Action", "CreateLaunchTemplateVersion")
                .formParam("LaunchTemplateId", template)
                .formParam("LaunchTemplateData.ImageId", "ami-amazonlinux2023")
                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                .formParam("LaunchTemplateData.IamInstanceProfile.Name", name)
                .formParam("LaunchTemplateData.IamInstanceProfile.Arn", arn)
                .post("/").then().statusCode(200);
        String prefix = "mixed".equals(request)
                ? "MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification." : "LaunchTemplate.";
        if ("update".equals(request)) {
            given().header("Authorization", AUTH).formParam("Action", "CreateAutoScalingGroup")
                    .formParam("AutoScalingGroupName", name).formParam(prefix + "LaunchTemplateId", template)
                    .formParam(prefix + "Version", "1")
                    .formParam("MinSize", "0").formParam("MaxSize", "1").formParam("DesiredCapacity", "0")
                    .formParam("AvailabilityZones.member.1", "us-east-1a").post("/").then().statusCode(200);
        }
        given().header("Authorization", AUTH)
                .formParam("Action", "update".equals(request) ? "UpdateAutoScalingGroup" : "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", name).formParam(prefix + "LaunchTemplateId", template)
                .formParam(prefix + "Version", "2")
                .formParam("MinSize", "0").formParam("MaxSize", "1").formParam("DesiredCapacity", "1")
                .formParam("AvailabilityZones.member.1", "us-east-1a").post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", equalTo("You must use a valid fully-formed launch template. "
                        + "The parameter iamInstanceProfile.name may not be used with iamInstanceProfile.arn."));
        String group = "DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member";
        ValidatableResponse described = given().header("Authorization", AUTH)
                .formParam("Action", "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", name).post("/").then().statusCode(200);
        if ("update".equals(request)) {
            described.body(group + ".LaunchTemplate.Version", equalTo("1"));
            given().header("Authorization", AUTH).formParam("Action", "DeleteAutoScalingGroup")
                    .formParam("AutoScalingGroupName", name).formParam("ForceDelete", "true")
                    .post("/").then().statusCode(200);
        } else {
            described.body(group + ".size()", equalTo(0));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrForeignProfileRefusesLaunchConfiguration(boolean foreignArn) {
        String name = "refused-profile-" + UUID.randomUUID();
        if (foreignArn) {
            given().header("Authorization", AUTH.replace("/autoscaling/", "/iam/"))
                    .formParam("Action", "CreateInstanceProfile").formParam("InstanceProfileName", name)
                    .post("/").then().statusCode(200);
        }
        given().header("Authorization", AUTH).formParam("Action", "CreateLaunchConfiguration")
                .formParam("LaunchConfigurationName", name).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro").formParam("IamInstanceProfile",
                        foreignArn ? "arn:aws:iam::333333333333:instance-profile/" + name : name)
                .post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
        given().header("Authorization", AUTH).formParam("Action", "DescribeLaunchConfigurations")
                .formParam("LaunchConfigurationNames.member.1", name).post("/").then().statusCode(200)
                .body("DescribeLaunchConfigurationsResponse.DescribeLaunchConfigurationsResult"
                        + ".LaunchConfigurations.member.size()", equalTo(0));
    }

}
