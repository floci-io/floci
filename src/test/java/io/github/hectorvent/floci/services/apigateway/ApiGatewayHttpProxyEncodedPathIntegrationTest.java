package io.github.hectorvent.floci.services.apigateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.apigatewayv2.HttpProxyApiFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A caller's percent-encoding has to reach an HTTP backend as the caller wrote it. JAX-RS hands the
 * controller a decoded path, and substituting that into an integration URI threw on a space, so
 * {@code /index/NIFTY%2050} answered 500 without reaching the backend, and turned an encoded
 * {@code ?} into the start of the backend's query string.
 */
@QuarkusTest
class ApiGatewayHttpProxyEncodedPathIntegrationTest {

    private static HttpServer backendServer;
    private static int backendPort;

    private static final AtomicReference<String> lastRawPath = new AtomicReference<>();
    private static final AtomicReference<String> lastRawQuery = new AtomicReference<>();

    @BeforeAll
    static void startBackend() throws IOException {
        backendServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backendServer.createContext("/", ApiGatewayHttpProxyEncodedPathIntegrationTest::handle);
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
        lastRawPath.set(exchange.getRequestURI().getRawPath());
        lastRawQuery.set(exchange.getRequestURI().getRawQuery());
        byte[] response = "{\"from\":\"backend\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    @BeforeEach
    void resetRecordings() {
        lastRawPath.set(null);
        lastRawQuery.set(null);
    }

    private String createRestApi(String type, String resourcePath, String targetPath, String requestParametersJson) {
        return createRestApi(type, resourcePath, "http://127.0.0.1:" + backendPort, targetPath, requestParametersJson);
    }

    private String createRestApi(String type, String resourcePath, String targetOrigin, String targetPath,
                                 String requestParametersJson) {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"encoded-path-api\"}")
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");

        String resourceId = given().when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        for (String pathPart : resourcePath.substring(1).split("/")) {
            resourceId = given()
                    .contentType(ContentType.JSON)
                    .body(Map.of("pathPart", pathPart))
                    .when().post("/restapis/" + apiId + "/resources/" + resourceId)
                    .then().statusCode(201).extract().path("id");
        }
        String method = "/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET";

        given().contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put(method)
                .then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body("{\"type\":\"" + type + "\",\"httpMethod\":\"GET\",\"uri\":\"" + targetOrigin + targetPath + "\""
                        + (requestParametersJson == null ? "" : ",\"requestParameters\":" + requestParametersJson)
                        + "}")
                .when().put(method + "/integration")
                .then().statusCode(201);
        if ("HTTP".equals(type)) {
            given().contentType(ContentType.JSON)
                    .body("{}")
                    .when().put(method + "/responses/200")
                    .then().statusCode(201);
            given().contentType(ContentType.JSON)
                    .body("{}")
                    .when().put(method + "/integration/responses/200")
                    .then().statusCode(201);
        }

        String deploymentId = given().contentType(ContentType.JSON)
                .body("{}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201).extract().path("id");
        given().contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);
        return apiId;
    }

