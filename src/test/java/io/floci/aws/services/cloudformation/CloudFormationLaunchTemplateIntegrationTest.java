package io.floci.aws.services.cloudformation;

import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.cloudformation.provisioners.CfnRollback;
import io.floci.aws.services.cloudformation.provisioners.Ec2LaunchTemplateCfnProvisioner;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.LaunchTemplate;
import io.floci.aws.services.ec2.model.LaunchTemplateData;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Inject
    Ec2LaunchTemplateCfnProvisioner launchTemplateProvisioner;

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    @Test
    void rollbackAcceptsAVersionAlreadyDeletedThroughThePublicApi() {
        LaunchTemplate template = ec2Service.createLaunchTemplate("us-east-1",
                "already-deleted-version-" + System.nanoTime(), new LaunchTemplateData(), List.of());
        String id = template.getLaunchTemplateId();
        try {
            ec2Service.createLaunchTemplateVersion("us-east-1", id, null, null, new LaunchTemplateData());
            StackResource resource = new StackResource();
            resource.setLogicalId("Lt");
            resource.getAttributes().put("LatestVersionNumber", "2");
            resource.getAttributes().put(CfnRollback.LAUNCH_TEMPLATE_UPDATE_SNAPSHOT_ATTR,
                    "{\"region\":\"us-east-1\",\"launchTemplateId\":\"" + id + "\","
                            + "\"createdVersion\":\"2\",\"priorLatestVersion\":\"1\",\"priorDefaultVersion\":\"1\"}");
            given().header("Authorization", EC2_AUTH)
                    .formParam("Action", "DeleteLaunchTemplateVersions")
                    .formParam("LaunchTemplateId", id).formParam("LaunchTemplateVersion.1", "2")
                    .post("/").then().statusCode(200);
            assertTrue(launchTemplateProvisioner.rollbackUpdate(resource));
            assertEquals("1", resource.getAttributes().get("LatestVersionNumber"));
            assertEquals("1", resource.getAttributes().get("DefaultVersionNumber"));
            assertEquals(List.of("1"), ec2Service.describeLaunchTemplateVersions("us-east-1", id, null, List.of())
                    .stream().map(LaunchTemplate::getLatestVersionNumber).toList());
        } finally {
            ec2Service.deleteLaunchTemplate("us-east-1", id, null);
        }
    }

    @Test
    void rollbackDeletesTheFailedVersionWithoutReusingItsNumber() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-lt-version-gap-" + suffix;
        String templateName = "cfn-lt-version-gap-template-" + suffix;
        try {
            applyVersionTemplate(stackName, templateName, "t3.micro", false, false);
            CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE", CFN_AUTH);
            applyVersionTemplate(stackName, templateName, "t3.small", true, false);
            CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE", CFN_AUTH);
            applyVersionTemplate(stackName, templateName, "t3.medium", true, true);
            CfnStackWaits.awaitStatus(stackName, "UPDATE_ROLLBACK_COMPLETE", CFN_AUTH);

            given()
                .header("Authorization", EC2_AUTH)
                .formParam("Action", "DescribeLaunchTemplates")
                .formParam("LaunchTemplateName.1", templateName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeLaunchTemplatesResponse.launchTemplates.item.defaultVersionNumber", equalTo("1"))
                .body("DescribeLaunchTemplatesResponse.launchTemplates.item.latestVersionNumber", equalTo("2"));
            given()
                .header("Authorization", EC2_AUTH)
                .formParam("Action", "DescribeLaunchTemplateVersions")
                .formParam("LaunchTemplateName", templateName)
                .formParam("LaunchTemplateVersion.1", "3")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(containsString("<Code>InvalidLaunchTemplateVersion.NotFound</Code>"));

            applyVersionTemplate(stackName, templateName, "t3.large", true, false);
            CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE", CFN_AUTH);
            given()
                .header("Authorization", EC2_AUTH)
                .formParam("Action", "DescribeLaunchTemplateVersions")
                .formParam("LaunchTemplateName", templateName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.versionNumber",
                        contains("1", "2", "4"))
                .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.instanceType",
                        contains("t3.micro", "t3.small", "t3.large"));
        } finally {
            given()
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DeleteStack")
                .formParam("StackName", stackName)
            .when()
                .post("/")
            .then()
                .statusCode(200);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
    }

    private void applyVersionTemplate(String stack, String name, String type, boolean update, boolean fail) {
        String failure = fail ? """
                ,"BrokenSubnet": {"Type": "AWS::EC2::Subnet", "DependsOn": "Lt",
                    "Properties": {"VpcId": "vpc-ffffffff", "CidrBlock": "10.84.1.0/24"}}
                """ : "";
        String template = """
                {"Resources": {"Lt": {"Type": "AWS::EC2::LaunchTemplate", "Properties": {
                    "LaunchTemplateName": "%s",
                    "LaunchTemplateData": {"ImageId": "ami-12345678", "InstanceType": "%s"}}}%s}}
                """.formatted(name, type, failure);
        given()
            .header("Authorization", CFN_AUTH)
            .formParam("Action", update ? "UpdateStack" : "CreateStack")
            .formParam("StackName", stack)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

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

        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE", CFN_AUTH);
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
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE", CFN_AUTH);
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

    @Test
    void explicitUserDataOverridesOversizedLegacyTemplate() {
        String templateName = "legacy-user-data-" + Long.toString(System.nanoTime(), 36);
        String oversized = Base64.getEncoder().encodeToString(
                "A".repeat(16 * 1024 + 1).getBytes(StandardCharsets.UTF_8));
        LaunchTemplateData legacyData = new LaunchTemplateData();
        legacyData.setImageId("ami-12345678");
        legacyData.setInstanceType("t3.micro");
        legacyData.setEncodedUserData(oversized);
        ec2Service.createLaunchTemplate("us-east-1", templateName, legacyData, List.of());

        String userData = "#!/bin/sh\necho override\n";
        String replacement = Base64.getEncoder().encodeToString(userData.getBytes(StandardCharsets.UTF_8));
        String instanceId = null;
        try {
            given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", EC2_AUTH)
                .formParam("Action", "RunInstances")
                .formParam("LaunchTemplate.LaunchTemplateName", templateName)
                .formParam("MinCount", "1")
                .formParam("MaxCount", "1")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(containsString("UserData exceeds the 16 KiB limit"));

            instanceId = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", EC2_AUTH)
                .formParam("Action", "RunInstances")
                .formParam("LaunchTemplate.LaunchTemplateName", templateName)
                .formParam("UserData", replacement)
                .formParam("MinCount", "1")
                .formParam("MaxCount", "1")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");

            Instance instance = ec2Service.describeInstances("us-east-1", List.of(instanceId), Map.of())
                    .getFirst().getInstances().getFirst();
            assertEquals(userData, instance.getUserData());
            assertEquals(replacement, instance.getEncodedUserData());
        } finally {
            if (instanceId != null) {
                ec2Service.terminateInstances("us-east-1", List.of(instanceId));
            }
            ec2Service.deleteLaunchTemplate("us-east-1", null, templateName);
        }
    }

    @Test
    void cloudFormationInstanceIgnoresUnusedLegacyTemplateUserData() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String templateName = "legacy-cfn-user-data-" + suffix;
        String stackName = "legacy-cfn-user-data-stack-" + suffix;
        String oversized = Base64.getEncoder().encodeToString(
                "A".repeat(16 * 1024 + 1).getBytes(StandardCharsets.UTF_8));
        LaunchTemplateData legacyData = new LaunchTemplateData();
        legacyData.setImageId("ami-12345678");
        legacyData.setInstanceType("t3.micro");
        legacyData.setEncodedUserData(oversized);
        ec2Service.createLaunchTemplate("us-east-1", templateName, legacyData, List.of());

        String template = """
                {
                  "Resources": {
                    "Inst": {
                      "Type": "AWS::EC2::Instance",
                      "Properties": {
                        "LaunchTemplate": {"LaunchTemplateName": "%s", "Version": "1"},
                        "UserData": "IyEvYmluL3NoCmVjaG8gY2ZuCg=="
                      }
                    }
                  }
                }
                """.formatted(templateName);
        try (AutoCloseable cleanup = () -> {
            try {
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
            } finally {
                ec2Service.deleteLaunchTemplate("us-east-1", null, templateName);
            }
        }) {
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

            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName);
            assertEquals("CREATE_COMPLETE", state.status(), state.reason());
        }
    }
}
