package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.testutil.ExecuteApiRequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * Gateway responses of a REST API: the {@code PutGatewayResponse} family on the management plane,
 * the {@code x-amazon-apigateway-gateway-responses} OpenAPI extension, and their effect on the
 * execute plane whenever the gateway itself answers (an unmatched path, a rejected validation),
 * which is what puts CORS headers on a browser-visible 4XX.
 */
@QuarkusTest
class ApiGatewayGatewayResponseIntegrationTest {

    private static final String DEFAULT_TEMPLATE = "{\"message\":$context.error.messageString}";

    private String apiId;

    @BeforeEach
    void createRestApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"gateway-responses-test\"}")
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");
    }

    @AfterEach
    void deleteRestApi() {
        if (apiId != null) {
            given().when().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    // ──────────────────────────── management plane ────────────────────────────

    @Test
    void everyTypeIsListedAsItsDefaultUntilCustomised() {
        given()
                .when().get("/restapis/" + apiId + "/gatewayresponses")
                .then().statusCode(200)
                .body("item", hasSize(21))
                .body("item.responseType", containsInAnyOrder(
                        "ACCESS_DENIED", "API_CONFIGURATION_ERROR", "AUTHORIZER_CONFIGURATION_ERROR",
                        "AUTHORIZER_FAILURE", "BAD_REQUEST_PARAMETERS", "BAD_REQUEST_BODY", "DEFAULT_4XX",
                        "DEFAULT_5XX", "EXPIRED_TOKEN", "INTEGRATION_FAILURE", "INTEGRATION_TIMEOUT",
                        "INVALID_API_KEY", "INVALID_SIGNATURE", "MISSING_AUTHENTICATION_TOKEN", "QUOTA_EXCEEDED",
                        "REQUEST_TOO_LARGE", "RESOURCE_NOT_FOUND", "THROTTLED", "UNAUTHORIZED",
                        "UNSUPPORTED_MEDIA_TYPE", "WAF_FILTERED"))
                .body("item.findAll { it.defaultResponse == false }", hasSize(0));

        given()
                .when().get("/restapis/" + apiId + "/gatewayresponses/MISSING_AUTHENTICATION_TOKEN")
                .then().statusCode(200)
                .body("responseType", equalTo("MISSING_AUTHENTICATION_TOKEN"))
                .body("statusCode", equalTo("403"))
                .body("defaultResponse", equalTo(true))
                .body("responseParameters", equalTo(Map.of()))
                .body("responseTemplates.'application/json'", equalTo(DEFAULT_TEMPLATE));

        // The two DEFAULT_ types have no status of their own.
        given()
                .when().get("/restapis/" + apiId + "/gatewayresponses/DEFAULT_4XX")
                .then().statusCode(200)
                .body("$", not(hasKey("statusCode")))
                .body("defaultResponse", equalTo(true));
    }

    @Test
    void putGetUpdateAndDeleteAGatewayResponse() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"responseParameters\":{"
                        + "\"gatewayresponse.header.Access-Control-Allow-Origin\":\"'*'\","
                        + "\"gatewayresponse.header.Access-Control-Allow-Headers\":\"'*'\"},"
                        + "\"responseTemplates\":{\"application/json\":\"{\\\"error\\\":$context.error.messageString}\"}}")
                .when().put("/restapis/" + apiId + "/gatewayresponses/DEFAULT_4XX")
                .then().statusCode(201)
                .body("responseType", equalTo("DEFAULT_4XX"))
                .body("defaultResponse", equalTo(false))
                .body("$", not(hasKey("statusCode")))
                .body("responseParameters.'gatewayresponse.header.Access-Control-Allow-Origin'", equalTo("'*'"))
                .body("responseTemplates.'application/json'", equalTo("{\"error\":$context.error.messageString}"));

        given()
                .contentType(ContentType.JSON)
                .body("{\"statusCode\":\"404\"}")
                .when().put("/restapis/" + apiId + "/gatewayresponses/MISSING_AUTHENTICATION_TOKEN")
                .then().statusCode(201)
                .body("statusCode", equalTo("404"))
                .body("responseTemplates", equalTo(Map.of()))
                .body("defaultResponse", equalTo(false));

        given()
                .when().get("/restapis/" + apiId + "/gatewayresponses")
                .then().statusCode(200)
                .body("item", hasSize(21))
                .body("item.findAll { it.defaultResponse == false }.responseType",
                        containsInAnyOrder("DEFAULT_4XX", "MISSING_AUTHENTICATION_TOKEN"));

        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":["
                        + "{\"op\":\"replace\",\"path\":\"/statusCode\",\"value\":\"400\"},"
                        + "{\"op\":\"add\",\"path\":\"/responseParameters/gatewayresponse.header.X-Trace\",\"value\":\"context.requestId\"},"
                        + "{\"op\":\"remove\",\"path\":\"/responseParameters/gatewayresponse.header.Access-Control-Allow-Headers\"},"
                        + "{\"op\":\"replace\",\"path\":\"/responseTemplates/application~1json\",\"value\":\"{}\"}"
                        + "]}")
                .when().patch("/restapis/" + apiId + "/gatewayresponses/DEFAULT_4XX")
                .then().statusCode(200)
                .body("statusCode", equalTo("400"))
                .body("responseParameters", equalTo(Map.of(
                        "gatewayresponse.header.Access-Control-Allow-Origin", "'*'",
                        "gatewayresponse.header.X-Trace", "context.requestId")))
                .body("responseTemplates", equalTo(Map.of("application/json", "{}")));

        given()
                .when().get("/restapis/" + apiId + "/gatewayresponses/DEFAULT_4XX")
                .then().statusCode(200)
                .body("statusCode", equalTo("400"))
                .body("responseParameters.'gatewayresponse.header.X-Trace'", equalTo("context.requestId"));

        given()
                .when().delete("/restapis/" + apiId + "/gatewayresponses/DEFAULT_4XX")
                .then().statusCode(202);

        // Deleting restores the default rather than removing the type.
        given()
                .when().get("/restapis/" + apiId + "/gatewayresponses/DEFAULT_4XX")
                .then().statusCode(200)
                .body("defaultResponse", equalTo(true))
                .body("responseParameters", equalTo(Map.of()));

        given()
                .when().delete("/restapis/" + apiId + "/gatewayresponses/DEFAULT_4XX")
                .then().statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void patchingAnUncustomisedTypeStartsFromItsDefault() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"add\","
                        + "\"path\":\"/responseParameters/gatewayresponse.header.Access-Control-Allow-Origin\","
                        + "\"value\":\"'*'\"}]}")
                .when().patch("/restapis/" + apiId + "/gatewayresponses/UNAUTHORIZED")
                .then().statusCode(200)
                .body("statusCode", equalTo("401"))
                .body("defaultResponse", equalTo(false))
                .body("responseParameters.'gatewayresponse.header.Access-Control-Allow-Origin'", equalTo("'*'"))
                .body("responseTemplates.'application/json'", equalTo(DEFAULT_TEMPLATE));
    }

    @Test
    void invalidInputIsRejected() {
        given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when().put("/restapis/" + apiId + "/gatewayresponses/NOT_A_TYPE")
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));

        given()
                .when().get("/restapis/" + apiId + "/gatewayresponses/NOT_A_TYPE")
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));

        given()
                .contentType(ContentType.JSON)
                .body("{\"statusCode\":\"abc\"}")
                .when().put("/restapis/" + apiId + "/gatewayresponses/DEFAULT_5XX")
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));

        given()
                .contentType(ContentType.JSON)
                .body("{\"responseParameters\":{\"method.response.header.X\":\"'*'\"}}")
                .when().put("/restapis/" + apiId + "/gatewayresponses/DEFAULT_5XX")
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));

        given()
                .contentType(ContentType.JSON)
                .body("{\"responseParameters\":{\"gatewayresponse.header.X\":\"integration.response.header.X\"}}")
                .when().put("/restapis/" + apiId + "/gatewayresponses/DEFAULT_5XX")
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));

        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/responseType\",\"value\":\"X\"}]}")
                .when().patch("/restapis/" + apiId + "/gatewayresponses/DEFAULT_5XX")
                .then().statusCode(400)
                .body("__type", equalTo("BadRequestException"));

        given()
                .when().get("/restapis/does-not-exist/gatewayresponses")
                .then().statusCode(404)
                .body("__type", equalTo("NotFoundException"));
    }

    @Test
    void openApiImportCreatesTheGatewayResponses() {
        String spec = """
            {
              "openapi": "3.0.1",
              "info": {"title": "gateway-responses-import", "version": "1"},
              "paths": {
                "/ping": {
                  "get": {
                    "responses": {"200": {"description": "ok"}},
                    "x-amazon-apigateway-integration": {
                      "type": "mock",
                      "requestTemplates": {"application/json": "{\\"statusCode\\": 200}"},
                      "responses": {"default": {"statusCode": "200"}}
                    }
                  }
                }
              },
              "x-amazon-apigateway-gateway-responses": {
                "DEFAULT_4XX": {
                  "responseParameters": {
                    "gatewayresponse.header.Access-Control-Allow-Origin": "'*'"
                  }
                },
                "UNAUTHORIZED": {
                  "statusCode": 403,
                  "responseTemplates": {"application/json": "{\\"nope\\": true}"}
                }
              }
            }
            """;

        String importedApiId = given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec)
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");
        try {
            given()
                    .when().get("/restapis/" + importedApiId + "/gatewayresponses/DEFAULT_4XX")
                    .then().statusCode(200)
                    .body("defaultResponse", equalTo(false))
                    .body("responseParameters.'gatewayresponse.header.Access-Control-Allow-Origin'", equalTo("'*'"));

            given()
                    .when().get("/restapis/" + importedApiId + "/gatewayresponses/UNAUTHORIZED")
                    .then().statusCode(200)
                    .body("defaultResponse", equalTo(false))
                    .body("statusCode", equalTo("403"))
                    .body("responseTemplates.'application/json'", equalTo("{\"nope\": true}"));

            // PutRestApi rebuilds the API from the new spec, dropping customisations it no longer declares.
            given()
                    .contentType(ContentType.JSON)
                    .queryParam("mode", "overwrite")
                    .body(spec.replace("\"UNAUTHORIZED\"", "\"ACCESS_DENIED\""))
                    .when().put("/restapis/" + importedApiId)
                    .then().statusCode(200);

            given()
                    .when().get("/restapis/" + importedApiId + "/gatewayresponses/UNAUTHORIZED")
                    .then().statusCode(200)
                    .body("defaultResponse", equalTo(true));
            given()
                    .when().get("/restapis/" + importedApiId + "/gatewayresponses/ACCESS_DENIED")
                    .then().statusCode(200)
                    .body("defaultResponse", equalTo(false))
                    .body("statusCode", equalTo("403"));
        } finally {
            given().when().delete("/restapis/" + importedApiId);
        }
    }

    // ──────────────────────────── execute plane ────────────────────────────

    @Test
    void anUncustomisedApiAnswersExactlyAsBefore() {
        deployMockApi();

        given()
                .when().get("/execute-api/" + apiId + "/api/missing")
                .then().statusCode(403)
                .contentType(ContentType.JSON)
                .header("x-amzn-ErrorType", equalTo("MissingAuthenticationTokenException"))
                .body("message", equalTo("Missing Authentication Token"))
                .header("Access-Control-Allow-Origin", nullValue());
    }

    @Test
    void default4xxPutsCorsHeadersOnAnUnmatchedRoute() {
        deployMockApi();
        putGatewayResponse("DEFAULT_4XX", "{\"responseParameters\":{"
                + "\"gatewayresponse.header.Access-Control-Allow-Origin\":\"'*'\","
                + "\"gatewayresponse.header.Access-Control-Allow-Headers\":\"'Content-Type,Authorization'\"}}");

        given()
                .when().get("/execute-api/" + apiId + "/api/missing")
                .then().statusCode(403)
                .header("Access-Control-Allow-Origin", equalTo("*"))
                .header("Access-Control-Allow-Headers", equalTo("Content-Type,Authorization"))
                .header("x-amzn-ErrorType", equalTo("MissingAuthenticationTokenException"))
                .body("message", equalTo("Missing Authentication Token"));

        // A path whose resource declares no usable method is the same gateway-generated answer.
        given()
                .when().delete("/execute-api/" + apiId + "/api/ping")
                .then().statusCode(403)
                .header("Access-Control-Allow-Origin", equalTo("*"))
                .body("message", equalTo("Missing Authentication Token"));

        // The integration's own answer is untouched.
        given()
                .when().get("/execute-api/" + apiId + "/api/ping")
                .then().statusCode(200)
                .header("Access-Control-Allow-Origin", nullValue());
    }

    @Test
    void theSpecificTypeWinsOverDefault4xxAndCanOverrideTheStatus() {
        deployMockApi();
        putGatewayResponse("DEFAULT_4XX", "{\"responseParameters\":{"
                + "\"gatewayresponse.header.Access-Control-Allow-Origin\":\"'*'\"}}");
        putGatewayResponse("MISSING_AUTHENTICATION_TOKEN", "{\"statusCode\":\"404\","
                + "\"responseParameters\":{\"gatewayresponse.header.X-Origin\":\"method.request.header.Origin\","
                + "\"gatewayresponse.header.X-Stage\":\"context.stage\","
                + "\"gatewayresponse.header.X-Type\":\"context.error.responseType\","
                + "\"gatewayresponse.header.X-Region\":\"stageVariables.region\","
                + "\"gatewayresponse.header.X-Query\":\"method.request.querystring.q\"},"
                + "\"responseTemplates\":{\"application/json\":"
                + "\"{\\\"error\\\":$context.error.messageString,\\\"path\\\":\\\"$context.path\\\"}\"}}");

        given()
                .header("Origin", "https://app.example")
                .queryParam("q", "1")
                .when().get("/restapis/" + apiId + "/api/_user_request_/missing")
                .then().statusCode(404)
                .header("X-Origin", equalTo("https://app.example"))
                .header("X-Stage", equalTo("api"))
                .header("X-Type", equalTo("MISSING_AUTHENTICATION_TOKEN"))
                .header("X-Region", equalTo("eu-west-1"))
                .header("X-Query", equalTo("1"))
                .header("Access-Control-Allow-Origin", nullValue())
                .body("error", equalTo("Missing Authentication Token"))
                .body("path", equalTo("/api/missing"));
    }

    @Test
    void requestValidationFailuresRenderBadRequestParameters() {
        deployMockApi();
        putGatewayResponse("BAD_REQUEST_PARAMETERS", "{\"responseTemplates\":{\"application/json\":"
                + "\"{\\\"type\\\":\\\"$context.error.responseType\\\",\\\"message\\\":$context.error.messageString}\"},"
                + "\"responseParameters\":{\"gatewayresponse.header.Access-Control-Allow-Origin\":\"'*'\"}}");

        given()
                .when().get("/execute-api/" + apiId + "/api/validated")
                .then().statusCode(400)
                .header("Access-Control-Allow-Origin", equalTo("*"))
                .body("type", equalTo("BAD_REQUEST_PARAMETERS"))
                .body("message", equalTo("Missing required request parameter in QUERY_STRING: 'q'"));

        given()
                .queryParam("q", "x")
                .when().get("/execute-api/" + apiId + "/api/validated")
                .then().statusCode(200);
    }

    @Test
    void aMissingApiKeyRendersInvalidApiKey() {
        deployMockApi();
        putGatewayResponse("DEFAULT_4XX", "{\"responseParameters\":{"
                + "\"gatewayresponse.header.Access-Control-Allow-Origin\":\"'*'\"}}");
        putGatewayResponse("INVALID_API_KEY", "{\"statusCode\":\"401\",\"responseTemplates\":{\"application/json\":"
                + "\"{\\\"type\\\":\\\"$context.error.responseType\\\",\\\"message\\\":$context.error.messageString}\"}}");

        given()
                .when().get("/execute-api/" + apiId + "/api/keyed")
                .then().statusCode(401)
                .header("Access-Control-Allow-Origin", nullValue())
                .body("type", equalTo("INVALID_API_KEY"))
                .body("message", equalTo("Forbidden"));
    }

    @Test
    void expiredAndInvalidSignaturesSelectTheirOwnTypes() throws Exception {
        deployMockApi();
        putGatewayResponse("INVALID_SIGNATURE", "{\"statusCode\":\"401\","
                + "\"responseParameters\":{\"gatewayresponse.header.X-Type\":\"context.error.responseType\"},"
                + "\"responseTemplates\":{\"application/json\":\"{\\\"reason\\\":\\\"invalid\\\"}\"}}");
        putGatewayResponse("EXPIRED_TOKEN", "{\"statusCode\":\"419\","
                + "\"responseParameters\":{\"gatewayresponse.header.X-Type\":\"context.error.responseType\"},"
                + "\"responseTemplates\":{\"application/json\":\"{\\\"reason\\\":\\\"expired\\\"}\"}}");
        String path = "/restapis/" + apiId + "/api/_user_request_/iam";

        // A signature outside the accepted window is EXPIRED_TOKEN, not INVALID_SIGNATURE.
        given()
                .headers(signedHeaders(path, Instant.now().minus(2, ChronoUnit.HOURS)))
                .when().get(path)
                .then().statusCode(419)
                .header("x-amzn-ErrorType", equalTo("InvalidSignatureException"))
                .header("X-Type", equalTo("EXPIRED_TOKEN"))
                .body("reason", equalTo("expired"));

        // A signature computed for another path is a mismatch: INVALID_SIGNATURE.
        given()
                .headers(signedHeaders("/restapis/" + apiId + "/api/_user_request_/ping", Instant.now()))
                .when().get(path)
                .then().statusCode(401)
                .header("X-Type", equalTo("INVALID_SIGNATURE"))
                .body("reason", equalTo("invalid"));

        given()
                .headers(signedHeaders(path, Instant.now()))
                .when().get(path)
                .then().statusCode(200);
    }

    @Test
    void theAcceptHeaderSelectsTheTemplate() {
        deployMockApi();
        putGatewayResponse("DEFAULT_4XX", "{\"responseTemplates\":{"
                + "\"application/json\":\"{\\\"message\\\":$context.error.messageString}\","
                + "\"text/plain\":\"$context.error.message\"}}");

        given()
                .accept("text/plain")
                .when().get("/execute-api/" + apiId + "/api/missing")
                .then().statusCode(403)
                .contentType("text/plain")
                .body(equalTo("Missing Authentication Token"));

        given()
                .when().get("/execute-api/" + apiId + "/api/missing")
                .then().statusCode(403)
                .contentType(ContentType.JSON)
                .body("message", equalTo("Missing Authentication Token"));
    }

    @Test
    void apiConfigurationErrorsUseDefault5xx() {
        deployMockApi();
        putGatewayResponse("DEFAULT_5XX", "{\"responseParameters\":{"
                + "\"gatewayresponse.header.Access-Control-Allow-Origin\":\"'*'\"}}");

        // A method whose MOCK request template does not render is a configuration error.
        given()
                .when().get("/execute-api/" + apiId + "/api/broken")
                .then().statusCode(500)
                .header("Access-Control-Allow-Origin", equalTo("*"))
                .body("message", equalTo("Internal server error"));
    }

    // ──────────────────────────── fixtures ────────────────────────────

    private void putGatewayResponse(String responseType, String body) {
        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/restapis/" + apiId + "/gatewayresponses/" + responseType)
                .then().statusCode(201);
    }

    /**
     * Stage {@code api} with five MOCK GETs: {@code /ping} answers 200, {@code /validated}
     * requires the {@code q} query parameter, {@code /broken} has a request template that does
     * not parse, {@code /keyed} requires an API key and {@code /iam} requires a SigV4 signature.
     */
    private void deployMockApi() {
        String rootId = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200)
                .extract().path("item[0].id");

        String validatorId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"params\",\"validateRequestParameters\":true}")
                .when().post("/restapis/" + apiId + "/requestvalidators")
                .then().statusCode(201)
                .extract().path("id");

        mockGet(rootId, "ping", "{\"authorizationType\":\"NONE\"}", "{\"statusCode\": 200}");
        mockGet(rootId, "validated", "{\"authorizationType\":\"NONE\",\"requestValidatorId\":\"" + validatorId
                + "\",\"requestParameters\":{\"method.request.querystring.q\":true}}", "{\"statusCode\": 200}");
        mockGet(rootId, "broken", "{\"authorizationType\":\"NONE\"}", "#if(");
        mockGet(rootId, "keyed", "{\"authorizationType\":\"NONE\",\"apiKeyRequired\":true}", "{\"statusCode\": 200}");
        mockGet(rootId, "iam", "{\"authorizationType\":\"AWS_IAM\"}", "{\"statusCode\": 200}");

        String deploymentId = given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"v1\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201)
                .extract().path("id");
        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"api\",\"deploymentId\":\"" + deploymentId
                        + "\",\"variables\":{\"region\":\"eu-west-1\"}}")
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);
    }

    private static Map<String, String> signedHeaders(String path, Instant signedAt) throws Exception {
        return ExecuteApiRequestSigner.signedHeaders("GET", path, Map.of(), "localhost:" + RestAssured.port,
                null, "test", "test", "us-east-1", signedAt);
    }

    private void mockGet(String rootId, String pathPart, String methodBody, String requestTemplate) {
        String resourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"" + pathPart + "\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201)
                .extract().path("id");
        String method = "/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET";
        given().contentType(ContentType.JSON).body(methodBody)
                .when().put(method).then().statusCode(201);
        given().contentType(ContentType.JSON).body("{\"responseParameters\":{}}")
                .when().put(method + "/responses/200").then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\""
                        + requestTemplate.replace("\"", "\\\"") + "\"}}")
                .when().put(method + "/integration").then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"{}\"}}")
                .when().put(method + "/integration/responses/200").then().statusCode(201);
    }
}
