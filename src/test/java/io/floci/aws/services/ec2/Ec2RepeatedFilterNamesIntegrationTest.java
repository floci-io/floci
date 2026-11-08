package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Filter.N with one name given more than once, over the wire. AWS joins every filter with an AND,
 * so each repeated name narrows the answer, instead of the last filter of a name replacing the
 * ones before it.
 */
@QuarkusTest
class Ec2RepeatedFilterNamesIntegrationTest {

    /** An account of its own, so nothing another test creates shows up in these answers. */
    private static final String ACCOUNT = "246813579135";
    private static final String AMI = "ami-0abcdef1234567890";

    private static RequestSpecification request(String service, String action, String... params) {
        RequestSpecification request = given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                        + "/20261021/us-east-1/" + service + "/aws4_request, SignedHeaders=host, Signature=abc")
                .formParam("Action", action);
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request;
    }

    private static XmlPath ec2(String action, String... params) {
        return request("ec2", action, params).when().post("/").then().statusCode(200).extract().xmlPath();
    }

    private static String[] filter(String name, String... values) {
        String[] filter = new String[values.length + 1];
        filter[0] = name;
        System.arraycopy(values, 0, filter, 1, values.length);
        return filter;
    }

    private static XmlPath describe(String action, List<String> params, String[]... filters) {
        List<String> all = new ArrayList<>(params);
        for (int i = 0; i < filters.length; i++) {
            all.add("Filter." + (i + 1) + ".Name");
            all.add(filters[i][0]);
            for (int j = 1; j < filters[i].length; j++) {
                all.add("Filter." + (i + 1) + ".Value." + j);
                all.add(filters[i][j]);
            }
        }
        return ec2(action, all.toArray(String[]::new));
    }

    private static Set<String> values(XmlPath xml, String path) {
        return new LinkedHashSet<>(xml.getList(path, String.class));
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String runInstance(String... tagKeys) {
        List<String> params = new ArrayList<>(List.of("ImageId", AMI, "InstanceType", "t3.micro",
                "MinCount", "1", "MaxCount", "1"));
        if (tagKeys.length > 0) {
            params.addAll(List.of("TagSpecification.1.ResourceType", "instance"));
            for (int i = 0; i < tagKeys.length; i++) {
                params.addAll(List.of("TagSpecification.1.Tag." + (i + 1) + ".Key", tagKeys[i],
                        "TagSpecification.1.Tag." + (i + 1) + ".Value", "v"));
            }
        }
        return ec2("RunInstances", params.toArray(String[]::new))
                .getString("RunInstancesResponse.instancesSet.item.instanceId");
    }

    private static String createVpc(String cidr) {
        return ec2("CreateVpc", "CidrBlock", cidr).getString("CreateVpcResponse.vpc.vpcId");
    }

    private static String createSubnet(String vpcId, String cidr) {
        return ec2("CreateSubnet", "VpcId", vpcId, "CidrBlock", cidr)
                .getString("CreateSubnetResponse.subnet.subnetId");
    }

    private static String createSecurityGroup(String vpcId) {
        return ec2("CreateSecurityGroup", "GroupName", unique("repeated-"), "GroupDescription", "repeated filters",
                "VpcId", vpcId).getString("CreateSecurityGroupResponse.groupId");
    }

    @Test
    void describeInstancesAndsTwoTagKeyFilters() {
        String only = unique("RepeatedOnly");
        String shared = unique("RepeatedShared");
        String both = runInstance(only, shared);
        String one = runInstance(shared);
        String path = "DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceId";

        assertEquals(Set.of(both, one), values(describe("DescribeInstances", List.of(),
                filter("tag-key", shared)), path));
        assertEquals(Set.of(both), values(describe("DescribeInstances", List.of(),
                filter("tag-key", only), filter("tag-key", shared)), path));
        assertEquals(Set.of(both), values(describe("DescribeInstances", List.of(),
                filter("tag-key", shared), filter("tag-key", only)), path));
    }

    @Test
    void describeSubnetsInTwoVpcsAtOnceFindsNone() {
        String first = createVpc("10.161.0.0/16");
        String second = createVpc("10.162.0.0/16");
        createSubnet(first, "10.161.1.0/24");
        String secondSubnet = createSubnet(second, "10.162.1.0/24");
        String path = "DescribeSubnetsResponse.subnetSet.item.subnetId";

        assertEquals(Set.of(secondSubnet), values(describe("DescribeSubnets", List.of(),
                filter("vpc-id", second)), path));
        assertEquals(Set.of(), values(describe("DescribeSubnets", List.of(),
                filter("vpc-id", first), filter("vpc-id", second)), path));
        assertEquals(Set.of(secondSubnet), values(describe("DescribeSubnets", List.of(),
                filter("vpc-id", first, second), filter("vpc-id", second)), path));
    }

    @Test
    void describeVpcsAndsTwoTagFiltersOfOneKey() {
        String key = unique("Stage");
        String prod = createVpc("10.163.0.0/16");
        String dev = createVpc("10.164.0.0/16");
        ec2("CreateTags", "ResourceId.1", prod, "Tag.1.Key", key, "Tag.1.Value", "prod");
        ec2("CreateTags", "ResourceId.1", dev, "Tag.1.Key", key, "Tag.1.Value", "dev");
        String path = "DescribeVpcsResponse.vpcSet.item.vpcId";

        assertEquals(Set.of(prod), values(describe("DescribeVpcs", List.of(),
                filter("tag:" + key, "prod", "dev"), filter("tag:" + key, "prod")), path));
        assertEquals(Set.of(), values(describe("DescribeVpcs", List.of(),
                filter("tag:" + key, "prod"), filter("tag:" + key, "dev")), path));
    }

    @Test
    void describeNetworkAclsInTwoVpcsAtOnceFindsNone() {
        String first = createVpc("10.165.0.0/16");
        String second = createVpc("10.166.0.0/16");
        String path = "DescribeNetworkAclsResponse.networkAclSet.item.networkAclId";

        assertEquals(1, values(describe("DescribeNetworkAcls", List.of(), filter("vpc-id", second)), path).size());
        assertEquals(Set.of(), values(describe("DescribeNetworkAcls", List.of(),
                filter("vpc-id", first), filter("vpc-id", second)), path));
    }

    @Test
    void describeSecurityGroupRulesOfTwoGroupsAtOnceFindsNone() {
        String vpc = createVpc("10.167.0.0/16");
        String first = createSecurityGroup(vpc);
        String second = createSecurityGroup(vpc);
        String path = "DescribeSecurityGroupRulesResponse.securityGroupRuleSet.item.securityGroupRuleId";
        Set<String> secondRules = values(describe("DescribeSecurityGroupRules", List.of(),
                filter("group-id", second)), path);
        assertTrue(!secondRules.isEmpty(), "a new group carries its default egress rule");

        assertEquals(Set.of(), values(describe("DescribeSecurityGroupRules", List.of(),
                filter("group-id", first), filter("group-id", second)), path));
    }

    @Test
    void describeImagesAndsTwoNamePatterns() {
        String path = "DescribeImagesResponse.imagesSet.item.name";
        Set<String> arm = values(describe("DescribeImages", List.of(), filter("name", "*arm64*")), path);
        Set<String> both = values(describe("DescribeImages", List.of(),
                filter("name", "al2023-ami-*"), filter("name", "*arm64*")), path);

        assertTrue(!both.isEmpty() && arm.containsAll(both) && both.size() < arm.size(), both + " of " + arm);
        assertTrue(both.stream().allMatch(name -> name.startsWith("al2023-ami-")), both.toString());
        assertEquals(Set.of(), values(describe("DescribeImages", List.of(),
                filter("name", "repeated-first-*"), filter("name", "repeated-second-*")), path));
    }

    @Test
    void describeIamInstanceProfileAssociationsOfTwoInstancesAtOnceFindsNone() {
        String profile = unique("repeated-filters-");
        request("iam", "CreateInstanceProfile", "InstanceProfileName", profile)
                .post("/").then().statusCode(200);
        String first = runInstance();
        String second = runInstance();
        for (String instance : List.of(first, second)) {
            ec2("AssociateIamInstanceProfile", "InstanceId", instance, "IamInstanceProfile.Name", profile);
        }
        String path = "DescribeIamInstanceProfileAssociationsResponse.iamInstanceProfileAssociationSet.item.instanceId";

        assertEquals(Set.of(second), values(describe("DescribeIamInstanceProfileAssociations", List.of(),
                filter("instance-id", second)), path));
        assertEquals(Set.of(), values(describe("DescribeIamInstanceProfileAssociations", List.of(),
                filter("instance-id", first), filter("instance-id", second)), path));
    }

    @Test
    void describeInstanceTypeOfferingsAndsTwoInstanceTypeFilters() {
        String path = "DescribeInstanceTypeOfferingsResponse.instanceTypeOfferingSet.item.instanceType";
        List<String> region = List.of("LocationType", "region");

        assertEquals(Set.of("t3.micro"), values(describe("DescribeInstanceTypeOfferings", region,
                filter("instance-type", "t3.micro", "m5.large"), filter("instance-type", "t3.micro")), path));
        assertEquals(Set.of(), values(describe("DescribeInstanceTypeOfferings", region,
                filter("instance-type", "m5.large"), filter("instance-type", "t3.micro")), path));
    }

    @Test
    void describeSpotPriceHistoryAndsTwoAvailabilityZoneFilters() {
        String path = "DescribeSpotPriceHistoryResponse.spotPriceHistorySet.item.availabilityZone";

        assertEquals(Set.of("us-east-1b"), values(describe("DescribeSpotPriceHistory", List.of(),
                filter("instance-type", "t3.micro"), filter("availability-zone", "us-east-1b")), path));
        assertEquals(Set.of(), values(describe("DescribeSpotPriceHistory", List.of(),
                filter("instance-type", "t3.micro"), filter("availability-zone", "us-east-1a"),
                filter("availability-zone", "us-east-1b")), path));
    }

    @Test
    void searchTransitGatewayRoutesAndsTwoExactMatchFilters() {
        String gateway = ec2("CreateTransitGateway").getString("CreateTransitGatewayResponse.transitGateway.transitGatewayId");
        String routeTable = ec2("CreateTransitGatewayRouteTable", "TransitGatewayId", gateway)
                .getString("CreateTransitGatewayRouteTableResponse.transitGatewayRouteTable.transitGatewayRouteTableId");
        for (String cidr : List.of("10.168.0.0/16", "10.169.0.0/16")) {
            ec2("CreateTransitGatewayRoute", "TransitGatewayRouteTableId", routeTable,
                    "DestinationCidrBlock", cidr, "Blackhole", "true");
        }
        String path = "SearchTransitGatewayRoutesResponse.routeSet.item.destinationCidrBlock";
        List<String> table = List.of("TransitGatewayRouteTableId", routeTable);

        assertEquals(Set.of("10.169.0.0/16"), values(describe("SearchTransitGatewayRoutes", table,
                filter("route-search.exact-match", "10.169.0.0/16")), path));
        assertEquals(Set.of(), values(describe("SearchTransitGatewayRoutes", table,
                filter("route-search.exact-match", "10.168.0.0/16"),
                filter("route-search.exact-match", "10.169.0.0/16")), path));
    }

    @Test
    void describePrefixListsAndsTwoNameFilters() {
        String s3 = "com.amazonaws.us-east-1.s3";
        String dynamodb = "com.amazonaws.us-east-1.dynamodb";
        String path = "DescribePrefixListsResponse.prefixListSet.item.prefixListName";

        assertEquals(Set.of(dynamodb), values(describe("DescribePrefixLists", List.of(),
                filter("prefix-list-name", s3, dynamodb), filter("prefix-list-name", dynamodb)), path));
        assertEquals(Set.of(), values(describe("DescribePrefixLists", List.of(),
                filter("prefix-list-name", s3), filter("prefix-list-name", dynamodb)), path));
    }
}
