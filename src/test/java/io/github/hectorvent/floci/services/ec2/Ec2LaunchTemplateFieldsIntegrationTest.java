package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Regression tests for CreateLaunchTemplate discarding nearly all of RequestLaunchTemplateData.
 *
 * <p>Only ImageId, InstanceType, KeyName, UserData, IamInstanceProfile, SecurityGroupIds and
 * TagSpecifications survived a create; MetadataOptions, BlockDeviceMappings, NetworkInterfaces and
 * every options block were dropped without an error. Terraform then read back its own input
 * missing and reported "Provider produced inconsistent result after apply", or diffed forever.
 *
 * <p>Field names and shapes here follow the EC2 service model's RequestLaunchTemplateData and
 * ResponseLaunchTemplateData. The request parameter names are the ones botocore's EC2 serializer
 * actually emits — note the singular {@code BlockDeviceMapping.1} / {@code NetworkInterface.1}
 * list prefixes, which come from each list member's locationName.
 */
@QuarkusTest
class Ec2LaunchTemplateFieldsIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static final String DATA =
            "DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.";

    private String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String describeBody(String name) {
        return describeLatest(name).extract().body().asString();
    }

    private ValidatableResponse describeLatest(String name) {
        return given()
            .formParam("Action", "DescribeLaunchTemplateVersions")
            .formParam("LaunchTemplateName", name)
            .formParam("Versions.1", "$Latest")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void metadataOptionsAndBlockDeviceMappingsRoundTrip() {
        String name = uniqueName("repro-lt");
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.InstanceType", "t3.micro")
            .formParam("LaunchTemplateData.BlockDeviceMapping.1.DeviceName", "/dev/xvda")
            .formParam("LaunchTemplateData.BlockDeviceMapping.1.Ebs.VolumeSize", "20")
            .formParam("LaunchTemplateData.BlockDeviceMapping.1.Ebs.VolumeType", "gp3")
            .formParam("LaunchTemplateData.BlockDeviceMapping.1.Ebs.Encrypted", "true")
            .formParam("LaunchTemplateData.MetadataOptions.HttpTokens", "required")
            .formParam("LaunchTemplateData.MetadataOptions.HttpPutResponseHopLimit", "2")
            .formParam("LaunchTemplateData.EbsOptimized", "true")
            .formParam("LaunchTemplateData.Monitoring.Enabled", "true")
            .formParam("LaunchTemplateData.CpuOptions.CoreCount", "1")
            .formParam("LaunchTemplateData.CpuOptions.ThreadsPerCore", "1")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        describeLatest(name)
            // IMDSv2 — appears in essentially every Gruntwork ASG/ECS module.
            .body(DATA + "metadataOptions.httpTokens", equalTo("required"))
            .body(DATA + "metadataOptions.httpPutResponseHopLimit", equalTo("2"))
            .body(DATA + "metadataOptions.state", equalTo("applied"))
            .body(DATA + "blockDeviceMappingSet.item.deviceName", equalTo("/dev/xvda"))
            .body(DATA + "blockDeviceMappingSet.item.ebs.volumeSize", equalTo("20"))
            .body(DATA + "blockDeviceMappingSet.item.ebs.volumeType", equalTo("gp3"))
            .body(DATA + "blockDeviceMappingSet.item.ebs.encrypted", equalTo("true"))
            .body(DATA + "ebsOptimized", equalTo("true"))
            .body(DATA + "monitoring.enabled", equalTo("true"))
            .body(DATA + "cpuOptions.coreCount", equalTo("1"))
            .body(DATA + "cpuOptions.threadsPerCore", equalTo("1"));
    }

    @Test
    void remainingOptionsBlocksRoundTrip() {
        String name = uniqueName("options-lt");
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.Placement.Tenancy", "default")
            .formParam("LaunchTemplateData.Placement.AvailabilityZone", "us-east-1a")
            .formParam("LaunchTemplateData.CreditSpecification.CpuCredits", "unlimited")
            .formParam("LaunchTemplateData.EnclaveOptions.Enabled", "false")
            .formParam("LaunchTemplateData.HibernationOptions.Configured", "false")
            .formParam("LaunchTemplateData.MaintenanceOptions.AutoRecovery", "default")
            .formParam("LaunchTemplateData.PrivateDnsNameOptions.HostnameType", "ip-name")
            .formParam("LaunchTemplateData.PrivateDnsNameOptions.EnableResourceNameDnsARecord", "true")
            .formParam("LaunchTemplateData.CapacityReservationSpecification.CapacityReservationPreference", "open")
            .formParam("LaunchTemplateData.DisableApiTermination", "false")
            .formParam("LaunchTemplateData.InstanceInitiatedShutdownBehavior", "terminate")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        describeLatest(name)
            .body(DATA + "placement.tenancy", equalTo("default"))
            .body(DATA + "placement.availabilityZone", equalTo("us-east-1a"))
            .body(DATA + "creditSpecification.cpuCredits", equalTo("unlimited"))
            .body(DATA + "enclaveOptions.enabled", equalTo("false"))
            .body(DATA + "hibernationOptions.configured", equalTo("false"))
            .body(DATA + "maintenanceOptions.autoRecovery", equalTo("default"))
            .body(DATA + "privateDnsNameOptions.hostnameType", equalTo("ip-name"))
            .body(DATA + "privateDnsNameOptions.enableResourceNameDnsARecord", equalTo("true"))
            .body(DATA + "capacityReservationSpecification.capacityReservationPreference", equalTo("open"))
            .body(DATA + "disableApiTermination", equalTo("false"))
            .body(DATA + "instanceInitiatedShutdownBehavior", equalTo("terminate"));
    }

    @Test
    void iamInstanceProfileKeepsTheFormItWasGiven() {
        // Terraform sets .name and read back .arn, so iam_instance_profile.name never converged.
        String byName = uniqueName("profile-name-lt");
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", byName)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.IamInstanceProfile.Name", "audit-ip")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        describeLatest(byName)
            .body(DATA + "iamInstanceProfile.name", equalTo("audit-ip"));
        assertFalse(describeBody(byName).contains("<arn>"),
                "a profile submitted as Name must not read back carrying an arn");

        String byArn = uniqueName("profile-arn-lt");
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", byArn)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.IamInstanceProfile.Arn",
                    "arn:aws:iam::000000000000:instance-profile/explicit")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        describeLatest(byArn)
            .body(DATA + "iamInstanceProfile.arn",
                    equalTo("arn:aws:iam::000000000000:instance-profile/explicit"));
        assertFalse(describeBody(byArn).contains("<name>"),
                "a profile submitted as Arn must not read back carrying a name");
    }

    @Test
    void networkInterfacesSurviveAsNetworkInterfacesInsteadOfBeingPromoted() {
        // Groups used to be hoisted into top-level SecurityGroupIds and the whole
        // NetworkInterfaces block discarded. On AWS the two are mutually exclusive, so the
        // network_interfaces block simply vanished from Terraform state.
        String name = uniqueName("eni-lt");
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.NetworkInterface.1.DeviceIndex", "0")
            .formParam("LaunchTemplateData.NetworkInterface.1.SubnetId", "subnet-0123456789abcdef0")
            .formParam("LaunchTemplateData.NetworkInterface.1.AssociatePublicIpAddress", "true")
            .formParam("LaunchTemplateData.NetworkInterface.1.DeleteOnTermination", "true")
            .formParam("LaunchTemplateData.NetworkInterface.1.SecurityGroupId.1", "sg-1111111111111111a")
            .formParam("LaunchTemplateData.NetworkInterface.1.SecurityGroupId.2", "sg-2222222222222222b")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        describeLatest(name)
            .body(DATA + "networkInterfaceSet.item.deviceIndex", equalTo("0"))
            .body(DATA + "networkInterfaceSet.item.subnetId", equalTo("subnet-0123456789abcdef0"))
            .body(DATA + "networkInterfaceSet.item.associatePublicIpAddress", equalTo("true"))
            .body(DATA + "networkInterfaceSet.item.deleteOnTermination", equalTo("true"))
            .body(DATA + "networkInterfaceSet.item.groupSet.item",
                    contains("sg-1111111111111111a", "sg-2222222222222222b"));
        // Not promoted to the top level: that promotion is what made the block disappear.
        assertFalse(describeBody(name).contains("securityGroupIdSet"),
                "interface groups must not be hoisted into top-level SecurityGroupIds");
    }

    @Test
    void createLaunchTemplateVersionInheritsOptionsBlocksItDoesNotRestate() {
        String name = uniqueName("version-lt");
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.InstanceType", "t3.micro")
            .formParam("LaunchTemplateData.MetadataOptions.HttpTokens", "required")
            .formParam("LaunchTemplateData.BlockDeviceMapping.1.DeviceName", "/dev/xvda")
            .formParam("LaunchTemplateData.BlockDeviceMapping.1.Ebs.VolumeSize", "20")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "CreateLaunchTemplateVersion")
            .formParam("LaunchTemplateName", name)
            .formParam("SourceVersion", "1")
            .formParam("LaunchTemplateData.InstanceType", "t3.small")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateLaunchTemplateVersionResponse.launchTemplateVersion.launchTemplateData.instanceType",
                    equalTo("t3.small"))
            .body("CreateLaunchTemplateVersionResponse.launchTemplateVersion.launchTemplateData"
                    + ".metadataOptions.httpTokens", equalTo("required"));

        describeLatest(name)
            .body(DATA + "instanceType", equalTo("t3.small"))
            .body(DATA + "metadataOptions.httpTokens", equalTo("required"))
            .body(DATA + "blockDeviceMappingSet.item.deviceName", equalTo("/dev/xvda"))
            .body(DATA + "blockDeviceMappingSet.item.ebs.volumeSize", equalTo("20"));
    }

    @Test
    void createLaunchTemplateVersionWithoutSourceVersionDoesNotInherit() {
        // AWS documents "no SourceVersion" as "no inheritance" — the new version must start from
        // an empty LaunchTemplateData, not merge onto the latest version the way an explicit
        // SourceVersion does (see createLaunchTemplateVersionInheritsOptionsBlocksItDoesNotRestate
        // above, which covers the explicit-SourceVersion case).
        String name = uniqueName("no-source-lt");
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.InstanceType", "t3.micro")
            .formParam("LaunchTemplateData.KeyName", "app-key")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "CreateLaunchTemplateVersion")
            .formParam("LaunchTemplateName", name)
            // No SourceVersion parameter at all.
            .formParam("LaunchTemplateData.InstanceType", "t3.small")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateLaunchTemplateVersionResponse.launchTemplateVersion.launchTemplateData.instanceType",
                    equalTo("t3.small"));

        String body = describeBody(name);
        assertFalse(body.contains("<imageId>"),
                "an omitted SourceVersion must not inherit ImageId from the latest version");
        assertFalse(body.contains("<keyName>"),
                "an omitted SourceVersion must not inherit KeyName from the latest version");
    }

    @Test
    void versionDescriptionRoundTripsThroughCreateAndDescribe() {
        String name = uniqueName("described-lt");
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("VersionDescription", "initial rollout")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId", org.hamcrest.Matchers.notNullValue());

        given()
            .formParam("Action", "CreateLaunchTemplateVersion")
            .formParam("LaunchTemplateName", name)
            .formParam("SourceVersion", "1")
            .formParam("LaunchTemplateData.InstanceType", "t3.small")
            .formParam("VersionDescription", "bump instance type")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateLaunchTemplateVersionResponse.launchTemplateVersion.versionDescription",
                    equalTo("bump instance type"));

        given()
            .formParam("Action", "DescribeLaunchTemplateVersions")
            .formParam("LaunchTemplateName", name)
            .formParam("Versions.1", "1")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.versionDescription",
                    equalTo("initial rollout"));

        describeLatest(name)
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.versionDescription",
                    equalTo("bump instance type"));
    }

    @Test
    void securityGroupsByNameAreAcceptedAndIgnored() {
        // By-name SecurityGroups stay out of scope (see docs/services/ec2.md): resolving names to
        // IDs would need lookup machinery no other EC2 action here has either. The request must
        // still succeed, and SecurityGroupIds — the supported form — must be unaffected.
        String name = uniqueName("sg-by-name-lt");
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.SecurityGroup.1", "my-app-sg")
            .formParam("LaunchTemplateData.SecurityGroupId.1", "sg-1111111111111111a")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        describeLatest(name)
            .body(DATA + "securityGroupIdSet.item", equalTo("sg-1111111111111111a"));
        assertFalse(describeBody(name).contains("my-app-sg"),
                "SecurityGroups (by name) is accepted and ignored, not stored");
    }

    @Test
    void instanceMarketOptionsRoundTrips() {
        // aws_launch_template's instance_market_options block. Dropping it made every spot
        // launch template diff forever, because Terraform read back no market options at all.
        String name = uniqueName("spot-lt");
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.InstanceMarketOptions.MarketType", "spot")
            .formParam("LaunchTemplateData.InstanceMarketOptions.SpotOptions.MaxPrice", "0.05")
            .formParam("LaunchTemplateData.InstanceMarketOptions.SpotOptions.SpotInstanceType", "one-time")
            .formParam("LaunchTemplateData.InstanceMarketOptions.SpotOptions.BlockDurationMinutes", "60")
            .formParam("LaunchTemplateData.InstanceMarketOptions.SpotOptions.ValidUntil", "2030-01-01T00:00:00Z")
            .formParam("LaunchTemplateData.InstanceMarketOptions.SpotOptions.InstanceInterruptionBehavior",
                    "terminate")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        describeLatest(name)
            .body(DATA + "instanceMarketOptions.marketType", equalTo("spot"))
            .body(DATA + "instanceMarketOptions.spotOptions.maxPrice", equalTo("0.05"))
            .body(DATA + "instanceMarketOptions.spotOptions.spotInstanceType", equalTo("one-time"))
            .body(DATA + "instanceMarketOptions.spotOptions.blockDurationMinutes", equalTo("60"))
            .body(DATA + "instanceMarketOptions.spotOptions.validUntil", equalTo("2030-01-01T00:00:00Z"))
            .body(DATA + "instanceMarketOptions.spotOptions.instanceInterruptionBehavior", equalTo("terminate"));
    }

    @Test
    void marketOptionsWithoutSpotOptionsOmitsTheSpotBlock() {
        String name = uniqueName("market-only-lt");
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.InstanceMarketOptions.MarketType", "spot")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        describeLatest(name)
            .body(DATA + "instanceMarketOptions.marketType", equalTo("spot"));
        assertFalse(describeBody(name).contains("<spotOptions>"),
                "an unset SpotOptions must stay absent rather than read back empty");
    }

    @Test
    void instanceRequirementsRoundTrips() {
        // Attribute-based instance type selection. The scalar lists carry the model's singular
        // locationName on the wire, so CpuManufacturer.N feeds cpuManufacturerSet on the way out.
        String name = uniqueName("requirements-lt");
        String req = "LaunchTemplateData.InstanceRequirements.";
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam(req + "VCpuCount.Min", "2")
            .formParam(req + "VCpuCount.Max", "8")
            .formParam(req + "MemoryMiB.Min", "1024")
            .formParam(req + "MemoryGiBPerVCpu.Min", "0.5")
            .formParam(req + "MemoryGiBPerVCpu.Max", "4.0")
            .formParam(req + "CpuManufacturer.1", "intel")
            .formParam(req + "CpuManufacturer.2", "amd")
            .formParam(req + "ExcludedInstanceType.1", "t2.*")
            .formParam(req + "InstanceGeneration.1", "current")
            .formParam(req + "BareMetal", "excluded")
            .formParam(req + "BurstablePerformance", "included")
            .formParam(req + "RequireHibernateSupport", "false")
            .formParam(req + "LocalStorage", "required")
            .formParam(req + "LocalStorageType.1", "ssd")
            .formParam(req + "TotalLocalStorageGB.Max", "100.0")
            .formParam(req + "NetworkInterfaceCount.Min", "1")
            .formParam(req + "BaselineEbsBandwidthMbps.Min", "100")
            .formParam(req + "AcceleratorType.1", "gpu")
            .formParam(req + "AcceleratorCount.Min", "1")
            .formParam(req + "AcceleratorManufacturer.1", "nvidia")
            .formParam(req + "AcceleratorName.1", "t4")
            .formParam(req + "AcceleratorTotalMemoryMiB.Min", "16")
            .formParam(req + "NetworkBandwidthGbps.Min", "1.5")
            .formParam(req + "SpotMaxPricePercentageOverLowestPrice", "20")
            .formParam(req + "OnDemandMaxPricePercentageOverLowestPrice", "30")
            .formParam(req + "RequireEncryptionInTransit", "true")
            .formParam(req + "BaselinePerformanceFactors.Cpu.Reference.1.InstanceFamily", "m6i")
            .formParam(req + "BaselinePerformanceFactors.Cpu.Reference.2.InstanceFamily", "c6i")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String set = DATA + "instanceRequirements.";
        describeLatest(name)
            .body(set + "vCpuCount.min", equalTo("2"))
            .body(set + "vCpuCount.max", equalTo("8"))
            .body(set + "memoryMiB.min", equalTo("1024"))
            .body(set + "memoryGiBPerVCpu.min", equalTo("0.5"))
            .body(set + "memoryGiBPerVCpu.max", equalTo("4.0"))
            .body(set + "cpuManufacturerSet.item", contains("intel", "amd"))
            .body(set + "excludedInstanceTypeSet.item", equalTo("t2.*"))
            .body(set + "instanceGenerationSet.item", equalTo("current"))
            .body(set + "bareMetal", equalTo("excluded"))
            .body(set + "burstablePerformance", equalTo("included"))
            .body(set + "requireHibernateSupport", equalTo("false"))
            .body(set + "localStorage", equalTo("required"))
            .body(set + "localStorageTypeSet.item", equalTo("ssd"))
            .body(set + "totalLocalStorageGB.max", equalTo("100.0"))
            .body(set + "networkInterfaceCount.min", equalTo("1"))
            .body(set + "baselineEbsBandwidthMbps.min", equalTo("100"))
            .body(set + "acceleratorTypeSet.item", equalTo("gpu"))
            .body(set + "acceleratorCount.min", equalTo("1"))
            .body(set + "acceleratorManufacturerSet.item", equalTo("nvidia"))
            .body(set + "acceleratorNameSet.item", equalTo("t4"))
            .body(set + "acceleratorTotalMemoryMiB.min", equalTo("16"))
            .body(set + "networkBandwidthGbps.min", equalTo("1.5"))
            .body(set + "spotMaxPricePercentageOverLowestPrice", equalTo("20"))
            .body(set + "onDemandMaxPricePercentageOverLowestPrice", equalTo("30"))
            .body(set + "requireEncryptionInTransit", equalTo("true"))
            .body(set + "baselinePerformanceFactors.cpu.referenceSet.item.instanceFamily",
                    contains("m6i", "c6i"));
        // A range the request never set must not read back as an empty element.
        assertFalse(describeBody(name).contains("<totalLocalStorageGB><min>"),
                "an unset range bound must stay absent");
    }

    @Test
    void theOtherHalfOfEachExclusiveRequirementsPairRoundTripsOnItsOwn() {
        // AllowedInstanceTypes excludes ExcludedInstanceTypes, and
        // MaxSpotPriceAsPercentageOfOptimalOnDemandPrice excludes
        // SpotMaxPricePercentageOverLowestPrice, so the members the round trip above cannot carry
        // are read back from a template of their own.
        String name = uniqueName("allowed-requirements-lt");
        String req = "LaunchTemplateData.InstanceRequirements.";
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam(req + "VCpuCount.Min", "2")
            .formParam(req + "MemoryMiB.Min", "1024")
            .formParam(req + "AllowedInstanceType.1", "m5.*")
            .formParam(req + "MaxSpotPriceAsPercentageOfOptimalOnDemandPrice", "40")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String set = DATA + "instanceRequirements.";
        describeLatest(name)
            .body(set + "allowedInstanceTypeSet.item", equalTo("m5.*"))
            .body(set + "maxSpotPriceAsPercentageOfOptimalOnDemandPrice", equalTo("40"));
    }

    @Test
    void connectionTrackingSpecificationRoundTripsInsideANetworkInterface() {
        String name = uniqueName("tracking-lt");
        String tracking = "LaunchTemplateData.NetworkInterface.1.ConnectionTrackingSpecification.";
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.NetworkInterface.1.DeviceIndex", "0")
            .formParam("LaunchTemplateData.NetworkInterface.1.SubnetId", "subnet-0123456789abcdef0")
            .formParam(tracking + "TcpEstablishedTimeout", "60")
            .formParam(tracking + "UdpStreamTimeout", "120")
            .formParam(tracking + "UdpTimeout", "30")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String item = DATA + "networkInterfaceSet.item.";
        describeLatest(name)
            .body(item + "deviceIndex", equalTo("0"))
            .body(item + "connectionTrackingSpecification.tcpEstablishedTimeout", equalTo("60"))
            .body(item + "connectionTrackingSpecification.udpStreamTimeout", equalTo("120"))
            .body(item + "connectionTrackingSpecification.udpTimeout", equalTo("30"));
    }

    @Test
    void aTemplateSettingNoneOfTheseBlocksReadsBackWithoutThem() {
        String name = uniqueName("bare-lt");
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.InstanceType", "t3.micro")
            .formParam("LaunchTemplateData.NetworkInterface.1.DeviceIndex", "0")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String body = describeBody(name);
        assertFalse(body.contains("instanceMarketOptions"),
                "an unset InstanceMarketOptions must stay absent");
        assertFalse(body.contains("instanceRequirements"),
                "an unset InstanceRequirements must stay absent");
        assertFalse(body.contains("connectionTrackingSpecification"),
                "an unset ConnectionTrackingSpecification must stay absent");
    }

    @Test
    void aNewVersionInheritsMarketOptionsAndRequirementsItDoesNotRestate() {
        String name = uniqueName("inherit-lt");
        String tracking = "LaunchTemplateData.NetworkInterface.1.ConnectionTrackingSpecification.";
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.InstanceMarketOptions.MarketType", "spot")
            .formParam("LaunchTemplateData.InstanceMarketOptions.SpotOptions.MaxPrice", "0.05")
            .formParam("LaunchTemplateData.InstanceRequirements.VCpuCount.Min", "2")
            .formParam("LaunchTemplateData.InstanceRequirements.MemoryMiB.Min", "1024")
            .formParam("LaunchTemplateData.NetworkInterface.1.DeviceIndex", "0")
            .formParam(tracking + "TcpEstablishedTimeout", "60")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // The source selects instance types by attribute, so the new version restates a member
        // that does not collide with InstanceRequirements.
        given()
            .formParam("Action", "CreateLaunchTemplateVersion")
            .formParam("LaunchTemplateName", name)
            .formParam("SourceVersion", "1")
            .formParam("LaunchTemplateData.KeyName", "app-key")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        describeLatest(name)
            .body(DATA + "keyName", equalTo("app-key"))
            .body(DATA + "instanceMarketOptions.marketType", equalTo("spot"))
            .body(DATA + "instanceMarketOptions.spotOptions.maxPrice", equalTo("0.05"))
            .body(DATA + "instanceRequirements.vCpuCount.min", equalTo("2"))
            .body(DATA + "instanceRequirements.memoryMiB.min", equalTo("1024"))
            .body(DATA + "networkInterfaceSet.item.connectionTrackingSpecification.tcpEstablishedTimeout",
                    equalTo("60"));
    }

    private ValidatableResponse createWithRequirements(String name, String... requirementParams) {
        RequestSpecification request = given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .header("Authorization", AUTH_HEADER);
        for (int i = 0; i < requirementParams.length; i += 2) {
            request = request.formParam(
                    "LaunchTemplateData.InstanceRequirements." + requirementParams[i], requirementParams[i + 1]);
        }
        return request.when().post("/").then();
    }

    private ValidatableResponse createWithConnectionTracking(String name, String parameter, String value) {
        return given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.NetworkInterface.1.DeviceIndex", "0")
            .formParam("LaunchTemplateData.NetworkInterface.1.ConnectionTrackingSpecification." + parameter, value)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then();
    }

    private void assertInvalidParameterValue(ValidatableResponse response) {
        response.statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
    }

    @Test
    void instanceRequirementsWithoutVCpuCountIsRejected() {
        // InstanceRequirementsRequest declares required: ["VCpuCount", "MemoryMiB"], so a block
        // carrying only an optional member is not a launch template AWS would store.
        String name = uniqueName("no-vcpu-lt");
        createWithRequirements(name, "BurstablePerformance", "included")
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("MissingParameter"));

        given()
            .formParam("Action", "DescribeLaunchTemplates")
            .formParam("LaunchTemplateName.1", name)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidLaunchTemplateName.NotFoundException"));
    }

    @Test
    void instanceRequirementsWithoutMemoryMiBIsRejected() {
        createWithRequirements(uniqueName("no-memory-lt"), "VCpuCount.Min", "2")
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("MissingParameter"));
    }

    @Test
    void aRequiredRangeWithoutItsMinIsRejected() {
        // VCpuCountRangeRequest and MemoryMiBRequest each declare required: ["Min"].
        createWithRequirements(uniqueName("max-only-lt"), "VCpuCount.Max", "8", "MemoryMiB.Min", "1024")
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("MissingParameter"));
        createWithRequirements(uniqueName("memory-max-only-lt"), "VCpuCount.Min", "2", "MemoryMiB.Max", "4096")
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("MissingParameter"));
    }

    @Test
    void bothRequiredMembersTogetherAreAccepted() {
        createWithRequirements(uniqueName("required-pair-lt"), "VCpuCount.Min", "2", "MemoryMiB.Min", "1024")
            .statusCode(200);
    }

    @Test
    void tcpEstablishedTimeoutKeepsItsDocumentedRange() {
        // "Min: 60 seconds. Max: 432000 seconds (5 days)."
        createWithConnectionTracking(uniqueName("tcp-min-lt"), "TcpEstablishedTimeout", "60").statusCode(200);
        createWithConnectionTracking(uniqueName("tcp-max-lt"), "TcpEstablishedTimeout", "432000").statusCode(200);
        assertInvalidParameterValue(
                createWithConnectionTracking(uniqueName("tcp-under-lt"), "TcpEstablishedTimeout", "59"));
        assertInvalidParameterValue(
                createWithConnectionTracking(uniqueName("tcp-over-lt"), "TcpEstablishedTimeout", "432001"));
    }

    @Test
    void udpTimeoutKeepsItsDocumentedRange() {
        // "Min: 30 seconds. Max: 60 seconds."
        createWithConnectionTracking(uniqueName("udp-min-lt"), "UdpTimeout", "30").statusCode(200);
        createWithConnectionTracking(uniqueName("udp-max-lt"), "UdpTimeout", "60").statusCode(200);
        assertInvalidParameterValue(createWithConnectionTracking(uniqueName("udp-under-lt"), "UdpTimeout", "29"));
        assertInvalidParameterValue(createWithConnectionTracking(uniqueName("udp-over-lt"), "UdpTimeout", "61"));
    }

    @Test
    void udpStreamTimeoutKeepsItsDocumentedRange() {
        // "Min: 60 seconds. Max: 180 seconds (3 minutes)."
        createWithConnectionTracking(uniqueName("stream-min-lt"), "UdpStreamTimeout", "60").statusCode(200);
        createWithConnectionTracking(uniqueName("stream-max-lt"), "UdpStreamTimeout", "180").statusCode(200);
        assertInvalidParameterValue(
                createWithConnectionTracking(uniqueName("stream-under-lt"), "UdpStreamTimeout", "59"));
        assertInvalidParameterValue(
                createWithConnectionTracking(uniqueName("stream-over-lt"), "UdpStreamTimeout", "181"));
    }

    @Test
    void createLaunchTemplateVersionValidatesTheSameWayAndStoresNothingWhenItFails() {
        String name = uniqueName("version-validation-lt");
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .formParam("LaunchTemplateData.InstanceType", "t3.micro")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "CreateLaunchTemplateVersion")
            .formParam("LaunchTemplateName", name)
            .formParam("SourceVersion", "1")
            .formParam("LaunchTemplateData.InstanceRequirements.BurstablePerformance", "included")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("MissingParameter"));

        given()
            .formParam("Action", "CreateLaunchTemplateVersion")
            .formParam("LaunchTemplateName", name)
            .formParam("SourceVersion", "1")
            .formParam("LaunchTemplateData.NetworkInterface.1.DeviceIndex", "0")
            .formParam("LaunchTemplateData.NetworkInterface.1.ConnectionTrackingSpecification.UdpTimeout", "999")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));

        given()
            .formParam("Action", "DescribeLaunchTemplates")
            .formParam("LaunchTemplateName.1", name)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeLaunchTemplatesResponse.launchTemplates.item.latestVersionNumber", equalTo("1"));
    }

    private ValidatableResponse createTemplate(String name, String... dataParams) {
        RequestSpecification request = given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", name)
            .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
            .header("Authorization", AUTH_HEADER);
        for (int i = 0; i < dataParams.length; i += 2) {
            request = request.formParam("LaunchTemplateData." + dataParams[i], dataParams[i + 1]);
        }
        return request.when().post("/").then();
    }

    private ValidatableResponse createVersion(String name, String sourceVersion, String... dataParams) {
        RequestSpecification request = given()
            .formParam("Action", "CreateLaunchTemplateVersion")
            .formParam("LaunchTemplateName", name)
            .header("Authorization", AUTH_HEADER);
        if (sourceVersion != null) {
            request = request.formParam("SourceVersion", sourceVersion);
        }
        for (int i = 0; i < dataParams.length; i += 2) {
            request = request.formParam("LaunchTemplateData." + dataParams[i], dataParams[i + 1]);
        }
        return request.when().post("/").then();
    }

    private void assertInvalidParameterCombination(ValidatableResponse response) {
        response.statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"));
    }

    private void assertLatestVersionIs(String name, String expected) {
        given()
            .formParam("Action", "DescribeLaunchTemplates")
            .formParam("LaunchTemplateName.1", name)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeLaunchTemplatesResponse.launchTemplates.item.latestVersionNumber", equalTo(expected));
    }

    @Test
    void instanceRequirementsAlongsideInstanceTypeIsRejected() {
        // "If you specify InstanceRequirements, you can't specify InstanceType."
        String name = uniqueName("type-and-requirements-lt");
        assertInvalidParameterCombination(createTemplate(name,
                "InstanceType", "t3.micro",
                "InstanceRequirements.VCpuCount.Min", "2",
                "InstanceRequirements.MemoryMiB.Min", "1024"));

        given()
            .formParam("Action", "DescribeLaunchTemplates")
            .formParam("LaunchTemplateName.1", name)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidLaunchTemplateName.NotFoundException"));
    }

    @Test
    void eitherOfInstanceTypeAndInstanceRequirementsOnItsOwnIsAccepted() {
        createTemplate(uniqueName("type-only-lt"), "InstanceType", "t3.micro").statusCode(200);
        createTemplate(uniqueName("requirements-only-lt"),
                "InstanceRequirements.VCpuCount.Min", "2",
                "InstanceRequirements.MemoryMiB.Min", "1024").statusCode(200);
    }

    @Test
    void allowedAndExcludedInstanceTypesTogetherAreRejected() {
        // "If you specify AllowedInstanceTypes, you can't specify ExcludedInstanceTypes."
        assertInvalidParameterCombination(createWithRequirements(uniqueName("both-lists-lt"),
                "VCpuCount.Min", "2", "MemoryMiB.Min", "1024",
                "AllowedInstanceType.1", "m5.*", "ExcludedInstanceType.1", "t2.*"));
        createWithRequirements(uniqueName("allowed-only-lt"),
                "VCpuCount.Min", "2", "MemoryMiB.Min", "1024", "AllowedInstanceType.1", "m5.*")
            .statusCode(200);
        createWithRequirements(uniqueName("excluded-only-lt"),
                "VCpuCount.Min", "2", "MemoryMiB.Min", "1024", "ExcludedInstanceType.1", "t2.*")
            .statusCode(200);
    }

    @Test
    void bothSpotPriceCeilingsTogetherAreRejected() {
        // "Only one of SpotMaxPricePercentageOverLowestPrice or
        // MaxSpotPriceAsPercentageOfOptimalOnDemandPrice can be specified."
        assertInvalidParameterCombination(createWithRequirements(uniqueName("both-spot-lt"),
                "VCpuCount.Min", "2", "MemoryMiB.Min", "1024",
                "SpotMaxPricePercentageOverLowestPrice", "20",
                "MaxSpotPriceAsPercentageOfOptimalOnDemandPrice", "40"));
        createWithRequirements(uniqueName("spot-over-lowest-lt"),
                "VCpuCount.Min", "2", "MemoryMiB.Min", "1024",
                "SpotMaxPricePercentageOverLowestPrice", "20")
            .statusCode(200);
        createWithRequirements(uniqueName("spot-over-on-demand-lt"),
                "VCpuCount.Min", "2", "MemoryMiB.Min", "1024",
                "MaxSpotPriceAsPercentageOfOptimalOnDemandPrice", "40")
            .statusCode(200);
    }

    @Test
    void createLaunchTemplateVersionRejectsTheSameExclusiveCombinations() {
        String name = uniqueName("version-exclusive-lt");
        createTemplate(name, "InstanceType", "t3.micro").statusCode(200);

        assertInvalidParameterCombination(createVersion(name, null,
                "InstanceType", "t3.small",
                "InstanceRequirements.VCpuCount.Min", "2",
                "InstanceRequirements.MemoryMiB.Min", "1024"));
        assertInvalidParameterCombination(createVersion(name, null,
                "InstanceRequirements.VCpuCount.Min", "2",
                "InstanceRequirements.MemoryMiB.Min", "1024",
                "InstanceRequirements.AllowedInstanceType.1", "m5.*",
                "InstanceRequirements.ExcludedInstanceType.1", "t2.*"));
        assertInvalidParameterCombination(createVersion(name, null,
                "InstanceRequirements.VCpuCount.Min", "2",
                "InstanceRequirements.MemoryMiB.Min", "1024",
                "InstanceRequirements.SpotMaxPricePercentageOverLowestPrice", "20",
                "InstanceRequirements.MaxSpotPriceAsPercentageOfOptimalOnDemandPrice", "40"));

        assertLatestVersionIs(name, "1");
    }

    @Test
    void aVersionNamingAnInstanceTypeOverAnAttributeBasedSourceIsRejected() {
        // The merge carries InstanceRequirements forward and has no way to express removal, so
        // this version would store both. The stored version is what AutoScaling and the fleet
        // APIs read, so it is validated after the merge rather than only as it arrived.
        String name = uniqueName("merge-conflict-lt");
        createTemplate(name,
                "InstanceRequirements.VCpuCount.Min", "2",
                "InstanceRequirements.MemoryMiB.Min", "1024").statusCode(200);

        assertInvalidParameterCombination(createVersion(name, "1", "InstanceType", "t3.micro"));
        assertLatestVersionIs(name, "1");

        // Starting from empty data instead of the source is how a caller switches selection mode.
        createVersion(name, null, "InstanceType", "t3.micro").statusCode(200);
        describeLatest(name).body(DATA + "instanceType", equalTo("t3.micro"));
    }
}
