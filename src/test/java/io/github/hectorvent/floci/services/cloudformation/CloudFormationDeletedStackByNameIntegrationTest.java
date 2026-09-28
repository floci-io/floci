package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * What a client polling a deleted stack by name gets back, per operation. The two wordings are
 * AWS's and clients match on them: the CDK's stack lookup treats DescribeStacks'
 * {@code Stack with id X does not exist} as "gone", and its event poller treats DescribeStackEvents'
 * {@code Stack [X] does not exist} the same way; either wording on the wrong operation escapes
 * as an error, which is how {@code cdk destroy --all} used to stop after the first stack (#4235).
 */
@QuarkusTest
class CloudFormationDeletedStackByNameIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";

    @Test
    void describeStackEventsOnADeletedStackUsesTheBracketedWording() {
        String stackName = "deleted-by-name-" + Long.toString(System.nanoTime(), 36);
        cfn("CreateStack", stackName, "{\"Resources\":{\"P\":{\"Type\":\"AWS::SSM::Parameter\","
                + "\"Properties\":{\"Type\":\"String\",\"Value\":\"v\"}}}}").then().statusCode(200);
        cfn("DeleteStack", stackName, null).then().statusCode(200);
        awaitGone(stackName);

        cfn("DescribeStackEvents", stackName, null).then().statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("<Message>Stack [" + stackName + "] does not exist</Message>"));
        cfn("DescribeStacks", stackName, null).then().statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("<Message>Stack with id " + stackName + " does not exist</Message>"));
    }

    @Test
    void describeStackEventsOnAStackThatNeverExistedUsesTheSameWording() {
        cfn("DescribeStackEvents", "never-created", null).then().statusCode(400)
                .body(containsString("<Message>Stack [never-created] does not exist</Message>"));
    }

    private static Response cfn(String action, String stackName, String template) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", stackName);
        if (template != null) {
            request = request.formParam("TemplateBody", template);
        }
        return request.when().post("/");
    }

    /** Deletion runs on an executor; wait until the name no longer resolves. */
    private static void awaitGone(String stackName) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (cfn("DescribeStacks", stackName, null).statusCode() == 400) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("stack " + stackName + " still resolves by name after its delete");
    }
}
