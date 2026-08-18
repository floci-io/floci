package io.github.hectorvent.floci.services.appsync;

import io.github.hectorvent.floci.core.common.ResolvedServiceCatalog;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncExecutionController;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AppSyncAuthIntegrationTest {

    private static final String MGMT_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/appsync/aws4_request";

    @Inject
    ResolvedServiceCatalog catalog;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void catalogRegistersExecutionController() {
        assertTrue(catalog.byResourceClass(AppSyncExecutionController.class).isPresent());
    }

    @Test
    void unsignedApiKeyPostReturnsAppSyncJsonNotS3Xml() {
        String apiId = createApi("auth-" + UUID.randomUUID().toString().substring(0, 8));
        startSchema(apiId, "type Query { hello: String }");
        awaitSchemaSuccess(apiId);
        String apiKey = createApiKey(apiId);

        given()
            .contentType("application/graphql")
            .header("x-api-key", apiKey)
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body(not(containsString("<Error>")))
            .body(not(containsString("NoSuchBucket")))
            .body("data.hello", nullValue());
    }

    @Test
    void missingCredentialsReturns401UnauthorizedException() {
        String apiId = createApi("unauth-" + UUID.randomUUID().toString().substring(0, 8));
        startSchema(apiId, "type Query { hello: String }");
        awaitSchemaSuccess(apiId);

        given()
            .contentType("application/json")
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(401)
            .header("x-amzn-errortype", containsString("UnauthorizedException"))
            .header("x-amz-request-id", not(nullValue()))
            .body("errors[0].errorType", equalTo("UnauthorizedException"))
            .body("errors[0].message", equalTo("Missing authorization header"))
            .body("data", nullValue())
            .body("__type", nullValue());
    }

    @Test
    void invalidApiKeyReturns401() {
        String apiId = createApi("badkey-" + UUID.randomUUID().toString().substring(0, 8));
        startSchema(apiId, "type Query { hello: String }");
        awaitSchemaSuccess(apiId);

        given()
            .contentType("application/json")
            .header("x-api-key", "da2-does-not-exist")
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(401)
            .header("x-amzn-errortype", containsString("UnauthorizedException"))
            .body("errors[0].message", equalTo("You are not authorized to make this call."));
    }

    @Test
    void dummySigV4OnApiKeyApiReturns401() {
        String apiId = createApi("sigv4-" + UUID.randomUUID().toString().substring(0, 8));
        startSchema(apiId, "type Query { hello: String }");
        awaitSchemaSuccess(apiId);

        given()
            .contentType("application/json")
            .header("Authorization", MGMT_AUTH)
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(401)
            .header("x-amzn-errortype", containsString("UnauthorizedException"));
    }

    @Test
    void sigV4PlusValidApiKeyDoesNotFallBackToApiKey() {
        String apiId = createApi("nofallback-" + UUID.randomUUID().toString().substring(0, 8));
        startSchema(apiId, "type Query { hello: String }");
        awaitSchemaSuccess(apiId);
        String apiKey = createApiKey(apiId);

        given()
            .contentType("application/json")
            .header("x-api-key", apiKey)
            .header("Authorization", MGMT_AUTH)
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(401)
            .header("x-amzn-errortype", containsString("UnauthorizedException"));
    }

    @Test
    void unknownApiReturns404BeforeAuth() {
        given()
            .contentType("application/json")
            .header("x-api-key", "da2-looks-valid")
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/does-not-exist-xyz/graphql")
        .then()
            .statusCode(404)
            .header("x-amzn-errortype", containsString("NotFoundException"));
    }

    @Test
    void missingCredentialsOnApiWithoutSchemaReturns401Not502() {
        String bareApiId = createApi("bare-auth-" + UUID.randomUUID().toString().substring(0, 8));

        given()
            .contentType("application/json")
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + bareApiId + "/graphql")
        .then()
            .statusCode(401)
            .header("x-amzn-errortype", containsString("UnauthorizedException"))
            .body("errors[0].message", equalTo("Missing authorization header"));
    }

    @Test
    void iamCallerOnApiKeyFieldReturns200WithUnauthorizedError() {
        String apiId = given()
            .header("Authorization", MGMT_AUTH)
            .contentType("application/json")
            .body("""
                {
                  "name": "multi-%s",
                  "authenticationType": "AWS_IAM",
                  "additionalAuthenticationProviders": [
                    {"authenticationType": "API_KEY"}
                  ]
                }
                """.formatted(UUID.randomUUID().toString().substring(0, 8)))
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(200)
            .extract().path("graphqlApi.apiId");

        startSchema(apiId, "type Query { hello: String @aws_api_key }");
        awaitSchemaSuccess(apiId);

        given()
            .contentType("application/json")
            .header("Authorization", MGMT_AUTH)
            .body("{\"query\":\"{ hello }\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(200)
            .header("x-amzn-errortype", nullValue())
            .body("data.hello", nullValue())
            .body("errors[0].errorType", equalTo("Unauthorized"))
            .body("errors[0].message", equalTo("Not Authorized to access hello on type Query"))
            .body("errors[0].path", equalTo(java.util.List.of("hello")));
    }

    @Test
    void additionalModeIamCanIntrospect() {
        String apiId = given()
            .header("Authorization", MGMT_AUTH)
            .contentType("application/json")
            .body("""
                {
                  "name": "intro-%s",
                  "authenticationType": "API_KEY",
                  "additionalAuthenticationProviders": [
                    {"authenticationType": "AWS_IAM"}
                  ]
                }
                """.formatted(UUID.randomUUID().toString().substring(0, 8)))
        .when()
            .post("/v1/apis")
        .then()
            .statusCode(200)
            .extract().path("graphqlApi.apiId");

        startSchema(apiId, "type Query { hello: String }");
        awaitSchemaSuccess(apiId);

        given()
            .contentType("application/json")
            .header("Authorization", MGMT_AUTH)
            .body("{\"query\":\"{ __schema { types { name } } }\"}")
        .when()
            .post("/v1/apis/" + apiId + "/graphql")
        .then()
            .statusCode(200)
            .body("data.__schema.types", not(nullValue()))
            .body("errors", nullValue());
    }

    @Test
    void awsAuthOnFieldDefinitionSchemaSucceeds() {
        String apiId = createApi("aws-auth-" + UUID.randomUUID().toString().substring(0, 8));
        startSchema(apiId, "type Query { secret: String @aws_auth(cognito_groups: [\"Admins\"]) }");
        awaitSchemaSuccess(apiId);
    }

    private static String createApi(String name) {
        return given()
            .header("Authorization", MGMT_AUTH)
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
            .header("Authorization", MGMT_AUTH)
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/v1/apis/" + apiId + "/apikeys")
        .then()
            .statusCode(200)
            .extract().path("apiKey.apiKey");
    }

    private static void startSchema(String apiId, String definition) {
        String escaped = definition.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        given()
            .header("Authorization", MGMT_AUTH)
            .contentType("application/json")
            .body("{\"definition\": \"" + escaped + "\"}")
        .when()
            .post("/v1/apis/" + apiId + "/schemacreation")
        .then()
            .statusCode(200);
    }

    private static void awaitSchemaSuccess(String apiId) {
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(25))
                .until(() -> {
                    String status = given()
                        .header("Authorization", MGMT_AUTH)
                    .when()
                        .get("/v1/apis/" + apiId + "/schemacreation")
                    .then()
                        .statusCode(200)
                        .extract().path("status");
                    return "SUCCESS".equals(status);
                });
    }
}
