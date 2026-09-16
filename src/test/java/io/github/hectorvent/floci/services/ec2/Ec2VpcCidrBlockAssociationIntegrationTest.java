package io.github.hectorvent.floci.services.ec2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * AssociateVpcCidrBlock returned cidrBlockState as a bare string
 * ({@code <cidrBlockState>associated</cidrBlockState>}) instead of the nested shape the real API
 * uses ({@code <cidrBlockState><state>associated</state></cidrBlockState>}), the same shape
 * DescribeVpcs already returned for the identical association. The AWS SDK waiter that
 * terraform-provider-aws uses for aws_vpc_ipv4_cidr_block_association parses the nested form, so
 * it never saw a valid state and polled DescribeVpcs until it gave up, hanging `terraform apply`
 * on any secondary_cidr_blocks entry.
 */
@QuarkusTest
class Ec2VpcCidrBlockAssociationIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    @Test
    void associateVpcCidrBlockReturnsNestedCidrBlockState() {
        String vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.94.0.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        String associationId = given()
            .formParam("Action", "AssociateVpcCidrBlock")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.95.0.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AssociateVpcCidrBlockResponse.cidrBlockAssociation.cidrBlock", equalTo("10.95.0.0/24"))
            // The regression: this must be the nested element, not a bare string value.
            .body("AssociateVpcCidrBlockResponse.cidrBlockAssociation.cidrBlockState.state",
                    equalTo("associated"))
            .extract().path("AssociateVpcCidrBlockResponse.cidrBlockAssociation.associationId");

        // Same association, read back through DescribeVpcs, must agree on both shape and state.
        given()
            .formParam("Action", "DescribeVpcs")
            .formParam("VpcId.1", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcsResponse.vpcSet.item.cidrBlockAssociationSet.item.find { "
                    + "it.associationId == '" + associationId + "' }.cidrBlockState.state",
                    equalTo("associated"));
    }
}
