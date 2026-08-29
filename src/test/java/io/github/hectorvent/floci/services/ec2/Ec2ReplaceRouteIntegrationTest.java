package io.github.hectorvent.floci.services.ec2;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.junit.QuarkusTest;
import static io.restassured.RestAssured.given;

/**
 * ReplaceRoute swaps the target of an existing route. AWS takes exactly one target per call, so
 * the target the request does not name is cleared rather than carried over — these tests pin that,
 * because a merge implementation would leave a route holding two targets at once.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2ReplaceRouteIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static final String DEFAULT_ROUTE = "0.0.0.0/0";
    private static final String UNROUTED_CIDR = "198.51.100.0/24";
    private static final String INTERNET_GATEWAY = "igw-0replace0route0test";
    private static final String NAT_GATEWAY = "nat-0replace0route0test";
    private static final String DEFAULT_ROUTE_NODE =
            "DescribeRouteTablesResponse.routeTableSet.item.routeSet.item"
                    + ".find { it.destinationCidrBlock == '0.0.0.0/0' }";
    private static final String ROUTE_SET =
            "DescribeRouteTablesResponse.routeTableSet.item.routeSet.item";

    private static String routeTableId;

    @Test
    @Order(1)
    void createRouteTableWithAnInternetGatewayRoute() {
        String vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "203.0.113.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        routeTableId = given()
            .formParam("Action", "CreateRouteTable")
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateRouteTableResponse.routeTable.routeTableId");

        given()
            .formParam("Action", "CreateRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", DEFAULT_ROUTE)
            .formParam("GatewayId", INTERNET_GATEWAY)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void replaceRouteSwapsTheGatewayForANatGatewayAndClearsTheOldTarget() {
        given()
            .formParam("Action", "ReplaceRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", DEFAULT_ROUTE)
            .formParam("NatGatewayId", NAT_GATEWAY)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ReplaceRouteResponse.return", equalTo("true"));

        // The table also holds the local route CreateRouteTable inserts, so each assertion selects
        // the route it means rather than the first item in the set.
        String replaced = ROUTE_SET + ".find { it.destinationCidrBlock == '" + DEFAULT_ROUTE + "' }";
        String local = ROUTE_SET + ".find { it.gatewayId == 'local' }";

        String table = given()
            .formParam("Action", "DescribeRouteTables")
            .formParam("RouteTableId.1", routeTableId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(replaced + ".natGatewayId", equalTo(NAT_GATEWAY))
            // The route keeps the origin it was created with — ReplaceRoute is not an origin value.
            .body(replaced + ".origin", equalTo("CreateRoute"))
            // ...and only the addressed route moved.
            .body(local + ".origin", equalTo("CreateRouteTable"))
            .extract().asString();

        // The whole point of replace-not-merge: the internet gateway is gone from the table, not
        // left sitting beside the NAT gateway on the same route.
        assertThat(table, not(containsString(INTERNET_GATEWAY)));
    }

    @Test
    @Order(3)
    void replaceRouteOnAnUnknownDestinationIsRejected() {
        given()
            .formParam("Action", "ReplaceRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", UNROUTED_CIDR)
            .formParam("GatewayId", INTERNET_GATEWAY)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidRoute.NotFound"));
    }

    /**
     * A target floci cannot model (transit gateway, network interface, peering connection, ...)
     * arrives as neither GatewayId nor NatGatewayId. Reporting success while quietly clearing the
     * route would be worse than the UnsupportedOperation callers got before this action existed.
     */
    @Test
    @Order(5)
    void replaceRouteWithNoSupportedTargetIsRejectedAndLeavesTheRouteAlone() {
        given()
            .formParam("Action", "ReplaceRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", DEFAULT_ROUTE)
            .formParam("TransitGatewayId", "tgw-0replace0route0test")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("UnsupportedOperation"));

        given()
            .formParam("Action", "DescribeRouteTables")
            .formParam("RouteTableId.1", routeTableId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(ROUTE_SET + ".find { it.destinationCidrBlock == '" + DEFAULT_ROUTE + "' }.natGatewayId",
                    equalTo(NAT_GATEWAY));
    }

    @Test
    @Order(6)
    void replaceRouteWithTwoTargetsIsRejected() {
        given()
            .formParam("Action", "ReplaceRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", DEFAULT_ROUTE)
            .formParam("GatewayId", INTERNET_GATEWAY)
            .formParam("NatGatewayId", NAT_GATEWAY)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"));
    }

    /**
     * AWS lets the local route be repointed at a NAT gateway and then reset with LocalTarget, and
     * both halves are expressible here because `local` is simply the gateway id that route carries.
     * Supporting only the repoint would make the built-in route a one-way door.
     */
    @Test
    @Order(8)
    void theLocalRouteCanBeRepointedAndThenResetToLocal() {
        String vpcCidr = "203.0.113.0/24";

        given()
            .formParam("Action", "ReplaceRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", vpcCidr)
            .formParam("NatGatewayId", NAT_GATEWAY)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "ReplaceRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", vpcCidr)
            .formParam("LocalTarget", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ReplaceRouteResponse.return", equalTo("true"));

        given()
            .formParam("Action", "DescribeRouteTables")
            .formParam("RouteTableId.1", routeTableId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(ROUTE_SET + ".find { it.destinationCidrBlock == '" + vpcCidr + "' }.gatewayId",
                    equalTo("local"))
            // The route was created by CreateRouteTable, so a replacement that hardcoded an origin
            // instead of preserving one would show up right here.
            .body(ROUTE_SET + ".find { it.destinationCidrBlock == '" + vpcCidr + "' }.origin",
                    equalTo("CreateRouteTable"));
    }

    /** `--no-local-target` puts LocalTarget=false on the wire; it must not read as a target. */
    @Test
    @Order(9)
    void anExplicitlyFalseLocalTargetIsNotTreatedAsATarget() {
        given()
            .formParam("Action", "ReplaceRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", DEFAULT_ROUTE)
            .formParam("GatewayId", INTERNET_GATEWAY)
            .formParam("LocalTarget", "false")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ReplaceRouteResponse.return", equalTo("true"));

        // ...and the NAT gateway this route used to carry is gone. Order 2 pins the gateway -> NAT
        // direction; without this the reverse direction could carry the old target over unnoticed.
        String table = given()
            .formParam("Action", "DescribeRouteTables")
            .formParam("RouteTableId.1", routeTableId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(DEFAULT_ROUTE_NODE + ".gatewayId", equalTo(INTERNET_GATEWAY))
            .extract().asString();

        assertThat(table, not(containsString(NAT_GATEWAY)));
    }

    /** A destination with no target at all must not blank the route and report success. */
    @Test
    @Order(10)
    void replaceRouteWithNoTargetAtAllIsRejected() {
        given()
            .formParam("Action", "ReplaceRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", DEFAULT_ROUTE)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"));

        given()
            .formParam("Action", "DescribeRouteTables")
            .formParam("RouteTableId.1", routeTableId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(DEFAULT_ROUTE_NODE + ".gatewayId", equalTo(INTERNET_GATEWAY));
    }

    /** LocalTarget is a target: pairing it with another one is still two targets. */
    @Test
    @Order(11)
    void localTargetCombinedWithAnotherTargetIsRejected() {
        given()
            .formParam("Action", "ReplaceRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", DEFAULT_ROUTE)
            .formParam("LocalTarget", "true")
            .formParam("GatewayId", INTERNET_GATEWAY)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"));
    }

    /**
     * A ReplaceRoute naming no destination at all cannot identify a route; it must not 500. All
     * three destination kinds are now accepted — see {@code Ec2Ipv6RouteTest}.
     */
    @Test
    @Order(7)
    void replaceRouteWithoutADestinationIsRejected() {
        given()
            .formParam("Action", "ReplaceRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("GatewayId", INTERNET_GATEWAY)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("MissingParameter"));
    }

    @Test
    @Order(4)
    void replaceRouteOnAnUnknownRouteTableIsRejected() {
        given()
            .formParam("Action", "ReplaceRoute")
            .formParam("RouteTableId", "rtb-0000000000000dead")
            .formParam("DestinationCidrBlock", DEFAULT_ROUTE)
            .formParam("GatewayId", INTERNET_GATEWAY)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidRouteTableID.NotFound"));
    }
}
