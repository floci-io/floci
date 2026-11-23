package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.CfnTestStack;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.services.cloudformation.model.Stack;

import static io.floci.aws.testing.CfnStackWaits.output;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Provisions {@code AWS::CodeDeploy::Application} through a stack and reads the applications back
 * through CodeDeploy (issue #8626: the stack reported CREATE_COMPLETE with nothing behind it).
 * {@code Ref} is the name, an update retags the same application, a later resource's failure puts
 * the tags back, changing ComputePlatform replaces the unnamed application, and deleting the stack
 * removes both.
 */
@QuarkusTest
class CodeDeployApplicationCfnIntegrationTest {

    private static final String CODEDEPLOY_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/codedeploy/aws4_request";

    @RegisterExtension
    final CfnTestStack stack = new CfnTestStack("codedeploy-app-cfn-it");

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createUpdateRollBackReplaceAndDeleteApplications() {
        String named = stack.name() + "-named";

        Stack created = stack.create(template(named, "v1", "Server", false));
        String unnamed = output(created, "UnnamedRef");
        assertEquals(named, output(created, "NamedRef"));
        getApplication(named).statusCode(200)
            .body("application.applicationName", equalTo(named))
            .body("application.computePlatform", equalTo("Lambda"));
        assertStageTag(named, "v1");
        getApplication(unnamed).statusCode(200)
            .body("application.applicationName", startsWith(stack.name() + "-Unnamed-"))
            .body("application.computePlatform", equalTo("Server"));

        Stack retagged = stack.update(template(named, "v2", "Server", false), "UPDATE_COMPLETE");
        assertEquals(unnamed, output(retagged, "UnnamedRef"));
        assertStageTag(named, "v2");

        stack.update(template(named, "v3", "Server", true), "UPDATE_ROLLBACK_COMPLETE");
        assertStageTag(named, "v2");

        // ComputePlatform is create-only: changing it replaces the unnamed application under a new
        // name, and the committed update deletes the old one.
        Stack replaced = stack.update(template(named, "v2", "ECS", false), "UPDATE_COMPLETE");
        String replacement = output(replaced, "UnnamedRef");
        assertNotEquals(unnamed, replacement);
        getApplication(replacement).statusCode(200)
            .body("application.applicationName", startsWith(stack.name() + "-Unnamed-"))
            .body("application.computePlatform", equalTo("ECS"));
        getApplication(unnamed).statusCode(400).body("__type", equalTo("ApplicationDoesNotExistException"));

        CfnStackWaits.deleteStack(stack.name());

        getApplication(named).statusCode(400).body("__type", equalTo("ApplicationDoesNotExistException"));
        getApplication(replacement).statusCode(400).body("__type", equalTo("ApplicationDoesNotExistException"));
    }

    private static String template(String named, String stage, String platform, boolean laterFails) {
        return """
            {
              "Resources": {
                "Named": {
                  "Type": "AWS::CodeDeploy::Application",
                  "Properties": {
                    "ApplicationName": "%s",
                    "ComputePlatform": "Lambda",
                    "Tags": [{"Key": "stage", "Value": "%s"}]
                  }
                },
                "Unnamed": {
                  "Type": "AWS::CodeDeploy::Application",
                  "Properties": {"ComputePlatform": "%s"}
                }%s
              },
              "Outputs": {
                "NamedRef": {"Value": {"Ref": "Named"}},
                "UnnamedRef": {"Value": {"Ref": "Unnamed"}}
              }
            }
            """.formatted(named, stage, platform, laterFails ? CfnStackWaits.failingResourceAfter("Named") : "");
    }

    private static void assertStageTag(String name, String value) {
        codeDeploy("ListTagsForResource",
                "{\"ResourceArn\":\"arn:aws:codedeploy:us-east-1:000000000000:application:" + name + "\"}")
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("stage"))
            .body("Tags[0].Value", equalTo(value));
    }

    private static ValidatableResponse getApplication(String name) {
        return codeDeploy("GetApplication", "{\"applicationName\":\"" + name + "\"}");
    }

    private static ValidatableResponse codeDeploy(String action, String body) {
        return given()
            .header("Authorization", CODEDEPLOY_AUTH)
            .header("X-Amz-Target", "CodeDeploy_20141006." + action)
            .contentType("application/x-amz-json-1.1")
            .body(body)
        .when().post("/").then();
    }
}
