package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the transit gateway family over the EC2 Query protocol.
 *
 * <p>The whole family is exercised as one lifecycle because the pieces only mean anything
 * together: a gateway mints its default route table, an attachment associates and propagates
 * into it, propagation puts the attached VPC's CIDRs in the table, and detaching unwinds all
 * three. Every state assertion checks the terminal value on the first read — a transitional
 * state here would strand the poller in a provider that waits on these resources.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2TransitGatewayIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static final String VPC_CIDR = "10.44.0.0/16";

    private static String vpcId;
    private static String subnetA;
    private static String subnetB;
    private static String transitGatewayId;
    private static String defaultRouteTableId;
    private static String attachmentId;
    private static String customRouteTableId;

    private static RequestSpecification ec2() {
        return given().header("Authorization", AUTH_HEADER);
    }

    @Test
    @Order(1)
    void createVpcAndSubnetsToAttach() {
        vpcId = ec2()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", VPC_CIDR)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        subnetA = createSubnet("10.44.1.0/24");
        subnetB = createSubnet("10.44.2.0/24");
        assertNotEquals(subnetA, subnetB);
    }

    private String createSubnet(String cidr) {
        return ec2()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", cidr)
            .formParam("AvailabilityZone", "us-east-1a")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateSubnetResponse.subnet.subnetId");
    }

    @Test
    @Order(2)
    void createTransitGatewayRoundTripsEveryOptionAndIsAvailable() {
        transitGatewayId = ec2()
            .formParam("Action", "CreateTransitGateway")
            .formParam("Description", "integration transit gateway")
            .formParam("Options.AmazonSideAsn", "64513")
            .formParam("Options.DnsSupport", "disable")
            .formParam("Options.VpnEcmpSupport", "disable")
            .formParam("Options.AutoAcceptSharedAttachments", "enable")
            .formParam("Options.MulticastSupport", "enable")
            .formParam("Options.TransitGatewayCidrBlocks.1", "10.200.0.0/24")
            .formParam("TagSpecification.1.ResourceType", "transit-gateway")
            .formParam("TagSpecification.1.Tag.1.Key", "Name")
            .formParam("TagSpecification.1.Tag.1.Value", "integration-tgw")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateTransitGatewayResponse.transitGateway.state", equalTo("available"))
            .body("CreateTransitGatewayResponse.transitGateway.description",
                    equalTo("integration transit gateway"))
            .body("CreateTransitGatewayResponse.transitGateway.transitGatewayArn",
                    org.hamcrest.Matchers.containsString(":transit-gateway/tgw-"))
            .body("CreateTransitGatewayResponse.transitGateway.options.amazonSideAsn", equalTo("64513"))
            .body("CreateTransitGatewayResponse.transitGateway.options.dnsSupport", equalTo("disable"))
            .body("CreateTransitGatewayResponse.transitGateway.options.vpnEcmpSupport", equalTo("disable"))
            .body("CreateTransitGatewayResponse.transitGateway.options.autoAcceptSharedAttachments",
                    equalTo("enable"))
            .body("CreateTransitGatewayResponse.transitGateway.options.multicastSupport", equalTo("enable"))
            .body("CreateTransitGatewayResponse.transitGateway.options.transitGatewayCidrBlocks.item",
                    equalTo("10.200.0.0/24"))
            // Not sent, so AWS's own defaults have to come back.
            .body("CreateTransitGatewayResponse.transitGateway.options.defaultRouteTableAssociation",
                    equalTo("enable"))
            .body("CreateTransitGatewayResponse.transitGateway.options.defaultRouteTablePropagation",
                    equalTo("enable"))
            .body("CreateTransitGatewayResponse.transitGateway.options.securityGroupReferencingSupport",
                    equalTo("disable"))
            .body("CreateTransitGatewayResponse.transitGateway.tagSet.item.value", equalTo("integration-tgw"))
            .extract().path("CreateTransitGatewayResponse.transitGateway.transitGatewayId");

        assertTrue(transitGatewayId.startsWith("tgw-"));
    }

    @Test
    @Order(3)
    void createMintsOneDefaultRouteTableServingBothDefaults() {
        String associationDefault = ec2()
            .formParam("Action", "DescribeTransitGateways")
            .formParam("TransitGatewayIds.1", transitGatewayId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeTransitGatewaysResponse.transitGatewaySet.item.state", equalTo("available"))
            .extract()
            .path("DescribeTransitGatewaysResponse.transitGatewaySet.item.options"
                    + ".associationDefaultRouteTableId");

        String propagationDefault = ec2()
            .formParam("Action", "DescribeTransitGateways")
            .formParam("TransitGatewayIds.1", transitGatewayId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract()
            .path("DescribeTransitGatewaysResponse.transitGatewaySet.item.options"
                    + ".propagationDefaultRouteTableId");

        assertTrue(associationDefault.startsWith("tgw-rtb-"));
        assertEquals(associationDefault, propagationDefault,
                "AWS points both defaults at the single route table it mints with the gateway");
        defaultRouteTableId = associationDefault;

        ec2()
            .formParam("Action", "DescribeTransitGatewayRouteTables")
            .formParam("TransitGatewayRouteTableIds.1", defaultRouteTableId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeTransitGatewayRouteTablesResponse.transitGatewayRouteTables.item.state",
                    equalTo("available"))
            .body("DescribeTransitGatewayRouteTablesResponse.transitGatewayRouteTables.item"
                    + ".defaultAssociationRouteTable", equalTo("true"))
            .body("DescribeTransitGatewayRouteTablesResponse.transitGatewayRouteTables.item"
                    + ".defaultPropagationRouteTable", equalTo("true"))
            .body("DescribeTransitGatewayRouteTablesResponse.transitGatewayRouteTables.item.transitGatewayId",
                    equalTo(transitGatewayId));
    }

    @Test
    @Order(4)
    void createVpcAttachmentIsAvailableAndEchoesSubnetsAndOptions() {
        attachmentId = ec2()
            .formParam("Action", "CreateTransitGatewayVpcAttachment")
            .formParam("TransitGatewayId", transitGatewayId)
            .formParam("VpcId", vpcId)
            .formParam("SubnetIds.1", subnetA)
            .formParam("Options.ApplianceModeSupport", "enable")
            .formParam("TagSpecification.1.ResourceType", "transit-gateway-attachment")
            .formParam("TagSpecification.1.Tag.1.Key", "Name")
            .formParam("TagSpecification.1.Tag.1.Value", "integration-attach")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateTransitGatewayVpcAttachmentResponse.transitGatewayVpcAttachment.state",
                    equalTo("available"))
            .body("CreateTransitGatewayVpcAttachmentResponse.transitGatewayVpcAttachment.vpcId",
                    equalTo(vpcId))
            .body("CreateTransitGatewayVpcAttachmentResponse.transitGatewayVpcAttachment.subnetIds.item",
                    equalTo(subnetA))
            .body("CreateTransitGatewayVpcAttachmentResponse.transitGatewayVpcAttachment.options"
                    + ".applianceModeSupport", equalTo("enable"))
            .body("CreateTransitGatewayVpcAttachmentResponse.transitGatewayVpcAttachment.options.dnsSupport",
                    equalTo("enable"))
            .body("CreateTransitGatewayVpcAttachmentResponse.transitGatewayVpcAttachment.tagSet.item.value",
                    equalTo("integration-attach"))
            .extract()
            .path("CreateTransitGatewayVpcAttachmentResponse.transitGatewayVpcAttachment"
                    + ".transitGatewayAttachmentId");

        assertTrue(attachmentId.startsWith("tgw-attach-"));

        ec2()
            .formParam("Action", "DescribeTransitGatewayVpcAttachments")
            .formParam("TransitGatewayAttachmentIds.1", attachmentId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeTransitGatewayVpcAttachmentsResponse.transitGatewayVpcAttachments.item.state",
                    equalTo("available"));
    }

    @Test
    @Order(5)
    void attachingAssociatesAndPropagatesIntoTheDefaultRouteTable() {
        ec2()
            .formParam("Action", "GetTransitGatewayRouteTableAssociations")
            .formParam("TransitGatewayRouteTableId", defaultRouteTableId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetTransitGatewayRouteTableAssociationsResponse.associations.item"
                    + ".transitGatewayAttachmentId", equalTo(attachmentId))
            .body("GetTransitGatewayRouteTableAssociationsResponse.associations.item.resourceId",
                    equalTo(vpcId))
            .body("GetTransitGatewayRouteTableAssociationsResponse.associations.item.resourceType",
                    equalTo("vpc"))
            .body("GetTransitGatewayRouteTableAssociationsResponse.associations.item.state",
                    equalTo("associated"));

        ec2()
            .formParam("Action", "GetTransitGatewayRouteTablePropagations")
            .formParam("TransitGatewayRouteTableId", defaultRouteTableId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetTransitGatewayRouteTablePropagationsResponse.transitGatewayRouteTablePropagations.item"
                    + ".transitGatewayAttachmentId", equalTo(attachmentId))
            .body("GetTransitGatewayRouteTablePropagationsResponse.transitGatewayRouteTablePropagations.item"
                    + ".state", equalTo("enabled"));
    }

    @Test
    @Order(6)
    void propagationPutsTheVpcCidrInTheRouteTable() {
        ec2()
            .formParam("Action", "SearchTransitGatewayRoutes")
            .formParam("TransitGatewayRouteTableId", defaultRouteTableId)
            .formParam("Filter.1.Name", "type")
            .formParam("Filter.1.Value.1", "propagated")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SearchTransitGatewayRoutesResponse.routeSet.item.destinationCidrBlock", equalTo(VPC_CIDR))
            .body("SearchTransitGatewayRoutesResponse.routeSet.item.state", equalTo("active"))
            .body("SearchTransitGatewayRoutesResponse.routeSet.item.transitGatewayAttachments.item"
                    + ".transitGatewayAttachmentId", equalTo(attachmentId))
            .body("SearchTransitGatewayRoutesResponse.routeSet.item.transitGatewayAttachments.item"
                    + ".resourceType", equalTo("vpc"))
            .body("SearchTransitGatewayRoutesResponse.additionalRoutesAvailable", equalTo("false"));
    }

    @Test
    @Order(7)
    void searchRequiresFilters() {
        ec2()
            .formParam("Action", "SearchTransitGatewayRoutes")
            .formParam("TransitGatewayRouteTableId", defaultRouteTableId)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("MissingParameter"));
    }

    @Test
    @Order(8)
    void staticAndBlackholeRoutesLandInTheTable() {
        ec2()
            .formParam("Action", "CreateTransitGatewayRoute")
            .formParam("TransitGatewayRouteTableId", defaultRouteTableId)
            .formParam("DestinationCidrBlock", "10.99.0.0/16")
            .formParam("TransitGatewayAttachmentId", attachmentId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateTransitGatewayRouteResponse.route.state", equalTo("active"))
            .body("CreateTransitGatewayRouteResponse.route.type", equalTo("static"))
            .body("CreateTransitGatewayRouteResponse.route.transitGatewayAttachments.item"
                    + ".transitGatewayAttachmentId", equalTo(attachmentId));

        ec2()
            .formParam("Action", "CreateTransitGatewayRoute")
            .formParam("TransitGatewayRouteTableId", defaultRouteTableId)
            .formParam("DestinationCidrBlock", "192.0.2.0/24")
            .formParam("Blackhole", "true")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateTransitGatewayRouteResponse.route.state", equalTo("blackhole"))
            .body("CreateTransitGatewayRouteResponse.route.type", equalTo("static"));

        ec2()
            .formParam("Action", "SearchTransitGatewayRoutes")
            .formParam("TransitGatewayRouteTableId", defaultRouteTableId)
            .formParam("Filter.1.Name", "type")
            .formParam("Filter.1.Value.1", "static")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SearchTransitGatewayRoutesResponse.routeSet.item.destinationCidrBlock",
                    containsInAnyOrder("10.99.0.0/16", "192.0.2.0/24"));
    }

    @Test
    @Order(9)
    void duplicateRouteIsRejected() {
        ec2()
            .formParam("Action", "CreateTransitGatewayRoute")
            .formParam("TransitGatewayRouteTableId", defaultRouteTableId)
            .formParam("DestinationCidrBlock", "10.99.0.0/16")
            .formParam("TransitGatewayAttachmentId", attachmentId)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("RouteAlreadyExists"));
    }

    @Test
    @Order(10)
    void deleteRouteRemovesItFromTheTable() {
        ec2()
            .formParam("Action", "DeleteTransitGatewayRoute")
            .formParam("TransitGatewayRouteTableId", defaultRouteTableId)
            .formParam("DestinationCidrBlock", "192.0.2.0/24")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteTransitGatewayRouteResponse.route.state", equalTo("deleted"));

        ec2()
            .formParam("Action", "SearchTransitGatewayRoutes")
            .formParam("TransitGatewayRouteTableId", defaultRouteTableId)
            .formParam("Filter.1.Name", "type")
            .formParam("Filter.1.Value.1", "static")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SearchTransitGatewayRoutesResponse.routeSet.item.destinationCidrBlock",
                    equalTo("10.99.0.0/16"));
    }

    @Test
    @Order(11)
    void anAttachmentCannotBeAssociatedTwice() {
        customRouteTableId = ec2()
            .formParam("Action", "CreateTransitGatewayRouteTable")
            .formParam("TransitGatewayId", transitGatewayId)
            .formParam("TagSpecification.1.ResourceType", "transit-gateway-route-table")
            .formParam("TagSpecification.1.Tag.1.Key", "Name")
            .formParam("TagSpecification.1.Tag.1.Value", "integration-tgw-rtb")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateTransitGatewayRouteTableResponse.transitGatewayRouteTable.state",
                    equalTo("available"))
            .body("CreateTransitGatewayRouteTableResponse.transitGatewayRouteTable"
                    + ".defaultAssociationRouteTable", equalTo("false"))
            .body("CreateTransitGatewayRouteTableResponse.transitGatewayRouteTable.tagSet.item.value",
                    equalTo("integration-tgw-rtb"))
            .extract()
            .path("CreateTransitGatewayRouteTableResponse.transitGatewayRouteTable"
                    + ".transitGatewayRouteTableId");

        ec2()
            .formParam("Action", "AssociateTransitGatewayRouteTable")
            .formParam("TransitGatewayRouteTableId", customRouteTableId)
            .formParam("TransitGatewayAttachmentId", attachmentId)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("Resource.AlreadyAssociated"));
    }

    @Test
    @Order(12)
    void associationCanBeMovedToACustomRouteTable() {
        ec2()
            .formParam("Action", "DisassociateTransitGatewayRouteTable")
            .formParam("TransitGatewayRouteTableId", defaultRouteTableId)
            .formParam("TransitGatewayAttachmentId", attachmentId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DisassociateTransitGatewayRouteTableResponse.association.state",
                    equalTo("disassociated"))
            .body("DisassociateTransitGatewayRouteTableResponse.association.transitGatewayRouteTableId",
                    equalTo(defaultRouteTableId));

        ec2()
            .formParam("Action", "AssociateTransitGatewayRouteTable")
            .formParam("TransitGatewayRouteTableId", customRouteTableId)
            .formParam("TransitGatewayAttachmentId", attachmentId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AssociateTransitGatewayRouteTableResponse.association.state", equalTo("associated"))
            .body("AssociateTransitGatewayRouteTableResponse.association.transitGatewayRouteTableId",
                    equalTo(customRouteTableId));

        ec2()
            .formParam("Action", "GetTransitGatewayRouteTableAssociations")
            .formParam("TransitGatewayRouteTableId", defaultRouteTableId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetTransitGatewayRouteTableAssociationsResponse.associations", emptyOrNullString());
    }

    @Test
    @Order(13)
    void propagationCanBeEnabledOnAndDisabledFromACustomTable() {
        ec2()
            .formParam("Action", "EnableTransitGatewayRouteTablePropagation")
            .formParam("TransitGatewayRouteTableId", customRouteTableId)
            .formParam("TransitGatewayAttachmentId", attachmentId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("EnableTransitGatewayRouteTablePropagationResponse.propagation.state", equalTo("enabled"))
            .body("EnableTransitGatewayRouteTablePropagationResponse.propagation.transitGatewayRouteTableId",
                    equalTo(customRouteTableId));

        ec2()
            .formParam("Action", "SearchTransitGatewayRoutes")
            .formParam("TransitGatewayRouteTableId", customRouteTableId)
            .formParam("Filter.1.Name", "type")
            .formParam("Filter.1.Value.1", "propagated")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SearchTransitGatewayRoutesResponse.routeSet.item.destinationCidrBlock", equalTo(VPC_CIDR));

        ec2()
            .formParam("Action", "DisableTransitGatewayRouteTablePropagation")
            .formParam("TransitGatewayRouteTableId", customRouteTableId)
            .formParam("TransitGatewayAttachmentId", attachmentId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DisableTransitGatewayRouteTablePropagationResponse.propagation.state",
                    equalTo("disabled"));

        ec2()
            .formParam("Action", "SearchTransitGatewayRoutes")
            .formParam("TransitGatewayRouteTableId", customRouteTableId)
            .formParam("Filter.1.Name", "type")
            .formParam("Filter.1.Value.1", "propagated")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SearchTransitGatewayRoutesResponse.routeSet", emptyOrNullString());
    }

    @Test
    @Order(14)
    void modifyTransitGatewayUpdatesDescriptionAndCidrBlocks() {
        ec2()
            .formParam("Action", "ModifyTransitGateway")
            .formParam("TransitGatewayId", transitGatewayId)
            .formParam("Description", "modified transit gateway")
            .formParam("Options.AddTransitGatewayCidrBlocks.1", "10.201.0.0/24")
            .formParam("Options.RemoveTransitGatewayCidrBlocks.1", "10.200.0.0/24")
            .formParam("Options.DnsSupport", "enable")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyTransitGatewayResponse.transitGateway.state", equalTo("available"))
            .body("ModifyTransitGatewayResponse.transitGateway.description",
                    equalTo("modified transit gateway"))
            .body("ModifyTransitGatewayResponse.transitGateway.options.dnsSupport", equalTo("enable"))
            .body("ModifyTransitGatewayResponse.transitGateway.options.transitGatewayCidrBlocks.item",
                    equalTo("10.201.0.0/24"))
            // The ASN was not part of the modify, so it must survive untouched.
            .body("ModifyTransitGatewayResponse.transitGateway.options.amazonSideAsn", equalTo("64513"));
    }

    @Test
    @Order(15)
    void modifyAttachmentAddsASubnetAndFlipsAnOption() {
        ec2()
            .formParam("Action", "ModifyTransitGatewayVpcAttachment")
            .formParam("TransitGatewayAttachmentId", attachmentId)
            .formParam("AddSubnetIds.1", subnetB)
            .formParam("Options.Ipv6Support", "enable")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyTransitGatewayVpcAttachmentResponse.transitGatewayVpcAttachment.state",
                    equalTo("available"))
            .body("ModifyTransitGatewayVpcAttachmentResponse.transitGatewayVpcAttachment.subnetIds.item",
                    containsInAnyOrder(subnetA, subnetB))
            .body("ModifyTransitGatewayVpcAttachmentResponse.transitGatewayVpcAttachment.options.ipv6Support",
                    equalTo("enable"));

        ec2()
            .formParam("Action", "ModifyTransitGatewayVpcAttachment")
            .formParam("TransitGatewayAttachmentId", attachmentId)
            .formParam("RemoveSubnetIds.1", subnetB)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyTransitGatewayVpcAttachmentResponse.transitGatewayVpcAttachment.subnetIds.item",
                    equalTo(subnetA));
    }

    @Test
    @Order(16)
    void createTagsReachesTheTransitGatewayAndItsFilters() {
        ec2()
            .formParam("Action", "CreateTags")
            .formParam("ResourceId.1", transitGatewayId)
            .formParam("Tag.1.Key", "env")
            .formParam("Tag.1.Value", "integration")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        ec2()
            .formParam("Action", "DescribeTransitGateways")
            .formParam("Filter.1.Name", "tag:env")
            .formParam("Filter.1.Value.1", "integration")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeTransitGatewaysResponse.transitGatewaySet.item.transitGatewayId",
                    equalTo(transitGatewayId));
    }

    @Test
    @Order(17)
    void aGatewayWithAttachmentsCannotBeDeleted() {
        ec2()
            .formParam("Action", "DeleteTransitGateway")
            .formParam("TransitGatewayId", transitGatewayId)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("IncorrectState"));
    }

    @Test
    @Order(18)
    void deletingTheAttachmentUnwindsItsAssociation() {
        ec2()
            .formParam("Action", "DeleteTransitGatewayVpcAttachment")
            .formParam("TransitGatewayAttachmentId", attachmentId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteTransitGatewayVpcAttachmentResponse.transitGatewayVpcAttachment.state",
                    equalTo("deleted"));

        ec2()
            .formParam("Action", "GetTransitGatewayRouteTableAssociations")
            .formParam("TransitGatewayRouteTableId", customRouteTableId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetTransitGatewayRouteTableAssociationsResponse.associations", emptyOrNullString());

        ec2()
            .formParam("Action", "DescribeTransitGatewayVpcAttachments")
            .formParam("Filter.1.Name", "transit-gateway-id")
            .formParam("Filter.1.Value.1", transitGatewayId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeTransitGatewayVpcAttachmentsResponse.transitGatewayVpcAttachments",
                    emptyOrNullString());
    }

    @Test
    @Order(19)
    void deleteRouteTableThenGatewayClearsEverything() {
        ec2()
            .formParam("Action", "DeleteTransitGatewayRouteTable")
            .formParam("TransitGatewayRouteTableId", customRouteTableId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteTransitGatewayRouteTableResponse.transitGatewayRouteTable.state",
                    equalTo("deleted"));

        ec2()
            .formParam("Action", "DeleteTransitGateway")
            .formParam("TransitGatewayId", transitGatewayId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteTransitGatewayResponse.transitGateway.state", equalTo("deleted"));

        // The default route table went with the gateway.
        ec2()
            .formParam("Action", "DescribeTransitGatewayRouteTables")
            .formParam("Filter.1.Name", "transit-gateway-id")
            .formParam("Filter.1.Value.1", transitGatewayId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeTransitGatewayRouteTablesResponse.transitGatewayRouteTables",
                    emptyOrNullString());
    }

    @Test
    @Order(20)
    void describingAMissingGatewayReturnsTheModelledError() {
        ec2()
            .formParam("Action", "DescribeTransitGateways")
            .formParam("TransitGatewayIds.1", transitGatewayId)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidTransitGatewayID.NotFound"));
    }

    @Test
    @Order(21)
    void aGatewayWithBothDefaultsDisabledMintsNoRouteTable() {
        String bare = ec2()
            .formParam("Action", "CreateTransitGateway")
            .formParam("Options.DefaultRouteTableAssociation", "disable")
            .formParam("Options.DefaultRouteTablePropagation", "disable")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateTransitGatewayResponse.transitGateway.options.associationDefaultRouteTableId",
                    emptyOrNullString())
            .body("CreateTransitGatewayResponse.transitGateway.options.propagationDefaultRouteTableId",
                    emptyOrNullString())
            .extract().path("CreateTransitGatewayResponse.transitGateway.transitGatewayId");

        ec2()
            .formParam("Action", "DescribeTransitGatewayRouteTables")
            .formParam("Filter.1.Name", "transit-gateway-id")
            .formParam("Filter.1.Value.1", bare)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeTransitGatewayRouteTablesResponse.transitGatewayRouteTables",
                    emptyOrNullString());

        ec2()
            .formParam("Action", "DeleteTransitGateway")
            .formParam("TransitGatewayId", bare)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(22)
    void attachmentRejectsASubnetFromAnotherVpc() {
        String otherVpc = ec2()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.45.0.0/16")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        String otherSubnet = ec2()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", otherVpc)
            .formParam("CidrBlock", "10.45.1.0/24")
            .formParam("AvailabilityZone", "us-east-1a")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateSubnetResponse.subnet.subnetId");

        String gateway = ec2()
            .formParam("Action", "CreateTransitGateway")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateTransitGatewayResponse.transitGateway.transitGatewayId");

        ec2()
            .formParam("Action", "CreateTransitGatewayVpcAttachment")
            .formParam("TransitGatewayId", gateway)
            .formParam("VpcId", vpcId)
            .formParam("SubnetIds.1", otherSubnet)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));

        // Nothing was attached, so the gateway still deletes cleanly.
        ec2()
            .formParam("Action", "DeleteTransitGateway")
            .formParam("TransitGatewayId", gateway)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        assertTrue(List.of(otherVpc, vpcId).contains(vpcId));
    }

    @Test
    @Order(23)
    void routeTablesAreScopedToTheirGateway() {
        String first = ec2()
            .formParam("Action", "CreateTransitGateway")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateTransitGatewayResponse.transitGateway.transitGatewayId");

        String second = ec2()
            .formParam("Action", "CreateTransitGateway")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateTransitGatewayResponse.transitGateway.transitGatewayId");

        ec2()
            .formParam("Action", "DescribeTransitGatewayRouteTables")
            .formParam("Filter.1.Name", "transit-gateway-id")
            .formParam("Filter.1.Value.1", first)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeTransitGatewayRouteTablesResponse.transitGatewayRouteTables.item.transitGatewayId",
                    equalTo(first));

        ec2()
            .formParam("Action", "DescribeTransitGateways")
            .formParam("Filter.1.Name", "state")
            .formParam("Filter.1.Value.1", "available")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeTransitGatewaysResponse.transitGatewaySet.item.transitGatewayId",
                    hasItem(second))
            .body("DescribeTransitGatewaysResponse.transitGatewaySet.item.transitGatewayId",
                    not(contains(transitGatewayId)));

        for (String id : List.of(first, second)) {
            ec2()
                .formParam("Action", "DeleteTransitGateway")
                .formParam("TransitGatewayId", id)
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }
    }
}
