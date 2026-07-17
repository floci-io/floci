package io.github.hectorvent.floci.services.cloudfront;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.cloudfront.model.ResponseHeadersPolicy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(CloudFrontCustomOriginServingTest.PrivateOriginProfile.class)
class CloudFrontCustomOriginServingTest {

    @Inject
    CloudFrontService cloudFrontService;

    private HttpServer originServer;

    @AfterEach
    void stopOrigin() {
        if (originServer != null) {
            originServer.stop(0);
        }
    }

    @Test
    void forwardsRawQueryResponseHeadersAndHeadMetadataToAllowlistedOrigin() throws Exception {
        AtomicReference<String> receivedQuery = new AtomicReference<>();
        originServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        originServer.createContext("/", exchange -> respond(exchange, receivedQuery));
        originServer.start();

        Origin customOrigin = new Origin();
        customOrigin.setId("custom-origin");
        // The embedded port is deliberately wrong. CustomOriginConfig.HTTPPort is authoritative.
        customOrigin.setDomainName("127.0.0.1:1");
        customOrigin.setCustomOriginConfig(customOriginConfig(originServer.getAddress().getPort()));

        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setOrigins(List.of(customOrigin));
        config.setDefaultCacheBehavior(defaultBehavior("custom-origin"));

        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        Distribution created = cloudFrontService.createDistribution(distribution, Map.of());

        given()
            .urlEncodingEnabled(false)
            .header("Host", created.getDomainName())
            .header("Origin", "https://viewer.example")
        .when()
            .get("/resource?x=1&x=2&encoded=a%2Fb")
        .then()
            .statusCode(200)
            .body(equalTo("origin-body"))
            .header("Access-Control-Allow-Origin", equalTo("https://viewer.example"))
            .header("Cache-Control", equalTo("public, max-age=60"))
            .header("ETag", equalTo("\"origin-etag\""))
            .header("X-Origin-Header", equalTo("preserved"));

        assertEquals("x=1&x=2&encoded=a%2Fb", receivedQuery.get());

        given()
            .header("Host", created.getDomainName())
        .when()
            .head("/resource")
        .then()
            .statusCode(200)
            .header("Content-Length", equalTo("123"))
            .body(equalTo(""));
    }

    @Test
    void responseHeadersPolicyRemovesOverridesAndPreservesOriginHeaderValues() throws Exception {
        originServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        originServer.createContext("/", CloudFrontCustomOriginServingTest::respondWithPolicyHeaders);
        originServer.start();

        Map<String, Object> policyConfig = new LinkedHashMap<>();
        policyConfig.put("RemoveHeadersConfig", List.of("X-Remove", "X-Readd", "Server", "Date"));
        policyConfig.put("CustomHeadersConfig", List.of(
                policyHeader("X-Readd", "policy-readded", false),
                policyHeader("X-Override", "policy-override", true),
                policyHeader("X-Preserve", "policy-ignored", false),
                policyHeader("X-New", "policy-new", false),
                policyHeader("X-Hop", "must-not-return", true)));
        policyConfig.put("CorsConfig", new LinkedHashMap<>(Map.of(
                "AccessControlAllowCredentials", "false",
                "AccessControlAllowHeaders", List.of(),
                "AccessControlAllowOrigins", List.of("https://viewer.example"),
                "AccessControlAllowMethods", List.of("GET"),
                "OriginOverride", "false")));
        ResponseHeadersPolicy policy = new ResponseHeadersPolicy();
        policy.setName("custom-origin-policy-" + System.nanoTime());
        policy.setConfig(policyConfig);
        ResponseHeadersPolicy createdPolicy = cloudFrontService.createResponseHeadersPolicy(policy);

        Origin customOrigin = new Origin();
        customOrigin.setId("custom-origin");
        customOrigin.setDomainName("127.0.0.1");
        customOrigin.setCustomOriginConfig(customOriginConfig(originServer.getAddress().getPort()));

        DefaultCacheBehavior behavior = defaultBehavior("custom-origin");
        behavior.setResponseHeadersPolicyId(createdPolicy.getId());
        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setOrigins(List.of(customOrigin));
        config.setDefaultCacheBehavior(behavior);
        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        Distribution created = cloudFrontService.createDistribution(distribution, Map.of());

        var response = given()
                .header("Host", created.getDomainName())
                .header("Origin", "https://viewer.example")
                .when().get("/resource")
                .then().statusCode(200)
                .extract().response();

        assertEquals("policy-readded", response.getHeader("X-Readd"));
        assertEquals("policy-override", response.getHeader("X-Override"));
        assertEquals("origin-preserve", response.getHeader("X-Preserve"));
        assertEquals("policy-new", response.getHeader("X-New"));
        assertEquals("https://origin.example", response.getHeader("Access-Control-Allow-Origin"));
        assertNull(response.getHeader("X-Remove"));
        assertNull(response.getHeader("X-Hop"));
        assertNull(response.getHeader("Connection"));
        assertEquals("CloudFront", response.getHeader("Server"));
        assertNotEquals("Wed, 01 Jan 2020 00:00:00 GMT", response.getHeader("Date"));
        List<String> cookies = response.getHeaders().getValues("Set-Cookie");
        assertEquals(2, cookies.size());
        assertTrue(cookies.contains("session=a"), cookies.toString());
        assertTrue(cookies.contains("preference=b"), cookies.toString());
    }

    private static void respond(HttpExchange exchange, AtomicReference<String> receivedQuery) throws IOException {
        receivedQuery.set(exchange.getRequestURI().getRawQuery());
        exchange.getResponseHeaders().add("Content-Type", "text/plain");
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "https://viewer.example");
        exchange.getResponseHeaders().add("Cache-Control", "public, max-age=60");
        exchange.getResponseHeaders().add("ETag", "\"origin-etag\"");
        exchange.getResponseHeaders().add("X-Origin-Header", "preserved");
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().add("Content-Length", "123");
            exchange.sendResponseHeaders(200, -1);
        } else {
            byte[] body = "origin-body".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    private static void respondWithPolicyHeaders(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/plain");
        exchange.getResponseHeaders().add("Connection", "X-Hop");
        exchange.getResponseHeaders().add("X-Hop", "origin-hop-value");
        exchange.getResponseHeaders().add("X-Remove", "remove-me");
        exchange.getResponseHeaders().add("X-Readd", "origin-readd");
        exchange.getResponseHeaders().add("X-Override", "origin-override");
        exchange.getResponseHeaders().add("X-Preserve", "origin-preserve");
        exchange.getResponseHeaders().add("Server", "origin-server");
        exchange.getResponseHeaders().add("Date", "Wed, 01 Jan 2020 00:00:00 GMT");
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "https://origin.example");
        exchange.getResponseHeaders().add("Set-Cookie", "session=a");
        exchange.getResponseHeaders().add("Set-Cookie", "preference=b");
        byte[] body = "origin-policy-body".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static Map<String, String> policyHeader(String name, String value, boolean override) {
        return new LinkedHashMap<>(Map.of(
                "Header", name,
                "Value", value,
                "Override", Boolean.toString(override)));
    }

    private static Map<String, Object> customOriginConfig(int port) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("HTTPPort", String.valueOf(port));
        config.put("HTTPSPort", "443");
        config.put("OriginProtocolPolicy", "http-only");
        return config;
    }

    private static DefaultCacheBehavior defaultBehavior(String originId) {
        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId(originId);
        behavior.setViewerProtocolPolicy("allow-all");
        return behavior;
    }

    public static final class PrivateOriginProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.cloudfront.allowed-private-origin-hosts", "127.0.0.1");
        }
    }
}
