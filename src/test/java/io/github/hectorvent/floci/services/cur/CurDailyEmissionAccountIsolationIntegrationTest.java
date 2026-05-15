package io.github.hectorvent.floci.services.cur;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
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
 * Verifies that the daily emission loop honors per-report owner accounts:
 * NDJSON staging (Java-side {@code S3Service.putObject}) and DuckDB-side
 * Parquet reads/writes both happen under the report owner's account, not the
 * default account that the scheduler thread starts in.
 *
 * <p>This is the daily-mode counterpart to
 * {@link CurNonDefaultAccountEmissionIntegrationTest}, which covers the
 * synchronous path. Without {@code CurEmissionScheduler#runUnderAccount}, the
 * staging write would land in the default account's S3 partition while
 * DuckDB would try to read it from account 111111111111 — triggering
 * {@code NoSuchBucket}.
 */
@QuarkusTest
@TestProfile(CurDailyEmissionAccountIsolationIntegrationTest.DailyProfile.class)
@EnabledIfEnvironmentVariable(named = "FLOCI_DUCK_CROSS_CONTAINER_TEST", matches = "1|true|yes")
class CurDailyEmissionAccountIsolationIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String ACCOUNT_ID = "111111111111";
    private static final String CUR_AUTH =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT_ID + "/20260101/us-east-1/cur/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT_ID + "/20260101/us-east-1/s3/aws4_request";

    public static final class DailyProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> o = new HashMap<>();
            o.put("quarkus.http.test-port", "4566");
            o.put("floci.base-url", "http://localhost:4566");
            // Sync mode is off so the put-report call below does NOT emit
            // immediately. The test then drives the scheduler's runDaily
            // codepath directly via the engine to simulate the daily tick.
            o.put("floci.services.cur.emit-mode", "off");
            o.put("floci.services.bcm-data-exports.emit-mode", "off");
            return o;
        }
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Inject
    EmissionEngine engine;

    @Inject
    CurService curService;

    @Test
    void dailyEmissionLoopHonorsOwnerAccount() {
        String destBucket = "ce-daily-acct-" + System.currentTimeMillis();

        // Bucket created under account 111111111111 only.
        given()
            .header("Authorization", S3_AUTH)
            .header("Host", "localhost")
        .when()
            .put("/" + destBucket)
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(204)));

        // Persist a report definition under the same account.
        String body = "{\"ReportDefinition\":{" +
                "\"ReportName\":\"daily-acct-emit\"," +
                "\"TimeUnit\":\"MONTHLY\"," +
                "\"Format\":\"Parquet\",\"Compression\":\"Parquet\"," +
                "\"AdditionalSchemaElements\":[]," +
                "\"S3Bucket\":\"" + destBucket + "\"," +
                "\"S3Prefix\":\"focus\"," +
                "\"S3Region\":\"us-east-1\"" +
                "}}";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.PutReportDefinition")
            .header("Authorization", CUR_AUTH).body(body)
        .when().post("/")
        .then().statusCode(200);

        // Simulate the scheduler's runDaily codepath: iterate every account's
        // report definitions and emit under the owner-account context. The
        // helper below mirrors what CurEmissionScheduler.runUnderAccount does,
        // since the @Test thread is also outside any incoming HTTP scope here
        // (the prior REST-Assured calls have terminated their request scopes).
        Map<String, java.util.List<io.github.hectorvent.floci.services.cur.model.ReportDefinition>> grouped =
                curService.listAllReportsByAccount();
        assertThat(grouped, org.hamcrest.Matchers.hasKey(ACCOUNT_ID));

        io.quarkus.arc.ManagedContext rc = io.quarkus.arc.Arc.container().requestContext();
        boolean wasActive = rc.isActive();
        if (!wasActive) {
            rc.activate();
        }
        try {
            io.github.hectorvent.floci.core.common.RequestContext ctx =
                    io.quarkus.arc.Arc.container()
                            .instance(io.github.hectorvent.floci.core.common.RequestContext.class).get();
            ctx.setAccountId(ACCOUNT_ID);
            for (io.github.hectorvent.floci.services.cur.model.ReportDefinition def
                    : grouped.get(ACCOUNT_ID)) {
                engine.emitForCurrentMonth(def.getReportName(), def.getS3Bucket(),
                        def.getS3Prefix(), def.getS3Region(), ACCOUNT_ID);
            }
        } finally {
            if (!wasActive) {
                rc.terminate();
            }
        }

        // Confirm the Parquet artifact landed in account 111111111111's bucket.
        String listResponse = given()
            .header("Authorization", S3_AUTH)
            .queryParam("prefix", "focus/daily-acct-emit/")
            .queryParam("list-type", "2")
        .when()
            .get("/" + destBucket)
        .then()
            .statusCode(200)
            .extract().asString();

        assertThat(listResponse, containsString("focus/daily-acct-emit/"));
        assertThat(listResponse, containsString(".parquet"));
    }
}
