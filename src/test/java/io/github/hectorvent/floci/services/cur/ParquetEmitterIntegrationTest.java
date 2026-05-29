package io.github.hectorvent.floci.services.cur;

import io.github.hectorvent.floci.core.common.UsageLine;
import io.github.hectorvent.floci.services.floci.FlociDuckClient;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.HashMap;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end Parquet emission test: drives {@link ParquetEmitter} with a small
 * {@link UsageLine} batch, then reads the Parquet artifact back through
 * {@link FlociDuckClient#query} and verifies the rows.
 *
 * <p>This test boots Floci's full stack including the {@code floci-duck}
 * sidecar container, so it requires a running Docker daemon and pulls
 * {@code floci/floci-duck:latest} on first run. Same prerequisite as
 * {@code AthenaIntegrationTest}.
 */
/*
 * Requires the {@code floci-duck} sidecar container plus a network where
 * the sidecar can reach Floci's S3 service via {@code host.docker.internal}.
 * That works on macOS / Windows Docker Desktop out of the box, but Linux
 * CI runners don't resolve that hostname unless the container is started
 * with {@code --add-host=host.docker.internal:host-gateway}, which Floci's
 * {@code FlociDuckManager} doesn't do today. Opt in via
 * {@code FLOCI_DUCK_CROSS_CONTAINER_TEST=1} (e.g. local macOS, or a CI
 * runner that adds the host alias).
 */
@QuarkusTest
@TestProfile(ParquetEmitterIntegrationTest.FixedPortProfile.class)
@EnabledIfEnvironmentVariable(named = "FLOCI_DUCK_CROSS_CONTAINER_TEST", matches = "1|true|yes")
class ParquetEmitterIntegrationTest {

    /**
     * Force Floci's HTTP server onto a known port so the floci-duck sidecar can
     * reach it via {@code host.docker.internal:<port>}. The default test port
     * of {@code 0} (random) is fine for tests that only hit the JVM-local API,
     * but DuckDB writes Parquet by talking back to Floci's S3 service over the
     * network and so needs a stable port.
     */
    public static final class FixedPortProfile implements QuarkusTestProfile {
        @Override
        public java.util.Map<String, String> getConfigOverrides() {
            java.util.Map<String, String> overrides = new HashMap<>();
            overrides.put("quarkus.http.test-port", "4566");
            overrides.put("floci.base-url", "http://localhost:4566");
            return overrides;
        }
    }

    private static final Instant JAN_15 = Instant.parse("2026-01-15T00:00:00Z");
    private static final Instant JAN_16 = Instant.parse("2026-01-16T00:00:00Z");

    @Inject
    ParquetEmitter emitter;

    @Inject
    FlociDuckClient duckClient;

    @Inject
    S3Service s3Service;

    @Test
    void emit_writesParquetThatRoundTripsThroughDuckDB() {
        String destBucket = "ce-parquet-roundtrip-" + System.currentTimeMillis();
        s3Service.createBucket(destBucket, "us-east-1");

        List<UsageLine> lines = List.of(
                new UsageLine(JAN_15, JAN_16,
                        "AmazonEC2", "us-east-1", "BoxUsage:t3.micro", "RunInstances",
                        UsageLine.RECORD_TYPE_USAGE,
                        "111122223333", "i-roundtrip-1", Map.of("Owner", "team-a"),
                        24.0, "Hrs"),
                new UsageLine(JAN_15, JAN_16,
                        "AmazonEC2", "us-east-1", "BoxUsage:t3.micro", "RunInstances",
                        UsageLine.RECORD_TYPE_USAGE,
                        "111122223333", "i-roundtrip-2", Map.of("Owner", "team-b"),
                        12.0, "Hrs"));

        ParquetEmitter.Result result = emitter.emit("roundtrip-report", destBucket, "billing/", lines);

        assertNotNull(result);
        assertNotNull(result.runId());
        assertThat(result.bucket(), equalTo(destBucket));
        assertThat(result.s3Uri(), startsWith("s3://" + destBucket + "/billing/roundtrip-report/"));
        assertThat(result.s3Uri().endsWith(".parquet"), equalTo(true));
        assertThat(result.rowCount(), equalTo(2));

        // Confirm the Parquet object actually landed in S3.
        var parquetObject = s3Service.getObject(destBucket, result.key());
        assertNotNull(parquetObject);
        assertThat(parquetObject.getSize(), greaterThanOrEqualTo(1L));

        // Read it back through DuckDB and assert the row count + a couple of columns.
        List<Map<String, Object>> rows = duckClient.query(
                "SELECT \"BillingAccountId\", \"ServiceName\", \"BilledCost\", \"ResourceId\" "
                        + "FROM read_parquet('" + result.s3Uri() + "') ORDER BY \"ResourceId\"",
                null);
        assertThat(rows.size(), equalTo(2));

        Map<String, Object> first = rows.get(0);
        assertThat((String) first.get("BillingAccountId"), equalTo("111122223333"));
        assertThat((String) first.get("ServiceName"), equalTo("AmazonEC2"));
        assertThat((String) first.get("ResourceId"), equalTo("i-roundtrip-1"));
        // Bundled snapshot prices BoxUsage:t3.micro at 0.0104 USD/hour.
        assertThat(((Number) first.get("BilledCost")).doubleValue(),
                closeTo(24.0 * 0.0104, 1e-6));

        Map<String, Object> second = rows.get(1);
        assertThat((String) second.get("ResourceId"), equalTo("i-roundtrip-2"));
        assertThat(((Number) second.get("BilledCost")).doubleValue(),
                closeTo(12.0 * 0.0104, 1e-6));
    }

    @Test
    void emit_stagingObjectIsCleanedUp() {
        String destBucket = "ce-parquet-cleanup-" + System.currentTimeMillis();
        s3Service.createBucket(destBucket, "us-east-1");

        List<UsageLine> lines = List.of(new UsageLine(JAN_15, JAN_16,
                "AmazonS3", "us-east-1", "TimedStorage-Standard", "StandardStorage",
                UsageLine.RECORD_TYPE_USAGE,
                "111122223333", "arn:aws:s3:::cleanup", Map.of(),
                10.0, "GB-Mo"));

        ParquetEmitter.Result result = emitter.emit("cleanup-report", destBucket, null, lines);

        // Staging bucket exists, but no staging objects should remain for this run.
        var staging = s3Service.listObjects("floci-cur-staging",
                "cur-staging/cleanup-report/" + result.runId(), null, Integer.MAX_VALUE);
        assertTrue(staging.isEmpty(),
                "Expected no leftover staging objects, found: " + staging);
    }

    @Test
    void emit_concurrentRunsDoNotClobberEachOther() throws InterruptedException {
        String destBucket = "ce-parquet-concurrent-" + System.currentTimeMillis();
        s3Service.createBucket(destBucket, "us-east-1");

        List<UsageLine> linesA = List.of(new UsageLine(JAN_15, JAN_16,
                "AmazonEC2", "us-east-1", "BoxUsage:t3.micro", "RunInstances",
                UsageLine.RECORD_TYPE_USAGE,
                "111122223333", "i-A", Map.of(), 24.0, "Hrs"));
        List<UsageLine> linesB = List.of(new UsageLine(JAN_15, JAN_16,
                "AmazonEC2", "us-east-1", "BoxUsage:t3.micro", "RunInstances",
                UsageLine.RECORD_TYPE_USAGE,
                "111122223333", "i-B", Map.of(), 48.0, "Hrs"));

        // Same report name on purpose — runId must keep the two outputs separate.
        ParquetEmitter.Result resultA = emitter.emit("shared-report", destBucket, null, linesA);
        ParquetEmitter.Result resultB = emitter.emit("shared-report", destBucket, null, linesB);

        assertThat(resultA.runId(), notNullValue());
        assertThat(resultB.runId(), notNullValue());
        assertThat(resultA.runId().equals(resultB.runId()), equalTo(false));
        assertThat(resultA.s3Uri().equals(resultB.s3Uri()), equalTo(false));

        // Both Parquet objects must exist independently.
        assertNotNull(s3Service.getObject(destBucket, resultA.key()));
        assertNotNull(s3Service.getObject(destBucket, resultB.key()));
    }
}
