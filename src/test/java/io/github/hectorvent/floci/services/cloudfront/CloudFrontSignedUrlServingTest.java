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

        // Same signature but an unknown Key-Pair-Id (not a trusted signer) → 403.
        given().header("Host", host)
                .queryParam("Policy", policyParam)
                .queryParam("Signature", signature)
                .queryParam("Key-Pair-Id", "not-a-real-key")
                .when().get("/private/secret.txt")
                .then().statusCode(403);
    }

    private static String cfBase64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes).replace('+', '-').replace('=', '_').replace('/', '~');
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
