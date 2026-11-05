package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.hectorvent.floci.testing.CfnRequests.cfn;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code AWS::Signer::SigningProfile} and {@code AWS::Lambda::CodeSigningConfig} through CloudFormation,
 * and a function that attaches the config, drops it and attaches it again, with the identifiers AWS returns.
 */
@QuarkusTest
class CloudFormationLambdaCodeSigningIntegrationTest {

    private static final String MISSING_CSC_ARN =
            "arn:aws:lambda:us-east-1:000000000000:code-signing-config:csc-00000000000000000";

    @Inject
    LambdaService lambdaService;

    @Test
    void signingProfileAndCodeSigningConfigAreProvisionedAttachedDetachedAndReattached() {
        String stackName = "cfn-lambda-csc-" + Long.toString(System.nanoTime(), 36);
        String signed = template("\"CodeSigningConfigArn\": {\"Ref\": \"Csc\"}");

        cfn("CreateStack", stackName).formParam("TemplateBody", signed).when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
        XmlPath stack = describe(stackName);
        String profileArn = output(stack, "ProfileArn");
        String version = output(stack, "ProfileVersion");
        String cscArn = output(stack, "CscArn");
        String function = output(stack, "Name");

        assertEquals(profileArn, output(stack, "ProfileRef"));
        assertThat(output(stack, "ProfileName"), matchesPattern("Profile_[A-Za-z0-9]{12}"));
        assertEquals("arn:aws:signer:us-east-1:000000000000:/signing-profiles/" + output(stack, "ProfileName"),
                profileArn);
        assertThat(version, matchesPattern("[A-Za-z0-9]{10}"));
        assertEquals(profileArn + "/" + version, output(stack, "ProfileVersionArn"));
        assertEquals(cscArn, output(stack, "CscRef"));
        assertEquals(cscArn, "arn:aws:lambda:us-east-1:000000000000:code-signing-config:" + output(stack, "CscId"));
        given()
        .when()
            .get("/2020-04-22/code-signing-configs/" + cscArn)
        .then()
            .statusCode(200)
            .body("CodeSigningConfig.Description", equalTo("signed"))
            .body("CodeSigningConfig.AllowedPublishers.SigningProfileVersionArns",
                    contains(profileArn + "/" + version))
            .body("CodeSigningConfig.CodeSigningPolicies.UntrustedArtifactOnDeployment", equalTo("Enforce"));
        assertCodeSigningConfig(function, cscArn);

        for (String rejected : List.of(MISSING_CSC_ARN, cscArn.replace(":000000000000:", ":111111111111:"))) {
            AwsException error = assertThrows(AwsException.class,
                    () -> lambdaService.attachFunctionCodeSigningConfig("us-east-1", function, rejected));
            assertEquals("CodeSigningConfigNotFoundException", error.getErrorCode());
        }
        assertCodeSigningConfig(function, cscArn);

        cfn("UpdateStack", stackName).formParam("TemplateBody", template("\"Description\": \"unsigned\""))
            .when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE");
        assertEquals(function, output(describe(stackName), "Name"), "dropping the config updates the function in place");
        assertCodeSigningConfig(function, "");

        cfn("UpdateStack", stackName).formParam("TemplateBody", signed).when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE");
        assertEquals(function, output(describe(stackName), "Name"), "adding the config updates the function in place");
        assertCodeSigningConfig(function, cscArn);

        CfnStackWaits.deleteStack(stackName);
        CfnStackWaits.awaitStackDeleted(stackName);
        given()
        .when()
            .get("/2020-04-22/code-signing-configs/" + cscArn)
        .then()
            .statusCode(404);
    }

    private static String template(String functionProperty) {
        return """
                {
                  "Resources": {
                    "Profile": {
                      "Type": "AWS::Signer::SigningProfile",
                      "Properties": {"PlatformId": "AWSLambda-SHA384-ECDSA"}
                    },
                    "Csc": {
                      "Type": "AWS::Lambda::CodeSigningConfig",
                      "Properties": {
                        "Description": "signed",
                        "AllowedPublishers": {
                          "SigningProfileVersionArns": [{"Fn::GetAtt": ["Profile", "ProfileVersionArn"]}]
                        },
                        "CodeSigningPolicies": {"UntrustedArtifactOnDeployment": "Enforce"}
                      }
                    },
                    "Fn": {
                      "Type": "AWS::Lambda::Function",
                      "Properties": {
                        "Runtime": "python3.14",
                        "Handler": "index.handler",
                        "Role": "arn:aws:iam::000000000000:role/lambda-role",
                        "Code": {"ZipFile": "def handler(e, c): return 'ok'"},
                        %s
                      }
                    }
                  },
                  "Outputs": {
                    "ProfileRef": {"Value": {"Ref": "Profile"}},
                    "ProfileArn": {"Value": {"Fn::GetAtt": ["Profile", "Arn"]}},
                    "ProfileName": {"Value": {"Fn::GetAtt": ["Profile", "ProfileName"]}},
                    "ProfileVersion": {"Value": {"Fn::GetAtt": ["Profile", "ProfileVersion"]}},
                    "ProfileVersionArn": {"Value": {"Fn::GetAtt": ["Profile", "ProfileVersionArn"]}},
                    "CscRef": {"Value": {"Ref": "Csc"}},
                    "CscArn": {"Value": {"Fn::GetAtt": ["Csc", "CodeSigningConfigArn"]}},
                    "CscId": {"Value": {"Fn::GetAtt": ["Csc", "CodeSigningConfigId"]}},
                    "Name": {"Value": {"Ref": "Fn"}}
                  }
                }
                """.formatted(functionProperty);
    }

    private static void assertCodeSigningConfig(String function, String expected) {
        given()
        .when()
            .get("/2020-06-30/functions/" + function + "/code-signing-config")
        .then()
            .statusCode(200)
            .body("CodeSigningConfigArn", equalTo(expected));
    }

    private static XmlPath describe(String stackName) {
        return cfn("DescribeStacks", stackName).when().post("/").then().statusCode(200).extract().xmlPath();
    }

    private static String output(XmlPath stack, String key) {
        return stack.getString("DescribeStacksResponse.DescribeStacksResult.Stacks.member"
                + ".Outputs.member.find { it.OutputKey == '" + key + "' }.OutputValue");
    }
}
