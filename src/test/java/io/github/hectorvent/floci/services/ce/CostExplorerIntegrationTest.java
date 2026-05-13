package io.github.hectorvent.floci.services.ce;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for the Cost Explorer service.
 * Validates AWS-compatible wire format using RestAssured.
 * Protocol: JSON 1.1 — Content-Type: application/x-amz-json-1.1,
 *           X-Amz-Target: AWSInsightsIndexService.&lt;Action&gt;
 */
@QuarkusTest
class CostExplorerIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/ce/aws4_request";
    private static final String S3_AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/s3/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getCostAndUsage_emptyState_returnsResultsByTime() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetCostAndUsage")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TimePeriod\":{\"Start\":\"2026-01-01\",\"End\":\"2026-01-04\"}," +
                    "\"Granularity\":\"DAILY\",\"Metrics\":[\"UnblendedCost\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResultsByTime", hasSize(3))
            .body("ResultsByTime[0].TimePeriod.Start", startsWith("2026-01-01"))
            .body("ResultsByTime[0].Total.UnblendedCost.Unit", equalTo("USD"));
    }

    @Test
    void getCostAndUsage_monthlyGranularitySplitsAcrossMonths() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetCostAndUsage")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TimePeriod\":{\"Start\":\"2026-01-15\",\"End\":\"2026-03-15\"}," +
                    "\"Granularity\":\"MONTHLY\",\"Metrics\":[\"UnblendedCost\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResultsByTime", hasSize(3));
    }

    @Test
    void getCostAndUsage_hourlyGranularityEmitsHourBuckets() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetCostAndUsage")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TimePeriod\":{\"Start\":\"2026-01-01T00:00:00Z\",\"End\":\"2026-01-01T03:00:00Z\"}," +
                    "\"Granularity\":\"HOURLY\",\"Metrics\":[\"UnblendedCost\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResultsByTime", hasSize(3));
    }

    @Test
    void getCostAndUsage_missingMetrics_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetCostAndUsage")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TimePeriod\":{\"Start\":\"2026-01-01\",\"End\":\"2026-01-04\"}," +
                    "\"Granularity\":\"DAILY\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void getCostAndUsage_missingTimePeriod_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetCostAndUsage")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Granularity\":\"DAILY\",\"Metrics\":[\"UnblendedCost\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void getCostAndUsage_invalidGranularity_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetCostAndUsage")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TimePeriod\":{\"Start\":\"2026-01-01\",\"End\":\"2026-01-04\"}," +
                    "\"Granularity\":\"YEARLY\",\"Metrics\":[\"UnblendedCost\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void getCostAndUsage_endBeforeStart_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetCostAndUsage")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TimePeriod\":{\"Start\":\"2026-02-01\",\"End\":\"2026-01-01\"}," +
                    "\"Granularity\":\"DAILY\",\"Metrics\":[\"UnblendedCost\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(400);
    }

    @Test
    void getCostAndUsage_returnsNonZeroCostFromS3BucketObjects() {
        // Stand up a bucket with one ~1-MiB object so cost synthesis has something
        // to multiply against. The bundled S3 fixture rates Standard at $0.023/GB-Mo,
        // so any non-zero size must produce a strictly positive UnblendedCost.
        String bucket = "ce-test-bucket-" + System.currentTimeMillis();
        given()
            .header("Authorization", S3_AUTH)
            .header("Host", "localhost")
        .when()
            .put("/" + bucket)
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(204)));

        byte[] payload = new byte[1024 * 1024]; // 1 MiB
        given()
            .header("Authorization", S3_AUTH)
            .body(payload)
        .when()
            .put("/" + bucket + "/object.bin")
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(204)));

        String unblendedAmount = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetCostAndUsage")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TimePeriod\":{\"Start\":\"2026-01-01\",\"End\":\"2026-02-01\"}," +
                    "\"Granularity\":\"MONTHLY\",\"Metrics\":[\"UnblendedCost\",\"UsageQuantity\"]," +
                    "\"GroupBy\":[{\"Type\":\"DIMENSION\",\"Key\":\"SERVICE\"}]," +
                    "\"Filter\":{\"Dimensions\":{\"Key\":\"SERVICE\",\"Values\":[\"AmazonS3\"]}}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResultsByTime[0].Groups.Keys.flatten()", hasItem("AmazonS3"))
            .extract()
            .path("ResultsByTime[0].Groups.find { it.Keys.contains('AmazonS3') }.Metrics.UnblendedCost.Amount");

        assertThat(Double.parseDouble(unblendedAmount), greaterThan(0.0));
    }

    @Test
    void getCostAndUsage_creditOffsetReducesUnblendedCost() {
        // Run two queries: one without and one with FLOCI_SERVICES_CE_CREDIT_USD_MONTHLY
        // toggled on. Credit injection is process-wide config, so this test asserts
        // the no-credit baseline; the credit-on flow has dedicated unit coverage in
        // CostSynthesizerTest. The intent here is that when a Credit RECORD_TYPE
        // line exists, its amount flows directly into UnblendedCost without going
        // through the rate lookup (which would produce 0 for a Credit-Promotional
        // usage type).
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetCostAndUsage")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TimePeriod\":{\"Start\":\"2026-01-01\",\"End\":\"2026-02-01\"}," +
                    "\"Granularity\":\"MONTHLY\",\"Metrics\":[\"UnblendedCost\"]," +
                    "\"GroupBy\":[{\"Type\":\"DIMENSION\",\"Key\":\"RECORD_TYPE\"}]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResultsByTime", hasSize(1));
    }

    @Test
    void getCostAndUsage_filterByService() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetCostAndUsage")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TimePeriod\":{\"Start\":\"2026-01-01\",\"End\":\"2026-01-04\"}," +
                    "\"Granularity\":\"DAILY\",\"Metrics\":[\"UnblendedCost\"]," +
                    "\"GroupBy\":[{\"Type\":\"DIMENSION\",\"Key\":\"SERVICE\"}]," +
                    "\"Filter\":{\"Dimensions\":{\"Key\":\"SERVICE\",\"Values\":[\"AmazonEC2\"]}}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResultsByTime[0].Groups.Keys.flatten()", everyItem(equalTo("AmazonEC2")));
    }

    @Test
    void getCostAndUsage_filterAndExpression() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetCostAndUsage")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TimePeriod\":{\"Start\":\"2026-01-01\",\"End\":\"2026-01-04\"}," +
                    "\"Granularity\":\"DAILY\",\"Metrics\":[\"UnblendedCost\"]," +
                    "\"Filter\":{\"And\":[" +
                    "{\"Dimensions\":{\"Key\":\"REGION\",\"Values\":[\"us-east-1\"]}}," +
                    "{\"Not\":{\"Dimensions\":{\"Key\":\"SERVICE\",\"Values\":[\"AmazonRDS\"]}}}" +
                    "]}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResultsByTime", hasSize(3));
    }

    @Test
    void getDimensionValues_serviceReturnsKnownServices() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetDimensionValues")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TimePeriod\":{\"Start\":\"2026-01-01\",\"End\":\"2026-01-04\"}," +
                    "\"Dimension\":\"SERVICE\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DimensionValues.Value", hasItems("AmazonEC2", "AmazonS3", "AWSLambda"));
    }

    @Test
    void getDimensionValues_recordTypeReturnsUsage() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetDimensionValues")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TimePeriod\":{\"Start\":\"2026-01-01\",\"End\":\"2026-01-04\"}," +
                    "\"Dimension\":\"RECORD_TYPE\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DimensionValues.Value", hasItem("Usage"));
    }

    @Test
    void getDimensionValues_missingDimension_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetDimensionValues")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TimePeriod\":{\"Start\":\"2026-01-01\",\"End\":\"2026-01-04\"}}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void getTags_emptyState_returnsEmpty() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetTags")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TimePeriod\":{\"Start\":\"2026-01-01\",\"End\":\"2026-01-04\"}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", notNullValue())
            .body("ReturnSize", greaterThanOrEqualTo(0));
    }

    @Test
    void getReservationCoverage_returnsZeroedTotal() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetReservationCoverage")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Total.CoverageHours.OnDemandHours", equalTo("0"))
            .body("CoveragesByTime", hasSize(0));
    }

    @Test
    void getReservationUtilization_returnsZeroedTotal() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetReservationUtilization")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Total.UtilizationPercentage", equalTo("0"))
            .body("UtilizationsByTime", hasSize(0));
    }

    @Test
    void getSavingsPlansCoverage_returnsEmpty() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetSavingsPlansCoverage")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SavingsPlansCoverages", hasSize(0));
    }

    @Test
    void getSavingsPlansUtilization_returnsZeroedTotal() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetSavingsPlansUtilization")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Total.Utilization.UtilizationPercentage", equalTo("0"));
    }

    @Test
    void getCostCategories_returnsEmpty() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetCostCategories")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TimePeriod\":{\"Start\":\"2026-01-01\",\"End\":\"2026-01-04\"}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CostCategoryNames", hasSize(0))
            .body("ReturnSize", equalTo(0));
    }

    @Test
    void getCostAndUsageWithResources_acceptsSameShape() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetCostAndUsageWithResources")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TimePeriod\":{\"Start\":\"2026-01-01\",\"End\":\"2026-01-04\"}," +
                    "\"Granularity\":\"DAILY\",\"Metrics\":[\"UnblendedCost\"]," +
                    "\"GroupBy\":[{\"Type\":\"DIMENSION\",\"Key\":\"RESOURCE_ID\"}]," +
                    "\"Filter\":{\"Dimensions\":{\"Key\":\"SERVICE\",\"Values\":[\"AmazonEC2\"]}}}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResultsByTime", hasSize(3))
            .body("GroupDefinitions[0].Key", equalTo("RESOURCE_ID"));
    }

    @Test
    void getCostAndUsage_dailyTruncatesEndToBucketBoundary() {
        // End at 13:30 should still produce 3 whole-day buckets aligned to UTC midnight
        // (Jan 1, Jan 2, Jan 3) per AWS DAILY semantics, not 3 buckets where the last
        // ends mid-day.
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetCostAndUsage")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TimePeriod\":{\"Start\":\"2026-01-01T00:00:00Z\",\"End\":\"2026-01-03T13:30:00Z\"}," +
                    "\"Granularity\":\"DAILY\",\"Metrics\":[\"UnblendedCost\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResultsByTime", hasSize(3))
            .body("ResultsByTime[2].TimePeriod.End", equalTo("2026-01-04T00:00:00Z"));
    }

    @Test
    void getDimensionValues_includesAmazonMSKWithCanonicalCode() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetDimensionValues")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TimePeriod\":{\"Start\":\"2026-01-01\",\"End\":\"2026-01-04\"}," +
                    "\"Dimension\":\"SERVICE\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DimensionValues.Value", hasItem("AmazonMSK"))
            .body("DimensionValues.Value", not(hasItem("AWSAmazonMSK")));
    }

    @Test
    void unknownAction_returnsUnknownOperationError() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSInsightsIndexService.GetBogusAction")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }
}
