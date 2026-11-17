package io.floci.aws.services.cloudformation;

import io.floci.aws.core.common.XmlParser;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Provisions {@code AWS::CodeDeploy::Application} through a stack and asserts the application
 * really exists in CodeDeploy (issue #8626: the stack reported CREATE_COMPLETE with nothing behind
 * it), that {@code Ref} is its name, that an update retags the same application, that
 * changing ComputePlatform replaces the unnamed one, and that deleting
 * the stack removes it.
 */
@QuarkusTest
class CodeDeployApplicationCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/cloudformation/aws4_request";
    private static final String CODEDEPLOY_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/codedeploy/aws4_request";
    private static final String STACK = "codedeploy-app-cfn-it";
    private static final String NAMED = "codedeploy-app-cfn-it-named";

    private static final String TEMPLATE = """
        {
          "Parameters": {
            "TagValue": {"Type": "String"},
            "Platform": {"Type": "String", "Default": "Server"}
          },
          "Resources": {
            "Named": {
              "Type": "AWS::CodeDeploy::Application",
              "Properties": {
                "ApplicationName": "%s",
                "ComputePlatform": "Lambda",
                "Tags": [{"Key": "stage", "Value": {"Ref": "TagValue"}}]
              }
            },
            "Unnamed": {
              "Type": "AWS::CodeDeploy::Application",
              "Properties": {"ComputePlatform": {"Ref": "Platform"}}
            }
          },
          "Outputs": {
            "NamedRef": {"Value": {"Ref": "Named"}},
            "UnnamedRef": {"Value": {"Ref": "Unnamed"}}
          }
        }
        """.formatted(NAMED);

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createUpdateAndDeleteApplications() {
        cloudFormation("CreateStack", Map.of("TagValue", "v1"));
        String created = describeStacks("CREATE_COMPLETE");
        String unnamed = outputValue(created, "UnnamedRef");

        assertEquals(NAMED, outputValue(created, "NamedRef"));
        getApplication(NAMED).statusCode(200)
            .body("application.applicationName", equalTo(NAMED))
            .body("application.computePlatform", equalTo("Lambda"));
        listTags(NAMED).statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("stage"))
            .body("Tags[0].Value", equalTo("v1"));
        getApplication(unnamed).statusCode(200)
            .body("application.applicationName", startsWith(STACK + "-Unnamed-"))
            .body("application.computePlatform", equalTo("Server"));

        cloudFormation("UpdateStack", Map.of("TagValue", "v2"));
        String updated = describeStacks("UPDATE_COMPLETE");

        assertEquals(unnamed, outputValue(updated, "UnnamedRef"));
        listTags(NAMED).statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Value", equalTo("v2"));

        // ComputePlatform is create-only: changing it replaces the unnamed application under a new
        // name, and the committed update deletes the old one.
        cloudFormation("UpdateStack", Map.of("TagValue", "v2", "Platform", "ECS"));
        String replaced = describeStacks("UPDATE_COMPLETE");
        String replacement = outputValue(replaced, "UnnamedRef");

        assertNotEquals(unnamed, replacement);
        getApplication(replacement).statusCode(200)
            .body("application.applicationName", startsWith(STACK + "-Unnamed-"))
            .body("application.computePlatform", equalTo("ECS"));
        getApplication(unnamed).statusCode(400).body("__type", equalTo("ApplicationDoesNotExistException"));

        cloudFormation("DeleteStack", Map.of());
        CfnStackWaits.awaitStackDeleted(STACK);

        getApplication(NAMED).statusCode(400).body("__type", equalTo("ApplicationDoesNotExistException"));
        getApplication(replacement).statusCode(400).body("__type", equalTo("ApplicationDoesNotExistException"));
    }

    private static void cloudFormation(String action, Map<String, String> parameters) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", STACK);
        if (!"DeleteStack".equals(action)) {
            request.formParam("TemplateBody", TEMPLATE);
        }
        int index = 1;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            request.formParam("Parameters.member." + index + ".ParameterKey", parameter.getKey());
            request.formParam("Parameters.member." + index + ".ParameterValue", parameter.getValue());
            index++;
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String describeStacks(String expectedStatus) {
        CfnStackWaits.awaitStatus(STACK, expectedStatus, CFN_AUTH);
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", STACK)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private static ValidatableResponse getApplication(String name) {
        return codeDeploy("GetApplication", "{\"applicationName\":\"" + name + "\"}");
    }

    private static ValidatableResponse listTags(String name) {
        return codeDeploy("ListTagsForResource",
                "{\"ResourceArn\":\"arn:aws:codedeploy:us-east-1:000000000000:application:" + name + "\"}");
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
