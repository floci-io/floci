package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * End-to-end check for the small metadata provisioners (issue #2002):
 * ApiGateway::Account records, AutoScaling::LifecycleHook lands in
 * AutoScalingService, EC2::FlowLog lands in FlowLogService. Docker-free.
 */
@QuarkusTest
class CloudFormationSmallMetadataIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String ASG_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/autoscaling/aws4_request";

    @Test
    void accountHookAndFlowLogProvision() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-smallmeta-" + suffix;
        String asgName = "meta-asg-" + suffix;
        String hookName = "meta-hook-" + suffix;

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
                      "Properties": {"CloudWatchRoleArn": "arn:aws:iam::000000000000:role/apigw-cw"}
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
                """.formatted(asgName, hookName);

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
    }
}
