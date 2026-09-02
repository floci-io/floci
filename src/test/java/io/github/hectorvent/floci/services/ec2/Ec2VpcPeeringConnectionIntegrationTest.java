package io.github.hectorvent.floci.services.ec2;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.junit.QuarkusTest;
import static io.restassured.RestAssured.given;

/**
 * CreateVpcPeeringConnection was entirely unimplemented (floci-k41), blocking all three VPC-peering
 * examples in terraform-aws-vpc (vpc-peering, vpc-peering-cross-accounts, vpc-peering-external).
 * These tests walk the lifecycle those examples actually exercise: create -> accept -> describe ->
 * route via the peering connection -> options -> delete.
 *
 * <p>Real AWS never auto-accepts a connection (same-account or not); every connection starts
 * "pending-acceptance" until an explicit AcceptVpcPeeringConnection. Terraform's own `auto_accept`
 * convenience is implemented by the *provider* re-issuing that call, not by the API — so that is
 * what these tests pin at the API layer.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2VpcPeeringConnectionIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static String requesterVpcId;
    private static String accepterVpcId;
    private static String pcxId;
    private static String routeTableId;

    @Test
    @Order(1)
    void createVpcPeeringConnectionStartsPendingAcceptance() {
        requesterVpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.20.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        accepterVpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.30.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        pcxId = given()
            .formParam("Action", "CreateVpcPeeringConnection")
            .formParam("VpcId", requesterVpcId)
            .formParam("PeerVpcId", accepterVpcId)
            .formParam("TagSpecification.1.ResourceType", "vpc-peering-connection")
            .formParam("TagSpecification.1.Tag.1.Key", "Name")
            .formParam("TagSpecification.1.Tag.1.Value", "pcx-example")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateVpcPeeringConnectionResponse.vpcPeeringConnection.requesterVpcInfo.vpcId",
                    equalTo(requesterVpcId))
            .body("CreateVpcPeeringConnectionResponse.vpcPeeringConnection.requesterVpcInfo.cidrBlock",
                    equalTo("10.20.0.0/16"))
            .body("CreateVpcPeeringConnectionResponse.vpcPeeringConnection.accepterVpcInfo.vpcId",
                    equalTo(accepterVpcId))
            .body("CreateVpcPeeringConnectionResponse.vpcPeeringConnection.accepterVpcInfo.cidrBlock",
                    equalTo("10.30.0.0/16"))
            .body("CreateVpcPeeringConnectionResponse.vpcPeeringConnection.status.code",
                    equalTo("pending-acceptance"))
            .body("CreateVpcPeeringConnectionResponse.vpcPeeringConnection.tagSet.item.value",
                    equalTo("pcx-example"))
            .extract().path("CreateVpcPeeringConnectionResponse.vpcPeeringConnection.vpcPeeringConnectionId");
    }

    @Test
    @Order(2)
    void describeReflectsThePendingConnection() {
        given()
            .formParam("Action", "DescribeVpcPeeringConnections")
            .formParam("VpcPeeringConnectionId.1", pcxId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcPeeringConnectionsResponse.vpcPeeringConnectionSet.item.vpcPeeringConnectionId",
                    equalTo(pcxId))
            .body("DescribeVpcPeeringConnectionsResponse.vpcPeeringConnectionSet.item.status.code",
                    equalTo("pending-acceptance"));
    }

    @Test
    @Order(3)
    void acceptTransitionsTheConnectionToActive() {
        given()
            .formParam("Action", "AcceptVpcPeeringConnection")
            .formParam("VpcPeeringConnectionId", pcxId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AcceptVpcPeeringConnectionResponse.vpcPeeringConnection.status.code", equalTo("active"));

        given()
            .formParam("Action", "DescribeVpcPeeringConnections")
            .formParam("VpcPeeringConnectionId.1", pcxId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcPeeringConnectionsResponse.vpcPeeringConnectionSet.item.status.code",
                    equalTo("active"));
    }

    /** Accepting an already-active connection is not a valid state transition. */
    @Test
    @Order(4)
    void acceptingAnAlreadyActiveConnectionIsRejected() {
        given()
            .formParam("Action", "AcceptVpcPeeringConnection")
            .formParam("VpcPeeringConnectionId", pcxId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidStateTransition"));
    }

    /**
     * aws_vpc_peering_connection_options: both modules/vpc-peering and
     * modules/vpc-peering-cross-accounts-accepter set allow_remote_vpc_dns_resolution on one or
     * both sides.
     */
    @Test
    @Order(5)
    void modifyPeeringConnectionOptionsSetsDnsResolutionPerSide() {
        given()
            .formParam("Action", "ModifyVpcPeeringConnectionOptions")
            .formParam("VpcPeeringConnectionId", pcxId)
            .formParam("AccepterPeeringConnectionOptions.AllowDnsResolutionFromRemoteVpc", "true")
            .formParam("RequesterPeeringConnectionOptions.AllowDnsResolutionFromRemoteVpc", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyVpcPeeringConnectionOptionsResponse.accepterPeeringConnectionOptions"
                    + ".allowDnsResolutionFromRemoteVpc", equalTo("true"))
            .body("ModifyVpcPeeringConnectionOptionsResponse.requesterPeeringConnectionOptions"
                    + ".allowDnsResolutionFromRemoteVpc", equalTo("true"));

        // Terraform's aws_vpc_peering_connection_options resource reads this back via Describe on
        // every plan, not by re-issuing Modify — it must round-trip here or the provider sees
        // permanent drift on a value it just set.
        given()
            .formParam("Action", "DescribeVpcPeeringConnections")
            .formParam("VpcPeeringConnectionId.1", pcxId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcPeeringConnectionsResponse.vpcPeeringConnectionSet.item"
                    + ".accepterVpcInfo.peeringOptions.allowDnsResolutionFromRemoteVpc", equalTo("true"))
            .body("DescribeVpcPeeringConnectionsResponse.vpcPeeringConnectionSet.item"
                    + ".requesterVpcInfo.peeringOptions.allowDnsResolutionFromRemoteVpc", equalTo("true"));
    }

    /**
     * modules/vpc-peering routes traffic to the peer over the connection via aws_route with
     * vpc_peering_connection_id as the target — this must round-trip on DescribeRouteTables or the
     * provider sees permanent drift on the route resource it just created.
     */
    @Test
    @Order(6)
    void createRouteWithThePeeringConnectionAsTargetRoundTrips() {
        routeTableId = given()
            .formParam("Action", "CreateRouteTable")
            .formParam("VpcId", requesterVpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateRouteTableResponse.routeTable.routeTableId");

        given()
            .formParam("Action", "CreateRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", "10.30.0.0/16")
            .formParam("VpcPeeringConnectionId", pcxId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateRouteResponse.return", equalTo("true"));

        given()
            .formParam("Action", "DescribeRouteTables")
            .formParam("RouteTableId.1", routeTableId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeRouteTablesResponse.routeTableSet.item.routeSet.item"
                    + ".find { it.destinationCidrBlock == '10.30.0.0/16' }.vpcPeeringConnectionId",
                    equalTo(pcxId));
    }

    @Test
    @Order(7)
    void deleteRemovesTheConnection() {
        given()
            .formParam("Action", "DeleteVpcPeeringConnection")
            .formParam("VpcPeeringConnectionId", pcxId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteVpcPeeringConnectionResponse.return", equalTo("true"));

        given()
            .formParam("Action", "DescribeVpcPeeringConnections")
            .formParam("VpcPeeringConnectionId.1", pcxId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcPeeringConnectionsResponse.vpcPeeringConnectionSet", emptyOrNullString());
    }

    @Test
    @Order(8)
    void deletingAnUnknownConnectionIsRejected() {
        given()
            .formParam("Action", "DeleteVpcPeeringConnection")
            .formParam("VpcPeeringConnectionId", "pcx-0000000000000dead")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidVpcPeeringConnectionID.NotFound"));
    }

    /**
     * vpc-peering-cross-accounts and vpc-peering-external both peer against a VPC id this account
     * never seeded (a different account/region's VPC). The accepter side must still be reported —
     * without a fabricated CIDR — rather than the request failing outright.
     */
    @Test
    @Order(9)
    void peeringToAnUnknownAccepterVpcSucceedsWithNoAccepterCidr() {
        String vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.2.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        String response = given()
            .formParam("Action", "CreateVpcPeeringConnection")
            .formParam("VpcId", vpcId)
            .formParam("PeerVpcId", "vpc-external0000000")
            .formParam("PeerOwnerId", "999999999999")
            .formParam("PeerRegion", "us-west-2")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateVpcPeeringConnectionResponse.vpcPeeringConnection.accepterVpcInfo.vpcId",
                    equalTo("vpc-external0000000"))
            .body("CreateVpcPeeringConnectionResponse.vpcPeeringConnection.accepterVpcInfo.ownerId",
                    equalTo("999999999999"))
            .body("CreateVpcPeeringConnectionResponse.vpcPeeringConnection.accepterVpcInfo.region",
                    equalTo("us-west-2"))
            .extract().asString();

        // The requester side (a VPC this account did seed) does carry a cidrBlock; only the
        // accepter side — a VPC never seeded here — must not have one fabricated.
        String accepterInfo = response.substring(response.indexOf("<accepterVpcInfo>"));
        assertThat(accepterInfo, not(containsString("<cidrBlock>")));
    }

    @Test
    @Order(10)
    void createOnAnUnknownVpcIsRejected() {
        given()
            .formParam("Action", "CreateVpcPeeringConnection")
            .formParam("VpcId", "vpc-0000000000000dead")
            .formParam("PeerVpcId", "vpc-0000000000000beef")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidVpcID.NotFound"));
    }
}
