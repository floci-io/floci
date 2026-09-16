package io.github.hectorvent.floci.services.marketplace;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class MarketplaceMeteringIntegrationTest {
    @BeforeAll static void configure() { RestAssuredJsonUtils.configureAwsContentTypes(); }

    @Test
    void resolveCustomerIsStableForRegistrationToken() {
        String body = "{\"RegistrationToken\":\"local-registration-token\"}";
        var first = rpc("ResolveCustomer", body).statusCode(200)
                .body("CustomerAWSAccountId", notNullValue()).body("LicenseArn", notNullValue())
                .extract().response();
        rpc("ResolveCustomer", body).statusCode(200)
                .body("CustomerAWSAccountId", equalTo(first.path("CustomerAWSAccountId")))
                .body("LicenseArn", equalTo(first.path("LicenseArn")));
    }

    @Test
    void meterUsageIsIdempotentWithinHourForSameQuantity() {
        long timestamp = System.currentTimeMillis() / 1000L;
        String body = "{\"ProductCode\":\"prod-local\",\"Timestamp\":" + timestamp
                + ",\"UsageDimension\":\"requests\",\"UsageQuantity\":3}";
        String recordId = rpc("MeterUsage", body).statusCode(200)
                .body("MeteringRecordId", notNullValue()).extract().path("MeteringRecordId");
        rpc("MeterUsage", body).statusCode(200).body("MeteringRecordId", equalTo(recordId));
    }

    @Test
    void batchMeterUsageReportsDuplicateRecordForChangedQuantity() {
        long timestamp = System.currentTimeMillis() / 1000L;
        String base = "{\"ProductCode\":\"prod-local\",\"UsageRecords\":[{\"Timestamp\":" + timestamp
                + ",\"CustomerAWSAccountId\":\"123456789012\",\"Dimension\":\"users\",\"Quantity\":";
        rpc("BatchMeterUsage", base + "1}]}").statusCode(200).body("Results[0].Status", equalTo("Success"));
        rpc("BatchMeterUsage", base + "2}]}").statusCode(200).body("Results[0].Status", equalTo("DuplicateRecord"));
    }

    @Test
    void registerUsageReturnsSignature() {
        rpc("RegisterUsage", "{\"ProductCode\":\"prod-local\",\"PublicKeyVersion\":1,\"Nonce\":\"instance-1\"}")
                .statusCode(200).body("Signature", matchesPattern("^[^.]+\\.[^.]+\\.[^.]+$"));
    }

    private static io.restassured.response.ValidatableResponse rpc(String action, String body) {
        return given().contentType("application/x-amz-json-1.1").header("Authorization", auth())
                .header("X-Amz-Target", "AWSMPMeteringService." + action).body(body).post("/").then();
    }

    private static String auth() { return "AWS4-HMAC-SHA256 Credential=000000000000/20260908/us-east-1/aws-marketplace/aws4_request"; }
}
