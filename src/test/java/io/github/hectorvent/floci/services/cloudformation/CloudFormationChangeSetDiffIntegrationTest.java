package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Covers three ways {@code computeChangeSetChanges} previously misreported a change set's diff:
 * ignoring parameter-only updates, comparing a SAM stack's raw template against its expanded
 * deployed template, and hardcoding {@code Replacement=False} even when a Type change forces a
 * replacement.
 */
@QuarkusTest
class CloudFormationChangeSetDiffIntegrationTest {

    private static final String SSM_CONTENT_TYPE = "application/x-amz-json-1.1";

    private final List<String> stacksToDelete = new ArrayList<>();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void deleteStacks() {
        for (String stackName : stacksToDelete) {
            given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack")
                .formParam("StackName", stackName)
            .when()
                .post("/");
        }
        stacksToDelete.clear();
    }

    @Test
    void parameterOnlyUpdate_isReportedAsAChange() {
        String stackName = "cs-diff-param-only-stack";
        stacksToDelete.add(stackName);

        String template = """
            {
              "Parameters": {
                "Suffix": {"Type": "String"}
              },
              "Resources": {
                "Q": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": {"Fn::Sub": "cs-diff-${Suffix}"}
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
            .formParam("Parameters.member.1.ParameterKey", "Suffix")
            .formParam("Parameters.member.1.ParameterValue", "one")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Same template, only the parameter value changes.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "param-only-cs")
            .formParam("ChangeSetType", "UPDATE")
            .formParam("TemplateBody", template)
            .formParam("Parameters.member.1.ParameterKey", "Suffix")
            .formParam("Parameters.member.1.ParameterValue", "two")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "param-only-cs")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<LogicalResourceId>Q</LogicalResourceId>"))
            .body(containsString("<Action>Modify</Action>"));
    }

    @Test
    void usePreviousValueParameter_isNotReportedAsAChange() {
        String stackName = "cs-diff-use-previous-value-stack";
        stacksToDelete.add(stackName);

        String template = """
            {
              "Parameters": {
                "Suffix": {"Type": "String"}
              },
              "Resources": {
                "Q": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": {"Fn::Sub": "cs-diff-upv-${Suffix}"}
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
            .formParam("Parameters.member.1.ParameterKey", "Suffix")
            .formParam("Parameters.member.1.ParameterValue", "one")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Same template, and the parameter says UsePreviousValue instead of resubmitting its value.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "use-previous-value-cs")
            .formParam("ChangeSetType", "UPDATE")
            .formParam("TemplateBody", template)
            .formParam("Parameters.member.1.ParameterKey", "Suffix")
            .formParam("Parameters.member.1.UsePreviousValue", "true")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "use-previous-value-cs")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<Action>Modify</Action>")));
    }

