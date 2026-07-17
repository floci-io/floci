package io.github.hectorvent.floci.services.cloudfront;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.cloudfront.model.CacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.cloudfront.model.ResponseHeadersPolicy;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.PutObjectOptions;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for CloudFront distribution request-serving: a viewer request addressed to a
 * distribution's domain (or alias) is routed to the matching origin and the content is returned,
 * per the AWS CloudFront data-plane spec (default root object, path-pattern routing, custom-error
 * SPA fallback, S3 and custom origins).
 *
 * <p>S3 origin content is seeded through the shared {@link S3Service} bean; distributions are created
 * through the shared {@link CloudFrontService} bean; requests are driven over HTTP with a spoofed
 * {@code Host} header so the {@link CloudFrontDistributionFilter} routes them.
 */
@QuarkusTest
class CloudFrontDistributionServingTest {

    private static final String REGION = "us-east-1";

    @Inject
    S3Service s3Service;

    @Inject
    CloudFrontService cloudFrontService;

    @Test
    void servesRootObjectSubdirSpaFallbackAndPathRouting() {
        String suffix = suffix();
        String contentBucket = "cf-content-" + suffix;
        String apiBucket = "cf-api-" + suffix;
        String alias = "console-" + suffix + ".example.test";

        createBucket(contentBucket);
        createBucket(apiBucket);
        putObject(contentBucket, "index.html", "INDEX-ROOT-" + suffix, "text/html");
        putObject(contentBucket, "assets/app.js", "APP-JS-" + suffix, "application/javascript");
        putObject(apiBucket, "api/data.json", "API-DATA-" + suffix, "application/json");

        DistributionConfig cfg = new DistributionConfig();
        cfg.setEnabled(true);
        cfg.setDefaultRootObject("index.html");
        cfg.setAliases(List.of(alias));
        cfg.setOrigins(List.of(
                s3Origin("content-origin", contentBucket),
                s3Origin("api-origin", apiBucket)));
        cfg.setDefaultCacheBehavior(defaultBehavior("content-origin"));
        cfg.setCacheBehaviors(List.of(behavior("/api/*", "api-origin")));
        cfg.setCustomErrorResponses(List.of(
                customError(403, 200, "/index.html"),
                customError(404, 200, "/index.html")));

        Distribution dist = cloudFrontService.createDistribution(distribution(cfg), Map.of());
        String host = dist.getDomainName();

        // Root request → default root object from the S3 origin.
        given().header("Host", host).when().get("/")
                .then().statusCode(200).body(containsString("INDEX-ROOT-" + suffix));

        // Subdirectory object, served as-is with its stored content type.
        given().header("Host", host).when().get("/assets/app.js")
                .then().statusCode(200)
                .header("Content-Type", containsString("javascript"))
                .body(containsString("APP-JS-" + suffix));

        // Unknown deep path (a client-side SPA route) → 404 rewritten to 200 /index.html.
        given().header("Host", host).when().get("/deep/spa/route")
                .then().statusCode(200).body(containsString("INDEX-ROOT-" + suffix));

        // Path-pattern behavior routes /api/* to the second origin.
        given().header("Host", host).when().get("/api/data.json")
                .then().statusCode(200).body(containsString("API-DATA-" + suffix));

        // The same distribution is reachable via its alternate domain name (CNAME alias).
        given().header("Host", alias).when().get("/")
                .then().statusCode(200).body(containsString("INDEX-ROOT-" + suffix));

        // HEAD returns headers without a body.
        given().header("Host", host).when().head("/assets/app.js")
                .then().statusCode(200);
    }

    @Test
    void returnsNotFoundWhenNoCustomErrorResponseConfigured() {
        String suffix = suffix();
        String bucket = "cf-plain-" + suffix;
        createBucket(bucket);
        putObject(bucket, "index.html", "PLAIN-INDEX-" + suffix, "text/html");

        DistributionConfig cfg = new DistributionConfig();
        cfg.setEnabled(true);
        cfg.setDefaultRootObject("index.html");
        cfg.setOrigins(List.of(s3Origin("only-origin", bucket)));
        cfg.setDefaultCacheBehavior(defaultBehavior("only-origin"));

        Distribution dist = cloudFrontService.createDistribution(distribution(cfg), Map.of());
        String host = dist.getDomainName();

        // Sanity: the root object is served.
        given().header("Host", host).when().get("/")
                .then().statusCode(200).body(containsString("PLAIN-INDEX-" + suffix));

        // Missing object with no matching CustomErrorResponse → the origin 404 is returned.
        given().header("Host", host).when().get("/missing-object.txt")
                .then().statusCode(404);
    }

