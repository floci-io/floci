package io.github.hectorvent.floci.services.autoscaling;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * lex00/floci#112: DescribeAutoScalingGroups dropped most of the resource's own optional fields
 * (default_instance_warmup, capacity_rebalance, service_linked_role_arn,
 * instance_maintenance_policy, availability_zone_distribution, capacity_reservation_specification,
 * mixed_instances_policy override fields, enabled metrics) - a stateless replan had nowhere to
 * source these from and proposed a permanent in-place-update diff for every one a module call
 * actually set. Oracle: botocore's autoscaling/2011-01-01/service-2.json AutoScalingGroup shape.
 * One test per field group, per this unit's own instructions.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AutoScalingOptionalFieldsIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260501/us-east-1/autoscaling/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260501/us-east-1/ec2/aws4_request";

    private static String launchTemplateId;

    @Test
    @Order(1)
    void createAutoScalingGroupRoundTripsEveryOptionalFieldGroup() {
        launchTemplateId = given()
                .formParam("Action", "CreateLaunchTemplate")
                .formParam("LaunchTemplateName", "optional-fields-lt")
                .formParam("LaunchTemplateData.ImageId", "ami-12345678")
                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                .header("Authorization", EC2_AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");

        given()
                .formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", "optional-fields-asg")
                .formParam("MinSize", "0")
                .formParam("MaxSize", "3")
                .formParam("DesiredCapacity", "1")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .formParam("MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.LaunchTemplateId", launchTemplateId)
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.1.InstanceType", "t3.micro")
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.1.WeightedCapacity", "2")
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.2.InstanceType", "t3.small")
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.2.WeightedCapacity", "4")
                .formParam("DefaultInstanceWarmup", "300")
                .formParam("CapacityRebalance", "true")
                .formParam("ServiceLinkedRoleARN", "arn:aws:iam::000000000000:role/aws-service-role/autoscaling.amazonaws.com/AWSServiceRoleForAutoScaling")
                .formParam("MaxInstanceLifetime", "604800")
                .formParam("InstanceMaintenancePolicy.MinHealthyPercentage", "100")
                .formParam("InstanceMaintenancePolicy.MaxHealthyPercentage", "110")
                .formParam("AvailabilityZoneDistribution.CapacityDistributionStrategy", "balanced-only")
                .formParam("CapacityReservationSpecification.CapacityReservationPreference", "capacity-reservations-only")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    @Test
    @Order(2)
    void describeAutoScalingGroupsEchoesEveryOptionalFieldGroupBack() {
        given()
                .formParam("Action", "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", "optional-fields-asg")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member.DefaultInstanceWarmup",
                        equalTo("300"))
                .body("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member.CapacityRebalance",
                        equalTo("true"))
                .body("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member.ServiceLinkedRoleARN",
                        equalTo("arn:aws:iam::000000000000:role/aws-service-role/autoscaling.amazonaws.com/AWSServiceRoleForAutoScaling"))
                .body("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member.MaxInstanceLifetime",
                        equalTo("604800"))
                .body("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member.InstanceMaintenancePolicy.MinHealthyPercentage",
                        equalTo("100"))
                .body("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member.InstanceMaintenancePolicy.MaxHealthyPercentage",
                        equalTo("110"))
                .body("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member.AvailabilityZoneDistribution.CapacityDistributionStrategy",
                        equalTo("balanced-only"))
                .body("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member.CapacityReservationSpecification.CapacityReservationPreference",
                        equalTo("capacity-reservations-only"))
                .body("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member.MixedInstancesPolicy.LaunchTemplate.Overrides.member[0].WeightedCapacity",
                        equalTo("2"))
                .body("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member.MixedInstancesPolicy.LaunchTemplate.Overrides.member[1].WeightedCapacity",
                        equalTo("4"));
    }

    @Test
    @Order(3)
    void updateAutoScalingGroupOverridesOnlyWhatItSetsAndLeavesTheRestAlone() {
        given()
                .formParam("Action", "UpdateAutoScalingGroup")
                .formParam("AutoScalingGroupName", "optional-fields-asg")
                .formParam("CapacityRebalance", "false")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
                .formParam("Action", "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", "optional-fields-asg")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member.CapacityRebalance",
                        equalTo("false"))
                // Untouched by this update - still carries the value set at create time.
                .body("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member.DefaultInstanceWarmup",
                        equalTo("300"))
                .body("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member.MaxInstanceLifetime",
                        equalTo("604800"));
    }

    @Test
    @Order(4)
    void enableAndDisableMetricsCollectionRoundTripEnabledMetrics() {
        given()
                .formParam("Action", "EnableMetricsCollection")
                .formParam("AutoScalingGroupName", "optional-fields-asg")
                .formParam("Metrics.member.1", "GroupMinSize")
                .formParam("Metrics.member.2", "GroupMaxSize")
                .formParam("Granularity", "1Minute")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
                .formParam("Action", "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", "optional-fields-asg")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member.EnabledMetrics.member[0].Metric",
                        equalTo("GroupMinSize"))
                .body("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member.EnabledMetrics.member[1].Metric",
                        equalTo("GroupMaxSize"));

        given()
                .formParam("Action", "DisableMetricsCollection")
                .formParam("AutoScalingGroupName", "optional-fields-asg")
                .formParam("Metrics.member.1", "GroupMinSize")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
                .formParam("Action", "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", "optional-fields-asg")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member.EnabledMetrics.member[0].Metric",
                        equalTo("GroupMaxSize"));
    }

    @Test
    @Order(5)
    void aGroupThatNeverSetAnyOptionalFieldOmitsAllOfThemRatherThanInventingDefaults() {
        given()
                .formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", "no-optional-fields-asg")
                .formParam("MinSize", "0")
                .formParam("MaxSize", "1")
                .formParam("DesiredCapacity", "0")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .formParam("LaunchTemplate.LaunchTemplateId", launchTemplateId)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        String xml = given()
                .formParam("Action", "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", "no-optional-fields-asg")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().asString();
        assertFalse(xml.contains("DefaultInstanceWarmup"), xml);
        assertFalse(xml.contains("CapacityRebalance"), xml);
        assertFalse(xml.contains("ServiceLinkedRoleARN"), xml);
        assertFalse(xml.contains("MaxInstanceLifetime"), xml);
        assertFalse(xml.contains("InstanceMaintenancePolicy"), xml);
        assertFalse(xml.contains("AvailabilityZoneDistribution"), xml);
        assertFalse(xml.contains("CapacityReservationSpecification"), xml);
        assertFalse(xml.contains("EnabledMetrics"), xml);
    }
}
