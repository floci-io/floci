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
 * VPC attachments over the EC2 Query protocol.
 *
 * <p>The two describes are deliberately both covered: the VPC-specific one carries the subnets and
 * options, while the resource-agnostic one drops those, types the VPC as a resource, and is the
 * only place the route table association appears. The shapes were captured from a live account,
 * including modify omitting the tagSet and delete omitting the subnets as well.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2TransitGatewayVpcAttachmentIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static String transitGatewayId;
    private static String vpcId;
    private static String subnetA;
    private static String subnetB;
    private static String attachmentId;

    @Test
    @Order(1)
    void createTheGatewayVpcAndSubnets() {
        transitGatewayId = given()
            .formParam("Action", "CreateTransitGateway")
            .formParam("Description", "attachment host")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateTransitGatewayResponse.transitGateway.transitGatewayId");

        vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.80.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        subnetA = createSubnet("10.80.1.0/24", "us-east-1a");
        subnetB = createSubnet("10.80.2.0/24", "us-east-1b");
    }

    private String createSubnet(String cidr, String zone) {
        return given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", cidr)
            .formParam("AvailabilityZone", zone)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateSubnetResponse.subnet.subnetId");
    }

    @Test
    @Order(2)
    void createAttachmentReturnsItsOwnOptionDefaults() {
        attachmentId = given()
            .formParam("Action", "CreateTransitGatewayVpcAttachment")
            .formParam("TransitGatewayId", transitGatewayId)
            .formParam("VpcId", vpcId)
            .formParam("SubnetIds.1", subnetA)
            // The CLI sends the plural wire name for this action: its TagSpecifications member
            // carries no locationName, unlike every neighbouring create.
            .formParam("TagSpecifications.1.ResourceType", "transit-gateway-attachment")
            .formParam("TagSpecifications.1.Tag.1.Key", "Name")
            .formParam("TagSpecifications.1.Tag.1.Value", "hub-attach")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("CreateTransitGatewayVpcAttachmentResponse.transitGatewayVpcAttachment.state",
                    equalTo("available"))
            .body("CreateTransitGatewayVpcAttachmentResponse.transitGatewayVpcAttachment.vpcId", equalTo(vpcId))
            .body("CreateTransitGatewayVpcAttachmentResponse.transitGatewayVpcAttachment.subnetIds.item",
                    equalTo(subnetA))
            // The attachment's default, not the gateway's, which is disabled.
            .body("CreateTransitGatewayVpcAttachmentResponse.transitGatewayVpcAttachment.options"
                    + ".securityGroupReferencingSupport", equalTo("enable"))
            .body("CreateTransitGatewayVpcAttachmentResponse.transitGatewayVpcAttachment.options.ipv6Support",
                    equalTo("disable"))
            .body("CreateTransitGatewayVpcAttachmentResponse.transitGatewayVpcAttachment.tagSet.item.value",
                    equalTo("hub-attach"))
            .extract()
            .path("CreateTransitGatewayVpcAttachmentResponse.transitGatewayVpcAttachment"
                    + ".transitGatewayAttachmentId");
    }

    @Test
    @Order(3)
    void theTwoDescribesServeDifferentShapesOfTheSameAttachment() {
        given()
            .formParam("Action", "DescribeTransitGatewayVpcAttachments")
            .formParam("TransitGatewayAttachmentIds.1", attachmentId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("DescribeTransitGatewayVpcAttachmentsResponse.transitGatewayVpcAttachments.item.vpcId",
                    equalTo(vpcId))
            .body("DescribeTransitGatewayVpcAttachmentsResponse.transitGatewayVpcAttachments.item"
                    + ".subnetIds.item", equalTo(subnetA));

        // The resource-agnostic form: no subnets or options, the VPC as a typed resource, and the
        // association with the gateway's default route table.
        String generic = given()
            .formParam("Action", "DescribeTransitGatewayAttachments")
            .formParam("TransitGatewayAttachmentIds.1", attachmentId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("DescribeTransitGatewayAttachmentsResponse.transitGatewayAttachments.item.resourceType",
                    equalTo("vpc"))
            .body("DescribeTransitGatewayAttachmentsResponse.transitGatewayAttachments.item.resourceId",
                    equalTo(vpcId))
            .body("DescribeTransitGatewayAttachmentsResponse.transitGatewayAttachments.item.association.state",
                    equalTo("associated"))
            .body("DescribeTransitGatewayAttachmentsResponse.transitGatewayAttachments.item"
                    + ".transitGatewayOwnerId", equalTo("000000000000"))
            .body("DescribeTransitGatewayAttachmentsResponse.transitGatewayAttachments.item.resourceOwnerId",
                    equalTo("000000000000"))
            .extract().asString();

        assertThat(generic, containsString("tgw-rtb-"));
        assertThat(generic, not(containsString("subnetIds")));
    }

    @Test
    @Order(4)
    void modifyAddsASubnetAndOmitsTheTagSet() {
        String body = given()
            .formParam("Action", "ModifyTransitGatewayVpcAttachment")
            .formParam("TransitGatewayAttachmentId", attachmentId)
            .formParam("AddSubnetIds.1", subnetB)
            .formParam("Options.DnsSupport", "disable")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("ModifyTransitGatewayVpcAttachmentResponse.transitGatewayVpcAttachment.options.dnsSupport",
                    equalTo("disable"))
            .extract().asString();

        assertThat(body, containsString(subnetB));
        assertThat(body, not(containsString("tagSet")));
    }

    @Test
    @Order(5)
    void aSecondAttachmentForTheSameVpcIsRejected() {
        given()
            .formParam("Action", "CreateTransitGatewayVpcAttachment")
            .formParam("TransitGatewayId", transitGatewayId)
            .formParam("VpcId", vpcId)
            .formParam("SubnetIds.1", subnetA)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("DuplicateTransitGatewayAttachment"));
    }

    @Test
    @Order(6)
    void theGatewayCannotBeDeletedWhileAttachedAndTheAttachmentDeletesCleanly() {
        given()
            .formParam("Action", "DeleteTransitGateway")
            .formParam("TransitGatewayId", transitGatewayId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("IncorrectState"));

        String body = given()
            .formParam("Action", "DeleteTransitGatewayVpcAttachment")
            .formParam("TransitGatewayAttachmentId", attachmentId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("DeleteTransitGatewayVpcAttachmentResponse.transitGatewayVpcAttachment.state",
                    equalTo("deleted"))
            .extract().asString();

        // Delete drops both the subnets and the tags from the echo.
        assertThat(body, not(containsString("subnetIds")));
        assertThat(body, not(containsString("tagSet")));

        given()
            .formParam("Action", "DeleteTransitGateway")
            .formParam("TransitGatewayId", transitGatewayId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("DeleteTransitGatewayResponse.transitGateway.state", equalTo("deleted"));
    }
}
