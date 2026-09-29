package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class Ec2DescribeSpotPriceHistoryIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260908/us-east-1/ec2/aws4_request";

    private static ValidatableResponse describeSpotPriceHistory(String... params) {
        io.restassured.specification.RequestSpecification request = given()
                .formParam("Action", "DescribeSpotPriceHistory")
                .header("Authorization", AUTH_HEADER);
        for (int i = 0; i < params.length; i += 2) {
            request.formParam(params[i], params[i + 1]);
        }
        return request.when().post("/").then();
    }

    @Test
    void anUnknownFilterNameIsRefused() {
        describeSpotPriceHistory("Filter.1.Name", "not-a-filter", "Filter.1.Value.1", "anything")
                .statusCode(400)
                .body(containsString("InvalidParameterValue"))
                .body(containsString("not-a-filter"));
    }

    @Test
    void theDocumentedFilterNamesAreAccepted() {
        for (String name : new String[]{"availability-zone", "availability-zone-id",
                "instance-type", "product-description", "spot-price", "timestamp"}) {
            describeSpotPriceHistory("Filter.1.Name", name, "Filter.1.Value.1", "whatever")
                    .statusCode(200);
        }
    }

    @Test
    void invalidProductDescriptionIsRefused() {
        describeSpotPriceHistory("ProductDescription.1", "InvalidPlatform")
                .statusCode(400)
                .body(containsString("InvalidParameterValue"))
                .body(containsString("ProductDescription"));
    }

    @Test
    void conflictingAvailabilityZoneAndIdIsRefused() {
        describeSpotPriceHistory("AvailabilityZone", "us-east-1a", "AvailabilityZoneId", "use1-az1")
                .statusCode(400)
                .body(containsString("InvalidParameterCombination"));
    }

    @Test
    void startTimeAfterEndTimeIsRefused() {
        describeSpotPriceHistory("StartTime", "2026-02-01T00:00:00Z", "EndTime", "2026-01-01T00:00:00Z")
                .statusCode(400)
                .body(containsString("InvalidParameterValue"));
    }

    @Test
    void returnsSpotPricesForKnownInstanceType() {
        describeSpotPriceHistory("InstanceType.1", "t3.micro", "AvailabilityZone", "us-east-1a")
                .statusCode(200)
                .body(containsString("<instanceType>t3.micro</instanceType>"))
                .body(containsString("<availabilityZone>us-east-1a</availabilityZone>"))
                .body(containsString("<productDescription>Linux/UNIX</productDescription>"))
                .body(containsString("<spotPrice>0.004400</spotPrice>"));
    }

    @Test
    void unknownInstanceTypeReturnsEmptySet() {
        describeSpotPriceHistory("InstanceType.1", "nonexistent.type")
                .statusCode(200)
                .body(not(containsString("<item>")));
    }
}
