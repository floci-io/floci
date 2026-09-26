package io.github.hectorvent.floci.services.autoscaling;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * A target tracking policy round-trips every member the model gives it.
 *
 * <p>DescribePolicies carried only PredefinedMetricSpecification.PredefinedMetricType and
 * TargetValue, so CustomizedMetricSpecification, DisableScaleIn and ResourceLabel were accepted at
 * put and then absent from the read. A client comparing its configuration against the live policy
 * sees a difference that never resolves, however many times it re-applies.
 */
@QuarkusTest
class AutoScalingTargetTrackingRoundTripIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/autoscaling/aws4_request";

    private static ValidatableResponse asg(String action, String... formParams) {
        RequestSpecification request = given()
                .header("Authorization", AUTH)
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2011-01-01");
        for (int i = 0; i < formParams.length; i += 2) {
            request = request.formParam(formParams[i], formParams[i + 1]);
        }
        return request.when().post("/").then();
    }

    private static String group() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String launchConfiguration = "tt-lc-" + suffix;
        asg("CreateLaunchConfiguration", "LaunchConfigurationName", launchConfiguration,
                "ImageId", "ami-12345678", "InstanceType", "t3.micro").statusCode(200);
        String name = "tt-" + suffix;
        asg("CreateAutoScalingGroup", "AutoScalingGroupName", name,
                "LaunchConfigurationName", launchConfiguration,
                "MinSize", "0", "MaxSize", "2",
                "AvailabilityZones.member.1", "us-east-1a").statusCode(200);
        return name;
    }

    @Test
    void aCustomizedMetricSpecificationSurvivesTheRoundTrip() {
        String name = group();
        asg("PutScalingPolicy",
                "AutoScalingGroupName", name,
                "PolicyName", "custom",
                "PolicyType", "TargetTrackingScaling",
                "TargetTrackingConfiguration.TargetValue", "42.5",
                "TargetTrackingConfiguration.DisableScaleIn", "true",
                "TargetTrackingConfiguration.CustomizedMetricSpecification.MetricName", "queue_depth",
                "TargetTrackingConfiguration.CustomizedMetricSpecification.Namespace", "app/metrics",
                "TargetTrackingConfiguration.CustomizedMetricSpecification.Statistic", "Average",
                "TargetTrackingConfiguration.CustomizedMetricSpecification.Unit", "Count",
                "TargetTrackingConfiguration.CustomizedMetricSpecification.Period", "60",
                "TargetTrackingConfiguration.CustomizedMetricSpecification.Dimensions.member.1.Name", "queue",
                "TargetTrackingConfiguration.CustomizedMetricSpecification.Dimensions.member.1.Value", "orders",
                "TargetTrackingConfiguration.CustomizedMetricSpecification.Dimensions.member.2.Name", "shard",
                "TargetTrackingConfiguration.CustomizedMetricSpecification.Dimensions.member.2.Value", "b")
            .statusCode(200);

        asg("DescribePolicies", "AutoScalingGroupName", name)
            .statusCode(200)
            .body(containsString("<MetricName>queue_depth</MetricName>"))
            .body(containsString("<Namespace>app/metrics</Namespace>"))
            .body(containsString("<Statistic>Average</Statistic>"))
            .body(containsString("<Unit>Count</Unit>"))
            .body(containsString("<Period>60</Period>"))
            .body(containsString("<Name>queue</Name>"))
            .body(containsString("<Value>orders</Value>"))
            .body(containsString("<Name>shard</Name>"))
            .body(containsString("<Value>b</Value>"))
            .body(containsString("<DisableScaleIn>true</DisableScaleIn>"))
            .body(containsString("<TargetValue>42.5</TargetValue>"));
    }

    /**
     * A customized metric can be defined by Metrics queries instead of MetricName, which is how
     * metric math reaches a target tracking policy. Those were accepted and never read back.
     *
     * <p>Each query carries its own Period, which the model keeps separate from the Period on its
     * MetricStat.
     */
    @Test
    void aMetricsQuerySurvivesTheRoundTrip() {
        String name = group();
        String q = "TargetTrackingConfiguration.CustomizedMetricSpecification.Metrics.member.";
        asg("PutScalingPolicy",
                "AutoScalingGroupName", name,
                "PolicyName", "mathy",
                "PolicyType", "TargetTrackingScaling",
                "TargetTrackingConfiguration.TargetValue", "10",
                q + "1.Id", "m1",
                q + "1.Period", "10",
                q + "1.ReturnData", "false",
                q + "1.MetricStat.Stat", "Sum",
                q + "1.MetricStat.Unit", "Count",
                q + "1.MetricStat.Period", "60",
                q + "1.MetricStat.Metric.Namespace", "AWS/SQS",
                q + "1.MetricStat.Metric.MetricName", "ApproximateNumberOfMessagesVisible",
                q + "1.MetricStat.Metric.Dimensions.member.1.Name", "QueueName",
                q + "1.MetricStat.Metric.Dimensions.member.1.Value", "orders",
                q + "2.Id", "e1",
                q + "2.Expression", "m1 / 4",
                q + "2.Label", "backlog per instance",
                q + "2.Period", "30",
                q + "2.ReturnData", "true")
            .statusCode(200);

        asg("DescribePolicies", "AutoScalingGroupName", name)
            .statusCode(200)
            .body(containsString("<Id>m1</Id>"))
            .body(containsString("<Stat>Sum</Stat>"))
            .body(containsString("<Namespace>AWS/SQS</Namespace>"))
            .body(containsString("<MetricName>ApproximateNumberOfMessagesVisible</MetricName>"))
            .body(containsString("<Name>QueueName</Name>"))
            .body(containsString("<Id>e1</Id>"))
            .body(containsString("<Expression>m1 / 4</Expression>"))
            .body(containsString("<Label>backlog per instance</Label>"))
            .body(containsString("<ReturnData>true</ReturnData>"))
            // The query's own Period and its MetricStat.Period are separate members, so all three
            // values have to survive rather than one overwriting another.
            .body(containsString("<Period>10</Period>"))
            .body(containsString("<Period>30</Period>"))
            .body(containsString("<Period>60</Period>"));
    }

    /** A bad Period is the client's error, and used to come back as InternalFailure. */
    @Test
    void aNonNumericPeriodIsAParameterError() {
        String name = group();
        asg("PutScalingPolicy",
                "AutoScalingGroupName", name,
                "PolicyName", "badperiod",
                "PolicyType", "TargetTrackingScaling",
                "TargetTrackingConfiguration.TargetValue", "10",
                "TargetTrackingConfiguration.CustomizedMetricSpecification.MetricName", "m",
                "TargetTrackingConfiguration.CustomizedMetricSpecification.Period", "not-a-number")
            .statusCode(400)
            .body(containsString("ValidationError"));

        asg("PutScalingPolicy",
                "AutoScalingGroupName", name,
                "PolicyName", "zeroperiod",
                "PolicyType", "TargetTrackingScaling",
                "TargetTrackingConfiguration.TargetValue", "10",
                "TargetTrackingConfiguration.CustomizedMetricSpecification.MetricName", "m",
                "TargetTrackingConfiguration.CustomizedMetricSpecification.Period", "0")
            .statusCode(400);
    }

    /**
     * Boolean.parseBoolean turns anything that is not "true" into false, so an unintended value was
     * accepted and read back as a setting the caller never asked for. This handler already has a
     * strict helper for exactly that.
     */
    @Test
    void anUnparseableBooleanIsAParameterError() {
        String name = group();
        asg("PutScalingPolicy",
                "AutoScalingGroupName", name, "PolicyName", "badbool",
                "PolicyType", "TargetTrackingScaling",
                "TargetTrackingConfiguration.TargetValue", "10",
                "TargetTrackingConfiguration.PredefinedMetricSpecification.PredefinedMetricType",
                "ASGAverageCPUUtilization",
                "TargetTrackingConfiguration.DisableScaleIn", "maybe")
            .statusCode(400)
            .body(containsString("ValidationError"));

        String q = "TargetTrackingConfiguration.CustomizedMetricSpecification.Metrics.member.";
        asg("PutScalingPolicy",
                "AutoScalingGroupName", name, "PolicyName", "badreturn",
                "PolicyType", "TargetTrackingScaling",
                "TargetTrackingConfiguration.TargetValue", "10",
                q + "1.Id", "m1", q + "1.Expression", "1", q + "1.ReturnData", "maybe")
            .statusCode(400)
            .body(containsString("ValidationError"));

        // The documented values still work, in either case.
        asg("PutScalingPolicy",
                "AutoScalingGroupName", name, "PolicyName", "goodbool",
                "PolicyType", "TargetTrackingScaling",
                "TargetTrackingConfiguration.TargetValue", "10",
                "TargetTrackingConfiguration.PredefinedMetricSpecification.PredefinedMetricType",
                "ASGAverageCPUUtilization",
                "TargetTrackingConfiguration.DisableScaleIn", "TRUE")
            .statusCode(200);
    }

    @Test
    void aPredefinedSpecificationKeepsItsResourceLabel() {
        String name = group();
        asg("PutScalingPolicy",
                "AutoScalingGroupName", name,
                "PolicyName", "predefined",
                "PolicyType", "TargetTrackingScaling",
                "TargetTrackingConfiguration.TargetValue", "70",
                "TargetTrackingConfiguration.PredefinedMetricSpecification.PredefinedMetricType",
                "ALBRequestCountPerTarget",
                "TargetTrackingConfiguration.PredefinedMetricSpecification.ResourceLabel",
                "app/my-lb/abc/targetgroup/my-tg/def")
            .statusCode(200);

        asg("DescribePolicies", "AutoScalingGroupName", name)
            .statusCode(200)
            .body(containsString("<PredefinedMetricType>ALBRequestCountPerTarget</PredefinedMetricType>"))
            .body(containsString("<ResourceLabel>app/my-lb/abc/targetgroup/my-tg/def</ResourceLabel>"));
    }

    /** A policy naming neither specification must not grow an empty one on read. */
    @Test
    void aPolicyWithNoCustomMetricReportsNone() {
        String name = group();
        asg("PutScalingPolicy",
                "AutoScalingGroupName", name,
                "PolicyName", "plain",
                "PolicyType", "TargetTrackingScaling",
                "TargetTrackingConfiguration.TargetValue", "50",
                "TargetTrackingConfiguration.PredefinedMetricSpecification.PredefinedMetricType",
                "ASGAverageCPUUtilization")
            .statusCode(200);

        asg("DescribePolicies", "AutoScalingGroupName", name)
            .statusCode(200)
            .body(not(containsString("<CustomizedMetricSpecification>")))
            .body(not(containsString("<Metrics>")))
            .body(not(containsString("<DisableScaleIn>")))
            .body(not(containsString("<ResourceLabel>")));
    }
}
