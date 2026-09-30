package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end check that CloudFormation provisions AWS::EC2::LaunchTemplate for real
 * (issue #1971): the template exists in Ec2Service, Ref resolves to the launch template
 * id, Fn::GetAtt LatestVersionNumber resolves, and DeleteStack removes it. Metadata-only,
 * so the test is Docker-free.
 */
@QuarkusTest
class CloudFormationLaunchTemplateIntegrationTest {

    @Inject
    Ec2Service ec2Service;

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    @Test
    void ec2InstanceResolvesImageAndTypeFromLaunchTemplate() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-lt-inst-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Lt": {
                      "Type": "AWS::EC2::LaunchTemplate",
                      "Properties": {
                        "LaunchTemplateData": {
                          "ImageId": "ami-87654321",
                          "InstanceType": "t3.small"
                        }
                      }
                    },
                    "Inst": {
                      "Type": "AWS::EC2::Instance",
                      "Properties": {
                        "LaunchTemplate": {
                          "LaunchTemplateId": {"Ref": "Lt"},
                          "Version": {"Fn::GetAtt": ["Lt", "LatestVersionNumber"]}
                        }
                      }
                    }
                  },
                  "Outputs": {
                    "InstanceId": {"Value": {"Ref": "Inst"}}
                  }
                }
                """;

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
            .body(containsString("<OutputValue>i-"));

        // Delete the stack so the instance is terminated: a live instance would otherwise leak
        // into the shared emulator state and change what EC2 describe calls in other tests see.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void createStackProvisionsLaunchTemplateWithVersionAttributes() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String templateName = "cfn-lt-" + suffix;
        String stackName = "cfn-lt-stack-" + suffix;
        String encodedUserData = "IyEvYmluL2Jhc2gKZWNobyBoaQo=";

        String template = """
                {
                  "Resources": {
                    "AppLaunchTemplate": {
                      "Type": "AWS::EC2::LaunchTemplate",
                      "Properties": {
                        "LaunchTemplateName": "%s",
                        "LaunchTemplateData": {
                          "ImageId": "ami-12345678",
                          "InstanceType": "t3.micro",
                          "UserData": "%s"
                        }
                      }
                    }
                  },
                  "Outputs": {
                    "TemplateId": {"Value": {"Ref": "AppLaunchTemplate"}},
                    "LatestVersion": {"Value": {"Fn::GetAtt": ["AppLaunchTemplate", "LatestVersionNumber"]}}
                  }
                }
                """.formatted(templateName, encodedUserData);

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

        // Stack completes, Ref resolves to a launch template id, GetAtt resolves the version.
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
            .body(containsString("<OutputValue>lt-"))
            .body(containsString("<OutputValue>1</OutputValue>"));

        // The launch template is real: EC2 DescribeLaunchTemplates finds it by name.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "DescribeLaunchTemplates")
            .formParam("LaunchTemplateName.1", templateName)
            .formParam("Version", "2016-11-15")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(templateName));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "DescribeLaunchTemplateVersions")
            .formParam("LaunchTemplateName", templateName)
            .formParam("Versions.1", "$Latest")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.userData",
                    equalTo(encodedUserData));

        LaunchTemplateData launchData = ec2Service.resolveLaunchTemplateData(
                "us-east-1", null, templateName, null);
        assertEquals("#!/bin/bash\necho hi\n", launchData.getUserData());
        assertEquals(encodedUserData, launchData.getEncodedUserData());

        String instanceId = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "RunInstances")
            .formParam("LaunchTemplate.LaunchTemplateName", templateName)
            .formParam("MinCount", "1")
            .formParam("MaxCount", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        Instance instance = ec2Service.describeInstances("us-east-1", List.of(instanceId), Map.of())
                .getFirst().getInstances().getFirst();
        assertEquals(launchData.getUserData(), instance.getUserData());
        assertEquals(encodedUserData, instance.getEncodedUserData());

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "TerminateInstances")
            .formParam("InstanceId.1", instanceId)
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
        CfnStackWaits.awaitStackDeleted(stackName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "DescribeLaunchTemplates")
            .formParam("Version", "2016-11-15")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString(templateName)));
    }
}