    @Test
    void returnsErrorPageOriginStatusWhenCustomErrorPageIsMissing() {
        // AWS: if the configured custom error page is itself unavailable, CloudFront returns the
        // status received from the error-page origin (here 404) — not the ResponseCode (200) override.
        String suffix = suffix();
        String bucket = "cf-missing-errpage-" + suffix;
        createBucket(bucket);
        putObject(bucket, "index.html", "ROOT-" + suffix, "text/html");

        DistributionConfig cfg = new DistributionConfig();
        cfg.setEnabled(true);
        cfg.setDefaultRootObject("index.html");
        cfg.setOrigins(List.of(s3Origin("only-origin", bucket)));
        cfg.setDefaultCacheBehavior(defaultBehavior("only-origin"));
        cfg.setCustomErrorResponses(List.of(customError(404, 200, "/does-not-exist.html")));

        Distribution dist = cloudFrontService.createDistribution(distribution(cfg), Map.of());

        given().header("Host", dist.getDomainName()).when().get("/some/missing/route")
                .then().statusCode(404);
    }

    @Test
    void blocksPrivateCustomOriginBeforeConnecting() throws Exception {
        String suffix = suffix();
        AtomicInteger hits = new AtomicInteger();
        HttpServer privateOrigin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        privateOrigin.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        privateOrigin.start();

        try {
            Map<String, Object> customOriginConfig = new LinkedHashMap<>();
            customOriginConfig.put("HTTPPort", String.valueOf(privateOrigin.getAddress().getPort()));
            customOriginConfig.put("HTTPSPort", "443");
            customOriginConfig.put("OriginProtocolPolicy", "http-only");
            Origin custom = new Origin();
            custom.setId("custom-origin");
            custom.setDomainName("127.0.0.1");
            custom.setCustomOriginConfig(customOriginConfig);

            DistributionConfig cfg = new DistributionConfig();
            cfg.setEnabled(true);
            cfg.setDefaultRootObject("index.html");
            cfg.setOrigins(List.of(custom));
            cfg.setDefaultCacheBehavior(defaultBehavior("custom-origin"));

            Distribution dist = cloudFrontService.createDistribution(distribution(cfg), Map.of());

            given().header("Host", dist.getDomainName()).when().get("/")
                    .then().statusCode(502);
            assertEquals(0, hits.get(), "blocked private origins must not receive a connection");
        } finally {
            privateOrigin.stop(0);
        }
    }

