package io.floci.aws.services.emr;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.emr.model.EmrCluster;
import io.floci.aws.services.emr.model.EmrInstanceFleet;
import io.floci.aws.services.emr.model.EmrInstanceGroup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;

import static io.floci.aws.testing.EmrRequests.emr;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EmrTerminatedCapacityIntegrationTest {

    private static final String GROUPS = "\"InstanceGroups\":["
            + "{\"InstanceRole\":\"MASTER\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1},"
            + "{\"InstanceRole\":\"CORE\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":2}]";
    private static final String FLEETS = "\"InstanceFleets\":["
            + "{\"InstanceFleetType\":\"MASTER\",\"TargetOnDemandCapacity\":1,"
            + "\"InstanceTypeConfigs\":[{\"InstanceType\":\"m5.xlarge\"}]},"
            + "{\"InstanceFleetType\":\"CORE\",\"TargetOnDemandCapacity\":2,"
            + "\"TargetSpotCapacity\":3,\"InstanceTypeConfigs\":[{\"InstanceType\":\"m5.xlarge\"}]}]";
    private static final String STEP = "{\"Name\":\"complete\","
            + "\"HadoopJarStep\":{\"Jar\":\"command-runner.jar\"}}";
    private static final String GROUP_REFUSAL =
            "An instance group may only be modified when the cluster is running or waiting.";
    private static final String SHUT_DOWN =
            "A job flow that is shutting down, terminated, or finished may not be modified.";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void initialStepsTerminateInstanceGroupsAndReleaseRunningCount() {
        String id = emr("RunJobFlow", "{\"Name\":\"group-auto\",\"Instances\":{"
                + GROUPS + "},\"Steps\":[" + STEP + "]}")
                .then().statusCode(200).extract().path("JobFlowId");

        assertTerminatedGroup(id);
        assertCannotModifyGroup(id, false);
    }

    @Test
    void addedStepsTerminateInstanceFleetsAndReleaseProvisionedCapacity() {
        String id = emr("RunJobFlow", "{\"Name\":\"fleet-auto\",\"Instances\":{"
                + FLEETS + "}}")
                .then().statusCode(200).extract().path("JobFlowId");
        String request = clusterRequest(id);
        emr("ListInstanceFleets", request).then().statusCode(200)
                .body("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.Status.State", equalTo("RUNNING"))
                .body("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.ProvisionedOnDemandCapacity", equalTo(2))
                .body("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.ProvisionedSpotCapacity", equalTo(3));

        emr("AddJobFlowSteps", "{\"JobFlowId\":\"" + id + "\",\"Steps\":[" + STEP + "]}")
                .then().statusCode(200);
        assertTerminatedFleet(id);
        assertCannotModifyFleet(id);
    }

    @Test
    void manualTerminationReleasesInstanceGroups() {
        String id = emr("RunJobFlow", "{\"Name\":\"group-manual\",\"Instances\":{"
                + "\"KeepJobFlowAliveWhenNoSteps\":true," + GROUPS + "}}")
                .then().statusCode(200).extract().path("JobFlowId");
        emr("ListInstanceGroups", clusterRequest(id)).then().statusCode(200)
                .body("InstanceGroups.find { it.InstanceGroupType == 'CORE' }.Status.State", equalTo("RUNNING"))
                .body("InstanceGroups.find { it.InstanceGroupType == 'CORE' }.RunningInstanceCount", equalTo(2));
        List<String> originalIds = emr("ListInstances", clusterRequest(id)).then().statusCode(200)
                .extract().jsonPath().getList("Instances.Id");
        List<String> originalEc2Ids = emr("ListInstances", clusterRequest(id)).then().statusCode(200)
                .extract().jsonPath().getList("Instances.Ec2InstanceId");
        emr("ListInstances", "{\"ClusterId\":\"" + id + "\",\"InstanceStates\":[\"TERMINATED\"]}")
                .then().statusCode(200).body("Instances", hasSize(0));

        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + id + "\"]}")
                .then().statusCode(200);
        assertTerminatedGroup(id);
        emr("ListInstances", clusterRequest(id)).then().statusCode(200)
                .body("Instances.Id", equalTo(originalIds))
                .body("Instances.Ec2InstanceId", equalTo(originalEc2Ids));
        emr("ListInstances", "{\"ClusterId\":\"" + id + "\",\"InstanceStates\":[\"RUNNING\"]}")
                .then().statusCode(200).body("Instances", hasSize(0));
        emr("ListInstances", "{\"ClusterId\":\"" + id + "\",\"InstanceStates\":[\"TERMINATED\"]}")
                .then().statusCode(200)
                .body("Instances.Id", equalTo(originalIds))
                .body("Instances.Ec2InstanceId", equalTo(originalEc2Ids));
        assertCannotModifyGroup(id, true);
        emr("AddInstanceGroups", "{\"JobFlowId\":\"" + id + "\",\"InstanceGroups\":["
                + "{\"InstanceRole\":\"TASK\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1}]}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo(SHUT_DOWN));
        assertTerminatedGroup(id);
    }

    @Test
    void manualTerminationReleasesInstanceFleets() {
        String id = emr("RunJobFlow", "{\"Name\":\"fleet-manual\",\"Instances\":{"
                + "\"KeepJobFlowAliveWhenNoSteps\":true," + FLEETS + "}}")
                .then().statusCode(200).extract().path("JobFlowId");
        emr("ListInstanceFleets", clusterRequest(id)).then().statusCode(200)
                .body("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.Status.State", equalTo("RUNNING"))
                .body("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.ProvisionedOnDemandCapacity", equalTo(2))
                .body("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.ProvisionedSpotCapacity", equalTo(3));

        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + id + "\"]}")
                .then().statusCode(200);
        assertTerminatedFleet(id);
        assertCannotModifyFleet(id);
        emr("AddInstanceFleet", "{\"ClusterId\":\"" + id + "\",\"InstanceFleet\":{"
                + "\"InstanceFleetType\":\"TASK\",\"TargetOnDemandCapacity\":1,"
                + "\"InstanceTypeConfigs\":[{\"InstanceType\":\"m5.xlarge\"}]}}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", equalTo(SHUT_DOWN))
                .body("ErrorCode", equalTo("INVALID_JOB_SHUT_DOWN"));
        assertTerminatedFleet(id);
    }

    @Test
    void implicitGroupModificationIsAtomicAcrossActiveAndTerminatedClusters() {
        String terminatedId = emr("RunJobFlow", "{\"Name\":\"group-atomic-terminated\",\"Instances\":{"
                + GROUPS + "},\"Steps\":[" + STEP + "]}")
                .then().statusCode(200).extract().path("JobFlowId");
        String activeId = emr("RunJobFlow", "{\"Name\":\"group-atomic-active\",\"Instances\":{"
                + "\"KeepJobFlowAliveWhenNoSteps\":true," + GROUPS + "}}")
                .then().statusCode(200).extract().path("JobFlowId");
        String terminatedGroup = coreGroupId(terminatedId);
        String activeGroup = coreGroupId(activeId);

        emr("ModifyInstanceGroups", "{\"InstanceGroups\":["
                + "{\"InstanceGroupId\":\"" + activeGroup + "\",\"InstanceCount\":4},"
                + "{\"InstanceGroupId\":\"" + terminatedGroup + "\",\"InstanceCount\":4}]}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo(GROUP_REFUSAL));
        emr("ListInstanceGroups", clusterRequest(activeId)).then().statusCode(200)
                .body("InstanceGroups.find { it.Id == '" + activeGroup + "' }.RunningInstanceCount", equalTo(2));
        assertTerminatedGroup(terminatedId);
    }

    @Test
    void terminatedInstancesExpireAfterThirtyDays() {
        Instant now = Instant.parse("2026-10-20T00:00:00Z");
        assertTrue(EmrHandler.withinTerminatedInstanceRetention(now.minus(30, ChronoUnit.DAYS), now));
        assertFalse(EmrHandler.withinTerminatedInstanceRetention(
                now.minus(30, ChronoUnit.DAYS).minusNanos(1), now));
        assertFalse(EmrHandler.withinTerminatedInstanceRetention(null, now));
    }

    @Test
    void legacyTerminatedGroupRetainsItsPreviouslyRunningInstances() throws Exception {
        EmrInstanceGroup legacy = new ObjectMapper().readValue(
                "{\"state\":\"RUNNING\",\"runningInstanceCount\":2}", EmrInstanceGroup.class);
        assertEquals(0, legacy.getTerminatedInstanceCount());
        assertEquals(2, EmrHandler.listedInstanceCount(legacy, true));
        legacy.setTerminatedInstanceCount(1);
        assertEquals(1, EmrHandler.listedInstanceCount(legacy, true));
        assertEquals(2, EmrHandler.listedInstanceCount(legacy, false));
    }

    @Test
    void aFleetStoredBeforeItsInstanceTypesWereKeptListsItsInstancesWithoutOne() throws Exception {
        EmrCluster cluster = new EmrCluster();
        cluster.getInstanceFleets().add(new ObjectMapper().readValue(
                "{\"instanceFleetType\":\"CORE\",\"targetSpotCapacity\":2,\"provisionedSpotCapacity\":2}",
                EmrInstanceFleet.class));

        EmrHandler.ListedInstances listed = EmrHandler.listedInstances(cluster, false).getFirst();

        assertEquals(2, listed.count());
        assertNull(listed.instanceType());
        assertEquals("SPOT", listed.marketOf(1));
    }

    private static String clusterRequest(String id) {
        return "{\"ClusterId\":\"" + id + "\"}";
    }

    private static String coreGroupId(String id) {
        return emr("ListInstanceGroups", clusterRequest(id)).then().statusCode(200)
                .extract().jsonPath().getString("InstanceGroups.find { it.InstanceGroupType == 'CORE' }.Id");
    }

    private static void assertCannotModifyGroup(String id, boolean includeClusterId) {
        String clusterId = includeClusterId ? "\"ClusterId\":\"" + id + "\"," : "";
        emr("ModifyInstanceGroups", "{" + clusterId + "\"InstanceGroups\":[{\"InstanceGroupId\":\""
                + coreGroupId(id) + "\",\"InstanceCount\":4}]}")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo(GROUP_REFUSAL));
        assertTerminatedGroup(id);
    }

    private static void assertCannotModifyFleet(String id) {
        String fleetId = emr("ListInstanceFleets", clusterRequest(id)).then().statusCode(200)
                .extract().jsonPath().getString("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.Id");
        emr("ModifyInstanceFleet", "{\"ClusterId\":\"" + id + "\",\"InstanceFleet\":{"
                + "\"InstanceFleetId\":\"" + fleetId + "\",\"TargetOnDemandCapacity\":4}}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", equalTo(SHUT_DOWN))
                .body("ErrorCode", equalTo("INVALID_JOB_SHUT_DOWN"));
        assertTerminatedFleet(id);
    }

    private static void assertTerminatedGroup(String id) {
        emr("DescribeCluster", clusterRequest(id)).then().statusCode(200)
                .body("Cluster.Status.State", equalTo("TERMINATED"));
        emr("ListInstanceGroups", clusterRequest(id)).then().statusCode(200)
                .body("InstanceGroups.find { it.InstanceGroupType == 'MASTER' }.Status.State", equalTo("TERMINATED"))
                .body("InstanceGroups.find { it.InstanceGroupType == 'MASTER' }.RequestedInstanceCount", equalTo(1))
                .body("InstanceGroups.find { it.InstanceGroupType == 'MASTER' }.RunningInstanceCount", equalTo(0))
                .body("InstanceGroups.find { it.InstanceGroupType == 'CORE' }.Status.State", equalTo("TERMINATED"))
                .body("InstanceGroups.find { it.InstanceGroupType == 'CORE' }.RequestedInstanceCount", equalTo(2))
                .body("InstanceGroups.find { it.InstanceGroupType == 'CORE' }.RunningInstanceCount", equalTo(0));
        emr("ListInstances", clusterRequest(id)).then().statusCode(200)
                .body("Instances", hasSize(3))
                .body("Instances.Id", equalTo(List.of("ci-1" + id, "ci-2" + id, "ci-3" + id)))
                .body("Instances[0].Status.State", equalTo("TERMINATED"))
                .body("Instances[1].Status.State", equalTo("TERMINATED"))
                .body("Instances[2].Status.State", equalTo("TERMINATED"));
    }

    private static void assertTerminatedFleet(String id) {
        emr("DescribeCluster", clusterRequest(id)).then().statusCode(200)
                .body("Cluster.Status.State", equalTo("TERMINATED"));
        emr("ListInstanceFleets", clusterRequest(id)).then().statusCode(200)
                .body("InstanceFleets.find { it.InstanceFleetType == 'MASTER' }.Status.State", equalTo("TERMINATED"))
                .body("InstanceFleets.find { it.InstanceFleetType == 'MASTER' }.TargetOnDemandCapacity", equalTo(1))
                .body("InstanceFleets.find { it.InstanceFleetType == 'MASTER' }.ProvisionedOnDemandCapacity", equalTo(0))
                .body("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.Status.State", equalTo("TERMINATED"))
                .body("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.TargetOnDemandCapacity", equalTo(2))
                .body("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.TargetSpotCapacity", equalTo(3))
                .body("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.ProvisionedOnDemandCapacity", equalTo(0))
                .body("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.ProvisionedSpotCapacity", equalTo(0));
        // What the fleets ran, kept as the 30 days of ended instances.
        emr("ListInstances", clusterRequest(id)).then().statusCode(200)
                .body("Instances.Id", equalTo(List.of("ci-1" + id, "ci-2" + id, "ci-3" + id, "ci-4" + id,
                        "ci-5" + id, "ci-6" + id)))
                .body("Instances.Market", equalTo(List.of("ON_DEMAND", "ON_DEMAND", "ON_DEMAND", "SPOT", "SPOT", "SPOT")))
                .body("Instances.Status.State", equalTo(Collections.nCopies(6, "TERMINATED")));
        emr("ListInstances", "{\"ClusterId\":\"" + id + "\",\"InstanceStates\":[\"RUNNING\"]}")
                .then().statusCode(200).body("Instances", hasSize(0));
    }
}
