package io.github.hectorvent.floci.services.cloudfront;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.cloudfront.model.CacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.PutObjectOptions;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void forwardsSafeS3ObjectHeadersWithoutStorageInternals() {
        String suffix = suffix();
        String bucket = "cf-object-headers-" + suffix;
        String body = "S3-OBJECT-HEADERS-" + suffix;
        createBucket(bucket);
        S3Object object = s3Service.putObject(
                bucket, "asset.txt", body.getBytes(StandardCharsets.UTF_8), "text/plain",
                Map.of("source", "origin"),
                new PutObjectOptions()
                        .withStorageClass("STANDARD_IA")
                        .withContentEncoding("identity")
                        .withContentDisposition("inline")
                        .withCacheControl("public, max-age=60")
                        .withServerSideEncryption("AES256")
                        .withChecksumAlgorithm("SHA256"));

        DistributionConfig cfg = new DistributionConfig();
        cfg.setEnabled(true);
        cfg.setOrigins(List.of(s3Origin("only-origin", bucket)));
        cfg.setDefaultCacheBehavior(defaultBehavior("only-origin"));
        Distribution dist = cloudFrontService.createDistribution(distribution(cfg), Map.of());
        String lastModified = DateTimeFormatter.RFC_1123_DATE_TIME
                .withZone(ZoneOffset.UTC)
                .format(object.getLastModified());

        given().header("Host", dist.getDomainName()).when().get("/asset.txt")
                .then().statusCode(200)
                .header("Content-Type", containsString("text/plain"))
                .header("Accept-Ranges", equalTo("bytes"))
                .header("ETag", equalTo(object.getETag()))
                .header("Last-Modified", equalTo(lastModified))
                .header("Cache-Control", equalTo("public, max-age=60"))
                .header("Content-Encoding", equalTo("identity"))
                .header("Content-Disposition", equalTo("inline"))
                .header("x-amz-storage-class", equalTo("STANDARD_IA"))
                .header("x-amz-meta-source", equalTo("origin"))
                .header("x-amz-server-side-encryption", nullValue())
                .header("x-amz-checksum-sha256", nullValue())
                .body(equalTo(body));

        given().header("Host", dist.getDomainName()).when().head("/asset.txt")
                .then().statusCode(200)
                .header("ETag", equalTo(object.getETag()))
                .header("Content-Length", equalTo(Integer.toString(body.getBytes(StandardCharsets.UTF_8).length)));
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

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }
}
