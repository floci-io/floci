package io.github.hectorvent.floci.services.ec2;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.xml.HasXPath.hasXPath;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.junit.QuarkusTest;
import static io.restassured.RestAssured.given;

/**
 * A destination identifies a route, so CreateRoute takes each one at most once per table and
 * https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_CreateRoute.html rejects a repeat with
 * RouteAlreadyExists.
 *
 * <p>Accepting the repeat is worse than untidy, because the two operations that address a route by
 * its destination then disagree about which copy they mean: ReplaceRoute matches only the first, so
 * the stale copy survives the replace, while DeleteRoute removes every copy, so deleting one of two
 * loses both. Neither has a defined result on a table holding duplicates, which is why the last two
 * tests here pin the behaviour of all three verbs together rather than only the rejection.
 *
 * <p>The local route CreateRouteTable seeds for the VPC CIDR is a route like any other for this
 * purpose, and AWS reports it the same way — the error is the one Terraform surfaces as
 * "RouteAlreadyExists: The route identified by 10.0.0.0/16 already exists" when a configuration
 * tries to add a route over it.
 *
 * <p>The XPath assertions use {@code local-name()} because EC2 responses carry a default namespace.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2DuplicateRouteTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static final String VPC_CIDR = "198.51.100.0/24";
    private static final String IPV4_ROUTE = "0.0.0.0/0";
    private static final String IPV6_ROUTE = "::/0";
    private static final String PREFIX_LIST = "pl-0dup0route0test0";
    private static final String INTERNET_GATEWAY = "igw-0dup0route0test0";
    private static final String OTHER_GATEWAY = "igw-0dup0route0othr0";
    private static final String NAT_GATEWAY = "nat-0dup0route0test0";

    private static final String ROUTE_COUNT =
            "count(//*[local-name()='routeSet']/*[local-name()='item'])";

    private static String routeTableId;

    private static io.restassured.specification.RequestSpecification ec2() {
        return given().header("Authorization", AUTH_HEADER);
    }

    private static io.restassured.response.ValidatableResponse describe() {
        return ec2()
            .formParam("Action", "DescribeRouteTables")
            .formParam("RouteTableId.1", routeTableId)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    /** CreateRoute with one destination and the internet gateway as its target. */
    private static io.restassured.specification.RequestSpecification createRoute(
            String destinationParam, String destination) {
        return ec2()
            .formParam("Action", "CreateRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam(destinationParam, destination)
            .formParam("GatewayId", INTERNET_GATEWAY);
    }

    /** Asserts the response is RouteAlreadyExists and names the destination it refused. */
    private static void expectRouteAlreadyExists(
            io.restassured.specification.RequestSpecification request, String destination) {
        String message = request
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("RouteAlreadyExists"))
            .extract().path("Response.Errors.Error.Message");

        assertThat(message, containsString(destination));
    }

    @Test
    @Order(1)
    void createARouteTableWithOneRoutePerDestinationKind() {
        String vpcId = ec2()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", VPC_CIDR)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        routeTableId = ec2()
            .formParam("Action", "CreateRouteTable")
            .formParam("VpcId", vpcId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateRouteTableResponse.routeTable.routeTableId");

        createRoute("DestinationCidrBlock", IPV4_ROUTE).when().post("/").then().statusCode(200);
        createRoute("DestinationIpv6CidrBlock", IPV6_ROUTE).when().post("/").then().statusCode(200);
        createRoute("DestinationPrefixListId", PREFIX_LIST).when().post("/").then().statusCode(200);

        // The seeded local route plus the three just created.
        describe().body(hasXPath(ROUTE_COUNT, equalTo("4")));
    }

    @Test
    @Order(2)
    void aRepeatedIpv4DestinationIsRejected() {
        expectRouteAlreadyExists(createRoute("DestinationCidrBlock", IPV4_ROUTE), IPV4_ROUTE);
    }

    @Test
    @Order(3)
    void aRepeatedIpv6DestinationIsRejected() {
        expectRouteAlreadyExists(createRoute("DestinationIpv6CidrBlock", IPV6_ROUTE), IPV6_ROUTE);
    }

    @Test
    @Order(4)
    void aRepeatedPrefixListDestinationIsRejected() {
        expectRouteAlreadyExists(createRoute("DestinationPrefixListId", PREFIX_LIST), PREFIX_LIST);
    }

    /**
     * The local route is seeded by CreateRouteTable rather than by a caller, and is still a route
     * whose destination is taken.
     */
    @Test
    @Order(5)
    void theSeededLocalRouteCannotBeRoutedOver() {
        expectRouteAlreadyExists(createRoute("DestinationCidrBlock", VPC_CIDR), VPC_CIDR);
    }

    /** A different target does not make the destination free. */
    @Test
    @Order(6)
    void aRepeatedDestinationIsRejectedEvenWithADifferentTarget() {
        expectRouteAlreadyExists(
                ec2()
                    .formParam("Action", "CreateRoute")
                    .formParam("RouteTableId", routeTableId)
                    .formParam("DestinationCidrBlock", IPV4_ROUTE)
                    .formParam("NatGatewayId", NAT_GATEWAY),
                IPV4_ROUTE);
    }

    /** Every rejection above left the table exactly as test 1 built it. */
    @Test
    @Order(7)
    void noRejectedCreateChangedTheTable() {
        describe().body(hasXPath(ROUTE_COUNT, equalTo("4")));
    }

    /**
     * ReplaceRoute matches the first route with the destination. With duplicates barred there is
     * only one, so the replace is unambiguous and leaves no stale copy behind.
     */
    @Test
    @Order(8)
    void replaceRouteRetargetsTheOnlyRouteWithThatDestination() {
        ec2()
            .formParam("Action", "ReplaceRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", IPV4_ROUTE)
            .formParam("GatewayId", OTHER_GATEWAY)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        describe()
            .body(hasXPath(ROUTE_COUNT, equalTo("4")))
            .body(hasXPath("count(//*[local-name()='routeSet']/*[local-name()='item']"
                    + "[*[local-name()='destinationCidrBlock']='" + IPV4_ROUTE + "'])",
                    equalTo("1")))
            .body("DescribeRouteTablesResponse.routeTableSet.item.routeSet.item"
                    + ".find { it.destinationCidrBlock == '" + IPV4_ROUTE + "' }.gatewayId",
                    equalTo(OTHER_GATEWAY));
    }

    /**
     * DeleteRoute removes every route matching the destination. With duplicates barred that is
     * exactly one, so a delete frees the destination rather than taking an unrelated route with it.
     */
    @Test
    @Order(9)
    void deleteRouteRemovesOneRouteAndFreesTheDestination() {
        ec2()
            .formParam("Action", "DeleteRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", IPV4_ROUTE)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        describe().body(hasXPath(ROUTE_COUNT, equalTo("3")));

        // Freed, so the destination can be created again.
        createRoute("DestinationCidrBlock", IPV4_ROUTE).when().post("/").then().statusCode(200);
        describe().body(hasXPath(ROUTE_COUNT, equalTo("4")));
    }
}
