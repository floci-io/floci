package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code networkInterfaceIdSet} an interface endpoint reports.
 *
 * <p>AWS creates one elastic network interface per subnet for an interface (PrivateLink)
 * endpoint and reports them here. Terraform's {@code aws_vpc_endpoint} surfaces the list as
 * {@code network_interface_ids}, and modules feed that output downstream -- to security-group
 * rules, to flow-log filters, to DNS -- so an empty list does not merely diff against AWS, it
 * propagates into whatever consumes it.
 *
 * <p>Floci already synthesized these interfaces: {@code Ec2Service.endpointNetworkInterfaces}
 * derives them deterministically from the endpoint's subnets so flow-log generation can
 * attribute AWS-service traffic to a stable address. Nothing reported them on the wire, so
 * DescribeVpcEndpoints answered with the element absent.
 *
 * <p>A Gateway endpoint has no interfaces, and AWS reports none for one; that direction is
 * asserted too. Note what that assertion can and cannot catch: it pins that no interface
 * ids are REPORTED, not that the element is absent. Dropping the emission guard would
 * yield an empty {@code <networkInterfaceIdSet/>}, whose {@code item} list is also empty,
 * and this test would still pass. That is deliberate rather than an oversight -- AWS emits
 * the empty element where this omits it, a difference no ec2query SDK can observe, so
 * pinning absence would pin the side of that choice further from AWS.
 *
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_DescribeVpcEndpoints.html">DescribeVpcEndpoints</a>
 */
@QuarkusTest
class Ec2VpcEndpointNetworkInterfaceIdsIntegrationTest {

    @Inject
    Ec2Service service;

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static final String ENI_IDS =
            "DescribeVpcEndpointsResponse.vpcEndpointSet.item.networkInterfaceIdSet.item";

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

