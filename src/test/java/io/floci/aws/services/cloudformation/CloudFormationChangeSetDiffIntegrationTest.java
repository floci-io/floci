package io.floci.aws.services.cloudformation;

import io.floci.aws.core.common.XmlParser;
import io.floci.aws.services.ses.SesReceiptRuleService;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.floci.aws.services.cloudformation.SsmParameterTestSupport.putParameter;
import static io.floci.aws.testing.CfnRequests.cfn;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Covers three ways {@code computeChangeSetChanges} previously misreported a change set's diff:
 * ignoring parameter-only updates, comparing a SAM stack's raw template against its expanded
 * deployed template, and hardcoding {@code Replacement=False} even when a Type change forces a
 * replacement.
 */
@QuarkusTest
class CloudFormationChangeSetDiffIntegrationTest {

    @Inject
    SesReceiptRuleService ses;

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

        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
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

        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
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

        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
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
    void omittedParameterWithNoDefault_isRefused() {
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

        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
        // Same template, but the update omits Suffix entirely and the template has no Default. An
        // update does not carry the deployed value over, so CloudFormation refuses the change set
        // rather than preview, or apply, the parameter without a value.
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
            .statusCode(400)
            .body(containsString("<Code>ValidationError</Code>"))
            .body(containsString("<Message>Parameters: [Suffix] must have values</Message>"));
    }

    @Test
    void missingSsmParameterInPreview_doesNotFailTheDescribe() {
        String stackName = "cs-diff-ssm-missing-stack";
        stacksToDelete.add(stackName);
        String deployedSsmParamName = "/cfn/test/cs-diff-ssm-missing-deployed";

        putParameter(deployedSsmParamName, "one");

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

        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
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

        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
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

        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
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

    /**
     * Registry schema aws-cognito-userpooluser.json (CloudformationSchema.zip, us-east-1) lists
     * /properties/UserPoolId in createOnlyProperties; the resource page
     * aws-resource-cognito-userpooluser says "Update requires: Replacement" for UserPoolId.
     */
    @Test
    void createOnlyPropertyChange_reportsReplacementTrue() {
        String stackName = "cs-diff-create-only-stack";
        stacksToDelete.add(stackName);

        String initialTemplate = userPoolUserTemplate("PoolA");
        String changedTemplate = userPoolUserTemplate("PoolB");

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", initialTemplate)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "create-only-cs")
            .formParam("ChangeSetType", "UPDATE")
            .formParam("TemplateBody", changedTemplate)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "create-only-cs")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<LogicalResourceId>U</LogicalResourceId>"))
            .body(containsString("<Replacement>True</Replacement>"));
    }

    private static String userPoolUserTemplate(String poolLogicalId) {
        return """
            {
              "Resources": {
                "PoolA": {"Type": "AWS::Cognito::UserPool", "Properties": {"UserPoolName": "cs-diff-pool-a"}},
                "PoolB": {"Type": "AWS::Cognito::UserPool", "Properties": {"UserPoolName": "cs-diff-pool-b"}},
                "U": {
                  "Type": "AWS::Cognito::UserPoolUser",
                  "Properties": {"UserPoolId": {"Ref": "%s"}, "Username": "cs-diff-user"}
                }
              }
            }
            """.formatted(poolLogicalId);
    }

