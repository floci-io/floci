package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingService;
import io.github.hectorvent.floci.services.autoscaling.model.ScalingPolicy;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Provisions two {@code AWS::AutoScaling::ScalingPolicy} resources on a group the same stack
 * creates: a target-tracking policy and a simple-scaling one. Asserts that {@code Ref} and
 * {@code Fn::GetAtt Arn} are the ARN {@code DescribePolicies} reports and {@code Fn::GetAtt
 * PolicyName} the generated name, that an update rewrites both policies in place under the same
 * name and ARN, and that deleting the stack removes them from the group.
 */
@QuarkusTest
class AutoScalingScalingPolicyCfnIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/cloudformation/aws4_request";
    private static final String ASG_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/autoscaling/aws4_request";
    private static final String STACK = "asg-policy-cfn-it";
    private static final String ASG = "asg-policy-cfn-it-group";

    private static final String TEMPLATE = """
        {
          "Parameters": {
            "TargetValue": {"Type": "String"},
            "Cooldown": {"Type": "String"}
          },
          "Resources": {
            "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.81.0.0/16"}},
            "Subnet": {
              "Type": "AWS::EC2::Subnet",
              "Properties": {"VpcId": {"Ref": "Vpc"}, "CidrBlock": "10.81.1.0/24"}
            },
            "Lc": {
              "Type": "AWS::AutoScaling::LaunchConfiguration",
              "Properties": {"ImageId": "ami-11111111", "InstanceType": "t3.micro"}
            },
            "Asg": {
              "Type": "AWS::AutoScaling::AutoScalingGroup",
              "Properties": {
                "AutoScalingGroupName": "%s",
                "MinSize": "0", "MaxSize": "0", "DesiredCapacity": "0",
                "LaunchConfigurationName": {"Ref": "Lc"},
                "VPCZoneIdentifier": [{"Ref": "Subnet"}]
              }
            },
            "Tracking": {
              "Type": "AWS::AutoScaling::ScalingPolicy",
              "Properties": {
                "AutoScalingGroupName": {"Ref": "Asg"},
                "PolicyType": "TargetTrackingScaling",
                "EstimatedInstanceWarmup": 120,
                "TargetTrackingConfiguration": {
                  "PredefinedMetricSpecification": {"PredefinedMetricType": "ASGAverageCPUUtilization"},
                  "TargetValue": {"Ref": "TargetValue"}
                }
              }
            },
            "Simple": {
              "Type": "AWS::AutoScaling::ScalingPolicy",
              "Properties": {
                "AutoScalingGroupName": {"Ref": "Asg"},
                "AdjustmentType": "ChangeInCapacity",
                "ScalingAdjustment": 1,
                "Cooldown": {"Ref": "Cooldown"}
              }
            }
          },
          "Outputs": {
            "TrackingRef": {"Value": {"Ref": "Tracking"}},
            "TrackingArn": {"Value": {"Fn::GetAtt": ["Tracking", "Arn"]}},
            "TrackingName": {"Value": {"Fn::GetAtt": ["Tracking", "PolicyName"]}},
            "SimpleRef": {"Value": {"Ref": "Simple"}},
            "SimpleName": {"Value": {"Fn::GetAtt": ["Simple", "PolicyName"]}}
          }
        }
        """.formatted(ASG);

    @Inject
    AutoScalingService autoScalingService;

    @Test
    void createUpdateAndDeleteScalingPolicies() throws InterruptedException {
        cloudFormation("CreateStack", Map.of("TargetValue", "50", "Cooldown", "60"));
        String created = describeStacks("CREATE_COMPLETE");
        String trackingArn = outputValue(created, "TrackingRef");
        String trackingName = outputValue(created, "TrackingName");
        String simpleArn = outputValue(created, "SimpleRef");
        String simpleName = outputValue(created, "SimpleName");

        // Ref and Fn::GetAtt Arn agree, and the name is CloudFormation-style, not a template property.
        assertEquals(trackingArn, outputValue(created, "TrackingArn"));
        assertTrue(trackingName.startsWith(STACK + "-Tracking-"), trackingName);
        assertTrue(simpleName.startsWith(STACK + "-Simple-"), simpleName);
        assertTrue(trackingArn.endsWith(":scalingPolicy:" + ASG + ":" + trackingName), trackingArn);

        // Both policies are live on the group, with the values the template asked for.
        describePolicies()
            .body(containsString("<PolicyName>" + trackingName + "</PolicyName>"))
            .body(containsString("<PolicyARN>" + simpleArn + "</PolicyARN>"))
            .body(containsString("<PolicyType>TargetTrackingScaling</PolicyType>"))
            .body(containsString("<Cooldown>60</Cooldown>"))
            .body(containsString("<EstimatedInstanceWarmup>120</EstimatedInstanceWarmup>"));
        assertEquals(50.0, tracking(trackingName).getTargetTrackingConfiguration().getTargetValue());
        assertEquals("ASGAverageCPUUtilization",
                tracking(trackingName).getTargetTrackingConfiguration().getPredefinedMetricSpecification()
                        .getPredefinedMetricType());

        cloudFormation("UpdateStack", Map.of("TargetValue", "70", "Cooldown", "120"));
        String updated = describeStacks("UPDATE_COMPLETE");

        // Same names and ARNs, new values: the policies were rewritten, not replaced.
        assertEquals(trackingArn, outputValue(updated, "TrackingRef"));
        assertEquals(trackingName, outputValue(updated, "TrackingName"));
        assertEquals(simpleArn, outputValue(updated, "SimpleRef"));
        describePolicies()
            .body(containsString("<Cooldown>120</Cooldown>"))
            .body(not(containsString("<Cooldown>60</Cooldown>")));
        assertEquals(70.0, tracking(trackingName).getTargetTrackingConfiguration().getTargetValue());
        assertEquals(2, autoScalingService.describePolicies(REGION, ASG, null).size());

        cloudFormation("DeleteStack", Map.of());
        awaitStackDeleted();

        assertEquals(List.of(), autoScalingService.describePolicies(REGION, null, List.of(trackingName, simpleName)));
    }

    private ScalingPolicy tracking(String name) {
        return autoScalingService.describePolicies(REGION, ASG, List.of(name)).get(0);
    }

    private static void cloudFormation(String action, Map<String, String> parameters) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", STACK);
        if (!"DeleteStack".equals(action)) {
            request.formParam("TemplateBody", TEMPLATE);
        }
        int index = 1;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            request.formParam("Parameters.member." + index + ".ParameterKey", parameter.getKey());
            request.formParam("Parameters.member." + index + ".ParameterValue", parameter.getValue());
            index++;
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String describeStacks(String expectedStatus) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", STACK)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    /** DeleteStack runs asynchronously; a successful delete removes the stack entirely. */
    private static void awaitStackDeleted() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String body = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", STACK)
            .when().post("/").then().extract().asString();
            if (body.contains("does not exist")) {
                return;
            }
            if (body.contains("<StackStatus>DELETE_FAILED</StackStatus>")) {
                fail("stack delete failed: " + body);
            }
            Thread.sleep(50);
        }
        fail("stack " + STACK + " was not deleted within the timeout");
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private static ValidatableResponse describePolicies() {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", ASG_AUTH)
            .formParam("Action", "DescribePolicies")
            .formParam("AutoScalingGroupName", ASG)
        .when().post("/").then().statusCode(200);
    }
}
