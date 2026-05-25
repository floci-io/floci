package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class SamTransformIntegrationTest {

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
    void samFunction_withInlineCode_createsLambdaAndRole() {
        String stackName = "sam-hello-stack";
        stacksToDelete.add(stackName);

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              HelloFunction:
                Type: AWS::Serverless::Function
                Properties:
                  FunctionName: sam-hello-func
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
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
        .when()
            .get("/2015-03-31/functions/sam-hello-func")
        .then()
            .statusCode(200)
            .body("Configuration.FunctionName", equalTo("sam-hello-func"))
            .body("Configuration.Handler", equalTo("index.handler"))
            .body("Configuration.Runtime", equalTo("nodejs22.x"));

        String resourcesXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(resourcesXml, containsString("<ResourceType>AWS::Lambda::Function</ResourceType>"));
        assertThat(resourcesXml, containsString("<ResourceType>AWS::IAM::Role</ResourceType>"));
        assertThat(resourcesXml, not(containsString("AWS::Serverless::Function")));
    }

    @Test
    void samFunction_withExplicitRole_skipsRoleGeneration() {
        String stackName = "sam-explicit-role-stack";
        stacksToDelete.add(stackName);

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              MyFunc:
                Type: AWS::Serverless::Function
                Properties:
                  FunctionName: sam-explicit-role-func
                  Handler: index.handler
                  Runtime: nodejs20.x
                  InlineCode: "exports.handler = async () => ({});"
                  Role: arn:aws:iam::000000000000:role/my-existing-role
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
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
        .when()
            .get("/2015-03-31/functions/sam-explicit-role-func")
        .then()
            .statusCode(200)
            .body("Configuration.FunctionName", equalTo("sam-explicit-role-func"))
            .body("Configuration.Role", equalTo("arn:aws:iam::000000000000:role/my-existing-role"));

        // Verify no IAM role was created (only Lambda, no Role resource)
        String resourcesXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(resourcesXml, containsString("<ResourceType>AWS::Lambda::Function</ResourceType>"));
        assertThat(resourcesXml, not(containsString("<ResourceType>AWS::IAM::Role</ResourceType>")));
    }

    @Test
    void samFunction_withEnvironmentAndTimeout() {
        String stackName = "sam-configured-stack";
        stacksToDelete.add(stackName);

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              ConfiguredFunc:
                Type: AWS::Serverless::Function
                Properties:
                  FunctionName: sam-configured-func
                  Handler: app.handler
                  Runtime: python3.12
                  InlineCode: "def handler(event, context): return {'statusCode': 200}"
                  Timeout: 30
                  MemorySize: 256
                  Environment:
                    Variables:
                      TABLE_NAME: my-table
                      STAGE: local
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
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
        .when()
            .get("/2015-03-31/functions/sam-configured-func")
        .then()
            .statusCode(200)
            .body("Configuration.FunctionName", equalTo("sam-configured-func"))
            .body("Configuration.Handler", equalTo("app.handler"))
            .body("Configuration.Runtime", equalTo("python3.12"))
            .body("Configuration.Timeout", equalTo(30))
            .body("Configuration.MemorySize", equalTo(256))
            .body("Configuration.Environment.Variables.TABLE_NAME", equalTo("my-table"))
            .body("Configuration.Environment.Variables.STAGE", equalTo("local"));
    }

    @Test
    void samSimpleTable_createsDynamoDbTable() {
        String stackName = "sam-table-stack";
        stacksToDelete.add(stackName);

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              MyTable:
                Type: AWS::Serverless::SimpleTable
                Properties:
                  TableName: sam-simple-table
                  PrimaryKey:
                    Name: userId
                    Type: String
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
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
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeTable")
            .contentType("application/x-amz-json-1.0")
            .body("""
                {"TableName": "sam-simple-table"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Table.TableName", equalTo("sam-simple-table"))
            .body("Table.KeySchema[0].AttributeName", equalTo("userId"))
            .body("Table.KeySchema[0].KeyType", equalTo("HASH"));

        String resourcesXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(resourcesXml, containsString("<ResourceType>AWS::DynamoDB::Table</ResourceType>"));
        assertThat(resourcesXml, not(containsString("AWS::Serverless::SimpleTable")));
    }

    @Test
    void samApi_createsApiGatewayResources() {
        String stackName = "sam-api-stack";
        stacksToDelete.add(stackName);

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              MyApi:
                Type: AWS::Serverless::Api
                Properties:
                  Name: sam-test-api
                  StageName: dev
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
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
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        String resourcesXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(resourcesXml, containsString("<ResourceType>AWS::ApiGateway::RestApi</ResourceType>"));
        assertThat(resourcesXml, not(containsString("AWS::Serverless::Api")));
    }

    @Test
    void samFunction_withCodeUri_s3Reference() {
        String stackName = "sam-s3code-stack";
        stacksToDelete.add(stackName);

        given()
        .when()
            .put("/sam-code-bucket")
        .then()
            .statusCode(200);

        byte[] zipBytes = buildHandlerZip();
        given()
            .contentType("application/zip")
            .body(zipBytes)
        .when()
            .put("/sam-code-bucket/app.zip")
        .then()
            .statusCode(200);

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              S3Func:
                Type: AWS::Serverless::Function
                Properties:
                  FunctionName: sam-s3code-func
                  Handler: index.handler
                  Runtime: nodejs20.x
                  CodeUri: s3://sam-code-bucket/app.zip
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
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
        .when()
            .get("/2015-03-31/functions/sam-s3code-func")
        .then()
            .statusCode(200)
            .body("Configuration.FunctionName", equalTo("sam-s3code-func"));
    }

    @Test
    void samMixedTemplate_withStandardAndSamResources() {
        String stackName = "sam-mixed-stack";
        stacksToDelete.add(stackName);

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              MyQueue:
                Type: AWS::SQS::Queue
                Properties:
                  QueueName: sam-mixed-queue
              ProcessorFunc:
                Type: AWS::Serverless::Function
                Properties:
                  FunctionName: sam-mixed-func
                  Handler: index.handler
                  Runtime: nodejs20.x
                  InlineCode: "exports.handler = async (e) => ({});"
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
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", "sam-mixed-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("sam-mixed-queue"));

        given()
        .when()
            .get("/2015-03-31/functions/sam-mixed-func")
        .then()
            .statusCode(200)
            .body("Configuration.FunctionName", equalTo("sam-mixed-func"));
    }

    @Test
    void templateWithoutTransform_isNotAffected() {
        String stackName = "no-transform-stack";
        stacksToDelete.add(stackName);

        String template = """
            {
              "Resources": {
                "MyQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "no-transform-queue"
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
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", "no-transform-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("no-transform-queue"));
    }

    @Test
    void samFunction_viaChangeSet_createsLambda() {
        String stackName = "sam-changeset-stack";
        stacksToDelete.add(stackName);

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              CsFunc:
                Type: AWS::Serverless::Function
                Properties:
                  FunctionName: sam-changeset-func
                  Handler: index.handler
                  Runtime: nodejs20.x
                  InlineCode: "exports.handler = async () => ({});"
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "sam-cs-1")
            .formParam("ChangeSetType", "CREATE")
            .formParam("TemplateBody", template)
            .formParam("Capabilities.member.1", "CAPABILITY_IAM")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Id>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ExecuteChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "sam-cs-1")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
        .when()
            .get("/2015-03-31/functions/sam-changeset-func")
        .then()
            .statusCode(200)
            .body("Configuration.FunctionName", equalTo("sam-changeset-func"));
    }

    private static byte[] buildHandlerZip() {
        try {
            var baos = new java.io.ByteArrayOutputStream();
            try (var zos = new java.util.zip.ZipOutputStream(baos)) {
                zos.putNextEntry(new java.util.zip.ZipEntry("index.js"));
                zos.write("exports.handler=async(e)=>({statusCode:200})".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
