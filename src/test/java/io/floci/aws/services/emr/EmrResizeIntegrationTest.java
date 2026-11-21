package io.floci.aws.services.emr;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static io.floci.aws.testing.EmrRequests.emr;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EMR instance group and fleet resizing and bootstrap action listing over the AWS JSON 1.1 wire
 * protocol.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmrResizeIntegrationTest {

    private static String groupsClusterId;
    private static String masterGroupId;
    private static String coreGroupId;
    private static String taskGroupId;
    private static String fleetClusterId;
    private static String masterFleetId;
    private static String coreFleetId;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /** ListInstances for one instance group of a cluster. */
    private static Response groupInstances(String clusterId, String groupId) {
        return emr("ListInstances", "{\"ClusterId\":\"" + clusterId + "\",\"InstanceGroupId\":\"" + groupId + "\"}");
    }

    private static String groupId(String type) {
        return emr("ListInstanceGroups", "{\"ClusterId\":\"" + groupsClusterId + "\"}").jsonPath()
                .getString("InstanceGroups.find { it.InstanceGroupType == '" + type + "' }.Id");
    }

    @Test
    @Order(1)
    void runJobFlowStoresBootstrapActions() {
        Response resp = emr("RunJobFlow",
                "{\"Name\":\"resize\",\"ReleaseLabel\":\"emr-7.5.0\","
                        + "\"BootstrapActions\":[{\"Name\":\"install\",\"ScriptBootstrapAction\":"
                        + "{\"Path\":\"s3://bucket/install.sh\",\"Args\":[\"--fast\",\"1\"]}},"
                        + "{\"Name\":\"noargs\",\"ScriptBootstrapAction\":{\"Path\":\"s3://bucket/b.sh\"}}],"
                        + "\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,"
                        + "\"InstanceGroups\":[{\"InstanceRole\":\"MASTER\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1},"
                        + "{\"InstanceRole\":\"CORE\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":2},"
                        + "{\"InstanceRole\":\"TASK\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1,"
                        + "\"Configurations\":[]}]}}");
        resp.then().statusCode(200);
        groupsClusterId = resp.jsonPath().getString("JobFlowId");
        masterGroupId = groupId("MASTER");
        coreGroupId = groupId("CORE");
        taskGroupId = groupId("TASK");

        emr("ListInstanceGroups", "{\"ClusterId\":\"" + groupsClusterId + "\"}").then().statusCode(200)
                .body("InstanceGroups.find { it.Id == '" + taskGroupId + "' }.Configurations", hasSize(0))
                .body("InstanceGroups.find { it.Id == '" + coreGroupId + "' }.Configurations", nullValue());

        emr("ListBootstrapActions", "{\"ClusterId\":\"" + groupsClusterId + "\"}").then().statusCode(200)
                .body("BootstrapActions", hasSize(2))
                .body("BootstrapActions[0].Name", equalTo("install"))
                .body("BootstrapActions[0].ScriptPath", equalTo("s3://bucket/install.sh"))
                .body("BootstrapActions[0].Args", equalTo(List.of("--fast", "1")))
                .body("BootstrapActions[1].Args", hasSize(0));
    }

    @Test
    @Order(2)
    void listBootstrapActionsEmptyAndValidated() {
        Response resp = emr("RunJobFlow", "{\"Name\":\"plain\",\"Instances\":{\"InstanceGroups\":"
                + "[{\"InstanceRole\":\"MASTER\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1}]}}");
        emr("ListBootstrapActions", "{\"ClusterId\":\"" + resp.jsonPath().getString("JobFlowId") + "\"}")
                .then().statusCode(200).body("BootstrapActions", hasSize(0));
        emr("ListBootstrapActions", "{\"ClusterId\":\"j-MISSING\"}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        emr("RunJobFlow", "{\"Name\":\"bad\",\"BootstrapActions\":[{\"Name\":\"x\"}]}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(3)
    void modifyInstanceGroupsResizesAndReconfigures() {
        emr("ModifyInstanceGroups", "{\"ClusterId\":\"" + groupsClusterId + "\",\"InstanceGroups\":["
                + "{\"InstanceGroupId\":\"" + coreGroupId + "\",\"InstanceCount\":4},"
                + "{\"InstanceGroupId\":\"" + taskGroupId + "\",\"InstanceCount\":0,\"Configurations\":"
                + "[{\"Classification\":\"yarn-site\",\"Properties\":{\"yarn.nodemanager.vmem-check-enabled\":\"false\"}}]}]}")
                .then().statusCode(200);
        emr("ListInstanceGroups", "{\"ClusterId\":\"" + groupsClusterId + "\"}").then().statusCode(200)
                .body("InstanceGroups.find { it.Id == '" + coreGroupId + "' }.RequestedInstanceCount", equalTo(4))
                .body("InstanceGroups.find { it.Id == '" + coreGroupId + "' }.RunningInstanceCount", equalTo(4))
                .body("InstanceGroups.find { it.Id == '" + coreGroupId + "' }.Configurations", nullValue())
                .body("InstanceGroups.find { it.Id == '" + taskGroupId + "' }.RunningInstanceCount", equalTo(0))
                .body("InstanceGroups.find { it.Id == '" + taskGroupId + "' }.Configurations[0].Classification",
                        equalTo("yarn-site"))
                .body("InstanceGroups.find { it.Id == '" + taskGroupId + "' }.ConfigurationsVersion", equalTo(1))
                .body("InstanceGroups.find { it.Id == '" + taskGroupId + "' }.LastSuccessfullyAppliedConfigurationsVersion",
                        equalTo(1));
        // 1 master + 4 core + 0 task.
        emr("ListInstances", "{\"ClusterId\":\"" + groupsClusterId + "\"}").then().statusCode(200)
                .body("Instances", hasSize(5));
    }

    @Test
    @Order(4)
    void modifyInstanceGroupsWithoutClusterIdFindsTheGroup() {
        emr("ModifyInstanceGroups", "{\"InstanceGroups\":[{\"InstanceGroupId\":\"" + coreGroupId
                + "\",\"InstanceCount\":3}]}").then().statusCode(200);
        emr("ListInstanceGroups", "{\"ClusterId\":\"" + groupsClusterId + "\"}").then().statusCode(200)
                .body("InstanceGroups.find { it.Id == '" + coreGroupId + "' }.RunningInstanceCount", equalTo(3));
    }

    @Test
    @Order(5)
    void modifyInstanceGroupsIsValidatedBeforeAnyChange() {
        // The second modification is rejected, so the first is not applied either.
        emr("ModifyInstanceGroups", "{\"ClusterId\":\"" + groupsClusterId + "\",\"InstanceGroups\":["
                + "{\"InstanceGroupId\":\"" + coreGroupId + "\",\"InstanceCount\":9},"
                + "{\"InstanceGroupId\":\"" + masterGroupId + "\",\"InstanceCount\":3}]}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        emr("ListInstanceGroups", "{\"ClusterId\":\"" + groupsClusterId + "\"}").then().statusCode(200)
                .body("InstanceGroups.find { it.Id == '" + coreGroupId + "' }.RunningInstanceCount", equalTo(3));

        emr("ModifyInstanceGroups", "{\"ClusterId\":\"" + groupsClusterId + "\",\"InstanceGroups\":["
                + "{\"InstanceGroupId\":\"" + coreGroupId + "\",\"InstanceCount\":-1}]}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        emr("ModifyInstanceGroups", "{\"InstanceGroups\":[{\"InstanceGroupId\":\"ig-MISSING\",\"InstanceCount\":1}]}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        emr("ModifyInstanceGroups", "{\"ClusterId\":\"" + groupsClusterId + "\",\"InstanceGroups\":[]}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(6)
    void modifyInstanceFleetResizes() {
        Response resp = emr("RunJobFlow", "{\"Name\":\"fleets\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,"
                + "\"InstanceFleets\":[{\"InstanceFleetType\":\"MASTER\",\"TargetOnDemandCapacity\":1,"
                + "\"InstanceTypeConfigs\":[{\"InstanceType\":\"m5.xlarge\"}]},"
                + "{\"InstanceFleetType\":\"CORE\",\"TargetOnDemandCapacity\":2,\"TargetSpotCapacity\":2,"
                + "\"InstanceTypeConfigs\":[{\"InstanceType\":\"m5.xlarge\"}]}]}}");
        resp.then().statusCode(200);
        fleetClusterId = resp.jsonPath().getString("JobFlowId");
        Response fleets = emr("ListInstanceFleets", "{\"ClusterId\":\"" + fleetClusterId + "\"}");
        masterFleetId = fleets.jsonPath().getString("InstanceFleets.find { it.InstanceFleetType == 'MASTER' }.Id");
        coreFleetId = fleets.jsonPath().getString("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.Id");

        emr("ModifyInstanceFleet", "{\"ClusterId\":\"" + fleetClusterId + "\",\"InstanceFleet\":"
                + "{\"InstanceFleetId\":\"" + coreFleetId + "\",\"TargetSpotCapacity\":6}}")
                .then().statusCode(200);
        emr("ListInstanceFleets", "{\"ClusterId\":\"" + fleetClusterId + "\"}").then().statusCode(200)
                .body("InstanceFleets.find { it.Id == '" + coreFleetId + "' }.TargetOnDemandCapacity", equalTo(2))
                .body("InstanceFleets.find { it.Id == '" + coreFleetId + "' }.TargetSpotCapacity", equalTo(6))
                .body("InstanceFleets.find { it.Id == '" + coreFleetId + "' }.ProvisionedSpotCapacity", equalTo(6));
    }

    @Test
    @Order(7)
    void modifyInstanceFleetIsValidated() {
        emr("ModifyInstanceFleet", "{\"ClusterId\":\"" + fleetClusterId + "\",\"InstanceFleet\":"
                + "{\"InstanceFleetId\":\"" + masterFleetId + "\",\"TargetOnDemandCapacity\":3}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        emr("ModifyInstanceFleet", "{\"ClusterId\":\"" + fleetClusterId + "\",\"InstanceFleet\":"
                + "{\"InstanceFleetId\":\"" + coreFleetId + "\",\"TargetOnDemandCapacity\":-1}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        emr("ModifyInstanceFleet", "{\"ClusterId\":\"" + fleetClusterId + "\",\"InstanceFleet\":"
                + "{\"InstanceFleetId\":\"if-MISSING\",\"TargetOnDemandCapacity\":1}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        emr("ModifyInstanceFleet", "{\"ClusterId\":\"j-MISSING\",\"InstanceFleet\":"
                + "{\"InstanceFleetId\":\"" + coreFleetId + "\",\"TargetOnDemandCapacity\":1}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(8)
    void thePrimaryNodeKeepsItsIdAndAddressWhileAGroupListedBeforeItResizes() {
        String id = emr("RunJobFlow", "{\"Name\":\"core-first\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,"
                + "\"InstanceGroups\":[{\"InstanceRole\":\"CORE\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":2},"
                + "{\"InstanceRole\":\"MASTER\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1}]}}")
                .then().statusCode(200).extract().path("JobFlowId");
        String master = emr("ListInstanceGroups", "{\"ClusterId\":\"" + id + "\"}").jsonPath()
                .getString("InstanceGroups.find { it.InstanceGroupType == 'MASTER' }.Id");
        String core = emr("ListInstanceGroups", "{\"ClusterId\":\"" + id + "\"}").jsonPath()
                .getString("InstanceGroups.find { it.InstanceGroupType == 'CORE' }.Id");
        String primary = "Instances.find { it.InstanceGroupId == '" + master + "' }";

        // Listed after the core group's two, numbered first, as DescribeCluster's MasterPublicDnsName names it.
        emr("ListInstances", "{\"ClusterId\":\"" + id + "\"}").then().statusCode(200)
                .body("Instances[2].InstanceGroupId", equalTo(master))
                .body(primary + ".Ec2InstanceId", equalTo("i-00000000000000001"))
                .body(primary + ".PrivateIpAddress", equalTo("10.0.0.1"));
        emr("ModifyInstanceGroups", "{\"ClusterId\":\"" + id + "\",\"InstanceGroups\":["
                + "{\"InstanceGroupId\":\"" + core + "\",\"InstanceCount\":3}]}").then().statusCode(200);

        emr("ListInstances", "{\"ClusterId\":\"" + id + "\"}").then().statusCode(200)
                .body("Instances", hasSize(4))
                .body(primary + ".Ec2InstanceId", equalTo("i-00000000000000001"))
                .body(primary + ".PrivateDnsName", equalTo("ip-10-0-0-1.ec2.internal"))
                .body("Instances.findAll { it.InstanceGroupId == '" + core + "' }.PrivateIpAddress",
                        equalTo(List.of("10.1.0.1", "10.1.0.2", "10.1.0.3")));
        emr("DescribeCluster", "{\"ClusterId\":\"" + id + "\"}").then().statusCode(200)
                .body("Cluster.MasterPublicDnsName", equalTo("ip-10-0-0-1.ec2.internal"));
        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + id + "\"]}").then().statusCode(200);
    }

    @Test
    @Order(9)
    void aSecondMasterGroupsInstanceIsNumberedAfterThePrimaryNodeAsAnyOtherGroupsIs() {
        String id = emr("RunJobFlow", "{\"Name\":\"two-masters\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,"
                + "\"InstanceGroups\":[{\"InstanceRole\":\"CORE\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1},"
                + "{\"InstanceRole\":\"MASTER\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1},"
                + "{\"InstanceRole\":\"MASTER\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1}]}}")
                .then().statusCode(200).extract().path("JobFlowId");

        emr("ListInstances", "{\"ClusterId\":\"" + id + "\"}").then().statusCode(200)
                .body("Instances.Ec2InstanceId", equalTo(List.of("i-00000000000000002", "i-00000000000000001",
                        "i-00000000000000003")))
                .body("Instances.PrivateIpAddress", equalTo(List.of("10.1.0.1", "10.0.0.1", "10.2.0.1")));
        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + id + "\"]}").then().statusCode(200);
    }

    @Test
    @Order(10)
    void anInstanceKeepsItsAddressWhileAGroupListedBeforeItResizes() {
        String id = emr("RunJobFlow", "{\"Name\":\"stable-addresses\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,"
                + "\"InstanceGroups\":[{\"InstanceRole\":\"MASTER\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1},"
                + "{\"InstanceRole\":\"CORE\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1},"
                + "{\"InstanceRole\":\"TASK\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":2}]}}")
                .then().statusCode(200).extract().path("JobFlowId");
        String core = emr("ListInstanceGroups", "{\"ClusterId\":\"" + id + "\"}").jsonPath()
                .getString("InstanceGroups.find { it.InstanceGroupType == 'CORE' }.Id");
        String task = emr("ListInstanceGroups", "{\"ClusterId\":\"" + id + "\"}").jsonPath()
                .getString("InstanceGroups.find { it.InstanceGroupType == 'TASK' }.Id");
        emr("ModifyInstanceGroups", "{\"ClusterId\":\"" + id + "\",\"InstanceGroups\":["
                + "{\"InstanceGroupId\":\"" + core + "\",\"InstanceCount\":2}]}").then().statusCode(200);

        emr("ListInstances", "{\"ClusterId\":\"" + id + "\"}").then().statusCode(200)
                .body("Instances.findAll { it.InstanceGroupId == '" + task + "' }.PrivateIpAddress",
                        equalTo(List.of("10.2.0.1", "10.2.0.2")))
                .body("Instances.findAll { it.InstanceGroupId == '" + task + "' }.PrivateDnsName",
                        equalTo(List.of("ip-10-2-0-1.ec2.internal", "ip-10-2-0-2.ec2.internal")));
        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + id + "\"]}").then().statusCode(200);
    }

    @Test
    @Order(11)
    void aFleetsInstancesAreListedOnePerUnitOnDemandFirstAndKeepTheirAddresses() {
        String id = emr("RunJobFlow", "{\"Name\":\"fleet-instances\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,"
                + "\"InstanceFleets\":[{\"InstanceFleetType\":\"CORE\",\"TargetOnDemandCapacity\":1,"
                + "\"TargetSpotCapacity\":2,\"InstanceTypeConfigs\":[{\"InstanceType\":\"r5.2xlarge\"},"
                + "{\"InstanceType\":\"m5.2xlarge\"}]},"
                + "{\"InstanceFleetType\":\"MASTER\",\"TargetOnDemandCapacity\":1,"
                + "\"InstanceTypeConfigs\":[{\"InstanceType\":\"m5.xlarge\"}]}]}}")
                .then().statusCode(200).extract().path("JobFlowId");
        Response fleets = emr("ListInstanceFleets", "{\"ClusterId\":\"" + id + "\"}");
        String master = fleets.jsonPath().getString("InstanceFleets.find { it.InstanceFleetType == 'MASTER' }.Id");
        String core = fleets.jsonPath().getString("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.Id");
        String task = emr("AddInstanceFleet", "{\"ClusterId\":\"" + id + "\",\"InstanceFleet\":"
                + "{\"InstanceFleetType\":\"TASK\",\"TargetSpotCapacity\":1,"
                + "\"InstanceTypeConfigs\":[{\"InstanceType\":\"c5.xlarge\"}]}}")
                .then().statusCode(200).extract().path("InstanceFleetId");
        String primary = "Instances.find { it.InstanceFleetId == '" + master + "' }";
        String coreInstances = "Instances.findAll { it.InstanceFleetId == '" + core + "' }";
        String taskInstances = "Instances.findAll { it.InstanceFleetId == '" + task + "' }";

        // The primary node is numbered first, as DescribeCluster's MasterPublicDnsName names it, and each node as the
        // cluster's containers are named.
        emr("ListInstances", "{\"ClusterId\":\"" + id + "\"}").then().statusCode(200)
                .body("Instances", hasSize(5))
                .body("Instances.findAll { it.containsKey('InstanceGroupId') }", hasSize(0))
                .body(primary + ".Ec2InstanceId", equalTo("i-00000000000000001"))
                .body(primary + ".PrivateDnsName", equalTo("ip-10-0-0-1.ec2.internal"))
                .body(primary + ".Market", equalTo("ON_DEMAND"))
                .body(primary + ".InstanceType", equalTo("m5.xlarge"))
                .body(coreInstances + ".PrivateIpAddress", equalTo(List.of("10.1.0.1", "10.1.0.2", "10.1.0.3")))
                .body(coreInstances + ".Market", equalTo(List.of("ON_DEMAND", "SPOT", "SPOT")))
                .body(coreInstances + ".InstanceType", equalTo(List.of("r5.2xlarge", "r5.2xlarge", "r5.2xlarge")))
                .body(taskInstances + ".PrivateDnsName", equalTo(List.of("ip-10-2-0-1.ec2.internal")))
                .body(taskInstances + ".Market", equalTo(List.of("SPOT")))
                .body(taskInstances + ".InstanceType", equalTo(List.of("c5.xlarge")));
        emr("DescribeCluster", "{\"ClusterId\":\"" + id + "\"}").then().statusCode(200)
                .body("Cluster.MasterPublicDnsName", equalTo("ip-10-0-0-1.ec2.internal"));

        emr("ModifyInstanceFleet", "{\"ClusterId\":\"" + id + "\",\"InstanceFleet\":"
                + "{\"InstanceFleetId\":\"" + core + "\",\"TargetSpotCapacity\":0}}").then().statusCode(200);
        emr("ListInstances", "{\"ClusterId\":\"" + id + "\"}").then().statusCode(200)
                .body("Instances", hasSize(3))
                .body(primary + ".Ec2InstanceId", equalTo("i-00000000000000001"))
                .body(coreInstances + ".PrivateIpAddress", equalTo(List.of("10.1.0.1")))
                .body(taskInstances + ".PrivateIpAddress", equalTo(List.of("10.2.0.1")));
        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + id + "\"]}").then().statusCode(200);
    }

    @Test
    @Order(12)
    void listInstancesKeepsTheGroupsOrFleetsItsFiltersName() {
        String fleetsId = emr("RunJobFlow", "{\"Name\":\"fleet-filters\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,"
                + "\"InstanceFleets\":[{\"InstanceFleetType\":\"MASTER\",\"TargetOnDemandCapacity\":1,"
                + "\"InstanceTypeConfigs\":[{\"InstanceType\":\"m5.xlarge\"}]},"
                + "{\"InstanceFleetType\":\"CORE\",\"TargetOnDemandCapacity\":2,"
                + "\"InstanceTypeConfigs\":[{\"InstanceType\":\"m5.xlarge\"}]}]}}")
                .then().statusCode(200).extract().path("JobFlowId");
        String core = emr("ListInstanceFleets", "{\"ClusterId\":\"" + fleetsId + "\"}").jsonPath()
                .getString("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.Id");
        String fleets = "{\"ClusterId\":\"" + fleetsId + "\",";

        emr("ListInstances", fleets + "\"InstanceFleetType\":\"CORE\"}").then().statusCode(200)
                .body("Instances.Ec2InstanceId", equalTo(List.of("i-00000000000000002", "i-00000000000000003")));
        emr("ListInstances", fleets + "\"InstanceFleetType\":\"MASTER\"}").then().statusCode(200)
                .body("Instances.PrivateIpAddress", equalTo(List.of("10.0.0.1")));
        emr("ListInstances", fleets + "\"InstanceFleetId\":\"" + core + "\"}").then().statusCode(200)
                .body("Instances", hasSize(2));
        emr("ListInstances", fleets + "\"InstanceFleetId\":\"" + core + "\",\"InstanceFleetType\":\"MASTER\"}")
                .then().statusCode(200).body("Instances", hasSize(0));
        emr("ListInstances", fleets + "\"InstanceGroupTypes\":[\"MASTER\"]}").then().statusCode(200)
                .body("Instances", hasSize(0));

        String groupsId = emr("RunJobFlow", "{\"Name\":\"group-filters\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,"
                + "\"InstanceGroups\":[{\"InstanceRole\":\"MASTER\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1},"
                + "{\"InstanceRole\":\"CORE\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":2},"
                + "{\"InstanceRole\":\"TASK\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1}]}}")
                .then().statusCode(200).extract().path("JobFlowId");
        String task = emr("ListInstanceGroups", "{\"ClusterId\":\"" + groupsId + "\"}").jsonPath()
                .getString("InstanceGroups.find { it.InstanceGroupType == 'TASK' }.Id");
        String groups = "{\"ClusterId\":\"" + groupsId + "\",";

        emr("ListInstances", groups + "\"InstanceGroupTypes\":[\"MASTER\"]}").then().statusCode(200)
                .body("Instances.Ec2InstanceId", equalTo(List.of("i-00000000000000001")));
        emr("ListInstances", groups + "\"InstanceGroupTypes\":[\"CORE\",\"TASK\"]}").then().statusCode(200)
                .body("Instances.PrivateIpAddress", equalTo(List.of("10.1.0.1", "10.1.0.2", "10.2.0.1")));
        emr("ListInstances", groups + "\"InstanceGroupId\":\"" + task + "\"}").then().statusCode(200)
                .body("Instances.Ec2InstanceId", equalTo(List.of("i-00000000000000004")));
        emr("ListInstances", groups + "\"InstanceGroupId\":\"" + task + "\",\"InstanceGroupTypes\":[\"CORE\"]}")
                .then().statusCode(200).body("Instances", hasSize(0));
        emr("ListInstances", groups + "\"InstanceFleetType\":\"MASTER\"}").then().statusCode(200)
                .body("Instances", hasSize(0));
        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + fleetsId + "\",\"" + groupsId + "\"]}").then().statusCode(200);
    }

    @Test
    @Order(13)
    void aListingGivesAtMostTwoThousandInstancesAndAMarkerForTheRest() {
        // "up to a maximum of 2,000", a fleet of any capacity costing a page.
        String id = emr("RunJobFlow", "{\"Name\":\"many-instances\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,"
                + "\"InstanceFleets\":[{\"InstanceFleetType\":\"MASTER\",\"TargetOnDemandCapacity\":1,"
                + "\"InstanceTypeConfigs\":[{\"InstanceType\":\"m5.xlarge\"}]},"
                + "{\"InstanceFleetType\":\"CORE\",\"TargetOnDemandCapacity\":2500,"
                + "\"InstanceTypeConfigs\":[{\"InstanceType\":\"m5.xlarge\"}]}]}}")
                .then().statusCode(200).extract().path("JobFlowId");

        emr("ListInstances", "{\"ClusterId\":\"" + id + "\"}").then().statusCode(200)
                .body("Instances", hasSize(2000))
                .body("Instances[0].Ec2InstanceId", equalTo("i-00000000000000001"))
                .body("Marker", equalTo("2000"));
        emr("ListInstances", "{\"ClusterId\":\"" + id + "\",\"Marker\":\"2000\"}").then().statusCode(200)
                .body("Instances", hasSize(501))
                .body("Instances[0].Ec2InstanceId", equalTo("i-00000000000002001"))
                .body("Instances[500].PrivateIpAddress", equalTo("10.1.9.214"))
                .body("Marker", nullValue());
        // The pages count what the filters keep.
        emr("ListInstances", "{\"ClusterId\":\"" + id + "\",\"InstanceFleetType\":\"CORE\",\"Marker\":\"2000\"}")
                .then().statusCode(200).body("Instances", hasSize(500));
        emr("ListInstances", "{\"ClusterId\":\"" + id + "\",\"Marker\":\"next\"}").then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", equalTo("Invalid pagination token: next"));

        // A fleet's two capacities add up past an int, and its last page is as cheap as its first.
        String widest = emr("RunJobFlow", "{\"Name\":\"widest\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,"
                + "\"InstanceFleets\":[{\"InstanceFleetType\":\"MASTER\",\"TargetOnDemandCapacity\":1,"
                + "\"InstanceTypeConfigs\":[{\"InstanceType\":\"m5.xlarge\"}]},"
                + "{\"InstanceFleetType\":\"CORE\",\"TargetOnDemandCapacity\":2147483647,"
                + "\"TargetSpotCapacity\":2147483647,\"InstanceTypeConfigs\":[{\"InstanceType\":\"m5.xlarge\"}]}]}}")
                .then().statusCode(200).extract().path("JobFlowId");
        emr("ListInstances", "{\"ClusterId\":\"" + widest + "\"}").then().statusCode(200)
                .body("Instances", hasSize(2000)).body("Marker", equalTo("2000"));
        emr("ListInstances", "{\"ClusterId\":\"" + widest + "\",\"Marker\":\"4294967293\"}").then().statusCode(200)
                .body("Instances.Ec2InstanceId", equalTo(List.of("i-00000004294967294", "i-00000004294967295")))
                .body("Instances.Market", equalTo(List.of("SPOT", "SPOT")))
                .body("Marker", nullValue());
        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + id + "\",\"" + widest + "\"]}").then().statusCode(200);
    }

    @Test
    @Order(14)
    void aClustersInstanceGroupsAndFleetsNeverMeet() {
        // "instance fleets and instance groups cannot coexist in a cluster".
        emr("RunJobFlow", "{\"Name\":\"both\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,"
                + "\"InstanceGroups\":[{\"InstanceRole\":\"MASTER\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1}],"
                + "\"InstanceFleets\":[{\"InstanceFleetType\":\"CORE\",\"TargetOnDemandCapacity\":1,"
                + "\"InstanceTypeConfigs\":[{\"InstanceType\":\"m5.xlarge\"}]}]}}").then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("The RunJobFlow request can contain InstanceFleets parameters or"
                        + " InstanceGroups parameters, but not both."));

        String fleetsId = emr("RunJobFlow", "{\"Name\":\"fleets\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,"
                + "\"InstanceFleets\":[{\"InstanceFleetType\":\"MASTER\",\"TargetOnDemandCapacity\":1,"
                + "\"InstanceTypeConfigs\":[{\"InstanceType\":\"m5.xlarge\"}]}]}}")
                .then().statusCode(200).extract().path("JobFlowId");
        emr("AddInstanceGroups", "{\"JobFlowId\":\"" + fleetsId + "\",\"InstanceGroups\":[{\"InstanceRole\":\"TASK\","
                + "\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1}]}").then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("Cluster " + fleetsId + " uses instance fleets, and instance fleets and"
                        + " instance groups cannot coexist in a cluster."));
        String groupsId = emr("RunJobFlow", "{\"Name\":\"groups\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,"
                + "\"InstanceGroups\":[{\"InstanceRole\":\"MASTER\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1}]}}")
                .then().statusCode(200).extract().path("JobFlowId");
        emr("AddInstanceFleet", "{\"ClusterId\":\"" + groupsId + "\",\"InstanceFleet\":{\"InstanceFleetType\":\"TASK\","
                + "\"TargetSpotCapacity\":1,\"InstanceTypeConfigs\":[{\"InstanceType\":\"m5.xlarge\"}]}}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"))
                .body("message", equalTo("Cluster " + groupsId + " uses instance groups, and instance fleets and"
                        + " instance groups cannot coexist in a cluster."));
        emr("ListInstanceFleets", "{\"ClusterId\":\"" + groupsId + "\"}").then().statusCode(200)
                .body("InstanceFleets", hasSize(0));
        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + fleetsId + "\",\"" + groupsId + "\"]}").then().statusCode(200);
    }

    @Test
    @Order(15)
    void aFleetWithoutItsTypeOrAnInstanceTypeIsRefused() {
        emr("RunJobFlow", "{\"Name\":\"typeless\",\"Instances\":{\"InstanceFleets\":["
                + "{\"TargetOnDemandCapacity\":1,\"InstanceTypeConfigs\":[{\"InstanceType\":\"m5.xlarge\"},"
                + "{\"WeightedCapacity\":1}]}]}}").then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("2 validation errors detected: Value null at"
                        + " 'instances.instanceFleets.1.member.instanceFleetType' failed to satisfy constraint: Member"
                        + " must not be null; Value null at"
                        + " 'instances.instanceFleets.1.member.instanceTypeConfigs.2.member.instanceType' failed to"
                        + " satisfy constraint: Member must not be null"));
        String id = emr("RunJobFlow", "{\"Name\":\"typed\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,"
                + "\"InstanceFleets\":[{\"InstanceFleetType\":\"MASTER\",\"TargetOnDemandCapacity\":1,"
                + "\"InstanceTypeConfigs\":[{\"InstanceType\":\"m5.xlarge\"}]}]}}")
                .then().statusCode(200).extract().path("JobFlowId");

        emr("AddInstanceFleet", "{\"ClusterId\":\"" + id + "\",\"InstanceFleet\":{\"InstanceFleetType\":\"TASK\","
                + "\"TargetSpotCapacity\":1,\"InstanceTypeConfigs\":[{\"BidPrice\":\"0.1\"}]}}").then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("1 validation error detected: Value null at"
                        + " 'instanceFleet.instanceTypeConfigs.1.member.instanceType' failed to satisfy constraint:"
                        + " Member must not be null"));
        emr("ListInstanceFleets", "{\"ClusterId\":\"" + id + "\"}").then().statusCode(200)
                .body("InstanceFleets", hasSize(1));
        emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + id + "\"]}").then().statusCode(200);
    }

    // A cluster on releaseLabel whose task group starts with these configurations: the cluster's id, then the group's.
    private static List<String> clusterWithTaskConfigurations(String releaseLabel, String configurations) {
        String id = emr("RunJobFlow", "{\"Name\":\"reconfigure\",\"ReleaseLabel\":\"" + releaseLabel + "\","
                + "\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,\"InstanceGroups\":["
                + "{\"InstanceRole\":\"MASTER\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1},"
                + "{\"InstanceRole\":\"TASK\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1,"
                + "\"Configurations\":" + configurations + "}]}}")
                .then().statusCode(200).extract().path("JobFlowId");
        return List.of(id, emr("ListInstanceGroups", "{\"ClusterId\":\"" + id + "\"}").jsonPath()
                .getString("InstanceGroups.find { it.InstanceGroupType == 'TASK' }.Id"));
    }

    /**
     * MERGE "only adds or modifies the new configurations that you submit. It preserves existing configurations",
     * where OVERWRITE, the default, replaces them.
     */
    @Test
    void aMergeReconfigurationKeepsTheGroupsOtherConfigurationsAndOverwriteReplacesThem() {
        List<String> ids = clusterWithTaskConfigurations("emr-7.5.0", "[{\"Classification\":\"yarn-site\","
                + "\"Properties\":{\"a\":\"1\",\"b\":\"2\"}},{\"Classification\":\"core-site\","
                + "\"Properties\":{\"x\":\"1\"}}]");
        String listGroups = "{\"ClusterId\":\"" + ids.get(0) + "\"}";
        String task = "InstanceGroups.find { it.Id == '" + ids.get(1) + "' }";

        emr("ModifyInstanceGroups", "{\"InstanceGroups\":[{\"InstanceGroupId\":\"" + ids.get(1) + "\","
                + "\"ReconfigurationType\":\"MERGE\",\"Configurations\":[{\"Classification\":\"yarn-site\","
                + "\"Properties\":{\"b\":\"3\",\"c\":\"4\"}},{\"Classification\":\"hive-site\","
                + "\"Properties\":{\"h\":\"1\"}}]}]}").then().statusCode(200);
        emr("ListInstanceGroups", listGroups).then().statusCode(200)
                .body(task + ".Configurations.Classification", equalTo(List.of("yarn-site", "core-site", "hive-site")))
                // GPath answers null for a key named Properties read as ".Properties".
                .body(task + ".Configurations[0].get('Properties')", equalTo(Map.of("a", "1", "b", "3", "c", "4")))
                .body(task + ".Configurations[1].get('Properties').x", equalTo("1"))
                .body(task + ".ConfigurationsVersion", equalTo(1));

        emr("ModifyInstanceGroups", "{\"InstanceGroups\":[{\"InstanceGroupId\":\"" + ids.get(1) + "\","
                + "\"Configurations\":[{\"Classification\":\"spark\",\"Properties\":{\"s\":\"1\"}}]}]}")
                .then().statusCode(200);
        emr("ListInstanceGroups", listGroups).then().statusCode(200)
                .body(task + ".Configurations.Classification", equalTo(List.of("spark")))
                .body(task + ".ConfigurationsVersion", equalTo(2));
    }

    @Test
    void aMergeBeforeItsReleasesOrAnUnknownTypeIsRefused() {
        String early = clusterWithTaskConfigurations("emr-6.5.0", "[]").get(1);
        String current = clusterWithTaskConfigurations("emr-7.5.0", "[]").get(1);
        String change = "\"Configurations\":[{\"Classification\":\"spark\",\"Properties\":{}}]";

        emr("ModifyInstanceGroups", "{\"InstanceGroups\":[{\"InstanceGroupId\":\"" + early + "\","
                + "\"ReconfigurationType\":\"MERGE\"," + change + "}]}").then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("5.35.0 and 6.6.0"));
        emr("ModifyInstanceGroups", "{\"InstanceGroups\":[{\"InstanceGroupId\":\"" + current + "\","
                + "\"ReconfigurationType\":\"REPLACE\"," + change + "}]}").then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("'instanceGroups.1.member.reconfigurationType'"));
    }

    /**
     * EC2InstanceIdsToTerminate ends the named instance: the group's others, and the next group's, keep their ids and
     * addresses, the group's counts go down, and an instance it gets later takes a new id.
     */
    @Test
    void aGroupsVolumesAmiAndShrinkPolicyAreKeptAndEachInstanceListsItsVolumesAndPublicAddress() {
        String id = emr("RunJobFlow", """
                {"Name": "volumes", "Instances": {"KeepJobFlowAliveWhenNoSteps": true, "InstanceGroups": [
                  {"InstanceRole": "MASTER", "InstanceType": "m5.xlarge", "InstanceCount": 1},
                  {"InstanceRole": "CORE", "InstanceType": "m5.xlarge", "InstanceCount": 3,
                   "CustomAmiId": "ami-0123456789abcdef0",
                   "EbsConfiguration": {"EbsOptimized": true, "EbsBlockDeviceConfigs": [{"VolumesPerInstance": 2,
                     "VolumeSpecification": {"VolumeType": "gp3", "SizeInGB": 100, "Iops": 3000,
                       "Throughput": 125}}]}}]}}
                """).then().statusCode(200).extract().path("JobFlowId");
        try {
            String cluster = "{\"ClusterId\":\"" + id + "\"}";
            String core = "InstanceGroups.find { it.InstanceGroupType == 'CORE' }";
            String coreId = emr("ListInstanceGroups", cluster).then().statusCode(200)
                    .body(core + ".EbsOptimized", equalTo(true))
                    .body(core + ".CustomAmiId", equalTo("ami-0123456789abcdef0"))
                    .body(core + ".EbsBlockDevices.Device", equalTo(List.of("/dev/sdb", "/dev/sdc")))
                    .body(core + ".EbsBlockDevices[0].VolumeSpecification.VolumeType", equalTo("gp3"))
                    .body(core + ".EbsBlockDevices[1].VolumeSpecification.SizeInGB", equalTo(100))
                    .extract().path(core + ".Id");
            Response instances = groupInstances(id, coreId);
            List<String> coreIds = instances.jsonPath().getList("Instances.Ec2InstanceId");
            assertEquals(List.of("/dev/sdb", "/dev/sdc"),
                    instances.jsonPath().getList("Instances[0].EbsVolumes.Device"));
            List<String> volumeIds = instances.jsonPath().getList("Instances.EbsVolumes.flatten().VolumeId");
            assertEquals(6, new HashSet<>(volumeIds).size(), "each of the 3 instances' 2 volumes has its own id");
            String publicIp = instances.jsonPath().getString("Instances[0].PublicIpAddress");
            assertTrue(instances.jsonPath().getString("Instances[0].PublicDnsName")
                    .startsWith("ec2-" + publicIp.replace('.', '-')), publicIp);

            // Shrinking to 2 ends the instance named to terminate, not the last one, which is protected.
            emr("ModifyInstanceGroups", """
                    {"ClusterId": "%s", "InstanceGroups": [{"InstanceGroupId": "%s", "InstanceCount": 2,
                      "ShrinkPolicy": {"DecommissionTimeout": 60, "InstanceResizePolicy": {
                        "InstancesToTerminate": ["%s"], "InstancesToProtect": ["%s"]}}}]}
                    """.formatted(id, coreId, coreIds.get(0), coreIds.get(2))).then().statusCode(200);
            assertEquals(List.of(coreIds.get(1), coreIds.get(2)),
                    groupInstances(id, coreId).jsonPath().getList("Instances.Ec2InstanceId"));
            emr("ListInstanceGroups", cluster).then().statusCode(200)
                    .body(core + ".RequestedInstanceCount", equalTo(2))
                    .body(core + ".ShrinkPolicy.DecommissionTimeout", equalTo(60))
                    .body(core + ".ShrinkPolicy.InstanceResizePolicy.InstancesToProtect[0]", equalTo(coreIds.get(2)));

            // Without the protection, the protected instance would have been the one to go: the group's last.
            emr("ModifyInstanceGroups", """
                    {"ClusterId": "%s", "InstanceGroups": [{"InstanceGroupId": "%s", "InstanceCount": 1,
                      "ShrinkPolicy": {"InstanceResizePolicy": {"InstancesToProtect": ["%s"]}}}]}
                    """.formatted(id, coreId, coreIds.get(2))).then().statusCode(200);
            assertEquals(List.of(coreIds.get(2)),
                    groupInstances(id, coreId).jsonPath().getList("Instances.Ec2InstanceId"));

            emr("AddInstanceGroups", """
                    {"JobFlowId": "%s", "InstanceGroups": [{"InstanceRole": "TASK", "InstanceType": "m5.xlarge",
                      "InstanceCount": 1, "EbsConfiguration": {"EbsBlockDeviceConfigs": [{"VolumesPerInstance": 26,
                        "VolumeSpecification": {"VolumeType": "gp3", "SizeInGB": 10}}]}}]}
                    """.formatted(id)).then().statusCode(400)
                    .body("__type", equalTo("ValidationException"))
                    .body("message", equalTo("There is a limit of 25 volumes per instance."));
        } finally {
            emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + id + "\"]}").then().statusCode(200);
        }
    }

    @Test
    void aShrinkThatNamesAnUnknownInstanceOrProtectsTooManyIsRefusedAndTwoEntriesShrinkAGroupOnce() {
        String id = emr("RunJobFlow", """
                {"Name": "shrinks", "Instances": {"KeepJobFlowAliveWhenNoSteps": true, "InstanceGroups": [
                  {"InstanceRole": "MASTER", "InstanceType": "m5.xlarge", "InstanceCount": 1},
                  {"InstanceRole": "CORE", "InstanceType": "m5.xlarge", "InstanceCount": 4}]}}
                """).then().statusCode(200).extract().path("JobFlowId");
        try {
            String coreId = emr("ListInstanceGroups", "{\"ClusterId\":\"" + id + "\"}").jsonPath()
                    .getString("InstanceGroups.find { it.InstanceGroupType == 'CORE' }.Id");
            List<String> ids = groupInstances(id, coreId).jsonPath().getList("Instances.Ec2InstanceId");

            emr("ModifyInstanceGroups", """
                    {"ClusterId": "%s", "InstanceGroups": [{"InstanceGroupId": "%s", "InstanceCount": 3,
                      "ShrinkPolicy": {"InstanceResizePolicy": {"InstancesToTerminate": ["i-99999999999999999"]}}}]}
                    """.formatted(id, coreId)).then().statusCode(400)
                    .body("__type", equalTo("ValidationException"))
                    .body("message", equalTo("Instance i-99999999999999999 is not a running instance of instance "
                            + "group " + coreId + "."));
            emr("ModifyInstanceGroups", """
                    {"ClusterId": "%s", "InstanceGroups": [{"InstanceGroupId": "%s", "InstanceCount": 3,
                      "ShrinkPolicy": {"InstanceResizePolicy": {"InstancesToProtect": ["%s", "%s", "%s", "%s"]}}}]}
                    """.formatted(id, coreId, ids.get(0), ids.get(1), ids.get(2), ids.get(3))).then().statusCode(400)
                    .body("__type", equalTo("ValidationException"))
                    .body("message", equalTo("Instance group " + coreId + " cannot shrink to 3 instances without "
                            + "ending one its shrink policy protects."));
            assertEquals(ids, groupInstances(id, coreId).jsonPath().getList("Instances.Ec2InstanceId"));

            // The second entry counts from what the first leaves: the group is already down to 3.
            emr("ModifyInstanceGroups", """
                    {"ClusterId": "%s", "InstanceGroups": [
                      {"InstanceGroupId": "%s", "InstanceCount": 3,
                       "ShrinkPolicy": {"InstanceResizePolicy": {"InstancesToTerminate": ["%s"]}}},
                      {"InstanceGroupId": "%s", "InstanceCount": 3,
                       "ShrinkPolicy": {"InstanceResizePolicy": {"InstancesToTerminate": ["%s"]}}}]}
                    """.formatted(id, coreId, ids.get(0), coreId, ids.get(1))).then().statusCode(200);
            assertEquals(ids.subList(1, 4), groupInstances(id, coreId).jsonPath().getList("Instances.Ec2InstanceId"));
            emr("ListInstanceGroups", "{\"ClusterId\":\"" + id + "\"}").then().statusCode(200)
                    .body("InstanceGroups.find { it.Id == '" + coreId + "' }.RunningInstanceCount", equalTo(3));
        } finally {
            emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + id + "\"]}").then().statusCode(200);
        }
    }

    @Test
    void terminatingANamedInstanceLeavesTheOthersTheirIdsAndAddresses() {
        String id = emr("RunJobFlow", "{\"Name\":\"named\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,"
                + "\"InstanceGroups\":[{\"InstanceRole\":\"MASTER\",\"InstanceType\":\"m5.xlarge\","
                + "\"InstanceCount\":1},{\"InstanceRole\":\"CORE\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":3},"
                + "{\"InstanceRole\":\"TASK\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1}]}}")
                .then().statusCode(200).extract().path("JobFlowId");
        try {
            String cluster = "{\"ClusterId\":\"" + id + "\"}";
            String core = emr("ListInstanceGroups", cluster).jsonPath()
                    .getString("InstanceGroups.find { it.InstanceGroupType == 'CORE' }.Id");
            Response before = emr("ListInstances", cluster);
            List<String> ids = before.jsonPath().getList("Instances.Ec2InstanceId");
            List<String> addresses = before.jsonPath().getList("Instances.PrivateIpAddress");
            String ended = ids.get(2);

            emr("ModifyInstanceGroups", "{\"ClusterId\":\"" + id + "\",\"InstanceGroups\":[{\"InstanceGroupId\":\""
                    + core + "\",\"EC2InstanceIdsToTerminate\":[\"" + ended + "\"]}]}").then().statusCode(200);

            Response after = emr("ListInstances", cluster);
            assertEquals(List.of(ids.get(0), ids.get(1), ids.get(3), ids.get(4)),
                    after.jsonPath().getList("Instances.Ec2InstanceId"));
            assertEquals(List.of(addresses.get(0), addresses.get(1), addresses.get(3), addresses.get(4)),
                    after.jsonPath().getList("Instances.PrivateIpAddress"));
            emr("ListInstanceGroups", cluster).then().statusCode(200)
                    .body("InstanceGroups.find { it.Id == '" + core + "' }.RequestedInstanceCount", equalTo(2))
                    .body("InstanceGroups.find { it.Id == '" + core + "' }.RunningInstanceCount", equalTo(2));
            emr("ModifyInstanceGroups", "{\"ClusterId\":\"" + id + "\",\"InstanceGroups\":[{\"InstanceGroupId\":\""
                    + core + "\",\"EC2InstanceIdsToTerminate\":[\"" + ended + "\"]}]}").then().statusCode(400)
                    .body("__type", equalTo("ValidationException"));
            emr("ModifyInstanceGroups", "{\"ClusterId\":\"" + id + "\",\"InstanceGroups\":[{\"InstanceGroupId\":\""
                    + core + "\",\"EC2InstanceIdsToTerminate\":\"" + ids.get(3) + "\"}]}").then().statusCode(400)
                    .body("__type", equalTo("SerializationException"));
            // The group's last instance too: the task instance after it keeps its id.
            emr("ModifyInstanceGroups", "{\"ClusterId\":\"" + id + "\",\"InstanceGroups\":[{\"InstanceGroupId\":\""
                    + core + "\",\"EC2InstanceIdsToTerminate\":[\"" + ids.get(3) + "\"]}]}").then().statusCode(200);
            assertEquals(List.of(ids.get(0), ids.get(1), ids.get(4)),
                    emr("ListInstances", cluster).jsonPath().getList("Instances.Ec2InstanceId"));

            emr("ModifyInstanceGroups", "{\"ClusterId\":\"" + id + "\",\"InstanceGroups\":[{\"InstanceGroupId\":\""
                    + core + "\",\"InstanceCount\":3}]}").then().statusCode(200);
            List<String> grown = emr("ListInstances", "{\"ClusterId\":\"" + id + "\",\"InstanceGroupId\":\"" + core
                    + "\"}").jsonPath().getList("Instances.Ec2InstanceId");
            assertEquals(3, grown.size());
            assertFalse(grown.contains(ended), "an ended instance's id is not taken again");
        } finally {
            emr("TerminateJobFlows", "{\"JobFlowIds\":[\"" + id + "\"]}").then().statusCode(200);
        }
    }
}
