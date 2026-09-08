package io.github.hectorvent.floci.services.ec2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * DescribeVpcs ignored the {@code cidr-block-association.*} and {@code ipv6-cidr-block-association.*}
 * filters (unrecognized filter names fell through to the catch-all {@code default -> true} in
 * {@code matchesFilter}, so every VPC in the account matched regardless of the requested
 * association). terraform-provider-aws's waiter for aws_vpc_ipv4_cidr_block_association polls
 * DescribeVpcs filtered by {@code cidr-block-association.association-id} and asserts exactly one
 * VPC comes back; with more than one VPC in the account the assertion failed with a "too many
 * results" error that the SDK treats as not-found-yet, so the waiter retried until it timed out,
 * hanging `terraform apply` on any secondary_cidr_blocks entry.
 */
@QuarkusTest
class Ec2VpcCidrBlockAssociationFilterIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260908/us-east-1/ec2/aws4_request";

    @Test
    void describeVpcsFiltersByCidrBlockAssociationId() {
        String vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.96.0.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        // A second, unrelated VPC must not match the first VPC's association filter.
        given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.97.0.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String associationId = given()
            .formParam("Action", "AssociateVpcCidrBlock")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.98.0.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("AssociateVpcCidrBlockResponse.cidrBlockAssociation.associationId");

        // This is the exact filter terraform-provider-aws's waiter sends: it must return only
        // the one VPC owning that association, not every VPC in the account.
        given()
            .formParam("Action", "DescribeVpcs")
            .formParam("Filter.1.Name", "cidr-block-association.association-id")
            .formParam("Filter.1.Value.1", associationId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcsResponse.vpcSet.item.size()", equalTo(1))
            .body("DescribeVpcsResponse.vpcSet.item.vpcId", equalTo(vpcId));
    }

    @Test
    void describeVpcsFiltersByCidrBlockAssociationCidrBlock() {
        String vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.99.0.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        given()
            .formParam("Action", "AssociateVpcCidrBlock")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.100.0.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeVpcs")
            .formParam("Filter.1.Name", "cidr-block-association.cidr-block")
            .formParam("Filter.1.Value.1", "10.100.0.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcsResponse.vpcSet.item.size()", equalTo(1))
            .body("DescribeVpcsResponse.vpcSet.item.vpcId", equalTo(vpcId));
    }

    @Test
    void describeVpcsFiltersByCidrBlockAssociationState() {
        String vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.101.0.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        // A second, unrelated VPC must not match on state alone either.
        given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.102.0.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "AssociateVpcCidrBlock")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.103.0.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeVpcs")
            .formParam("Filter.1.Name", "cidr-block-association.state")
            .formParam("Filter.1.Value.1", "associated")
            .formParam("Filter.2.Name", "cidr-block-association.cidr-block")
            .formParam("Filter.2.Value.1", "10.103.0.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcsResponse.vpcSet.item.size()", equalTo(1))
            .body("DescribeVpcsResponse.vpcSet.item.vpcId", equalTo(vpcId));
    }

    @Test
    void describeVpcsFiltersByIpv6CidrBlockAssociationId() {
        String vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.104.0.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        // A second, unrelated VPC must not match the first VPC's IPv6 association filter.
        given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.105.0.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String associationId = given()
            .formParam("Action", "AssociateVpcCidrBlock")
            .formParam("VpcId", vpcId)
            .formParam("AmazonProvidedIpv6CidrBlock", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("AssociateVpcCidrBlockResponse.ipv6CidrBlockAssociation.associationId");

        given()
            .formParam("Action", "DescribeVpcs")
            .formParam("Filter.1.Name", "ipv6-cidr-block-association.association-id")
            .formParam("Filter.1.Value.1", associationId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcsResponse.vpcSet.item.size()", equalTo(1))
            .body("DescribeVpcsResponse.vpcSet.item.vpcId", equalTo(vpcId));
    }

    @Test
    void describeVpcsFiltersByIpv6CidrBlockAssociationCidrBlock() {
        String vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.106.0.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        String ipv6CidrBlock = given()
            .formParam("Action", "AssociateVpcCidrBlock")
            .formParam("VpcId", vpcId)
            .formParam("AmazonProvidedIpv6CidrBlock", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("AssociateVpcCidrBlockResponse.ipv6CidrBlockAssociation.ipv6CidrBlock");

        given()
            .formParam("Action", "DescribeVpcs")
            .formParam("Filter.1.Name", "ipv6-cidr-block-association.ipv6-cidr-block")
            .formParam("Filter.1.Value.1", ipv6CidrBlock)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcsResponse.vpcSet.item.size()", equalTo(1))
            .body("DescribeVpcsResponse.vpcSet.item.vpcId", equalTo(vpcId));
    }

    @Test
    void describeVpcsFiltersByIpv6CidrBlockAssociationState() {
        String vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.107.0.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        // A second, unrelated VPC must not match on state alone either.
        given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.108.0.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String ipv6CidrBlock = given()
            .formParam("Action", "AssociateVpcCidrBlock")
            .formParam("VpcId", vpcId)
            .formParam("AmazonProvidedIpv6CidrBlock", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("AssociateVpcCidrBlockResponse.ipv6CidrBlockAssociation.ipv6CidrBlock");

        given()
            .formParam("Action", "DescribeVpcs")
            .formParam("Filter.1.Name", "ipv6-cidr-block-association.state")
            .formParam("Filter.1.Value.1", "associated")
            .formParam("Filter.2.Name", "ipv6-cidr-block-association.ipv6-cidr-block")
            .formParam("Filter.2.Value.1", ipv6CidrBlock)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcsResponse.vpcSet.item.size()", equalTo(1))
            .body("DescribeVpcsResponse.vpcSet.item.vpcId", equalTo(vpcId));
    }
}
