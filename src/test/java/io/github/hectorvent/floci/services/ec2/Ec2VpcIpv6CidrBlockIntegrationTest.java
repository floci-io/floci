package io.github.hectorvent.floci.services.ec2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * CreateVpc ignored AmazonProvidedIpv6CidrBlock and no response carried an
 * ipv6CidrBlockAssociationSet at all, so aws_vpc's assign_generated_ipv6_cidr_block never
 * converged: Terraform asks for a block, reads none back, and plans the same change forever.
 *
 * <p>The prefix itself is Amazon's to choose and a client cannot predict it, so these tests
 * assert the shape and the round trip rather than a literal block.
 */
@QuarkusTest
class Ec2VpcIpv6CidrBlockIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    /** A /56 out of Amazon's pool, in the notation the API returns. */
    private static final String IPV6_CIDR = "[0-9a-f:]+::/56";

    @Test
    void createVpcWithAnAmazonProvidedBlockReturnsOneAndKeepsIt() {
        String vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.91.0.0/16")
            .formParam("AmazonProvidedIpv6CidrBlock", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateVpcResponse.vpc.ipv6CidrBlockAssociationSet.item.ipv6CidrBlock",
                    matchesRegex(IPV6_CIDR))
            .body("CreateVpcResponse.vpc.ipv6CidrBlockAssociationSet.item.ipv6CidrBlockState.state",
                    equalTo("associated"))
            .body("CreateVpcResponse.vpc.ipv6CidrBlockAssociationSet.item.ipv6Pool", equalTo("Amazon"))
            .body("CreateVpcResponse.vpc.ipv6CidrBlockAssociationSet.item.networkBorderGroup",
                    equalTo("us-east-1"))
            .extract().path("CreateVpcResponse.vpc.vpcId");

        // The convergence half: the same block has to come back on a later read, unchanged.
        String created = given()
            .formParam("Action", "DescribeVpcs")
            .formParam("VpcId.1", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcsResponse.vpcSet.item.ipv6CidrBlockAssociationSet.item.ipv6CidrBlock",
                    matchesRegex(IPV6_CIDR))
            .extract().path("DescribeVpcsResponse.vpcSet.item.ipv6CidrBlockAssociationSet.item.ipv6CidrBlock");

        given()
            .formParam("Action", "DescribeVpcs")
            .formParam("VpcId.1", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcsResponse.vpcSet.item.ipv6CidrBlockAssociationSet.item.ipv6CidrBlock",
                    equalTo(created));
    }

    @Test
    void aVpcThatDidNotAskForOneHasNoIpv6Block() {
        given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.92.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            // The element is present but empty, no association inside it.
            .body(not(containsString("<ipv6CidrBlock>")));
    }

    /**
     * Turning assign_generated_ipv6_cidr_block on for a VPC that already exists goes through
     * AssociateVpcCidrBlock, not CreateVpc: the same flag on a different operation.
     */
    @Test
    void anExistingVpcCanBeGivenAnAmazonProvidedBlock() {
        String vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.93.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        String associationId = given()
            .formParam("Action", "AssociateVpcCidrBlock")
            .formParam("VpcId", vpcId)
            .formParam("AmazonProvidedIpv6CidrBlock", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AssociateVpcCidrBlockResponse.ipv6CidrBlockAssociation.ipv6CidrBlock",
                    matchesRegex(IPV6_CIDR))
            .extract().path("AssociateVpcCidrBlockResponse.ipv6CidrBlockAssociation.associationId");

        given()
            .formParam("Action", "DescribeVpcs")
            .formParam("VpcId.1", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcsResponse.vpcSet.item.ipv6CidrBlockAssociationSet.item.associationId",
                    equalTo(associationId))
            // The IPv4 set is a separate member and must not have gained an entry.
            .body("DescribeVpcsResponse.vpcSet.item.cidrBlockAssociationSet.item.cidrBlock",
                    equalTo("10.93.0.0/16"));

        given()
            .formParam("Action", "DisassociateVpcCidrBlock")
            .formParam("AssociationId", associationId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeVpcs")
            .formParam("VpcId.1", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<ipv6CidrBlock>")))
            .body("DescribeVpcsResponse.vpcSet.item.cidrBlock", not(emptyOrNullString()));
    }
}
