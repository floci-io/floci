package io.github.hectorvent.floci.services.apigatewayv2;

import io.restassured.http.ContentType;

import static io.restassured.RestAssured.given;

/** A deployed HTTP API whose one route is an {@code HTTP_PROXY} integration on a test's fixture backend. */
public final class HttpProxyApiFixtures {

    private HttpProxyApiFixtures() {
    }

    /**
     * Creates an HTTP API, {@code apiFields} appended to its CreateApi body, with the auto-deployed
     * stage {@code stageName} and a {@code GET path} route to an {@code HTTP_PROXY} integration on
     * {@code http://127.0.0.1:backendPort path}; returns its id.
     */
    public static String createProxyApi(String name, String apiFields, String stageName, int backendPort, String path) {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\",\"protocolType\":\"HTTP\"" + apiFields + "}")
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");
        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"" + stageName + "\",\"autoDeploy\":true}")
                .when().post("/v2/apis/" + apiId + "/stages")
                .then().statusCode(201);
        String integrationId = given()
                .contentType(ContentType.JSON)
                .body("{\"integrationType\":\"HTTP_PROXY\",\"integrationUri\":\"http://127.0.0.1:"
                        + backendPort + path + "\",\"payloadFormatVersion\":\"1.0\"}")
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then().statusCode(201)
                .extract().path("integrationId");
        given()
                .contentType(ContentType.JSON)
                .body("{\"routeKey\":\"GET " + path + "\",\"target\":\"integrations/" + integrationId + "\"}")
                .when().post("/v2/apis/" + apiId + "/routes")
                .then().statusCode(201);
        return apiId;
    }
}
