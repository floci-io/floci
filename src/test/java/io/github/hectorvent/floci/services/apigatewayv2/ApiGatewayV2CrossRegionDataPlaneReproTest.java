package io.github.hectorvent.floci.services.apigatewayv2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Reproduces floci-io/floci#1902.
 *
 * <p>A user creates an API Gateway v2 (HTTP API) in a non-default region
 * (e.g. {@code eu-west-1}, as produced by Terraform/CDK) and then tries to
 * invoke it from a browser/frontend via the data-plane URL
 * {@code /execute-api/{apiId}/$default/{path}} WITHOUT an {@code Authorization}
 * header (unsigned request).
 *
 * <p>The data-plane dispatch resolves the region from the {@code Authorization}
 * header and falls back to the configured default region ({@code us-east-1})
 * when the header is absent. Because the API was created in {@code eu-west-1},
 * the lookup in the default region fails with "Invalid API id specified"
 * (404) — exactly the error reported in the issue.
 *
 * <p>The unsigned data-plane request now resolves the API across all regions,
 * so it reaches the v2 dispatch and is no longer rejected with
 * "Invalid API id specified". The assertions below validate this fixed
 * behaviour.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2CrossRegionDataPlaneReproTest {

    private static final String REGION = "eu-west-1";
    // SigV4-style Authorization carrying the region so the management API
    // stores the API under eu-west-1 (mirrors what the AWS SDK/Terraform sends).
    private static final String AUTH = "AWS4-HMAC-SHA256 "
            + "Credential=AKID/20260717/" + REGION + "/apigateway/aws4_request, "
            + "SignedHeaders=host;x-amz-target, Signature=deadbeef";

    private static String apiId;
    private static String integrationId;
    private static String routeId;
    private static String deploymentId;

    @Test @Order(1)
    void createApiInNonDefaultRegion() {
        apiId = given()
                .header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"repro-http-api","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .body("apiEndpoint", containsString("execute-api." + REGION + ".amazonaws.com"))
                .extract().path("apiId");
    }

    @Test @Order(2)
    void createIntegration() {
        integrationId = given()
                .header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"HTTP_PROXY","integrationUri":"https://example.com","payloadFormatVersion":"2.0"}
                        """)
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");
    }

    @Test @Order(3)
    void createRoute() {
        routeId = given()
                .header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"GET /events","target":"integrations/%s"}
                        """.formatted(integrationId))
                .when().post("/v2/apis/" + apiId + "/routes")
                .then()
                .statusCode(201)
                .extract().path("routeId");
    }

    @Test @Order(4)
    void createDeployment() {
        deploymentId = given()
                .header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {"description":"v1"}
                        """)
                .when().post("/v2/apis/" + apiId + "/deployments")
                .then()
                .statusCode(201)
                .extract().path("deploymentId");
    }

    @Test @Order(5)
    void createDefaultStage() {
        given()
                .header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"$default","deploymentId":"%s","autoDeploy":false}
                        """.formatted(deploymentId))
                .when().post("/v2/apis/" + apiId + "/stages")
                .then()
                .statusCode(201)
                .body("stageName", equalTo("$default"));
    }

    /**
     * Reproduces the user's failing request: an UNSIGNED data-plane call
     * (no Authorization header) to the execute-api URL. The frontend/browser
     * makes exactly this kind of request.
     *
     * <p>Before the fix this returned 404 "Invalid API id specified" because the
     * region fell back to the default (us-east-1) while the API lives in
     * eu-west-1. After the fix the region is resolved across all stored APIs, so
     * the request reaches the v2 dispatch and is no longer rejected with that
     * error. The integration targets example.com, which may be unreachable in
     * the test sandbox (yielding a 502), but the key assertion is that the
     * region-resolution error is gone.
     */
    @Test @Order(6)
    void unsignedDataPlaneRequestResolvesApiAcrossRegions() {
        given()
                .contentType(ContentType.JSON)
                .when().get("/execute-api/" + apiId + "/$default/events")
                .then()
                .statusCode(not(equalTo(404)))
                .body(not(containsString("Invalid API id specified")));
    }

    @Test @Order(99)
    void cleanup() {
        given()
                .header("Authorization", AUTH)
                .contentType(ContentType.JSON)
                .when().delete("/v2/apis/" + apiId)
                .then()
                .statusCode(204);
    }
}