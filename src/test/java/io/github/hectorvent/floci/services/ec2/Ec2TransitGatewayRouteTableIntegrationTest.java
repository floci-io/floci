package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Route tables, associations, propagations and routes over the EC2 Query protocol.
 *
 * <p>The shapes were captured from a live account, including two asymmetries that are easy to get
 * wrong: the association and propagation listings drop the route table id that the mutating calls
 * carry, and a blackhole route omits the attachment block entirely.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2TransitGatewayRouteTableIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static String transitGatewayId;
    private static String defaultRouteTableId;
    private static String routeTableId;
    private static String attachmentId;
    private static String vpcId;

    @Test
    @Order(1)
    void buildAGatewayAnAttachmentAndASecondRouteTable() {
        String created = given()
            .formParam("Action", "CreateTransitGateway")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200).extract().asString();
        transitGatewayId = extract(created, "transitGatewayId");
        defaultRouteTableId = extract(created, "associationDefaultRouteTableId");

        vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.40.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");

        String subnetId = given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.40.1.0/24")
            .formParam("AvailabilityZone", "us-east-1a")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");

        attachmentId = given()
            .formParam("Action", "CreateTransitGatewayVpcAttachment")
            .formParam("TransitGatewayId", transitGatewayId)
            .formParam("VpcId", vpcId)
            .formParam("SubnetIds.1", subnetId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateTransitGatewayVpcAttachmentResponse.transitGatewayVpcAttachment"
                    + ".transitGatewayAttachmentId");

        routeTableId = given()
            .formParam("Action", "CreateTransitGatewayRouteTable")
            .formParam("TransitGatewayId", transitGatewayId)
            .formParam("TagSpecification.1.ResourceType", "transit-gateway-route-table")
            .formParam("TagSpecification.1.Tag.1.Key", "Name")
            .formParam("TagSpecification.1.Tag.1.Value", "spoke")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("CreateTransitGatewayRouteTableResponse.transitGatewayRouteTable.state", equalTo("available"))
            .body("CreateTransitGatewayRouteTableResponse.transitGatewayRouteTable"
                    + ".defaultAssociationRouteTable", equalTo("false"))
            .body("CreateTransitGatewayRouteTableResponse.transitGatewayRouteTable.tagSet.item.value",
                    equalTo("spoke"))
            .extract().path("CreateTransitGatewayRouteTableResponse.transitGatewayRouteTable"
                    + ".transitGatewayRouteTableId");
    }

    private String extract(String body, String element) {
        int start = body.indexOf("<" + element + ">") + element.length() + 2;
        return body.substring(start, body.indexOf("</" + element + ">", start));
    }

    @Test
    @Order(2)
    void associationsMoveBetweenTablesAndTheListingDropsTheTableId() {
        given()
            .formParam("Action", "AssociateTransitGatewayRouteTable")
            .formParam("TransitGatewayRouteTableId", routeTableId)
            .formParam("TransitGatewayAttachmentId", attachmentId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("Resource.AlreadyAssociated"));

        given()
            .formParam("Action", "DisassociateTransitGatewayRouteTable")
            .formParam("TransitGatewayRouteTableId", defaultRouteTableId)
            .formParam("TransitGatewayAttachmentId", attachmentId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("DisassociateTransitGatewayRouteTableResponse.association.state", equalTo("disassociating"))
            .body("DisassociateTransitGatewayRouteTableResponse.association.transitGatewayRouteTableId",
                    equalTo(defaultRouteTableId));

        given()
            .formParam("Action", "AssociateTransitGatewayRouteTable")
            .formParam("TransitGatewayRouteTableId", routeTableId)
            .formParam("TransitGatewayAttachmentId", attachmentId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("AssociateTransitGatewayRouteTableResponse.association.state", equalTo("associating"));

        String listing = given()
            .formParam("Action", "GetTransitGatewayRouteTableAssociations")
            .formParam("TransitGatewayRouteTableId", routeTableId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("GetTransitGatewayRouteTableAssociationsResponse.associations.item.resourceType",
                    equalTo("vpc"))
            .body("GetTransitGatewayRouteTableAssociationsResponse.associations.item.state",
                    equalTo("associated"))
            .extract().asString();

        // The caller named the table in the request, so the listing does not repeat it.
        assertThat(listing, not(containsString("transitGatewayRouteTableId")));
    }

    @Test
    @Order(3)
    void propagationEnablesAtOnceAndShowsUpAsARoute() {
        given()
            .formParam("Action", "EnableTransitGatewayRouteTablePropagation")
            .formParam("TransitGatewayRouteTableId", routeTableId)
            .formParam("TransitGatewayAttachmentId", attachmentId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("EnableTransitGatewayRouteTablePropagationResponse.propagation.state", equalTo("enabled"))
            .body("EnableTransitGatewayRouteTablePropagationResponse.propagation.transitGatewayRouteTableId",
                    equalTo(routeTableId));

        String listing = given()
            .formParam("Action", "GetTransitGatewayRouteTablePropagations")
            .formParam("TransitGatewayRouteTableId", routeTableId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("GetTransitGatewayRouteTablePropagationsResponse.transitGatewayRouteTablePropagations"
                    + ".item.state", equalTo("enabled"))
            .extract().asString();
        assertThat(listing, not(containsString("transitGatewayRouteTableId")));

        // The attached VPC's CIDR appears as a propagated route.
        given()
            .formParam("Action", "SearchTransitGatewayRoutes")
            .formParam("TransitGatewayRouteTableId", routeTableId)
            .formParam("Filter.1.Name", "type")
            .formParam("Filter.1.Value.1", "propagated")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("SearchTransitGatewayRoutesResponse.routeSet.item.destinationCidrBlock",
                    equalTo("10.40.0.0/16"))
            .body("SearchTransitGatewayRoutesResponse.routeSet.item.type", equalTo("propagated"))
            .body("SearchTransitGatewayRoutesResponse.routeSet.item.state", equalTo("active"));
    }

    @Test
    @Order(4)
    void aBlackholeRouteCarriesNoAttachment() {
        given()
            .formParam("Action", "CreateTransitGatewayRoute")
            .formParam("TransitGatewayRouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", "10.60.0.0/16")
            .formParam("TransitGatewayAttachmentId", attachmentId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("CreateTransitGatewayRouteResponse.route.type", equalTo("static"))
            .body("CreateTransitGatewayRouteResponse.route.state", equalTo("active"))
            .body("CreateTransitGatewayRouteResponse.route.transitGatewayAttachments.item"
                    + ".transitGatewayAttachmentId", equalTo(attachmentId));

        String blackhole = given()
            .formParam("Action", "CreateTransitGatewayRoute")
            .formParam("TransitGatewayRouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", "10.61.0.0/16")
            .formParam("Blackhole", "true")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("CreateTransitGatewayRouteResponse.route.type", equalTo("static"))
            .body("CreateTransitGatewayRouteResponse.route.state", equalTo("blackhole"))
            .extract().asString();
        assertThat(blackhole, not(containsString("transitGatewayAttachments")));

        given()
            .formParam("Action", "CreateTransitGatewayRoute")
            .formParam("TransitGatewayRouteTableId", routeTableId)
            .formParam("DestinationCidrBlock", "10.60.0.0/16")
            .formParam("TransitGatewayAttachmentId", attachmentId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("RouteAlreadyExists"));
    }

    @Test
    @Order(5)
    void aTableInUseIsRefusedAndTheIdErrorsKeepTheirCasing() {
        given()
            .formParam("Action", "DeleteTransitGatewayRouteTable")
            .formParam("TransitGatewayRouteTableId", routeTableId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("IncorrectState"));

        given()
            .formParam("Action", "DescribeTransitGatewayRouteTables")
            .formParam("TransitGatewayRouteTableIds.1", "tgw-rtb-0123456789abcdef0")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidRouteTableID.NotFound"));

        given()
            .formParam("Action", "DescribeTransitGatewayRouteTables")
            .formParam("TransitGatewayRouteTableIds.1", "tgw-rtb-nope")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidRouteTableId.Malformed"));
    }
}
