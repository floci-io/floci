package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.floci.aws.testing.Ec2Requests.ec2;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class Ec2SubnetVpcRangeIntegrationTest {
    private static final String ACCOUNT_ID = "855555555555";
    private static final String REGION = "us-east-1";

    @Test
    void subnetRangeMustFitPrimaryOrAssociatedSecondaryVpcBlock() {
        String vpcId = ec2("CreateVpc", ACCOUNT_ID, REGION).formParam("CidrBlock", "10.0.0.0/16").post("/").then()
                .statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        ec2("CreateSubnet", ACCOUNT_ID, REGION).formParam("VpcId", vpcId).formParam("CidrBlock", "10.1.1.0/24")
                .post("/").then().statusCode(400).body("Response.Errors.Error.Code", equalTo("InvalidSubnet.Range"));
        ec2("AssociateVpcCidrBlock", ACCOUNT_ID, REGION).formParam("VpcId", vpcId).formParam("CidrBlock", "10.1.0.0/16")
                .post("/").then().statusCode(200);
        String subnetId = ec2("CreateSubnet", ACCOUNT_ID, REGION).formParam("VpcId", vpcId).formParam("CidrBlock", "10.1.1.9/24")
                .post("/").then().statusCode(200).body("CreateSubnetResponse.subnet.cidrBlock", equalTo("10.1.1.0/24"))
                .extract().path("CreateSubnetResponse.subnet.subnetId");
        ec2("DeleteSubnet", ACCOUNT_ID, REGION).formParam("SubnetId", subnetId).post("/").then().statusCode(200);
        ec2("DeleteVpc", ACCOUNT_ID, REGION).formParam("VpcId", vpcId).post("/").then().statusCode(200);
    }
}
