package io.github.hectorvent.floci.services.autoscaling;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Wire-level coverage for the optional CreateAutoScalingGroup/UpdateAutoScalingGroup members that
 * DescribeAutoScalingGroups echoes back: DesiredCapacityType, CapacityRebalance,
 * MaxInstanceLifetime, DefaultInstanceWarmup, and a mixed instances policy launch template
 * override that selects instance types by requirements instead of by name.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AutoScalingGroupOptionalFieldsIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260501/us-east-1/autoscaling/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260501/us-east-1/ec2/aws4_request";

    private static final String GROUP = "opt-fields-asg";
    private static final String BARE_GROUP = "opt-fields-bare-asg";
    private static final String REQUIREMENTS_GROUP = "opt-fields-requirements-asg";

    private static final String OVERRIDE_REQUIREMENTS =
            "MixedInstancesPolicy.LaunchTemplate.Overrides.member.1.InstanceRequirements";

    private static String launchTemplateId;

    // DesiredCapacityType is legal only for attribute-based instance type selection, so the group the
    // later ordered tests build on is created from a mixed instances policy whose override selects
    // instance types by InstanceRequirements rather than from a launch template naming t3.micro.
    @Test
    @Order(1)
    void createGroupCarryingEveryOptionalField() {
        launchTemplateId = createLaunchTemplate("opt-fields-lt");

        given()
                .formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", GROUP)
                .formParam("MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.LaunchTemplateId",
                        launchTemplateId)
                .formParam("MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.Version", "1")
                .formParam(OVERRIDE_REQUIREMENTS + ".VCpuCount.Min", "2")
                .formParam(OVERRIDE_REQUIREMENTS + ".VCpuCount.Max", "8")
                .formParam(OVERRIDE_REQUIREMENTS + ".MemoryMiB.Min", "2048")
                .formParam(OVERRIDE_REQUIREMENTS + ".MemoryMiB.Max", "16384")
                .formParam("MinSize", "0")
                .formParam("MaxSize", "4")
                .formParam("DesiredCapacity", "0")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .formParam("DesiredCapacityType", "vcpu")
                .formParam("CapacityRebalance", "true")
                .formParam("MaxInstanceLifetime", "604800")
                .formParam("DefaultInstanceWarmup", "120")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("CreateAutoScalingGroupResponse"));

        describe(GROUP)
                .body(containsString("<DesiredCapacityType>vcpu</DesiredCapacityType>"))
                .body(containsString("<CapacityRebalance>true</CapacityRebalance>"))
                .body(containsString("<MaxInstanceLifetime>604800</MaxInstanceLifetime>"))
                .body(containsString("<DefaultInstanceWarmup>120</DefaultInstanceWarmup>"))
                .body(containsString("<VCpuCount><Min>2</Min><Max>8</Max></VCpuCount>"))
                .body(containsString("<MemoryMiB><Min>2048</Min><Max>16384</Max></MemoryMiB>"));
    }

    @Test
    @Order(2)
    void groupThatSetsNoneOfThemOmitsThemFromDescribe() {
        given()
                .formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", BARE_GROUP)
                .formParam("LaunchTemplate.LaunchTemplateId", launchTemplateId)
                .formParam("LaunchTemplate.Version", "1")
                .formParam("MinSize", "0")
                .formParam("MaxSize", "2")
                .formParam("DesiredCapacity", "0")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        describe(BARE_GROUP)
                .body(not(containsString("<DesiredCapacityType>")))
                .body(not(containsString("<CapacityRebalance>")))
                .body(not(containsString("<MaxInstanceLifetime>")))
                .body(not(containsString("<DefaultInstanceWarmup>")));
    }

    // The request names no launch source, so the stored attribute-based policy stays in effect and
    // memory-mib remains legal.
    @Test
    @Order(3)
    void updateOverwritesOnlyTheOptionalFieldsTheRequestCarries() {
        given()
                .formParam("Action", "UpdateAutoScalingGroup")
                .formParam("AutoScalingGroupName", GROUP)
                .formParam("MaxInstanceLifetime", "86400")
                .formParam("DesiredCapacityType", "memory-mib")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("UpdateAutoScalingGroupResponse"));

        describe(GROUP)
                .body(containsString("<MaxInstanceLifetime>86400</MaxInstanceLifetime>"))
                .body(containsString("<DesiredCapacityType>memory-mib</DesiredCapacityType>"))
                .body(containsString("<CapacityRebalance>true</CapacityRebalance>"))
                .body(containsString("<DefaultInstanceWarmup>120</DefaultInstanceWarmup>"));
    }

    @Test
    @Order(4)
    void maxInstanceLifetimeAcceptsTheZeroSentinel() {
        given()
                .formParam("Action", "UpdateAutoScalingGroup")
                .formParam("AutoScalingGroupName", GROUP)
                .formParam("MaxInstanceLifetime", "0")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        describe(GROUP).body(containsString("<MaxInstanceLifetime>0</MaxInstanceLifetime>"));
    }

    @Test
    @Order(5)
    void defaultInstanceWarmupMinusOneRemovesThePreviouslySetValue() {
        given()
                .formParam("Action", "UpdateAutoScalingGroup")
                .formParam("AutoScalingGroupName", GROUP)
                .formParam("DefaultInstanceWarmup", "-1")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        describe(GROUP)
                .body(not(containsString("<DefaultInstanceWarmup>")))
                .body(containsString("<CapacityRebalance>true</CapacityRebalance>"));
    }

    @Test
    @Order(6)
    void createRejectsAnUnknownDesiredCapacityType() {
        given()
                .formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", "opt-fields-bad-capacity-type")
                .formParam("LaunchTemplate.LaunchTemplateId", launchTemplateId)
                .formParam("MinSize", "0")
                .formParam("MaxSize", "1")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .formParam("DesiredCapacityType", "gpu")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(containsString("ValidationError"))
                .body(containsString("units, vcpu, memory-mib"));
    }

    @Test
    @Order(7)
    void createRejectsAMaxInstanceLifetimeUnderOneDay() {
        given()
                .formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", "opt-fields-bad-lifetime")
                .formParam("LaunchTemplate.LaunchTemplateId", launchTemplateId)
                .formParam("MinSize", "0")
                .formParam("MaxSize", "1")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .formParam("MaxInstanceLifetime", "3600")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(containsString("ValidationError"))
                .body(containsString("MaxInstanceLifetime"));
    }

    @Test
    @Order(8)
    void mixedInstancesPolicyOverrideRoundTripsInstanceRequirements() {
        String prefix = OVERRIDE_REQUIREMENTS;

        given()
                .formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", REQUIREMENTS_GROUP)
                .formParam("MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.LaunchTemplateId",
                        launchTemplateId)
                .formParam("MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.Version", "1")
                .formParam(prefix + ".VCpuCount.Min", "2")
                .formParam(prefix + ".VCpuCount.Max", "8")
                .formParam(prefix + ".MemoryMiB.Min", "2048")
                .formParam(prefix + ".MemoryMiB.Max", "16384")
                .formParam(prefix + ".MemoryGiBPerVCpu.Min", "2.0")
                .formParam(prefix + ".NetworkInterfaceCount.Min", "1")
                .formParam(prefix + ".NetworkInterfaceCount.Max", "4")
                .formParam(prefix + ".AcceleratorCount.Max", "2")
                .formParam(prefix + ".AcceleratorTotalMemoryMiB.Min", "1024")
                .formParam(prefix + ".BaselineEbsBandwidthMbps.Min", "125")
                .formParam(prefix + ".TotalLocalStorageGB.Max", "500.0")
                .formParam(prefix + ".NetworkBandwidthGbps.Min", "1.5")
                .formParam(prefix + ".CpuManufacturers.member.1", "intel")
                .formParam(prefix + ".CpuManufacturers.member.2", "amd")
                .formParam(prefix + ".ExcludedInstanceTypes.member.1", "m1.*")
                .formParam(prefix + ".InstanceGenerations.member.1", "current")
                .formParam(prefix + ".LocalStorageTypes.member.1", "ssd")
                .formParam(prefix + ".AcceleratorTypes.member.1", "gpu")
                .formParam(prefix + ".AcceleratorManufacturers.member.1", "nvidia")
                .formParam(prefix + ".AcceleratorNames.member.1", "t4")
                .formParam(prefix + ".AllowedInstanceTypes.member.1", "m6i.*")
                .formParam(prefix + ".BareMetal", "excluded")
                .formParam(prefix + ".BurstablePerformance", "included")
                .formParam(prefix + ".LocalStorage", "required")
                .formParam(prefix + ".RequireHibernateSupport", "false")
                .formParam(prefix + ".SpotMaxPricePercentageOverLowestPrice", "100")
                .formParam(prefix + ".MaxSpotPriceAsPercentageOfOptimalOnDemandPrice", "90")
                .formParam(prefix + ".OnDemandMaxPricePercentageOverLowestPrice", "20")
                .formParam("MinSize", "0")
                .formParam("MaxSize", "4")
                .formParam("DesiredCapacity", "0")
                .formParam("DesiredCapacityType", "units")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("CreateAutoScalingGroupResponse"));

        describe(REQUIREMENTS_GROUP)
                .body(containsString("<Overrides><member><InstanceRequirements>"))
                .body(containsString("<VCpuCount><Min>2</Min><Max>8</Max></VCpuCount>"))
                .body(containsString("<MemoryMiB><Min>2048</Min><Max>16384</Max></MemoryMiB>"))
                .body(containsString("<MemoryGiBPerVCpu><Min>2.0</Min></MemoryGiBPerVCpu>"))
                .body(containsString("<NetworkInterfaceCount><Min>1</Min><Max>4</Max></NetworkInterfaceCount>"))
                .body(containsString("<AcceleratorCount><Max>2</Max></AcceleratorCount>"))
                .body(containsString("<AcceleratorTotalMemoryMiB><Min>1024</Min></AcceleratorTotalMemoryMiB>"))
                .body(containsString("<BaselineEbsBandwidthMbps><Min>125</Min></BaselineEbsBandwidthMbps>"))
                .body(containsString("<TotalLocalStorageGB><Max>500.0</Max></TotalLocalStorageGB>"))
                .body(containsString("<NetworkBandwidthGbps><Min>1.5</Min></NetworkBandwidthGbps>"))
                .body(containsString("<CpuManufacturers><member>intel</member><member>amd</member></CpuManufacturers>"))
                .body(containsString("<ExcludedInstanceTypes><member>m1.*</member></ExcludedInstanceTypes>"))
                .body(containsString("<InstanceGenerations><member>current</member></InstanceGenerations>"))
                .body(containsString("<LocalStorageTypes><member>ssd</member></LocalStorageTypes>"))
                .body(containsString("<AcceleratorTypes><member>gpu</member></AcceleratorTypes>"))
                .body(containsString("<AcceleratorManufacturers><member>nvidia</member></AcceleratorManufacturers>"))
                .body(containsString("<AcceleratorNames><member>t4</member></AcceleratorNames>"))
                .body(containsString("<AllowedInstanceTypes><member>m6i.*</member></AllowedInstanceTypes>"))
                .body(containsString("<BareMetal>excluded</BareMetal>"))
                .body(containsString("<BurstablePerformance>included</BurstablePerformance>"))
                .body(containsString("<LocalStorage>required</LocalStorage>"))
                .body(containsString("<RequireHibernateSupport>false</RequireHibernateSupport>"))
                .body(containsString("<SpotMaxPricePercentageOverLowestPrice>100</SpotMaxPricePercentageOverLowestPrice>"))
                .body(containsString("<MaxSpotPriceAsPercentageOfOptimalOnDemandPrice>90"
                        + "</MaxSpotPriceAsPercentageOfOptimalOnDemandPrice>"))
                .body(containsString("<OnDemandMaxPricePercentageOverLowestPrice>20"
                        + "</OnDemandMaxPricePercentageOverLowestPrice>"))
                .body(containsString("<DesiredCapacityType>units</DesiredCapacityType>"));
    }

    @Test
    @Order(9)
    void overrideRejectsInstanceTypeAlongsideInstanceRequirements() {
        given()
                .formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", "opt-fields-conflicting-override")
                .formParam("MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.LaunchTemplateId",
                        launchTemplateId)
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.1.InstanceType", "m6i.large")
                .formParam("MixedInstancesPolicy.LaunchTemplate.Overrides.member.1."
                        + "InstanceRequirements.VCpuCount.Min", "2")
                .formParam("MinSize", "0")
                .formParam("MaxSize", "1")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(containsString("ValidationError"))
                .body(containsString("InstanceRequirements"));
    }

    @Test
    @Order(10)
    void createRejectsVcpuDesiredCapacityTypeWithoutAttributeBasedSelection() {
        given()
                .formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", "opt-fields-fixed-type-vcpu")
                .formParam("LaunchTemplate.LaunchTemplateId", launchTemplateId)
                .formParam("LaunchTemplate.Version", "1")
                .formParam("MinSize", "0")
                .formParam("MaxSize", "1")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .formParam("DesiredCapacityType", "vcpu")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(containsString("ValidationError"))
                .body(containsString("attribute-based instance type selection only"));
    }

    @Test
    @Order(11)
    void updateRejectsMemoryMibDesiredCapacityTypeWithoutAttributeBasedSelection() {
        given()
                .formParam("Action", "UpdateAutoScalingGroup")
                .formParam("AutoScalingGroupName", BARE_GROUP)
                .formParam("DesiredCapacityType", "memory-mib")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(containsString("ValidationError"))
                .body(containsString("attribute-based instance type selection only"));

        describe(BARE_GROUP).body(not(containsString("<DesiredCapacityType>")));
    }

    @Test
    @Order(12)
    void updateAcceptsVcpuOnceTheSameRequestSuppliesInstanceRequirements() {
        given()
                .formParam("Action", "UpdateAutoScalingGroup")
                .formParam("AutoScalingGroupName", BARE_GROUP)
                .formParam("MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.LaunchTemplateId",
                        launchTemplateId)
                .formParam("MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.Version", "1")
                .formParam(OVERRIDE_REQUIREMENTS + ".VCpuCount.Min", "4")
                .formParam(OVERRIDE_REQUIREMENTS + ".MemoryMiB.Min", "8192")
                .formParam("DesiredCapacityType", "vcpu")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        describe(BARE_GROUP)
                .body(containsString("<DesiredCapacityType>vcpu</DesiredCapacityType>"))
                .body(containsString("<VCpuCount><Min>4</Min></VCpuCount>"))
                .body(containsString("<MemoryMiB><Min>8192</Min></MemoryMiB>"));
    }

    @Test
    @Order(13)
    void createRejectsAnInstanceRequirementsOverrideMissingMemoryMiB() {
        given()
                .formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", "opt-fields-partial-requirements")
                .formParam("MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.LaunchTemplateId",
                        launchTemplateId)
                .formParam("MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.Version", "1")
                .formParam(OVERRIDE_REQUIREMENTS + ".VCpuCount.Min", "2")
                .formParam("MinSize", "0")
                .formParam("MaxSize", "1")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(containsString("ValidationError"))
                .body(containsString("VCpuCount and MemoryMiB"));
    }

    @Test
    @Order(14)
    void updateRejectsAnInstanceRequirementsOverrideMissingVCpuCount() {
        given()
                .formParam("Action", "UpdateAutoScalingGroup")
                .formParam("AutoScalingGroupName", REQUIREMENTS_GROUP)
                .formParam("MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.LaunchTemplateId",
                        launchTemplateId)
                .formParam("MixedInstancesPolicy.LaunchTemplate.LaunchTemplateSpecification.Version", "1")
                .formParam(OVERRIDE_REQUIREMENTS + ".MemoryMiB.Min", "2048")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(containsString("ValidationError"))
                .body(containsString("VCpuCount and MemoryMiB"));

        describe(REQUIREMENTS_GROUP).body(containsString("<VCpuCount><Min>2</Min><Max>8</Max></VCpuCount>"));
    }

    @Test
    @Order(15)
    void cleanUp() {
        for (String name : new String[] {GROUP, BARE_GROUP, REQUIREMENTS_GROUP}) {
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

    private static String createLaunchTemplate(String name) {
        return given()
                .formParam("Action", "CreateLaunchTemplate")
                .formParam("LaunchTemplateName", name)
                .formParam("LaunchTemplateData.ImageId", "ami-12345678")
                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                .header("Authorization", EC2_AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
    }
}