    private static void getExpecting200(String path) {
        given().urlEncodingEnabled(false)
                .when().get(path)
                .then().statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "NIFTY%2050/constituents", "x%25y", "trailing%25", "a%3Fb", "a%23b", "a%2Fb", "a%252Fb", "a+b", "caf%C3%A9"
    })
    void restGreedyParameterKeepsTheCallersEncoding(String suffix) {
        String apiId = createRestApi("HTTP_PROXY", "/index/{proxy+}", "/backend/{proxy}", null);

        getExpecting200("/execute-api/" + apiId + "/test/index/" + suffix);

        assertEquals("/backend/" + suffix, lastRawPath.get());
        assertNull(lastRawQuery.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "x%25y", "trailing%25", "a%252Fb", "a%2Fb", "a%20b", "a%3Fb%23c", "caf%C3%A9"})
    void restRoutedHostsKeepTheCallersEncoding(String suffix) {
        String apiId = createRestApi("HTTP_PROXY", "/index/{proxy+}", "/backend/{proxy}", null);
        String domain = createCustomDomain(apiId);

        try {
            given().contentType(ContentType.JSON)
                    .body(Map.of("restApiId", apiId, "stage", "test", "basePath", "public"))
                    .when().post("/domainnames/" + domain + "/basepathmappings").then().statusCode(201);
            assertRoutedHostsForwardPath(apiId, domain, suffix, "/backend/" + suffix);
        } finally {
            given().delete("/domainnames/" + domain).then().statusCode(202);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "x%25y", "trailing%25", "a%252Fb", "a%2Fb", "a%20b", "a%3Fb%23c", "caf%C3%A9"})
    void httpApiRoutedHostsKeepTheCallersEncoding(String suffix) {
        String apiId = HttpProxyApiFixtures.createProxyApi("encoded-host-http-api", "", "test", backendPort,
                "/index/{proxy+}");
        String domain = createCustomDomain(apiId);

        try {
            given().contentType(ContentType.JSON)
                    .body(Map.of("apiId", apiId, "stage", "test", "apiMappingKey", "public"))
                    .when().post("/v2/domainnames/" + domain + "/apimappings").then().statusCode(201);
            assertRoutedHostsForwardPath(apiId, domain, suffix, "/index/" + suffix);
        } finally {
            given().delete("/domainnames/" + domain).then().statusCode(202);
        }
    }

    private static String createCustomDomain(String apiId) {
        String domain = apiId + ".example.test";
        given().contentType(ContentType.JSON)
                .body(Map.of("domainName", domain, "endpointConfiguration", Map.of("types", List.of("REGIONAL"))))
                .when().post("/domainnames").then().statusCode(201);
        return domain;
    }

    private static void assertRoutedHostsForwardPath(String apiId, String domain, String suffix, String expectedPath) {
        given().urlEncodingEnabled(false).header("Host", domain)
                .when().get("/public/index/" + suffix)
                .then().statusCode(200);
        assertEquals(expectedPath, lastRawPath.get());

        lastRawPath.set(null);
        given().urlEncodingEnabled(false).header("Host", apiId + ".execute-api.localhost.floci.io")
                .when().get("/test/index/" + suffix)
                .then().statusCode(200);
        assertEquals(expectedPath, lastRawPath.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/execute-api/{apiId}/test/index/NIFTY%2050",
            "/_aws/execute-api/{apiId}/test/index/NIFTY%2050",
            "/restapis/{apiId}/test/_user_request_/index/NIFTY%2050"
    })
    void everyExecuteApiUrlFormKeepsTheCallersEncoding(String urlTemplate) {
        String apiId = createRestApi("HTTP_PROXY", "/index/{proxy+}", "/backend/{proxy}", null);

        getExpecting200(urlTemplate.replace("{apiId}", apiId));

        assertEquals("/backend/NIFTY%2050", lastRawPath.get());
    }

    @Test
    void restNamedParameterMappedFromMethodRequestKeepsTheCallersEncoding() {
        String apiId = createRestApi("HTTP_PROXY", "/index/{name}", "/backend/{target}",
                "{\"integration.request.path.target\":\"method.request.path.name\"}");

        getExpecting200("/execute-api/" + apiId + "/test/index/NIFTY%2050");

        assertEquals("/backend/NIFTY%2050", lastRawPath.get());
    }

    @Test
    void restRequestWithQueryKeepsTheEncodedPathAndForwardsTheQuery() {
        String apiId = createRestApi("HTTP_PROXY", "/index/{proxy+}", "/backend/{proxy}", null);

        getExpecting200("/execute-api/" + apiId + "/test/index/NIFTY%2050/price/history?range=1d");

        assertEquals("/backend/NIFTY%2050/price/history", lastRawPath.get());
        assertEquals("range=1d", lastRawQuery.get());
    }

    @Test
    void restNamedBackendHostKeepsTheEncodedPath() {
        String apiId = createRestApi("HTTP_PROXY", "/index/{proxy+}", "http://localhost:" + backendPort,
                "/backend/{proxy}", null);

        getExpecting200("/execute-api/" + apiId + "/test/index/NIFTY%2050");

        assertEquals("/backend/NIFTY%2050", lastRawPath.get());
    }

    @Test
    void restPathValueMappedFromAHeaderIsEncoded() {
        String apiId = createRestApi("HTTP_PROXY", "/index", "/backend/{tenant}",
                "{\"integration.request.path.tenant\":\"method.request.header.X-Tenant\"}");

        given().header("X-Tenant", "acme corp?x")
                .when().get("/execute-api/" + apiId + "/test/index")
                .then().statusCode(200);

        assertEquals("/backend/acme%20corp%3Fx", lastRawPath.get());
        assertNull(lastRawQuery.get());
    }

    @Test
    void restTrailingSlashDoesNotLoseTheNamedParameter() {
        String apiId = createRestApi("HTTP_PROXY", "/index/{name}", "/backend/{name}", null);

        getExpecting200("/execute-api/" + apiId + "/test/index/NIFTY%2050/");

        assertEquals("/backend/NIFTY%2050", lastRawPath.get());
    }

    @Test
    void restNonProxyIntegrationKeepsTheCallersEncoding() {
        String apiId = createRestApi("HTTP", "/index/{name}", "/backend/{name}", null);

        getExpecting200("/execute-api/" + apiId + "/test/index/NIFTY%2050");

        assertEquals("/backend/NIFTY%2050", lastRawPath.get());
    }

    @Test
    void restEncodedSlashBeforeTheGreedyParameterKeepsItsWholeValue() {
        String apiId = createRestApi("HTTP_PROXY", "/a/b/{proxy+}", "/backend/{proxy}", null);

        getExpecting200("/execute-api/" + apiId + "/test/a%2Fb/c/d");

        assertEquals("/backend/c/d", lastRawPath.get());
    }

    @Test
    void restEncodedSlashAheadOfOnlyParametersKeepsTheirDecodedValues() {
        String apiId = createRestApi("HTTP_PROXY", "/{x}/{y}/{z+}", "/backend/{y}/{z}", null);

        getExpecting200("/execute-api/" + apiId + "/test/a%2Fb/c/d");

        assertEquals("/backend/b/c/d", lastRawPath.get());
    }

    @Test
    void restEncodedSlashInsideTheGreedyParameterAfterLiteralsIsKept() {
        String apiId = createRestApi("HTTP_PROXY", "/a/b/{proxy+}", "/backend/{proxy}", null);

        getExpecting200("/execute-api/" + apiId + "/test/a/b/c%2Fd");

        assertEquals("/backend/c%2Fd", lastRawPath.get());
    }

    @Test
    void restIntegrationUriThatCannotBeParsedYields502() {
        String apiId = createRestApi("HTTP_PROXY", "/index/{proxy+}", "/not a uri/{proxy}", null);

        given().when().get("/execute-api/" + apiId + "/test/index/x")
                .then().statusCode(502);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NIFTY%2050/constituents", "a%3Fb", "a%2Fb"})
    void httpApiGreedyParameterKeepsTheCallersEncoding(String suffix) {
        String apiId = HttpProxyApiFixtures.createProxyApi("encoded-path-http-api", "", "test", backendPort,
                "/index/{proxy+}");

        getExpecting200("/execute-api/" + apiId + "/test/index/" + suffix);

        assertEquals("/index/" + suffix, lastRawPath.get());
        assertNull(lastRawQuery.get());
    }

    @Test
    void httpApiNamedParameterKeepsTheCallersEncoding() {
        String apiId = HttpProxyApiFixtures.createProxyApi("encoded-path-http-api", "", "test", backendPort,
                "/index/{name}");

        getExpecting200("/execute-api/" + apiId + "/test/index/NIFTY%2050");

        assertEquals("/index/NIFTY%2050", lastRawPath.get());
    }
}
