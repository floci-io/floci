package io.github.hectorvent.floci.services.cloudfront;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Distributions whose origin is an execute-api endpoint of an API that exists in this Floci, answered
 * by Floci itself. The only backend is a stub on 127.0.0.1, and no private origin host is allowed here.
 */
@QuarkusTest
class CloudFrontExecuteApiOriginServingTest {

    private static final String CACHING_DISABLED = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad";
    private static final String REGION = "us-east-1";
    private static final String STAGE = "dev";
    private static final String OTHER_ACCOUNT_AUTHORIZATION =
            "AWS4-HMAC-SHA256 Credential=111122223333/20261027/" + REGION + "/apigateway/aws4_request";

    @Inject
    CloudFrontService cloudFrontService;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private HttpServer backend;

    @AfterEach
    void stopBackend() {
        if (backend != null) {
            backend.stop(0);
        }
    }

    @Test
    void anHttpApiOriginIsServedWithItsStatusBodyAndHeadersAtTheStageOfTheOriginPath() throws IOException {
        String apiId = createHttpApi();
        Distribution created = createDistribution(executeApiDomain(apiId), "/" + STAGE,
                CloudFrontService.MANAGED_ALL_VIEWER_EXCEPT_HOST_HEADER_ORIGIN_REQUEST_POLICY_ID);

        given().header("Host", created.getDomainName())
                .when().get("/accounts?tenant=alpha")
                .then().statusCode(200)
                .header("X-Backend", "stub")
                .body("path", equalTo("/backend"))
                .body("query", equalTo("tenant=alpha"));
    }

    @Test
    void aRestApiOriginIsServedAtTheStageOfTheOriginPath() {
        String apiId = createRestApi();
        Distribution created = createDistribution(executeApiDomain(apiId), "/" + STAGE,
                CloudFrontService.MANAGED_ALL_VIEWER_EXCEPT_HOST_HEADER_ORIGIN_REQUEST_POLICY_ID);

        given().header("Host", created.getDomainName())
                .when().get("/")
                .then().statusCode(200)
                .body("from", equalTo("rest"));
    }

    @Test
    void anExecuteApiOriginOfAnApiThatDoesNotExistIsNotServedInProcess() {
        Distribution created = createDistribution("nosuchapi01.execute-api." + REGION + ".localhost", "",
                CloudFrontService.MANAGED_ALL_VIEWER_EXCEPT_HOST_HEADER_ORIGIN_REQUEST_POLICY_ID);

        given().header("Host", created.getDomainName())
                .when().get("/accounts")
                .then().statusCode(502);
    }

    @Test
    void aPrivateOriginThatIsNotAnExecuteApiEndpointIsStillRefused() throws IOException {
        startBackend();
        Distribution created = createDistribution("127.0.0.1", "",
                CloudFrontService.MANAGED_ALL_VIEWER_EXCEPT_HOST_HEADER_ORIGIN_REQUEST_POLICY_ID,
                backend.getAddress().getPort());

        given().header("Host", created.getDomainName())
                .when().get("/accounts")
                .then().statusCode(502);
    }

    @Test
    void forwardingTheViewerHostToAnExecuteApiOriginIsABadGateway() throws IOException {
        String apiId = createHttpApi();
        Distribution created = createDistribution(executeApiDomain(apiId), "/" + STAGE,
                CloudFrontService.MANAGED_ALL_VIEWER_ORIGIN_REQUEST_POLICY_ID);

        given().header("Host", created.getDomainName())
                .when().get("/accounts")
                .then().statusCode(502);
    }

    @Test
    void aPostReachesTheApiIntegrationWithItsMethodAndBody() throws IOException {
        String apiId = createHttpApi();
        Distribution created = createDistribution(executeApiDomain(apiId), "/" + STAGE,
                CloudFrontService.MANAGED_ALL_VIEWER_EXCEPT_HOST_HEADER_ORIGIN_REQUEST_POLICY_ID);

        given().header("Host", created.getDomainName()).contentType(ContentType.TEXT).body("ping")
                .when().post("/accounts")
                .then().statusCode(200)
                .body("method", equalTo("POST"))
                .body("body", equalTo("ping"));
    }

    @Test
    void theStageIsTakenFromTheViewerPathWhenTheOriginPathIsEmpty() throws IOException {
        String apiId = createHttpApi();
        Distribution created = createDistribution(executeApiDomain(apiId), "",
                CloudFrontService.MANAGED_ALL_VIEWER_EXCEPT_HOST_HEADER_ORIGIN_REQUEST_POLICY_ID);

        given().header("Host", created.getDomainName())
                .when().get("/" + STAGE + "/accounts")
                .then().statusCode(200)
                .body("path", equalTo("/backend"));
    }

