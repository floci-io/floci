package io.floci.aws.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class LambdaFunctionUrlResponseStreamTest {

    private static final String FUNCTION_NAME = "function-url-response-stream";

    @Test
    void responseStreamUrlAnswersWithThePreludeAndTheStreamedBody() throws Exception {
        String urlId = createStreamingFunctionUrl();

        given()
                .when().get("/lambda-url/" + urlId + "/")
                .then()
                .statusCode(201)
                .header("content-type", "text/event-stream")
                .body(equalTo("data: first\n\ndata: last\n\n"));
    }

    private String createStreamingFunctionUrl() throws Exception {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "FunctionName":"%s",
                          "Runtime":"nodejs22.x",
                          "Role":"arn:aws:iam::000000000000:role/lambda-role",
                          "Handler":"index.handler",
                          "Code":{"ZipFile":"%s"}
                        }
                        """.formatted(FUNCTION_NAME, Base64.getEncoder().encodeToString(lambdaZip())))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);

        String functionUrl = given()
                .contentType("application/json")
                .body("""
                        {"AuthType":"NONE","InvokeMode":"RESPONSE_STREAM"}
                        """)
                .when().post("/2021-10-31/functions/" + FUNCTION_NAME + "/url")
                .then()
                .statusCode(201)
                .extract().path("FunctionUrl");
        int scheme = functionUrl.indexOf("://");
        return functionUrl.substring(scheme + 3, functionUrl.indexOf('.', scheme + 3));
    }

    private byte[] lambdaZip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write("""
                    exports.handler = awslambda.streamifyResponse(async (event, responseStream) => {
                      responseStream = awslambda.HttpResponseStream.from(responseStream, {
                        statusCode: 201, headers: { "content-type": "text/event-stream" } });
                      responseStream.write("data: first\\n\\n");
                      responseStream.write("data: last\\n\\n");
                      responseStream.end();
                    });
                    """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
