package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.testing.Ec2Requests;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.not;

/**
 * EC2 Client VPN over the Query API, as {@code cloudposse/ec2-client-vpn/aws} drives it: an endpoint
 * with certificate authentication, a subnet association per Availability Zone, an authorization rule
 * for the VPC, routes, and the client configuration export.
 */
@QuarkusTest
class Ec2ClientVpnIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String ACM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261103/us-east-1/acm/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void anEndpointIsAssociatedAuthorizedRoutedExportedAndDeleted() {
        String vpcId = ec2("CreateVpc").formParam("CidrBlock", "10.80.0.0/16").post("/").then().statusCode(200)
                .extract().path("CreateVpcResponse.vpc.vpcId");
        String subnetA = subnet(vpcId, "10.80.1.0/24", "us-east-1a");
        String subnetSameZone = subnet(vpcId, "10.80.2.0/24", "us-east-1a");
        String domain = "vpn-" + UUID.randomUUID().toString().substring(0, 8) + ".example.com";
        String certificateArn = given().header("Authorization", ACM_AUTH)
                .header("X-Amz-Target", "CertificateManager.RequestCertificate")
                .contentType("application/x-amz-json-1.1")
                .body("{\"DomainName\": \"" + domain + "\"}")
                .post("/").then().statusCode(200).extract().path("CertificateArn");

        String id = ec2("CreateClientVpnEndpoint")
                .formParam("ClientCidrBlock", "172.16.0.0/20")
                .formParam("ServerCertificateArn", certificateArn)
                .formParam("Authentication.1.Type", "certificate-authentication")
                .formParam("Authentication.1.MutualAuthentication.ClientRootCertificateChainArn", certificateArn)
                .formParam("ConnectionLogOptions.Enabled", "false")
                .formParam("DnsServers.1", "10.80.0.2")
                .formParam("SplitTunnel", "true")
                .formParam("VpcId", vpcId)
                .formParam("Description", "team vpn")
                .formParam("TagSpecification.1.ResourceType", "client-vpn-endpoint")
                .formParam("TagSpecification.1.Tag.1.Key", "Name")
                .formParam("TagSpecification.1.Tag.1.Value", "team-vpn")
                .post("/").then().statusCode(200)
                .body("CreateClientVpnEndpointResponse.status.code", equalTo("pending-associate"))
                .body("CreateClientVpnEndpointResponse.dnsName", matchesRegex(
                        "\\*\\.cvpn-endpoint-[0-9a-f]{17}\\.prod\\.clientvpn\\.us-east-1\\.amazonaws\\.com"))
                .extract().path("CreateClientVpnEndpointResponse.clientVpnEndpointId");

        String item = "DescribeClientVpnEndpointsResponse.clientVpnEndpoint.item.";
        ec2("DescribeClientVpnEndpoints").formParam("ClientVpnEndpointId.1", id).post("/").then().statusCode(200)
                .body(item + "status.code", equalTo("pending-associate"))
                .body(item + "transportProtocol", equalTo("udp"))
                .body(item + "vpnPort", equalTo("443"))
                .body(item + "vpnProtocol", equalTo("openvpn"))
                .body(item + "sessionTimeoutHours", equalTo("24"))
                .body(item + "splitTunnel", equalTo("true"))
                .body(item + "vpcId", equalTo(vpcId))
                .body(item + "securityGroupIdSet.item", matchesRegex("sg-[0-9a-f]+"))
                .body(item + "authenticationOptions.item.type", equalTo("certificate-authentication"))
                .body(item + "authenticationOptions.item.mutualAuthentication.clientRootCertificateChain",
                        equalTo(certificateArn))
                .body(item + "connectionLogOptions.Enabled", equalTo("false"))
                .body(item + "dnsServer.item", equalTo("10.80.0.2"))
                .body(item + "tagSet.item.value", equalTo("team-vpn"));

        String associationId = ec2("AssociateClientVpnTargetNetwork").formParam("ClientVpnEndpointId", id)
                .formParam("SubnetId", subnetA).post("/").then().statusCode(200)
                .body("AssociateClientVpnTargetNetworkResponse.status.code", equalTo("associating"))
                .extract().path("AssociateClientVpnTargetNetworkResponse.associationId");
        ec2("DescribeClientVpnTargetNetworks").formParam("ClientVpnEndpointId", id).post("/").then()
                .body("DescribeClientVpnTargetNetworksResponse.clientVpnTargetNetworks.item.associationId",
                        equalTo(associationId))
                .body("DescribeClientVpnTargetNetworksResponse.clientVpnTargetNetworks.item.status.code",
                        equalTo("associated"))
                .body("DescribeClientVpnTargetNetworksResponse.clientVpnTargetNetworks.item.vpcId", equalTo(vpcId));
        ec2("DescribeClientVpnEndpoints").formParam("ClientVpnEndpointId.1", id).post("/").then()
                .body(item + "status.code", equalTo("available"));
        // One subnet per Availability Zone.
        ec2("AssociateClientVpnTargetNetwork").formParam("ClientVpnEndpointId", id)
                .formParam("SubnetId", subnetSameZone).post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"));

