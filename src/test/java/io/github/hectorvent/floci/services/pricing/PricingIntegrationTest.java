package io.github.hectorvent.floci.services.pricing;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for the AWS Pricing (Price List Service) emulation.
 * Validates AWS-compatible wire format using RestAssured.
 * Protocol: JSON 1.1 — Content-Type: application/x-amz-json-1.1,
 *           X-Amz-Target: AWSPriceListService.&lt;Action&gt;
 */
@QuarkusTest
class PricingIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/pricing/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void describeServices_noArgs_returnsBundledServices() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.DescribeServices")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FormatVersion", equalTo("aws_v1"))
            .body("Services.ServiceCode", hasItems("AmazonEC2", "AmazonS3", "AWSLambda"))
            .body("Services.find { it.ServiceCode == 'AmazonEC2' }.AttributeNames",
                    hasItems("instanceType", "location"));
    }

    @Test
    void describeServices_specificServiceCode_returnsSingleEntry() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.DescribeServices")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"AmazonEC2\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Services", hasSize(1))
            .body("Services[0].ServiceCode", equalTo("AmazonEC2"));
    }

    @Test
    void describeServices_unknownServiceCode_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.DescribeServices")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"AmazonBogus\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void getAttributeValues_instanceType_returnsValues() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.GetAttributeValues")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"AmazonEC2\",\"AttributeName\":\"instanceType\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AttributeValues.Value", hasItems("t3.micro", "m5.large"));
    }

    @Test
    void getAttributeValues_missingServiceCode_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.GetAttributeValues")
            .header("Authorization", AUTH_HEADER)
            .body("{\"AttributeName\":\"instanceType\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void getAttributeValues_unknownAttribute_returnsEmpty() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.GetAttributeValues")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"AmazonEC2\",\"AttributeName\":\"unknownAttr\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AttributeValues", hasSize(0));
    }

    @Test
    void getProducts_filtersByInstanceType() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.GetProducts")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"AmazonEC2\",\"Filters\":[" +
                    "{\"Type\":\"TERM_MATCH\",\"Field\":\"instanceType\",\"Value\":\"t3.micro\"}," +
                    "{\"Type\":\"TERM_MATCH\",\"Field\":\"regionCode\",\"Value\":\"us-east-1\"}]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FormatVersion", equalTo("aws_v1"))
            .body("PriceList", hasSize(1))
            .body("PriceList[0]", containsString("t3.micro"))
            .body("PriceList[0]", containsString("AmazonEC2"));
    }

    @Test
    void getProducts_noFilters_returnsAllForDefaultRegion() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.GetProducts")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"AmazonEC2\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("PriceList", hasSize(3));
    }

    @Test
    void getProducts_missingServiceCode_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.GetProducts")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void getProducts_unknownServiceCode_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.GetProducts")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"AmazonBogus\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void listPriceLists_returnsMatchingRegion() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.ListPriceLists")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"AmazonEC2\",\"EffectiveDate\":\"2026-01-01T00:00:00Z\"," +
                    "\"CurrencyCode\":\"USD\",\"RegionCode\":\"us-east-1\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("PriceLists", hasSize(1))
            .body("PriceLists[0].RegionCode", equalTo("us-east-1"))
            .body("PriceLists[0].CurrencyCode", equalTo("USD"))
            .body("PriceLists[0].FileFormats", hasItems("json", "csv"));
    }

    @Test
    void listPriceLists_withoutRegion_returnsAll() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.ListPriceLists")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"AmazonEC2\",\"EffectiveDate\":\"2026-01-01T00:00:00Z\"," +
                    "\"CurrencyCode\":\"USD\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("PriceLists", hasSize(2));
    }

    @Test
    void listPriceLists_missingEffectiveDate_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.ListPriceLists")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"AmazonEC2\",\"CurrencyCode\":\"USD\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void getPriceListFileUrl_returnsStubUrl() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.GetPriceListFileUrl")
            .header("Authorization", AUTH_HEADER)
            .body("{\"PriceListArn\":\"arn:aws:pricing:::price-list/aws/AmazonEC2/USD/20260101000000/us-east-1\"," +
                    "\"FileFormat\":\"json\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Url", startsWith("https://pricing-snapshot.floci.local/"))
            .body("Url", endsWith("/json"));
    }

    @Test
    void getPriceListFileUrl_missingArn_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.GetPriceListFileUrl")
            .header("Authorization", AUTH_HEADER)
            .body("{\"FileFormat\":\"json\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void unknownAction_returnsUnknownOperationError() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.GetBogusAction")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    void getAttributeValues_rejectsPathTraversalInAttributeName() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.GetAttributeValues")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"AmazonEC2\",\"AttributeName\":\"../../../../etc/passwd\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void getProducts_rejectsPathTraversalInRegionFilter() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.GetProducts")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"AmazonEC2\",\"Filters\":[" +
                    "{\"Type\":\"TERM_MATCH\",\"Field\":\"regionCode\",\"Value\":\"../../etc\"}]}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void listPriceLists_selectsNewestVersionAtOrBeforeEffectiveDate() {
        // Before the 2026-04-01 version begins, expect the 2026-01-01 ARN.
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.ListPriceLists")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"AmazonEC2\",\"EffectiveDate\":\"2026-02-15T00:00:00Z\"," +
                    "\"CurrencyCode\":\"USD\",\"RegionCode\":\"us-east-1\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("PriceLists", hasSize(1))
            .body("PriceLists[0].PriceListArn", endsWith("20260101000000/us-east-1"));

        // After the 2026-04-01 version begins, expect that newer ARN.
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.ListPriceLists")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"AmazonEC2\",\"EffectiveDate\":\"2026-06-01T00:00:00Z\"," +
                    "\"CurrencyCode\":\"USD\",\"RegionCode\":\"us-east-1\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("PriceLists", hasSize(1))
            .body("PriceLists[0].PriceListArn", endsWith("20260401000000/us-east-1"));
    }

    @Test
    void listPriceLists_excludesEntriesNotYetEffective() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.ListPriceLists")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"AmazonEC2\",\"EffectiveDate\":\"2025-01-01T00:00:00Z\"," +
                    "\"CurrencyCode\":\"USD\",\"RegionCode\":\"us-east-1\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("PriceLists", hasSize(0));
    }

    @Test
    void listPriceLists_malformedEffectiveDate_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.ListPriceLists")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"AmazonEC2\",\"EffectiveDate\":\"not-a-date\"," +
                    "\"CurrencyCode\":\"USD\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void listPriceLists_acceptsNumericEpochEffectiveDate() {
        // 1767225600 = 2026-01-01T00:00:00Z; before 2026-04-01, so expect the older ARN.
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.ListPriceLists")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"AmazonEC2\",\"EffectiveDate\":1767225600," +
                    "\"CurrencyCode\":\"USD\",\"RegionCode\":\"us-east-1\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("PriceLists", hasSize(1))
            .body("PriceLists[0].PriceListArn", endsWith("20260101000000/us-east-1"));
    }

    @Test
    void listPriceLists_acceptsFractionalEpochEffectiveDate() {
        // 1775001600 = 2026-04-01T00:00:00Z. 0.5s offset is still past that boundary.
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.ListPriceLists")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"AmazonEC2\",\"EffectiveDate\":1775001600.5," +
                    "\"CurrencyCode\":\"USD\",\"RegionCode\":\"us-east-1\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("PriceLists", hasSize(1))
            .body("PriceLists[0].PriceListArn", endsWith("20260401000000/us-east-1"));
    }

    @Test
    void getProducts_rejectsFilterWithMissingValue() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.GetProducts")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"AmazonEC2\",\"Filters\":[" +
                    "{\"Type\":\"TERM_MATCH\",\"Field\":\"instanceType\"}]}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void getProducts_rejectsFilterWithMissingField() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.GetProducts")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"AmazonEC2\",\"Filters\":[" +
                    "{\"Type\":\"TERM_MATCH\",\"Value\":\"t3.micro\"}]}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void getProducts_paginationTokenSlicesResults() {
        String next = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.GetProducts")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"AmazonEC2\",\"MaxResults\":2}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("PriceList", hasSize(2))
            .body("NextToken", notNullValue())
            .extract().path("NextToken");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSPriceListService.GetProducts")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"AmazonEC2\",\"MaxResults\":2,\"NextToken\":\"" + next + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("PriceList", hasSize(1))
            .body("NextToken", nullValue());
    }
}
