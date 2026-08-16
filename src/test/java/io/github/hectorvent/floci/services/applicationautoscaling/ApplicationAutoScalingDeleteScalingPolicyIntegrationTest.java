package io.github.hectorvent.floci.services.applicationautoscaling;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class ApplicationAutoScalingDeleteScalingPolicyIntegrationTest {

    @BeforeAll
    static void setUp() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void testDeleteScalingPolicyWorkflow() {
        String resourceId = "service/delete-policy-cluster/delete-policy-service";

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
