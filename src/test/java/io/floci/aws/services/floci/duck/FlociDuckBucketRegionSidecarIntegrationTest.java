package io.floci.aws.services.floci.duck;

import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Inject
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
     * not see them: floci-duck runs every request in a DuckDB instance of its own.
     */
    @Test
    void aConcurrentRequestSeesNoneOfAnotherRequestsBucketSecrets() throws Exception {
        s3Service.createBucket(euBucket, "eu-west-1");
        s3Service.putObject(euBucket, "rows.csv", "id,name\n1,eu\n".getBytes(StandardCharsets.UTF_8),
                "text/csv", null);
        CompletableFuture<List<Map<String, Object>>> slow = CompletableFuture.supplyAsync(() -> duck.query(
                "SELECT count(*) AS n FROM read_csv('s3://" + euBucket + "/rows.csv'), range(300000000) t(x)"
                        + " WHERE x % 7 = 3", null));
        int probesWhileRunning = 0;
        try {
            while (!slow.isDone()) {
                List<Map<String, Object>> secrets = duck.query(
                        "SELECT CAST(count(*) AS VARCHAR) AS n FROM duckdb_secrets()", null, "222222222222",
                        "secret-of-222", null);
                if (!slow.isDone()) {
                    assertEquals(List.of(Map.of("n", "0")), secrets);
                    probesWhileRunning++;
                }
            }
        } finally {
            assertEquals(List.of(Map.of("n", "42857143")), slow.get(60, TimeUnit.SECONDS).stream()
                    .map(row -> Map.of("n", String.valueOf(row.get("n")))).toList());
        }
        assertTrue(probesWhileRunning > 0, "no probe ran while the other request held its secret");
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
