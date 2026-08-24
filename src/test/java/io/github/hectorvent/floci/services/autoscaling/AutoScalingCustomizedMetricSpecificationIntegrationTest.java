package io.github.hectorvent.floci.services.autoscaling;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * lex00/floci#119's own disclaimed sub-gap: PutScalingPolicy accepted
 * TargetTrackingConfiguration.CustomizedMetricSpecification and dropped it entirely (no field at
 * all, unlike PredefinedMetricSpecification which already round-tripped), so a TargetTracking
 * policy built on a customized CloudWatch metric (rather than one of AWS's predefined ones) came
 * back from DescribePolicies missing its whole metric definition. Oracle: botocore's
 * autoscaling/2011-01-01/service-2.json TargetTrackingConfiguration/CustomizedMetricSpecification
 * shapes.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AutoScalingCustomizedMetricSpecificationIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260501/us-east-1/autoscaling/aws4_request";

    @Test
    @Order(1)
    void createGroupForCustomizedMetricPolicy() {
        given()
                .formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", "customized-metric-asg")
                .formParam("MinSize", "0")
                .formParam("MaxSize", "1")
                .formParam("DesiredCapacity", "0")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .formParam("LaunchConfigurationName", "does-not-need-to-exist-for-this-test")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    @Test
    @Order(2)
    void putScalingPolicyRoundTripsCustomizedMetricSpecification() {
        given()
                .formParam("Action", "PutScalingPolicy")
                .formParam("AutoScalingGroupName", "customized-metric-asg")
                .formParam("PolicyName", "custom-metric-target")
                .formParam("PolicyType", "TargetTrackingScaling")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.MetricName", "RequestCountPerTarget")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Namespace", "AWS/ApplicationELB")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Dimensions.member.1.Name", "TargetGroup")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Dimensions.member.1.Value", "targetgroup/app/123")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Statistic", "Sum")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Unit", "Count")
                .formParam("TargetTrackingConfiguration.TargetValue", "1000")
                .formParam("TargetTrackingConfiguration.DisableScaleIn", "true")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("PolicyARN"));
    }

    @Test
    @Order(3)
    void describePoliciesEchoesCustomizedMetricSpecificationBack() {
        given()
                .formParam("Action", "DescribePolicies")
                .formParam("AutoScalingGroupName", "customized-metric-asg")
                .formParam("PolicyNames.member.1", "custom-metric-target")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<CustomizedMetricSpecification>"))
                .body(containsString("<MetricName>RequestCountPerTarget</MetricName>"))
                .body(containsString("<Namespace>AWS/ApplicationELB</Namespace>"))
                .body(containsString("<Name>TargetGroup</Name>"))
                .body(containsString("<Value>targetgroup/app/123</Value>"))
                .body(containsString("<Statistic>Sum</Statistic>"))
                .body(containsString("<Unit>Count</Unit>"))
                .body(containsString("<TargetValue>1000.0</TargetValue>"))
                .body(containsString("<DisableScaleIn>true</DisableScaleIn>"));
    }

    @Test
    @Order(4)
    void aPolicyThatNeverSetCustomizedMetricSpecificationOmitsItRatherThanInventingDefaults() {
        given()
                .formParam("Action", "PutScalingPolicy")
                .formParam("AutoScalingGroupName", "customized-metric-asg")
                .formParam("PolicyName", "predefined-metric-target")
                .formParam("PolicyType", "TargetTrackingScaling")
                .formParam("TargetTrackingConfiguration.PredefinedMetricSpecification.PredefinedMetricType",
                        "ASGAverageCPUUtilization")
                .formParam("TargetTrackingConfiguration.TargetValue", "50")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
                .formParam("Action", "DescribePolicies")
                .formParam("AutoScalingGroupName", "customized-metric-asg")
                .formParam("PolicyNames.member.1", "predefined-metric-target")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(not(containsString("CustomizedMetricSpecification")))
                .body(not(containsString("DisableScaleIn")));
    }
}
