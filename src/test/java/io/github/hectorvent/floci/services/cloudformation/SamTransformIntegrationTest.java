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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.nullValue;

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

        waitForStackStatus(stackName, "CREATE_COMPLETE");

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
    void samFunction_withAutoPublishAlias_createsVersionAndAlias() {
        String stackName = "sam-alias-stack";
        stacksToDelete.add(stackName);

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              AliasFunction:
                Type: AWS::Serverless::Function
                Properties:
                  FunctionName: sam-alias-func
                  Handler: index.handler
                  Runtime: nodejs20.x
                  AutoPublishAlias: production
                  InlineCode: |
                    exports.handler = async () => ({ ok: true });
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

        waitForStackStatus(stackName, "CREATE_COMPLETE");

        // The point of the fix: an alias-qualified reference resolves. Before it, this 404'd with
        // "Alias not found: production" even though the template declared the alias.
        given()
        .when()
            .get("/2015-03-31/functions/sam-alias-func/aliases/production")
        .then()
            .statusCode(200)
            .body("Name", equalTo("production"))
            // $LATEST rather than the published version real SAM targets — see #1987/#1988 and the
            // comment in expandAutoPublishAlias. The invoke below is what actually matters.
            .body("FunctionVersion", equalTo("$LATEST"))
            .body("AliasArn", containsString(":function:sam-alias-func:production"));

        // The behavior the whole expansion exists for: an alias-qualified invoke runs the function.
        // Asserting only that the alias *record* exists is not enough — an alias pointing at a
        // published version satisfies that and still times out on invoke (#1987), which is how an
        // earlier revision of this change shipped a broken alias past its own tests.
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/2015-03-31/functions/sam-alias-func:production/invocations")
        .then()
            .statusCode(200)
            .header("X-Amz-Function-Error", nullValue())
            .body("ok", equalTo(true));

        given()
        .when()
            .get("/2015-03-31/functions/sam-alias-func/aliases")
        .then()
            .statusCode(200)
            .body("Aliases.size()", equalTo(1))
            .body("Aliases[0].Name", equalTo("production"));

        String resourcesXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(resourcesXml, containsString("<ResourceType>AWS::Lambda::Version</ResourceType>"));
        assertThat(resourcesXml, containsString("<ResourceType>AWS::Lambda::Alias</ResourceType>"));
        assertThat(resourcesXml, containsString("<LogicalResourceId>AliasFunctionAliasProduction</LogicalResourceId>"));
    }

    @Test
    void samFunction_withoutAutoPublishAlias_createsNoAlias() {
        String stackName = "sam-no-alias-stack";
        stacksToDelete.add(stackName);

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              PlainFunction:
                Type: AWS::Serverless::Function
                Properties:
                  FunctionName: sam-no-alias-func
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

        waitForStackStatus(stackName, "CREATE_COMPLETE");

        given()
        .when()
            .get("/2015-03-31/functions/sam-no-alias-func/aliases")
        .then()
            .statusCode(200)
            .body("Aliases.size()", equalTo(0));

        String resourcesXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(resourcesXml, not(containsString("AWS::Lambda::Version")));
        assertThat(resourcesXml, not(containsString("AWS::Lambda::Alias")));
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

        waitForStackStatus(stackName, "CREATE_COMPLETE");

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
    void samApi_definitionBodyCreatesApiGatewayMethods() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "sam-api-body-" + suffix;
        String apiName = "sam-api-body-" + suffix;
        stacksToDelete.add(stackName);

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              MyApi:
                Type: AWS::Serverless::Api
                Properties:
                  Name: %s
                  StageName: prod
                  DefinitionBody:
                    openapi: 3.0.1
                    paths:
                      /hello:
                        get:
                          x-amazon-apigateway-integration:
                            type: MOCK
                      /widgets:
                        post:
                          x-amazon-apigateway-integration:
                            type: MOCK
            """.formatted(apiName);

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

        waitForStackStatus(stackName, "CREATE_COMPLETE");

        String apiId = given()
        .when()
            .get("/restapis")
        .then()
            .statusCode(200)
            .body("item.find { it.name == '" + apiName + "' }.name", equalTo(apiName))
            .extract()
            .path("item.find { it.name == '" + apiName + "' }.id");

        String helloResourceId = given()
        .when()
            .get("/restapis/" + apiId + "/resources")
        .then()
            .statusCode(200)
            .body("item.path", hasItems("/hello", "/widgets"))
            .extract()
            .path("item.find { it.path == '/hello' }.id");

        given()
        .when()
            .get("/restapis/" + apiId + "/resources/" + helloResourceId + "/methods/GET/integration")
        .then()
            .statusCode(200)
            .body("type", equalTo("MOCK"));
    }

    @Test
    void samApi_declaredNameAndDescriptionSurviveMaterializedDefinitionBody() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "sam-api-name-" + suffix;
        String apiName = "sam-api-name-" + suffix;
        String apiDescription = "declared-description-" + suffix;
        stacksToDelete.add(stackName);

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              MyApi:
                Type: AWS::Serverless::Api
                Properties:
                  Name: %s
                  Description: %s
                  DefinitionBody:
                    openapi: 3.0.1
                    info: {title: title-from-document, description: description-from-document, version: '1.0'}
                    paths:
                      /hello:
                        get:
                          x-amazon-apigateway-integration:
                            type: MOCK
            """.formatted(apiName, apiDescription);

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

        waitForStackStatus(stackName, "CREATE_COMPLETE");

        // putRestApi overwrites Name and Description from the DefinitionBody's info, so the
        // provisioner re-applies the declared Name and Description immediately after: this
        // proves what floci's provisioner does with the processed template, not what real
        // AWS's runtime prefers. The /hello method assertion below proves the reapply runs on
        // the same path that also turns the DefinitionBody into resources and methods, so a
        // provisioner that never reads DefinitionBody at all cannot pass this test.
        String apiId = given()
        .when()
            .get("/restapis")
        .then()
            .statusCode(200)
            .body("item.name", hasItem(apiName))
            .body("item.name", not(hasItem("title-from-document")))
            .body("item.find { it.name == '" + apiName + "' }.description", equalTo(apiDescription))
            .body("item.description", not(hasItem("description-from-document")))
            .extract()
            .path("item.find { it.name == '" + apiName + "' }.id");

        String helloResourceId = given()
        .when()
            .get("/restapis/" + apiId + "/resources")
        .then()
            .statusCode(200)
            .body("item.path", hasItem("/hello"))
            .extract()
            .path("item.find { it.path == '/hello' }.id");

        given()
        .when()
            .get("/restapis/" + apiId + "/resources/" + helloResourceId + "/methods/GET/integration")
        .then()
            .statusCode(200)
            .body("type", equalTo("MOCK"));
    }

    @Test
    void samHttpApi_definitionBodyCreatesApiGatewayV2Routes() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "sam-http-api-" + suffix;
        String apiName = "sam-http-api-" + suffix;
        stacksToDelete.add(stackName);

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              HttpApi:
                Type: AWS::Serverless::HttpApi
                Properties:
                  Name: %s
                  DefinitionBody:
                    openapi: 3.0.1
                    paths:
                      /hello:
                        get: {}
                      /widgets:
                        post: {}
            """.formatted(apiName);

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

        waitForStackStatus(stackName, "CREATE_COMPLETE");

        String apiId = given()
        .when()
            .get("/v2/apis")
        .then()
            .statusCode(200)
            .body("items.find { it.name == '" + apiName + "' }.protocolType", equalTo("HTTP"))
            .extract()
            .path("items.find { it.name == '" + apiName + "' }.apiId");

        given()
        .when()
            .get("/v2/apis/" + apiId + "/routes")
        .then()
            .statusCode(200)
            .body("items.routeKey", hasItems("GET /hello", "POST /widgets"));
    }

    @Test
    void samHttpApi_matchingDefinitionBodyAndFunctionEventCreateOneIntegratedRoute() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "sam-http-api-overlap-" + suffix;
        String apiName = "sam-http-api-overlap-" + suffix;
        String functionName = "sam-http-overlap-fn-" + suffix;
        stacksToDelete.add(stackName);

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              HttpApi:
                Type: AWS::Serverless::HttpApi
                Properties:
                  Name: %s
                  Auth:
                    DefaultAuthorizer: JwtAuth
                    Authorizers:
                      JwtAuth:
                        IdentitySource: '$request.header.Authorization'
                        AuthorizationScopes: [read:items]
                        JwtConfiguration:
                          issuer: https://issuer.example.com
                          audience: [items-client]
                  DefinitionBody:
                    openapi: 3.0.1
                    info: {title: overlap, version: '1.0'}
                    components: {}
                    paths:
                      /items:
                        get:
                          responses: {'200': {description: ok}}
              Handler:
                Type: AWS::Serverless::Function
                Properties:
                  FunctionName: %s
                  Runtime: python3.12
                  Handler: index.handler
                  InlineCode: 'def handler(e,c): return {}'
                  Events:
                    Api:
                      Type: HttpApi
                      Properties:
                        ApiId: {Ref: HttpApi}
                        Path: /items
                        Method: GET
            """.formatted(apiName, functionName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        waitForStackStatus(stackName, "CREATE_COMPLETE");
        String apiId = apiIdForName(apiName);

        given()
        .when()
            .get("/v2/apis/" + apiId + "/routes")
        .then()
            .statusCode(200)
            .body("items.size()", equalTo(1))
            .body("items[0].routeKey", equalTo("GET /items"))
            .body("items[0].authorizationType", equalTo("JWT"))
            .body("items[0].authorizationScopes", hasItem("read:items"))
            .body("items[0].authorizerId", notNullValue())
            .body("items[0].target", containsString("integrations/"));

        given()
        .when()
            .get("/v2/apis/" + apiId + "/integrations")
        .then()
            .statusCode(200)
            .body("items.size()", equalTo(1))
            .body("items[0].integrationUri", containsString(functionName));
    }

    @Test
    void samHttpApi_intrinsicDefinitionUriIsRejected() {
        // Real AWS (measured against us-east-1 via create-change-set) rejects an HttpApi
        // DefinitionUri that never resolves to a literal Bucket/Key the same way it rejects that
        // shape on a StateMachine: expandServerlessHttpApi shares the DefinitionUri guard with
        // expandServerlessStateMachine, so the transform fails before a single resource is
        // provisioned, rather than importing the intrinsic as an unusable BodyS3Location.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "sam-http-api-uri-" + suffix;
        String apiName = "sam-http-api-uri-" + suffix;
        stacksToDelete.add(stackName);

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              HttpApi:
                Type: AWS::Serverless::HttpApi
                Properties:
                  Name: %s
                  DefinitionUri:
                    Fn::Sub: s3://${SpecBucket}/openapi.json
            """.formatted(apiName);

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

        waitForStackStatus(stackName, "CREATE_FAILED");

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>")))
            // XmlBuilder.elem escapes the single quotes AWS's own wording uses around
            // 'DefinitionUri' to &apos;.
            .body(containsString("Resource with id [HttpApi] is invalid. "
                    + "&apos;DefinitionUri&apos; requires Bucket and Key properties to be "
                    + "specified."));
    }

    @Test
    void samHttpApi_definitionUriCreatesApiGatewayV2Routes() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "sam-http-api-uri-" + suffix;
        String apiName = "sam-http-api-uri-" + suffix;
        String bucketName = "sam-http-api-spec-" + suffix;
        stacksToDelete.add(stackName);

        given()
        .when()
            .put("/" + bucketName)
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("""
                {"openapi":"3.0.1","paths":{"/from-uri":{"get":{}}}}
                """)
        .when()
            .put("/" + bucketName + "/openapi.json")
        .then()
            .statusCode(200);

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              HttpApi:
                Type: AWS::Serverless::HttpApi
                Properties:
                  Name: %s
                  DefinitionUri: s3://%s/openapi.json
            """.formatted(apiName, bucketName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        waitForStackStatus(stackName, "CREATE_COMPLETE");

        String apiId = apiIdForName(apiName);
        given()
        .when()
            .get("/v2/apis/" + apiId + "/routes")
        .then()
            .statusCode(200)
            .body("items.routeKey", hasItem("GET /from-uri"));
    }

    @Test
    void samHttpApi_definitionBodyReconcilesRoutesOnStackUpdate() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "sam-http-api-update-" + suffix;
        String apiName = "sam-http-api-update-" + suffix;
        stacksToDelete.add(stackName);

        String initialTemplate = httpApiTemplate(apiName, "/before");
        String updatedTemplate = httpApiTemplate(apiName, "/after");

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", initialTemplate)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        waitForStackStatus(stackName, "CREATE_COMPLETE");
        String apiId = apiIdForName(apiName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", updatedTemplate)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        waitForStackStatus(stackName, "UPDATE_COMPLETE");

        given()
        .when()
            .get("/v2/apis/" + apiId + "/routes")
        .then()
            .statusCode(200)
            .body("items.routeKey", hasItem("GET /after"))
            .body("items.routeKey", not(hasItem("GET /before")));
    }

    private static String httpApiTemplate(String apiName, String path) {
        return """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              HttpApi:
                Type: AWS::Serverless::HttpApi
                Properties:
                  Name: %s
                  DefinitionBody:
                    openapi: 3.0.1
                    paths:
                      %s:
                        get: {}
            """.formatted(apiName, path);
    }

    private static String apiIdForName(String apiName) {
        return given()
        .when()
            .get("/v2/apis")
        .then()
            .statusCode(200)
            .body("items.find { it.name == '" + apiName + "' }.protocolType", equalTo("HTTP"))
            .extract()
            .path("items.find { it.name == '" + apiName + "' }.apiId");
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
    void samFunction_viaChangeSet_createsLambda() throws Exception {
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

        waitForStackStatus(stackName, "CREATE_COMPLETE");

        given()
        .when()
            .get("/2015-03-31/functions/sam-changeset-func")
        .then()
            .statusCode(200)
            .body("Configuration.FunctionName", equalTo("sam-changeset-func"));
    }

    @Test
    void samStateMachine_createsStepFunctionsStateMachine() {
        String stackName = "sam-sfn-stack";
        stacksToDelete.add(stackName);

        given()
        .when()
            .put("/sam-asl-bucket")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Pass\",\"End\":true}}}")
        .when()
            .put("/sam-asl-bucket/orders.asl.json")
        .then()
            .statusCode(200);

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              OrdersStateMachine:
                Type: AWS::Serverless::StateMachine
                Properties:
                  Name: sam-orders-sm
                  Type: STANDARD
                  Role: arn:aws:iam::000000000000:role/orders-sfn-role
                  DefinitionUri: s3://sam-asl-bucket/orders.asl.json
            Outputs:
              MachineArn:
                Value: !GetAtt OrdersStateMachine.Arn
              MachineName:
                Value: !GetAtt OrdersStateMachine.Name
              MachineRevisionId:
                Value: !GetAtt OrdersStateMachine.StateMachineRevisionId
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

        waitForStackStatus(stackName, "CREATE_COMPLETE");

        String describeStacks = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        // AGENTS.md:289-291 asks for the exact Fn::GetAtt key set the provisioner publishes
        // (CloudFormationResourceProvisioner.java:4231-4233): Arn, Name, StateMachineRevisionId.
        // Asserting only "not a stub ARN" (as red loop 2 did) would also pass on an empty Output.
        String machineArn = stackOutputValue(describeStacks, "MachineArn");
        assertThat(machineArn, startsWith("arn:aws:states:"));
        assertThat(machineArn, not(containsString("arn:aws:stub:::")));
        assertThat(stackOutputValue(describeStacks, "MachineName"), equalTo("sam-orders-sm"));
        assertThat(stackOutputValue(describeStacks, "MachineRevisionId"), not(equalTo("")));

        given()
            .header("X-Amz-Target", "AWSStepFunctions.ListStateMachines")
            .contentType("application/x-amz-json-1.0")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("stateMachines.name", hasItem("sam-orders-sm"));
    }

    @Test
    void samStateMachine_withLocalDefinitionUri_createFails() {
        // This is the shape all 172 source templates measured across 19 repositories carry:
        // `sam package` never ran, so DefinitionUri is still a local path. Real SAM rejects this
        // the same way, and here the ValidationError is thrown by expandServerlessStateMachine
        // inside expandSamTemplate, called before the per-resource provisioning loop starts. With
        // no resource attempted, there is nothing to roll back: the stack lands directly on
        // CREATE_FAILED, not ROLLBACK_COMPLETE.
        String stackName = "sam-sfn-local-uri-stack";
        stacksToDelete.add(stackName);

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              OrdersStateMachine:
                Type: AWS::Serverless::StateMachine
                Properties:
                  Name: sam-orders-local-sm
                  Role: arn:aws:iam::000000000000:role/orders-sfn-role
                  DefinitionUri: ./src/stepFunctions/orders.asl.json
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

        waitForStackStatus(stackName, "CREATE_FAILED");

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>")))
            // XmlBuilder.elem escapes the single quotes AWS's own wording uses around
            // 'DefinitionUri' and 's3://bucket/key' to &apos;.
            .body(containsString("Resource with id [OrdersStateMachine] is invalid. "
                    + "&apos;DefinitionUri&apos; is not a valid S3 Uri of the form "
                    + "&apos;s3://bucket/key&apos; with optional versionId query parameter."));

        // Nothing was provisioned: the transform rejects the whole template before the
        // per-resource loop attempts a single resource, so no state machine (stub or real)
        // ever appears in the resource list.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<member>")))
            .body(not(containsString("AWS::StepFunctions::StateMachine")));
    }

    @Test
    void samStateMachine_withLocalDefinitionUri_createChangeSetReportsFailedStatus() {
        // Measured against real AWS, us-east-1, aws cloudformation create-change-set
        // does not reject this API call with an HTTP error: it creates the change set and marks
        // it FAILED, reporting the transform's own message in StatusReason with 0 Changes.
        String stackName = "sam-sfn-changeset-local-uri-stack";
        stacksToDelete.add(stackName);

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              OrdersStateMachine:
                Type: AWS::Serverless::StateMachine
                Properties:
                  Name: sam-orders-changeset-local-sm
                  Role: arn:aws:iam::000000000000:role/orders-sfn-role
                  DefinitionUri: ./src/stepFunctions/orders.asl.json
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "initial")
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
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "initial")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Status>FAILED</Status>"))
            .body(containsString("<ExecutionStatus>UNAVAILABLE</ExecutionStatus>"))
            .body(containsString("<StatusReason>Transform AWS::Serverless-2016-10-31 failed "
                    + "with: Invalid Serverless Application Specification document. Number of "
                    + "errors found: 1. Resource with id [OrdersStateMachine] is invalid. "
                    + "&apos;DefinitionUri&apos; is not a valid S3 Uri of the form "
                    + "&apos;s3://bucket/key&apos; with optional versionId query "
                    + "parameter.</StatusReason>"))
            .body(not(containsString("<member>")));
    }

    @Test
    void samStateMachine_withIntrinsicDefinitionUri_createChangeSetReportsFailedStatus() {
        // Measured against real AWS, us-east-1: DefinitionUri as an intrinsic (no literal
        // Bucket/Key) fails the SAM transform itself with the object-form wording, the same
        // Status: FAILED / ExecutionStatus: UNAVAILABLE / 0 Changes shape as the local-path and
        // bucket-only cases. Left unrejected, this shape reaches CREATE_COMPLETE against floci
        // while real AWS already reports FAILED at create-change-set time.
        String stackName = "sam-sfn-changeset-intrinsic-uri-stack";
        stacksToDelete.add(stackName);

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              OrdersStateMachine:
                Type: AWS::Serverless::StateMachine
                Properties:
                  Name: sam-orders-changeset-intrinsic-sm
                  Role: arn:aws:iam::000000000000:role/orders-sfn-role
                  DefinitionUri:
                    Fn::Sub: s3://${SpecBucket}/orders.asl.json
            """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "initial")
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
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "initial")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Status>FAILED</Status>"))
            .body(containsString("<ExecutionStatus>UNAVAILABLE</ExecutionStatus>"))
            .body(containsString("<StatusReason>Transform AWS::Serverless-2016-10-31 failed "
                    + "with: Invalid Serverless Application Specification document. Number of "
                    + "errors found: 1. Resource with id [OrdersStateMachine] is invalid. "
                    + "&apos;DefinitionUri&apos; requires Bucket and Key properties to be "
                    + "specified.</StatusReason>"))
            .body(not(containsString("<member>")));
    }

    @Test
    void samStateMachine_withLocalDefinitionUri_executeChangeSetIsRejected() {
        // A change set a failed SAM transform already marked FAILED/UNAVAILABLE cannot be
        // executed. Real CloudFormation refuses ExecuteChangeSet with InvalidChangeSetStatus,
        // naming the change set's ARN and its current status, instead of running it and landing
        // CREATE_FAILED, which would burn the stack name (see #2207).
        String stackName = "sam-sfn-changeset-execute-rejected-stack";
        stacksToDelete.add(stackName);

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              OrdersStateMachine:
                Type: AWS::Serverless::StateMachine
                Properties:
                  Name: sam-orders-execute-rejected-sm
                  Role: arn:aws:iam::000000000000:role/orders-sfn-role
                  DefinitionUri: ./src/stepFunctions/orders.asl.json
            """;

        String createChangeSetXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "initial")
            .formParam("ChangeSetType", "CREATE")
            .formParam("TemplateBody", template)
            .formParam("Capabilities.member.1", "CAPABILITY_IAM")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Id>"))
            .extract().asString();
        String changeSetId = createChangeSetXml.split("<Id>")[1].split("</Id>")[0];

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ExecuteChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "initial")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidChangeSetStatus</Code>"))
            .body(containsString("<Message>ChangeSet [" + changeSetId + "] cannot be executed in "
                    + "its current status of [FAILED]</Message>"));

        // The AWS CLI's has_stack treats a REVIEW_IN_PROGRESS stack as nonexistent (see
        // createChangeSetForRequest's javadoc); the rejected execute must leave the stack there,
        // never CREATE_FAILED, so the name stays reusable rather than requiring an explicit
        // DeleteStack.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>REVIEW_IN_PROGRESS</StackStatus>"))
            .body(not(containsString("CREATE_FAILED")));
    }

    @Test
    void samStateMachine_withDefinitionSubstitutions_resolvesPlaceholders() {
        String stackName = "sam-sfn-subs-stack";
        stacksToDelete.add(stackName);

        given()
        .when()
            .put("/sam-asl-subs-bucket")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Pass\",\"Result\":\"${TableName}\",\"End\":true}}}")
        .when()
            .put("/sam-asl-subs-bucket/orders.asl.json")
        .then()
            .statusCode(200);

        String template = """
            AWSTemplateFormatVersion: '2010-09-09'
            Transform: AWS::Serverless-2016-10-31
            Resources:
              OrdersStateMachine:
                Type: AWS::Serverless::StateMachine
                Properties:
                  Name: sam-orders-subs-sm
                  Role: arn:aws:iam::000000000000:role/orders-sfn-role
                  DefinitionUri: s3://sam-asl-subs-bucket/orders.asl.json
                  DefinitionSubstitutions:
                    TableName: my-orders-table
            Outputs:
              MachineArn:
                Value: !GetAtt OrdersStateMachine.Arn
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

        waitForStackStatus(stackName, "CREATE_COMPLETE");

        String describeStacks = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
        String machineArn = stackOutputValue(describeStacks, "MachineArn");

        given()
            .header("X-Amz-Target", "AWSStepFunctions.DescribeStateMachine")
            .contentType("application/x-amz-json-1.0")
            .body("{\"stateMachineArn\":\"" + machineArn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("definition", containsString("my-orders-table"))
            .body("definition", not(containsString("${TableName}")));
    }

    private static String stackOutputValue(String describeStacksXml, String outputKey) {
        return describeStacksXml
                .split("<OutputKey>" + outputKey + "</OutputKey>")[1]
                .split("<OutputValue>")[1]
                .split("</OutputValue>")[0];
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

    private static void waitForStackStatus(String stackName, String status) {
        String expected = "<StackStatus>" + status + "</StackStatus>";
        String body = "";
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            body = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .asString();
            if (body.contains(expected)) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for stack " + stackName
                        + " to reach " + expected, e);
            }
        }
        assertThat(body, containsString(expected));
    }
}
