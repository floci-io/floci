package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Transit gateways over the EC2 Query protocol: creation with and without options, the read-back
 * shape, modification, and deletion.
 *
 * <p>The response shapes asserted here were captured from a live AWS account, including the one
 * that is not obvious from the API reference: create and describe carry a {@code tagSet}, while
 * modify and delete return the same gateway without one.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2TransitGatewayIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static String transitGatewayId;
    private static String defaultRouteTableId;

    @Test
    @Order(1)
    void createReturnsTheGatewayWithAwsDefaultsAndItsTags() {
        transitGatewayId = given()
            .formParam("Action", "CreateTransitGateway")
            .formParam("Description", "hub")
            .formParam("TagSpecification.1.ResourceType", "transit-gateway")
            .formParam("TagSpecification.1.Tag.1.Key", "env")
            .formParam("TagSpecification.1.Tag.1.Value", "prod")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("CreateTransitGatewayResponse.transitGateway.state", equalTo("available"))
            .body("CreateTransitGatewayResponse.transitGateway.description", equalTo("hub"))
            .body("CreateTransitGatewayResponse.transitGateway.options.amazonSideAsn", equalTo("64512"))
            .body("CreateTransitGatewayResponse.transitGateway.options.dnsSupport", equalTo("enable"))
            .body("CreateTransitGatewayResponse.transitGateway.options.securityGroupReferencingSupport",
                    equalTo("disable"))
            .body("CreateTransitGatewayResponse.transitGateway.tagSet.item.key", equalTo("env"))
            .extract().path("CreateTransitGatewayResponse.transitGateway.transitGatewayId");

        defaultRouteTableId = given()
            .formParam("Action", "DescribeTransitGateways")
            .formParam("TransitGatewayIds.1", transitGatewayId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("DescribeTransitGatewaysResponse.transitGatewaySet.item.transitGatewayArn",
                    startsWith("arn:aws:ec2:"))
            .body("DescribeTransitGatewaysResponse.transitGatewaySet.item.tagSet.item.value", equalTo("prod"))
            .extract()
            .path("DescribeTransitGatewaysResponse.transitGatewaySet.item.options.associationDefaultRouteTableId");

        // The default route table is minted during creation, so its id is already reported back.
        assertTrue(defaultRouteTableId.startsWith("tgw-rtb-"),
                "expected a default route table id, got " + defaultRouteTableId);
    }

    @Test
    @Order(2)
    void createHonoursExplicitOptions() {
        String body = given()
            .formParam("Action", "CreateTransitGateway")
            .formParam("Options.AmazonSideAsn", "65001")
            .formParam("Options.DnsSupport", "disable")
            .formParam("Options.DefaultRouteTableAssociation", "disable")
            .formParam("Options.DefaultRouteTablePropagation", "disable")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("CreateTransitGatewayResponse.transitGateway.options.amazonSideAsn", equalTo("65001"))
            .body("CreateTransitGatewayResponse.transitGateway.options.dnsSupport", equalTo("disable"))
            .extract().asString();

        // Opting out of both defaults means there is no table to point at, and AWS omits the
        // element rather than sending it empty. Asserted on the raw body because a missing path
        // reads back as an empty node object rather than a null string.
        assertThat(body, not(containsString("associationDefaultRouteTableId")));
        assertThat(body, not(containsString("propagationDefaultRouteTableId")));
    }

    @Test
    @Order(3)
    void modifyAppliesOptionsAndReturnsNoTagSet() {
        String body = given()
            .formParam("Action", "ModifyTransitGateway")
            .formParam("TransitGatewayId", transitGatewayId)
            .formParam("Description", "hub-modified")
            .formParam("Options.DnsSupport", "disable")
            .formParam("Options.AddTransitGatewayCidrBlocks.1", "10.100.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("ModifyTransitGatewayResponse.transitGateway.description", equalTo("hub-modified"))
            .body("ModifyTransitGatewayResponse.transitGateway.options.dnsSupport", equalTo("disable"))
            // A plain string list: the CIDR is the item's own text, which is what the AWS CLI
            // parses back into TransitGatewayCidrBlocks[0].
            .body("ModifyTransitGatewayResponse.transitGateway.options.transitGatewayCidrBlocks.item",
                    equalTo("10.100.0.0/16"))
            .extract().asString();

        assertThat(body, not(containsString("tagSet")));

        // The tags are still there; modify just does not echo them.
        given()
            .formParam("Action", "DescribeTransitGateways")
            .formParam("TransitGatewayIds.1", transitGatewayId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("DescribeTransitGatewaysResponse.transitGatewaySet.item.tagSet.item.key", equalTo("env"));
    }

    @Test
    @Order(4)
    void describeRejectsAnUnknownGateway() {
        given()
            .formParam("Action", "DescribeTransitGateways")
            .formParam("TransitGatewayIds.1", "tgw-0123456789abcdef0")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidTransitGatewayID.NotFound"));
    }

    @Test
    @Order(5)
    void deleteRemovesTheGateway() {
        given()
            .formParam("Action", "DeleteTransitGateway")
            .formParam("TransitGatewayId", transitGatewayId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("DeleteTransitGatewayResponse.transitGateway.state", equalTo("deleted"))
            .body("DeleteTransitGatewayResponse.transitGateway.transitGatewayId", equalTo(transitGatewayId));

        given()
            .formParam("Action", "DescribeTransitGateways")
            .formParam("TransitGatewayIds.1", transitGatewayId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidTransitGatewayID.NotFound"));

        // The gateway created in the options test is untouched by this delete.
        given()
            .formParam("Action", "DescribeTransitGateways")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("DescribeTransitGatewaysResponse.transitGatewaySet.item.options.amazonSideAsn",
                    not(emptyOrNullString()));
    }
}
