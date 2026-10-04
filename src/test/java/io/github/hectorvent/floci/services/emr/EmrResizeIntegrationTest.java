package io.github.hectorvent.floci.services.emr;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static io.restassured.RestAssured.given;
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

    private static final String CT = "application/x-amz-json-1.1";
    private static final String PREFIX = "ElasticMapReduce.";

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

    private static Response call(String action, String body) {
        return given().contentType(CT).header("X-Amz-Target", PREFIX + action)
                .body(body).when().post("/");
    }

    private static String groupId(String type) {
        return call("ListInstanceGroups", "{\"ClusterId\":\"" + groupsClusterId + "\"}").jsonPath()
                .getString("InstanceGroups.find { it.InstanceGroupType == '" + type + "' }.Id");
    }

    @Test
    @Order(1)
    void runJobFlowStoresBootstrapActions() {
        Response resp = call("RunJobFlow",
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

        call("ListInstanceGroups", "{\"ClusterId\":\"" + groupsClusterId + "\"}").then().statusCode(200)
                .body("InstanceGroups.find { it.Id == '" + taskGroupId + "' }.Configurations", hasSize(0))
                .body("InstanceGroups.find { it.Id == '" + coreGroupId + "' }.Configurations", nullValue());

        call("ListBootstrapActions", "{\"ClusterId\":\"" + groupsClusterId + "\"}").then().statusCode(200)
                .body("BootstrapActions", hasSize(2))
                .body("BootstrapActions[0].Name", equalTo("install"))
                .body("BootstrapActions[0].ScriptPath", equalTo("s3://bucket/install.sh"))
                .body("BootstrapActions[0].Args", equalTo(List.of("--fast", "1")))
                .body("BootstrapActions[1].Args", hasSize(0));
    }

    @Test
    @Order(2)
    void listBootstrapActionsEmptyAndValidated() {
        Response resp = call("RunJobFlow", "{\"Name\":\"plain\",\"Instances\":{\"InstanceGroups\":"
                + "[{\"InstanceRole\":\"MASTER\",\"InstanceType\":\"m5.xlarge\",\"InstanceCount\":1}]}}");
        call("ListBootstrapActions", "{\"ClusterId\":\"" + resp.jsonPath().getString("JobFlowId") + "\"}")
                .then().statusCode(200).body("BootstrapActions", hasSize(0));
        call("ListBootstrapActions", "{\"ClusterId\":\"j-MISSING\"}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        call("RunJobFlow", "{\"Name\":\"bad\",\"BootstrapActions\":[{\"Name\":\"x\"}]}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(3)
    void modifyInstanceGroupsResizesAndReconfigures() {
        call("ModifyInstanceGroups", "{\"ClusterId\":\"" + groupsClusterId + "\",\"InstanceGroups\":["
                + "{\"InstanceGroupId\":\"" + coreGroupId + "\",\"InstanceCount\":4},"
                + "{\"InstanceGroupId\":\"" + taskGroupId + "\",\"InstanceCount\":0,\"Configurations\":"
                + "[{\"Classification\":\"yarn-site\",\"Properties\":{\"yarn.nodemanager.vmem-check-enabled\":\"false\"}}]}]}")
                .then().statusCode(200);
        call("ListInstanceGroups", "{\"ClusterId\":\"" + groupsClusterId + "\"}").then().statusCode(200)
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
        call("ListInstances", "{\"ClusterId\":\"" + groupsClusterId + "\"}").then().statusCode(200)
                .body("Instances", hasSize(5));
    }

    @Test
    @Order(4)
    void modifyInstanceGroupsWithoutClusterIdFindsTheGroup() {
        call("ModifyInstanceGroups", "{\"InstanceGroups\":[{\"InstanceGroupId\":\"" + coreGroupId
                + "\",\"InstanceCount\":3}]}").then().statusCode(200);
        call("ListInstanceGroups", "{\"ClusterId\":\"" + groupsClusterId + "\"}").then().statusCode(200)
                .body("InstanceGroups.find { it.Id == '" + coreGroupId + "' }.RunningInstanceCount", equalTo(3));
    }

    @Test
    @Order(5)
    void modifyInstanceGroupsIsValidatedBeforeAnyChange() {
        // The second modification is rejected, so the first is not applied either.
        call("ModifyInstanceGroups", "{\"ClusterId\":\"" + groupsClusterId + "\",\"InstanceGroups\":["
                + "{\"InstanceGroupId\":\"" + coreGroupId + "\",\"InstanceCount\":9},"
                + "{\"InstanceGroupId\":\"" + masterGroupId + "\",\"InstanceCount\":3}]}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        call("ListInstanceGroups", "{\"ClusterId\":\"" + groupsClusterId + "\"}").then().statusCode(200)
                .body("InstanceGroups.find { it.Id == '" + coreGroupId + "' }.RunningInstanceCount", equalTo(3));

        call("ModifyInstanceGroups", "{\"ClusterId\":\"" + groupsClusterId + "\",\"InstanceGroups\":["
                + "{\"InstanceGroupId\":\"" + coreGroupId + "\",\"InstanceCount\":-1}]}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        call("ModifyInstanceGroups", "{\"InstanceGroups\":[{\"InstanceGroupId\":\"ig-MISSING\",\"InstanceCount\":1}]}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        call("ModifyInstanceGroups", "{\"ClusterId\":\"" + groupsClusterId + "\",\"InstanceGroups\":[]}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(6)
    void modifyInstanceFleetResizes() {
        Response resp = call("RunJobFlow", "{\"Name\":\"fleets\",\"Instances\":{\"KeepJobFlowAliveWhenNoSteps\":true,"
                + "\"InstanceFleets\":[{\"InstanceFleetType\":\"MASTER\",\"TargetOnDemandCapacity\":1,"
                + "\"InstanceTypeConfigs\":[{\"InstanceType\":\"m5.xlarge\"}]},"
                + "{\"InstanceFleetType\":\"CORE\",\"TargetOnDemandCapacity\":2,\"TargetSpotCapacity\":2,"
                + "\"InstanceTypeConfigs\":[{\"InstanceType\":\"m5.xlarge\"}]}]}}");
        resp.then().statusCode(200);
        fleetClusterId = resp.jsonPath().getString("JobFlowId");
        Response fleets = call("ListInstanceFleets", "{\"ClusterId\":\"" + fleetClusterId + "\"}");
        masterFleetId = fleets.jsonPath().getString("InstanceFleets.find { it.InstanceFleetType == 'MASTER' }.Id");
        coreFleetId = fleets.jsonPath().getString("InstanceFleets.find { it.InstanceFleetType == 'CORE' }.Id");

        call("ModifyInstanceFleet", "{\"ClusterId\":\"" + fleetClusterId + "\",\"InstanceFleet\":"
                + "{\"InstanceFleetId\":\"" + coreFleetId + "\",\"TargetSpotCapacity\":6}}")
                .then().statusCode(200);
        call("ListInstanceFleets", "{\"ClusterId\":\"" + fleetClusterId + "\"}").then().statusCode(200)
                .body("InstanceFleets.find { it.Id == '" + coreFleetId + "' }.TargetOnDemandCapacity", equalTo(2))
                .body("InstanceFleets.find { it.Id == '" + coreFleetId + "' }.TargetSpotCapacity", equalTo(6))
                .body("InstanceFleets.find { it.Id == '" + coreFleetId + "' }.ProvisionedSpotCapacity", equalTo(6));
    }

    @Test
    @Order(7)
    void modifyInstanceFleetIsValidated() {
        call("ModifyInstanceFleet", "{\"ClusterId\":\"" + fleetClusterId + "\",\"InstanceFleet\":"
                + "{\"InstanceFleetId\":\"" + masterFleetId + "\",\"TargetOnDemandCapacity\":3}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        call("ModifyInstanceFleet", "{\"ClusterId\":\"" + fleetClusterId + "\",\"InstanceFleet\":"
                + "{\"InstanceFleetId\":\"" + coreFleetId + "\",\"TargetOnDemandCapacity\":-1}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        call("ModifyInstanceFleet", "{\"ClusterId\":\"" + fleetClusterId + "\",\"InstanceFleet\":"
                + "{\"InstanceFleetId\":\"if-MISSING\",\"TargetOnDemandCapacity\":1}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        call("ModifyInstanceFleet", "{\"ClusterId\":\"j-MISSING\",\"InstanceFleet\":"
                + "{\"InstanceFleetId\":\"" + coreFleetId + "\",\"TargetOnDemandCapacity\":1}}")
                .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
    }
}
