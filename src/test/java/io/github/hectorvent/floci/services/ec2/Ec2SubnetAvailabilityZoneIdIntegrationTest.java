package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * CreateSubnet never read AvailabilityZoneId and hardcoded every new subnet's zone id to the
 * region's first zone, so a subnet asked for use1-az2 came back in use1-az1, and even
 * a subnet placed correctly by zone <em>name</em> reported a zone id that contradicted it.
 * Anything selecting subnets by zone id (Terraform's aws_subnet.availability_zone_id, and any
 * client pairing subnets with the DescribeAvailabilityZones list) therefore saw one zone.
 */
@QuarkusTest
class Ec2SubnetAvailabilityZoneIdIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private String vpcId;

    @BeforeEach
    void createVpc() {
        vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.90.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");
    }

    @Test
    void theZoneIdFollowsTheZoneTheSubnetWasPlacedIn() {
        given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.90.1.0/24")
            .formParam("AvailabilityZone", "us-east-1b")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateSubnetResponse.subnet.availabilityZone", equalTo("us-east-1b"))
            .body("CreateSubnetResponse.subnet.availabilityZoneId", equalTo("use1-az2"));
    }

    @Test
    void aSubnetCanBePlacedByZoneIdAlone() {
        String subnetId = given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.90.2.0/24")
            .formParam("AvailabilityZoneId", "use1-az3")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateSubnetResponse.subnet.availabilityZoneId", equalTo("use1-az3"))
            .body("CreateSubnetResponse.subnet.availabilityZone", equalTo("us-east-1c"))
            .extract().path("CreateSubnetResponse.subnet.subnetId");

        // ... and it reads back the same way, not just in the create response.
        given()
            .formParam("Action", "DescribeSubnets")
            .formParam("SubnetId.1", subnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSubnetsResponse.subnetSet.item.availabilityZoneId", equalTo("use1-az3"));
    }

    /**
     * The zone id a subnet reports has to be one DescribeAvailabilityZones actually publishes,
     * or a client that pairs the two lists finds no subnet in the zone it just looked up.
     */
    @Test
    void theZoneIdMatchesTheZoneListTheSameClientCanRead() {
        given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.90.3.0/24")
            .formParam("AvailabilityZone", "us-east-1c")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateSubnetResponse.subnet.availabilityZoneId", equalTo("use1-az3"));

        given()
            .formParam("Action", "DescribeAvailabilityZones")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeAvailabilityZonesResponse.availabilityZoneInfo.item.zoneId",
                    hasItem("use1-az3"));
    }

    /**
     * Issue #5580: the AWS Load Balancer Controller resolves each subnet's zone by passing its
     * AvailabilityZoneId to DescribeAvailabilityZones --zone-ids, the default VPC's subnets
     * included, so every id a subnet reports has to be accepted there.
     */
    @Test
    void everyDefaultSubnetZoneIdCanBeLookedUpByZoneId() {
        String auth = "AWS4-HMAC-SHA256 Credential=test/20260205/ap-southeast-1/ec2/aws4_request";
        XmlPath subnets = given()
            .formParam("Action", "DescribeSubnets")
            .formParam("Filter.1.Name", "default-for-az")
            .formParam("Filter.1.Value.1", "true")
            .header("Authorization", auth)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath();
        List<String> zoneNames = subnets.getList("DescribeSubnetsResponse.subnetSet.item.availabilityZone");
        List<String> zoneIds = subnets.getList("DescribeSubnetsResponse.subnetSet.item.availabilityZoneId");

        assertFalse(zoneIds.isEmpty());
        for (int i = 0; i < zoneIds.size(); i++) {
            given()
                .formParam("Action", "DescribeAvailabilityZones")
                .formParam("ZoneId.1", zoneIds.get(i))
                .header("Authorization", auth)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeAvailabilityZonesResponse.availabilityZoneInfo.item.zoneName",
                        equalTo(zoneNames.get(i)));
        }
    }

    @Test
    void aZoneAndZoneIdThatDisagreeAreRefusedRatherThanSilentlyResolved() {
        given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.90.4.0/24")
            .formParam("AvailabilityZone", "us-east-1a")
            .formParam("AvailabilityZoneId", "use1-az2")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"));
    }

    @Test
    void aZoneAndZoneIdNamingTheSameZoneAreAccepted() {
        given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.90.5.0/24")
            .formParam("AvailabilityZone", "us-east-1b")
            .formParam("AvailabilityZoneId", "use1-az2")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateSubnetResponse.subnet.availabilityZone", equalTo("us-east-1b"));
    }

    /**
     * A well-formed id past the zones this region publishes is refused too. Resolving it would put
     * the subnet in a zone DescribeAvailabilityZones does not list, leaving a client unable to
     * pair the two: the same inconsistency the hardcoded az1 produced, only quieter.
     */
    @Test
    void aZoneIdBeyondThePublishedZonesIsRefused() {
        given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.90.7.0/24")
            .formParam("AvailabilityZoneId", "use1-az4")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
            .body("Response.Errors.Error.Message", containsString("use1-az1 to use1-az3"));
    }

    @Test
    void aMalformedZoneIdIsRefused() {
        given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.90.6.0/24")
            .formParam("AvailabilityZoneId", "not-a-zone-id")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
    }

    @Test
    void aNonModeledZoneNameIsRefused() {
        given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.90.8.0/24")
            .formParam("AvailabilityZone", "us-east-1-custom")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
            .body("Response.Errors.Error.Message", containsString(
                    "Value (us-east-1-custom) for parameter availabilityZone is invalid. Subnets can "
                            + "currently only be created in the following availability zones: "
                            + "us-east-1a, us-east-1b, us-east-1c."));
    }

    @Test
    void aZoneNameBeyondModeledRangeIsRefused() {
        given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.90.9.0/24")
            .formParam("AvailabilityZone", "us-east-1aa")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
            .body("Response.Errors.Error.Message", containsString(
                    "Value (us-east-1aa) for parameter availabilityZone is invalid. Subnets can "
                            + "currently only be created in the following availability zones: "
                            + "us-east-1a, us-east-1b, us-east-1c."));
    }
}
