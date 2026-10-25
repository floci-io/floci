package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A public/private networking stack the way CDK lays one out: internet gateway and attachment,
 * a public subnet with a route table routing {@code 0.0.0.0/0} to the gateway and an association, an
 * EIP and a NAT gateway in that subnet, and a private route table routing to the NAT. Asserts the
 * {@code Ref} shapes ({@code Route} is {@code <RouteTableId>|<destination>}, {@code EIP} the public IP)
 * and the {@code Fn::GetAtt} attributes, that an {@code UpdateStack} with the same template keeps
 * every id (the legacy switch re-created all of them), that a changed subnet CIDR replaces the subnet
 * and the NAT gateway inside it and removes the displaced ones, and that {@code DeleteStack} removes
 * every one of the seven networking resources, which the legacy switch left behind. A second case
 * changes a route table's tag and a subnet's MapPublicIpOnLaunch in place next to a resource that
 * fails, and asserts the rollback puts both back. A third provisions a prefix list that a route
 * targets, updates it in place and rolls a change back. The VPC is
 * created through the EC2 API and passed in as a literal: {@code Ec2VpcCfnProvisioner} still
 * re-creates a VPC on every update (the #3033 fix), which would rightly replace everything in it.
 */
@QuarkusTest
class CloudFormationEc2NetworkingIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260907/us-east-1/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260907/us-east-1/ec2/aws4_request";
    private static final String STACK = "ec2-networking-it";

    private static String template(String vpcId, String publicSubnetCidr) {
        return template(vpcId, publicSubnetCidr, "{\"Key\": \"tier\", \"Value\": \"web\"}");
    }

    private static final String ROLLBACK_STACK = "ec2-networking-rollback-it";

    /** A subnet and a tagged route table; the second form flips the mutable properties and adds a resource that cannot be created. */
    private static String rollbackTemplate(String vpcId, boolean changed) {
        String failing = changed
                ? ",\n            \"Boom\": {\"Type\": \"AWS::SecretsManager::Secret\","
                  + " \"Properties\": {\"SecretString\": \"x\", \"GenerateSecretString\": {\"SecretStringTemplate\": \"{}\", \"GenerateStringKey\": \"p\"}}}"
                : "";
        return """
        {
          "Resources": {
            "Subnet": {"Type": "AWS::EC2::Subnet",
                       "Properties": {"VpcId": "%1$s", "CidrBlock": "10.41.0.0/24", "MapPublicIpOnLaunch": %2$s}},
            "Rt": {"Type": "AWS::EC2::RouteTable",
                   "Properties": {"VpcId": "%1$s", "Tags": [{"Key": "Name", "Value": "%3$s"}]}}%4$s
          },
          "Outputs": {"SubnetId": {"Value": {"Ref": "Subnet"}}, "RtId": {"Value": {"Ref": "Rt"}}}
        }
        """.formatted(vpcId, changed ? "false" : "true", changed ? "after" : "before", failing);
    }

    @Test
    void anInPlaceChangeNextToAFailingResourceIsRolledBack() throws Exception {
        String vpcId = between(ec2("CreateVpc", Map.of("CidrBlock", "10.41.0.0/16")), "<vpcId>", "</vpcId>");
        cloudFormation(ROLLBACK_STACK, "CreateStack", rollbackTemplate(vpcId, false));
        Map<String, String> out = XmlParser.extractPairs(describeStacks(ROLLBACK_STACK, "CREATE_COMPLETE"), "Outputs", "OutputKey", "OutputValue");
        assertTrue(ec2("DescribeRouteTables").contains("<value>before</value>"));
        assertTrue(subnetXml(out.get("SubnetId")).contains("<mapPublicIpOnLaunch>true</mapPublicIpOnLaunch>"));

        cloudFormation(ROLLBACK_STACK, "UpdateStack", rollbackTemplate(vpcId, true));

        describeStacks(ROLLBACK_STACK, "UPDATE_ROLLBACK_COMPLETE");
        String routeTables = ec2("DescribeRouteTables");
        assertTrue(routeTables.contains("<value>before</value>") && !routeTables.contains("<value>after</value>"),
                "the route table's prior tag must be back: " + routeTables);
        assertTrue(subnetXml(out.get("SubnetId")).contains("<mapPublicIpOnLaunch>true</mapPublicIpOnLaunch>"),
                "the subnet's prior MapPublicIpOnLaunch must be back");

        cloudFormation(ROLLBACK_STACK, "DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(ROLLBACK_STACK);
        ec2("DeleteVpc", Map.of("VpcId", vpcId));
    }

    /**
     * CreateNatGateway with an Elastic IP another gateway holds succeeds on AWS and leaves the
     * gateway failed; CloudFormation must still fail the resource rather than report it created.
     */
    @Test
    void aNatGatewayGivenAnAlreadyHeldElasticIpFailsTheStack() {
        String vpcId = between(ec2("CreateVpc", Map.of("CidrBlock", "10.42.0.0/16")), "<vpcId>", "</vpcId>");
        String nat = "{\"Type\": \"AWS::EC2::NatGateway\", %s\"Properties\": {\"SubnetId\": {\"Ref\": \"Subnet\"},"
                + " \"AllocationId\": {\"Fn::GetAtt\": [\"Eip\", \"AllocationId\"]}}}";
        cloudFormation("ec2-nat-shared-eip-it", "CreateStack", """
        {"Resources": {
          "Subnet": {"Type": "AWS::EC2::Subnet", "Properties": {"VpcId": "%s", "CidrBlock": "10.42.0.0/24"}},
          "Eip": {"Type": "AWS::EC2::EIP", "Properties": {"Domain": "vpc"}},
          "NatA": %s,
          "NatB": %s
        }}
        """.formatted(vpcId, nat.formatted(""), nat.formatted("\"DependsOn\": \"NatA\", ")));

        describeStacks("ec2-nat-shared-eip-it", "ROLLBACK_COMPLETE");
        assertFalse(ec2("DescribeNatGateways").contains(vpcId), "no gateway, failed or not, is left behind");
    }

    private static String subnetXml(String subnetId) {
        return ec2("DescribeSubnets", Map.of("SubnetId.1", subnetId));
    }

    private static final String PREFIX_LIST_STACK = "ec2-prefix-list-it";

    /**
     * A prefix list and a route to it through an internet gateway, the way a template sends a set of
     * CIDRs out of a VPC. {@code tags} is the Tags list, or empty for none; {@code failing} adds a
     * resource that cannot be created once the prefix list is done.
     */
    private static String prefixListTemplate(String vpcId, String family, int maxEntries, String entries,
                                             String tags, boolean failing) {
        String boom = failing
                ? ",\n            \"Boom\": {\"Type\": \"AWS::SecretsManager::Secret\", \"DependsOn\": \"Firewall\","
                  + " \"Properties\": {\"SecretString\": \"x\", \"GenerateSecretString\": {\"SecretStringTemplate\": \"{}\", \"GenerateStringKey\": \"p\"}}}"
                : "";
        return """
        {
          "Resources": {
            "Firewall": {"Type": "AWS::EC2::PrefixList",
                         "Properties": {"PrefixListName": "office-egress", "AddressFamily": "%2$s",
                                        "MaxEntries": %3$d, "Entries": %4$s%5$s}},
            "Igw": {"Type": "AWS::EC2::InternetGateway"},
            "Attach": {"Type": "AWS::EC2::VPCGatewayAttachment",
                       "Properties": {"VpcId": "%1$s", "InternetGatewayId": {"Ref": "Igw"}}},
            "Rt": {"Type": "AWS::EC2::RouteTable", "Properties": {"VpcId": "%1$s"}},
            "OfficeRoute": {"Type": "AWS::EC2::Route", "DependsOn": "Attach",
                            "Properties": {"RouteTableId": {"Ref": "Rt"}, "DestinationPrefixListId": {"Ref": "Firewall"},
                                           "GatewayId": {"Ref": "Igw"}}}%6$s
          },
          "Outputs": {
            "Ref": {"Value": {"Ref": "Firewall"}},
            "PrefixListId": {"Value": {"Fn::GetAtt": ["Firewall", "PrefixListId"]}},
            "Arn": {"Value": {"Fn::GetAtt": ["Firewall", "Arn"]}},
            "OwnerId": {"Value": {"Fn::GetAtt": ["Firewall", "OwnerId"]}},
            "RtId": {"Value": {"Ref": "Rt"}},
            "RouteRef": {"Value": {"Ref": "OfficeRoute"}}
          }
        }
        """.formatted(vpcId, family, maxEntries, entries, tags.isEmpty() ? "" : ", \"Tags\": " + tags, boom);
    }

    @Test
    void aPrefixListIsARealListThatARouteCanTargetAndUpdatesInPlace() {
        String vpcId = between(ec2("CreateVpc", Map.of("CidrBlock", "10.43.0.0/16")), "<vpcId>", "</vpcId>");
        String office = "[{\"Cidr\": \"192.0.2.0/24\", \"Description\": \"office\"}]";
        String offices = "[{\"Cidr\": \"192.0.2.0/24\", \"Description\": \"head office\"},"
                + " {\"Cidr\": \"198.51.100.0/24\", \"Description\": \"branch\"}]";
        String team = "[{\"Key\": \"team\", \"Value\": \"network\"}]";
        cloudFormation(PREFIX_LIST_STACK, "CreateStack", prefixListTemplate(vpcId, "IPv4", 2, office, team, false));

        Map<String, String> out = XmlParser.extractPairs(describeStacks(PREFIX_LIST_STACK, "CREATE_COMPLETE"),
                "Outputs", "OutputKey", "OutputValue");
        String id = out.get("Ref");
        assertTrue(id.startsWith("pl-"), "Ref of a prefix list is its EC2 id: " + id);
        assertEquals(id, out.get("PrefixListId"));
        assertEquals("arn:aws:ec2:us-east-1:" + out.get("OwnerId") + ":prefix-list/" + id, out.get("Arn"));
        String list = prefixListXml(id);
        assertTrue(list.contains("<prefixListName>office-egress</prefixListName>") && list.contains("<version>1</version>")
                && list.contains("<maxEntries>2</maxEntries>"), list);
        assertTrue(list.contains("<key>team</key>") && list.contains("<value>network</value>"), "tags must reach EC2: " + list);
        assertTrue(entriesXml(id).contains("<cidr>192.0.2.0/24</cidr>"));
        assertEquals(out.get("RtId") + "|" + id, out.get("RouteRef"), "the route is keyed by the list it targets");
        String routes = ec2("DescribeRouteTables", Map.of("RouteTableId.1", out.get("RtId")));
        assertTrue(routes.contains("<destinationPrefixListId>" + id + "</destinationPrefixListId>"), routes);

        // Entries change in place. A changed description is a removal and then an addition, two
        // EC2 calls because EC2 refuses one that removes and adds the same CIDR, so version 3.
        cloudFormation(PREFIX_LIST_STACK, "UpdateStack", prefixListTemplate(vpcId, "IPv4", 2, offices, team, false));
        assertEquals(id, XmlParser.extractPairs(describeStacks(PREFIX_LIST_STACK, "UPDATE_COMPLETE"),
                "Outputs", "OutputKey", "OutputValue").get("Ref"));
        assertTrue(prefixListXml(id).contains("<version>3</version>"));
        String entries = entriesXml(id);
        assertTrue(entries.contains("<description>head office</description>") && entries.contains("198.51.100.0/24"), entries);

        // Capacity alone changes in place and makes no new version.
        cloudFormation(PREFIX_LIST_STACK, "UpdateStack", prefixListTemplate(vpcId, "IPv4", 3, offices, team, false));
        describeStacks(PREFIX_LIST_STACK, "UPDATE_COMPLETE");
        list = prefixListXml(id);
        assertTrue(list.contains("<maxEntries>3</maxEntries>") && list.contains("<version>3</version>"), list);

        // Capacity and entries together, and another address family, fail the update and change nothing.
        cloudFormation(PREFIX_LIST_STACK, "UpdateStack", prefixListTemplate(vpcId, "IPv4", 4, office, team, false));
        describeStacks(PREFIX_LIST_STACK, "UPDATE_ROLLBACK_COMPLETE");
        String reason = CfnStackWaits.failedResourceReason(PREFIX_LIST_STACK, CFN_AUTH);
        assertTrue(reason.contains("cannot change MaxEntries (from 3 to 4) and Entries"), reason);
        cloudFormation(PREFIX_LIST_STACK, "UpdateStack", prefixListTemplate(vpcId, "IPv6", 3, offices, team, false));
        describeStacks(PREFIX_LIST_STACK, "UPDATE_ROLLBACK_COMPLETE");
        reason = CfnStackWaits.failedResourceReason(PREFIX_LIST_STACK, CFN_AUTH);
        assertTrue(reason.contains("cannot change the AddressFamily of prefix list " + id + " from IPv4 to IPv6"), reason);
        list = prefixListXml(id);
        assertTrue(list.contains("<maxEntries>3</maxEntries>") && list.contains("<version>3</version>")
                && list.contains("<addressFamily>IPv4</addressFamily>"), list);

        // An entry and tag change next to a resource that fails is put back.
        cloudFormation(PREFIX_LIST_STACK, "UpdateStack", prefixListTemplate(vpcId, "IPv4", 3, office,
                "[{\"Key\": \"team\", \"Value\": \"platform\"}]", true));
        describeStacks(PREFIX_LIST_STACK, "UPDATE_ROLLBACK_COMPLETE");
        entries = entriesXml(id);
        assertTrue(entries.contains("<description>head office</description>") && entries.contains("198.51.100.0/24"),
                "the prior entries must be back: " + entries);
        list = prefixListXml(id);
        assertTrue(list.contains("<value>network</value>") && !list.contains("<value>platform</value>"),
                "the prior tags must be back: " + list);

        // A rename is no replacement: the change set modifies the list alone, and the route that
        // targets it is left as it is.
        String renamed = prefixListTemplate(vpcId, "IPv4", 3, offices, team, false)
                .replace("\"office-egress\"", "\"office-egress-renamed\"");
        changeSet("CreateChangeSet", "rename", renamed);
        String preview = changeSet("DescribeChangeSet", "rename", null);
        assertTrue(preview.contains("<LogicalResourceId>Firewall</LogicalResourceId>")
                && preview.contains("<Replacement>False</Replacement>") && !preview.contains("<Replacement>True</Replacement>")
                && !preview.contains("<LogicalResourceId>OfficeRoute</LogicalResourceId>"), preview);
        changeSet("ExecuteChangeSet", "rename", null);
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(PREFIX_LIST_STACK, CFN_AUTH).status());
        assertEquals(id, XmlParser.extractPairs(describeStacks(PREFIX_LIST_STACK, "UPDATE_COMPLETE"),
                "Outputs", "OutputKey", "OutputValue").get("Ref"));
        assertTrue(prefixListXml(id).contains("<prefixListName>office-egress-renamed</prefixListName>"));

        // A template that drops Tags removes them.
        cloudFormation(PREFIX_LIST_STACK, "UpdateStack", prefixListTemplate(vpcId, "IPv4", 3, offices, "", false)
                .replace("\"office-egress\"", "\"office-egress-renamed\""));
        describeStacks(PREFIX_LIST_STACK, "UPDATE_COMPLETE");
        assertFalse(prefixListXml(id).contains("<key>team</key>"), "the dropped tags must be removed");

        cloudFormation(PREFIX_LIST_STACK, "DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(PREFIX_LIST_STACK);
        given().contentType("application/x-www-form-urlencoded").header("Authorization", EC2_AUTH)
                .formParam("Action", "GetManagedPrefixListEntries").formParam("Version", "2016-11-15")
                .formParam("PrefixListId", id)
        .when().post("/").then().statusCode(400).body(containsString("InvalidPrefixListID.NotFound"));
        ec2("DeleteVpc", Map.of("VpcId", vpcId));
    }

    /**
     * {@code Fn::GetAtt Version} is the list's version, and a resource whose integer property reads
     * it follows the list: here a second list sized by the first one's version.
     */
    @Test
    void aPrefixListVersionCanSizeAnotherList() {
        String stack = "ec2-prefix-list-version-it";
        String template = """
        {
          "Resources": {
            "First": {"Type": "AWS::EC2::PrefixList",
                      "Properties": {"PrefixListName": "version-source", "AddressFamily": "IPv4", "MaxEntries": 2,
                                     "Entries": %s}},
            "Second": {"Type": "AWS::EC2::PrefixList",
                       "Properties": {"PrefixListName": "version-sized", "AddressFamily": "IPv4",
                                      "MaxEntries": {"Fn::GetAtt": ["First", "Version"]}}}
          },
          "Outputs": {"SecondId": {"Value": {"Ref": "Second"}}}
        }
        """;
        cloudFormation(stack, "CreateStack", template.formatted("[{\"Cidr\": \"192.0.2.0/24\"}]"));
        String second = XmlParser.extractPairs(describeStacks(stack, "CREATE_COMPLETE"),
                "Outputs", "OutputKey", "OutputValue").get("SecondId");
        assertTrue(prefixListXml(second).contains("<maxEntries>1</maxEntries>"), prefixListXml(second));

        cloudFormation(stack, "UpdateStack",
                template.formatted("[{\"Cidr\": \"192.0.2.0/24\"}, {\"Cidr\": \"198.51.100.0/24\"}]"));
        describeStacks(stack, "UPDATE_COMPLETE");
        assertTrue(prefixListXml(second).contains("<maxEntries>2</maxEntries>"),
                "the first list's new version resizes the second: " + prefixListXml(second));

        cloudFormation(stack, "DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(stack);
    }

    /** EC2 checks a prefix list's tags; one it refuses fails the create and leaves no list behind. */
    @Test
    void aTagEc2RefusesFailsThePrefixListAndLeavesNoList() {
        String stack = "ec2-prefix-list-bad-tag-it";
        cloudFormation(stack, "CreateStack", """
        {"Resources": {"Firewall": {"Type": "AWS::EC2::PrefixList",
          "Properties": {"PrefixListName": "bad-tag-list", "AddressFamily": "IPv4", "MaxEntries": 1,
                         "Tags": [{"Key": "%s", "Value": "x"}]}}}}
        """.formatted("k".repeat(129)));

        describeStacks(stack, "ROLLBACK_COMPLETE");
        String reason = CfnStackWaits.failedResourceReason(stack, CFN_AUTH);
        assertTrue(reason.contains("Tag key exceeds the maximum length of 128 characters"), reason);
        assertFalse(ec2("DescribeManagedPrefixLists").contains("bad-tag-list"), "the new list is removed again");
        cloudFormation(stack, "DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(stack);
    }

    /** A change set action on the prefix list stack; {@code templateBody} only for CreateChangeSet. */
    private static String changeSet(String action, String changeSetName, String templateBody) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", PREFIX_LIST_STACK)
            .formParam("ChangeSetName", changeSetName);
        if (templateBody != null) {
            request.formParam("ChangeSetType", "UPDATE").formParam("TemplateBody", templateBody);
        }
        return request.when().post("/").then().statusCode(200).extract().asString();
    }

    private static String prefixListXml(String prefixListId) {
        return ec2("DescribeManagedPrefixLists", Map.of("PrefixListId.1", prefixListId));
    }

    private static String entriesXml(String prefixListId) {
        return ec2("GetManagedPrefixListEntries", Map.of("PrefixListId", prefixListId));
    }

    private static String template(String vpcId, String publicSubnetCidr, String extraSubnetTag) {
        return """
        {
          "Resources": {
            "Igw": {"Type": "AWS::EC2::InternetGateway"},
            "Attach": {"Type": "AWS::EC2::VPCGatewayAttachment",
                       "Properties": {"VpcId": "%1$s", "InternetGatewayId": {"Ref": "Igw"}}},
            "PublicSubnet": {"Type": "AWS::EC2::Subnet",
                             "Properties": {"VpcId": "%1$s", "CidrBlock": "%2$s",
                                            "AvailabilityZone": "us-east-1a", "MapPublicIpOnLaunch": true,
                                            "Tags": [{"Key": "Name", "Value": "public"}, %3$s]}},
            "PublicRt": {"Type": "AWS::EC2::RouteTable",
                         "Properties": {"VpcId": "%1$s", "Tags": [{"Key": "Name", "Value": "public-rt"}]}},
            "PublicRoute": {"Type": "AWS::EC2::Route", "DependsOn": "Attach",
                            "Properties": {"RouteTableId": {"Ref": "PublicRt"}, "DestinationCidrBlock": "0.0.0.0/0",
                                           "GatewayId": {"Ref": "Igw"}}},
            "PeerRoute": {"Type": "AWS::EC2::Route",
                          "Properties": {"RouteTableId": {"Ref": "PublicRt"}, "DestinationCidrBlock": "10.99.0.18/24",
                                         "GatewayId": {"Ref": "Igw"}}},
            "PublicAssoc": {"Type": "AWS::EC2::SubnetRouteTableAssociation",
                            "Properties": {"RouteTableId": {"Ref": "PublicRt"}, "SubnetId": {"Ref": "PublicSubnet"}}},
            "NatEip": {"Type": "AWS::EC2::EIP", "Properties": {"Domain": "vpc"}},
            "Nat": {"Type": "AWS::EC2::NatGateway",
                    "Properties": {"SubnetId": {"Ref": "PublicSubnet"}, "AllocationId": {"Fn::GetAtt": ["NatEip", "AllocationId"]}}},
            "PrivateRt": {"Type": "AWS::EC2::RouteTable", "Properties": {"VpcId": "%1$s"}},
            "PrivateRoute": {"Type": "AWS::EC2::Route",
                             "Properties": {"RouteTableId": {"Ref": "PrivateRt"}, "DestinationCidrBlock": "0.0.0.0/0",
                                            "NatGatewayId": {"Ref": "Nat"}}}
          },
          "Outputs": {
            "SubnetId": {"Value": {"Ref": "PublicSubnet"}},
            "SubnetAz": {"Value": {"Fn::GetAtt": ["PublicSubnet", "AvailabilityZone"]}},
            "IgwId": {"Value": {"Ref": "Igw"}},
            "PublicRtId": {"Value": {"Ref": "PublicRt"}},
            "PrivateRtId": {"Value": {"Ref": "PrivateRt"}},
            "PublicRouteRef": {"Value": {"Ref": "PublicRoute"}},
            "PublicRouteCidr": {"Value": {"Fn::GetAtt": ["PublicRoute", "CidrBlock"]}},
            "PeerRouteRef": {"Value": {"Ref": "PeerRoute"}},
            "AssocId": {"Value": {"Ref": "PublicAssoc"}},
            "EipRef": {"Value": {"Ref": "NatEip"}},
            "EipAllocationId": {"Value": {"Fn::GetAtt": ["NatEip", "AllocationId"]}},
            "NatId": {"Value": {"Ref": "Nat"}}
          }
        }
        """.formatted(vpcId, publicSubnetCidr, extraSubnetTag);
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void networkingStackExposesTheAwsRefShapesAndDeletesEverythingItCreated() throws Exception {
        String vpcId = between(ec2("CreateVpc", Map.of("CidrBlock", "10.40.0.0/16")), "<vpcId>", "</vpcId>");
        cloudFormation("CreateStack", template(vpcId, "10.40.0.0/24"));

        String stacks = describeStacks("CREATE_COMPLETE");
        Map<String, String> out = XmlParser.extractPairs(stacks, "Outputs", "OutputKey", "OutputValue");
        assertTrue(out.get("SubnetId").startsWith("subnet-"), out.get("SubnetId"));
        assertEquals("us-east-1a", out.get("SubnetAz"));
        assertTrue(out.get("IgwId").startsWith("igw-"), out.get("IgwId"));
        assertTrue(out.get("PublicRtId").startsWith("rtb-"), out.get("PublicRtId"));
        assertEquals(out.get("PublicRtId") + "|0.0.0.0/0", out.get("PublicRouteRef"),
                "Ref of a Route is the registry primary identifier, table id and destination");
        assertEquals("0.0.0.0/0", out.get("PublicRouteCidr"));
        assertEquals(out.get("PublicRtId") + "|10.99.0.0/24", out.get("PeerRouteRef"),
                "a non-canonical destination is keyed by the canonical CIDR the service stores");
        assertTrue(out.get("AssocId").startsWith("rtbassoc-"), out.get("AssocId"));
        assertTrue(out.get("EipAllocationId").startsWith("eipalloc-"), out.get("EipAllocationId"));
        assertFalse(out.get("EipRef").startsWith("eipalloc-"), "Ref of an EIP is its public IP: " + out.get("EipRef"));
        assertTrue(out.get("NatId").startsWith("nat-"), out.get("NatId"));

        String subnets = ec2("DescribeSubnets");
        assertTrue(subnets.contains(out.get("SubnetId")));
        assertTrue(subnets.contains("<key>tier</key>") && subnets.contains("<value>web</value>"), "subnet tags must reach EC2: " + subnets);
        String routeTables = ec2("DescribeRouteTables");
        assertTrue(routeTables.contains("<value>public-rt</value>"), "route table tags must reach EC2");
        assertTrue(routeTables.contains(out.get("PublicRtId")) && routeTables.contains(out.get("AssocId")));
        assertTrue(routeTables.contains(out.get("NatId")), "the private route must point at the NAT gateway");
        assertTrue(ec2("DescribeNatGateways").contains(out.get("NatId")));
        assertTrue(ec2("DescribeAddresses").contains(out.get("EipAllocationId")));
        assertTrue(ec2("DescribeInternetGateways").contains(out.get("IgwId")));

        // The same template again: every id survives (the switch re-created all seven).
        cloudFormation("UpdateStack", CfnUpdates.forceUpdate(template(vpcId, "10.40.0.0/24")));
        Map<String, String> again = XmlParser.extractPairs(describeStacks("UPDATE_COMPLETE"), "Outputs", "OutputKey", "OutputValue");
        again.remove(CfnUpdates.FORCED_OUTPUT);
        assertEquals(out, again, "an unchanged update must keep every networking resource");

        // Tags are mutable: a dropped key goes, a changed value is written, the subnet stays.
        cloudFormation("UpdateStack", template(vpcId, "10.40.0.0/24", "{\"Key\": \"env\", \"Value\": \"test\"}"));
        assertEquals(out.get("SubnetId"), XmlParser.extractPairs(describeStacks("UPDATE_COMPLETE"), "Outputs", "OutputKey", "OutputValue").get("SubnetId"));
        String retagged = ec2("DescribeSubnets");
        assertTrue(retagged.contains("<key>env</key>"), "the new tag must be written");
        assertFalse(retagged.contains("<key>tier</key>"), "the dropped tag must be removed");

        // CidrBlock is createOnly: the public subnet is replaced, and with it the NAT gateway and the
        // association that name it; the gateway and route tables stay. The replacement gateway gets a
        // new EIP: CloudFormation creates it before deleting the old one, and on AWS a gateway given
        // an Elastic IP the displaced gateway still holds is created failed. The old EIP stays in
        // the template for a second reason: Floci's update cleanup deletes removed resources before
        // the old copies of replaced ones (AWS deletes in reverse dependency order), so dropping the
        // EIP while replacing its gateway would try to release it while the old gateway still holds
        // it. Keeping NatEip sidesteps that; the cleanup order is a separate issue. The old EIP is
        // free once the displaced gateway is gone. oldEip is NatEip: out is NatEipB's outputs below.
        cloudFormation("UpdateStack", template(vpcId, "10.40.2.0/24").replace("NatEip", "NatEipB")
                .replace("\"NatEipB\": {\"Type\"",
                        "\"NatEip\": {\"Type\": \"AWS::EC2::EIP\", \"Properties\": {\"Domain\": \"vpc\"}},\n"
                        + "            \"NatEipB\": {\"Type\""));
        Map<String, String> replaced = XmlParser.extractPairs(describeStacks("UPDATE_COMPLETE"), "Outputs", "OutputKey", "OutputValue");
        assertFalse(out.get("SubnetId").equals(replaced.get("SubnetId")), "a changed CIDR must replace the subnet");
        assertFalse(out.get("NatId").equals(replaced.get("NatId")), "the NAT gateway follows its subnet");
        assertFalse(out.get("AssocId").equals(replaced.get("AssocId")), "the association follows its subnet");
        assertEquals(out.get("IgwId"), replaced.get("IgwId"));
        assertFalse(out.get("EipAllocationId").equals(replaced.get("EipAllocationId")), "the gateway takes the new EIP");
        assertFalse(ec2("DescribeAddresses", Map.of("AllocationId.1", out.get("EipAllocationId"))).contains("<associationId>"),
                "the displaced gateway's EIP must be free");
        String oldEip = out.get("EipAllocationId");
        assertEquals(out.get("PublicRtId"), replaced.get("PublicRtId"));
        assertEquals(out.get("PrivateRtId"), replaced.get("PrivateRtId"));
        assertFalse(ec2("DescribeSubnets").contains(out.get("SubnetId")), "the displaced subnet must be gone");
        assertFalse(ec2("DescribeNatGateways").contains(out.get("NatId")), "the displaced NAT gateway must be gone");
        assertTrue(ec2("DescribeSubnets").contains(replaced.get("SubnetId")));
        out = replaced;

        cloudFormation("DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(STACK);

        for (String id : List.of(out.get("SubnetId"), out.get("PublicRtId"), out.get("PrivateRtId"),
                out.get("AssocId"), out.get("NatId"), out.get("EipAllocationId"), oldEip, out.get("IgwId"))) {
            String everything = ec2("DescribeSubnets") + ec2("DescribeRouteTables") + ec2("DescribeNatGateways")
                    + ec2("DescribeAddresses") + ec2("DescribeInternetGateways");
            assertFalse(everything.contains(id), "stack delete must remove " + id);
        }
        ec2("DeleteVpc", Map.of("VpcId", vpcId));
    }

    private static String between(String haystack, String open, String close) {
        int start = haystack.indexOf(open);
        int end = haystack.indexOf(close, start);
        assertTrue(start >= 0 && end > start, "missing " + open + " in " + haystack);
        return haystack.substring(start + open.length(), end);
    }

    private static void cloudFormation(String action, String templateBody) {
        cloudFormation(STACK, action, templateBody);
    }

    private static void cloudFormation(String stack, String action, String templateBody) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stack);
        if (templateBody != null) {
            request.formParam("TemplateBody", templateBody);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String describeStacks(String expectedStatus) {
        return describeStacks(STACK, expectedStatus);
    }

    private static String describeStacks(String stack, String expectedStatus) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stack)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }

    private static String ec2(String action) {
        return ec2(action, Map.of());
    }

    private static String ec2(String action, Map<String, String> params) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", action)
            .formParam("Version", "2016-11-15");
        params.forEach(request::formParam);
        return request.when().post("/").then().statusCode(200).extract().asString();
    }
}