    @Test
    void ssmParameterValueDrift_isReportedAsAChange() {
        String stackName = "cs-diff-ssm-drift-stack";
        stacksToDelete.add(stackName);
        String ssmParamName = "/cfn/test/cs-diff-ssm-drift-suffix";

        putParameter(ssmParamName, "one");

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

        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
        // The SSM parameter's value changes in Parameter Store; the referencing template parameter
        // (the SSM parameter *name*) is resubmitted unchanged. Real CloudFormation re-resolves the
        // SSM value at CreateChangeSet time, so ExecuteChangeSet would apply "two" to the queue —
        // the preview must report that Modify, not compare the unchanged raw parameter name.
        putParameter(ssmParamName, "two");

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

        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
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

        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
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

    @Test
    void inPlaceAttributeChangePullsInDependentResourceInChangeSet() {
        String stackName = "cs-diff-attr-change-stack";
        stacksToDelete.add(stackName);

        String template1 = """
            {
              "Resources": {
                "Lt": {
                  "Type": "AWS::EC2::LaunchTemplate",
                  "Properties": {
                    "LaunchTemplateName": "cs-diff-lt",
                    "LaunchTemplateData": {
                      "ImageId": "ami-12345678",
                      "InstanceType": "t3.micro"
                    }
                  }
                },
                "Asg": {
                  "Type": "AWS::AutoScaling::AutoScalingGroup",
                  "Properties": {
                    "AutoScalingGroupName": "cs-diff-asg",
                    "LaunchTemplate": {
                      "LaunchTemplateId": {"Ref": "Lt"},
                      "Version": {"Fn::GetAtt": ["Lt", "LatestVersionNumber"]}
                    },
                    "MinSize": 0,
                    "MaxSize": 0,
                    "DesiredCapacity": 0,
                    "AvailabilityZones": ["us-east-1a"]
                  }
                },
                "DefaultAsg": {
                  "Type": "AWS::AutoScaling::AutoScalingGroup",
                  "Properties": {
                    "AutoScalingGroupName": "cs-diff-default-asg",
                    "LaunchTemplate": {
                      "LaunchTemplateId": {"Ref": "Lt"},
                      "Version": {"Fn::GetAtt": ["Lt", "DefaultVersionNumber"]}
                    },
                    "MinSize": 0,
                    "MaxSize": 0,
                    "DesiredCapacity": 0,
                    "AvailabilityZones": ["us-east-1a"]
                  }
                },
                "UnchangedQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "cs-diff-unchanged-q"
                  }
                }
              }
            }
            """;

        String template2 = """
            {
              "Resources": {
                "Lt": {
                  "Type": "AWS::EC2::LaunchTemplate",
                  "Properties": {
                    "LaunchTemplateName": "cs-diff-lt",
                    "LaunchTemplateData": {
                      "ImageId": "ami-87654321",
                      "InstanceType": "t3.small"
                    }
                  }
                },
                "Asg": {
                  "Type": "AWS::AutoScaling::AutoScalingGroup",
                  "Properties": {
                    "AutoScalingGroupName": "cs-diff-asg",
                    "LaunchTemplate": {
                      "LaunchTemplateId": {"Ref": "Lt"},
                      "Version": {"Fn::GetAtt": ["Lt", "LatestVersionNumber"]}
                    },
                    "MinSize": 0,
                    "MaxSize": 0,
                    "DesiredCapacity": 0,
                    "AvailabilityZones": ["us-east-1a"]
                  }
                },
                "DefaultAsg": {
                  "Type": "AWS::AutoScaling::AutoScalingGroup",
                  "Properties": {
                    "AutoScalingGroupName": "cs-diff-default-asg",
                    "LaunchTemplate": {
                      "LaunchTemplateId": {"Ref": "Lt"},
                      "Version": {"Fn::GetAtt": ["Lt", "DefaultVersionNumber"]}
                    },
                    "MinSize": 0,
                    "MaxSize": 0,
                    "DesiredCapacity": 0,
                    "AvailabilityZones": ["us-east-1a"]
                  }
                },
                "UnchangedQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "cs-diff-unchanged-q"
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template1)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "attr-change-cs")
            .formParam("ChangeSetType", "UPDATE")
            .formParam("TemplateBody", template2)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String desc = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "attr-change-cs")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().body().asString();

        List<Map<String, String>> changes = XmlParser.extractGroups(desc, "ResourceChange");
        Map<String, String> ltChange = changes.stream()
                .filter(m -> "Lt".equals(m.get("LogicalResourceId")))
                .findFirst()
                .orElseThrow();
        assertEquals("Modify", ltChange.get("Action"));
        assertEquals("False", ltChange.get("Replacement"));

        Map<String, String> asgChange = changes.stream()
                .filter(m -> "Asg".equals(m.get("LogicalResourceId")))
                .findFirst()
                .orElseThrow();
        assertEquals("Modify", asgChange.get("Action"));
        assertEquals("False", asgChange.get("Replacement"));

        boolean unchangedDefaultDependentIncluded = changes.stream()
                .anyMatch(m -> "DefaultAsg".equals(m.get("LogicalResourceId")));
        assertFalse(unchangedDefaultDependentIncluded);

        boolean unchangedQueueIncluded = changes.stream()
                .anyMatch(m -> "UnchangedQueue".equals(m.get("LogicalResourceId")));
        assertFalse(unchangedQueueIncluded);
    }

    /**
     * Registry schema aws-route53-healthcheck.json lists only /properties/HealthCheckConfig/Type,
     * /MeasureLatency and /RequestInterval in createOnlyProperties, so editing the Port inside
     * HealthCheckConfig is an in-place update and editing its Type is a replacement.
     */
    @Test
    void nestedCreateOnlyPointer_replacesOnlyWhenTheNestedValueChanges() {
        String stackName = "cs-diff-nested-hc-stack";
        stacksToDelete.add(stackName);

        createStack(stackName, healthCheckTemplate("HTTP", 80));

        assertEquals("False", previewReplacement(stackName, "hc-port-cs", healthCheckTemplate("HTTP", 8080)).get("Check"));
        assertEquals("True", previewReplacement(stackName, "hc-type-cs", healthCheckTemplate("HTTPS", 80)).get("Check"));
    }

    private static String healthCheckTemplate(String type, int port) {
        return """
            {
              "Resources": {
                "Check": {
                  "Type": "AWS::Route53::HealthCheck",
                  "Properties": {
                    "HealthCheckConfig": {
                      "Type": "%s", "IPAddress": "192.0.2.10", "Port": %d, "ResourcePath": "/x"
                    }
                  }
                }
              }
            }
            """.formatted(type, port);
    }

    /**
     * Registry schema aws-ses-receiptrule.json lists /properties/Rule/Name and /properties/RuleSetName
     * in createOnlyProperties, but not Rule/Enabled: toggling Enabled is in place, renaming is not.
     */
    @Test
    void sesReceiptRule_replacesOnRuleNameButNotOnEnabled() {
        String stackName = "cs-diff-nested-ses-stack";
        String ruleSet = "cs-diff-nested-ses-set";
        stacksToDelete.add(stackName);
        ses.createReceiptRuleSet(ruleSet, "us-east-1");
        try {
            createStack(stackName, receiptRuleTemplate(ruleSet, "rule-a", true));

            assertEquals("False", previewReplacement(stackName, "ses-enabled-cs",
                    receiptRuleTemplate(ruleSet, "rule-a", false)).get("Rule"));
            assertEquals("True", previewReplacement(stackName, "ses-name-cs",
                    receiptRuleTemplate(ruleSet, "rule-b", true)).get("Rule"));
        } finally {
            deleteStackNow(stackName);
            ses.deleteReceiptRuleSet(ruleSet, "us-east-1");
        }
    }

    private static String receiptRuleTemplate(String ruleSet, String name, boolean enabled) {
        return """
            {
              "Resources": {
                "Rule": {
                  "Type": "AWS::SES::ReceiptRule",
                  "Properties": {
                    "RuleSetName": "%s",
                    "Rule": {"Name": "%s", "Enabled": %s, "Recipients": ["example.com"]}
                  }
                }
              }
            }
            """.formatted(ruleSet, name, enabled);
    }

    /**
     * Registry schema aws-batch-computeenvironment.json lists /properties/ComputeResources/SpotIamFleetRole
     * in createOnlyProperties and does not list ComputeResources/InstanceRole. AWS::IAM::Role RoleName is
     * create-only (aws-iam-role.json), so renaming the role replaces it. A compute environment that
     * references the replaced role under SpotIamFleetRole is replaced with it; one that references it only
     * under InstanceRole (same parent, not create-only) is updated in place.
     */
    @Test
    void dependentIsReplacedOnlyWhenTheReferenceSitsUnderANestedCreateOnlyPointer() {
        String stackName = "cs-diff-nested-dep-stack";
        stacksToDelete.add(stackName);

        createStack(stackName, roleAndComputeTemplate("cs-diff-nested-role-a"));

        Map<String, String> replacement = previewReplacement(stackName, "dep-cs",
                roleAndComputeTemplate("cs-diff-nested-role-b"));
        assertEquals("True", replacement.get("Role"));
        assertEquals("True", replacement.get("CreateOnlyRef"));
        assertEquals("False", replacement.get("SiblingRef"));
    }

    private static String roleAndComputeTemplate(String roleName) {
        return """
            {
              "Resources": {
                "Role": {
                  "Type": "AWS::IAM::Role",
                  "Properties": {
                    "RoleName": "%s",
                    "AssumeRolePolicyDocument": {"Version": "2012-10-17", "Statement": [
                      {"Effect": "Allow", "Principal": {"Service": "batch.amazonaws.com"}, "Action": "sts:AssumeRole"}]}
                  }
                },
                "CreateOnlyRef": {
                  "Type": "AWS::Batch::ComputeEnvironment",
                  "Properties": {
                    "ComputeEnvironmentName": "cs-diff-nested-ce-1",
                    "Type": "MANAGED",
                    "ComputeResources": {"Type": "FARGATE", "MaxvCpus": 4, "Subnets": ["subnet-local"],
                      "SecurityGroupIds": ["sg-local"], "SpotIamFleetRole": {"Ref": "Role"}}
                  }
                },
                "SiblingRef": {
                  "Type": "AWS::Batch::ComputeEnvironment",
                  "Properties": {
                    "ComputeEnvironmentName": "cs-diff-nested-ce-2",
                    "Type": "MANAGED",
                    "ComputeResources": {"Type": "FARGATE", "MaxvCpus": 4, "Subnets": ["subnet-local"],
                      "SecurityGroupIds": ["sg-local"], "InstanceRole": {"Ref": "Role"}}
                  }
                }
              }
            }
            """.formatted(roleName);
    }

    private void createStack(String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
    }

    private void deleteStackNow(String stackName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/");
    }

    /** Creates an UPDATE change set and returns the Replacement value per logical id. */
    private Map<String, String> previewReplacement(String stackName, String changeSetName, String template) {
        Map<String, String> result = new HashMap<>();
        for (Map<String, String> change : XmlParser.extractGroups(
                describeChangeSet(stackName, changeSetName, template, null), "ResourceChange")) {
            result.put(change.get("LogicalResourceId"), change.get("Replacement"));
        }
        return result;
    }

    /** Creates an UPDATE change set (with one "Name=value" parameter when given) and returns the DescribeChangeSet body. */
    private String describeChangeSet(String stackName, String changeSetName, String template, String param) {
        RequestSpecification create = cfn("CreateChangeSet", stackName)
            .formParam("ChangeSetName", changeSetName)
            .formParam("ChangeSetType", "UPDATE")
            .formParam("TemplateBody", template);
        if (param != null) {
            create.formParam("Parameters.member.1.ParameterKey", param.split("=")[0])
                  .formParam("Parameters.member.1.ParameterValue", param.split("=")[1]);
        }
        create.when().post("/").then().statusCode(200);

        return cfn("DescribeChangeSet", stackName)
            .formParam("ChangeSetName", changeSetName)
            .when().post("/")
            .then().statusCode(200)
            .extract().body().asString();
    }

    private record DetailsRow(String name, String type, String oldProps, String newProps, String newParam,
            List<String> scope, List<String> details, String replacement, boolean exact,
            String logicalId, String oldExtra, String newExtra) {
        private DetailsRow(String name, String type, String oldProps, String newProps, String newParam,
                List<String> scope, List<String> details, String replacement, boolean exact) {
            this(name, type, oldProps, newProps, newParam, scope, details, replacement, exact, "R", "", "");
        }
    }

    /** Details are "Attribute|Name|RequiresRecreation|ChangeSource|Evaluation|CausingEntity", empty when absent. */
    private static String template(String type, String props, String... extraResources) {
        return "{\"Parameters\":{\"P\":{\"Type\":\"String\",\"Default\":\"v1\"},\"Env\":{\"Type\":\"String\",\"Default\":\"prod\"}},\"Conditions\":{\"C\":{\"Fn::Equals\":[{\"Ref\":\"Env\"},\"prod\"]}},\"Resources\":{\"R\":{\"Type\":\""
                + type + "\",\"Properties\":{" + props + "}}" + String.join("", extraResources) + "}}";
    }

    private static String healthCheck(String type) {
        return "\"HealthCheckConfig\":{\"Type\":\"" + type + "\",\"IPAddress\":\"192.0.2.10\",\"Port\":80}";
    }

    private static String text(XmlParser.XmlElement parent, String child) {
        XmlParser.XmlElement e = parent == null ? null : parent.child(child);
        return e == null ? "" : e.text();
    }

    private static XmlParser.XmlElement resourceChange(String body, String logicalId) {
        for (XmlParser.XmlElement member : XmlParser.parseDocumentElementTree(body)
                .child("DescribeChangeSetResult").child("Changes").children()) {
            XmlParser.XmlElement rc = member.child("ResourceChange");
            if (logicalId.equals(text(rc, "LogicalResourceId"))) {
                return rc;
            }
        }
        return null;
    }

    private static List<String> detailsOf(XmlParser.XmlElement rc) {
        List<String> result = new ArrayList<>();
        for (XmlParser.XmlElement d : rc.child("Details").children()) {
            XmlParser.XmlElement t = d.child("Target");
            result.add(String.join("|", text(t, "Attribute"), text(t, "Name"), text(t, "RequiresRecreation"),
                    text(d, "ChangeSource"), text(d, "Evaluation"), text(d, "CausingEntity")));
        }
        return result.stream().sorted().toList();
    }

    /**
     * Expected values: AWS DescribeChangeSet samples (direct Tags edit, parameter change giving a
     * DirectModification/Dynamic and a ParameterReference/Static detail) and the ResourceChangeDetail
     * reference (Name only for Properties; Replacement is the most intrusive RequiresRecreation).
     */
    @Test
    void modifiedResource_reportsScopeAndDetails() {
        String direct = "|DirectModification|Static|";
        String ifSet = "{\"Fn::If\":[\"C\",5,{\"Ref\":\"AWS::NoValue\"}]}";
        String queueName = "\"QueueName\":\"cs-det-%s-%s\"";
        String topic = """
            ,"Reader":{"Type":"AWS::SNS::Topic","Properties":{"DisplayName":%s}}
            """;
        String readsRef = topic.formatted("{\"Ref\":\"R\"}");
        String readsArn = topic.formatted("{\"Fn::GetAtt\":[\"R\",\"Arn\"]}");
        String role = """
            ,"Role":{"Type":"AWS::IAM::Role","Properties":{"RoleName":"cs-det-role-%s",
              "AssumeRolePolicyDocument":{"Version":"2012-10-17","Statement":[
                {"Effect":"Allow","Principal":{"Service":"batch.amazonaws.com"},"Action":"sts:AssumeRole"}]}}}
            """;
        String compute = """
            "ComputeEnvironmentName":"cs-det-compute","Type":"MANAGED",
            "ComputeResources":{"Type":"FARGATE","MaxvCpus":4,"Subnets":["subnet-local"],
              "SecurityGroupIds":["sg-local"],"SpotIamFleetRole":{"Ref":"Role"}}
            """;
        String launchTemplate = """
            ,"Lt":{"Type":"AWS::EC2::LaunchTemplate","Properties":{"LaunchTemplateName":"cs-det-lt",
              "LaunchTemplateData":{"ImageId":"%s","InstanceType":"t3.micro"}}}
            """;
        String asg = """
            "AutoScalingGroupName":"cs-det-asg","MinSize":0,"MaxSize":0,"DesiredCapacity":0,
            "AvailabilityZones":["us-east-1a"],"LaunchTemplate":{
              "LaunchTemplateId":{"Ref":"Lt"},"Version":{"Fn::GetAtt":["Lt","LatestVersionNumber"]}}
            """;
        String asgSub = asg.replace("{\"Fn::GetAtt\":[\"Lt\",\"LatestVersionNumber\"]}",
            "{\"Fn::Sub\":[\"${Ref}\",{\"Ref\":{\"Fn::GetAtt\":[\"Lt\",\"LatestVersionNumber\"]}}]}")
            .replace("cs-det-asg", "cs-det-asg-sub");
        String nestedUrl = "\"TemplateURL\":\"http://localhost/cs-det-nested-tpl/child.json\"";
        String nestedParams = nestedUrl + ",\"Parameters\":{\"K\":\"%s\"}";
        String app = """
            ,"App":{"Type":"AWS::AppConfig::Application","Properties":{"Name":"cs-det-app"}}
            """;
        String locationUriParamName = "/cs-det/location-uri";
        String profile = """
            "Name":"cs-det-profile","LocationUri":"{{resolve:ssm:%s}}","ApplicationId":%s
            """;
        putParameter(locationUriParamName, "hosted");
        given().when().put("/cs-det-nested-tpl");
        given().contentType("application/json").body("{\"Parameters\":{\"K\":{\"Type\":\"String\",\"Default\":\"d\"}},\"Resources\":{}}")
            .when().put("/cs-det-nested-tpl/child.json").then().statusCode(200);
        List<DetailsRow> rows = List.of(
            new DetailsRow("changed", "AWS::SNS::Topic", "\"DisplayName\":\"a\"", "\"DisplayName\":\"b\"", null,
                List.of("Properties"), List.of("Properties|DisplayName|Never" + direct), "False", true),
            new DetailsRow("added", "AWS::SNS::Topic", "\"TopicName\":\"cs-det-added\"",
                "\"TopicName\":\"cs-det-added\",\"DisplayName\":\"b\"", null,
                List.of("Properties"), List.of("Properties|DisplayName|Never" + direct), "False", true),
            new DetailsRow("dropped", "AWS::SNS::Topic", "\"TopicName\":\"cs-det-dropped\",\"DisplayName\":\"a\"",
                "\"TopicName\":\"cs-det-dropped\"", null,
                List.of("Properties"), List.of("Properties|DisplayName|Never" + direct), "False", true),
            new DetailsRow("create-only", "AWS::SQS::Queue", "\"QueueName\":\"cs-det-q-a\"", "\"QueueName\":\"cs-det-q-b\"",
                null, List.of("Properties"), List.of("Properties|QueueName|Always" + direct), "True", true),
            new DetailsRow("tags", "AWS::SQS::Queue",
                "\"QueueName\":\"cs-det-tags\",\"Tags\":[{\"Key\":\"k\",\"Value\":\"v1\"}]",
                "\"QueueName\":\"cs-det-tags\",\"Tags\":[{\"Key\":\"k\",\"Value\":\"v2\"}]", null,
                List.of("Tags"), List.of("Tags||Never" + direct), "False", true),
            new DetailsRow("parameter", "AWS::SNS::Topic", "\"DisplayName\":{\"Ref\":\"P\"}",
                "\"DisplayName\":{\"Ref\":\"P\"}", "P=v2", List.of("Properties"),
                List.of("Properties|DisplayName|Never|DirectModification|Dynamic|",
                        "Properties|DisplayName|Never|ParameterReference|Static|P"), "False", true),
            new DetailsRow("no-value", "AWS::SNS::Topic", "\"DisplayName\":\"a\"",
                "\"DisplayName\":\"b\",\"KmsMasterKeyId\":{\"Ref\":\"AWS::NoValue\"}", null,
                List.of("Properties"), List.of("Properties|DisplayName|Never" + direct), "False", true),
            new DetailsRow("edited-and-parameter", "AWS::SQS::Queue",
                "\"QueueName\":\"cs-det-both\",\"Tags\":[{\"Key\":\"k\",\"Value\":{\"Ref\":\"P\"}}]",
                "\"QueueName\":\"cs-det-both\",\"Tags\":[{\"Key\":\"k2\",\"Value\":{\"Ref\":\"P\"}}]", "P=v2",
                List.of("Tags"), List.of("Tags||Never" + direct, "Tags||Never|ParameterReference|Static|P"), "False", true),
            new DetailsRow("nested-create-only", "AWS::Route53::HealthCheck", healthCheck("HTTP"), healthCheck("HTTPS"), null,
                List.of("Properties"), List.of("Properties|HealthCheckConfig|Always" + direct), "True", true),
            new DetailsRow("rewritten-create-only-ref", "AWS::AppConfig::ConfigurationProfile",
                profile.formatted(locationUriParamName, "{\"Ref\":\"App\"}"),
                profile.formatted(locationUriParamName, "{\"Fn::Join\":[\"\",[{\"Ref\":\"App\"}]]}"),
                null, List.of("Properties"), List.of("Properties|ApplicationId|Always" + direct),
                "True", true, "R", app, app),
            new DetailsRow("condition", "AWS::SQS::Queue", "\"QueueName\":\"cs-det-cond\",\"DelaySeconds\":" + ifSet,
                "\"QueueName\":\"cs-det-cond\",\"DelaySeconds\":" + ifSet, "Env=dev", List.of("Properties"),
                List.of("Properties|DelaySeconds|Never|DirectModification|Dynamic|"), "False", false),
            new DetailsRow("replaced-ref", "AWS::SQS::Queue", queueName.formatted("ref", "a"),
                queueName.formatted("ref", "b"), null, List.of("Properties"),
                List.of("Properties|DisplayName|Never|ResourceReference|Static|R"), "False", true,
                "Reader", readsRef, readsRef),
            new DetailsRow("replaced-get-att", "AWS::SQS::Queue", queueName.formatted("arn", "a"),
                queueName.formatted("arn", "b"), null, List.of("Properties"),
                List.of("Properties|DisplayName|Never|ResourceAttribute|Static|R.Arn"), "False", true,
                "Reader", readsArn, readsArn),
            new DetailsRow("create-only-ref", "AWS::Batch::ComputeEnvironment", compute, compute, null,
                List.of("Properties"), List.of("Properties|ComputeResources|Always|ResourceReference|Static|Role"),
                "True", true, "R", role.formatted("a"), role.formatted("b")),
            new DetailsRow("in-place-get-att", "AWS::AutoScaling::AutoScalingGroup", asg, asg, null,
                List.of("Properties"), List.of("Properties|LaunchTemplate|Never|ResourceAttribute|Dynamic|Lt.LatestVersionNumber"),
                "False", true, "R", launchTemplate.formatted("ami-12345678"), launchTemplate.formatted("ami-87654321")),
            new DetailsRow("sub-variable-named-ref", "AWS::AutoScaling::AutoScalingGroup", asgSub, asgSub, null,
                List.of("Properties"), List.of("Properties|LaunchTemplate|Never|ResourceAttribute|Dynamic|Lt.LatestVersionNumber"),
                "False", true, "R", launchTemplate.formatted("ami-12345678").replace("cs-det-lt", "cs-det-lt-sub"),
                launchTemplate.formatted("ami-87654321").replace("cs-det-lt", "cs-det-lt-sub")),
            new DetailsRow("name-derived-create-only", "AWS::IAM::Group", "\"GroupName\":\"cs-det-g-a\"",
                "\"GroupName\":\"cs-det-g-b\"", null, List.of("Properties"), List.of("Properties|GroupName|Always" + direct),
                "True", true),
            new DetailsRow("name-derived-parameter", "AWS::IAM::Group", "\"GroupName\":{\"Ref\":\"P\"}",
                "\"GroupName\":{\"Ref\":\"P\"}", "P=v2", List.of("Properties"),
                List.of("Properties|GroupName|Always|DirectModification|Dynamic|",
                        "Properties|GroupName|Always|ParameterReference|Static|P"), "True", true),
            new DetailsRow("nested-automatic", "AWS::CloudFormation::Stack", nestedUrl, nestedUrl, null,
                List.of("Properties"), List.of("Properties||Never|Automatic|Dynamic|"),
                "False", true, "R", topic.formatted("\"a\""), topic.formatted("\"b\"")),
            new DetailsRow("nested-parameters", "AWS::CloudFormation::Stack", nestedParams.formatted("a"),
                nestedParams.formatted("b"), null, List.of("Properties"), List.of("Properties|Parameters|Never" + direct),
                "False", true),
            // This pins Floci's merge of both details. The AWS response for this combination is unconfirmed.
            new DetailsRow("edited-and-ref", "AWS::SQS::Queue", queueName.formatted("both", "a"),
                queueName.formatted("both", "b"), null, List.of("Properties"),
                List.of("Properties|DisplayName|Never" + direct, "Properties|DisplayName|Never|ResourceReference|Static|R"),
                "False", true, "Reader", topic.formatted("\"before\""), readsRef));

        List<String> failures = new ArrayList<>();
        for (DetailsRow row : rows) {
            String stackName = "cs-details-" + row.name();
            stacksToDelete.add(stackName);
            createStack(stackName, template(row.type(), row.oldProps(), row.oldExtra()));
            XmlParser.XmlElement rc = resourceChange(describeChangeSet(stackName, row.name() + "-cs",
                    template(row.type(), row.newProps(), row.newExtra()), row.newParam()), row.logicalId());
            if (!"Modify".equals(text(rc, "Action"))) {
                failures.add(row.name() + ": expected Modify for " + row.logicalId() + ", action=" + text(rc, "Action"));
                continue;
            }
            List<String> scope = rc.child("Scope").children().stream().map(XmlParser.XmlElement::text).sorted().toList();
            List<String> details = detailsOf(rc);
            boolean recreates = row.details().stream().anyMatch(d -> d.contains("|Always|"));
            boolean staticDirect = details.stream().anyMatch(d -> d.endsWith("|DirectModification|Static|"));
            boolean detailsOk = row.exact() ? details.equals(row.details().stream().sorted().toList())
                    : details.containsAll(row.details()) && !staticDirect;
            if (!scope.equals(row.scope()) || !detailsOk
                    || !row.replacement().equals(text(rc, "Replacement")) || recreates != "True".equals(row.replacement())) {
                failures.add(row.name() + ": scope=" + scope + " details=" + details + " replacement=" + text(rc, "Replacement"));
            }
        }
        assertEquals(List.of(), failures);
    }

    /** ResourceChange.Scope and Details are documented "for the Modify action" only. */
    @Test
    void addedAndRemovedResources_haveNoScopeAndNoDetails() {
        String stackName = "cs-details-add-remove";
        stacksToDelete.add(stackName);
        String gone = ",\"Gone\":{\"Type\":\"AWS::SNS::Topic\",\"Properties\":{\"TopicName\":\"cs-det-gone\"}}";
        String fresh = ",\"Fresh\":{\"Type\":\"AWS::SNS::Topic\",\"Properties\":{\"TopicName\":\"cs-det-fresh\"}}";
        createStack(stackName, template("AWS::SNS::Topic", "\"TopicName\":\"cs-det-keep\"", gone));

        String body = describeChangeSet(stackName, "add-remove-cs",
                template("AWS::SNS::Topic", "\"TopicName\":\"cs-det-keep\"", fresh), null);
        XmlParser.XmlElement removed = resourceChange(body, "Gone");
        XmlParser.XmlElement added = resourceChange(body, "Fresh");
        assertEquals("Remove", text(removed, "Action"));
        assertEquals("Add", text(added, "Action"));
        for (XmlParser.XmlElement rc : List.of(removed, added)) {
            assertEquals(0, rc.child("Scope") == null ? 0 : rc.child("Scope").children().size());
            assertEquals(0, rc.child("Details") == null ? 0 : rc.child("Details").children().size());
        }
    }
}
