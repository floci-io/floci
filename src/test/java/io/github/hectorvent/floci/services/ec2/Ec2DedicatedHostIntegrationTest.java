package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Arrays;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * EC2 Dedicated Hosts over the Query protocol, walked through the lifecycle terraform's
 * {@code aws_ec2_host} drives: AllocateHosts, DescribeHosts until available, ModifyHosts,
 * ReleaseHosts, then DescribeHosts showing the host released. Each step is read back through
 * a separate DescribeHosts rather than trusting the mutating call's own response.
 *
 * <p>Ordered because the cases walk one host through its lifecycle.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2DedicatedHostIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static String hostId;
    private static String instanceId;

    @Test
    @Order(1)
    void allocateReturnsAHostId() {
        hostId = given()
            .formParam("Action", "AllocateHosts")
            .formParam("InstanceType", "m5.large")
            .formParam("AvailabilityZone", "us-east-1a")
            .formParam("AutoPlacement", "off")
            .formParam("Quantity", "1")
            .formParam("TagSpecification.1.ResourceType", "dedicated-host")
            .formParam("TagSpecification.1.Tag.1.Key", "Name")
            .formParam("TagSpecification.1.Tag.1.Value", "dh-test")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AllocateHostsResponse.hostIdSet.item", startsWith("h-"))
            .extract().path("AllocateHostsResponse.hostIdSet.item");
    }

    @Test
    @Order(2)
    void describeShowsTheHostAvailableWithItsPropertiesAndTags() {
        given()
            .formParam("Action", "DescribeHosts")
            .formParam("HostId.1", hostId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeHostsResponse.hostSet.item.hostId", equalTo(hostId))
            .body("DescribeHostsResponse.hostSet.item.state", equalTo("available"))
            .body("DescribeHostsResponse.hostSet.item.availabilityZone", equalTo("us-east-1a"))
            .body("DescribeHostsResponse.hostSet.item.autoPlacement", equalTo("off"))
            .body("DescribeHostsResponse.hostSet.item.hostRecovery", equalTo("off"))
            .body("DescribeHostsResponse.hostSet.item.ownerId", equalTo("000000000000"))
            .body("DescribeHostsResponse.hostSet.item.hostProperties.instanceType", equalTo("m5.large"))
            // Allocated by type, so no family is echoed: terraform would read it as drift.
            .body("DescribeHostsResponse.hostSet.item.hostProperties.instanceFamily.size()", equalTo(0))
            .body("DescribeHostsResponse.hostSet.item.tagSet.item.key", equalTo("Name"))
            .body("DescribeHostsResponse.hostSet.item.tagSet.item.value", equalTo("dh-test"));
    }

    @Test
    @Order(3)
    void modifyIsReadBackThroughDescribe() {
        given()
            .formParam("Action", "ModifyHosts")
            .formParam("HostId.1", hostId)
            .formParam("AutoPlacement", "on")
            .formParam("HostRecovery", "on")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ModifyHostsResponse.successful.item", equalTo(hostId));

        given()
            .formParam("Action", "DescribeHosts")
            .formParam("HostId.1", hostId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeHostsResponse.hostSet.item.autoPlacement", equalTo("on"))
            .body("DescribeHostsResponse.hostSet.item.hostRecovery", equalTo("on"));
    }

    @Test
    @Order(4)
    void runInstancesOnTheHostReportsPlacementAndOccupiesIt() {
        instanceId = given()
            .formParam("Action", "RunInstances")
            .formParam("ImageId", "ami-0abcdef1234567891")
            .formParam("InstanceType", "m5.large")
            .formParam("MinCount", "1")
            .formParam("MaxCount", "1")
            .formParam("Placement.HostId", hostId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("RunInstancesResponse.instancesSet.item.instanceId");

        given()
            .formParam("Action", "DescribeInstances")
            .formParam("InstanceId.1", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.placement.hostId",
                    equalTo(hostId))
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.placement.tenancy",
                    equalTo("host"))
            .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.placement.availabilityZone",
                    equalTo("us-east-1a"));

        given()
            .formParam("Action", "DescribeHosts")
            .formParam("HostId.1", hostId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeHostsResponse.hostSet.item.instances.item.instanceId", equalTo(instanceId));
    }

    @Test
    @Order(5)
    void releaseOfAnOccupiedHostIsUnsuccessful() {
        given()
            .formParam("Action", "ReleaseHosts")
            .formParam("HostId.1", hostId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ReleaseHostsResponse.unsuccessful.item.resourceId", equalTo(hostId))
            .body("ReleaseHostsResponse.unsuccessful.item.error.code", equalTo("Client.InvalidHost.Occupied"))
            .body("ReleaseHostsResponse.unsuccessful.item.error.message",
                    equalTo("Dedicated host '" + hostId + "' cannot be released as it is occupied."));

        given()
            .formParam("Action", "TerminateInstances")
            .formParam("InstanceId.1", instanceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(6)
    void releaseLeavesTheHostDescribableAsReleased() {
        given()
            .formParam("Action", "ReleaseHosts")
            .formParam("HostId.1", hostId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ReleaseHostsResponse.successful.item", equalTo(hostId));

        given()
            .formParam("Action", "DescribeHosts")
            .formParam("HostId.1", hostId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeHostsResponse.hostSet.item.state", equalTo("released"));
    }

    @Test
    @Order(7)
    void describeOfAnUnknownHostIsNotFound() {
        given()
            .formParam("Action", "DescribeHosts")
            .formParam("HostId.1", "h-00000000000000000")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidHostID.NotFound"))
            .body("Response.Errors.Error.Message", containsString("h-00000000000000000"));
    }

    @Test
    @Order(8)
    void releaseOfAnUnknownOrAlreadyReleasedHostIsUnsuccessfulNotAnError() {
        given()
            .formParam("Action", "ReleaseHosts")
            .formParam("HostId.1", "h-00000000000000000")
            .formParam("HostId.2", hostId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ReleaseHostsResponse.unsuccessful.item.size()", equalTo(2))
            .body("ReleaseHostsResponse.unsuccessful.item[0].error.code", equalTo("Client.InvalidHostID.NotFound"))
            .body("ReleaseHostsResponse.unsuccessful.item[1].resourceId", equalTo(hostId));
    }

    @Test
    @Order(9)
    void allocationThatOmitsAutoPlacementDefaultsItToOff() {
        String defaultedHostId = given()
            .formParam("Action", "AllocateHosts")
            .formParam("InstanceFamily", "m5")
            .formParam("AvailabilityZone", "us-east-1a")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("AllocateHostsResponse.hostIdSet.item");

        given()
            .formParam("Action", "DescribeHosts")
            .formParam("HostId.1", defaultedHostId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeHostsResponse.hostSet.item.autoPlacement", equalTo("off"));
    }

    private static ValidatableResponse post(String... kv) {
        RequestSpecification r = given().header("Authorization", AUTH_HEADER);
        for (int i = 0; i < kv.length; i += 2) {
            r = r.formParam(kv[i], kv[i + 1]);
        }
        return r.when().post("/").then();
    }

    private static String allocate(String zone) {
        return post("Action", "AllocateHosts", "InstanceType", "m5.large", "AvailabilityZone", zone)
                .statusCode(200).extract().path("AllocateHostsResponse.hostIdSet.item");
    }

    private static int hostCount() {
        return post("Action", "DescribeHosts").statusCode(200)
                .extract().xmlPath().getList("DescribeHostsResponse.hostSet.item").size();
    }

    @Test
    @Order(10)
    void allocateRejectsANonexistentZoneAndStoresNothing() {
        int before = hostCount();
        post("Action", "AllocateHosts", "InstanceType", "m5.large", "AvailabilityZone", "us-east-1z")
                .statusCode(400).body(containsString("InvalidParameterValue"));
        org.junit.jupiter.api.Assertions.assertEquals(before, hostCount());
    }

    @Test
    @Order(11)
    void invalidHostSettingsAreRejectedOnAllocateAndModifyAndNotStored() {
        post("Action", "AllocateHosts", "InstanceType", "m5.large", "AvailabilityZone", "us-east-1a",
                "AutoPlacement", "banana").statusCode(400).body(containsString("InvalidParameterValue"));
        String id = allocate("us-east-1a");
        post("Action", "ModifyHosts", "HostId.1", id, "HostRecovery", "banana")
                .statusCode(400).body(containsString("InvalidParameterValue"));
        post("Action", "DescribeHosts", "HostId.1", id).statusCode(200)
                .body("DescribeHostsResponse.hostSet.item.hostRecovery", equalTo("off"));
    }

    @Test
    @Order(12)
    void launchPlacementThatContradictsTheHostIsRejected() {
        String id = allocate("us-east-1a");
        String subnetInB = post("Action", "DescribeSubnets", "Filter.1.Name", "availability-zone",
                "Filter.1.Value.1", "us-east-1b").statusCode(200)
                .extract().xmlPath().getList("DescribeSubnetsResponse.subnetSet.item.subnetId").get(0).toString();
        String[] base = {"Action", "RunInstances", "ImageId", "ami-0abcdef1234567891", "MinCount", "1",
                "MaxCount", "1", "Placement.HostId", id};
        post(concat(base, "InstanceType", "m5.large", "SubnetId", subnetInB))
                .statusCode(400).body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"));
        post(concat(base, "InstanceType", "m5.large", "Placement.Tenancy", "default"))
                .statusCode(400).body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"));
        post(concat(base, "InstanceType", "c5.large"))
                .statusCode(400).body("Response.Errors.Error.Code", equalTo("InvalidParameter"));
        post("Action", "DescribeHosts", "HostId.1", id).statusCode(200)
                .body("DescribeHostsResponse.hostSet.item.instances.item.size()", equalTo(0));
    }

    private static String[] concat(String[] a, String... b) {
        String[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
