package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end check that CloudFormation provisions the leaf IAM types into IamService for real
 * rather than stubbing them: {@code AWS::IAM::AccessKey} Ref resolves to a real access key id, and
 * {@code AWS::IAM::InstanceProfile} Fn::GetAtt Arn resolves to a real instance-profile arn.
 */
@QuarkusTest
class CloudFormationIamLeafIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=111122223333/20260205/us-east-1/cloudformation/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=111122223333/20260205/us-east-1/iam/aws4_request";

    @Test
    void createStackProvisionsAccessKeyAndInstanceProfile() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String userName = "leaf-user-" + suffix;
        String roleName = "leaf-role-" + suffix;
        String profileName = "leaf-profile-" + suffix;
        String stackName = "cfn-iam-leaf-" + suffix;

        String template = """
                {
                  "Resources": {
                    "User": {
                      "Type": "AWS::IAM::User",
                      "Properties": {"UserName": "%s"}
                    },
                    "Key": {
                      "Type": "AWS::IAM::AccessKey",
                      "Properties": {"UserName": {"Ref": "User"}}
                    },
                    "Role": {
                      "Type": "AWS::IAM::Role",
                      "Properties": {
                        "RoleName": "%s",
                        "AssumeRolePolicyDocument": {
                          "Version": "2012-10-17",
                          "Statement": [{"Effect": "Allow", "Principal": {"Service": "ec2.amazonaws.com"}, "Action": "sts:AssumeRole"}]
                        }
                      }
                    },
                    "Profile": {
                      "Type": "AWS::IAM::InstanceProfile",
                      "Properties": {"InstanceProfileName": "%s", "Roles": [{"Ref": "Role"}]}
                    }
                  },
                  "Outputs": {
                    "KeyRef": {"Value": {"Ref": "Key"}},
                    "KeyId": {"Value": {"Fn::GetAtt": ["Key", "Id"]}},
                    "ProfileArn": {"Value": {"Fn::GetAtt": ["Profile", "Arn"]}}
                  }
                }
                """.formatted(userName, roleName, profileName);

        String stackId = createStack(stackName, template);
        awaitStackStatus(stackId, "CREATE_COMPLETE");

        String stackXml = describeStacks(stackId);
        String keyRef = outputValue(stackXml, "KeyRef");
        // Ref and Fn::GetAtt Id resolve to the same real access key id, not the literal "Key.Id".
        assertEquals(keyRef, outputValue(stackXml, "KeyId"));
        assertTrue(keyRef != null && !keyRef.isBlank() && !keyRef.contains("Key."), keyRef);

        String profileArn = outputValue(stackXml, "ProfileArn");
        assertEquals("arn:aws:iam::111122223333:instance-profile/" + profileName, profileArn);

        // The instance profile really exists in IAM (provisioned, not stubbed).
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetInstanceProfile")
            .formParam("InstanceProfileName", profileName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<InstanceProfileName>" + profileName + "</InstanceProfileName>"));

        deleteStack(stackName);
        awaitStackStatus(stackId, "DELETE_COMPLETE");

        // The instance profile is gone after the stack delete.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetInstanceProfile")
            .formParam("InstanceProfileName", profileName)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchEntity"));
    }

    @Test
    void updateStackRenamingTheInstanceProfileReplacesItAndDeletesTheOldProfile() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String roleName = "rename-role-" + suffix;
        String firstName = "rename-profile-a-" + suffix;
        String secondName = "rename-profile-b-" + suffix;
        String stackName = "cfn-iam-profile-rename-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Role": {
                      "Type": "AWS::IAM::Role",
                      "Properties": {
                        "RoleName": "%s",
                        "AssumeRolePolicyDocument": {
                          "Version": "2012-10-17",
                          "Statement": [{"Effect": "Allow", "Principal": {"Service": "ec2.amazonaws.com"}, "Action": "sts:AssumeRole"}]
                        }
                      }
                    },
                    "Profile": {
                      "Type": "AWS::IAM::InstanceProfile",
                      "Properties": {"InstanceProfileName": "%s", "Roles": [{"Ref": "Role"}]}
                    }
                  },
                  "Outputs": {
                    "ProfileRef": {"Value": {"Ref": "Profile"}}
                  }
                }
                """;

        String stackId = createStack(stackName, template.formatted(roleName, firstName));
        awaitStackStatus(stackId, "CREATE_COMPLETE");
        assertEquals(firstName, outputValue(describeStacks(stackId), "ProfileRef"));

        // InstanceProfileName is createOnly, so renaming it replaces the profile.
        updateStack(stackName, template.formatted(roleName, secondName));
        awaitStackStatus(stackId, "UPDATE_COMPLETE");

        // Ref now resolves to the replacement profile, which really exists in IAM.
        assertEquals(secondName, outputValue(describeStacks(stackId), "ProfileRef"));
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetInstanceProfile")
            .formParam("InstanceProfileName", secondName)
        .when().post("/").then().statusCode(200)
            .body(containsString("<InstanceProfileName>" + secondName + "</InstanceProfileName>"));

        // The profile it displaced was cleaned up once the update committed.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetInstanceProfile")
            .formParam("InstanceProfileName", firstName)
        .when().post("/").then().statusCode(404)
            .body(containsString("NoSuchEntity"));

        deleteStack(stackName);
        awaitStackStatus(stackId, "DELETE_COMPLETE");
    }

    private static String createStack(String stackName, String template) {
        return cfnQuery("CreateStack", stackName, template)
                .then()
                .statusCode(200)
                .extract()
                .xmlPath()
                .getString("CreateStackResponse.CreateStackResult.StackId");
    }

    private static void updateStack(String stackName, String template) {
        cfnQuery("UpdateStack", stackName, template).then().statusCode(200);
    }

    private static void deleteStack(String stackName) {
        cfnQuery("DeleteStack", stackName, null).then().statusCode(200);
    }

    private static void awaitStackStatus(String stackId, String status) throws InterruptedException {
        String xml = "";
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            xml = describeStacks(stackId);
            if (xml.contains("<StackStatus>" + status + "</StackStatus>")) {
                return;
            }
            Thread.sleep(50);
        }
        fail("stack " + stackId + " never reached " + status + ": " + xml);
    }

    private static String describeStacks(String stackId) {
        return cfnQuery("DescribeStacks", stackId, null).then().statusCode(200).extract().asString();
    }

    private static Response cfnQuery(String action, String stackName, String template) {
        RequestSpecification req = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", stackName);
        if (template != null) {
            req.formParam("TemplateBody", template);
        }
        return req.when().post("/");
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }
}
