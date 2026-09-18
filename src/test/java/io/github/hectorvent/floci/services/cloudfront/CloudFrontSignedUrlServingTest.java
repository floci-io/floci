package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.services.cloudfront.model.CacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.KeyGroup;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test for CloudFront private content: a cache behavior with trusted key groups requires a
 * valid signed request (signed URL) and returns 403 otherwise, while a public behavior serves without
 * a signature. Registers a real RSA public key + key group through the CloudFront service and signs the
 * request with the matching private key.
 */
@QuarkusTest
class CloudFrontSignedUrlServingTest {

    private static final String REGION = "us-east-1";

    @Inject
    S3Service s3Service;

    @Inject
    CloudFrontService cloudFrontService;

    @Test
    void privateContentRequiresAValidSignedUrl() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cf-private-" + suffix;
        s3Service.createBucket(bucket, REGION);
        s3Service.putObject(bucket, "private/secret.txt", ("SECRET-" + suffix).getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of());
        s3Service.putObject(bucket, "public/open.txt", ("OPEN-" + suffix).getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of());

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";

        io.github.hectorvent.floci.services.cloudfront.model.PublicKey publicKey =
                new io.github.hectorvent.floci.services.cloudfront.model.PublicKey();
        publicKey.setName("pk-" + suffix);
        publicKey.setCallerReference("cr-" + suffix);
        publicKey.setEncodedKey(pem);
        publicKey = cloudFrontService.createPublicKey(publicKey);

        KeyGroup keyGroup = new KeyGroup();
        keyGroup.setName("kg-" + suffix);
        keyGroup.setItems(List.of(publicKey.getId()));
        keyGroup = cloudFrontService.createKeyGroup(keyGroup);

        DistributionConfig cfg = new DistributionConfig();
        cfg.setEnabled(true);
        cfg.setOrigins(List.of(s3Origin("o", bucket)));
        cfg.setDefaultCacheBehavior(defaultBehavior("o"));
        CacheBehavior priv = new CacheBehavior();
        priv.setPathPattern("/private/*");
        priv.setTargetOriginId("o");
        priv.setViewerProtocolPolicy("allow-all");
        priv.setTrustedKeyGroups(List.of(keyGroup.getId()));
        cfg.setCacheBehaviors(List.of(priv));

        Distribution dist = cloudFrontService.createDistribution(distribution(cfg), Map.of());
        String host = dist.getDomainName();

        // Public behavior: served without a signature.
        given().header("Host", host).when().get("/public/open.txt")
                .then().statusCode(200).body(containsString("OPEN-" + suffix));

        // Private behavior without a signature → 403.
        given().header("Host", host).when().get("/private/secret.txt")
                .then().statusCode(403);

        // Vert.x rejects a malformed raw query name at ingress; it must fail closed, never become a 500.
        String malformedResponse = rawGet(host, "/private/secret.txt?%ZZ=value");
        assertTrue(malformedResponse.startsWith("HTTP/1.1 400"), malformedResponse);

        // A valid signed URL (custom policy, wildcard resource) → 200 with the private content.
        long expires = Instant.now().getEpochSecond() + 3600;
        String policyJson = "{\"Statement\":[{\"Resource\":\"*\",\"Condition\":{\"DateLessThan\":"
                + "{\"AWS:EpochTime\":" + expires + "}}}]}";
        Signature signer = Signature.getInstance("SHA1withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(policyJson.getBytes(StandardCharsets.UTF_8));
        String signature = cfBase64(signer.sign());
        String policyParam = cfBase64(policyJson.getBytes(StandardCharsets.UTF_8));

        given().header("Host", host)
                .queryParam("Policy", policyParam)
                .queryParam("Signature", signature)
                .queryParam("Key-Pair-Id", publicKey.getId())
                .when().get("/private/secret.txt")
                .then().statusCode(200).body(containsString("SECRET-" + suffix));

        // Signed-cookie custom policies require an explicit http:// or https:// protocol.
        String cookiePolicyJson = "{\"Statement\":[{\"Resource\":\"http://*\",\"Condition\":"
                + "{\"DateLessThan\":{\"AWS:EpochTime\":" + expires + "}}}]}";
        String cookiePolicyParam =
                cfBase64(cookiePolicyJson.getBytes(StandardCharsets.UTF_8));

        // A valid SHA-256 signed cookie is accepted through the real HTTP cookie path.
        Signature cookieSigner = Signature.getInstance("SHA256withRSA");
        cookieSigner.initSign(keyPair.getPrivate());
        cookieSigner.update(cookiePolicyJson.getBytes(StandardCharsets.UTF_8));
        String signedCookies = "CloudFront-Policy=" + cookiePolicyParam
                + "; CloudFront-Signature=" + cfBase64(cookieSigner.sign())
                + "; CloudFront-Key-Pair-Id=" + publicKey.getId()
                + "; CloudFront-Hash-Algorithm=SHA256";

        given().header("Host", host)
                .header("Cookie", signedCookies)
                .when().get("/private/secret.txt")
                .then().statusCode(200).body(containsString("SECRET-" + suffix));

        // The presence of any signed-URL field makes CloudFront ignore valid signed cookies.
        given().header("Host", host)
                .header("Cookie", signedCookies)
                .queryParam("Expires", expires)
                .when().get("/private/secret.txt")
                .then().statusCode(403);

        // Application-query bytes remain part of the signed resource after signing fields are removed.
        String queryResource = "http://" + host + "/private/secret.txt?color=blue";
        String queryPolicy = "{\"Statement\":[{\"Resource\":\"" + queryResource + "\","
                + "\"Condition\":{\"DateLessThan\":{\"AWS:EpochTime\":" + expires + "}}}]}";
        Signature querySigner = Signature.getInstance("SHA1withRSA");
        querySigner.initSign(keyPair.getPrivate());
        querySigner.update(queryPolicy.getBytes(StandardCharsets.UTF_8));

        given().header("Host", host)
                .queryParam("color", "blue")
                .queryParam("Policy",
                        cfBase64(queryPolicy.getBytes(StandardCharsets.UTF_8)))
                .queryParam("Signature", cfBase64(querySigner.sign()))
                .queryParam("Key-Pair-Id", publicKey.getId())
                .when().get("/private/secret.txt")
                .then().statusCode(200).body(containsString("SECRET-" + suffix));

        // Same signature but an unknown Key-Pair-Id (not a trusted signer) → 403.
        given().header("Host", host)
                .queryParam("Policy", policyParam)
                .queryParam("Signature", signature)
                .queryParam("Key-Pair-Id", "not-a-real-key")
                .when().get("/private/secret.txt")
                .then().statusCode(403);
    }

