package io.floci.aws.services.applicationautoscaling;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.applicationautoscaling.ApplicationAutoScalingClient;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScheduledActionsResponse;
import software.amazon.awssdk.services.applicationautoscaling.model.InvalidNextTokenException;
import software.amazon.awssdk.services.applicationautoscaling.model.ObjectNotFoundException;
import software.amazon.awssdk.services.applicationautoscaling.model.ScheduledAction;
import software.amazon.awssdk.services.applicationautoscaling.model.ValidationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Wire-level coverage for the Application Auto Scaling API.
 *
 * <p>The resource identifiers mirror a real ECS service so that the round-trip of
 * {@code ResourceLabel} and the cooldown fields — the ones that surface as Terraform drift
 * when dropped — are exercised end to end.</p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApplicationAutoScalingIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AnyScaleFrontendService.";
    private static final String CLOUDWATCH_TARGET = "GraniteServiceVersion20100801.";
    private static final String CLOUDWATCH_JSON_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String RESOURCE_ID = "service/aas-it-cluster/aas-it-service";
    private static final String PAGED_RESOURCE_ID = "service/aas-it-cluster/aas-it-service-paged";
    private static final String ALARM_RESOURCE_ID = "service/aas-it-cluster/aas-it-alarm-service";
    private static final String ALARM_CLUSTER = "aas-it-cluster";
    private static final String ALARM_SERVICE = "aas-it-alarm-service";
    private static final String DIMENSION = "ecs:service:DesiredCount";
    private static final String EXCLUDED_RESOURCE_ID = "service/aas-it-cluster/aas-it-service-excluded";

    private static String scalableTargetArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification call(String action) {
        return given().header("X-Amz-Target", TARGET_PREFIX + action).contentType(CONTENT_TYPE);
    }

    private static RequestSpecification cloudwatchCall(String action) {
        return given().header("X-Amz-Target", CLOUDWATCH_TARGET + action).contentType(CLOUDWATCH_JSON_CONTENT_TYPE);
    }

    @Test
    @Order(1)
    void registerScalableTargetReturnsScalableTargetArn() {
        scalableTargetArn = call("RegisterScalableTarget")
            .body("""
                {
                  "ServiceNamespace": "ecs",
                  "ResourceId": "%s",
                  "ScalableDimension": "%s",
                  "MinCapacity": 2,
                  "MaxCapacity": 20,
                  "Tags": { "Environment": "test" }
                }
                """.formatted(RESOURCE_ID, DIMENSION))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ScalableTargetARN", matchesPattern(
                "^arn:aws:application-autoscaling:[a-z0-9-]+:\\d+:scalable-target/[a-zA-Z0-9-]+$"))
            .extract().path("ScalableTargetARN");
    }

    @Test
    @Order(2)
    void describeScalableTargetsReturnsTheArnThatDrivesTagging() {
        call("DescribeScalableTargets")
            .body("""
                { "ServiceNamespace": "ecs", "ResourceIds": ["%s"] }
                """.formatted(RESOURCE_ID))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ScalableTargets", hasSize(1))
            .body("ScalableTargets[0].ResourceId", equalTo(RESOURCE_ID))
            .body("ScalableTargets[0].ScalableDimension", equalTo(DIMENSION))
            .body("ScalableTargets[0].MinCapacity", equalTo(2))
            .body("ScalableTargets[0].MaxCapacity", equalTo(20))
            .body("ScalableTargets[0].ScalableTargetARN", equalTo(scalableTargetArn))
            .body("ScalableTargets[0].RoleARN", containsString(
                "AWSServiceRoleForApplicationAutoScaling_ECSService"))
            .body("ScalableTargets[0].SuspendedState.DynamicScalingInSuspended", equalTo(false))
            .body("ScalableTargets[0].CreationTime", notNullValue());
    }

    @Test
    @Order(3)
    void registerIsAnUpsertThatPreservesUnsuppliedFields() {
        call("RegisterScalableTarget")
            .body("""
                {
                  "ServiceNamespace": "ecs",
                  "ResourceId": "%s",
                  "ScalableDimension": "%s",
                  "MaxCapacity": 40
                }
                """.formatted(RESOURCE_ID, DIMENSION))
        .when().post("/").then().statusCode(200)
            .body("ScalableTargetARN", equalTo(scalableTargetArn));

        call("DescribeScalableTargets")
            .body("""
                { "ServiceNamespace": "ecs", "ResourceIds": ["%s"] }
                """.formatted(RESOURCE_ID))
        .when().post("/").then().statusCode(200)
            .body("ScalableTargets[0].MaxCapacity", equalTo(40))
            .body("ScalableTargets[0].MinCapacity", equalTo(2));
    }

    @Test
    @Order(4)
    void describeScalableTargetsPaginatesWithMaxResultsAndNextToken() {
        call("RegisterScalableTarget")
            .body("""
                {
                  "ServiceNamespace": "ecs",
                  "ResourceId": "%s",
                  "ScalableDimension": "%s",
                  "MinCapacity": 1,
                  "MaxCapacity": 2
                }
                """.formatted(PAGED_RESOURCE_ID, DIMENSION))
        .when().post("/").then().statusCode(200);

        call("RegisterScalableTarget")
            .body("""
                {
                  "ServiceNamespace": "ecs",
                  "ResourceId": "%s",
                  "ScalableDimension": "%s",
                  "MinCapacity": 1,
                  "MaxCapacity": 2
                }
                """.formatted(EXCLUDED_RESOURCE_ID, DIMENSION))
        .when().post("/").then().statusCode(200);

        try {
            String nextToken = call("DescribeScalableTargets")
                .body("""
                    { "ServiceNamespace": "ecs", "ResourceIds": ["%s", "%s"], "MaxResults": 1 }
                    """.formatted(RESOURCE_ID, PAGED_RESOURCE_ID))
            .when().post("/")
            .then().statusCode(200)
                .body("ScalableTargets", hasSize(1))
                .body("NextToken", notNullValue())
                .body("ScalableTargets[0].ResourceId", equalTo(RESOURCE_ID))
                .extract().path("NextToken");

            call("DescribeScalableTargets")
                .body("""
                    { "ServiceNamespace": "ecs", "ResourceIds": ["%s", "%s"],
                      "MaxResults": 1, "NextToken": "%s" }
                    """.formatted(RESOURCE_ID, PAGED_RESOURCE_ID, nextToken))
            .when().post("/").then().statusCode(200)
                .body("ScalableTargets", hasSize(1))
                .body("ScalableTargets[0].ResourceId", equalTo(PAGED_RESOURCE_ID))
                .body("NextToken", nullValue());
        } finally {
            call("DeregisterScalableTarget")
                .body("""
                    { "ServiceNamespace": "ecs", "ResourceId": "%s", "ScalableDimension": "%s" }
                    """.formatted(EXCLUDED_RESOURCE_ID, DIMENSION))
            .when().post("/").then().statusCode(200);
        }
    }

    @Test
    @Order(5)
    void listTagsForResourceResolvesByScalableTargetArn() {
        call("ListTagsForResource")
            .body("{ \"ResourceARN\": \"%s\" }".formatted(scalableTargetArn))
        .when().post("/").then().statusCode(200)
            .body("Tags.Environment", equalTo("test"));
    }

    @Test
    @Order(6)
    void tagAndUntagResource() {
        call("TagResource")
            .body("""
                { "ResourceARN": "%s", "Tags": { "Team": "platform" } }
                """.formatted(scalableTargetArn))
        .when().post("/").then().statusCode(200);

        call("ListTagsForResource")
            .body("{ \"ResourceARN\": \"%s\" }".formatted(scalableTargetArn))
        .when().post("/").then().statusCode(200)
            .body("Tags.Team", equalTo("platform"))
            .body("Tags.Environment", equalTo("test"));

        call("UntagResource")
            .body("""
                { "ResourceARN": "%s", "TagKeys": ["Environment"] }
                """.formatted(scalableTargetArn))
        .when().post("/").then().statusCode(200);

        call("ListTagsForResource")
            .body("{ \"ResourceARN\": \"%s\" }".formatted(scalableTargetArn))
        .when().post("/").then().statusCode(200)
            .body("Tags.Environment", nullValue())
            .body("Tags.Team", equalTo("platform"));
    }

    @Test
    @Order(7)
    void putScalingPolicyReturnsPolicyArnAndAlarms() {
        call("PutScalingPolicy")
            .body("""
                {
                  "PolicyName": "aas-it-alb-policy",
                  "PolicyType": "TargetTrackingScaling",
                  "ServiceNamespace": "ecs",
                  "ResourceId": "%s",
                  "ScalableDimension": "%s",
                  "TargetTrackingScalingPolicyConfiguration": {
                    "TargetValue": 1000.0,
                    "PredefinedMetricSpecification": {
                      "PredefinedMetricType": "ALBRequestCountPerTarget",
                      "ResourceLabel": "app/my-lb/abc123/targetgroup/my-tg/def456"
                    },
                    "ScaleInCooldown": 240,
                    "ScaleOutCooldown": 60
                  }
                }
                """.formatted(RESOURCE_ID, DIMENSION))
        .when().post("/").then().statusCode(200)
            // Policy ARNs use the "autoscaling" service name, not "application-autoscaling".
            .body("PolicyARN", startsWith("arn:aws:autoscaling:"))
            .body("PolicyARN", containsString(":resource/ecs/" + RESOURCE_ID + ":policyName/aas-it-alb-policy"))
            .body("Alarms", hasSize(2))
            .body("Alarms[0].AlarmName", startsWith("TargetTracking-" + RESOURCE_ID))
            .body("Alarms[0].AlarmARN", startsWith("arn:aws:cloudwatch:"));

        cloudwatchCall("DescribeAlarms")
            .body("{ \"AlarmNamePrefix\": \"TargetTracking-" + RESOURCE_ID + "\" }")
        .when().post("/").then().statusCode(200)
            .body("MetricAlarms.find { it.MetricName == 'RequestCountPerTarget' }.Namespace",
                equalTo("AWS/ApplicationELB"))
            .body("MetricAlarms.find { it.MetricName == 'RequestCountPerTarget' }.Statistic",
                equalTo("Sum"));
    }

    @Test
    @Order(8)
    void describeScalingPoliciesRoundTripsEveryConfiguredField() {
        call("DescribeScalingPolicies")
            .body("""
                { "ServiceNamespace": "ecs", "ResourceId": "%s", "ScalableDimension": "%s" }
                """.formatted(RESOURCE_ID, DIMENSION))
        .when().post("/").then().statusCode(200)
            .body("ScalingPolicies", hasSize(1))
            .body("ScalingPolicies[0].PolicyName", equalTo("aas-it-alb-policy"))
            .body("ScalingPolicies[0].PolicyType", equalTo("TargetTrackingScaling"))
            .body("ScalingPolicies[0].TargetTrackingScalingPolicyConfiguration.TargetValue",
                equalTo(1000.0f))
            .body("ScalingPolicies[0].TargetTrackingScalingPolicyConfiguration"
                + ".PredefinedMetricSpecification.PredefinedMetricType",
                equalTo("ALBRequestCountPerTarget"))
            .body("ScalingPolicies[0].TargetTrackingScalingPolicyConfiguration"
                + ".PredefinedMetricSpecification.ResourceLabel",
                equalTo("app/my-lb/abc123/targetgroup/my-tg/def456"))
            .body("ScalingPolicies[0].TargetTrackingScalingPolicyConfiguration.ScaleInCooldown",
                equalTo(240))
            .body("ScalingPolicies[0].TargetTrackingScalingPolicyConfiguration.ScaleOutCooldown",
                equalTo(60))
            // Never configured, so it must be absent rather than defaulted.
            .body("ScalingPolicies[0].TargetTrackingScalingPolicyConfiguration.DisableScaleIn",
                nullValue());
    }

    @Test
    @Order(9)
    void describeScalingPoliciesPaginatesWithMaxResultsAndNextToken() {
        call("PutScalingPolicy")
            .body("""
                {
                  "PolicyName": "aas-it-step-policy",
                  "PolicyType": "StepScaling",
                  "ServiceNamespace": "ecs",
                  "ResourceId": "%s",
                  "ScalableDimension": "%s",
                  "StepScalingPolicyConfiguration": { "AdjustmentType": "ChangeInCapacity" }
                }
                """.formatted(RESOURCE_ID, DIMENSION))
        .when().post("/").then().statusCode(200);

        String nextToken = call("DescribeScalingPolicies")
            .body("""
                {
                  "ServiceNamespace": "ecs",
                  "ResourceId": "%s",
                  "ScalableDimension": "%s",
                  "MaxResults": 1
                }
                """.formatted(RESOURCE_ID, DIMENSION))
        .when().post("/")
        .then().statusCode(200)
            .body("ScalingPolicies", hasSize(1))
            .body("NextToken", notNullValue())
            .extract().path("NextToken");

        call("DescribeScalingPolicies")
            .body("""
                {
                  "ServiceNamespace": "ecs",
                  "ResourceId": "%s",
                  "ScalableDimension": "%s",
                  "MaxResults": 1,
                  "NextToken": "%s"
                }
                """.formatted(RESOURCE_ID, DIMENSION, nextToken))
        .when().post("/").then().statusCode(200)
            .body("ScalingPolicies", hasSize(1))
            .body("NextToken", nullValue());
    }

    @Test
    @Order(10)
    void putScalingPolicyOnUnregisteredTargetThrowsObjectNotFound() {
        call("PutScalingPolicy")
            .body("""
                {
                  "PolicyName": "orphan",
                  "PolicyType": "TargetTrackingScaling",
                  "ServiceNamespace": "ecs",
                  "ResourceId": "service/absent/absent",
                  "ScalableDimension": "%s",
                  "TargetTrackingScalingPolicyConfiguration": {
                    "TargetValue": 50.0,
                    "PredefinedMetricSpecification": {
                      "PredefinedMetricType": "ECSServiceAverageCPUUtilization"
                    }
                  }
                }
                """.formatted(DIMENSION))
        .when().post("/").then().statusCode(400)
            .body("__type", equalTo("ObjectNotFoundException"));
    }

    @Test
    @Order(11)
    void invalidServiceNamespaceIsRejected() {
        call("DescribeScalableTargets")
            .body("{ \"ServiceNamespace\": \"bogus\" }")
        .when().post("/").then().statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(12)
    void tagsOnUnknownArnThrowResourceNotFound() {
        call("ListTagsForResource")
            .body("""
                { "ResourceARN": "arn:aws:application-autoscaling:us-east-1:000000000000:scalable-target/missing" }
                """)
        .when().post("/").then().statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(13)
    void deregisterScalableTargetCascadesToItsPolicies() {
        call("DeregisterScalableTarget")
            .body("""
                { "ServiceNamespace": "ecs", "ResourceId": "%s", "ScalableDimension": "%s" }
                """.formatted(RESOURCE_ID, DIMENSION))
        .when().post("/").then().statusCode(200);

        call("DescribeScalableTargets")
            .body("""
                { "ServiceNamespace": "ecs", "ResourceIds": ["%s"] }
                """.formatted(RESOURCE_ID))
        .when().post("/").then().statusCode(200)
            .body("ScalableTargets", hasSize(0));

        call("DescribeScalingPolicies")
            .body("""
                { "ServiceNamespace": "ecs", "ResourceId": "%s", "ScalableDimension": "%s" }
                """.formatted(RESOURCE_ID, DIMENSION))
        .when().post("/").then().statusCode(200)
            .body("ScalingPolicies", hasSize(0));
    }

    @Test
    @Order(14)
    void deregisteringTwiceThrowsObjectNotFound() {
        call("DeregisterScalableTarget")
            .body("""
                { "ServiceNamespace": "ecs", "ResourceId": "%s", "ScalableDimension": "%s" }
                """.formatted(RESOURCE_ID, DIMENSION))
        .when().post("/").then().statusCode(400)
            .body("__type", equalTo("ObjectNotFoundException"));
    }

    @Test
    @Order(15)
    void unsupportedActionIsRejected() {
        call("PutScheduledAction")
            .body("{ \"ServiceNamespace\": \"ecs\" }")
        .when().post("/").then().statusCode(400);
    }

    @Test
    @Order(16)
    void invalidPaginationTokensReturnTheModeledError() {
        for (String action : List.of("DescribeScalableTargets", "DescribeScalingPolicies")) {
            for (String token : List.of("", "not!base64")) {
                call(action)
                    .body("{ \"ServiceNamespace\": \"ecs\", \"NextToken\": \"%s\" }".formatted(token))
                .when().post("/").then().statusCode(400)
                    .body("__type", equalTo("InvalidNextTokenException"));
            }
        }
    }

    @Test
    @Order(17)
    void pageSizeLimitsAreSpecificToEachOperation() {
        call("DescribeScalableTargets")
            .body("{ \"ServiceNamespace\": \"ecs\", \"MaxResults\": 50 }")
        .when().post("/").then().statusCode(200);

        call("DescribeScalingPolicies")
            .body("{ \"ServiceNamespace\": \"ecs\", \"MaxResults\": 10 }")
        .when().post("/").then().statusCode(200);

        call("DescribeScalingPolicies")
            .body("{ \"ServiceNamespace\": \"ecs\", \"MaxResults\": 11 }")
        .when().post("/").then().statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(18)
    void customizedTargetTrackingAlarmsWatchTheMetricIdentityTheCallerGave() {
        call("RegisterScalableTarget")
            .body("""
                {
                  "ServiceNamespace": "ecs",
                  "ResourceId": "%s",
                  "ScalableDimension": "%s",
                  "MinCapacity": 1,
                  "MaxCapacity": 3
                }
                """.formatted(ALARM_RESOURCE_ID, DIMENSION))
        .when().post("/").then().statusCode(200);

        call("PutScalingPolicy")
            .body("""
                {
                  "PolicyName": "aas-it-custom-metric",
                  "PolicyType": "TargetTrackingScaling",
                  "ServiceNamespace": "ecs",
                  "ResourceId": "%s",
                  "ScalableDimension": "%s",
                  "TargetTrackingScalingPolicyConfiguration": {
                    "TargetValue": 10.0,
                    "CustomizedMetricSpecification": {
                      "Namespace": "App/Workers",
                      "MetricName": "JobsPerTask",
                      "Statistic": "Average",
                      "Dimensions": [{ "Name": "ServiceName", "Value": "web" }]
                    }
                  }
                }
                """.formatted(ALARM_RESOURCE_ID, DIMENSION))
        .when().post("/").then().statusCode(200);

        cloudwatchCall("DescribeAlarms")
            .body("{ \"AlarmNamePrefix\": \"TargetTracking-" + ALARM_RESOURCE_ID + "\" }")
        .when().post("/").then().statusCode(200)
            .body("MetricAlarms.findAll { it.MetricName == 'JobsPerTask' }.size()", equalTo(2))
            .body("MetricAlarms.find { it.MetricName == 'JobsPerTask' }.Namespace", equalTo("App/Workers"))
            .body("MetricAlarms.find { it.MetricName == 'JobsPerTask' }.Statistic", equalTo("Average"))
            .body("MetricAlarms.find { it.MetricName == 'JobsPerTask' }.Dimensions.size()", equalTo(1))
            .body("MetricAlarms.find { it.MetricName == 'JobsPerTask' }.Dimensions[0].Name", equalTo("ServiceName"))
            .body("MetricAlarms.find { it.MetricName == 'JobsPerTask' }.Dimensions[0].Value", equalTo("web"))
            // The metric the alarm watches must be the caller's, not the ECS-shaped fallback.
            .body("MetricAlarms.findAll { it.Namespace == 'AWS/ECS' }.size()", equalTo(0));

        call("DeregisterScalableTarget")
            .body("""
                { "ServiceNamespace": "ecs", "ResourceId": "%s", "ScalableDimension": "%s" }
                """.formatted(ALARM_RESOURCE_ID, DIMENSION))
        .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(19)
    void ecsPredefinedTargetTrackingAlarmsWatchTheRealMetricTheServicePublishes() {
        call("RegisterScalableTarget")
            .body("""
                {
                  "ServiceNamespace": "ecs",
                  "ResourceId": "%s",
                  "ScalableDimension": "%s",
                  "MinCapacity": 1,
                  "MaxCapacity": 3
                }
                """.formatted(ALARM_RESOURCE_ID, DIMENSION))
        .when().post("/").then().statusCode(200);

        for (String policy : List.of("""
                {
                  "PolicyName": "aas-it-cpu",
                  "PolicyType": "TargetTrackingScaling",
                  "ServiceNamespace": "ecs",
                  "ResourceId": "%s",
                  "ScalableDimension": "%s",
                  "TargetTrackingScalingPolicyConfiguration": {
                    "TargetValue": 70.0,
                    "PredefinedMetricSpecification": {
                      "PredefinedMetricType": "ECSServiceAverageCPUUtilization"
                    }
                  }
                }
                """, """
                {
                  "PolicyName": "aas-it-memory",
                  "PolicyType": "TargetTrackingScaling",
                  "ServiceNamespace": "ecs",
                  "ResourceId": "%s",
                  "ScalableDimension": "%s",
                  "TargetTrackingScalingPolicyConfiguration": {
                    "TargetValue": 80.0,
                    "PredefinedMetricSpecification": {
                      "PredefinedMetricType": "ECSServiceAverageMemoryUtilization"
                    }
                  }
                }
                """)) {
            call("PutScalingPolicy").body(policy.formatted(ALARM_RESOURCE_ID, DIMENSION))
                    .when().post("/").then().statusCode(200);
        }

        cloudwatchCall("DescribeAlarms")
            .body("{ \"AlarmNamePrefix\": \"TargetTracking-" + ALARM_RESOURCE_ID + "\" }")
        .when().post("/").then().statusCode(200)
            .body("MetricAlarms.findAll { it.MetricName == 'CPUUtilization' }.size()", equalTo(2))
            .body("MetricAlarms.find { it.MetricName == 'CPUUtilization' }.Namespace", equalTo("AWS/ECS"))
            .body("MetricAlarms.find { it.MetricName == 'CPUUtilization' }.Dimensions.size()", equalTo(2))
            .body("MetricAlarms.find { it.MetricName == 'CPUUtilization' }.Dimensions[0].Name", equalTo("ClusterName"))
            .body("MetricAlarms.find { it.MetricName == 'CPUUtilization' }.Dimensions[0].Value", equalTo(ALARM_CLUSTER))
            .body("MetricAlarms.find { it.MetricName == 'CPUUtilization' }.Dimensions[1].Name", equalTo("ServiceName"))
            .body("MetricAlarms.find { it.MetricName == 'CPUUtilization' }.Dimensions[1].Value", equalTo(ALARM_SERVICE))
            .body("MetricAlarms.findAll { it.MetricName == 'MemoryUtilization' }.size()", equalTo(2))
            .body("MetricAlarms.find { it.MetricName == 'MemoryUtilization' }.Namespace", equalTo("AWS/ECS"))
            .body("MetricAlarms.find { it.MetricName == 'MemoryUtilization' }.Dimensions[0].Name", equalTo("ClusterName"))
            .body("MetricAlarms.find { it.MetricName == 'MemoryUtilization' }.Dimensions[1].Name", equalTo("ServiceName"))
            // Nothing watches the policy type name itself, as real AWS would never publish it.
            .body("MetricAlarms.findAll { it.MetricName == 'ECSServiceAverageCPUUtilization' }.size()", equalTo(0))
            .body("MetricAlarms.findAll { it.MetricName == 'ECSServiceAverageMemoryUtilization' }.size()", equalTo(0));

        call("DeregisterScalableTarget")
            .body("""
                { "ServiceNamespace": "ecs", "ResourceId": "%s", "ScalableDimension": "%s" }
                """.formatted(ALARM_RESOURCE_ID, DIMENSION))
        .when().post("/").then().statusCode(200);
    }
    @Test
    void scheduledActionSdkLifecycle() {
        String resource = "service/scheduled-sdk/service";
        Instant deadline = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.SECONDS);
        try (ApplicationAutoScalingClient client = AwsTestClients.create(ApplicationAutoScalingClient.builder(),
                "000000000000", Region.US_EAST_1)) {
            ScheduledActionFixtures.register(client, resource, 1, 10);
            try {
                client.putScheduledAction(ScheduledActionFixtures.put(resource, "once")
                        .timezone("UTC").schedule(ScheduledActionFixtures.schedule(deadline))
                        .scalableTargetAction(ScheduledActionFixtures.bounds(2, 8)).build());
                ScheduledAction original = ScheduledActionFixtures.describe(client, resource).scheduledActions().getFirst();
                assertNotNull(original.creationTime());
                assertEquals("UTC", original.timezone());
                assertTrue(original.scheduledActionARN().contains(":autoscaling:us-east-1:000000000000:scheduledAction:"));
                client.putScheduledAction(ScheduledActionFixtures.put(resource, "once")
                        .scalableTargetAction(ScheduledActionFixtures.bounds(3, null)).build());
                ScheduledAction replaced = ScheduledActionFixtures.describe(client, resource).scheduledActions().getFirst();
                assertEquals(3, replaced.scalableTargetAction().minCapacity());
                assertNull(replaced.scalableTargetAction().maxCapacity());
                assertEquals(original.schedule(), replaced.schedule());
                assertEquals("UTC", replaced.timezone());
                client.putScheduledAction(ScheduledActionFixtures.put(resource, "once").build());
                client.putScheduledAction(ScheduledActionFixtures.put(resource, "once")
                        .schedule(ScheduledActionFixtures.schedule(deadline.plusSeconds(60))).build());
                ScheduledAction rescheduled = ScheduledActionFixtures.describe(client, resource).scheduledActions().getFirst();
                assertEquals(original.scheduledActionARN(), rescheduled.scheduledActionARN());
                assertEquals(original.creationTime(), rescheduled.creationTime());
                assertEquals("UTC", rescheduled.timezone());
                client.putScheduledAction(ScheduledActionFixtures.put(resource, "two")
                        .schedule(ScheduledActionFixtures.schedule(deadline))
                        .scalableTargetAction(ScheduledActionFixtures.bounds(null, 4)).build());
                ScheduledAction omittedTimezone = client.describeScheduledActions(b -> b.serviceNamespace("ecs")
                        .resourceId(resource).scheduledActionNames("two")).scheduledActions().getFirst();
                assertNull(omittedTimezone.timezone());
                client.putScheduledAction(ScheduledActionFixtures.put(resource, "two").timezone("UTC").build());
                assertEquals("UTC", client.describeScheduledActions(b -> b.serviceNamespace("ecs")
                        .resourceId(resource).scheduledActionNames("two")).scheduledActions().getFirst().timezone());
                DescribeScheduledActionsResponse page = client.describeScheduledActions(b -> b.serviceNamespace("ecs")
                        .resourceId(resource).maxResults(1));
                assertEquals(1, page.scheduledActions().size());
                assertNotNull(page.nextToken());
                assertEquals(1, client.describeScheduledActions(b -> b.serviceNamespace("ecs").resourceId(resource)
                        .nextToken(page.nextToken()).maxResults(1)).scheduledActions().size());
                assertThrows(InvalidNextTokenException.class, () -> client.describeScheduledActions(b -> b.serviceNamespace("ecs")
                        .resourceId("service/other/service").nextToken(page.nextToken())));
                assertTrue(client.describeScheduledActions(b -> b.serviceNamespace("ecs").resourceId(resource)
                        .scheduledActionNames("missing")).scheduledActions().isEmpty());
                client.deleteScheduledAction(b -> b.serviceNamespace("ecs").resourceId(resource)
                        .scalableDimension(DIMENSION).scheduledActionName("once"));
                assertThrows(ObjectNotFoundException.class, () -> client.deleteScheduledAction(b -> b.serviceNamespace("ecs")
                        .resourceId(resource).scalableDimension(DIMENSION).scheduledActionName("once")));
                assertThrows(ValidationException.class, () -> client.putScheduledAction(ScheduledActionFixtures.put(resource, "two")
                        .schedule("rate(1 minute)").build()));
                assertThrows(ValidationException.class, () -> client.putScheduledAction(ScheduledActionFixtures.put(resource, "two")
                        .timezone("Europe/London").build()));
                assertThrows(ValidationException.class, () -> client.putScheduledAction(ScheduledActionFixtures.put(resource, "two")
                        .startTime(deadline).build()));
                assertThrows(ValidationException.class, () -> client.putScheduledAction(ScheduledActionFixtures.put(resource, "two")
                        .endTime(deadline).build()));
                assertThrows(ValidationException.class, () -> client.describeScheduledActions(b -> b.serviceNamespace("ecs")
                        .resourceId(resource).maxResults(51)));
                assertThrows(ValidationException.class, () -> client.describeScheduledActions(b -> b.serviceNamespace("ecs")
                        .scalableDimension(DIMENSION)));
            } finally {
                ScheduledActionFixtures.deregister(client, resource);
            }
            assertTrue(ScheduledActionFixtures.describe(client, resource).scheduledActions().isEmpty());
            assertThrows(ObjectNotFoundException.class, () -> client.putScheduledAction(ScheduledActionFixtures.put(resource, "absent")
                    .schedule(ScheduledActionFixtures.schedule(deadline)).scalableTargetAction(ScheduledActionFixtures.bounds(0, 0)).build()));
        }
    }

    @Test
    void scheduledActionsRejectInvalidDatesAndUnsupportedExpressionSyntax() {
        String resource = "service/scheduled-invalid-expression/service";
        String validSchedule = "at(2096-02-29T12:34:56)";
        try (ApplicationAutoScalingClient client = AwsTestClients.create(ApplicationAutoScalingClient.builder(),
                "000000000000", Region.US_EAST_1)) {
            ScheduledActionFixtures.register(client, resource, 0, 10);
            try {
                client.putScheduledAction(ScheduledActionFixtures.put(resource, "once")
                        .schedule(validSchedule).scalableTargetAction(ScheduledActionFixtures.bounds(0, 0)).build());
                for (String schedule : List.of("at(2099-02-29T12:34:56)", "at(2099-04-31T12:34:56)",
                        "at(2099-01-01T24:00:00)", "at(2099-01-01T00:00:00Z)",
                        "at(2099-01-01T00:00:00+00:00)", "at(2099-01-01T00:00:00.123)",
                        "at(2099-01-01T00:00:00)trailing", "AT(2099-01-01T00:00:00)",
                        " at(2099-01-01T00:00:00)", "at( 2099-01-01T00:00:00)",
                        "rate(1 minute)", "cron(0 0 * * ? *)")) {
                    assertThrows(ValidationException.class, () -> client.putScheduledAction(
                            ScheduledActionFixtures.put(resource, "once").schedule(schedule).build()), schedule);
                }
                assertEquals(validSchedule, ScheduledActionFixtures.describe(client, resource)
                        .scheduledActions().getFirst().schedule());
            } finally {
                ScheduledActionFixtures.deregister(client, resource);
            }
        }
    }

    @Test
    void scheduledActionsAreIsolatedByAccountAndRegion() {
        String resource = "service/scheduled-isolation/service";
        try (ApplicationAutoScalingClient first = AwsTestClients.create(ApplicationAutoScalingClient.builder(),
                "111111111111", Region.US_EAST_1);
                ApplicationAutoScalingClient account = AwsTestClients.create(ApplicationAutoScalingClient.builder(),
                        "222222222222", Region.US_EAST_1);
                ApplicationAutoScalingClient region = AwsTestClients.create(ApplicationAutoScalingClient.builder(),
                        "111111111111", Region.EU_WEST_1)) {
            for (ApplicationAutoScalingClient client : List.of(first, account, region)) {
                ScheduledActionFixtures.register(client, resource, 0, 10);
            }
            try {
                int min = 1;
                for (ApplicationAutoScalingClient client : List.of(first, account, region)) {
                    assertTrue(ScheduledActionFixtures.describe(client, resource).scheduledActions().isEmpty());
                    client.putScheduledAction(ScheduledActionFixtures.put(resource, "same-name")
                            .schedule("at(2099-01-01T00:00:00)").scalableTargetAction(ScheduledActionFixtures.bounds(min++, null)).build());
                }
                assertEquals(1, ScheduledActionFixtures.describe(first, resource).scheduledActions().getFirst().scalableTargetAction().minCapacity());
                assertEquals(2, ScheduledActionFixtures.describe(account, resource).scheduledActions().getFirst().scalableTargetAction().minCapacity());
                assertEquals(3, ScheduledActionFixtures.describe(region, resource).scheduledActions().getFirst().scalableTargetAction().minCapacity());
                first.deleteScheduledAction(b -> b.serviceNamespace("ecs").resourceId(resource)
                        .scalableDimension(DIMENSION).scheduledActionName("same-name"));
                assertEquals(1, ScheduledActionFixtures.describe(account, resource).scheduledActions().size());
                assertEquals(1, ScheduledActionFixtures.describe(region, resource).scheduledActions().size());
            } finally {
                for (ApplicationAutoScalingClient client : List.of(first, account, region)) {
                    ScheduledActionFixtures.deregister(client, resource);
                }
            }
        }
    }

    @Test
    void scheduledActionNamesRejectMalformedFiltersWithoutReturningUnfilteredActions() {
        String resource = "service/scheduled-name-filter/service";
        try (ApplicationAutoScalingClient client = AwsTestClients.create(ApplicationAutoScalingClient.builder(),
                "000000000000", Region.US_EAST_1)) {
            ScheduledActionFixtures.register(client, resource, 0, 10);
            try {
                for (String name : List.of("wanted", "other")) {
                    client.putScheduledAction(ScheduledActionFixtures.put(resource, name)
                            .schedule("at(2099-01-01T00:00:00)")
                            .scalableTargetAction(ScheduledActionFixtures.bounds(0, 1)).build());
                }
                assertEquals(List.of("wanted"), client.describeScheduledActions(b -> b.serviceNamespace("ecs")
                        .resourceId(resource).scheduledActionNames("wanted")).scheduledActions().stream()
                        .map(ScheduledAction::scheduledActionName).toList());
                assertEquals(2, ScheduledActionFixtures.describe(client, resource).scheduledActions().size());
                String base = "\"ServiceNamespace\":\"ecs\",\"ResourceId\":\"" + resource + "\",\"ScheduledActionNames\":";
                for (String names : List.of("\"wanted\"", "1", "true", "{}", "[1]", "[true]", "[{}]", "[[]]", "[null]",
                        "[\"wanted\",1]")) {
                    call("DescribeScheduledActions").body("{" + base + names + "}").post("/").then()
                            .statusCode(400).body("__type", equalTo("ValidationException"));
                }
                for (String names : List.of("[]", "null")) {
                    call("DescribeScheduledActions").body("{" + base + names + "}").post("/").then()
                            .statusCode(200).body("ScheduledActions", hasSize(2));
                }
            } finally {
                ScheduledActionFixtures.deregister(client, resource);
            }
        }
    }

    @Test
    void unsupportedScheduledTimezonesAreRejectedWithoutCoercion() {
        String base = "\"ServiceNamespace\":\"ecs\",\"ResourceId\":\"service/malformed-timezone/service\","
                + "\"ScalableDimension\":\"ecs:service:DesiredCount\",\"ScheduledActionName\":\"bad\","
                + "\"Schedule\":\"at(2099-01-01T00:00:00)\",\"ScalableTargetAction\":{\"MinCapacity\":0},\"Timezone\":";
        for (String timezone : List.of("\"Europe/London\"", "\"Etc/UTC\"", "\"utc\"", "\"\"", "1", "true", "{}", "[]", "null")) {
            call("PutScheduledAction").body("{" + base + timezone + "}").post("/").then()
                    .statusCode(400).body("__type", equalTo("ValidationException"));
        }
    }

    @Test
    void malformedScheduledBoundsAreRejectedWithoutCoercion() {
        String base = "\"ServiceNamespace\":\"ecs\",\"ResourceId\":\"service/malformed/service\","
                + "\"ScalableDimension\":\"ecs:service:DesiredCount\",\"ScheduledActionName\":\"bad\","
                + "\"Schedule\":\"at(2030-01-01T00:00:00)\",\"ScalableTargetAction\":";
        for (String bounds : List.of("{}", "\"\"", "{\"MinCapacity\":1.5}", "{\"MinCapacity\":\"2\"}",
                "{\"MaxCapacity\":2147483648}", "{\"MinCapacity\":null}")) {
            call("PutScheduledAction").body("{" + base + bounds + "}").post("/").then()
                    .statusCode(400).body("__type", equalTo("ValidationException"));
        }
    }

}
