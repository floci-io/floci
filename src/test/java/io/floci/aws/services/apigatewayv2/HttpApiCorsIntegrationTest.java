package io.floci.aws.services.apigatewayv2;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * CORS on the actual responses of an HTTP API, not only on its preflights. AWS documents that once
 * an HTTP API has a CORS configuration, API Gateway adds the configured CORS headers to the
 * integration's response for a request carrying an allowed {@code Origin}, and ignores the CORS
 * headers the integration returns. Before the fix the configuration answered the preflight only,
 * so a browser refused to hand the page any response of the API.
 *
 * <p>The backend sets its own CORS headers on every response, so each case also shows them gone.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpApiCorsIntegrationTest {

    private static final String APP_ORIGIN = "https://app.localhost.floci.io";
    private static final String DOMAIN = "cors-probe.localhost.floci.io";
    private static final String BACKEND_BODY = "{\"from\":\"backend\"}";
    private static final String THROTTLED_FUNCTION = "http-api-cors-throttled";

    private static HttpServer backend;
    private static int backendPort;
    private static String corsApiId;
    private static String plainApiId;

    @BeforeAll
    static void startBackend() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", HttpApiCorsIntegrationTest::handle);
        backend.start();
        backendPort = backend.getAddress().getPort();
    }

    @AfterAll
    static void stopBackend() {
        if (backend != null) {
            backend.stop(0);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        byte[] response = BACKEND_BODY.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("X-Request-Id", "backend-request");
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "https://backend.example.com");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "PUT");
        exchange.getResponseHeaders().add("Access-Control-Expose-Headers", "x-backend");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    @Test
    @Order(1)
    void createApis() {
        corsApiId = HttpProxyApiFixtures.createProxyApi("http-api-cors", """
                ,"corsConfiguration":{
                  "allowOrigins":["%s"],
                  "allowMethods":["GET","POST","DELETE"],
                  "allowHeaders":["authorization","content-type"],
                  "exposeHeaders":["x-request-id"],
                  "maxAge":3600,
                  "allowCredentials":true
                }""".formatted(APP_ORIGIN), "$default", backendPort, "/hello");
        plainApiId = HttpProxyApiFixtures.createProxyApi("http-api-no-cors", "", "$default", backendPort, "/hello");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"domainName":"%s","domainNameConfigurations":[
                            {"certificateArn":"arn:aws:acm:us-east-1:000000000000:certificate/abc",
                             "endpointType":"REGIONAL","securityPolicy":"TLS_1_2"}]}
                        """.formatted(DOMAIN))
                .when().post("/v2/domainnames")
                .then().statusCode(201);
        given()
                .contentType(ContentType.JSON)
                .body("{\"apiId\":\"" + corsApiId + "\",\"stage\":\"$default\"}")
                .when().post("/v2/domainnames/" + DOMAIN + "/apimappings")
                .then().statusCode(201);
    }

    @Test
    @Order(2)
    void anAllowedOriginGetsTheConfiguredHeadersOnTheActualResponse() {
        given()
                .header("Origin", APP_ORIGIN)
                .when().get("/execute-api/" + corsApiId + "/$default/hello")
                .then()
                .statusCode(200)
                .body(equalTo(BACKEND_BODY))
                .header("X-Request-Id", equalTo("backend-request"))
                .header("Access-Control-Allow-Origin", equalTo(APP_ORIGIN))
                .header("Access-Control-Allow-Credentials", equalTo("true"))
                .header("Access-Control-Expose-Headers", equalTo("x-request-id"))
                .header("Vary", nullValue())
                .header("Access-Control-Allow-Methods", nullValue());
    }

    @Test
    @Order(3)
    void theSameHeadersArriveThroughTheCustomDomain() {
        given()
                .header("Host", DOMAIN)
                .header("Origin", APP_ORIGIN)
                .when().get("/hello")
                .then()
                .statusCode(200)
                .body(equalTo(BACKEND_BODY))
                .header("Access-Control-Allow-Origin", equalTo(APP_ORIGIN))
                .header("Access-Control-Allow-Credentials", equalTo("true"));
    }

    @Test
    @Order(4)
    void aDisallowedOriginGetsNoCorsHeaders() {
        given()
                .header("Origin", "https://evil.example")
                .when().get("/execute-api/" + corsApiId + "/$default/hello")
                .then()
                .statusCode(200)
                .body(equalTo(BACKEND_BODY))
                .header("Access-Control-Allow-Origin", nullValue())
                .header("Access-Control-Allow-Credentials", nullValue())
                .header("Access-Control-Expose-Headers", nullValue())
                .header("Access-Control-Allow-Methods", nullValue());
    }

    @Test
    @Order(5)
    void aRequestWithoutAnOriginGetsNoCorsHeaders() {
        given()
                .when().get("/execute-api/" + corsApiId + "/$default/hello")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", nullValue())
                .header("Access-Control-Expose-Headers", nullValue());
    }

    @Test
    @Order(6)
    void aPreflightNoRouteMatchesIsAnsweredFromTheConfiguration() {
        given()
                .header("Origin", APP_ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .when().options("/execute-api/" + corsApiId + "/$default/hello")
                .then()
                .statusCode(204)
                .header("Access-Control-Allow-Origin", equalTo(APP_ORIGIN))
                .header("Access-Control-Allow-Methods", equalTo("GET,POST,DELETE"))
                .header("Access-Control-Allow-Headers", equalTo("authorization,content-type"))
                .header("Access-Control-Allow-Credentials", equalTo("true"))
                .header("Access-Control-Max-Age", equalTo("3600"))
                .header("Access-Control-Expose-Headers", nullValue())
                .header("Vary", nullValue());
        // Measured: an OPTIONS request no route matches is answered 204 even when it is not a
        // preflight, with no CORS header.
        given()
                .when().options("/execute-api/" + corsApiId + "/$default/hello")
                .then()
                .statusCode(204)
                .header("Access-Control-Allow-Origin", nullValue());
        given()
                .header("Origin", APP_ORIGIN)
                .when().options("/execute-api/" + corsApiId + "/$default/hello")
                .then()
                .statusCode(204)
                .header("Access-Control-Allow-Origin", nullValue());
    }

    /**
     * API Gateway's own refusals carry the CORS headers too: on AWS a JWT authorizer's 401, an
     * unsigned call to an AWS_IAM route's 403 and a Lambda authorizer's 401 and 403 all answer an
     * allowed Origin with Access-Control-Allow-Origin, so a browser can read the status.
     */
    @Test
    @Order(8)
    void anAuthorizersRefusalCarriesTheCorsHeaders() {
        String integrationId = given()
                .contentType(ContentType.JSON)
                .body("{\"integrationType\":\"HTTP_PROXY\",\"integrationUri\":\"http://127.0.0.1:"
                        + backendPort + "/hello\",\"payloadFormatVersion\":\"1.0\"}")
                .when().post("/v2/apis/" + corsApiId + "/integrations")
                .then().statusCode(201)
                .extract().path("integrationId");
        String authorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"jwt","authorizerType":"JWT",
                         "identitySource":["$request.header.Authorization"],
                         "jwtConfiguration":{"audience":["cors"],"issuer":"https://issuer.example.com"}}""")
                .when().post("/v2/apis/" + corsApiId + "/authorizers")
                .then().statusCode(201)
                .extract().path("authorizerId");
        given()
                .contentType(ContentType.JSON)
                .body("{\"routeKey\":\"GET /jwt\",\"authorizationType\":\"JWT\",\"authorizerId\":\""
                        + authorizerId + "\",\"target\":\"integrations/" + integrationId + "\"}")
                .when().post("/v2/apis/" + corsApiId + "/routes")
                .then().statusCode(201);
        given()
                .contentType(ContentType.JSON)
                .body("{\"routeKey\":\"GET /iam\",\"authorizationType\":\"AWS_IAM\","
                        + "\"target\":\"integrations/" + integrationId + "\"}")
                .when().post("/v2/apis/" + corsApiId + "/routes")
                .then().statusCode(201);

        given()
                .header("Origin", APP_ORIGIN)
                .when().get("/execute-api/" + corsApiId + "/$default/jwt")
                .then()
                .statusCode(401)
                .header("Access-Control-Allow-Origin", equalTo(APP_ORIGIN))
                .header("Access-Control-Allow-Credentials", equalTo("true"))
                .header("Vary", nullValue());
        given()
                .header("Origin", APP_ORIGIN)
                .when().get("/execute-api/" + corsApiId + "/$default/iam")
                .then()
                .statusCode(403)
                .header("Access-Control-Allow-Origin", equalTo(APP_ORIGIN));
        given()
                .header("Origin", "https://evil.example")
                .when().get("/execute-api/" + corsApiId + "/$default/jwt")
                .then()
                .statusCode(401)
                .header("Access-Control-Allow-Origin", nullValue());
    }

    /** Without a CORS configuration API Gateway leaves CORS to the integration. */
    @Test
    @Order(7)
    void anApiWithoutCorsConfigurationPassesTheIntegrationsHeadersThrough() {
        given()
                .header("Origin", APP_ORIGIN)
                .when().get("/execute-api/" + plainApiId + "/$default/hello")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", equalTo("https://backend.example.com"))
                .header("Access-Control-Allow-Methods", equalTo("PUT"))
                .header("Access-Control-Expose-Headers", equalTo("x-backend"));
    }

    /**
     * Measured (eu-west-1, 2026-10-16): a preflight a route matches goes through that route, so an
     * ANY route's integration answers it and an authorizer refuses it, and either answer carries the
     * preflight headers.
     */
    @Test
    @Order(9)
    void aPreflightARouteMatchesGoesThroughTheRoute() {
        String integrationId = given()
                .contentType(ContentType.JSON)
                .body("{\"integrationType\":\"HTTP_PROXY\",\"integrationUri\":\"http://127.0.0.1:"
                        + backendPort + "/hello\",\"payloadFormatVersion\":\"1.0\"}")
                .when().post("/v2/apis/" + corsApiId + "/integrations")
                .then().statusCode(201)
                .extract().path("integrationId");
        String authorizerId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"jwt-any","authorizerType":"JWT",
                         "identitySource":["$request.header.Authorization"],
                         "jwtConfiguration":{"audience":["cors"],"issuer":"https://issuer.example.com"}}""")
                .when().post("/v2/apis/" + corsApiId + "/authorizers")
                .then().statusCode(201)
                .extract().path("authorizerId");
        given()
                .contentType(ContentType.JSON)
                .body("{\"routeKey\":\"ANY /open/{proxy+}\",\"target\":\"integrations/" + integrationId + "\"}")
                .when().post("/v2/apis/" + corsApiId + "/routes")
                .then().statusCode(201);
        given()
                .contentType(ContentType.JSON)
                .body("{\"routeKey\":\"ANY /secure/{proxy+}\",\"authorizationType\":\"JWT\",\"authorizerId\":\""
                        + authorizerId + "\",\"target\":\"integrations/" + integrationId + "\"}")
                .when().post("/v2/apis/" + corsApiId + "/routes")
                .then().statusCode(201);

        given()
                .header("Origin", APP_ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .when().options("/execute-api/" + corsApiId + "/$default/open/x")
                .then()
                .statusCode(200)
                .body(equalTo(BACKEND_BODY))
                .header("Access-Control-Allow-Origin", equalTo(APP_ORIGIN))
                .header("Access-Control-Allow-Methods", equalTo("GET,POST,DELETE"))
                .header("Access-Control-Max-Age", equalTo("3600"));
        given()
                .header("Origin", APP_ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .when().options("/execute-api/" + corsApiId + "/$default/secure/x")
                .then()
                .statusCode(401)
                .header("Access-Control-Allow-Origin", equalTo(APP_ORIGIN))
                .header("Access-Control-Allow-Methods", equalTo("GET,POST,DELETE"));
        given()
                .header("Origin", APP_ORIGIN)
                .header("Access-Control-Request-Method", "PATCH")
                .when().options("/execute-api/" + corsApiId + "/$default/open/x")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", nullValue())
                .header("Access-Control-Allow-Methods", nullValue());
    }

    /**
     * Measured: a function that is gone fails the request with API Gateway's 500 and one Lambda
     * throttles with its 503, both carrying the CORS headers, while a route without a target
     * answers as an unmatched one, without them.
     */
    @Test
    @Order(10)
    void apiGatewaysOwnAnswersOnARoute() throws Exception {
        String integrationId = given()
                .contentType(ContentType.JSON)
                .body("{\"integrationType\":\"AWS_PROXY\",\"integrationUri\":"
                        + "\"arn:aws:lambda:us-east-1:000000000000:function:cors-gone\",\"payloadFormatVersion\":\"2.0\"}")
                .when().post("/v2/apis/" + corsApiId + "/integrations")
                .then().statusCode(201)
                .extract().path("integrationId");
        given()
                .contentType(ContentType.JSON)
                .body("{\"routeKey\":\"GET /gone\",\"target\":\"integrations/" + integrationId + "\"}")
                .when().post("/v2/apis/" + corsApiId + "/routes")
                .then().statusCode(201);
        given()
                .contentType(ContentType.JSON)
                .body("{\"routeKey\":\"GET /notarget\"}")
                .when().post("/v2/apis/" + corsApiId + "/routes")
                .then().statusCode(201);
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Handler":"index.handler",
                         "Role":"arn:aws:iam::000000000000:role/lambda-role","Code":{"ZipFile":"%s"}}
                        """.formatted(THROTTLED_FUNCTION,
                                WebSocketTestSupport.createLambdaZip("exports.handler = async () => 'ok';")))
                .when().post("/2015-03-31/functions")
                .then().statusCode(201);
        given()
                .contentType(ContentType.JSON)
                .body("{\"ReservedConcurrentExecutions\":0}")
                .when().put("/2017-10-31/functions/" + THROTTLED_FUNCTION + "/concurrency")
                .then().statusCode(200);
        String throttledIntegrationId = given()
                .contentType(ContentType.JSON)
                .body("{\"integrationType\":\"AWS_PROXY\",\"integrationUri\":"
                        + "\"arn:aws:lambda:us-east-1:000000000000:function:" + THROTTLED_FUNCTION + "\","
                        + "\"payloadFormatVersion\":\"2.0\"}")
                .when().post("/v2/apis/" + corsApiId + "/integrations")
                .then().statusCode(201)
                .extract().path("integrationId");
        given()
                .contentType(ContentType.JSON)
                .body("{\"routeKey\":\"GET /throttled\",\"target\":\"integrations/"
                        + throttledIntegrationId + "\"}")
                .when().post("/v2/apis/" + corsApiId + "/routes")
                .then().statusCode(201);

        given()
                .header("Origin", APP_ORIGIN)
                .when().get("/execute-api/" + corsApiId + "/$default/gone")
                .then()
                .statusCode(500)
                .body(containsString("Internal Server Error"))
                .header("Access-Control-Allow-Origin", equalTo(APP_ORIGIN))
                .header("Access-Control-Expose-Headers", equalTo("x-request-id"));
        given()
                .header("Origin", APP_ORIGIN)
                .when().get("/execute-api/" + corsApiId + "/$default/notarget")
                .then()
                .statusCode(404)
                .body(containsString("Not Found"))
                .header("Access-Control-Allow-Origin", nullValue());
        given()
                .header("Origin", APP_ORIGIN)
                .when().get("/execute-api/" + corsApiId + "/$default/throttled")
                .then()
                .statusCode(503)
                .body(containsString("Service Unavailable"))
                .header("Access-Control-Allow-Origin", equalTo(APP_ORIGIN))
                .header("Access-Control-Expose-Headers", equalTo("x-request-id"));
    }

    @Test
    @Order(20)
    void cleanup() {
        String mappingId = given()
                .when().get("/v2/domainnames/" + DOMAIN + "/apimappings")
                .then().statusCode(200)
                .extract().path("items[0].apiMappingId");
        given().when().delete("/v2/domainnames/" + DOMAIN + "/apimappings/" + mappingId)
                .then().statusCode(204);
        given().when().delete("/v2/domainnames/" + DOMAIN).then().statusCode(204);
        given().when().delete("/v2/apis/" + corsApiId).then().statusCode(204);
        given().when().delete("/v2/apis/" + plainApiId).then().statusCode(204);
        given().when().delete("/2015-03-31/functions/" + THROTTLED_FUNCTION).then().statusCode(204);
    }
}
