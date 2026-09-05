package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies CloudFormation provisioning and deprovisioning lifecycle for {@code AWS::IAM::User}
 * (regression test for issue #2490).
 */
@QuarkusTest
class CloudFormationIamUserIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=111122223333/20260205/us-east-1/cloudformation/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=111122223333/20260205/us-east-1/iam/aws4_request";

    @Test
    void deleteStackDeletesIamUserAndAllowsRecreation() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String userName = "probe-user-" + suffix;
        String stackName = "cfn-user-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "ProbeUser": {
                      "Type": "AWS::IAM::User",
                      "Properties": {
                        "UserName": "%s"
                      }
                    }
                  }
                }
                """.formatted(userName);

        String stackId = createStack(stackName, template);
        awaitStackStatus(stackId, "CREATE_COMPLETE");

        // Verify IAM user exists and Arn matches
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetUser")
            .formParam("UserName", userName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<UserName>" + userName + "</UserName>"))
            .body(containsString("<Arn>arn:aws:iam::111122223333:user/" + userName + "</Arn>"));

        // Delete the stack
        deleteStack(stackName);
        awaitStackStatus(stackId, "DELETE_COMPLETE");

        // Verify IAM user is gone (NoSuchEntity / 404)
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetUser")
            .formParam("UserName", userName)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchEntity"));

        // Redeploying with the same explicit UserName succeeds rather than failing with EntityAlreadyExists
        String newStackName = "cfn-user-stack-recreate-" + suffix;
        String newStackId = createStack(newStackName, template);
        awaitStackStatus(newStackId, "CREATE_COMPLETE");

        deleteStack(newStackName);
        awaitStackStatus(newStackId, "DELETE_COMPLETE");
    }

    @Test
    void deleteStackDeletesUserWithGeneratedName() throws InterruptedException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-genuser-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "GenUser": {
                      "Type": "AWS::IAM::User"
                    }
                  }
                }
                """;

        String stackId = createStack(stackName, template);
        awaitStackStatus(stackId, "CREATE_COMPLETE");

        String resourcesXml = cfnQuery("DescribeStackResources", stackName)
                .then().statusCode(200).extract().asString();
        assertThat(resourcesXml, containsString("<PhysicalResourceId>"));

        String physicalIdTag = "<PhysicalResourceId>";
        int start = resourcesXml.indexOf(physicalIdTag) + physicalIdTag.length();
        int end = resourcesXml.indexOf("</PhysicalResourceId>", start);
        String generatedUserName = resourcesXml.substring(start, end);

        // Verify generated user exists
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetUser")
            .formParam("UserName", generatedUserName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<UserName>" + generatedUserName + "</UserName>"));

        deleteStack(stackName);
        awaitStackStatus(stackId, "DELETE_COMPLETE");

        // Verify generated user is deleted
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "GetUser")
            .formParam("UserName", generatedUserName)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchEntity"));
    }

    private static String createStack(String stackName, String template) {
        return cfnQuery("CreateStack", stackName, template)
                .then()
                .statusCode(200)
                .extract()
                .xmlPath()
                .getString("CreateStackResponse.CreateStackResult.StackId");
    }

    private static void deleteStack(String stackName) {
        cfnQuery("DeleteStack", stackName)
                .then()
                .statusCode(200);
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
        return cfnQuery("DescribeStacks", stackId).then().statusCode(200).extract().asString();
    }

    private static Response cfnQuery(String action, String stackName) {
        return cfnQuery(action, stackName, null);
    }

    private static Response cfnQuery(String action, String stackName, String template) {
        var req = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", stackName);
        if (template != null) {
            req.formParam("TemplateBody", template);
        }
        return req.when().post("/");
    }
}
