package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.testing.Ec2Requests.ec2;
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

    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";

    private String subnetId() {
        String vpcId = ec2("CreateVpc", ACCOUNT_ID, REGION).formParam("CidrBlock", "10.94.0.0/16").post("/")
        .then().statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        return ec2("CreateSubnet", ACCOUNT_ID, REGION)
            .formParam("VpcId", vpcId).formParam("CidrBlock", "10.94.1.0/24").post("/")
        .then().statusCode(200)
            .extract().path("CreateSubnetResponse.subnet.subnetId");
    }

    @Test
    void aPublicGatewayReportsItsElasticIpPrivateIpAndInterface() {
        Response allocation = allocation();
        String allocationId = allocation.path("AllocateAddressResponse.allocationId");
        String publicIp = allocation.path("AllocateAddressResponse.publicIp");

        String natGatewayId = createNat(allocationId).then()
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
            .body("CreateNatGatewayResponse.natGateway.availabilityMode", equalTo("zonal"))
            .extract().path("CreateNatGatewayResponse.natGateway.natGatewayId");

        // Describe is the read Terraform refreshes from, so it has to agree, not just Create.
        ec2("DescribeNatGateways", ACCOUNT_ID, REGION).formParam("NatGatewayId.1", natGatewayId).post("/")
        .then()
            .statusCode(200)
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.natGatewayAddressSet.item.publicIp",
                    equalTo(publicIp))
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.natGatewayAddressSet.item.networkInterfaceId",
                    startsWith("eni-"))
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.availabilityMode", equalTo("zonal"));
    }

    /**
     * The interface id DescribeNatGateways publishes has to resolve through
     * DescribeNetworkInterfaces, and the Elastic IP the gateway holds has to read as associated
     * through DescribeAddresses. Deleting the gateway releases both.
     */
    @Test
    void aGatewaysInterfaceResolvesAndItsElasticIpReadsAsAssociated() {
        String subnetId = subnetId();
        String allocationId = allocate();

        String natGatewayId = createNat(Map.of("SubnetId", subnetId, "AllocationId", allocationId)).then()
            .statusCode(200)
            .extract().path("CreateNatGatewayResponse.natGateway.natGatewayId");

        Response described = ec2("DescribeNatGateways", ACCOUNT_ID, REGION)
            .formParam("NatGatewayId.1", natGatewayId).post("/")
        .then().statusCode(200).extract().response();
        String prefix = "DescribeNatGatewaysResponse.natGatewaySet.item.";
        String eniId = described.path(prefix + "natGatewayAddressSet.item.networkInterfaceId");
        String privateIp = described.path(prefix + "natGatewayAddressSet.item.privateIp");
        String associationId = described.path(prefix + "natGatewayAddressSet.item.associationId");
        String vpcId = described.path(prefix + "vpcId");

        // Twice: the id must be stable, not re-minted per read.
        for (int i = 0; i < 2; i++) {
            ec2("DescribeNetworkInterfaces", ACCOUNT_ID, REGION).formParam("NetworkInterfaceId.1", eniId).post("/")
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

        ec2("DescribeAddresses", ACCOUNT_ID, REGION).formParam("AllocationId.1", allocationId).post("/")
        .then()
            .statusCode(200)
            .body("DescribeAddressesResponse.addressesSet.item.associationId", equalTo(associationId))
            .body("DescribeAddressesResponse.addressesSet.item.networkInterfaceId", equalTo(eniId))
            .body("DescribeAddressesResponse.addressesSet.item.privateIpAddress", equalTo(privateIp));

        ec2("DeleteNatGateway", ACCOUNT_ID, REGION).formParam("NatGatewayId", natGatewayId).post("/")
        .then().statusCode(200);

        ec2("DescribeNetworkInterfaces", ACCOUNT_ID, REGION).formParam("NetworkInterfaceId.1", eniId).post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidNetworkInterfaceID.NotFound"));

        ec2("DescribeAddresses", ACCOUNT_ID, REGION).formParam("AllocationId.1", allocationId).post("/")
        .then()
            .statusCode(200)
            .body("DescribeAddressesResponse.addressesSet.item.allocationId", equalTo(allocationId))
            .body(not(containsString("<associationId>")))
            .body(not(containsString("<networkInterfaceId>")));
    }

    @Test
    void aPrivateGatewayHasAPrivateAddressAndNoElasticIp() {
        createNat(Map.of("SubnetId", subnetId(), "ConnectivityType", "private")).then()
            .statusCode(200)
            .body("CreateNatGatewayResponse.natGateway.connectivityType", equalTo("private"))
            .body("CreateNatGatewayResponse.natGateway.availabilityMode", equalTo("zonal"))
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
        createNat(Map.of("SubnetId", subnetId(), "ConnectivityType", "private", "AllocationId", allocate())).then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"));
    }

    /**
     * Every gateway Floci creates is zonal. A regional request names its VPC and no subnet, and
     * is refused rather than failing on the missing subnet; one that names a subnet as well is
     * refused rather than created as zonal. AWS's enum is exact-case, so a value that is neither
     * mode is invalid rather than zonal.
     */
    @Test
    void aRegionalOrUnknownAvailabilityModeIsRefused() {
        createNat(Map.of("AvailabilityMode", "regional", "VpcId", "vpc-0123456789abcdef0")).then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("UnsupportedOperation"));

        String subnetId = subnetId();
        createNat(Map.of("AvailabilityMode", "regional", "SubnetId", subnetId, "AllocationId", allocate())).then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("UnsupportedOperation"));
        createNat(Map.of("AvailabilityMode", "Regional", "SubnetId", subnetId, "ConnectivityType", "private")).then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
        assertNatGatewaysIn(subnetId, 0);

        createNat(Map.of("AvailabilityMode", "zonal", "SubnetId", subnetId, "ConnectivityType", "private")).then()
            .statusCode(200)
            .body("CreateNatGatewayResponse.natGateway.availabilityMode", equalTo("zonal"));
    }

    /**
     * PrivateIpAddress is the gateway's address, and every read that reports it has to agree. The
     * Terraform AWS provider reads private_ip back, and it forces a new gateway, so a different
     * address would replace the gateway on every plan.
     */
    @Test
    void aRequestedPrivateIpIsTheAddressEveryReadReports() {
        String allocationId = allocate();
        Response created = createNat(Map.of("SubnetId", subnetId(), "AllocationId", allocationId,
                "PrivateIpAddress", "10.94.1.50")).then()
            .statusCode(200)
            .body("CreateNatGatewayResponse.natGateway.natGatewayAddressSet.item.privateIp", equalTo("10.94.1.50"))
            .extract().response();
        String natGatewayId = created.path("CreateNatGatewayResponse.natGateway.natGatewayId");
        String eniId = created.path("CreateNatGatewayResponse.natGateway.natGatewayAddressSet.item.networkInterfaceId");

        ec2("DescribeNatGateways", ACCOUNT_ID, REGION).formParam("NatGatewayId.1", natGatewayId).post("/")
        .then().statusCode(200)
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.natGatewayAddressSet.item.privateIp",
                    equalTo("10.94.1.50"));
        ec2("DescribeNetworkInterfaces", ACCOUNT_ID, REGION).formParam("NetworkInterfaceId.1", eniId).post("/")
        .then().statusCode(200)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.privateIpAddress",
                    equalTo("10.94.1.50"));
        ec2("DescribeAddresses", ACCOUNT_ID, REGION).formParam("AllocationId.1", allocationId).post("/")
        .then().statusCode(200)
            .body("DescribeAddressesResponse.addressesSet.item.privateIpAddress", equalTo("10.94.1.50"));
    }

    /** The RunInstances rules: an assignable address of the subnet that nothing else holds. */
    @Test
    void aRequestedPrivateIpOutsideTheSubnetOrInUseIsRefused() {
        String subnetId = subnetId();
        for (String unassignable : List.of("10.95.1.50", "10.94.1.1")) {
            createNat(Map.of("SubnetId", subnetId, "ConnectivityType", "private", "PrivateIpAddress", unassignable))
                .then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
        }
        assertNatGatewaysIn(subnetId, 0);

        Map<String, String> request = Map.of("SubnetId", subnetId, "ConnectivityType", "private",
                "PrivateIpAddress", "10.94.1.60");
        createNat(request).then().statusCode(200);
        createNat(request).then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidIPAddress.InUse"))
            .body("Response.Errors.Error.Message", equalTo("Address 10.94.1.60 is in use."));
        assertNatGatewaysIn(subnetId, 1);
    }

    /**
     * A gateway gets its primary address only, so the secondary-address parameters are refused
     * rather than answered with a gateway that has fewer addresses than it was asked for.
     */
    @Test
    void theSecondaryAddressParametersAreRefused() {
        String subnetId = subnetId();
        for (Map<String, String> request : List.of(
                Map.of("SubnetId", subnetId, "AllocationId", allocate(), "SecondaryAllocationId.1", allocate()),
                Map.of("SubnetId", subnetId, "ConnectivityType", "private", "SecondaryPrivateIpAddress.1", "10.94.1.70"),
                Map.of("SubnetId", subnetId, "ConnectivityType", "private", "SecondaryPrivateIpAddressCount", "2"))) {
            createNat(request).then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("UnsupportedOperation"));
        }
        assertNatGatewaysIn(subnetId, 0);
    }

    private static Response allocation() {
        return ec2("AllocateAddress", ACCOUNT_ID, REGION).formParam("Domain", "vpc").post("/")
        .then().statusCode(200).extract().response();
    }

    private static String allocate() {
        return allocation().path("AllocateAddressResponse.allocationId");
    }

    private Response createNat(String allocationId) {
        return createNat(Map.of("SubnetId", subnetId(), "AllocationId", allocationId));
    }

    private static Response createNat(Map<String, String> params) {
        return ec2("CreateNatGateway", ACCOUNT_ID, REGION).formParams(params).post("/");
    }

    /** A refused request must not leave a gateway behind in the subnet it named. */
    private static void assertNatGatewaysIn(String subnetId, int expected) {
        ec2("DescribeNatGateways", ACCOUNT_ID, REGION)
            .formParam("Filter.1.Name", "subnet-id").formParam("Filter.1.Value.1", subnetId).post("/")
        .then().statusCode(200)
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.size()", equalTo(expected));
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
        ec2("DescribeNatGateways", ACCOUNT_ID, REGION).formParam("NatGatewayId.1", failedId).post("/")
        .then().statusCode(200)
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.state", equalTo("failed"))
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.failureCode",
                    equalTo("Resource.AlreadyAssociated"))
            .body("DescribeNatGatewaysResponse.natGatewaySet.item.failureMessage",
                    equalTo("Elastic IP address " + heldByGateway + " is already associated"))
            .body(not(containsString("<networkInterfaceId>")));

        String heldByInstance = allocate();
        ec2("AssociateAddress", ACCOUNT_ID, REGION)
            .formParam("AllocationId", heldByInstance).formParam("InstanceId", "i-0123456789abcdef0").post("/")
        .then().statusCode(200);
        createNat(heldByInstance).then().statusCode(200)
            .body("CreateNatGatewayResponse.natGateway.state", equalTo("failed"))
            .body("CreateNatGatewayResponse.natGateway.failureCode", equalTo("Resource.AlreadyAssociated"));
        String instanceAssociation = ec2("DescribeAddresses", ACCOUNT_ID, REGION)
            .formParam("AllocationId.1", heldByInstance).post("/")
        .then().statusCode(200)
            .body("DescribeAddressesResponse.addressesSet.item.instanceId", equalTo("i-0123456789abcdef0"))
            .extract().path("DescribeAddressesResponse.addressesSet.item.associationId");
        // The failed gateway does not hold the address: once the instance lets go it can be released.
        ec2("DisassociateAddress", ACCOUNT_ID, REGION).formParam("AssociationId", instanceAssociation).post("/")
        .then().statusCode(200);
        ec2("ReleaseAddress", ACCOUNT_ID, REGION).formParam("AllocationId", heldByInstance).post("/")
        .then().statusCode(200);

        // The failed gateway claimed nothing, and deleting it leaves the first gateway's association alone.
        String held = ec2("DescribeAddresses", ACCOUNT_ID, REGION).formParam("AllocationId.1", heldByGateway).post("/")
        .then().statusCode(200)
            .body("DescribeAddressesResponse.addressesSet.item.associationId", startsWith("eipassoc-"))
            .extract().path("DescribeAddressesResponse.addressesSet.item.associationId");
        ec2("DeleteNatGateway", ACCOUNT_ID, REGION).formParam("NatGatewayId", failedId).post("/")
        .then().statusCode(200);
        ec2("DescribeAddresses", ACCOUNT_ID, REGION).formParam("AllocationId.1", heldByGateway).post("/")
        .then().statusCode(200)
            .body("DescribeAddressesResponse.addressesSet.item.associationId", equalTo(held));
    }

    /** The association a gateway publishes is released by deleting the gateway, not by disassociating. */
    @Test
    void aGatewaysElasticIpCannotBeDisassociated() {
        String allocationId = allocate();
        String natGatewayId = createNat(allocationId).then().statusCode(200)
            .extract().path("CreateNatGatewayResponse.natGateway.natGatewayId");
        String associationId = ec2("DescribeAddresses", ACCOUNT_ID, REGION)
            .formParam("AllocationId.1", allocationId).post("/")
        .then().statusCode(200)
            .extract().path("DescribeAddressesResponse.addressesSet.item.associationId");

        ec2("DisassociateAddress", ACCOUNT_ID, REGION).formParam("AssociationId", associationId).post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));

        ec2("DescribeAddresses", ACCOUNT_ID, REGION).formParam("AllocationId.1", allocationId).post("/")
        .then().statusCode(200)
            .body("DescribeAddressesResponse.addressesSet.item.associationId", equalTo(associationId));

        // Nor released out from under the gateway: AWS answers InvalidIPAddress.InUse.
        ec2("ReleaseAddress", ACCOUNT_ID, REGION).formParam("AllocationId", allocationId).post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidIPAddress.InUse"));
        ec2("DescribeAddresses", ACCOUNT_ID, REGION).formParam("AllocationId.1", allocationId).post("/")
        .then().statusCode(200)
            .body("DescribeAddressesResponse.addressesSet.item.associationId", equalTo(associationId));

        ec2("DeleteNatGateway", ACCOUNT_ID, REGION).formParam("NatGatewayId", natGatewayId).post("/")
        .then().statusCode(200);
        ec2("ReleaseAddress", ACCOUNT_ID, REGION).formParam("AllocationId", allocationId).post("/")
        .then().statusCode(200);
    }
}
