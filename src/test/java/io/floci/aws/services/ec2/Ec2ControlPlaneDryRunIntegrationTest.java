package io.floci.aws.services.ec2;

import io.floci.aws.testing.QueryRequests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DryRun on the EBS encryption defaults, Transit Gateway, Client VPN, key pair, launch template and
 * describe calls is answered with DryRunOperation and HTTP 412 before the resource is resolved, so a
 * well-formed id that does not exist still gets it, and the request changes nothing.
 */
@QuarkusTest
class Ec2ControlPlaneDryRunIntegrationTest {

    private static final AtomicLong ACCOUNTS = new AtomicLong(726200000000L);
    private static final String TGW = "tgw-0123456789abcdef0";
    private static final String ATTACHMENT = "tgw-attach-0123456789abcdef0";
    private static final String ROUTE_TABLE = "tgw-rtb-0123456789abcdef0";
    private static final String CLIENT_VPN = "cvpn-endpoint-0123456789abcdef0";

    private final String account = Long.toString(ACCOUNTS.incrementAndGet());

    /** Calls with nothing to resolve, and calls AWS answers before it looks the resource up. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "GetEbsEncryptionByDefault|",
            "EnableEbsEncryptionByDefault|",
            "DisableEbsEncryptionByDefault|",
            "GetEbsDefaultKmsKeyId|",
            "ModifyEbsDefaultKmsKeyId|KmsKeyId=alias/aws/ebs",
            "ResetEbsDefaultKmsKeyId|",
            "DescribeAccountAttributes|",
            "DescribeCapacityReservations|",
            "DescribeTransitGateways|",
            "DescribeTransitGatewayAttachments|",
            "DescribeTransitGatewayConnects|",
            "DescribeTransitGatewayPeeringAttachments|",
            "DescribeTransitGatewayVpcAttachments|",
            "DescribeTransitGatewayRouteTables|",
            "DescribeClientVpnEndpoints|",
            "DescribeInstanceTypes|",
            "DescribePrefixLists|",
            "DescribeManagedPrefixLists|",
            "DescribeRegions|",
            "DescribeRouteTables|",
            "DescribeVpcEndpointServices|",
            "DescribeNatGateways|",
            "DescribeNetworkAcls|",
            "DescribeNetworkInterfaces|",
            "DeleteKeyPair|KeyName=no-such-key",
            "DisassociateAddress|AssociationId=eipassoc-0123456789abcdef0",
            "DeleteFlowLogs|FlowLogId.1=fl-0123456789abcdef0",
            "AcceptTransitGatewayPeeringAttachment|TransitGatewayAttachmentId=" + ATTACHMENT,
            "ApplySecurityGroupsToClientVpnTargetNetwork|ClientVpnEndpointId=" + CLIENT_VPN
                    + "&VpcId=vpc-0123456789abcdef0&SecurityGroupId.1=sg-0123456789abcdef0",
            "DescribeClientVpnTargetNetworks|ClientVpnEndpointId=" + CLIENT_VPN,
            "DescribeClientVpnAuthorizationRules|ClientVpnEndpointId=" + CLIENT_VPN,
            "DescribeClientVpnRoutes|ClientVpnEndpointId=" + CLIENT_VPN
    })
    void aDryRunIsAnsweredWithDryRunOperation(String action, String query) {
        dryRun(action, query).then()
                .statusCode(412)
                .body("Response.Errors.Error.Code", equalTo("DryRunOperation"))
                .body("Response.Errors.Error.Message",
                        equalTo("Request would have succeeded, but DryRun flag is set."));
    }

    /** A dry run the real call would refuse keeps the real call's error. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "DeleteTransitGateway|TransitGatewayId=" + TGW + "|InvalidTransitGatewayID.NotFound",
            "AssociateTransitGatewayRouteTable|TransitGatewayRouteTableId=" + ROUTE_TABLE
                    + "&TransitGatewayAttachmentId=" + ATTACHMENT + "|InvalidRouteTableID.NotFound",
            "DisableTransitGatewayRouteTablePropagation|TransitGatewayRouteTableId=" + ROUTE_TABLE
                    + "&TransitGatewayAttachmentId=" + ATTACHMENT + "|InvalidRouteTableID.NotFound",
            "CreateTransitGatewayRoute|TransitGatewayRouteTableId=" + ROUTE_TABLE
                    + "&DestinationCidrBlock=10.0.0.0/16&TransitGatewayAttachmentId=" + ATTACHMENT
                    + "|InvalidRouteTableID.NotFound",
            "AcceptTransitGatewayPeeringAttachment|TransitGatewayAttachmentId=not-an-attachment"
                    + "|InvalidTransitGatewayAttachmentID.Malformed",
            "DescribeTransitGateways|TransitGatewayIds.1=" + TGW + "|InvalidTransitGatewayID.NotFound",
            "ModifyClientVpnEndpoint|ClientVpnEndpointId=" + CLIENT_VPN + "&Description=d"
                    + "|InvalidClientVpnEndpointId.NotFound",
            "DeleteClientVpnEndpoint|ClientVpnEndpointId=" + CLIENT_VPN + "|InvalidClientVpnEndpointId.NotFound",
            "DisassociateClientVpnTargetNetwork|ClientVpnEndpointId=" + CLIENT_VPN
                    + "&AssociationId=cvpn-assoc-0123456789abcdef0|InvalidClientVpnEndpointId.NotFound",
            "AuthorizeClientVpnIngress|ClientVpnEndpointId=" + CLIENT_VPN
                    + "&TargetNetworkCidr=10.0.0.0/16&AuthorizeAllGroups=true|InvalidClientVpnEndpointId.NotFound",
            "DeleteClientVpnRoute|ClientVpnEndpointId=" + CLIENT_VPN
                    + "&DestinationCidrBlock=10.0.0.0/16|InvalidClientVpnEndpointId.NotFound",
            "ExportClientVpnClientConfiguration|ClientVpnEndpointId=" + CLIENT_VPN
                    + "|InvalidClientVpnEndpointId.NotFound",
            "DescribeClientVpnTargetNetworks|ClientVpnEndpointId=not-an-endpoint|InvalidParameterValue",
            "DeleteLaunchTemplate|LaunchTemplateName=no-such-template|InvalidLaunchTemplateName.NotFoundException",
            "ModifyCapacityReservation|CapacityReservationId=cr-0123456789abcdef0&InstanceCount=2"
                    + "|InvalidCapacityReservationId.NotFound",
            "DescribeCapacityReservations|CapacityReservationId.1=cr-0123456789abcdef0"
                    + "|InvalidCapacityReservationId.NotFound",
            "DescribeNetworkInterfaces|NetworkInterfaceId.1=eni-0123456789abcdef0"
                    + "|InvalidNetworkInterfaceID.NotFound",
            "ModifyEbsDefaultKmsKeyId||MissingParameter",
            "DeleteKeyPair||MissingParameter",
            "ExportTransitGatewayRoutes|TransitGatewayRouteTableId=" + ROUTE_TABLE + "&S3Bucket=b"
                    + "|InvalidRouteTableID.NotFound"
    })
    void aDryRunTheRealCallWouldRefuseKeepsItsError(String action, String query, String errorCode) {
        dryRun(action, query).then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo(errorCode));
        // The real call, without DryRun, is refused the same way.
        QueryRequests.request(account, "ec2", action).formParams(params(query)).post("/").then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo(errorCode));
    }

    /** A DryRun value that is not a boolean is refused as one. */
    @ParameterizedTest
    @CsvSource({
            "DeleteTransitGateway, maybe",
            "DeleteTransitGateway, ''",
            "DeleteKeyPair, maybe",
            "DeleteLaunchTemplate, ''",
            "DescribeRegions, maybe",
            "DescribeNatGateways, ''",
            "ModifyClientVpnEndpoint, maybe",
            "EnableEbsEncryptionByDefault, ''"
    })
    void aDryRunValueThatIsNotABooleanIsRefused(String action, String value) {
        QueryRequests.request(account, "ec2", action).formParam("DryRun", value)
                .formParam("TransitGatewayId", TGW).formParam("KeyName", "k")
                .formParam("LaunchTemplateName", "t").formParam("ClientVpnEndpointId", CLIENT_VPN)
                .post("/").then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
                .body("Response.Errors.Error.Message", equalTo("DryRun is not a valid boolean."));
    }

