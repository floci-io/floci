package io.github.hectorvent.floci.services.apigatewayv2;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.github.hectorvent.floci.testutil.ExecuteApiRequestSigner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An HTTP API route whose {@code authorizationType} is {@code AWS_IAM} was recorded
 * but never enforced: {@code dispatchV2} branched on JWT and CUSTOM only, so an entirely unsigned
 * request reached the integration exactly as if the route were {@code NONE}.
 *
 * <p>The backend is a fixture HTTP server behind an HTTP_PROXY integration, counting every request
 * that reaches it. Asserting the count is what separates "rejected with 403" from "rejected after
 * the integration already ran", which is the failure mode the issue describes.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpApiIamAuthorizationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCESS_KEY = "test";
    private static final String SECRET_KEY = "test";

    private static HttpServer backendServer;
    private static final AtomicInteger backendHits = new AtomicInteger();

    private static String httpApiId;

    @BeforeAll
    static void startBackend() throws Exception {
        backendServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backendServer.createContext("/", exchange -> {
            backendHits.incrementAndGet();
            byte[] body = "reached the integration".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        backendServer.start();
    }

    @AfterAll
    static void stopBackend() {
        if (backendServer != null) {
            backendServer.stop(0);
        }
    }

    @Test
    @Order(1)
    void setup() {
        httpApiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"http-api-iam-auth-test","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");

        given().contentType(ContentType.JSON)
                .body("""
                        {"stageName":"test"}
                        """)
                .when().post("/v2/apis/" + httpApiId + "/stages")
                .then().statusCode(201);

        String backendUrl = "http://127.0.0.1:" + backendServer.getAddress().getPort();
        String integrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"HTTP_PROXY","integrationUri":"%s","payloadFormatVersion":"1.0"}
                        """.formatted(backendUrl))
                .when().post("/v2/apis/" + httpApiId + "/integrations")
                .then().statusCode(201)
                .extract().path("integrationId");

        given().contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"GET /iam","authorizationType":"AWS_IAM","target":"integrations/%s"}
                        """.formatted(integrationId))
                .when().post("/v2/apis/" + httpApiId + "/routes")
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"GET /open","target":"integrations/%s"}
                        """.formatted(integrationId))
                .when().post("/v2/apis/" + httpApiId + "/routes")
                .then().statusCode(201);

        given().when().get("/v2/apis/" + httpApiId + "/routes")
                .then().statusCode(200)
                .body("items.find { it.routeKey == 'GET /iam' }.authorizationType", equalTo("AWS_IAM"));
    }

    @Test
    @Order(10)
    void unsignedRequestIsRejectedBeforeTheIntegrationRuns() {
        backendHits.set(0);

        given().when().get(iamPath())
                .then().statusCode(403)
                .body("message", equalTo("Forbidden"));

        assertEquals(0, backendHits.get(),
                "an unsigned request to an AWS_IAM route must not reach the integration");
    }

    @Test
    @Order(11)
    void authorizationHeaderThatIsNotSigV4IsRejected() {
        backendHits.set(0);

        given().header("Authorization", "Bearer not-a-sigv4-credential")
                .when().get(iamPath())
                .then().statusCode(403)
                .body("message", equalTo("Forbidden"));

        assertEquals(0, backendHits.get());
    }

    @Test
    @Order(12)
    void validlySignedRequestReachesTheIntegration() throws Exception {
        backendHits.set(0);

        given().headers(signedHeaders(Instant.now()))
                .when().get(iamPath())
                .then().statusCode(200)
                .body(equalTo("reached the integration"));

        assertEquals(1, backendHits.get());
    }

    @Test
    @Order(13)
    void tamperedSignatureIsRejected() throws Exception {
        backendHits.set(0);

        Map<String, String> headers = signedHeaders(Instant.now());
        String authorization = headers.get("Authorization");
        // Flip the signature's last hex digit: everything else about the request stays valid, so
        // only the signature comparison can be what rejects it.
        char last = authorization.charAt(authorization.length() - 1);
        headers.put("Authorization", authorization.substring(0, authorization.length() - 1)
                + (last == '0' ? '1' : '0'));

        given().headers(headers)
                .when().get(iamPath())
                .then().statusCode(403)
                .body("message", equalTo("Forbidden"));

        assertEquals(0, backendHits.get());
    }

    @Test
    @Order(14)
    void signatureFromAnotherPathDoesNotAuthorizeThisOne() throws Exception {
        backendHits.set(0);

        // Signed for /open, replayed against /iam. The path is inside the canonical request, so a
        // verifier that only checks "is there a well-formed signature" would let this through.
        Map<String, String> headers = ExecuteApiRequestSigner.signedHeaders(
                "GET", "/execute-api/" + httpApiId + "/test/open", Map.of(), host(), null,
                ACCESS_KEY, SECRET_KEY, REGION, Instant.now());

        given().headers(headers)
                .when().get(iamPath())
                .then().statusCode(403)
                .body("message", equalTo("Forbidden"));

        assertEquals(0, backendHits.get());
    }

    @Test
    @Order(15)
    void staleSignatureIsRejected() throws Exception {
        backendHits.set(0);

        given().headers(signedHeaders(Instant.now().minus(2, ChronoUnit.HOURS)))
                .when().get(iamPath())
                .then().statusCode(403)
                .body("message", equalTo("Forbidden"));

        assertEquals(0, backendHits.get());
    }

    @Test
    @Order(16)
    void unregisteredAccessKeyIsRejected() throws Exception {
        backendHits.set(0);

        Map<String, String> headers = ExecuteApiRequestSigner.signedHeaders(
                "GET", "/execute-api/" + httpApiId + "/test/iam", Map.of(), host(), null,
                "AKIANOTREGISTERED", "AKIANOTREGISTERED", REGION, Instant.now());

        given().headers(headers)
                .when().get(iamPath())
                .then().statusCode(403)
                .body("message", equalTo("Forbidden"));

        assertEquals(0, backendHits.get(),
                "an unknown access key must not be able to sign for itself");
    }

    @Test
    @Order(17)
    void routeWithoutIamAuthorizationStillAcceptsUnsignedRequests() {
        backendHits.set(0);

        given().when().get("/execute-api/" + httpApiId + "/test/open")
                .then().statusCode(200);

        assertEquals(1, backendHits.get(), "enforcement must be scoped to AWS_IAM routes");
    }

    @Test
    @Order(999)
    void cleanup() {
        if (httpApiId != null) {
            given().when().delete("/v2/apis/" + httpApiId);
        }
    }

    private static String iamPath() {
        return "/execute-api/" + httpApiId + "/test/iam";
    }

    private static String host() {
        return "localhost:" + RestAssured.port;
    }

    private static Map<String, String> signedHeaders(Instant signedAt) throws Exception {
        return ExecuteApiRequestSigner.signedHeaders(
                "GET", "/execute-api/" + httpApiId + "/test/iam", Map.of(), host(), null,
                ACCESS_KEY, SECRET_KEY, REGION, signedAt);
    }
}
