package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
class LambdaFunctionUrlAccountRoutingTest {

    private static final String ACCOUNT_A = "345678901234";
    private static final String ACCOUNT_B = "456789012345";
    private static final String FUNCTION_NAME = "function-url-multi-account";

    @Test
    void functionUrlIdsAndInvocationsAreAccountScoped() throws Exception {
        String urlA = createFunctionUrl(ACCOUNT_A);
        String urlB = createFunctionUrl(ACCOUNT_B);

        assertNotEquals(urlA, urlB);

        given()
                .when().get("/lambda-url/" + urlId(urlA) + "/")
                .then()
                .statusCode(200)
                .body("accountId", equalTo(ACCOUNT_A));

        given()
                .when().get("/lambda-url/" + urlId(urlB) + "/")
                .then()
                .statusCode(200)
                .body("accountId", equalTo(ACCOUNT_B));
    }

    private String createFunctionUrl(String accountId) throws Exception {
        String authorization = authorization(accountId, "lambda");
        String zip = Base64.getEncoder().encodeToString(lambdaZip());

        given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {
                          "FunctionName":"%s",
                          "Runtime":"nodejs20.x",
                          "Role":"arn:aws:iam::%s:role/lambda-role",
                          "Handler":"index.handler",
                          "Code":{"ZipFile":"%s"}
                        }
                        """.formatted(FUNCTION_NAME, accountId, zip))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);

        return given()
                .contentType("application/json")
                .header("Authorization", authorization)
                .body("""
                        {"AuthType":"NONE"}
                        """)
                .when().post("/2021-10-31/functions/" + FUNCTION_NAME + "/url")
                .then()
                .statusCode(201)
                .extract().path("FunctionUrl");
    }

    private byte[] lambdaZip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write("""
                    exports.handler = async (event) => ({
                      statusCode: 200,
                      headers: { "content-type": "application/json" },
                      body: JSON.stringify({ accountId: event.requestContext.accountId })
                    });
                    """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private String urlId(String functionUrl) {
        int scheme = functionUrl.indexOf("://");
        int firstDot = functionUrl.indexOf('.', scheme + 3);
        return functionUrl.substring(scheme + 3, firstDot);
    }

    private String authorization(String accountId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId
                + "/20260803/us-east-1/" + service + "/aws4_request";
    }
}
