package io.floci.aws.services.floci.duck;

import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * S3 answers a bucket only in its own Region, so floci-duck, which Floci drives for Athena, S3 Select, Redshift and
 * the CUR reports, must sign each bucket's requests for that bucket's Region. One query reads buckets of two Regions
 * here, through the real sidecar.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
class FlociDuckBucketRegionSidecarIntegrationTest {

    @Inject
    FlociDuckClient duck;

    @InjectSpy
    S3Service s3Service;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String euBucket = "duck-region-eu-" + suffix;
    private final String usBucket = "duck-region-us-" + suffix;

    @BeforeAll
    static void requireDocker() {
        SidecarContainersProfile.requireDocker();
    }

    @AfterEach
    void deleteBuckets() {
        for (String bucket : List.of(euBucket, usBucket)) {
            try {
                s3Service.deleteObject(bucket, "rows.csv");
                s3Service.deleteBucket(bucket);
            } catch (RuntimeException ignored) {
                // The bucket was never created because the test failed first; nothing to remove.
            }
        }
    }

    @Test
    void aQueryReadsBucketsOfTwoRegionsEachSignedForItsOwn() {
        s3Service.createBucket(euBucket, "eu-west-1");
        s3Service.putObject(euBucket, "rows.csv", "id,name\n1,eu\n".getBytes(StandardCharsets.UTF_8),
                "text/csv", null);
        s3Service.createBucket(usBucket, "us-east-1");
        s3Service.putObject(usBucket, "rows.csv", "id,name\n2,us\n".getBytes(StandardCharsets.UTF_8),
                "text/csv", null);

        List<Map<String, Object>> rows = duck.query("SELECT CAST(id AS VARCHAR) AS id, name FROM read_csv('s3://"
                + euBucket + "/rows.csv') UNION ALL SELECT CAST(id AS VARCHAR), name FROM read_csv('s3://"
                + usBucket + "/rows.csv') ORDER BY id", null);

        assertEquals(List.of(Map.of("id", "1", "name", "eu"), Map.of("id", "2", "name", "us")), rows);
    }

    /**
     * The secrets carry the caller's credentials, so a request running at the same time, as another account, must
     * not see them: floci-duck runs every request in a DuckDB instance of its own. The first request is held inside
     * its read, after its secret exists, while the second looks.
     */
    @Test
    void aConcurrentRequestSeesNoneOfAnotherRequestsBucketSecrets() throws Exception {
        s3Service.createBucket(euBucket, "eu-west-1");
        s3Service.putObject(euBucket, "held.csv", "id,name\n1,eu\n".getBytes(StandardCharsets.UTF_8),
                "text/csv", null);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            reading.countDown();
            assertTrue(release.await(30, TimeUnit.SECONDS), "the held read was never released");
            return invocation.callRealMethod();
        }).when(s3Service).headObject(eq(euBucket), eq("held.csv"), any());
        CompletableFuture<List<Map<String, Object>>> held = CompletableFuture.supplyAsync(() -> duck.query(
                "SELECT CAST(id AS VARCHAR) AS id FROM read_csv('s3://" + euBucket + "/held.csv')", null));
        try {
            assertTrue(reading.await(30, TimeUnit.SECONDS), "the first request never read its bucket");

            assertEquals(List.of(Map.of("n", "0")), duck.query(
                    "SELECT CAST(count(*) AS VARCHAR) AS n FROM duckdb_secrets()", null, "222222222222",
                    "secret-of-222", null));
        } finally {
            release.countDown();
        }
        try {
            assertEquals(List.of(Map.of("id", "1")), held.get(30, TimeUnit.SECONDS));
        } finally {
            s3Service.deleteObject(euBucket, "held.csv");
        }
    }

    @Test
    void aQueryWritesItsResultToABucketOfAnotherRegion() {
        s3Service.createBucket(euBucket, "eu-west-1");

        duck.execute("SELECT 7 AS answer", null, "s3://" + euBucket + "/results/out.csv");

        String written = new String(s3Service.getObject(euBucket, "results/out.csv").getData(), StandardCharsets.UTF_8);
        assertEquals("answer\n7\n", written);
        s3Service.deleteObject(euBucket, "results/out.csv");
    }
}
