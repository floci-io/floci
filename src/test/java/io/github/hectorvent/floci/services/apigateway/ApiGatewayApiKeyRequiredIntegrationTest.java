package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;

/**
 * Integration tests for {@code AWS::ApiGateway::Method.ApiKeyRequired} enforcement on the
 * execute-api data plane. A method with {@code apiKeyRequired=true} must reject requests whose
 * {@code x-api-key} header does not resolve to an enabled key linked to the invoked stage through
 * a usage plan, and must not affect a method that leaves it at the default {@code false}.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayApiKeyRequiredIntegrationTest {

    private static final String LAMBDA_BASE_PATH = "/2015-03-31/functions";
    private static final String ECHO_FUNCTION = "apigw-apikeyrequired-echo";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/lambda-role";
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private static String apiId;
    private static String protectedResourceId;
    private static String openResourceId;
    private static String keyValue;

    @Test @Order(1)
    void createEchoLambda() throws Exception {
        String zipBase64 = Base64.getEncoder().encodeToString(zipEntries(Map.of("index.js", """
                exports.handler = async () => ({ statusCode: 200, body: JSON.stringify({ ok: true }) });
                """)));
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
                        """.formatted(ECHO_FUNCTION, ROLE_ARN, zipBase64))
                .when().post(LAMBDA_BASE_PATH)
                .then()
                .statusCode(201);
    }

    @Test @Order(2)
    void setupApiWithProtectedAndOpenMethods() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"apikeyrequired-api\"}")
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");

        String rootId = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200)
                .extract().path("item[0].id");

        protectedResourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"secret\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201)
                .extract().path("id");

        openResourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"public\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\",\"apiKeyRequired\":true}")
                .when().put("/restapis/" + apiId + "/resources/" + protectedResourceId + "/methods/GET")
                .then().statusCode(201)
                .body("apiKeyRequired", org.hamcrest.Matchers.is(true));

        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + openResourceId + "/methods/GET")
                .then().statusCode(201)
                .body("apiKeyRequired", org.hamcrest.Matchers.is(false));

        String uri = "arn:aws:apigateway:" + REGION + ":lambda:path/2015-03-31/functions/"
                + "arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:" + ECHO_FUNCTION + "/invocations";
        for (String resourceId : new String[] {protectedResourceId, openResourceId}) {
            given()
                    .contentType(ContentType.JSON)
                    .body("""
                            {"type":"AWS_PROXY","httpMethod":"POST","uri":"%s"}
                            """.formatted(uri))
                    .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration")
                    .then().statusCode(201);
        }

        String depId = given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"apikeyrequired\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"prod","deploymentId":"%s"}
                        """.formatted(depId))
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);
    }

    @Test @Order(3)
    void createKeyAndAttachToUsagePlan() {
        String keyId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"apikeyrequired-key\",\"enabled\":true,\"generateDistinctId\":true}")
                .when().post("/apikeys")
                .then().statusCode(201)
                .extract().path("id");

        keyValue = given()
                .when().get("/apikeys/" + keyId + "?includeValue=true")
                .then().statusCode(200)
                .extract().path("value");

        String planId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"apikeyrequired-plan","apiStages":[{"apiId":"%s","stage":"prod"}]}
                        """.formatted(apiId))
                .when().post("/usageplans")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"keyId":"%s","keyType":"API_KEY"}
                        """.formatted(keyId))
                .when().post("/usageplans/" + planId + "/keys")
                .then().statusCode(201);
    }

    @Test @Order(4)
    void protectedMethodRejectsRequestWithoutApiKey() {
        given()
                .when().get("/execute-api/" + apiId + "/prod/secret")
                .then().statusCode(403)
                .body("message", org.hamcrest.Matchers.is("Forbidden"));
    }

    @Test @Order(5)
    void protectedMethodRejectsRequestWithUnknownApiKey() {
        given()
                .header("x-api-key", "not-a-real-key")
                .when().get("/execute-api/" + apiId + "/prod/secret")
                .then().statusCode(403)
                .body("message", org.hamcrest.Matchers.is("Forbidden"));
    }

    @Test @Order(6)
    void protectedMethodAcceptsRequestWithValidApiKey() {
        given()
                .header("x-api-key", keyValue)
                .when().get("/execute-api/" + apiId + "/prod/secret")
                .then().statusCode(200)
                .body("ok", org.hamcrest.Matchers.is(true));
    }

    @Test @Order(7)
    void openMethodDoesNotRequireApiKey() {
        given()
                .when().get("/execute-api/" + apiId + "/prod/public")
                .then().statusCode(200)
                .body("ok", org.hamcrest.Matchers.is(true));
    }

    @Test @Order(8)
    void updateMethodRejectsUnsupportedPatchOperationOnApiKeyRequired() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "patchOperations": [
                            {"op": "remove", "path": "/apiKeyRequired"}
                          ]
                        }
                        """)
                .when().patch("/restapis/" + apiId + "/resources/" + protectedResourceId + "/methods/GET")
                .then().statusCode(400);
    }

    @Test @Order(9)
    void updateMethodRejectsInvalidBooleanValueOnApiKeyRequired() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "patchOperations": [
                            {"op": "replace", "path": "/apiKeyRequired", "value": "garbage"}
                          ]
                        }
                        """)
                .when().patch("/restapis/" + apiId + "/resources/" + protectedResourceId + "/methods/GET")
                .then().statusCode(400);
    }

    @Test @Order(10)
    void updateMethodSuccessfullyUpdatesApiKeyRequired() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "patchOperations": [
                            {"op": "replace", "path": "/apiKeyRequired", "value": "false"}
                          ]
                        }
                        """)
                .when().patch("/restapis/" + apiId + "/resources/" + protectedResourceId + "/methods/GET")
                .then().statusCode(200)
                .body("apiKeyRequired", org.hamcrest.Matchers.is(false));

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "patchOperations": [
                            {"op": "replace", "path": "/apiKeyRequired", "value": "true"}
                          ]
                        }
                        """)
                .when().patch("/restapis/" + apiId + "/resources/" + protectedResourceId + "/methods/GET")
                .then().statusCode(200)
                .body("apiKeyRequired", org.hamcrest.Matchers.is(true));
    }

    private static byte[] zipEntries(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }
}
