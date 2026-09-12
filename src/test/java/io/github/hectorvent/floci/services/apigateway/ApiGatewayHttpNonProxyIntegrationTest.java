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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers the REST (v1) {@code HTTP} integration type — the non-proxy form, where API Gateway builds
 * the backend request from mapping templates and explicit parameter mappings, then maps the
 * backend's response back through the method's integration responses.
 *
 * <p>This is the counterpart to {@link ApiGatewayHttpProxyIntegrationTest}: {@code HTTP_PROXY} is a
 * passthrough, whereas {@code HTTP} transforms in both directions. The distinguishing AWS behaviours
 * pinned here are (a) unmapped inbound headers are <em>not</em> forwarded, and (b) an integration
 * response's {@code selectionPattern} is matched against the backend's <em>HTTP status code</em>,
 * not against an error message as it is for {@code AWS}/Lambda integrations.
 */
@QuarkusTest
class ApiGatewayHttpNonProxyIntegrationTest {

    private static HttpServer backendServer;
    private static int backendPort;

    private static final AtomicReference<String> lastBody = new AtomicReference<>();
    private static final AtomicReference<String> lastQuery = new AtomicReference<>();
    private static final AtomicReference<String> lastTenantHeader = new AtomicReference<>();
    private static final AtomicReference<String> lastUnmappedHeader = new AtomicReference<>();
    private static final AtomicReference<String> lastMethod = new AtomicReference<>();
    private static final AtomicReference<String> lastContentType = new AtomicReference<>();

    private final List<String> createdApis = new ArrayList<>();

    @BeforeAll
    static void startBackend() throws IOException {
        backendServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backendServer.createContext("/", ApiGatewayHttpNonProxyIntegrationTest::handle);
        backendServer.start();
        backendPort = backendServer.getAddress().getPort();
    }

