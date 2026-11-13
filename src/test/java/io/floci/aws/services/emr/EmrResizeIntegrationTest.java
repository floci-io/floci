package io.floci.aws.services.emr;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static io.floci.aws.testing.EmrRequests.emr;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

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
}
