package io.github.hectorvent.floci.services.apigatewayv2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2BinaryPayloadIntegrationTest {

    private static final String FUNCTION_NAME = "httpv2-binary-payload-fn";
    private static final byte[] BINARY_BODY = {(byte) 0xff, (byte) 0xfe, (byte) 0xfd, (byte) 0xfa};

    private static String apiId;
    private static String integrationId;

    @Test
    @Order(1)
    void setupLambdaAndHttpApi() throws Exception {
        String zip = WebSocketTestSupport.createLambdaZip("""
                exports.handler = async (event) => ({
                    statusCode: 200,
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({
                        body: event.body,
                        isBase64Encoded: event.isBase64Encoded
                    })
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
                        {"name":"binary-payload-test","protocolType":"HTTP"}
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

        integrationId = given()
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
                        {"routeKey":"POST /echo","target":"integrations/%s"}
                        """.formatted(integrationId))
                .when().post("/v2/apis/" + apiId + "/routes")
                .then().statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/2015-03-31/functions/" + FUNCTION_NAME + "/invocations")
                .then().statusCode(200);
    }

    @Test
    @Order(10)
    void binaryRequestIsBase64EncodedInLambdaEvent() {
        given()
                .contentType("application/octet-stream")
                .body(BINARY_BODY)
                .when().post("/execute-api/" + apiId + "/test/echo")
                .then()
                .statusCode(200)
                .body("body", equalTo(Base64.getEncoder().encodeToString(BINARY_BODY)))
                .body("isBase64Encoded", equalTo(true));
    }

    @Test
    @Order(11)
    void textRequestRemainsPlainInLambdaEvent() {
        given()
                .contentType("text/plain; charset=utf-8")
                .body("hello world")
                .when().post("/execute-api/" + apiId + "/test/echo")
                .then()
                .statusCode(200)
                .body("body", equalTo("hello world"))
                .body("isBase64Encoded", equalTo(false));
    }

    @Test
    @Order(12)
    void jsonRequestRemainsPlainInLambdaEvent() {
        String jsonBody = "{\"message\":\"hello\"}";

        given()
                .contentType(ContentType.JSON)
                .body(jsonBody)
                .when().post("/execute-api/" + apiId + "/test/echo")
                .then()
                .statusCode(200)
                .body("body", equalTo(jsonBody))
                .body("isBase64Encoded", equalTo(false));
    }

    @Test
    @Order(999)
    void cleanup() {
        if (apiId != null) {
            given().when().delete("/v2/apis/" + apiId);
        }
        given().when().delete("/2015-03-31/functions/" + FUNCTION_NAME);
    }
}
