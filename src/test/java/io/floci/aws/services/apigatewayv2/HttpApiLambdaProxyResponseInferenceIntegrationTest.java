package io.floci.aws.services.apigatewayv2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * End to end check of how an HTTP API answers a Lambda proxy integration whose function returns
 * no {@code statusCode} or throws. AWS infers a 200 JSON response whose body is the function's
 * result, and answers a function error with {@code {"message":"Internal Server Error"}}. An
 * integration configured with format 1.0 keeps the 1.0 response rules.
 *
 * <p>A result that carries a {@code statusCode} and a {@code body} and leaves the
 * {@code Content-Type} header out is answered {@code text/plain; charset=utf-8}, in both payload
 * formats (measured on AWS, eu-west-1, 2026-10-19). A result that names a {@code Content-Type}
 * keeps it.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpApiLambdaProxyResponseInferenceIntegrationTest {

    private static final String BARE_FUNCTION = "httpv2-inference-bare-fn";
    private static final String STRING_FUNCTION = "httpv2-inference-string-fn";
    private static final String THROW_FUNCTION = "httpv2-inference-throw-fn";
    private static final String V1_FUNCTION = "httpv2-inference-v1-fn";
    private static final String PLAIN_FUNCTION = "httpv2-inference-plain-fn";
    private static final String PLAIN_V1_FUNCTION = "httpv2-inference-plain-v1-fn";
    private static final String JSON_FUNCTION = "httpv2-inference-json-fn";

    private static String apiId;

    @Test
    @Order(1)
    void setupLambdasAndHttpApi() throws Exception {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"response-inference-test","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .extract().path("apiId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"test"}
                        """)
                .when().post("/v2/apis/" + apiId + "/stages")
                .then().statusCode(201);

        createProxyRoute(BARE_FUNCTION, "/bare",
                "exports.handler = async () => ({ message: 'Hello from Lambda!' });");
        createProxyRoute(STRING_FUNCTION, "/string",
                "exports.handler = async () => 'Hello from Lambda!';");
        createProxyRoute(THROW_FUNCTION, "/throw",
                "exports.handler = async () => { throw new Error('boom'); };");
        createProxyRoute(V1_FUNCTION, "/v1", "1.0",
                "exports.handler = async () => ({ headers: { 'X-Trace': 'value' }, body: 'inner' });");
        createProxyRoute(PLAIN_FUNCTION, "/plain", "2.0",
                "exports.handler = async () => ({ statusCode: 200, body: 'hello' });");
        createProxyRoute(PLAIN_V1_FUNCTION, "/plain-v1", "1.0",
                "exports.handler = async () => ({ statusCode: 200, body: 'hello' });");
        createProxyRoute(JSON_FUNCTION, "/json", "2.0",
                "exports.handler = async () => ({ statusCode: 200, headers: { 'Content-Type': 'application/json' }, body: '{\"a\":1}' });");
    }

    private static void createProxyRoute(String functionName, String path, String handlerCode) throws Exception {
        createProxyRoute(functionName, path, "2.0", handlerCode);
    }

    private static void createProxyRoute(String functionName, String path, String payloadFormatVersion,
                                         String handlerCode) throws Exception {
        String zip = WebSocketTestSupport.createLambdaZip(handlerCode);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(functionName, zip))
                .when().post("/2015-03-31/functions")
                .then().statusCode(201);

        String integrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST","payloadFormatVersion":"%s"}
                        """.formatted(functionName, payloadFormatVersion))
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"GET %s","target":"integrations/%s"}
                        """.formatted(path, integrationId))
                .when().post("/v2/apis/" + apiId + "/routes")
                .then().statusCode(201);
    }

    @Test
    @Order(10)
    void objectWithoutStatusCodeIsReturnedAsJsonBody() {
        given()
                .when().get("/execute-api/" + apiId + "/test/bare")
                .then()
                .statusCode(200)
                .contentType(startsWith("application/json"))
                .body("message", equalTo("Hello from Lambda!"));
    }

    @Test
    @Order(11)
    void stringResultIsReturnedAsBody() {
        given()
                .when().get("/execute-api/" + apiId + "/test/string")
                .then()
                .statusCode(200)
                .contentType(startsWith("application/json"))
                .body(equalTo("Hello from Lambda!"));
    }

    @Test
    @Order(12)
    void functionErrorAnswersInternalServerErrorMessage() {
        // Measured on HTTP APIs (eu-west-1, 2026-10-19): 500, in both payload formats.
        given()
                .when().get("/execute-api/" + apiId + "/test/throw")
                .then()
                .statusCode(500)
                .contentType(startsWith("application/json"))
                .body("message", equalTo("Internal Server Error"));
    }

    @Test
    @Order(13)
    void payloadV1ResponseWithoutStatusCodeAnswersInternalServerErrorMessage() {
        // Format 1.0 infers nothing: a result with headers and a body but no statusCode is one
        // the HTTP API cannot read, and it answers 500 (measured, eu-west-1, 2026-10-19).
        given()
                .when().get("/execute-api/" + apiId + "/test/v1")
                .then()
                .statusCode(500)
                .contentType(startsWith("application/json"))
                .header("X-Trace", nullValue())
                .body("message", equalTo("Internal Server Error"));
    }

    @Test
    @Order(14)
    void aResultWithoutAContentTypeIsAnsweredAsTextPlain() {
        given()
                .when().get("/execute-api/" + apiId + "/test/plain")
                .then()
                .statusCode(200)
                .contentType(allOf(startsWith("text/plain"), containsStringIgnoringCase("charset=utf-8")))
                .body(equalTo("hello"));
    }

    @Test
    @Order(15)
    void aResultWithoutAContentTypeIsAnsweredAsTextPlainInPayloadFormatOne() {
        given()
                .when().get("/execute-api/" + apiId + "/test/plain-v1")
                .then()
                .statusCode(200)
                .contentType(allOf(startsWith("text/plain"), containsStringIgnoringCase("charset=utf-8")))
                .body(equalTo("hello"));
    }

    @Test
    @Order(16)
    void aResultThatNamesAContentTypeKeepsIt() {
        given()
                .when().get("/execute-api/" + apiId + "/test/json")
                .then()
                .statusCode(200)
                .contentType(startsWith("application/json"))
                .body(equalTo("{\"a\":1}"));
    }

    @Test
    @Order(999)
    void cleanup() throws Exception {
        if (apiId != null) {
            given().when().delete("/v2/apis/" + apiId);
        }
        WebSocketTestSupport.deleteFunctions(BARE_FUNCTION, STRING_FUNCTION, THROW_FUNCTION, V1_FUNCTION,
                PLAIN_FUNCTION, PLAIN_V1_FUNCTION, JSON_FUNCTION);
    }
}
