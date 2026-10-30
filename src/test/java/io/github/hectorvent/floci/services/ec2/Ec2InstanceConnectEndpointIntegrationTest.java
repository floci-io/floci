package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.Ec2Requests.ec2;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

/**
 * CreateInstanceConnectEndpoint, DescribeInstanceConnectEndpoints and DeleteInstanceConnectEndpoint
 * over the EC2 Query wire: SecurityGroupId.N and TagSpecification.N on the way in, the response
 * envelopes on the way out. Every read is a separate Describe rather than the create's own response.
 */
@QuarkusTest
class Ec2InstanceConnectEndpointIntegrationTest {

    private static final String ITEM = "DescribeInstanceConnectEndpointsResponse.instanceConnectEndpointSet.item";
    private static final String CREATED = "CreateInstanceConnectEndpointResponse.instanceConnectEndpoint";

    @Test
    void createDescribeAndDeleteOverTheWire() {
        String vpcId = createVpc("10.81.0.0/16");
        String subnetId = createSubnet(vpcId, "10.81.1.0/24");
        String groupA = createGroup(vpcId, "eice-a");
        String groupB = createGroup(vpcId, "eice-b");
        String name = "eice-" + UUID.randomUUID();
        String token = UUID.randomUUID().toString();

        String id = create(subnetId, groupA, groupB, name, token)
                .post("/").then().statusCode(200)
                .body("CreateInstanceConnectEndpointResponse.clientToken", equalTo(token))
                .body(CREATED + ".instanceConnectEndpointId", matchesPattern("eice-[0-9a-f]{17}"))
                .body(CREATED + ".state", equalTo("create-complete"))
                .body(CREATED + ".subnetId", equalTo(subnetId))
                .extract().path(CREATED + ".instanceConnectEndpointId");

        // The replay answers with the same endpoint and makes no second one.
        create(subnetId, groupA, groupB, name, token)
                .post("/").then().statusCode(200)
                .body(CREATED + ".instanceConnectEndpointId", equalTo(id));

        ec2("DescribeInstanceConnectEndpoints", "test", "us-east-1")
                .formParam("Filter.1.Name", "tag:Name")
                .formParam("Filter.1.Value.1", name)
                .post("/").then().statusCode(200)
                .body(ITEM + ".size()", equalTo(1))
                .body(ITEM + ".instanceConnectEndpointId", equalTo(id))
                .body(ITEM + ".instanceConnectEndpointArn",
                        equalTo("arn:aws:ec2:us-east-1:000000000000:instance-connect-endpoint/" + id))
                .body(ITEM + ".vpcId", equalTo(vpcId))
                .body(ITEM + ".subnetId", equalTo(subnetId))
                .body(ITEM + ".ipAddressType", equalTo("ipv4"))
                .body(ITEM + ".preserveClientIp", equalTo("false"))
                .body(ITEM + ".dnsName", equalTo(id + ".localhost.floci.io"))
                .body(ITEM + ".publicDnsNames.ipv4.dnsName", equalTo(id + ".localhost.floci.io"))
                .body(ITEM + ".securityGroupIdSet.item", containsInAnyOrder(groupA, groupB))
                .body(ITEM + ".tagSet.item.find { it.key == 'Name' }.value", equalTo(name));

        ec2("DeleteInstanceConnectEndpoint", "test", "us-east-1")
                .formParam("InstanceConnectEndpointId", id)
                .post("/").then().statusCode(200)
                .body("DeleteInstanceConnectEndpointResponse.instanceConnectEndpoint.instanceConnectEndpointId",
                        equalTo(id))
                .body("DeleteInstanceConnectEndpointResponse.instanceConnectEndpoint.state",
                        equalTo("delete-in-progress"));

        ec2("DescribeInstanceConnectEndpoints", "test", "us-east-1")
                .formParam("InstanceConnectEndpointId.1", id)
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidInstanceConnectEndpointId.NotFound"));

        deleteAll(List.of(groupA, groupB), subnetId, vpcId);
    }

    private static RequestSpecification create(String subnetId, String groupA, String groupB, String name,
                                               String token) {
        return ec2("CreateInstanceConnectEndpoint", "test", "us-east-1")
                .formParam("SubnetId", subnetId)
                .formParam("SecurityGroupId.1", groupA)
                .formParam("SecurityGroupId.2", groupB)
                .formParam("TagSpecification.1.ResourceType", "instance-connect-endpoint")
                .formParam("TagSpecification.1.Tag.1.Key", "Name")
                .formParam("TagSpecification.1.Tag.1.Value", name)
                .formParam("ClientToken", token);
    }

    static String createVpc(String cidr) {
        return ec2("CreateVpc", "test", "us-east-1").formParam("CidrBlock", cidr)
                .post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
    }

    static String createSubnet(String vpcId, String cidr) {
        return ec2("CreateSubnet", "test", "us-east-1").formParam("VpcId", vpcId).formParam("CidrBlock", cidr)
                .post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
    }

    static String createGroup(String vpcId, String name) {
        return ec2("CreateSecurityGroup", "test", "us-east-1").formParam("VpcId", vpcId)
                .formParam("GroupName", name).formParam("GroupDescription", name)
                .post("/").then().statusCode(200).extract().path("CreateSecurityGroupResponse.groupId");
    }

    static void deleteAll(List<String> groups, String subnetId, String vpcId) {
        for (String group : groups) {
            ec2("DeleteSecurityGroup", "test", "us-east-1").formParam("GroupId", group)
                    .post("/").then().statusCode(200);
        }
        ec2("DeleteSubnet", "test", "us-east-1").formParam("SubnetId", subnetId).post("/").then().statusCode(200);
        ec2("DeleteVpc", "test", "us-east-1").formParam("VpcId", vpcId).post("/").then().statusCode(200);
    }
}
