package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_CreateNetworkInterface.html,
 * {@code Ipv6AddressCount} is "the number of IPv6 addresses to assign to a network interface. Amazon EC2 automatically
 * selects the IPv6 addresses from the subnet range", it cannot be combined with specific IPv6 addresses, specific IPv6
 * prefixes or a count of IPv6 prefixes, and "if your subnet has the AssignIpv6AddressOnCreation attribute set, you can
 * override that setting by specifying 0 as the IPv6 address count". The interface reports its addresses in
 * {@code ipv6AddressesSet}, DescribeNetworkInterfaces' {@code ipv6-addresses.ipv6-address} filter finds it by one, and
 * an attached interface carries them onto the instance.
 */
@QuarkusTest
class Ec2NetworkInterfaceIpv6AddressCountIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String ADDRESSES = "CreateNetworkInterfaceResponse.networkInterface.ipv6AddressesSet.item";

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

    private Response create(String subnetId, String... params) {
        RequestSpecification request = given().formParam("Action", "CreateNetworkInterface").formParam("SubnetId", subnetId)
                .header("Authorization", AUTH_HEADER);
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request.when().post("/").then().extract().response();
    }

    private static List<String> addresses(Response response, String path) {
        return XmlPath.from(response.asString()).getList(path + ".ipv6Address");
    }

    private static void assertInBlock(String block, String address) throws Exception {
        BigInteger network = new BigInteger(1, InetAddress.getByName(block.split("/")[0]).getAddress());
        BigInteger value = new BigInteger(1, InetAddress.getByName(address).getAddress());
        assertEquals(network, value.shiftRight(64).shiftLeft(64), address + " is outside " + block);
        // AWS reserves the first four addresses of a subnet's block.
        assertTrue(value.subtract(network).compareTo(BigInteger.valueOf(4)) >= 0, address + " is reserved");
    }

    @Test
    void theCountAssignsThatManyDistinctAddressesFromTheSubnetsBlock() throws Exception {
        String[] subnet = dualStackSubnet("10.110.0.0/16", "10.110.1.0/24", false);
        Response created = create(subnet[0], "Ipv6AddressCount", "3");
        assertEquals(200, created.statusCode(), created.asString());
        List<String> assigned = addresses(created, ADDRESSES);
        assertEquals(3, assigned.size(), created.asString());
        assertEquals(3, new HashSet<>(assigned).size(), "addresses repeat: " + assigned);
        for (String address : assigned) {
            assertInBlock(subnet[1], address);
        }
        assertEquals(List.of("false", "false", "false"),
                XmlPath.from(created.asString()).getList(ADDRESSES + ".isPrimaryIpv6"));

        String eniId = created.path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");
        Response described = given()
            .formParam("Action", "DescribeNetworkInterfaces").formParam("NetworkInterfaceId.1", eniId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200).extract().response();
        assertEquals(assigned, addresses(described,
                "DescribeNetworkInterfacesResponse.networkInterfaceSet.item.ipv6AddressesSet.item"));
    }

    @Test
    void theIpv6AddressFilterFindsTheInterfaceByOneOfItsAddresses() throws Exception {
        String[] subnet = dualStackSubnet("10.111.0.0/16", "10.111.1.0/24", false);
        Response created = create(subnet[0], "Ipv6AddressCount", "2");
        String eniId = created.path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");
        String second = addresses(created, ADDRESSES).get(1);
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("Filter.1.Name", "ipv6-addresses.ipv6-address").formParam("Filter.1.Value.1", second)
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.size()", equalTo(1))
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.networkInterfaceId", equalTo(eniId));
        String absent = subnet[1].replace("::/64", "::ffff:1");
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("Filter.1.Name", "ipv6-addresses.ipv6-address").formParam("Filter.1.Value.1", absent)
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.size()", equalTo(0));
    }

    @Test
    void aSubnetThatAssignsIpv6GivesOneAddressUnlessTheCountIsZero() throws Exception {
        String[] subnet = dualStackSubnet("10.112.0.0/16", "10.112.1.0/24", true);
        Response defaulted = create(subnet[0]);
        assertEquals(200, defaulted.statusCode(), defaulted.asString());
        List<String> one = addresses(defaulted, ADDRESSES);
        assertEquals(1, one.size(), defaulted.asString());
        assertInBlock(subnet[1], one.get(0));

        Response overridden = create(subnet[0], "Ipv6AddressCount", "0");
        assertEquals(200, overridden.statusCode(), overridden.asString());
        assertFalse(overridden.asString().contains("ipv6Address"), overridden.asString());

        String[] noAttribute = dualStackSubnet("10.113.0.0/16", "10.113.1.0/24", false);
        Response plain = create(noAttribute[0]);
        assertEquals(200, plain.statusCode(), plain.asString());
        assertFalse(plain.asString().contains("ipv6Address"), plain.asString());
    }

    @Test
    void aCountOnASubnetWithoutAnIpv6BlockIsRefusedAndCreatesNothing() {
        String vpcId = given()
            .formParam("Action", "CreateVpc").formParam("CidrBlock", "10.114.0.0/16").header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        String ipv4Only = given()
            .formParam("Action", "CreateSubnet").formParam("VpcId", vpcId).formParam("CidrBlock", "10.114.1.0/24")
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
        Response refused = create(ipv4Only, "Ipv6AddressCount", "1", "Description", "ipv4-only-count");
        assertEquals(400, refused.statusCode(), refused.asString());
        assertEquals("InvalidParameterValue", refused.path("Response.Errors.Error.Code"));
        given()
            .formParam("Action", "DescribeNetworkInterfaces")
            .formParam("Filter.1.Name", "description").formParam("Filter.1.Value.1", "ipv4-only-count")
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200)
            .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.size()", equalTo(0));
    }

    @Test
    void aNegativeCountOrACountWithSpecificAddressesOrPrefixesIsRefused() throws Exception {
        String[] subnet = dualStackSubnet("10.115.0.0/16", "10.115.1.0/24", false);
        Response negative = create(subnet[0], "Ipv6AddressCount", "-1");
        assertEquals(400, negative.statusCode(), negative.asString());
        assertEquals("InvalidParameterValue", negative.path("Response.Errors.Error.Code"));
        String specific = subnet[1].replace("::/64", "::10");
        for (String[] combined : new String[][] {
                {"Ipv6Addresses.1.Ipv6Address", specific},
                {"Ipv6PrefixCount", "1"},
                {"Ipv6Prefix.1.Ipv6Prefix", subnet[1].replace("::/64", "::/80")}}) {
            Response refused = create(subnet[0], "Ipv6AddressCount", "1", combined[0], combined[1]);
            assertEquals(400, refused.statusCode(), combined[0] + ": " + refused.asString());
            assertEquals("InvalidParameterCombination", refused.path("Response.Errors.Error.Code"), combined[0]);
        }
    }

    @Test
    void anAttachedInterfaceCarriesItsAddressesOntoTheInstance() throws Exception {
        String[] subnet = dualStackSubnet("10.116.0.0/16", "10.116.1.0/24", false);
        Response created = create(subnet[0], "Ipv6AddressCount", "1");
        String eniId = created.path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");
        String address = addresses(created, ADDRESSES).get(0);
        String instanceId = given()
            .formParam("Action", "RunInstances").formParam("ImageId", "ami-ipv6-eni")
            .formParam("InstanceType", "t3.micro").formParam("MinCount", "1").formParam("MaxCount", "1")
            .formParam("SubnetId", subnet[0]).header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200)
            .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        // Mock mode settles a pending instance to running on the next describe; an attach needs running or stopped.
        given()
            .formParam("Action", "DescribeInstances").formParam("InstanceId.1", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200)
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceState.name",
                    equalTo("running"));
        given()
            .formParam("Action", "AttachNetworkInterface").formParam("NetworkInterfaceId", eniId)
            .formParam("InstanceId", instanceId).formParam("DeviceIndex", "1").header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200);
        Response described = given()
            .formParam("Action", "DescribeInstances").formParam("InstanceId.1", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200).extract().response();
        List<String> interfaceIds = XmlPath.from(described.asString()).getList(
                "DescribeInstancesResponse.reservationSet.item.instancesSet.item.networkInterfaceSet.item"
                        + ".networkInterfaceId");
        List<String> carried = XmlPath.from(described.asString()).getList(
                "DescribeInstancesResponse.reservationSet.item.instancesSet.item.networkInterfaceSet.item"
                        .concat(".find { it.networkInterfaceId == '" + eniId + "' }.ipv6AddressesSet.item.ipv6Address"));
        assertTrue(interfaceIds.contains(eniId), described.asString());
        assertEquals(List.of(address), carried, described.asString());
    }
}
