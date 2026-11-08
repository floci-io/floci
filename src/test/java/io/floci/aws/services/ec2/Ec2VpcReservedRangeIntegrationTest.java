package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * The VPC User Guide rules out 0.0.0.0/8, 127.0.0.0/8, 169.254.0.0/16 and 224.0.0.0/4 for any VPC
 * block. CreateVpc and AssociateVpcCidrBlock answer a block from one of them with InvalidVpc.Range.
 */
@QuarkusTest
class Ec2VpcReservedRangeIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20261013/us-east-1/ec2/aws4_request";

    @Test
    void createVpcRejectsALinkLocalBlock() {
        given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "169.254.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidVpc.Range"))
            .body("Response.Errors.Error.Message", equalTo("The CIDR '169.254.0.0/16' is invalid."));
    }

    @Test
    void associateVpcCidrBlockRejectsALinkLocalBlock() {
        String vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.96.0.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        given()
            .formParam("Action", "AssociateVpcCidrBlock")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "169.254.0.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidVpc.Range"))
            .body("Response.Errors.Error.Message", equalTo("The CIDR '169.254.0.0/24' is invalid."));
    }
}
