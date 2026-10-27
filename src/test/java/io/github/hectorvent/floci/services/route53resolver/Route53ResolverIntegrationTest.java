package io.github.hectorvent.floci.services.route53resolver;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Test
    void createResolverEndpoint_acceptsTheAwsIpAddressesMember() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.CreateResolverEndpoint")
            .header("Authorization", AUTH_HEADER)
            .body(endpointBody("IpAddresses", "aws-member-1", 2))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.Id", startsWith("rslvr-in-"))
            .body("ResolverEndpoint.Direction", equalTo("INBOUND"))
            .body("ResolverEndpoint.IpAddressCount", equalTo(2));
    }

    @Test
    void createResolverEndpoint_stillAcceptsTheLegacyMember() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.CreateResolverEndpoint")
            .header("Authorization", AUTH_HEADER)
            .body(endpointBody("IpAddressRequests", "legacy-member-1", 2))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.IpAddressCount", equalTo(2));
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
            .body(endpointBody("IpAddressRequests", "replay-across-names", 2))
        .when().post("/").then().statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.CreateResolverEndpoint")
            .header("Authorization", AUTH_HEADER)
            .body(endpointBody("IpAddresses", "replay-across-names", 2))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.IpAddressCount", equalTo(2));
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

    // ── ListResolverEndpointIpAddresses ─────────────────────────────────────────
    //
    // The Terraform provider calls this right after CreateResolverEndpoint to read the
    // endpoint's ip_address set, so an unimplemented operation fails every endpoint create.

    private static String listIpAddresses(String endpointId) {
        return given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.ListResolverEndpointIpAddresses")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResolverEndpointId\":\"" + endpointId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
    }

    @Test
    void listResolverEndpointIpAddresses_returnsTheAddressesTheEndpointWasCreatedWith() {
        String endpointId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.CreateResolverEndpoint")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Name\":\"ep-ips\",\"Direction\":\"OUTBOUND\",\"CreatorRequestId\":\"list-ips-1\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],"
                + "\"IpAddresses\":[{\"SubnetId\":\"subnet-aaa\",\"Ip\":\"10.0.1.10\"},"
                + "{\"SubnetId\":\"subnet-bbb\"}]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("ResolverEndpoint.Id");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.ListResolverEndpointIpAddresses")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResolverEndpointId\":\"" + endpointId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("IpAddresses.size()", equalTo(2))
            .body("IpAddresses.SubnetId", containsInAnyOrder("subnet-aaa", "subnet-bbb"))
            .body("IpAddresses.find { it.SubnetId == 'subnet-aaa' }.Ip", equalTo("10.0.1.10"))
            .body("IpAddresses.find { it.SubnetId == 'subnet-bbb' }.Ip", nullValue())
            .body("IpAddresses.Status", everyItem(equalTo("ATTACHED")))
            .body("IpAddresses.IpId", everyItem(startsWith("rni-")))
            .body("IpAddresses.CreationTime", everyItem(notNullValue()));

        // IpId is the provider's handle on each address, so it must not change between reads.
        assertEquals(listIpAddresses(endpointId), listIpAddresses(endpointId));
    }

    @Test
    void listResolverEndpointIpAddresses_repeatedEntriesGetDistinctStableIds() {
        // Two addresses in one subnet, both left for AWS to pick, is a valid request: the
        // model's IpAddressesRequest is a plain list with no uniqueness constraint. Each is a
        // separate address, so each needs its own IpId.
        String endpointId = given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.CreateResolverEndpoint")
            .header("Authorization", AUTH_HEADER)
            .body("{\"Name\":\"ep-dup-ips\",\"Direction\":\"INBOUND\",\"CreatorRequestId\":\"list-ips-dup\","
                + "\"SecurityGroupIds\":[\"sg-abc123\"],"
                + "\"IpAddresses\":[{\"SubnetId\":\"subnet-aaa\"},{\"SubnetId\":\"subnet-aaa\"}]}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("ResolverEndpoint.Id");

        String first = listIpAddresses(endpointId);
        List<String> ids = JsonPath.from(first).getList("IpAddresses.IpId");
        assertEquals(2, ids.size());
        assertEquals(2, new HashSet<>(ids).size(), "repeated entries share an IpId: " + ids);
        assertEquals(first, listIpAddresses(endpointId));
    }

    @Test
    void listResolverEndpointIpAddresses_unknownEndpoint_returnsResourceNotFound() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.ListResolverEndpointIpAddresses")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResolverEndpointId\":\"rslvr-in-doesnotexist\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ── CreateResolverEndpoint: IpAddresses is min 2 / max 20 ──────────────────
    //
    // The request shape models IpAddresses as min 2 / max 20, so a resolver endpoint
    // needs at least two IP addresses and rejects more than twenty. The emulator used
    // to accept a single address and create the endpoint, which a real SDK would never
    // be allowed to do.

    @Test
    void createResolverEndpoint_singleAddress_returnsInvalidParameters() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.CreateResolverEndpoint")
            .header("Authorization", AUTH_HEADER)
            .body(endpointBody("IpAddresses", "single-address", 1))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"))
            .body("message", equalTo("IpAddresses must contain at least two IP addresses."));
    }

    @Test
    void createResolverEndpoint_twentyOneAddresses_returnsInvalidParameters() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.CreateResolverEndpoint")
            .header("Authorization", AUTH_HEADER)
            .body(endpointBody("IpAddresses", "too-many-addresses", 21))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"))
            .body("message", equalTo("IpAddresses must contain at most twenty IP addresses."));
    }

    @Test
    void createResolverEndpoint_twoAddresses_succeeds() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.CreateResolverEndpoint")
            .header("Authorization", AUTH_HEADER)
            .body(endpointBody("IpAddresses", "two-addresses", 2))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResolverEndpoint.IpAddressCount", equalTo(2));
    }

    @Test
    void createResolverEndpoint_singleAddress_createsNoEndpoint() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.CreateResolverEndpoint")
            .header("Authorization", AUTH_HEADER)
            .body(endpointBody("IpAddresses", "rejected-endpoint", 1))
        .when()
            .post("/")
        .then()
            .statusCode(400);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver.ListResolverEndpoints")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResolverEndpoints.findAll { it.Name == 'ep-rejected-endpoint' }.size()",
                    equalTo(0));
    }

    private static String endpointBody(String ipMember, String creatorRequestId, int ipCount) {
        StringBuilder addresses = new StringBuilder();
        for (int i = 0; i < ipCount; i++) {
            if (i > 0) {
                addresses.append(",");
            }
            addresses.append("{\"SubnetId\":\"subnet-").append(i)
                    .append("\",\"Ip\":\"10.0.0.").append(i + 1).append("\"}");
        }
        return "{\"Name\":\"ep-" + creatorRequestId + "\",\"Direction\":\"INBOUND\","
             + "\"CreatorRequestId\":\"" + creatorRequestId + "\","
             + "\"SecurityGroupIds\":[\"sg-abc123\"],"
             + "\"" + ipMember + "\":[" + addresses + "]}";
    }
}
