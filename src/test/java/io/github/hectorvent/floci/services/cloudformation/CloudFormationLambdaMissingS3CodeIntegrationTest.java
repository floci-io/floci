package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * A stack whose Lambda names code in S3 that cannot be read must fail, the way real
 * CloudFormation does (issue #2648). Floci used to substitute a built-in stub handler, so the
 * stack reached CREATE_COMPLETE running code the template never referenced — and when the
 * handler was not "index.handler" it failed with a handler error that pointed away from the
 * real problem. Control plane only, so the test is Docker-free.
 */
@QuarkusTest
class CloudFormationLambdaMissingS3CodeIntegrationTest {

    static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";

    static String template(String fnName, String handler) {
        return """
                {
                  "Resources": {
                    "Fn": {
                      "Type": "AWS::Lambda::Function",
                      "Properties": {
                        "FunctionName": "%s",
                        "Runtime": "nodejs20.x",
                        "Handler": "%s",
                        "Role": "arn:aws:iam::000000000000:role/r",
                        "Code": {"S3Bucket": "cfn-missing-code-bucket", "S3Key": "does-not-exist.zip"}
                      }
                    }
                  }
                }
                """.formatted(fnName, handler);
    }

    static void createStack(String stackName, String template) {
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

    private static void assertStackFailed(String stackName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>")));
    }

    @Test
    void unreadableS3CodeFailsTheStackInsteadOfDeployingAStub() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-missing-code-" + suffix;
        String fnName = "missing-code-fn-" + suffix;

        // "index.handler" is the stub's own handler, so before the fix this combination was the
        // silent one: the stack completed and the function served the stub's canned response.
        createStack(stackName, template(fnName, "index.handler"));

        assertStackFailed(stackName);

        // The function must not exist at all — a stub must never stand in for the real package.
        given()
            .when().get("/2015-03-31/functions/" + fnName)
            .then()
            .statusCode(404);
    }

    @Test
    void theFailureNamesTheUnreadableCodeNotTheHandler() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-missing-code-msg-" + suffix;
        String fnName = "missing-code-msg-fn-" + suffix;

        // With a non-default handler the old behaviour failed with
        // "Handler file 'src/api' not found in deployment package", which is misleading: the
        // handler is correct and present in the real package that was never fetched.
        createStack(stackName, template(fnName, "src/api.handler"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("does-not-exist.zip"))
            .body(not(containsString("not found in deployment package")));
    }
}