    @Test
    void roundTripsCustomErrorResponsesThroughTheApi() {
        // Validates that the CloudFront API parses and re-serializes CustomErrorResponses (needed for
        // the SPA fallback) rather than dropping them (Quantity 0) as it did before.
        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <DistributionConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                  <CallerReference>cf-cer-roundtrip</CallerReference>
                  <DefaultRootObject>index.html</DefaultRootObject>
                  <Origins>
                    <Quantity>1</Quantity>
                    <Items>
                      <Origin>
                        <Id>o1</Id>
                        <DomainName>cer-bucket.s3.us-east-1.amazonaws.com</DomainName>
                        <OriginPath></OriginPath>
                        <S3OriginConfig><OriginAccessIdentity></OriginAccessIdentity></S3OriginConfig>
                      </Origin>
                    </Items>
                  </Origins>
                  <DefaultCacheBehavior>
                    <TargetOriginId>o1</TargetOriginId>
                    <ViewerProtocolPolicy>allow-all</ViewerProtocolPolicy>
                  </DefaultCacheBehavior>
                  <CustomErrorResponses>
                    <Quantity>1</Quantity>
                    <Items>
                      <CustomErrorResponse>
                        <ErrorCode>403</ErrorCode>
                        <ResponsePagePath>/index.html</ResponsePagePath>
                        <ResponseCode>200</ResponseCode>
                        <ErrorCachingMinTTL>10</ErrorCachingMinTTL>
                      </CustomErrorResponse>
                    </Items>
                  </CustomErrorResponses>
                  <Comment>cer round-trip</Comment>
                  <Enabled>true</Enabled>
                </DistributionConfig>
                """;

        given()
            .contentType("application/xml")
            .body(body)
        .when()
            .post("/2020-05-31/distribution")
        .then()
            .statusCode(201)
            .body(containsString("<CustomErrorResponse>"))
            .body(containsString("<ErrorCode>403</ErrorCode>"))
            .body(containsString("<ResponseCode>200</ResponseCode>"))
            .body(containsString("<ResponsePagePath>/index.html</ResponsePagePath>"));
    }

    @Test
    void appliesResponseHeadersPolicyToServedResponses() {
        String suffix = suffix();
        String bucket = "cf-rhp-" + suffix;
        createBucket(bucket);
        putObject(bucket, "index.html", "RHP-INDEX-" + suffix, "text/html");

        Map<String, Object> security = new LinkedHashMap<>();
        security.put("StrictTransportSecurity", new LinkedHashMap<>(Map.of(
                "Override", "true", "AccessControlMaxAgeSec", "31536000",
                "IncludeSubdomains", "true", "Preload", "false")));
        security.put("ContentTypeOptions", new LinkedHashMap<>(Map.of("Override", "true")));
        security.put("FrameOptions", new LinkedHashMap<>(Map.of("Override", "true", "FrameOption", "DENY")));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("SecurityHeadersConfig", security);
        config.put("CustomHeadersConfig", List.of(new LinkedHashMap<>(Map.of(
                "Header", "X-App", "Value", "floci", "Override", "true"))));
        config.put("CorsConfig", new LinkedHashMap<>(Map.of(
                "AccessControlAllowCredentials", "false",
                "AccessControlAllowHeaders", List.of(),
                "AccessControlAllowMethods", List.of("GET", "HEAD"),
                "AccessControlAllowOrigins", List.of("*"),
                "OriginOverride", "true")));

        ResponseHeadersPolicy policy = new ResponseHeadersPolicy();
        policy.setName("sec-" + suffix);
        policy.setConfig(config);
        ResponseHeadersPolicy created = cloudFrontService.createResponseHeadersPolicy(policy);

        DefaultCacheBehavior dcb = defaultBehavior("only-origin");
        dcb.setResponseHeadersPolicyId(created.getId());

        DistributionConfig cfg = new DistributionConfig();
        cfg.setEnabled(true);
        cfg.setDefaultRootObject("index.html");
        cfg.setOrigins(List.of(s3Origin("only-origin", bucket)));
        cfg.setDefaultCacheBehavior(dcb);

        Distribution dist = cloudFrontService.createDistribution(distribution(cfg), Map.of());

        given()
                .header("Host", dist.getDomainName())
                .header("Origin", "https://viewer.example")
                .when().get("/")
                .then().statusCode(200)
                .header("Strict-Transport-Security", containsString("max-age=31536000"))
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Frame-Options", "DENY")
                .header("X-App", "floci")
                .header("Access-Control-Allow-Origin", "*")
                .body(containsString("RHP-INDEX-" + suffix));
    }

    @Test
    void preservesOverridesAndRemovesS3OriginHeaders() {
        String suffix = suffix();
        String bucket = "cf-s3-headers-" + suffix;
        createBucket(bucket);
        s3Service.putObject(bucket, "index.html",
                ("S3-HEADERS-" + suffix).getBytes(StandardCharsets.UTF_8),
                "text/html",
                Map.of("keep", "origin-keep", "override", "origin-override", "remove", "remove-me"),
                new PutObjectOptions()
                        .withCacheControl("public, max-age=60")
                        .withContentDisposition("inline"));

        Map<String, Object> policyConfig = new LinkedHashMap<>();
        policyConfig.put("RemoveHeadersConfig", List.of("X-Amz-Meta-Remove"));
        policyConfig.put("CustomHeadersConfig", List.of(
                policyHeader("Cache-Control", "policy-cache", false),
                policyHeader("X-Amz-Meta-Override", "policy-override", true),
                policyHeader("X-New", "policy-new", false)));
        ResponseHeadersPolicy policy = new ResponseHeadersPolicy();
        policy.setName("s3-origin-headers-" + suffix);
        policy.setConfig(policyConfig);
        policy = cloudFrontService.createResponseHeadersPolicy(policy);

        DefaultCacheBehavior behavior = defaultBehavior("only-origin");
        behavior.setResponseHeadersPolicyId(policy.getId());
        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setDefaultRootObject("index.html");
        config.setOrigins(List.of(s3Origin("only-origin", bucket)));
        config.setDefaultCacheBehavior(behavior);
        Distribution distribution = cloudFrontService.createDistribution(distribution(config), Map.of());

        given().header("Host", distribution.getDomainName())
                .when().get("/")
                .then().statusCode(200)
                .header("Cache-Control", equalTo("public, max-age=60"))
                .header("Content-Disposition", equalTo("inline"))
                .header("ETag", notNullValue())
                .header("Last-Modified", notNullValue())
                .header("X-Amz-Meta-Keep", equalTo("origin-keep"))
                .header("X-Amz-Meta-Override", equalTo("policy-override"))
                .header("X-Amz-Meta-Remove", nullValue())
                .header("X-New", equalTo("policy-new"));
    }

    @Test
    void servesCorsPreflightThroughS3AndAppliesPreflightPolicyFields() {
        String suffix = suffix();
        String bucket = "cf-preflight-" + suffix;
        createBucket(bucket);
        s3Service.putBucketCors(bucket, """
                <CORSConfiguration>
                  <CORSRule>
                    <AllowedOrigin>*</AllowedOrigin>
                    <AllowedMethod>GET</AllowedMethod>
                    <AllowedHeader>*</AllowedHeader>
                  </CORSRule>
                </CORSConfiguration>
                """);

        Map<String, Object> cors = new LinkedHashMap<>();
        cors.put("AccessControlAllowCredentials", "false");
        cors.put("AccessControlAllowHeaders", List.of("*", "Authorization"));
        cors.put("AccessControlAllowMethods", List.of("ALL"));
        cors.put("AccessControlAllowOrigins", List.of("*"));
        cors.put("AccessControlExposeHeaders", List.of("ETag"));
        cors.put("AccessControlMaxAgeSec", "600");
        cors.put("OriginOverride", "true");
        ResponseHeadersPolicy policy = new ResponseHeadersPolicy();
        policy.setName("preflight-policy-" + suffix);
        policy.setConfig(Map.of("CorsConfig", cors));
        policy = cloudFrontService.createResponseHeadersPolicy(policy);

        DefaultCacheBehavior behavior = defaultBehavior("only-origin");
        behavior.setResponseHeadersPolicyId(policy.getId());
        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setOrigins(List.of(s3Origin("only-origin", bucket)));
        config.setDefaultCacheBehavior(behavior);
        Distribution distribution = cloudFrontService.createDistribution(distribution(config), Map.of());

        given()
                .header("Host", distribution.getDomainName())
                .header("Origin", "https://viewer.example")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization")
                .when().options("/resource")
                .then().statusCode(200)
                .header("Access-Control-Allow-Origin", equalTo("*"))
                .header("Access-Control-Allow-Methods",
                        equalTo("DELETE, GET, HEAD, OPTIONS, PATCH, POST, PUT"))
                .header("Access-Control-Allow-Headers", equalTo("*, Authorization"))
                .header("Access-Control-Expose-Headers", equalTo("ETag"))
                .header("Access-Control-Max-Age", equalTo("600"));
    }

    @Test
    void listsManagedResponseHeadersPoliciesWithManagedType() {
        String body = given()
                .queryParam("Type", "managed")
                .when().get("/2020-05-31/response-headers-policy")
                .then().statusCode(200)
                .extract().asString();

        assertTrue(body.contains("<Quantity>5</Quantity>"), body);
        assertTrue(body.contains("<IsTruncated>false</IsTruncated>"), body);
        assertFalse(body.contains("<NextMarker>"), body);
        assertTrue(body.contains("<Type>managed</Type>"), body);
        assertTrue(body.contains("Managed-SimpleCORS"), body);
        assertFalse(body.contains("<Type>custom</Type>"), body);

        String firstPage = given()
                .queryParam("Type", "managed")
                .queryParam("MaxItems", 1)
                .when().get("/2020-05-31/response-headers-policy")
                .then().statusCode(200)
                .extract().asString();
        assertTrue(firstPage.contains("<Quantity>1</Quantity>"), firstPage);
        assertTrue(firstPage.contains("<IsTruncated>true</IsTruncated>"), firstPage);
        assertTrue(firstPage.contains("<NextMarker>"), firstPage);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private void createBucket(String bucket) {
        s3Service.createBucket(bucket, REGION);
    }

    private void putObject(String bucket, String key, String body, String contentType) {
        s3Service.putObject(bucket, key, body.getBytes(StandardCharsets.UTF_8), contentType, Map.of());
    }

    private static Distribution distribution(DistributionConfig cfg) {
        Distribution dist = new Distribution();
        dist.setConfig(cfg);
        return dist;
    }

    private static Origin s3Origin(String id, String bucket) {
        Origin origin = new Origin();
        origin.setId(id);
        origin.setDomainName(bucket + ".s3." + REGION + ".amazonaws.com");
        origin.setS3OriginConfig(new LinkedHashMap<>(Map.of("OriginAccessIdentity", "")));
        return origin;
    }

    private static DefaultCacheBehavior defaultBehavior(String originId) {
        DefaultCacheBehavior dcb = new DefaultCacheBehavior();
        dcb.setTargetOriginId(originId);
        dcb.setViewerProtocolPolicy("allow-all");
        return dcb;
    }

    private static CacheBehavior behavior(String pattern, String originId) {
        CacheBehavior cb = new CacheBehavior();
        cb.setPathPattern(pattern);
        cb.setTargetOriginId(originId);
        cb.setViewerProtocolPolicy("allow-all");
        return cb;
    }

    private static Map<String, Object> customError(int errorCode, int responseCode, String pagePath) {
        Map<String, Object> cer = new LinkedHashMap<>();
        cer.put("ErrorCode", String.valueOf(errorCode));
        cer.put("ResponseCode", String.valueOf(responseCode));
        cer.put("ResponsePagePath", pagePath);
        return cer;
    }

    private static Map<String, String> policyHeader(String name, String value, boolean override) {
        return new LinkedHashMap<>(Map.of(
                "Header", name,
                "Value", value,
                "Override", Boolean.toString(override)));
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }
}
