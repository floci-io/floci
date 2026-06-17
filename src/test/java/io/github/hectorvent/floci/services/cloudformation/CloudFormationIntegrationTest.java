package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.MutableClock;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class CloudFormationIntegrationTest {

    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String SSM_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String SM_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String COGNITO_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Inject
    MutableClock clock;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void resetClock() {
        clock.reset();
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

    private static String firstPhysicalResourceId(String xml) {
        assertThat(xml, containsString("<PhysicalResourceId>"));
        String startMarker = "<PhysicalResourceId>";
        String endMarker = "</PhysicalResourceId>";
        int start = xml.indexOf(startMarker) + startMarker.length();
        int end = xml.indexOf(endMarker, start);
        return xml.substring(start, end);
    }

    private static String physicalIdByLogicalId(String xml, String logicalId) {
        String memberOpen = "<member>";
        String memberClose = "</member>";
        String logicalMarker = "<LogicalResourceId>" + logicalId + "</LogicalResourceId>";
        int logicalIdx = xml.indexOf(logicalMarker);
        assertThat("logical id '" + logicalId + "' present in DescribeStackResources output",
                logicalIdx, not(equalTo(-1)));
        int memberStart = xml.lastIndexOf(memberOpen, logicalIdx);
        int memberEnd = xml.indexOf(memberClose, logicalIdx);
        String member = xml.substring(memberStart, memberEnd);
        String physicalOpen = "<PhysicalResourceId>";
        String physicalClose = "</PhysicalResourceId>";
        int pStart = member.indexOf(physicalOpen) + physicalOpen.length();
        int pEnd = member.indexOf(physicalClose, pStart);
        return member.substring(pStart, pEnd);
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
    void updateStack_autoNamedLambdaKeepsPhysicalIdForWarmContainerReuse() {
        String stackName = "cfn-lambda-reuse-stack";
        String template = """
            {
              "Resources": {
                "MyFunction": {
                  "Type": "AWS::Lambda::Function",
                  "Properties": {
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
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        String createdResourceXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<LogicalResourceId>MyFunction</LogicalResourceId>"))
            .extract().asString();

        String firstFunctionName = firstPhysicalResourceId(createdResourceXml);
        assertThat(firstFunctionName, startsWith(stackName + "-MyFunction-"));

        String firstRevisionId = given()
        .when()
            .get("/2015-03-31/functions/" + firstFunctionName)
        .then()
            .statusCode(200)
            .extract().path("Configuration.RevisionId");

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        String updatedResourceXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<LogicalResourceId>MyFunction</LogicalResourceId>"))
            .extract().asString();

        assertThat(firstPhysicalResourceId(updatedResourceXml), equalTo(firstFunctionName));

        given()
        .when()
            .get("/2015-03-31/functions/" + firstFunctionName)
        .then()
            .statusCode(200)
            .body("Configuration.FunctionName", equalTo(firstFunctionName));

        String secondRevisionId = given()
        .when()
            .get("/2015-03-31/functions/" + firstFunctionName)
        .then()
            .statusCode(200)
            .extract().path("Configuration.RevisionId");

        assertThat(secondRevisionId, equalTo(firstRevisionId));
    }

    @Test
    void updateStack_lambdaMutableConfigurationUpdatesInPlace() {
        String stackName = "cfn-lambda-config-update-stack";
        String functionName = "cfn-lambda-config-update-func";
        String template = """
            {
              "Resources": {
                "MyFunction": {
                  "Type": "AWS::Lambda::Function",
                  "Properties": {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Handler": "index.handler",
                    "Timeout": 3,
                    "Role": "arn:aws:iam::000000000000:role/cfn-test-lambda-role",
                    "Environment": {
                      "Variables": {
                        "STAGE": "blue"
                      }
                    }
                  }
                }
              }
            }
            """.formatted(functionName);
        String updatedTemplate = """
            {
              "Resources": {
                "MyFunction": {
                  "Type": "AWS::Lambda::Function",
                  "Properties": {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Handler": "index.handler",
                    "Timeout": 9,
                    "Role": "arn:aws:iam::000000000000:role/cfn-test-lambda-role",
                    "Environment": {
                      "Variables": {
                        "STAGE": "green"
                      }
                    }
                  }
                }
              }
            }
            """.formatted(functionName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String createdResourceXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(firstPhysicalResourceId(createdResourceXml), equalTo(functionName));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", updatedTemplate)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String updatedResourceXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(firstPhysicalResourceId(updatedResourceXml), equalTo(functionName));

        given()
        .when()
            .get("/2015-03-31/functions/" + functionName)
        .then()
            .statusCode(200)
            .body("Configuration.FunctionName", equalTo(functionName))
            .body("Configuration.Timeout", equalTo(9))
            .body("Configuration.Environment.Variables.STAGE", equalTo("green"));
    }

    @Test
    void updateStack_lambdaFunctionNameChangeReplacesPhysicalResource() {
        String stackName = "cfn-lambda-replace-stack";
        String oldFunctionName = "cfn-lambda-replace-old-func";
        String newFunctionName = "cfn-lambda-replace-new-func";
        String template = lambdaTemplateWithFunctionName(oldFunctionName);
        String updatedTemplate = lambdaTemplateWithFunctionName(newFunctionName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", updatedTemplate)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String updatedResourceXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(firstPhysicalResourceId(updatedResourceXml), equalTo(newFunctionName));

        given()
        .when()
            .get("/2015-03-31/functions/" + newFunctionName)
        .then()
            .statusCode(200)
            .body("Configuration.FunctionName", equalTo(newFunctionName));

        given()
        .when()
            .get("/2015-03-31/functions/" + oldFunctionName)
        .then()
            .statusCode(404);
    }

    private static String lambdaTemplateWithFunctionName(String functionName) {
        return """
            {
              "Resources": {
                "MyFunction": {
                  "Type": "AWS::Lambda::Function",
                  "Properties": {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Handler": "index.handler",
                    "Role": "arn:aws:iam::000000000000:role/cfn-test-lambda-role"
                  }
                }
              }
            }
            """.formatted(functionName);
    }

    @Test
    void createStack_kmsKeyWithOverrideTagUsesPinnedId() {
        String template = """
            {
              "Resources": {
                "MyKey": {
                  "Type": "AWS::KMS::Key",
                  "Properties": {
                    "Description": "cfn override key",
                    "Tags": [
                      { "Key": "floci:override-id", "Value": "cfn-pinned-key" },
                      { "Key": "env", "Value": "test" }
                    ]
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "cfn-kms-override-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "TrentService.DescribeKey")
            .body("""
                {"KeyId":"cfn-pinned-key"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("KeyMetadata.KeyId", equalTo("cfn-pinned-key"));

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "TrentService.ListResourceTags")
            .body("""
                {"KeyId":"cfn-pinned-key"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.TagKey", hasItem("env"))
            .body("Tags.find { it.TagKey == 'env' }.TagValue", equalTo("test"))
            .body("Tags.find { it.TagKey == 'floci:override-id' }", nullValue());
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
    void createStack_lambdaWithInlineZipFile() {
        String template = """
            {
              "Resources": {
                "MyFunction": {
                  "Type": "AWS::Lambda::Function",
                  "Properties": {
                    "FunctionName": "cfn-inline-zipfile-func",
                    "Runtime": "nodejs20.x",
                    "Handler": "index.handler",
                    "Code": {
                      "ZipFile": "exports.handler = async (e) => ({ statusCode: 200 });"
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
            .formParam("StackName", "cfn-inline-zipfile-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "cfn-inline-zipfile-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
        .when()
            .get("/2015-03-31/functions/cfn-inline-zipfile-func")
        .then()
            .statusCode(200)
            .body("Configuration.FunctionName", equalTo("cfn-inline-zipfile-func"));
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
    void describeDeletedStackEvents_byArn_returnsDeleteCompleteEvents() throws Exception {
        String template = """
            {
              "Resources": {
                "MyBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": {
                    "BucketName": "deleted-arn-events-test-bucket"
                  }
                }
              }
            }
            """;

        String createResponse = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "deleted-arn-events-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"))
            .extract().asString();

        String stackArn = createResponse.substring(
                createResponse.indexOf("<StackId>") + "<StackId>".length(),
                createResponse.indexOf("</StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStack")
            .formParam("StackName", "deleted-arn-events-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String deletedEventsXml = null;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            deletedEventsXml = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStackEvents")
                .formParam("StackName", stackArn)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().asString();

            if (deletedEventsXml.contains("<ResourceStatus>DELETE_COMPLETE</ResourceStatus>")) {
                break;
            }
            Thread.sleep(200);
        }

        assertThat(deletedEventsXml, containsString("<StackId>" + stackArn + "</StackId>"));
        assertThat(deletedEventsXml, containsString("<ResourceStatus>DELETE_COMPLETE</ResourceStatus>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>DELETE_COMPLETE</StackStatus>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "deleted-arn-events-stack")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("does not exist"));
    }

    @Test
    void describeDeletedStack_byArn_expiresAfterRetentionWindow() throws Exception {
        String template = """
            {
              "Resources": {
                "MyBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": {
                    "BucketName": "deleted-arn-expiry-test-bucket"
                  }
                }
              }
            }
            """;

        String createResponse = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "deleted-arn-expiry-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"))
            .extract().asString();

        String stackArn = createResponse.substring(
                createResponse.indexOf("<StackId>") + "<StackId>".length(),
                createResponse.indexOf("</StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStack")
            .formParam("StackName", "deleted-arn-expiry-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        long readyDeadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < readyDeadline) {
            String deletedEventsXml = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStackEvents")
                .formParam("StackName", stackArn)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().asString();

            if (deletedEventsXml.contains("<ResourceStatus>DELETE_COMPLETE</ResourceStatus>")) {
                break;
            }
            Thread.sleep(200);
        }

        clock.advance(Duration.ofSeconds(31));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stackArn)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("does not exist"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackArn)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("does not exist"));
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
    void updateStack_dynamoDbRefStillResolvesWhenTableAlreadyExists() {
        String suffix = Long.toHexString(System.nanoTime());
        String stackName = "redeploy-ref-stack-" + suffix;
        String tableName = "redeploy-ref-table-" + suffix;
        String parameterName = "/app/redeploy-ref-table-" + suffix;
        String template = """
            {
              "Resources": {
                "MyTable": {
                  "Type": "AWS::DynamoDB::Table",
                  "Properties": {
                    "TableName": "%s",
                    "AttributeDefinitions": [
                      {"AttributeName": "pk", "AttributeType": "S"}
                    ],
                    "KeySchema": [
                      {"AttributeName": "pk", "KeyType": "HASH"}
                    ],
                    "BillingMode": "PAY_PER_REQUEST"
                  }
                },
                "TableNameParam": {
                  "Type": "AWS::SSM::Parameter",
                  "Properties": {
                    "Name": "%s",
                    "Type": "String",
                    "Value": {"Ref": "MyTable"}
                  }
                }
              },
              "Outputs": {
                "TableName": {
                  "Value": {"Ref": "MyTable"}
                }
              }
            }
            """.formatted(tableName, parameterName);

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
            .formParam("Action", "UpdateStack")
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
            .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"))
            .body(containsString("<OutputKey>TableName</OutputKey>"))
            .body(containsString("<OutputValue>" + tableName + "</OutputValue>"));

        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(SSM_CONTENT_TYPE)
            .body("""
                {"Name": "%s", "WithDecryption": true}
                """.formatted(parameterName))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Parameter.Name", equalTo(parameterName))
            .body("Parameter.Value", equalTo(tableName));
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
    void createStack_withEventBridgeRule_preservesSqsParametersMessageGroupId() {
        // Regression test for issue #787: CFN provisioner dropped Targets[].SqsParameters,
        // making FIFO SQS delivery fail with "The request must contain the parameter MessageGroupId".
        // The same target works when registered via direct events PutTargets, proving the
        // EventBridge API path supports the field — only the CFN translator was missing it.
        String template = """
            {
              "Resources": {
                "FifoQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "cfn-eb-fifo-target.fifo",
                    "FifoQueue": true,
                    "ContentBasedDeduplication": true
                  }
                },
                "FifoRule": {
                  "Type": "AWS::Events::Rule",
                  "Properties": {
                    "Name": "cfn-eb-fifo-rule",
                    "EventPattern": {
                      "source": ["cfn.fifo.test"]
                    },
                    "Targets": [
                      {
                        "Id": "FifoQueueTarget",
                        "Arn": {"Fn::GetAtt": ["FifoQueue", "Arn"]},
                        "SqsParameters": {"MessageGroupId": "group-1"}
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
            .formParam("StackName", "cfn-eb-fifo-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "cfn-eb-fifo-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "AWSEvents.ListTargetsByRule")
            .body("{\"Rule\":\"cfn-eb-fifo-rule\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Targets[0].Id", equalTo("FifoQueueTarget"))
            .body("Targets[0].Arn", equalTo("arn:aws:sqs:us-east-1:000000000000:cfn-eb-fifo-target.fifo"))
            .body("Targets[0].SqsParameters.MessageGroupId", equalTo("group-1"));
    }

    @Test
    void createStack_withEventBridgeRule_fifoDeliveryEndToEnd() {
        // Functional regression for issue #787: not only must the field round-trip
        // through ListTargetsByRule, an event published via PutEvents must actually
        // be delivered to the FIFO queue. Without MessageGroupId, SQS rejects with
        // "The request must contain the parameter MessageGroupId" and the message
        // never lands. We verify delivery + that MessageGroupId surfaces on the
        // received SQS attribute.
        String template = """
            {
              "Resources": {
                "FifoQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "cfn-eb-fifo-e2e.fifo",
                    "FifoQueue": true,
                    "ContentBasedDeduplication": true
                  }
                },
                "FifoRule": {
                  "Type": "AWS::Events::Rule",
                  "Properties": {
                    "Name": "cfn-eb-fifo-e2e-rule",
                    "EventPattern": {
                      "source": ["cfn.fifo.e2e"]
                    },
                    "Targets": [
                      {
                        "Id": "FifoQueueTarget",
                        "Arn": {"Fn::GetAtt": ["FifoQueue", "Arn"]},
                        "SqsParameters": {"MessageGroupId": "e2e-group"}
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
            .formParam("StackName", "cfn-eb-fifo-e2e-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Match the issue #787 reproducer: re-apply ContentBasedDeduplication via
        // SetQueueAttributes to control for a separate Floci CFN→SQS bug where
        // FIFO queue attributes do not always propagate from the template. This
        // test focuses solely on the EventBridge target SqsParameters wiring.
        String queueUrl = "http://localhost:4566/000000000000/cfn-eb-fifo-e2e.fifo";
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "SetQueueAttributes")
            .formParam("QueueUrl", queueUrl)
            .formParam("Attribute.1.Name", "ContentBasedDeduplication")
            .formParam("Attribute.1.Value", "true")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "AWSEvents.PutEvents")
            .body("""
                {
                  "Entries": [
                    {
                      "Source": "cfn.fifo.e2e",
                      "DetailType": "poc",
                      "Detail": "{\\"marker\\":\\"cfn-fifo-e2e\\"}"
                    }
                  ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FailedEntryCount", equalTo(0));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MaxNumberOfMessages", "1")
            .formParam("WaitTimeSeconds", "1")
            .formParam("AttributeName.1", "All")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Body>"))
            .body(containsString("cfn-fifo-e2e"))
            .body(containsString("<Name>MessageGroupId</Name>"))
            .body(containsString("<Value>e2e-group</Value>"));
    }

    @Test
    void createStack_withEventBridgeRule_targetWithoutSqsParameters_omitsField() {
        // Backwards-compat: targets that have no SqsParameters in the CFN template
        // must NOT acquire one in the materialised target. Real AWS omits the field
        // entirely from ListTargetsByRule when none was supplied at PutTargets time;
        // we mirror that to avoid SDK clients seeing a phantom (null) container.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "cfn-eb-no-sqs-params-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String template = """
            {
              "Resources": {
                "PlainRule": {
                  "Type": "AWS::Events::Rule",
                  "Properties": {
                    "Name": "cfn-eb-no-sqs-params-rule",
                    "EventPattern": {"source": ["cfn.no.sqs.params"]},
                    "Targets": [
                      {
                        "Id": "PlainTarget",
                        "Arn": "arn:aws:sqs:us-east-1:000000000000:cfn-eb-no-sqs-params-queue"
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
            .formParam("StackName", "cfn-eb-no-sqs-params-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "AWSEvents.ListTargetsByRule")
            .body("{\"Rule\":\"cfn-eb-no-sqs-params-rule\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Targets[0].Id", equalTo("PlainTarget"))
            .body("Targets[0].SqsParameters", nullValue());
    }

    @Test
    void createStack_withEventBridgeRule_emptySqsParametersBlock_isIgnored() {
        // Edge case mirroring the direct PutTargets handler (EventBridgeHandler line 211-219):
        // an SqsParameters object that is present but does not carry a MessageGroupId
        // produces NO SqsParameters on the target. We do not coerce empty input into a
        // half-populated object — that would mask user mistakes.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "cfn-eb-empty-sqs-params-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String template = """
            {
              "Resources": {
                "EmptyParamsRule": {
                  "Type": "AWS::Events::Rule",
                  "Properties": {
                    "Name": "cfn-eb-empty-sqs-params-rule",
                    "EventPattern": {"source": ["cfn.empty.sqs.params"]},
                    "Targets": [
                      {
                        "Id": "EmptyParamsTarget",
                        "Arn": "arn:aws:sqs:us-east-1:000000000000:cfn-eb-empty-sqs-params-queue",
                        "SqsParameters": {}
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
            .formParam("StackName", "cfn-eb-empty-sqs-params-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "AWSEvents.ListTargetsByRule")
            .body("{\"Rule\":\"cfn-eb-empty-sqs-params-rule\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Targets[0].Id", equalTo("EmptyParamsTarget"))
            .body("Targets[0].SqsParameters", nullValue());
    }

    @Test
    void createStack_withEventBridgeRule_mixedTargets_preservesPerTargetSqsParameters() {
        // Reviewer-defence test: a single rule with two FIFO targets — only one carrying
        // SqsParameters — must keep them on the right target and leave the other untouched.
        // Catches future regressions where the field would leak across iterations of the
        // Targets[] loop (e.g. via a hoisted local variable).
        String template = """
            {
              "Resources": {
                "QueueA": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "cfn-eb-mixed-a.fifo",
                    "FifoQueue": true,
                    "ContentBasedDeduplication": true
                  }
                },
                "QueueB": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "cfn-eb-mixed-b.fifo",
                    "FifoQueue": true,
                    "ContentBasedDeduplication": true
                  }
                },
                "MixedRule": {
                  "Type": "AWS::Events::Rule",
                  "Properties": {
                    "Name": "cfn-eb-mixed-rule",
                    "EventPattern": {"source": ["cfn.mixed.targets"]},
                    "Targets": [
                      {
                        "Id": "TargetWithGroup",
                        "Arn": {"Fn::GetAtt": ["QueueA", "Arn"]},
                        "SqsParameters": {"MessageGroupId": "group-A"}
                      },
                      {
                        "Id": "TargetWithoutGroup",
                        "Arn": {"Fn::GetAtt": ["QueueB", "Arn"]}
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
            .formParam("StackName", "cfn-eb-mixed-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "AWSEvents.ListTargetsByRule")
            .body("{\"Rule\":\"cfn-eb-mixed-rule\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Targets.find { it.Id == 'TargetWithGroup' }.SqsParameters.MessageGroupId", equalTo("group-A"))
            .body("Targets.find { it.Id == 'TargetWithoutGroup' }.SqsParameters", nullValue());
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

    @Test
    void createChangeSet_describeAndExecuteByArn_succeeds() {
        // Regression test for: DescribeChangeSet / ExecuteChangeSet fail when called
        // with a changeset ARN instead of a short name.
        // The AWS CLI's `aws cloudformation deploy` always passes the full ARN returned
        // by CreateChangeSet back to DescribeChangeSet and ExecuteChangeSet, so this
        // path must work for `deploy` to function at all.
        String template = """
            {
              "Resources": {
                "MyQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": { "QueueName": "cfn-cs-arn-queue" }
                }
              }
            }
            """;

        // 1. CreateChangeSet — returns a changeset ARN in the response
        String createXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", "cfn-cs-arn-stack")
            .formParam("ChangeSetName", "my-changeset")
            .formParam("ChangeSetType", "CREATE")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Id>"))
            .extract().asString();

        // Extract the full changeset ARN from the CreateChangeSet response
        String changeSetArn = createXml
            .split("<Id>")[1]
            .split("</Id>")[0];

        assertThat("CreateChangeSet should return a changeset ARN",
            changeSetArn, startsWith("arn:aws:cloudformation:"));

        // 2. DescribeChangeSet by ARN — must return Status, not 400
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", "cfn-cs-arn-stack")
            .formParam("ChangeSetName", changeSetArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Status>CREATE_COMPLETE</Status>"));

        // 3. ExecuteChangeSet by ARN — must succeed and provision the stack
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ExecuteChangeSet")
            .formParam("StackName", "cfn-cs-arn-stack")
            .formParam("ChangeSetName", changeSetArn)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // 4. Stack should reach CREATE_COMPLETE
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "cfn-cs-arn-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));
    }

    @Test
    void createStack_withPipe() {
        String template = """
            {
              "Resources": {
                "SourceQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "cfn-pipe-source"
                  }
                },
                "TargetQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "cfn-pipe-target"
                  }
                },
                "MyPipe": {
                  "Type": "AWS::Pipes::Pipe",
                  "Properties": {
                    "Name": "cfn-test-pipe",
                    "Source": { "Fn::GetAtt": ["SourceQueue", "Arn"] },
                    "Target": { "Fn::GetAtt": ["TargetQueue", "Arn"] },
                    "RoleArn": "arn:aws:iam::000000000000:role/pipe-role",
                    "Description": "CF provisioned pipe",
                    "DesiredState": "STOPPED",
                    "SourceParameters": {
                      "SqsQueueParameters": {
                        "BatchSize": 5
                      }
                    }
                  }
                }
              }
            }
            """;

        // 1. Create Stack
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "cfn-pipe-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        // 2. Stack should reach CREATE_COMPLETE
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "cfn-pipe-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        // 3. Verify pipe exists via Pipes REST API
        given()
            .contentType("application/json")
        .when()
            .get("/v1/pipes/cfn-test-pipe")
        .then()
            .statusCode(200)
            .body("Name", equalTo("cfn-test-pipe"))
            .body("Source", containsString("cfn-pipe-source"))
            .body("Target", containsString("cfn-pipe-target"))
            .body("Description", equalTo("CF provisioned pipe"))
            .body("DesiredState", equalTo("STOPPED"))
            .body("CurrentState", equalTo("STOPPED"));

        // 4. Delete stack and verify pipe is cleaned up
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStack")
            .formParam("StackName", "cfn-pipe-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
        .when()
            .get("/v1/pipes/cfn-test-pipe")
        .then()
            .statusCode(404);
    }

    // ── TemplateURL (path-style AWS S3) ──────────────────────────────────────

    @Test
    void createStack_templateUrlPathStyle_resolvesLocalS3() {
        String bucket = "cfn-template-url-bucket";
        String key = "template.json";
        String template = """
            {
              "Resources": {
                "MyQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "cfn-template-url-queue"
                  }
                }
              }
            }
            """;

        // Create S3 bucket and upload template
        given().when().put("/" + bucket).then().statusCode(200);
        given()
            .contentType("application/json")
            .body(template)
        .when()
            .put("/" + bucket + "/" + key)
        .then()
            .statusCode(200);

        // CreateStack using a CDK-style path-style AWS S3 TemplateURL
        String templateUrl = "https://s3.us-east-1.amazonaws.com/" + bucket + "/" + key;
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "cfn-template-url-stack")
            .formParam("TemplateURL", templateUrl)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        // Verify stack and its resource were provisioned from the S3 template
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "cfn-template-url-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("CREATE_COMPLETE"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", "cfn-template-url-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void createStack_templateUrlVirtualHosted_resolvesLocalS3() {
        String bucket = "cfn-vhost-template-bucket";
        String key = "template.json";
        String template = """
            {
              "Resources": {
                "MyQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "cfn-vhost-template-queue"
                  }
                }
              }
            }
            """;

        given().when().put("/" + bucket).then().statusCode(200);
        given()
            .contentType("application/json")
            .body(template)
        .when()
            .put("/" + bucket + "/" + key)
        .then()
            .statusCode(200);

        // Virtual-hosted style: bucket.s3.region.amazonaws.com/key
        String templateUrl = "https://" + bucket + ".s3.us-east-1.amazonaws.com/" + key;
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "cfn-vhost-template-stack")
            .formParam("TemplateURL", templateUrl)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "cfn-vhost-template-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("CREATE_COMPLETE"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", "cfn-vhost-template-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void createStack_templateUrlFlociVirtualHost_resolvesLocalS3() {
        String suffix = Long.toHexString(System.nanoTime());
        String bucket = "cfn-floci-template-" + suffix;
        String key = "templates/template.json";
        String queueName = "cfn-floci-template-queue-" + suffix;
        String stackName = "cfn-floci-template-stack-" + suffix;
        String template = """
            {
              "Resources": {
                "MyQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "%s"
                  }
                }
              }
            }
            """.formatted(queueName);

        given().when().put("/" + bucket).then().statusCode(200);
        given()
            .contentType("application/json")
            .body(template)
        .when()
            .put("/" + bucket + "/" + key)
        .then()
            .statusCode(200);

        String templateUrl = "http://" + bucket + ".localhost.floci.io:4566/" + key;
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateURL", templateUrl)
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
            .body(containsString("CREATE_COMPLETE"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", queueName)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void createStack_lambdaEventSourceMapping() throws Exception {
        String stackName = "cfn-esm-stack";
        String funcName = "cfn-esm-func";
        String queueName = "cfn-esm-queue";

        String template = """
            {
              "Resources": {
                "MyQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "%s"
                  }
                },
                "MyFunction": {
                  "Type": "AWS::Lambda::Function",
                  "Properties": {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Handler": "index.handler",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Code": {
                      "ZipFile": "exports.handler = async (e) => ({ statusCode: 200 });"
                    }
                  }
                },
                "MyESM": {
                  "Type": "AWS::Lambda::EventSourceMapping",
                  "Properties": {
                    "FunctionName": { "Ref": "MyFunction" },
                    "EventSourceArn": { "Fn::GetAtt": ["MyQueue", "Arn"] },
                    "Enabled": true,
                    "BatchSize": 5
                  }
                }
              }
            }
            """.formatted(queueName, funcName);

        // 1. Create stack
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

        // 2. Stack must reach CREATE_COMPLETE
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        // 3. ESM resource must be present with CREATE_COMPLETE
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("AWS::Lambda::EventSourceMapping"))
            .body(containsString("CREATE_COMPLETE"));

        // 4. Lambda list-event-source-mappings must return our ESM; extract UUID from JSON
        String esmJson = given()
        .when()
            .get("/2015-03-31/event-source-mappings?FunctionName=" + funcName)
        .then()
            .statusCode(200)
            .body(containsString(funcName))
            .extract().body().asString();

        JsonNode esmList = OBJECT_MAPPER.readTree(esmJson);
        String esmUuid = esmList.path("EventSourceMappings").get(0).path("UUID").asText();

        // 5. Delete stack and verify ESM is gone
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Wait for async stack deletion to complete
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String deleteStatus = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName)
            .when()
                .post("/")
            .then()
                .extract().body().asString();
            if (deleteStatus.contains("DELETE_COMPLETE") || deleteStatus.contains("does not exist")) {
                break;
            }
            Thread.sleep(200);
        }

        given()
        .when()
            .get("/2015-03-31/event-source-mappings/" + esmUuid)
        .then()
            .statusCode(404);
    }

    @Test
    void crossStackReference_fnImportValue() {
        // Stack A exports a bucket name
        String templateA = """
            {
              "Resources": {
                "SharedBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": {
                    "BucketName": "cross-stack-shared-bucket"
                  }
                }
              },
              "Outputs": {
                "BucketNameOutput": {
                  "Value": { "Ref": "SharedBucket" },
                  "Export": {
                    "Name": "SharedBucketName"
                  }
                }
              }
            }
            """;

        // Create Stack A
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "exporter-stack")
            .formParam("TemplateBody", templateA)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        // Verify Stack A is complete and has export in output
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "exporter-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .body(containsString("<OutputKey>BucketNameOutput</OutputKey>"))
            .body(containsString("<OutputValue>cross-stack-shared-bucket</OutputValue>"))
            .body(containsString("<ExportName>SharedBucketName</ExportName>"));

        // Verify ListExports returns the export
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListExports")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Name>SharedBucketName</Name>"))
            .body(containsString("<Value>cross-stack-shared-bucket</Value>"));

        // Stack B imports the bucket name from Stack A
        String templateB = """
            {
              "Resources": {
                "ImporterQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": {
                      "Fn::Join": ["-", [
                        { "Fn::ImportValue": "SharedBucketName" },
                        "queue"
                      ]]
                    }
                  }
                }
              },
              "Outputs": {
                "QueueNameOutput": {
                  "Value": { "Ref": "ImporterQueue" }
                }
              }
            }
            """;

        // Create Stack B (imports from Stack A)
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "importer-stack")
            .formParam("TemplateBody", templateB)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        // Verify Stack B is complete and resolved the import correctly
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "importer-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .body(containsString("cross-stack-shared-bucket-queue"));

        // Verify the SQS queue was actually created with the resolved name
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", "cross-stack-shared-bucket-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("cross-stack-shared-bucket-queue"));
    }

    @Test
    void crossStackReference_fnImportValueWithSub() {
        // Stack that exports with a Fn::Sub-based export name
        String templateExporter = """
            {
              "Resources": {
                "MyTable": {
                  "Type": "AWS::DynamoDB::Table",
                  "Properties": {
                    "TableName": "cross-stack-table",
                    "AttributeDefinitions": [
                      { "AttributeName": "pk", "AttributeType": "S" }
                    ],
                    "KeySchema": [
                      { "AttributeName": "pk", "KeyType": "HASH" }
                    ],
                    "BillingMode": "PAY_PER_REQUEST"
                  }
                }
              },
              "Outputs": {
                "TableNameOut": {
                  "Value": { "Ref": "MyTable" },
                  "Export": {
                    "Name": { "Fn::Sub": "${AWS::StackName}-TableName" }
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "sub-exporter-stack")
            .formParam("TemplateBody", templateExporter)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify the dynamic export name resolved correctly
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListExports")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Name>sub-exporter-stack-TableName</Name>"))
            .body(containsString("<Value>cross-stack-table</Value>"));

        // Stack that imports using the dynamic export name
        String templateImporter = """
            {
              "Resources": {
                "ConsumerQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": {
                      "Fn::Join": ["-", [
                        { "Fn::ImportValue": "sub-exporter-stack-TableName" },
                        "consumer"
                      ]]
                    }
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "sub-importer-stack")
            .formParam("TemplateBody", templateImporter)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify the queue was created with the correctly resolved imported value
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "sub-importer-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", "cross-stack-table-consumer")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("cross-stack-table-consumer"));
    }

    @Test
    void crossStackReference_updateRemovesOldExportName() {
        String oldTemplate = """
            {
              "Outputs": {
                "SharedValue": {
                  "Value": "old-value",
                  "Export": { "Name": "OldExportName" }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "export-rename-stack")
            .formParam("TemplateBody", oldTemplate)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String newTemplate = """
            {
              "Outputs": {
                "SharedValue": {
                  "Value": "new-value",
                  "Export": { "Name": "NewExportName" }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UpdateStack")
            .formParam("StackName", "export-rename-stack")
            .formParam("TemplateBody", newTemplate)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String exportsXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListExports")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().body().asString();

        assertThat(exportsXml, containsString("<Name>NewExportName</Name>"));
        assertThat(exportsXml, containsString("<Value>new-value</Value>"));
        assertThat(exportsXml, not(containsString("<Name>OldExportName</Name>")));
    }

    @Test
    void crossStackReference_duplicateExportNameFailsSecondStack() {
        String firstTemplate = """
            {
              "Outputs": {
                "SharedValue": {
                  "Value": "first-value",
                  "Export": { "Name": "DuplicateExportName" }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "duplicate-export-stack-a")
            .formParam("TemplateBody", firstTemplate)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String secondTemplate = """
            {
              "Outputs": {
                "SharedValue": {
                  "Value": "second-value",
                  "Export": { "Name": "DuplicateExportName" }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "duplicate-export-stack-b")
            .formParam("TemplateBody", secondTemplate)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "duplicate-export-stack-b")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_FAILED</StackStatus>"))
            .body(containsString("DuplicateExportName"));

        String exportsXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListExports")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().body().asString();

        assertThat(exportsXml, containsString("<Name>DuplicateExportName</Name>"));
        assertThat(exportsXml, containsString("<Value>first-value</Value>"));
        assertThat(exportsXml, not(containsString("<Value>second-value</Value>")));
    }

    @Test
    void crossStackReference_missingImportValueFailsResource() {
        String template = """
            {
              "Resources": {
                "ImporterQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": { "Fn::ImportValue": "MissingExportName" }
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "missing-import-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", "missing-import-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("CREATE_FAILED"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", "missing-import-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("MissingExportName"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", "MissingExportName")
        .when()
            .post("/")
        .then()
            .statusCode(400);
    }

    // ── Issue #788: AWS::ApiGateway::Authorizer support + Method.AuthorizerId wiring ───

    @Test
    void createStack_withApiGatewayAuthorizer_createsAuthorizerResource() {
        // Regression test for issue #788: CFN previously fell through the default
        // stub branch for AWS::ApiGateway::Authorizer, so the resource never reached
        // ApiGatewayService and `get-authorizers` returned an empty list.
        String stackName = "cfn-apigw-auth-create-stack";
        String template = """
            {
              "Resources": {
                "RestApi": {
                  "Type": "AWS::ApiGateway::RestApi",
                  "Properties": {
                    "Name": "cfn-apigw-auth-create-api"
                  }
                },
                "MyAuthorizer": {
                  "Type": "AWS::ApiGateway::Authorizer",
                  "Properties": {
                    "Name": "MyTokenAuth",
                    "Type": "TOKEN",
                    "RestApiId": {"Ref": "RestApi"},
                    "AuthorizerUri": "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:my-auth/invocations",
                    "IdentitySource": "method.request.header.Authorization"
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

        String resourcesXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        String apiId = physicalIdByLogicalId(resourcesXml, "RestApi");
        String authorizerId = physicalIdByLogicalId(resourcesXml, "MyAuthorizer");

        // PhysicalResourceId must be the ApiGatewayService-issued authorizer id, not the logical name.
        assertThat(authorizerId, matchesRegex("^[A-Za-z0-9]+$"));
        assertThat(authorizerId, not(equalTo("MyAuthorizer")));

        // The authorizer must be retrievable from the standard REST endpoint.
        given()
        .when()
            .get("/restapis/" + apiId + "/authorizers")
        .then()
            .statusCode(200)
            .body("item.size()", equalTo(1))
            .body("item[0].id", equalTo(authorizerId))
            .body("item[0].name", equalTo("MyTokenAuth"))
            .body("item[0].type", equalTo("TOKEN"));
    }

    // ── Issue #1163: AWS::ApiGateway::Deployment with inline StageName creates the stage ──

    @Test
    void createStack_withApiGatewayDeploymentStageName_createsStage() {
        // Regression test for issue #1163: a Deployment carrying an inline StageName created
        // the deployment but never the stage, so `get-stages` returned empty and invoking
        // .../{stage}/_user_request_/... returned "Stage not found".
        String stackName = "cfn-apigw-deploy-stagename-stack";
        String template = """
            {
              "Resources": {
                "RestApi": {
                  "Type": "AWS::ApiGateway::RestApi",
                  "Properties": {
                    "Name": "cfn-apigw-deploy-stagename-api"
                  }
                },
                "Deployment": {
                  "Type": "AWS::ApiGateway::Deployment",
                  "Properties": {
                    "RestApiId": {"Ref": "RestApi"},
                    "StageName": "prod"
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

        String resourcesXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        String apiId = physicalIdByLogicalId(resourcesXml, "RestApi");
        String deploymentId = physicalIdByLogicalId(resourcesXml, "Deployment");

        // The inline StageName must have produced a real stage bound to this deployment.
        given()
        .when()
            .get("/restapis/" + apiId + "/stages")
        .then()
            .statusCode(200)
            .body("item.size()", equalTo(1))
            .body("item[0].stageName", equalTo("prod"))
            .body("item[0].deploymentId", equalTo(deploymentId));
    }

    @Test
    void createStack_withApiGatewayMethod_wiresAuthorizerIdViaRef() {
        // Core scenario from issue #788: `AuthorizationType: CUSTOM` plus
        // `AuthorizerId: !Ref MyAuthorizer` must produce a method whose
        // authorizerId points to the actual authorizer resource. Before the fix,
        // authorizerId was null on the method and the API behaved as `NONE`.
        String stackName = "cfn-apigw-auth-method-stack";
        String template = """
            {
              "Resources": {
                "RestApi": {
                  "Type": "AWS::ApiGateway::RestApi",
                  "Properties": {
                    "Name": "cfn-apigw-auth-method-api"
                  }
                },
                "ApiResource": {
                  "Type": "AWS::ApiGateway::Resource",
                  "Properties": {
                    "RestApiId": {"Ref": "RestApi"},
                    "ParentId": {"Fn::GetAtt": ["RestApi", "RootResourceId"]},
                    "PathPart": "secured"
                  }
                },
                "TokenAuthorizer": {
                  "Type": "AWS::ApiGateway::Authorizer",
                  "Properties": {
                    "Name": "MyTokenAuth",
                    "Type": "TOKEN",
                    "RestApiId": {"Ref": "RestApi"},
                    "AuthorizerUri": "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:my-auth/invocations",
                    "IdentitySource": "method.request.header.Authorization",
                    "AuthorizerResultTtlInSeconds": 0
                  }
                },
                "SecuredMethod": {
                  "Type": "AWS::ApiGateway::Method",
                  "Properties": {
                    "RestApiId": {"Ref": "RestApi"},
                    "ResourceId": {"Ref": "ApiResource"},
                    "HttpMethod": "GET",
                    "AuthorizationType": "CUSTOM",
                    "AuthorizerId": {"Ref": "TokenAuthorizer"}
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
            .statusCode(200);

        String resourcesXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        String apiId = physicalIdByLogicalId(resourcesXml, "RestApi");
        String resourceId = physicalIdByLogicalId(resourcesXml, "ApiResource");
        String authorizerId = physicalIdByLogicalId(resourcesXml, "TokenAuthorizer");

        given()
        .when()
            .get("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET")
        .then()
            .statusCode(200)
            .body("httpMethod", equalTo("GET"))
            .body("authorizationType", equalTo("CUSTOM"))
            .body("authorizerId", equalTo(authorizerId));
    }

    @Test
    void createStack_withApiGatewayAuthorizer_preservesAllFields() {
        // Reviewer-defence: every CFN-supported property on AWS::ApiGateway::Authorizer
        // round-trips through GET /restapis/{apiId}/authorizers, not just the Name.
        // Catches future regressions where a new field is added to the CFN handler
        // but not threaded into the service request map.
        String stackName = "cfn-apigw-auth-fields-stack";
        String template = """
            {
              "Resources": {
                "RestApi": {
                  "Type": "AWS::ApiGateway::RestApi",
                  "Properties": {"Name": "cfn-apigw-auth-fields-api"}
                },
                "TokenAuth": {
                  "Type": "AWS::ApiGateway::Authorizer",
                  "Properties": {
                    "Name": "FieldsTokenAuth",
                    "Type": "TOKEN",
                    "RestApiId": {"Ref": "RestApi"},
                    "AuthorizerUri": "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:fields-auth/invocations",
                    "IdentitySource": "method.request.header.X-Auth",
                    "AuthorizerResultTtlInSeconds": 120
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
            .statusCode(200);

        String resourcesXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        String apiId = physicalIdByLogicalId(resourcesXml, "RestApi");
        String authorizerId = physicalIdByLogicalId(resourcesXml, "TokenAuth");

        given()
        .when()
            .get("/restapis/" + apiId + "/authorizers/" + authorizerId)
        .then()
            .statusCode(200)
            .body("id", equalTo(authorizerId))
            .body("name", equalTo("FieldsTokenAuth"))
            .body("type", equalTo("TOKEN"))
            .body("authorizerUri", containsString("function:fields-auth"))
            .body("identitySource", equalTo("method.request.header.X-Auth"))
            .body("authorizerResultTtlInSeconds", equalTo(120));
    }

    @Test
    void createStack_withApiGatewayMethod_withoutAuthorizerId_authorizerIdRemainsNull() {
        // Backwards-compat: methods that omit AuthorizerId must NOT acquire one
        // accidentally (e.g. from a stale loop variable or default value). A
        // NONE-auth method whose authorizerId surfaces in GET is a contract leak
        // for SDK clients.
        String stackName = "cfn-apigw-method-noauth-stack";
        String template = """
            {
              "Resources": {
                "RestApi": {
                  "Type": "AWS::ApiGateway::RestApi",
                  "Properties": {"Name": "cfn-apigw-method-noauth-api"}
                },
                "PublicResource": {
                  "Type": "AWS::ApiGateway::Resource",
                  "Properties": {
                    "RestApiId": {"Ref": "RestApi"},
                    "ParentId": {"Fn::GetAtt": ["RestApi", "RootResourceId"]},
                    "PathPart": "public"
                  }
                },
                "PublicMethod": {
                  "Type": "AWS::ApiGateway::Method",
                  "Properties": {
                    "RestApiId": {"Ref": "RestApi"},
                    "ResourceId": {"Ref": "PublicResource"},
                    "HttpMethod": "GET",
                    "AuthorizationType": "NONE"
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
            .statusCode(200);

        String resourcesXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        String apiId = physicalIdByLogicalId(resourcesXml, "RestApi");
        String resourceId = physicalIdByLogicalId(resourcesXml, "PublicResource");

        given()
        .when()
            .get("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET")
        .then()
            .statusCode(200)
            .body("authorizationType", equalTo("NONE"))
            .body("authorizerId", nullValue());
    }

    @Test
    void createStack_withMultipleAuthorizers_eachWiredToOwnMethod() {
        // Multi-authorizer isolation: two authorizers + two methods, each method
        // must end up wired to the correct authorizer id. Prevents future
        // regressions where a shared local would leak across iterations of the
        // resource provisioning loop.
        String stackName = "cfn-apigw-multi-auth-stack";
        String template = """
            {
              "Resources": {
                "RestApi": {
                  "Type": "AWS::ApiGateway::RestApi",
                  "Properties": {"Name": "cfn-apigw-multi-auth-api"}
                },
                "FirstResource": {
                  "Type": "AWS::ApiGateway::Resource",
                  "Properties": {
                    "RestApiId": {"Ref": "RestApi"},
                    "ParentId": {"Fn::GetAtt": ["RestApi", "RootResourceId"]},
                    "PathPart": "first"
                  }
                },
                "SecondResource": {
                  "Type": "AWS::ApiGateway::Resource",
                  "Properties": {
                    "RestApiId": {"Ref": "RestApi"},
                    "ParentId": {"Fn::GetAtt": ["RestApi", "RootResourceId"]},
                    "PathPart": "second"
                  }
                },
                "FirstAuth": {
                  "Type": "AWS::ApiGateway::Authorizer",
                  "Properties": {
                    "Name": "FirstAuth",
                    "Type": "TOKEN",
                    "RestApiId": {"Ref": "RestApi"},
                    "AuthorizerUri": "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:first-auth/invocations",
                    "IdentitySource": "method.request.header.Authorization"
                  }
                },
                "SecondAuth": {
                  "Type": "AWS::ApiGateway::Authorizer",
                  "Properties": {
                    "Name": "SecondAuth",
                    "Type": "TOKEN",
                    "RestApiId": {"Ref": "RestApi"},
                    "AuthorizerUri": "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/arn:aws:lambda:us-east-1:000000000000:function:second-auth/invocations",
                    "IdentitySource": "method.request.header.X-Token"
                  }
                },
                "FirstMethod": {
                  "Type": "AWS::ApiGateway::Method",
                  "Properties": {
                    "RestApiId": {"Ref": "RestApi"},
                    "ResourceId": {"Ref": "FirstResource"},
                    "HttpMethod": "GET",
                    "AuthorizationType": "CUSTOM",
                    "AuthorizerId": {"Ref": "FirstAuth"}
                  }
                },
                "SecondMethod": {
                  "Type": "AWS::ApiGateway::Method",
                  "Properties": {
                    "RestApiId": {"Ref": "RestApi"},
                    "ResourceId": {"Ref": "SecondResource"},
                    "HttpMethod": "POST",
                    "AuthorizationType": "CUSTOM",
                    "AuthorizerId": {"Ref": "SecondAuth"}
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
            .statusCode(200);

        String resourcesXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        String apiId = physicalIdByLogicalId(resourcesXml, "RestApi");
        String firstResource = physicalIdByLogicalId(resourcesXml, "FirstResource");
        String secondResource = physicalIdByLogicalId(resourcesXml, "SecondResource");
        String firstAuth = physicalIdByLogicalId(resourcesXml, "FirstAuth");
        String secondAuth = physicalIdByLogicalId(resourcesXml, "SecondAuth");

        assertThat(firstAuth, not(equalTo(secondAuth)));

        given()
        .when()
            .get("/restapis/" + apiId + "/resources/" + firstResource + "/methods/GET")
        .then()
            .statusCode(200)
            .body("authorizationType", equalTo("CUSTOM"))
            .body("authorizerId", equalTo(firstAuth));

        given()
        .when()
            .get("/restapis/" + apiId + "/resources/" + secondResource + "/methods/POST")
        .then()
            .statusCode(200)
            .body("authorizationType", equalTo("CUSTOM"))
            .body("authorizerId", equalTo(secondAuth));
    }

    @Test
    void createStack_snsSqsFifoWithContentBasedDeduplicationAndSubscription() {
        String stackName = "cfn-sns-sqs-fifo-stack";
        String template = """
            {
              "Resources": {
                "MyTopic": {
                  "Type": "AWS::SNS::Topic",
                  "Properties": {
                    "TopicName": "cfn-test-topic.fifo",
                    "ContentBasedDeduplication": true
                  }
                },
                "MyQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "cfn-test-queue.fifo",
                    "FifoQueue": true,
                    "ContentBasedDeduplication": true
                  }
                },
                "MySubscription": {
                  "Type": "AWS::SNS::Subscription",
                  "Properties": {
                    "TopicArn": {"Ref": "MyTopic"},
                    "Protocol": "sqs",
                    "Endpoint": {"Fn::GetAtt": ["MyQueue", "Arn"]},
                    "RawMessageDelivery": true
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
            .statusCode(200);

        // 1. Verify SNS Topic attributes
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTopicAttributes")
            .formParam("TopicArn", "arn:aws:sns:us-east-1:000000000000:cfn-test-topic.fifo")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("ContentBasedDeduplication"))
            .body(containsString("<value>true</value>"))
            .body(containsString("FifoTopic"));

        // 2. Verify SQS Queue attributes
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueAttributes")
            .formParam("QueueUrl", "http://localhost:4566/000000000000/cfn-test-queue.fifo")
            .formParam("AttributeName.1", "All")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("ContentBasedDeduplication"))
            .body(containsString("<Value>true</Value>"))
            .body(containsString("FifoQueue"));

        // 3. Verify SNS Subscription attributes
        String resourcesXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        String subArn = physicalIdByLogicalId(resourcesXml, "MySubscription");

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetSubscriptionAttributes")
            .formParam("SubscriptionArn", subArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("RawMessageDelivery"))
            .body(containsString("<value>true</value>"))
            .body(containsString("sqs"));
    }

    @Test
    void createStack_snsSubscriptionWithFilterPolicyAndRedrivePolicy() {
        String stackName = "cfn-sns-sub-policies-stack";
        String template = """
            {
              "Resources": {
                "MyTopic": {
                  "Type": "AWS::SNS::Topic",
                  "Properties": {
                    "TopicName": "cfn-sub-policies-topic"
                  }
                },
                "MyQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "cfn-sub-policies-queue"
                  }
                },
                "MyDLQ": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "cfn-sub-policies-dlq"
                  }
                },
                "MySubscription": {
                  "Type": "AWS::SNS::Subscription",
                  "Properties": {
                    "TopicArn": {"Ref": "MyTopic"},
                    "Protocol": "sqs",
                    "Endpoint": {"Fn::GetAtt": ["MyQueue", "Arn"]},
                    "FilterPolicy": {
                      "price_usd": [{"numeric": [">=", 100]}]
                    },
                    "RedrivePolicy": {
                      "deadLetterTargetArn": {"Fn::GetAtt": ["MyDLQ", "Arn"]}
                    }
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
            .statusCode(200);

        String resourcesXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        String subArn = physicalIdByLogicalId(resourcesXml, "MySubscription");

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetSubscriptionAttributes")
            .formParam("SubscriptionArn", subArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("FilterPolicy"))
            .body(containsString("price_usd"))
            .body(containsString("RedrivePolicy"))
            .body(containsString("deadLetterTargetArn"))
            .body(containsString("cfn-sub-policies-dlq"));
    }

    @Test
    void createStack_withCognitoUserPoolAndClient() {
        String template = """
            {
              "Resources": {
                "UserPool": {
                  "Type": "AWS::Cognito::UserPool",
                  "Properties": {
                    "UserPoolName": "cfn-test-pool",
                    "UserPoolTags": [
                      { "Key": "env", "Value": "test" },
                      { "Key": "team", "Value": "auth" }
                    ]
                  }
                },
                "UserPoolClient": {
                  "Type": "AWS::Cognito::UserPoolClient",
                  "Properties": {
                    "ClientName": "cfn-test-client",
                    "UserPoolId": { "Ref": "UserPool" },
                    "GenerateSecret": true
                  }
                }
              },
              "Outputs": {
                "PoolId": { "Value": { "Ref": "UserPool" } },
                "PoolArn": { "Value": { "Fn::GetAtt": ["UserPool", "Arn"] } },
                "ClientId": { "Value": { "Ref": "UserPoolClient" } }
              }
            }
            """;

        String stackName = "cognito-test-stack";

        // 1. Create Stack
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

        // 2. Describe Stacks and capture outputs
        String describeXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .extract().asString();

        String poolId = describeXml.split("<OutputKey>PoolId</OutputKey>")[1].split("<OutputValue>")[1].split("</OutputValue>")[0];
        String poolArn = describeXml.split("<OutputKey>PoolArn</OutputKey>")[1].split("<OutputValue>")[1].split("</OutputValue>")[0];
        String clientId = describeXml.split("<OutputKey>ClientId</OutputKey>")[1].split("<OutputValue>")[1].split("</OutputValue>")[0];

        assertThat(poolId, startsWith("us-east-1_"));
        assertThat(poolArn, startsWith("arn:aws:cognito-idp:"));
        assertThat(clientId, notNullValue());

        // 3. Verify UserPool via Cognito API
        given()
            .header("X-Amz-Target", "AWSCognitoIdentityProviderService.DescribeUserPool")
            .contentType(COGNITO_CONTENT_TYPE)
            .body("{\"UserPoolId\": \"" + poolId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("UserPool.Name", equalTo("cfn-test-pool"))
            .body("UserPool.UserPoolTags.env", equalTo("test"))
            .body("UserPool.UserPoolTags.team", equalTo("auth"));

        // 4. Verify UserPoolClient via Cognito API
        given()
            .header("X-Amz-Target", "AWSCognitoIdentityProviderService.DescribeUserPoolClient")
            .contentType(COGNITO_CONTENT_TYPE)
            .body("{\"UserPoolId\": \"" + poolId + "\", \"ClientId\": \"" + clientId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("UserPoolClient.ClientName", equalTo("cfn-test-client"))
            .body("UserPoolClient.UserPoolId", equalTo(poolId))
            .body("UserPoolClient.ClientSecret", notNullValue());

        // 5. Delete Stack
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // 6. Verify resources are deleted
        given()
            .header("X-Amz-Target", "AWSCognitoIdentityProviderService.DescribeUserPool")
            .contentType(COGNITO_CONTENT_TYPE)
            .body("{\"UserPoolId\": \"" + poolId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(404);

        given()
            .header("X-Amz-Target", "AWSCognitoIdentityProviderService.DescribeUserPoolClient")
            .contentType(COGNITO_CONTENT_TYPE)
            .body("{\"UserPoolId\": \"" + poolId + "\", \"ClientId\": \"" + clientId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(404);
    }

    @Test
    void createStack_withNestedStack_resourcesAreProvisioned() {
        String childTemplate = """
            {
              "Resources": {
                "ChildQueue": {
                  "Type": "AWS::SQS::Queue",
                  "Properties": {
                    "QueueName": "nested-stack-child-queue"
                  }
                }
              },
              "Outputs": {
                "QueueUrl": {
                  "Value": {"Ref": "ChildQueue"}
                }
              }
            }
            """;

        // Upload child template to S3
        given().when().put("/nested-stack-templates").then().statusCode(200);
        given()
            .contentType("application/json")
            .body(childTemplate)
        .when()
            .put("/nested-stack-templates/child.json")
        .then()
            .statusCode(200);

        String parentTemplate = """
            {
              "Resources": {
                "ChildStack": {
                  "Type": "AWS::CloudFormation::Stack",
                  "Properties": {
                    "TemplateURL": "http://localhost/nested-stack-templates/child.json"
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "parent-nested-stack")
            .formParam("TemplateBody", parentTemplate)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        // Parent stack reaches CREATE_COMPLETE
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "parent-nested-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        // Nested stack resource itself is CREATE_COMPLETE
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", "parent-nested-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ResourceType>AWS::CloudFormation::Stack</ResourceType>"))
            .body(containsString("<ResourceStatus>CREATE_COMPLETE</ResourceStatus>"));

        // SQS queue defined in the nested template actually exists
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", "nested-stack-child-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("nested-stack-child-queue"));
    }

    // ── Issue #1072: AWS::ApiGatewayV2::Api WEBSOCKET drops RouteSelectionExpression ───

    @Test
    void createStack_apiGatewayV2WebSocketApi_forwardsRouteSelectionExpression() {
        String template = """
            {
              "Resources": {
                "MyWsApi": {
                  "Type": "AWS::ApiGatewayV2::Api",
                  "Properties": {
                    "Name": "cfn-ws-api",
                    "ProtocolType": "WEBSOCKET",
                    "RouteSelectionExpression": "$request.body.action",
                    "Description": "ws api created via cfn",
                    "ApiKeySelectionExpression": "$request.header.x-custom-key",
                    "CorsConfiguration": {
                      "AllowOrigins": ["https://example.com"],
                      "AllowMethods": ["GET", "POST"],
                      "AllowHeaders": ["content-type"],
                      "ExposeHeaders": ["x-request-id"],
                      "MaxAge": 600,
                      "AllowCredentials": true
                    },
                    "Tags": {
                      "Environment": "test",
                      "Owner": "floci"
                    }
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "ws-api-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", "ws-api-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<LogicalResourceId>MyWsApi</LogicalResourceId>"))
            .body(containsString("<ResourceStatus>CREATE_COMPLETE</ResourceStatus>"))
            .body(not(containsString("CREATE_FAILED")));

        String apisJson = given()
            .header("X-Amz-Target", "AmazonApiGatewayV2.GetApis")
            .contentType("application/x-amz-json-1.1")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(apisJson, containsString("\"Name\":\"cfn-ws-api\""));
        assertThat(apisJson, containsString("\"ProtocolType\":\"WEBSOCKET\""));
        assertThat(apisJson, containsString("\"RouteSelectionExpression\":\"$request.body.action\""));
        assertThat(apisJson, containsString("\"Description\":\"ws api created via cfn\""));
        assertThat(apisJson, containsString("\"ApiKeySelectionExpression\":\"$request.header.x-custom-key\""));
        assertThat(apisJson, containsString("\"Environment\":\"test\""));
        assertThat(apisJson, containsString("\"Owner\":\"floci\""));
        assertThat(apisJson, containsString("\"AllowOrigins\":[\"https://example.com\"]"));
        assertThat(apisJson, containsString("\"AllowMethods\":[\"GET\",\"POST\"]"));
        assertThat(apisJson, containsString("\"AllowHeaders\":[\"content-type\"]"));
        assertThat(apisJson, containsString("\"ExposeHeaders\":[\"x-request-id\"]"));
        assertThat(apisJson, containsString("\"MaxAge\":600"));
        assertThat(apisJson, containsString("\"AllowCredentials\":true"));
    }

    // ── Issue #924: ECS provisioning via CloudFormation ──────────────────────

    private static final String ECS_TARGET_PREFIX = "AmazonEC2ContainerServiceV20141113.";
    private static final String ECS_CONTENT_TYPE = "application/x-amz-json-1.1";

    private static String outputValue(String describeXml, String key) {
        return describeXml.split("<OutputKey>" + key + "</OutputKey>")[1]
                .split("<OutputValue>")[1].split("</OutputValue>")[0];
    }

    @Test
    void createStack_withEcsClusterTaskDefAndService() {
        String template = """
            {
              "Resources": {
                "EcsCluster": {
                  "Type": "AWS::ECS::Cluster",
                  "Properties": { "ClusterName": "cfn-ecs-cluster" }
                },
                "TaskDef": {
                  "Type": "AWS::ECS::TaskDefinition",
                  "Properties": {
                    "Family": "cfn-ecs-taskdef",
                    "Cpu": "256",
                    "Memory": "512",
                    "NetworkMode": "awsvpc",
                    "TaskRoleArn": "arn:aws:iam::000000000000:role/cfn-ecs-task-role",
                    "ExecutionRoleArn": "arn:aws:iam::000000000000:role/cfn-ecs-exec-role",
                    "ContainerDefinitions": [
                      {
                        "Name": "web",
                        "Image": "nginx:latest",
                        "Essential": true,
                        "Cpu": 128,
                        "Memory": 256,
                        "PortMappings": [ { "ContainerPort": 80, "Protocol": "tcp" } ],
                        "Environment": [ { "Name": "STAGE", "Value": "test" } ]
                      }
                    ]
                  }
                },
                "EcsService": {
                  "Type": "AWS::ECS::Service",
                  "Properties": {
                    "ServiceName": "cfn-ecs-service",
                    "Cluster": { "Ref": "EcsCluster" },
                    "TaskDefinition": { "Ref": "TaskDef" },
                    "DesiredCount": 2,
                    "LaunchType": "FARGATE",
                    "NetworkConfiguration": {
                      "AwsvpcConfiguration": {
                        "Subnets": ["subnet-aaa", "subnet-bbb"],
                        "SecurityGroups": ["sg-123"],
                        "AssignPublicIp": "ENABLED"
                      }
                    }
                  }
                }
              },
              "Outputs": {
                "ClusterRef": { "Value": { "Ref": "EcsCluster" } },
                "ClusterArn": { "Value": { "Fn::GetAtt": ["EcsCluster", "Arn"] } },
                "TaskDefRef": { "Value": { "Ref": "TaskDef" } },
                "ServiceRef": { "Value": { "Ref": "EcsService" } },
                "ServiceName": { "Value": { "Fn::GetAtt": ["EcsService", "Name"] } },
                "ServiceArn": { "Value": { "Fn::GetAtt": ["EcsService", "ServiceArn"] } }
              }
            }
            """;

        String stackName = "cfn-ecs-stack";

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

        String describeXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .extract().asString();

        // Ref/GetAtt parity with AWS CloudFormation:
        //  - Cluster Ref = name, GetAtt Arn = ARN
        //  - TaskDefinition Ref = full ARN including revision
        //  - Service Ref = ARN; GetAtt Name = service name, GetAtt ServiceArn = ARN
        assertThat(outputValue(describeXml, "ClusterRef"), equalTo("cfn-ecs-cluster"));
        assertThat(outputValue(describeXml, "ClusterArn"),
                equalTo("arn:aws:ecs:us-east-1:000000000000:cluster/cfn-ecs-cluster"));
        assertThat(outputValue(describeXml, "TaskDefRef"),
                equalTo("arn:aws:ecs:us-east-1:000000000000:task-definition/cfn-ecs-taskdef:1"));
        String serviceArn = "arn:aws:ecs:us-east-1:000000000000:service/cfn-ecs-cluster/cfn-ecs-service";
        assertThat(outputValue(describeXml, "ServiceRef"), equalTo(serviceArn));
        assertThat(outputValue(describeXml, "ServiceArn"), equalTo(serviceArn));
        assertThat(outputValue(describeXml, "ServiceName"), equalTo("cfn-ecs-service"));

        // Task definition carries role ARNs (Part 5a) and container definitions
        given()
            .header("X-Amz-Target", ECS_TARGET_PREFIX + "DescribeTaskDefinition")
            .contentType(ECS_CONTENT_TYPE)
            .body("{\"taskDefinition\": \"cfn-ecs-taskdef\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("taskDefinition.family", equalTo("cfn-ecs-taskdef"))
            .body("taskDefinition.networkMode", equalTo("awsvpc"))
            .body("taskDefinition.taskRoleArn", equalTo("arn:aws:iam::000000000000:role/cfn-ecs-task-role"))
            .body("taskDefinition.executionRoleArn", equalTo("arn:aws:iam::000000000000:role/cfn-ecs-exec-role"))
            .body("taskDefinition.containerDefinitions[0].name", equalTo("web"))
            .body("taskDefinition.containerDefinitions[0].image", equalTo("nginx:latest"))
            .body("taskDefinition.containerDefinitions[0].portMappings[0].containerPort", equalTo(80))
            .body("taskDefinition.containerDefinitions[0].environment[0].name", equalTo("STAGE"));

        // Service carries desiredCount and network configuration (Part 5b)
        given()
            .header("X-Amz-Target", ECS_TARGET_PREFIX + "DescribeServices")
            .contentType(ECS_CONTENT_TYPE)
            .body("{\"cluster\": \"cfn-ecs-cluster\", \"services\": [\"cfn-ecs-service\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("services[0].serviceName", equalTo("cfn-ecs-service"))
            .body("services[0].desiredCount", equalTo(2))
            .body("services[0].launchType", equalTo("FARGATE"))
            .body("services[0].networkConfiguration.awsvpcConfiguration.subnets", hasItem("subnet-aaa"))
            .body("services[0].networkConfiguration.awsvpcConfiguration.securityGroups", hasItem("sg-123"))
            .body("services[0].networkConfiguration.awsvpcConfiguration.assignPublicIp", equalTo("ENABLED"));
    }

    @Test
    void updateStack_ecsService_registersNewTaskDefRevisionAndUpdatesDesiredCount() {
        String stackName = "cfn-ecs-update-stack";
        String template = """
            {
              "Resources": {
                "EcsCluster": {
                  "Type": "AWS::ECS::Cluster",
                  "Properties": { "ClusterName": "cfn-ecs-update-cluster" }
                },
                "TaskDef": {
                  "Type": "AWS::ECS::TaskDefinition",
                  "Properties": {
                    "Family": "cfn-ecs-update-taskdef",
                    "ContainerDefinitions": [
                      { "Name": "app", "Image": "%s", "Essential": true }
                    ]
                  }
                },
                "EcsService": {
                  "Type": "AWS::ECS::Service",
                  "Properties": {
                    "ServiceName": "cfn-ecs-update-service",
                    "Cluster": { "Ref": "EcsCluster" },
                    "TaskDefinition": { "Ref": "TaskDef" },
                    "DesiredCount": %d,
                    "LaunchType": "FARGATE"
                  }
                }
              },
              "Outputs": {
                "TaskDefRef": { "Value": { "Ref": "TaskDef" } }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template.formatted("app:v1", 1))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String createXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
        assertThat(outputValue(createXml, "TaskDefRef"),
                equalTo("arn:aws:ecs:us-east-1:000000000000:task-definition/cfn-ecs-update-taskdef:1"));

        // Update: new container image registers a fresh revision; desiredCount changes 1 -> 3
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template.formatted("app:v2", 3))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String updateXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
        assertThat(outputValue(updateXml, "TaskDefRef"),
                equalTo("arn:aws:ecs:us-east-1:000000000000:task-definition/cfn-ecs-update-taskdef:2"));

        given()
            .header("X-Amz-Target", ECS_TARGET_PREFIX + "DescribeServices")
            .contentType(ECS_CONTENT_TYPE)
            .body("{\"cluster\": \"cfn-ecs-update-cluster\", \"services\": [\"cfn-ecs-update-service\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("services[0].desiredCount", equalTo(3))
            .body("services[0].taskDefinition",
                    equalTo("arn:aws:ecs:us-east-1:000000000000:task-definition/cfn-ecs-update-taskdef:2"));
    }

    @Test
    void deleteStack_ecs_leavesNoOrphans() {
        String stackName = "cfn-ecs-delete-stack";
        String template = """
            {
              "Resources": {
                "EcsCluster": {
                  "Type": "AWS::ECS::Cluster",
                  "Properties": { "ClusterName": "cfn-ecs-delete-cluster" }
                },
                "TaskDef": {
                  "Type": "AWS::ECS::TaskDefinition",
                  "Properties": {
                    "Family": "cfn-ecs-delete-taskdef",
                    "ContainerDefinitions": [
                      { "Name": "app", "Image": "app:latest", "Essential": true }
                    ]
                  }
                },
                "EcsService": {
                  "Type": "AWS::ECS::Service",
                  "Properties": {
                    "ServiceName": "cfn-ecs-delete-service",
                    "Cluster": { "Ref": "EcsCluster" },
                    "TaskDefinition": { "Ref": "TaskDef" },
                    "DesiredCount": 0,
                    "LaunchType": "FARGATE"
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
            .statusCode(200);

        // Sanity: cluster exists before delete
        given()
            .header("X-Amz-Target", ECS_TARGET_PREFIX + "DescribeClusters")
            .contentType(ECS_CONTENT_TYPE)
            .body("{\"clusters\": [\"cfn-ecs-delete-cluster\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("clusters[0].clusterName", equalTo("cfn-ecs-delete-cluster"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Cluster is gone (deleted in reverse order, after the service)
        given()
            .header("X-Amz-Target", ECS_TARGET_PREFIX + "DescribeClusters")
            .contentType(ECS_CONTENT_TYPE)
            .body("{\"clusters\": [\"cfn-ecs-delete-cluster\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("clusters", org.hamcrest.Matchers.empty());

        // Task definition is deregistered (INACTIVE)
        given()
            .header("X-Amz-Target", ECS_TARGET_PREFIX + "DescribeTaskDefinition")
            .contentType(ECS_CONTENT_TYPE)
            .body("{\"taskDefinition\": \"cfn-ecs-delete-taskdef\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("taskDefinition.status", equalTo("INACTIVE"));
    }

    // ── Issue #924: ELBv2 provisioning via CloudFormation ────────────────────

    private static final String ELB_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260601/us-east-1/elasticloadbalancing/aws4_request";

    private static String cfnOutputValue(String describeXml, String key) {
        return describeXml.split("<OutputKey>" + key + "</OutputKey>")[1]
                .split("<OutputValue>")[1].split("</OutputValue>")[0];
    }

    @Test
    void createStack_withElbV2LoadBalancerTargetGroupListenerRule() {
        String template = """
            {
              "Resources": {
                "Alb": {
                  "Type": "AWS::ElasticLoadBalancingV2::LoadBalancer",
                  "Properties": {
                    "Name": "cfn-alb",
                    "Type": "application",
                    "Scheme": "internet-facing",
                    "Subnets": ["subnet-default-a", "subnet-default-b"],
                    "SecurityGroups": ["sg-123"]
                  }
                },
                "Tg": {
                  "Type": "AWS::ElasticLoadBalancingV2::TargetGroup",
                  "Properties": {
                    "Name": "cfn-tg",
                    "Protocol": "HTTP",
                    "Port": 80,
                    "VpcId": "vpc-default",
                    "TargetType": "ip",
                    "HealthCheckPath": "/health",
                    "Matcher": { "HttpCode": "200-299" }
                  }
                },
                "Listener": {
                  "Type": "AWS::ElasticLoadBalancingV2::Listener",
                  "Properties": {
                    "LoadBalancerArn": { "Ref": "Alb" },
                    "Protocol": "HTTP",
                    "Port": 80,
                    "DefaultActions": [
                      { "Type": "forward", "TargetGroupArn": { "Ref": "Tg" } }
                    ]
                  }
                },
                "Rule": {
                  "Type": "AWS::ElasticLoadBalancingV2::ListenerRule",
                  "Properties": {
                    "ListenerArn": { "Ref": "Listener" },
                    "Priority": 10,
                    "Conditions": [
                      { "Field": "path-pattern", "PathPatternConfig": { "Values": ["/api/*"] } }
                    ],
                    "Actions": [
                      { "Type": "forward", "TargetGroupArn": { "Ref": "Tg" } }
                    ]
                  }
                }
              },
              "Outputs": {
                "AlbRef": { "Value": { "Ref": "Alb" } },
                "AlbDns": { "Value": { "Fn::GetAtt": ["Alb", "DNSName"] } },
                "AlbFullName": { "Value": { "Fn::GetAtt": ["Alb", "LoadBalancerFullName"] } },
                "AlbCanonical": { "Value": { "Fn::GetAtt": ["Alb", "CanonicalHostedZoneID"] } },
                "TgRef": { "Value": { "Ref": "Tg" } },
                "TgFullName": { "Value": { "Fn::GetAtt": ["Tg", "TargetGroupFullName"] } },
                "TgName": { "Value": { "Fn::GetAtt": ["Tg", "TargetGroupName"] } },
                "ListenerRef": { "Value": { "Ref": "Listener" } },
                "RuleRef": { "Value": { "Ref": "Rule" } }
              }
            }
            """;

        String stackName = "cfn-elbv2-stack";

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

        String describeXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .extract().asString();

        // Ref/GetAtt parity: every ELBv2 resource's Ref is its ARN.
        String albArn = cfnOutputValue(describeXml, "AlbRef");
        assertThat(albArn, startsWith(
                "arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/app/cfn-alb/"));
        assertThat(cfnOutputValue(describeXml, "AlbDns"), containsString(".elb.localhost"));
        assertThat(cfnOutputValue(describeXml, "AlbFullName"), startsWith("app/cfn-alb/"));
        assertThat(cfnOutputValue(describeXml, "AlbCanonical"), notNullValue());
        String tgArn = cfnOutputValue(describeXml, "TgRef");
        assertThat(tgArn, startsWith(
                "arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/cfn-tg/"));
        assertThat(cfnOutputValue(describeXml, "TgFullName"), startsWith("targetgroup/cfn-tg/"));
        assertThat(cfnOutputValue(describeXml, "TgName"), equalTo("cfn-tg"));
        assertThat(cfnOutputValue(describeXml, "ListenerRef"), startsWith(
                "arn:aws:elasticloadbalancing:us-east-1:000000000000:listener/app/cfn-alb/"));
        assertThat(cfnOutputValue(describeXml, "RuleRef"), startsWith(
                "arn:aws:elasticloadbalancing:us-east-1:000000000000:listener-rule/app/cfn-alb/"));

        // Load balancer is live in the ELBv2 service
        given()
            .formParam("Action", "DescribeLoadBalancers")
            .formParam("Names.member.1", "cfn-alb")
            .header("Authorization", ELB_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.LoadBalancerArn",
                    equalTo(albArn))
            .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.Type",
                    equalTo("application"));

        // Target group carries its health check configuration
        given()
            .formParam("Action", "DescribeTargetGroups")
            .formParam("Names.member.1", "cfn-tg")
            .header("Authorization", ELB_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeTargetGroupsResponse.DescribeTargetGroupsResult.TargetGroups.member.TargetGroupArn",
                    equalTo(tgArn))
            .body("DescribeTargetGroupsResponse.DescribeTargetGroupsResult.TargetGroups.member.HealthCheckPath",
                    equalTo("/health"))
            .body("DescribeTargetGroupsResponse.DescribeTargetGroupsResult.TargetGroups.member.Port",
                    equalTo("80"));

        // Listener exists on port 80
        given()
            .formParam("Action", "DescribeListeners")
            .formParam("LoadBalancerArn", albArn)
            .header("Authorization", ELB_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeListenersResponse.DescribeListenersResult.Listeners.member.Port", equalTo("80"))
            .body("DescribeListenersResponse.DescribeListenersResult.Listeners.member.Protocol", equalTo("HTTP"));

        // The explicit listener rule (priority 10, path-pattern /api/*) was created
        String rulesXml = given()
            .formParam("Action", "DescribeRules")
            .formParam("ListenerArn", cfnOutputValue(describeXml, "ListenerRef"))
            .header("Authorization", ELB_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
        assertThat(rulesXml, containsString("<Priority>10</Priority>"));
        assertThat(rulesXml, containsString("/api/*"));
    }

    @Test
    void updateStack_elbV2Listener_modifiesPort() {
        String stackName = "cfn-elbv2-update-stack";
        String template = """
            {
              "Resources": {
                "Alb": {
                  "Type": "AWS::ElasticLoadBalancingV2::LoadBalancer",
                  "Properties": { "Name": "cfn-upd-alb", "Type": "application" }
                },
                "Listener": {
                  "Type": "AWS::ElasticLoadBalancingV2::Listener",
                  "Properties": {
                    "LoadBalancerArn": { "Ref": "Alb" },
                    "Protocol": "HTTP",
                    "Port": %d,
                    "DefaultActions": [
                      {
                        "Type": "fixed-response",
                        "FixedResponseConfig": {
                          "StatusCode": "200",
                          "ContentType": "text/plain",
                          "MessageBody": "ok"
                        }
                      }
                    ]
                  }
                }
              },
              "Outputs": {
                "AlbRef": { "Value": { "Ref": "Alb" } }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template.formatted(80))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String createXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
        String albArn = cfnOutputValue(createXml, "AlbRef");

        given()
            .formParam("Action", "DescribeListeners")
            .formParam("LoadBalancerArn", albArn)
            .header("Authorization", ELB_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeListenersResponse.DescribeListenersResult.Listeners.member.Port", equalTo("80"));

        // Update: change the listener port 80 -> 8080 (criterion #7, listener modify path)
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template.formatted(8080))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeListeners")
            .formParam("LoadBalancerArn", albArn)
            .header("Authorization", ELB_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeListenersResponse.DescribeListenersResult.Listeners.member.Port", equalTo("8080"));
    }

    @Test
    void deleteStack_elbV2_leavesNoOrphans() {
        // Declares the load balancer before the target group so teardown deletes the target
        // group (reverse order) while the load balancer still exists — exercising the
        // listener/rule target-group unlink so the target group is not left orphaned.
        String stackName = "cfn-elbv2-delete-stack";
        String template = """
            {
              "Resources": {
                "Alb": {
                  "Type": "AWS::ElasticLoadBalancingV2::LoadBalancer",
                  "Properties": { "Name": "cfn-del-alb", "Type": "application" }
                },
                "Tg": {
                  "Type": "AWS::ElasticLoadBalancingV2::TargetGroup",
                  "Properties": {
                    "Name": "cfn-del-tg",
                    "Protocol": "HTTP",
                    "Port": 80,
                    "VpcId": "vpc-00000001",
                    "TargetType": "ip"
                  }
                },
                "Listener": {
                  "Type": "AWS::ElasticLoadBalancingV2::Listener",
                  "Properties": {
                    "LoadBalancerArn": { "Ref": "Alb" },
                    "Protocol": "HTTP",
                    "Port": 80,
                    "DefaultActions": [
                      { "Type": "forward", "TargetGroupArn": { "Ref": "Tg" } }
                    ]
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
            .statusCode(200);

        // Sanity: target group exists before delete
        given()
            .formParam("Action", "DescribeTargetGroups")
            .formParam("Names.member.1", "cfn-del-tg")
            .header("Authorization", ELB_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeTargetGroupsResponse.DescribeTargetGroupsResult.TargetGroups.member.TargetGroupName",
                    equalTo("cfn-del-tg"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Load balancer is gone
        given()
            .formParam("Action", "DescribeLoadBalancers")
            .formParam("Names.member.1", "cfn-del-alb")
            .header("Authorization", ELB_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("LoadBalancerNotFound"));

        // Target group is gone too — not left orphaned during teardown
        String tgXml = given()
            .formParam("Action", "DescribeTargetGroups")
            .header("Authorization", ELB_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
        assertThat(tgXml, not(containsString("cfn-del-tg")));
    }


    // ── Issue #924: ApiGatewayV2 CloudFormation update path ──────────────────

    private static final String APIGWV2_CONTENT_TYPE = "application/x-amz-json-1.1";

    private static String apigwOutputValue(String describeXml, String key) {
        return describeXml.split("<OutputKey>" + key + "</OutputKey>")[1]
                .split("<OutputValue>")[1].split("</OutputValue>")[0];
    }

    private String apigwv2DescribeStacks(String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("_COMPLETE"))
            .body(not(containsString("FAILED")))
            .extract().asString();
    }

    @Test
    void updateStack_apiGatewayV2_updatesInPlaceWithoutDuplicating() {
        // Template parameterised over: api name, integration uri, route key, stage autoDeploy.
        String template = """
            {
              "Resources": {
                "HttpApi": {
                  "Type": "AWS::ApiGatewayV2::Api",
                  "Properties": { "Name": "%s", "ProtocolType": "HTTP" }
                },
                "Integration": {
                  "Type": "AWS::ApiGatewayV2::Integration",
                  "Properties": {
                    "ApiId": { "Ref": "HttpApi" },
                    "IntegrationType": "HTTP_PROXY",
                    "IntegrationUri": "%s",
                    "PayloadFormatVersion": "1.0"
                  }
                },
                "Route": {
                  "Type": "AWS::ApiGatewayV2::Route",
                  "Properties": {
                    "ApiId": { "Ref": "HttpApi" },
                    "RouteKey": "%s",
                    "Target": { "Fn::Join": ["/", ["integrations", { "Ref": "Integration" }]] }
                  }
                },
                "Stage": {
                  "Type": "AWS::ApiGatewayV2::Stage",
                  "Properties": {
                    "ApiId": { "Ref": "HttpApi" },
                    "StageName": "dev",
                    "AutoDeploy": %s
                  }
                }
              },
              "Outputs": {
                "ApiId": { "Value": { "Ref": "HttpApi" } },
                "IntegrationId": { "Value": { "Ref": "Integration" } },
                "RouteId": { "Value": { "Ref": "Route" } }
              }
            }
            """;

        String stackName = "cfn-apigwv2-update-stack";

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody",
                    template.formatted("cfn-apigwv2-api", "https://example.com/v1", "GET /items", "false"))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String createXml = apigwv2DescribeStacks(stackName);
        String apiId = apigwOutputValue(createXml, "ApiId");
        String integrationId = apigwOutputValue(createXml, "IntegrationId");
        String routeId = apigwOutputValue(createXml, "RouteId");

        // Baseline: exactly one route / integration / stage on the API
        getRoutes(apiId).body("Items.size()", equalTo(1))
                .body("Items[0].RouteKey", equalTo("GET /items"));
        getIntegrations(apiId).body("Items.size()", equalTo(1))
                .body("Items[0].IntegrationUri", equalTo("https://example.com/v1"));
        getStages(apiId).body("Items.size()", equalTo(1))
                .body("Items[0].AutoDeploy", equalTo(false));

        // Update: change api name, integration uri, route key, and stage autoDeploy
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody",
                    template.formatted("cfn-apigwv2-api-v2", "https://example.com/v2", "GET /things", "true"))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String updateXml = apigwv2DescribeStacks(stackName);
        // Physical IDs are stable across the update (in-place patch, not replacement)
        assertThat(apigwOutputValue(updateXml, "ApiId"), equalTo(apiId));
        assertThat(apigwOutputValue(updateXml, "IntegrationId"), equalTo(integrationId));
        assertThat(apigwOutputValue(updateXml, "RouteId"), equalTo(routeId));

        // Still exactly one of each — updated in place, not duplicated (criteria #6, #3)
        getRoutes(apiId).body("Items.size()", equalTo(1))
                .body("Items[0].RouteId", equalTo(routeId))
                .body("Items[0].RouteKey", equalTo("GET /things"));
        getIntegrations(apiId).body("Items.size()", equalTo(1))
                .body("Items[0].IntegrationId", equalTo(integrationId))
                .body("Items[0].IntegrationUri", equalTo("https://example.com/v2"));
        getStages(apiId).body("Items.size()", equalTo(1))
                .body("Items[0].AutoDeploy", equalTo(true));

        // The API name was updated, and there is still exactly one matching API
        given()
            .header("X-Amz-Target", "AmazonApiGatewayV2.GetApis")
            .contentType(APIGWV2_CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Items.findAll { it.ApiId == '" + apiId + "' }.Name", hasItem("cfn-apigwv2-api-v2"));

        // Idempotent re-deploy with no changes is a no-op (criterion #3): counts/ids unchanged
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody",
                    template.formatted("cfn-apigwv2-api-v2", "https://example.com/v2", "GET /things", "true"))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        apigwv2DescribeStacks(stackName);
        getRoutes(apiId).body("Items.size()", equalTo(1))
                .body("Items[0].RouteId", equalTo(routeId));
        getIntegrations(apiId).body("Items.size()", equalTo(1))
                .body("Items[0].IntegrationId", equalTo(integrationId));
        getStages(apiId).body("Items.size()", equalTo(1));
    }

    private io.restassured.response.ValidatableResponse getRoutes(String apiId) {
        return given()
            .header("X-Amz-Target", "AmazonApiGatewayV2.GetRoutes")
            .contentType(APIGWV2_CONTENT_TYPE)
            .body("{\"ApiId\": \"" + apiId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private io.restassured.response.ValidatableResponse getIntegrations(String apiId) {
        return given()
            .header("X-Amz-Target", "AmazonApiGatewayV2.GetIntegrations")
            .contentType(APIGWV2_CONTENT_TYPE)
            .body("{\"ApiId\": \"" + apiId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private io.restassured.response.ValidatableResponse getStages(String apiId) {
        return given()
            .header("X-Amz-Target", "AmazonApiGatewayV2.GetStages")
            .contentType(APIGWV2_CONTENT_TYPE)
            .body("{\"ApiId\": \"" + apiId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";

    @Test
    void createStack_stepFunctionsStateMachineResolvesDefinitionSubstitutionsAndIsDeletable() {
        String template = """
            {
              "Resources": {
                "MyRole": {
                  "Type": "AWS::IAM::Role",
                  "Properties": {
                    "RoleName": "cfn-sfn-role",
                    "AssumeRolePolicyDocument": {
                      "Version": "2012-10-17",
                      "Statement": [{
                        "Effect": "Allow",
                        "Principal": { "Service": "states.amazonaws.com" },
                        "Action": "sts:AssumeRole"
                      }]
                    }
                  }
                },
                "MyStateMachine": {
                  "Type": "AWS::StepFunctions::StateMachine",
                  "Properties": {
                    "StateMachineName": "cfn-sfn-pipeline",
                    "RoleArn": { "Fn::GetAtt": ["MyRole", "Arn"] },
                    "DefinitionString": "{\\"StartAt\\":\\"Done\\",\\"States\\":{\\"Done\\":{\\"Type\\":\\"Pass\\",\\"Result\\":\\"${Marker}\\",\\"End\\":true}}}",
                    "DefinitionSubstitutions": {
                      "Marker": "substituted-value"
                    },
                    "Tags": [
                      { "Key": "env", "Value": "test" }
                    ]
                  }
                }
              },
              "Outputs": {
                "StateMachineArn": { "Value": { "Ref": "MyStateMachine" } },
                "StateMachineName": { "Value": { "Fn::GetAtt": ["MyStateMachine", "Name"] } }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "sfn-cfn-stack")
            .formParam("TemplateBody", template)
            .formParam("Capabilities.member.1", "CAPABILITY_NAMED_IAM")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String expectedArn = "arn:aws:states:us-east-1:000000000000:stateMachine:cfn-sfn-pipeline";

        given()
            .header("X-Amz-Target", "AWSStepFunctions.DescribeStateMachine")
            .contentType(SFN_CONTENT_TYPE)
            .body("{\"stateMachineArn\":\"" + expectedArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("name", equalTo("cfn-sfn-pipeline"))
            .body("roleArn", containsString("cfn-sfn-role"))
            .body("definition", containsString("substituted-value"))
            .body("definition", not(containsString("${Marker}")));

        String describeXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "sfn-cfn-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
        assertThat(describeXml, containsString("<OutputKey>StateMachineArn</OutputKey>"));
        assertThat(describeXml, containsString("<OutputValue>" + expectedArn + "</OutputValue>"));
        assertThat(describeXml, containsString("<OutputKey>StateMachineName</OutputKey>"));
        assertThat(describeXml, containsString("<OutputValue>cfn-sfn-pipeline</OutputValue>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStack")
            .formParam("StackName", "sfn-cfn-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AWSStepFunctions.DescribeStateMachine")
            .contentType(SFN_CONTENT_TYPE)
            .body("{\"stateMachineArn\":\"" + expectedArn + "\"}")
        .when()
            .post("/")
        .then()
            .body("__type", containsString("StateMachineDoesNotExist"));
    }

    // ── Issue #924: roll back failed stack creates (criterion #9) ────────────

    @Test
    void createStack_resourceFailure_rollsBackCreatedResourcesThenRetrySucceeds() {
        // GoodBucket provisions first (DependsOn forces ordering); BadSecret then fails because
        // it sets both SecretString and GenerateSecretString. The successful bucket must be rolled
        // back so a corrected re-deploy starts from a clean slate.
        String failingTemplate = """
            {
              "Resources": {
                "GoodBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": { "BucketName": "cfn-rollback-bucket" }
                },
                "BadSecret": {
                  "Type": "AWS::SecretsManager::Secret",
                  "DependsOn": "GoodBucket",
                  "Properties": {
                    "Name": "cfn-rollback-secret",
                    "SecretString": "explicit",
                    "GenerateSecretString": { "PasswordLength": 32 }
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "cfn-rollback-stack")
            .formParam("TemplateBody", failingTemplate)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // The stack rolled back rather than being left half-built
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "cfn-rollback-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>ROLLBACK_COMPLETE</StackStatus>"));

        // The bucket that was successfully created is gone again — rollback cleaned it up
        given()
            .header("Host", "cfn-rollback-bucket.localhost")
        .when()
            .get("/")
        .then()
            .statusCode(404);

        // A corrected re-deploy (no failing resource) succeeds on the now-clean slate
        String goodTemplate = """
            {
              "Resources": {
                "GoodBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": { "BucketName": "cfn-rollback-bucket" }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "cfn-rollback-retry-stack")
            .formParam("TemplateBody", goodTemplate)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "cfn-rollback-retry-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));

        // The bucket now exists again, provisioned by the successful retry
        given()
            .header("Host", "cfn-rollback-bucket.localhost")
        .when()
            .get("/")
        .then()
            .statusCode(200);
    }

    @Test
    void createStack_resourceFailure_setsRollbackComplete_singleResource() {
        // A lone failing resource still moves the stack to ROLLBACK_COMPLETE (no orphaned state).
        String template = """
            {
              "Resources": {
                "BadSecret": {
                  "Type": "AWS::SecretsManager::Secret",
                  "Properties": {
                    "Name": "cfn-rollback-lone-secret",
                    "SecretString": "explicit",
                    "GenerateSecretString": { "PasswordLength": 32 }
                  }
                }
              }
            }
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", "cfn-rollback-lone-stack")
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", "cfn-rollback-lone-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>ROLLBACK_COMPLETE</StackStatus>"));

        // The failed resource is still reported as CREATE_FAILED in the resource list
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", "cfn-rollback-lone-stack")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("CREATE_FAILED"));
    }

    @Test
    void createStack_apiGatewayRestApi_withEndpointConfiguration() {
        String template = """
            {
              "Resources": {
                "MyPrivateApi": {
                  "Type": "AWS::ApiGateway::RestApi",
                  "Properties": {
                    "Name": "cfn-private-api",
                    "EndpointConfiguration": {
                      "Types": ["PRIVATE"],
                      "VpcEndpointIds": ["vpce-12345678"]
                    }
                  }
                }
              }
            }
            """;

        String stackName = "apigw-ep-stack";

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

        String resourcesXml = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStackResources")
                .formParam("StackName", stackName)
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().asString();

        String apiId = physicalIdByLogicalId(resourcesXml, "MyPrivateApi");

        given()
                .when()
                .get("/restapis/" + apiId)
                .then()
                .statusCode(200)
                .body("name", equalTo("cfn-private-api"))
                .body("endpointConfiguration.types", contains("PRIVATE"))
                .body("endpointConfiguration.vpcEndpointIds", contains("vpce-12345678"));
    }

}
