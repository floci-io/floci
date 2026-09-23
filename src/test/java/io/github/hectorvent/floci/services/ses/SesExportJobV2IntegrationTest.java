package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the four SES v2 export-job operations.
 *
 * <p>Probe-confirmed against real SES (2026-09-23): a request naming its own {@code S3Url} is
 * refused, exactly one data source is required, the metrics source insists on whole UTC days, an
 * unknown job is a 400 rather than a 404, and a finished job cannot be cancelled. Uses an isolated
 * signing region so no other test's sends reach the exported rows.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesExportJobV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/eu-west-2/ses/aws4_request";
    private static final String SENDER = "export-sender@example.com";

    private static long midnightToday() {
        return Instant.now().truncatedTo(ChronoUnit.DAYS).getEpochSecond();
    }

    private static Response create(String body) {
        return given().contentType("application/json").header("Authorization", AUTH)
                .body(body)
        .when().post("/v2/email/export-jobs");
    }

    private static String insightsSource() {
        return """
            {"ExportDataSource": {"MessageInsightsDataSource":
                {"StartDate": %d, "EndDate": %d}},
             "ExportDestination": {"DataFormat": "CSV"}}
            """.formatted(Instant.now().minus(Duration.ofDays(1)).getEpochSecond(),
                Instant.now().plus(Duration.ofDays(1)).getEpochSecond());
    }

    private static String awaitTerminal(String jobId) {
        for (int attempt = 0; attempt < 100; attempt++) {
            String status = given().header("Authorization", AUTH)
            .when().get("/v2/email/export-jobs/" + jobId).then().statusCode(200)
                    .extract().path("JobStatus");
            if (!"PROCESSING".equals(status) && !"CREATED".equals(status)) {
                return status;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for the job", e);
            }
        }
        throw new AssertionError("export job " + jobId + " never reached a terminal state");
    }

    @Test
    @Order(0)
    void createExportJob_vdmDisabled_isABadRequestNotANotFound() {
        // The same sentence message insights and metric data use, but 400 here, not 404.
        create(insightsSource()).then().statusCode(400)
                .body("message", containsString(
                        "To use this feature you must enable Virtual Deliverability Manager"));
    }

    @Test
    @Order(1)
    void enableVdmAndVerifyTheSender() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"VdmAttributes\":{\"VdmEnabled\":\"ENABLED\"}}")
        .when().put("/v2/email/account/vdm").then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"EmailIdentity\":\"" + SENDER + "\"}")
        .when().post("/v2/email/identities").then().statusCode(200);
    }

    @Test
    @Order(2)
    void createExportJob_refusesACallerChosenDestination() {
        create("""
            {"ExportDataSource": {"MessageInsightsDataSource":
                {"StartDate": %d, "EndDate": %d}},
             "ExportDestination": {"DataFormat": "CSV", "S3Url": "s3://mine/prefix/"}}
            """.formatted(midnightToday() - 86400, midnightToday()))
        .then().statusCode(400)
                .body("message", equalTo("Providing a custom S3 URL is not supported"));
    }

    @Test
    @Order(3)
    void createExportJob_checksTheDestinationInTheProbedOrder() {
        // Probed: the data source outranks the destination's own constraints, which outrank the
        // refusal of a caller-chosen S3Url, which outranks the VDM gate.
        create("""
            {"ExportDataSource": {"MessageInsightsDataSource":
                {"StartDate": %d, "EndDate": %d}}}
            """.formatted(midnightToday() - 86400, midnightToday()))
        .then().statusCode(400).body("message", equalTo(
                "1 validation error detected: Value at 'exportDestination' failed to satisfy "
                        + "constraint: Member must not be null"));

        create("""
            {"ExportDataSource": {"MessageInsightsDataSource":
                {"StartDate": %d, "EndDate": %d}},
             "ExportDestination": {"S3Url": "s3://mine/prefix/"}}
            """.formatted(midnightToday() - 86400, midnightToday()))
        .then().statusCode(400).body("message", equalTo(
                "1 validation error detected: Value at 'exportDestination.dataFormat' failed to "
                        + "satisfy constraint: Member must not be null"));

        create("""
            {"ExportDataSource": {"MessageInsightsDataSource":
                {"StartDate": %d, "EndDate": %d}},
             "ExportDestination": {"DataFormat": "PARQUET"}}
            """.formatted(midnightToday() - 86400, midnightToday()))
        .then().statusCode(400).body("message", containsString(
                "Member must satisfy enum value set: [CSV, JSON]"));

        // A missing data source beats even a caller-chosen destination.
        create("""
            {"ExportDataSource": {},
             "ExportDestination": {"DataFormat": "CSV", "S3Url": "s3://mine/prefix/"}}
            """)
        .then().statusCode(400).body("message", equalTo("One data source must be provided"));
    }

    @Test
    @Order(4)
    void createExportJob_requiresExactlyOneDataSource() {
        String both = """
            {"ExportDataSource": {"MessageInsightsDataSource": {"StartDate": %d, "EndDate": %d},
                                  "MetricsDataSource": {"Dimensions": {"ISP": ["*"]},
                                                        "Namespace": "VDM",
                                                        "Metrics": [{"Name": "SEND"}],
                                                        "StartDate": %d, "EndDate": %d}},
             "ExportDestination": {"DataFormat": "CSV"}}
            """.formatted(midnightToday() - 86400, midnightToday(),
                midnightToday() - 86400, midnightToday());
        create(both).then().statusCode(400)
                .body("message", equalTo("One data source must be provided"));

        create("{\"ExportDataSource\": {}, \"ExportDestination\": {\"DataFormat\": \"CSV\"}}")
        .then().statusCode(400).body("message", equalTo("One data source must be provided"));
    }

    @Test
    @Order(5)
    void createExportJob_checksTheDates() {
        create("""
            {"ExportDataSource": {"MessageInsightsDataSource":
                {"StartDate": %d, "EndDate": %d}},
             "ExportDestination": {"DataFormat": "CSV"}}
            """.formatted(midnightToday(), midnightToday() - 86400))
        .then().statusCode(400).body("message", equalTo("Invalid date range"));

        // Only the metrics source insists on whole days.
        create("""
            {"ExportDataSource": {"MetricsDataSource": {"Dimensions": {"ISP": ["*"]},
                                                        "Namespace": "VDM",
                                                        "Metrics": [{"Name": "SEND"}],
                                                        "StartDate": %d, "EndDate": %d}},
             "ExportDestination": {"DataFormat": "CSV"}}
            """.formatted(midnightToday() - 86400 + 3600, midnightToday()))
        .then().statusCode(400).body("message", containsString("midnight to midnight UTC"));
    }

    @Test
    @Order(6)
    void exportJob_writesTheInsightsFileAndServesItBehindAPresignedUrl() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"FromEmailAddress": "%s",
                     "Destination": {"ToAddresses": ["reader@example.com"]},
                     "Content": {"Simple": {"Subject": {"Data": "exported"},
                                            "Body": {"Text": {"Data": "hi"}}}}}
                    """.formatted(SENDER))
        .when().post("/v2/email/outbound-emails").then().statusCode(200);

        String jobId = create(insightsSource()).then().statusCode(200)
                .extract().path("JobId");
        assertEquals("COMPLETED", awaitTerminal(jobId));

        Response job = given().header("Authorization", AUTH)
        .when().get("/v2/email/export-jobs/" + jobId);
        job.then().statusCode(200)
                .body("ExportSourceType", equalTo("MESSAGE_INSIGHTS"))
                .body("ExportDestination.DataFormat", equalTo("CSV"))
                .body("ExportDestination.S3Url", notNullValue())
                .body("ExportDataSource.MessageInsightsDataSource", notNullValue())
                .body("CompletedTimestamp", notNullValue())
                // Probed: AWS reports the rows examined and never ExportedRecordsCount.
                .body("Statistics.ProcessedRecordsCount", equalTo(1))
                .body("Statistics", not(hasKey("ExportedRecordsCount")));

        // The URL is Floci's own S3 emulation, so the object is fetched back through it.
        String url = job.path("ExportDestination.S3Url");
        String path = url.substring(url.indexOf('/', url.indexOf("//") + 2));
        String body = given().when().get(path).then().statusCode(200).extract().asString();
        assertTrue(body.startsWith("\"messageid\",\"sendtimestamp\""), body.substring(0, 60));
        assertTrue(body.contains("reader@example.com"), "the send should be in the export");
    }

    @Test
    @Order(7)
    void exportJob_metricsSourceCountsTheSameSends() {
        String jobId = create("""
            {"ExportDataSource": {"MetricsDataSource": {"Dimensions": {"ISP": ["*"]},
                                                        "Namespace": "VDM",
                                                        "Metrics": [{"Name": "SEND",
                                                                     "Aggregation": "VOLUME"}],
                                                        "StartDate": %d, "EndDate": %d}},
             "ExportDestination": {"DataFormat": "CSV"}}
            """.formatted(midnightToday(), midnightToday() + 86400))
        .then().statusCode(200).extract().path("JobId");
        assertEquals("COMPLETED", awaitTerminal(jobId));

        String url = given().header("Authorization", AUTH)
        .when().get("/v2/email/export-jobs/" + jobId).then().statusCode(200)
                .extract().path("ExportDestination.S3Url");
        String path = url.substring(url.indexOf('/', url.indexOf("//") + 2));
        String body = given().when().get(path).then().statusCode(200).extract().asString();

        assertTrue(body.startsWith("ISP,SEND_VOLUME\n"), body);
        assertTrue(body.contains("UNKNOWN_ISP,1.0000"), body);
    }

    @Test
    @Order(8)
    void cancelExportJob_refusesAFinishedJob() {
        // Only the refusal is reachable here: a Floci export finishes in milliseconds, so a job is
        // already COMPLETED by the time a second request arrives. The accepted-cancel path is
        // covered deterministically in SesExportJobServiceTest.
        String finished = create(insightsSource()).then().statusCode(200).extract().path("JobId");
        assertEquals("COMPLETED", awaitTerminal(finished));

        given().contentType("application/json").header("Authorization", AUTH)
        .when().put("/v2/email/export-jobs/" + finished + "/cancel").then().statusCode(400)
                .body("message", equalTo(
                        "Cannot cancel an export job that has a <COMPLETED> status."));
    }

    @Test
    @Order(9)
    void getExportJob_unknownJobIsABadRequest() {
        given().header("Authorization", AUTH)
        .when().get("/v2/email/export-jobs/no-such-job").then().statusCode(400)
                .body("message", equalTo("Invalid JobId"));
    }

    @Test
    @Order(10)
    void listExportJobs_filtersAndPaginates() {
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
        .when().post("/v2/email/list-export-jobs").then().statusCode(200)
                .body("ExportJobs.JobStatus", everyItem(equalTo("COMPLETED")));

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"ExportSourceType\": \"METRICS_DATA\"}")
        .when().post("/v2/email/list-export-jobs").then().statusCode(200)
                .body("ExportJobs", hasSize(1));

        String nextToken = given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"PageSize\": 1}")
        .when().post("/v2/email/list-export-jobs").then().statusCode(200)
                .body("ExportJobs", hasSize(1))
                .extract().path("NextToken");
        assertNotNull(nextToken, "a truncated page carries a NextToken");
    }

    @Test
    @Order(11)
    void insightsExport_filtersOnTheTenantTheSendNamed() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"TenantName\": \"export-tenant\"}")
        .when().post("/v2/email/tenants").then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"TenantName": "export-tenant",
                     "ResourceArn": "arn:aws:ses:eu-west-2:000000000000:identity/%s"}
                    """.formatted(SENDER))
        .when().post("/v2/email/tenants/resources").then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("""
                    {"FromEmailAddress": "%s", "TenantName": "export-tenant",
                     "Destination": {"ToAddresses": ["in-tenant@example.com"]},
                     "Content": {"Simple": {"Subject": {"Data": "t"},
                                            "Body": {"Text": {"Data": "hi"}}}}}
                    """.formatted(SENDER))
        .when().post("/v2/email/outbound-emails").then().statusCode(200);

        // reader@example.com was sent at order 6 without a tenant, so it is the row the filter
        // has to drop. An Include that matched everything would keep it.
        assertTrue(insightsExport("\"Include\": {\"TenantName\": [\"export-tenant\"]},")
                .contains("in-tenant@example.com"));
        assertFalse(insightsExport("\"Include\": {\"TenantName\": [\"export-tenant\"]},")
                .contains("reader@example.com"));

        String excluded = insightsExport("\"Exclude\": {\"TenantName\": [\"export-tenant\"]},");
        assertTrue(excluded.contains("reader@example.com"), excluded);
        assertFalse(excluded.contains("in-tenant@example.com"), excluded);
    }

    /** Runs a message-insights export carrying the given filter members and returns the file. */
    private static String insightsExport(String filters) {
        String jobId = create("""
            {"ExportDataSource": {"MessageInsightsDataSource":
                {%s "StartDate": %d, "EndDate": %d}},
             "ExportDestination": {"DataFormat": "CSV"}}
            """.formatted(filters, Instant.now().minus(Duration.ofDays(1)).getEpochSecond(),
                Instant.now().plus(Duration.ofDays(1)).getEpochSecond()))
        .then().statusCode(200).extract().path("JobId");
        assertEquals("COMPLETED", awaitTerminal(jobId));

        String url = given().header("Authorization", AUTH)
        .when().get("/v2/email/export-jobs/" + jobId).then().statusCode(200)
                .extract().path("ExportDestination.S3Url");
        String path = url.substring(url.indexOf('/', url.indexOf("//") + 2));
        return given().when().get(path).then().statusCode(200).extract().asString();
    }

    @Test
    @Order(12)
    void createExportJob_validatesTheMetricsSourceMembers() {
        // Without these an unknown dimension was dropped, an unknown metric aggregated to zero and
        // an unrecognised aggregation was read as VOLUME, so the job completed with a meaningless
        // file. The wording follows BatchGetMetricData; AWS's own was not observed.
        metricsSource("{\"NOT_A_DIMENSION\": [\"*\"]}", "\"VDM\"", "[{\"Name\": \"SEND\"}]")
                .then().statusCode(400).body("message", containsString(
                        "Value at 'exportDataSource.metricsDataSource.dimensions' failed to satisfy "
                                + "constraint: Member must satisfy enum value set: "
                                + "[EMAIL_IDENTITY, CONFIGURATION_SET, ISP, TENANT_NAME]"));

        metricsSource("{\"ISP\": [\"*\"]}", "\"VDM\"", "[{\"Name\": \"NOT_A_METRIC\"}]")
                .then().statusCode(400).body("message", containsString(
                        "'exportDataSource.metricsDataSource.metrics.1.member.name'"));

        metricsSource("{\"ISP\": [\"*\"]}", "\"VDM\"",
                "[{\"Name\": \"SEND\", \"Aggregation\": \"AVERAGE\"}]")
                .then().statusCode(400).body("message", containsString(
                        "'exportDataSource.metricsDataSource.metrics.1.member.aggregation'"));

        metricsSource("{}", "\"SES\"", "[]")
                .then().statusCode(400).body("message", containsString("3 validation errors"));

        metricsSource("{\"ISP\": []}", "\"VDM\"", "[{\"Name\": \"SEND\"}]")
                .then().statusCode(400).body("message", containsString(
                        "'exportDataSource.metricsDataSource.dimensions.ISP'"));
    }

    @Test
    @Order(13)
    void metricsExport_matchesAnIdentityDomainTheWayTheBatchQueryDoes() {
        // The sender is an address; a domain names the same identity, so it has to select the row.
        String jobId = metricsSource("{\"EMAIL_IDENTITY\": [\"example.com\"]}", "\"VDM\"",
                "[{\"Name\": \"SEND\", \"Aggregation\": \"VOLUME\"}]")
                .then().statusCode(200).extract().path("JobId");
        assertEquals("COMPLETED", awaitTerminal(jobId));

        String url = given().header("Authorization", AUTH)
        .when().get("/v2/email/export-jobs/" + jobId).then().statusCode(200)
                .extract().path("ExportDestination.S3Url");
        String path = url.substring(url.indexOf('/', url.indexOf("//") + 2));
        String body = given().when().get(path).then().statusCode(200).extract().asString();

        assertTrue(body.startsWith("EMAIL_IDENTITY,SEND_VOLUME\n"), body);
        assertTrue(body.contains(SENDER), "a domain filter has to match the addresses inside it");
    }

    @Test
    @Order(14)
    void malformedPagingAndResultLimitsAreRefused() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"PageSize\": \"ten\"}")
        .when().post("/v2/email/list-export-jobs").then().statusCode(400);

        create("""
            {"ExportDataSource": {"MessageInsightsDataSource":
                {"MaxResults": -1, "StartDate": %d, "EndDate": %d}},
             "ExportDestination": {"DataFormat": "CSV"}}
            """.formatted(midnightToday(), midnightToday() + 86400))
        .then().statusCode(400).body("message", containsString("Member must be between 1 and 10000"));
    }

    /** A metrics export request with the three members the service validates. */
    private static Response metricsSource(String dimensions, String namespace, String metrics) {
        return create("""
            {"ExportDataSource": {"MetricsDataSource": {"Dimensions": %s,
                                                        "Namespace": %s,
                                                        "Metrics": %s,
                                                        "StartDate": %d, "EndDate": %d}},
             "ExportDestination": {"DataFormat": "CSV"}}
            """.formatted(dimensions, namespace, metrics, midnightToday(),
                midnightToday() + 86400));
    }

    @Test
    @Order(15)
    void exportJob_runsUnderTheCallersAccount() {
        // The worker runs on its own thread, where account-aware storage falls back to the default
        // account unless the caller's is carried over: the job would then never be found again.
        String otherAccount = "AWS4-HMAC-SHA256 Credential=111122223333/20260101/eu-west-2/ses/"
                + "aws4_request";
        given().contentType("application/json").header("Authorization", otherAccount)
                .body("{\"VdmAttributes\":{\"VdmEnabled\":\"ENABLED\"}}")
        .when().put("/v2/email/account/vdm").then().statusCode(200);

        String jobId = given().contentType("application/json").header("Authorization", otherAccount)
                .body(insightsSource())
        .when().post("/v2/email/export-jobs").then().statusCode(200).extract().path("JobId");

        String status = null;
        for (int attempt = 0; attempt < 100 && !"COMPLETED".equals(status); attempt++) {
            status = given().header("Authorization", otherAccount)
            .when().get("/v2/email/export-jobs/" + jobId).then().statusCode(200)
                    .extract().path("JobStatus");
            if ("PROCESSING".equals(status) || "CREATED".equals(status)) {
                status = null;
            }
        }
        assertEquals("COMPLETED", status, "the job never finished under its own account");

        // The default account cannot see it.
        given().header("Authorization", AUTH)
        .when().get("/v2/email/export-jobs/" + jobId).then().statusCode(400);

        given().contentType("application/json").header("Authorization", otherAccount)
                .body("{\"VdmAttributes\":{\"VdmEnabled\":\"DISABLED\"}}")
        .when().put("/v2/email/account/vdm");
    }

    @Test
    @Order(16)
    void listExportJobs_acceptsAPageSizeAboveTheDefault() {
        // PageSize carries no bound in the model, so a large page is a normal request.
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"PageSize\": 250}")
        .when().post("/v2/email/list-export-jobs").then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"PageSize\": 0}")
        .when().post("/v2/email/list-export-jobs").then().statusCode(400);

        // A page size at the int ceiling is clamped, not refused, and must not report a further
        // page: the paginator adds the page size to the offset as an int.
        String token = given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"PageSize\": 1}")
        .when().post("/v2/email/list-export-jobs").then().statusCode(200)
                .extract().path("NextToken");
        assertNotNull(token, "the fixture needs more than one job for this to mean anything");

        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"PageSize\": 2147483647, \"NextToken\": \"%s\"}".formatted(token))
        .when().post("/v2/email/list-export-jobs").then().statusCode(200)
                .body("$", not(hasKey("NextToken")));
    }

    @Test
    @Order(17)
    void createExportJob_rejectsMembersOfTheWrongType() {
        // Each of these was read as absent or coerced, so the job was created and produced a
        // successful but meaningless file. They are deserialization errors, like every other
        // typed SES v2 member.
        create("""
            {"ExportDataSource": {"MessageInsightsDataSource": {"StartDate": %d, "EndDate": %d},
                                  "MetricsDataSource": []},
             "ExportDestination": {"DataFormat": "CSV"}}
            """.formatted(midnightToday(), midnightToday() + 86400))
        .then().statusCode(400).body("__type", equalTo("SerializationException"));

        create("""
            {"ExportDataSource": {"MessageInsightsDataSource":
                {"Include": {"TenantName": "blue"}, "StartDate": %d, "EndDate": %d}},
             "ExportDestination": {"DataFormat": "CSV"}}
            """.formatted(midnightToday(), midnightToday() + 86400))
        .then().statusCode(400).body("__type", equalTo("SerializationException"));

        metricsSource("{\"ISP\": [1]}", "\"VDM\"", "[{\"Name\": \"SEND\"}]")
                .then().statusCode(400).body("__type", equalTo("SerializationException"));

        metricsSource("{\"ISP\": [\"*\"]}", "\"VDM\"", "[\"SEND\"]")
                .then().statusCode(400).body("__type", equalTo("SerializationException"));

        // A MaxResults of the wrong type is a deserialization error, not a value out of range.
        insightsWith("\"MaxResults\": \"100\",")
                .then().statusCode(400).body("__type", equalTo("SerializationException"));
    }

    @Test
    @Order(18)
    void createExportJob_holdsEachFilterMemberToItsLength() {
        // The model allows one Subject and two LastEngagementEvent values.
        insightsWith("\"Include\": {\"Subject\": [\"one\", \"two\"]},")
                .then().statusCode(400).body("message", equalTo(
                        "1 validation error detected: Value at 'exportDataSource."
                                + "messageInsightsDataSource.include.subject' failed to satisfy "
                                + "constraint: Member must have length less than or equal to 1"));

        insightsWith("\"Exclude\": {\"LastEngagementEvent\": [\"OPEN\", \"CLICK\", \"OPEN\"]},")
                .then().statusCode(400).body("message", containsString(
                        "exclude.lastEngagementEvent' failed to satisfy constraint: Member must "
                                + "have length less than or equal to 2"));

        insightsWith("\"Include\": {\"Isp\": [\"a\", \"b\", \"c\", \"d\", \"e\"]},")
                .then().statusCode(200);
    }

    /** A message-insights export request carrying the given extra members. */
    private static Response insightsWith(String members) {
        return create("""
            {"ExportDataSource": {"MessageInsightsDataSource":
                {%s "StartDate": %d, "EndDate": %d}},
             "ExportDestination": {"DataFormat": "CSV"}}
            """.formatted(members, midnightToday(), midnightToday() + 86400));
    }

    @Test
    @Order(19)
    void cleanUpVdmAndIdentity() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"VdmAttributes\":{\"VdmEnabled\":\"DISABLED\"}}")
        .when().put("/v2/email/account/vdm");
        given().header("Authorization", AUTH)
        .when().delete("/v2/email/identities/" + SENDER);
    }
}
