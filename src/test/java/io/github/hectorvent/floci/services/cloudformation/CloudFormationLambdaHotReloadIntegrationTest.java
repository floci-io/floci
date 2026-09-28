package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * A stack whose Lambda code is {@code {S3Bucket: hot-reload, S3Key: /host/path}} must reach
 * CREATE_COMPLETE with the function bound to that host path, exactly as CreateFunction with the
 * same Code does. The provisioner used to probe S3 for the pair and fail the stack because no
 * such object exists. Control plane only: the bind mount is only consulted at invoke time, so
 * the test is Docker-free. The test profile enables hot-reload.
 */
@QuarkusTest
class CloudFormationLambdaHotReloadIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";

    @Test
    void hotReloadCodeReachesLambdaAndCompletesTheStack() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-hot-reload-" + suffix;
        String fnName = "hot-reload-fn-" + suffix;
        String hostPath = "/tmp/floci-cfn-hot-reload-" + suffix;

        createStack(stackName, template(fnName, hostPath));

        describeStacks(stackName)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
            .when().get("/2015-03-31/functions/" + fnName)
            .then()
            .statusCode(200)
            .body("Configuration.FunctionName", equalTo(fnName))
            .body("Configuration.Handler", equalTo("index.handler"));

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
    void aRelativeHostPathFailsTheStackWithLambdasOwnMessage() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-hot-reload-rel-" + suffix;
        String fnName = "hot-reload-rel-fn-" + suffix;

        createStack(stackName, template(fnName, "relative/dist"));

        describeStacks(stackName)
            .body(not(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>")))
            .body(containsString("Hot-reload S3Key must be an absolute path"));

        given()
            .when().get("/2015-03-31/functions/" + fnName)
            .then()
            .statusCode(404);
    }

    private static String template(String fnName, String hostPath) {
        return """
                {
                  "Resources": {
                    "Fn": {
                      "Type": "AWS::Lambda::Function",
                      "Properties": {
                        "FunctionName": "%s",
                        "Runtime": "nodejs20.x",
                        "Handler": "index.handler",
                        "Role": "arn:aws:iam::000000000000:role/r",
                        "Code": {"S3Bucket": "hot-reload", "S3Key": "%s"}
                      }
                    }
                  }
                }
                """.formatted(fnName, hostPath);
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

    private static ValidatableResponse describeStacks(String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