    private XmlPath describeEndpoint(String endpointId) {
        return given()
            .formParam("Action", "DescribeVpcEndpoints")
            .formParam("VpcEndpointId.1", endpointId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .extract().xmlPath();
    }

    private List<String> eniIdsOf(String endpointId) {
        return describeEndpoint(endpointId).getList(ENI_IDS, String.class);
    }

    @Test
    void anInterfaceEndpointReportsOneNetworkInterfacePerSubnet() {
        String vpcId = createVpc("10.74.0.0/16");
        String subnetA = createSubnet(vpcId, "10.74.1.0/24", "us-east-1a");
        String subnetB = createSubnet(vpcId, "10.74.2.0/24", "us-east-1b");

        String endpointId = ec2Value("CreateVpcEndpoint",
                "CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId",
                "VpcId", vpcId, "ServiceName", "com.amazonaws.us-east-1.ec2",
                "VpcEndpointType", "Interface", "SubnetId.1", subnetA, "SubnetId.2", subnetB);

        List<String> eniIds = eniIdsOf(endpointId);
        assertEquals(2, eniIds.size(), "one interface per subnet, got " + eniIds);
        assertThat(eniIds, everyItem(matchesPattern("eni-[0-9a-f]{17}")));
        assertNotEquals(eniIds.get(0), eniIds.get(1),
                "each subnet gets its own interface, not one shared id");

        // Stable across calls. The ids are derived, not stored, so a caller that reads them
        // twice -- as Terraform does between plan and apply -- must see the same list.
        assertEquals(eniIds, eniIdsOf(endpointId));
    }

    @Test
    void theWireIdsAreTheInterfacesFlowLogAttributionReads() {
        // THE PROPERTY THE DESIGN RESTS ON, and the one the other tests do not reach.
        // They pin count, shape, distinctness and stability -- all of which an
        // implementation deriving ids from the subnet id alone would satisfy while
        // reporting interfaces that endpointNetworkInterfaces() denies exist. That is
        // not hypothetical: an earlier version of endpointNetworkInterfaceIds called
        // endpointEniId itself, lacked this method's skip of a vanished subnet, and the
        // two were measured reporting different sets after a subnet was deleted out from
        // under a live endpoint. Flow-log attribution reads the service side; Terraform
        // reads the wire side; they have to be the same list.
        String vpcId = createVpc("10.77.0.0/16");
        String subnetA = createSubnet(vpcId, "10.77.1.0/24", "us-east-1a");
        String subnetB = createSubnet(vpcId, "10.77.2.0/24", "us-east-1b");

        String endpointId = ec2Value("CreateVpcEndpoint",
                "CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId",
                "VpcId", vpcId, "ServiceName", "com.amazonaws.us-east-1.ec2",
                "VpcEndpointType", "Interface", "SubnetId.1", subnetA, "SubnetId.2", subnetB);

        // DELETE A SUBNET OUT FROM UNDER THE LIVE ENDPOINT. Without this the test is
        // decorative: both derivations agree whenever every subnet still exists, so a
        // version that skipped the vanished-subnet filter passed this assertion happily.
        // Measured -- the first draft of this test did exactly that. Real AWS answers
        // DependencyViolation here and Terraform's graph destroys the endpoint first, so
        // the state is reachable only in an emulator; that is precisely why the emulator
        // has to stay self-consistent in it rather than report two different answers.
        ec2Value("DeleteSubnet", "DeleteSubnetResponse.return", "SubnetId", subnetB);

        List<String> onTheWire = eniIdsOf(endpointId);

        // Scoped to THIS endpoint: the region-wide overload returns every endpoint's
        // interfaces, and other tests in this class leave their own behind.
        List<String> fromTheService = new ArrayList<>();
        for (NetworkInterface ni : service.endpointNetworkInterfaces("us-east-1")) {
            if (("VPC Endpoint Interface " + endpointId).equals(ni.getDescription())) {
                fromTheService.add(ni.getNetworkInterfaceId());
            }
        }

        assertEquals(fromTheService, onTheWire,
                "the ids DescribeVpcEndpoints publishes must be the interfaces the "
                + "service reports, in the same order");
        assertEquals(1, onTheWire.size(),
                "the endpoint has one surviving subnet, so it reports one interface");
    }

    @Test
    void anEndpointOnASubnetWithoutIpv4StillAnswers() {
        // REGRESSION GUARD for the request path this change created. Deriving the
        // interfaces now happens while rendering CreateVpcEndpoint and
        // DescribeVpcEndpoints, and that derivation computes a private IP by splitting
        // the subnet's CidrBlock on dots. CreateSubnet stores whatever CidrBlock it is
        // given without validating the family, so the value is not guaranteed to be
        // dotted IPv4 -- and before the guard, parts[1] threw, turning an odd subnet into
        // a FAILED EC2 response instead of a degraded address. Previously the same
        // calculation ran only on the flow-log scheduler, where a throw was contained.
        String vpcId = createVpc("10.78.0.0/16");
        String subnetId = ec2Value("CreateSubnet", "CreateSubnetResponse.subnet.subnetId",
                "VpcId", vpcId, "CidrBlock", "2001:db8::/56",
                "AvailabilityZone", "us-east-1a");

        String endpointId = ec2Value("CreateVpcEndpoint",
                "CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId",
                "VpcId", vpcId, "ServiceName", "com.amazonaws.us-east-1.ec2",
                "VpcEndpointType", "Interface", "SubnetId.1", subnetId);

        // The assertion is that we get an ANSWER at all: every ec2Value above already
        // requires statusCode 200, so a throw in the rendering path fails this test at
        // CreateVpcEndpoint. Describe is exercised too, because it renders by the same
        // path and is the call Terraform makes between plan and apply.
        assertEquals(1, eniIdsOf(endpointId).size(),
                "one subnet, so one interface, whatever family its CIDR is");
    }

    @Test
    void twoSubnetsWithoutIpv4GetDistinctAddresses() {
        // The first fix for the non-IPv4 crash used a CONSTANT fallback network, which
        // turned a crash into a silent collision: the host octet is derived from the
        // ENDPOINT, so it is identical across that endpoint's interfaces, and on the IPv4
        // path the only thing making them distinct is each subnet's own network. Collapse
        // the network to a constant and every non-IPv4 subnet gets the same address.
        // Caught in review, not by the test above, which uses a single subnet and so
        // cannot see a collision at all.
        String vpcId = createVpc("10.79.0.0/16");
        String subnetA = ec2Value("CreateSubnet", "CreateSubnetResponse.subnet.subnetId",
                "VpcId", vpcId, "CidrBlock", "2001:db8:1::/64",
                "AvailabilityZone", "us-east-1a");
        String subnetB = ec2Value("CreateSubnet", "CreateSubnetResponse.subnet.subnetId",
                "VpcId", vpcId, "CidrBlock", "2001:db8:2::/64",
                "AvailabilityZone", "us-east-1b");

        String endpointId = ec2Value("CreateVpcEndpoint",
                "CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId",
                "VpcId", vpcId, "ServiceName", "com.amazonaws.us-east-1.ec2",
                "VpcEndpointType", "Interface",
                "SubnetId.1", subnetA, "SubnetId.2", subnetB);

        List<String> addresses = new ArrayList<>();
        for (NetworkInterface ni : service.endpointNetworkInterfaces("us-east-1")) {
            if (("VPC Endpoint Interface " + endpointId).equals(ni.getDescription())) {
                addresses.add(ni.getPrivateIpAddress());
            }
        }

        assertEquals(2, addresses.size(), "one interface per subnet");
        assertNotEquals(addresses.get(0), addresses.get(1),
                "each subnet's interface needs its own address, got " + addresses);
        // And the ids stay distinct too, which they already were -- endpointEniId mixes the
        // subnet in. Asserted so a future change cannot fix one and break the other.
        assertEquals(2, eniIdsOf(endpointId).size());
    }

    @Test
    void aGatewayEndpointReportsNoNetworkInterfaces() {
        String vpcId = createVpc("10.75.0.0/16");
        String routeTableId = ec2Value("CreateRouteTable",
                "CreateRouteTableResponse.routeTable.routeTableId", "VpcId", vpcId);

        String endpointId = ec2Value("CreateVpcEndpoint",
                "CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId",
                "VpcId", vpcId, "ServiceName", "com.amazonaws.us-east-1.s3",
                "VpcEndpointType", "Gateway", "RouteTableId.1", routeTableId);

        // A gateway endpoint is a route-table entry, not an ENI. This is a regression guard
        // on that, not a discriminator for the emission change: see the class javadoc for
        // why an empty element and an absent one are indistinguishable here.
        assertTrue(eniIdsOf(endpointId).isEmpty(),
                "a gateway endpoint owns no interfaces");
    }

    @Test
    void anInterfaceEndpointInOneSubnetReportsExactlyOneInterface() {
        String vpcId = createVpc("10.76.0.0/16");
        String subnetId = createSubnet(vpcId, "10.76.1.0/24", "us-east-1a");

        String endpointId = ec2Value("CreateVpcEndpoint",
                "CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId",
                "VpcId", vpcId, "ServiceName", "com.amazonaws.us-east-1.ecr.api",
                "VpcEndpointType", "Interface", "SubnetId.1", subnetId);

        assertEquals(1, eniIdsOf(endpointId).size());
    }
}
