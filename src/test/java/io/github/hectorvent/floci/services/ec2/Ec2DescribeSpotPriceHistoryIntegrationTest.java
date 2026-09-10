package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class Ec2DescribeSpotPriceHistoryIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    @Test
    void describeSpotPriceHistoryReturnsEmptyAwsCompatibleSetWhenNoSnapshotConfigured() {
        given()
                .formParam("Action", "DescribeSpotPriceHistory")
                .formParam("InstanceType.1", "m5.large")
                .formParam("InstanceType.2", "t4g.medium")
                .formParam("ProductDescription.1", "Linux/UNIX")
                .formParam("AvailabilityZone.1", "us-east-1a")
                .formParam("StartTime", "2026-01-01T00:00:00Z")
                .formParam("EndTime", "2026-01-02T00:00:00Z")
                .formParam("MaxResults", "100")
                .header("Authorization", AUTH_HEADER)
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .contentType("application/xml")
                .body("DescribeSpotPriceHistoryResponse.requestId", notNullValue())
                .body("DescribeSpotPriceHistoryResponse.spotPriceHistorySet.item.size()", equalTo(0));
    }
}
