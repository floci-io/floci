package io.github.hectorvent.floci.services.marketplace;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;

@QuarkusTest
class MarketplaceEntitlementIntegrationTest {
    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getEntitlementsReturnsEmptySetForUnknownProduct() {
        given()
                .contentType("application/x-amz-json-1.1")
                .header("Authorization", auth("us-east-1"))
                .header("X-Amz-Target", "AWSMPEntitlementService.GetEntitlements")
                .body("{\"ProductCode\":\"product-local\"}")
                .post("/")
                .then()
                .statusCode(200)
                .body("Entitlements", empty());
    }

    @Test
    void getEntitlementsRejectsMutuallyExclusiveCustomerFilters() {
        given()
                .contentType("application/x-amz-json-1.1")
                .header("Authorization", auth("us-east-1"))
                .header("X-Amz-Target", "AWSMPEntitlementService.GetEntitlements")
                .body("{\"ProductCode\":\"product-local\",\"Filter\":{\"CUSTOMER_IDENTIFIER\":[\"customer\"],\"CUSTOMER_AWS_ACCOUNT_ID\":[\"123456789012\"]}}")
                .post("/")
                .then()
                .statusCode(400)
                .body("__type", containsString("InvalidParameterException"));
    }

    @Test
    void entitlementEndpointRejectsUnsupportedRegion() {
        given()
                .contentType("application/x-amz-json-1.1")
                .header("Authorization", auth("us-west-2"))
                .header("X-Amz-Target", "AWSMPEntitlementService.GetEntitlements")
                .body("{\"ProductCode\":\"product-local\"}")
                .post("/")
                .then()
                .statusCode(400)
                .body("__type", containsString("InvalidParameterException"));
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=000000000000/20260908/" + region + "/aws-marketplace/aws4_request";
    }
}
