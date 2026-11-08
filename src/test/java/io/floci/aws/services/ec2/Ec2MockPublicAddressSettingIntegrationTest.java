package io.floci.aws.services.ec2;

import io.floci.aws.testing.EnforcementFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mock instances under the default {@code mock-public-addresses: none}: a launch that asks for an
 * automatic public address gets none, while Elastic IPs work as before. The {@code fixed} setting
 * is covered by {@link Ec2MockFixedPublicAddressIntegrationTest}, {@code documentation} by
 * {@link Ec2MockPublicAddressIntegrationTest}.
 */
@QuarkusTest
class Ec2MockPublicAddressSettingIntegrationTest {
    private static final AtomicLong ACCOUNTS = new AtomicLong(723000000000L);
    static final String REGION = "us-east-1";
    static final String INSTANCE = "DescribeInstancesResponse.reservationSet.item.instancesSet.item.";

    @Test
    void byDefaultAMockInstanceGetsNoAutomaticAddressButKeepsElasticIps() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        String instance = launchInPublicSubnet(owner, "10.94");
        ValidatableResponse described = describe(owner, instance);
        assertEquals("running", described.extract().path(INSTANCE + "instanceState.name"));
        assertEquals("", described.extract().xmlPath().getString(INSTANCE + "ipAddress"));
        assertEquals("", described.extract().xmlPath().getString(INSTANCE + "dnsName"));

        ValidatableResponse allocated = request("AllocateAddress", owner).formParam("Domain", "vpc")
                .post("/").then().statusCode(200);
        String allocation = allocated.extract().path("AllocateAddressResponse.allocationId");
        String elastic = allocated.extract().path("AllocateAddressResponse.publicIp");
        assertTrue(elastic.matches("203\\.0\\.113\\.[0-9]+"), elastic);
        String association = request("AssociateAddress", owner).formParam("AllocationId", allocation)
                .formParam("InstanceId", instance).post("/").then().statusCode(200)
                .extract().path("AssociateAddressResponse.associationId");
        assertEquals(elastic, publicIp(owner, instance));
        request("StopInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        request("StartInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        assertEquals(elastic, publicIp(owner, instance));

        // Disassociating leaves the instance without an address rather than inventing one.
        request("DisassociateAddress", owner).formParam("AssociationId", association).post("/").then()
                .statusCode(200);
        assertEquals("", publicIp(owner, instance));
        request("StopInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        request("StartInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        assertEquals("", publicIp(owner, instance));

        request("TerminateInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        request("ReleaseAddress", owner).formParam("AllocationId", allocation).post("/").then().statusCode(200);
    }

    /** Launches an instance into a new subnet whose MapPublicIpOnLaunch is on. */
    static String launchInPublicSubnet(String owner, String prefix) {
        String vpc = request("CreateVpc", owner).formParam("CidrBlock", prefix + ".0.0/16")
                .post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        String subnet = request("CreateSubnet", owner).formParam("VpcId", vpc)
                .formParam("CidrBlock", prefix + ".1.0/24").formParam("AvailabilityZone", REGION + "a")
                .post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
        request("ModifySubnetAttribute", owner).formParam("SubnetId", subnet)
                .formParam("MapPublicIpOnLaunch.Value", true).post("/").then().statusCode(200);
        return request("RunInstances", owner).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro").formParam("MinCount", 1).formParam("MaxCount", 1)
                .formParam("SubnetId", subnet).post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
    }

    static String publicIp(String owner, String instance) {
        return describe(owner, instance).extract().xmlPath().getString(INSTANCE + "ipAddress");
    }

    static ValidatableResponse describe(String owner, String instance) {
        return request("DescribeInstances", owner).formParam("InstanceId.1", instance)
                .post("/").then().statusCode(200);
    }

    static RequestSpecification request(String action, String owner) {
        return given().formParam("Action", action).header("Authorization", EnforcementFixtures.auth(owner, "ec2"));
    }
}
