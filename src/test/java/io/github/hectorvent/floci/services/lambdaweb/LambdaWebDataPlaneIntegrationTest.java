package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.services.iam.IamService;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Inject
    IamService iamService;

    @Inject
    LambdaWebMetrics metrics;

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

    @Test
    void anEndpointCreatedOnAFailedRevisionIsNotActive() {
        String functionName = "dp-failed-" + Long.toString(System.nanoTime(), 36);
        String domain = createFunction(functionName, "ApplicationManaged",
                "this is not a zip\n".getBytes(StandardCharsets.UTF_8));

        Response response = given().header("Host", domain).when().get("/");
        response.then().statusLine("HTTP/1.1 409 Conflict")
                .header("content-type", "text/html")
                .header("connection", "keep-alive")
                .header("lambda-web-error-type", "Platform.EndpointNotActive")
                .header("lambda-web-request-id", matchesPattern(REQUEST_ID))
                .header("content-length", "225")
                .body(equalTo(LambdaWebErrorPages.html(LambdaWebErrorPages.ENDPOINT_NOT_ACTIVE,
                        response.header("lambda-web-request-id"))));
        given().header("Host", domain).when().head("/")
                .then().statusCode(409)
                .header("lambda-web-error-type", "Platform.EndpointNotActive")
                .header("content-length", "225")
                .body(equalTo(""));
        Map<String, String> endpoint = Map.of("FunctionName", functionName, "EndpointName", "dev");
        metrics.awaitPublished(Duration.ofSeconds(5));
        assertEquals(2, LambdaWebMetricStatistics.of(LambdaWebMetrics.NAMESPACE, "HTTPResponseStatusClass4XX",
                endpoint, "Sum"));
        assertEquals(0, LambdaWebMetricStatistics.of(LambdaWebMetrics.NAMESPACE, "ExecutedRequests", endpoint, "Sum"));

        given().when().delete(BASE + "/web-functions/" + functionName + "/endpoints/dev").then().statusCode(204);
        given().header("Host", domain).when().get("/")
                .then().statusCode(404).header("lambda-web-error-type", "Platform.InvalidEndpoint");
        given().when().delete(BASE + "/web-functions/" + functionName).then().statusCode(204);
    }

    @Test
    void aRevisionWhoseCodeVersionIsDeletedFailsForGood() {
        String functionName = "dp-lost-" + Long.toString(System.nanoTime(), 36);
        String domain = createFunction(functionName);
        String versionId = s3Service.headObject(BUCKET, "app.zip").getVersionId();
        String endpoint = BASE + "/web-functions/" + functionName + "/endpoints/dev";
        String revisionId = given().when().get(endpoint).then().statusCode(200)
                .extract().path("revisionWeights[0].revisionId");
        String revision = BASE + "/web-functions/" + functionName + "/revisions/" + revisionId;

        // A delete marker leaves the version the revision runs.
        s3Service.deleteObject(BUCKET, "app.zip");
        given().when().get(revision).then().statusCode(200).body("state", equalTo("Active"));

        s3Service.deleteObject(BUCKET, "app.zip", versionId);
        Response response = given().header("Host", domain).when().get("/");
        response.then().statusLine("HTTP/1.1 409 Conflict")
                .header("content-type", "text/html")
                .header("connection", "keep-alive")
                .header("lambda-web-error-type", "Platform.RevisionNotActive")
                .header("content-length", "225")
                .body(equalTo(LambdaWebErrorPages.html(LambdaWebErrorPages.REVISION_NOT_ACTIVE,
                        response.header("lambda-web-request-id"))));
        String reason = "The specified S3 object does not exist: s3://" + BUCKET + "/app.zip (versionId=" + versionId
                + "). Verify the object exists and the versionId is correct.";
        given().when().get(revision).then().statusCode(200)
                .body("state", equalTo("Failed"))
                .body("stateReason", equalTo(reason));

        s3Service.putObject(BUCKET, "app.zip", LambdaWebZips.indexJs(""), "application/zip", Map.of());
        given().when().get(revision).then().statusCode(200).body("state", equalTo("Failed"));
        given().contentType("application/json")
                .body("{\"autoDeploymentMode\": \"Disabled\", \"revisionWeights\": [{\"revisionId\": \"" + revisionId
                        + "\", \"weight\": 100}]}")
                .when().patch(endpoint)
                .then().statusCode(409)
                .body("message", equalTo("The revision " + revisionId + " is in Failed state and cannot be deployed."
                        + " Only revisions in Active state can be deployed."));

        given().when().delete(BASE + "/web-functions/" + functionName).then().statusCode(204);
    }

    @Test
    void anEnvironmentDoesNotStartWhenLambdaCanNoLongerAssumeTheRole() {
        String functionName = "dp-role-" + Long.toString(System.nanoTime(), 36);
        String domain = createFunction(functionName, "ApplicationManaged", LambdaWebZips.indexJs(""),
                "arn:aws:iam::000000000000:role/" + functionName);
        iamService.updateAssumeRolePolicy(functionName, """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}""");

        Response response = given().header("Host", domain).when().get("/");
        response.then().statusLine("HTTP/1.1 502 Bad Gateway")
                .header("content-type", "text/html")
                .header("connection", "keep-alive")
                .header("lambda-web-error-type", "Platform.CannotAssumeRole")
                .header("lambda-web-request-id", matchesPattern(REQUEST_ID))
                .header("lambda-web-logstream", nullValue())
                .header("content-length", "228")
                .body(equalTo(LambdaWebErrorPages.html(LambdaWebErrorPages.CANNOT_ASSUME_ROLE,
                        response.header("lambda-web-request-id"))));

        iamService.deleteRole(functionName);
        given().header("Host", domain).when().get("/")
                .then().statusCode(502).header("lambda-web-error-type", "Platform.CannotAssumeRole");
        given().contentType("application/json").body("{}")
                .when().post(BASE + "/web-functions/" + functionName + "/list-revisions")
                .then().statusCode(200).body("revisions[0].state", equalTo("Active"));
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
    void anIamAuthEndpointRefusesARequestWithoutAValidSignature() {
        String functionName = "dp-iam-" + Long.toString(System.nanoTime(), 36);
        String domain = createFunction(functionName, "IamAuth");

        Response unsigned = given().header("Host", domain).when().get("/");
        unsigned.then().statusCode(403)
                .header("content-type", "text/html")
                .header("connection", "keep-alive")
                .header("lambda-web-error-type", nullValue())
                .header("lambda-web-request-id", matchesPattern(REQUEST_ID))
                .header("content-length", "230")
                .body(equalTo(LambdaWebErrorPages.html(LambdaWebErrorPages.FORBIDDEN,
                        unsigned.header("lambda-web-request-id"))));
        given().header("Host", domain).when().head("/")
                .then().statusCode(403).header("content-length", "230").body(equalTo(""));
        given().header("Host", domain).header("Authorization", "Bearer abc").when().get("/")
                .then().statusCode(403);
        LambdaWebRequestSigner.Signed forged = new LambdaWebRequestSigner()
                .credentials("test", "not-the-secret", null).sign("GET", domain, "/");
        given().header("Host", domain).headers(forged.headers()).when().get("/")
                .then().statusCode(403);

        // A disabled endpoint answers before the signature is checked.
        given().contentType("application/json").body("{\"throttleConfig\": {\"rateLimit\": 0}}")
                .when().patch(BASE + "/web-functions/" + functionName + "/endpoints/dev")
                .then().statusCode(202);
        given().header("Host", domain).when().get("/")
                .then().statusCode(503).header("lambda-web-error-type", "Platform.EndpointDisabled");

        given().when().delete(BASE + "/web-functions/" + functionName).then().statusCode(204);
    }

    @Test
    void aPageWithoutAnEnvironmentCountsOnlyItsStatusClass() {
        String functionName = "dp-metrics-" + Long.toString(System.nanoTime(), 36);
        String domain = createFunction(functionName, "IamAuth");
        given().header("Host", domain).when().get("/").then().statusCode(403);
        given().header("Host", domain).when().get("/").then().statusCode(403);
        given().contentType("application/json").body("{\"throttleConfig\": {\"rateLimit\": 0}}")
                .when().patch(BASE + "/web-functions/" + functionName + "/endpoints/dev")
                .then().statusCode(202);
        given().header("Host", domain).when().get("/").then().statusCode(503);

        Map<String, String> endpoint = Map.of("FunctionName", functionName, "EndpointName", "dev");
        Map<String, String> usage = Map.of("FunctionName", functionName, "EndpointName", "dev",
                "ExecutionRegion", "us-east-1");
        metrics.awaitPublished(Duration.ofSeconds(5));
        assertEquals(2, LambdaWebMetricStatistics.of(LambdaWebMetrics.NAMESPACE, "HTTPResponseStatusClass4XX",
                endpoint, "Sum"));
        assertEquals(1, LambdaWebMetricStatistics.of(LambdaWebMetrics.NAMESPACE, "HTTPResponseStatusClass5XX",
                endpoint, "Sum"));
        assertEquals(0, LambdaWebMetricStatistics.of(LambdaWebMetrics.NAMESPACE, "ExecutedRequests", endpoint, "Sum"));
        assertEquals(0, LambdaWebMetricStatistics.of(LambdaWebMetrics.USAGE_NAMESPACE, "ExecutedRequests", usage,
                "Sum"));
        assertEquals(0, LambdaWebMetricStatistics.of(LambdaWebMetrics.USAGE_NAMESPACE, "EndpointActiveEnvironments",
                usage, "SampleCount"));

        given().when().delete(BASE + "/web-functions/" + functionName).then().statusCode(204);
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
        assertTrue(response.contains("\r\nContent-Length: 180\r\n"), response);
        assertTrue(response.endsWith(LambdaWebErrorPages.MALFORMED_REQUEST_HTML), response);
    }

    @Test
    void aHeadPastTheLimitIsRefusedByTheEdgeBeforeAnyOtherCheck() throws IOException {
        String host = "Host: " + UNKNOWN_HOST + "\r\n";
        String closing = host + "Connection: close\r\n";
        assertTrue(raw(head("GET / HTTP/1.1", closing, 1, 131_072)).startsWith("HTTP/1.1 404 Not Found\r\n"));
        assertTrue(raw(head("GET / HTTP/1.1", closing, 120, 131_072)).startsWith("HTTP/1.1 404 Not Found\r\n"));

        for (String line : List.of("GET / HTTP/1.1", "TRACE / HTTP/1.1", "GET / HTTP/1.0")) {
            for (int headers : List.of(1, 120)) {
                String response = raw(head(line, host, headers, 131_073));
                String message = line + " " + headers + "\n" + response;
                assertTrue(response.contains(" 431 Request Header Fields Too Large\r\n"), message);
                assertTrue(response.contains("\r\nLambda-Web-Error-Type: Platform.HeaderTooLarge\r\n"), message);
                assertTrue(response.contains("\r\nContent-Type: text/html; charset=utf-8\r\n"), message);
                assertTrue(response.contains("\r\nConnection: close\r\n"), message);
                assertTrue(response.contains("\r\nContent-Length: 196\r\n"), message);
                assertTrue(response.endsWith(LambdaWebErrorPages.HEADER_TOO_LARGE_HTML), message);
            }
        }
    }

    @Test
    void aUriOf64KiBIsTooLong() throws IOException {
        String fits = raw("GET /" + "a".repeat(65_533) + " HTTP/1.1\r\nHost: " + UNKNOWN_HOST
                + "\r\nConnection: close\r\n\r\n");
        assertTrue(fits.startsWith("HTTP/1.1 404 Not Found\r\n"), fits);

        String response = raw("GET /" + "a".repeat(65_534) + " HTTP/1.1\r\nHost: " + UNKNOWN_HOST
                + "\r\nConnection: close\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 414 URI Too Long\r\n"), response);
        assertTrue(response.contains("\r\nconnection: close\r\n"), response);
        assertTrue(response.contains("\r\ncontent-length: 0\r\n"), response);
        assertTrue(response.endsWith("\r\n\r\n"), response);
    }

    @Test
    void otherHostsKeepTheQuarkusLimits() throws IOException {
        String path = "/" + "a".repeat(4096 - "GET / HTTP/1.1".length());
        assertFalse(raw("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                .startsWith("HTTP/1.1 414 "));
        assertTrue(raw("GET " + path + "a HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                .startsWith("HTTP/1.1 414 "));

        String headers = "Host: localhost\r\nConnection: close\r\nX-Pad: ";
        String pad = "a".repeat(20 * 1024 - "Host: localhost".length() - "Connection: close".length() - "X-Pad: ".length());
        assertFalse(raw("GET / HTTP/1.1\r\n" + headers + pad + "\r\n\r\n").startsWith("HTTP/1.1 431 "));
        assertTrue(raw("GET / HTTP/1.1\r\n" + headers + pad + "a\r\n\r\n").startsWith("HTTP/1.1 431 "));
    }

    @Test
    void aPerRegionEndpointCountsAPageInTheRegionThatServedAndInTheHomeRegion() {
        String functionName = "dp-regions-" + Long.toString(System.nanoTime(), 36);
        createFunction(functionName);
        String endpoints = BASE + "/web-functions/" + functionName + "/endpoints";
        String revisionId = given().when().get(endpoints + "/dev").then().statusCode(200)
                .extract().path("revisionWeights[0].revisionId");
        String west = given().contentType("application/json")
                .body("""
                        {"endpointName": "per", "endpointType": "PerRegion", "regions": ["us-west-2"],
                         "authType": "IamAuth", "revisionWeights": [{"revisionId": "%s", "weight": 100}]}"""
                        .formatted(revisionId))
                .when().put(endpoints)
                .then().statusCode(202)
                .extract().path("regionalEndpoints.us-west-2.domainName");
        given().header("Host", west).when().get("/").then().statusCode(403);

        Map<String, String> endpoint = Map.of("FunctionName", functionName, "EndpointName", "per");
        metrics.awaitPublished(Duration.ofSeconds(5));
        assertEquals(1, LambdaWebMetricStatistics.of("us-west-2", LambdaWebMetrics.NAMESPACE,
                "HTTPResponseStatusClass4XX", endpoint, "Sum"));
        assertEquals(1, LambdaWebMetricStatistics.of("us-east-1", LambdaWebMetrics.NAMESPACE,
                "HTTPResponseStatusClass4XX", endpoint, "Sum"));

        given().when().delete(endpoints + "/per").then().statusCode(204);
        given().header("Host", west).when().get("/")
                .then().statusCode(404).header("lambda-web-error-type", "Platform.InvalidEndpoint");
        given().when().delete(BASE + "/web-functions/" + functionName).then().statusCode(204);
    }

    @Test
    void theGlobalDomainOfAFunctionInAnotherRegionCountsAPageInTheDefaultRegion() {
        String functionName = "dp-global-" + Long.toString(System.nanoTime(), 36);
        createFunction("us-west-2", functionName, "ApplicationManaged", LambdaWebZips.indexJs(""), ROLE);
        String west = auth("test", "us-west-2", "lambda");
        String endpoints = BASE + "/web-functions/" + functionName + "/endpoints";
        String revisionId = given().header("Authorization", west).when().get(endpoints + "/dev").then().statusCode(200)
                .extract().path("revisionWeights[0].revisionId");
        String global = given().header("Authorization", west).contentType("application/json")
                .body("""
                        {"endpointName": "global", "endpointType": "MultiRegion", "regions": ["us-east-1"],
                         "authType": "IamAuth", "revisionWeights": [{"revisionId": "%s", "weight": 100}]}"""
                        .formatted(revisionId))
                .when().put(endpoints)
                .then().statusCode(202)
                .extract().path("domainName");
        given().header("Host", global).when().get("/").then().statusCode(403);

        Map<String, String> endpoint = Map.of("FunctionName", functionName, "EndpointName", "global");
        metrics.awaitPublished(Duration.ofSeconds(5));
        assertEquals(1, LambdaWebMetricStatistics.of("us-east-1", LambdaWebMetrics.NAMESPACE,
                "HTTPResponseStatusClass4XX", endpoint, "Sum"));
        assertEquals(1, LambdaWebMetricStatistics.of("us-west-2", LambdaWebMetrics.NAMESPACE,
                "HTTPResponseStatusClass4XX", endpoint, "Sum"));
        given().header("Authorization", west).when().delete(BASE + "/web-functions/" + functionName)
                .then().statusCode(204);
    }

    @Test
    void otherHostsStillReachTheControlPlane() {
        given().when().get(BASE + "/web-account-settings").then().statusCode(200);
    }

    private String createFunction(String functionName) {
        return createFunction(functionName, "ApplicationManaged");
    }

    private String createFunction(String functionName, String authType) {
        return createFunction(functionName, authType, LambdaWebZips.indexJs(""));
    }

    private String createFunction(String functionName, String authType, byte[] code) {
        return createFunction(functionName, authType, code, ROLE);
    }

    private String createFunction(String functionName, String authType, byte[] code, String role) {
        return createFunction("us-east-1", functionName, authType, code, role);
    }

    private String createFunction(String region, String functionName, String authType, byte[] code, String role) {
        LambdaWebRoles.trustLambda(iamService, role);
        LambdaWebCodeBuckets.create(s3Service, BUCKET);
        s3Service.putObject(BUCKET, "app.zip", code, "application/zip", Map.of());
        return given().header("Authorization", auth("test", region, "lambda")).contentType("application/json")
                .body("""
                        {"functionName": "%s",
                         "revisionConfig": {
                           "buildConfig": {"codeConfig": {"s3Object": {"bucket": "%s", "key": "app.zip"}},
                                           "runtimeConfig": {"runtime": "nodejs24.x"}},
                           "serviceConfig": {"executionRoleArn": "%s"}},
                         "endpointConfig": {"endpointName": "dev", "endpointType": "HomeRegion",
                                            "authType": "%s", "autoDeploymentMode": "LatestRevision"}}
                        """.formatted(functionName, BUCKET, role, authType))
                .when().put(BASE + "/web-functions")
                .then().statusCode(202)
                .extract().path("endpoint.domainName");
    }

    /** A head of exactly {@code length} bytes, padded over {@code headers} headers after the fixed ones. */
    private static String head(String requestLine, String fixedHeaders, int headers, int length) {
        StringBuilder head = new StringBuilder(requestLine + "\r\n" + fixedHeaders);
        int padding = length - head.length() - 2;
        int share = padding / headers;
        for (int i = 0; i < headers; i++) {
            String name = "X-Pad-%03d: ".formatted(i);
            int line = i < headers - 1 ? share : padding - (headers - 1) * share;
            head.append(name).append("a".repeat(line - name.length() - 2)).append("\r\n");
        }
        String result = head.append("\r\n").toString();
        assertEquals(length, result.length());
        return result;
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
