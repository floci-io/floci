package io.github.hectorvent.floci.services.apigatewayv2;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

/**
 * Round-trip coverage for {@code AuthorizationScopes} on Route resources over the
 * JSON 1.1 surface (X-Amz-Target AmazonApiGatewayV2.*). The REST surface is covered
 * by {@link RouteAuthorizerIdResponseTest}; this guards the {@code toRouteNode}
 * rendering in {@code ApiGatewayV2JsonHandler}, which is a separate code path.
 */
@QuarkusTest
class RouteAuthorizationScopesJson11Test {

    private static final String AMZ_JSON = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AmazonApiGatewayV2.";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260820/us-east-1/apigatewayv2/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void authorizationScopesRoundTripOverJson11() {
        String apiId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"Name":"scopes-json11-api","ProtocolType":"HTTP"}
                        """)
                .when().post("/")
                .then().statusCode(201)
                .extract().path("ApiId");

        String routeId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateRoute")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","RouteKey":"GET /scoped","AuthorizationType":"JWT","AuthorizationScopes":["orders/read","orders/write"]}
                        """.formatted(apiId))
                .when().post("/")
                .then().statusCode(201)
                .body("AuthorizationScopes", contains("orders/read", "orders/write"))
                .extract().path("RouteId");

        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "GetRoute")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","RouteId":"%s"}
                        """.formatted(apiId, routeId))
                .when().post("/")
                .then().statusCode(200)
                .body("AuthorizationScopes", contains("orders/read", "orders/write"));

        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "UpdateRoute")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","RouteId":"%s","AuthorizationScopes":["orders/admin"]}
                        """.formatted(apiId, routeId))
                .when().post("/")
                .then().statusCode(200)
                .body("AuthorizationScopes", contains("orders/admin"));
    }

    @Test
    void routeWithoutScopesOmitsAuthorizationScopesKey() {
        String apiId = given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateApi")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"Name":"noscopes-json11-api","ProtocolType":"HTTP"}
                        """)
                .when().post("/")
                .then().statusCode(201)
                .extract().path("ApiId");

        given()
                .contentType(AMZ_JSON)
                .header("X-Amz-Target", TARGET_PREFIX + "CreateRoute")
                .header("Authorization", AUTH_HEADER)
                .body("""
                        {"ApiId":"%s","RouteKey":"GET /open","AuthorizationType":"NONE"}
                        """.formatted(apiId))
                .when().post("/")
                .then().statusCode(201)
                .body("RouteKey", equalTo("GET /open"))
                .body("$", not(hasKey("AuthorizationScopes")));
    }
}
