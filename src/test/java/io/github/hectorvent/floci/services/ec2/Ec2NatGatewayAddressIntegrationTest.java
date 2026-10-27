package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

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
        Response allocation = given()
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

    /**
     * The interface id DescribeNatGateways publishes has to resolve through
     * DescribeNetworkInterfaces, and the Elastic IP the gateway holds has to read as associated
     * through DescribeAddresses. Deleting the gateway releases both.
     */
    @Test
    void aGatewaysInterfaceResolvesAndItsElasticIpReadsAsAssociated() {
        String subnetId = subnetId();
        String allocationId = given()
            .formParam("Action", "AllocateAddress")
            .formParam("Domain", "vpc")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("AllocateAddressResponse.allocationId");

        String natGatewayId = given()
            .formParam("Action", "CreateNatGateway")
            .formParam("SubnetId", subnetId)
            .formParam("AllocationId", allocationId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateNatGatewayResponse.natGateway.natGatewayId");

        Response described = given()
            .formParam("Action", "DescribeNatGateways")
            .formParam("NatGatewayId.1", natGatewayId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200).extract().response();
        String prefix = "DescribeNatGatewaysResponse.natGatewaySet.item.";
        String eniId = described.path(prefix + "natGatewayAddressSet.item.networkInterfaceId");
        String privateIp = described.path(prefix + "natGatewayAddressSet.item.privateIp");
        String associationId = described.path(prefix + "natGatewayAddressSet.item.associationId");
        String vpcId = described.path(prefix + "vpcId");

        // Twice: the id must be stable, not re-minted per read.
        for (int i = 0; i < 2; i++) {
            given()
                .formParam("Action", "DescribeNetworkInterfaces")
                .formParam("NetworkInterfaceId.1", eniId)
                .header("Authorization", AUTH_HEADER)
            .when().post("/")
            .then()
                .statusCode(200)
                .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.networkInterfaceId",
                        equalTo(eniId))
                .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.interfaceType",
                        equalTo("nat_gateway"))
                .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.subnetId",
                        equalTo(subnetId))
                .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.vpcId",
                        equalTo(vpcId))
                .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.privateIpAddress",
                        equalTo(privateIp))
                .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item"
                        + ".privateIpAddressesSet.item.association.allocationId", equalTo(allocationId));
        }

        given()
            .formParam("Action", "DescribeAddresses")
            .formParam("AllocationId.1", allocationId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("DescribeAddressesResponse.addressesSet.item.associationId", equalTo(associationId))
            .body("DescribeAddressesResponse.addressesSet.item.networkInterfaceId", equalTo(eniId))
            .body("DescribeAddressesResponse.addressesSet.item.privateIpAddress", equalTo(privateIp));

        given()
            .formParam("Action", "DeleteNatGateway")
            .formParam("NatGatewayId", natGatewayId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200);

        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("NetworkInterfaceId.1", eniId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidNetworkInterfaceID.NotFound"));

        given()
            .formParam("Action", "DescribeAddresses")
            .formParam("AllocationId.1", allocationId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("DescribeAddressesResponse.addressesSet.item.allocationId", equalTo(allocationId))
            .body(not(containsString("<associationId>")))
            .body(not(containsString("<networkInterfaceId>")));
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

    private String allocate() {
        return given()
            .formParam("Action", "AllocateAddress")
            .formParam("Domain", "vpc")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("AllocateAddressResponse.allocationId");
    }

    private Response createNat(String allocationId) {
        return given()
            .formParam("Action", "CreateNatGateway")
            .formParam("SubnetId", subnetId())
            .formParam("AllocationId", allocationId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/");
    }

    /**
     * As on AWS, CreateNatGateway with an Elastic IP another gateway or an instance holds
     * succeeds, but the gateway is failed: no interface, and the holder keeps the address.
     */
    @Test
    void aGatewayGivenAnAssociatedElasticIpIsCreatedFailed() {
        String heldByGateway = allocate();
        createNat(heldByGateway).then().statusCode(200);
        String failedId = createNat(heldByGateway).then().statusCode(200)
            .body("CreateNatGatewayResponse.natGateway.state", equalTo("failed"))
            .extract().path("CreateNatGatewayResponse.natGateway.natGatewayId");
        given()
            .formParam("Action", "DescribeNatGateways")
            .formParam("NatGatewayId.1", failedId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.state", equalTo("failed"))
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.failureCode",
                    equalTo("Resource.AlreadyAssociated"))
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.failureMessage",
                    equalTo("Elastic IP address " + heldByGateway + " is already associated"))
            .body(not(containsString("<networkInterfaceId>")));

        String heldByInstance = allocate();
        given()
            .formParam("Action", "AssociateAddress")
            .formParam("AllocationId", heldByInstance)
            .formParam("InstanceId", "i-0123456789abcdef0")
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200);
        createNat(heldByInstance).then().statusCode(200)
            .body("CreateNatGatewayResponse.natGateway.state", equalTo("failed"))
            .body("CreateNatGatewayResponse.natGateway.failureCode", equalTo("Resource.AlreadyAssociated"));
        String instanceAssociation = given()
            .formParam("Action", "DescribeAddresses")
            .formParam("AllocationId.1", heldByInstance)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("DescribeAddressesResponse.addressesSet.item.instanceId", equalTo("i-0123456789abcdef0"))
            .extract().path("DescribeAddressesResponse.addressesSet.item.associationId");
        // The failed gateway does not hold the address: once the instance lets go it can be released.
        given()
            .formParam("Action", "DisassociateAddress")
            .formParam("AssociationId", instanceAssociation)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200);
        given()
            .formParam("Action", "ReleaseAddress")
            .formParam("AllocationId", heldByInstance)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200);

        // The failed gateway claimed nothing, and deleting it leaves the first gateway's association alone.
        String held = given()
            .formParam("Action", "DescribeAddresses")
            .formParam("AllocationId.1", heldByGateway)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("DescribeAddressesResponse.addressesSet.item.associationId", startsWith("eipassoc-"))
            .extract().path("DescribeAddressesResponse.addressesSet.item.associationId");
        given()
            .formParam("Action", "DeleteNatGateway")
            .formParam("NatGatewayId", failedId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200);
        given()
            .formParam("Action", "DescribeAddresses")
            .formParam("AllocationId.1", heldByGateway)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("DescribeAddressesResponse.addressesSet.item.associationId", equalTo(held));
    }

    /** The association a gateway publishes is released by deleting the gateway, not by disassociating. */
    @Test
    void aGatewaysElasticIpCannotBeDisassociated() {
        String allocationId = allocate();
        String natGatewayId = createNat(allocationId).then().statusCode(200)
            .extract().path("CreateNatGatewayResponse.natGateway.natGatewayId");
        String associationId = given()
            .formParam("Action", "DescribeAddresses")
            .formParam("AllocationId.1", allocationId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("DescribeAddressesResponse.addressesSet.item.associationId");

        given()
            .formParam("Action", "DisassociateAddress")
            .formParam("AssociationId", associationId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));

        given()
            .formParam("Action", "DescribeAddresses")
            .formParam("AllocationId.1", allocationId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("DescribeAddressesResponse.addressesSet.item.associationId", equalTo(associationId));

        // Nor released out from under the gateway: AWS answers InvalidIPAddress.InUse.
        given()
            .formParam("Action", "ReleaseAddress")
            .formParam("AllocationId", allocationId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidIPAddress.InUse"));
        given()
            .formParam("Action", "DescribeAddresses")
            .formParam("AllocationId.1", allocationId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .body("DescribeAddressesResponse.addressesSet.item.associationId", equalTo(associationId));

        given()
            .formParam("Action", "DeleteNatGateway")
            .formParam("NatGatewayId", natGatewayId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200);
        given()
            .formParam("Action", "ReleaseAddress")
            .formParam("AllocationId", allocationId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200);
    }
}
