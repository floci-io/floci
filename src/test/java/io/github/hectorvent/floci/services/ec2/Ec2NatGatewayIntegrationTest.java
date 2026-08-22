package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * Integration tests for NAT gateways over the EC2 Query protocol, asserting the read-back a
 * Terraform refresh or import depends on (lex00/floci#97).
 *
 * <p>{@code availabilityMode} is the load-bearing one: the AWS provider switches on it before it
 * reads {@code subnetId} or the address set, so an answer without it leaves {@code subnet_id} and
 * {@code allocation_id} unset in state and every later plan proposes destroy-and-recreate.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2NatGatewayIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static String subnetId;
    private static String allocationId;
    private static String publicIp;
    private static String natGatewayId;

    @Test
    @Order(1)
    void createSubnetAndElasticIp() {
        String vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.50.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        subnetId = given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.50.1.0/24")
            .formParam("AvailabilityZone", "us-east-1a")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateSubnetResponse.subnet.subnetId");

        io.restassured.path.xml.XmlPath address = given()
            .formParam("Action", "AllocateAddress")
            .formParam("Domain", "vpc")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath();
        allocationId = address.getString("AllocateAddressResponse.allocationId");
        publicIp = address.getString("AllocateAddressResponse.publicIp");
    }

    @Test
    @Order(2)
    void createReturnsTheSubnetAndTheFullPrimaryAddress() {
        natGatewayId = given()
            .formParam("Action", "CreateNatGateway")
            .formParam("SubnetId", subnetId)
            .formParam("AllocationId", allocationId)
            .formParam("TagSpecification.1.ResourceType", "natgateway")
            .formParam("TagSpecification.1.Tag.1.Key", "Name")
            .formParam("TagSpecification.1.Tag.1.Value", "egress")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateNatGatewayResponse.natGateway.subnetId", equalTo(subnetId))
            .body("CreateNatGatewayResponse.natGateway.availabilityMode", equalTo("zonal"))
            .body("CreateNatGatewayResponse.natGateway.natGatewayAddressSet.item.allocationId",
                    equalTo(allocationId))
            .extract().path("CreateNatGatewayResponse.natGateway.natGatewayId");
    }

    @Test
    @Order(3)
    void describeRoundTripsEverythingTheProviderReads() {
        given()
            .formParam("Action", "DescribeNatGateways")
            .formParam("NatGatewayId.1", natGatewayId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.natGatewayId", equalTo(natGatewayId))
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.subnetId", equalTo(subnetId))
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.availabilityMode", equalTo("zonal"))
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.connectivityType", equalTo("public"))
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.natGatewayAddressSet.item.allocationId",
                    equalTo(allocationId))
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.natGatewayAddressSet.item.isPrimary",
                    equalTo("true"))
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.natGatewayAddressSet.item.publicIp",
                    equalTo(publicIp))
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.natGatewayAddressSet.item.networkInterfaceId",
                    startsWith("eni-"))
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.natGatewayAddressSet.item.privateIp",
                    startsWith("10.50.1."))
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.natGatewayAddressSet.item.status",
                    equalTo("succeeded"))
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.tagSet.item.value", equalTo("egress"));
    }

    @Test
    @Order(4)
    void tagsAddedAfterCreationSurviveTheRoundTrip() {
        given()
            .formParam("Action", "CreateTags")
            .formParam("ResourceId.1", natGatewayId)
            .formParam("Tag.1.Key", "tofu-slot")
            .formParam("Tag.1.Value", "slot-3")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeNatGateways")
            .formParam("NatGatewayId.1", natGatewayId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.tagSet.item.find "
                    + "{ it.key == 'tofu-slot' }.value", equalTo("slot-3"))
            // The subnet must still be there after a tag write — this is the read a converged
            // plan does before it decides whether the gateway needs replacing.
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.subnetId", equalTo(subnetId));
    }

    @Test
    @Order(5)
    void privateGatewayHasNoPublicAddress() {
        String privateNatId = given()
            .formParam("Action", "CreateNatGateway")
            .formParam("SubnetId", subnetId)
            .formParam("ConnectivityType", "private")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateNatGatewayResponse.natGateway.natGatewayId");

        given()
            .formParam("Action", "DescribeNatGateways")
            .formParam("NatGatewayId.1", privateNatId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.connectivityType", equalTo("private"))
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.availabilityMode", equalTo("zonal"))
            .body(not(containsString("<publicIp>")))
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.natGatewayAddressSet.item.privateIp",
                    startsWith("10.50.1."));

        given()
            .formParam("Action", "DeleteNatGateway")
            .formParam("NatGatewayId", privateNatId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(6)
    void deleteNatGateway() {
        given()
            .formParam("Action", "DeleteNatGateway")
            .formParam("NatGatewayId", natGatewayId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
