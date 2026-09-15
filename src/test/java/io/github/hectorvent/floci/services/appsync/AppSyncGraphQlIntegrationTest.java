package io.github.hectorvent.floci.services.appsync;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AppSyncGraphQlIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=111111111111/20260205/us-east-1/appsync/aws4_request";
    private static final String DYNAMODB_AUTH =
            "AWS4-HMAC-SHA256 Credential=111111111111/20260205/us-east-1/dynamodb/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=111111111111/20260205/us-east-1/iam/aws4_request";
    private static final String TABLE = "GqlTodos";
    private static final String ALLOWED_ROLE = "AppSyncGqlAllowedRole";
    private static final String DENIED_ROLE = "AppSyncGqlDeniedRole";
    private static final String ROLE_POLICY = "DynamoDbAccess";

    private static final String SCHEMA = """
            type Todo { id: ID! title: String owner: String secret: String @aws_iam child: Todo }
            type Query {
              hello(name: String): String
              getTodo(id: ID!): Todo
              getTodoDenied(id: ID!): Todo
              failTodo: Todo
              partial: String
              echoReturn: String
              missingResponse: String
              pipelined: String
              js: String
              iamOnly: String @aws_iam
              nested: Todo
              infoTodo: Todo
              headerCustom: [String]
              headerCookie: String
            }
            type Mutation {
              createTodo(id: ID!, title: String): Todo
              createTodoIgnoringErrors(id: ID!, title: String): Todo
              updateTodo(id: ID!, title: String): Todo
            }
            schema { query: Query mutation: Mutation }
            """;

    private static final String PUT_TODO_REQUEST = """
            {
              "version": "2018-05-29",
              "operation": "PutItem",
              "key": { "id": $util.dynamodb.toDynamoDBJson($ctx.args.id) },
              "attributeValues": {
                "title": $util.dynamodb.toDynamoDBJson($ctx.args.title),
                "owner": { "S": "$util.authType()" }
              },
              "condition": { "expression": "attribute_not_exists(#id)", "expressionNames": { "#id": "id" } }
            }
            """;
    private static final String RERAISE_RESPONSE =
            "#if($ctx.error)$util.error($ctx.error.message, $ctx.error.type)#end$util.toJson($ctx.result)";

    private static String apiId;
    private static String apiKey;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification signed() {
        return given().header("Authorization", AUTH).contentType("application/json");
    }

    private static RequestSpecification iamSigned() {
        return given().header("Authorization", IAM_AUTH);
    }

    private static ValidatableResponse query(String query) {
        return given().contentType("application/json")
                .header("x-api-key", apiKey)
                .body(Map.of("query", query))
                .when().post("/v1/apis/" + apiId + "/graphql")
                .then().statusCode(200);
    }

    private static void createResolver(String type, String field, String dataSource,
                                       String request, String response) {
        Map<String, Object> body = new HashMap<>();
        body.put("fieldName", field);
        body.put("dataSourceName", dataSource);
        if (request != null) {
            body.put("requestMappingTemplate", request);
        }
        if (response != null) {
            body.put("responseMappingTemplate", response);
        }
        signed().body(body)
                .when().post("/v1/apis/" + apiId + "/types/" + type + "/resolvers")
                .then().statusCode(200);
    }

    private static void awaitSchemaActive() {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(25))
                .until(() -> signed().when().get("/v1/apis/" + apiId + "/schemacreation")
                                .then().statusCode(200).extract().jsonPath().getString("status"),
                        status -> !"PROCESSING".equals(status));
        signed().when().get("/v1/apis/" + apiId + "/schemacreation")
                .then().body("status", equalTo("SUCCESS"));
    }

    @Test
    @Order(1)
    void provisionApiTableAndResolvers() {
        given().header("Authorization", DYNAMODB_AUTH)
                .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
                .contentType("application/x-amz-json-1.0")
                .body("""
                        {"TableName": "%s",
                         "KeySchema": [{"AttributeName": "id", "KeyType": "HASH"}],
                         "AttributeDefinitions": [{"AttributeName": "id", "AttributeType": "S"}],
                         "BillingMode": "PAY_PER_REQUEST"}
                        """.formatted(TABLE))
                .when().post("/").then().statusCode(200);

        String trustPolicy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                 "Principal":{"Service":"appsync.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                """;
        iamSigned().formParam("Action", "CreateRole")
                .formParam("RoleName", ALLOWED_ROLE)
                .formParam("AssumeRolePolicyDocument", trustPolicy)
                .when().post("/").then().statusCode(200);
        iamSigned().formParam("Action", "PutRolePolicy")
                .formParam("RoleName", ALLOWED_ROLE)
                .formParam("PolicyName", ROLE_POLICY)
                .formParam("PolicyDocument", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                         "Action":["dynamodb:GetItem","dynamodb:PutItem"],
                         "Resource":"arn:aws:dynamodb:us-east-1:111111111111:table/GqlTodos"}]}
                        """)
                .when().post("/").then().statusCode(200);
        iamSigned().formParam("Action", "CreateRole")
                .formParam("RoleName", DENIED_ROLE)
                .formParam("AssumeRolePolicyDocument", trustPolicy)
                .when().post("/").then().statusCode(200);

        apiId = signed().body(Map.of("name", "gql-vtl", "authenticationType", "API_KEY"))
                .when().post("/v1/apis").then().statusCode(200)
                .extract().path("graphqlApi.apiId");
        apiKey = signed().body(Map.of("description", "resolver tests"))
                .when().post("/v1/apis/" + apiId + "/apikeys").then().statusCode(200)
                .extract().path("apiKey.id");

        signed().body(Map.of("definition", SCHEMA))
                .when().post("/v1/apis/" + apiId + "/schemacreation").then().statusCode(200);
        awaitSchemaActive();

        signed().body(Map.of("name", "none", "type", "NONE"))
                .when().post("/v1/apis/" + apiId + "/datasources").then().statusCode(200);
        signed().body(Map.of("name", "todos", "type", "AMAZON_DYNAMODB",
                        "serviceRoleArn", "arn:aws:iam::111111111111:role/" + ALLOWED_ROLE,
                        "dynamodbConfig", Map.of("tableName", TABLE, "awsRegion", "us-east-1")))
                .when().post("/v1/apis/" + apiId + "/datasources").then().statusCode(200);
        signed().body(Map.of("name", "todosDenied", "type", "AMAZON_DYNAMODB",
                        "serviceRoleArn", "arn:aws:iam::111111111111:role/" + DENIED_ROLE,
                        "dynamodbConfig", Map.of("tableName", TABLE, "awsRegion", "us-east-1")))
                .when().post("/v1/apis/" + apiId + "/datasources").then().statusCode(200);

        createResolver("Query", "hello", "none",
                "{\"version\":\"2018-05-29\",\"payload\":{\"greeting\":\"Hello, $ctx.args.name!\"}}",
                "$util.toJson($ctx.result.greeting)");
        createResolver("Mutation", "createTodo", "todos", PUT_TODO_REQUEST, RERAISE_RESPONSE);
        createResolver("Mutation", "createTodoIgnoringErrors", "todos", PUT_TODO_REQUEST,
                "$util.toJson($ctx.result)");
        createResolver("Mutation", "updateTodo", "todos",
                "{\"version\":\"2018-05-29\",\"operation\":\"UpdateItem\","
                        + "\"key\":{\"id\":$util.dynamodb.toDynamoDBJson($ctx.args.id)}}",
                "$util.toJson($ctx.result)");
        createResolver("Query", "getTodo", "todos",
                "{\"version\":\"2018-05-29\",\"operation\":\"GetItem\","
                        + "\"key\":{\"id\":$util.dynamodb.toDynamoDBJson($ctx.args.id)}}",
                "#if($ctx.error)$util.error($ctx.error.message, $ctx.error.type)#end"
                        + "#if($util.isNull($ctx.result))#return#end$util.toJson($ctx.result)");
        createResolver("Query", "getTodoDenied", "todosDenied",
                "{\"version\":\"2018-05-29\",\"operation\":\"GetItem\","
                        + "\"key\":{\"id\":$util.dynamodb.toDynamoDBJson($ctx.args.id)}}",
                RERAISE_RESPONSE);
        createResolver("Query", "failTodo", "none",
                "$util.error(\"Custom failure\", \"CustomError\", "
                        + "{\"id\":\"1\",\"title\":\"hidden\"}, {\"code\":42})",
                "$util.toJson($ctx.result)");
        createResolver("Query", "partial", "none",
                "$util.appendError(\"soft failure\", \"Soft\")"
                        + "{\"version\":\"2018-05-29\",\"payload\":{\"value\":\"ok\"}}",
                "$util.toJson($ctx.result.value)");
        createResolver("Query", "echoReturn", "none", "#return(\"returned\")",
                "$util.toJson($ctx.result)");
        createResolver("Query", "missingResponse", "none",
                "{\"version\":\"2018-05-29\",\"payload\":\"x\"}", null);
        createResolver("Query", "nested", "none",
                "{\"version\":\"2018-05-29\",\"payload\":{\"id\":\"n1\",\"title\":\"nested\"}}",
                "$util.toJson($ctx.result)");
        createResolver("Query", "iamOnly", "none",
                "{\"version\":\"2018-05-29\",\"payload\":\"never\"}",
                "$util.toJson($ctx.result)");
        createResolver("Query", "infoTodo", "none",
                "{\"version\":\"2018-05-29\",\"payload\":{\"id\":\"i\","
                        + "\"title\":\"$ctx.info.selectionSetList\"}}",
                "$util.toJson($ctx.result)");
        createResolver("Query", "headerCustom", "none",
                "{\"version\":\"2018-05-29\",\"payload\":$util.toJson($ctx.request.headers.custom)}",
                "$util.toJson($ctx.result)");
        createResolver("Query", "headerCookie", "none",
                "{\"version\":\"2018-05-29\",\"payload\":$util.toJson($ctx.request.headers.cookie)}",
                "$util.toJson($ctx.result)");

        signed().body(Map.of("fieldName", "pipelined", "kind", "PIPELINE",
                        "pipelineConfig", Map.of("functions", List.of()),
                        "requestMappingTemplate", "{}", "responseMappingTemplate", "{}"))
                .when().post("/v1/apis/" + apiId + "/types/Query/resolvers").then().statusCode(200);
        signed().body(Map.of("fieldName", "js", "dataSourceName", "none",
                        "runtime", Map.of("name", "APPSYNC_JS", "runtimeVersion", "1.0.0"),
                        "code", "export function request(ctx) { return {}; } "
                                + "export function response(ctx) { return 'js'; }"))
                .when().post("/v1/apis/" + apiId + "/types/Query/resolvers").then().statusCode(200);
    }

    @Test
    @Order(2)
    void executesNoneResolverWithVariables() {
        given().contentType("application/json").header("x-api-key", apiKey)
                .body(Map.of("query", "query Hi($name: String) { hello(name: $name) }",
                        "operationName", "Hi", "variables", Map.of("name", "Ann")))
                .when().post("/v1/apis/" + apiId + "/graphql")
                .then().statusCode(200)
                .body("data.hello", equalTo("Hello, Ann!"))
                .body("$", not(hasKey("errors")));
    }

    @Test
    @Order(3)
    void putAndGetItemThroughDynamoDbResolvers() {
        query("mutation { createTodo(id: \"1\", title: \"milk\") { id title owner } }")
                .body("data.createTodo.id", equalTo("1"))
                .body("data.createTodo.title", equalTo("milk"))
                .body("data.createTodo.owner", equalTo("API Key Authorization"))
                .body("$", not(hasKey("errors")));

        query("query { todo: getTodo(id: \"1\") { ...TodoFields } } "
                + "fragment TodoFields on Todo { id title }")
                .body("data.todo.id", equalTo("1"))
                .body("data.todo.title", equalTo("milk"));

        given().header("Authorization", DYNAMODB_AUTH)
                .header("X-Amz-Target", "DynamoDB_20120810.GetItem")
                .contentType("application/x-amz-json-1.0")
                .body("{\"TableName\":\"" + TABLE + "\",\"Key\":{\"id\":{\"S\":\"1\"}}}")
                .when().post("/").then().statusCode(200)
                .body("Item.title.S", equalTo("milk"));

        query("query { getTodoDenied(id: \"1\") { id title } }")
                .body("data.getTodoDenied", nullValue())
                .body("errors[0].errorType", equalTo("DynamoDB:AccessDeniedException"))
                .body("errors[0].message", containsString("dynamodb:GetItem"));
    }

    @Test
    @Order(4)
    void responseTemplateControlsDynamoDbErrors() {
        query("mutation { createTodo(id: \"1\", title: \"again\") { id } }")
                .body("data.createTodo", nullValue())
                .body("errors[0].errorType", equalTo("DynamoDB:ConditionalCheckFailedException"))
                .body("errors[0].path", contains("createTodo"));

        query("mutation { createTodoIgnoringErrors(id: \"1\", title: \"again\") { id title } }")
                .body("data.createTodoIgnoringErrors.id", equalTo("1"))
                .body("data.createTodoIgnoringErrors.title", equalTo("milk"))
                .body("$", not(hasKey("errors")));
    }

    @Test
    @Order(5)
    void supportsVtlErrorsReturnsContextAndNestedProperties() {
        query("{ failTodo { id } partial echoReturn nested { id title } "
                + "infoTodo { t: id title child { title } } }")
                .body("data.failTodo", nullValue())
                .body("data.partial", equalTo("ok"))
                .body("data.echoReturn", equalTo("returned"))
                .body("data.nested.id", equalTo("n1"))
                .body("data.infoTodo.title", equalTo("[t, title, child, child/title]"))
                .body("errors", hasSize(2))
                .body("errors[0].errorType", equalTo("CustomError"))
                .body("errors[0].data.id", equalTo("1"))
                .body("errors[0].data", not(hasKey("title")))
                .body("errors[0].errorInfo.code", equalTo(42))
                .body("errors[1].errorType", equalTo("Soft"));
    }

    @Test
    @Order(6)
    void preservesAuthorizationAndAppSyncRequestHeaders() {
        given().contentType("application/json").header("x-api-key", apiKey)
                .header("custom", "bailey").header("custom", "nadia")
                .header("Cookie", "session=1")
                .body(Map.of("query", "{ headerCustom headerCookie iamOnly }"))
                .when().post("/v1/apis/" + apiId + "/graphql")
                .then().statusCode(200)
                .body("data.headerCustom", contains("bailey", "nadia"))
                .body("data.headerCookie", nullValue())
                .body("data.iamOnly", nullValue())
                .body("errors[0].errorType", equalTo("Unauthorized"));
    }

    @Test
    @Order(7)
    void reportsUnsupportedAndInvalidResolverShapes() {
        query("{ pipelined js missingResponse }")
                .body("data.pipelined", nullValue())
                .body("data.js", nullValue())
                .body("data.missingResponse", nullValue())
                .body("errors", hasSize(3))
                .body("errors.errorType",
                        contains("UnsupportedOperation", "UnsupportedOperation", "MappingTemplate"));
        query("mutation { updateTodo(id: \"1\", title: \"x\") { id } }")
                .body("data.updateTodo", nullValue())
                .body("errors[0].errorType", equalTo("UnsupportedOperation"))
                .body("errors[0].message", containsString("UpdateItem"));
    }

    @Test
    @Order(8)
    void enforcesIntrospectionConfigWithoutBlockingTypename() {
        query("{ __schema { queryType { name } } }")
                .body("data.__schema.queryType.name", equalTo("Query"));
        signed().body(Map.of("name", "gql-vtl", "authenticationType", "API_KEY",
                        "introspectionConfig", "DISABLED"))
                .when().post("/v1/apis/" + apiId).then().statusCode(200);
        query("{ __schema { queryType { name } } }")
                .body("data", nullValue())
                .body("errors[0].errorType", equalTo("IntrospectionDisabled"));
        query("{ __typename }").body("data.__typename", equalTo("Query"));
    }

    @Test
    @Order(99)
    void cleanUp() {
        signed().when().delete("/v1/apis/" + apiId).then().statusCode(204);
        given().header("Authorization", DYNAMODB_AUTH)
                .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
                .contentType("application/x-amz-json-1.0")
                .body("{\"TableName\":\"" + TABLE + "\"}")
                .when().post("/").then().statusCode(200);
        iamSigned().formParam("Action", "DeleteRolePolicy")
                .formParam("RoleName", ALLOWED_ROLE)
                .formParam("PolicyName", ROLE_POLICY)
                .when().post("/").then().statusCode(200);
        iamSigned().formParam("Action", "DeleteRole")
                .formParam("RoleName", ALLOWED_ROLE)
                .when().post("/").then().statusCode(200);
        iamSigned().formParam("Action", "DeleteRole")
                .formParam("RoleName", DENIED_ROLE)
                .when().post("/").then().statusCode(200);
    }
}
