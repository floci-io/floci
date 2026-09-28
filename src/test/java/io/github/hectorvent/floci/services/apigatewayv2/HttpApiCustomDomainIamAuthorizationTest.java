package io.github.hectorvent.floci.services.apigatewayv2;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.testutil.ExecuteApiRequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The HTTP API (v2) half of custom-domain IAM authorization. A v2 API mapping is stored as a
 * base path mapping and routed by the same custom domain filter as REST APIs, so the caller
 * signs {@code Host: <domain>} and the mapped path while the filter rewrites the URI onto
 * {@code /execute-api/...}. Verification has to use the signed form here too, and a rejection
 * takes v2's flat {@code 403 Forbidden} shape rather than v1's {@code x-amzn-ErrorType}.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpApiCustomDomainIamAuthorizationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCESS_KEY = "test";
    private static final String SECRET_KEY = "test";
    private static final String STAGE = "test";
    private static final String DOMAIN = "iam-http.example.com";
    private static final String MAPPING_KEY = "v1";

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
        httpApiId = given().contentType(ContentType.JSON)
                .body("""
                        {"name":"http-api-custom-domain-iam-test","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");
        given().contentType(ContentType.JSON)
                .body("""
                        {"stageName":"%s"}
                        """.formatted(STAGE))
                .when().post("/v2/apis/" + httpApiId + "/stages")
                .then().statusCode(201);

        String backendUrl = "http://127.0.0.1:" + backendServer.getAddress().getPort();
        String integrationId = given().contentType(ContentType.JSON)
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

        given().contentType(ContentType.JSON)
                .body("""
                        {"domainName":"%s","domainNameConfigurations":[
                            {"certificateArn":"arn:aws:acm:us-east-1:000000000000:certificate/abc",
                             "endpointType":"REGIONAL"}]}
                        """.formatted(DOMAIN))
                .when().post("/v2/domainnames")
                .then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body("""
                        {"apiId":"%s","stage":"%s","apiMappingKey":"%s"}
                        """.formatted(httpApiId, STAGE, MAPPING_KEY))
                .when().post("/v2/domainnames/" + DOMAIN + "/apimappings")
                .then().statusCode(201);
    }

    @Test
    @Order(10)
    void openRouteIsReachableThroughTheCustomDomain() {
        backendHits.set(0);
        given().header("Host", DOMAIN)
                .when().get("/" + MAPPING_KEY + "/open")
                .then().statusCode(200)
                .body(equalTo("reached the integration"));
        assertEquals(1, backendHits.get(), "the v2 API mapping must route through the custom domain");
    }

    @Test
    @Order(11)
    void unsignedRequestThroughTheCustomDomainIsRejected() {
        backendHits.set(0);
        given().header("Host", DOMAIN)
                .when().get(customDomainPath())
                .then().statusCode(403)
                .body("message", equalTo("Forbidden"));
        assertEquals(0, backendHits.get());
    }

    @Test
    @Order(12)
    void requestSignedForTheCustomDomainPathReachesTheIntegration() throws Exception {
        backendHits.set(0);
        given().header("Host", DOMAIN)
                .headers(signedHeaders(customDomainPath()))
                .when().get(customDomainPath())
                .then().statusCode(200)
                .body(equalTo("reached the integration"));
        assertEquals(1, backendHits.get());
    }

    @Test
    @Order(13)
    void signatureOverTheRewrittenExecuteApiPathIsRejected() throws Exception {
        backendHits.set(0);
        given().header("Host", DOMAIN)
                .headers(signedHeaders("/execute-api/" + httpApiId + "/" + STAGE + "/iam"))
                .when().get(customDomainPath())
                .then().statusCode(403)
                .body("message", equalTo("Forbidden"));
        assertEquals(0, backendHits.get());
    }

    @Test
    @Order(14)
    void signatureForAnotherMappedPathIsRejected() throws Exception {
        backendHits.set(0);
        given().header("Host", DOMAIN)
                .headers(signedHeaders("/" + MAPPING_KEY + "/open"))
                .when().get(customDomainPath())
                .then().statusCode(403)
                .body("message", equalTo("Forbidden"));
        assertEquals(0, backendHits.get());
    }

    @Test
    @Order(999)
    void cleanup() {
        given().when().delete("/v2/domainnames/" + DOMAIN);
        if (httpApiId != null) {
            given().when().delete("/v2/apis/" + httpApiId);
        }
    }

    private static String customDomainPath() {
        return "/" + MAPPING_KEY + "/iam";
    }

    private static Map<String, String> signedHeaders(String path) throws Exception {
        return ExecuteApiRequestSigner.signedHeaders(
                "GET", path, Map.of(), DOMAIN, null, ACCESS_KEY, SECRET_KEY, REGION, Instant.now());
    }
}
