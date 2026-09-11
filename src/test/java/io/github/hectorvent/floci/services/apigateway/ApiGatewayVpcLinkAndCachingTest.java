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
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the REST (v1) VPC Link operations and stage-level response caching, both previously absent.
 *
 * <p>Floci has no real VPC, so a VPC_LINK integration routes straight to its URI — what is enforced
 * is the misconfiguration AWS also rejects: naming a link that does not exist.
 *
 * <p>Caching follows AWS's two-level switch: the stage needs a cache cluster and the method needs
 * caching enabled, and the entry is keyed by the integration's {@code cacheKeyParameters}.
 */
@QuarkusTest
class ApiGatewayVpcLinkAndCachingTest {

    private static HttpServer backend;
    private static int backendPort;
    /** Counts backend hits, so a cache hit is observable as the absence of one. */
    private static final AtomicInteger backendHits = new AtomicInteger();

    private final List<String> createdApis = new ArrayList<>();
    private final List<String> createdLinks = new ArrayList<>();

    @BeforeAll
    static void startBackend() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", ApiGatewayVpcLinkAndCachingTest::handle);
        backend.start();
        backendPort = backend.getAddress().getPort();
    }

    @AfterAll
    static void stopBackend() {
        if (backend != null) backend.stop(0);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        int hit = backendHits.incrementAndGet();
        byte[] response = ("{\"hit\":" + hit + "}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    @AfterEach
    void cleanup() {
        for (String apiId : createdApis) {
            given().when().delete("/restapis/" + apiId).then().statusCode(202);
        }
        createdApis.clear();
        for (String linkId : createdLinks) {
            given().when().delete("/vpclinks/" + linkId);
        }
        createdLinks.clear();
    }

    private String createVpcLink(String name) {
        String id = given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\",\"targetArns\":"
                        + "[\"arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/net/nlb/abc\"]}")
                .when().post("/vpclinks")
                .then().statusCode(202).body("id", notNullValue())
                .extract().path("id");
        createdLinks.add(id);
        return id;
    }

    /** A deployed API with a GET /widget method; caller supplies integration and stage extras. */
    private String createApi(String name, String integrationExtras, String stageExtras) {
        String apiId = given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\"}")
                .when().post("/restapis").then().statusCode(201).extract().path("id");
        createdApis.add(apiId);

        String rootId = given().when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        String resourceId = given().contentType(ContentType.JSON)
                .body("{\"pathPart\":\"widget\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201).extract().path("id");

        given().contentType(ContentType.JSON).body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET")
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"type\":\"HTTP_PROXY\",\"httpMethod\":\"GET\","
                        + "\"uri\":\"http://127.0.0.1:" + backendPort + "/widget\"" + integrationExtras + "}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration")
                .then().statusCode(201);

        String deploymentId = given().contentType(ContentType.JSON).body("{}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201).extract().path("id");
        given().contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\",\"deploymentId\":\"" + deploymentId + "\"" + stageExtras + "}")
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);

        return apiId;
    }

    /** Turns on caching for GET /widget on the stage. */
    private void enableMethodCaching(String apiId, int ttlSeconds) {
        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":["
                        + "{\"op\":\"replace\",\"path\":\"/~1widget/GET/caching/enabled\",\"value\":\"true\"},"
                        + "{\"op\":\"replace\",\"path\":\"/~1widget/GET/caching/ttlInSeconds\",\"value\":\""
                        + ttlSeconds + "\"}]}")
                .when().patch("/restapis/" + apiId + "/stages/test")
                .then().statusCode(200);
    }

    @Test
    void vpcLinkCrudRoundTrips() {
        String linkId = createVpcLink("crud-link");

        given().when().get("/vpclinks/" + linkId)
                .then().statusCode(200)
                .body("name", equalTo("crud-link"))
                .body("status", equalTo("AVAILABLE"))
                .body("targetArns", contains(
                        "arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/net/nlb/abc"));

        given().when().get("/vpclinks").then().statusCode(200).body("item.id", contains(linkId));

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/name\",\"value\":\"renamed\"}]}")
                .when().patch("/vpclinks/" + linkId)
                .then().statusCode(200).body("name", equalTo("renamed"));

        given().when().delete("/vpclinks/" + linkId).then().statusCode(202);
        given().when().get("/vpclinks/" + linkId).then().statusCode(404);
        createdLinks.remove(linkId);
    }

    @Test
    void createVpcLinkRequiresATargetArn() {
        given().contentType(ContentType.JSON).body("{\"name\":\"no-targets\"}")
                .when().post("/vpclinks").then().statusCode(400);
    }

    @Test
    void vpcLinkIntegrationRoutesWhenTheLinkExists() {
        String linkId = createVpcLink("routing-link");
        String apiId = createApi("vpclink-ok",
                ",\"connectionType\":\"VPC_LINK\",\"connectionId\":\"" + linkId + "\"", "");

        given().when().get("/execute-api/" + apiId + "/test/widget").then().statusCode(200);
    }

    @Test
    void vpcLinkIntegrationIsRejectedWhenTheLinkIsUnknown() {
        String apiId = createApi("vpclink-missing",
                ",\"connectionType\":\"VPC_LINK\",\"connectionId\":\"does-not-exist\"", "");

        given().when().get("/execute-api/" + apiId + "/test/widget").then().statusCode(502);
    }

    @Test
    void cachingServesTheSecondRequestWithoutHittingTheBackend() {
        backendHits.set(0);
        String apiId = createApi("cache-on", "", ",\"cacheClusterEnabled\":true");
        enableMethodCaching(apiId, 300);

        given().when().get("/execute-api/" + apiId + "/test/widget")
                .then().statusCode(200).body("hit", equalTo(1));
        given().when().get("/execute-api/" + apiId + "/test/widget")
                .then().statusCode(200).body("hit", equalTo(1));

        assertEquals(1, backendHits.get(), "second request should have been served from the cache");
    }

    @Test
    void cachingIsOffWhenTheStageHasNoCacheCluster() {
        backendHits.set(0);
        // Method-level caching alone is not enough — AWS needs the stage's cache cluster too.
        String apiId = createApi("cache-no-cluster", "", "");
        enableMethodCaching(apiId, 300);

        given().when().get("/execute-api/" + apiId + "/test/widget").then().statusCode(200);
        given().when().get("/execute-api/" + apiId + "/test/widget").then().statusCode(200);

        assertEquals(2, backendHits.get(), "without a cache cluster every request reaches the backend");
    }

    @Test
    void cacheKeyParametersSeparateEntries() {
        backendHits.set(0);
        String apiId = createApi("cache-keys",
                ",\"cacheKeyParameters\":[\"method.request.querystring.tenant\"]",
                ",\"cacheClusterEnabled\":true");
        enableMethodCaching(apiId, 300);

        given().when().get("/execute-api/" + apiId + "/test/widget?tenant=a").then().statusCode(200);
        given().when().get("/execute-api/" + apiId + "/test/widget?tenant=a").then().statusCode(200);
        // A different value for a declared cache key parameter is a different entry.
        given().when().get("/execute-api/" + apiId + "/test/widget?tenant=b").then().statusCode(200);

        assertEquals(2, backendHits.get(), "tenant=a should be cached, tenant=b should miss");
    }

    @Test
    void stageReportsItsCacheClusterState() {
        String apiId = createApi("cache-reported", "", ",\"cacheClusterEnabled\":true");

        given().when().get("/restapis/" + apiId + "/stages/test")
                .then().statusCode(200)
                .body("cacheClusterEnabled", equalTo(true))
                .body("cacheClusterStatus", equalTo("AVAILABLE"));
    }
}
