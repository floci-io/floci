package io.github.hectorvent.floci.services.ec2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Covers the cross-account and multi-target scenarios the same-account lifecycle test
 * (Ec2VpcPeeringConnectionIntegrationTest) cannot exercise on its own: only the actual accepter
 * account may accept a connection, a describe issued from an unrelated region must not see it,
 * ReplaceRoute must accept a peering connection as a target, and CreateRoute must reject a
 * peering connection combined with another target.
 *
 * <p>Uses 12-digit numeric access key IDs so they resolve directly to distinct account IDs
 * (see AccountIsolationIntegrationTest for the same pattern).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2VpcPeeringConnectionCrossAccountIntegrationTest {

    // 000000000000 is the emulator's configured default account — distinct from every account
    // below, so it stands in for "an unrelated account that happens to be the default".
    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String REQUESTER_ACCOUNT = "000000000101";
    private static final String ACCEPTER_ACCOUNT = "000000000102";
    private static final String OTHER_ACCOUNT = "000000000103";

    private static final String DEFAULT_ACCOUNT_AUTH =
            "AWS4-HMAC-SHA256 Credential=" + DEFAULT_ACCOUNT + "/20260215/us-east-1/ec2/aws4_request";
    private static final String REQUESTER_AUTH =
            "AWS4-HMAC-SHA256 Credential=" + REQUESTER_ACCOUNT + "/20260215/us-east-1/ec2/aws4_request";
    private static final String ACCEPTER_AUTH =
            "AWS4-HMAC-SHA256 Credential=" + ACCEPTER_ACCOUNT + "/20260215/us-east-1/ec2/aws4_request";
    private static final String OTHER_ACCOUNT_AUTH =
            "AWS4-HMAC-SHA256 Credential=" + OTHER_ACCOUNT + "/20260215/us-east-1/ec2/aws4_request";
    // Same requester account, different region — used to prove a describe (and, below, a
    // mutation) issued from a region unrelated to either side of the connection does not see it.
    private static final String REQUESTER_AUTH_OTHER_REGION =
            "AWS4-HMAC-SHA256 Credential=" + REQUESTER_ACCOUNT + "/20260215/us-west-2/ec2/aws4_request";

    private static String requesterVpcId;
    private static String pcxId;

    @Test
    @Order(1)
    void requesterCreatesAConnectionAddressedToTheAccepterAccount() {
        requesterVpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.40.0.0/16")
            .header("Authorization", REQUESTER_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        pcxId = given()
            .formParam("Action", "CreateVpcPeeringConnection")
            .formParam("VpcId", requesterVpcId)
            .formParam("PeerVpcId", "vpc-accepter00000000")
            .formParam("PeerOwnerId", ACCEPTER_ACCOUNT)
            .header("Authorization", REQUESTER_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateVpcPeeringConnectionResponse.vpcPeeringConnection.accepterVpcInfo.ownerId",
                    equalTo(ACCEPTER_ACCOUNT))
            .body("CreateVpcPeeringConnectionResponse.vpcPeeringConnection.status.code",
                    equalTo("pending-acceptance"))
            .extract().path("CreateVpcPeeringConnectionResponse.vpcPeeringConnection.vpcPeeringConnectionId");
    }

    /** The requester created the connection but is not the accepter and cannot activate it. */
    @Test
    @Order(2)
    void requesterCannotAcceptItsOwnConnection() {
        given()
            .formParam("Action", "AcceptVpcPeeringConnection")
            .formParam("VpcPeeringConnectionId", pcxId)
            .header("Authorization", REQUESTER_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidVpcPeeringConnectionID.NotFound"));
    }

    /** An unrelated third account cannot see or accept the connection either. */
    @Test
    @Order(3)
    void anUnrelatedAccountCannotSeeOrAcceptTheConnection() {
        given()
            .formParam("Action", "DescribeVpcPeeringConnections")
            .formParam("VpcPeeringConnectionId.1", pcxId)
            .header("Authorization", OTHER_ACCOUNT_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcPeeringConnectionsResponse.vpcPeeringConnectionSet", emptyOrNullString());

        given()
            .formParam("Action", "AcceptVpcPeeringConnection")
            .formParam("VpcPeeringConnectionId", pcxId)
            .header("Authorization", OTHER_ACCOUNT_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidVpcPeeringConnectionID.NotFound"));
    }

    /** A describe issued from a region that is neither side of the connection must not see it. */
    @Test
    @Order(4)
    void describeFromAnUnrelatedRegionDoesNotSeeTheConnection() {
        given()
            .formParam("Action", "DescribeVpcPeeringConnections")
            .formParam("VpcPeeringConnectionId.1", pcxId)
            .header("Authorization", REQUESTER_AUTH_OTHER_REGION)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcPeeringConnectionsResponse.vpcPeeringConnectionSet", emptyOrNullString());
    }

    /** The actual accepter account can see and accept it. */
    @Test
    @Order(5)
    void theAccepterAccountCanAcceptTheConnection() {
        given()
            .formParam("Action", "DescribeVpcPeeringConnections")
            .formParam("VpcPeeringConnectionId.1", pcxId)
            .header("Authorization", ACCEPTER_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcPeeringConnectionsResponse.vpcPeeringConnectionSet.item.vpcPeeringConnectionId",
                    equalTo(pcxId));

        given()
            .formParam("Action", "AcceptVpcPeeringConnection")
            .formParam("VpcPeeringConnectionId", pcxId)
            .header("Authorization", ACCEPTER_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AcceptVpcPeeringConnectionResponse.vpcPeeringConnection.status.code", equalTo("active"));
    }

    /**
     * CreateRoute must reject a route that names both a peering connection and another target
     * (here: GatewayId) — AWS accepts exactly one target per route.
     */
    @Test
    @Order(6)
    void createRouteRejectsAPeeringConnectionCombinedWithAnotherTarget() {
        String routeTableId = given()
            .formParam("Action", "CreateRouteTable")
            .formParam("VpcId", requesterVpcId)
            .header("Authorization", REQUESTER_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateRouteTableResponse.routeTable.routeTableId");

        given()
            .formParam("Action", "CreateRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", "10.50.0.0/16")
            .formParam("VpcPeeringConnectionId", pcxId)
            .formParam("GatewayId", "igw-conflicting00000")
            .header("Authorization", REQUESTER_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"));
    }

    /**
     * ReplaceRoute must accept a peering connection as the replacement target — the peering
     * examples repoint a route at the connection after it goes active, same as CreateRoute.
     */
    @Test
    @Order(7)
    void replaceRouteAcceptsThePeeringConnectionAsTheTarget() {
        String routeTableId = given()
            .formParam("Action", "CreateRouteTable")
            .formParam("VpcId", requesterVpcId)
            .header("Authorization", REQUESTER_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateRouteTableResponse.routeTable.routeTableId");

        given()
            .formParam("Action", "CreateRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", "10.60.0.0/16")
            .formParam("GatewayId", "igw-replaceable00000")
            .header("Authorization", REQUESTER_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "ReplaceRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", "10.60.0.0/16")
            .formParam("VpcPeeringConnectionId", pcxId)
            .header("Authorization", REQUESTER_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ReplaceRouteResponse.return", equalTo("true"));

        given()
            .formParam("Action", "DescribeRouteTables")
            .formParam("RouteTableId.1", routeTableId)
            .header("Authorization", REQUESTER_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeRouteTablesResponse.routeTableSet.item.routeSet.item"
                    + ".find { it.destinationCidrBlock == '10.60.0.0/16' }.vpcPeeringConnectionId",
                    equalTo(pcxId));
    }

    /**
     * The requester account (not the emulator's configured default account) must be able to
     * describe and modify the connection it created. Recording requesterVpcInfo.ownerId as the
     * fixed default account, rather than the account that actually made the request, would deny
     * the real requester while handing an unrelated account participant-level access.
     */
    @Test
    @Order(8)
    void theRequesterAccountCanManageItsOwnConnectionButTheDefaultAccountCannot() {
        given()
            .formParam("Action", "DescribeVpcPeeringConnections")
            .formParam("VpcPeeringConnectionId.1", pcxId)
            .header("Authorization", REQUESTER_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcPeeringConnectionsResponse.vpcPeeringConnectionSet.item.vpcPeeringConnectionId",
                    equalTo(pcxId));

        given()
            .formParam("Action", "DescribeVpcPeeringConnections")
            .formParam("VpcPeeringConnectionId.1", pcxId)
            .header("Authorization", DEFAULT_ACCOUNT_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcPeeringConnectionsResponse.vpcPeeringConnectionSet", emptyOrNullString());

        given()
            .formParam("Action", "ModifyVpcPeeringConnectionOptions")
            .formParam("VpcPeeringConnectionId", pcxId)
            .formParam("RequesterPeeringConnectionOptions.AllowDnsResolutionFromRemoteVpc", "true")
            .header("Authorization", REQUESTER_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyVpcPeeringConnectionOptionsResponse.requesterPeeringConnectionOptions"
                    + ".allowDnsResolutionFromRemoteVpc", equalTo("true"));

        given()
            .formParam("Action", "ModifyVpcPeeringConnectionOptions")
            .formParam("VpcPeeringConnectionId", pcxId)
            .formParam("RequesterPeeringConnectionOptions.AllowDnsResolutionFromRemoteVpc", "false")
            .header("Authorization", DEFAULT_ACCOUNT_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidVpcPeeringConnectionID.NotFound"));
    }

    /**
     * Accept, modify and delete must enforce the same regional-visibility invariant Describe
     * does: an endpoint in a region belonging to neither side must not be able to act on the
     * connection, even when the caller's account is a legitimate participant.
     */
    @Test
    @Order(9)
    void mutationsFromAnUnrelatedRegionAreRejected() {
        given()
            .formParam("Action", "ModifyVpcPeeringConnectionOptions")
            .formParam("VpcPeeringConnectionId", pcxId)
            .formParam("RequesterPeeringConnectionOptions.AllowDnsResolutionFromRemoteVpc", "true")
            .header("Authorization", REQUESTER_AUTH_OTHER_REGION)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidVpcPeeringConnectionID.NotFound"));

        given()
            .formParam("Action", "DeleteVpcPeeringConnection")
            .formParam("VpcPeeringConnectionId", pcxId)
            .header("Authorization", REQUESTER_AUTH_OTHER_REGION)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidVpcPeeringConnectionID.NotFound"));

        // Still there, and still manageable from the correct region.
        given()
            .formParam("Action", "DeleteVpcPeeringConnection")
            .formParam("VpcPeeringConnectionId", pcxId)
            .header("Authorization", REQUESTER_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteVpcPeeringConnectionResponse.return", equalTo("true"));
    }
}
