package io.github.hectorvent.floci.services.apigatewayv2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * Regression coverage for the HTTP API v2 management API returning {@code authorizerId}
 * on Route resources. The value is persisted via {@code Route.setAuthorizerId} during
 * create/update and used at runtime (e.g. WebSocketHandler, ApiGatewayExecuteController),
 * so omitting it from the response causes IaC tools like Terraform to perceive drift on
 * every plan.
 */
@QuarkusTest
class RouteAuthorizerIdResponseTest {

    @Test
    void authorizerIdReturnedFromCreateGetAndList() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"authz-route-create","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");

        String authorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "authorizerType":"JWT",
                          "name":"jwt-authz",
                          "identitySource":["$request.header.Authorization"],
                          "jwtConfiguration":{"issuer":"https://example.com","audience":["api"]}
                        }
                        """)
                .when().post("/v2/apis/" + apiId + "/authorizers")
                .then().statusCode(201)
                .extract().path("authorizerId");

        String routeId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "routeKey":"GET /secured",
                          "authorizationType":"JWT",
                          "authorizerId":"%s"
                        }
                        """.formatted(authorizerId))
                .when().post("/v2/apis/" + apiId + "/routes")
                .then().statusCode(201)
                .body("authorizationType", equalTo("JWT"))
                .body("authorizerId", equalTo(authorizerId))
                .extract().path("routeId");

        given()
                .when().get("/v2/apis/" + apiId + "/routes/" + routeId)
                .then().statusCode(200)
                .body("authorizationType", equalTo("JWT"))
                .body("authorizerId", equalTo(authorizerId));

        given()
                .when().get("/v2/apis/" + apiId + "/routes")
                .then().statusCode(200)
                .body("items.authorizerId", hasItem(authorizerId));
    }

    @Test
    void authorizationScopesPersistedAndReturned() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"authz-route-scopes","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");

        String authorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "authorizerType":"JWT",
                          "name":"jwt-authz-scopes",
                          "identitySource":["$request.header.Authorization"],
                          "jwtConfiguration":{"issuer":"https://example.com","audience":["api"]}
                        }
                        """)
                .when().post("/v2/apis/" + apiId + "/authorizers")
                .then().statusCode(201)
                .extract().path("authorizerId");

        String routeId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "routeKey":"GET /scoped",
                          "authorizationType":"JWT",
                          "authorizerId":"%s",
                          "authorizationScopes":["orders/read","orders/write"]
                        }
                        """.formatted(authorizerId))
                .when().post("/v2/apis/" + apiId + "/routes")
                .then().statusCode(201)
                .body("authorizationScopes", equalTo(java.util.List.of("orders/read", "orders/write")))
                .extract().path("routeId");

        given()
                .when().get("/v2/apis/" + apiId + "/routes/" + routeId)
                .then().statusCode(200)
                .body("authorizationScopes", equalTo(java.util.List.of("orders/read", "orders/write")));

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizationScopes":["orders/admin"]}
                        """)
                .when().patch("/v2/apis/" + apiId + "/routes/" + routeId)
                .then().statusCode(200)
                .body("authorizationScopes", equalTo(java.util.List.of("orders/admin")));
    }

    @Test
    void authorizationScopesNonStringElementsNormalizedToStrings() {
        // Route scopes arrive as a JSON array; a non-string element (legal JSON, invalid input)
        // used to survive the unchecked List<String> cast and blow up with ClassCastException
        // when the response was serialized. They are now normalized to strings at ingestion.
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"authz-route-scope-norm","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "routeKey":"GET /norm",
                          "authorizationType":"JWT",
                          "authorizationScopes":["orders/read", 42]
                        }
                        """)
                .when().post("/v2/apis/" + apiId + "/routes")
                .then().statusCode(201)
                .body("authorizationScopes", equalTo(java.util.List.of("orders/read", "42")));
    }

    @Test
    void nonArrayAuthorizationScopesRejectedWithoutClearingEnforcement() {
        // A present-but-non-array value must 400, not be treated as absent: silently
        // mapping it to null would create the route without scope enforcement, or
        // clear the scopes of an existing route on update.
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"authz-route-scope-badreq","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "routeKey":"GET /bad",
                          "authorizationType":"JWT",
                          "authorizationScopes":"orders/read"
                        }
                        """)
                .when().post("/v2/apis/" + apiId + "/routes")
                .then().statusCode(400);

        String routeId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "routeKey":"GET /guarded",
                          "authorizationType":"JWT",
                          "authorizationScopes":["orders/read"]
                        }
                        """)
                .when().post("/v2/apis/" + apiId + "/routes")
                .then().statusCode(201)
                .extract().path("routeId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizationScopes":"orders/write"}
                        """)
                .when().patch("/v2/apis/" + apiId + "/routes/" + routeId)
                .then().statusCode(400);

        given()
                .when().get("/v2/apis/" + apiId + "/routes/" + routeId)
                .then().statusCode(200)
                .body("authorizationScopes", equalTo(java.util.List.of("orders/read")));
    }

    @Test
    void authorizerIdReturnedAfterUpdate() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"authz-route-update","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");

        String authorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "authorizerType":"JWT",
                          "name":"jwt-authz-2",
                          "identitySource":["$request.header.Authorization"],
                          "jwtConfiguration":{"issuer":"https://example.com","audience":["api"]}
                        }
                        """)
                .when().post("/v2/apis/" + apiId + "/authorizers")
                .then().statusCode(201)
                .extract().path("authorizerId");

        String routeId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"GET /open","authorizationType":"NONE"}
                        """)
                .when().post("/v2/apis/" + apiId + "/routes")
                .then().statusCode(201)
                .extract().path("routeId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"authorizationType":"JWT","authorizerId":"%s"}
                        """.formatted(authorizerId))
                .when().patch("/v2/apis/" + apiId + "/routes/" + routeId)
                .then().statusCode(200)
                .body("authorizationType", equalTo("JWT"))
                .body("authorizerId", equalTo(authorizerId));

        given()
                .when().get("/v2/apis/" + apiId + "/routes/" + routeId)
                .then().statusCode(200)
                .body("authorizerId", equalTo(authorizerId));
    }
}
