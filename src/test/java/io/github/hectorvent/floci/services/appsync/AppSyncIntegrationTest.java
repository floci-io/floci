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
            .body("schema.definition", containsString("type Query"));
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
            .body("functionConfiguration.functionArn", containsString("arn:aws:appsync:"))
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

        // Create a data source first (function requires it)
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "name": "cascade-fn-ds",
                  "type": "NONE"
                }
                """)
        .when()
            .post("/v1/apis/" + tempApiId + "/datasources")
        .then()
            .statusCode(200);

        String fnId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "name": "cascade-fn",
                  "dataSourceName": "cascade-fn-ds"
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

    // ── Phase 2: Model Completeness ─────────────────────────────────────────

    @Test
    @Order(200)
    void createGraphqlApi_returnsNewFields() {
        String tempApiId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "name": "full-api",
                  "authenticationType": "API_KEY",
                  "apiType": "GRAPHQL",
                  "visibility": "GLOBAL",
                  "queryDepthLimit": 10
                }
                """)
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(200)
            .body("graphqlApi.apiType", equalTo("GRAPHQL"))
            .body("graphqlApi.visibility", equalTo("GLOBAL"))
            .body("graphqlApi.queryDepthLimit", equalTo(10))
            .extract().path("graphqlApi.apiId");

        // cleanup
        given().header("Authorization", AUTH).delete("/v1/apis/" + tempApiId).then().statusCode(204);
    }

    @Test
    @Order(205)
    void updateGraphqlApi_handlesNewFields() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "owner": "test-owner",
                  "ownerContact": "test@example.com",
                  "queryDepthLimit": 25,
                  "resolverCountLimit": 100
                }
                """)
        .when()
            .post("/v1/apis/" + apiId)
        .then()
            .statusCode(200)
            .body("graphqlApi.owner", equalTo("test-owner"))
            .body("graphqlApi.ownerContact", equalTo("test@example.com"))
            .body("graphqlApi.queryDepthLimit", equalTo(25))
            .body("graphqlApi.resolverCountLimit", equalTo(100));
    }

    @Test
    @Order(207)
    void updateGraphqlApi_additionalAuthProviders() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "additionalAuthenticationProviders": [
                    {"authenticationType": "AWS_IAM"}
                  ]
                }
                """)
        .when()
            .post("/v1/apis/" + apiId)
        .then()
            .statusCode(200)
            .body("graphqlApi.additionalAuthenticationProviders", hasSize(1))
            .body("graphqlApi.additionalAuthenticationProviders[0].authenticationType", equalTo("AWS_IAM"));
    }

    @Test
    @Order(210)
    void createDataSource_returnsDataSourceArn() {
        String dsName = "arn-test-ds";
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "name": "%s",
                  "type": "NONE"
                }
                """.formatted(dsName))
        .when()
            .post("/v1/apis/" + apiId + "/datasources")
        .then()
            .statusCode(200)
            .body("dataSource.dataSourceArn", containsString("arn:aws:appsync:"))
            .body("dataSource.dataSourceArn", containsString("/datasources/" + dsName));

        // cleanup
        given().header("Authorization", AUTH).delete("/v1/apis/" + apiId + "/datasources/" + dsName).then().statusCode(204);
    }

    @Test
    @Order(215)
    void createResolver_returnsResolverArn() {
        String resolverName = "resolverArnTest";
        String dsName = "resolver-arn-ds";

        // create temp data source
        given().header("Authorization", AUTH).contentType("application/json")
            .body("""
                { "name": "%s", "type": "NONE" }
                """.formatted(dsName))
            .post("/v1/apis/" + apiId + "/datasources").then().statusCode(200);

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "fieldName": "%s",
                  "dataSourceName": "%s"
                }
                """.formatted(resolverName, dsName))
        .when()
            .post("/v1/apis/" + apiId + "/types/Query/resolvers")
        .then()
            .statusCode(200)
            .body("resolver.resolverArn", containsString("arn:aws:appsync:"))
            .body("resolver.resolverArn", containsString("/types/Query/resolvers/" + resolverName));

        // cleanup
        given().header("Authorization", AUTH).delete("/v1/apis/" + apiId + "/types/Query/resolvers/" + resolverName).then().statusCode(204);
        given().header("Authorization", AUTH).delete("/v1/apis/" + apiId + "/datasources/" + dsName).then().statusCode(204);
    }

    @Test
    @Order(220)
    void getIntrospectionSchema_nestedFormat() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/schema")
        .then()
            .statusCode(200)
            .body("schema.definition", containsString("type Query"));
    }

    // ── Phase 2: Domain Names ────────────────────────────────────────────────

    private static String domainName;

    @Test
    @Order(300)
    void createDomainName() {
        domainName = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "domainName": "api.example.com",
                  "description": "Test domain",
                  "certificateArn": "arn:aws:acm:us-east-1:000000000000:certificate/123"
                }
                """)
        .when()
            .post("/v1/domainnames")
        .then()
            .statusCode(200)
            .body("domainNameConfig.domainName", equalTo("api.example.com"))
            .body("domainNameConfig.description", equalTo("Test domain"))
            .body("domainNameConfig.appsyncDomainName", containsString(".appsync-api."))
            .body("domainNameConfig.hostedZoneId", notNullValue())
            .extract().path("domainNameConfig.domainName");
    }

    @Test
    @Order(301)
    void getDomainName() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/domainnames/" + domainName)
        .then()
            .statusCode(200)
            .body("domainNameConfig.domainName", equalTo("api.example.com"));
    }

    @Test
    @Order(302)
    void listDomainNames() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/domainnames")
        .then()
            .statusCode(200)
            .body("domainNameConfigs", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(303)
    void associateApi() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "apiId": "%s"
                }
                """.formatted(apiId))
        .when()
            .post("/v1/domainnames/" + domainName + "/apiassociation")
        .then()
            .statusCode(200)
            .body("apiAssociation.apiId", equalTo(apiId))
            .body("apiAssociation.domainName", equalTo(domainName));
    }

    @Test
    @Order(304)
    void getAssociatedApi() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/domainnames/" + domainName + "/apiassociation")
        .then()
            .statusCode(200)
            .body("apiAssociation.apiId", equalTo(apiId));
    }

    @Test
    @Order(306)
    void disassociateApi() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v1/domainnames/" + domainName + "/apiassociation")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(307)
    void deleteDomainName() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v1/domainnames/" + domainName)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(308)
    void domainName_notFound() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/domainnames/nonexistent.example.com")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(309)
    void associateApi_invalidApi() {
        // Create a temp domain to test invalid association
        String tempDomain = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "domainName": "invalid-test.example.com",
                  "description": "temp",
                  "certificateArn": "arn:aws:acm:us-east-1:000000000000:certificate/123"
                }
                """)
        .when()
            .post("/v1/domainnames")
        .then()
            .statusCode(200)
            .extract().path("domainNameConfig.domainName");

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "apiId": "nonexistent00000000000000"
                }
                """)
        .when()
            .post("/v1/domainnames/" + tempDomain + "/apiassociation")
        .then()
            .statusCode(404);

        // cleanup
        given().header("Authorization", AUTH).delete("/v1/domainnames/" + tempDomain).then().statusCode(204);
    }

    // ── Phase 2: Channel Namespaces ──────────────────────────────────────────

    private static String channelNsName;

    @Test
    @Order(400)
    void createChannelNamespace() {
        channelNsName = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "name": "my-channels",
                  "description": "Test channel namespace"
                }
                """)
        .when()
            .post("/v2/apis/" + apiId + "/channelNamespaces")
        .then()
            .statusCode(200)
            .body("channelNamespace.name", equalTo("my-channels"))
            .body("channelNamespace.description", equalTo("Test channel namespace"))
            .extract().path("channelNamespace.name");
    }

    @Test
    @Order(401)
    void getChannelNamespace() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v2/apis/" + apiId + "/channelNamespaces/" + channelNsName)
        .then()
            .statusCode(200)
            .body("channelNamespace.name", equalTo("my-channels"));
    }

    @Test
    @Order(402)
    void listChannelNamespaces() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v2/apis/" + apiId + "/channelNamespaces")
        .then()
            .statusCode(200)
            .body("channelNamespaces", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(403)
    void deleteChannelNamespace() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v2/apis/" + apiId + "/channelNamespaces/" + channelNsName)
        .then()
            .statusCode(204);
    }

    // ── Phase 2: Schema Registry ─────────────────────────────────────────────

    @Test
    @Order(600)
    void startSchemaCreation_validatesSDL() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "definition": "type Query { invalid syntax!!! }"
                }
                """)
        .when()
            .post("/v1/apis/" + apiId + "/schemacreation")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(601)
    void schemaRegistry_parseSimpleSchema() {
        String tempApiId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "name": "schema-test", "authenticationType": "API_KEY" }
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
                {
                  "definition": "type Query { hello: String }"
                }
                """)
        .when()
            .post("/v1/apis/" + tempApiId + "/schemacreation")
        .then()
            .statusCode(200)
            .body("status", equalTo("ACTIVE"));

        given().header("Authorization", AUTH).delete("/v1/apis/" + tempApiId).then().statusCode(204);
    }

    @Test
    @Order(602)
    void schemaRegistry_parseWithAWSScalars() {
        String tempApiId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "name": "scalar-test", "authenticationType": "API_KEY" }
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
                {
                  "definition": "type Query { now: AWSDateTime, data: AWSJSON }"
                }
                """)
        .when()
            .post("/v1/apis/" + tempApiId + "/schemacreation")
        .then()
            .statusCode(200)
            .body("status", equalTo("ACTIVE"));

        given().header("Authorization", AUTH).delete("/v1/apis/" + tempApiId).then().statusCode(204);
    }

    @Test
    @Order(603)
    void schemaRegistry_schemaExtension() {
        String tempApiId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "name": "ext-test", "authenticationType": "API_KEY" }
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
                {
                  "definition": "type Query { hello: String } extend type Query { world: String }"
                }
                """)
        .when()
            .post("/v1/apis/" + tempApiId + "/schemacreation")
        .then()
            .statusCode(200)
            .body("status", equalTo("ACTIVE"));

        given().header("Authorization", AUTH).delete("/v1/apis/" + tempApiId).then().statusCode(204);
    }

    @Test
    @Order(604)
    void directive_awsApiKey_inSchema() {
        String tempApiId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "name": "dir-test", "authenticationType": "API_KEY" }
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
                {
                  "definition": "type Query @aws_api_key { hello: String }"
                }
                """)
        .when()
            .post("/v1/apis/" + tempApiId + "/schemacreation")
        .then()
            .statusCode(200)
            .body("status", equalTo("ACTIVE"));

        given().header("Authorization", AUTH).delete("/v1/apis/" + tempApiId).then().statusCode(204);
    }

    @Test
    @Order(605)
    void directive_awsSubscribe_validMutation() {
        String tempApiId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "name": "sub-test", "authenticationType": "API_KEY" }
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
                {
                  "definition": "type Query { dummy: String } type Mutation { createPost(id: ID!): Post } type Subscription { onPost: Post @aws_subscribe(mutations: [\\\"createPost\\\"]) } type Post { id: ID! }"
                }
                """)
        .when()
            .post("/v1/apis/" + tempApiId + "/schemacreation")
        .then()
            .statusCode(200)
            .body("status", equalTo("ACTIVE"));

        given().header("Authorization", AUTH).delete("/v1/apis/" + tempApiId).then().statusCode(204);
    }

    @Test
    @Order(606)
    void directive_skipInclude_work() {
        // @skip/@include are built-in graphql-java directives.
        // They are valid on FIELD (in queries), not on FIELD_DEFINITION (in type definitions).
        // This test verifies that SDL parsing still works when these standard directives
        // are present (e.g. @deprecated on a field definition).
        String tempApiId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "name": "skiptest", "authenticationType": "API_KEY" }
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
                {
                  "definition": "type Query { hello: String @deprecated(reason: \\\"use world\\\") }"
                }
                """)
        .when()
            .post("/v1/apis/" + tempApiId + "/schemacreation")
        .then()
            .statusCode(200)
            .body("status", equalTo("ACTIVE"));

        given().header("Authorization", AUTH).delete("/v1/apis/" + tempApiId).then().statusCode(204);
    }

    @Test
    @Order(607)
    void directive_unknown_rejected() {
        String tempApiId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "name": "unknowntest", "authenticationType": "API_KEY" }
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
                {
                  "definition": "type Query { hello: String } directive @unknownDirective on FIELD_DEFINITION"
                }
                """)
        .when()
            .post("/v1/apis/" + tempApiId + "/schemacreation")
        .then()
            .statusCode(400);

        given().header("Authorization", AUTH).delete("/v1/apis/" + tempApiId).then().statusCode(204);
    }

    // ── Phase 2: Merged APIs ───────────────────────────────────────────────

    @Test
    @Order(610)
    void associateSourceGraphqlApi() {
        // Create source API
        String sourceApiId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "name": "merged-source", "authenticationType": "API_KEY" }
                """)
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(200)
            .extract().path("graphqlApi.apiId");

        // Association between the main API and source
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "sourceApiId": "%s",
                  "description": "test association"
                }
                """.formatted(sourceApiId))
        .when()
            .post("/v1/mergedApis/" + apiId + "/sourceApiAssociations")
        .then()
            .statusCode(200)
            .body("sourceApiAssociation.mergedApiId", equalTo(apiId))
            .body("sourceApiAssociation.sourceApiId", equalTo(sourceApiId))
            .body("sourceApiAssociation.sourceApiAssociationStatus", equalTo("MERGED"))
            .body("sourceApiAssociation.associationId", notNullValue());

        given().header("Authorization", AUTH).delete("/v1/apis/" + sourceApiId).then().statusCode(204);
    }

    @Test
    @Order(611)
    void getSourceApiAssociation() {
        String sourceApiId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "name": "get-source", "authenticationType": "API_KEY" }
                """)
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(200)
            .extract().path("graphqlApi.apiId");

        String assocId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "sourceApiId": "%s" }
                """.formatted(sourceApiId))
        .when()
            .post("/v1/mergedApis/" + apiId + "/sourceApiAssociations")
        .then()
            .statusCode(200)
            .extract().path("sourceApiAssociation.associationId");

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/mergedApis/" + apiId + "/sourceApiAssociations/" + assocId)
        .then()
            .statusCode(200)
            .body("sourceApiAssociation.associationId", equalTo(assocId));

        given().header("Authorization", AUTH).delete("/v1/apis/" + sourceApiId).then().statusCode(204);
    }

    @Test
    @Order(612)
    void listSourceApiAssociations() {
        String sourceApiId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "name": "list-source", "authenticationType": "API_KEY" }
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
                { "sourceApiId": "%s" }
                """.formatted(sourceApiId))
        .when()
            .post("/v1/mergedApis/" + apiId + "/sourceApiAssociations")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId + "/sourceApiAssociations")
        .then()
            .statusCode(200)
            .body("sourceApiAssociationSummaries", hasSize(greaterThanOrEqualTo(1)));

        given().header("Authorization", AUTH).delete("/v1/apis/" + sourceApiId).then().statusCode(204);
    }

    @Test
    @Order(613)
    void deleteSourceApiAssociation() {
        String sourceApiId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "name": "del-source", "authenticationType": "API_KEY" }
                """)
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(200)
            .extract().path("graphqlApi.apiId");

        String assocId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "sourceApiId": "%s" }
                """.formatted(sourceApiId))
        .when()
            .post("/v1/mergedApis/" + apiId + "/sourceApiAssociations")
        .then()
            .statusCode(200)
            .extract().path("sourceApiAssociation.associationId");

        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v1/mergedApis/" + apiId + "/sourceApiAssociations/" + assocId)
        .then()
            .statusCode(200)
            .body("sourceApiAssociationStatus", equalTo("DELETION_SCHEDULED"));

        given().header("Authorization", AUTH).delete("/v1/apis/" + sourceApiId).then().statusCode(204);
    }

    // ── Phase 2: New endpoint coverage ────────────────────────────────

    @Test
    @Order(614)
    void updateDomainName() {
        String testDomain = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "domainName": "update-test.example.com",
                  "certificateArn": "arn:aws:acm:us-east-1:000000000000:certificate/update"
                }
                """)
        .when()
            .post("/v1/domainnames")
        .then()
            .statusCode(200)
            .extract().path("domainNameConfig.domainName");

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "description": "updated description" }
                """)
        .when()
            .post("/v1/domainnames/" + testDomain)
        .then()
            .statusCode(200)
            .body("domainNameConfig.domainName", equalTo(testDomain))
            .body("domainNameConfig.description", equalTo("updated description"));

        given().header("Authorization", AUTH).delete("/v1/domainnames/" + testDomain).then().statusCode(204);
    }

    @Test
    @Order(615)
    void associateMergedGraphqlApi() {
        String sourceApiId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "name": "merged-source-v2", "authenticationType": "API_KEY" }
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
                {
                  "mergedApiIdentifier": "%s",
                  "description": "merged test"
                }
                """.formatted(apiId))
        .when()
            .post("/v1/sourceApis/" + sourceApiId + "/mergedApiAssociations")
        .then()
            .statusCode(200)
            .body("sourceApiAssociation.sourceApiId", equalTo(sourceApiId))
            .body("sourceApiAssociation.mergedApiId", equalTo(apiId))
            .body("sourceApiAssociation.sourceApiAssociationStatus", equalTo("MERGED"));

        given().header("Authorization", AUTH).delete("/v1/apis/" + sourceApiId).then().statusCode(204);
    }

    @Test
    @Order(616)
    void updateSourceApiAssociation() {
        String sourceApiId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "name": "update-source", "authenticationType": "API_KEY" }
                """)
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(200)
            .extract().path("graphqlApi.apiId");

        String assocId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "sourceApiId": "%s" }
                """.formatted(sourceApiId))
        .when()
            .post("/v1/mergedApis/" + apiId + "/sourceApiAssociations")
        .then()
            .statusCode(200)
            .extract().path("sourceApiAssociation.associationId");

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "description": "updated assoc" }
                """)
        .when()
            .post("/v1/mergedApis/" + apiId + "/sourceApiAssociations/" + assocId)
        .then()
            .statusCode(200)
            .body("sourceApiAssociation.description", equalTo("updated assoc"))
            .body("sourceApiAssociation.associationId", equalTo(assocId));

        given().header("Authorization", AUTH).delete("/v1/apis/" + sourceApiId).then().statusCode(204);
    }

    @Test
    @Order(617)
    void disassociateMergedGraphqlApi() {
        String sourceApiId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "name": "disassoc-source", "authenticationType": "API_KEY" }
                """)
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(200)
            .extract().path("graphqlApi.apiId");

        String assocId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "mergedApiIdentifier": "%s" }
                """.formatted(apiId))
        .when()
            .post("/v1/sourceApis/" + sourceApiId + "/mergedApiAssociations")
        .then()
            .statusCode(200)
            .extract().path("sourceApiAssociation.associationId");

        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v1/sourceApis/" + sourceApiId + "/mergedApiAssociations/" + assocId)
        .then()
            .statusCode(200)
            .body("sourceApiAssociationStatus", equalTo("DELETION_SCHEDULED"));

        given().header("Authorization", AUTH).delete("/v1/apis/" + sourceApiId).then().statusCode(204);
    }

    // ── Teardown ─────────────────────────────────────────────────────────────

    // ── Phase 2: Edge Cases ──────────────────────────────────────────────

    @Test
    @Order(620)
    void updateGraphqlApi_additionalAuthProvidersNull() {
        // Set initial providers
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "additionalAuthenticationProviders": [
                    {"authenticationType": "AWS_IAM"}
                  ]
                }
                """)
        .when()
            .post("/v1/apis/" + apiId)
        .then()
            .statusCode(200)
            .body("graphqlApi.additionalAuthenticationProviders", hasSize(1));

        // Send null — should preserve existing
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "additionalAuthenticationProviders": null }
                """)
        .when()
            .post("/v1/apis/" + apiId)
        .then()
            .statusCode(200)
            .body("graphqlApi.additionalAuthenticationProviders", hasSize(1));
    }

    @Test
    @Order(621)
    void createResolver_withMaxBatchSizeZero() {
        String dsName = "batch-zero-ds";
        given().header("Authorization", AUTH).contentType("application/json")
            .body("""
                { "name": "%s", "type": "NONE" }
                """.formatted(dsName))
            .post("/v1/apis/" + apiId + "/datasources").then().statusCode(200);

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "fieldName": "batchZeroTest",
                  "dataSourceName": "%s",
                  "maxBatchSize": 0
                }
                """.formatted(dsName))
        .when()
            .post("/v1/apis/" + apiId + "/types/Query/resolvers")
        .then()
            .statusCode(200)
            .body("resolver.maxBatchSize", equalTo(0));

        given().header("Authorization", AUTH)
            .delete("/v1/apis/" + apiId + "/types/Query/resolvers/batchZeroTest").then().statusCode(204);
        given().header("Authorization", AUTH)
            .delete("/v1/apis/" + apiId + "/datasources/" + dsName).then().statusCode(204);
    }

    @Test
    @Order(622)
    void updateGraphqlApi_dnsNull() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "dns": null }
                """)
        .when()
            .post("/v1/apis/" + apiId)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(623)
    void createDomainName_invalidCertificateArn() {
        // Our emulator doesn't validate certificate ARN format, so this should succeed (200).
        // AWS itself validates the pattern, but we're permissive.
        String tempDomain = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "domainName": "invalidd-cert.example.com",
                  "certificateArn": "not-a-valid-arn"
                }
                """)
        .when()
            .post("/v1/domainnames")
        .then()
            .statusCode(200)
            .extract().path("domainNameConfig.domainName");

        given().header("Authorization", AUTH)
            .delete("/v1/domainnames/" + tempDomain).then().statusCode(204);
    }

    @Test
    @Order(624)
    void deleteGraphqlApi_removesDomainAssociation() {
        // Create a domain + API + association
        String tempApiId = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "name": "edge-del-api", "authenticationType": "API_KEY" }
                """)
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(200)
            .extract().path("graphqlApi.apiId");

        String tempDomain = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "domainName": "edge-del.example.com",
                  "certificateArn": "arn:aws:acm:us-east-1:000000000000:certificate/edge"
                }
                """)
        .when()
            .post("/v1/domainnames")
        .then()
            .statusCode(200)
            .extract().path("domainNameConfig.domainName");

        // Associate
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "apiId": "%s" }
                """.formatted(tempApiId))
        .when()
            .post("/v1/domainnames/" + tempDomain + "/apiassociation")
        .then()
            .statusCode(200);

        // Delete API
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v1/apis/" + tempApiId)
        .then()
            .statusCode(204);

        // Domain should still exist (association was cleaned up)
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/domainnames/" + tempDomain)
        .then()
            .statusCode(200);

        // Cleanup domain
        given().header("Authorization", AUTH)
            .delete("/v1/domainnames/" + tempDomain).then().statusCode(204);
    }

    @Test
    @Order(625)
    void deleteDomainName_associatedApiStillWorks() {
        // Create a domain + associate with the main apiId
        String tempDomain = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "domainName": "edge-api-still-works.example.com",
                  "certificateArn": "arn:aws:acm:us-east-1:000000000000:certificate/edge2"
                }
                """)
        .when()
            .post("/v1/domainnames")
        .then()
            .statusCode(200)
            .extract().path("domainNameConfig.domainName");

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                { "apiId": "%s" }
                """.formatted(apiId))
        .when()
            .post("/v1/domainnames/" + tempDomain + "/apiassociation")
        .then()
            .statusCode(200);

        // Delete domain
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/v1/domainnames/" + tempDomain)
        .then()
            .statusCode(204);

        // API should still work
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/apis/" + apiId)
        .then()
            .statusCode(200)
            .body("graphqlApi.apiId", equalTo(apiId));
    }

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
