package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for API Gateway AWS (non-proxy) Lambda integration.
 *
 * <p>Covers two bugs fixed in {@code ApiGatewayExecuteController#invokeAwsIntegration}:
 * <ol>
 *   <li>AWS type with a Lambda path-style URI was incorrectly routed through the query-protocol
 *       (form-urlencoded) dispatch path, returning
 *       {@code "The request must contain the parameter Action"} instead of invoking the Lambda.</li>
 *   <li>If a {@code Content-Type} header was set via {@code responseParameters} mapping or a VTL
 *       {@code $context.responseOverride}, it was added on top of an already-set
 *       {@code APPLICATION_JSON} type, producing duplicate {@code Content-Type} response headers.</li>
 * </ol>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayAwsLambdaIntegrationTest {

    private static final String LAMBDA_BASE_PATH = "/2015-03-31/functions";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/lambda-role";
    private static final String REGION = "us-east-1";

    // Echo function: returns the received event body back in the response body.
    private static final String ECHO_FUNCTION = "apigw-aws-lambda-echo";

    private static String apiId;
    private static String rootId;
    private static String echoResourceId;
    private static String ctHeaderResourceId;

    // ──────────────────────────── Lambda setup ────────────────────────────

    @Test
    @Order(1)
    void setup_createEchoLambda() throws Exception {
        // Returns the raw event payload as the response body so the test can
        // assert that the VTL-rendered request template reached the function.
        // AWS (non-proxy) integrations pass the raw Lambda return value through
        // the integration response pipeline — do not wrap in a proxy envelope.
        createNodeLambda(ECHO_FUNCTION, """
                exports.handler = async (event) => event;
                """);
    }

    // ──────────────────────────── API Gateway setup ────────────────────────────

    @Test
    @Order(2)
    void setup_createRestApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"aws-lambda-integration-regression\"}")
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");
        assertNotNull(apiId);
    }

    @Test
    @Order(3)
    void setup_getRootResource() {
        rootId = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200)
                .extract().path("item[0].id");
        assertNotNull(rootId);
    }

    // ──────────────────────────── Resource 1: /echo — AWS Lambda integration ────────────────────────────

    @Test
    @Order(4)
    void setup_createEchoResource() {
        echoResourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"echo\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201)
                .extract().path("id");
        assertNotNull(echoResourceId);
    }

    @Test
    @Order(5)
    void setup_configureEchoMethod() {
        String functionArn = "arn:aws:lambda:" + REGION + ":000000000000:function:" + ECHO_FUNCTION;
        String integrationUri = "arn:aws:apigateway:" + REGION + ":lambda:path/2015-03-31/functions/"
                + functionArn + "/invocations";

        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + echoResourceId + "/methods/POST")
                .then().statusCode(201);

        // AWS (non-proxy) Lambda integration with a VTL request template
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                            "type": "AWS",
                            "httpMethod": "POST",
                            "uri": "%s",
                            "requestTemplates": {
                                "application/json": "{\\"forwarded\\": $input.json('$')}"
                            }
                        }
                        """.formatted(integrationUri))
                .when().put("/restapis/" + apiId + "/resources/" + echoResourceId + "/methods/POST/integration")
                .then().statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + echoResourceId
                        + "/methods/POST/integration/responses/200")
                .then().statusCode(201);
    }

    // ──────────────────────────── Resource 2: /ct-header — Content-Type header dedup ────────────────────────────

    @Test
    @Order(6)
    void setup_createCtHeaderResource() {
        ctHeaderResourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"ct-header\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201)
                .extract().path("id");
        assertNotNull(ctHeaderResourceId);
    }

    @Test
    @Order(7)
    void setup_configureCtHeaderMethod() {
        String functionArn = "arn:aws:lambda:" + REGION + ":000000000000:function:" + ECHO_FUNCTION;
        String integrationUri = "arn:aws:apigateway:" + REGION + ":lambda:path/2015-03-31/functions/"
                + functionArn + "/invocations";

        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + ctHeaderResourceId + "/methods/POST")
                .then().statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                            "type": "AWS",
                            "httpMethod": "POST",
                            "uri": "%s",
                            "requestTemplates": {
                                "application/json": "{\\"input\\": $input.json('$')}"
                            }
                        }
                        """.formatted(integrationUri))
                .when().put("/restapis/" + apiId + "/resources/" + ctHeaderResourceId + "/methods/POST/integration")
                .then().statusCode(201);

        // Integration response with a responseParameters Content-Type mapping.
        // Before the fix, this caused a second Content-Type header to be added on
        // top of the ResponseBuilder's already-set APPLICATION_JSON type.
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                            "selectionPattern": "",
                            "responseTemplates": {"application/json": ""},
                            "responseParameters": {
                                "method.response.header.Content-Type": "'application/json'"
                            }
                        }
                        """)
                .when().put("/restapis/" + apiId + "/resources/" + ctHeaderResourceId
                        + "/methods/POST/integration/responses/200")
                .then().statusCode(201);
    }

    @Test
    @Order(8)
    void setup_deployStage() {
        String deploymentId = given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"regression-test\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);
    }

    // ──────────────────────────── Regression test 1: AWS Lambda dispatch ────────────────────────────

    /**
     * Regression: AWS type Lambda integration must dispatch to Lambda rather than falling
     * through to the query-protocol path. Before the fix, Floci returned 500 with
     * {@code "The request must contain the parameter Action"}.
     *
     * <p>This test verifies the dispatch routing is correct. Actual Lambda execution
     * requires Docker and is covered by compatibility tests.
     */
    @Test
    @Order(10)
    void awsLambdaIntegration_doesNotReturnQueryProtocolError() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"hello\":\"world\"}")
                .when().post("/execute-api/" + apiId + "/test/echo")
                .then()
                // Must not be a 500 from the query-protocol dispatcher
                .statusCode(not(equalTo(500)))
                // Exact regression: the old error from invokeQuery must not appear
                .body(not(containsString("must contain the parameter Action")));
    }

    // ──────────────────────────── Regression test 2: duplicate Content-Type header ────────────────────────────

    /**
     * Regression: When a {@code responseParameters} mapping sets {@code Content-Type},
     * the response must contain exactly one {@code Content-Type} header. Before the fix,
     * two values were present — one from the eager {@code .type(APPLICATION_JSON)} call
     * and one added by the response parameter mapping.
     */
    @Test
    @Order(12)
    void awsLambdaIntegration_responseParametersContentType_notDuplicated() {
        Response response = given()
                .contentType(ContentType.JSON)
                .body("{\"check\":\"ct\"}")
                .when().post("/execute-api/" + apiId + "/test/ct-header")
                .then()
                .statusCode(200)
                .extract().response();

        // Must be exactly one Content-Type header value, not two
        assertEquals(1, response.getHeaders().getList("Content-Type").size(),
                "Content-Type header must appear exactly once in the response");
        assertThat(response.getHeader("Content-Type"), containsString("application/json"));
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private static void createNodeLambda(String functionName, String handlerSource) throws Exception {
        String zipBase64 = Base64.getEncoder().encodeToString(makeZip("index.js", handlerSource));
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                            "FunctionName": "%s",
                            "Runtime": "nodejs20.x",
                            "Role": "%s",
                            "Handler": "index.handler",
                            "Timeout": 30,
                            "Code": {"ZipFile": "%s"}
                        }
                        """.formatted(functionName, ROLE_ARN, zipBase64))
                .when().post(LAMBDA_BASE_PATH)
                .then().statusCode(201);
    }

    private static byte[] makeZip(String filename, String content) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(filename));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
