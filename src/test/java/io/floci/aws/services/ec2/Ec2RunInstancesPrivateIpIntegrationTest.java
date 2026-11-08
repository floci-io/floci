package io.floci.aws.services.ec2;

import io.floci.aws.testing.EnforcementFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicLong;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class Ec2RunInstancesPrivateIpIntegrationTest {
    // A fresh account per test, so what one test launches never holds an address another asks for.
    private static final AtomicLong ACCOUNTS = new AtomicLong(725000000000L);
    private static final String INSTANCE = "DescribeInstancesResponse.reservationSet.item.instancesSet.item.";

    @Test
    void topLevelPrivateIpAddressIsTheAddressTheInstanceGets() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        Network network = createSubnet(owner, "10.51.0.0/16", "10.51.1.0/24");

        String id = runInstance(owner)
                .formParam("SubnetId", network.subnetId())
                .formParam("PrivateIpAddress", "10.51.1.50")
                .post("/").then().statusCode(200)
                .body("RunInstancesResponse.instancesSet.item.privateIpAddress", equalTo("10.51.1.50"))
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");

        assertDescribedAddress(owner, id, "10.51.1.50");
        terminate(owner, id);
        delete(owner, network);
    }

    @Test
    void networkInterfacePrivateIpAddressIsTheAddressTheInstanceGets() {
        // The shape Terraform's aws_instance sends for private_ip together with subnet_id.
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        Network network = createSubnet(owner, "10.52.0.0/16", "10.52.1.0/24");

        String id = runInstance(owner)
                .formParam("NetworkInterface.1.DeviceIndex", "0")
                .formParam("NetworkInterface.1.SubnetId", network.subnetId())
                .formParam("NetworkInterface.1.PrivateIpAddress", "10.52.1.60")
                .post("/").then().statusCode(200)
                .body("RunInstancesResponse.instancesSet.item.privateIpAddress", equalTo("10.52.1.60"))
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");

        assertDescribedAddress(owner, id, "10.52.1.60");
        terminate(owner, id);
        delete(owner, network);
    }

    @Test
    void primaryPrivateIpAddressesEntryIsTheAddressTheInstanceGets() {
        // The shape AWS CLI v2 sends for --private-ip-address with --associate-public-ip-address.
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        Network network = createSubnet(owner, "10.56.0.0/16", "10.56.1.0/24");

        String id = runInstance(owner)
                .formParam("NetworkInterface.1.DeviceIndex", "0")
                .formParam("NetworkInterface.1.SubnetId", network.subnetId())
                .formParam("NetworkInterface.1.AssociatePublicIpAddress", "true")
                .formParam("NetworkInterface.1.PrivateIpAddresses.1.PrivateIpAddress", "10.56.1.60")
                .formParam("NetworkInterface.1.PrivateIpAddresses.1.Primary", "true")
                .post("/").then().statusCode(200)
                .body("RunInstancesResponse.instancesSet.item.privateIpAddress", equalTo("10.56.1.60"))
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");

        assertDescribedAddress(owner, id, "10.56.1.60");
        terminate(owner, id);
        delete(owner, network);
    }

    @Test
    void aSecondaryPrivateIpAddressesEntryDoesNotPlaceTheInstance() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        Network network = createSubnet(owner, "10.57.0.0/16", "10.57.1.0/24");

        String id = runInstance(owner)
                .formParam("NetworkInterface.1.DeviceIndex", "0")
                .formParam("NetworkInterface.1.SubnetId", network.subnetId())
                .formParam("NetworkInterface.1.PrivateIpAddresses.1.PrivateIpAddress", "10.57.1.70")
                .formParam("NetworkInterface.1.PrivateIpAddresses.1.Primary", "false")
                .formParam("NetworkInterface.1.PrivateIpAddresses.2.PrivateIpAddress", "10.57.1.60")
                .formParam("NetworkInterface.1.PrivateIpAddresses.2.Primary", "true")
                .post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");

        assertDescribedAddress(owner, id, "10.57.1.60");
        terminate(owner, id);
        delete(owner, network);
    }

    @Test
    void launchTemplatePrimaryInterfacePrivateIpAddressIsTheAddressTheInstanceGets() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        Network network = createSubnet(owner, "10.58.0.0/16", "10.58.1.0/24");
        String template = request("CreateLaunchTemplate", owner)
                .formParam("LaunchTemplateName", "fixed-private-address")
                .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                .formParam("LaunchTemplateData.NetworkInterface.1.DeviceIndex", 0)
                .formParam("LaunchTemplateData.NetworkInterface.1.SubnetId", network.subnetId())
                .formParam("LaunchTemplateData.NetworkInterface.1.PrivateIpAddress", "10.58.1.80")
                .post("/").then().statusCode(200)
                .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");

        String id = request("RunInstances", owner).formParam("MinCount", 1).formParam("MaxCount", 1)
                .formParam("LaunchTemplate.LaunchTemplateId", template)
                .post("/").then().statusCode(200)
                .body("RunInstancesResponse.instancesSet.item.privateIpAddress", equalTo("10.58.1.80"))
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");

        assertDescribedAddress(owner, id, "10.58.1.80");
        terminate(owner, id);
        request("DeleteLaunchTemplate", owner).formParam("LaunchTemplateId", template).post("/").then().statusCode(200);
        delete(owner, network);
    }

    @Test
    void privateIpAddressOutsideTheSubnetIsRejected() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        Network network = createSubnet(owner, "10.53.0.0/16", "10.53.1.0/24");

        runInstance(owner).formParam("SubnetId", network.subnetId()).formParam("PrivateIpAddress", "10.53.2.50")
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));

        assertInstancesIn(owner, network.subnetId(), 0);
        delete(owner, network);
    }

    @Test
    void privateIpAddressAnotherInstanceHoldsIsRejected() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        Network network = createSubnet(owner, "10.54.0.0/16", "10.54.1.0/24");
        String holder = runInstance(owner).formParam("SubnetId", network.subnetId())
                .formParam("PrivateIpAddress", "10.54.1.50")
                .post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");

        runInstance(owner).formParam("SubnetId", network.subnetId()).formParam("PrivateIpAddress", "10.54.1.50")
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidIPAddress.InUse"))
                .body("Response.Errors.Error.Message", equalTo("Address 10.54.1.50 is in use."));

        assertInstancesIn(owner, network.subnetId(), 1);
        terminate(owner, holder);
        delete(owner, network);
    }

    @Test
    void privateIpAddressForMoreThanOneInstanceIsRejected() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        Network network = createSubnet(owner, "10.55.0.0/16", "10.55.1.0/24");

        request("RunInstances", owner)
                .formParam("ImageId", "ami-0abcdef1234567890").formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "2").formParam("MaxCount", "2")
                .formParam("SubnetId", network.subnetId()).formParam("PrivateIpAddress", "10.55.1.50")
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"));

        assertInstancesIn(owner, network.subnetId(), 0);
        delete(owner, network);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void networkInterfacesRejectAnInstanceLevelPrivateIpAddress(boolean fromTemplate) {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        Network network = createSubnet(owner, "10.59.0.0/16", "10.59.1.0/24");
        RequestSpecification launch = request("RunInstances", owner).formParam("MinCount", 1)
                .formParam("MaxCount", 1).formParam("PrivateIpAddress", "10.59.1.50");
        String template = null;
        if (fromTemplate) {
            template = request("CreateLaunchTemplate", owner)
                    .formParam("LaunchTemplateName", "invalid-private-address-combination")
                    .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
                    .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                    .formParam("LaunchTemplateData.NetworkInterface.1.DeviceIndex", 0)
                    .formParam("LaunchTemplateData.NetworkInterface.1.SubnetId", network.subnetId())
                    .post("/").then().statusCode(200)
                    .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
            launch.formParam("LaunchTemplate.LaunchTemplateId", template);
        } else {
            launch.formParam("ImageId", "ami-0abcdef1234567890").formParam("InstanceType", "t3.micro")
                    .formParam("NetworkInterface.1.DeviceIndex", 0)
                    .formParam("NetworkInterface.1.SubnetId", network.subnetId());
        }

        launch.post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"))
                .body("Response.Errors.Error.Message", equalTo("Network interfaces and an instance-level "
                        + "private IP address may not be specified on the same request"));

        assertInstancesIn(owner, network.subnetId(), 0);
        if (template != null) {
            request("DeleteLaunchTemplate", owner).formParam("LaunchTemplateId", template)
                    .post("/").then().statusCode(200);
        }
        delete(owner, network);
    }

    private record Network(String vpcId, String subnetId) {}

    private static RequestSpecification runInstance(String owner) {
        return request("RunInstances", owner)
                .formParam("ImageId", "ami-0abcdef1234567890").formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1").formParam("MaxCount", "1");
    }

    private static RequestSpecification request(String action, String owner) {
        return given().formParam("Action", action).header("Authorization", EnforcementFixtures.auth(owner, "ec2"));
    }

    private static void assertDescribedAddress(String owner, String instanceId, String address) {
        request("DescribeInstances", owner).formParam("InstanceId.1", instanceId)
                .post("/").then().statusCode(200)
                .body(INSTANCE + "privateIpAddress", equalTo(address))
                .body(INSTANCE + "networkInterfaceSet.item.privateIpAddress", equalTo(address));
    }

    /** A rejected launch must not leave an instance behind in the subnet it named. */
    private static void assertInstancesIn(String owner, String subnetId, int expected) {
        request("DescribeInstances", owner)
                .formParam("Filter.1.Name", "subnet-id").formParam("Filter.1.Value.1", subnetId)
                .post("/").then().statusCode(200)
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.size()", equalTo(expected));
    }

    private static void terminate(String owner, String instanceId) {
        request("TerminateInstances", owner).formParam("InstanceId.1", instanceId).post("/").then().statusCode(200);
    }

    private static Network createSubnet(String owner, String vpcCidr, String subnetCidr) {
        String vpcId = request("CreateVpc", owner).formParam("CidrBlock", vpcCidr)
                .post("/").then().statusCode(200)
                .extract().path("CreateVpcResponse.vpc.vpcId");
        String subnetId = request("CreateSubnet", owner).formParam("VpcId", vpcId).formParam("CidrBlock", subnetCidr)
                .post("/").then().statusCode(200)
                .extract().path("CreateSubnetResponse.subnet.subnetId");
        return new Network(vpcId, subnetId);
    }

    private static void delete(String owner, Network network) {
        request("DeleteSubnet", owner).formParam("SubnetId", network.subnetId()).post("/").then().statusCode(200);
        request("DeleteVpc", owner).formParam("VpcId", network.vpcId()).post("/").then().statusCode(200);
    }
}
