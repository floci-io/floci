package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class CloudFormationIntegrationTest {

    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String SSM_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String SM_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static byte[] buildHandlerZip() {
        try {
            var baos = new ByteArrayOutputStream();
            try (var zos = new ZipOutputStream(baos)) {
                zos.putNextEntry(new ZipEntry("index.js"));
                zos.write("exports.handler=async(e)=>({statusCode:200})".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void createStack_withS3AndSqs() {
        String template = """
            {
              "Mappings": {
                "Env": {
                  "us-east-1": {
                    "Name": "test"
                  }
                }
              },
              "Resources": {
                "MyBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": {
                    "BucketName": {
                       "Fn::Sub": ["cf-${env}-bucket", {
                         "env": {
                            "Fn::FindInMap": ["Env", { "Ref" : "AWS::Region" }, "Name"]
                         }
                       }]
                    }
                  }
                },
                "MyQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "cf-test-queue"
                  }
                }
              }
            }
            """;

        // 1. Create Stack
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "test-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        // 2. Verify S3 Bucket exists
        given()
            .header("Host", "cf-test-bucket.localhost")
        .when()
            .get("/")
        .then()
            .statusCode(200);

        // 3. Verify SQS Queue exists
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", "cf-test-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("cf-test-queue"));
        
        // 4. Describe Stacks
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "test-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackName>test-stack</StackName>"))
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));
    }

    @Test
    void createStack_lambdaWithS3Code() {
        byte[] zipBytes = buildHandlerZip();

        // Create S3 bucket
        given()
            .when()
            .put("/cfn-lambda-code-bucket")
        .then()
            .statusCode(200);

        // Upload ZIP to S3
        given()
            .contentType("application/zip")
            .body(zipBytes)
        .when()
            .put("/cfn-lambda-code-bucket/handler.zip")
        .then()
            .statusCode(200);

        String template = """
            {
              "Resources": {
                "MyFunction": {
                  "Type": "AWS::Lambda::Function",
                  "Properties": {
                    "FunctionName": "cfn-s3code-func",
                    "Runtime": "nodejs20.x",
                    "Handler": "index.handler",
                    "Code": {
                      "S3Bucket": "cfn-lambda-code-bucket",
                      "S3Key": "handler.zip"
                    },
                    "Role": "arn:aws:iam::000000000000:role/cfn-test-lambda-role"
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "cfn-s3code-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "cfn-s3code-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        // Verify Lambda function was created
        given()
        .when()
            .get("/2015-03-31/functions/cfn-s3code-func")
        .then()
            .statusCode(200)
            .body("Configuration.FunctionName", equalTo("cfn-s3code-func"));
    }

    @Test
    void createStack_lambdaWithNoCode() {
        String template = """
            {
              "Resources": {
                "MyFunction": {
                  "Type": "AWS::Lambda::Function",
                  "Properties": {
                    "FunctionName": "cfn-nocode-func",
                    "Runtime": "nodejs20.x",
                    "Handler": "index.handler",
                    "Role": "arn:aws:iam::000000000000:role/cfn-test-lambda-role"
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "cfn-nocode-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "cfn-nocode-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
        .when()
            .get("/2015-03-31/functions/cfn-nocode-func")
        .then()
            .statusCode(200)
            .body("Configuration.FunctionName", equalTo("cfn-nocode-func"));
    }

    @Test
    void createStack_lambdaWithEnvironmentVariables() {
        String template = """
            {
              "Resources": {
                "MyFunction": {
                  "Type": "AWS::Lambda::Function",
                  "Properties": {
                    "FunctionName": "cfn-env-func",
                    "Runtime": "nodejs20.x",
                    "Handler": "index.handler",
                    "Role": "arn:aws:iam::000000000000:role/cfn-test-lambda-role",
                    "Environment": {
                      "Variables": {
                        "MY_VAR": "hello",
                        "STAGE": "local"
                      }
                    }
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "cfn-env-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "cfn-env-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
        .when()
            .get("/2015-03-31/functions/cfn-env-func")
        .then()
            .statusCode(200)
            .body("Configuration.FunctionName", equalTo("cfn-env-func"))
            .body("Configuration.Environment.Variables.MY_VAR", equalTo("hello"))
            .body("Configuration.Environment.Variables.STAGE", equalTo("local"));
    }

    @Test
    void createStack_lambdaWithImageUri() {
        String template = """
            {
              "Resources": {
                "MyFunction": {
                  "Type": "AWS::Lambda::Function",
                  "Properties": {
                    "FunctionName": "cfn-image-func",
                    "Handler": "index.handler",
                    "Code": {
                      "ImageUri": "123456789012.dkr.ecr.us-east-1.amazonaws.com/my-repo:latest"
                    },
                    "Role": "arn:aws:iam::000000000000:role/cfn-test-lambda-role"
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "cfn-image-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "cfn-image-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));
    }

    @Test
    void createStack_lambdaWithZipFile() {
        String base64Zip = Base64.getEncoder().encodeToString(buildHandlerZip());

        String template = """
            {
              "Resources": {
                "MyFunction": {
                  "Type": "AWS::Lambda::Function",
                  "Properties": {
                    "FunctionName": "cfn-zipfile-func",
                    "Runtime": "nodejs20.x",
                    "Handler": "index.handler",
                    "Code": {
                      "ZipFile": "%s"
                    },
                    "Role": "arn:aws:iam::000000000000:role/cfn-test-lambda-role"
                  }
                }
              }
            }
            """.formatted(base64Zip);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "cfn-zipfile-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "cfn-zipfile-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
        .when()
            .get("/2015-03-31/functions/cfn-zipfile-func")
        .then()
            .statusCode(200)
            .body("Configuration.FunctionName", equalTo("cfn-zipfile-func"));
    }

    @Test
    void createStack_withDynamoDbGsiAndLsi() {
        String template = """
            {
                "Resources": {
                    "MyTable": {
                        "Type": "AWS::DynamoDB::Table",
                        "Properties": {
                            "TableName": "cf-index-table",
                            "AttributeDefinitions": [
                                {"AttributeName": "pk", "AttributeType": "S"},
                                {"AttributeName": "sk", "AttributeType": "S"},
                                {"AttributeName": "gsiPk", "AttributeType": "S"}
                            ],
                            "KeySchema": [
                                {"AttributeName": "pk", "KeyType": "HASH"},
                                {"AttributeName": "sk", "KeyType": "RANGE"}
                            ],
                            "GlobalSecondaryIndexes": [
                                {
                                    "IndexName": "gsi-1",
                                    "KeySchema": [
                                        {"AttributeName": "gsiPk", "KeyType": "HASH"},
                                        {"AttributeName": "sk", "KeyType": "RANGE"}
                                    ],
                                    "Projection": {"ProjectionType": "ALL"}
                                }
                            ],
                            "LocalSecondaryIndexes": [
                                {
                                    "IndexName": "lsi-1",
                                    "KeySchema": [
                                        {"AttributeName": "pk", "KeyType": "HASH"},
                                        {"AttributeName": "gsiPk", "KeyType": "RANGE"}
                                    ],
                                    "Projection": {"ProjectionType": "KEYS_ONLY"}
                                }
                            ]
                        }
                    }
                }
            }
            """;

        // 1. Create Stack
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "test-dynamo-index-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        // 2. Verify GSI and LSI via DescribeTable
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.DescribeTable")
            .contentType(DYNAMODB_CONTENT_TYPE)
            .body("""
                {"TableName": "cf-index-table"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Table.GlobalSecondaryIndexes.size()", equalTo(1))
            .body("Table.GlobalSecondaryIndexes[0].IndexName", equalTo("gsi-1"))
            .body("Table.LocalSecondaryIndexes.size()", equalTo(1))
            .body("Table.LocalSecondaryIndexes[0].IndexName", equalTo("lsi-1"));
    }

    @Test
    void deleteChangeSet_removesChangeSet() {
        String template = """
            {
              "Resources": {
                "MyBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": {
                    "BucketName": "cs-delete-test-bucket"
                  }
                }
              }
            }
            """;

        // 1. Create a ChangeSet (implicitly creates the stack)
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", "cs-delete-stack")
            .formParam("ChangeSetName", "my-changeset")
            .formParam("ChangeSetType", "CREATE")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Id>"));

        // 2. Verify ChangeSet exists
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", "cs-delete-stack")
            .formParam("ChangeSetName", "my-changeset")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ChangeSetName>my-changeset</ChangeSetName>"));

        // 3. Delete the ChangeSet
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteChangeSet")
            .formParam("StackName", "cs-delete-stack")
            .formParam("ChangeSetName", "my-changeset")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DeleteChangeSetResult/>"));

        // 4. Verify ChangeSet no longer exists
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", "cs-delete-stack")
            .formParam("ChangeSetName", "my-changeset")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("ChangeSetNotFoundException"));

        // 5. Verify ChangeSet is absent from ListChangeSets
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListChangeSets")
            .formParam("StackName", "cs-delete-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("my-changeset")));
    }

    @Test
    void describeStackEvents_byArn() {
        String template = """
            {
              "Resources": {
                "MyBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": {
                    "BucketName": "arn-events-test-bucket"
                  }
                }
              }
            }
            """;

        // 1. Create stack and capture the ARN
        String createResponse = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "arn-events-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"))
            .extract().asString();

        // Extract the ARN from the response
        String stackArn = createResponse.substring(
                createResponse.indexOf("<StackId>") + "<StackId>".length(),
                createResponse.indexOf("</StackId>"));

        // 2. Describe stack events using the ARN
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stackArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackName>arn-events-stack</StackName>"));

        // 3. Describe stacks using the ARN
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackName>arn-events-stack</StackName>"));
    }

    @Test
    void deleteChangeSet_nonExistentChangeSet_returnsError() {
        String template = """
            {
              "Resources": {
                "MyBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": {
                    "BucketName": "cs-error-test-bucket"
                  }
                }
              }
            }
            """;

        // Create a stack via CreateChangeSet so the stack exists
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", "cs-error-stack")
            .formParam("ChangeSetName", "existing-changeset")
            .formParam("ChangeSetType", "CREATE")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Attempt to delete a changeset that does not exist
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteChangeSet")
            .formParam("StackName", "cs-error-stack")
            .formParam("ChangeSetName", "nonexistent-changeset")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("ChangeSetNotFoundException"));
    }

    @Test
    void createStack_autoGeneratedName_crossResourceRef() {
        // DynamoDB table without explicit TableName → auto-generated name
        // SSM Parameter uses !Ref to get the auto-generated table name as its Value
        String template = """
            {
              "Resources": {
                "MyTable": {
                  "Type": "AWS::DynamoDB::Table",
                  "Properties": {
                    "AttributeDefinitions": [
                      {"AttributeName": "pk", "AttributeType": "S"}
                    ],
                    "KeySchema": [
                      {"AttributeName": "pk", "KeyType": "HASH"}
                    ]
                  }
                },
                "TableNameParam": {
                  "Type": "AWS::SSM::Parameter",
                  "Properties": {
                    "Name": "/app/auto-table-name",
                    "Type": "String",
                    "Value": {"Ref": "MyTable"}
                  }
                }
              }
            }
            """;

        // 1. Create Stack
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "auto-name-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        // 2. Verify stack completed and the auto-generated table name follows the pattern
        var describeResponse = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", "auto-name-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ResourceType>AWS::DynamoDB::Table</ResourceType>"))
            .body(containsString("auto-name-stack-MyTable-"))
            .extract().asString();

        // 3. Verify SSM Parameter was created with the auto-generated table name as value
        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {"Name": "/app/auto-table-name", "WithDecryption": true}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameter.Name", equalTo("/app/auto-table-name"))
            .body("Parameter.Value", startsWith("auto-name-stack-MyTable-"));
    }

    @Test
    void createStack_explicitNamesPreserved() {
        // When explicit names are provided, CloudFormation uses them as-is.
        // See: https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-properties-name.html
        String template = """
            {
              "Resources": {
                "Bucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": {
                    "BucketName": "my-explicit-bucket-name"
                  }
                },
                "Queue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "MyExplicitQueueName"
                  }
                },
                "Table": {
                  "Type": "AWS::DynamoDB::Table",
                  "Properties": {
                    "TableName": "MyExplicitTableName",
                    "AttributeDefinitions": [
                      {"AttributeName": "id", "AttributeType": "S"}
                    ],
                    "KeySchema": [
                      {"AttributeName": "id", "KeyType": "HASH"}
                    ]
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "explicit-names-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        // Verify explicit names are used as-is in DescribeStackResources
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", "explicit-names-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("my-explicit-bucket-name"))
            .body(containsString("MyExplicitQueueName"))
            .body(containsString("MyExplicitTableName"))
            // Must NOT contain auto-generated pattern
            .body(not(containsString("explicit-names-stack-Bucket-")))
            .body(not(containsString("explicit-names-stack-Queue-")))
            .body(not(containsString("explicit-names-stack-Table-")));
    }

    @Test
    void createStack_s3AutoName_isLowercase() {
        // S3 bucket names must be lowercase letters, numbers, periods, and hyphens (max 63 chars).
        // See: https://docs.aws.amazon.com/AmazonS3/latest/userguide/bucketnamingrules.html
        String template = """
            {
              "Resources": {
                "MyUpperCaseBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": {}
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "S3LowerCase-Stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // The auto-generated name should be all lowercase: s3lowercase-stack-myuppercasebucket-...
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", "S3LowerCase-Stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("s3lowercase-stack-myuppercasebucket-"))
            // Must not contain uppercase variants
            .body(not(containsString("S3LowerCase-Stack-MyUpperCaseBucket-")));
    }

    @Test
    void createStack_sqsAutoName_preservesCase() {
        // SQS queue names preserve case. AWS example: mystack-myqueue-1VF9BKQH5BJVI
        // See: https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-sqs-queue.html
        String template = """
            {
              "Resources": {
                "MyMixedCaseQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {}
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "CaseSensitive-Stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // The SQS auto-generated name should preserve case: CaseSensitive-Stack-MyMixedCaseQueue-...
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", "CaseSensitive-Stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("CaseSensitive-Stack-MyMixedCaseQueue-"));
    }

    @Test
    void createStack_multipleUnnamedResources_uniqueNames() {
        // Multiple resources of same type without names get unique auto-generated names
        String template = """
            {
              "Resources": {
                "TableA": {
                  "Type": "AWS::DynamoDB::Table",
                  "Properties": {
                    "AttributeDefinitions": [
                      {"AttributeName": "id", "AttributeType": "S"}
                    ],
                    "KeySchema": [
                      {"AttributeName": "id", "KeyType": "HASH"}
                    ]
                  }
                },
                "TableB": {
                  "Type": "AWS::DynamoDB::Table",
                  "Properties": {
                    "AttributeDefinitions": [
                      {"AttributeName": "id", "AttributeType": "S"}
                    ],
                    "KeySchema": [
                      {"AttributeName": "id", "KeyType": "HASH"}
                    ]
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "multi-table-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Both tables should have distinct names derived from their logical IDs
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", "multi-table-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("multi-table-stack-TableA-"))
            .body(containsString("multi-table-stack-TableB-"));
    }

    @Test
    void createStack_ssmAutoName_followsAwsPattern() {
        // AWS SSM Parameter auto-name pattern: {stackName}-{logicalId}-{suffix}
        // See: https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-ssm-parameter.html
        String template = """
            {
              "Resources": {
                "MyParam": {
                  "Type": "AWS::SSM::Parameter",
                  "Properties": {
                    "Type": "String",
                    "Value": "test-value"
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "ssm-auto-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // SSM Parameter physical ID should follow {stackName}-{logicalId}-{suffix} pattern
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", "ssm-auto-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("ssm-auto-stack-MyParam-"));

        // Verify SSM Parameter name via SSM API using DescribeStackResources physical ID
        // We extract the auto-generated name from the stack resource and verify it's accessible
        var ssmResourceXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", "ssm-auto-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        // Extract the auto-generated parameter name from the XML response
        String paramName = ssmResourceXml
            .split("<PhysicalResourceId>")[1]
            .split("</PhysicalResourceId>")[0];

        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("{\"Name\": \"" + paramName + "\", \"WithDecryption\": true}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameter.Value", equalTo("test-value"));
    }

    @Test
    void createStack_getAttOnAutoNamedResource() {
        // Fn::GetAtt should work on auto-named resources (e.g. DynamoDB Arn)
        String template = """
            {
              "Resources": {
                "AutoTable": {
                  "Type": "AWS::DynamoDB::Table",
                  "Properties": {
                    "AttributeDefinitions": [
                      {"AttributeName": "pk", "AttributeType": "S"}
                    ],
                    "KeySchema": [
                      {"AttributeName": "pk", "KeyType": "HASH"}
                    ]
                  }
                },
                "ArnParam": {
                  "Type": "AWS::SSM::Parameter",
                  "Properties": {
                    "Name": "/app/auto-table-arn",
                    "Type": "String",
                    "Value": {"Fn::GetAtt": ["AutoTable", "Arn"]}
                  }
                }
              },
              "Outputs": {
                "TableArn": {
                  "Value": {"Fn::GetAtt": ["AutoTable", "Arn"]}
                },
                "TableName": {
                  "Value": {"Ref": "AutoTable"}
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "getatt-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify Outputs contain the auto-generated name and ARN
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "getatt-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<OutputKey>TableArn</OutputKey>"))
            .body(containsString("<OutputKey>TableName</OutputKey>"))
            .body(containsString("getatt-stack-AutoTable-"));

        // Verify SSM Parameter received the Arn via GetAtt
        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {"Name": "/app/auto-table-arn", "WithDecryption": true}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameter.Value", startsWith("arn:aws:dynamodb:"));
    }

    @Test
    void createStack_snsAutoName_refReturnsArn() {
        // SNS Ref returns TopicArn. AWS example: arn:aws:sns:us-east-1:123456789012:mystack-mytopic-NZJ5JSMVGFIE
        // See: https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-sns-topic.html
        String template = """
            {
              "Resources": {
                "MyTopic": {
                  "Type": "AWS::SNS::Topic",
                  "Properties": {}
                }
              },
              "Outputs": {
                "TopicRef": {
                  "Value": {"Ref": "MyTopic"}
                },
                "TopicArn": {
                  "Value": {"Fn::GetAtt": ["MyTopic", "TopicName"]}
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "sns-auto-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // SNS Ref returns ARN (which contains the auto-generated topic name)
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "sns-auto-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            // Ref returns ARN containing the auto-generated name
            .body(containsString("arn:aws:sns:"))
            .body(containsString("sns-auto-stack-MyTopic-"));
    }

    @Test
    void createStack_ecrAutoName_isLowercase() {
        // ECR repository names must be lowercase.
        // See: https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-ecr-repository.html
        String template = """
            {
              "Resources": {
                "MyRepo": {
                  "Type": "AWS::ECR::Repository",
                  "Properties": {}
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "ECR-Upper-Stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // ECR auto-name should be lowercase
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", "ECR-Upper-Stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("ecr-upper-stack-myrepo-"))
            .body(not(containsString("ECR-Upper-Stack-MyRepo-")));
    }

    // ── Secrets Manager: GenerateSecretString + Description ──────────────────

    @Test
    void createStack_secretWithGenerateSecretString_defaultPassword() {
        String template = """
            {
              "Resources": {
                "MySecret": {
                  "Type": "AWS::SecretsManager::Secret",
                  "Properties": {
                    "Name": "cfn-gen-default",
                    "GenerateSecretString": {}
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "gen-secret-default")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        // Verify secret was created and has a generated value (default 32 chars)
        String body = given()
            .header("X-Amz-Target", "secretsmanager.GetSecretValue")
            .contentType(SM_CONTENT_TYPE)
            .body("{\"SecretId\": \"cfn-gen-default\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        try {
            JsonNode json = OBJECT_MAPPER.readTree(body);
            String secretString = json.get("SecretString").asText();
            assertThat(secretString, notNullValue());
            assertThat(secretString.length(), equalTo(32));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void createStack_secretWithGenerateSecretString_customLength() {
        String template = """
            {
              "Resources": {
                "MySecret": {
                  "Type": "AWS::SecretsManager::Secret",
                  "Properties": {
                    "Name": "cfn-gen-len64",
                    "GenerateSecretString": {
                      "PasswordLength": 64,
                      "ExcludePunctuation": true
                    }
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "gen-secret-len64")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String body = given()
            .header("X-Amz-Target", "secretsmanager.GetSecretValue")
            .contentType(SM_CONTENT_TYPE)
            .body("{\"SecretId\": \"cfn-gen-len64\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        try {
            JsonNode json = OBJECT_MAPPER.readTree(body);
            String secretString = json.get("SecretString").asText();
            assertThat(secretString.length(), equalTo(64));
            // No punctuation
            assertThat(secretString, not(matchesRegex(".*[!\"#$%&'()*+,\\-./:;<=>?@\\[\\\\\\]^_`{|}~].*")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void createStack_secretWithGenerateSecretString_templateAndKey() {
        String template = """
            {
              "Resources": {
                "MySecret": {
                  "Type": "AWS::SecretsManager::Secret",
                  "Properties": {
                    "Name": "cfn-gen-template",
                    "GenerateSecretString": {
                      "SecretStringTemplate": "{\\"username\\": \\"admin\\"}",
                      "GenerateStringKey": "password",
                      "PasswordLength": 20,
                      "ExcludePunctuation": true
                    }
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "gen-secret-tpl")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String body = given()
            .header("X-Amz-Target", "secretsmanager.GetSecretValue")
            .contentType(SM_CONTENT_TYPE)
            .body("{\"SecretId\": \"cfn-gen-template\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        try {
            JsonNode json = OBJECT_MAPPER.readTree(body);
            String secretString = json.get("SecretString").asText();
            assertThat(secretString, notNullValue());
            // Parse the secret value as JSON
            JsonNode secretJson = OBJECT_MAPPER.readTree(secretString);
            assertThat(secretJson.get("username").asText(), equalTo("admin"));
            assertThat(secretJson.has("password"), equalTo(true));
            assertThat(secretJson.get("password").asText().length(), equalTo(20));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void createStack_secretWithDescription() {
        String template = """
            {
              "Resources": {
                "MySecret": {
                  "Type": "AWS::SecretsManager::Secret",
                  "Properties": {
                    "Name": "cfn-desc-secret",
                    "Description": "My test secret description",
                    "SecretString": "my-value"
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "desc-secret-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify description via DescribeSecret
        given()
            .header("X-Amz-Target", "secretsmanager.DescribeSecret")
            .contentType(SM_CONTENT_TYPE)
            .body("{\"SecretId\": \"cfn-desc-secret\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Description", equalTo("My test secret description"))
            .body("Name", equalTo("cfn-desc-secret"));
    }

    @Test
    void createStack_secretWithDescriptionAndGenerateSecretString() {
        String template = """
            {
              "Resources": {
                "MySecret": {
                  "Type": "AWS::SecretsManager::Secret",
                  "Properties": {
                    "Name": "cfn-desc-gen-secret",
                    "Description": "Generated secret with desc",
                    "GenerateSecretString": {
                      "PasswordLength": 16,
                      "ExcludeNumbers": true
                    }
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "desc-gen-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify description
        given()
            .header("X-Amz-Target", "secretsmanager.DescribeSecret")
            .contentType(SM_CONTENT_TYPE)
            .body("{\"SecretId\": \"cfn-desc-gen-secret\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Description", equalTo("Generated secret with desc"));

        // Verify generated value
        String body = given()
            .header("X-Amz-Target", "secretsmanager.GetSecretValue")
            .contentType(SM_CONTENT_TYPE)
            .body("{\"SecretId\": \"cfn-desc-gen-secret\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        try {
            JsonNode json = OBJECT_MAPPER.readTree(body);
            String secretString = json.get("SecretString").asText();
            assertThat(secretString.length(), equalTo(16));
            assertThat(secretString, not(matchesRegex(".*[0-9].*")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void createStack_secretWithBothSecretStringAndGenerateSecretString_fails() {
        // AWS rejects when both SecretString and GenerateSecretString are specified
        String template = """
            {
              "Resources": {
                "MySecret": {
                  "Type": "AWS::SecretsManager::Secret",
                  "Properties": {
                    "Name": "cfn-both-secret",
                    "SecretString": "explicit-value",
                    "GenerateSecretString": {
                      "PasswordLength": 64
                    }
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "both-secret-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // The resource should have failed provisioning
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", "both-secret-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("CREATE_FAILED"));
    }

    @Test
    void createStack_secretWithNoSecretStringOrGenerate_defaultsEmptyJson() {
        String template = """
            {
              "Resources": {
                "MySecret": {
                  "Type": "AWS::SecretsManager::Secret",
                  "Properties": {
                    "Name": "cfn-no-value-secret"
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "no-value-secret-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "secretsmanager.GetSecretValue")
            .contentType(SM_CONTENT_TYPE)
            .body("{\"SecretId\": \"cfn-no-value-secret\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SecretString", equalTo("{}"));
    }

    @Test
    void createStack_secretAutoName_withGenerateSecretString() {
        String template = """
            {
              "Resources": {
                "AutoSecret": {
                  "Type": "AWS::SecretsManager::Secret",
                  "Properties": {
                    "GenerateSecretString": {
                      "PasswordLength": 10,
                      "ExcludeLowercase": true,
                      "ExcludeUppercase": true,
                      "ExcludePunctuation": true
                    }
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "auto-gen-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify resource was created with auto-generated name
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", "auto-gen-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("auto-gen-stack-AutoSecret-"))
            .body(containsString("CREATE_COMPLETE"));
    }

    @Test
    void createStack_secretRefReturnsArn() {
        String template = """
            {
              "Resources": {
                "MySecret": {
                  "Type": "AWS::SecretsManager::Secret",
                  "Properties": {
                    "Name": "cfn-ref-secret",
                    "GenerateSecretString": {}
                  }
                }
              },
              "Outputs": {
                "SecretArn": {
                  "Value": {"Ref": "MySecret"}
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "ref-secret-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "ref-secret-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("arn:aws:secretsmanager:"));
    }

    @Test
    void createStack_withEventBridgeRule() {
        // First, create an SQS queue to use as a target
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "cfn-eventbridge-target-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String template = """
            {
              "Resources": {
                "MyRule": {
                  "Type": "AWS::Events::Rule",
                  "Properties": {
                    "Name": "cfn-test-rule",
                    "Description": "Test rule created via CloudFormation",
                    "EventPattern": {
                      "source": ["my.application"],
                      "detail-type": ["MyEvent"]
                    },
                    "Targets": [
                      {
                        "Id": "Target0",
                        "Arn": "arn:aws:sqs:us-east-1:000000000000:cfn-eventbridge-target-queue"
                      }
                    ]
                  }
                }
              }
            }
            """;

        // 1. Create Stack
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "cfn-eventbridge-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        // 2. Verify stack is CREATE_COMPLETE
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "cfn-eventbridge-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        // 3. Verify the EventBridge rule was actually created
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "AWSEvents.DescribeRule")
            .body("{\"Name\":\"cfn-test-rule\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Name", equalTo("cfn-test-rule"))
            .body("Description", equalTo("Test rule created via CloudFormation"))
            .body("State", equalTo("ENABLED"))
            .body("Arn", notNullValue());

        // 4. Verify targets were attached to the rule
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "AWSEvents.ListTargetsByRule")
            .body("{\"Rule\":\"cfn-test-rule\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Targets[0].Id", equalTo("Target0"))
            .body("Targets[0].Arn", equalTo("arn:aws:sqs:us-east-1:000000000000:cfn-eventbridge-target-queue"));
    }

    @Test
    void createStack_withEventBridgeRule_resolvesFnGetAttOnTargetArn() {
        // This template uses Fn::GetAtt to reference the SQS queue's ARN as an EventBridge
        // rule target — the pattern produced by AWS CDK when wiring an SqsQueue target.
        // The queue ARN must be resolved during target provisioning, otherwise the rule
        // ends up with an empty target ARN and events are never delivered.
        String template = """
            {
              "Resources": {
                "TargetQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "cfn-eb-getatt-queue"
                  }
                },
                "MyRule": {
                  "Type": "AWS::Events::Rule",
                  "Properties": {
                    "Name": "cfn-eb-getatt-rule",
                    "EventPattern": {
                      "source": ["my.getatt.test"]
                    },
                    "Targets": [
                      {
                        "Id": "Target0",
                        "Arn": {"Fn::GetAtt": ["TargetQueue", "Arn"]}
                      }
                    ]
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "cfn-eb-getatt-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "cfn-eb-getatt-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "AWSEvents.ListTargetsByRule")
            .body("{\"Rule\":\"cfn-eb-getatt-rule\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Targets[0].Id", equalTo("Target0"))
            .body("Targets[0].Arn", equalTo("arn:aws:sqs:us-east-1:000000000000:cfn-eb-getatt-queue"));
    }

    @Test
    void createStack_withEventBridgeRuleAutoName() {
        String template = """
            {
              "Resources": {
                "AutoNamedRule": {
                  "Type": "AWS::Events::Rule",
                  "Properties": {
                    "EventPattern": {
                      "source": ["auto.test"]
                    }
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "cfn-eb-autoname-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "cfn-eb-autoname-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        // Verify the rule was created via ListRules — should find one matching the auto-generated name
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "AWSEvents.ListRules")
            .body("{\"NamePrefix\":\"cfn-eb-autoname-stack\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Rules.size()", equalTo(1));
    }

    @Test
    void createStack_dependencyOrdering_refBeforeTarget() {
        String template = """
            {
              "Resources": {
                "ParamForQueue": {
                  "Type": "AWS::SSM::Parameter",
                  "Properties": {
                    "Name": "/dep-order/ref-queue-name",
                    "Type": "String",
                    "Value": {"Ref": "DepOrderQueue"}
                  }
                },
                "DepOrderQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "dep-order-ref-queue"
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "dep-order-ref-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "dep-order-ref-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {"Name": "/dep-order/ref-queue-name", "WithDecryption": true}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameter.Value", containsString("dep-order-ref-queue"));
    }

    @Test
    void createStack_dependencyOrdering_getAttBeforeTarget() {
        String template = """
            {
              "Resources": {
                "ArnParam": {
                  "Type": "AWS::SSM::Parameter",
                  "Properties": {
                    "Name": "/dep-order/getatt-table-arn",
                    "Type": "String",
                    "Value": {"Fn::GetAtt": ["DepOrderTable", "Arn"]}
                  }
                },
                "DepOrderTable": {
                  "Type": "AWS::DynamoDB::Table",
                  "Properties": {
                    "TableName": "dep-order-getatt-table",
                    "AttributeDefinitions": [
                      {"AttributeName": "pk", "AttributeType": "S"}
                    ],
                    "KeySchema": [
                      {"AttributeName": "pk", "KeyType": "HASH"}
                    ]
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "dep-order-getatt-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "dep-order-getatt-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {"Name": "/dep-order/getatt-table-arn", "WithDecryption": true}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameter.Value", startsWith("arn:aws:dynamodb:"));
    }

    @Test
    void createStack_dependencyOrdering_fnSubBeforeTarget() {
        String template = """
            {
              "Resources": {
                "SubParam": {
                  "Type": "AWS::SSM::Parameter",
                  "Properties": {
                    "Name": "/dep-order/sub-queue-arn",
                    "Type": "String",
                    "Value": {"Fn::Sub": "arn:aws:sqs:${AWS::Region}:${AWS::AccountId}:${DepSubQueue}"}
                  }
                },
                "DepSubQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "dep-order-sub-queue"
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "dep-order-sub-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "dep-order-sub-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {"Name": "/dep-order/sub-queue-arn", "WithDecryption": true}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameter.Value", containsString("dep-order-sub-queue"));
    }

    @Test
    void deleteStack_withEventBridgeRule() {
        String template = """
            {
              "Resources": {
                "DeleteTestRule": {
                  "Type": "AWS::Events::Rule",
                  "Properties": {
                    "Name": "cfn-delete-test-rule",
                    "EventPattern": {
                      "source": ["delete.test"]
                    }
                  }
                }
              }
            }
            """;

        // Create
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "cfn-eb-delete-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify rule exists
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "AWSEvents.DescribeRule")
            .body("{\"Name\":\"cfn-delete-test-rule\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Name", equalTo("cfn-delete-test-rule"));

        // Delete stack
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStack")
            .formParam("StackName", "cfn-eb-delete-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify rule is gone
        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "AWSEvents.DescribeRule")
            .body("{\"Name\":\"cfn-delete-test-rule\"}")
        .when()
            .post("/")
        .then()
            .statusCode(404);
    }
}
