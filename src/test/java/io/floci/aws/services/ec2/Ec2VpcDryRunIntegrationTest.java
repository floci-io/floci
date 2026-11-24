package io.floci.aws.services.ec2;

import io.floci.aws.testing.QueryRequests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DryRun on the VPC, subnet, internet gateway, route table and security group rule calls is answered with
 * DryRunOperation and HTTP 412 once the request has passed the checks the real call makes, and changes nothing.
 * A dry run the real call would refuse keeps that call's error.
 */
@QuarkusTest
class Ec2VpcDryRunIntegrationTest {

    /** Each test works in an account of its own, so the describe calls list only the fixture's resources. */
    private static final AtomicLong ACCOUNTS = new AtomicLong(726300000000L);
    private static final String MISSING_VPC = "vpc-0123456789abcdef0";
    private static final String MISSING_GATEWAY = "igw-0123456789abcdef0";
    private static final String MISSING_TABLE = "rtb-0123456789abcdef0";
    private static final String MISSING_GROUP = "sg-0123456789abcdef0";
    private static final String MISSING_RULE = "sgr-0123456789abcdef0";
    /** One TCP port from 10.0.0.0/8, in an IpPermissions member; each request below gives the port it adds. */
    private static final String PERMISSION = "IpPermissions.1.IpProtocol=tcp IpPermissions.1.FromPort=%1$d"
            + " IpPermissions.1.ToPort=%1$d IpPermissions.1.IpRanges.1.CidrIp=10.0.0.0/8";

    private String account;
    /** The fixture's ids by the names the requests below give them in braces, such as {vpc}. */
    private final Map<String, String> ids = new HashMap<>();

    /**
     * A VPC with two subnets, one holding a network interface; an internet gateway attached to it; a route
     * table routing 0.0.0.0/0 to the gateway and associated with the occupied subnet, and a second one with
     * neither. Beside them, an empty VPC and a gateway attached to nothing. In the first VPC, a security group
     * allowing port 22 in and port 443 out, from and to 10.0.0.0/8.
     */
    @BeforeEach
    void buildTheNetwork() {
        account = Long.toString(ACCOUNTS.incrementAndGet());
        ids.clear();
        ids.put("vpc", create("CreateVpc", "vpc.vpcId", "CidrBlock", "10.0.0.0/16"));
        ids.put("emptyVpc", create("CreateVpc", "vpc.vpcId", "CidrBlock", "10.1.0.0/16"));
        ids.put("subnet", create("CreateSubnet", "subnet.subnetId", "VpcId", ids.get("vpc"),
                "CidrBlock", "10.0.1.0/24"));
        ids.put("freeSubnet", create("CreateSubnet", "subnet.subnetId", "VpcId", ids.get("vpc"),
                "CidrBlock", "10.0.2.0/24"));
        create("CreateNetworkInterface", "networkInterface.networkInterfaceId", "SubnetId", ids.get("subnet"));
        ids.put("gateway", create("CreateInternetGateway", "internetGateway.internetGatewayId"));
        ids.put("spareGateway", create("CreateInternetGateway", "internetGateway.internetGatewayId"));
        ec2("AttachInternetGateway", "InternetGatewayId", ids.get("gateway"), "VpcId", ids.get("vpc"));
        ids.put("table", create("CreateRouteTable", "routeTable.routeTableId", "VpcId", ids.get("vpc")));
        ids.put("spareTable", create("CreateRouteTable", "routeTable.routeTableId", "VpcId", ids.get("vpc")));
        ec2("CreateRoute", "RouteTableId", ids.get("table"), "DestinationCidrBlock", "0.0.0.0/0",
                "GatewayId", ids.get("gateway"));
        ids.put("association", create("AssociateRouteTable", "associationId", "RouteTableId", ids.get("table"),
                "SubnetId", ids.get("subnet")));
        ids.put("group", create("CreateSecurityGroup", "groupId", "GroupName", "dry-run",
                "GroupDescription", "dry run", "VpcId", ids.get("vpc")));
        ids.put("ingressRule", authorize("AuthorizeSecurityGroupIngress", 22));
        ids.put("egressRule", authorize("AuthorizeSecurityGroupEgress", 443));
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
                        + PERMISSION.formatted(443) + " IpPermissions.1.IpRanges.1.Description=changed"});
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
                    + " | InvalidSecurityGroupRuleId.NotFound"
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
     * Everything these calls can change in the test's account, as the describe calls report it: the VPCs
     * and what DeleteVpc removes with one (its default security group and network ACL), the subnets, the
     * network interface, the internet gateways, the route tables with their routes and associations, and the
     * security groups with their rules.
     */
    private String snapshot() {
        return String.join("\n",
                body("DescribeVpcs"),
                body("DescribeSubnets"),
                body("DescribeNetworkInterfaces"),
                body("DescribeSecurityGroups"),
                body("DescribeSecurityGroupRules"),
                body("DescribeNetworkAcls"),
                body("DescribeInternetGateways"),
                body("DescribeRouteTables"));
    }

    /** A describe call's answer without the request id, the one part that differs on every call. */
    private String body(String action) {
        return request(action).post("/").then().statusCode(200).extract().asString()
                .replaceAll("<requestId>[^<]*</requestId>", "");
    }

    /** {@code Key=Value} pairs separated by spaces, with a fixture's name in braces standing for its id. */
    private String[] params(String spec) {
        if (spec == null || spec.isBlank()) {
            return new String[0];
        }
        return Stream.of(spec.trim().split(" +"))
                .flatMap(pair -> Stream.of(pair.split("=", 2)))
                .map(this::withIds)
                .toArray(String[]::new);
    }

    private String withIds(String part) {
        String result = part;
        for (Map.Entry<String, String> id : ids.entrySet()) {
            result = result.replace("{" + id.getKey() + "}", id.getValue());
        }
        return result;
    }

    /** Adds {@code port} to the fixture's group in the direction {@code action} names, and returns the rule's id. */
    private String authorize(String action, int port) {
        return create(action, "securityGroupRuleSet.item.securityGroupRuleId",
                params("GroupId={group} " + PERMISSION.formatted(port)));
    }

    /** Runs {@code action} and returns what its answer holds at {@code path}. */
    private String create(String action, String path, String... params) {
        return ec2(action, params).getString(action + "Response." + path);
    }

    private XmlPath ec2(String action, String... params) {
        return request(action, params).post("/").then().statusCode(200).extract().xmlPath();
    }

    private RequestSpecification request(String action, String... params) {
        return QueryRequests.request(account, "ec2", action, params);
    }
}
