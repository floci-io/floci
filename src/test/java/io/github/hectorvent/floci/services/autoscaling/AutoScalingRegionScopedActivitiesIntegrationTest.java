package io.github.hectorvent.floci.services.autoscaling;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

/** Scaling activities are stored for every region; DescribeScalingActivities returns only the request region's. */
@QuarkusTest
class AutoScalingRegionScopedActivitiesIntegrationTest {

    private static final String HOME = "eu-west-1";
    private static final String OTHER = "us-west-2";

    @Inject
    AutoScalingReconciler reconciler;

    private static String auth(String region, String service) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260215/" + region + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    @Test
    void autoScalingActivitiesStayInTheirRegion() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String launchConfig = "region-lc-" + suffix;
        String group = "region-asg-" + suffix;

        given().header("Authorization", auth(HOME, "autoscaling"))
                .formParam("Action", "CreateLaunchConfiguration")
                .formParam("LaunchConfigurationName", launchConfig)
                .formParam("ImageId", "ami-amazonlinux2023").formParam("InstanceType", "t3.micro")
            .when().post("/").then().statusCode(200);
        given().header("Authorization", auth(HOME, "autoscaling"))
                .formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", group)
                .formParam("LaunchConfigurationName", launchConfig)
                .formParam("MinSize", "1").formParam("MaxSize", "1").formParam("DesiredCapacity", "1")
                .formParam("AvailabilityZones.member.1", HOME + "a")
            .when().post("/").then().statusCode(200);
        reconciler.reconcileAll();
        reconciler.reconcileAll();

        given().header("Authorization", auth(HOME, "autoscaling"))
                .formParam("Action", "DescribeScalingActivities").formParam("AutoScalingGroupName", group)
            .when().post("/").then().statusCode(200)
            .body("DescribeScalingActivitiesResponse.DescribeScalingActivitiesResult.Activities.member.size()",
                    greaterThanOrEqualTo(1));
        given().header("Authorization", auth(OTHER, "autoscaling"))
                .formParam("Action", "DescribeScalingActivities").formParam("AutoScalingGroupName", group)
            .when().post("/").then().statusCode(200)
            .body("DescribeScalingActivitiesResponse.DescribeScalingActivitiesResult.Activities.member.size()",
                    equalTo(0));

        given().header("Authorization", auth(HOME, "autoscaling"))
                .formParam("Action", "DeleteAutoScalingGroup").formParam("AutoScalingGroupName", group)
                .formParam("ForceDelete", "true")
            .when().post("/").then().statusCode(200);
        given().header("Authorization", auth(HOME, "autoscaling"))
                .formParam("Action", "DeleteLaunchConfiguration").formParam("LaunchConfigurationName", launchConfig)
            .when().post("/").then().statusCode(200);
    }
}
