package io.floci.aws.services.cloudformation;

import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.LambdaAlias;
import io.floci.aws.testing.CfnRequests;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * End-to-end check that CloudFormation provisions the Lambda addressing
 * resources (issue #1996): Permission lands in the function's resource
 * policy, Version publishes, and Alias resolves — all via LambdaService.
 * Uses a zip-less inline function definition, so the test is Docker-free
 * (no invoke, only control plane).
 */
@QuarkusTest
class CloudFormationLambdaAddressingIntegrationTest {

    @Inject
    LambdaService lambdaService;

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";

    @Test
    void permissionVersionAliasAllProvision() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-lambda-addr-" + suffix;
        String fnName = "addr-fn-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Role": {
                      "Type": "AWS::IAM::Role",
                      "Properties": {
                        "AssumeRolePolicyDocument": {"Version": "2012-10-17", "Statement": [
                          {"Effect": "Allow", "Principal": {"Service": "lambda.amazonaws.com"}, "Action": "sts:AssumeRole"}]}
                      }
                    },
                    "Fn": {
                      "Type": "AWS::Lambda::Function",
                      "Properties": {
                        "FunctionName": "%s",
                        "Runtime": "python3.12",
                        "Handler": "index.handler",
                        "Role": {"Fn::GetAtt": ["Role", "Arn"]},
                        "Code": {"ZipFile": "def handler(e, c): return 'ok'"}
                      }
                    },
                    "Perm": {
                      "Type": "AWS::Lambda::Permission",
                      "Properties": {
                        "FunctionName": {"Ref": "Fn"},
                        "Action": "lambda:InvokeFunction",
                        "Principal": "s3.amazonaws.com",
                        "SourceArn": "arn:aws:s3:::some-bucket"
                      }
                    },
                    "Ver": {
                      "Type": "AWS::Lambda::Version",
                      "Properties": {"FunctionName": {"Ref": "Fn"}}
                    },
                    "Live": {
                      "Type": "AWS::Lambda::Alias",
                      "Properties": {
                        "FunctionName": {"Ref": "Fn"},
                        "Name": "live",
                        "FunctionVersion": {"Fn::GetAtt": ["Ver", "Version"]}
                      }
                    }
                  },
                  "Outputs": {
                    "AliasArn": {"Value": {"Ref": "Live"}},
                    "VersionArn": {"Value": {"Ref": "Ver"}}
                  }
                }
                """.formatted(fnName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
            .formParam("Capabilities.member.1", "CAPABILITY_IAM")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .body(containsString(":function:" + fnName + ":1"))
            .body(containsString(":function:" + fnName + ":live"));

        // The permission is in the function's resource policy.
        given()
            .when().get("/2015-03-31/functions/" + fnName + "/policy")
            .then()
            .statusCode(200)
            .body(containsString("s3.amazonaws.com"))
            .body(containsString("arn:aws:s3:::some-bucket"));
    }

    @Test
    void updateKeepsVersionAppliesRoutingAndDeleteCleansUp() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-lambda-addr-upd-" + suffix;
        String fnName = "addr-upd-fn-" + suffix;

        String base = """
                    "Role": {
                      "Type": "AWS::IAM::Role",
                      "Properties": {
                        "AssumeRolePolicyDocument": {"Version": "2012-10-17", "Statement": [
                          {"Effect": "Allow", "Principal": {"Service": "lambda.amazonaws.com"}, "Action": "sts:AssumeRole"}]}
                      }
                    },
                    "Fn": {
                      "Type": "AWS::Lambda::Function",
                      "Properties": {
                        "FunctionName": "%s",
                        "Runtime": "python3.12",
                        "Handler": "index.handler",
                        "Role": {"Fn::GetAtt": ["Role", "Arn"]},
                        "Code": {"ZipFile": "def handler(e, c): return 'ok'"}
                      }
                    },
                    "Perm": {
                      "Type": "AWS::Lambda::Permission",
                      "Properties": {
                        "FunctionName": {"Ref": "Fn"},
                        "Action": "lambda:InvokeFunction",
                        "Principal": "s3.amazonaws.com",
                        "SourceArn": "arn:aws:s3:::some-bucket"
                      }
                    },
                    "Ver": {
                      "Type": "AWS::Lambda::Version",
                      "Properties": {"FunctionName": {"Ref": "Fn"}}
                    }""".formatted(fnName);

        String template = """
                {
                  "Resources": {
                %s,
                    "Live": {
                      "Type": "AWS::Lambda::Alias",
                      "Properties": {
                        "FunctionName": {"Ref": "Fn"},
                        "Name": "live",
                        "FunctionVersion": {"Fn::GetAtt": ["Ver", "Version"]}
                      }
                    }
                  },
                  "Outputs": {"VersionArn": {"Value": {"Ref": "Ver"}}}
                }
                """.formatted(base);

        // Second revision: a new version plus weighted routing on the alias.
        String updatedTemplate = """
                {
                  "Resources": {
                %s,
                    "Ver2": {
                      "Type": "AWS::Lambda::Version",
                      "Properties": {"FunctionName": {"Ref": "Fn"}}
                    },
                    "Live": {
                      "Type": "AWS::Lambda::Alias",
                      "Properties": {
                        "FunctionName": {"Ref": "Fn"},
                        "Name": "live",
                        "FunctionVersion": {"Fn::GetAtt": ["Ver", "Version"]},
                        "RoutingConfig": {"AdditionalVersionWeights": [
                          {"FunctionVersion": {"Fn::GetAtt": ["Ver2", "Version"]}, "FunctionWeight": 0.5}]}
                      }
                    }
                  },
                  "Outputs": {"VersionArn": {"Value": {"Ref": "Ver"}}}
                }
                """.formatted(base);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
            .formParam("Capabilities.member.1", "CAPABILITY_IAM")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", updatedTemplate)
            .formParam("Capabilities.member.1", "CAPABILITY_IAM")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE");
        // "Ver" must still be version 1: the update must not republish an unchanged version.
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"))
            .body(containsString(":function:" + fnName + ":1"));

        // The alias carries the routing weights, and the permission was replaced, not duplicated.
        given()
            .when().get("/2015-03-31/functions/" + fnName + "/aliases/live")
            .then()
            .statusCode(200)
            .body(containsString("AdditionalVersionWeights"))
            .body(containsString("0.5"));

        String policy = given()
            .when().get("/2015-03-31/functions/" + fnName + "/policy")
            .then()
            .statusCode(200)
            .body(containsString("s3.amazonaws.com"))
            .extract().asString();
        assertEquals(policy.indexOf("\"Perm\""), policy.lastIndexOf("\"Perm\""),
                "permission statement must not be duplicated by the update: " + policy);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // A permission, version or alias whose delete fails leaves the stack in DELETE_FAILED
        // and it never drops out of DescribeStacks — so waiting for the stack to disappear
        // exercises all three delete paths.
        CfnStackWaits.awaitStackDeleted(stackName);
    }

    @Test
    void updateStackThatDropsTheAliasDescriptionClearsItAndAReAddSetsItAgain() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-alias-desc-drop-" + suffix;
        String fnName = "alias-desc-drop-fn-" + suffix;
        deploy("CreateStack", stackName, aliasTemplate(fnName, ", \"Description\": \"orig\"", "0.1"),
                "CREATE_COMPLETE");
        assertEquals("orig", lambdaService.getAlias("us-east-1", fnName, "live").getDescription());

        deploy("UpdateStack", stackName, aliasTemplate(fnName, "", "0.1"), "UPDATE_COMPLETE");
        assertNull(lambdaService.getAlias("us-east-1", fnName, "live").getDescription());

        deploy("UpdateStack", stackName, aliasTemplate(fnName, ", \"Description\": \"back\"", "0.1"),
                "UPDATE_COMPLETE");
        assertEquals("back", lambdaService.getAlias("us-east-1", fnName, "live").getDescription());
        CfnStackWaits.deleteStack(stackName, CFN_AUTH);
    }

    @Test
    void updateStackWhoseAliasDescriptionBecomesNoValueClearsIt() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-alias-desc-novalue-" + suffix;
        String fnName = "alias-desc-novalue-fn-" + suffix;
        deploy("CreateStack", stackName, aliasTemplate(fnName, ", \"Description\": \"orig\"", "0.1"),
                "CREATE_COMPLETE");

        deploy("UpdateStack", stackName, aliasTemplate(fnName,
                ", \"Description\": {\"Fn::If\": [\"Off\", {\"Ref\": \"AWS::NoValue\"}, \"orig\"]}", "0.1"),
                "UPDATE_COMPLETE");

        assertNull(lambdaService.getAlias("us-east-1", fnName, "live").getDescription());
        CfnStackWaits.deleteStack(stackName, CFN_AUTH);
    }

    @Test
    void changedAliasDescriptionAppliesAndAnEmptyOneIsStoredAsGivenUntilALaterDropClearsIt() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-alias-desc-empty-" + suffix;
        String fnName = "alias-desc-empty-fn-" + suffix;
        deploy("CreateStack", stackName, aliasTemplate(fnName, ", \"Description\": \"orig\"", "0.1"),
                "CREATE_COMPLETE");

        deploy("UpdateStack", stackName, aliasTemplate(fnName, ", \"Description\": \"changed\"", "0.1"),
                "UPDATE_COMPLETE");
        assertEquals("changed", lambdaService.getAlias("us-east-1", fnName, "live").getDescription());

        deploy("UpdateStack", stackName, aliasTemplate(fnName, ", \"Description\": \"\"", "0.1"),
                "UPDATE_COMPLETE");
        assertEquals("", lambdaService.getAlias("us-east-1", fnName, "live").getDescription());

        deploy("UpdateStack", stackName, aliasTemplate(fnName, "", "0.1"), "UPDATE_COMPLETE");
        assertNull(lambdaService.getAlias("us-east-1", fnName, "live").getDescription());
        CfnStackWaits.deleteStack(stackName, CFN_AUTH);
    }

    @Test
    void aliasDescriptionSetOutsideTheStackSurvivesAnUpdateThatNeverDeclaredIt() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-alias-desc-outside-" + suffix;
        String fnName = "alias-desc-outside-fn-" + suffix;
        deploy("CreateStack", stackName, aliasTemplate(fnName, "", "0.1"), "CREATE_COMPLETE");
        lambdaService.updateAlias("us-east-1", fnName, "live", null, "outside", null);

        deploy("UpdateStack", stackName, aliasTemplate(fnName, "", "0.2"), "UPDATE_COMPLETE");

        LambdaAlias stored = lambdaService.getAlias("us-east-1", fnName, "live");
        assertEquals("outside", stored.getDescription());
        assertEquals(Map.of("2", 0.2), stored.getRoutingConfig());
        CfnStackWaits.deleteStack(stackName, CFN_AUTH);
    }

    @Test
    void droppingTheAliasDescriptionLeavesTheVersionAndTheRoutingConfigAlone() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-alias-desc-others-" + suffix;
        String fnName = "alias-desc-others-fn-" + suffix;
        deploy("CreateStack", stackName, aliasTemplate(fnName, ", \"Description\": \"orig\"", "0.1"),
                "CREATE_COMPLETE");
        String version = lambdaService.getAlias("us-east-1", fnName, "live").getFunctionVersion();

        deploy("UpdateStack", stackName, aliasTemplate(fnName, "", "0.1"), "UPDATE_COMPLETE");

        LambdaAlias stored = lambdaService.getAlias("us-east-1", fnName, "live");
        assertEquals(version, stored.getFunctionVersion());
        assertEquals(Map.of("2", 0.1), stored.getRoutingConfig());
        CfnStackWaits.deleteStack(stackName, CFN_AUTH);
    }

    private static void deploy(String action, String stackName, String template, String expectedStatus) {
        CfnRequests.deploy(action, stackName, template, CFN_AUTH, expectedStatus, "CAPABILITY_IAM");
    }

    /** A function, a published version and the alias {@code live}; {@code aliasExtras} is appended to the alias properties. */
    private static String aliasTemplate(String fnName, String aliasExtras, String routingWeight) {
        return """
                {
                  "Conditions": {"Off": {"Fn::Equals": ["a", "a"]}},
                  "Resources": {
                    "Role": {
                      "Type": "AWS::IAM::Role",
                      "Properties": {
                        "AssumeRolePolicyDocument": {"Version": "2012-10-17", "Statement": [
                          {"Effect": "Allow", "Principal": {"Service": "lambda.amazonaws.com"}, "Action": "sts:AssumeRole"}]}
                      }
                    },
                    "Fn": {
                      "Type": "AWS::Lambda::Function",
                      "Properties": {
                        "FunctionName": "%s",
                        "Runtime": "python3.12",
                        "Handler": "index.handler",
                        "Role": {"Fn::GetAtt": ["Role", "Arn"]},
                        "Code": {"ZipFile": "def handler(e, c): return 'ok'"}
                      }
                    },
                    "Ver": {
                      "Type": "AWS::Lambda::Version",
                      "Properties": {"FunctionName": {"Ref": "Fn"}}
                    },
                    "Live": {
                      "Type": "AWS::Lambda::Alias",
                      "Properties": {
                        "FunctionName": {"Ref": "Fn"},
                        "Name": "live",
                        "FunctionVersion": {"Fn::GetAtt": ["Ver", "Version"]},
                        "RoutingConfig": {"AdditionalVersionWeights": [
                          {"FunctionVersion": "2", "FunctionWeight": %s}]}%s
                      }
                    }
                  }
                }
                """.formatted(fnName, routingWeight, aliasExtras);
    }
}
