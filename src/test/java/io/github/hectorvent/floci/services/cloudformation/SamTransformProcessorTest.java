package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.Map;

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
    void expandSamTemplate_autoPublishAliasGeneratesVersionAndAlias() throws Exception {
        // AutoPublishAlias must expand into the Version + Alias pair real SAM generates. Dropping it
        // leaves the function with only $LATEST, so an alias-qualified invoke (<function>:production,
        // which the declaration exists to enable) fails with "Alias not found" long after the deploy
        // reported CREATE_COMPLETE.
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyFunc": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "Handler": "index.handler",
                    "Runtime": "nodejs20.x",
                    "AutoPublishAlias": "production",
                    "InlineCode": "exports.handler = async () => ({});"
                  }
                }
              }
            }
            """);

        JsonNode resources = processor.expandSamTemplate(template).path("Resources");

        assertTrue(resources.has("MyFuncVersion"));
        assertEquals("AWS::Lambda::Version", resources.path("MyFuncVersion").path("Type").asText());
        assertEquals("MyFunc",
                resources.path("MyFuncVersion").path("Properties").path("FunctionName").path("Ref").asText());

        // Suffix is sanitize()d, so the logical id stays alphanumeric — see the alias-name case
        // below, where the raw name would otherwise leak a '-' into it.
        assertTrue(resources.has("MyFuncAliasProduction"));
        JsonNode aliasProps = resources.path("MyFuncAliasProduction").path("Properties");
        assertEquals("AWS::Lambda::Alias", resources.path("MyFuncAliasProduction").path("Type").asText());
        // The alias NAME itself is untouched — only the logical id is sanitized.
        assertEquals("production", aliasProps.path("Name").asText());
        assertEquals("MyFunc", aliasProps.path("FunctionName").path("Ref").asText());

        // The alias deliberately targets $LATEST rather than the published version real SAM points
        // at: Floci cannot invoke a published version (#1987 cold-start timeout, #1988 warm-pool
        // keying), so the faithful form would break the alias-qualified invoke this expansion
        // exists to enable.
        assertEquals("$LATEST", aliasProps.path("FunctionVersion").asText());
    }

    @Test
    void expandSamTemplate_withoutAutoPublishAliasGeneratesNeither() throws Exception {
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

        JsonNode resources = processor.expandSamTemplate(template).path("Resources");

        Iterator<String> names = resources.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            String type = resources.path(name).path("Type").asText();
            assertNotEquals("AWS::Lambda::Version", type, name + " should not exist");
            assertNotEquals("AWS::Lambda::Alias", type, name + " should not exist");
        }
    }

    @Test
    void expandSamTemplate_autoPublishAliasFromIntrinsicPassesNodeThrough() throws Exception {
        // SAM allows an intrinsic alias name (e.g. !Ref StageName). The logical id needs a literal,
        // so it drops the suffix, but the node itself is passed through to Name for the template
        // engine to resolve at provision time.
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Parameters": {"StageName": {"Type": "String", "Default": "live"}},
              "Resources": {
                "MyFunc": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "Handler": "index.handler",
                    "Runtime": "nodejs20.x",
                    "AutoPublishAlias": {"Ref": "StageName"},
                    "InlineCode": "exports.handler = async () => ({});"
                  }
                }
              }
            }
            """);

        JsonNode resources = processor.expandSamTemplate(template).path("Resources");

        assertTrue(resources.has("MyFuncAlias"));
        assertEquals("StageName",
                resources.path("MyFuncAlias").path("Properties").path("Name").path("Ref").asText());
    }

    @Test
    void expandSamTemplate_autoPublishAliasWithSeparatorsYieldsAlphanumericLogicalId() throws Exception {
        // '-' and '_' are legal in a Lambda alias name but not in a CloudFormation logical id, so
        // the suffix has to be sanitized. Using the raw name emits "MyFuncAliasblue-green".
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyFunc": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "Handler": "index.handler",
                    "Runtime": "nodejs20.x",
                    "AutoPublishAlias": "blue-green_2",
                    "InlineCode": "exports.handler = async () => ({});"
                  }
                }
              }
            }
            """);

        JsonNode resources = processor.expandSamTemplate(template).path("Resources");

        String aliasId = null;
        Iterator<String> names = resources.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if ("AWS::Lambda::Alias".equals(resources.path(name).path("Type").asText())) {
                aliasId = name;
            }
        }
        assertNotNull(aliasId, "expansion emitted no AWS::Lambda::Alias");
        assertTrue(aliasId.matches("[A-Za-z0-9]+"),
                "logical id must be alphanumeric, was: " + aliasId);
        assertEquals("MyFuncAliasBlueGreen2", aliasId);

        // The alias name reaching Lambda keeps its separators — only the logical id is sanitized.
        assertEquals("blue-green_2", resources.path(aliasId).path("Properties").path("Name").asText());
    }

    @Test
    void expandSamTemplate_functionWithPackageTypeImage() throws Exception {
        // PackageType must be carried through to the expanded AWS::Lambda::Function: without it,
        // CloudFormationResourceProvisioner.buildLambdaDesiredState defaults PackageType to "Zip"
        // (resolveOrDefault(props, "PackageType", engine, "Zip")), which then forces Runtime/Handler
        // defaults (nodejs18.x / index.handler) onto a function that never had either — the function
        // gets created as a Zip function running the wrong runtime instead of the real container image.
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyFunc": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "PackageType": "Image",
                    "ImageUri": "000000000000.dkr.ecr.us-east-1.localhost:5100/my-repo:latest"
                  }
                }
              }
            }
            """);

        JsonNode expanded = processor.expandSamTemplate(template);

        JsonNode lambdaProps = expanded.path("Resources").path("MyFunc").path("Properties");
        assertEquals("Image", lambdaProps.path("PackageType").asText());
        assertEquals("000000000000.dkr.ecr.us-east-1.localhost:5100/my-repo:latest",
                lambdaProps.path("Code").path("ImageUri").asText());
        // No Handler/Runtime were declared and none should be synthesized by the transform itself —
        // CloudFormationResourceProvisioner is responsible for not defaulting them once it sees
        // PackageType: Image (verified separately in CloudFormationIntegrationTest).
        assertTrue(lambdaProps.path("Handler").isMissingNode());
        assertTrue(lambdaProps.path("Runtime").isMissingNode());
    }

    @Test
    void expandSamTemplate_functionWithFileSystemConfig() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyFunc": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "Handler": "index.handler",
                    "Runtime": "nodejs20.x",
                    "InlineCode": "exports.handler = async () => ({});",
                    "VpcConfig": {
                      "SubnetIds": ["subnet-0123456789abcdef0"],
                      "SecurityGroupIds": ["sg-0123456789abcdef0"]
                    },
                    "FileSystemConfigs": [{
                      "Arn": "arn:aws:elasticfilesystem:us-east-1:000000000000:access-point/fsap-0123456789abcdef0",
                      "LocalMountPath": "/mnt/shared"
                    }]
                  }
                }
              }
            }
            """);

        JsonNode lambdaProps = processor.expandSamTemplate(template)
                .path("Resources").path("MyFunc").path("Properties");

        assertEquals("subnet-0123456789abcdef0",
                lambdaProps.path("VpcConfig").path("SubnetIds").get(0).asText());
        assertEquals("sg-0123456789abcdef0",
                lambdaProps.path("VpcConfig").path("SecurityGroupIds").get(0).asText());
        assertEquals("arn:aws:elasticfilesystem:us-east-1:000000000000:"
                        + "access-point/fsap-0123456789abcdef0",
                lambdaProps.path("FileSystemConfigs").get(0).path("Arn").asText());
        assertEquals("/mnt/shared",
                lambdaProps.path("FileSystemConfigs").get(0).path("LocalMountPath").asText());
    }

    @Test
    void expandSamTemplate_functionWithImageConfig() throws Exception {
        // ImageConfig (EntryPoint/Command/WorkingDirectory overrides for a container-image
        // function) must also be carried through: CloudFormationResourceProvisioner.provisionLambda
        // already reads it (putResolvedMapIfPresent(configRequest, props, "ImageConfig", ...)), but
        // the SAM transform previously dropped it, silently losing any container entry-point/command
        // override on a PackageType: Image SAM function.
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyFunc": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "PackageType": "Image",
                    "ImageUri": "000000000000.dkr.ecr.us-east-1.localhost:5100/my-repo:latest",
                    "ImageConfig": {
                      "EntryPoint": ["/bootstrap"],
                      "Command": ["handler.main"],
                      "WorkingDirectory": "/var/task"
                    }
                  }
                }
              }
            }
            """);

        JsonNode expanded = processor.expandSamTemplate(template);

        JsonNode imageConfig = expanded.path("Resources").path("MyFunc").path("Properties").path("ImageConfig");
        assertEquals("/bootstrap", imageConfig.path("EntryPoint").get(0).asText());
        assertEquals("handler.main", imageConfig.path("Command").get(0).asText());
        assertEquals("/var/task", imageConfig.path("WorkingDirectory").asText());
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

    @Test
    void expandSamTemplate_generatesImplicitApiFromApiEvents() throws Exception {
        // Functions with Api events and no explicit RestApiId must produce a full implicit REST API.
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Globals": { "Api": { "Name": "MyServiceApi" } },
              "Resources": {
                "Fn": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "Handler": "bootstrap",
                    "Runtime": "provided.al2023",
                    "InlineCode": "x",
                    "Events": {
                      "Docs":  { "Type": "Api", "Properties": { "Path": "/docs",      "Method": "GET" } },
                      "Proxy": { "Type": "Api", "Properties": { "Path": "/{proxy+}",  "Method": "ANY" } }
                    }
                  }
                }
              }
            }
            """);

        JsonNode resources = processor.expandSamTemplate(template).path("Resources");

        // RestApi created, with the name from Globals.Api
        assertEquals("AWS::ApiGateway::RestApi", resources.path("ServerlessRestApi").path("Type").asText());
        assertEquals("MyServiceApi", resources.path("ServerlessRestApi").path("Properties").path("Name").asText());

        // Deployment + Prod stage
        assertEquals("AWS::ApiGateway::Deployment", resources.path("ServerlessRestApiDeployment").path("Type").asText());
        assertEquals("Prod", resources.path("ServerlessRestApiProdStage").path("Properties").path("StageName").asText());

        // A /docs resource exists, and there is a Method with an AWS_PROXY integration + a Lambda permission
        boolean hasDocsResource = false;
        boolean hasProxyMethod = false;
        boolean hasPermission = false;
        Iterator<Map.Entry<String, JsonNode>> it = resources.fields();
        while (it.hasNext()) {
            JsonNode n = it.next().getValue();
            String type = n.path("Type").asText();
            if ("AWS::ApiGateway::Resource".equals(type) && "docs".equals(n.path("Properties").path("PathPart").asText())) {
                hasDocsResource = true;
            }
            if ("AWS::ApiGateway::Method".equals(type)
                    && "AWS_PROXY".equals(n.path("Properties").path("Integration").path("Type").asText())) {
                hasProxyMethod = true;
            }
            if ("AWS::Lambda::Permission".equals(type)
                    && "apigateway.amazonaws.com".equals(n.path("Properties").path("Principal").asText())) {
                hasPermission = true;
            }
        }
        assertTrue(hasDocsResource, "expected an API Gateway Resource for /docs");
        assertTrue(hasProxyMethod, "expected an API Gateway Method with AWS_PROXY integration");
        assertTrue(hasPermission, "expected a Lambda permission for apigateway.amazonaws.com");
    }

    @Test
    void expandSamTemplate_dedupesDuplicateApiRoutes() throws Exception {
        // Two events resolving to the same (path, method) must collapse to a single API Gateway Method.
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "Fn": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "Handler": "bootstrap",
                    "Runtime": "provided.al2023",
                    "InlineCode": "x",
                    "Events": {
                      "A": { "Type": "Api", "Properties": { "Path": "/docs", "Method": "GET" } },
                      "B": { "Type": "Api", "Properties": { "Path": "/docs", "Method": "GET" } }
                    }
                  }
                }
              }
            }
            """);

        JsonNode resources = processor.expandSamTemplate(template).path("Resources");
        int methods = 0;
        Iterator<Map.Entry<String, JsonNode>> it = resources.fields();
        while (it.hasNext()) {
            if ("AWS::ApiGateway::Method".equals(it.next().getValue().path("Type").asText())) {
                methods++;
            }
        }
        assertEquals(1, methods, "duplicate (path, method) routes must collapse to a single Method");
    }

    @Test
    void expandSamTemplate_skipsApiRouteWithNonLiteralPath() throws Exception {
        // A Path given as an intrinsic (Ref/Fn::Sub) can't be turned into a literal API Gateway
        // resource path, so the route must be skipped rather than registered as the API root.
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "Fn": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "Handler": "bootstrap",
                    "Runtime": "provided.al2023",
                    "InlineCode": "x",
                    "Events": {
                      "A": { "Type": "Api", "Properties": { "Path": { "Ref": "SomePath" }, "Method": "GET" } }
                    }
                  }
                }
              }
            }
            """);

        JsonNode resources = processor.expandSamTemplate(template).path("Resources");
        int methods = 0;
        Iterator<Map.Entry<String, JsonNode>> it = resources.fields();
        while (it.hasNext()) {
            if ("AWS::ApiGateway::Method".equals(it.next().getValue().path("Type").asText())) {
                methods++;
            }
        }
        assertEquals(0, methods, "a route with a non-literal Path must not be registered");
    }

    @Test
    void expandSamTemplate_appliesGlobalsFunctionToFunction() throws Exception {
        // Handler/Runtime defined only in Globals.Function (common SAM pattern, e.g. Go provided.al2023)
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Globals": {
                "Function": {
                  "Handler": "bootstrap",
                  "Runtime": "provided.al2023"
                }
              },
              "Resources": {
                "MyFunc": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "InlineCode": "bootstrap"
                  }
                }
              }
            }
            """);

        JsonNode expanded = processor.expandSamTemplate(template);

        // Globals is a SAM-only section and must be stripped from the emitted CFN template
        assertTrue(expanded.path("Globals").isMissingNode());

        JsonNode lambdaProps = expanded.path("Resources").path("MyFunc").path("Properties");
        assertEquals("AWS::Lambda::Function",
                expanded.path("Resources").path("MyFunc").path("Type").asText());
        // Handler/Runtime from Globals must be propagated onto the generated function
        assertEquals("bootstrap", lambdaProps.path("Handler").asText());
        assertEquals("provided.al2023", lambdaProps.path("Runtime").asText());
    }

    @Test
    void expandSamTemplate_functionPropertiesOverrideGlobals() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Globals": {
                "Function": {
                  "Handler": "bootstrap",
                  "Runtime": "provided.al2023",
                  "Timeout": 3
                }
              },
              "Resources": {
                "MyFunc": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "Runtime": "nodejs20.x",
                    "Handler": "index.handler",
                    "InlineCode": "exports.handler = async () => ({});"
                  }
                }
              }
            }
            """);

        JsonNode lambdaProps = processor.expandSamTemplate(template)
                .path("Resources").path("MyFunc").path("Properties");

        // Function-level values win; Globals-only values still apply
        assertEquals("index.handler", lambdaProps.path("Handler").asText());
        assertEquals("nodejs20.x", lambdaProps.path("Runtime").asText());
        assertEquals(3, lambdaProps.path("Timeout").asInt());
    }

    @Test
    void expandSamTemplate_mergesNestedMapsFromGlobals() throws Exception {
        // Environment.Variables must merge key-wise: globals-only keys preserved, function keys win on clash
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Globals": {
                "Function": {
                  "Handler": "bootstrap",
                  "Runtime": "provided.al2023",
                  "Environment": { "Variables": { "GLOBAL_VAR": "g", "SHARED": "from-globals" } }
                }
              },
              "Resources": {
                "MyFunc": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "InlineCode": "bootstrap",
                    "Environment": { "Variables": { "LOCAL_VAR": "l", "SHARED": "from-function" } }
                  }
                }
              }
            }
            """);

        JsonNode vars = processor.expandSamTemplate(template)
                .path("Resources").path("MyFunc").path("Properties")
                .path("Environment").path("Variables");

        assertEquals("g", vars.path("GLOBAL_VAR").asText());          // globals-only key preserved
        assertEquals("l", vars.path("LOCAL_VAR").asText());           // function-only key added
        assertEquals("from-function", vars.path("SHARED").asText());  // clash resolved in favor of function
    }

    @Test
    void expandSamTemplate_stripsGlobalsWhenResourcesAbsent() throws Exception {
        // SAM-only Globals must be stripped even on the early return (no/!object Resources)
        JsonNode template = objectMapper.readTree("""
            {"Transform": "AWS::Serverless-2016-10-31", "Globals": {"Function": {"Runtime": "provided.al2023"}}}
            """);

        JsonNode expanded = processor.expandSamTemplate(template);

        assertTrue(expanded.path("Transform").isMissingNode());
        assertTrue(expanded.path("Globals").isMissingNode());
    }
    @Test
    void expandSamTemplate_httpApiPreservesDefinitionBodyAsRoutes() throws Exception {
        // Regression for #1956: a route-bearing HttpApi carries its routes in the inline
        // OpenAPI DefinitionBody, which must survive as the ApiGatewayV2::Api Body — otherwise
        // the API expands with no routes.
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyHttpApi": {
                  "Type": "AWS::Serverless::HttpApi",
                  "Properties": {
                    "DefinitionBody": {
                      "openapi": "3.0.1",
                      "paths": {
                        "/hello": { "get": { "x-amazon-apigateway-integration": { "type": "aws_proxy" } } }
                      }
                    }
                  }
                }
              }
            }
            """);

        JsonNode resources = processor.expandSamTemplate(template).path("Resources");

        JsonNode api = resources.path("MyHttpApi");
        assertEquals("AWS::ApiGatewayV2::Api", api.path("Type").asText());
        JsonNode body = api.path("Properties").path("Body");
        assertFalse(body.isMissingNode(), "DefinitionBody must be preserved as the API Body");
        assertEquals("3.0.1", body.path("openapi").asText());
        assertTrue(body.path("paths").has("/hello"), "route definitions must survive the transform");
    }

    @Test
    void expandSamTemplate_httpApiEventMergesIntoMatchingDefinitionBodyOperation() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyHttpApi": {
                  "Type": "AWS::Serverless::HttpApi",
                  "Properties": {
                    "Auth": {
                      "DefaultAuthorizer": "JwtAuth",
                      "Authorizers": {
                        "JwtAuth": {
                          "IdentitySource": "$request.header.Authorization",
                          "AuthorizationScopes": ["read:items"],
                          "JwtConfiguration": {
                            "Issuer": "https://issuer.example.com",
                            "Audience": ["items-client"]
                          }
                        }
                      }
                    },
                    "DefinitionBody": {
                      "openapi": "3.0.1",
                      "paths": {
                        "/items": {
                          "get": {
                            "security": [],
                            "responses": { "200": { "description": "ok" } }
                          }
                        }
                      }
                    }
                  }
                },
                "MyFunction": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "Runtime": "python3.12",
                    "Handler": "index.handler",
                    "InlineCode": "def handler(e,c): return {}",
                    "Events": {
                      "Api": {
                        "Type": "HttpApi",
                        "Properties": {
                          "ApiId": { "Ref": "MyHttpApi" },
                          "Path": "/items",
                          "Method": "GET"
                        }
                      }
                    }
                  }
                }
              }
            }
            """);

        JsonNode resources = processor.expandSamTemplate(template).path("Resources");
        JsonNode operation = resources.path("MyHttpApi").path("Properties").path("Body")
                .path("paths").path("/items").path("get");
        JsonNode integration = operation.path("x-amazon-apigateway-integration");
        JsonNode authorizer = resources.path("MyHttpApi").path("Properties").path("Body")
                .path("components").path("securitySchemes").path("JwtAuth");

        assertEquals("ok", operation.path("responses").path("200").path("description").asText());
        assertEquals("aws_proxy", integration.path("type").asText());
        assertEquals("POST", integration.path("httpMethod").asText());
        assertEquals("2.0", integration.path("payloadFormatVersion").asText());
        assertEquals("MyFunction", integration.path("uri").path("Fn::Sub").path(1)
                .path("FnArn").path("Fn::GetAtt").path(0).asText());
        assertEquals("oauth2", authorizer.path("type").asText());
        assertEquals("jwt", authorizer.path("x-amazon-apigateway-authorizer").path("type").asText());
        assertEquals("$request.header.Authorization", authorizer.path("x-amazon-apigateway-authorizer")
                .path("identitySource").path(0).asText());
        assertEquals("https://issuer.example.com", authorizer.path("x-amazon-apigateway-authorizer")
                .path("jwtConfiguration").path("issuer").asText());
        assertEquals("items-client", authorizer.path("x-amazon-apigateway-authorizer")
                .path("jwtConfiguration").path("audience").path(0).asText());
        assertEquals("read:items", operation.path("security").path(0).path("JwtAuth").path(0).asText());

        int routeResources = 0;
        int integrationResources = 0;
        int permissionResources = 0;
        Iterator<JsonNode> definitions = resources.elements();
        while (definitions.hasNext()) {
            String type = definitions.next().path("Type").asText();
            routeResources += "AWS::ApiGatewayV2::Route".equals(type) ? 1 : 0;
            integrationResources += "AWS::ApiGatewayV2::Integration".equals(type) ? 1 : 0;
            permissionResources += "AWS::Lambda::Permission".equals(type) ? 1 : 0;
        }
        assertEquals(0, routeResources, "matching body operations must not emit a duplicate route resource");
        assertEquals(0, integrationResources, "the body owns the merged integration");
        assertEquals(1, permissionResources, "API Gateway still needs permission to invoke the function");
    }

    @Test
    void expandSamTemplate_matchingHttpApiEventCanOptOutOfDefaultAuthorizer() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "HttpApi": {
                  "Type": "AWS::Serverless::HttpApi",
                  "Properties": {
                    "Auth": {
                      "DefaultAuthorizer": "JwtAuth",
                      "Authorizers": {
                        "JwtAuth": {
                          "IdentitySource": ["$request.header.Authorization"],
                          "JwtConfiguration": {
                            "issuer": "https://issuer.example.com",
                            "audience": ["client-id"]
                          }
                        }
                      }
                    },
                    "DefinitionBody": {
                      "openapi": "3.0.1",
                      "paths": { "/public": { "get": {} } }
                    }
                  }
                },
                "Handler": {
                  "Type": "AWS::Serverless::Function",
                  "Properties": {
                    "Runtime": "python3.12",
                    "Handler": "index.handler",
                    "InlineCode": "def handler(e,c): return {}",
                    "Events": {
                      "Public": {
                        "Type": "HttpApi",
                        "Properties": {
                          "ApiId": { "Ref": "HttpApi" },
                          "Path": "/public",
                          "Method": "GET",
                          "Auth": { "Authorizer": "NONE" }
                        }
                      }
                    }
                  }
                }
              }
            }
            """);

        JsonNode operation = processor.expandSamTemplate(template).path("Resources")
                .path("HttpApi").path("Properties").path("Body")
                .path("paths").path("/public").path("get");

        assertTrue(operation.path("security").isArray());
        assertTrue(operation.path("security").isEmpty(),
                "Authorizer NONE must override the API's default authorizer");
        assertTrue(operation.path("x-amazon-apigateway-integration").isObject());
    }

    @Test
    void expandSamTemplate_httpApiMapsDefinitionUriToBodyS3Location() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyHttpApi": {
                  "Type": "AWS::Serverless::HttpApi",
                  "Properties": { "DefinitionUri": "s3://api-specs/openapi.yaml" }
                }
              }
            }
            """);

        JsonNode properties = processor.expandSamTemplate(template)
                .path("Resources").path("MyHttpApi").path("Properties");

        assertTrue(properties.path("Body").isMissingNode(), "DefinitionUri must not become an inline Body");
        assertEquals("api-specs", properties.path("BodyS3Location").path("Bucket").asText());
        assertEquals("openapi.yaml", properties.path("BodyS3Location").path("Key").asText());
    }
    @Test
    void expandSamTemplate_httpApiPreservesIntrinsicDefinitionUriForProvisioning() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyHttpApi": {
                  "Type": "AWS::Serverless::HttpApi",
                  "Properties": {
                    "DefinitionUri": { "Fn::Sub": "s3://${SpecBucket}/openapi.yaml" }
                  }
                }
              }
            }
            """);

        JsonNode bodyS3Location = processor.expandSamTemplate(template)
                .path("Resources").path("MyHttpApi").path("Properties").path("BodyS3Location");

        assertEquals("s3://${SpecBucket}/openapi.yaml", bodyS3Location.path("Fn::Sub").asText());
    }
}
