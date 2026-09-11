package io.github.hectorvent.floci.services.marketplace;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class MarketplaceReportingIntegrationTest {
    @BeforeAll static void configure() { RestAssuredJsonUtils.configureAwsContentTypes(); }

    @Test
    void getBuyerDashboardReturnsEmbeddingUrl() {
        String dashboard = "arn:aws:aws-marketplace::000000000000:AWSMarketplace/ReportingData/Agreement_V1/Dashboard/AgreementSummary_V1";
        given().contentType("application/json").header("Authorization", auth())
                .body("{\"dashboardIdentifier\":\"" + dashboard + "\",\"embeddingDomains\":[\"https://example.com\"]}")
                .post("/getBuyerDashboard").then().statusCode(200)
                .body("dashboardIdentifier", equalTo(dashboard)).body("embedUrl", notNullValue())
                .body("embeddingDomains[0]", equalTo("https://example.com"));
    }

    @Test
    void rejectsTrailingJson() {
        given().contentType("application/json").header("Authorization", auth())
                .body("{} {}")
                .post("/getBuyerDashboard").then().statusCode(400)
                .body("__type", containsString("BadRequestException"));
    }

    @Test
    void rejectsUnsupportedDashboardArn() {
        given().contentType("application/json").header("Authorization", auth())
                .body("{\"dashboardIdentifier\":\"bad\",\"embeddingDomains\":[\"https://example.com\"]}")
                .post("/getBuyerDashboard").then().statusCode(400)
                .body("__type", containsString("BadRequestException"));
    }

    private static String auth() { return "AWS4-HMAC-SHA256 Credential=000000000000/20260908/us-east-1/aws-marketplace/aws4_request"; }
}
