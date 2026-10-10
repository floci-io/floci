package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * End-to-end check that CloudFormation provisions Application Auto Scaling for real rather than
 * stubbing it.
 *
 * <p>An unmapped type is stubbed as {@code CREATE_COMPLETE} with a fake ARN, so asserting the
 * stack status alone would pass against a type that was never wired. These assertions are on the
 * {@code Fn::GetAtt} keys from the resource schemas ({@code Id} for the target, {@code Arn} for the
 * policy) and on the entities being visible through the Application Auto Scaling API afterwards.
 */
@QuarkusTest
class CloudFormationApplicationAutoScalingIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String AAS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/application-autoscaling/aws4_request";

    @Test
    void createStackProvisionsAScalableTargetAndItsPolicy() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String resourceId = "service/cfn-cluster-" + suffix + "/svc";
        String stackName = "cfn-aas-stack-" + suffix;
        String policyName = "cfn-aas-policy-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Target": {
                      "Type": "AWS::ApplicationAutoScaling::ScalableTarget",
                      "Properties": {
                        "ServiceNamespace": "ecs",
                        "ResourceId": "%s",
                        "ScalableDimension": "ecs:service:DesiredCount",
                        "MinCapacity": 1,
                        "MaxCapacity": 4
                      }
                    },
                    "Policy": {
                      "Type": "AWS::ApplicationAutoScaling::ScalingPolicy",
                      "Properties": {
                        "PolicyName": "%s",
                        "PolicyType": "TargetTrackingScaling",
                        "ScalingTargetId": {"Ref": "Target"},
                        "TargetTrackingScalingPolicyConfiguration": {
                          "TargetValue": 60,
                          "PredefinedMetricSpecification": {
                            "PredefinedMetricType": "ECSServiceAverageCPUUtilization"
                          }
                        }
                      }
                    }
                  },
                  "Outputs": {
                    "TargetId": {"Value": {"Fn::GetAtt": ["Target", "Id"]}},
                    "PolicyArn": {"Value": {"Fn::GetAtt": ["Policy", "Arn"]}}
                  }
                }
                """.formatted(resourceId, policyName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            // Ref on a scalable target is resourceId|scalableDimension|serviceNamespace, and
            // GetAtt Id is the same composite.
            .body(containsString(resourceId + "|ecs:service:DesiredCount|ecs"))
            .body(containsString(":scalingPolicy:"))
            // An unwired type resolves Fn::GetAtt to the literal "LogicalId.Attr".
            .body(not(containsString("Target.Id")))
            .body(not(containsString("Policy.Arn")));

        given()
            .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                    .encodeContentTypeAs("application/x-amz-json-1.1", ContentType.TEXT)))
            .header("Authorization", AAS_AUTH)
            .header("X-Amz-Target", "AnyScaleFrontendService.DescribeScalableTargets")
            .contentType("application/x-amz-json-1.1")
            .body("{\"ServiceNamespace\":\"ecs\",\"ResourceIds\":[\"" + resourceId + "\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(resourceId))
            .body(containsString("\"MinCapacity\":1"))
            .body(containsString("\"MaxCapacity\":4"));

        given()
            .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                    .encodeContentTypeAs("application/x-amz-json-1.1", ContentType.TEXT)))
            .header("Authorization", AAS_AUTH)
            .header("X-Amz-Target", "AnyScaleFrontendService.DescribeScalingPolicies")
            .contentType("application/x-amz-json-1.1")
            .body("{\"ServiceNamespace\":\"ecs\",\"ResourceId\":\"" + resourceId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(policyName))
            .body(containsString("TargetTrackingScaling"))
            // The configuration reached the service rather than being dropped on the way through.
            .body(containsString("ECSServiceAverageCPUUtilization"));
    }

    @Test
    void deletingTheStackDeregistersTheTarget() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String resourceId = "service/cfn-cluster-del-" + suffix + "/svc";
        String stackName = "cfn-aas-del-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Target": {
                      "Type": "AWS::ApplicationAutoScaling::ScalableTarget",
                      "Properties": {
                        "ServiceNamespace": "ecs",
                        "ResourceId": "%s",
                        "ScalableDimension": "ecs:service:DesiredCount",
                        "MinCapacity": 1,
                        "MaxCapacity": 2
                      }
                    }
                  }
                }
                """.formatted(resourceId);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                    .encodeContentTypeAs("application/x-amz-json-1.1", ContentType.TEXT)))
            .header("Authorization", AAS_AUTH)
            .header("X-Amz-Target", "AnyScaleFrontendService.DescribeScalableTargets")
            .contentType("application/x-amz-json-1.1")
            .body("{\"ServiceNamespace\":\"ecs\",\"ResourceIds\":[\"" + resourceId + "\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString(resourceId)));
    }
}
