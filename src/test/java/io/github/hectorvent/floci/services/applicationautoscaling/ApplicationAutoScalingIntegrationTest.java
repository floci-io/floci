package io.github.hectorvent.floci.services.applicationautoscaling;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

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
    private static final String RESOURCE_ID = "service/aas-it-cluster/aas-it-service";
    private static final String DIMENSION = "ecs:service:DesiredCount";

    private static String scalableTargetArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static io.restassured.specification.RequestSpecification call(String action) {
        return given().header("X-Amz-Target", TARGET_PREFIX + action).contentType(CONTENT_TYPE);
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
    void listTagsForResourceResolvesByScalableTargetArn() {
        call("ListTagsForResource")
            .body("{ \"ResourceARN\": \"%s\" }".formatted(scalableTargetArn))
        .when().post("/").then().statusCode(200)
            .body("Tags.Environment", equalTo("test"));
    }

    @Test
    @Order(5)
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
    @Order(6)
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
    }

    @Test
    @Order(7)
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
    @Order(8)
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
    @Order(9)
    void invalidServiceNamespaceIsRejected() {
        call("DescribeScalableTargets")
            .body("{ \"ServiceNamespace\": \"bogus\" }")
        .when().post("/").then().statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(10)
    void tagsOnUnknownArnThrowResourceNotFound() {
        call("ListTagsForResource")
            .body("""
                { "ResourceARN": "arn:aws:application-autoscaling:us-east-1:000000000000:scalable-target/missing" }
                """)
        .when().post("/").then().statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(11)
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
    @Order(12)
    void deregisteringTwiceThrowsObjectNotFound() {
        call("DeregisterScalableTarget")
            .body("""
                { "ServiceNamespace": "ecs", "ResourceId": "%s", "ScalableDimension": "%s" }
                """.formatted(RESOURCE_ID, DIMENSION))
        .when().post("/").then().statusCode(400)
            .body("__type", equalTo("ObjectNotFoundException"));
    }

    @Test
    @Order(13)
    void unsupportedActionIsRejected() {
        call("PutScheduledAction")
            .body("{ \"ServiceNamespace\": \"ecs\" }")
        .when().post("/").then().statusCode(400);
    }
}
