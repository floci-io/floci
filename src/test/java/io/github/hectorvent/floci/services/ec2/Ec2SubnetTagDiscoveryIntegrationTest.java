package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

/**
 * Pins the shape a tag-based discovery sweep uses on subnets: create carrying
 * TagSpecification, then find that one subnet by its CIDR and read the tags back.
 *
 * <p>The two halves fail together in a way that reads as one bug. DescribeSubnets
 * used to ignore {@code Name=cidr-block} and answer with every subnet in the
 * account, so a caller taking the first element of the result got whichever
 * default subnet sorted first — an untagged one. That looks exactly like the
 * subnet having been created without its tags, which is why this test asserts
 * the filter narrows to one subnet before it asserts anything about tags.
 *
 * <p>The request bodies mirror what the terraform/tofu AWS provider sends for an
 * {@code aws_subnet} with a tags map (hashicorp/aws 6.58.0):
 * {@code TagSpecification.1.ResourceType=subnet} with numbered Tag pairs.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2SubnetTagDiscoveryIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static final String MARKED_CIDR = "10.71.1.0/24";
    private static final String SIBLING_CIDR = "10.71.2.0/24";

    private static String vpcId;
    private static String markedSubnetId;

    @Test
    @Order(1)
    void createSubnetsOneMarkedOneNot() {
        vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.71.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        markedSubnetId = given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", MARKED_CIDR)
            .formParam("AvailabilityZone", "us-east-1a")
            .formParam("TagSpecification.1.ResourceType", "subnet")
            .formParam("TagSpecification.1.Tag.1.Key", "Name")
            .formParam("TagSpecification.1.Tag.1.Value", "app")
            .formParam("TagSpecification.1.Tag.2.Key", "team")
            .formParam("TagSpecification.1.Tag.2.Value", "platform")
            .formParam("TagSpecification.1.Tag.3.Key", "marker:estate")
            .formParam("TagSpecification.1.Tag.3.Value", "discovery")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateSubnetResponse.subnet.subnetId");

        // A sibling in the same VPC, deliberately untagged: an unfiltered
        // DescribeSubnets would hand a caller this one just as readily.
        given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", SIBLING_CIDR)
            .formParam("AvailabilityZone", "us-east-1b")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void createSubnetResponseCarriesTheTagsItWasGiven() {
        given()
            .formParam("Action", "DescribeSubnets")
            .formParam("SubnetId.1", markedSubnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSubnetsResponse.subnetSet.item.tagSet.item.key",
                    hasItems("Name", "team", "marker:estate"))
            .body("DescribeSubnetsResponse.subnetSet.item.tagSet.item.value",
                    hasItems("app", "platform", "discovery"));
    }

    @Test
    @Order(3)
    void cidrBlockFilterNarrowsToTheOneSubnetAndKeepsItsTags() {
        // The filter has to narrow. Without it the account-wide result puts an
        // untagged default subnet first, and a caller reading the first element
        // reports the marked subnet as untagged.
        given()
            .formParam("Action", "DescribeSubnets")
            .formParam("Filter.1.Name", "cidr-block")
            .formParam("Filter.1.Value.1", MARKED_CIDR)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSubnetsResponse.subnetSet.item.subnetId", equalTo(markedSubnetId))
            .body("DescribeSubnetsResponse.subnetSet.item.cidrBlock", equalTo(MARKED_CIDR))
            .body("DescribeSubnetsResponse.subnetSet.item.tagSet.item.key",
                    hasItems("Name", "team", "marker:estate"));
    }

    @Test
    @Order(4)
    void cidrBlockFilterCombinesWithVpcIdAndExcludesTheSibling() {
        given()
            .formParam("Action", "DescribeSubnets")
            .formParam("Filter.1.Name", "vpc-id")
            .formParam("Filter.1.Value.1", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSubnetsResponse.subnetSet.item.cidrBlock",
                    hasItems(MARKED_CIDR, SIBLING_CIDR));

        given()
            .formParam("Action", "DescribeSubnets")
            .formParam("Filter.1.Name", "vpc-id")
            .formParam("Filter.1.Value.1", vpcId)
            .formParam("Filter.2.Name", "cidr-block")
            .formParam("Filter.2.Value.1", SIBLING_CIDR)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSubnetsResponse.subnetSet.item.cidrBlock", equalTo(SIBLING_CIDR));
    }

    @Test
    @Order(5)
    void tagFilterFindsTheMarkedSubnetOnItsOwn() {
        given()
            .formParam("Action", "DescribeSubnets")
            .formParam("Filter.1.Name", "tag:marker:estate")
            .formParam("Filter.1.Value.1", "discovery")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSubnetsResponse.subnetSet.item.subnetId", equalTo(markedSubnetId));
    }
}
