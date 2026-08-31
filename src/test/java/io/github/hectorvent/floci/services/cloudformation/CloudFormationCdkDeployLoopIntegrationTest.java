package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * End-to-end checks for the CloudFormation responses CDK's deploy loop depends on:
 * DescribeChangeSet must report a real Add/Modify/Remove diff (an empty Changes list makes CDK
 * skip ExecuteChangeSet on updates), Fn::GetAtt [Param, Value] on an SSM parameter must resolve
 * to the stored value (CDK reads its bootstrap version that way), and DescribeStacks must echo
 * the stack's parameters (CDK's parameter merge treats a missing list as an empty set).
 */
@QuarkusTest
class CloudFormationCdkDeployLoopIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260808/us-east-1/cloudformation/aws4_request";

    @Test
    void describeChangeSet_createType_reportsAllResourcesAsAdd() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cs-diff-create-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Bucket": {
                      "Type": "AWS::S3::Bucket",
                      "Properties": {"BucketName": "cs-diff-create-bucket-%s"}
                    },
                    "Queue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {"QueueName": "cs-diff-create-queue-%s"}
                    }
                  }
                }
                """.formatted(suffix, suffix);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "create-changeset")
            .formParam("ChangeSetType", "CREATE")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Id>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "create-changeset")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<Changes/>")))
            .body(containsString("<ResourceChange><Action>Add</Action>"
                    + "<LogicalResourceId>Bucket</LogicalResourceId>"
                    + "<ResourceType>AWS::S3::Bucket</ResourceType>"
                    + "<Scope/><Details/></ResourceChange>"))
            .body(containsString("<ResourceChange><Action>Add</Action>"
                    + "<LogicalResourceId>Queue</LogicalResourceId>"
                    + "<ResourceType>AWS::SQS::Queue</ResourceType>"
                    + "<Scope/><Details/></ResourceChange>"));
    }

    @Test
    void describeChangeSet_updateType_reportsAddModifyRemove() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cs-diff-update-stack-" + suffix;

        String initialTemplate = """
                {
                  "Resources": {
                    "KeepQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {"QueueName": "cs-diff-keep-%s"}
                    },
                    "ModQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {"QueueName": "cs-diff-mod-%s", "DelaySeconds": 0}
                    },
                    "OldQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {"QueueName": "cs-diff-old-%s"}
                    }
                  }
                }
                """.formatted(suffix, suffix, suffix);

        String updatedTemplate = """
                {
                  "Resources": {
                    "KeepQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {"QueueName": "cs-diff-keep-%s"}
                    },
                    "ModQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {"QueueName": "cs-diff-mod-%s", "DelaySeconds": 5}
                    },
                    "NewQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {"QueueName": "cs-diff-new-%s"}
                    }
                  }
                }
                """.formatted(suffix, suffix, suffix);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", initialTemplate)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "update-changeset")
            .formParam("ChangeSetType", "UPDATE")
            .formParam("TemplateBody", updatedTemplate)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Id>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "update-changeset")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ResourceChange><Action>Add</Action>"
                    + "<LogicalResourceId>NewQueue</LogicalResourceId>"
                    + "<ResourceType>AWS::SQS::Queue</ResourceType>"
                    + "<Scope/><Details/></ResourceChange>"))
            .body(containsString("<Action>Modify</Action>"
                    + "<LogicalResourceId>ModQueue</LogicalResourceId>"
                    + "<PhysicalResourceId>"))
            .body(containsString("<Replacement>False</Replacement>"))
            .body(containsString("<Action>Remove</Action>"
                    + "<LogicalResourceId>OldQueue</LogicalResourceId>"
                    + "<PhysicalResourceId>"))
            .body(not(containsString("<LogicalResourceId>KeepQueue</LogicalResourceId>")));
    }

    @Test
    void getAttOnSsmParameter_resolvesValueInOutputs() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "ssm-getatt-stack-" + suffix;

        String template = """
                {
                  "Resources": {
                    "CdkBootstrapVersion": {
                      "Type": "AWS::SSM::Parameter",
                      "Properties": {
                        "Name": "/ssm-getatt-test/%s/version",
                        "Type": "String",
                        "Value": "21"
                      }
                    }
                  },
                  "Outputs": {
                    "BootstrapVersion": {"Value": {"Fn::GetAtt": ["CdkBootstrapVersion", "Value"]}}
                  }
                }
                """.formatted(suffix);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

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
            .body(containsString("<OutputKey>BootstrapVersion</OutputKey><OutputValue>21</OutputValue>"))
            .body(not(containsString("CdkBootstrapVersion.Value")));
    }

    @Test
    void describeStacks_includesMergedStackParameters() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "params-echo-stack-" + suffix;

        String template = """
                {
                  "Parameters": {
                    "Env": {"Type": "String"},
                    "Tier": {"Type": "String", "Default": "standard"}
                  },
                  "Resources": {
                    "Queue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {"QueueName": "params-echo-queue-%s"}
                    }
                  }
                }
                """.formatted(suffix);

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
            .formParam("Parameters.member.1.ParameterKey", "Env")
            .formParam("Parameters.member.1.ParameterValue", "dev")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

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
            .body(containsString("<ParameterKey>Env</ParameterKey><ParameterValue>dev</ParameterValue>"))
            .body(containsString("<ParameterKey>Tier</ParameterKey><ParameterValue>standard</ParameterValue>"));
    }
}
