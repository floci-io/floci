package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check for the small metadata provisioners (issue #2002):
 * ApiGateway::Account records the CloudWatch role, AutoScaling::LifecycleHook lands in
 * AutoScalingService, EC2::FlowLog lands in FlowLogService — and both the hook and the flow log
 * go away again when the stack is deleted. Docker-free.
 */
@QuarkusTest
class CloudFormationSmallMetadataIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String ASG_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/autoscaling/aws4_request";
    private static final String APIGW_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/apigateway/aws4_request";

    private static final Pattern FLOW_LOG_ID =
            Pattern.compile("<OutputValue>(fl-[0-9a-f]+)</OutputValue>");

    @Test
    void accountHookAndFlowLogProvision() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-smallmeta-" + suffix;
        String asgName = "meta-asg-" + suffix;
        String hookName = "meta-hook-" + suffix;
        String roleArn = "arn:aws:iam::000000000000:role/apigw-cw-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.80.0.0/16"}},
                    "Subnet": {
                      "Type": "AWS::EC2::Subnet",
                      "Properties": {"VpcId": {"Ref": "Vpc"}, "CidrBlock": "10.80.1.0/24"}
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
                    "Hook": {
                      "Type": "AWS::AutoScaling::LifecycleHook",
                      "Properties": {
                        "AutoScalingGroupName": {"Ref": "Asg"},
                        "LifecycleHookName": "%s",
                        "LifecycleTransition": "autoscaling:EC2_INSTANCE_TERMINATING"
                      }
                    },
                    "ApiAccount": {
                      "Type": "AWS::ApiGateway::Account",
                      "Properties": {"CloudWatchRoleArn": "%s"}
                    },
                    "Flow": {
                      "Type": "AWS::EC2::FlowLog",
                      "Properties": {
                        "ResourceId": {"Ref": "Vpc"},
                        "ResourceType": "VPC",
                        "TrafficType": "ALL",
                        "LogDestinationType": "cloud-watch-logs",
                        "LogDestination": "arn:aws:logs:us-east-1:000000000000:log-group:/flow/meta"
                      }
                    }
                  },
                  "Outputs": {"FlowId": {"Value": {"Ref": "Flow"}}}
                }
                """.formatted(asgName, hookName, roleArn);

        createStack(stackName, template);

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
            .body(containsString("<OutputValue>fl-"));

        // The hook is live in AutoScalingService.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", ASG_AUTH)
            .formParam("Action", "DescribeLifecycleHooks")
            .formParam("AutoScalingGroupName", asgName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(hookName));

        // The flow log is live in FlowLogService.
        given()
            .formParam("Action", "DescribeFlowLogs")
            .header("Authorization", EC2_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("/flow/meta"));

        // The account-level CloudWatch role reached the API Gateway account settings.
        given()
            .header("Authorization", APIGW_AUTH)
        .when()
            .get("/account")
        .then()
            .statusCode(200)
            .body("cloudwatchRoleArn", equalTo(roleArn));
    }

    /**
     * Deleting the stack must take the hook and the flow log with it. The group the hook hangs off
     * is created outside the stack so it survives the delete and DescribeLifecycleHooks still
     * answers — the hook itself has to be gone.
     */
    @Test
    void stackDeletionRemovesHookAndFlowLog() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-smallmeta-del-" + suffix;
        String asgName = "meta-survivor-asg-" + suffix;
        String lcName = "meta-survivor-lc-" + suffix;
        String hookName = "meta-del-hook-" + suffix;

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", ASG_AUTH)
            .formParam("Action", "CreateLaunchConfiguration")
            .formParam("LaunchConfigurationName", lcName)
            .formParam("ImageId", "ami-11111111")
            .formParam("InstanceType", "t3.micro")
        .when().post("/").then().statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", ASG_AUTH)
            .formParam("Action", "CreateAutoScalingGroup")
            .formParam("AutoScalingGroupName", asgName)
            .formParam("LaunchConfigurationName", lcName)
            .formParam("MinSize", "0")
            .formParam("MaxSize", "0")
            .formParam("DesiredCapacity", "0")
            .formParam("AvailabilityZones.member.1", "us-east-1a")
        .when().post("/").then().statusCode(200);

        String template = """
                {
                  "Resources": {
                    "Hook": {
                      "Type": "AWS::AutoScaling::LifecycleHook",
                      "Properties": {
                        "AutoScalingGroupName": "%s",
                        "LifecycleHookName": "%s",
                        "LifecycleTransition": "autoscaling:EC2_INSTANCE_LAUNCHING",
                        "HeartbeatTimeout": 120,
                        "DefaultResult": "CONTINUE"
                      }
                    },
                    "Flow": {
                      "Type": "AWS::EC2::FlowLog",
                      "Properties": {
                        "ResourceId": "vpc-0000%s",
                        "ResourceType": "VPC",
                        "TrafficType": "REJECT",
                        "LogDestinationType": "cloud-watch-logs",
                        "LogDestination": "arn:aws:logs:us-east-1:000000000000:log-group:/flow/del"
                      }
                    }
                  },
                  "Outputs": {"FlowId": {"Value": {"Ref": "Flow"}}}
                }
                """.formatted(asgName, hookName, suffix);

        createStack(stackName, template);

        String describe = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .extract().asString();

        Matcher matcher = FLOW_LOG_ID.matcher(describe);
        assertTrue(matcher.find(), "stack outputs should carry the flow log id: " + describe);
        String flowLogId = matcher.group(1);

        assertTrue(describeFlowLogs().contains(flowLogId), "flow log should exist after create");
        assertTrue(describeHooks(asgName).contains(hookName), "hook should exist after create");

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200);

        awaitGone(() -> !describeFlowLogs().contains(flowLogId),
                "flow log " + flowLogId + " still present after DeleteStack");
        awaitGone(() -> !describeHooks(asgName).contains(hookName),
                "lifecycle hook " + hookName + " still present after DeleteStack");

        // Only the hook was stack-owned: the externally created group is untouched.
        assertFalse(describeHooks(asgName).contains(hookName));
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", ASG_AUTH)
            .formParam("Action", "DescribeAutoScalingGroups")
            .formParam("AutoScalingGroupNames.member.1", asgName)
        .when().post("/").then().statusCode(200).body(containsString(asgName));
    }

    private static void createStack(String stackName, String template) {
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
    }

    private static String describeFlowLogs() {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "DescribeFlowLogs")
        .when().post("/").then().statusCode(200).extract().asString();
    }

    private static String describeHooks(String asgName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", ASG_AUTH)
            .formParam("Action", "DescribeLifecycleHooks")
            .formParam("AutoScalingGroupName", asgName)
        .when().post("/").then().statusCode(200).extract().asString();
    }

    /** DeleteStack runs asynchronously, so poll until the resource is gone or time out. */
    private static void awaitGone(BooleanSupplier gone, String message) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (gone.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(message);
    }
}
