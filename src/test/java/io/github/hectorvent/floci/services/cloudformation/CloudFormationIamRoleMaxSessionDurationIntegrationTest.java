package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * An {@code AWS::IAM::Role}'s {@code MaxSessionDuration} reaches the role, so AssumeRole accepts a
 * session as long as the template allows and refuses a longer one.
 */
@QuarkusTest
class CloudFormationIamRoleMaxSessionDurationIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String STS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/sts/aws4_request";

    private static String stackWithMaxSessionDuration(String roleName, int maxSessionDuration) {
        return """
                {
                  "Resources": {
                    "Role": {
                      "Type": "AWS::IAM::Role",
                      "Properties": {
                        "RoleName": "%s",
                        "MaxSessionDuration": %d,
                        "AssumeRolePolicyDocument": {
                          "Version": "2012-10-17",
                          "Statement": [{
                            "Effect": "Allow",
                            "Principal": {"AWS": "arn:aws:iam::000000000000:root"},
                            "Action": "sts:AssumeRole"
                          }]
                        }
                      }
                    }
                  }
                }
                """.formatted(roleName, maxSessionDuration);
    }

    private static void stackAction(String action, String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static void assertStackStatus(String stackName, String status) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>" + status + "</StackStatus>"));
    }

    private static ValidatableResponse assumeRole(String roleName, int durationSeconds) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", STS_AUTH)
            .formParam("Action", "AssumeRole")
            .formParam("RoleArn", "arn:aws:iam::000000000000:role/" + roleName)
            .formParam("RoleSessionName", "max-session")
            .formParam("DurationSeconds", String.valueOf(durationSeconds))
        .when()
            .post("/")
        .then();
    }

    @Test
    void assumeRoleHonorsTheTemplatesMaxSessionDurationOnCreateAndUpdate() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "max-session-" + suffix;
        String roleName = "max-session-role-" + suffix;

        stackAction("CreateStack", stackName, stackWithMaxSessionDuration(roleName, 43200));
        assertStackStatus(stackName, "CREATE_COMPLETE");
        assumeRole(roleName, 43200).statusCode(200);

        stackAction("UpdateStack", stackName, stackWithMaxSessionDuration(roleName, 7200));
        assertStackStatus(stackName, "UPDATE_COMPLETE");
        assumeRole(roleName, 7200).statusCode(200);
        assumeRole(roleName, 7201).statusCode(400).body(containsString("<Code>ValidationError</Code>"));
    }
}