    @Test
    void aDryRunDisableLeavesEbsEncryptionByDefaultOn() {
        ec2("EnableEbsEncryptionByDefault");
        dryRun("DisableEbsEncryptionByDefault", null).then().statusCode(412);
        assertEquals("true", ec2("GetEbsEncryptionByDefault")
                .getString("GetEbsEncryptionByDefaultResponse.ebsEncryptionByDefault"));
    }

    @Test
    void aDryRunDeleteLeavesTheTransitGatewayAvailable() {
        String id = ec2("CreateTransitGateway")
                .getString("CreateTransitGatewayResponse.transitGateway.transitGatewayId");
        dryRun("DeleteTransitGateway", "TransitGatewayId=" + id).then().statusCode(412);
        assertEquals("available", ec2("DescribeTransitGateways", "TransitGatewayId.1", id)
                .getString("DescribeTransitGatewaysResponse.transitGatewaySet.item.state"));
    }

    @Test
    void aDryRunDeleteLeavesTheKeyPairAndTheLaunchTemplate() {
        ec2("CreateKeyPair", "KeyName", "dry-run-key");
        dryRun("DeleteKeyPair", "KeyName=dry-run-key").then().statusCode(412);
        assertEquals("dry-run-key", ec2("DescribeKeyPairs", "KeyName.1", "dry-run-key")
                .getString("DescribeKeyPairsResponse.keySet.item.keyName"));

        ec2("CreateLaunchTemplate", "LaunchTemplateName", "dry-run-template",
                "LaunchTemplateData.InstanceType", "t3.micro");
        dryRun("DeleteLaunchTemplate", "LaunchTemplateName=dry-run-template").then().statusCode(412);
        assertEquals("dry-run-template", ec2("DescribeLaunchTemplates", "LaunchTemplateName.1", "dry-run-template")
                .getString("DescribeLaunchTemplatesResponse.launchTemplates.item.launchTemplateName"));
    }

