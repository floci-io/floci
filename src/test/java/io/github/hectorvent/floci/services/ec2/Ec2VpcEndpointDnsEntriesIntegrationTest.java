package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code DnsEntries} an interface endpoint reports, which is how a caller finds the name to
 * point a Route 53 alias at. AWS orders them by class: regional, zonal, then the private-DNS
 * name. Terraform's aws_vpc_endpoint exposes the list positionally as {@code dns_entry}, so the
 * first entry has to be the regional one.
 *
 * <p>The service segment is not the last segment of the service name: AWS drops
 * {@code com.amazonaws.<region>} and reverses what is left, so an {@code ecr.api} endpoint is
 * reached at {@code api.ecr.<region>.vpce.amazonaws.com}.
 *
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_DescribeVpcEndpoints.html">DescribeVpcEndpoints</a>
 */
@QuarkusTest
class Ec2VpcEndpointDnsEntriesIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static final String CN_AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/cn-north-1/ec2/aws4_request";

    private static final String ENTRIES =
            "DescribeVpcEndpointsResponse.vpcEndpointSet.item.dnsEntrySet.item";

    private String ec2Value(String action, String element, String... formParams) {
        return ec2ValueWithHeader(AUTH_HEADER, action, element, formParams);
    }

    private String ec2ValueWithHeader(String authHeader, String action, String element, String... formParams) {
        RequestSpecification req = given().formParam("Action", action)
                .header("Authorization", authHeader);
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

    private List<String> dnsNamesOf(String endpointId) {
        return describeEndpoint(endpointId).getList(ENTRIES + ".dnsName", String.class);
    }

    private List<String> hostedZoneIdsOf(String endpointId) {
        return describeEndpoint(endpointId).getList(ENTRIES + ".hostedZoneId", String.class);
    }

    @Test
    void anInterfaceEndpointReportsTheRegionalNameThenEachZoneThenThePrivateDnsName() {
        String vpcId = createVpc("10.70.0.0/16");
        String subnetA = createSubnet(vpcId, "10.70.1.0/24", "us-east-1a");
        String subnetB = createSubnet(vpcId, "10.70.2.0/24", "us-east-1b");

        String endpointId = ec2Value("CreateVpcEndpoint",
                "CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId",
                "VpcId", vpcId, "ServiceName", "com.amazonaws.us-east-1.ecr.api",
                "VpcEndpointType", "Interface", "SubnetId.1", subnetA, "SubnetId.2", subnetB);

        List<String> names = dnsNamesOf(endpointId);
        assertEquals(4, names.size(), "regional, one per zone, then private DNS, got " + names);
        assertThat(names.get(0), startsWith(endpointId + "-"));
        assertThat(names.get(0), endsWith(".api.ecr.us-east-1.vpce.amazonaws.com"));
        assertThat(names.get(1), endsWith("-us-east-1a.api.ecr.us-east-1.vpce.amazonaws.com"));
        assertEquals("api.ecr.us-east-1.amazonaws.com", names.get(3));

        List<String> zones = hostedZoneIdsOf(endpointId);
        assertEquals(zones.get(0), zones.get(1), "the regional and zonal names share one zone");
        assertThat(zones.get(3), not(zones.get(0)));

        assertEquals(names, dnsNamesOf(endpointId));
    }

    @Test
    void anEndpointServiceReportsItsOwnIdentifierAndNoPrivateDnsName() {
        String vpcId = createVpc("10.72.0.0/16");
        String subnetId = createSubnet(vpcId, "10.72.1.0/24", "us-east-1a");

        String endpointId = ec2Value("CreateVpcEndpoint",
                "CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId",
                "VpcId", vpcId, "ServiceName", "com.amazonaws.vpce.us-east-1.vpce-svc-0123456789abcdef0",
                "VpcEndpointType", "Interface", "SubnetId.1", subnetId);

        List<String> names = dnsNamesOf(endpointId);
        assertEquals(2, names.size(), "regional and one zonal name, no private DNS, got " + names);
        assertThat(names.get(0), endsWith(".vpce-svc-0123456789abcdef0.us-east-1.vpce.amazonaws.com"));
        assertThat(names.get(1),
                endsWith("-us-east-1a.vpce-svc-0123456789abcdef0.us-east-1.vpce.amazonaws.com"));
    }

    /**
     * The private DNS name AWS answers for each service floci advertises. Most are the service
     * token, a few are not, and the IoT data plane has no private name at all.
     */
    @ParameterizedTest
    @CsvSource({
        "com.amazonaws.us-east-1.monitoring, monitoring.us-east-1.amazonaws.com",
        "com.amazonaws.us-east-1.execute-api, *.execute-api.us-east-1.amazonaws.com",
        "com.amazonaws.us-east-1.ecr.api, api.ecr.us-east-1.amazonaws.com",
        "com.amazonaws.us-east-1.kinesis-streams, kinesis.us-east-1.amazonaws.com",
        "com.amazonaws.us-east-1.kinesis-firehose, firehose.us-east-1.amazonaws.com",
        "com.amazonaws.us-east-1.ecr.dkr, *.dkr.ecr.us-east-1.amazonaws.com",
        "com.amazonaws.us-east-1.iot.data, ''",
    })
    void thePrivateDnsNameIsTheOneAwsAnswersFor(String serviceName, String expectedPrivateName) {
        String vpcId = createVpc("10.76.0.0/16");
        String subnetId = createSubnet(vpcId, "10.76.1.0/24", "us-east-1a");

        String endpointId = ec2Value("CreateVpcEndpoint",
                "CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId",
                "VpcId", vpcId, "ServiceName", serviceName,
                "VpcEndpointType", "Interface", "SubnetId.1", subnetId);

        List<String> names = dnsNamesOf(endpointId);
        if (expectedPrivateName.isEmpty()) {
            assertEquals(2, names.size(), "regional and one zone, no private name, got " + names);
        } else {
            assertEquals(3, names.size(), "regional, one zone, then private name, got " + names);
            assertEquals(expectedPrivateName, names.get(2));
        }
    }

    @Test
    void aChinaRegionEndpointUsesItsPartitionDnsSuffix() {
        String vpcId = ec2ValueWithHeader(CN_AUTH_HEADER, "CreateVpc", "CreateVpcResponse.vpc.vpcId",
                "CidrBlock", "10.95.0.0/16");
        String subnetId = ec2ValueWithHeader(CN_AUTH_HEADER, "CreateSubnet", "CreateSubnetResponse.subnet.subnetId",
                "VpcId", vpcId, "CidrBlock", "10.95.1.0/24", "AvailabilityZone", "cn-north-1a");

        List<String> names = given()
            .formParam("Action", "CreateVpcEndpoint")
            .formParam("VpcId", vpcId)
            .formParam("ServiceName", "com.amazonaws.cn-north-1.monitoring")
            .formParam("VpcEndpointType", "Interface")
            .formParam("SubnetId.1", subnetId)
            .header("Authorization", CN_AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200)
            .extract().xmlPath()
            .getList("CreateVpcEndpointResponse.vpcEndpoint.dnsEntrySet.item.dnsName", String.class);

        assertEquals(3, names.size(), "regional, one zone, then private DNS, got " + names);
        assertThat(names, everyItem(endsWith(".amazonaws.com.cn")));
    }

    @Test
    void aGatewayEndpointReportsNoDnsEntries() {
        String vpcId = createVpc("10.71.0.0/16");
        String endpointId = ec2Value("CreateVpcEndpoint",
                "CreateVpcEndpointResponse.vpcEndpoint.vpcEndpointId",
                "VpcId", vpcId, "ServiceName", "com.amazonaws.us-east-1.s3", "VpcEndpointType", "Gateway");

        String body = given()
            .formParam("Action", "DescribeVpcEndpoints")
            .formParam("VpcEndpointId.1", endpointId)
            .header("Authorization", AUTH_HEADER)
        .when().post("/")
        .then().statusCode(200).extract().asString();
        assertThat(body, not(containsString("dnsEntrySet")));
    }
}
