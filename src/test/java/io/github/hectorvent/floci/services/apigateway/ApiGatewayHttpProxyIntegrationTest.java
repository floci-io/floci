package io.github.hectorvent.floci.services.apigateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the REST (v1) {@code HTTP_PROXY} integration type: API Gateway forwards the request to an
 * arbitrary HTTP backend and relays the backend's response verbatim, with no VTL mapping and no
 * integration-response selection (AWS applies neither to {@code HTTP_PROXY}).
 *
 * <p>{@code putIntegration} already accepted {@code "type":"HTTP_PROXY"}, but dispatch handled only
 * {@code AWS_PROXY}/{@code AWS}/{@code MOCK} and fell through to a 500 "Unsupported integration
 * type" — so a REST API fronting an HTTP microservice, the shape CDK's
 * {@code HttpIntegration}/{@code addProxy} emits, could be created but never invoked.
 */
@QuarkusTest
class ApiGatewayHttpProxyIntegrationTest {

    private static HttpServer backendServer;
    private static int backendPort;
    /** A port nothing is listening on, for the connection-failure case. */
    private static int deadPort;

    private static final AtomicReference<String> lastMethod = new AtomicReference<>();
    private static final AtomicReference<String> lastPath = new AtomicReference<>();
    private static final AtomicReference<String> lastQuery = new AtomicReference<>();
    private static final AtomicReference<String> lastBody = new AtomicReference<>();
    private static final AtomicReference<String> lastForwardedHeader = new AtomicReference<>();
    private static final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private static final AtomicReference<String> lastHost = new AtomicReference<>();

    private final List<String> createdApis = new ArrayList<>();

    @BeforeAll
    static void startBackend() throws IOException {
        backendServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backendServer.createContext("/", ApiGatewayHttpProxyIntegrationTest::handle);
        backendServer.start();
        backendPort = backendServer.getAddress().getPort();

        try (ServerSocket probe = new ServerSocket(0)) {
            deadPort = probe.getLocalPort();
        }
    }