    @Test
    void aDryRunAssociateLeavesTheAttachmentUnassociated() {
        String gateway = ec2("CreateTransitGateway", "Options.DefaultRouteTableAssociation", "disable",
                "Options.DefaultRouteTablePropagation", "disable")
                .getString("CreateTransitGatewayResponse.transitGateway.transitGatewayId");
        String vpc = ec2("CreateVpc", "CidrBlock", "10.40.0.0/16").getString("CreateVpcResponse.vpc.vpcId");
        String subnet = ec2("CreateSubnet", "VpcId", vpc, "CidrBlock", "10.40.1.0/24", "AvailabilityZone",
                "us-east-1a").getString("CreateSubnetResponse.subnet.subnetId");
        String attachment = ec2("CreateTransitGatewayVpcAttachment", "TransitGatewayId", gateway, "VpcId", vpc,
                "SubnetIds.1", subnet).getString("CreateTransitGatewayVpcAttachmentResponse"
                + ".transitGatewayVpcAttachment.transitGatewayAttachmentId");
        String routeTable = ec2("CreateTransitGatewayRouteTable", "TransitGatewayId", gateway)
                .getString("CreateTransitGatewayRouteTableResponse.transitGatewayRouteTable"
                        + ".transitGatewayRouteTableId");

        dryRun("AssociateTransitGatewayRouteTable", "TransitGatewayRouteTableId=" + routeTable
                + "&TransitGatewayAttachmentId=" + attachment).then().statusCode(412);

        assertEquals(0, ec2("GetTransitGatewayRouteTableAssociations", "TransitGatewayRouteTableId", routeTable)
                .getList("GetTransitGatewayRouteTableAssociationsResponse.associations.item").size());
    }

