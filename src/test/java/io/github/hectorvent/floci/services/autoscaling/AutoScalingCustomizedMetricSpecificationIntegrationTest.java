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
 *
 * <p>lex00/floci#122: PR #121's round only modeled CustomizedMetricSpecification's classic
 * bare-metric fields (MetricName/Namespace/Dimensions/Statistic/Unit - tested above by
 * {@link #describePoliciesEchoesCustomizedMetricSpecificationBack()}), which is why that test was
 * green while choudoufu's corpus-autoscaling-complete crossing still saw the field vanish: the
 * real module under test (terraform-aws-modules/terraform-aws-autoscaling's own "complete"
 * example, "metric_math" scaling policy) uses CustomizedMetricSpecification's OTHER shape -
 * Metrics, a list of TargetTrackingMetricDataQuery entries doing CloudWatch metric math - which
 * had no field at all. {@link #putScalingPolicyRoundTripsMetricMathCustomizedMetricSpecification()}
 * and {@link #describePoliciesEchoesMetricMathCustomizedMetricSpecificationBack()} below reproduce
 * that exact shape (Id/Expression/MetricStat/Label/ReturnData) against the real Describe response,
 * not the store.
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

    @Test
    @Order(5)
    void putScalingPolicyRoundTripsMetricMathCustomizedMetricSpecification() {
        // Same shape as terraform-aws-modules/terraform-aws-autoscaling's "complete" example's
        // "metric_math" scaling policy (choudoufu's corpus-autoscaling-complete crossing): two
        // MetricStat queries (m1, m2) plus one Expression query (e1) computing a backlog ratio.
        given()
                .formParam("Action", "PutScalingPolicy")
                .formParam("AutoScalingGroupName", "customized-metric-asg")
                .formParam("PolicyName", "metric-math-target")
                .formParam("PolicyType", "TargetTrackingScaling")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Metrics.member.1.Id", "m1")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Metrics.member.1.Label",
                        "Get the queue size (the number of messages waiting to be processed)")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Metrics.member.1.MetricStat.Metric.Namespace",
                        "AWS/SQS")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Metrics.member.1.MetricStat.Metric.MetricName",
                        "ApproximateNumberOfMessagesVisible")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Metrics.member.1.MetricStat.Metric.Dimensions.member.1.Name",
                        "QueueName")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Metrics.member.1.MetricStat.Metric.Dimensions.member.1.Value",
                        "my-queue")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Metrics.member.1.MetricStat.Stat", "Sum")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Metrics.member.1.ReturnData", "false")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Metrics.member.2.Id", "m2")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Metrics.member.2.Label",
                        "Get the group size (the number of InService instances)")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Metrics.member.2.MetricStat.Metric.Namespace",
                        "AWS/AutoScaling")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Metrics.member.2.MetricStat.Metric.MetricName",
                        "GroupInServiceInstances")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Metrics.member.2.MetricStat.Metric.Dimensions.member.1.Name",
                        "AutoScalingGroupName")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Metrics.member.2.MetricStat.Metric.Dimensions.member.1.Value",
                        "customized-metric-asg")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Metrics.member.2.MetricStat.Stat", "Average")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Metrics.member.2.ReturnData", "true")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Metrics.member.3.Id", "e1")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Metrics.member.3.Label",
                        "Calculate the backlog per instance")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Metrics.member.3.Expression", "m1 / m2")
                .formParam("TargetTrackingConfiguration.CustomizedMetricSpecification.Metrics.member.3.ReturnData", "false")
                .formParam("TargetTrackingConfiguration.TargetValue", "100")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("PolicyARN"));
    }

    @Test
    @Order(6)
    void describePoliciesEchoesMetricMathCustomizedMetricSpecificationBack() {
        given()
                .formParam("Action", "DescribePolicies")
                .formParam("AutoScalingGroupName", "customized-metric-asg")
                .formParam("PolicyNames.member.1", "metric-math-target")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<CustomizedMetricSpecification>"))
                .body(containsString("<Metrics>"))
                .body(containsString("<Id>m1</Id>"))
                .body(containsString("<Namespace>AWS/SQS</Namespace>"))
                .body(containsString("<MetricName>ApproximateNumberOfMessagesVisible</MetricName>"))
                .body(containsString("<Name>QueueName</Name>"))
                .body(containsString("<Value>my-queue</Value>"))
                .body(containsString("<Stat>Sum</Stat>"))
                .body(containsString("<ReturnData>false</ReturnData>"))
                .body(containsString("<Id>m2</Id>"))
                .body(containsString("<Namespace>AWS/AutoScaling</Namespace>"))
                .body(containsString("<MetricName>GroupInServiceInstances</MetricName>"))
                .body(containsString("<Stat>Average</Stat>"))
                .body(containsString("<ReturnData>true</ReturnData>"))
                .body(containsString("<Id>e1</Id>"))
                .body(containsString("<Expression>m1 / m2</Expression>"))
                .body(containsString("<TargetValue>100.0</TargetValue>"));
    }
}
