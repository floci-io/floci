package io.github.hectorvent.floci.services.cur;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies that a CUR report created under a non-default 12-digit AWS account
 * still reaches its own account-aware S3 partition during synchronous Parquet
 * emission. Without owner-account threading through {@code FlociDuckClient},
 * the Parquet read/write would fall back to the default account and fail with
 * {@code NoSuchBucket} or write into a same-named default-account bucket.
 *
 * <p>Requires the {@code floci-duck} sidecar plus a host-network where the
 * sidecar can reach Floci's S3 service. Opt in via
 * {@code FLOCI_DUCK_CROSS_CONTAINER_TEST=1}; see
 * {@link ParquetEmitterIntegrationTest} for prerequisites.
 */
@QuarkusTest
@TestProfile(CurNonDefaultAccountEmissionIntegrationTest.SyncProfile.class)
@EnabledIfEnvironmentVariable(named = "FLOCI_DUCK_CROSS_CONTAINER_TEST", matches = "1|true|yes")
class CurNonDefaultAccountEmissionIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String ACCOUNT_ID = "111111111111";
    private static final String CUR_AUTH =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT_ID + "/20260101/us-east-1/cur/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT_ID + "/20260101/us-east-1/s3/aws4_request";

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

    @Test
    void putReportDefinitionUnderNonDefaultAccount_writesParquetIntoThatAccountsBucket() {
        String destBucket = "ce-acct-bucket-" + System.currentTimeMillis();

        // Create the destination bucket under the SAME 12-digit access key the
        // CUR call will use; AccountResolver routes both calls into account
        // 111111111111's S3 partition.
        given()
            .header("Authorization", S3_AUTH)
            .header("Host", "localhost")
        .when()
            .put("/" + destBucket)
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(204)));

        String body = "{\"ReportDefinition\":{" +
                "\"ReportName\":\"acct-emit\"," +
                "\"TimeUnit\":\"MONTHLY\"," +
                "\"Format\":\"Parquet\"," +
                "\"Compression\":\"Parquet\"," +
                "\"AdditionalSchemaElements\":[]," +
                "\"S3Bucket\":\"" + destBucket + "\"," +
                "\"S3Prefix\":\"focus\"," +
                "\"S3Region\":\"us-east-1\"" +
                "}}";

        // Synchronous emission must land in account 111111111111's bucket.
        // ReportStatus.LastStatus = SUCCESS means DuckDB resolved the bucket
        // under the owner account; ERROR would indicate a default-account
        // fallback hitting NoSuchBucket.
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.PutReportDefinition")
            .header("Authorization", CUR_AUTH).body(body)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("ReportStatus.LastStatus", equalTo("SUCCESS"));

        // List the same account's bucket and confirm the Parquet artifact is
        // there. The list call is itself account-scoped, so a default-account
        // write would not surface here.
        String listResponse = given()
            .header("Authorization", S3_AUTH)
            .queryParam("prefix", "focus/acct-emit/")
            .queryParam("list-type", "2")
        .when()
            .get("/" + destBucket)
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(listResponse, containsString("focus/acct-emit/"));
        assertThat(listResponse, containsString(".parquet"));
    }
}
