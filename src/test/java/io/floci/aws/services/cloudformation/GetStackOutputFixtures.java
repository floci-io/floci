package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.PartitionCleanup;
import io.restassured.response.ValidatableResponse;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;

/**
 * What the {@code Fn::GetStackOutput} integration tests, with IAM enforcement off and on, do to
 * stacks and parameters, each request signed as an account's root.
 */
final class GetStackOutputFixtures {

    private GetStackOutputFixtures() {
    }

    /** Creates {@code stackName} in {@code account} and {@code region}, its deletion registered first. */
    static void createStack(PartitionCleanup cleanup, String account, String region, String stackName,
                            String template) {
        String authorization = auth(account, region, "cloudformation");
        cleanup.register(() -> given().header("Authorization", authorization)
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack")
                .formParam("StackName", stackName)
                .when().post("/"));
        given().header("Authorization", authorization)
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", stackName)
                .formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200);
    }

    /** SSM's GetParameter for {@code name}, as the root of {@code account}. */
    static ValidatableResponse parameterValue(String account, String region, String name) {
        return given().header("Authorization", auth(account, region, "ssm"))
                .header("X-Amz-Target", "AmazonSSM.GetParameter")
                .contentType("application/x-amz-json-1.1")
                .body("{\"Name\": \"" + name + "\"}")
                .when().post("/").then();
    }
}
