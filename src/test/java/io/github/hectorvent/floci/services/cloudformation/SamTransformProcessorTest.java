package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the SAM Transform processor logic.
 */
class SamTransformProcessorTest {

    private ObjectMapper objectMapper;
    private SamTransformProcessor processor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        processor = new SamTransformProcessor(objectMapper);
    }

    @Test
    void hasSamTransform_withStringTransform() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {"Transform": "AWS::Serverless-2016-10-31", "Resources": {}}
            """);
        assertTrue(processor.hasSamTransform(template));
    }

    @Test
    void hasSamTransform_withArrayTransform() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {"Transform": ["AWS::Serverless-2016-10-31", "AWS::Other"], "Resources": {}}
            """);
        assertTrue(processor.hasSamTransform(template));
    }

    @Test
    void hasSamTransform_withoutTransform() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {"Resources": {"MyBucket": {"Type": "AWS::S3::Bucket"}}}
            """);
        assertFalse(processor.hasSamTransform(template));
    }

    @Test
    void hasSamTransform_withDifferentTransform() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {"Transform": "AWS::Include", "Resources": {}}
            """);
        assertFalse(processor.hasSamTransform(template));
    }

    @Test
    void expandSamTemplate_functionWithInlineCode() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyFunc": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "Handler": "index.handler",
                    "Runtime": "nodejs20.x",
                    "InlineCode": "exports.handler = async () => ({});"
                  }
                }
              }
            }
            """);

        JsonNode expanded = processor.expandSamTemplate(template);

        // Transform should be removed
        assertTrue(expanded.path("Transform").isMissingNode());

        // Should have Lambda function and IAM role
        JsonNode resources = expanded.path("Resources");
        assertTrue(resources.has("MyFunc"));
        assertTrue(resources.has("MyFuncRole"));

        assertEquals("AWS::Lambda::Function", resources.path("MyFunc").path("Type").asText());
        assertEquals("AWS::IAM::Role", resources.path("MyFuncRole").path("Type").asText());

        // Lambda should have ZipFile code from InlineCode
        JsonNode lambdaProps = resources.path("MyFunc").path("Properties");
        assertEquals("index.handler", lambdaProps.path("Handler").asText());
        assertEquals("nodejs20.x", lambdaProps.path("Runtime").asText());
        assertEquals("exports.handler = async () => ({});",
                lambdaProps.path("Code").path("ZipFile").asText());

        // Role should reference the generated role via Fn::GetAtt
        JsonNode roleRef = lambdaProps.path("Role");
        assertTrue(roleRef.has("Fn::GetAtt"));
        assertEquals("MyFuncRole", roleRef.path("Fn::GetAtt").get(0).asText());
        assertEquals("Arn", roleRef.path("Fn::GetAtt").get(1).asText());
    }

    @Test
    void expandSamTemplate_functionWithExplicitRole() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyFunc": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "Handler": "index.handler",
                    "Runtime": "nodejs20.x",
                    "InlineCode": "code",
                    "Role": "arn:aws:iam::123456789012:role/my-role"
                  }
                }
              }
            }
            """);

        JsonNode expanded = processor.expandSamTemplate(template);
        JsonNode resources = expanded.path("Resources");
        JsonNode lambdaProps = resources.path("MyFunc").path("Properties");

        // Should use the explicit role ARN
        assertEquals("arn:aws:iam::123456789012:role/my-role", lambdaProps.path("Role").asText());
        // Should NOT create a generated role resource
        assertFalse(resources.has("MyFuncRole"));
    }

    @Test
    void expandSamTemplate_functionWithS3CodeUri() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyFunc": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "Handler": "index.handler",
                    "Runtime": "nodejs20.x",
                    "CodeUri": "s3://my-bucket/code.zip"
                  }
                }
              }
            }
            """);

        JsonNode expanded = processor.expandSamTemplate(template);
        JsonNode code = expanded.path("Resources").path("MyFunc").path("Properties").path("Code");

        assertEquals("my-bucket", code.path("S3Bucket").asText());
        assertEquals("code.zip", code.path("S3Key").asText());
    }

    @Test
    void expandSamTemplate_functionWithCodeUriObject() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyFunc": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "Handler": "index.handler",
                    "Runtime": "nodejs20.x",
                    "CodeUri": {
                      "Bucket": "my-bucket",
                      "Key": "path/to/code.zip",
                      "Version": "abc123"
                    }
                  }
                }
              }
            }
            """);

        JsonNode expanded = processor.expandSamTemplate(template);
        JsonNode code = expanded.path("Resources").path("MyFunc").path("Properties").path("Code");

        assertEquals("my-bucket", code.path("S3Bucket").asText());
        assertEquals("path/to/code.zip", code.path("S3Key").asText());
        assertEquals("abc123", code.path("S3ObjectVersion").asText());
    }

    @Test
    void expandSamTemplate_simpleTable() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyTable": {
                  "Type": "AWS::Serverless::SimpleTable",
                  "Properties": {
                    "TableName": "my-table",
                    "PrimaryKey": {
                      "Name": "pk",
                      "Type": "String"
                    }
                  }
                }
              }
            }
            """);

        JsonNode expanded = processor.expandSamTemplate(template);
        JsonNode resources = expanded.path("Resources");

        assertEquals("AWS::DynamoDB::Table", resources.path("MyTable").path("Type").asText());

        JsonNode tableProps = resources.path("MyTable").path("Properties");
        assertEquals("my-table", tableProps.path("TableName").asText());
        assertEquals("pk", tableProps.path("KeySchema").get(0).path("AttributeName").asText());
        assertEquals("HASH", tableProps.path("KeySchema").get(0).path("KeyType").asText());
        assertEquals("pk", tableProps.path("AttributeDefinitions").get(0).path("AttributeName").asText());
        assertEquals("S", tableProps.path("AttributeDefinitions").get(0).path("AttributeType").asText());
        assertEquals("PAY_PER_REQUEST", tableProps.path("BillingMode").asText());
    }

    @Test
    void expandSamTemplate_simpleTableWithDefaultKey() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyTable": {
                  "Type": "AWS::Serverless::SimpleTable",
                  "Properties": {
                    "TableName": "default-key-table"
                  }
                }
              }
            }
            """);

        JsonNode expanded = processor.expandSamTemplate(template);
        JsonNode tableProps = expanded.path("Resources").path("MyTable").path("Properties");

        // Default key should be "id" of type "S"
        assertEquals("id", tableProps.path("KeySchema").get(0).path("AttributeName").asText());
        assertEquals("S", tableProps.path("AttributeDefinitions").get(0).path("AttributeType").asText());
    }

    @Test
    void expandSamTemplate_api() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyApi": {
                  "Type": "AWS::Serverless::Api",
                  "Properties": {
                    "Name": "test-api",
                    "StageName": "prod"
                  }
                }
              }
            }
            """);

        JsonNode expanded = processor.expandSamTemplate(template);
        JsonNode resources = expanded.path("Resources");

        // Should create RestApi, Deployment, and Stage
        assertEquals("AWS::ApiGateway::RestApi", resources.path("MyApi").path("Type").asText());
        assertEquals("AWS::ApiGateway::Deployment", resources.path("MyApiDeployment").path("Type").asText());
        assertEquals("AWS::ApiGateway::Stage", resources.path("MyApiStage").path("Type").asText());

        // Stage should have the specified name
        assertEquals("prod",
                resources.path("MyApiStage").path("Properties").path("StageName").asText());
    }

    @Test
    void expandSamTemplate_mixedResources() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": {"BucketName": "my-bucket"}
                },
                "MyFunc": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "Handler": "index.handler",
                    "Runtime": "nodejs20.x",
                    "InlineCode": "code"
                  }
                }
              }
            }
            """);

        JsonNode expanded = processor.expandSamTemplate(template);
        JsonNode resources = expanded.path("Resources");

        // Standard resource should be preserved
        assertEquals("AWS::S3::Bucket", resources.path("MyBucket").path("Type").asText());
        assertEquals("my-bucket", resources.path("MyBucket").path("Properties").path("BucketName").asText());

        // SAM resource should be expanded
        assertEquals("AWS::Lambda::Function", resources.path("MyFunc").path("Type").asText());
        assertTrue(resources.has("MyFuncRole"));
    }

    @Test
    void expandSamTemplate_noTransform_returnsUnchanged() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Resources": {
                "MyBucket": {
                  "Type": "AWS::S3::Bucket",
                  "Properties": {"BucketName": "my-bucket"}
                }
              }
            }
            """);

        JsonNode result = processor.expandSamTemplate(template);
        assertEquals(template, result);
    }

    @Test
    void expandSamTemplate_functionWithEnvironmentAndTimeout() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyFunc": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "Handler": "app.handler",
                    "Runtime": "python3.12",
                    "InlineCode": "def handler(e,c): pass",
                    "Timeout": 30,
                    "MemorySize": 512,
                    "Environment": {
                      "Variables": {
                        "TABLE": "my-table"
                      }
                    }
                  }
                }
              }
            }
            """);

        JsonNode expanded = processor.expandSamTemplate(template);
        JsonNode lambdaProps = expanded.path("Resources").path("MyFunc").path("Properties");

        assertEquals(30, lambdaProps.path("Timeout").asInt());
        assertEquals(512, lambdaProps.path("MemorySize").asInt());
        assertEquals("my-table", lambdaProps.path("Environment").path("Variables").path("TABLE").asText());
    }

    @Test
    void expandSamTemplate_functionWithSqsEvent() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyFunc": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "Handler": "index.handler",
                    "Runtime": "nodejs20.x",
                    "InlineCode": "code",
                    "Events": {
                      "SqsTrigger": {
                        "Type": "SQS",
                        "Properties": {
                          "Queue": "arn:aws:sqs:us-east-1:123456789012:my-queue",
                          "BatchSize": 10
                        }
                      }
                    }
                  }
                }
              }
            }
            """);

        JsonNode expanded = processor.expandSamTemplate(template);
        JsonNode resources = expanded.path("Resources");

        // Should create an EventSourceMapping
        assertTrue(resources.has("MyFuncSqsTrigger"));
        assertEquals("AWS::Lambda::EventSourceMapping",
                resources.path("MyFuncSqsTrigger").path("Type").asText());

        JsonNode esmProps = resources.path("MyFuncSqsTrigger").path("Properties");
        assertEquals("MyFunc", esmProps.path("FunctionName").path("Ref").asText());
        assertEquals("arn:aws:sqs:us-east-1:123456789012:my-queue",
                esmProps.path("EventSourceArn").asText());
        assertEquals(10, esmProps.path("BatchSize").asInt());
    }
}
