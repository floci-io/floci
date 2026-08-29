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
 * AWS canonicalizes an IPv4 destination CIDR on input:
 * https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_CreateRoute.html — "We modify the
 * specified CIDR block to its canonical form; for example, if you specify 100.68.0.18/18, we
 * modify it to 100.68.0.0/18." This is the property that makes RouteAlreadyExists (see
 * {@link Ec2DuplicateRouteTest}) actually work for the destinations people write: two operators
 * spelling the same /18 network differently must collide as the same route, and
 * DescribeRouteTables must always echo the canonical spelling regardless of which spelling
 * CreateRoute, ReplaceRoute or DeleteRoute were called with.
 *
 * <p>Canonicalization is IPv4-only. DestinationIpv6CidrBlock has no equivalent sentence in the
 * model and DestinationPrefixListId is an opaque ID, not a CIDR, so neither is touched — the last
 * test here pins that an IPv6 destination survives byte-for-byte.
 *
 * <p>The XPath assertions use {@code local-name()} because EC2 responses carry a default
 * namespace.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2RouteCidrCanonicalizationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static final String VPC_CIDR = "203.0.113.0/24";
    private static final String NON_CANONICAL = "100.68.0.18/18";
    private static final String CANONICAL = "100.68.0.0/18";
    private static final String OTHER_SPELLING_SAME_NETWORK = "100.68.63.255/18";
    private static final String IPV6_ROUTE = "2001:db8::/32";
    private static final String INTERNET_GATEWAY = "igw-0cidrcanon0test00";
    private static final String OTHER_GATEWAY = "igw-0cidrcanon0othr00";

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

    private static io.restassured.specification.RequestSpecification createRoute(
            String destinationCidrBlock, String gatewayId) {
        return ec2()
            .formParam("Action", "CreateRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", destinationCidrBlock)
            .formParam("GatewayId", gatewayId);
    }

    @Test
    @Order(1)
    void createARouteTable() {
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
    }

    /** CreateRoute stores and echoes the canonical form, not the spelling the caller sent. */
    @Test
    @Order(2)
    void createRouteCanonicalizesTheDestination() {
        createRoute(NON_CANONICAL, INTERNET_GATEWAY).when().post("/").then().statusCode(200);

        describe()
            .body(hasXPath(ROUTE_COUNT, equalTo("2"))) // the seeded local route, plus this one
            .body(hasXPath("//*[local-name()='routeSet']/*[local-name()='item']"
                    + "[*[local-name()='destinationCidrBlock']='" + CANONICAL + "']"))
            .body(hasXPath("count(//*[local-name()='routeSet']/*[local-name()='item']"
                    + "[*[local-name()='destinationCidrBlock']='" + NON_CANONICAL + "'])", equalTo("0")));
    }

    /**
     * A second CreateRoute spelling the same /18 network differently is the same destination as
     * far as AWS is concerned, so it collides with RouteAlreadyExists exactly like a literal
     * repeat would (see {@link Ec2DuplicateRouteTest}). Without canonicalizing on input, this
     * would have silently created a second, functionally ambiguous route instead.
     */
    @Test
    @Order(3)
    void anEquivalentSpellingOfTheSameNetworkCollidesAsADuplicate() {
        String message = createRoute(OTHER_SPELLING_SAME_NETWORK, INTERNET_GATEWAY)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("RouteAlreadyExists"))
            .extract().path("Response.Errors.Error.Message");

        // The error names the canonical destination, since that is what is actually on file.
        assertThat(message, containsString(CANONICAL));

        describe().body(hasXPath(ROUTE_COUNT, equalTo("2")));
    }

    /**
     * ReplaceRoute must canonicalize its own DestinationCidrBlock before matching, or an
     * equivalent-but-differently-spelled request would miss the stored canonical route entirely
     * and fail with InvalidRoute.NotFound instead of retargeting it.
     */
    @Test
    @Order(4)
    void replaceRouteMatchesTheCanonicalRouteByAnEquivalentSpelling() {
        ec2()
            .formParam("Action", "ReplaceRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", OTHER_SPELLING_SAME_NETWORK)
            .formParam("GatewayId", OTHER_GATEWAY)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        describe()
            .body(hasXPath(ROUTE_COUNT, equalTo("2")))
            .body("DescribeRouteTablesResponse.routeTableSet.item.routeSet.item"
                    + ".find { it.destinationCidrBlock == '" + CANONICAL + "' }.gatewayId",
                    equalTo(OTHER_GATEWAY));
    }

    /**
     * DeleteRoute must canonicalize the same way, or an equivalent spelling would silently match
     * nothing and leave the route in place while reporting success.
     */
    @Test
    @Order(5)
    void deleteRouteMatchesTheCanonicalRouteByAnEquivalentSpelling() {
        ec2()
            .formParam("Action", "DeleteRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", NON_CANONICAL)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        describe().body(hasXPath(ROUTE_COUNT, equalTo("1"))); // only the seeded local route remains
    }

    /** DestinationIpv6CidrBlock has no canonical form in the model and is stored byte-for-byte. */
    @Test
    @Order(6)
    void ipv6DestinationsAreNotCanonicalized() {
        ec2()
            .formParam("Action", "CreateRoute")
            .formParam("RouteTableId", routeTableId)
            .formParam("DestinationIpv6CidrBlock", IPV6_ROUTE)
            .formParam("GatewayId", INTERNET_GATEWAY)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        describe().body(hasXPath("//*[local-name()='routeSet']/*[local-name()='item']"
                + "[*[local-name()='destinationIpv6CidrBlock']='" + IPV6_ROUTE + "']"));
    }
}
