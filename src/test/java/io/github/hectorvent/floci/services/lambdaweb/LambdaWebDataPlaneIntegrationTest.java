package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.services.s3.S3Service;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The platform answers that need no execution environment, so no Docker. */
@QuarkusTest
class LambdaWebDataPlaneIntegrationTest {

    private static final String BASE = "/2025-03-07";
    private static final String BUCKET = "web-code-data-plane";
    private static final String ROLE = "arn:aws:iam::000000000000:role/web";
    private static final String UNKNOWN_HOST = "abcdefghijklmnop.001.lambda-web.us-east-1.localhost:4566";
    private static final String REQUEST_ID = "[A-Z2-7]{26}";

    @Inject
    S3Service s3Service;

    @Test
    void anUnknownEndpointDomainIsNotFound() {
        Response response = given().header("Host", UNKNOWN_HOST).when().get("/some/path?q=1");
        response.then().statusCode(404)
                .header("content-type", "text/html")
                .header("connection", "keep-alive")
                .header("lambda-web-error-type", "Platform.InvalidEndpoint")
                .header("lambda-web-request-id", matchesPattern(REQUEST_ID))
                .header("content-length", "225");
        String requestId = response.header("lambda-web-request-id");
        assertEquals(LambdaWebErrorPages.html(LambdaWebErrorPages.INVALID_ENDPOINT, requestId), response.asString());

        // A domain the control plane minted but with another case or port is another domain.
        given().header("Host", UNKNOWN_HOST.toUpperCase()).when().get("/")
                .then().statusCode(404).header("lambda-web-error-type", "Platform.InvalidEndpoint");
    }

    @Test
    void aDisabledEndpointAnswersBeforeAnyEnvironmentStarts() throws IOException {
        String functionName = "dp-disabled-" + Long.toString(System.nanoTime(), 36);
        String domain = createFunction(functionName);
        given().contentType("application/json").body("{\"throttleConfig\": {\"rateLimit\": 0}}")
                .when().patch(BASE + "/web-functions/" + functionName + "/endpoints/dev")
                .then().statusCode(202);

        Response response = given().header("Host", domain).when().get("/");
        response.then().statusCode(503)
                .header("lambda-web-error-type", "Platform.EndpointDisabled")
                .header("content-length", "274")
                .body(equalTo(LambdaWebErrorPages.html(LambdaWebErrorPages.ENDPOINT_DISABLED,
                        response.header("lambda-web-request-id"))));

        // The page keeps the connection, as on AWS. A body sent after it is skipped and the next request is served.
        try (Socket socket = new Socket("localhost", RestAssured.port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(("POST /upload HTTP/1.1\r\nHost: " + domain + "\r\nContent-Length: 5\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String first = readUntil(in, "</html>");
            assertTrue(first.startsWith("HTTP/1.1 503 "), first);
            assertTrue(first.contains("\r\nconnection: keep-alive\r\n"), first);
            out.write(("hello" + "GET /next HTTP/1.1\r\nHost: " + domain + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String second = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(second.startsWith("HTTP/1.1 503 "), second);
            assertTrue(second.contains("\r\nlambda-web-error-type: Platform.EndpointDisabled\r\n"), second);
        }

        given().when().delete(BASE + "/web-functions/" + functionName).then().statusCode(204);
    }

    private static String readUntil(InputStream in, String end) throws IOException {
        StringBuilder read = new StringBuilder();
        while (!read.toString().endsWith(end)) {
            int next = in.read();
            if (next < 0) {
                break;
            }
            read.append((char) next);
        }
        return read.toString();
    }

    @Test
    void httpOneDotZeroIsRefused() throws IOException {
        String response = raw("GET / HTTP/1.0\r\nHost: " + UNKNOWN_HOST + "\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.0 505 HTTP Version Not Supported\r\n"), response);
        assertTrue(response.contains("\r\nlambda-web-error-type: Platform.UnsupportedHttpVersion\r\n"), response);
        assertTrue(response.contains("\r\nconnection: close\r\n"), response);
        assertTrue(response.contains("<p>The provided HTTP version is not supported. Please use HTTP/1.1.</p>"), response);
    }

    @Test
    void anUnsupportedMethodIsRefusedByTheEdge() throws IOException {
        String response = raw("TRACE / HTTP/1.1\r\nHost: " + UNKNOWN_HOST + "\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 400 Bad Request\r\n"), response);
        assertTrue(response.contains("\r\nLambda-Web-Error-Type: Platform.MalformedRequest\r\n"), response);
        assertTrue(response.contains("\r\nContent-Type: text/html; charset=utf-8\r\n"), response);
        assertTrue(response.contains("\r\nConnection: close\r\n"), response);
        assertTrue(response.endsWith(LambdaWebErrorPages.MALFORMED_REQUEST_HTML), response);
    }

    @Test
    void otherHostsStillReachTheControlPlane() {
        given().when().get(BASE + "/web-account-settings").then().statusCode(200);
    }

    private String createFunction(String functionName) {
        s3Service.createBucket(BUCKET, "us-east-1");
        s3Service.putObject(BUCKET, "app.zip", "zip".getBytes(StandardCharsets.UTF_8), "application/zip", Map.of());
        return given().contentType("application/json")
                .body("""
                        {"functionName": "%s",
                         "revisionConfig": {
                           "buildConfig": {"codeConfig": {"s3Object": {"bucket": "%s", "key": "app.zip"}},
                                           "runtimeConfig": {"runtime": "nodejs24.x"}},
                           "serviceConfig": {"executionRoleArn": "%s"}},
                         "endpointConfig": {"endpointName": "dev", "endpointType": "HomeRegion",
                                            "authType": "ApplicationManaged", "autoDeploymentMode": "LatestRevision"}}
                        """.formatted(functionName, BUCKET, ROLE))
                .when().put(BASE + "/web-functions")
                .then().statusCode(202)
                .extract().path("endpoint.domainName");
    }

    private static String raw(String request) throws IOException {
        try (Socket socket = new Socket("localhost", RestAssured.port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