    @AfterAll
    static void stopBackend() {
        if (backendServer != null) backendServer.stop(0);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        lastMethod.set(exchange.getRequestMethod());
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        lastQuery.set(exchange.getRequestURI().getQuery());
        lastTenantHeader.set(exchange.getRequestHeaders().getFirst("X-Tenant"));
        lastUnmappedHeader.set(exchange.getRequestHeaders().getFirst("X-Client-Trace"));
        lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));

        String query = exchange.getRequestURI().getQuery();
        String mode = query == null ? "" : query;
        int status = mode.contains("mode=missing") ? 404 : mode.contains("mode=boom") ? 500 : 200;

        byte[] response = ("{\"backendStatus\":" + status + ",\"widget\":\"cog\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("X-Backend-Id", "backend-7");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void resetRecordings() {
        lastBody.set(null);
        lastQuery.set(null);
        lastTenantHeader.set(null);
        lastUnmappedHeader.set(null);
        lastMethod.set(null);
        lastContentType.set(null);
    }

    /**
     * Creates a REST API with a POST {@code /widget} method backed by an HTTP (non-proxy)
     * integration, wiring request/response templates, parameter mappings and integration responses.
     */
    private String createHttpApi(String name, String integrationExtras, boolean withIntegrationResponses) {
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
                .body("{\"pathPart\":\"widget\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201).extract().path("id");

        given().contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST")
                .then().statusCode(201);

        String backendUrl = "http://127.0.0.1:" + backendPort + "/widget";
        given().contentType(ContentType.JSON)
                .body("{\"type\":\"HTTP\",\"httpMethod\":\"POST\",\"uri\":\"" + backendUrl + "\""
                        + integrationExtras + "}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST/integration")
                .then().statusCode(201);

        if (withIntegrationResponses) {
            // Default (empty selectionPattern) → 200.
            given().contentType(ContentType.JSON)
                    .body("{\"selectionPattern\":\"\"}")
                    .when().put("/restapis/" + apiId + "/resources/" + resourceId
                            + "/methods/POST/integration/responses/200")
                    .then().statusCode(201);
            // Backend 404 → method 404.
            given().contentType(ContentType.JSON)
                    .body("{\"selectionPattern\":\"404\"}")
                    .when().put("/restapis/" + apiId + "/resources/" + resourceId
                            + "/methods/POST/integration/responses/404")
                    .then().statusCode(201);
            // Any backend 5xx → method 502.
            given().contentType(ContentType.JSON)
                    .body("{\"selectionPattern\":\"5\\\\d{2}\"}")
                    .when().put("/restapis/" + apiId + "/resources/" + resourceId
                            + "/methods/POST/integration/responses/502")
                    .then().statusCode(201);
        }

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

    @AfterEach
    void cleanup() {
        for (String apiId : createdApis) {
            given().when().delete("/restapis/" + apiId).then().statusCode(202);
        }
        createdApis.clear();
    }

    @Test
    void rendersRequestTemplateBeforeSendingToTheBackend() {
        resetRecordings();
        String apiId = createHttpApi("http-nonproxy-reqtemplate",
                ",\"requestTemplates\":{\"application/json\":"
                        + "\"{\\\"wrapped\\\":$input.json('$.value')}\"}", false);

        given().contentType(ContentType.JSON).body("{\"value\":42}")
                .when().post("/execute-api/" + apiId + "/test/widget")
                .then().statusCode(200);

        assertEquals("POST", lastMethod.get());
        assertEquals("{\"wrapped\":42}", lastBody.get());
    }

    @Test
    void forwardsOnlyExplicitlyMappedHeadersAndQueryParameters() {
        resetRecordings();
        String apiId = createHttpApi("http-nonproxy-params",
                ",\"requestParameters\":{"
                        + "\"integration.request.header.X-Tenant\":\"method.request.querystring.tenant\","
                        + "\"integration.request.querystring.mode\":\"method.request.querystring.mode\"}", false);

        given().contentType(ContentType.JSON).body("{}")
                .header("X-Client-Trace", "should-not-be-forwarded")
                .when().post("/execute-api/" + apiId + "/test/widget?tenant=acme&mode=ok")
                .then().statusCode(200);

        assertEquals("acme", lastTenantHeader.get());
        assertEquals("mode=ok", lastQuery.get());
        // A non-proxy HTTP integration builds its request from mappings alone; inbound headers that
        // were not mapped must not leak to the backend (that passthrough is HTTP_PROXY's job).
        assertNull(lastUnmappedHeader.get(),
                "unmapped inbound header leaked to backend: " + lastUnmappedHeader.get());
    }

    @Test
    void selectionPatternMatchesTheBackendStatusCode() {
        resetRecordings();
        String apiId = createHttpApi("http-nonproxy-selection",
                ",\"requestParameters\":{"
                        + "\"integration.request.querystring.mode\":\"method.request.querystring.mode\"}", true);

        // Backend 200 → default integration response → 200
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/execute-api/" + apiId + "/test/widget?mode=ok")
                .then().statusCode(200);

        // Backend 404 → selectionPattern "404" → 404
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/execute-api/" + apiId + "/test/widget?mode=missing")
                .then().statusCode(404);

        // Backend 500 → selectionPattern "5\d{2}" → remapped to 502
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/execute-api/" + apiId + "/test/widget?mode=boom")
                .then().statusCode(502);
    }

    @Test
    void appliesResponseTemplateToTheBackendBody() {
        resetRecordings();
        String apiId = createHttpApi("http-nonproxy-resptemplate", "", false);

        String resourceId = given().when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item.find { it.pathPart == 'widget' }.id");

        given().contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":"
                        + "\"{\\\"renamed\\\":$input.json('$.widget')}\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId
                        + "/methods/POST/integration/responses/200")
                .then().statusCode(201);

        given().contentType(ContentType.JSON).body("{}")
                .when().post("/execute-api/" + apiId + "/test/widget")
                .then().statusCode(200)
                .body("renamed", equalTo("cog"));
    }

    @Test
    void mapsBackendResponseHeaderToMethodResponseHeader() {
        resetRecordings();
        String apiId = createHttpApi("http-nonproxy-respparams", "", false);

        String resourceId = given().when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item.find { it.pathPart == 'widget' }.id");

        given().contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseParameters\":"
                        + "{\"method.response.header.X-Out\":\"integration.response.header.X-Backend-Id\"}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId
                        + "/methods/POST/integration/responses/200")
                .then().statusCode(201);

        given().contentType(ContentType.JSON).body("{}")
                .when().post("/execute-api/" + apiId + "/test/widget")
                .then().statusCode(200)
                .header("X-Out", "backend-7");
    }

    @Test
    void passthroughNeverRejectsAnUnmatchedContentType() {
        resetRecordings();
        String apiId = createHttpApi("http-nonproxy-passthrough",
                ",\"passthroughBehavior\":\"NEVER\","
                        + "\"requestTemplates\":{\"application/xml\":\"<x/>\"}", false);

        given().contentType(ContentType.JSON).body("{}")
                .when().post("/execute-api/" + apiId + "/test/widget")
                .then().statusCode(415);
    }

    @Test
    void tellsTheBackendTheMediaTypeOfTheTemplateItRendered() {
        resetRecordings();
        // The template is keyed under text/plain, so the rendered payload is text/plain — the
        // backend must be told that, not the JSON default.
        String apiId = createHttpApi("http-nonproxy-contenttype",
                ",\"requestTemplates\":{\"text/plain\":\"rendered-as-plain\"}", false);

        given().contentType("text/plain").body("ignored")
                .when().post("/execute-api/" + apiId + "/test/widget")
                .then().statusCode(200);

        assertEquals("rendered-as-plain", lastBody.get());
        assertEquals("text/plain", lastContentType.get());
    }

    @Test
    void selectsTheTemplateByBaseTypeWhenTheRequestCarriesACharset() {
        resetRecordings();
        String apiId = createHttpApi("http-nonproxy-charset",
                ",\"requestTemplates\":{\"application/json\":"
                        + "\"{\\\"wrapped\\\":$input.json('$.value')}\"}", false);

        given().contentType("application/json; charset=utf-8").body("{\"value\":7}")
                .when().post("/execute-api/" + apiId + "/test/widget")
                .then().statusCode(200);

        assertEquals("{\"wrapped\":7}", lastBody.get());
        assertEquals("application/json", lastContentType.get());
    }

    @Test
    void relaysBackendStatusWhenNoIntegrationResponsesAreConfigured() {
        resetRecordings();
        String apiId = createHttpApi("http-nonproxy-nointegresp",
                ",\"requestParameters\":{"
                        + "\"integration.request.querystring.mode\":\"method.request.querystring.mode\"}", false);

        given().contentType(ContentType.JSON).body("{}")
                .when().post("/execute-api/" + apiId + "/test/widget?mode=missing")
                .then().statusCode(404);
    }
}
