package io.github.hectorvent.floci.services.ec2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * A NAT gateway's natGatewayAddressSet carried only an allocation id. aws_nat_gateway exposes
 * public_ip, private_ip and network_interface_id as resource outputs, and Gruntwork's VPC modules
 * re-export nat_gateway_public_ips, so the omission does not merely diff, it propagates empty
 * values into whatever consumes those outputs.
 */
@QuarkusTest
class Ec2NatGatewayAddressIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private String subnetId() {
        String vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.94.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        return given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.94.1.0/24")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateSubnetResponse.subnet.subnetId");
    }

    @Test
    void aPublicGatewayReportsItsElasticIpPrivateIpAndInterface() {
        String subnetId = subnetId();
        io.restassured.response.Response allocation = given()
            .formParam("Action", "AllocateAddress")
            .formParam("Domain", "vpc")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200).extract().response();
        String allocationId = allocation.path("AllocateAddressResponse.allocationId");
        String publicIp = allocation.path("AllocateAddressResponse.publicIp");

        String natGatewayId = given()
            .formParam("Action", "CreateNatGateway")
            .formParam("SubnetId", subnetId)
            .formParam("AllocationId", allocationId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("CreateNatGatewayResponse.natGateway.natGatewayAddressSet.item.allocationId",
                    equalTo(allocationId))
            .body("CreateNatGatewayResponse.natGateway.natGatewayAddressSet.item.publicIp",
                    equalTo(publicIp))
            .body("CreateNatGatewayResponse.natGateway.natGatewayAddressSet.item.privateIp",
                    matchesRegex("10\\.94\\.1\\.\\d+"))
            .body("CreateNatGatewayResponse.natGateway.natGatewayAddressSet.item.networkInterfaceId",
                    startsWith("eni-"))
            .body("CreateNatGatewayResponse.natGateway.natGatewayAddressSet.item.associationId",
                    startsWith("eipassoc-"))
            .body("CreateNatGatewayResponse.natGateway.natGatewayAddressSet.item.status",
                    equalTo("succeeded"))
            .extract().path("CreateNatGatewayResponse.natGateway.natGatewayId");

        // Describe is the read Terraform refreshes from, so it has to agree, not just Create.
        given()
            .formParam("Action", "DescribeNatGateways")
            .formParam("NatGatewayId.1", natGatewayId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.natGatewayAddressSet.item.publicIp",
                    equalTo(publicIp))
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.natGatewayAddressSet.item.networkInterfaceId",
                    startsWith("eni-"));
    }

    @Test
    void aPrivateGatewayHasAPrivateAddressAndNoElasticIp() {
        given()
            .formParam("Action", "CreateNatGateway")
            .formParam("SubnetId", subnetId())
            .formParam("ConnectivityType", "private")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("CreateNatGatewayResponse.natGateway.connectivityType", equalTo("private"))
            .body("CreateNatGatewayResponse.natGateway.natGatewayAddressSet.item.privateIp",
                    matchesRegex("10\\.94\\.1\\.\\d+"))
            .body("CreateNatGatewayResponse.natGateway.natGatewayAddressSet.item.networkInterfaceId",
                    startsWith("eni-"))
            // No Elastic IP was requested, so there is none to report, and no association either.
            .body(not(containsString("<publicIp>")))
            .body(not(containsString("<associationId>")));
    }

    /**
     * A private gateway has no route to the internet and nothing to attach an Elastic IP to, so
     * asking for both is refused rather than answered with an impossible public association.
     */
    @Test
    void aPrivateGatewayCannotBeGivenAnElasticIp() {
        String allocationId = given()
            .formParam("Action", "AllocateAddress")
            .formParam("Domain", "vpc")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("AllocateAddressResponse.allocationId");

        given()
            .formParam("Action", "CreateNatGateway")
            .formParam("SubnetId", subnetId())
            .formParam("ConnectivityType", "private")
            .formParam("AllocationId", allocationId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"));
    }
}
