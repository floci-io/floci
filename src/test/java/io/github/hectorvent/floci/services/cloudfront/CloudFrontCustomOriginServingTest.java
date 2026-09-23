package io.github.hectorvent.floci.services.cloudfront;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.cloudfront.model.CacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.CachePolicy;
import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.KeyGroup;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.cloudfront.model.OriginRequestPolicy;
import io.github.hectorvent.floci.services.cloudfront.model.PublicKey;
import io.github.hectorvent.floci.services.cloudfront.model.ResponseHeadersPolicy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(CloudFrontCustomOriginServingTest.PrivateOriginProfile.class)
class CloudFrontCustomOriginServingTest {

    /** The AWS managed CachingDisabled cache policy, which forwards no viewer values. */
    private static final String CACHING_DISABLED = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad";

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

    @Test
    void forwardsWriteMethodsWithTheirBodyToTheOrigin() throws Exception {
        AtomicReference<ReceivedRequest> received = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        startRecordingOrigin(received, hits);
        Distribution created = cloudFrontService.createDistribution(
                customOriginDistribution(allMethodsBehavior()), Map.of());

        String json = "{\"name\":\"widget\",\"note\":\"café\"}";
        given()
            .header("Host", created.getDomainName())
            .contentType("application/json; charset=UTF-8")
            .body(json.getBytes(StandardCharsets.UTF_8))
        .when()
            .post("/api/things")
        .then()
            .statusCode(201)
            .header("X-Origin-Method", equalTo("POST"))
            .body(equalTo("created"));
        ReceivedRequest post = received.get();
        assertEquals("POST", post.method());
        assertEquals("/api/things", post.path());
        assertEquals("application/json; charset=UTF-8", post.header("Content-Type"));
        assertEquals(Integer.toString(json.getBytes(StandardCharsets.UTF_8).length),
                post.header("Content-Length"));
        assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), post.body());

        byte[] binary = new byte[] {0, 1, 2, (byte) 0xff, (byte) 0xfe, 10, 13};
        given()
            .header("Host", created.getDomainName())
            .contentType("application/octet-stream")
            .body(binary)
        .when()
            .put("/api/things/1")
        .then()
            .statusCode(200)
            .header("X-Origin-Method", equalTo("PUT"));
        assertEquals("PUT", received.get().method());
        assertTrue(received.get().header("Content-Type").startsWith("application/octet-stream"));
        assertArrayEquals(binary, received.get().body());

        given()
            .header("Host", created.getDomainName())
            .contentType("application/x-www-form-urlencoded")
            .body("name=widget&size=2")
        .when()
            .patch("/api/things/1")
        .then()
            .statusCode(200)
            .header("X-Origin-Method", equalTo("PATCH"));
        assertEquals("PATCH", received.get().method());
        assertTrue(received.get().header("Content-Type")
                .startsWith("application/x-www-form-urlencoded"));
        assertEquals("name=widget&size=2",
                new String(received.get().body(), StandardCharsets.UTF_8));

        given()
            .header("Host", created.getDomainName())
        .when()
            .delete("/api/things/1")
        .then()
            .statusCode(200)
            .header("X-Origin-Method", equalTo("DELETE"));
        assertEquals("DELETE", received.get().method());
        assertEquals("/api/things/1", received.get().path());
        assertEquals(0, received.get().body().length);
        assertNull(received.get().header("Content-Type"));

        given()
            .header("Host", created.getDomainName())
        .when()
            .post("/api/empty")
        .then()
            .statusCode(201);
        assertEquals("0", received.get().header("Content-Length"));
        assertEquals(5, hits.get());
    }

    @Test
    void forwardsTheViewerAuthorizationOnWriteMethodsOnly() throws Exception {
        AtomicReference<ReceivedRequest> received = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        startRecordingOrigin(received, hits);
        Distribution created = cloudFrontService.createDistribution(
                customOriginDistribution(allMethodsBehavior()), Map.of());

        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            given()
                .header("Host", created.getDomainName())
                .header("Authorization", "Bearer viewer-token")
            .when()
                .request(method, "/api/things/1")
            .then()
                .header("X-Origin-Method", equalTo(method));
            assertEquals("Bearer viewer-token", received.get().header("Authorization"), method);
        }

        // CloudFront removes Authorization from GET and HEAD requests.
        given()
            .header("Host", created.getDomainName())
            .header("Authorization", "Bearer viewer-token")
        .when()
            .get("/api/things/1")
        .then()
            .statusCode(200)
            .header("X-Origin-Method", equalTo("GET"));
        assertNull(received.get().header("Authorization"));
        assertEquals(5, hits.get());
    }

    @Test
    void customErrorPageForAWriteIsFetchedWithoutTheViewerAuthorization() throws Exception {
        Map<String, String> authorizationByRequest = new ConcurrentHashMap<>();
        originServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        originServer.createContext("/", exchange -> {
            String request = exchange.getRequestMethod() + " "
                    + exchange.getRequestURI().getRawPath();
            authorizationByRequest.put(request,
                    String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            boolean errorPage = "GET /errors/500.html".equals(request);
            byte[] body = (errorPage ? "error-page" : "failed").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(errorPage ? 200 : 500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        originServer.start();

        Map<String, Object> errorPage = new LinkedHashMap<>(Map.of(
                "ErrorCode", "500", "ResponseCode", "503", "ResponsePagePath", "/errors/500.html"));
        Distribution distribution = customOriginDistribution(allMethodsBehavior());
        distribution.getConfig().setCustomErrorResponses(List.of(errorPage));
        Distribution created = cloudFrontService.createDistribution(distribution, Map.of());

        given()
            .header("Host", created.getDomainName())
            .header("Authorization", "Bearer viewer-token")
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/api/things")
        .then()
            .statusCode(503)
            .body(equalTo("error-page"));
        assertEquals(Map.of(
                "POST /api/things", "Bearer viewer-token",
                "GET /errors/500.html", "null"), authorizationByRequest);
    }

    @Test
    void rejectsMethodsTheMatchedBehaviorDoesNotAllowWithoutContactingTheOrigin() throws Exception {
        AtomicReference<ReceivedRequest> received = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        startRecordingOrigin(received, hits);

        CacheBehavior readOnly = new CacheBehavior();
        readOnly.setPathPattern("/static/*");
        readOnly.setTargetOriginId("custom-origin");
        readOnly.setViewerProtocolPolicy("allow-all");
        readOnly.setAllowedMethods(List.of("GET", "HEAD"));
        DistributionConfig config = customOriginDistribution(allMethodsBehavior()).getConfig();
        config.setCacheBehaviors(List.of(readOnly));
        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        Distribution created = cloudFrontService.createDistribution(distribution, Map.of());

        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            given()
                .header("Host", created.getDomainName())
                .body("ignored")
            .when()
                .request(method, "/static/app.js")
            .then()
                .statusCode(403)
                .body(equalTo("Invalid method."));
        }
        assertEquals(0, hits.get());

        // A behavior without AllowedMethods keeps CloudFront's GET/HEAD minimum.
        Distribution defaultsOnly = cloudFrontService.createDistribution(
                customOriginDistribution(defaultBehavior("custom-origin")), Map.of());
        given()
            .header("Host", defaultsOnly.getDomainName())
            .body("ignored")
        .when()
            .post("/api/things")
        .then()
            .statusCode(403)
            .body(equalTo("Invalid method."));
        assertEquals(0, hits.get());
    }

    @Test
    void signedBehaviorsRequireASignatureForWriteMethods() throws Exception {
        AtomicReference<ReceivedRequest> received = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        startRecordingOrigin(received, hits);

        String suffix = Long.toString(System.nanoTime(), 36);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        PublicKey publicKey = new PublicKey();
        publicKey.setName("pk-" + suffix);
        publicKey.setCallerReference("cr-" + suffix);
        publicKey.setEncodedKey("-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----");
        publicKey = cloudFrontService.createPublicKey(publicKey);
        KeyGroup keyGroup = new KeyGroup();
        keyGroup.setName("kg-" + suffix);
        keyGroup.setItems(List.of(publicKey.getId()));
        keyGroup = cloudFrontService.createKeyGroup(keyGroup);

        DefaultCacheBehavior behavior = allMethodsBehavior();
        behavior.setTrustedKeyGroups(List.of(keyGroup.getId()));
        behavior.setCachePolicyId(CACHING_DISABLED);
        behavior.setOriginRequestPolicyId(
                CloudFrontService.MANAGED_ALL_VIEWER_ORIGIN_REQUEST_POLICY_ID);
        Distribution created = cloudFrontService.createDistribution(
                customOriginDistribution(behavior), Map.of());

        given()
            .header("Host", created.getDomainName())
            .contentType("application/json")
            .body("{\"unsigned\":true}")
        .when()
            .post("/api/things")
        .then()
            .statusCode(403);
        assertEquals(0, hits.get());

        long expires = Instant.now().getEpochSecond() + 3600;
        String policy = "{\"Statement\":[{\"Resource\":\"*\",\"Condition\":{\"DateLessThan\":"
                + "{\"AWS:EpochTime\":" + expires + "}}}]}";
        Signature signer = Signature.getInstance("SHA1withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(policy.getBytes(StandardCharsets.UTF_8));

        given()
            .header("Host", created.getDomainName())
            .queryParam("color", "blue")
            .queryParam("Policy", cfBase64(policy.getBytes(StandardCharsets.UTF_8)))
            .queryParam("Signature", cfBase64(signer.sign()))
            .queryParam("Key-Pair-Id", publicKey.getId())
            .contentType("application/json")
            .body("{\"signed\":true}")
        .when()
            .post("/api/things")
        .then()
            .statusCode(201);
        assertEquals(1, hits.get());
        assertEquals("{\"signed\":true}",
                new String(received.get().body(), StandardCharsets.UTF_8));
        // AllViewer forwards every query string, but never CloudFront's signing parameters.
        assertEquals("color=blue", received.get().query());
        assertEquals(created.getDomainName(), received.get().header("Host"));
    }

    @Test
    void allViewerExceptHostHeaderForwardsViewerCookiesHeadersAndQuery() throws Exception {
        AtomicReference<ReceivedRequest> received = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        startRecordingOrigin(received, hits);
        DefaultCacheBehavior behavior = policyBehavior(CACHING_DISABLED,
                CloudFrontService.MANAGED_ALL_VIEWER_EXCEPT_HOST_HEADER_ORIGIN_REQUEST_POLICY_ID);
        Distribution created = cloudFrontService.createDistribution(
                customOriginDistribution(behavior, originVerifyHeader()), Map.of());

        Response response = given()
            .header("Host", created.getDomainName())
            .header("Cookie", "session=abc123; csrf=xyz")
            .header("Authorization", "Bearer t")
            .header("X-Custom", "custom")
            .header("User-Agent", "viewer-agent/1.0")
            .header("X-Origin-Verify", "forged")
            .header("X-Origin-Verify", "forged-again")
        .when()
            .get("/api/me?x=1")
        .then()
            .statusCode(200)
            .extract().response();

        ReceivedRequest request = received.get();
        assertEquals("/api/me", request.path());
        assertEquals("x=1", request.query());
        assertEquals("csrf=xyz; session=abc123", request.header("Cookie"));
        assertEquals("Bearer t", request.header("Authorization"));
        assertEquals("custom", request.header("X-Custom"));
        assertEquals("viewer-agent/1.0", request.header("User-Agent"));
        assertEquals(List.of("s3cr3t"), request.headers().get("X-Origin-Verify"));
        assertEquals("127.0.0.1:" + originServer.getAddress().getPort(), request.header("Host"));
        assertEquals("1.1 " + created.getDomainName() + " (CloudFront)", request.header("Via"));
        assertFalse(request.header("X-Forwarded-For").isBlank());
        assertEquals(56, request.header("X-Amz-Cf-Id").length());
        assertEquals("http", request.header("CloudFront-Forwarded-Proto"));
        assertEquals(List.of("session=renewed; Path=/; HttpOnly"),
                response.getHeaders().getValues("Set-Cookie"));
    }

    @Test
    void cachePolicyWithoutAnOriginRequestPolicyForwardsNoViewerValues() throws Exception {
        AtomicReference<ReceivedRequest> received = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        startRecordingOrigin(received, hits);
        DefaultCacheBehavior behavior = policyBehavior(CACHING_DISABLED, null);
        Distribution created = cloudFrontService.createDistribution(
                customOriginDistribution(behavior, originVerifyHeader()), Map.of());

        given()
            .header("Host", created.getDomainName())
            .header("Cookie", "session=abc123")
            .header("Authorization", "Bearer t")
            .header("X-Custom", "custom")
            .header("User-Agent", "viewer-agent/1.0")
        .when()
            .get("/api/me?x=1")
        .then()
            .statusCode(200);
        ReceivedRequest get = received.get();
        assertNull(get.query());
        assertNull(get.header("Cookie"));
        assertNull(get.header("Authorization"));
        assertNull(get.header("X-Custom"));
        assertEquals("Amazon CloudFront", get.header("User-Agent"));
        assertEquals("s3cr3t", get.header("X-Origin-Verify"));
        assertEquals("127.0.0.1:" + originServer.getAddress().getPort(), get.header("Host"));

        // CloudFront never strips Authorization from methods it does not cache.
        given()
            .header("Host", created.getDomainName())
            .header("Cookie", "session=abc123")
            .header("Authorization", "Bearer t")
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/api/things")
        .then()
            .statusCode(201);
        ReceivedRequest post = received.get();
        assertEquals("Bearer t", post.header("Authorization"));
        assertTrue(post.header("Content-Type").startsWith("application/json"));
        assertNull(post.header("Cookie"));
    }

    @Test
    void customPoliciesFilterCookiesHeadersAndQueryStrings() throws Exception {
        AtomicReference<ReceivedRequest> received = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        startRecordingOrigin(received, hits);

        CachePolicy cachePolicy = new CachePolicy();
        cachePolicy.setName("forwarding-cache-policy-" + System.nanoTime());
        cachePolicy.setConfig(CloudFrontPolicyConfigCodec.parseCachePolicy("""
                <CachePolicyConfig>
                  <MinTTL>0</MinTTL><DefaultTTL>0</DefaultTTL><MaxTTL>0</MaxTTL>
                  <ParametersInCacheKeyAndForwardedToOrigin>
                    <EnableAcceptEncodingGzip>false</EnableAcceptEncodingGzip>
                    <HeadersConfig><HeaderBehavior>whitelist</HeaderBehavior>
                      <Headers><Quantity>1</Quantity><Items><Name>Accept-Language</Name></Items></Headers>
                    </HeadersConfig>
                    <CookiesConfig><CookieBehavior>none</CookieBehavior></CookiesConfig>
                    <QueryStringsConfig><QueryStringBehavior>none</QueryStringBehavior></QueryStringsConfig>
                  </ParametersInCacheKeyAndForwardedToOrigin>
                </CachePolicyConfig>
                """));
        cachePolicy = cloudFrontService.createCachePolicy(cachePolicy);
        OriginRequestPolicy originRequestPolicy = new OriginRequestPolicy();
        originRequestPolicy.setName("forwarding-origin-policy-" + System.nanoTime());
        originRequestPolicy.setConfig(CloudFrontPolicyConfigCodec.parseOriginRequestPolicy("""
                <OriginRequestPolicyConfig>
                  <HeadersConfig><HeaderBehavior>whitelist</HeaderBehavior>
                    <Headers><Quantity>1</Quantity><Items><Name>X-Custom</Name></Items></Headers>
                  </HeadersConfig>
                  <CookiesConfig><CookieBehavior>whitelist</CookieBehavior>
                    <Cookies><Quantity>1</Quantity><Items><Name>session</Name></Items></Cookies>
                  </CookiesConfig>
                  <QueryStringsConfig><QueryStringBehavior>allExcept</QueryStringBehavior>
                    <QueryStrings><Quantity>1</Quantity><Items><Name>utm_source</Name></Items></QueryStrings>
                  </QueryStringsConfig>
                </OriginRequestPolicyConfig>
                """));
        originRequestPolicy = cloudFrontService.createOriginRequestPolicy(originRequestPolicy);
        Distribution created = cloudFrontService.createDistribution(customOriginDistribution(
                policyBehavior(cachePolicy.getId(), originRequestPolicy.getId())), Map.of());

        given()
            .urlEncodingEnabled(false)
            .header("Host", created.getDomainName())
            .header("Cookie", "tracking=1; session=abc; theme=dark")
            .header("X-Custom", "custom")
            .header("X-Other", "other")
            .header("Accept-Language", "en-GB")
        .when()
            .get("/search?q=red%20shoes&utm_source=ad&page=2")
        .then()
            .statusCode(200);

        ReceivedRequest request = received.get();
        assertEquals("q=red%20shoes&page=2", request.query());
        assertEquals("session=abc", request.header("Cookie"));
        assertEquals("custom", request.header("X-Custom"));
        assertEquals("en-GB", request.header("Accept-Language"));
        assertNull(request.header("X-Other"));
    }

    @Test
    void legacyForwardedValuesSelectQueryCookiesAndHeaders() throws Exception {
        AtomicReference<ReceivedRequest> received = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        startRecordingOrigin(received, hits);
        Map<String, Object> forwardedValues = new LinkedHashMap<>();
        forwardedValues.put("QueryString", true);
        forwardedValues.put("CookiesForward", "whitelist");
        forwardedValues.put("CookieNames", List.of("session"));
        forwardedValues.put("Headers", List.of("Authorization"));
        DefaultCacheBehavior behavior = defaultBehavior("custom-origin");
        behavior.setForwardedValues(forwardedValues);
        Distribution created = cloudFrontService.createDistribution(
                customOriginDistribution(behavior), Map.of());

        given()
            .header("Host", created.getDomainName())
            .header("Cookie", "session=abc; other=1")
            .header("Authorization", "Bearer t")
            .header("X-Custom", "custom")
            .header("Accept-Language", "en-GB")
            .header("User-Agent", "viewer-agent/1.0")
        .when()
            .get("/legacy?x=1&y=2")
        .then()
            .statusCode(200);

        ReceivedRequest request = received.get();
        assertEquals("x=1&y=2", request.query());
        assertEquals("session=abc", request.header("Cookie"));
        assertEquals("Bearer t", request.header("Authorization"));
        assertEquals("custom", request.header("X-Custom"));
        assertNull(request.header("Accept-Language"));
        assertEquals("Amazon CloudFront", request.header("User-Agent"));
        assertEquals("127.0.0.1:" + originServer.getAddress().getPort(), request.header("Host"));
    }

    private void startRecordingOrigin(AtomicReference<ReceivedRequest> received, AtomicInteger hits)
            throws IOException {
        originServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        originServer.createContext("/", exchange -> {
            hits.incrementAndGet();
            received.set(new ReceivedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders(),
                    exchange.getRequestBody().readAllBytes()));
            byte[] body = ("POST".equals(exchange.getRequestMethod()) ? "created" : "ok")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.getResponseHeaders().add("X-Origin-Method", exchange.getRequestMethod());
            exchange.getResponseHeaders().add("Set-Cookie", "session=renewed; Path=/; HttpOnly");
            exchange.sendResponseHeaders(
                    "POST".equals(exchange.getRequestMethod()) ? 201 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        originServer.start();
    }

    private Distribution customOriginDistribution(DefaultCacheBehavior behavior) {
        return customOriginDistribution(behavior, null);
    }

    private Distribution customOriginDistribution(DefaultCacheBehavior behavior,
                                                  List<Map<String, String>> customHeaders) {
        Origin customOrigin = new Origin();
        customOrigin.setId("custom-origin");
        customOrigin.setDomainName("127.0.0.1");
        customOrigin.setCustomOriginConfig(customOriginConfig(originServer.getAddress().getPort()));
        customOrigin.setCustomHeaders(customHeaders);
        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setOrigins(List.of(customOrigin));
        config.setDefaultCacheBehavior(behavior);
        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        return distribution;
    }

    private static List<Map<String, String>> originVerifyHeader() {
        return List.of(new LinkedHashMap<>(Map.of(
                "HeaderName", "X-Origin-Verify", "HeaderValue", "s3cr3t")));
    }

    private static DefaultCacheBehavior policyBehavior(String cachePolicyId,
                                                       String originRequestPolicyId) {
        DefaultCacheBehavior behavior = allMethodsBehavior();
        behavior.setCachePolicyId(cachePolicyId);
        behavior.setOriginRequestPolicyId(originRequestPolicyId);
        return behavior;
    }

    private static DefaultCacheBehavior allMethodsBehavior() {
        DefaultCacheBehavior behavior = defaultBehavior("custom-origin");
        behavior.setAllowedMethods(List.of("GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"));
        behavior.setCachedMethods(List.of("GET", "HEAD"));
        return behavior;
    }

    private static String cfBase64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes)
                .replace('+', '-').replace('=', '_').replace('/', '~');
    }

    private record ReceivedRequest(String method, String path, String query, Headers headers,
                                   byte[] body) {
        String header(String name) {
            return headers.getFirst(name);
        }
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
