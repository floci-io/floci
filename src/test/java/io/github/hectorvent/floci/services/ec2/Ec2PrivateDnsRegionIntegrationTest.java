package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsRegions;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class Ec2PrivateDnsRegionIntegrationTest {

    @Test
    void instanceAndNetworkInterfaceNamesUseTheRequestRegion() {
        for (String region : new String[]{"us-east-1", "us-west-2"}) {
            String auth = "AWS4-HMAC-SHA256 Credential=test/20261004/" + region + "/ec2/aws4_request";
            String subnetId = given().formParam("Action", "DescribeSubnets")
                    .header("Authorization", auth)
                    .when().post("/").then().statusCode(200)
                    .extract().path("DescribeSubnetsResponse.subnetSet.item[0].subnetId");

            ExtractableResponse<Response> instanceResponse = given().formParam("Action", "RunInstances")
                    .formParam("ImageId", "ami-0abcdef1234567890")
                    .formParam("InstanceType", "t3.micro")
                    .formParam("MinCount", "1")
                    .formParam("MaxCount", "1")
                    .formParam("SubnetId", subnetId)
                    .header("Authorization", auth)
                    .when().post("/").then().statusCode(200).extract();
            String instanceId = instanceResponse.path("RunInstancesResponse.instancesSet.item.instanceId");
            String privateIp = instanceResponse.path("RunInstancesResponse.instancesSet.item.privateIpAddress");
            String expectedDnsName = AwsRegions.ec2PrivateIpDnsName(privateIp, region);
            assertEquals(expectedDnsName,
                    instanceResponse.path("RunInstancesResponse.instancesSet.item.privateDnsName"));

            given().formParam("Action", "DescribeInstances")
                    .formParam("InstanceId.1", instanceId)
                    .header("Authorization", auth)
                    .when().post("/").then().statusCode(200)
                    .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.privateDnsName",
                            equalTo(expectedDnsName));

            ExtractableResponse<Response> interfaceResponse = given().formParam("Action", "CreateNetworkInterface")
                    .formParam("SubnetId", subnetId)
                    .header("Authorization", auth)
                    .when().post("/").then().statusCode(200).extract();
            String interfaceIp = interfaceResponse.path(
                    "CreateNetworkInterfaceResponse.networkInterface.privateIpAddress");
            String expectedInterfaceDns = AwsRegions.ec2PrivateIpDnsName(interfaceIp, region);
            assertEquals(expectedInterfaceDns, interfaceResponse.path(
                    "CreateNetworkInterfaceResponse.networkInterface.privateDnsName"));
            assertEquals(expectedInterfaceDns, interfaceResponse.path(
                    "CreateNetworkInterfaceResponse.networkInterface.privateIpAddressesSet.item.privateDnsName"));
        }
    }
}
