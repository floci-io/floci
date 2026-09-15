package io.github.hectorvent.floci.services.appsync;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.graphql.GraphqlSidecarServer;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncErrorFormatter;
import io.github.hectorvent.floci.services.appsync.graphql.SchemaRegistry;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
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

    private static HttpServer graphqlServer;

    @Inject
    SchemaRegistry schemaRegistry;

    private String apiId;
    private String apiKey;

    @BeforeAll
    static void configureRestAssured() throws Exception {
        // Matches src/test/resources/application.yml's services.appsync.graphql-url.
        graphqlServer = GraphqlSidecarServer.start(18181);
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterAll
    static void stopGraphqlSidecar() {
        graphqlServer.stop(0);
    }

    @BeforeEach
    void createApiWithSchema() {
        apiId = createApi("exec-" + UUID.randomUUID().toString().substring(0, 8));
        apiKey = createApiKey(apiId);
        startSchema(apiId, "type Query { hello: String }");
        awaitSchemaSuccess(apiId);
    }

    @Test
    void dualContentTypeJsonReturnsNullHello() {
        given()
            .header("x-api-key", apiKey)
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
            .header("x-api-key", apiKey)
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
            .header("x-amzn-errortype", containsString("MalformedHttpRequestException"))
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
            .statusCode(404)
            .header("x-amzn-errortype", containsString("NotFoundException"));
    }

    @Test
    void apiWithoutSchemaReturns502GraphQLSchemaException() {
        String bareApiId = createApi("bare-" + UUID.randomUUID().toString().substring(0, 8));
        String bareKey = createApiKey(bareApiId);

        given()
            .header("x-api-key", bareKey)
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
            .header("x-api-key", apiKey)
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
            .header("x-api-key", apiKey)
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
            .header("x-api-key", apiKey)
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
        String multiKey = createApiKey(multiApi);
        startSchema(multiApi, "type Query { hello(id: String): String }");
        awaitSchemaSuccess(multiApi);

        given()
            .header("x-api-key", multiKey)
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
        String subKey = createApiKey(subApi);
        startSchema(subApi, """
                type Query { hello: String }
                type Subscription { onHello: String }
                """);
        awaitSchemaSuccess(subApi);

        given()
            .header("x-api-key", subKey)
            .contentType("application/json")
            .body("{\"query\":\"subscription { onHello }\"}")
        .when()
            .post("/v1/apis/" + subApi + "/graphql")
        .then()
            .statusCode(200)
            .body("errors[0].errorType", equalTo("OperationNotSupported"));
    }

    @Test
    void awsDateTimeLiteralCoercionRejectsInvalidValueAsValidationErrorNotServerError() {
        // graphql-java only catches CoercingParseLiteralException while validating a literal
        // argument, and every AppSyncScalars parseLiteral() used to delegate to parseValue(),
        // which throws CoercingParseValueException instead: uncaught, that escaped as an HTTP
        // 500 rather than becoming a spec-correct ValidationError. Fixed in AppSyncScalars.
        String scalarApi = createApi("scalar-lit-" + UUID.randomUUID().toString().substring(0, 8));
        String scalarKey = createApiKey(scalarApi);
        startSchema(scalarApi, "type Query { echo(t: AWSDateTime): AWSDateTime }");
        awaitSchemaSuccess(scalarApi);

        given()
            .header("x-api-key", scalarKey)
            .contentType("application/json")
            .body("{\"query\":\"{ echo(t: \\\"not-a-date\\\") }\"}")
        .when()
            .post("/v1/apis/" + scalarApi + "/graphql")
        .then()
            .statusCode(200)
            .body("errors[0].errorType", equalTo("ValidationError"))
            .body("errors[0].message", containsString("Invalid AWSDateTime"));

        given()
            .header("x-api-key", scalarKey)
            .contentType("application/json")
            .body("{\"query\":\"{ echo(t: \\\"2026-01-01T00:00:00Z\\\") }\"}")
        .when()
            .post("/v1/apis/" + scalarApi + "/graphql")
        .then()
            .statusCode(200)
            .body("errors", nullValue());
    }

    @Test
    void awsDateTimeVariableCoercionRejectsInvalidValueAndAcceptsValid() {
        // Proves the real AWSDateTime Coercing actually runs through the sidecar's schema
        // build, not just that StartSchemaCreation accepts the type name (issue #2917).
        String scalarApi = createApi("scalar-" + UUID.randomUUID().toString().substring(0, 8));
        String scalarKey = createApiKey(scalarApi);
        startSchema(scalarApi, "type Query { echo(t: AWSDateTime): AWSDateTime }");
        awaitSchemaSuccess(scalarApi);

        given()
            .header("x-api-key", scalarKey)
            .contentType("application/json")
            .body("""
                {
                  "query": "query($t: AWSDateTime) { echo(t: $t) }",
                  "variables": {"t": "not-a-date"}
                }
                """)
        .when()
            .post("/v1/apis/" + scalarApi + "/graphql")
        .then()
            .statusCode(200)
            .body("errors[0].errorType", equalTo("ValidationError"))
            .body("errors[0].message", containsString("Invalid AWSDateTime"));

        given()
            .header("x-api-key", scalarKey)
            .contentType("application/json")
            .body("""
                {
                  "query": "query($t: AWSDateTime) { echo(t: $t) }",
                  "variables": {"t": "2026-01-01T00:00:00Z"}
                }
                """)
        .when()
            .post("/v1/apis/" + scalarApi + "/graphql")
        .then()
            .statusCode(200)
            .body("errors", nullValue());
    }

    @Test
    void fragmentCycleAgainstProtectedFieldNeverLeaksRealData() {
        // Proves the fail-closed fix in GraphqlSidecarServer.plan(): a query the planner
        // can't analyze must never fall back to "nothing to authorize, run it anyway."
        String protectedApi = createApi("protected-" + UUID.randomUUID().toString().substring(0, 8));
        String protectedKey = createApiKey(protectedApi);
        startSchema(protectedApi, "type Query { hello: String secret: String @aws_iam }");
        awaitSchemaSuccess(protectedApi);

        given()
            .header("x-api-key", protectedKey)
            .contentType("application/json")
            .body("{\"query\":\"{ hello secret ...a } fragment a on Query { ...a }\"}")
        .when()
            .post("/v1/apis/" + protectedApi + "/graphql")
        .then()
            .body("data.secret", nullValue());
    }

    @Test
    void whitespaceOnlyQueryReturns200SyntaxError() {
        given()
            .header("x-api-key", apiKey)
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
        String hydrateKey = createApiKey(hydrateApi);
        startSchema(hydrateApi, "type Query { hello: String }");
        awaitSchemaSuccess(hydrateApi);

        schemaRegistry.remove(hydrateApi);
        assertTrue(schemaRegistry.getSdl(hydrateApi).isEmpty());

        given()
            .header("x-api-key", hydrateKey)
            .contentType("application/json")
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + hydrateApi + "/graphql")
        .then()
            .statusCode(502);

        // invoke rehydrate via worker (package-visible through CDI bean)
        rehydrateWorker().rehydrateSchemas();

        given()
            .header("x-api-key", hydrateKey)
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

    private static String createApiKey(String apiId) {
        return given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/v1/apis/" + apiId + "/apikeys")
        .then()
            .statusCode(200)
            .extract().path("apiKey.id");
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
