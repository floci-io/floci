package io.github.hectorvent.floci.services.appsync;

import io.github.hectorvent.floci.services.appsync.graphql.AppSyncErrorFormatter;
import io.github.hectorvent.floci.services.appsync.graphql.SchemaRegistry;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AppSyncExecutionIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/appsync/aws4_request";

    @Inject
    SchemaRegistry schemaRegistry;

    private String apiId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createApiWithSchema() {
        apiId = createApi("exec-" + UUID.randomUUID().toString().substring(0, 8));
        startSchema(apiId, "type Query { hello: String }");
        awaitSchemaSuccess(apiId);
    }

    @Test
    void dualContentTypeJsonReturnsNullHello() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("data.hello", nullValue())
            .body("errors", nullValue());
    }

    @Test
    void dualContentTypeGraphqlReturnsNullHello() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/graphql")
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(200)
            .body("data.hello", nullValue());
    }

    @Test
    void missingContentTypeReturns400GraphqlErrors() {
        given()
            .header("Authorization", AUTH)
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(400)
            .body("errors[0].errorType", equalTo("MalformedHttpRequestException"))
            .body("__type", nullValue());
    }

    @Test
    void unsupportedContentTypeReturns400GraphqlErrors() {
        given()
            .header("Authorization", AUTH)
            .contentType("text/plain")
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(400)
            .body("errors[0].errorType", equalTo("MalformedHttpRequestException"))
            .body("__type", nullValue());
    }

    @Test
    void emptyBodyReturns400MalformedWithEmptyMessage() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(400)
            .body("errors[0].errorType", equalTo("MalformedHttpRequestException"))
            .body("errors[0].message", equalTo(AppSyncErrorFormatter.MSG_EMPTY_BODY));
    }

    @Test
    void emptyObjectBodyReturns400UnableToParse() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(400)
            .body("errors[0].errorType", equalTo("MalformedHttpRequestException"))
            .body("errors[0].message", equalTo(AppSyncErrorFormatter.MSG_UNABLE_TO_PARSE));
    }

    @Test
    void arrayBodyReturns400UnableToParse() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("[{\"query\":\"{ hello }\"}]")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(400)
            .body("errors[0].errorType", equalTo("MalformedHttpRequestException"))
            .body("errors[0].message", equalTo(AppSyncErrorFormatter.MSG_UNABLE_TO_PARSE));
    }

    @Test
    void unparseableBodyReturns400UnableToParse() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("meow")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(400)
            .body("errors[0].errorType", equalTo("MalformedHttpRequestException"))
            .body("errors[0].message", equalTo(AppSyncErrorFormatter.MSG_UNABLE_TO_PARSE));
    }

    @Test
    void unknownApiIdReturns404() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/does-not-exist-xyz/graphql")
        .then()
            .statusCode(404);
    }

    @Test
    void apiWithoutSchemaReturns502GraphQLSchemaException() {
        String bareApiId = createApi("bare-" + UUID.randomUUID().toString().substring(0, 8));

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + bareApiId + "/graphql")
        .then()
            .statusCode(502)
            .header("x-amzn-errortype", containsString("GraphQLSchemaException"))
            .body("errors[0].errorType", equalTo("GraphQLSchemaException"))
            .body("errors[0].message", equalTo(AppSyncErrorFormatter.MSG_NO_SCHEMA));
    }

    @Test
    void validationErrorReturns200WithTopLevelErrorType() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"query\":\"{ nope }\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(200)
            .body("errors[0].errorType", equalTo("ValidationError"))
            .body("errors[0].extensions", nullValue());
    }

    @Test
    void syntaxErrorReturns200WithSyntaxError() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"query\":\"{ hello\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(200)
            .body("errors[0].errorType", equalTo("SyntaxError"));
    }

    @Test
    void introspectionReturnsNonEmptyTypes() {
        Map<String, Object> body = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"query\":\"{ __schema { types { name } } }\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(200)
            .extract().jsonPath().getMap("");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) data.get("__schema");
        @SuppressWarnings("unchecked")
        List<?> types = (List<?>) schema.get("types");
        assertThat(types, hasSize(greaterThan(0)));
    }

    @Test
    void variablesAndOperationNameExecuteSelectedOp() {
        String multiApi = createApi("multi-" + UUID.randomUUID().toString().substring(0, 8));
        startSchema(multiApi, "type Query { hello(id: String): String }");
        awaitSchemaSuccess(multiApi);

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "query": "query GetHello($id: String) { hello(id: $id) } query Other { hello }",
                  "variables": {"id": "x"},
                  "operationName": "GetHello"
                }
                """)
        .when()
            .post("/v1/apis/" + multiApi + "/graphql")
        .then()
            .statusCode(200)
            .body("data.hello", nullValue());
    }

    @Test
    void httpSubscriptionReturns200OperationNotSupported() {
        String subApi = createApi("sub-" + UUID.randomUUID().toString().substring(0, 8));
        startSchema(subApi, """
                type Query { hello: String }
                type Subscription { onHello: String }
                """);
        awaitSchemaSuccess(subApi);

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"query\":\"subscription { onHello }\"}")
        .when()
            .post("/v1/apis/" + subApi + "/graphql")
        .then()
            .statusCode(200)
            .body("errors[0].errorType", equalTo("OperationNotSupported"));
    }

    @Test
    void whitespaceOnlyQueryReturns200SyntaxError() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"query\":\"   \"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(200)
            .body("errors[0].errorType", equalTo("SyntaxError"));
    }

    @Test
    void rehydrateFromSchemaStoreEnablesExecute() {
        String hydrateApi = createApi("hydrate-" + UUID.randomUUID().toString().substring(0, 8));
        startSchema(hydrateApi, "type Query { hello: String }");
        awaitSchemaSuccess(hydrateApi);

        schemaRegistry.remove(hydrateApi);
        assertTrue(schemaRegistry.getSchema(hydrateApi).isEmpty());

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + hydrateApi + "/graphql")
        .then()
            .statusCode(502);

        // invoke rehydrate via worker (package-visible through CDI bean)
        rehydrateWorker().rehydrateSchemas();

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + hydrateApi + "/graphql")
        .then()
            .statusCode(200)
            .body("data.hello", nullValue());
    }

    @Inject
    io.github.hectorvent.floci.services.appsync.graphql.SchemaCreationWorker schemaCreationWorker;

    private io.github.hectorvent.floci.services.appsync.graphql.SchemaCreationWorker rehydrateWorker() {
        return schemaCreationWorker;
    }

    private static String createApi(String name) {
        return given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"name": "%s", "authenticationType": "API_KEY"}
                """.formatted(name))
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(200)
            .extract().path("graphqlApi.apiId");
    }

    private static void startSchema(String apiId, String definition) {
        String escaped = definition.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"definition\": \"" + escaped + "\"}")
        .when()
            .post("/v1/apis/" + apiId + "/schemacreation")
        .then()
            .statusCode(200)
            .body("status", equalTo("PROCESSING"));
    }

    private static void awaitSchemaSuccess(String apiId) {
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(25))
                .until(() -> {
                    String status = given()
                        .header("Authorization", AUTH)
                    .when()
                        .get("/v1/apis/" + apiId + "/schemacreation")
                    .then()
                        .statusCode(200)
                        .extract().path("status");
                    return "SUCCESS".equals(status);
                });
    }
}
