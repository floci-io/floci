package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.services.cloudfront.CloudFrontService;
import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.KeyGroup;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.cloudfront.model.PublicKey;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Host-based routing over HTTPS with HTTP/2, where the client sends {@code :authority} and no
 * {@code Host} header. The client connects to 127.0.0.1 and names the service host only in
 * {@code :authority}, so no DNS lookup is needed.
 */
@QuarkusTest
@TestProfile(TlsIntegrationTest.TlsEnabledProfile.class)
class TlsHttp2HostRoutingIntegrationTest {

    private static final String REGION = "us-east-1";

    @ConfigProperty(name = "quarkus.http.test-ssl-port", defaultValue = "0")
    int sslPort;

    @Inject
    Vertx vertx;

    @Inject
    S3Service s3Service;

    @Inject
    CloudFrontService cloudFrontService;

    @Test
    void cloudFrontServesACannedSignedUrlOnTheLocalDeliveryHost() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cf-h2-" + suffix;
        s3Service.createBucket(bucket, REGION);
        s3Service.putObject(bucket, "private/report.txt", ("H2-" + suffix).getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of());

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

        Origin origin = new Origin();
        origin.setId("o");
        origin.setDomainName(bucket + ".s3." + REGION + ".amazonaws.com");
        origin.setS3OriginConfig(new LinkedHashMap<>(Map.of("OriginAccessIdentity", "")));
        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId("o");
        behavior.setViewerProtocolPolicy("allow-all");
        behavior.setTrustedKeyGroups(List.of(keyGroup.getId()));
        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setOrigins(List.of(origin));
        config.setDefaultCacheBehavior(behavior);
        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        distribution = cloudFrontService.createDistribution(distribution, Map.of());

        // A browser sends the host in lower case, and a canned policy signs the port too.
        String host = distribution.getId().toLowerCase(Locale.ROOT) + ".cloudfront.localhost.floci.io";
        long expires = Instant.now().getEpochSecond() + 3600;
        String policy = "{\"Statement\":[{\"Resource\":\"https://" + host + ":" + sslPort
                + "/private/report.txt\",\"Condition\":{\"DateLessThan\":{\"AWS:EpochTime\":" + expires + "}}}]}";
        Signature rsa = Signature.getInstance("SHA1withRSA");
        rsa.initSign(keyPair.getPrivate());
        rsa.update(policy.getBytes(StandardCharsets.UTF_8));

        Http2Response signed = getOverHttp2(host, "/private/report.txt?Expires=" + expires
                + "&Signature=" + cloudFrontBase64(rsa.sign()) + "&Key-Pair-Id=" + publicKey.getId());
        assertEquals(HttpVersion.HTTP_2, signed.version());
        assertEquals(200, signed.status(), signed.body());
        assertEquals("H2-" + suffix, signed.body());

        Http2Response unsigned = getOverHttp2(host, "/private/report.txt");
        assertEquals(403, unsigned.status(), unsigned.body());
    }

    @Test
    void apiGatewayCustomDomainRoutesToTheRestApi() throws Exception {
        String apiId = given().contentType(ContentType.JSON)
                .body("{\"name\":\"h2-host-routing\"}")
                .when().post("/restapis")
                .then().statusCode(201).extract().path("id");
        String rootId = given().when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        String resourceId = given().contentType(ContentType.JSON)
                .body("{\"pathPart\":\"ping\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201).extract().path("id");
        String method = "/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET";
        given().contentType(ContentType.JSON).body("{\"authorizationType\":\"NONE\"}")
                .when().put(method).then().statusCode(201);
        given().contentType(ContentType.JSON).body("{}")
                .when().put(method + "/responses/200").then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body("{\"type\":\"MOCK\",\"requestTemplates\":{\"application/json\":\"{\\\"statusCode\\\":200}\"}}")
                .when().put(method + "/integration").then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"\",\"responseTemplates\":{\"application/json\":\"{\\\"pong\\\":true}\"}}")
                .when().put(method + "/integration/responses/200").then().statusCode(201);
        String deploymentId = given().contentType(ContentType.JSON).body("{}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201).extract().path("id");
        given().contentType(ContentType.JSON)
                .body("{\"stageName\":\"dev\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages").then().statusCode(201);

        String domain = "api-" + apiId + ".h2-routing.example.test";
        given().contentType(ContentType.JSON)
                .body("{\"domainName\":\"" + domain + "\"}")
                .when().post("/domainnames").then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body("{\"basePath\":\"v1\",\"restApiId\":\"" + apiId + "\",\"stage\":\"dev\"}")
                .when().post("/domainnames/" + domain + "/basepathmappings").then().statusCode(201);

        Http2Response response = getOverHttp2(domain, "/v1/ping");
        assertEquals(HttpVersion.HTTP_2, response.version());
        assertEquals(200, response.status(), response.body());
        assertTrue(response.body().contains("\"pong\":true"), response.body());
    }

    private record Http2Response(HttpVersion version, int status, String body) {}

    private Http2Response getOverHttp2(String host, String pathAndQuery) throws Exception {
        HttpClient client = vertx.createHttpClient(new HttpClientOptions()
                .setSsl(true)
                .setUseAlpn(true)
                .setTrustAll(true)
                .setVerifyHost(false)
                .setProtocolVersion(HttpVersion.HTTP_2));
        try {
            RequestOptions options = new RequestOptions()
                    .setServer(SocketAddress.inetSocketAddress(sslPort, "127.0.0.1"))
                    .setHost(host)
                    .setPort(sslPort)
                    .setMethod(HttpMethod.GET)
                    .setURI(pathAndQuery);
            return client.request(options)
                    .compose(request -> request.send())
                    .compose(response -> response.body().map(body ->
                            new Http2Response(response.version(), response.statusCode(), body.toString())))
                    .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } finally {
            client.close();
        }
    }

    private static String cloudFrontBase64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes).replace('+', '-').replace('=', '_').replace('/', '~');
    }
}