    @Test
    void aWebSocketApiOriginNeverExposesFlociManagementOutput() {
        String apiId = given().contentType(ContentType.JSON)
                .body(Map.of("name", "cf-ws", "protocolType", "WEBSOCKET",
                        "routeSelectionExpression", "$request.body.action"))
                .when().post("/v2/apis").then().statusCode(201).extract().path("apiId");
        cleanup.register(() -> given().when().delete("/v2/apis/" + apiId));
        Distribution created = createDistribution(executeApiDomain(apiId), "",
                CloudFrontService.MANAGED_ALL_VIEWER_EXCEPT_HOST_HEADER_ORIGIN_REQUEST_POLICY_ID);

        given().header("Host", created.getDomainName())
                .when().get("/v2/apis")
                .then().statusCode(404)
                .body("message", equalTo("Not Found"));
    }

    @Test
    void anHttpApiOriginWithoutTheRequestedStageNeverExposesFlociManagementOutput() throws IOException {
        String apiId = createHttpApi();
        Distribution created = createDistribution(executeApiDomain(apiId), "/nostage",
                CloudFrontService.MANAGED_ALL_VIEWER_EXCEPT_HOST_HEADER_ORIGIN_REQUEST_POLICY_ID);

        given().header("Host", created.getDomainName())
                .when().get("/v2/apis")
                .then().statusCode(404)
                .body("message", equalTo("Not Found"));
    }

    @Test
    void aViewerPathIntoTheInternalExecuteApiRouteIsNotServed() throws IOException {
        String apiId = createHttpApi();
        Distribution created = createDistribution(executeApiDomain(apiId), "",
                CloudFrontService.MANAGED_ALL_VIEWER_EXCEPT_HOST_HEADER_ORIGIN_REQUEST_POLICY_ID);

        given().header("Host", created.getDomainName())
                .when().get("/execute-api/" + apiId + "/" + STAGE + "/accounts")
                .then().statusCode(404);
    }

    @Test
    void anExecuteApiDomainWithAnotherRegionThanTheApiIsNotServed() throws IOException {
        String apiId = createHttpApi();
        Distribution created = createDistribution(apiId + ".execute-api.eu-west-1.amazonaws.com", "/" + STAGE,
                CloudFrontService.MANAGED_ALL_VIEWER_EXCEPT_HOST_HEADER_ORIGIN_REQUEST_POLICY_ID);

        given().header("Host", created.getDomainName())
                .when().get("/accounts")
                .then().statusCode(404);
    }

    @Test
    void anHttpApiOfAnotherAccountThanTheViewerIsServed() throws IOException {
        String apiId = createHttpApi(OTHER_ACCOUNT_AUTHORIZATION);
        Distribution created = createDistribution(executeApiDomain(apiId), "/" + STAGE,
                CloudFrontService.MANAGED_ALL_VIEWER_EXCEPT_HOST_HEADER_ORIGIN_REQUEST_POLICY_ID);

        given().header("Host", created.getDomainName())
                .when().get("/accounts")
                .then().statusCode(200)
                .body("path", equalTo("/backend"));
    }

    @Test
    void aRestApiWithAnotherRegionLabelThanTheApiIsNotServed() {
        String apiId = createRestApi();
        Distribution created = createDistribution(apiId + ".execute-api.eu-west-1.amazonaws.com", "/" + STAGE,
                CloudFrontService.MANAGED_ALL_VIEWER_EXCEPT_HOST_HEADER_ORIGIN_REQUEST_POLICY_ID);

        given().header("Host", created.getDomainName())
                .when().get("/")
                .then().statusCode(404)
                .body("message", equalTo("Not Found"));
    }

    @Test
    void dotSegmentsInTheViewerPathCannotLeaveTheApiStage() throws IOException {
        String apiId = createHttpApi();
        Distribution created = createDistribution(executeApiDomain(apiId), "",
                CloudFrontService.MANAGED_ALL_VIEWER_EXCEPT_HOST_HEADER_ORIGIN_REQUEST_POLICY_ID);

        // A raw socket, because the test HTTP client would resolve the dot segments itself.
        try (Socket socket = new Socket("127.0.0.1", RestAssured.port)) {
            socket.getOutputStream().write(("GET /" + STAGE + "/../../../v2/apis HTTP/1.1\r\nHost: "
                    + created.getDomainName() + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            assertThat(response.lines().findFirst().orElse(""), equalTo("HTTP/1.1 404 Not Found"));
            assertThat(response, not(containsString("\"items\"")));
        }
    }

    private static String executeApiDomain(String apiId) {
        return apiId + ".execute-api." + REGION + ".amazonaws.com";
    }

    private void startBackend() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            String sentBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] response = "{\"path\":\"%s\",\"query\":\"%s\",\"method\":\"%s\",\"body\":\"%s\"}"
                    .formatted(exchange.getRequestURI().getRawPath(), query == null ? "" : query,
                            exchange.getRequestMethod(), sentBody)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-Backend", "stub");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        backend.start();
    }