    @Test
    void signedUrlForAPercentEncodedPathVerifiesAgainstTheRawUrl() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cf-enc-" + suffix;
        s3Service.createBucket(bucket, REGION);
        // The object key contains a space; on the wire the viewer requests it percent-encoded.
        s3Service.putObject(bucket, "private/my report.pdf", ("DOC-" + suffix).getBytes(StandardCharsets.UTF_8),
                "application/pdf", Map.of());

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
        io.github.hectorvent.floci.services.cloudfront.model.PublicKey publicKey =
                new io.github.hectorvent.floci.services.cloudfront.model.PublicKey();
        publicKey.setName("pk-" + suffix);
        publicKey.setCallerReference("cr-" + suffix);
        publicKey.setEncodedKey(pem);
        publicKey = cloudFrontService.createPublicKey(publicKey);

        KeyGroup keyGroup = new KeyGroup();
        keyGroup.setName("kg-" + suffix);
        keyGroup.setItems(List.of(publicKey.getId()));
        keyGroup = cloudFrontService.createKeyGroup(keyGroup);

        DistributionConfig cfg = new DistributionConfig();
        cfg.setEnabled(true);
        cfg.setOrigins(List.of(s3Origin("o", bucket)));
        cfg.setDefaultCacheBehavior(defaultBehavior("o"));
        CacheBehavior priv = new CacheBehavior();
        priv.setPathPattern("/private/*");
        priv.setTargetOriginId("o");
        priv.setViewerProtocolPolicy("allow-all");
        priv.setTrustedKeyGroups(List.of(keyGroup.getId()));
        cfg.setCacheBehaviors(List.of(priv));
        Distribution dist = cloudFrontService.createDistribution(distribution(cfg), Map.of());
        String host = dist.getDomainName();

        // Custom policy whose Resource carries the EXACT percent-encoded path the signer signs (scheme
        // and host are wildcarded so the assertion isolates path encoding). If the resource URL were
        // rebuilt from the decoded path, "my%20report.pdf" would not match "my report.pdf" → wrongly 403.
        long expires = Instant.now().getEpochSecond() + 3600;
        String resource = "*/private/my%20report.pdf";
        String policyJson = "{\"Statement\":[{\"Resource\":\"" + resource + "\",\"Condition\":"
                + "{\"DateLessThan\":{\"AWS:EpochTime\":" + expires + "}}}]}";
        Signature signer = Signature.getInstance("SHA1withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(policyJson.getBytes(StandardCharsets.UTF_8));
        String signature = cfBase64(signer.sign());
        String policyParam = cfBase64(policyJson.getBytes(StandardCharsets.UTF_8));

        given().header("Host", host).urlEncodingEnabled(false)
                .queryParam("Policy", policyParam)
                .queryParam("Signature", signature)
                .queryParam("Key-Pair-Id", publicKey.getId())
                .when().get("/private/my%20report.pdf")
                .then().statusCode(200).body(containsString("DOC-" + suffix));
    }

    private static String cfBase64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes).replace('+', '-').replace('=', '_').replace('/', '~');
    }

    private static String rawGet(String host, String pathAndQuery) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", io.restassured.RestAssured.port)) {
            String request = "GET " + pathAndQuery + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
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
}
