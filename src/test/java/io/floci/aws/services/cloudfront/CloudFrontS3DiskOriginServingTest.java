package io.floci.aws.services.cloudfront;

import io.floci.aws.services.cloudfront.model.DefaultCacheBehavior;
import io.floci.aws.services.cloudfront.model.Distribution;
import io.floci.aws.services.cloudfront.model.DistributionConfig;
import io.floci.aws.services.cloudfront.model.Origin;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.PerServicePersistentStorageProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static io.floci.aws.testing.PerServicePersistentStorageProfile.STORAGE_DIR;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An S3 origin whose objects live on disk, where CloudFront streams the object file to the viewer
 * instead of reading it into memory first.
 *
 * <p>S3 on disk comes from {@link PerServicePersistentStorageProfile}, which this class shares
 * with other classes that each call a different service, so it deletes its distribution as well
 * as its bucket.
 */
@QuarkusTest
@TestProfile(PerServicePersistentStorageProfile.class)
class CloudFrontS3DiskOriginServingTest {

    private static final String REGION = "us-east-1";

    @Inject
    S3Service s3Service;

    @Inject
    CloudFrontService cloudFrontService;

    @Test
    void streamsAnObjectFileWithItsLength() throws IOException {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "cf-disk-origin-" + suffix;
        // Larger than any one buffer on the way, so the body arrives over many writes.
        byte[] body = new byte[3 * 1024 * 1024 + 1];
        new Random(5080).nextBytes(body);
        s3Service.createBucket(bucket, REGION);
        Distribution dist = null;
        try {
            s3Service.putObject(bucket, "video.bin", body, "application/octet-stream", Map.of());
            // S3 in memory would serve the same bytes, so check the object really is a file first.
            assertTrue(hasObjectFile(Path.of(STORAGE_DIR, "s3"), bucket, "video.bin", body.length),
                    "video.bin should be a file in " + bucket + "'s directory under "
                            + STORAGE_DIR + "/s3");
            s3Service.putObject(bucket, "index.html", ("INDEX-ON-DISK-" + suffix).getBytes(StandardCharsets.UTF_8),
                    "text/html", Map.of());
            dist = distributionFor(bucket);

            Response get = given().header("Host", dist.getDomainName()).when().get("/video.bin");

            assertEquals(200, get.statusCode());
            assertEquals(Integer.toString(body.length), get.header("Content-Length"));
            assertArrayEquals(body, get.asByteArray());
            given().header("Host", dist.getDomainName()).when().head("/video.bin")
                    .then().statusCode(200)
                    .header("Content-Length", equalTo(Integer.toString(body.length)));
            // A range from the middle of the object file.
            Response part = given().header("Host", dist.getDomainName())
                    .header("Range", "bytes=1048576-1048675").when().get("/video.bin");
            assertEquals(206, part.statusCode());
            assertEquals("bytes 1048576-1048675/" + body.length, part.header("Content-Range"));
            assertArrayEquals(Arrays.copyOfRange(body, 1048576, 1048676), part.asByteArray());
            // The single-page-app fallback serves its error page through the same stream.
            given().header("Host", dist.getDomainName()).when().get("/missing/route")
                    .then().statusCode(200)
                    .header("Content-Length", equalTo(Integer.toString(("INDEX-ON-DISK-" + suffix).length())))
                    .body(containsString("INDEX-ON-DISK-" + suffix));
        } finally {
            if (dist != null) {
                cloudFrontService.removeDistribution(dist.getId());
            }
            // The bucket is on disk and outlives the test, so it is removed rather than left for the next run.
            s3Service.deleteObject(bucket, "video.bin");
            s3Service.deleteObject(bucket, "index.html");
            s3Service.deleteBucket(bucket);
        }
    }

    /**
     * Whether a file named for {@code key}, of {@code size} bytes, sits in a directory named for
     * {@code bucket}. The bucket name is new on every run, so a file an earlier run left behind
     * cannot match.
     */
    private static boolean hasObjectFile(Path dir, String bucket, String key, long size)
            throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.anyMatch(f -> Files.isRegularFile(f)
                    && f.getParent().getFileName().toString().equals(bucket)
                    && f.getFileName().toString().startsWith(key)
                    && f.toFile().length() == size);
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
}
