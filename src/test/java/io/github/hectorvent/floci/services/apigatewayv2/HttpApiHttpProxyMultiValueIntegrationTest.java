package io.github.hectorvent.floci.services.apigatewayv2;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HTTP API (v2) counterpart of the REST multi-value coverage in
 * {@code ApiGatewayHttpProxyIntegrationTest}: {@code dispatchHttpProxyV2} is a second entry point
 * into the same {@code HttpProxyInvoker} / {@code RequestContext} / {@code ProxyResult} transport,
 * and it built its request context from comma-joined single-value maps. So an HTTP API with an
 * HTTP_PROXY integration still sent {@code ?tag=a,b} for {@code ?tag=a&tag=b} after the REST path
 * was fixed, because the transport's multi-value support was never populated from here.
 *
 * <p>AWS documents HTTP_PROXY as passing the request through, with multi-valued headers and query
 * strings explicitly supported, so the two shapes are not interchangeable: most servers parse
 * {@code tag=a,b} as one value containing a comma rather than as two values.
 */
@QuarkusTest
class HttpApiHttpProxyMultiValueIntegrationTest {

    private static HttpServer backendServer;
    private static int backendPort;

    private static final AtomicReference<String> lastQuery = new AtomicReference<>();
    private static final AtomicReference<List<String>> lastTraceHeaders = new AtomicReference<>();

    @BeforeAll
    static void startBackend() throws IOException {
        backendServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backendServer.createContext("/", HttpApiHttpProxyMultiValueIntegrationTest::handle);
        backendServer.start();
        backendPort = backendServer.getAddress().getPort();
    }

    @AfterAll
    static void stopBackend() {
        if (backendServer != null) {
            backendServer.stop(0);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        lastQuery.set(exchange.getRequestURI().getQuery());
        List<String> trace = exchange.getRequestHeaders().get("X-Trace");
        lastTraceHeaders.set(trace == null ? null : List.copyOf(trace));

        byte[] response = "{\"from\":\"backend\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        // Two separate Set-Cookie lines, the canonical repeated response header. The Expires
        // attribute carries its own comma, so a comma-joined relay cannot be split back apart.
        exchange.getResponseHeaders().add("Set-Cookie",
                "session=abc; Path=/; Expires=Wed, 21 Oct 2026 07:28:00 GMT");
        exchange.getResponseHeaders().add("Set-Cookie", "tracking=xyz; Path=/");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void resetRecordings() {
        lastQuery.set(null);
        lastTraceHeaders.set(null);
    }

    /**
     * Creates an HTTP API whose {@code GET /orders} route is an HTTP_PROXY integration pointing at
     * the fixture backend, deployed to stage {@code test}.
     */
    private String createProxyApi(String name) {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\",\"protocolType\":\"HTTP\"}")
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");

        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\"}")
                .when().post("/v2/apis/" + apiId + "/stages")
                .then().statusCode(201);

        String integrationId = given()
                .contentType(ContentType.JSON)
                .body("{\"integrationType\":\"HTTP_PROXY\",\"integrationUri\":\"http://127.0.0.1:"
                        + backendPort + "/orders\",\"payloadFormatVersion\":\"1.0\"}")
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then().statusCode(201)
                .extract().path("integrationId");

        given()
                .contentType(ContentType.JSON)
                .body("{\"routeKey\":\"GET /orders\",\"target\":\"integrations/" + integrationId + "\"}")
                .when().post("/v2/apis/" + apiId + "/routes")
                .then().statusCode(201);

        return apiId;
    }

    @Test
    void repeatedQueryParametersReachTheBackendSeparately() {
        resetRecordings();
        String apiId = createProxyApi("v2-http-proxy-multi-query-api");

        given()
                .when().get("/execute-api/" + apiId + "/test/orders?tag=a&tag=b&limit=5")
                .then().statusCode(200);

        // Asserted per parameter rather than on the whole query string: the relative order of two
        // differently named parameters is not part of the contract and is not stable across runs.
        // The order of repeated values for one name is, and is checked.
        Map<String, List<String>> received = parseQuery(lastQuery.get());
        assertEquals(List.of("a", "b"), received.get("tag"));
        assertEquals(List.of("5"), received.get("limit"));
    }

    @Test
    void repeatedRequestHeadersReachTheBackendSeparately() {
        resetRecordings();
        String apiId = createProxyApi("v2-http-proxy-multi-header-api");

        given()
                .header("X-Trace", "first")
                .header("X-Trace", "second")
                .when().get("/execute-api/" + apiId + "/test/orders")
                .then().statusCode(200);

        assertEquals(List.of("first", "second"), lastTraceHeaders.get());
    }

    @Test
    void repeatedResponseHeadersRelayToTheCallerSeparately() {
        resetRecordings();
        String apiId = createProxyApi("v2-http-proxy-multi-response-header-api");

        List<String> cookies = given()
                .when().get("/execute-api/" + apiId + "/test/orders")
                .then().statusCode(200)
                .extract().headers().getValues("Set-Cookie");

        assertEquals(2, cookies.size(), "expected both Set-Cookie headers, got: " + cookies);
        assertTrue(cookies.contains("session=abc; Path=/; Expires=Wed, 21 Oct 2026 07:28:00 GMT"),
                "session cookie should relay with its Expires comma intact, got: " + cookies);
        assertTrue(cookies.contains("tracking=xyz; Path=/"), "got: " + cookies);
    }

    /** Query string to name to values, preserving the order values appear in for a given name. */
    private static Map<String, List<String>> parseQuery(String query) {
        Map<String, List<String>> parsed = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return parsed;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            parsed.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return parsed;
    }
}