    private String createHttpApi() throws IOException {
        return createHttpApi(null);
    }

    private String createHttpApi(String authorization) throws IOException {
        startBackend();
        String apiId = asOwner(authorization).contentType(ContentType.JSON)
                .body(Map.of("name", "cf-execute-api", "protocolType", "HTTP"))
                .when().post("/v2/apis")
                .then().statusCode(201).extract().path("apiId");
        cleanup.register(() -> asOwner(authorization).when().delete("/v2/apis/" + apiId));
        asOwner(authorization).contentType(ContentType.JSON).body(Map.of("stageName", STAGE))
                .when().post("/v2/apis/" + apiId + "/stages").then().statusCode(201);
        String integrationId = asOwner(authorization).contentType(ContentType.JSON)
                .body(Map.of("integrationType", "HTTP_PROXY", "integrationMethod", "GET",
                        "integrationUri", "http://127.0.0.1:" + backend.getAddress().getPort() + "/backend",
                        "payloadFormatVersion", "1.0"))
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then().statusCode(201).extract().path("integrationId");
        asOwner(authorization).contentType(ContentType.JSON)
                .body(Map.of("routeKey", "GET /accounts", "target", "integrations/" + integrationId))
                .when().post("/v2/apis/" + apiId + "/routes").then().statusCode(201);
        String postIntegrationId = asOwner(authorization).contentType(ContentType.JSON)
                .body(Map.of("integrationType", "HTTP_PROXY", "integrationMethod", "POST",
                        "integrationUri", "http://127.0.0.1:" + backend.getAddress().getPort() + "/backend",
                        "payloadFormatVersion", "1.0"))
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then().statusCode(201).extract().path("integrationId");
        asOwner(authorization).contentType(ContentType.JSON)
                .body(Map.of("routeKey", "POST /accounts", "target", "integrations/" + postIntegrationId))
                .when().post("/v2/apis/" + apiId + "/routes").then().statusCode(201);
        return apiId;
    }

    private static RequestSpecification asOwner(String authorization) {
        return authorization == null ? given() : given().header("Authorization", authorization);
    }

    private String createRestApi() {
        String apiId = given().contentType(ContentType.JSON).body(Map.of("name", "cf-execute-api-rest"))
                .when().post("/restapis").then().statusCode(201).extract().path("id");
        cleanup.register(() -> given().when().delete("/restapis/" + apiId));
        String rootId = given().when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        String method = "/restapis/" + apiId + "/resources/" + rootId + "/methods/GET";
        given().contentType(ContentType.JSON).body(Map.of("authorizationType", "NONE"))
                .when().put(method).then().statusCode(201);
        given().contentType(ContentType.JSON).body(Map.of())
                .when().put(method + "/responses/200").then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body(Map.of("type", "MOCK", "requestTemplates", Map.of("application/json", "{\"statusCode\": 200}")))
                .when().put(method + "/integration").then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body(Map.of("selectionPattern", "", "responseTemplates",
                        Map.of("application/json", "{\"from\":\"rest\"}")))
                .when().put(method + "/integration/responses/200").then().statusCode(201);
        given().contentType(ContentType.JSON).body(Map.of("stageName", STAGE))
                .when().post("/restapis/" + apiId + "/deployments").then().statusCode(201);
        return apiId;
    }

    private Distribution createDistribution(String domain, String originPath, String originRequestPolicyId) {
        return createDistribution(domain, originPath, originRequestPolicyId, 443);
    }

    private Distribution createDistribution(String domain, String originPath, String originRequestPolicyId,
                                            int originPort) {
        Origin origin = new Origin();
        origin.setId("api");
        origin.setDomainName(domain);
        origin.setOriginPath(originPath);
        Map<String, Object> customOriginConfig = new LinkedHashMap<>();
        customOriginConfig.put("HTTPPort", String.valueOf(originPort));
        customOriginConfig.put("HTTPSPort", String.valueOf(originPort));
        customOriginConfig.put("OriginProtocolPolicy", originPort == 443 ? "https-only" : "http-only");
        origin.setCustomOriginConfig(customOriginConfig);
        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId("api");
        behavior.setViewerProtocolPolicy("allow-all");
        behavior.setAllowedMethods(List.of("GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"));
        behavior.setCachePolicyId(CACHING_DISABLED);
        behavior.setOriginRequestPolicyId(originRequestPolicyId);
        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setOrigins(List.of(origin));
        config.setDefaultCacheBehavior(behavior);
        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        Distribution created = cloudFrontService.createDistribution(distribution, Map.of());
        cleanup.register(() -> cloudFrontService.removeDistribution(created.getId()));
        return created;
    }
}
