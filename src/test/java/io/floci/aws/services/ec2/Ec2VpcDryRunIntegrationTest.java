package io.floci.aws.services.ec2;

import io.floci.aws.testing.QueryRequests;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DryRun on the VPC, subnet, internet gateway, route table, security group rule, Elastic IP and network
 * interface calls is answered with DryRunOperation and HTTP 412 once the request has passed the checks the real
 * call makes, and changes nothing.
 * A dry run the real call would refuse keeps that call's error.
 *
 * <p>No case changes the network, so every case shares one, built once for the class.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Ec2VpcDryRunIntegrationTest {

    /** The class works in an account of its own, so the describe calls list only the fixture's resources. */
    private static final String ACCOUNT = "726300000001";
    private static final String MISSING_VPC = "vpc-0123456789abcdef0";
    private static final String MISSING_GATEWAY = "igw-0123456789abcdef0";
    private static final String MISSING_TABLE = "rtb-0123456789abcdef0";
    private static final String MISSING_GROUP = "sg-0123456789abcdef0";
    private static final String MISSING_RULE = "sgr-0123456789abcdef0";
    private static final String MISSING_ALLOCATION = "eipalloc-0123456789abcdef0";
    private static final String MISSING_INTERFACE = "eni-0123456789abcdef0";
    private static final String MISSING_INSTANCE = "i-0123456789abcdef0";
    /** A tag on create in a TagSpecification member for {@code %s}, the resource type. */
    private static final String TAG = "TagSpecification.1.ResourceType=%s TagSpecification.1.Tag.1.Key=team"
            + " TagSpecification.1.Tag.1.Value=dry-run";
    /** One TCP port from 10.0.0.0/8, in an IpPermissions member; each request below gives the port it adds. */
    private static final String PERMISSION = "IpPermissions.1.IpProtocol=tcp IpPermissions.1.FromPort=%1$d"
            + " IpPermissions.1.ToPort=%1$d IpPermissions.1.IpRanges.1.CidrIp=10.0.0.0/8";

    /** The fixture's ids by the names the requests below give them in braces, such as {vpc}. */
    private static final Map<String, String> IDS = new HashMap<>();

    /** The test port: RestAssured learns it only per test, and the fixture is built before the first one. */
    private static int port;

    @TestHTTPResource("/")
    URI baseUri;

    /**
     * A VPC with two subnets, one holding a network interface; an internet gateway attached to it; a route
     * table routing 0.0.0.0/0 to the gateway and associated with the occupied subnet, and a second one with
     * neither. Beside them, an empty VPC and a gateway attached to nothing. In the first VPC, a security group
     * allowing port 22 in and port 443 out, from and to 10.0.0.0/8, and in the occupied subnet an instance with
     * a second network interface attached at index 1. An Elastic IP associated with the instance, and one
     * associated with nothing.
     */
    @BeforeAll
    void buildTheNetwork() {
        port = baseUri.getPort();
        IDS.clear();
        IDS.put("vpc", create("CreateVpc", "vpc.vpcId", "CidrBlock", "10.0.0.0/16"));
        IDS.put("emptyVpc", create("CreateVpc", "vpc.vpcId", "CidrBlock", "10.1.0.0/16"));
        IDS.put("subnet", create("CreateSubnet", "subnet.subnetId", "VpcId", IDS.get("vpc"),
                "CidrBlock", "10.0.1.0/24"));
        IDS.put("freeSubnet", create("CreateSubnet", "subnet.subnetId", "VpcId", IDS.get("vpc"),
                "CidrBlock", "10.0.2.0/24"));
        IDS.put("networkInterface", create("CreateNetworkInterface", "networkInterface.networkInterfaceId",
                "SubnetId", IDS.get("subnet")));
        IDS.put("gateway", create("CreateInternetGateway", "internetGateway.internetGatewayId"));
        IDS.put("spareGateway", create("CreateInternetGateway", "internetGateway.internetGatewayId"));
        ec2("AttachInternetGateway", "InternetGatewayId", IDS.get("gateway"), "VpcId", IDS.get("vpc"));
        IDS.put("table", create("CreateRouteTable", "routeTable.routeTableId", "VpcId", IDS.get("vpc")));
        IDS.put("spareTable", create("CreateRouteTable", "routeTable.routeTableId", "VpcId", IDS.get("vpc")));
        ec2("CreateRoute", "RouteTableId", IDS.get("table"), "DestinationCidrBlock", "0.0.0.0/0",
                "GatewayId", IDS.get("gateway"));
        IDS.put("association", create("AssociateRouteTable", "associationId", "RouteTableId", IDS.get("table"),
                "SubnetId", IDS.get("subnet")));
        IDS.put("group", create("CreateSecurityGroup", "groupId", "GroupName", "dry-run",
                "GroupDescription", "dry run", "VpcId", IDS.get("vpc")));
        IDS.put("ingressRule", authorize("AuthorizeSecurityGroupIngress", 22));
        IDS.put("egressRule", authorize("AuthorizeSecurityGroupEgress", 443));
        IDS.put("instance", create("RunInstances", "instancesSet.item.instanceId", "ImageId", "ami-0abcdef1234567890",
                "InstanceType", "t3.micro", "MinCount", "1", "MaxCount", "1", "SubnetId", IDS.get("subnet")));
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertEquals("running", ec2("DescribeInstances", "InstanceId.1", IDS.get("instance"))
                        .getString("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceState.name")));
        IDS.put("attachedInterface", create("CreateNetworkInterface", "networkInterface.networkInterfaceId",
                "SubnetId", IDS.get("subnet")));
        IDS.put("attachment", create("AttachNetworkInterface", "attachmentId",
                "NetworkInterfaceId", IDS.get("attachedInterface"), "InstanceId", IDS.get("instance"),
                "DeviceIndex", "1"));
        IDS.put("address", create("AllocateAddress", "allocationId", "Domain", "vpc"));
        IDS.put("associatedAddress", create("AllocateAddress", "allocationId", "Domain", "vpc"));
        IDS.put("addressAssociation", create("AssociateAddress", "associationId",
                "AllocationId", IDS.get("associatedAddress"), "InstanceId", IDS.get("instance")));
    }

    @AfterAll
    void deleteTheNetwork() {
        ec2("DisassociateAddress", "AssociationId", IDS.get("addressAssociation"));
        ec2("ReleaseAddress", "AllocationId", IDS.get("associatedAddress"));
        ec2("ReleaseAddress", "AllocationId", IDS.get("address"));
        ec2("DetachNetworkInterface", "AttachmentId", IDS.get("attachment"));
        ec2("DeleteNetworkInterface", "NetworkInterfaceId", IDS.get("attachedInterface"));
        ec2("TerminateInstances", "InstanceId.1", IDS.get("instance"));
        ec2("DeleteSecurityGroup", "GroupId", IDS.get("group"));
        ec2("DisassociateRouteTable", "AssociationId", IDS.get("association"));
        ec2("DeleteRouteTable", "RouteTableId", IDS.get("table"));
        ec2("DeleteRouteTable", "RouteTableId", IDS.get("spareTable"));
        ec2("DetachInternetGateway", "InternetGatewayId", IDS.get("gateway"), "VpcId", IDS.get("vpc"));
        ec2("DeleteInternetGateway", "InternetGatewayId", IDS.get("gateway"));
        ec2("DeleteInternetGateway", "InternetGatewayId", IDS.get("spareGateway"));
        ec2("DeleteNetworkInterface", "NetworkInterfaceId", IDS.get("networkInterface"));
        ec2("DeleteSubnet", "SubnetId", IDS.get("subnet"));
        ec2("DeleteSubnet", "SubnetId", IDS.get("freeSubnet"));
        ec2("DeleteVpc", "VpcId", IDS.get("vpc"));
        ec2("DeleteVpc", "VpcId", IDS.get("emptyVpc"));
    }

    /** A request each call's real call accepts in the fixture. */
    static Stream<String[]> acceptedRequests() {
        return Stream.of(
                new String[] {"DeleteVpc", "VpcId={emptyVpc}"},
                new String[] {"CreateDefaultVpc", ""},
                new String[] {"DeleteSubnet", "SubnetId={freeSubnet}"},
                new String[] {"CreateInternetGateway", ""},
                new String[] {"AttachInternetGateway", "InternetGatewayId={spareGateway} VpcId={emptyVpc}"},
                new String[] {"DetachInternetGateway", "InternetGatewayId={gateway} VpcId={vpc}"},
                new String[] {"DeleteInternetGateway", "InternetGatewayId={spareGateway}"},
                new String[] {"CreateRouteTable", "VpcId={vpc}"},
                new String[] {"DeleteRouteTable", "RouteTableId={spareTable}"},
                new String[] {"AssociateRouteTable", "RouteTableId={spareTable} SubnetId={freeSubnet}"},
                new String[] {"DisassociateRouteTable", "AssociationId={association}"},
                new String[] {"DeleteRoute", "RouteTableId={table} DestinationCidrBlock=0.0.0.0/0"},
                new String[] {"AuthorizeSecurityGroupIngress", "GroupId={group} " + PERMISSION.formatted(8080)},
                new String[] {"AuthorizeSecurityGroupEgress", "GroupId={group} " + PERMISSION.formatted(8443)},
                new String[] {"RevokeSecurityGroupIngress", "GroupId={group} " + PERMISSION.formatted(22)},
                new String[] {"RevokeSecurityGroupIngress", "GroupId={group} SecurityGroupRuleId.1={ingressRule}"},
                new String[] {"RevokeSecurityGroupEgress", "GroupId={group} " + PERMISSION.formatted(443)},
                new String[] {"RevokeSecurityGroupEgress", "GroupId={group} SecurityGroupRuleId.1={egressRule}"},
                new String[] {"ModifySecurityGroupRules", "GroupId={group}"
                        + " SecurityGroupRule.1.SecurityGroupRuleId={ingressRule}"
                        + " SecurityGroupRule.1.SecurityGroupRule.Description=changed"},
                new String[] {"UpdateSecurityGroupRuleDescriptionsIngress", "GroupId={group} "
                        + PERMISSION.formatted(22) + " IpPermissions.1.IpRanges.1.Description=changed"},
                new String[] {"UpdateSecurityGroupRuleDescriptionsEgress", "GroupId={group} "
                        + PERMISSION.formatted(443) + " IpPermissions.1.IpRanges.1.Description=changed"},
                new String[] {"AllocateAddress", "Domain=vpc"},
                new String[] {"AllocateAddress", "Domain=vpc " + TAG.formatted("elastic-ip")},
                new String[] {"AssociateAddress", "AllocationId={address} InstanceId={instance}"},
                new String[] {"AssociateAddress", "AllocationId={address} NetworkInterfaceId={networkInterface}"},
                new String[] {"AssociateAddress", "AllocationId={associatedAddress} InstanceId={instance}"},
                new String[] {"ReleaseAddress", "AllocationId={address}"},
                new String[] {"CreateNetworkInterface", "SubnetId={freeSubnet}"},
                new String[] {"CreateNetworkInterface", "SubnetId={freeSubnet} PrivateIpAddress=10.0.2.50 "
                        + TAG.formatted("network-interface")},
                new String[] {"DeleteNetworkInterface", "NetworkInterfaceId={networkInterface}"},
                new String[] {"AttachNetworkInterface", "NetworkInterfaceId={networkInterface} InstanceId={instance}"
                        + " DeviceIndex=2"},
                new String[] {"DetachNetworkInterface", "AttachmentId={attachment}"});
    }

    @ParameterizedTest
    @MethodSource("acceptedRequests")
    void aDryRunIsAnsweredWithDryRunOperationAndChangesNothing(String action, String params) {
        String before = snapshot();

        request(action, params(params)).formParam("DryRun", "true").post("/").then()
                .statusCode(412)
                .body("Response.Errors.Error.Code", equalTo("DryRunOperation"))
                .body("Response.Errors.Error.Message",
                        equalTo("Request would have succeeded, but DryRun flag is set."));

        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @MethodSource("acceptedRequests")
    void aDryRunValueThatIsNotABooleanIsRefused(String action, String params) {
        String before = snapshot();

        request(action, params(params)).formParam("DryRun", "maybe").post("/").then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
                .body("Response.Errors.Error.Message", equalTo("DryRun is not a valid boolean."));

        assertEquals(before, snapshot());
    }

    /** The real call refuses each of these; its dry run keeps the same error, and neither changes anything. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "DeleteVpc | VpcId={vpc} | DependencyViolation",
            "DeleteVpc | VpcId=" + MISSING_VPC + " | InvalidVpcID.NotFound",
            "DeleteSubnet | SubnetId={subnet} | DependencyViolation",
            "DeleteSubnet | SubnetId=subnet-0123456789abcdef0 | InvalidSubnetID.NotFound",
            "CreateInternetGateway | TagSpecification.1.ResourceType=internet-gateway"
                    + " TagSpecification.1.Tag.1.Key=aws:dry-run | InvalidParameterValue",
            "AttachInternetGateway | InternetGatewayId={spareGateway} VpcId=" + MISSING_VPC + " | InvalidVpcID.NotFound",
            "DetachInternetGateway | InternetGatewayId=" + MISSING_GATEWAY + " VpcId={vpc}"
                    + " | InvalidInternetGatewayID.NotFound",
            "DeleteInternetGateway | InternetGatewayId=" + MISSING_GATEWAY + " | InvalidInternetGatewayID.NotFound",
            "CreateRouteTable | VpcId=" + MISSING_VPC + " | InvalidVpcID.NotFound",
            "DeleteRouteTable | RouteTableId=" + MISSING_TABLE + " | InvalidRouteTableID.NotFound",
            "AssociateRouteTable | RouteTableId=" + MISSING_TABLE + " SubnetId={freeSubnet}"
                    + " | InvalidRouteTableID.NotFound",
            "DeleteRoute | RouteTableId={table} DestinationCidrBlock=10.0.0.0/16 | InvalidParameterValue",
            "DeleteRoute | RouteTableId={table} DestinationCidrBlock=10.9.0.0/16 | InvalidRoute.NotFound",
            "DeleteRoute | RouteTableId={table} | MissingParameter",
            "AuthorizeSecurityGroupIngress | GroupId=" + MISSING_GROUP + " IpPermissions.1.IpProtocol=-1"
                    + " | InvalidGroup.NotFound",
            "AuthorizeSecurityGroupIngress | GroupId={group} IpPermissions.1.IpProtocol=-1"
                    + " IpPermissions.1.PrefixListIds.1.PrefixListId=pl-0123456789abcdef0"
                    + " | InvalidPrefixListID.NotFound",
            "AuthorizeSecurityGroupIngress | GroupId={group} IpPermissions.1.IpProtocol=-1"
                    + " TagSpecification.1.ResourceType=security-group-rule TagSpecification.1.Tag.1.Key=aws:dry-run"
                    + " | InvalidParameter",
            "AuthorizeSecurityGroupEgress | GroupId=" + MISSING_GROUP + " IpPermissions.1.IpProtocol=-1"
                    + " | InvalidGroup.NotFound",
            "RevokeSecurityGroupIngress | GroupId=" + MISSING_GROUP + " IpPermissions.1.IpProtocol=-1"
                    + " | InvalidGroup.NotFound",
            "RevokeSecurityGroupIngress | GroupId={group} SecurityGroupRuleId.1={egressRule}"
                    + " | InvalidSecurityGroupRuleId.NotFound",
            "RevokeSecurityGroupEgress | GroupId={group} SecurityGroupRuleId.1=" + MISSING_RULE
                    + " | InvalidSecurityGroupRuleId.NotFound",
            "ModifySecurityGroupRules | GroupId=" + MISSING_GROUP + " SecurityGroupRule.1.SecurityGroupRuleId="
                    + MISSING_RULE + " | InvalidGroup.NotFound",
            "ModifySecurityGroupRules | GroupId={group} SecurityGroupRule.1.SecurityGroupRuleId=" + MISSING_RULE
                    + " SecurityGroupRule.1.SecurityGroupRule.Description=changed"
                    + " | InvalidSecurityGroupRuleId.NotFound",
            "AllocateAddress | TagSpecification.1.ResourceType=elastic-ip TagSpecification.1.Tag.1.Key=aws:dry-run"
                    + " | InvalidParameterValue",
            "AssociateAddress | AllocationId=" + MISSING_ALLOCATION + " InstanceId={instance}"
                    + " | InvalidAllocationID.NotFound",
            "AssociateAddress | AllocationId={address} | MissingParameter",
            "AssociateAddress | AllocationId={address} InstanceId={instance} NetworkInterfaceId={networkInterface}"
                    + " | InvalidParameterCombination",
            "AssociateAddress | AllocationId={address} NetworkInterfaceId={networkInterface}"
                    + " PrivateIpAddress=10.0.1.250 | InvalidParameterValue",
            "ReleaseAddress | AllocationId=" + MISSING_ALLOCATION + " | InvalidAllocationID.NotFound",
            "ReleaseAddress | AllocationId={associatedAddress} | InvalidIPAddress.InUse",
            "CreateNetworkInterface | SubnetId=subnet-0123456789abcdef0 | InvalidSubnetID.NotFound",
            "CreateNetworkInterface | Description=no-subnet | MissingParameter",
            "CreateNetworkInterface | SubnetId={freeSubnet} SecurityGroupId.1=" + MISSING_GROUP
                    + " | InvalidSecurityGroupID.NotFound",
            "DeleteNetworkInterface | NetworkInterfaceId=" + MISSING_INTERFACE + " | InvalidNetworkInterfaceID.NotFound",
            "DeleteNetworkInterface | NetworkInterfaceId={attachedInterface} | InvalidParameterValue",
            "AttachNetworkInterface | NetworkInterfaceId={networkInterface} InstanceId=" + MISSING_INSTANCE
                    + " DeviceIndex=2 | InvalidInstanceID.NotFound",
            "AttachNetworkInterface | NetworkInterfaceId={attachedInterface} InstanceId={instance} DeviceIndex=2"
                    + " | InvalidNetworkInterface.InUse",
            "AttachNetworkInterface | NetworkInterfaceId={networkInterface} InstanceId={instance} DeviceIndex=1"
                    + " | InvalidParameterValue",
            "DetachNetworkInterface | AttachmentId=eni-attach-0123456789abcdef0 | InvalidAttachmentID.NotFound",
            "DetachNetworkInterface | Force=true | MissingParameter"
    })
    void aDryRunTheRealCallWouldRefuseKeepsItsError(String action, String params, String code) {
        String before = snapshot();

        for (String dryRun : List.of("true", "false")) {
            request(action, params(params)).formParam("DryRun", dryRun).post("/").then()
                    .statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo(code));
        }

        assertEquals(before, snapshot());
    }

    /**
     * Everything these calls can change in the class's account, as the describe calls report it: the VPCs
     * and what DeleteVpc removes with one (its default security group and network ACL), the subnets, the
     * network interfaces, the internet gateways, the route tables with their routes and associations, the
     * security groups with their rules, the instances, the Elastic IPs and the tags.
     */
    private static String snapshot() {
        return String.join("\n",
                body("DescribeVpcs"),
                body("DescribeSubnets"),
                body("DescribeNetworkInterfaces"),
                body("DescribeSecurityGroups"),
                body("DescribeSecurityGroupRules"),
                body("DescribeNetworkAcls"),
                body("DescribeInternetGateways"),
                body("DescribeRouteTables"),
                body("DescribeInstances"),
                body("DescribeAddresses"),
                body("DescribeTags"));
    }

    /** A describe call's answer without the request id, the one part that differs on every call. */
    private static String body(String action) {
        return request(action).post("/").then().statusCode(200).extract().asString()
                .replaceAll("<requestId>[^<]*</requestId>", "");
    }

    /** {@code Key=Value} pairs separated by spaces, with a fixture's name in braces standing for its id. */
    private static String[] params(String spec) {
        if (spec == null || spec.isBlank()) {
            return new String[0];
        }
        return Stream.of(spec.trim().split(" +"))
                .flatMap(pair -> Stream.of(pair.split("=", 2)))
                .map(Ec2VpcDryRunIntegrationTest::withIds)
                .toArray(String[]::new);
    }

    private static String withIds(String part) {
        String result = part;
        for (Map.Entry<String, String> id : IDS.entrySet()) {
            result = result.replace("{" + id.getKey() + "}", id.getValue());
        }
        return result;
    }

    /** Adds {@code port} to the fixture's group in the direction {@code action} names, and returns the rule's id. */
    private static String authorize(String action, int port) {
        return create(action, "securityGroupRuleSet.item.securityGroupRuleId",
                params("GroupId={group} " + PERMISSION.formatted(port)));
    }

    /** Runs {@code action} and returns what its answer holds at {@code path}. */
    private static String create(String action, String path, String... params) {
        return ec2(action, params).getString(action + "Response." + path);
    }

    private static XmlPath ec2(String action, String... params) {
        return request(action, params).post("/").then().statusCode(200).extract().xmlPath();
    }

    private static RequestSpecification request(String action, String... params) {
        return QueryRequests.request(ACCOUNT, "ec2", action, params).port(port);
    }
}
