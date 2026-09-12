package io.github.hectorvent.floci.services.wafv2;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Validates that {@code CreateIPSet} and {@code UpdateIPSet} reject {@code Addresses}
 * entries that are not valid CIDR blocks for the declared {@code IPAddressVersion},
 * matching the AWS WAFv2 API contract (issue #3328).
 */
@QuarkusTest
class WafV2IpSetCidrValidationIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";
    private static final String PREFIX = "AWSWAF_20190729.";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return given().contentType(CT).header("X-Amz-Target", PREFIX + action)
                .body(body).when().post("/");
    }

    @Test
    void bareIpv4AddressIsRejected() {
        call("CreateIPSet",
                "{\"Name\":\"cidr-validation-bare-ipv4\",\"Scope\":\"REGIONAL\",\"IPAddressVersion\":\"IPV4\","
                        + "\"Addresses\":[\"203.0.113.10\"]}")
                .then().statusCode(400)
                .body("__type", equalTo("WAFInvalidParameterException"));
    }

    @Test
    void validIpv4CidrSucceeds() {
        call("CreateIPSet",
                "{\"Name\":\"cidr-validation-valid-ipv4\",\"Scope\":\"REGIONAL\",\"IPAddressVersion\":\"IPV4\","
                        + "\"Addresses\":[\"203.0.113.10/32\"]}")
                .then().statusCode(200)
                .body("Summary.Id", notNullValue());
    }

    @Test
    void bareIpv6AddressIsRejected() {
        call("CreateIPSet",
                "{\"Name\":\"cidr-validation-bare-ipv6\",\"Scope\":\"REGIONAL\",\"IPAddressVersion\":\"IPV6\","
                        + "\"Addresses\":[\"1111:0000:0000:0000:0000:0000:0000:0111\"]}")
                .then().statusCode(400)
                .body("__type", equalTo("WAFInvalidParameterException"));
    }

    @Test
    void validIpv6CidrSucceeds() {
        call("CreateIPSet",
                "{\"Name\":\"cidr-validation-valid-ipv6\",\"Scope\":\"REGIONAL\",\"IPAddressVersion\":\"IPV6\","
                        + "\"Addresses\":[\"1111:0000:0000:0000:0000:0000:0000:0111/128\"]}")
                .then().statusCode(200)
                .body("Summary.Id", notNullValue());
    }

    @Test
    void outOfRangeIpv4PrefixIsRejected() {
        call("CreateIPSet",
                "{\"Name\":\"cidr-validation-out-of-range\",\"Scope\":\"REGIONAL\",\"IPAddressVersion\":\"IPV4\","
                        + "\"Addresses\":[\"203.0.113.10/33\"]}")
                .then().statusCode(400)
                .body("__type", equalTo("WAFInvalidParameterException"));
    }

    @Test
    void mismatchedAddressFamilyIsRejected() {
        call("CreateIPSet",
                "{\"Name\":\"cidr-validation-mismatched-family\",\"Scope\":\"REGIONAL\",\"IPAddressVersion\":\"IPV4\","
                        + "\"Addresses\":[\"1111:0000:0000:0000:0000:0000:0000:0111/128\"]}")
                .then().statusCode(400)
                .body("__type", equalTo("WAFInvalidParameterException"));
    }

    @Test
    void updateIpSetValidatesAddresses() {
        Response created = call("CreateIPSet",
                "{\"Name\":\"cidr-validation-update\",\"Scope\":\"REGIONAL\",\"IPAddressVersion\":\"IPV4\","
                        + "\"Addresses\":[\"203.0.113.10/32\"]}");
        created.then().statusCode(200);
        String id = created.jsonPath().getString("Summary.Id");
        String lockToken = created.jsonPath().getString("Summary.LockToken");

        call("UpdateIPSet",
                "{\"Name\":\"cidr-validation-update\",\"Scope\":\"REGIONAL\",\"Id\":\"" + id + "\","
                        + "\"LockToken\":\"" + lockToken + "\",\"Addresses\":[\"198.51.100.5\"]}")
                .then().statusCode(400)
                .body("__type", equalTo("WAFInvalidParameterException"));

        call("UpdateIPSet",
                "{\"Name\":\"cidr-validation-update\",\"Scope\":\"REGIONAL\",\"Id\":\"" + id + "\","
                        + "\"LockToken\":\"" + lockToken + "\",\"Addresses\":[\"198.51.100.5/32\"]}")
                .then().statusCode(200)
                .body("NextLockToken", notNullValue());
    }
}
