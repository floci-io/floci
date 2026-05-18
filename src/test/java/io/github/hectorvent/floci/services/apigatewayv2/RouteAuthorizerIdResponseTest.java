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