    @AfterAll
    static void stopBackend() {
        if (backendServer != null) backendServer.stop(0);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        lastMethod.set(exchange.getRequestMethod());
        lastPath.set(exchange.getRequestURI().getPath());
        lastQuery.set(exchange.getRequestURI().getQuery());
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        lastForwardedHeader.set(exchange.getRequestHeaders().getFirst("X-Forwarded-Tenant"));
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        lastHost.set(exchange.getRequestHeaders().getFirst("Host"));

        // /missing exercises relaying a backend error status untouched.
        int status = exchange.getRequestURI().getPath().endsWith("/missing") ? 404 : 200;
        byte[] response = ("{\"from\":\"backend\",\"path\":\"" + exchange.getRequestURI().getPath() + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("X-Backend-Marker", "hit");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void resetRecordings() {
        lastMethod.set(null);
        lastPath.set(null);
        lastQuery.set(null);
        lastBody.set(null);
        lastForwardedHeader.set(null);
        lastAuthorization.set(null);
        lastHost.set(null);
    }

    /**
     * Creates a REST API with a greedy {@code /{proxy+}} resource whose ANY method is an
     * HTTP_PROXY integration pointing at {@code targetUri}, deployed to stage {@code test}.
     */
    private String createProxyApi(String name, String targetUri, String requestParametersJson) {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\"}")
                .when().post("/restapis")
                .then().statusCode(201).body("id", notNullValue())
                .extract().path("id");
        createdApis.add(apiId);

        String rootId = given().when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");

        String resourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"{proxy+}\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201).extract().path("id");

        given().contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/ANY")
                .then().statusCode(201);

        String integrationBody = "{\"type\":\"HTTP_PROXY\",\"httpMethod\":\"ANY\",\"uri\":\"" + targetUri + "\""
                + (requestParametersJson == null ? "" : ",\"requestParameters\":" + requestParametersJson)
                + "}";
        given().contentType(ContentType.JSON)
                .body(integrationBody)
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/ANY/integration")
                .then().statusCode(201);

        String deploymentId = given().contentType(ContentType.JSON)
                .body("{\"description\":\"v1\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201).extract().path("id");
        given().contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);

        return apiId;
    }

    private String backendUri() {
        return "http://127.0.0.1:" + backendPort + "/{proxy}";
    }

    @AfterEach
    void cleanup() {
        for (String apiId : createdApis) {
            given().when().delete("/restapis/" + apiId).then().statusCode(202);
        }
        createdApis.clear();
    }

    @Test
    void forwardsPathAndQueryToTheBackendAndRelaysItsResponse() {
        resetRecordings();
        String apiId = createProxyApi("http-proxy-get-api", backendUri(), null);

        given()
                .when().get("/execute-api/" + apiId + "/test/orders/42?limit=5")
                .then().statusCode(200)
                .header("X-Backend-Marker", "hit")
                .body("from", org.hamcrest.Matchers.equalTo("backend"));

        assertEquals("GET", lastMethod.get());
        assertEquals("/orders/42", lastPath.get());
        assertEquals("limit=5", lastQuery.get());
    }

    @Test
    void forwardsRequestBodyOnPost() {
        resetRecordings();
        String apiId = createProxyApi("http-proxy-post-api", backendUri(), null);

        given()
                .contentType(ContentType.JSON)
                .body("{\"symbol\":\"ACME\"}")
                .when().post("/execute-api/" + apiId + "/test/orders")
                .then().statusCode(200);

        assertEquals("POST", lastMethod.get());
        assertEquals("/orders", lastPath.get());
        assertEquals("{\"symbol\":\"ACME\"}", lastBody.get());
    }

    @Test
    void relaysBackendErrorStatusVerbatim() {
        resetRecordings();
        String apiId = createProxyApi("http-proxy-error-api", backendUri(), null);

        // HTTP_PROXY performs no integration-response selection, so a backend 404 must reach the
        // caller as a 404 carrying the backend's own body — not be remapped to a gateway error.
        given()
                .when().get("/execute-api/" + apiId + "/test/orders/missing")
                .then().statusCode(404)
                .body("from", org.hamcrest.Matchers.equalTo("backend"));
    }

    @Test
    void unreachableBackendYields502() {
        String apiId = createProxyApi("http-proxy-dead-api", "http://127.0.0.1:" + deadPort + "/{proxy}", null);

        given()
                .when().get("/execute-api/" + apiId + "/test/anything")
                .then().statusCode(502);
    }

    @Test
    void appliesIntegrationRequestParameterMappingToOutgoingHeaders() {
        resetRecordings();
        String apiId = createProxyApi("http-proxy-params-api", backendUri(),
                "{\"integration.request.header.X-Forwarded-Tenant\":\"method.request.querystring.tenant\"}");

        given()
                .when().get("/execute-api/" + apiId + "/test/orders?tenant=acme")
                .then().statusCode(200);

        assertEquals("acme", lastForwardedHeader.get());
    }

    @Test
    void forwardsAuthorizationHeaderUnchangedOnNoneAuthRoute() {
        resetRecordings();
        String apiId = createProxyApi("http-proxy-auth-passthrough-api", backendUri(), null);

        // A NONE-auth route must not have SigV4 applied to it; the caller's Authorization header is
        // the backend's to interpret and has to arrive byte-for-byte.
        given()
                .header("Authorization", "Bearer caller-supplied-token")
                .when().get("/execute-api/" + apiId + "/test/orders")
                .then().statusCode(200);

        assertEquals("Bearer caller-supplied-token", lastAuthorization.get());
    }

    @Test
    void doesNotForwardTheInboundHostHeaderToTheBackend() {
        resetRecordings();
        String apiId = createProxyApi("http-proxy-host-api", backendUri(), null);

        given()
                .when().get("/execute-api/" + apiId + "/test/orders")
                .then().statusCode(200);

        // Host is hop-by-hop for this proxy: the backend must see its own authority, not the
        // gateway's, or virtual-hosted backends route the request to the wrong vhost.
        assertTrue(lastHost.get() != null && lastHost.get().contains(String.valueOf(backendPort)),
                "backend should see its own Host authority, saw: " + lastHost.get());
    }
}
