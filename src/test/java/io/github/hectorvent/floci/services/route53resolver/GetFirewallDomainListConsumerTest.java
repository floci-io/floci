package io.github.hectorvent.floci.services.route53resolver;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@code Route53Resolver.GetFirewallDomainList}.
 *
 * <p>Success path exercises the happy path by listing the pre-seeded AWS-managed
 * domain lists, picking the first stable Id, and asserting the shape returned by
 * {@code GetFirewallDomainList}. The not-found path verifies that an invalid Id
 * produces the AWS-style {@code ResourceNotFoundException} (HTTP 400 with
 * {@code __type} in the body).</p>
 */
@QuarkusTest
class GetFirewallDomainListConsumerTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/route53resolver/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getFirewallDomainList_success() {
        // 1. List to obtain a real, seeded FirewallDomainList Id. Other tests create
        //    custom lists into the same store, so assert the managed lists are present
        //    by name rather than pinning the total or an index.
        String firewallDomainListId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.ListFirewallDomainLists")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FirewallDomainLists", notNullValue())
            .body("FirewallDomainLists.Name", hasItems(
                    "AWSManagedDomainsAggregateThreatList",
                    "AWSManagedDomainsAmazonGuardDutyThreatList",
                    "AWSManagedDomainsBotnetCommandandControl",
                    "AWSManagedDomainsMalwareDomainList"))
            .extract().path(
                    "FirewallDomainLists.find { it.Name == 'AWSManagedDomainsAggregateThreatList' }.Id");

        // 2. Get the individual list and assert the response shape.
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.GetFirewallDomainList")
            .header("Authorization", AUTH_HEADER)
            .body("{\"FirewallDomainListId\":\"" + firewallDomainListId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FirewallDomainList.Id", equalTo(firewallDomainListId))
            .body("FirewallDomainList.Arn", startsWith("arn:aws:route53resolver:"))
            .body("FirewallDomainList.Name", notNullValue())
            .body("FirewallDomainList.Name", not(equalTo("")));
    }

    @Test
    void getFirewallDomainList_notFound_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.GetFirewallDomainList")
            .header("Authorization", AUTH_HEADER)
            .body("{\"FirewallDomainListId\":\"rslvr-fdl-nonexistent\"}")
        .when()
            .post("/")
        .then()
            // 404, matching Floci's dominant convention for ResourceNotFoundException.
            // The Botocore packet does not pin an httpStatusCode for this shape and no
            // AWS doc quote was obtained, so the real AWS status is UNVERIFIED —
            // recorded as a known ambiguity rather than asserted as parity.
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
