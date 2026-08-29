package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.hectorvent.floci.services.cloudformation.CloudFormationLambdaMissingS3CodeIntegrationTest.CFN_AUTH;
import static io.github.hectorvent.floci.services.cloudformation.CloudFormationLambdaMissingS3CodeIntegrationTest.createStack;
import static io.github.hectorvent.floci.services.cloudformation.CloudFormationLambdaMissingS3CodeIntegrationTest.template;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * The opt-in escape hatch for issue #2648: a stack that deliberately leaves its Lambda packages
 * unbuilt can set one property and keep the old stub-handler behaviour instead of breaking on
 * upgrade. Off by default, because a stack that reports CREATE_COMPLETE while serving a placeholder
 * is the more dangerous of the two behaviours.
 *
 * <p>A separate top-level class rather than a nested one, because {@code @TestProfile} applies per
 * test class and Surefire discovers test classes by file name: a nested class carrying the profile
 * compiles and passes when targeted directly, but is silently skipped in a full {@code mvn test}.
 */
@QuarkusTest
@TestProfile(CloudFormationLambdaStubCodeAllowedIntegrationTest.AllowStubProfile.class)
class CloudFormationLambdaStubCodeAllowedIntegrationTest {

    public static final class AllowStubProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.cloudformation.allow-stub-lambda-code", "true");
        }
    }

    @Test
    void unreadableS3CodeFallsBackToTheStubHandler() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-stub-allowed-" + suffix;
        String fnName = "stub-allowed-fn-" + suffix;

        createStack(stackName, template(fnName, "index.handler"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        // The stub stands in for the package that could not be read.
        given()
            .when().get("/2015-03-31/functions/" + fnName)
            .then()
            .statusCode(200);
    }
}
