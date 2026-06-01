package io.github.hectorvent.floci.services.appsync;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AppSyncIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/appsync/aws4_request";
    private static String apiId;
    private static String keyId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String encodeArn(String arn) {
        return URLEncoder.encode(arn, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ── GraphQL API ──────────────────────────────────────────────────────────

    @Test
    @Order(10)
    void createGraphqlApi() {
        apiId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "name": "my-api",
                  "authenticationType": "API_KEY",
                  "tags": {"env": "test"}
                }
                """)
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(200)
            .body("graphqlApi.apiId", notNullValue())
            .body("graphqlApi.name", equalTo("my-api"))
            .body("graphqlApi.authenticationType", equalTo("API_KEY"))
            .body("graphqlApi.arn", containsString("arn:aws:appsync:"))
            .body("graphqlApi.uris.GRAPHQL", containsString("/v1/apis/"))
            .body("graphqlApi.tags.env", equalTo("test"))
            .extract().path("graphqlApi.apiId");
    }

    @Test
    @Order(11)
    void getGraphqlApi() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId)
        .then()
            .statusCode(200)
            .body("graphqlApi.apiId", equalTo(apiId))
            .body("graphqlApi.name", equalTo("my-api"))
            .body("graphqlApi.authenticationType", equalTo("API_KEY"));
    }

    @Test
    @Order(12)
    void listGraphqlApis() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis")
        .then()
            .statusCode(200)
            .body("graphqlApis", hasSize(greaterThanOrEqualTo(1)))
            .body("graphqlApis[0].apiId", notNullValue());
    }

    @Test
    @Order(13)
    void updateGraphqlApi() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"name": "my-api-v2"}
                """)
        .when()
            .post("/v1/apis/" + apiId)
        .then()
            .statusCode(200)
            .body("graphqlApi.apiId", equalTo(apiId))
            .body("graphqlApi.name", equalTo("my-api-v2"));
    }

    // ── Schema ───────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    void startSchemaCreation() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "definition": "type Query { hello: String }"
                }
                """)
        .when()
            .post("/v1/apis/" + apiId + "/schemacreation")
        .then()
            .statusCode(200)
            .body("status", equalTo("ACTIVE"));
    }

    @Test
    @Order(21)
    void getSchemaCreationStatus() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/schemacreation")
        .then()
            .statusCode(200)
            .body("status", equalTo("ACTIVE"));
    }

    @Test
    @Order(22)
    void getIntrospectionSchema() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/schema")
        .then()
            .statusCode(200)
            .body("schema", containsString("type Query"));
    }

    // ── API Keys ─────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    void createApiKey() {
        keyId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "description": "test-key",
                  "expires": "2027-01-01T00:00:00Z"
                }
                """)
        .when()
            .post("/v1/apis/" + apiId + "/apikeys")
        .then()
            .statusCode(200)
            .body("apiKey.id", notNullValue())
            .body("apiKey.apiKey", startsWith("da2-"))
            .body("apiKey.description", equalTo("test-key"))
            .extract().path("apiKey.id");
    }

    @Test
    @Order(31)
    void listApiKeys() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/apikeys")
        .then()
            .statusCode(200)
            .body("apiKeys", hasSize(greaterThanOrEqualTo(1)))
            .body("apiKeys[0].id", notNullValue());
    }

    @Test
    @Order(32)
    void getApiKey() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/apikeys/" + keyId)
        .then()
            .statusCode(200)
            .body("apiKey.id", equalTo(keyId))
            .body("apiKey.description", equalTo("test-key"));
    }

    @Test
    @Order(33)
    void updateApiKey() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"description": "updated-key"}
                """)
        .when()
            .post("/v1/apis/" + apiId + "/apikeys/" + keyId)
        .then()
            .statusCode(200)
            .body("apiKey.id", equalTo(keyId))
            .body("apiKey.description", equalTo("updated-key"));
    }

    // ── Data Sources ─────────────────────────────────────────────────────────

    @Test
    @Order(40)
    void createDataSourceNone() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "name": "none-ds",
                  "type": "NONE"
                }
                """)
        .when()
            .post("/v1/apis/" + apiId + "/datasources")
        .then()
            .statusCode(200)
            .body("dataSource.name", equalTo("none-ds"))
            .body("dataSource.type", equalTo("NONE"));
    }

    @Test
    @Order(41)
    void getDataSource() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/datasources/none-ds")
        .then()
            .statusCode(200)
            .body("dataSource.name", equalTo("none-ds"))
            .body("dataSource.type", equalTo("NONE"));
    }

    @Test
    @Order(42)
    void listDataSources() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/datasources")
        .then()
            .statusCode(200)
            .body("dataSources", hasSize(greaterThanOrEqualTo(1)))
            .body("dataSources[0].name", notNullValue());
    }

    @Test
    @Order(43)
    void createDataSourceDynamoDb() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "name": "dynamo-ds",
                  "type": "AMAZON_DYNAMODB",
                  "dynamodbConfig": {
                    "tableName": "my-table",
                    "awsRegion": "us-east-1"
                  }
                }
                """)
        .when()
            .post("/v1/apis/" + apiId + "/datasources")
        .then()
            .statusCode(200)
            .body("dataSource.name", equalTo("dynamo-ds"))
            .body("dataSource.type", equalTo("AMAZON_DYNAMODB"));
    }

    @Test
    @Order(44)
    void updateDataSource() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"description": "updated-ds"}
                """)
        .when()
            .post("/v1/apis/" + apiId + "/datasources/none-ds")
        .then()
            .statusCode(200)
            .body("dataSource.description", equalTo("updated-ds"));
    }

    @Test
    @Order(45)
    void deleteDataSource() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v1/apis/" + apiId + "/datasources/dynamo-ds")
        .then()
            .statusCode(204);
    }

    // ── Types ────────────────────────────────────────────────────────────────

    @Test
    @Order(50)
    void createType() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "name": "Query",
                  "definition": "type Query { hello: String, getItem(id: ID!): Item }"
                }
                """)
        .when()
            .post("/v1/apis/" + apiId + "/types")
        .then()
            .statusCode(200)
            .body("type.name", equalTo("Query"))
            .body("type.definition", containsString("hello"));
    }

    @Test
    @Order(51)
    void getType() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/types/Query")
        .then()
            .statusCode(200)
            .body("type.name", equalTo("Query"));
    }

    @Test
    @Order(52)
    void listTypes() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/types")
        .then()
            .statusCode(200)
            .body("types", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(53)
    void updateType() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"definition": "type Query { hello: String, goodbye: String }"}
                """)
        .when()
            .post("/v1/apis/" + apiId + "/types/Query")
        .then()
            .statusCode(200)
            .body("type.definition", containsString("goodbye"));
    }

    @Test
    @Order(54)
    void deleteType() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "name": "TempType",
                  "definition": "type TempType { id: ID }"
                }
                """)
        .when()
            .post("/v1/apis/" + apiId + "/types")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v1/apis/" + apiId + "/types/TempType")
        .then()
            .statusCode(204);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/types/TempType")
        .then()
            .statusCode(404);
    }

    // ── Resolvers ────────────────────────────────────────────────────────────

    @Test
    @Order(60)
    void createResolver() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "fieldName": "hello",
                  "dataSourceName": "none-ds",
                  "requestMappingTemplate": "{ \\"version\\": \\"2017-02-28\\", \\"payload\\": {} }",
                  "responseMappingTemplate": "$util.toJson({\\"hello\\": \\"world\\"})"
                }
                """)
        .when()
            .post("/v1/apis/" + apiId + "/types/Query/resolvers")
        .then()
            .statusCode(200)
            .body("resolver.typeName", equalTo("Query"))
            .body("resolver.fieldName", equalTo("hello"))
            .body("resolver.dataSourceName", equalTo("none-ds"));
    }

    @Test
    @Order(61)
    void getResolver() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/types/Query/resolvers/hello")
        .then()
            .statusCode(200)
            .body("resolver.typeName", equalTo("Query"))
            .body("resolver.fieldName", equalTo("hello"));
    }

    @Test
    @Order(62)
    void listResolvers() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/types/Query/resolvers")
        .then()
            .statusCode(200)
            .body("resolvers", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(62)
    void listAllResolvers() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/resolvers")
        .then()
            .statusCode(200)
            .body("resolvers", hasSize(greaterThanOrEqualTo(1)))
            .body("resolvers[0].typeName", notNullValue())
            .body("resolvers[0].fieldName", notNullValue());
    }

    @Test
    @Order(63)
    void updateResolver() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "dataSourceName": "none-ds",
                  "responseMappingTemplate": "$util.toJson({\\"hello\\": \\"updated\\"})"
                }
                """)
        .when()
            .post("/v1/apis/" + apiId + "/types/Query/resolvers/hello")
        .then()
            .statusCode(200)
            .body("resolver.typeName", equalTo("Query"))
            .body("resolver.fieldName", equalTo("hello"))
            .body("resolver.responseMappingTemplate", containsString("updated"));
    }

    // ── Functions ────────────────────────────────────────────────────────────

    private static String functionId;

    @Test
    @Order(70)
    void createFunction() {
        functionId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "name": "my-function",
                  "dataSourceName": "none-ds",
                  "requestMappingTemplate": "{ \\"version\\": \\"2017-02-28\\", \\"payload\\": {} }",
                  "responseMappingTemplate": "$util.toJson({})"
                }
                """)
        .when()
            .post("/v1/apis/" + apiId + "/functions")
        .then()
            .statusCode(200)
            .body("functionConfiguration.name", equalTo("my-function"))
            .body("functionConfiguration.functionId", notNullValue())
            .body("functionConfiguration.arn", containsString("arn:aws:appsync:"))
            .extract().path("functionConfiguration.functionId");
    }

    @Test
    @Order(71)
    void getFunction() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/functions/" + functionId)
        .then()
            .statusCode(200)
            .body("functionConfiguration.functionId", equalTo(functionId))
            .body("functionConfiguration.name", equalTo("my-function"));
    }

    @Test
    @Order(72)
    void listFunctions() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/functions")
        .then()
            .statusCode(200)
            .body("functions", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(73)
    void updateFunction() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"description": "updated-function"}
                """)
        .when()
            .post("/v1/apis/" + apiId + "/functions/" + functionId)
        .then()
            .statusCode(200)
            .body("functionConfiguration.description", equalTo("updated-function"));
    }

    @Test
    @Order(74)
    void listResolversByFunction() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "fieldName": "fnResolverField",
                  "dataSourceName": "none-ds",
                  "functionId": "%s"
                }
                """.formatted(functionId))
        .when()
            .post("/v1/apis/" + apiId + "/types/Query/resolvers")
        .then()
            .statusCode(200)
            .body("resolver.functionId", equalTo(functionId));

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/functions/" + functionId + "/resolvers")
        .then()
            .statusCode(200)
            .body("resolvers", hasSize(greaterThanOrEqualTo(1)))
            .body("resolvers[0].functionId", equalTo(functionId));

        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v1/apis/" + apiId + "/types/Query/resolvers/fnResolverField")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(75)
    void deleteFunction() {
        String tempFnId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "name": "temp-function",
                  "dataSourceName": "none-ds"
                }
                """)
        .when()
            .post("/v1/apis/" + apiId + "/functions")
        .then()
            .statusCode(200)
            .extract().path("functionConfiguration.functionId");

        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v1/apis/" + apiId + "/functions/" + tempFnId)
        .then()
            .statusCode(204);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/functions/" + tempFnId)
        .then()
            .statusCode(404);
    }

    // ── Tags ─────────────────────────────────────────────────────────────────

    @Test
    @Order(80)
    void tagResource() {
        String apiArn = "arn:aws:appsync:us-east-1:000000000000:apis/" + apiId;

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"tags": {"team": "platform"}}
                """)
            .urlEncodingEnabled(false)
        .when()
            .post("/v1/tags/" + apiArn)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(81)
    void listTagsForResource() {
        String apiArn = "arn:aws:appsync:us-east-1:000000000000:apis/" + apiId;

        given()
            .header("Authorization", AUTH)
            .urlEncodingEnabled(false)
        .when()
            .get("/v1/tags/" + apiArn)
        .then()
            .statusCode(200)
            .body("tags.env", equalTo("test"))
            .body("tags.team", equalTo("platform"));
    }

    @Test
    @Order(82)
    void untagResource() {
        String apiArn = "arn:aws:appsync:us-east-1:000000000000:apis/" + apiId;

        given()
            .header("Authorization", AUTH)
            .queryParam("tagKeys", "team")
            .urlEncodingEnabled(false)
        .when()
            .delete("/v1/tags/" + apiArn)
        .then()
            .statusCode(204);

        given()
            .header("Authorization", AUTH)
            .urlEncodingEnabled(false)
        .when()
            .get("/v1/tags/" + apiArn)
        .then()
            .statusCode(200)
            .body("tags.team", nullValue())
            .body("tags.env", equalTo("test"));
    }

    // ── Environment Variables ────────────────────────────────────────────────

    @Test
    @Order(890)
    void putEnvironmentVariables() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "environmentVariables": {
                    "TABLE_NAME": "my-table",
                    "REGION": "us-east-1"
                  }
                }
                """)
        .when()
            .put("/v1/apis/" + apiId + "/environmentvariables")
        .then()
            .statusCode(200)
            .body("environmentVariables.TABLE_NAME", equalTo("my-table"))
            .body("environmentVariables.REGION", equalTo("us-east-1"));
    }

    @Test
    @Order(891)
    void getEnvironmentVariables() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/environmentvariables")
        .then()
            .statusCode(200)
            .body("environmentVariables.TABLE_NAME", equalTo("my-table"))
            .body("environmentVariables.REGION", equalTo("us-east-1"));
    }

    @Test
    @Order(893)
    void putEnvironmentVariablesOverwrites() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "environmentVariables": {
                    "NEW_VAR": "new-value"
                  }
                }
                """)
        .when()
            .put("/v1/apis/" + apiId + "/environmentvariables")
        .then()
            .statusCode(200)
            .body("environmentVariables.NEW_VAR", equalTo("new-value"))
            .body("environmentVariables.TABLE_NAME", nullValue());
    }

    // ── Error Handling ─────────────────────────────────────────────────────

    @Test
    @Order(100)
    void createGraphqlApiMissingNameReturns400() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"authenticationType": "API_KEY"}
                """)
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(101)
    void createGraphqlApiBlankNameReturns400() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"name": "  ", "authenticationType": "API_KEY"}
                """)
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(102)
    void createGraphqlApiInvalidAuthTypeReturns400() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"name": "test-api", "authenticationType": "INVALID_TYPE"}
                """)
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(103)
    void createDataSourceMissingNameReturns400() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"type": "NONE"}
                """)
        .when()
            .post("/v1/apis/" + apiId + "/datasources")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(104)
    void createDataSourceMissingTypeReturns400() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"name": "no-type-ds"}
                """)
        .when()
            .post("/v1/apis/" + apiId + "/datasources")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(105)
    void createDataSourceInvalidTypeReturns400() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"name": "bad-ds", "type": "NOT_A_REAL_TYPE"}
                """)
        .when()
            .post("/v1/apis/" + apiId + "/datasources")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(106)
    void createResolverMissingFieldNameReturns400() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"dataSourceName": "none-ds"}
                """)
        .when()
            .post("/v1/apis/" + apiId + "/types/Query/resolvers")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(107)
    void createResolverMissingDataSourceReturns400() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"fieldName": "missingDs"}
                """)
        .when()
            .post("/v1/apis/" + apiId + "/types/Query/resolvers")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(108)
    void createTypeMissingNameReturns400() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"definition": "type Foo { id: ID }"}
                """)
        .when()
            .post("/v1/apis/" + apiId + "/types")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {}
                """)
        .when()
            .post("/v1/apis/" + apiId + "/types")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(109)
    void createFunctionMissingNameReturns400() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"dataSourceName": "none-ds"}
                """)
        .when()
            .post("/v1/apis/" + apiId + "/functions")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(110)
    void getNonExistentApiReturns404() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/doesnotexist12345678901234")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(111)
    void getNonExistentDataSourceReturns404() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/datasources/nonexistent-ds")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(112)
    void getNonExistentTypeReturns404() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/types/NonExistentType")
        .then()
            .statusCode(404);
    }

    // ── Delete Standalone ──────────────────────────────────────────────────

    @Test
    @Order(120)
    void deleteResolverStandalone() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "fieldName": "tempResolver",
                  "dataSourceName": "none-ds"
                }
                """)
        .when()
            .post("/v1/apis/" + apiId + "/types/Query/resolvers")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v1/apis/" + apiId + "/types/Query/resolvers/tempResolver")
        .then()
            .statusCode(204);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/types/Query/resolvers/tempResolver")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(121)
    void deleteDataSourceStandalone() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "name": "temp-ds-delete",
                  "type": "NONE"
                }
                """)
        .when()
            .post("/v1/apis/" + apiId + "/datasources")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v1/apis/" + apiId + "/datasources/temp-ds-delete")
        .then()
            .statusCode(204);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/datasources/temp-ds-delete")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(122)
    void deleteFunctionStandalone() {
        String tempFnId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "name": "temp-fn-delete",
                  "dataSourceName": "none-ds"
                }
                """)
        .when()
            .post("/v1/apis/" + apiId + "/functions")
        .then()
            .statusCode(200)
            .extract().path("functionConfiguration.functionId");

        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v1/apis/" + apiId + "/functions/" + tempFnId)
        .then()
            .statusCode(204);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/functions/" + tempFnId)
        .then()
            .statusCode(404);
    }

    @Test
    @Order(123)
    void deleteTypeStandalone() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "name": "StandaloneDeleteType",
                  "definition": "type StandaloneDeleteType { id: ID }"
                }
                """)
        .when()
            .post("/v1/apis/" + apiId + "/types")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v1/apis/" + apiId + "/types/StandaloneDeleteType")
        .then()
            .statusCode(204);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/types/StandaloneDeleteType")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(124)
    void deleteApiKeyStandalone() {
        String tempKeyId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"description": "temp-key-delete"}
                """)
        .when()
            .post("/v1/apis/" + apiId + "/apikeys")
        .then()
            .statusCode(200)
            .extract().path("apiKey.id");

        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v1/apis/" + apiId + "/apikeys/" + tempKeyId)
        .then()
            .statusCode(204);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/apikeys/" + tempKeyId)
        .then()
            .statusCode(404);
    }

    @Test
    @Order(125)
    void listDataSourcesAfterDeleteReturnsExpectedCount() {
        int before = given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/datasources")
        .then()
            .statusCode(200)
            .extract().jsonPath().getList("dataSources").size();

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"name": "count-check-ds", "type": "NONE"}
                """)
        .when()
            .post("/v1/apis/" + apiId + "/datasources")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v1/apis/" + apiId + "/datasources/count-check-ds")
        .then()
            .statusCode(204);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/datasources")
        .then()
            .statusCode(200)
            .body("dataSources.size()", equalTo(before));
    }

    // ── Cascade Verification ───────────────────────────────────────────────

    @Test
    @Order(130)
    void deleteApiCascadeDeletesDataSources() {
        String tempApiId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "name": "cascade-test-api",
                  "authenticationType": "API_KEY"
                }
                """)
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(200)
            .extract().path("graphqlApi.apiId");

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"name": "cascade-ds", "type": "NONE"}
                """)
        .when()
            .post("/v1/apis/" + tempApiId + "/datasources")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v1/apis/" + tempApiId)
        .then()
            .statusCode(204);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + tempApiId + "/datasources/cascade-ds")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(131)
    void deleteApiCascadeDeletesFunctions() {
        String tempApiId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "name": "cascade-fn-test-api",
                  "authenticationType": "API_KEY"
                }
                """)
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(200)
            .extract().path("graphqlApi.apiId");

        String fnId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "name": "cascade-fn",
                  "dataSourceName": "none-ds"
                }
                """)
        .when()
            .post("/v1/apis/" + tempApiId + "/functions")
        .then()
            .statusCode(200)
            .extract().path("functionConfiguration.functionId");

        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v1/apis/" + tempApiId)
        .then()
            .statusCode(204);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + tempApiId + "/functions/" + fnId)
        .then()
            .statusCode(404);
    }

    @Test
    @Order(132)
    void deleteFunctionVerifyResolverStillExists() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "fieldName": "fnCascadeField",
                  "dataSourceName": "none-ds"
                }
                """)
        .when()
            .post("/v1/apis/" + apiId + "/types/Query/resolvers")
        .then()
            .statusCode(200);

        String fnId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "name": "fn-for-cascade-test",
                  "dataSourceName": "none-ds"
                }
                """)
        .when()
            .post("/v1/apis/" + apiId + "/functions")
        .then()
            .statusCode(200)
            .extract().path("functionConfiguration.functionId");

        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v1/apis/" + apiId + "/functions/" + fnId)
        .then()
            .statusCode(204);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/types/Query/resolvers/fnCascadeField")
        .then()
            .statusCode(200)
            .body("resolver.fieldName", equalTo("fnCascadeField"));

        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v1/apis/" + apiId + "/types/Query/resolvers/fnCascadeField")
        .then()
            .statusCode(204);
    }

    // ── Pagination ──────────────────────────────────────────────────────────

    @Test
    @Order(140)
    void listApisWithMaxResults() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"name": "pagination-api-1", "authenticationType": "API_KEY"}
                """)
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"name": "pagination-api-2", "authenticationType": "API_KEY"}
                """)
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
            .queryParam("maxResults", 2)
        .when()
            .get("/v1/apis")
        .then()
            .statusCode(200)
            .body("graphqlApis", hasSize(greaterThanOrEqualTo(2)))
            .body("nextToken", notNullValue());
    }

    @Test
    @Order(141)
    void listApisWithNextToken() {
        String firstPageToken = given()
            .header("Authorization", AUTH)
            .queryParam("maxResults", 2)
        .when()
            .get("/v1/apis")
        .then()
            .statusCode(200)
            .extract().path("nextToken");

        given()
            .header("Authorization", AUTH)
            .queryParam("maxResults", 2)
            .queryParam("nextToken", firstPageToken)
        .when()
            .get("/v1/apis")
        .then()
            .statusCode(200)
            .body("graphqlApis", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(142)
    void listApisWithoutPaginationReturnsAll() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis")
        .then()
            .statusCode(200)
            .body("graphqlApis", hasSize(greaterThanOrEqualTo(1)))
            .body("nextToken", nullValue());
    }

    @Test
    @Order(143)
    void listDataSourcesWithMaxResults() {
        given()
            .header("Authorization", AUTH)
            .queryParam("maxResults", 1)
        .when()
            .get("/v1/apis/" + apiId + "/datasources")
        .then()
            .statusCode(200)
            .body("dataSources", hasSize(1));
    }

    @Test
    @Order(144)
    void listTypesWithMaxResults() {
        given()
            .header("Authorization", AUTH)
            .queryParam("maxResults", 1)
        .when()
            .get("/v1/apis/" + apiId + "/types")
        .then()
            .statusCode(200)
            .body("types", hasSize(1));
    }

    @Test
    @Order(145)
    void listFunctionsWithMaxResults() {
        given()
            .header("Authorization", AUTH)
            .queryParam("maxResults", 1)
        .when()
            .get("/v1/apis/" + apiId + "/functions")
        .then()
            .statusCode(200)
            .body("functions", hasSize(1));
    }

    @Test
    @Order(146)
    void listApiKeysWithMaxResults() {
        given()
            .header("Authorization", AUTH)
            .queryParam("maxResults", 1)
        .when()
            .get("/v1/apis/" + apiId + "/apikeys")
        .then()
            .statusCode(200)
            .body("apiKeys", hasSize(1));
    }

    @Test
    @Order(147)
    void listResolversWithMaxResults() {
        given()
            .header("Authorization", AUTH)
            .queryParam("maxResults", 1)
        .when()
            .get("/v1/apis/" + apiId + "/types/Query/resolvers")
        .then()
            .statusCode(200)
            .body("resolvers", hasSize(1));
    }

    @Test
    @Order(148)
    void listWithInvalidNextTokenReturns400() {
        given()
            .header("Authorization", AUTH)
            .queryParam("nextToken", "!!!invalid-token!!!")
        .when()
            .get("/v1/apis")
        .then()
            .statusCode(400);
    }

    // ── Teardown ─────────────────────────────────────────────────────────────

    @Test
    @Order(900)
    void deleteApiKey() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v1/apis/" + apiId + "/apikeys/" + keyId)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(901)
    void deleteResolver() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v1/apis/" + apiId + "/types/Query/resolvers/hello")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(902)
    void deleteGraphqlApiCascadeDeletesAll() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v1/apis/" + apiId)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(903)
    void getDeletedGraphqlApiReturns404() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId)
        .then()
            .statusCode(404);
    }

    @Test
    @Order(904)
    void deletedDataSourcesAreGone() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/datasources/none-ds")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(905)
    void deletedTypesAreGone() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/types/Query")
        .then()
            .statusCode(404);
    }
}
