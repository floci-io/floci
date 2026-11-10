package io.floci.aws.services.lambda;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class LambdaFunctionUrlResponseStreamTest {

    private static final String FUNCTION_NAME = "function-url-response-stream";
    private static final long PAUSE_MS = 2000;

    @TestHTTPResource("/")
    URL baseUrl;

    @Test
    void responseStreamUrlDeliversEachChunkAsTheFunctionWritesIt() throws Exception {
        String urlId = createStreamingFunctionUrl();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "lambda-url/" + urlId + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            assertEquals(201, response.statusCode());
            assertEquals("text/event-stream", response.headers().firstValue("content-type").orElse(""));
            try (BufferedReader body = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                assertEquals("data: first", body.readLine());
                long firstChunkAt = System.nanoTime();
                assertEquals("", body.readLine());
                assertEquals("data: last", body.readLine());
                long lastChunkAt = System.nanoTime();

                assertTrue((lastChunkAt - firstChunkAt) / 1_000_000 >= PAUSE_MS / 2,
                        "the first chunk should arrive before the function writes the last one");
            }
        }
    }

    private String createStreamingFunctionUrl() throws Exception {
        return createStreamingFunctionUrl(FUNCTION_NAME, """
                exports.handler = awslambda.streamifyResponse(async (event, responseStream) => {
                  responseStream = awslambda.HttpResponseStream.from(responseStream, {
                    statusCode: 201, headers: { "content-type": "text/event-stream" } });
                  responseStream.write("data: first\\n\\n");
                  await new Promise((resolve) => setTimeout(resolve, %d));
                  responseStream.write("data: last\\n\\n");
                  responseStream.end();
                });
                """.formatted(PAUSE_MS));
    }

    private String createStreamingFunctionUrl(String functionName, String code) throws Exception {
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
                        """.formatted(functionName, Base64.getEncoder().encodeToString(lambdaZip(code))))
                .when().post("/2015-03-31/functions")
                .then()
                .statusCode(201);

        String functionUrl = given()
                .contentType("application/json")
                .body("""
                        {"AuthType":"NONE","InvokeMode":"RESPONSE_STREAM"}
                        """)
                .when().post("/2021-10-31/functions/" + functionName + "/url")
                .then()
                .statusCode(201)
                .extract().path("FunctionUrl");
        int scheme = functionUrl.indexOf("://");
        return functionUrl.substring(scheme + 3, functionUrl.indexOf('.', scheme + 3));
    }

    private byte[] lambdaZip(String code) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write(code.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
