package io.floci.aws.services.emr;

import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static io.floci.aws.testing.EmrRequests.emr;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code ListInstanceFleets} gives each fleet's {@code InstanceTypeConfigs} back as its
 * {@code InstanceTypeSpecifications}, with the defaults the API reference gives where a config leaves them out, which is
 * what a client reading a fleet's configuration back compares its own against.
 */
@QuarkusTest
class EmrInstanceTypeSpecificationsIntegrationTest {

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void eachInstanceTypeConfigIsListedAsTheSpecificationItDescribes() {
        String id = cluster("""
                [{"InstanceFleetType": "MASTER", "TargetOnDemandCapacity": 1,
                  "InstanceTypeConfigs": [{"InstanceType": "m5.xlarge"}]},
                 {"InstanceFleetType": "CORE", "TargetOnDemandCapacity": 2, "TargetSpotCapacity": 4,
                  "InstanceTypeConfigs": [
                    {"InstanceType": "r5.2xlarge", "WeightedCapacity": 2, "BidPrice": "0.5",
                     "CustomAmiId": "ami-0123456789abcdef0", "Priority": 1.0,
                     "Configurations": [{"Classification": "spark", "Properties": {"maximizeResourceAllocation": "true"}}],
                     "EbsConfiguration": {"EbsOptimized": true, "EbsBlockDeviceConfigs": [
                       {"VolumeSpecification": {"VolumeType": "gp3", "SizeInGB": 64, "Iops": 3000, "Throughput": 125},
                        "VolumesPerInstance": 2},
                       {"VolumeSpecification": {"VolumeType": "gp2", "SizeInGB": 32}}]}},
                    {"InstanceType": "m5.2xlarge", "BidPriceAsPercentageOfOnDemandPrice": 60}]}]""");

        String master = "InstanceFleets.find { it.InstanceFleetType == 'MASTER' }.InstanceTypeSpecifications";
        String core = "InstanceFleets.find { it.InstanceFleetType == 'CORE' }.InstanceTypeSpecifications";
        ValidatableResponse fleets = emr("ListInstanceFleets", "{\"ClusterId\":\"" + id + "\"}").then().statusCode(200);
        fleets.body(master, hasSize(1))
                .body(master + "[0].InstanceType", equalTo("m5.xlarge"))
                .body(master + "[0].WeightedCapacity", equalTo(1))
                .body(master + "[0].BidPriceAsPercentageOfOnDemandPrice", equalTo(100.0f))
                .body(master + "[0]", not(hasKey("BidPrice")))
                .body(master + "[0]", not(hasKey("EbsBlockDevices")))
                .body(core, hasSize(2))
                .body(core + "[0].InstanceType", equalTo("r5.2xlarge"))
                .body(core + "[0].WeightedCapacity", equalTo(2))
                .body(core + "[0].BidPrice", equalTo("0.5"))
                .body(core + "[0]", not(hasKey("BidPriceAsPercentageOfOnDemandPrice")))
                .body(core + "[0].CustomAmiId", equalTo("ami-0123456789abcdef0"))
                .body(core + "[0].Priority", equalTo(1.0f))
                .body(core + "[0].Configurations[0].Classification", equalTo("spark"))
                // GPath answers null for a key named Properties read as ".Properties" or "['Properties']".
                .body(core + "[0].Configurations[0].get('Properties').maximizeResourceAllocation", equalTo("true"))
                .body(core + "[0].EbsOptimized", equalTo(true))
                .body(core + "[0].EbsBlockDevices.Device", equalTo(List.of("/dev/sdb", "/dev/sdc", "/dev/sdd")))
                .body(core + "[0].EbsBlockDevices.VolumeSpecification.VolumeType", equalTo(List.of("gp3", "gp3", "gp2")))
                .body(core + "[0].EbsBlockDevices[1].VolumeSpecification.SizeInGB", equalTo(64))
                .body(core + "[0].EbsBlockDevices[1].VolumeSpecification.Iops", equalTo(3000))
                .body(core + "[0].EbsBlockDevices[1].VolumeSpecification.Throughput", equalTo(125))
                .body(core + "[1].InstanceType", equalTo("m5.2xlarge"))
                .body(core + "[1].WeightedCapacity", equalTo(1))
                .body(core + "[1].BidPriceAsPercentageOfOnDemandPrice", equalTo(60.0f))
                .body(core + "[1]", not(hasKey("BidPrice")));
    }

    @Test
    void anAddedFleetListsItsSpecificationsToo() {
        String id = cluster("""
                [{"InstanceFleetType": "MASTER", "TargetOnDemandCapacity": 1,
                  "InstanceTypeConfigs": [{"InstanceType": "m5.xlarge"}]}]""");
        String task = emr("AddInstanceFleet", "{\"ClusterId\":\"" + id + "\",\"InstanceFleet\":"
                + "{\"InstanceFleetType\":\"TASK\",\"TargetSpotCapacity\":1,"
                + "\"InstanceTypeConfigs\":[{\"InstanceType\":\"c5.xlarge\",\"BidPrice\":\"0.2\"}]}}")
                .then().statusCode(200).extract().path("InstanceFleetId");

        String specifications = "InstanceFleets.find { it.Id == '" + task + "' }.InstanceTypeSpecifications";
        emr("ListInstanceFleets", "{\"ClusterId\":\"" + id + "\"}").then().statusCode(200)
                .body(specifications + ".InstanceType", equalTo(List.of("c5.xlarge")))
                .body(specifications + "[0].BidPrice", equalTo("0.2"))
                .body(specifications + "[0].WeightedCapacity", equalTo(1));
    }

    /** The Management Guide: "There is a limit of 25 volumes per instance." */
    @Test
    void aConfigAttachingMoreThan25VolumesPerInstanceIsRefused() {
        String tooMany = """
                [{"InstanceFleetType": "MASTER", "TargetOnDemandCapacity": 1,
                  "InstanceTypeConfigs": [{"InstanceType": "m5.xlarge", "EbsConfiguration": {"EbsBlockDeviceConfigs": [
                    {"VolumeSpecification": {"VolumeType": "gp3", "SizeInGB": 32}, "VolumesPerInstance": 20},
                    {"VolumeSpecification": {"VolumeType": "gp2", "SizeInGB": 32}, "VolumesPerInstance": 6}]}}]}]""";
        emr("RunJobFlow", "{\"Name\":\"too-many-volumes\",\"ReleaseLabel\":\"emr-7.2.0\","
                + "\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,\"InstanceFleets\":" + tooMany + "}}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("There is a limit of 25 volumes per instance."));
    }

    @Test
    void devicesAreNamedPastSdzAsLinuxNamesThem() {
        assertEquals("/dev/sdb", EmrHandler.deviceName(0));
        assertEquals("/dev/sdz", EmrHandler.deviceName(24));
        assertEquals("/dev/sdaa", EmrHandler.deviceName(25));
        assertEquals("/dev/sdab", EmrHandler.deviceName(26));
    }

    /** A cluster with {@code fleets}, terminated after the test. */
    private String cluster(String fleets) {
        String id = emr("RunJobFlow", "{\"Name\":\"fleet-specifications\",\"ReleaseLabel\":\"emr-7.2.0\","
                + "\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,\"InstanceFleets\":" + fleets + "}}")
                .then().statusCode(200).extract().path("JobFlowId");
        cleanup.register(() -> emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + id + "\"]}").then().statusCode(200));
        return id;
    }
}
