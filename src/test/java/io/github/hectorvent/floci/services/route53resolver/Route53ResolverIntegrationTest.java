package io.github.hectorvent.floci.services.route53resolver;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static io.github.hectorvent.floci.testing.Ec2Requests.ec2;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void createResolverEndpoint_backsEachAddressWithAHeldEc2Interface() {
        String vpc = ec2Ok("CreateVpc", "CidrBlock", "10.77.0.0/16").extract().path("CreateVpcResponse.vpc.vpcId");
        String subA = ec2Ok("CreateSubnet", "VpcId", vpc, "CidrBlock", "10.77.1.0/24", "AvailabilityZone", "us-east-1a")
                .extract().path("CreateSubnetResponse.subnet.subnetId");
        String subB = ec2Ok("CreateSubnet", "VpcId", vpc, "CidrBlock", "10.77.2.0/28", "AvailabilityZone", "us-east-1b")
                .extract().path("CreateSubnetResponse.subnet.subnetId");
        String sg = securityGroup(vpc, "ep-iface");
        // "Ip": null is a request that leaves the address to AWS, the same as one with no Ip.
        String id = createEndpoint("{\"Name\":\"ep-iface\",\"Direction\":\"INBOUND\",\"SecurityGroupIds\":[\"" + sg
                + "\"],\"IpAddresses\":[{\"SubnetId\":\"" + subA + "\"},{\"SubnetId\":\"" + subA
                + "\",\"Ip\":\"10.77.1.40\"},{\"SubnetId\":\"" + subB + "\",\"Ip\":null}]}");

        given().contentType(CONTENT_TYPE).header("X-Amz-Target", "Route53Resolver.GetResolverEndpoint")
                .header("Authorization", AUTH_HEADER).body("{\"ResolverEndpointId\":\"" + id + "\"}")
                .when().post("/").then().statusCode(200).body("ResolverEndpoint.HostVPCId", equalTo(vpc));
        JsonPath listed = JsonPath.from(listIpAddresses(id));
        for (String subnet : List.of(subA, subB)) {
            XmlPath enis = describeInterfaces(subnet);
            List<String> eniIps = new ArrayList<>(enis.getList(ENI_ITEMS + ".privateIpAddress", String.class));
            List<String> listedIps = new ArrayList<>(
                    listed.getList("IpAddresses.findAll { it.SubnetId == '" + subnet + "' }.Ip", String.class));
            Collections.sort(eniIps);
            Collections.sort(listedIps);
            assertEquals(subnet.equals(subA) ? 2 : 1, eniIps.size(), subnet);
            assertEquals(eniIps, listedIps, "ListResolverEndpointIpAddresses must report the interfaces' addresses");
            assertEquals(List.of(sg), enis.getList(ENI_ITEMS + ".groupSet.item.groupId", String.class)
                    .stream().distinct().toList());
            assertEquals(List.of("in-use"), enis.getList(ENI_ITEMS + ".status", String.class)
                    .stream().distinct().toList());
        }
        assertTrue(listed.getList("IpAddresses.Ip").contains("10.77.1.40"));

        // The endpoint holds its interfaces, so the account cannot delete one from under it.
        String held = describeInterfaces(subA).getString(ENI_ITEMS + "[0].networkInterfaceId");
        ec2("DeleteNetworkInterface", "AKID", "us-east-1")
                .formParam("NetworkInterfaceId", held)
                .when().post("/").then().statusCode(400);
    }

    @Test
    void createResolverEndpoint_explicitIpAnotherEndpointHoldsIsRefused_deleteFreesIt() {
        String vpc = ec2Ok("CreateVpc", "CidrBlock", "10.78.0.0/16").extract().path("CreateVpcResponse.vpc.vpcId");
        String sub = ec2Ok("CreateSubnet", "VpcId", vpc, "CidrBlock", "10.78.1.0/24", "AvailabilityZone", "us-east-1a")
                .extract().path("CreateSubnetResponse.subnet.subnetId");
        String sg = securityGroup(vpc, "ep-taken");
        String first = createEndpoint("{\"Name\":\"ep-taken-1\",\"Direction\":\"INBOUND\",\"SecurityGroupIds\":[\""
                + sg + "\"],\"IpAddresses\":[{\"SubnetId\":\"" + sub + "\",\"Ip\":\"10.78.1.30\"},{\"SubnetId\":\""
                + sub + "\"}]}");
        // .20 is free and is made first, so the refusal of .30 has an interface to roll back.
        String second = "{\"Name\":\"ep-taken-2\",\"Direction\":\"INBOUND\",\"SecurityGroupIds\":[\"" + sg
                + "\"],\"IpAddresses\":[{\"SubnetId\":\"" + sub + "\",\"Ip\":\"10.78.1.30\"},{\"SubnetId\":\""
                + sub + "\",\"Ip\":\"10.78.1.20\"}]}";

        given().contentType(CONTENT_TYPE).header("X-Amz-Target", "Route53Resolver.CreateResolverEndpoint")
                .header("Authorization", AUTH_HEADER).body(second)
                .when().post("/").then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        assertEquals(2, describeInterfaces(sub).getList(ENI_ITEMS + ".networkInterfaceId").size(),
                "a refused endpoint must leave no interface behind");
        given().contentType(CONTENT_TYPE).header("X-Amz-Target", "Route53Resolver.ListResolverEndpoints")
                .header("Authorization", AUTH_HEADER).body("{}")
                .when().post("/").then().statusCode(200)
                .body("ResolverEndpoints.findAll { it.Name == 'ep-taken-2' }.size()", equalTo(0));

        given().contentType(CONTENT_TYPE).header("X-Amz-Target", "Route53Resolver.DeleteResolverEndpoint")
                .header("Authorization", AUTH_HEADER).body("{\"ResolverEndpointId\":\"" + first + "\"}")
                .when().post("/").then().statusCode(200);
        assertTrue(describeInterfaces(sub).getList(ENI_ITEMS + ".networkInterfaceId").isEmpty(),
                "deleting the endpoint deletes its interfaces");
        String again = createEndpoint(second);
        assertEquals(List.of("10.78.1.20", "10.78.1.30"), JsonPath.from(listIpAddresses(again))
                .getList("IpAddresses.Ip", String.class).stream().sorted().toList());
    }

    @Test
    void createResolverEndpoint_anAutomaticAddressDoesNotTakeOneRequestedLaterInTheList() {
        String vpc = ec2Ok("CreateVpc", "CidrBlock", "10.81.0.0/16").extract().path("CreateVpcResponse.vpc.vpcId");
        String sub = ec2Ok("CreateSubnet", "VpcId", vpc, "CidrBlock", "10.81.1.0/24", "AvailabilityZone", "us-east-1a")
                .extract().path("CreateSubnetResponse.subnet.subnetId");
        String sg = securityGroup(vpc, "ep-order");
        // A fresh subnet's first automatic address, which a later entry asks for explicitly.
        String probe = ec2Ok("CreateNetworkInterface", "SubnetId", sub)
                .extract().path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");
        String first = describeInterfaces(sub).getString(ENI_ITEMS + ".privateIpAddress");
        ec2Ok("DeleteNetworkInterface", "NetworkInterfaceId", probe);
        String next = first.substring(0, first.lastIndexOf('.') + 1)
                + (Integer.parseInt(first.substring(first.lastIndexOf('.') + 1)) + 1);

        for (String auto : List.of("{\"SubnetId\":\"" + sub + "\"}", "{\"SubnetId\":\"" + sub + "\",\"Ip\":null}")) {
            String id = createEndpoint("{\"Name\":\"ep-order\",\"Direction\":\"INBOUND\",\"SecurityGroupIds\":[\""
                    + sg + "\"],\"IpAddresses\":[" + auto + ",{\"SubnetId\":\"" + sub + "\",\"Ip\":\"" + next + "\"}]}");
            assertTrue(JsonPath.from(listIpAddresses(id)).getList("IpAddresses.Ip").contains(next), auto);
            given().contentType(CONTENT_TYPE).header("X-Amz-Target", "Route53Resolver.DeleteResolverEndpoint")
                    .header("Authorization", AUTH_HEADER).body("{\"ResolverEndpointId\":\"" + id + "\"}")
                    .when().post("/").then().statusCode(200);
        }
    }

    @Test
    void createResolverEndpoint_exhaustedSubnetIsRejectedAndStoresNothing() {
        String vpc = ec2Ok("CreateVpc", "CidrBlock", "10.80.0.0/16").extract().path("CreateVpcResponse.vpc.vpcId");
        // /28 = 16 addresses, minus 4 reserved at the bottom and 1 at the top = 11 assignable.
        String sub = ec2Ok("CreateSubnet", "VpcId", vpc, "CidrBlock", "10.80.1.0/28", "AvailabilityZone", "us-east-1a")
                .extract().path("CreateSubnetResponse.subnet.subnetId");
        String sg = securityGroup(vpc, "ep-exhausted");
        String requests = String.join(",", Collections.nCopies(12, "{\"SubnetId\":\"" + sub + "\"}"));
        given().contentType(CONTENT_TYPE).header("X-Amz-Target", "Route53Resolver.CreateResolverEndpoint")
                .header("Authorization", AUTH_HEADER)
                .body("{\"Name\":\"ep-exhausted\",\"Direction\":\"INBOUND\",\"SecurityGroupIds\":[\"" + sg + "\"],"
                        + "\"IpAddresses\":[" + requests + "]}")
                .when().post("/").then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        assertTrue(describeInterfaces(sub).getList(ENI_ITEMS + ".networkInterfaceId").isEmpty());
        given().contentType(CONTENT_TYPE).header("X-Amz-Target", "Route53Resolver.ListResolverEndpoints")
                .header("Authorization", AUTH_HEADER).body("{}")
                .when().post("/").then().statusCode(200)
                .body("ResolverEndpoints.findAll { it.Name == 'ep-exhausted' }.size()", equalTo(0));
    }

    private static final String ENI_ITEMS = "DescribeNetworkInterfacesResponse.networkInterfaceSet.item";

    private static String securityGroup(String vpc, String name) {
        return ec2Ok("CreateSecurityGroup", "GroupName", name, "GroupDescription", name, "VpcId", vpc)
                .extract().path("CreateSecurityGroupResponse.groupId");
    }

    private static XmlPath describeInterfaces(String subnet) {
        return ec2Ok("DescribeNetworkInterfaces", "Filter.1.Name", "subnet-id", "Filter.1.Value.1", subnet)
                .extract().xmlPath();
    }

    private static String createEndpoint(String body) {
        return given().contentType(CONTENT_TYPE).header("X-Amz-Target", "Route53Resolver.CreateResolverEndpoint")
                .header("Authorization", AUTH_HEADER).body(body)
                .when().post("/").then().statusCode(200).extract().path("ResolverEndpoint.Id");
    }

    /** An EC2 Query call through {@link io.github.hectorvent.floci.testing.Ec2Requests}, expecting 200. */
    private static ValidatableResponse ec2Ok(String action, String... params) {
        RequestSpecification spec = ec2(action, "AKID", "us-east-1");
        for (int i = 0; i < params.length; i += 2) {
            spec = spec.formParam(params[i], params[i + 1]);
        }
        return spec.post("/").then().statusCode(200);
    }

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
