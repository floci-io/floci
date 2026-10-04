package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An S3 origin whose objects live on disk, where CloudFront streams the object file to the viewer
 * instead of reading it into memory first.
 */
@QuarkusTest
@TestProfile(CloudFrontS3DiskOriginServingTest.S3OnDiskProfile.class)
class CloudFrontS3DiskOriginServingTest {

    private static final String REGION = "us-east-1";

    @Inject
    S3Service s3Service;

    @Inject
    CloudFrontService cloudFrontService;

    @Test
    void streamsAnObjectFileWithItsLength() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cf-disk-origin-" + suffix;
        // Larger than any one buffer on the way, so the body arrives over many writes.
        byte[] body = new byte[3 * 1024 * 1024 + 1];
        new Random(5080).nextBytes(body);
        s3Service.createBucket(bucket, REGION);
        try {
            s3Service.putObject(bucket, "video.bin", body, "application/octet-stream", Map.of());
            s3Service.putObject(bucket, "index.html", ("INDEX-ON-DISK-" + suffix).getBytes(StandardCharsets.UTF_8),
                    "text/html", Map.of());
            Distribution dist = distributionFor(bucket);

            Response get = given().header("Host", dist.getDomainName()).when().get("/video.bin");

            assertEquals(200, get.statusCode());
            assertEquals(Integer.toString(body.length), get.header("Content-Length"));
            assertArrayEquals(body, get.asByteArray());
            given().header("Host", dist.getDomainName()).when().head("/video.bin")
                    .then().statusCode(200)
                    .header("Content-Length", equalTo(Integer.toString(body.length)));
            // The single-page-app fallback serves its error page through the same stream.
            given().header("Host", dist.getDomainName()).when().get("/missing/route")
                    .then().statusCode(200)
                    .header("Content-Length", equalTo(Integer.toString(("INDEX-ON-DISK-" + suffix).length())))
                    .body(containsString("INDEX-ON-DISK-" + suffix));
        } finally {
            // The bucket is on disk and outlives the test, so it is removed rather than left for the next run.
            s3Service.deleteObject(bucket, "video.bin");
            s3Service.deleteObject(bucket, "index.html");
            s3Service.deleteBucket(bucket);
        }
    }

    private Distribution distributionFor(String bucket) {
        Origin origin = new Origin();
        origin.setId("disk-origin");
        origin.setDomainName(bucket + ".s3." + REGION + ".amazonaws.com");
        origin.setS3OriginConfig(new LinkedHashMap<>(Map.of("OriginAccessIdentity", "")));
        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId(origin.getId());
        behavior.setViewerProtocolPolicy("allow-all");
        Map<String, Object> spaFallback = new LinkedHashMap<>();
        spaFallback.put("ErrorCode", "404");
        spaFallback.put("ResponseCode", "200");
        spaFallback.put("ResponsePagePath", "/index.html");
        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setOrigins(List.of(origin));
        config.setDefaultCacheBehavior(behavior);
        config.setCustomErrorResponses(List.of(spaFallback));
        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        return cloudFrontService.createDistribution(distribution, Map.of());
    }

    public static final class S3OnDiskProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.storage.services.s3.mode", "persistent",
                    "floci.storage.persistent-path", "target/cloudfront-s3-disk-origin-it");
        }
    }
}
