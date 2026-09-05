package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
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
    void expandSamTemplate_functionPreservesMetadata() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "OrdersFunction": {
                  "Type": "AWS::Serverless::Function",
                  "Metadata": { "SamResourceId": "OrdersFunction" },
                  "Properties": {
                    "Handler": "index.handler",
                    "Runtime": "nodejs20.x",
                    "CodeUri": "s3://code-bucket/orders.zip"
                  }
                }
              }
            }
            """);

        JsonNode function = processor.expandSamTemplate(template).path("Resources").path("OrdersFunction");

        // SAM CLI writes SamResourceId in Metadata for every AWS::Serverless::* resource it
        // transforms, not only state machines; copyResourceLevelAttributes is called from every
        // arm's switch case (Function, SimpleTable, Api, HttpApi, StateMachine) so each carries it.
        assertEquals("OrdersFunction", function.path("Metadata").path("SamResourceId").asText(),
                "Metadata must survive the transform on the Function arm too");
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
    void expandSamTemplate_apiPreservesDefinitionBodyAsBody() throws Exception {
        // A route-bearing Api carries its routes in the inline OpenAPI DefinitionBody, which
        // must survive as the RestApi's Body: otherwise the deployed API serves no method.
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyApi": {
                  "Type": "AWS::Serverless::Api",
                  "Properties": {
                    "DefinitionBody": {
                      "openapi": "3.0.1",
                      "paths": {
                        "/hello": { "get": { "x-amazon-apigateway-integration": { "type": "MOCK" } } }
                      }
                    }
                  }
                }
              }
            }
            """);

        JsonNode resources = processor.expandSamTemplate(template).path("Resources");

        JsonNode api = resources.path("MyApi");
        assertEquals("AWS::ApiGateway::RestApi", api.path("Type").asText());
        JsonNode body = api.path("Properties").path("Body");
        assertFalse(body.isMissingNode(), "DefinitionBody must be preserved as the RestApi Body");
        assertEquals("3.0.1", body.path("openapi").asText());
        assertTrue(body.path("paths").has("/hello"), "route definitions must survive the transform");
    }

    @Test
    void expandSamTemplate_apiMapsDefinitionUriToBodyS3Location() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyApi": {
                  "Type": "AWS::Serverless::Api",
                  "Properties": { "DefinitionUri": "s3://api-specs/openapi.yaml" }
                }
              }
            }
            """);

        JsonNode properties = processor.expandSamTemplate(template)
                .path("Resources").path("MyApi").path("Properties");

        assertTrue(properties.path("Body").isMissingNode(), "DefinitionUri must not become an inline Body");
        assertEquals("api-specs", properties.path("BodyS3Location").path("Bucket").asText());
        assertEquals("openapi.yaml", properties.path("BodyS3Location").path("Key").asText());
    }

    @Test
    void expandSamTemplate_apiWithBothDefinitionBodyAndDefinitionUriIsRejected() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyApi": {
                  "Type": "AWS::Serverless::Api",
                  "Properties": {
                    "DefinitionBody": { "openapi": "3.0.1", "paths": {} },
                    "DefinitionUri": "s3://api-specs/openapi.yaml"
                  }
                }
              }
            }
            """);

        AwsException exception = assertThrows(AwsException.class,
                () -> processor.expandSamTemplate(template));

        // Measured against real AWS, us-east-1, create-change-set: an Api declaring both
        // DefinitionBody and DefinitionUri is rejected, DefinitionUri named first, the same
        // order as the equivalent HttpApi message. floci must not silently prefer DefinitionBody
        // and emit Body with no error.
        assertEquals("ValidationError", exception.getErrorCode());
        assertEquals("Resource with id [MyApi] is invalid. Specify either 'DefinitionUri' "
                        + "or 'DefinitionBody' property and not both.",
                exception.getMessage());
    }

    @Test
    void expandSamTemplate_apiWithLocalDefinitionUriIsRejected() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyApi": {
                  "Type": "AWS::Serverless::Api",
                  "Properties": { "DefinitionUri": "./openapi.yaml" }
                }
              }
            }
            """);

        AwsException exception = assertThrows(AwsException.class,
                () -> processor.expandSamTemplate(template));

        // Measured against real AWS, us-east-1, create-change-set: a local-path DefinitionUri is
        // rejected with the same string-form wording the StateMachine and HttpApi arms already
        // carry, not silently accepted with no Body and no BodyS3Location.
        assertEquals("ValidationError", exception.getErrorCode());
        assertEquals("Resource with id [MyApi] is invalid. 'DefinitionUri' is not a valid S3 "
                        + "Uri of the form 's3://bucket/key' with optional versionId query "
                        + "parameter.",
                exception.getMessage());
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
    void expandSamTemplate_httpApiMapsVersionedDefinitionUriToBodyS3LocationWithVersion() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyHttpApi": {
                  "Type": "AWS::Serverless::HttpApi",
                  "Properties": { "DefinitionUri": "s3://api-specs/openapi.yaml?versionId=abc123" }
                }
              }
            }
            """);

        JsonNode bodyS3Location = processor.expandSamTemplate(template)
                .path("Resources").path("MyHttpApi").path("Properties").path("BodyS3Location");

        // samUriToS3Location is shared with expandServerlessStateMachine (see its javadoc); a
        // versioned DefinitionUri must carry Version through on the HttpApi arm the same way.
        assertEquals("api-specs", bodyS3Location.path("Bucket").asText());
        assertEquals("openapi.yaml", bodyS3Location.path("Key").asText());
        assertEquals("abc123", bodyS3Location.path("Version").asText());
    }

    @Test
    void expandSamTemplate_httpApiWithIntrinsicDefinitionUriIsRejected() throws Exception {
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

        AwsException exception = assertThrows(AwsException.class,
                () -> processor.expandSamTemplate(template));

        // Measured against real AWS, us-east-1, create-change-set: an HttpApi DefinitionUri that
        // never resolves to a literal Bucket/Key is rejected with the same object-form wording as
        // the equivalent StateMachine case, not preserved unresolved for the ApiGatewayV2
        // provisioner to import as a BodyS3Location it cannot use.
        assertEquals("ValidationError", exception.getErrorCode());
        assertEquals("Resource with id [MyHttpApi] is invalid. 'DefinitionUri' requires "
                        + "Bucket and Key properties to be specified.",
                exception.getMessage());
    }

    @Test
    void expandSamTemplate_httpApiWithObjectDefinitionUriMissingKeyIsRejected() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyHttpApi": {
                  "Type": "AWS::Serverless::HttpApi",
                  "Properties": {
                    "DefinitionUri": { "Bucket": "api-specs" }
                  }
                }
              }
            }
            """);

        AwsException exception = assertThrows(AwsException.class,
                () -> processor.expandSamTemplate(template));

        // A Bucket-only DefinitionUri on an HttpApi must not fall through to BodyS3Location: the
        // native ApiGatewayV2 provisioner cannot import a location with no Key, so this is
        // rejected here instead of surfacing later as a provisioner-side error.
        assertEquals("ValidationError", exception.getErrorCode());
        assertEquals("Resource with id [MyHttpApi] is invalid. 'DefinitionUri' requires "
                        + "Bucket and Key properties to be specified.",
                exception.getMessage());
    }

    @Test
    void expandSamTemplate_httpApiWithArrayDefinitionUriIsRejected() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyHttpApi": {
                  "Type": "AWS::Serverless::HttpApi",
                  "Properties": {
                    "DefinitionUri": ["s3://api-specs/openapi.yaml"]
                  }
                }
              }
            }
            """);

        AwsException exception = assertThrows(AwsException.class,
                () -> processor.expandSamTemplate(template));

        // Measured against real AWS, us-east-1, create-change-set: an array DefinitionUri on an
        // HttpApi fails with the same third wording as the StateMachine case below, not silently
        // with no Body and no BodyS3Location.
        assertEquals("ValidationError", exception.getErrorCode());
        assertEquals("Resource with id [MyHttpApi] is invalid. Type of property "
                        + "'DefinitionUri' is invalid.",
                exception.getMessage());
    }

    @Test
    void expandSamTemplate_httpApiWithBothDefinitionBodyAndDefinitionUriIsRejected() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyHttpApi": {
                  "Type": "AWS::Serverless::HttpApi",
                  "Properties": {
                    "DefinitionBody": { "openapi": "3.0.1", "paths": {} },
                    "DefinitionUri": "s3://api-specs/openapi.yaml"
                  }
                }
              }
            }
            """);

        AwsException exception = assertThrows(AwsException.class,
                () -> processor.expandSamTemplate(template));

        // Measured against real AWS, us-east-1, create-change-set: an HttpApi declaring both
        // DefinitionBody and DefinitionUri is rejected, DefinitionUri named first, the reverse
        // order from the equivalent StateMachine message below. floci must not silently prefer
        // DefinitionBody and emit Body with no error.
        assertEquals("ValidationError", exception.getErrorCode());
        assertEquals("Resource with id [MyHttpApi] is invalid. Specify either 'DefinitionUri' "
                        + "or 'DefinitionBody' property and not both.",
                exception.getMessage());
    }

    @Test
    void expandSamTemplate_httpApiWithLocalDefinitionUriIsRejected() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyHttpApi": {
                  "Type": "AWS::Serverless::HttpApi",
                  "Properties": {
                    "DefinitionUri": "./openapi.yaml"
                  }
                }
              }
            }
            """);

        AwsException exception = assertThrows(AwsException.class,
                () -> processor.expandSamTemplate(template));

        // Measured against real AWS, us-east-1, create-change-set: a local-path DefinitionUri
        // (the shape an unpackaged `sam` template carries) is rejected with the same string-form
        // wording the StateMachine arm already carries, not silently accepted with no Body and
        // no BodyS3Location.
        assertEquals("ValidationError", exception.getErrorCode());
        assertEquals("Resource with id [MyHttpApi] is invalid. 'DefinitionUri' is not a valid S3 "
                        + "Uri of the form 's3://bucket/key' with optional versionId query "
                        + "parameter.",
                exception.getMessage());
    }

    @Test
    void expandSamTemplate_stateMachineBecomesStepFunctionsStateMachine() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "OrdersStateMachine": {
                  "Type": "AWS::Serverless::StateMachine",
                  "Properties": {
                    "Name": "orders-sm",
                    "Type": "EXPRESS",
                    "Role": "arn:aws:iam::000000000000:role/orders-sfn-role",
                    "DefinitionUri": "s3://asl-definitions/orders.asl.json",
                    "DefinitionSubstitutions": { "OrdersTable": "orders" },
                    "Tracing": { "Enabled": true },
                    "Logging": {
                      "Level": "ALL",
                      "IncludeExecutionData": true,
                      "Destinations": [
                        { "CloudWatchLogsLogGroup": { "LogGroupArn": "arn:aws:logs:us-east-1:000000000000:log-group:/orders:*" } }
                      ]
                    },
                    "Tags": { "team": "orders" }
                  }
                }
              }
            }
            """);

        JsonNode resources = processor.expandSamTemplate(template).path("Resources");

        assertFalse(resources.path("OrdersStateMachine").isMissingNode(),
                "SAM StateMachine must survive the transform under the same logical id, not vanish");
        JsonNode machine = resources.path("OrdersStateMachine");
        assertEquals("AWS::StepFunctions::StateMachine", machine.path("Type").asText());
        JsonNode props = machine.path("Properties");
        assertEquals("orders-sm", props.path("StateMachineName").asText());
        assertEquals("EXPRESS", props.path("StateMachineType").asText());
        assertEquals("arn:aws:iam::000000000000:role/orders-sfn-role", props.path("RoleArn").asText());
        assertEquals("asl-definitions", props.path("DefinitionS3Location").path("Bucket").asText());
        assertEquals("orders.asl.json", props.path("DefinitionS3Location").path("Key").asText());
        assertEquals("orders", props.path("DefinitionSubstitutions").path("OrdersTable").asText());
        assertTrue(props.path("TracingConfiguration").path("Enabled").asBoolean());
        assertEquals("ALL", props.path("LoggingConfiguration").path("Level").asText());

        JsonNode tags = props.path("Tags");
        assertTrue(tags.isArray(), "SAM's Tags map must become the native list-of-{Key,Value} shape");
        assertEquals(2, tags.size());
        assertEquals("stateMachine:createdBy", tags.get(0).path("Key").asText());
        assertEquals("SAM", tags.get(0).path("Value").asText());
        assertEquals("team", tags.get(1).path("Key").asText());
        assertEquals("orders", tags.get(1).path("Value").asText());
    }

    @Test
    void expandSamTemplate_stateMachineWithLocalDefinitionUriIsRejected() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "OrdersStateMachine": {
                  "Type": "AWS::Serverless::StateMachine",
                  "Properties": {
                    "Name": "orders-sm",
                    "Role": "arn:aws:iam::000000000000:role/orders-sfn-role",
                    "DefinitionUri": "./src/stepFunctions/orders.asl.json"
                  }
                }
              }
            }
            """);

        AwsException exception = assertThrows(AwsException.class,
                () -> processor.expandSamTemplate(template));

        // Measured against real AWS, us-east-1, create-change-set does not
        // reject the API call: it creates the change set and marks it FAILED with this exact
        // sentence in StatusReason. The message is asserted as a literal, not a substring, so an
        // invented wording can no longer pass this test.
        assertEquals("ValidationError", exception.getErrorCode());
        assertEquals("Resource with id [OrdersStateMachine] is invalid. 'DefinitionUri' is not a "
                        + "valid S3 Uri of the form 's3://bucket/key' with optional versionId "
                        + "query parameter.",
                exception.getMessage());
    }

    @Test
    void expandSamTemplate_stateMachineWithQuestionMarkInKeyIsAccepted() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "OrdersStateMachine": {
                  "Type": "AWS::Serverless::StateMachine",
                  "Properties": {
                    "Role": "arn:aws:iam::000000000000:role/orders-sfn-role",
                    "DefinitionUri": "s3://asl-definitions/orders.asl.json?foo=1"
                  }
                }
              }
            }
            """);

        JsonNode location = processor.expandSamTemplate(template)
                .path("Resources").path("OrdersStateMachine").path("Properties").path("DefinitionS3Location");

        // S3 permits a literal '?' in a key; only a trailing '?versionId=' is a query separator,
        // so a key carrying an unrelated query string must still match, keeping the '?' in Key.
        assertEquals("asl-definitions", location.path("Bucket").asText());
        assertEquals("orders.asl.json?foo=1", location.path("Key").asText(),
                "a '?' not followed by 'versionId=' belongs to the key, not a stripped query string");
        assertTrue(location.path("Version").isMissingNode(), "no versionId means no Version field");
    }

    @Test
    void expandSamTemplate_stateMachineWithS3UriMissingKeyIsRejected() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "OrdersStateMachine": {
                  "Type": "AWS::Serverless::StateMachine",
                  "Properties": {
                    "Role": "arn:aws:iam::000000000000:role/orders-sfn-role",
                    "DefinitionUri": "s3://asl-definitions"
                  }
                }
              }
            }
            """);

        AwsException exception = assertThrows(AwsException.class,
                () -> processor.expandSamTemplate(template));

        // A bucket-only DefinitionUri fails the same 's3://bucket/key' shape check as a local
        // path; the two must not be told apart into different messages, and the failure must
        // surface here rather than downstream from the provisioner's generic "Specify exactly
        // one of Definition, DefinitionString, or DefinitionS3Location", which names neither the
        // logical id nor DefinitionUri nor the value.
        assertEquals("ValidationError", exception.getErrorCode());
        assertEquals("Resource with id [OrdersStateMachine] is invalid. 'DefinitionUri' is not a "
                        + "valid S3 Uri of the form 's3://bucket/key' with optional versionId "
                        + "query parameter.",
                exception.getMessage());
    }

    @Test
    void expandSamTemplate_stateMachineWithIntrinsicDefinitionUriIsRejected() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "OrdersStateMachine": {
                  "Type": "AWS::Serverless::StateMachine",
                  "Properties": {
                    "Role": "arn:aws:iam::000000000000:role/orders-sfn-role",
                    "DefinitionUri": { "Fn::Sub": "s3://${SpecBucket}/orders.asl.json" }
                  }
                }
              }
            }
            """);

        AwsException exception = assertThrows(AwsException.class,
                () -> processor.expandSamTemplate(template));

        // Measured against real AWS, us-east-1, create-change-set: an intrinsic DefinitionUri
        // (no literal Bucket/Key) fails the SAM transform with the object-form wording, distinct
        // from the string-form wording the local-path and bucket-only cases above carry.
        assertEquals("ValidationError", exception.getErrorCode());
        assertEquals("Resource with id [OrdersStateMachine] is invalid. 'DefinitionUri' requires "
                        + "Bucket and Key properties to be specified.",
                exception.getMessage());
    }

    @Test
    void expandSamTemplate_stateMachineWithObjectDefinitionUriMissingKeyIsRejected() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "OrdersStateMachine": {
                  "Type": "AWS::Serverless::StateMachine",
                  "Properties": {
                    "Role": "arn:aws:iam::000000000000:role/orders-sfn-role",
                    "DefinitionUri": { "Bucket": "asl-definitions" }
                  }
                }
              }
            }
            """);

        AwsException exception = assertThrows(AwsException.class,
                () -> processor.expandSamTemplate(template));

        // An object DefinitionUri that names Bucket but not Key must be rejected the same way as
        // one that names neither: only a Bucket-and-Key pair resolves to a location the native
        // DefinitionS3Location property accepts.
        assertEquals("ValidationError", exception.getErrorCode());
        assertEquals("Resource with id [OrdersStateMachine] is invalid. 'DefinitionUri' requires "
                        + "Bucket and Key properties to be specified.",
                exception.getMessage());
    }

    @Test
    void expandSamTemplate_stateMachineWithNullKeyDefinitionUriIsRejected() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "OrdersStateMachine": {
                  "Type": "AWS::Serverless::StateMachine",
                  "Properties": {
                    "Role": "arn:aws:iam::000000000000:role/orders-sfn-role",
                    "DefinitionUri": { "Bucket": "asl-definitions", "Key": null }
                  }
                }
              }
            }
            """);

        AwsException exception = assertThrows(AwsException.class,
                () -> processor.expandSamTemplate(template));

        // YAML `Key:` with no value (and JSON's explicit null) parses to a NullNode. Jackson's
        // has("Key") returns true for a null-valued field, so the guard must check the resolved
        // location actually carries a Key value, not merely that the field is present.
        assertEquals("ValidationError", exception.getErrorCode());
        assertEquals("Resource with id [OrdersStateMachine] is invalid. 'DefinitionUri' requires "
                        + "Bucket and Key properties to be specified.",
                exception.getMessage());
    }

    @Test
    void expandSamTemplate_stateMachineWithArrayDefinitionUriIsRejected() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "OrdersStateMachine": {
                  "Type": "AWS::Serverless::StateMachine",
                  "Properties": {
                    "Role": "arn:aws:iam::000000000000:role/orders-sfn-role",
                    "DefinitionUri": ["s3://asl-definitions/orders.asl.json"]
                  }
                }
              }
            }
            """);

        AwsException exception = assertThrows(AwsException.class,
                () -> processor.expandSamTemplate(template));

        // Measured against real AWS, us-east-1, create-change-set: a DefinitionUri of any JSON
        // type other than string or object (array, number, boolean) fails with this third
        // wording, distinct from both the string-form and object-form messages above.
        assertEquals("ValidationError", exception.getErrorCode());
        assertEquals("Resource with id [OrdersStateMachine] is invalid. Type of property "
                        + "'DefinitionUri' is invalid.",
                exception.getMessage());
    }

    @Test
    void expandSamTemplate_stateMachineWithVersionedDefinitionUriSplitsVersion() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "OrdersStateMachine": {
                  "Type": "AWS::Serverless::StateMachine",
                  "Properties": {
                    "Role": "arn:aws:iam::000000000000:role/orders-sfn-role",
                    "DefinitionUri": "s3://asl-definitions/orders.asl.json?versionId=abc123"
                  }
                }
              }
            }
            """);

        JsonNode location = processor.expandSamTemplate(template)
                .path("Resources").path("OrdersStateMachine").path("Properties").path("DefinitionS3Location");

        // Measured against real AWS, us-east-1: a versioned DefinitionUri splits into a
        // third field rather than folding the query string into Key:
        // {"Bucket": "...", "Key": "...", "Version": "abc123"}.
        assertEquals("asl-definitions", location.path("Bucket").asText());
        assertEquals("orders.asl.json", location.path("Key").asText(),
                "the '?versionId=...' query string must not be folded into Key");
        assertEquals("abc123", location.path("Version").asText());
    }

    @Test
    void expandSamTemplate_stateMachinePreservesMetadataAndDependsOn() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "OrdersRole": {
                  "Type": "AWS::IAM::Role",
                  "Properties": {}
                },
                "OrdersStateMachine": {
                  "Type": "AWS::Serverless::StateMachine",
                  "Metadata": { "SamResourceId": "OrdersStateMachine" },
                  "DependsOn": "OrdersRole",
                  "Properties": {
                    "Role": "arn:aws:iam::000000000000:role/orders-sfn-role",
                    "DefinitionUri": "s3://asl-definitions/orders.asl.json"
                  }
                }
              }
            }
            """);

        JsonNode machine = processor.expandSamTemplate(template).path("Resources").path("OrdersStateMachine");

        // Measured against real AWS, us-east-1: Metadata carries through on 67 of 67 deployed
        // state machines. CloudFormation, not SAM, writes it into the Processed template, so any
        // sibling of Type/Properties on the SAM resource node must survive the same way. This
        // measurement covers Metadata only; DependsOn survival is asserted below as the same
        // generic sibling-copy behaviour, not as a separately measured claim.
        assertEquals("OrdersStateMachine", machine.path("Metadata").path("SamResourceId").asText(),
                "Metadata must survive the transform with its literal value");
        assertEquals("OrdersRole", machine.path("DependsOn").asText(),
                "a declared DependsOn must survive the transform");
    }

    @Test
    void expandSamTemplate_stateMachinePreservesIntrinsicNameAndRole() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "OrdersRole": {
                  "Type": "AWS::IAM::Role",
                  "Properties": {}
                },
                "OrdersStateMachine": {
                  "Type": "AWS::Serverless::StateMachine",
                  "Properties": {
                    "Name": { "Fn::Sub": "${Environment}-orders-sm" },
                    "Role": { "Fn::GetAtt": ["OrdersRole", "Arn"] },
                    "DefinitionUri": "s3://asl-definitions/orders.asl.json"
                  }
                }
              }
            }
            """);

        JsonNode props = processor.expandSamTemplate(template)
                .path("Resources").path("OrdersStateMachine").path("Properties");

        // copyRenamed must deep-copy the node, never read it with asText(): asText() on an object
        // node silently returns "", which would collapse RoleArn/StateMachineName to an empty
        // string instead of failing loudly.
        assertTrue(props.path("StateMachineName").isObject(),
                "an intrinsic Name must survive as an object, not be stringified");
        assertEquals("${Environment}-orders-sm", props.path("StateMachineName").path("Fn::Sub").asText());
        assertTrue(props.path("RoleArn").isObject(),
                "an intrinsic Role must survive as an object, not be stringified");
        JsonNode getAtt = props.path("RoleArn").path("Fn::GetAtt");
        assertEquals("OrdersRole", getAtt.get(0).asText());
        assertEquals("Arn", getAtt.get(1).asText());
    }

    @Test
    void expandSamTemplate_stateMachineWithEmptyTagMapEmitsOnlyTheSamTag() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "OrdersStateMachine": {
                  "Type": "AWS::Serverless::StateMachine",
                  "Properties": {
                    "Role": "arn:aws:iam::000000000000:role/orders-sfn-role",
                    "DefinitionUri": "s3://asl-definitions/orders.asl.json",
                    "Tags": {}
                  }
                }
              }
            }
            """);

        JsonNode tags = processor.expandSamTemplate(template)
                .path("Resources").path("OrdersStateMachine").path("Properties").path("Tags");

        assertTrue(tags.isArray());
        assertEquals(1, tags.size(), "an empty Tags map must not emit zero entries; SAM still adds its own");
        assertEquals("stateMachine:createdBy", tags.get(0).path("Key").asText());
        assertEquals("SAM", tags.get(0).path("Value").asText());
    }

    @Test
    void expandSamTemplate_stateMachineTagValueKeepsItsJsonType() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "OrdersStateMachine": {
                  "Type": "AWS::Serverless::StateMachine",
                  "Properties": {
                    "Role": "arn:aws:iam::000000000000:role/orders-sfn-role",
                    "DefinitionUri": "s3://asl-definitions/orders.asl.json",
                    "Tags": {
                      "project-id": 12345678,
                      "environment": { "Ref": "Environment" }
                    }
                  }
                }
              }
            }
            """);

        JsonNode tags = processor.expandSamTemplate(template)
                .path("Resources").path("OrdersStateMachine").path("Properties").path("Tags");

        JsonNode projectIdTag = null;
        JsonNode environmentTag = null;
        for (JsonNode tag : tags) {
            if ("project-id".equals(tag.path("Key").asText())) {
                projectIdTag = tag;
            }
            if ("environment".equals(tag.path("Key").asText())) {
                environmentTag = tag;
            }
        }

        assertTrue(projectIdTag.path("Value").isNumber(),
                "a numeric tag value must survive as a JSON number, not be stringified");
        assertEquals(12345678, projectIdTag.path("Value").asInt());
        assertTrue(environmentTag.path("Value").isObject(),
                "an intrinsic tag value must survive as an object, not be stringified");
        assertEquals("Environment", environmentTag.path("Value").path("Ref").asText());
    }

    @Test
    void expandSamTemplate_stateMachineWithInlineDefinition() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "OrdersStateMachine": {
                  "Type": "AWS::Serverless::StateMachine",
                  "Properties": {
                    "Role": "arn:aws:iam::000000000000:role/orders-sfn-role",
                    "Definition": {
                      "StartAt": "Done",
                      "States": { "Done": { "Type": "Pass", "End": true } }
                    }
                  }
                }
              }
            }
            """);

        JsonNode machine = processor.expandSamTemplate(template).path("Resources").path("OrdersStateMachine");
        JsonNode props = machine.path("Properties");

        assertEquals("AWS::StepFunctions::StateMachine", machine.path("Type").asText(),
                "a Definition-only machine must still be expanded to the native type");
        assertTrue(props.path("DefinitionS3Location").isMissingNode(),
                "no DefinitionUri was given, so no DefinitionS3Location must appear");
        assertEquals("Done", props.path("Definition").path("StartAt").asText());
        assertEquals("Pass", props.path("Definition").path("States").path("Done").path("Type").asText());
    }

    @Test
    void expandSamTemplate_stateMachineWithNeitherDefinitionNorDefinitionUriIsRejected() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "OrdersStateMachine": {
                  "Type": "AWS::Serverless::StateMachine",
                  "Properties": {
                    "Role": "arn:aws:iam::000000000000:role/orders-sfn-role"
                  }
                }
              }
            }
            """);

        AwsException exception = assertThrows(AwsException.class,
                () -> processor.expandSamTemplate(template));

        // Measured against real AWS, us-east-1, create-change-set: a StateMachine with neither
        // Definition nor DefinitionUri is rejected before a single resource is provisioned,
        // rather than emitting an AWS::StepFunctions::StateMachine with no definition at all.
        assertEquals("ValidationError", exception.getErrorCode());
        assertEquals("Resource with id [OrdersStateMachine] is invalid. Either 'Definition' or "
                        + "'DefinitionUri' property must be specified.",
                exception.getMessage());
    }

    @Test
    void expandSamTemplate_stateMachineWithBothDefinitionAndDefinitionUriIsRejected() throws Exception {
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "OrdersStateMachine": {
                  "Type": "AWS::Serverless::StateMachine",
                  "Properties": {
                    "Role": "arn:aws:iam::000000000000:role/orders-sfn-role",
                    "Definition": {
                      "StartAt": "Done",
                      "States": { "Done": { "Type": "Pass", "End": true } }
                    },
                    "DefinitionUri": "s3://asl-definitions/orders.asl.json"
                  }
                }
              }
            }
            """);

        AwsException exception = assertThrows(AwsException.class,
                () -> processor.expandSamTemplate(template));

        // Measured against real AWS, us-east-1, create-change-set: a StateMachine declaring both
        // Definition and a resolvable DefinitionUri is rejected, not expanded into one native
        // resource carrying both Definition and DefinitionS3Location.
        assertEquals("ValidationError", exception.getErrorCode());
        assertEquals("Resource with id [OrdersStateMachine] is invalid. Specify either "
                        + "'Definition' or 'DefinitionUri' property and not both.",
                exception.getMessage());
    }

    @Test
    void unexpandedSamResourceTypeWarns() throws Exception {
        // A SAM type this processor cannot expand is left in the template, where the provisioner
        // stubs it. Reported at debug that is the first of two stacked silences on one path, so
        // the type reaches the stub arm with nothing said about the expansion that never happened.
        JsonNode template = objectMapper.readTree("""
            {
              "Transform": "AWS::Serverless-2016-10-31",
              "Resources": {
                "MyApp": {
                  "Type": "AWS::Serverless::Application",
                  "Properties": {
                    "Location": "s3://example-templates/app.yaml"
                  }
                }
              }
            }
            """);

        java.util.List<java.util.logging.LogRecord> logged = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Logger logger =
                java.util.logging.Logger.getLogger(SamTransformProcessor.class.getName());
        java.util.logging.Level original = logger.getLevel();
        java.util.logging.Handler handler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord logRecord) {
                logged.add(logRecord);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        logger.setLevel(java.util.logging.Level.ALL);
        try {
            processor.expandSamTemplate(template);
        } finally {
            logger.setLevel(original);
            logger.removeHandler(handler);
        }

        String expected = "Unsupported SAM resource type AWS::Serverless::Application (MyApp): "
                + "left in the template for the CloudFormation provisioner.";
        assertTrue(logged.stream().anyMatch(r ->
                        r.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()
                                && expected.equals(text(r))),
                "expected the unexpanded SAM type to be reported at warn, got: "
                        + logged.stream().map(r -> r.getLevel() + " " + text(r)).toList());
    }

    /**
     * The record's text. Which logging backend is in play decides whether the parameters are
     * already substituted or still carried alongside the pattern.
     */
    private static String text(java.util.logging.LogRecord record) {
        Object[] parameters = record.getParameters();
        return parameters == null || parameters.length == 0
                ? record.getMessage()
                : java.text.MessageFormat.format(record.getMessage(), parameters);
    }
}
