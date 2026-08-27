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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void omitsQueryByDefaultAndForwardsResponseHeadersAndHeadMetadata() throws Exception {
        AtomicReference<String> receivedQuery = new AtomicReference<>();
        AtomicReference<String> receivedPath = new AtomicReference<>();
        AtomicReference<String> receivedCustomHeader = new AtomicReference<>();
        AtomicReference<String> receivedExpectHeader = new AtomicReference<>();
        originServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        originServer.createContext("/", exchange ->
                respond(exchange, receivedQuery, receivedPath, receivedCustomHeader, receivedExpectHeader));
        originServer.start();

        Origin customOrigin = new Origin();
        customOrigin.setId("custom-origin");
        // The embedded port is deliberately wrong. CustomOriginConfig.HTTPPort is authoritative.
        customOrigin.setDomainName("127.0.0.1:1");
        customOrigin.setCustomOriginConfig(customOriginConfig(originServer.getAddress().getPort()));
        customOrigin.setCustomHeaders(List.of(
                new LinkedHashMap<>(Map.of(
                        "HeaderName", "X-Origin-Verify", "HeaderValue", "shared-secret-42")),
                new LinkedHashMap<>(Map.of(
                        "HeaderName", "Expect", "HeaderValue", "100-continue"))));

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

        assertNull(receivedQuery.get());
        assertEquals("shared-secret-42", receivedCustomHeader.get());
        assertEquals("100-continue", receivedExpectHeader.get());

        given()
            .urlEncodingEnabled(false)
            .header("Host", created.getDomainName())
        .when()
            .get("/encoded%2Fpath")
        .then()
            .statusCode(200);

        assertEquals("/encoded%2Fpath", receivedPath.get());

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
                policyHeader("X-Hop", "policy-hop", true),
                policyHeader("sErVeR", "policy-server", false),
                policyHeader("dAtE", "Thu, 02 Jan 2020 00:00:00 GMT", false)));
        policyConfig.put("SecurityHeadersConfig", Map.of(
                "StrictTransportSecurity", Map.of(
                        "AccessControlMaxAgeSec", "31536000",
                        "IncludeSubdomains", "true",
                        "Preload", "false",
                        "Override", "true")));
        policyConfig.put("ServerTimingHeadersConfig", Map.of(
                "Enabled", "true", "SamplingRate", "100"));
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
        assertEquals("policy-hop", response.getHeader("X-Hop"));
        assertNull(response.getHeader("Connection"));
        assertEquals("max-age=31536000; includeSubDomains",
                response.getHeader("Strict-Transport-Security"));
        assertEquals("policy-server", response.getHeader("Server"));
        List<String> dateValues = response.getHeaders().getValues("Date");
        assertTrue(dateValues.contains("Thu, 02 Jan 2020 00:00:00 GMT"),
                dateValues.toString());
        assertFalse(dateValues.contains("Wed, 01 Jan 2020 00:00:00 GMT"),
                dateValues.toString());
        assertTrue(response.getHeader("Server-Timing").contains("origin;dur=5"));
        assertTrue(response.getHeader("Server-Timing").contains("cdn-cache-miss"));
        List<String> cookies = response.getHeaders().getValues("Set-Cookie");
        assertEquals(2, cookies.size());
        assertTrue(cookies.contains("session=a"), cookies.toString());
        assertTrue(cookies.contains("preference=b"), cookies.toString());

        ResponseHeadersPolicy removeOnly = new ResponseHeadersPolicy();
        removeOnly.setName("remove-server-date-" + System.nanoTime());
        removeOnly.setConfig(Map.of("RemoveHeadersConfig", List.of("Server", "Date")));
        removeOnly = cloudFrontService.createResponseHeadersPolicy(removeOnly);
        DefaultCacheBehavior removeOnlyBehavior = defaultBehavior("custom-origin");
        removeOnlyBehavior.setResponseHeadersPolicyId(removeOnly.getId());
        DistributionConfig removeOnlyConfig = new DistributionConfig();
        removeOnlyConfig.setEnabled(true);
        removeOnlyConfig.setOrigins(List.of(customOrigin));
        removeOnlyConfig.setDefaultCacheBehavior(removeOnlyBehavior);
        Distribution removeOnlyDistribution = new Distribution();
        removeOnlyDistribution.setConfig(removeOnlyConfig);
        Distribution removeOnlyCreated =
                cloudFrontService.createDistribution(removeOnlyDistribution, Map.of());

        var removeOnlyResponse = given()
                .header("Host", removeOnlyCreated.getDomainName())
                .when().get("/resource")
                .then().statusCode(200)
                .extract().response();
        assertEquals("CloudFront", removeOnlyResponse.getHeader("Server"));
        assertNotEquals("Wed, 01 Jan 2020 00:00:00 GMT",
                removeOnlyResponse.getHeader("Date"));
    }

    private static void respond(HttpExchange exchange, AtomicReference<String> receivedQuery,
                                AtomicReference<String> receivedPath,
                                AtomicReference<String> receivedCustomHeader,
                                AtomicReference<String> receivedExpectHeader) throws IOException {
        receivedQuery.set(exchange.getRequestURI().getRawQuery());
        receivedPath.set(exchange.getRequestURI().getRawPath());
        receivedCustomHeader.set(exchange.getRequestHeaders().getFirst("X-Origin-Verify"));
        receivedExpectHeader.set(exchange.getRequestHeaders().getFirst("Expect"));
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
        exchange.getResponseHeaders().add(
                "Connection", "X-Hop, Strict-Transport-Security");
        exchange.getResponseHeaders().add("X-Hop", "origin-hop-value");
        exchange.getResponseHeaders().add(
                "Strict-Transport-Security", "max-age=1");
        exchange.getResponseHeaders().add("X-Remove", "remove-me");
        exchange.getResponseHeaders().add("X-Readd", "origin-readd");
        exchange.getResponseHeaders().add("X-Override", "origin-override");
        exchange.getResponseHeaders().add("X-Preserve", "origin-preserve");
        exchange.getResponseHeaders().add("Server", "origin-server");
        exchange.getResponseHeaders().add("Date", "Wed, 01 Jan 2020 00:00:00 GMT");
        exchange.getResponseHeaders().add("Server-Timing", "origin;dur=5");
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