        ec2("AuthorizeClientVpnIngress").formParam("ClientVpnEndpointId", id)
                .formParam("TargetNetworkCidr", "10.80.0.0/16").formParam("AuthorizeAllGroups", "true")
                .formParam("Description", "Full VPC access").post("/").then().statusCode(200)
                .body("AuthorizeClientVpnIngressResponse.status.code", equalTo("authorizing"));
        ec2("DescribeClientVpnAuthorizationRules").formParam("ClientVpnEndpointId", id).post("/").then()
                .body(containsString("<groupId/>"))
                .body("DescribeClientVpnAuthorizationRulesResponse.authorizationRule.item.accessAll", equalTo("true"))
                .body("DescribeClientVpnAuthorizationRulesResponse.authorizationRule.item.status.code",
                        equalTo("active"));

        // The association added the route to the VPC through its subnet.
        String routes = "DescribeClientVpnRoutesResponse.routes.item";
        ec2("DescribeClientVpnRoutes").formParam("ClientVpnEndpointId", id).post("/").then()
                .body(routes + ".destinationCidr", equalTo("10.80.0.0/16"))
                .body(routes + ".origin", equalTo("associate"))
                .body(routes + ".type", equalTo("Nat"))
                .body(routes + ".targetSubnet", equalTo(subnetA))
                .body(routes + ".description", equalTo("Default Route"));
        ec2("CreateClientVpnRoute").formParam("ClientVpnEndpointId", id)
                .formParam("DestinationCidrBlock", "192.168.0.0/16").formParam("TargetVpcSubnetId", subnetA)
                .post("/").then().statusCode(200)
                .body("CreateClientVpnRouteResponse.status.code", equalTo("creating"));
        ec2("DescribeClientVpnRoutes").formParam("ClientVpnEndpointId", id).post("/").then()
                .body(routes + ".destinationCidr", hasItem("192.168.0.0/16"));
        ec2("DeleteClientVpnRoute").formParam("ClientVpnEndpointId", id)
                .formParam("DestinationCidrBlock", "192.168.0.0/16").formParam("TargetVpcSubnetId", subnetA)
                .post("/").then().statusCode(200);

        ec2("ExportClientVpnClientConfiguration").formParam("ClientVpnEndpointId", id).post("/").then()
                .statusCode(200)
                .body("ExportClientVpnClientConfigurationResponse.clientConfiguration", containsString(
                        "remote " + id + ".prod.clientvpn.us-east-1.amazonaws.com 443\n"))
                .body("ExportClientVpnClientConfigurationResponse.clientConfiguration",
                        containsString("proto udp\n"))
                .body("ExportClientVpnClientConfigurationResponse.clientConfiguration",
                        containsString("<ca>\n-----BEGIN CERTIFICATE-----"))
                .body("ExportClientVpnClientConfigurationResponse.clientConfiguration",
                        containsString("verify-x509-name " + domain + " name"));

        ec2("CreateTags").formParam("ResourceId.1", id).formParam("Tag.1.Key", "team")
                .formParam("Tag.1.Value", "data").post("/").then().statusCode(200);
        ec2("DescribeClientVpnEndpoints").formParam("ClientVpnEndpointId.1", id).post("/").then()
                .body(item + "tagSet.item.key", hasItem("team"));

