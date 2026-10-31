package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Inject
    Ec2Service service;

    @Test
    void createVpcWithAnAmazonProvidedBlockReturnsOneAndKeepsIt() throws UnknownHostException {
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
        assertEquals(canonicalText(created), created);

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

    /**
     * CreateVpc and AssociateVpcCidrBlock draw their block from one generator, and it comes back in
     * RFC 5952 text: {@code 2600:1f18:a3f:1200::/56}, not {@code 2600:1f18:0a3f:1200::/56}, and
     * {@code 2600:1f18:a3f::/56}, not {@code 2600:1f18:a3f:0::/56}. A random group starts with a
     * zero digit in about one block in eight, but the fourth group is all zeros in only one in 256,
     * so the test draws 4096 blocks in process: the chance that none has an all-zero fourth group
     * is about one in ten million. They are all drawn on one VPC, so the test costs the same
     * however many resources the app already holds. That leans on Floci not enforcing AWS's quota
     * of five IPv6 blocks per VPC (adjustable to 50); if it ever does, spread the draws over VPCs.
     */
    @Test
    void amazonProvidedBlocksAreWrittenInCanonicalForm() throws UnknownHostException {
        String vpcId = service.createVpc("us-east-1", "10.97.0.0/16", false).getVpcId();
        for (int i = 0; i < 4096; i++) {
            String block = service.associateAmazonProvidedIpv6CidrBlock("us-east-1", vpcId).getIpv6CidrBlock();
            assertEquals(canonicalText(block), block);
        }
        service.deleteVpc("us-east-1", vpcId);
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

    /**
     * The RFC 5952 text of the /56 that {@code block} names. Every byte past the seventh is zero
     * in a /56, so its text is the first four groups without leading zeros, less any zero groups
     * at their end, then "::": the zero groups that end the address are always the longest run,
     * the one RFC 5952 compresses.
     */
    private static String canonicalText(String block) throws UnknownHostException {
        byte[] network = Arrays.copyOf(InetAddress.ofLiteral(block.split("/")[0]).getAddress(), 7);
        String[] groups = InetAddress.getByAddress(Arrays.copyOf(network, 16)).getHostAddress().split(":");
        int kept = 4;
        while (kept > 0 && groups[kept - 1].equals("0")) {
            kept--;
        }
        return String.join(":", Arrays.copyOf(groups, kept)) + "::/56";
    }
}
