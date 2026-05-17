package io.github.hectorvent.floci.services.cur;

import io.github.hectorvent.floci.services.floci.FlociDuckClient;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end test that exercises the full sync emission path:
 * {@code PutReportDefinition} -&gt; {@link CurEmissionScheduler} -&gt;
 * {@link EmissionEngine} -&gt; {@link ParquetEmitter} -&gt; DuckDB sidecar -&gt;
 * Parquet object readable from Floci S3.
 */
/*
 * Requires the {@code floci-duck} sidecar plus a host-network where the
 * sidecar can reach Floci's S3 service. Opt in via
 * {@code FLOCI_DUCK_CROSS_CONTAINER_TEST=1}; see
 * {@link ParquetEmitterIntegrationTest} for prerequisites.
 */
@QuarkusTest
@TestProfile(CurEmissionIntegrationTest.SyncProfile.class)
@EnabledIfEnvironmentVariable(named = "FLOCI_DUCK_CROSS_CONTAINER_TEST", matches = "1|true|yes")
class CurEmissionIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/cur/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/s3/aws4_request";

    /**
     * Wires the full sync path: emit-mode=synchronous, fixed Floci HTTP port so
     * the DuckDB sidecar can reach Floci S3 over the network.
     */
    public static final class SyncProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> o = new HashMap<>();
            o.put("quarkus.http.test-port", "4566");
            o.put("floci.base-url", "http://localhost:4566");
            o.put("floci.services.cur.emit-mode", "synchronous");
            o.put("floci.services.bcm-data-exports.emit-mode", "synchronous");
            return o;
        }
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Inject
    FlociDuckClient duckClient;

    @Inject
    S3Service s3Service;

    @Test
    void putReportDefinition_triggersSyncEmissionAndWritesParquet() {
        String destBucket = "ce-emit-bucket-" + System.currentTimeMillis();
        // Pre-create the destination so the emitter doesn't trip on a missing bucket.
        s3Service.createBucket(destBucket, "us-east-1");

        String body = "{\"ReportDefinition\":{" +
                "\"ReportName\":\"sync-emit\"," +
                "\"TimeUnit\":\"MONTHLY\"," +
                "\"Format\":\"Parquet\"," +
                "\"Compression\":\"Parquet\"," +
                "\"AdditionalSchemaElements\":[\"RESOURCES\"]," +
                "\"S3Bucket\":\"" + destBucket + "\"," +
                "\"S3Prefix\":\"billing/\"," +
                "\"S3Region\":\"us-east-1\"" +
                "}}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.PutReportDefinition")
            .header("Authorization", AUTH).body(body)
        .when().post("/")
        .then().statusCode(200);

        // Find the freshly written Parquet object by listing the destination prefix.
        var listing = s3Service.listObjects(destBucket, "billing/sync-emit/", null, Integer.MAX_VALUE);
        assertThat(listing.size(), greaterThanOrEqualTo(1));
        String parquetKey = listing.get(0).getKey();
        assertThat(parquetKey, startsWith("billing/sync-emit/"));
        assertThat(parquetKey.endsWith(".parquet"), org.hamcrest.Matchers.equalTo(true));

        // Confirm DuckDB can read the artifact back.
        String s3Uri = "s3://" + destBucket + "/" + parquetKey;
        List<Map<String, Object>> rows = duckClient.query(
                "SELECT \"ServiceName\" FROM read_parquet('" + s3Uri + "') LIMIT 5",
                null);
        assertNotNull(rows);
    }

    @Test
    void describeReportDefinitions_reportsLastStatusAfterSync() {
        String destBucket = "ce-emit-status-" + System.currentTimeMillis();
        s3Service.createBucket(destBucket, "us-east-1");

        String body = "{\"ReportDefinition\":{" +
                "\"ReportName\":\"sync-status\"," +
                "\"TimeUnit\":\"MONTHLY\",\"Format\":\"Parquet\",\"Compression\":\"Parquet\"," +
                "\"AdditionalSchemaElements\":[]," +
                "\"S3Bucket\":\"" + destBucket + "\",\"S3Region\":\"us-east-1\"}}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.PutReportDefinition")
            .header("Authorization", AUTH).body(body)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("ReportStatus.LastStatus", notNullValue());
    }

    @Test
    void createExport_recordsExecution() {
        String destBucket = "ce-emit-exec-" + System.currentTimeMillis();
        s3Service.createBucket(destBucket, "us-east-1");

        String createBody = "{\"Export\":{" +
                "\"Name\":\"sync-export\"," +
                "\"DataQuery\":{\"QueryStatement\":\"SELECT * FROM COST_AND_USAGE_REPORT\"}," +
                "\"DestinationConfigurations\":{\"S3Destination\":{" +
                  "\"S3Bucket\":\"" + destBucket + "\",\"S3Prefix\":\"out/\",\"S3Region\":\"us-east-1\"," +
                  "\"S3OutputConfigurations\":{\"Format\":\"PARQUET\",\"Compression\":\"PARQUET\",\"OutputType\":\"CUSTOM\",\"Overwrite\":\"OVERWRITE_REPORT\"}}}," +
                "\"RefreshCadence\":{\"Frequency\":\"SYNCHRONOUS\"}}}";

        String arn = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.CreateExport")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bcm-data-exports/aws4_request")
            .body(createBody)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("ExportArn");

        // The sync emission hook must have produced exactly one execution record.
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.ListExecutions")
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bcm-data-exports/aws4_request")
            .body("{\"ExportArn\":\"" + arn + "\"}")
        .when().post("/")
        .then().statusCode(200)
            .body("Executions.size()", greaterThanOrEqualTo(1))
            .body("Executions[0].ExecutionStatus.StatusCode",
                    org.hamcrest.Matchers.either(org.hamcrest.Matchers.equalTo("DELIVERY_SUCCESS"))
                            .or(org.hamcrest.Matchers.equalTo("DELIVERY_FAILURE")));
    }
}
