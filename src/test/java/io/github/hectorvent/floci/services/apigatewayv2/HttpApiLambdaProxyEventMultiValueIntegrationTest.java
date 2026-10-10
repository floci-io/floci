package io.github.hectorvent.floci.services.apigatewayv2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * End to end checks of the events Lambda proxy integrations receive. Format 2.0 combines repeated
 * values and moves cookies into a {@code cookies} array, while format 1.0 keeps multi-value maps.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpApiLambdaProxyEventMultiValueIntegrationTest {

    private static final String FUNCTION_NAME = "httpv2-multi-value-event-fn";

    private static String apiId;

    @Test
    @Order(1)
    void setupLambdaAndHttpApi() throws Exception {
        String zip = WebSocketTestSupport.createLambdaZip("""
                exports.handler = async (event) => ({
                    statusCode: 200,
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(event)
                });
                """);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(FUNCTION_NAME, zip))
                .when().post("/2015-03-31/functions")
                .then().statusCode(201);

        apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"multi-value-event-test","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .extract().path("apiId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"test","stageVariables":{"environment":"review"}}
                        """)
                .when().post("/v2/apis/" + apiId + "/stages")
                .then().statusCode(201);

        String integrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST","payloadFormatVersion":"2.0"}
                        """.formatted(FUNCTION_NAME))
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"GET /echo","target":"integrations/%s"}
                        """.formatted(integrationId))
                .when().post("/v2/apis/" + apiId + "/routes")
                .then().statusCode(201);

        String v1IntegrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST","payloadFormatVersion":"1.0"}
                        """.formatted(FUNCTION_NAME))
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"GET /echo-v1","target":"integrations/%s"}
                        """.formatted(v1IntegrationId))
                .when().post("/v2/apis/" + apiId + "/routes")
                .then().statusCode(201);
    }

    @Test
    @Order(10)
    void repeatedQueryKeysAreCommaJoined() {
        given()
                .queryParam("q", "1", "2")
                .queryParam("single", "x")
                .when().get("/execute-api/" + apiId + "/test/echo")
                .then()
                .statusCode(200)
                .body("rawQueryString", equalTo("q=1&q=2&single=x"))
                .body("queryStringParameters.q", equalTo("1,2"))
                .body("queryStringParameters.single", equalTo("x"));
    }

    @Test
    @Order(11)
    void repeatedHeadersAreCommaJoined() {
        given()
                .header("X-Trace", "first")
                .header("X-Trace", "second")
                .when().get("/execute-api/" + apiId + "/test/echo")
                .then()
                .statusCode(200)
                .body("headers.'x-trace'", equalTo("first,second"));
    }

    @Test
    @Order(12)
    void cookieHeaderIsDeliveredAsCookiesArray() {
        given()
                .header("Cookie", "a=1; b=2")
                .when().get("/execute-api/" + apiId + "/test/echo")
                .then()
                .statusCode(200)
                .body("cookies", contains("a=1", "b=2"));
    }

    @Test
    @Order(13)
    void requestWithoutCookiesHasNoCookiesField() {
        given()
                .when().get("/execute-api/" + apiId + "/test/echo")
                .then()
                .statusCode(200)
                .body("cookies", nullValue());
    }

    @Test
    @Order(14)
    void payloadFormatOneUsesV1Event() {
        given()
                .queryParam("q", "1", "2")
                .when().get("/execute-api/" + apiId + "/test/echo-v1")
                .then()
                .statusCode(200)
                .body("version", equalTo("1.0"))
                .body("resource", equalTo("/echo-v1"))
                .body("path", equalTo("/echo-v1"))
                .body("httpMethod", equalTo("GET"))
                .body("rawPath", nullValue())
                .body("stageVariables.environment", equalTo("review"))
                .body("multiValueHeaders", notNullValue())
                .body("multiValueQueryStringParameters.q", contains("1", "2"));
    }

    @Test
    @Order(999)
    void cleanup() throws Exception {
        if (apiId != null) {
            given().when().delete("/v2/apis/" + apiId);
        }
        WebSocketTestSupport.deleteFunctions(FUNCTION_NAME);
    }
}
