package io.github.hectorvent.floci.services.applicationautoscaling;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class ApplicationAutoScalingDeleteScalingPolicyIntegrationTest {

    private static final String RESOURCE_ID = "service/delete-policy-cluster/delete-policy-service";
    private static final String DIMENSION = "ecs:service:DesiredCount";

    @BeforeAll
    static void setUp() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void tearDown() {
        // Best-effort: if an assertion above fails mid-workflow, this still runs and prevents
        // the scalable target from leaking into the shared @QuarkusTest JVM for later tests.
        try {
            given()
                    .contentType("application/x-amz-json-1.1")
                    .header("X-Amz-Target", "AnyScaleFrontendService.DeregisterScalableTarget")
                    .body("""
                            {
                              "ServiceNamespace": "ecs",
                              "ResourceId": "%s",
                              "ScalableDimension": "%s"
                            }
                            """.formatted(RESOURCE_ID, DIMENSION))
                    .post("/");
        } catch (RuntimeException ignored) {
            // Cleanup is best-effort; the test's own assertions are the source of truth.
        }
    }

    @Test
    void testDeleteScalingPolicyWorkflow() {
        String resourceId = RESOURCE_ID;

        given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AnyScaleFrontendService.RegisterScalableTarget")
                .body("""
                        {
                          "ServiceNamespace": "ecs",
                          "ResourceId": "%s",
                          "ScalableDimension": "ecs:service:DesiredCount",
                          "MinCapacity": 1,
                          "MaxCapacity": 5
                        }
                        """.formatted(resourceId))
                .post("/")
                .then()
                .statusCode(200)
                .body("ScalableTargetARN", notNullValue());

        given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AnyScaleFrontendService.PutScalingPolicy")
                .body("""
                        {
                          "PolicyName": "delete-policy",
                          "PolicyType": "TargetTrackingScaling",
                          "ServiceNamespace": "ecs",
                          "ResourceId": "%s",
                          "ScalableDimension": "ecs:service:DesiredCount",
                          "TargetTrackingScalingPolicyConfiguration": {
                            "TargetValue": 50.0,
                            "PredefinedMetricSpecification": {
                              "PredefinedMetricType": "ECSServiceAverageCPUUtilization"
                            }
                          }
                        }
                        """.formatted(resourceId))
                .post("/")
                .then()
                .statusCode(200)
                .body("PolicyARN", notNullValue());

        given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AnyScaleFrontendService.DeleteScalingPolicy")
                .body("""
                        {
                          "PolicyName": "delete-policy",
                          "ServiceNamespace": "ecs",
                          "ResourceId": "%s",
                          "ScalableDimension": "ecs:service:DesiredCount"
                        }
                        """.formatted(resourceId))
                .post("/")
                .then()
                .statusCode(200);

        given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AnyScaleFrontendService.DescribeScalingPolicies")
                .body("""
                        {
                          "ServiceNamespace": "ecs",
                          "ResourceId": "%s",
                          "ScalableDimension": "ecs:service:DesiredCount"
                        }
                        """.formatted(resourceId))
                .post("/")
                .then()
                .statusCode(200)
                .body("ScalingPolicies", hasSize(0));

        given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AnyScaleFrontendService.DeregisterScalableTarget")
                .body("""
                        {
                          "ServiceNamespace": "ecs",
                          "ResourceId": "%s",
                          "ScalableDimension": "ecs:service:DesiredCount"
                        }
                        """.formatted(resourceId))
                .post("/")
                .then()
                .statusCode(200);
    }
}
