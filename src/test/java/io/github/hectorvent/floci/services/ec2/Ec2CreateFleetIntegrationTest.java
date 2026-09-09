package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.startsWith;

/**
 * Contract coverage for the instant CreateFleet request used by Karpenter's EC2 provider.
 *
 * <p>The test deliberately exercises both the singular EC2 Query wire location name
 * ({@code LaunchTemplateConfig.N}) and the AWS-compatible DryRun exception. No real Docker
 * runtime is required because the test profile runs EC2 in mock mode.</p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2CreateFleetIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String CONTRACT_IMAGE_ID = "ami-create-fleet-contract";
    private static String launchTemplateId;

    @Test
    @Order(1)
    void createFleetDryRunReturnsAwsCompatibleExceptionWithoutLaunching() {
        launchTemplateId = createLaunchTemplate();

        given()
                .formParam("Action", "CreateFleet")
                .formParam("Type", "instant")
                .formParam("DryRun", "true")
                .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.LaunchTemplateId", launchTemplateId)
                .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.Version", "1")
                .formParam("LaunchTemplateConfig.1.Overrides.1.InstanceType", "t3.micro")
                .formParam("LaunchTemplateConfig.1.Overrides.1.ImageId", CONTRACT_IMAGE_ID)
                .formParam("TargetCapacitySpecification.TotalTargetCapacity", "1")
                .formParam("TargetCapacitySpecification.DefaultTargetCapacityType", "on-demand")
                .header("Authorization", AUTH_HEADER)
                .when()
                .post("/")
                .then()
                .statusCode(412)
                .body(containsString("<Code>DryRunOperation</Code>"));

        given()
                .formParam("Action", "DescribeInstances")
                .formParam("Filter.1.Name", "image-id")
                .formParam("Filter.1.Value.1", CONTRACT_IMAGE_ID)
                .header("Authorization", AUTH_HEADER)
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.size()", equalTo(0));
    }

    @Test
    @Order(2)
    void createFleetRejectsConflictingSubnetAndAvailabilityZone() {
        given()
                .formParam("Action", "CreateFleet")
                .formParam("Type", "instant")
                .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.LaunchTemplateId", launchTemplateId)
                .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.Version", "1")
                .formParam("LaunchTemplateConfig.1.Overrides.1.InstanceType", "t3.micro")
                .formParam("LaunchTemplateConfig.1.Overrides.1.ImageId", CONTRACT_IMAGE_ID)
                .formParam("LaunchTemplateConfig.1.Overrides.1.SubnetId", "subnet-default-us-east-1-a")
                .formParam("LaunchTemplateConfig.1.Overrides.1.AvailabilityZone", "us-east-1b")
                .formParam("TargetCapacitySpecification.TotalTargetCapacity", "1")
                .formParam("TargetCapacitySpecification.DefaultTargetCapacityType", "on-demand")
                .header("Authorization", AUTH_HEADER)
                .when()
                .post("/")
                .then()
                .statusCode(400)
                .body(containsString("InvalidParameterCombination"));
    }

    @Test
    @Order(3)
    void createFleetSubnetOverrideDerivesAvailabilityZoneInsteadOfInheritingTemplateZone() {
        String placementLaunchTemplateId = createLaunchTemplateWithAvailabilityZone();

        given()
                .formParam("Action", "CreateFleet")
                .formParam("Type", "instant")
                .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.LaunchTemplateId",
                        placementLaunchTemplateId)
                .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.Version", "1")
                .formParam("LaunchTemplateConfig.1.Overrides.1.InstanceType", "t3.micro")
                .formParam("LaunchTemplateConfig.1.Overrides.1.ImageId", CONTRACT_IMAGE_ID)
                .formParam("LaunchTemplateConfig.1.Overrides.1.SubnetId", "subnet-default-us-east-1-b")
                .formParam("TargetCapacitySpecification.TotalTargetCapacity", "1")
                .formParam("TargetCapacitySpecification.DefaultTargetCapacityType", "on-demand")
                .header("Authorization", AUTH_HEADER)
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("CreateFleetResponse.fleetInstanceSet.item.availabilityZone", equalTo("us-east-1b"))
                .body("CreateFleetResponse.fleetInstanceSet.item.subnetId",
                        equalTo("subnet-default-us-east-1-b"))
                .body("CreateFleetResponse.fleetInstanceSet.item.launchTemplateAndOverrides.overrides.subnetId",
                        equalTo("subnet-default-us-east-1-b"))
                .body("CreateFleetResponse.fleetInstanceSet.item.launchTemplateAndOverrides.overrides.availabilityZone",
                        equalTo("us-east-1b"));
    }

    @Test
    @Order(4)
    void createFleetRollsBackEarlierLaunchesWhenLaterLaunchFails() {
        given()
                .formParam("Action", "CreateFleet")
                .formParam("Type", "instant")
                .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.LaunchTemplateId", launchTemplateId)
                .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.Version", "1")
                .formParam("LaunchTemplateConfig.1.Overrides.1.InstanceType", "t3.micro")
                .formParam("LaunchTemplateConfig.1.Overrides.1.ImageId", "ami-rollback-test")
                .formParam("LaunchTemplateConfig.1.Overrides.2.InstanceType", "t3.micro")
                .formParam("LaunchTemplateConfig.1.Overrides.2.ImageId", "ami-rollback-test")
                .formParam("LaunchTemplateConfig.1.Overrides.2.SubnetId", "subnet-does-not-exist")
                .formParam("TargetCapacitySpecification.TotalTargetCapacity", "2")
                .formParam("TargetCapacitySpecification.DefaultTargetCapacityType", "on-demand")
                .header("Authorization", AUTH_HEADER)
                .when()
                .post("/")
                .then()
                .statusCode(400)
                .body(containsString("InvalidSubnetID.NotFound"));

        given()
                .formParam("Action", "DescribeInstances")
                .formParam("Filter.1.Name", "image-id")
                .formParam("Filter.1.Value.1", "ami-rollback-test")
                .formParam("Filter.2.Name", "instance-state-name")
                .formParam("Filter.2.Value.1", "running")
                .header("Authorization", AUTH_HEADER)
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.size()", equalTo(0));
    }

    @Test
    @Order(5)
    void createFleetLaunchesInstanceAndReturnsFleetShape() {
        if (launchTemplateId == null) {
            launchTemplateId = createLaunchTemplate();
        }

        given()
                .formParam("Action", "CreateFleet")
                .formParam("Type", "instant")
                .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.LaunchTemplateId", launchTemplateId)
                .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.Version", "1")
                .formParam("LaunchTemplateConfig.1.Overrides.1.InstanceType", "t3.micro")
                .formParam("LaunchTemplateConfig.1.Overrides.1.ImageId", CONTRACT_IMAGE_ID)
                .formParam("LaunchTemplateConfig.1.Overrides.1.AvailabilityZone", "us-east-1b")
                .formParam("TargetCapacitySpecification.TotalTargetCapacity", "2")
                .formParam("TargetCapacitySpecification.DefaultTargetCapacityType", "on-demand")
                .header("Authorization", AUTH_HEADER)
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("CreateFleetResponse.fleetId", startsWith("fleet-"))
                .body("CreateFleetResponse.fleetInstanceSet.item.size()", equalTo(1))
                .body("CreateFleetResponse.fleetInstanceSet.item.instanceIds.item.size()", equalTo(2))
                .body("CreateFleetResponse.fleetInstanceSet.item.instanceIds.item", everyItem(startsWith("i-")))
                .body("CreateFleetResponse.fleetInstanceSet.item.instanceType", equalTo("t3.micro"))
                .body("CreateFleetResponse.fleetInstanceSet.item.availabilityZone", equalTo("us-east-1b"))
                .body("CreateFleetResponse.fleetInstanceSet.item.lifecycle", equalTo("on-demand"))
                .body("CreateFleetResponse.fleetInstanceSet.item.launchTemplateAndOverrides.launchTemplateSpecification.launchTemplateId",
                        equalTo(launchTemplateId))
                .body("CreateFleetResponse.fleetInstanceSet.item.launchTemplateAndOverrides.overrides.imageId",
                        equalTo(CONTRACT_IMAGE_ID));
    }

    private static String createLaunchTemplate() {
        return given()
                .formParam("Action", "CreateLaunchTemplate")
                .formParam("LaunchTemplateName", "create-fleet-contract")
                .formParam("LaunchTemplateData.ImageId", CONTRACT_IMAGE_ID)
                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                .header("Authorization", AUTH_HEADER)
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract()
                .path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
    }

    private static String createLaunchTemplateWithAvailabilityZone() {
        return given()
                .formParam("Action", "CreateLaunchTemplate")
                .formParam("LaunchTemplateName", "create-fleet-placement-contract")
                .formParam("LaunchTemplateData.ImageId", CONTRACT_IMAGE_ID)
                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                .formParam("LaunchTemplateData.Placement.AvailabilityZone", "us-east-1a")
                .header("Authorization", AUTH_HEADER)
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract()
                .path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
    }
}
