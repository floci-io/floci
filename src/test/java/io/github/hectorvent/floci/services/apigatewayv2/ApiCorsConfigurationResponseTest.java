package io.github.hectorvent.floci.services.apigatewayv2;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * Regression coverage for the HTTP API v2 management API returning {@code corsConfiguration}
 * on Api resources. The value is accepted on Create/Update but currently never emitted on
 * Get/Create/Update responses, causing IaC tools like Terraform's
 * {@code aws_apigatewayv2_api.cors_configuration} to perceive drift on every plan.
 */
@QuarkusTest
class ApiCorsConfigurationResponseTest {

    private static final String AMZ_JSON = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AmazonApiGatewayV2.";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260519/us-east-1/apigatewayv2/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    // ──────────────────────────── REST path ────────────────────────────

    @Test
    void corsConfigurationReturnedFromCreateGetAndList() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name":"cors-create",
                          "protocolType":"HTTP",
                          "corsConfiguration":{
                            "allowOrigins":["https://example.com"],
                            "allowMethods":["GET","POST"],
                            "allowHeaders":["Content-Type","Authorization"],
                            "exposeHeaders":["X-Request-Id"],
                            "maxAge":600,
                            "allowCredentials":true
                          }
                        }
                        """)
                .when().post("/v2/apis")
                .then().statusCode(201)
                .body("corsConfiguration.allowOrigins", contains("https://example.com"))
                .body("corsConfiguration.allowMethods", contains("GET", "POST"))
                .body("corsConfiguration.allowHeaders", contains("Content-Type", "Authorization"))
                .body("corsConfiguration.exposeHeaders", contains("X-Request-Id"))
                .body("corsConfiguration.maxAge", equalTo(600))
                .body("corsConfiguration.allowCredentials", equalTo(true))
                .extract().path("apiId");

        given()
                .when().get("/v2/apis/" + apiId)
                .then().statusCode(200)
                .body("corsConfiguration.allowOrigins", contains("https://example.com"))
                .body("corsConfiguration.maxAge", equalTo(600))
                .body("corsConfiguration.allowCredentials", equalTo(true));

        given()
                .when().get("/v2/apis")
                .then().statusCode(200)
                .body("items.find { it.apiId == '" + apiId + "' }.corsConfiguration.allowOrigins",
                        contains("https://example.com"))
                .body("items.find { it.apiId == '" + apiId + "' }.corsConfiguration.maxAge",
                        equalTo(600));
    }

    @Test
    void corsConfigurationReturnedAfterUpdate() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"cors-update","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then().statusCode(201)
                .body("$", not(hasKey("corsConfiguration")))
                .extract().path("apiId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "corsConfiguration":{
                            "allowOrigins":["*"],
                            "allowMethods":["*"],
                            "maxAge":300
                          }
                        }
                        """)
                .when().patch("/v2/apis/" + apiId)
                .then().statusCode(200)
                .body("corsConfiguration.allowOrigins", contains("*"))
                .body("corsConfiguration.allowMethods", contains("*"))
                .body("corsConfiguration.maxAge", equalTo(300));

        given()
                .when().get("/v2/apis/" + apiId)
                .then().statusCode(200)
                .body("corsConfiguration.allowOrigins", contains("*"))
                .body("corsConfiguration.maxAge", equalTo(300));
    }

    @Test
    void corsConfigurationPreservedWhenUpdatingOtherFields() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "name":"cors-preserve",
                          "protocolType":"HTTP",
                          "corsConfiguration":{
                            "allowOrigins":["https://example.com"],
                            "maxAge":600
                          }
                        }
                        """)
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"description":"updated description"}
                        """)
                .when().patch("/v2/apis/" + apiId)
                .then().statusCode(200)
                .body("description", equalTo("updated description"))
                .body("corsConfiguration.allowOrigins", contains("https://example.com"))
                .body("corsConfiguration.maxAge", equalTo(600));
    }

    @Test
    void corsConfigurationOmittedWhenNull() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"cors-omit","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then().statusCode(201)
                .body("$", not(hasKey("corsConfiguration")))
                .body("corsConfiguration", nullValue());
    }

    // ──────────────────────────── JSON 1.1 path ────────────────────────────

    @Test
    void corsConfigurationReturnedViaJson11() {
        String apiId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {
                          "Name":"cors-json11",
                          "ProtocolType":"HTTP",
                          "CorsConfiguration":{
                            "AllowOrigins":["https://example.com"],
                            "AllowMethods":["GET"],
                            "AllowHeaders":["Content-Type"],
                            "ExposeHeaders":["X-Request-Id"],
                            "MaxAge":600,
                            "AllowCredentials":true
                          }
                        }
                        """)
                .when().post("/")
                .then().statusCode(201)
                .body("CorsConfiguration.AllowOrigins", contains("https://example.com"))
                .body("CorsConfiguration.AllowMethods", contains("GET"))
                .body("CorsConfiguration.MaxAge", equalTo(600))
                .body("CorsConfiguration.AllowCredentials", equalTo(true))
                .extract().path("ApiId");

        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s"}
                        """.formatted(apiId))
                .when().post("/")
                .then().statusCode(200)
                .body("CorsConfiguration.AllowOrigins", contains("https://example.com"))
                .body("CorsConfiguration.MaxAge", equalTo(600));
    }
}
