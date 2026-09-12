package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.xml.HasXPath.hasXPath;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CreateVpcEndpoint and ModifyVpcEndpoint accept {@code SubnetConfiguration.N}, the per-subnet
 * IPv4 and IPv6 addresses an interface endpoint takes on its network interfaces. Terraform's
 * aws_vpc_endpoint sends the block on create and reads it back by following the endpoint to its
 * interfaces, so an emulator that drops the parameter answers with an address the configuration
 * never asked for and the endpoint replans as a replacement on every plan.
 *
 * <p>{@code SubnetConfiguration} is request-only in ec2/2016-11-15, so DescribeVpcEndpoints never
 * echoes it. What the wire shows is the subnet set; the addresses themselves are asserted on the
 * endpoint's interfaces.
 *
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_SubnetConfiguration.html">SubnetConfiguration</a>
 */
@QuarkusTest
class Ec2VpcEndpointSubnetConfigurationIntegrationTest {

    @Inject
    Ec2Service service;

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private String ec2Value(String action, String element, String... formParams) {
        RequestSpecification req = given().formParam("Action", action)
                .header("Authorization", AUTH_HEADER);
        for (int i = 0; i < formParams.length; i += 2) {
            req = req.formParam(formParams[i], formParams[i + 1]);
        }
        return req.when().post("/").then().statusCode(200).extract().path(element);
    }

    private String createVpc(String cidr) {
        return ec2Value("CreateVpc", "CreateVpcResponse.vpc.vpcId", "CidrBlock", cidr);
    }

    private String createSubnet(String vpcId, String cidr, String availabilityZone) {
        return ec2Value("CreateSubnet", "CreateSubnetResponse.subnet.subnetId",
                "VpcId", vpcId, "CidrBlock", cidr, "AvailabilityZone", availabilityZone);
    }

    private Map<String, String> endpointAddressesBySubnet() {
        Map<String, String> addresses = new HashMap<>();
        for (NetworkInterface eni : service.endpointNetworkInterfaces("us-east-1")) {
            addresses.put(eni.getSubnetId(), eni.getPrivateIpAddress());
        }
        return addresses;
    }

