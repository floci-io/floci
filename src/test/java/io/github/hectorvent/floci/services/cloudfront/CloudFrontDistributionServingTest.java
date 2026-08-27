package io.github.hectorvent.floci.services.cloudfront;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.cloudfront.model.CacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.KeyGroup;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.cloudfront.model.PublicKey;
import io.github.hectorvent.floci.services.cloudfront.model.ResponseHeadersPolicy;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.PutObjectOptions;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
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
        String alias = "viewer-" + suffix + ".example.test";
        String s3ShapedAlias = "viewer-" + suffix + ".localhost";

        createBucket(contentBucket);
        createBucket(apiBucket);
        putObject(contentBucket, "index.html", "INDEX-ROOT-" + suffix, "text/html");
        putObject(contentBucket, "assets/app.js", "APP-JS-" + suffix, "application/javascript");
        putObject(contentBucket, "encoded key.txt", "ENCODED-KEY-" + suffix, "text/plain");
        putObject(apiBucket, "api/data.json", "API-DATA-" + suffix, "application/json");
        putObject(apiBucket, "/api//raw-data.json", "RAW-PATH-" + suffix, "application/json");
        putObject(apiBucket, "question?v1", "ENCODED-QUESTION-" + suffix, "application/json");

        DistributionConfig cfg = new DistributionConfig();
        cfg.setEnabled(true);
        cfg.setDefaultRootObject("index.html");
        cfg.setAliases(List.of(alias, s3ShapedAlias));
        cfg.setOrigins(List.of(
                s3Origin("content-origin", contentBucket),
                s3Origin("api-origin", apiBucket)));
        cfg.setDefaultCacheBehavior(defaultBehavior("content-origin"));
        cfg.setCacheBehaviors(List.of(
                behavior("/api/*", "api-origin"),
                behavior("question?*", "api-origin")));
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

        // URI encoding is decoded once for the in-process S3 key lookup.
        given().urlEncodingEnabled(false).header("Host", host).when().get("/encoded%20key.txt")
                .then().statusCode(200).body(containsString("ENCODED-KEY-" + suffix));

        // Unknown deep path (a client-side SPA route) → 404 rewritten to 200 /index.html.
        given().header("Host", host).when().get("/deep/spa/route")
                .then().statusCode(200).body(containsString("INDEX-ROOT-" + suffix));

        // Path-pattern behavior routes /api/* to the second origin.
        given().header("Host", host).when().get("/api/data.json")
                .then().statusCode(200).body(containsString("API-DATA-" + suffix));

        // CloudFront normalizes the viewer path only for behavior selection. The selected origin
        // still receives the original path, including repeated slashes.
        given().urlEncodingEnabled(false).header("Host", host).when().get("/%2Fapi/%2Fraw-data.json")
                .then().statusCode(200).body(containsString("RAW-PATH-" + suffix));

        // An encoded question mark is object-key data, not the start of the already-separated query.
        given().urlEncodingEnabled(false).header("Host", host).when().get("/question%3Fv1")
                .then().statusCode(200).body(containsString("ENCODED-QUESTION-" + suffix));

        // The same distribution is reachable via its alternate domain name (CNAME alias).
        given().header("Host", alias).when().get("/")
                .then().statusCode(200).body(containsString("INDEX-ROOT-" + suffix));

        // A CloudFront alias that resembles an S3 virtual host must remain CloudFront-routed.
        given().header("Host", s3ShapedAlias).when().get("/")
                .then().statusCode(200).body(containsString("INDEX-ROOT-" + suffix));

        // HEAD returns headers without a body.
        given().header("Host", host).when().head("/assets/app.js")
                .then().statusCode(200);

        // The implementation-only controller path is not an alternate public endpoint.
        given().when().get("/_cloudfront/" + dist.getId() + "/")
                .then().statusCode(404);
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
    void doesNotServeDisabledDistributionThroughInternalRoute() {
        String suffix = suffix();
        String bucket = "cf-disabled-" + suffix;
        String alias = bucket + ".localhost";
        createBucket(bucket);
        putObject(bucket, "index.html", "DISABLED-INDEX-" + suffix, "text/html");

        DistributionConfig cfg = new DistributionConfig();
        cfg.setEnabled(false);
        cfg.setDefaultRootObject("index.html");
        cfg.setAliases(List.of(alias));
        cfg.setOrigins(List.of(s3Origin("only-origin", bucket)));
        cfg.setDefaultCacheBehavior(defaultBehavior("only-origin"));

        Distribution dist = cloudFrontService.createDistribution(distribution(cfg), Map.of());

        given().header("Host", dist.getDomainName()).when().get("/")
                .then().statusCode(404);
        // The disabled alias remains owned by CloudFront and must not fall through to the S3
        // virtual-host filter, even though it names a real bucket.
        given().header("Host", alias).when().get("/index.html")
                .then().statusCode(404);
        given().when().get("/_cloudfront/" + dist.getId() + "/")
                .then().statusCode(404).body(equalTo("Distribution not found."));
    }

    @Test
    void servesDottedS3BucketWhoseNameContainsAnEndpointMarker() {
        String suffix = suffix();
        String shortBucket = "assets-" + suffix;
        String dottedBucket = shortBucket + ".s3.example";
        createBucket(shortBucket);
        createBucket(dottedBucket);
        putObject(shortBucket, "asset.txt", "WRONG-BUCKET", "text/plain");
        putObject(dottedBucket, "asset.txt", "DOTTED-BUCKET", "text/plain");

        DistributionConfig cfg = new DistributionConfig();
        cfg.setEnabled(true);
        cfg.setOrigins(List.of(s3Origin("dotted-origin", dottedBucket)));
        cfg.setDefaultCacheBehavior(defaultBehavior("dotted-origin"));
        Distribution dist = cloudFrontService.createDistribution(distribution(cfg), Map.of());

        given().header("Host", dist.getDomainName()).when().get("/asset.txt")
                .then().statusCode(200).body(equalTo("DOTTED-BUCKET"));
    }

    @Test
    void rejectsUnrecognizedDottedS3OriginHost() {
        String suffix = suffix();
        String bucket = "sensitive-" + suffix;
        createBucket(bucket);
        putObject(bucket, "asset.txt", "MUST-NOT-SERVE", "text/plain");

        Origin origin = new Origin();
        origin.setId("invalid-origin");
        origin.setDomainName(bucket + ".s3.attacker.invalid");
        origin.setS3OriginConfig(new LinkedHashMap<>(Map.of("OriginAccessIdentity", "")));
        DistributionConfig cfg = new DistributionConfig();
        cfg.setEnabled(true);
        cfg.setOrigins(List.of(origin));
        cfg.setDefaultCacheBehavior(defaultBehavior(origin.getId()));
        Distribution dist = cloudFrontService.createDistribution(distribution(cfg), Map.of());

        given().header("Host", dist.getDomainName()).when().get("/asset.txt")
                .then().statusCode(502);
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

    @Test
    void roundTripsTrustedKeyGroupsThroughDistributionConfigApi()
            throws Exception {
        String suffix = suffix();
        SignerResources defaultSigner =
                createSignerResources("default-" + suffix);
        SignerResources privateSigner =
                createSignerResources("private-" + suffix);
        String body = distributionConfigXml("trusted-key-groups-roundtrip-" + suffix, """
                <DefaultCacheBehavior>
                  <TargetOriginId>o1</TargetOriginId>
                  <ViewerProtocolPolicy>allow-all</ViewerProtocolPolicy>
                  <TrustedKeyGroups>
                    <Enabled>true</Enabled>
                    <Quantity>1</Quantity>
                    <Items><KeyGroup>%s</KeyGroup></Items>
                  </TrustedKeyGroups>
                </DefaultCacheBehavior>
                <CacheBehaviors>
                  <Quantity>1</Quantity>
                  <Items>
                    <CacheBehavior>
                      <PathPattern>/private/*</PathPattern>
                      <TargetOriginId>o1</TargetOriginId>
                      <ViewerProtocolPolicy>allow-all</ViewerProtocolPolicy>
                      <TrustedKeyGroups>
                        <Enabled>true</Enabled>
                        <Quantity>1</Quantity>
                        <Items><KeyGroup>%s</KeyGroup></Items>
                      </TrustedKeyGroups>
                    </CacheBehavior>
                  </Items>
                </CacheBehaviors>
                """.formatted(
                        defaultSigner.keyGroupId(),
                        privateSigner.keyGroupId()));

        var created = given()
                .contentType("application/xml")
                .body(body)
            .when()
                .post("/2020-05-31/distribution")
            .then()
                .statusCode(201)
                .body(containsString("<ActiveTrustedKeyGroups>"))
                .body(containsString("<Enabled>true</Enabled>"))
                .body(containsString(
                        "<KeyGroupId>" + defaultSigner.keyGroupId()
                                + "</KeyGroupId>"))
                .body(containsString(
                        "<KeyPairId>" + defaultSigner.publicKeyId()
                                + "</KeyPairId>"))
                .body(containsString("<ActiveTrustedSigners>"))
                .extract().response();

        String configPath = created.header("Location") + "/config";
        var config = given()
            .when()
                .get(configPath)
            .then()
                .statusCode(200)
                .extract().response();

        String configXml = config.asString();
        assertTrue(configXml.contains(
                trustedKeyGroupsXml(defaultSigner.keyGroupId())));
        assertTrue(configXml.contains(
                trustedKeyGroupsXml(privateSigner.keyGroupId())));

        given()
                .contentType("application/xml")
                .header("If-Match", config.header("ETag"))
                .body(configXml)
            .when()
                .put(configPath)
            .then()
                .statusCode(200)
                .body(containsString(
                        trustedKeyGroupsXml(defaultSigner.keyGroupId())))
                .body(containsString(
                        trustedKeyGroupsXml(privateSigner.keyGroupId())))
                .body(containsString("<ActiveTrustedKeyGroups>"))
                .body(containsString(
                        "<KeyGroupId>" + privateSigner.keyGroupId()
                                + "</KeyGroupId>"));
    }

    @Test
    void disabledTrustedKeyGroupsRemainConfiguredButDoNotRequireSignatures()
            throws Exception {
        String suffix = suffix();
        SignerResources defaultSigner =
                createSignerResources("disabled-default-" + suffix);
        SignerResources privateSigner =
                createSignerResources("disabled-private-" + suffix);
        String body = distributionConfigXml("disabled-key-groups-" + suffix, """
                <DefaultCacheBehavior>
                  <TargetOriginId>o1</TargetOriginId>
                  <ViewerProtocolPolicy>allow-all</ViewerProtocolPolicy>
                  <TrustedKeyGroups>
                    <Enabled>false</Enabled>
                    <Quantity>1</Quantity>
                    <Items><KeyGroup>%s</KeyGroup></Items>
                  </TrustedKeyGroups>
                </DefaultCacheBehavior>
                <CacheBehaviors>
                  <Quantity>1</Quantity>
                  <Items>
                    <CacheBehavior>
                      <PathPattern>/private/*</PathPattern>
                      <TargetOriginId>o1</TargetOriginId>
                      <ViewerProtocolPolicy>allow-all</ViewerProtocolPolicy>
                      <TrustedKeyGroups>
                        <Enabled>false</Enabled>
                        <Quantity>1</Quantity>
                        <Items><KeyGroup>%s</KeyGroup></Items>
                      </TrustedKeyGroups>
                    </CacheBehavior>
                  </Items>
                </CacheBehaviors>
                """.formatted(
                        defaultSigner.keyGroupId(),
                        privateSigner.keyGroupId()));

        var created = given()
                .contentType("application/xml")
                .body(body)
            .when()
                .post("/2020-05-31/distribution")
            .then()
                .statusCode(201)
                .body(containsString("<Enabled>false</Enabled>"))
                .body(containsString(
                        "<ActiveTrustedKeyGroups><Enabled>false</Enabled>"
                                + "<Quantity>0</Quantity></ActiveTrustedKeyGroups>"))
                .extract().response();

        String configPath = created.header("Location") + "/config";
        var config = given()
            .when()
                .get(configPath)
            .then()
                .statusCode(200)
                .body(containsString("<Enabled>false</Enabled>"))
                .extract().response();

        given()
                .contentType("application/xml")
                .header("If-Match", config.header("ETag"))
                .body(config.asString())
            .when()
                .put(configPath)
            .then()
                .statusCode(200)
                .body(containsString("<Enabled>false</Enabled>"));

        String distributionId = XmlParser.extractFirst(
                created.asString(), "Id", null);
        DistributionConfig stored = cloudFrontService
                .getDistribution(distributionId)
                .getConfig();

        assertFalse(stored.getDefaultCacheBehavior()
                .isTrustedKeyGroupsEnabled());
        assertEquals(
                List.of(defaultSigner.keyGroupId()),
                stored.getDefaultCacheBehavior().getTrustedKeyGroups());
        assertFalse(stored.getCacheBehaviors().getFirst()
                .isTrustedKeyGroupsEnabled());
        assertEquals(
                List.of(privateSigner.keyGroupId()),
                stored.getCacheBehaviors().getFirst().getTrustedKeyGroups());
        assertTrue(CloudFrontRequestRouter
                .trustedKeyGroupsFor(stored, "/public/file.txt")
                .isEmpty());
        assertTrue(CloudFrontRequestRouter
                .trustedKeyGroupsFor(stored, "/private/file.txt")
                .isEmpty());
    }

    @Test
    void rejectsEnabledTrustedKeyGroupsWithoutItems() {
        String callerReference =
                "empty-enabled-key-groups-" + suffix();
        String body = distributionConfigXml(callerReference, """
                <DefaultCacheBehavior>
                  <TargetOriginId>o1</TargetOriginId>
                  <ViewerProtocolPolicy>allow-all</ViewerProtocolPolicy>
                  <TrustedKeyGroups>
                    <Enabled>true</Enabled>
                    <Quantity>0</Quantity>
                  </TrustedKeyGroups>
                </DefaultCacheBehavior>
                """);

        given()
            .contentType("application/xml")
            .body(body)
        .when()
            .post("/2020-05-31/distribution")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidArgument</Code>"));

        assertDistributionWasNotCreated(callerReference);
    }

    @Test
    void rejectsUnknownTrustedKeyGroup() {
        String callerReference =
                "unknown-key-group-" + suffix();
        String body = distributionConfigXml(callerReference, """
                <DefaultCacheBehavior>
                  <TargetOriginId>o1</TargetOriginId>
                  <ViewerProtocolPolicy>allow-all</ViewerProtocolPolicy>
                  <TrustedKeyGroups>
                    <Enabled>true</Enabled>
                    <Quantity>1</Quantity>
                    <Items><KeyGroup>missing-key-group</KeyGroup></Items>
                  </TrustedKeyGroups>
                </DefaultCacheBehavior>
                """);

        given()
            .contentType("application/xml")
            .body(body)
        .when()
            .post("/2020-05-31/distribution")
        .then()
            .statusCode(400)
            .body(containsString(
                    "<Code>TrustedKeyGroupDoesNotExist</Code>"));

        assertDistributionWasNotCreated(callerReference);
    }

    @Test
    void rejectsMalformedTrustedKeyGroupsInDefaultBehavior() {
        String callerReference = "malformed-default-key-groups-" + suffix();
        String body = distributionConfigXml(callerReference, """
                <DefaultCacheBehavior>
                  <TargetOriginId>o1</TargetOriginId>
                  <ViewerProtocolPolicy>allow-all</ViewerProtocolPolicy>
                  <TrustedKeyGroups>
                    <Quantity>1</Quantity>
                    <Items><KeyGroup>kg-private</KeyGroup></Items>
                  </TrustedKeyGroups>
                </DefaultCacheBehavior>
                """);

        given()
            .contentType("application/xml")
            .body(body)
        .when()
            .post("/2020-05-31/distribution")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidArgument</Code>"));

        assertDistributionWasNotCreated(callerReference);
    }

    @Test
    void rejectsTrustedKeyGroupsWithoutQuantityInDefaultBehavior() {
        String callerReference = "missing-default-key-group-quantity-" + suffix();
        String body = distributionConfigXml(callerReference, """
                <DefaultCacheBehavior>
                  <TargetOriginId>o1</TargetOriginId>
                  <ViewerProtocolPolicy>allow-all</ViewerProtocolPolicy>
                  <TrustedKeyGroups>
                    <Enabled>true</Enabled>
                    <Items><KeyGroup>kg-private</KeyGroup></Items>
                  </TrustedKeyGroups>
                </DefaultCacheBehavior>
                """);

        given()
            .contentType("application/xml")
            .body(body)
        .when()
            .post("/2020-05-31/distribution")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidArgument</Code>"));

        assertDistributionWasNotCreated(callerReference);
    }

    @Test
    void rejectsTrustedKeyGroupsWithInconsistentQuantity() {
        String callerReference = "inconsistent-key-group-quantity-" + suffix();
        String body = distributionConfigXml(callerReference, """
                <DefaultCacheBehavior>
                  <TargetOriginId>o1</TargetOriginId>
                  <ViewerProtocolPolicy>allow-all</ViewerProtocolPolicy>
                  <TrustedKeyGroups>
                    <Enabled>true</Enabled>
                    <Quantity>2</Quantity>
                    <Items><KeyGroup>kg-private</KeyGroup></Items>
                  </TrustedKeyGroups>
                </DefaultCacheBehavior>
                """);

        given()
            .contentType("application/xml")
            .body(body)
        .when()
            .post("/2020-05-31/distribution")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InconsistentQuantities</Code>"));

        assertDistributionWasNotCreated(callerReference);
    }

    @Test
    void rejectsMalformedTrustedKeyGroupsInOrderedBehavior() {
        String callerReference = "malformed-ordered-key-groups-" + suffix();
        String body = distributionConfigXml(callerReference, """
                <DefaultCacheBehavior>
                  <TargetOriginId>o1</TargetOriginId>
                  <ViewerProtocolPolicy>allow-all</ViewerProtocolPolicy>
                </DefaultCacheBehavior>
                <CacheBehaviors>
                  <Quantity>1</Quantity>
                  <Items>
                    <CacheBehavior>
                      <PathPattern>/private/*</PathPattern>
                      <TargetOriginId>o1</TargetOriginId>
                      <ViewerProtocolPolicy>allow-all</ViewerProtocolPolicy>
                      <TrustedKeyGroups>
                        <Quantity>1</Quantity>
                        <Items><KeyGroup>kg-private</KeyGroup></Items>
                      </TrustedKeyGroups>
                    </CacheBehavior>
                  </Items>
                </CacheBehaviors>
                """);

        given()
            .contentType("application/xml")
            .body(body)
        .when()
            .post("/2020-05-31/distribution")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidArgument</Code>"));

        assertDistributionWasNotCreated(callerReference);
    }

    @Test
    void rejectsTrustedKeyGroupsWithoutQuantityInOrderedBehavior() {
        String callerReference = "missing-ordered-key-group-quantity-" + suffix();
        String body = distributionConfigXml(callerReference, """
                <DefaultCacheBehavior>
                  <TargetOriginId>o1</TargetOriginId>
                  <ViewerProtocolPolicy>allow-all</ViewerProtocolPolicy>
                </DefaultCacheBehavior>
                <CacheBehaviors>
                  <Quantity>1</Quantity>
                  <Items>
                    <CacheBehavior>
                      <PathPattern>/private/*</PathPattern>
                      <TargetOriginId>o1</TargetOriginId>
                      <ViewerProtocolPolicy>allow-all</ViewerProtocolPolicy>
                      <TrustedKeyGroups>
                        <Enabled>true</Enabled>
                        <Items><KeyGroup>kg-private</KeyGroup></Items>
                      </TrustedKeyGroups>
                    </CacheBehavior>
                  </Items>
                </CacheBehaviors>
                """);

        given()
            .contentType("application/xml")
            .body(body)
        .when()
            .post("/2020-05-31/distribution")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidArgument</Code>"));

        assertDistributionWasNotCreated(callerReference);
    }

    @Test
    void roundTripsOriginCustomHeadersThroughTheApi() {
        // The distribution parser must capture and re-serialize origin CustomHeaders so a read-back
        // (get-distribution) matches what was configured, rather than dropping them.
        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <DistributionConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                  <CallerReference>cf-custom-headers-roundtrip</CallerReference>
                  <Origins>
                    <Quantity>1</Quantity>
                    <Items>
                      <Origin>
                        <Id>secured</Id>
                        <DomainName>api.internal.example</DomainName>
                        <OriginPath></OriginPath>
                        <CustomHeaders>
                          <Quantity>1</Quantity>
                          <Items>
                            <OriginCustomHeader>
                              <HeaderName>X-Origin-Verify</HeaderName>
                              <HeaderValue>shared-secret-42</HeaderValue>
                            </OriginCustomHeader>
                          </Items>
                        </CustomHeaders>
                        <CustomOriginConfig>
                          <HTTPPort>80</HTTPPort>
                          <HTTPSPort>443</HTTPSPort>
                          <OriginProtocolPolicy>https-only</OriginProtocolPolicy>
                        </CustomOriginConfig>
                      </Origin>
                    </Items>
                  </Origins>
                  <DefaultCacheBehavior>
                    <TargetOriginId>secured</TargetOriginId>
                    <ViewerProtocolPolicy>https-only</ViewerProtocolPolicy>
                  </DefaultCacheBehavior>
                  <Comment>custom-headers round-trip</Comment>
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
            .body(containsString("<OriginCustomHeader>"))
            .body(containsString("<HeaderName>X-Origin-Verify</HeaderName>"))
            .body(containsString("<HeaderValue>shared-secret-42</HeaderValue>"));
    }

    @Test
    void rejectsProhibitedOriginCustomHeaders() {
        String body = """
                <DistributionConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                  <CallerReference>cf-prohibited-custom-header</CallerReference>
                  <Origins><Quantity>1</Quantity><Items><Origin>
                    <Id>secured</Id><DomainName>example.com</DomainName>
                    <CustomHeaders><Quantity>1</Quantity><Items><OriginCustomHeader>
                      <HeaderName>Host</HeaderName><HeaderValue>internal.example</HeaderValue>
                    </OriginCustomHeader></Items></CustomHeaders>
                    <CustomOriginConfig><HTTPPort>80</HTTPPort><HTTPSPort>443</HTTPSPort>
                      <OriginProtocolPolicy>https-only</OriginProtocolPolicy></CustomOriginConfig>
                  </Origin></Items></Origins>
                  <DefaultCacheBehavior><TargetOriginId>secured</TargetOriginId>
                    <ViewerProtocolPolicy>https-only</ViewerProtocolPolicy></DefaultCacheBehavior>
                  <Enabled>true</Enabled>
                </DistributionConfig>
                """;

        given()
            .contentType("application/xml")
            .body(body)
        .when()
            .post("/2020-05-31/distribution")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidArgument</Code>"));
    }

    @Test
    void acceptsAuthorizationOriginCustomHeaders() {
        String body = distributionWithOriginCustomHeaders(1, """
                <OriginCustomHeader>
                  <HeaderName>Authorization</HeaderName>
                  <HeaderValue>Bearer secret</HeaderValue>
                </OriginCustomHeader>
                """).replace("cf-custom-header-validation", "cf-authorization-custom-header");

        given()
            .contentType("application/xml")
            .body(body)
        .when()
            .post("/2020-05-31/distribution")
        .then()
            .statusCode(201)
            .body(containsString("<HeaderName>Authorization</HeaderName>"))
            .body(containsString("<HeaderValue>Bearer secret</HeaderValue>"));
    }

    @Test
    void rejectsInconsistentOriginCustomHeaderQuantity() {
        String body = distributionWithOriginCustomHeaders(2, """
                <OriginCustomHeader>
                  <HeaderName>X-Origin-Verify</HeaderName>
                  <HeaderValue>shared-secret</HeaderValue>
                </OriginCustomHeader>
                """);

        given()
            .contentType("application/xml")
            .body(body)
        .when()
            .post("/2020-05-31/distribution")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InconsistentQuantities</Code>"));
    }

    @Test
    void serializesEmptyOriginCustomHeadersWithZeroQuantity() {
        given()
            .contentType("application/xml")
            .body(distributionWithOriginCustomHeaders(0, "", false))
        .when()
            .post("/2020-05-31/distribution")
        .then()
            .statusCode(201)
            .body(containsString("<CustomHeaders><Quantity>0</Quantity></CustomHeaders>"));
    }

    @Test
    void rejectsMalformedOriginCustomHeaderStructure() {
        String item = """
                <OriginCustomHeader>
                  <HeaderName>X-Origin-Verify</HeaderName>
                  <HeaderValue>shared-secret</HeaderValue>
                </OriginCustomHeader>
                """;
        List<String> malformedBodies = List.of(
                distributionWithOriginCustomHeaders(1, item, false),
                distributionWithOriginCustomHeaders(1, """
                        <Header>
                          <HeaderName>X-Origin-Verify</HeaderName>
                          <HeaderValue>shared-secret</HeaderValue>
                        </Header>
                        """),
                distributionWithOriginCustomHeaders(1, """
                        <OriginCustomHeader>
                          <HeaderName><Value>X-Origin-Verify</Value></HeaderName>
                          <HeaderValue>shared-secret</HeaderValue>
                        </OriginCustomHeader>
                        """));

        for (String body : malformedBodies) {
            given()
                .contentType("application/xml")
                .body(body)
            .when()
                .post("/2020-05-31/distribution")
            .then()
                .statusCode(400)
                .body(containsString("<Code>InvalidArgument</Code>"));
        }
    }

    @Test
    void rejectsTooManyOriginCustomHeadersWithAwsErrorCode() {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 31; i++) {
            items.append("""
                    <OriginCustomHeader>
                      <HeaderName>X-Origin-%d</HeaderName>
                      <HeaderValue>value</HeaderValue>
                    </OriginCustomHeader>
                    """.formatted(i));
        }
        String body = distributionWithOriginCustomHeaders(31, items.toString());

        given()
            .contentType("application/xml")
            .body(body)
        .when()
            .post("/2020-05-31/distribution")
        .then()
            .statusCode(400)
            .body(containsString("<Code>TooManyOriginCustomHeaders</Code>"));
    }

    /**
     * A rule whose {@code AllowedOrigin} is {@code *} echoes {@code *}, and the CORS specification
     * forbids pairing that with {@code Access-Control-Allow-Credentials: true} — browsers reject the
     * combination for credentialed requests, so emitting it would break in the browser while passing
     * here. Verified against real S3: a wildcard rule returns only Allow-Origin, Allow-Methods and
     * Max-Age, with no Allow-Credentials and no Vary; a concrete origin returns both (covered by
     * {@link #appliesConfiguredOriginHeaderToS3CorsResponses()}).
     */
    @Test
    void wildcardCorsOriginOmitsCredentialsAndVary() {
        String suffix = suffix();
        String bucket = "cf-origin-header-cors-wild-" + suffix;
        createBucket(bucket);
        putObject(bucket, "asset.txt", "cors-body", "text/plain");
        s3Service.putBucketCors(bucket, """
                <CORSConfiguration>
                  <CORSRule>
                    <AllowedOrigin>*</AllowedOrigin>
                    <AllowedMethod>GET</AllowedMethod>
                    <AllowedMethod>HEAD</AllowedMethod>
                    <MaxAgeSeconds>600</MaxAgeSeconds>
                  </CORSRule>
                </CORSConfiguration>
                """);

        Origin origin = s3Origin("s3-origin", bucket);
        origin.setCustomHeaders(List.of(new LinkedHashMap<>(Map.of(
                "HeaderName", "Origin",
                "HeaderValue", "https://configured.example"))));
        DistributionConfig cfg = new DistributionConfig();
        cfg.setEnabled(true);
        cfg.setOrigins(List.of(origin));
        DefaultCacheBehavior behavior = defaultBehavior(origin.getId());
        behavior.setAllowedMethods(List.of("GET", "HEAD", "OPTIONS"));
        cfg.setDefaultCacheBehavior(behavior);
        Distribution dist = cloudFrontService.createDistribution(distribution(cfg), Map.of());

        given()
            .header("Host", dist.getDomainName())
            .header("Origin", "https://viewer.example")
        .when()
            .get("/asset.txt")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", equalTo("*"))
            .header("Access-Control-Allow-Methods", equalTo("GET, HEAD"))
            .header("Access-Control-Max-Age", equalTo("600"))
            .header("Access-Control-Allow-Credentials", nullValue())
            .header("Vary", nullValue());

        given()
            .header("Host", dist.getDomainName())
            .header("Origin", "https://viewer.example")
            .header("Access-Control-Request-Method", "GET")
        .when()
            .options("/asset.txt")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", equalTo("*"))
            .header("Access-Control-Allow-Methods", equalTo("GET, HEAD"))
            .header("Access-Control-Max-Age", equalTo("600"))
            .header("Access-Control-Allow-Credentials", nullValue())
            .header("Vary", nullValue());
    }

    @Test
    void appliesConfiguredOriginHeaderToS3CorsResponses() {
        String suffix = suffix();
        String bucket = "cf-origin-header-cors-" + suffix;
        createBucket(bucket);
        putObject(bucket, "asset.txt", "cors-body", "text/plain");
        s3Service.putBucketCors(bucket, """
                <CORSConfiguration>
                  <CORSRule>
                    <AllowedOrigin>https://configured.example</AllowedOrigin>
                    <AllowedMethod>GET</AllowedMethod>
                    <AllowedMethod>HEAD</AllowedMethod>
                    <ExposeHeader>ETag</ExposeHeader>
                    <MaxAgeSeconds>600</MaxAgeSeconds>
                  </CORSRule>
                </CORSConfiguration>
                """);

        Origin origin = s3Origin("s3-origin", bucket);
        origin.setCustomHeaders(List.of(new LinkedHashMap<>(Map.of(
                "HeaderName", "Origin",
                "HeaderValue", "https://configured.example"))));
        DistributionConfig cfg = new DistributionConfig();
        cfg.setEnabled(true);
        cfg.setOrigins(List.of(origin));
        cfg.setDefaultCacheBehavior(defaultBehavior(origin.getId()));
        Distribution dist = cloudFrontService.createDistribution(distribution(cfg), Map.of());

        given()
            .header("Host", dist.getDomainName())
            .header("Origin", "https://viewer.example")
        .when()
            .get("/asset.txt")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", equalTo("https://configured.example"))
            .header("Access-Control-Allow-Methods", equalTo("GET, HEAD"))
            .header("Access-Control-Allow-Credentials", equalTo("true"))
            .header("Access-Control-Max-Age", equalTo("600"))
            .header("Access-Control-Expose-Headers", equalTo("ETag"))
            .header("Vary", containsString("Origin"))
            .header("Vary", containsString("Access-Control-Request-Headers"))
            .header("Vary", containsString("Access-Control-Request-Method"));

        given()
            .header("Host", dist.getDomainName())
            .header("Origin", "https://viewer.example")
        .when()
            .head("/asset.txt")
        .then()
            .statusCode(200)
            .header("Access-Control-Allow-Origin", equalTo("https://configured.example"))
            .header("Access-Control-Allow-Methods", equalTo("GET, HEAD"))
            .header("Access-Control-Allow-Credentials", equalTo("true"))
            .header("Access-Control-Max-Age", equalTo("600"))
            .header("Access-Control-Expose-Headers", equalTo("ETag"))
            .header("Vary", containsString("Origin"))
            .header("Vary", containsString("Access-Control-Request-Headers"))
            .header("Vary", containsString("Access-Control-Request-Method"));
    }

    @Test
    void appliesConfiguredOriginHeaderToS3CorsPreflight() {
        String suffix = suffix();
        String bucket = "cf-origin-header-preflight-" + suffix;
        createBucket(bucket);
        s3Service.putBucketCors(bucket, """
                <CORSConfiguration>
                  <CORSRule>
                    <AllowedOrigin>https://configured.example</AllowedOrigin>
                    <AllowedMethod>GET</AllowedMethod>
                    <AllowedHeader>Authorization</AllowedHeader>
                  </CORSRule>
                </CORSConfiguration>
                """);

        Origin origin = s3Origin("s3-origin", bucket);
        origin.setCustomHeaders(List.of(new LinkedHashMap<>(Map.of(
                "HeaderName", "Origin",
                "HeaderValue", "https://configured.example"))));
        DefaultCacheBehavior behavior = defaultBehavior(origin.getId());
        behavior.setAllowedMethods(List.of("GET", "HEAD", "OPTIONS"));
        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setOrigins(List.of(origin));
        config.setDefaultCacheBehavior(behavior);
        Distribution distribution =
                cloudFrontService.createDistribution(distribution(config), Map.of());

        given()
                .header("Host", distribution.getDomainName())
                .header("Origin", "https://viewer.example")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization")
                .when().options("/asset.txt")
                .then().statusCode(200)
                .header("Access-Control-Allow-Origin", equalTo("https://configured.example"))
                .header("Access-Control-Allow-Methods", equalTo("GET"))
                .header("Access-Control-Allow-Headers", equalTo("Authorization"))
                .header("Access-Control-Allow-Credentials", equalTo("true"))
                .header("Vary", containsString("Origin"))
                .header("Vary", containsString("Access-Control-Request-Headers"))
                .header("Vary", containsString("Access-Control-Request-Method"));
    }

    private static String distributionWithOriginCustomHeaders(int quantity, String items) {
        return distributionWithOriginCustomHeaders(quantity, items, true);
    }

    private static String distributionWithOriginCustomHeaders(
            int quantity, String items, boolean includeItems) {
        String itemList = includeItems ? "<Items>" + items + "</Items>" : items;
        return """
                <DistributionConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                  <CallerReference>cf-custom-header-validation</CallerReference>
                  <Origins><Quantity>1</Quantity><Items><Origin>
                    <Id>secured</Id><DomainName>example.com</DomainName>
                    <CustomHeaders><Quantity>%d</Quantity>%s</CustomHeaders>
                    <CustomOriginConfig><HTTPPort>80</HTTPPort><HTTPSPort>443</HTTPSPort>
                      <OriginProtocolPolicy>https-only</OriginProtocolPolicy></CustomOriginConfig>
                  </Origin></Items></Origins>
                  <DefaultCacheBehavior><TargetOriginId>secured</TargetOriginId>
                    <ViewerProtocolPolicy>https-only</ViewerProtocolPolicy></DefaultCacheBehavior>
                  <Enabled>true</Enabled>
                </DistributionConfig>
                """.formatted(quantity, itemList);
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
        behavior.setAllowedMethods(List.of("GET", "HEAD", "OPTIONS"));
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

        given()
                .header("Host", distribution.getDomainName())
                .header("Access-Control-Request-Method", "GET")
                .when().options("/resource")
                .then().statusCode(403);

        given()
                .header("Host", distribution.getDomainName())
                .header("Origin", "https://viewer.example")
                .when().options("/resource")
                .then().statusCode(403);

        given()
                .header("Host", distribution.getDomainName())
                .header("Origin", "https://viewer.example")
                .header("Access-Control-Request-Method", "POST")
                .when().options("/resource")
                .then().statusCode(403);
    }

    @Test
    void corsOriginOverrideIsGroupWideButDoesNotSuppressCustomHeaders() {
        String suffix = suffix();
        String bucket = "cf-cors-origin-override-" + suffix;
        createBucket(bucket);
        s3Service.putBucketCors(bucket, """
                <CORSConfiguration><CORSRule>
                  <AllowedOrigin>*</AllowedOrigin>
                  <AllowedMethod>GET</AllowedMethod>
                </CORSRule></CORSConfiguration>
                """);

        Map<String, Object> cors = new LinkedHashMap<>();
        cors.put("AccessControlAllowCredentials", "false");
        cors.put("AccessControlAllowHeaders", List.of());
        cors.put("AccessControlAllowMethods", List.of("GET"));
        cors.put("AccessControlAllowOrigins", List.of("*"));
        cors.put("AccessControlExposeHeaders", List.of());
        cors.put("AccessControlMaxAgeSec", "600");
        cors.put("OriginOverride", "false");

        Map<String, Object> policyConfig = new LinkedHashMap<>();
        policyConfig.put("CorsConfig", cors);
        policyConfig.put("CustomHeadersConfig", List.of(
                policyHeader("Access-Control-X-Floci", "custom", false)));
        ResponseHeadersPolicy policy = new ResponseHeadersPolicy();
        policy.setName("cors-origin-override-" + suffix);
        policy.setConfig(policyConfig);
        policy = cloudFrontService.createResponseHeadersPolicy(policy);

        DefaultCacheBehavior behavior = defaultBehavior("only-origin");
        behavior.setAllowedMethods(List.of("GET", "HEAD", "OPTIONS"));
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
                .when().options("/resource")
                .then().statusCode(200)
                .header("Access-Control-Allow-Origin", equalTo("*"))
                .header("Access-Control-Max-Age", nullValue())
                .header("Access-Control-X-Floci", equalTo("custom"));
    }

    @Test
    void rejectsOptionsWhenTheMatchedBehaviorDoesNotAllowIt() {
        String suffix = suffix();
        String bucket = "cf-options-" + suffix;
        createBucket(bucket);
        s3Service.putBucketCors(bucket, """
                <CORSConfiguration><CORSRule>
                  <AllowedOrigin>*</AllowedOrigin>
                  <AllowedMethod>GET</AllowedMethod>
                </CORSRule></CORSConfiguration>
                """);

        DefaultCacheBehavior dflt = defaultBehavior("only-origin");
        dflt.setAllowedMethods(List.of("GET", "HEAD", "OPTIONS"));
        CacheBehavior api = behavior("api/*", "only-origin");
        api.setAllowedMethods(List.of("GET", "HEAD"));
        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setOrigins(List.of(s3Origin("only-origin", bucket)));
        config.setDefaultCacheBehavior(dflt);
        config.setCacheBehaviors(List.of(api));
        Distribution distribution = cloudFrontService.createDistribution(distribution(config), Map.of());

        given()
                .header("Host", distribution.getDomainName())
                .header("Origin", "https://viewer.example")
                .header("Access-Control-Request-Method", "GET")
                .when().options("/api/resource")
                .then().statusCode(403)
                .body(equalTo("Invalid method."));

        given()
                .header("Host", distribution.getDomainName())
                .header("Origin", "https://viewer.example")
                .header("Access-Control-Request-Method", "GET")
                .when().options("/resource")
                .then().statusCode(200);
    }

    @Test
    void managedPreflightPoliciesEmitOnlyAllowOriginForSimpleRequests() {
        String suffix = suffix();
        String bucket = "cf-managed-cors-" + suffix;
        createBucket(bucket);
        putObject(bucket, "index.html", "MANAGED-CORS-" + suffix, "text/html");

        for (String policyId : List.of(
                CloudFrontService.MANAGED_CORS_PREFLIGHT_POLICY_ID,
                CloudFrontService.MANAGED_CORS_PREFLIGHT_AND_SECURITY_POLICY_ID)) {
            DefaultCacheBehavior behavior = defaultBehavior("only-origin");
            behavior.setResponseHeadersPolicyId(policyId);
            DistributionConfig config = new DistributionConfig();
            config.setEnabled(true);
            config.setDefaultRootObject("index.html");
            config.setOrigins(List.of(s3Origin("only-origin", bucket)));
            config.setDefaultCacheBehavior(behavior);
            Distribution distribution =
                    cloudFrontService.createDistribution(distribution(config), Map.of());

            given()
                    .header("Host", distribution.getDomainName())
                    .header("Origin", "https://viewer.example")
                    .when().get("/")
                    .then().statusCode(200)
                    .header("Access-Control-Allow-Origin", equalTo("*"))
                    .header("Access-Control-Expose-Headers", nullValue())
                    .header("Access-Control-Allow-Methods", nullValue());
        }
    }

    @Test
    void pragmaForcesServerTimingWhenSamplingRateIsZero() {
        String suffix = suffix();
        String bucket = "cf-timing-" + suffix;
        createBucket(bucket);
        putObject(bucket, "index.html", "TIMING-" + suffix, "text/html");

        ResponseHeadersPolicy policy = new ResponseHeadersPolicy();
        policy.setName("timing-" + suffix);
        policy.setConfig(Map.of("ServerTimingHeadersConfig",
                Map.of("Enabled", "true", "SamplingRate", "0")));
        policy = cloudFrontService.createResponseHeadersPolicy(policy);
        DefaultCacheBehavior behavior = defaultBehavior("only-origin");
        behavior.setResponseHeadersPolicyId(policy.getId());
        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setDefaultRootObject("index.html");
        config.setOrigins(List.of(s3Origin("only-origin", bucket)));
        config.setDefaultCacheBehavior(behavior);
        Distribution distribution = cloudFrontService.createDistribution(distribution(config), Map.of());

        given()
                .header("Host", distribution.getDomainName())
                .when().get("/")
                .then().statusCode(200)
                .header("Server-Timing", nullValue());

        given()
                .header("Host", distribution.getDomainName())
                .header("Pragma", "server-timing")
                .when().get("/")
                .then().statusCode(200)
                .header("Server-Timing", containsString("cdn-cache-miss"));
    }

    @Test
    void listsManagedResponseHeadersPoliciesWithAwsModeledPayloadRoot() {
        String body = given()
                .queryParam("Type", "managed")
                .when().get("/2020-05-31/response-headers-policy")
                .then().statusCode(200)
                .extract().asString();

        assertTrue(body.startsWith("<ResponseHeadersPolicyList xmlns=\""
                + "http://cloudfront.amazonaws.com/doc/2020-05-31/\">"), body);
        assertFalse(body.contains("<ListResponseHeadersPoliciesResult"), body);
        assertTrue(body.contains("<Quantity>5</Quantity>"), body);
        assertFalse(body.contains("<NextMarker>"), body);
        assertFalse(body.contains("<Marker>"), body);
        assertFalse(body.contains("<IsTruncated>"), body);
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
        assertTrue(firstPage.contains("<NextMarker>"), firstPage);
        assertFalse(firstPage.contains("<IsTruncated>"), firstPage);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static String trustedKeyGroupsXml(String keyGroup) {
        return "<TrustedKeyGroups><Enabled>true</Enabled><Quantity>1</Quantity>"
                + "<Items><KeyGroup>" + keyGroup + "</KeyGroup></Items></TrustedKeyGroups>";
    }

    private String distributionConfigXml(String callerReference, String cacheBehaviors) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <DistributionConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                  <CallerReference>%s</CallerReference>
                  <Origins>
                    <Quantity>1</Quantity>
                    <Items>
                      <Origin>
                        <Id>o1</Id>
                        <DomainName>malformed-key-groups.s3.us-east-1.amazonaws.com</DomainName>
                        <S3OriginConfig><OriginAccessIdentity></OriginAccessIdentity></S3OriginConfig>
                      </Origin>
                    </Items>
                  </Origins>
                  %s
                  <Comment>must not be created</Comment>
                  <Enabled>true</Enabled>
                </DistributionConfig>
                """.formatted(callerReference, cacheBehaviors);
    }

    private void assertDistributionWasNotCreated(String callerReference) {
        assertTrue(cloudFrontService.listDistributions(null, Integer.MAX_VALUE).stream()
                .noneMatch(d -> callerReference.equals(d.getConfig().getCallerReference())));
    }

    private SignerResources createSignerResources(String name)
            throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String encodedKey = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(
                        generator.generateKeyPair().getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
        PublicKey publicKey = new PublicKey();
        publicKey.setCallerReference("reference-" + name);
        publicKey.setName("key-" + name);
        publicKey.setEncodedKey(encodedKey);
        publicKey = cloudFrontService.createPublicKey(publicKey);

        KeyGroup keyGroup = new KeyGroup();
        keyGroup.setName("group-" + name);
        keyGroup.setItems(List.of(publicKey.getId()));
        keyGroup = cloudFrontService.createKeyGroup(keyGroup);
        return new SignerResources(publicKey.getId(), keyGroup.getId());
    }

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

    private record SignerResources(
            String publicKeyId, String keyGroupId) {
    }
}
