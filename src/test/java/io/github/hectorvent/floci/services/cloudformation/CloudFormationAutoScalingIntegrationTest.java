package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * End-to-end check that CloudFormation provisions AWS::AutoScaling::LaunchConfiguration and
 * AWS::AutoScaling::AutoScalingGroup for real (into AutoScalingService) rather than stubbing them,
 * and that the group references the launch configuration created in the same stack. Both are
 * metadata (no container), so the test is Docker-free. Auto Scaling is the Query API.
 */
@QuarkusTest
class CloudFormationAutoScalingIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String ASG_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/autoscaling/aws4_request";

    @Test
    void createStackProvisionsAutoScalingGroupVisibleToAutoScaling() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String lcName = "cfn-lc-" + suffix;
        String asgName = "cfn-asg-" + suffix;
        String stackName = "cfn-asg-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "LaunchConfig": {
                      "Type": "AWS::AutoScaling::LaunchConfiguration",
                      "Properties": {
                        "LaunchConfigurationName": "%s",
                        "ImageId": "ami-12345678",
                        "InstanceType": "t3.micro"
                      }
                    },
                    "Asg": {
                      "Type": "AWS::AutoScaling::AutoScalingGroup",
                      "Properties": {
                        "AutoScalingGroupName": "%s",
                        "LaunchConfigurationName": {"Ref": "LaunchConfig"},
                        "MinSize": 1,
                        "MaxSize": 3,
                        "DesiredCapacity": 2,
                        "AvailabilityZones": ["us-east-1a"]
                      }
                    }
                  },
                  "Outputs": {
                    "AsgArn": {"Value": {"Fn::GetAtt": ["Asg", "Arn"]}}
                  }
                }
                """.formatted(lcName, asgName);

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

        // Stack completes and Fn::GetAtt(Asg, Arn) resolves to the real ASG ARN.
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
            .body(containsString(":autoScalingGroup:" + asgName));

        // The group really exists in Auto Scaling and references the launch configuration.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", ASG_AUTH)
            .formParam("Action", "DescribeAutoScalingGroups")
            .formParam("AutoScalingGroupNames.member.1", asgName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(asgName))
            .body(containsString(lcName));
    }
}