    @Test
    void aDryRunCreateRouteWritesNoRoute() {
        String gateway = ec2("CreateTransitGateway")
                .getString("CreateTransitGatewayResponse.transitGateway.transitGatewayId");
        String routeTable = ec2("CreateTransitGatewayRouteTable", "TransitGatewayId", gateway)
                .getString("CreateTransitGatewayRouteTableResponse.transitGatewayRouteTable"
                        + ".transitGatewayRouteTableId");

        dryRun("CreateTransitGatewayRoute", "TransitGatewayRouteTableId=" + routeTable
                + "&DestinationCidrBlock=10.50.0.0/16&Blackhole=true").then().statusCode(412);

        assertEquals(0, ec2("SearchTransitGatewayRoutes", "TransitGatewayRouteTableId", routeTable,
                "Filter.1.Name", "type", "Filter.1.Value.1", "static")
                .getList("SearchTransitGatewayRoutesResponse.routeSet.item").size());
    }

    @Test
    void aDryRunModifyAndDeleteLeaveTheClientVpnEndpointAsItWas() {
        String endpoint = ec2("CreateClientVpnEndpoint", "ClientCidrBlock", "10.100.0.0/16",
                "ServerCertificateArn", "arn:aws:acm:us-east-1:000000000000:certificate/dry-run",
                "Authentication.1.Type", "certificate-authentication",
                "Authentication.1.MutualAuthentication.ClientRootCertificateChainArn",
                "arn:aws:acm:us-east-1:000000000000:certificate/dry-run-client",
                "ConnectionLogOptions.Enabled", "false", "Description", "original")
                .getString("CreateClientVpnEndpointResponse.clientVpnEndpointId");

        dryRun("ModifyClientVpnEndpoint", "ClientVpnEndpointId=" + endpoint + "&Description=changed")
                .then().statusCode(412);
        dryRun("DeleteClientVpnEndpoint", "ClientVpnEndpointId=" + endpoint).then().statusCode(412);

        XmlPath described = ec2("DescribeClientVpnEndpoints", "ClientVpnEndpointId.1", endpoint);
        assertEquals("original", described.getString("DescribeClientVpnEndpointsResponse.clientVpnEndpoint"
                + ".item.description"));
        assertEquals(endpoint, described.getString("DescribeClientVpnEndpointsResponse.clientVpnEndpoint"
                + ".item.clientVpnEndpointId"));
    }

    @Test
    void aDryRunModifyLeavesTheStoredCapacityReservationAsItWas() {
        String reservation = ec2("CreateCapacityReservation", "InstanceType", "t3.micro", "InstancePlatform",
                "Linux/UNIX", "AvailabilityZone", "us-east-1a", "InstanceCount", "1")
                .getString("CreateCapacityReservationResponse.capacityReservation.capacityReservationId");

        dryRun("ModifyCapacityReservation", "CapacityReservationId=" + reservation + "&InstanceCount=5")
                .then().statusCode(412);

        assertEquals("1", ec2("DescribeCapacityReservations", "CapacityReservationId.1", reservation)
                .getString("DescribeCapacityReservationsResponse.capacityReservationSet.item.totalInstanceCount"));
    }

    private Response dryRun(String action, String query) {
        return QueryRequests.request(account, "ec2", action).formParams(params(query))
                .formParam("DryRun", "true").post("/");
    }

    private static Map<String, String> params(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query != null) {
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                params.put(kv[0], kv[1]);
            }
        }
        return params;
    }

    private XmlPath ec2(String action, String... params) {
        return QueryRequests.request(account, "ec2", action, params).post("/").then().statusCode(200)
                .extract().xmlPath();
    }
}
