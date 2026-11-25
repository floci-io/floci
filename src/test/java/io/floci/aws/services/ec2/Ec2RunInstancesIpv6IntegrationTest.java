package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.InetAddress;
import java.util.Arrays;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_ModifySubnetAttribute.html an instance launched
 * into a subnet with AssignIpv6AddressOnCreation receives an IPv6 address from the subnet's block; DescribeInstances
 * reports it on the instance (ipv6Address) and on its interface (ipv6AddressesSet). A subnet without the attribute
 * or without an IPv6 block gives none.
 */
@QuarkusTest
class Ec2RunInstancesIpv6IntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private String[] dualStackSubnet(String ipv4Cidr, String subnetCidr, boolean assign) throws Exception {
        Response vpc = given()
            .formParam("Action", "CreateVpc").formParam("CidrBlock", ipv4Cidr)
            .formParam("AmazonProvidedIpv6CidrBlock", "true").header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200).extract().response();
        String vpcId = vpc.path("CreateVpcResponse.vpc.vpcId");
        String vpcCidr = vpc.path("CreateVpcResponse.vpc.ipv6CidrBlockAssociationSet.item.ipv6CidrBlock");
        byte[] address = InetAddress.getByName(vpcCidr.split("/")[0]).getAddress();
        address[7] = 1;
        String prefix = String.join(":", Arrays.copyOf(
                InetAddress.getByAddress(address).getHostAddress().split(":"), 4));
        String ipv6Block = prefix + "::/64";
        String subnetId = given()
            .formParam("Action", "CreateSubnet").formParam("VpcId", vpcId).formParam("CidrBlock", subnetCidr)
            .formParam("Ipv6CidrBlock", ipv6Block).header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
        if (assign) {
            given()
                .formParam("Action", "ModifySubnetAttribute").formParam("SubnetId", subnetId)
                .formParam("AssignIpv6AddressOnCreation.Value", "true").header("Authorization", AUTH_HEADER)
            .when().post("/").then().statusCode(200);
        }
        return new String[] {subnetId, ipv6Block};
    }

    private Response launchAndDescribe(String subnetId) {
        String instanceId = given()
            .formParam("Action", "RunInstances").formParam("ImageId", "ami-ipv6-launch")
            .formParam("InstanceType", "t3.micro").formParam("MinCount", "1").formParam("MaxCount", "1")
            .formParam("SubnetId", subnetId).header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200)
            .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        return given()
            .formParam("Action", "DescribeInstances").formParam("InstanceId.1", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200).extract().response();
    }

    @Test
    void aSubnetThatAssignsIpv6GivesTheInstanceOneAddressInsideItsBlock() throws Exception {
        String[] subnet = dualStackSubnet("10.96.0.0/16", "10.96.1.0/24", true);
        Response described = launchAndDescribe(subnet[0]);
        String instanceAddress = described.path("DescribeInstancesResponse.reservationSet.item.instancesSet.item.ipv6Address");
        String interfaceAddress = described.path(
                "DescribeInstancesResponse.reservationSet.item.instancesSet.item.networkInterfaceSet.item.ipv6AddressesSet.item.ipv6Address");
        assertEquals(instanceAddress, interfaceAddress);
        BigInteger network = new BigInteger(1, InetAddress.getByName(subnet[1].split("/")[0]).getAddress());
        BigInteger value = new BigInteger(1, InetAddress.getByName(instanceAddress).getAddress());
        assertEquals(network, value.shiftRight(64).shiftLeft(64));
        assertTrue(value.subtract(network).compareTo(BigInteger.valueOf(4)) >= 0);
        given()
            .formParam("Action", "DescribeInstances")
            .formParam("InstanceId.1", (String) described.path(
                    "DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceId"))
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200)
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.networkInterfaceSet.item"
                    + ".ipv6AddressesSet.item.isPrimaryIpv6", equalTo("false"));
    }

    @Test
    void aSubnetWithoutTheAttributeOrWithoutIpv6GivesNoAddress() throws Exception {
        String[] noAttribute = dualStackSubnet("10.97.0.0/16", "10.97.1.0/24", false);
        assertFalse(launchAndDescribe(noAttribute[0]).asString().contains("ipv6Address"));
        String vpcId = given()
            .formParam("Action", "CreateVpc").formParam("CidrBlock", "10.98.0.0/16").header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        String ipv4Only = given()
            .formParam("Action", "CreateSubnet").formParam("VpcId", vpcId).formParam("CidrBlock", "10.98.1.0/24")
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
        given()
            .formParam("Action", "ModifySubnetAttribute").formParam("SubnetId", ipv4Only)
            .formParam("AssignIpv6AddressOnCreation.Value", "true").header("Authorization", AUTH_HEADER)
        .when().post("/");
        assertFalse(launchAndDescribe(ipv4Only).asString().contains("ipv6Address"));
    }
}
