package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Deploys the AppSync shape a Serverless Framework {@code appSync:} block compiles to: an API with
 * inline schema, a DynamoDB and a relational data source, an {@code APPSYNC_JS} pipeline function
 * and the PIPELINE resolver that calls it, plus an API key, and asserts the control plane really
 * holds them.
 *
 * <p>The point of going through a stack rather than the provisioner alone is the ordering: the data
 * sources and resolvers are created behind the schema, whose compilation is asynchronous and locks
 * the API while it runs. It also pins the two {@code Fn::GetAtt} attributes such a template is
 * built on, {@code GraphQlApi.ApiId} and {@code FunctionConfiguration.FunctionId}: an unset
 * attribute resolves to the literal string {@code "LogicalId.Attr"} and every dependent resource
 * would then be wired to that text instead of an id.
 */
@QuarkusTest
class AppSyncCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/cloudformation/aws4_request";
    private static final String APPSYNC_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/appsync/aws4_request";
    private static final String STACK = "appsync-cfn-it";

    private static final String TEMPLATE = """
        {
          "Parameters": {"FieldLogLevel": {"Type": "String"}},
          "Resources": {
            "GraphQlApi": {
              "Type": "AWS::AppSync::GraphQLApi",
              "Properties": {
                "Name": "appsync-cfn-it-api",
                "AuthenticationType": "API_KEY",
                "XrayEnabled": false,
                "EnvironmentVariables": {"STRIPE_PRODUCT_SMALL_ID": "prod_small"},
                "LogConfig": {"FieldLogLevel": {"Ref": "FieldLogLevel"}}
              }
            },
            "GraphQlSchema": {
              "Type": "AWS::AppSync::GraphQLSchema",
              "Properties": {
                "ApiId": {"Fn::GetAtt": ["GraphQlApi", "ApiId"]},
                "Definition": "type Message { id: ID! } type Query { getMessages: [Message] }"
              }
            },
            "GraphQlDsAuthKeys": {
              "Type": "AWS::AppSync::DataSource",
              "DependsOn": "GraphQlSchema",
              "Properties": {
                "ApiId": {"Fn::GetAtt": ["GraphQlApi", "ApiId"]},
                "Name": "publicApiAuthKeysTable",
                "Type": "AMAZON_DYNAMODB",
                "DynamoDBConfig": {
                  "AwsRegion": {"Ref": "AWS::Region"},
                  "TableName": "appsync-cfn-it-auth-keys",
                  "UseCallerCredentials": false
                }
              }
            },
            "GraphQlDsAccountDb": {
              "Type": "AWS::AppSync::DataSource",
              "DependsOn": "GraphQlSchema",
              "Properties": {
                "ApiId": {"Fn::GetAtt": ["GraphQlApi", "ApiId"]},
                "Name": "accountDB",
                "Type": "RELATIONAL_DATABASE",
                "RelationalDatabaseConfig": {
                  "RelationalDatabaseSourceType": "RDS_HTTP_ENDPOINT",
                  "RdsHttpEndpointConfig": {
                    "AwsRegion": {"Ref": "AWS::Region"},
                    "DbClusterIdentifier": "appsync-cfn-it-cluster",
                    "DatabaseName": "accountdb",
                    "AwsSecretStoreArn": "arn:aws:secretsmanager:us-east-1:000000000000:secret:db"
                  }
                }
              }
            },
            "GraphQlFnGetMessages": {
              "Type": "AWS::AppSync::FunctionConfiguration",
              "Properties": {
                "ApiId": {"Fn::GetAtt": ["GraphQlApi", "ApiId"]},
                "Name": "Query_getMessages_0",
                "DataSourceName": {"Fn::GetAtt": ["GraphQlDsAccountDb", "Name"]},
                "FunctionVersion": "2018-05-29",
                "Runtime": {"Name": "APPSYNC_JS", "RuntimeVersion": "1.0.0"},
                "Code": "export function request(ctx) { return {}; }"
              }
            },
            "GraphQlResolverQuerygetMessages": {
              "Type": "AWS::AppSync::Resolver",
              "Properties": {
                "ApiId": {"Fn::GetAtt": ["GraphQlApi", "ApiId"]},
                "TypeName": "Query",
                "FieldName": "getMessages",
                "Kind": "PIPELINE",
                "Runtime": {"Name": "APPSYNC_JS", "RuntimeVersion": "1.0.0"},
                "Code": "export function response(ctx) { return ctx.prev.result; }",
                "PipelineConfig": {
                  "Functions": [{"Fn::GetAtt": ["GraphQlFnGetMessages", "FunctionId"]}]
                }
              }
            },
            "GraphQlApiKey": {
              "Type": "AWS::AppSync::ApiKey",
              "Properties": {
                "ApiId": {"Fn::GetAtt": ["GraphQlApi", "ApiId"]},
                "Description": "integration"
              }
            }
          },
          "Outputs": {
            "ApiId": {"Value": {"Fn::GetAtt": ["GraphQlApi", "ApiId"]}},
            "ApiRef": {"Value": {"Ref": "GraphQlApi"}},
            "GraphQLUrl": {"Value": {"Fn::GetAtt": ["GraphQlApi", "GraphQLUrl"]}},
            "DataSourceName": {"Value": {"Fn::GetAtt": ["GraphQlDsAccountDb", "Name"]}},
            "FunctionId": {"Value": {"Fn::GetAtt": ["GraphQlFnGetMessages", "FunctionId"]}},
            "ApiKey": {"Value": {"Fn::GetAtt": ["GraphQlApiKey", "ApiKey"]}}
          }
        }
        """;

    @Test
    void createUpdateAndDeleteAnAppSyncApi() throws InterruptedException {
        cloudFormation("CreateStack", Map.of("FieldLogLevel", "ERROR"));
        String created = describeStacks("CREATE_COMPLETE");

        String apiId = outputValue(created, "ApiId");
        String functionId = outputValue(created, "FunctionId");
        String apiKey = outputValue(created, "ApiKey");

        // Every dependent resource is wired through these; the literal "LogicalId.Attr" is what an
        // unset attribute yields, so a real id is the assertion that matters.
        assertNotEquals("GraphQlApi.ApiId", apiId);
        assertNotEquals("GraphQlFnGetMessages.FunctionId", functionId);
        assertEquals(apiId, outputValue(created, "ApiRef"));
        assertEquals("accountDB", outputValue(created, "DataSourceName"));
        assertTrue(outputValue(created, "GraphQLUrl").contains(apiId),
                "GraphQLUrl should address the api: " + outputValue(created, "GraphQLUrl"));

        appSync("/v1/apis/" + apiId)
            .statusCode(200)
            .body("graphqlApi.name", equalTo("appsync-cfn-it-api"))
            .body("graphqlApi.authenticationType", equalTo("API_KEY"))
            .body("graphqlApi.logConfig.fieldLogLevel", equalTo("ERROR"));

        // The schema resource waits for compilation, so by the time the stack completes the SDL is
        // queryable rather than still PROCESSING.
        appSync("/v1/apis/" + apiId + "/schemacreation").statusCode(200).body("status", equalTo("SUCCESS"));

        // Free-form map: the variable names are data and must not be camelised into oblivion.
        appSync("/v1/apis/" + apiId + "/environmentVariables")
            .statusCode(200)
            .body("environmentVariables.STRIPE_PRODUCT_SMALL_ID", equalTo("prod_small"));

        appSync("/v1/apis/" + apiId + "/datasources/publicApiAuthKeysTable")
            .statusCode(200)
            .body("dataSource.type", equalTo("AMAZON_DYNAMODB"))
            // dynamodbConfig, not dynamoDBConfig: the one member AWS does not lower-camelise.
            .body("dataSource.dynamodbConfig.tableName", equalTo("appsync-cfn-it-auth-keys"));

        appSync("/v1/apis/" + apiId + "/datasources/accountDB")
            .statusCode(200)
            .body("dataSource.type", equalTo("RELATIONAL_DATABASE"))
            .body("dataSource.relationalDatabaseConfig.rdsHttpEndpointConfig.dbClusterIdentifier",
                    equalTo("appsync-cfn-it-cluster"))
            .body("dataSource.relationalDatabaseConfig.rdsHttpEndpointConfig.databaseName",
                    equalTo("accountdb"));

        appSync("/v1/apis/" + apiId + "/functions/" + functionId)
            .statusCode(200)
            .body("functionConfiguration.name", equalTo("Query_getMessages_0"))
            .body("functionConfiguration.dataSourceName", equalTo("accountDB"))
            .body("functionConfiguration.runtime.name", equalTo("APPSYNC_JS"));

        // A PIPELINE resolver names no data source and reaches its data through the function above.
        appSync("/v1/apis/" + apiId + "/types/Query/resolvers/getMessages")
            .statusCode(200)
            .body("resolver.kind", equalTo("PIPELINE"))
            .body("resolver.pipelineConfig.functions[0]", equalTo(functionId))
            .body("resolver.runtime.name", equalTo("APPSYNC_JS"))
            .body("resolver.code", notNullValue());

        assertNotEquals("GraphQlApiKey.ApiKey", apiKey);
        appSync("/v1/apis/" + apiId + "/apikeys")
            .statusCode(200)
            .body("apiKeys[0].id", equalTo(apiKey))
            .body("apiKeys[0].description", equalTo("integration"));

        cloudFormation("UpdateStack", Map.of("FieldLogLevel", "ALL"));
        String updated = describeStacks("UPDATE_COMPLETE");

        // Nothing is replaced by a second deploy: the same API, data source and function, updated
        // in place. Re-creating instead would answer 400 on the duplicate name.
        assertEquals(apiId, outputValue(updated, "ApiId"));
        assertEquals(functionId, outputValue(updated, "FunctionId"));
        appSync("/v1/apis/" + apiId).statusCode(200).body("graphqlApi.logConfig.fieldLogLevel", equalTo("ALL"));
        appSync("/v1/apis/" + apiId + "/types/Query/resolvers/getMessages")
            .statusCode(200)
            .body("resolver.pipelineConfig.functions[0]", equalTo(functionId));

        cloudFormation("DeleteStack", Map.of());
        awaitStackDeleted();

        appSync("/v1/apis/" + apiId).statusCode(404);
    }

    private static void cloudFormation(String action, Map<String, String> parameters) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", STACK);
        if (!"DeleteStack".equals(action)) {
            request.formParam("TemplateBody", TEMPLATE);
        }
        int index = 1;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            request.formParam("Parameters.member." + index + ".ParameterKey", parameter.getKey());
            request.formParam("Parameters.member." + index + ".ParameterValue", parameter.getValue());
            index++;
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String describeStacks(String expectedStatus) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", STACK)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    private static void awaitStackDeleted() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            String body = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", STACK)
            .when().post("/").then().extract().asString();
            if (body.contains("does not exist")) {
                return;
            }
            if (body.contains("<StackStatus>DELETE_FAILED</StackStatus>")) {
                fail("stack delete failed: " + body);
            }
            Thread.sleep(50);
        }
        fail("stack " + STACK + " was not deleted within the timeout");
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private static ValidatableResponse appSync(String path) {
        return given().header("Authorization", APPSYNC_AUTH).when().get(path).then();
    }
}
