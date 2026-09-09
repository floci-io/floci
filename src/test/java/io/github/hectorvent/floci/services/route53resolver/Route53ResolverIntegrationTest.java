package io.github.hectorvent.floci.services.route53resolver;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.startsWith;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for Route 53 Resolver DNS Firewall operations
 * (JSON 1.1 protocol, {@code X-Amz-Target: Route53Resolver.*}).
 *
 * <p>LZA's {@code Custom::ResolverManagedDomainList} Lambda pages through
 * {@code ListFirewallDomainLists} and resolves an AWS-managed list's Id by
 * Name, so the managed lists must be present out of the box.</p>
 */
@QuarkusTest
class Route53ResolverIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/route53resolver/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listFirewallDomainLists_returnsAwsManagedLists() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.ListFirewallDomainLists")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FirewallDomainLists.Name", hasItems(
                    "AWSManagedDomainsAggregateThreatList",
                    "AWSManagedDomainsMalwareDomainList",
                    "AWSManagedDomainsBotnetCommandandControl"))
            .body("FirewallDomainLists[0].Id", startsWith("rslvr-fdl-"))
            .body("FirewallDomainLists[0].ManagedOwnerName", equalTo("Route 53 Resolver DNS Firewall"));
    }

    @Test
    void listFirewallDomainLists_idsAreStableAcrossCalls() {
        String firstId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.ListFirewallDomainLists")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("FirewallDomainLists[0].Id");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.ListFirewallDomainLists")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FirewallDomainLists[0].Id", equalTo(firstId));
    }

    @Test
    void unknownOperation_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.DoesNotExist")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400);
    }
}
