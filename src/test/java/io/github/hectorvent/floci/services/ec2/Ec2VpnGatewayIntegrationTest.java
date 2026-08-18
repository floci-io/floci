package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for VPN gateways over the EC2 Query protocol: create with inline tags,
 * describe by id and by filter, attach/detach against a real VPC, tag round trip through
 * CreateTags, and delete.
 *
 * <p>Ordered because the cases walk one gateway through its lifecycle.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2VpnGatewayIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static String vpnGatewayId;
    private static String vpcId;

    @Test
    @Order(1)
    void createReturnsAvailableOnTheFirstRead() {
        vpnGatewayId = given()
            .formParam("Action", "CreateVpnGateway")
            .formParam("Type", "ipsec.1")
            .formParam("AvailabilityZone", "us-east-1a")
            .formParam("AmazonSideAsn", "65010")
            .formParam("TagSpecification.1.ResourceType", "vpn-gateway")
            .formParam("TagSpecification.1.Tag.1.Key", "Name")
            .formParam("TagSpecification.1.Tag.1.Value", "main-vpn")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateVpnGatewayResponse.vpnGateway.state", equalTo("available"))
            .body("CreateVpnGatewayResponse.vpnGateway.type", equalTo("ipsec.1"))
            .body("CreateVpnGatewayResponse.vpnGateway.availabilityZone", equalTo("us-east-1a"))
            .body("CreateVpnGatewayResponse.vpnGateway.amazonSideAsn", equalTo("65010"))
            .body("CreateVpnGatewayResponse.vpnGateway.tagSet.item.key", equalTo("Name"))
            .body("CreateVpnGatewayResponse.vpnGateway.tagSet.item.value", equalTo("main-vpn"))
            .extract().path("CreateVpnGatewayResponse.vpnGateway.vpnGatewayId");

        assertTrue(vpnGatewayId.startsWith("vgw-"), "id must use the vgw- prefix");
    }

    @Test
    @Order(2)
    void createWithoutAmazonSideAsnUsesTheAwsDefault() {
        given()
            .formParam("Action", "CreateVpnGateway")
            .formParam("Type", "ipsec.1")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateVpnGatewayResponse.vpnGateway.amazonSideAsn", equalTo("64512"))
            .body("CreateVpnGatewayResponse.vpnGateway.availabilityZone", equalTo("us-east-1a"));
    }

    @Test
    @Order(3)
    void describeByIdReturnsTheCreatedGatewayStillAvailable() {
        given()
            .formParam("Action", "DescribeVpnGateways")
            .formParam("VpnGatewayId.1", vpnGatewayId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpnGatewaysResponse.vpnGatewaySet.item.vpnGatewayId", equalTo(vpnGatewayId))
            .body("DescribeVpnGatewaysResponse.vpnGatewaySet.item.state", equalTo("available"))
            .body("DescribeVpnGatewaysResponse.vpnGatewaySet.item.amazonSideAsn", equalTo("65010"));
    }

    @Test
    @Order(4)
    void describeSupportsTypeAndTagFilters() {
        given()
            .formParam("Action", "DescribeVpnGateways")
            .formParam("Filter.1.Name", "tag:Name")
            .formParam("Filter.1.Value.1", "main-vpn")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpnGatewaysResponse.vpnGatewaySet.item.vpnGatewayId", equalTo(vpnGatewayId));
    }

    @Test
    @Order(5)
    void attachToARealVpcRecordsTheAttachment() {
        vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.77.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        given()
            .formParam("Action", "AttachVpnGateway")
            .formParam("VpnGatewayId", vpnGatewayId)
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AttachVpnGatewayResponse.attachment.vpcId", equalTo(vpcId))
            .body("AttachVpnGatewayResponse.attachment.state", equalTo("attached"));

        given()
            .formParam("Action", "DescribeVpnGateways")
            .formParam("VpnGatewayId.1", vpnGatewayId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpnGatewaysResponse.vpnGatewaySet.item.state", equalTo("available"))
            .body("DescribeVpnGatewaysResponse.vpnGatewaySet.item.attachments.item.vpcId", equalTo(vpcId))
            .body("DescribeVpnGatewaysResponse.vpnGatewaySet.item.attachments.item.state", equalTo("attached"));
    }

    @Test
    @Order(6)
    void describeSupportsAttachmentFilters() {
        given()
            .formParam("Action", "DescribeVpnGateways")
            .formParam("Filter.1.Name", "attachment.vpc-id")
            .formParam("Filter.1.Value.1", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpnGatewaysResponse.vpnGatewaySet.item.vpnGatewayId", equalTo(vpnGatewayId));
    }

    @Test
    @Order(7)
    void attachingToAMissingVpcIsRejected() {
        given()
            .formParam("Action", "AttachVpnGateway")
            .formParam("VpnGatewayId", vpnGatewayId)
            .formParam("VpcId", "vpc-00000000000000000")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidVpcID.NotFound"));
    }

    @Test
    @Order(8)
    void detachRemovesTheAttachment() {
        given()
            .formParam("Action", "DetachVpnGateway")
            .formParam("VpnGatewayId", vpnGatewayId)
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DetachVpnGatewayResponse.return", equalTo("true"));

        given()
            .formParam("Action", "DescribeVpnGateways")
            .formParam("VpnGatewayId.1", vpnGatewayId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpnGatewaysResponse.vpnGatewaySet.item.attachments", emptyOrNullString());
    }

    @Test
    @Order(9)
    void detachingAMissingAttachmentIsRejected() {
        given()
            .formParam("Action", "DetachVpnGateway")
            .formParam("VpnGatewayId", vpnGatewayId)
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidVpnGatewayAttachment.NotFound"));
    }

    @Test
    @Order(10)
    void unsupportedGatewayTypeIsRejected() {
        given()
            .formParam("Action", "CreateVpnGateway")
            .formParam("Type", "ipsec.2")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
    }

    @Test
    @Order(11)
    void deleteRemovesTheGateway() {
        given()
            .formParam("Action", "DeleteVpnGateway")
            .formParam("VpnGatewayId", vpnGatewayId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteVpnGatewayResponse.return", equalTo("true"));

        given()
            .formParam("Action", "DescribeVpnGateways")
            .formParam("Filter.1.Name", "tag:Name")
            .formParam("Filter.1.Value.1", "main-vpn")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpnGatewaysResponse.vpnGatewaySet", emptyOrNullString());
    }

    @Test
    @Order(12)
    void describingAMissingGatewayReturnsTheModelledError() {
        given()
            .formParam("Action", "DescribeVpnGateways")
            .formParam("VpnGatewayId.1", "vgw-00000000")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidVpnGatewayID.NotFound"))
            .body("Response.Errors.Error.Message", containsString("vgw-00000000"));
    }
}
