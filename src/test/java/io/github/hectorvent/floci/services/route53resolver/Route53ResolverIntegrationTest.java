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

    // ── CreateResolverEndpoint: the request member is IpAddresses ──────────────
    //
    // AWS names it IpAddresses; its list shape is named IpAddressRequest, which is
    // where the emulator's earlier wire name came from. Every SDK, the CLI and the
    // Terraform provider send IpAddresses, and the CLI will not send the old name at
    // all, so reading only the old one made the operation dispatch and stay
    // uncallable. These cover both spellings so the alias cannot be dropped silently.

    private static String createEndpointBody(String ipMember, String creatorRequestId) {
        return "{\"Name\":\"ep\",\"Direction\":\"INBOUND\","
             + "\"CreatorRequestId\":\"" + creatorRequestId + "\","
             + "\"SecurityGroupIds\":[\"sg-abc123\"],"
             + "\"" + ipMember + "\":[{\"SubnetId\":\"subnet-abc\",\"Ip\":\"10.0.0.10\"}]}";
    }

    @Test
    void createResolverEndpoint_acceptsTheAwsIpAddressesMember() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.CreateResolverEndpoint")
            .header("Authorization", AUTH_HEADER)
            .body(createEndpointBody("IpAddresses", "aws-member-1"))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.Id", startsWith("rslvr-in-"))
            .body("ResolverEndpoint.Direction", equalTo("INBOUND"))
            .body("ResolverEndpoint.IpAddressCount", equalTo(1));
    }

    @Test
    void createResolverEndpoint_stillAcceptsTheLegacyMember() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.CreateResolverEndpoint")
            .header("Authorization", AUTH_HEADER)
            .body(createEndpointBody("IpAddressRequests", "legacy-member-1"))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.IpAddressCount", equalTo(1));
    }

    @Test
    void createResolverEndpoint_replayMatchesAcrossTheTwoSpellings() {
        // A caller that created an endpoint with the old name and retries with the AWS
        // one is sending the same request. The idempotency check compares contents, so
        // it must not read the rename as a conflict.
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.CreateResolverEndpoint")
            .header("Authorization", AUTH_HEADER)
            .body(createEndpointBody("IpAddressRequests", "replay-across-names"))
        .when().post("/").then().statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.CreateResolverEndpoint")
            .header("Authorization", AUTH_HEADER)
            .body(createEndpointBody("IpAddresses", "replay-across-names"))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.IpAddressCount", equalTo(1));
    }

    @Test
    void createResolverEndpoint_withoutAddressesNamesTheAwsMember() {
        // The message is the only hint a caller gets about which member to send, so it
        // must name the one AWS documents rather than the emulator's internal spelling.
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.CreateResolverEndpoint")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Name\":\"ep\",\"Direction\":\"INBOUND\","
                + "\"CreatorRequestId\":\"no-addresses\",\"SecurityGroupIds\":[\"sg-abc123\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("message", equalTo("IpAddresses is required"));
    }
}