        ec2("DeleteClientVpnEndpoint").formParam("ClientVpnEndpointId", id).post("/").then().statusCode(400)
                .body(containsString("<Code>IncorrectState</Code>"));
        ec2("DisassociateClientVpnTargetNetwork").formParam("ClientVpnEndpointId", id)
                .formParam("AssociationId", associationId).post("/").then().statusCode(200)
                .body("DisassociateClientVpnTargetNetworkResponse.status.code", equalTo("disassociating"));
        ec2("DescribeClientVpnRoutes").formParam("ClientVpnEndpointId", id).post("/").then()
                .body(not(containsString("<item>")));
        ec2("DeleteClientVpnEndpoint").formParam("ClientVpnEndpointId", id).post("/").then().statusCode(200)
                .body("DeleteClientVpnEndpointResponse.status.code", equalTo("deleting"));
        ec2("DescribeClientVpnEndpoints").formParam("ClientVpnEndpointId.1", id).post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidClientVpnEndpointId.NotFound</Code>"))
                .body(containsString("<Message>Endpoint " + id + " does not exist</Message>"));
    }

    @Test
    void describeFiltersSecurityGroupsSubnetPinsAndEquivalentCidrs() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String vpcId = ec2("CreateVpc").formParam("CidrBlock", "10.81.0.0/16").post("/").then().statusCode(200)
                .extract().path("CreateVpcResponse.vpc.vpcId");
        String subnetId = subnet(vpcId, "10.81.1.0/24", "us-east-1a");
        String certificateArn = "arn:aws:acm:us-east-1:000000000000:certificate/" + UUID.randomUUID();

        // Every requested security group must exist.
        refused(endpoint(certificateArn, "udp").formParam("VpcId", vpcId)
                        .formParam("SecurityGroupId.1", "sg-0000000000000000f"),
                "InvalidGroup.NotFound", "The security group 'sg-0000000000000000f' does not exist");

        String tagged = endpoint(certificateArn, "udp")
                .formParam("TagSpecification.1.ResourceType", "client-vpn-endpoint")
                .formParam("TagSpecification.1.Tag.1.Key", "Name")
                .formParam("TagSpecification.1.Tag.1.Value", "filter-" + suffix)
                .post("/").then().statusCode(200).extract().path("CreateClientVpnEndpointResponse.clientVpnEndpointId");
        String portal = endpoint(certificateArn, "tcp").formParam("SelfServicePortal", "enabled")
                .post("/").then().statusCode(200).extract().path("CreateClientVpnEndpointResponse.clientVpnEndpointId");

        String item = "DescribeClientVpnEndpointsResponse.clientVpnEndpoint.item";
        ec2("DescribeClientVpnEndpoints").formParam("Filter.1.Name", "tag:Name")
                .formParam("Filter.1.Value.1", "filter-" + suffix).post("/").then().statusCode(200)
                .body(item + ".clientVpnEndpointId", equalTo(tagged));
        ec2("DescribeClientVpnEndpoints").formParam("Filter.1.Name", "endpoint-id")
                .formParam("Filter.1.Value.1", tagged).formParam("Filter.1.Value.2", portal)
                .formParam("Filter.2.Name", "transport-protocol").formParam("Filter.2.Value.1", "tcp")
                .post("/").then().statusCode(200)
                .body(item + ".clientVpnEndpointId", equalTo(portal))
                .body(item + ".selfServicePortalUrl",
                        equalTo("https://self-service.clientvpn.amazonaws.com/endpoints/" + portal));
        refused(ec2("DescribeClientVpnEndpoints").formParam("Filter.1.Name", "bogus").formParam("Filter.1.Value.1", "x"),
                "InvalidParameterValue", "The filter 'bogus' is invalid");

        // An association pins its subnet until it is disassociated.
        String associationId = ec2("AssociateClientVpnTargetNetwork").formParam("ClientVpnEndpointId", tagged)
                .formParam("SubnetId", subnetId).post("/").then().statusCode(200)
                .extract().path("AssociateClientVpnTargetNetworkResponse.associationId");
        ec2("DeleteSubnet").formParam("SubnetId", subnetId).post("/").then().statusCode(400)
                .body(containsString("<Code>DependencyViolation</Code>"));
        String networks = "DescribeClientVpnTargetNetworksResponse.clientVpnTargetNetworks.item";
        ec2("DescribeClientVpnTargetNetworks").formParam("ClientVpnEndpointId", tagged)
                .formParam("Filter.1.Name", "vpc-id").formParam("Filter.1.Value.1", vpcId).post("/").then()
                .body(networks + ".associationId", equalTo(associationId));
        ec2("DescribeClientVpnTargetNetworks").formParam("ClientVpnEndpointId", tagged)
                .formParam("Filter.1.Name", "association-id").formParam("Filter.1.Value.1", "cvpn-assoc-0").post("/")
                .then().statusCode(200).body(not(containsString("<item>")));

        // The same network spelled with host bits is the same rule and the same route.
        ec2("AuthorizeClientVpnIngress").formParam("ClientVpnEndpointId", tagged)
                .formParam("TargetNetworkCidr", "10.81.0.0/16").formParam("AuthorizeAllGroups", "true")
                .post("/").then().statusCode(200);
        ec2("AuthorizeClientVpnIngress").formParam("ClientVpnEndpointId", tagged)
                .formParam("TargetNetworkCidr", "10.81.0.9/16").formParam("AuthorizeAllGroups", "true")
                .post("/").then().statusCode(400).body(containsString("<Code>InvalidParameterValue</Code>"));
        String rules = "DescribeClientVpnAuthorizationRulesResponse.authorizationRule.item";
        ec2("DescribeClientVpnAuthorizationRules").formParam("ClientVpnEndpointId", tagged)
                .formParam("Filter.1.Name", "destination-cidr").formParam("Filter.1.Value.1", "10.81.0.0/16")
                .post("/").then().body(rules + ".destinationCidr", equalTo("10.81.0.0/16"));
        ec2("DescribeClientVpnAuthorizationRules").formParam("ClientVpnEndpointId", tagged)
                .formParam("Filter.1.Name", "destination-cidr").formParam("Filter.1.Value.1", "10.99.0.0/16")
                .post("/").then().statusCode(200).body(not(containsString("<item>")));
        ec2("RevokeClientVpnIngress").formParam("ClientVpnEndpointId", tagged)
                .formParam("TargetNetworkCidr", "10.81.0.9/16").formParam("RevokeAllGroups", "true")
                .post("/").then().statusCode(200);

        String routes = "DescribeClientVpnRoutesResponse.routes.item";
        ec2("CreateClientVpnRoute").formParam("ClientVpnEndpointId", tagged)
                .formParam("DestinationCidrBlock", "192.168.0.0/16").formParam("TargetVpcSubnetId", subnetId)
                .post("/").then().statusCode(200);
        ec2("CreateClientVpnRoute").formParam("ClientVpnEndpointId", tagged)
                .formParam("DestinationCidrBlock", "192.168.0.5/16").formParam("TargetVpcSubnetId", subnetId)
                .post("/").then().statusCode(400).body(containsString("<Code>InvalidClientVpnDuplicateRoute</Code>"));
        ec2("DescribeClientVpnRoutes").formParam("ClientVpnEndpointId", tagged)
                .formParam("Filter.1.Name", "origin").formParam("Filter.1.Value.1", "associate").post("/").then()
                .body(routes + ".destinationCidr", equalTo("10.81.0.0/16"));
        ec2("DeleteClientVpnRoute").formParam("ClientVpnEndpointId", tagged)
                .formParam("DestinationCidrBlock", "192.168.0.5/16").formParam("TargetVpcSubnetId", subnetId)
                .post("/").then().statusCode(200);

        ec2("DisassociateClientVpnTargetNetwork").formParam("ClientVpnEndpointId", tagged)
                .formParam("AssociationId", associationId).post("/").then().statusCode(200);
        ec2("DeleteSubnet").formParam("SubnetId", subnetId).post("/").then().statusCode(200);
        ec2("DeleteClientVpnEndpoint").formParam("ClientVpnEndpointId", tagged).post("/").then().statusCode(200);
        ec2("DeleteClientVpnEndpoint").formParam("ClientVpnEndpointId", portal).post("/").then().statusCode(200);
    }

    @Test
    void unknownAndMalformedEndpointIdsAreRefusedWithAwsErrors() {
        refused(ec2("DescribeClientVpnEndpoints").formParam("ClientVpnEndpointId.1", "cvpn-endpoint-0123456789abcdef0"),
                "InvalidClientVpnEndpointId.NotFound", "Endpoint cvpn-endpoint-0123456789abcdef0 does not exist");
        refused(ec2("DescribeClientVpnTargetNetworks").formParam("ClientVpnEndpointId", "cvpn-endpoint-0123456789abcdef0"),
                "InvalidClientVpnEndpointId.NotFound", "Endpoint cvpn-endpoint-0123456789abcdef0 does not exist");
        refused(ec2("DescribeClientVpnEndpoints").formParam("ClientVpnEndpointId.1", "not-an-id"),
                "InvalidParameterValue", "ClientVpnEndpointId you provided is not valid");
        ec2("CreateClientVpnEndpoint").formParam("ClientCidrBlock", "172.16.0.0/20")
                .formParam("Authentication.1.Type", "certificate-authentication")
                .formParam("ConnectionLogOptions.Enabled", "false")
                .post("/").then().statusCode(400).body(containsString("<Code>MissingParameter</Code>"));
    }

    private static String subnet(String vpcId, String cidr, String zone) {
        return ec2("CreateSubnet").formParam("VpcId", vpcId).formParam("CidrBlock", cidr)
                .formParam("AvailabilityZone", zone).post("/").then().statusCode(200)
                .extract().path("CreateSubnetResponse.subnet.subnetId");
    }

    private static RequestSpecification endpoint(String certificateArn, String transport) {
        return ec2("CreateClientVpnEndpoint").formParam("ClientCidrBlock", "172.16.0.0/22")
                .formParam("ServerCertificateArn", certificateArn)
                .formParam("Authentication.1.Type", "certificate-authentication")
                .formParam("Authentication.1.MutualAuthentication.ClientRootCertificateChainArn", certificateArn)
                .formParam("ConnectionLogOptions.Enabled", "false")
                .formParam("TransportProtocol", transport);
    }

    private static void refused(RequestSpecification request, String code, String message) {
        ValidatableResponse response = request.post("/").then().statusCode(400);
        response.body("Response.Errors.Error.Code", equalTo(code))
                .body("Response.Errors.Error.Message", equalTo(message));
    }

    private static RequestSpecification ec2(String action) {
        return Ec2Requests.ec2(action, ACCOUNT, REGION);
    }
}
