package io.github.hectorvent.floci.services.autoscaling;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class AutoScalingMultiAccountIntegrationTest {

    private static final String ACCOUNT_ID = "222222222222";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT_ID + "/20260917/us-east-1/autoscaling/aws4_request";

    @Inject
    AutoScalingReconciler reconciler;

    @Test
    void reconcilesAGroupOwnedByANonDefaultAccount() {
        String suffix = UUID.randomUUID().toString();
        String launchConfigurationName = "multi-account-lc-" + suffix;
        String groupName = "multi-account-asg-" + suffix;

        given()
            .formParam("Action", "CreateLaunchConfiguration")
            .formParam("LaunchConfigurationName", launchConfigurationName)
            .formParam("ImageId", "ami-amazonlinux2023")
            .formParam("InstanceType", "t3.micro")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "CreateAutoScalingGroup")
            .formParam("AutoScalingGroupName", groupName)
            .formParam("LaunchConfigurationName", launchConfigurationName)
            .formParam("MinSize", "1")
            .formParam("MaxSize", "1")
            .formParam("DesiredCapacity", "1")
            .formParam("AvailabilityZones.member.1", "us-east-1a")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        reconciler.reconcileAll();
        reconciler.reconcileAll();

        given()
            .formParam("Action", "DescribeAutoScalingGroups")
            .formParam("AutoScalingGroupNames.member.1", groupName)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult"
                    + ".AutoScalingGroups.member.Instances.member.size()", equalTo(1));

        given()
            .formParam("Action", "DescribeScalingActivities")
            .formParam("AutoScalingGroupName", groupName)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeScalingActivitiesResponse.DescribeScalingActivitiesResult"
                    + ".Activities.member.size()", equalTo(1));
    }
}
