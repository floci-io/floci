package io.github.hectorvent.floci.services.ec2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * lex00/floci#119: LaunchTemplateData modeled only ~6 of AWS's ~30 documented
 * RequestLaunchTemplateData/ResponseLaunchTemplateData fields (oracle: botocore's
 * ec2/2016-11-15/service-2.json), so CreateLaunchTemplate accepted and then dropped
 * block_device_mappings, capacity_reservation_specification, cpu_options,
 * instance_market_options, maintenance_options, network_interfaces, placement,
 * tag_specifications, instance_requirements, description (VersionDescription) and
 * ebs_optimized, and DescribeLaunchTemplateVersions could not echo any of them back.
 * One test class per field group, per this unit's own instructions.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2LaunchTemplateFieldsIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-west-2/ec2/aws4_request";

    @Test
    @Order(1)
    void createRoundTripsEveryWidenedFieldGroup() {
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", "widened-fields-test")
            .formParam("VersionDescription", "initial version")
            .formParam("LaunchTemplateData.ImageId", "ami-0123456789abcdef0")
            .formParam("LaunchTemplateData.InstanceType", "t3.micro")
            .formParam("LaunchTemplateData.EbsOptimized", "true")
            // block_device_mappings
            .formParam("LaunchTemplateData.BlockDeviceMapping.1.DeviceName", "/dev/xvda")
            .formParam("LaunchTemplateData.BlockDeviceMapping.1.Ebs.VolumeSize", "20")
            .formParam("LaunchTemplateData.BlockDeviceMapping.1.Ebs.VolumeType", "gp3")
            .formParam("LaunchTemplateData.BlockDeviceMapping.1.Ebs.DeleteOnTermination", "true")
            .formParam("LaunchTemplateData.BlockDeviceMapping.1.Ebs.Encrypted", "true")
            .formParam("LaunchTemplateData.BlockDeviceMapping.1.Ebs.KmsKeyId", "arn:aws:kms:us-west-2:000000000000:key/test-key")
            .formParam("LaunchTemplateData.BlockDeviceMapping.2.DeviceName", "/dev/xvdb")
            .formParam("LaunchTemplateData.BlockDeviceMapping.2.VirtualName", "ephemeral0")
            // capacity_reservation_specification
            .formParam("LaunchTemplateData.CapacityReservationSpecification.CapacityReservationPreference", "open")
            // cpu_options
            .formParam("LaunchTemplateData.CpuOptions.CoreCount", "2")
            .formParam("LaunchTemplateData.CpuOptions.ThreadsPerCore", "1")
            // instance_market_options
            .formParam("LaunchTemplateData.InstanceMarketOptions.MarketType", "spot")
            .formParam("LaunchTemplateData.InstanceMarketOptions.SpotOptions.MaxPrice", "0.05")
            .formParam("LaunchTemplateData.InstanceMarketOptions.SpotOptions.SpotInstanceType", "one-time")
            // maintenance_options
            .formParam("LaunchTemplateData.MaintenanceOptions.AutoRecovery", "disabled")
            // network_interfaces
            .formParam("LaunchTemplateData.NetworkInterface.1.DeviceIndex", "0")
            .formParam("LaunchTemplateData.NetworkInterface.1.SubnetId", "subnet-0123456789abcdef0")
            .formParam("LaunchTemplateData.NetworkInterface.1.AssociatePublicIpAddress", "true")
            .formParam("LaunchTemplateData.NetworkInterface.1.DeleteOnTermination", "true")
            .formParam("LaunchTemplateData.NetworkInterface.1.SecurityGroupId.1", "sg-0123456789abcdef0")
            // placement
            .formParam("LaunchTemplateData.Placement.AvailabilityZone", "us-west-2a")
            .formParam("LaunchTemplateData.Placement.Tenancy", "dedicated")
            .formParam("LaunchTemplateData.Placement.GroupName", "cluster-group")
            // tag_specifications (a non-"instance" resource type, to prove genericity)
            .formParam("LaunchTemplateData.TagSpecification.1.ResourceType", "volume")
            .formParam("LaunchTemplateData.TagSpecification.1.Tag.1.Key", "Name")
            .formParam("LaunchTemplateData.TagSpecification.1.Tag.1.Value", "widened-fields-volume")
            // instance_requirements
            .formParam("LaunchTemplateData.InstanceRequirements.VCpuCount.Min", "2")
            .formParam("LaunchTemplateData.InstanceRequirements.VCpuCount.Max", "8")
            .formParam("LaunchTemplateData.InstanceRequirements.MemoryMiB.Min", "2048")
            .formParam("LaunchTemplateData.InstanceRequirements.BareMetal", "excluded")
            .formParam("LaunchTemplateData.InstanceRequirements.InstanceGeneration.1", "current")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml");
    }

    @Test
    @Order(2)
    void describeLaunchTemplateVersionsEchoesEveryWidenedFieldGroupBack() {
        given()
            .formParam("Action", "DescribeLaunchTemplateVersions")
            .formParam("LaunchTemplateName", "widened-fields-test")
            .formParam("Versions.1", "$Latest")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.versionDescription",
                    equalTo("initial version"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.ebsOptimized",
                    equalTo("true"))
            // block_device_mappings
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.blockDeviceMappingSet.item[0].deviceName",
                    equalTo("/dev/xvda"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.blockDeviceMappingSet.item[0].ebs.volumeSize",
                    equalTo("20"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.blockDeviceMappingSet.item[0].ebs.volumeType",
                    equalTo("gp3"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.blockDeviceMappingSet.item[0].ebs.kmsKeyId",
                    equalTo("arn:aws:kms:us-west-2:000000000000:key/test-key"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.blockDeviceMappingSet.item[1].virtualName",
                    equalTo("ephemeral0"))
            // capacity_reservation_specification
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.capacityReservationSpecification.capacityReservationPreference",
                    equalTo("open"))
            // cpu_options
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.cpuOptions.coreCount",
                    equalTo("2"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.cpuOptions.threadsPerCore",
                    equalTo("1"))
            // instance_market_options
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.instanceMarketOptions.marketType",
                    equalTo("spot"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.instanceMarketOptions.spotOptions.maxPrice",
                    equalTo("0.05"))
            // maintenance_options
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.maintenanceOptions.autoRecovery",
                    equalTo("disabled"))
            // network_interfaces
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.networkInterfaceSet.item.subnetId",
                    equalTo("subnet-0123456789abcdef0"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.networkInterfaceSet.item.associatePublicIpAddress",
                    equalTo("true"))
            // placement
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.placement.availabilityZone",
                    equalTo("us-west-2a"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.placement.tenancy",
                    equalTo("dedicated"))
            // tag_specifications
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.tagSpecificationSet.item.resourceType",
                    equalTo("volume"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.tagSpecificationSet.item.tagSet.item.value",
                    equalTo("widened-fields-volume"))
            // instance_requirements
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.instanceRequirements.vCpuCount.min",
                    equalTo("2"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.instanceRequirements.vCpuCount.max",
                    equalTo("8"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.instanceRequirements.memoryMiB.min",
                    equalTo("2048"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.instanceRequirements.bareMetal",
                    equalTo("excluded"));
    }

    @Test
    @Order(3)
    void createLaunchTemplateVersionOverridesOnlyWhatItSetsAndInheritsTheRest() {
        given()
            .formParam("Action", "CreateLaunchTemplateVersion")
            .formParam("LaunchTemplateName", "widened-fields-test")
            .formParam("SourceVersion", "1")
            .formParam("VersionDescription", "bumped cpu options only")
            .formParam("LaunchTemplateData.CpuOptions.CoreCount", "4")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeLaunchTemplateVersions")
            .formParam("LaunchTemplateName", "widened-fields-test")
            .formParam("Versions.1", "2")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.versionDescription",
                    equalTo("bumped cpu options only"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.cpuOptions.coreCount",
                    equalTo("4"))
            // Inherited from version 1, untouched by this request.
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.ebsOptimized",
                    equalTo("true"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.placement.availabilityZone",
                    equalTo("us-west-2a"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.instanceRequirements.vCpuCount.min",
                    equalTo("2"));
    }

    @Test
    @Order(4)
    void aTemplateThatNeverSetAnyWidenedFieldOmitsAllOfThemRatherThanInventingDefaults() {
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", "no-widened-fields-test")
            .formParam("LaunchTemplateData.ImageId", "ami-0123456789abcdef0")
            .formParam("LaunchTemplateData.InstanceType", "t3.micro")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String xml = given()
            .formParam("Action", "DescribeLaunchTemplateVersions")
            .formParam("LaunchTemplateName", "no-widened-fields-test")
            .formParam("Versions.1", "$Latest")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
        assertFalse(xml.contains("versionDescription"), xml);
        assertFalse(xml.contains("ebsOptimized"), xml);
        assertFalse(xml.contains("blockDeviceMappingSet"), xml);
        assertFalse(xml.contains("capacityReservationSpecification"), xml);
        assertFalse(xml.contains("cpuOptions"), xml);
        assertFalse(xml.contains("instanceMarketOptions"), xml);
        assertFalse(xml.contains("maintenanceOptions"), xml);
        assertFalse(xml.contains("networkInterfaceSet"), xml);
        assertFalse(xml.contains("placement"), xml);
        assertFalse(xml.contains("tagSpecificationSet"), xml);
        assertFalse(xml.contains("instanceRequirements"), xml);
    }
}
