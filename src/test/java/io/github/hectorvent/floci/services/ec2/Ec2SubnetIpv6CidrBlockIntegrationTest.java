package io.github.hectorvent.floci.services.ec2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * CreateSubnet accepted an Ipv6CidrBlock parameter and silently dropped it: the response always
 * carried an empty ipv6CidrBlockAssociationSet, so a Terraform aws_subnet with an ipv6_cidr_block
 * never converged the same way the VPC-level case fixed in
 * {@link Ec2VpcIpv6CidrBlockIntegrationTest} needed fixing.
 */
@QuarkusTest
class Ec2SubnetIpv6CidrBlockIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    @Test
    void createSubnetWithAnIpv6CidrBlockReturnsItAndKeepsIt() {
        String vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.94.0.0/16")
            .formParam("AmazonProvidedIpv6CidrBlock", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        String subnetId = given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.94.1.0/24")
            .formParam("Ipv6CidrBlock", "2600:1f18:1::/64")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateSubnetResponse.subnet.ipv6CidrBlockAssociationSet.item.ipv6CidrBlock",
                    equalTo("2600:1f18:1::/64"))
            .body("CreateSubnetResponse.subnet.ipv6CidrBlockAssociationSet.item.ipv6CidrBlockState.state",
                    equalTo("associated"))
            .extract().path("CreateSubnetResponse.subnet.subnetId");

        given()
            .formParam("Action", "DescribeSubnets")
            .formParam("SubnetId.1", subnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSubnetsResponse.subnetSet.item.ipv6CidrBlockAssociationSet.item.ipv6CidrBlock",
                    equalTo("2600:1f18:1::/64"));
    }

    @Test
    void createSubnetWithoutAnIpv6CidrBlockHasNoIpv6Association() {
        String vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.95.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.95.1.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<ipv6CidrBlock>")));
    }

    @Test
    void createSubnetRejectsAnIpv6CidrBlockWhenTheVpcHasNone() {
        String vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.96.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.96.1.0/24")
            .formParam("Ipv6CidrBlock", "2600:1f18:2::/64")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidParameterValue"));
    }
}