    @Test
    void omittedParameterFallingBackToDefault_isReportedAsAChange() {
        String stackName = "cs-diff-omitted-param-default-stack";
        stacksToDelete.add(stackName);

        String template = """
            {
              "Parameters": {
                "Suffix": {"Type": "String", "Default": "fallback"}
              },
              "Resources": {
                "Q": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": {"Fn::Sub": "cs-diff-omit-${Suffix}"}
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
            .formParam("Parameters.member.1.ParameterKey", "Suffix")
            .formParam("Parameters.member.1.ParameterValue", "override")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Same template, but the update omits Suffix entirely - real CloudFormation falls back to
        // the template Default ("fallback"), which differs from the deployed value ("override").
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "omitted-param-cs")
            .formParam("ChangeSetType", "UPDATE")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "omitted-param-cs")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<LogicalResourceId>Q</LogicalResourceId>"))
            .body(containsString("<Action>Modify</Action>"));
    }

    @Test
    void omittedParameterWithNoDefault_isReportedAsAChange() {
        String stackName = "cs-diff-omitted-param-no-default-stack";
        stacksToDelete.add(stackName);

        String template = """
            {
              "Parameters": {
                "Suffix": {"Type": "String"}
              },
              "Resources": {
                "Q": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": {"Fn::Sub": "cs-diff-omit-nodefault-${Suffix}"}
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
            .formParam("Parameters.member.1.ParameterKey", "Suffix")
            .formParam("Parameters.member.1.ParameterValue", "override")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Same template, but the update omits Suffix entirely and the template has no Default -
        // resolveDefaultParameters drops the parameter, and ExecuteChangeSet would apply the
        // resulting (missing) value. The preview must flag the resource as changed too.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "omitted-param-no-default-cs")
            .formParam("ChangeSetType", "UPDATE")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "omitted-param-no-default-cs")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<LogicalResourceId>Q</LogicalResourceId>"))
            .body(containsString("<Action>Modify</Action>"));
    }

    @Test
    void missingSsmParameterInPreview_doesNotFailTheDescribe() {
        String stackName = "cs-diff-ssm-missing-stack";
        stacksToDelete.add(stackName);
        String deployedSsmParamName = "/cfn/test/cs-diff-ssm-missing-deployed";

        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "%s",
                    "Value": "one",
                    "Type": "String",
                    "Overwrite": true
                }
                """.formatted(deployedSsmParamName))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String template = """
            {
              "Parameters": {
                "QueueSuffix": {"Type": "AWS::SSM::Parameter::Value<String>"}
              },
              "Resources": {
                "Q": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": {"Fn::Sub": "cs-diff-ssm-missing-${QueueSuffix}"}
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
            .formParam("Parameters.member.1.ParameterKey", "QueueSuffix")
            .formParam("Parameters.member.1.ParameterValue", deployedSsmParamName)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // The update points QueueSuffix at an SSM parameter that was never put. ExecuteChangeSet
        // would fail resolving it, but DescribeChangeSet is only a preview - it must not 400 just
        // because the current value can't be resolved.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "ssm-missing-cs")
            .formParam("ChangeSetType", "UPDATE")
            .formParam("TemplateBody", template)
            .formParam("Parameters.member.1.ParameterKey", "QueueSuffix")
            .formParam("Parameters.member.1.ParameterValue", "/cfn/test/cs-diff-ssm-missing-never-put")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "ssm-missing-cs")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void samStackNoOpUpdate_doesNotReportSpuriousChanges() {
        String stackName = "cs-diff-sam-noop-stack";
        stacksToDelete.add(stackName);

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              HelloFunction:
                Type: AWS::Serverless::Function
                Properties:
                  FunctionName: cs-diff-sam-func
                  Handler: index.handler
                  Runtime: nodejs22.x
                  InlineCode: |
                    exports.handler = async () => ({ statusCode: 200, body: 'ok' });
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
            .formParam("Capabilities.member.1", "CAPABILITY_IAM")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Byte-identical re-submission of the same SAM source: the deployed template is the
        // *expanded* form, so diffing the raw SAM source against it must not report the
        // SAM-generated IAM role as removed or the function as modified.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "sam-noop-cs")
            .formParam("ChangeSetType", "UPDATE")
            .formParam("TemplateBody", template)
            .formParam("Capabilities.member.1", "CAPABILITY_IAM")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "sam-noop-cs")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<Action>Remove</Action>")))
            .body(not(containsString("<Action>Modify</Action>")));
    }

    @Test
    void resourceTypeChange_reportsReplacementTrue() {
        String stackName = "cs-diff-replacement-stack";
        stacksToDelete.add(stackName);

        String initialTemplate = """
            {
              "Resources": {
                "R": {
                  "Type": "AWS::SNS::Topic",
                  "Properties": {"TopicName": "cs-diff-repl-topic"}
                }
              }
            }
            """;
        // Same logical id, different resource Type: AWS always forces a replacement here.
        String replacedTemplate = """
            {
              "Resources": {
                "R": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {"QueueName": "cs-diff-repl-queue"}
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", initialTemplate)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "replacement-cs")
            .formParam("ChangeSetType", "UPDATE")
            .formParam("TemplateBody", replacedTemplate)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "replacement-cs")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Replacement>True</Replacement>"));
    }

    @Test
    void ssmParameterValueDrift_isReportedAsAChange() {
        String stackName = "cs-diff-ssm-drift-stack";
        stacksToDelete.add(stackName);
        String ssmParamName = "/cfn/test/cs-diff-ssm-drift-suffix";

        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "%s",
                    "Value": "one",
                    "Type": "String",
                    "Overwrite": true
                }
                """.formatted(ssmParamName))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String template = """
            {
              "Parameters": {
                "QueueSuffix": {"Type": "AWS::SSM::Parameter::Value<String>"}
              },
              "Resources": {
                "Q": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": {"Fn::Sub": "cs-diff-ssm-${QueueSuffix}"}
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
            .formParam("Parameters.member.1.ParameterKey", "QueueSuffix")
            .formParam("Parameters.member.1.ParameterValue", ssmParamName)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // The SSM parameter's value changes in Parameter Store; the referencing template parameter
        // (the SSM parameter *name*) is resubmitted unchanged. Real CloudFormation re-resolves the
        // SSM value at CreateChangeSet time, so ExecuteChangeSet would apply "two" to the queue —
        // the preview must report that Modify, not compare the unchanged raw parameter name.
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {
                    "Name": "%s",
                    "Value": "two",
                    "Type": "String",
                    "Overwrite": true
                }
                """.formatted(ssmParamName))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "ssm-drift-cs")
            .formParam("ChangeSetType", "UPDATE")
            .formParam("TemplateBody", template)
            .formParam("Parameters.member.1.ParameterKey", "QueueSuffix")
            .formParam("Parameters.member.1.ParameterValue", ssmParamName)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "ssm-drift-cs")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<LogicalResourceId>Q</LogicalResourceId>"))
            .body(containsString("<Action>Modify</Action>"));
    }

    @Test
    void parameterChangeFlippingConditionToTrue_isReportedAsAnAdd() {
        String stackName = "cs-diff-condition-add-stack";
        stacksToDelete.add(stackName);

        String template = """
            {
              "Parameters": {
                "Env": {"Type": "String"}
              },
              "Conditions": {
                "IsProd": {"Fn::Equals": [{"Ref": "Env"}, "prod"]}
              },
              "Resources": {
                "Q": {
                  "Type": "AWS::SQS::Queue",
                  "Condition": "IsProd",
                  "Properties": {"QueueName": "cs-diff-condition-add-queue"}
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
            .formParam("Parameters.member.1.ParameterKey", "Env")
            .formParam("Parameters.member.1.ParameterValue", "dev")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Q's definition text is unchanged, but Env flips IsProd from false to true - Q was never
        // created, and ExecuteChangeSet would create it now.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "condition-add-cs")
            .formParam("ChangeSetType", "UPDATE")
            .formParam("TemplateBody", template)
            .formParam("Parameters.member.1.ParameterKey", "Env")
            .formParam("Parameters.member.1.ParameterValue", "prod")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "condition-add-cs")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<LogicalResourceId>Q</LogicalResourceId>"))
            .body(containsString("<Action>Add</Action>"));
    }

    @Test
    void parameterChangeFlippingConditionToFalse_isReportedAsARemove() {
        String stackName = "cs-diff-condition-remove-stack";
        stacksToDelete.add(stackName);

        String template = """
            {
              "Parameters": {
                "Env": {"Type": "String"}
              },
              "Conditions": {
                "IsProd": {"Fn::Equals": [{"Ref": "Env"}, "prod"]}
              },
              "Resources": {
                "Q": {
                  "Type": "AWS::SQS::Queue",
                  "Condition": "IsProd",
                  "Properties": {"QueueName": "cs-diff-condition-remove-queue"}
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
            .formParam("Parameters.member.1.ParameterKey", "Env")
            .formParam("Parameters.member.1.ParameterValue", "prod")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Q's definition text is unchanged, but Env flips IsProd from true to false - Q was created,
        // and ExecuteChangeSet would delete it now.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "condition-remove-cs")
            .formParam("ChangeSetType", "UPDATE")
            .formParam("TemplateBody", template)
            .formParam("Parameters.member.1.ParameterKey", "Env")
            .formParam("Parameters.member.1.ParameterValue", "dev")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "condition-remove-cs")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<LogicalResourceId>Q</LogicalResourceId>"))
            .body(containsString("<Action>Remove</Action>"));
    }
}
