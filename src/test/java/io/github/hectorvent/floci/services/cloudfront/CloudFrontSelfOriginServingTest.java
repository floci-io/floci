package io.github.hectorvent.floci.services.cloudfront;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.config.TlsProxyServer;
import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.when;

/**
 * Distributions whose custom origin is this Floci itself, addressed as {@code localhost} or a
 * loopback address on Floci's own port: a path-style S3 bucket fronted by CloudFront, the usual
 * local setup for a single-page app. No private origin host is allowed here.
 */
@QuarkusTest
class CloudFrontSelfOriginServingTest {

    private static final String CACHING_DISABLED = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad";
    private static final String REGION = "us-east-1";

    @Inject
    CloudFrontService cloudFrontService;

    @Inject
    S3Service s3Service;

    @InjectMock
    TlsProxyServer tlsProxyServer;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private String bucket;
    private String otherBucket;
    private HttpServer backend;

    @BeforeEach
    void createSite() {
        bucket = "self-origin-" + UUID.randomUUID().toString().substring(0, 8);
        s3Service.createBucket(bucket, REGION);
        s3Service.putObject(bucket, "index.html", "<h1>spa</h1>".getBytes(StandardCharsets.UTF_8),
                "text/html", Map.of());
        cleanup.register(() -> {
            s3Service.deleteObject(bucket, "index.html");
            s3Service.deleteBucket(bucket);
        });
        otherBucket = "self-origin-other-" + UUID.randomUUID().toString().substring(0, 8);
        s3Service.createBucket(otherBucket, REGION);
        s3Service.putObject(otherBucket, "secret.txt", "secret".getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of());
        cleanup.register(() -> {
            s3Service.deleteObject(otherBucket, "secret.txt");
            s3Service.deleteBucket(otherBucket);
        });
    }

    @AfterEach
    void stopBackend() {
        if (backend != null) {
            backend.stop(0);
        }
    }

    @Test
    void aLocalhostOriginOnFlociPortServesThePathStyleBucket() {
        Distribution created = createDistribution("localhost", "/" + bucket, RestAssured.port,
                CloudFrontService.MANAGED_ALL_VIEWER_EXCEPT_HOST_HEADER_ORIGIN_REQUEST_POLICY_ID);

        given().header("Host", created.getDomainName())
                .when().get("/index.html")
                .then().statusCode(200)
                .body(equalTo("<h1>spa</h1>"));
    }

    @Test
    void aLoopbackAddressOriginOnFlociPortServesThePathStyleBucket() {
        Distribution created = createDistribution("127.0.0.1", "/" + bucket, RestAssured.port,
                CloudFrontService.MANAGED_ALL_VIEWER_EXCEPT_HOST_HEADER_ORIGIN_REQUEST_POLICY_ID);

        given().header("Host", created.getDomainName())
                .when().get("/index.html")
                .then().statusCode(200)
                .body(equalTo("<h1>spa</h1>"));
    }

    @Test
    void dotSegmentsDoNotLeaveTheOriginPath() {
        Distribution created = createDistribution("localhost", "/" + bucket, RestAssured.port,
                CloudFrontService.MANAGED_ALL_VIEWER_EXCEPT_HOST_HEADER_ORIGIN_REQUEST_POLICY_ID);

        given().header("Host", created.getDomainName()).urlEncodingEnabled(false)
                .when().get("/x/../index.html")
                .then().statusCode(200)
                .body(equalTo("<h1>spa</h1>"));
    }

    @Test
    void dotSegmentsCannotClimbOutOfTheOriginPathIntoAnotherBucket() {
        Distribution created = createDistribution("localhost", "/" + bucket, RestAssured.port,
                CloudFrontService.MANAGED_ALL_VIEWER_EXCEPT_HOST_HEADER_ORIGIN_REQUEST_POLICY_ID);

        given().header("Host", created.getDomainName()).urlEncodingEnabled(false)
                .when().get("/x/../../" + otherBucket + "/secret.txt")
                .then().statusCode(404);
        // Floci's listener refuses encoded dot segments outright.
        given().header("Host", created.getDomainName()).urlEncodingEnabled(false)
                .when().get("/x/%2e%2e/%2e%2e/" + otherBucket + "/secret.txt")
                .then().statusCode(400);
    }

    @Test
    void anHttpsOriginOnTheTlsProxyAwsPortServesThePathStyleBucket() {
        when(tlsProxyServer.reservesPort(8443)).thenReturn(true);
        Distribution created = createDistribution("localhost", "/" + bucket, 80, 8443, "https-only",
                CloudFrontService.MANAGED_ALL_VIEWER_EXCEPT_HOST_HEADER_ORIGIN_REQUEST_POLICY_ID);

        given().header("Host", created.getDomainName())
                .when().get("/index.html")
                .then().statusCode(200)
                .body(equalTo("<h1>spa</h1>"));
    }

    @Test
    void forwardingTheViewerHostToASelfOriginIsABadGateway() {
        Distribution created = createDistribution("localhost", "/" + bucket, RestAssured.port,
                CloudFrontService.MANAGED_ALL_VIEWER_ORIGIN_REQUEST_POLICY_ID);

        given().header("Host", created.getDomainName())
                .when().get("/index.html")
                .then().statusCode(502);
    }

    @Test
    void aLocalhostOriginOnAnotherPortIsStillRefused() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            byte[] body = "private".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        backend.start();
        Distribution created = createDistribution("localhost", "", backend.getAddress().getPort(),
                CloudFrontService.MANAGED_ALL_VIEWER_EXCEPT_HOST_HEADER_ORIGIN_REQUEST_POLICY_ID);

        given().header("Host", created.getDomainName())
                .when().get("/index.html")
                .then().statusCode(502);
    }

    private Distribution createDistribution(String domain, String originPath, int originPort,
                                            String originRequestPolicyId) {
        return createDistribution(domain, originPath, originPort, 443, "http-only", originRequestPolicyId);
    }

    private Distribution createDistribution(String domain, String originPath, int httpPort, int httpsPort,
                                            String protocolPolicy, String originRequestPolicyId) {
        Origin origin = new Origin();
        origin.setId("self");
        origin.setDomainName(domain);
        origin.setOriginPath(originPath);
        Map<String, Object> customOriginConfig = new LinkedHashMap<>();
        customOriginConfig.put("HTTPPort", String.valueOf(httpPort));
        customOriginConfig.put("HTTPSPort", String.valueOf(httpsPort));
        customOriginConfig.put("OriginProtocolPolicy", protocolPolicy);
        origin.setCustomOriginConfig(customOriginConfig);
        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId("self");
        behavior.setViewerProtocolPolicy("allow-all");
        behavior.setAllowedMethods(List.of("GET", "HEAD"));
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
