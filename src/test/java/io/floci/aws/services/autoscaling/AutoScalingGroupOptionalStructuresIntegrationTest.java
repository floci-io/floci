package io.floci.aws.services.autoscaling;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Wire-level coverage for the AutoScalingGroup members that DescribeAutoScalingGroups used to drop:
 * ServiceLinkedRoleARN, InstanceMaintenancePolicy, AvailabilityZoneDistribution,
 * CapacityReservationSpecification, a mixed instances policy override's WeightedCapacity, and
 * EnabledMetrics, which only EnableMetricsCollection and DisableMetricsCollection set.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AutoScalingGroupOptionalStructuresIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260501/us-east-1/autoscaling/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260501/us-east-1/ec2/aws4_request";

    private static final String GROUP = "opt-structures-asg";
    private static final String BARE_GROUP = "opt-structures-bare-asg";
    private static final String MEMBER =
            "DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member.";
    private static final String ROLE_ARN =
            "arn:aws:iam::000000000000:role/aws-service-role/autoscaling.amazonaws.com/AWSServiceRoleForAutoScaling_opt";

    private static String launchTemplateId;

    @Test
    @Order(1)
    void createRoundTripsEveryOptionalStructure() {
        launchTemplateId = given()
                .formParam("Action", "CreateLaunchTemplate")
                .formParam("LaunchTemplateName", "opt-structures-lt")
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
                .formParam("AutoScalingGroupName", GROUP)
                .formParam("MinSize", "0")
                .formParam("MaxSize", "6")
                .formParam("DesiredCapacity", "0")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .formParam("MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.LaunchTemplateId",
                        launchTemplateId)
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.1.InstanceType", "t3.micro")
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.1.WeightedCapacity", "2")
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.2.InstanceType", "t3.small")
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.2.WeightedCapacity", "4")
                .formParam("ServiceLinkedRoleARN", ROLE_ARN)
                .formParam("InstanceMaintenancePolicy.MinHealthyPercentage", "100")
                .formParam("InstanceMaintenancePolicy.MaxHealthyPercentage", "110")
                .formParam("AvailabilityZoneDistribution.CapacityDistributionStrategy", "balanced-only")
                .formParam("CapacityReservationSpecification.CapacityReservationPreference",
                        "capacity-reservations-only")
                .formParam("CapacityReservationSpecification.CapacityReservationTarget"
                        + ".CapacityReservationIds.member.1", "cr-0123456789abcdef0")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        describe(GROUP)
                .body(MEMBER + "ServiceLinkedRoleARN", equalTo(ROLE_ARN))
                .body(MEMBER + "InstanceMaintenancePolicy.MinHealthyPercentage", equalTo("100"))
                .body(MEMBER + "InstanceMaintenancePolicy.MaxHealthyPercentage", equalTo("110"))
                .body(MEMBER + "AvailabilityZoneDistribution.CapacityDistributionStrategy",
                        equalTo("balanced-only"))
                .body(MEMBER + "CapacityReservationSpecification.CapacityReservationPreference",
                        equalTo("capacity-reservations-only"))
                .body(MEMBER + "CapacityReservationSpecification.CapacityReservationTarget"
                        + ".CapacityReservationIds.member", equalTo("cr-0123456789abcdef0"))
                .body(MEMBER + "MixedInstancesPolicy.LaunchTemplate.Overrides.member[0].WeightedCapacity",
                        equalTo("2"))
                .body(MEMBER + "MixedInstancesPolicy.LaunchTemplate.Overrides.member[1].WeightedCapacity",
                        equalTo("4"));
    }

    @Test
    @Order(2)
    void updateOverwritesOnlyTheStructuresTheRequestCarries() {
        given()
                .formParam("Action", "UpdateAutoScalingGroup")
                .formParam("AutoScalingGroupName", GROUP)
                .formParam("AvailabilityZoneDistribution.CapacityDistributionStrategy", "balanced-best-effort")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        describe(GROUP)
                .body(MEMBER + "AvailabilityZoneDistribution.CapacityDistributionStrategy",
                        equalTo("balanced-best-effort"))
                .body(MEMBER + "ServiceLinkedRoleARN", equalTo(ROLE_ARN))
                .body(MEMBER + "InstanceMaintenancePolicy.MinHealthyPercentage", equalTo("100"))
                .body(MEMBER + "CapacityReservationSpecification.CapacityReservationPreference",
                        equalTo("capacity-reservations-only"));
    }

    @Test
    @Order(3)
    void instanceMaintenancePolicyMinusOneOnBothClearsIt() {
        given()
                .formParam("Action", "UpdateAutoScalingGroup")
                .formParam("AutoScalingGroupName", GROUP)
                .formParam("InstanceMaintenancePolicy.MinHealthyPercentage", "-1")
                .formParam("InstanceMaintenancePolicy.MaxHealthyPercentage", "-1")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        describe(GROUP)
                .body(not(containsString("<InstanceMaintenancePolicy>")))
                .body(MEMBER + "ServiceLinkedRoleARN", equalTo(ROLE_ARN));
    }

    @Test
    @Order(4)
    void enableAndDisableMetricsCollectionRoundTripEnabledMetrics() {
        given()
                .formParam("Action", "EnableMetricsCollection")
                .formParam("AutoScalingGroupName", GROUP)
                .formParam("Metrics.member.1", "GroupMinSize")
                .formParam("Metrics.member.2", "GroupMaxSize")
                .formParam("Granularity", "1Minute")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("EnableMetricsCollectionResponse"));

        describe(GROUP)
                .body(MEMBER + "EnabledMetrics.member[0].Metric", equalTo("GroupMinSize"))
                .body(MEMBER + "EnabledMetrics.member[0].Granularity", equalTo("1Minute"))
                .body(MEMBER + "EnabledMetrics.member[1].Metric", equalTo("GroupMaxSize"));

        given()
                .formParam("Action", "DisableMetricsCollection")
                .formParam("AutoScalingGroupName", GROUP)
                .formParam("Metrics.member.1", "GroupMinSize")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("DisableMetricsCollectionResponse"));

        describe(GROUP)
                .body(MEMBER + "EnabledMetrics.member.Metric", equalTo("GroupMaxSize"))
                .body(not(containsString("<Metric>GroupMinSize</Metric>")));

        given()
                .formParam("Action", "DisableMetricsCollection")
                .formParam("AutoScalingGroupName", GROUP)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        describe(GROUP).body(not(containsString("<EnabledMetrics>")));
    }

    @Test
    @Order(5)
    void enableMetricsCollectionWithNoMetricsEnablesAllOfThem() {
        given()
                .formParam("Action", "EnableMetricsCollection")
                .formParam("AutoScalingGroupName", GROUP)
                .formParam("Granularity", "1Minute")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        describe(GROUP)
                .body(containsString("<Metric>GroupTotalInstances</Metric>"))
                .body(containsString("<Metric>GroupAndWarmPoolTotalCapacity</Metric>"));
    }

    @Test
    @Order(6)
    void enableMetricsCollectionRejectsAGranularityOtherThanOneMinute() {
        given()
                .formParam("Action", "EnableMetricsCollection")
                .formParam("AutoScalingGroupName", GROUP)
                .formParam("Granularity", "5Minute")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(containsString("ValidationError"))
                .body(containsString("1Minute"));
    }

    @Test
    @Order(7)
    void aGroupThatSetsNoneOfThemOmitsThemFromDescribe() {
        given()
                .formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", BARE_GROUP)
                .formParam("LaunchTemplate.LaunchTemplateId", launchTemplateId)
                .formParam("MinSize", "0")
                .formParam("MaxSize", "1")
                .formParam("DesiredCapacity", "0")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        describe(BARE_GROUP)
                .body(not(containsString("<ServiceLinkedRoleARN>")))
                .body(not(containsString("<InstanceMaintenancePolicy>")))
                .body(not(containsString("<AvailabilityZoneDistribution>")))
                .body(not(containsString("<CapacityReservationSpecification>")))
                .body(not(containsString("<EnabledMetrics>")));
    }

    @Test
    @Order(8)
    void createRejectsAMaxHealthyPercentageBelowOneHundred() {
        rejectedCreate("opt-structures-bad-max",
                "InstanceMaintenancePolicy.MinHealthyPercentage", "50",
                "InstanceMaintenancePolicy.MaxHealthyPercentage", "90")
                .body(containsString("MaxHealthyPercentage"));
    }

    @Test
    @Order(9)
    void createRejectsAHealthyPercentageSpreadAboveOneHundred() {
        rejectedCreate("opt-structures-wide-spread",
                "InstanceMaintenancePolicy.MinHealthyPercentage", "0",
                "InstanceMaintenancePolicy.MaxHealthyPercentage", "150")
                .body(containsString("cannot be greater than 100"));
    }

    @Test
    @Order(10)
    void createRejectsAnInstanceMaintenancePolicyMissingOnePercentage() {
        rejectedCreate("opt-structures-half-policy",
                "InstanceMaintenancePolicy.MinHealthyPercentage", "90",
                "DesiredCapacity", "0")
                .body(containsString("Both MinHealthyPercentage and MaxHealthyPercentage"));
    }

    @Test
    @Order(11)
    void createRejectsAnUnknownCapacityDistributionStrategy() {
        rejectedCreate("opt-structures-bad-strategy",
                "AvailabilityZoneDistribution.CapacityDistributionStrategy", "round-robin",
                "DesiredCapacity", "0")
                .body(containsString("CapacityDistributionStrategy"));
    }

    @Test
    @Order(12)
    void createRejectsAnUnknownCapacityReservationPreference() {
        rejectedCreate("opt-structures-bad-preference",
                "CapacityReservationSpecification.CapacityReservationPreference", "open",
                "DesiredCapacity", "0")
                .body(containsString("CapacityReservationPreference"));
    }

    @Test
    @Order(13)
    void createRejectsAWeightedCapacityOutsideOneTo999() {
        given()
                .formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", "opt-structures-bad-weight")
                .formParam("MinSize", "0")
                .formParam("MaxSize", "1")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .formParam("MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.LaunchTemplateId",
                        launchTemplateId)
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.1.InstanceType", "t3.micro")
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.1.WeightedCapacity", "1000")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(containsString("ValidationError"))
                .body(containsString("WeightedCapacity"));
    }

    @Test
    @Order(14)
    void createRejectsAWeightedCapacityOnOnlySomeOverrides() {
        given()
                .formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", "opt-structures-partial-weight")
                .formParam("MinSize", "0")
                .formParam("MaxSize", "1")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .formParam("MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.LaunchTemplateId",
                        launchTemplateId)
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.1.InstanceType", "t3.micro")
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.1.WeightedCapacity", "1")
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.2.InstanceType", "t3.small")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(containsString("ValidationError"))
                .body(containsString("for all of them"));
    }

    @Test
    @Order(15)
    void createRejectsACapacityReservationTargetWithPreferenceNone() {
        rejectedCreate("opt-structures-none-target",
                "CapacityReservationSpecification.CapacityReservationPreference", "none",
                "CapacityReservationSpecification.CapacityReservationTarget.CapacityReservationIds.member.1",
                "cr-0123456789abcdef0")
                .body(containsString("CapacityReservationTarget"));
    }

    @Test
    @Order(16)
    void createRejectsACapacityReservationIdOutsideItsPattern() {
        rejectedCreate("opt-structures-bad-reservation-id",
                "CapacityReservationSpecification.CapacityReservationPreference", "capacity-reservations-only",
                "CapacityReservationSpecification.CapacityReservationTarget.CapacityReservationIds.member.1",
                "cr 0123")
                .body(containsString("capacityReservationIds"));
    }

    @Test
    @Order(17)
    void cleanUp() {
        for (String name : new String[] {GROUP, BARE_GROUP}) {
            given()
                    .formParam("Action", "DeleteAutoScalingGroup")
                    .formParam("AutoScalingGroupName", name)
                    .formParam("ForceDelete", "true")
                    .header("Authorization", AUTH)
                .when()
                    .post("/")
                .then()
                    .statusCode(200);
        }
    }

    private static ValidatableResponse rejectedCreate(String name, String key1, String value1,
                                                      String key2, String value2) {
        return given()
                .formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", name)
                .formParam("LaunchTemplate.LaunchTemplateId", launchTemplateId)
                .formParam("MinSize", "0")
                .formParam("MaxSize", "1")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .formParam(key1, value1)
                .formParam(key2, value2)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(containsString("ValidationError"));
    }

    private static ValidatableResponse describe(String name) {
        return given()
                .formParam("Action", "DescribeAutoScalingGroups")
                .formParam("AutoScalingGroupNames.member.1", name)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }
}