    @Test
    void createPinsTheAddressInEverySubnetItNames() {
        String vpcId = createVpc("10.70.0.0/16");
        String subnetA = createSubnet(vpcId, "10.70.1.0/24", "us-east-1a");
        String subnetB = createSubnet(vpcId, "10.70.2.0/24", "us-east-1b");

        String endpointId = ec2Value("CreateVpcEndpoint",
                "CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId",
                "VpcId", vpcId,
                "ServiceName", "com.amazonaws.us-east-1.ecs",
                "VpcEndpointType", "Interface",
                "SubnetId.1", subnetA,
                "SubnetId.2", subnetB,
                "SubnetConfiguration.1.SubnetId", subnetA,
                "SubnetConfiguration.1.Ipv4", "10.70.1.10",
                "SubnetConfiguration.2.SubnetId", subnetB,
                "SubnetConfiguration.2.Ipv4", "10.70.2.10");

        // Two reads, the way an apply followed by a plan reads the endpoint back. Both must
        // answer with the address the request pinned.
        for (int round = 0; round < 2; round++) {
            given()
                .formParam("Action", "DescribeVpcEndpoints")
                .formParam("VpcEndpointId.1", endpointId)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .contentType("application/xml")
                .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.subnetIdSet.item",
                        hasItem(subnetA))
                .body("DescribeVpcEndpointsResponse.vpcEndpointSet.item.subnetIdSet.item",
                        hasItem(subnetB));

            Map<String, String> addresses = endpointAddressesBySubnet();
            assertEquals("10.70.1.10", addresses.get(subnetA), "round " + round);
            assertEquals("10.70.2.10", addresses.get(subnetB), "round " + round);
        }
    }

    @Test
    void aSubnetIsNamedThroughItsConfigurationAlone() {
        String vpcId = createVpc("10.71.0.0/16");
        String subnetId = createSubnet(vpcId, "10.71.1.0/24", "us-east-1a");

        String endpointId = ec2Value("CreateVpcEndpoint",
                "CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId",
                "VpcId", vpcId,
                "ServiceName", "com.amazonaws.us-east-1.ecr.api",
                "VpcEndpointType", "Interface",
                "SubnetConfiguration.1.SubnetId", subnetId,
                "SubnetConfiguration.1.Ipv4", "10.71.1.20");

        given()
            .formParam("Action", "DescribeVpcEndpoints")
            .formParam("VpcEndpointId.1", endpointId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(hasXPath("//*[local-name()='subnetIdSet']/*[local-name()='item']", equalTo(subnetId)));

        assertEquals("10.71.1.20", endpointAddressesBySubnet().get(subnetId));
    }

    @Test
    void anEndpointWithoutTheParameterKeepsItsSynthesizedAddress() {
        String vpcId = createVpc("10.72.0.0/16");
        String subnetId = createSubnet(vpcId, "10.72.1.0/24", "us-east-1a");

        ec2Value("CreateVpcEndpoint", "CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId",
                "VpcId", vpcId,
                "ServiceName", "com.amazonaws.us-east-1.rds",
                "VpcEndpointType", "Interface",
                "SubnetId.1", subnetId);

        String address = endpointAddressesBySubnet().get(subnetId);
        assertEquals("10.72.1.", address.substring(0, address.lastIndexOf('.') + 1),
                "the synthesized address still comes from the subnet's own range");
        assertEquals(address, endpointAddressesBySubnet().get(subnetId),
                "and it is stable across reads");
    }

    @Test
    void modifyRewritesThePinnedAddress() {
        String vpcId = createVpc("10.73.0.0/16");
        String subnetId = createSubnet(vpcId, "10.73.1.0/24", "us-east-1a");

        String endpointId = ec2Value("CreateVpcEndpoint",
                "CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId",
                "VpcId", vpcId,
                "ServiceName", "com.amazonaws.us-east-1.ecs",
                "VpcEndpointType", "Interface",
                "SubnetId.1", subnetId,
                "SubnetConfiguration.1.SubnetId", subnetId,
                "SubnetConfiguration.1.Ipv4", "10.73.1.10");

        given()
            .formParam("Action", "ModifyVpcEndpoint")
            .formParam("VpcEndpointId", endpointId)
            .formParam("SubnetConfiguration.1.SubnetId", subnetId)
            .formParam("SubnetConfiguration.1.Ipv4", "10.73.1.44")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(hasXPath("//*[local-name()='ModifyVpcEndpointResponse']/*[local-name()='return']",
                    equalTo("true")));

        assertEquals("10.73.1.44", endpointAddressesBySubnet().get(subnetId));
    }

    @Test
    void anAddressOutsideTheSubnetIsRejected() {
        String vpcId = createVpc("10.74.0.0/16");
        String subnetId = createSubnet(vpcId, "10.74.1.0/24", "us-east-1a");

        given()
            .formParam("Action", "CreateVpcEndpoint")
            .formParam("VpcId", vpcId)
            .formParam("ServiceName", "com.amazonaws.us-east-1.ecs")
            .formParam("VpcEndpointType", "Interface")
            .formParam("SubnetId.1", subnetId)
            .formParam("SubnetConfiguration.1.SubnetId", subnetId)
            .formParam("SubnetConfiguration.1.Ipv4", "10.74.9.10")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));

        List<String> subnets = service.describeVpcEndpoints("us-east-1", List.of(), Map.of()).stream()
                .filter(endpoint -> vpcId.equals(endpoint.getVpcId()))
                .flatMap(endpoint -> endpoint.getSubnetIds().stream())
                .toList();
        assertEquals(List.of(), subnets, "the rejected request must not have created an endpoint");
    }
}
