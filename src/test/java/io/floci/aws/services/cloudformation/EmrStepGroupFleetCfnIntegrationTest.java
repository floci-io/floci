package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.CfnTestStack;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.services.cloudformation.model.Stack;

import static io.floci.aws.testing.CfnStackWaits.output;
import static io.floci.aws.testing.EmrRequests.emr;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Provisions {@code AWS::EMR::Step}, {@code AWS::EMR::InstanceGroupConfig} and {@code AWS::EMR::InstanceFleetConfig}
 * on clusters of the same stack and reads them back through EMR: each {@code Ref} and {@code Fn::GetAtt} names its
 * step, group or fleet, an update resizes the group and the fleet in place, a later resource's failure puts the sizes
 * back, and deleting the stack shrinks them with their clusters.
 */
@QuarkusTest
class EmrStepGroupFleetCfnIntegrationTest {

    @RegisterExtension
    final CfnTestStack stack = new CfnTestStack("emr-step-group-fleet-cfn-it");

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void addResizeRollBackAndDeleteAStepAnInstanceGroupAndAnInstanceFleet() {
        Stack created = stack.create(template(1, 1, false));
        String groups = output(created, "Groups");
        String fleets = output(created, "Fleets");
        String stepId = output(created, "StepRef");
        String groupId = output(created, "GroupRef");
        String fleetId = output(created, "FleetRef");
        assertEquals(stepId, output(created, "StepId"));
        assertEquals(groupId, output(created, "GroupId"));
        assertEquals(fleetId, output(created, "FleetId"));
        emr("ListSteps", "{\"ClusterId\": \"" + groups + "\"}").then().statusCode(200)
            .body("Steps.Id", hasItem(stepId));
        groupCount(groups, groupId, 1);
        fleetCapacity(fleets, fleetId, 1);

        Stack resized = stack.update(template(2, 2, false), "UPDATE_COMPLETE");
        assertEquals(groupId, output(resized, "GroupRef"));
        assertEquals(fleetId, output(resized, "FleetRef"));
        groupCount(groups, groupId, 2);
        fleetCapacity(fleets, fleetId, 2);

        stack.update(template(3, 3, true), "UPDATE_ROLLBACK_COMPLETE");
        groupCount(groups, groupId, 2);
        fleetCapacity(fleets, fleetId, 2);

        CfnStackWaits.deleteStack(stack.name());

        emr("DescribeCluster", "{\"ClusterId\": \"" + groups + "\"}").then().statusCode(200)
            .body("Cluster.Status.State", equalTo("TERMINATED"));
        emr("DescribeCluster", "{\"ClusterId\": \"" + fleets + "\"}").then().statusCode(200)
            .body("Cluster.Status.State", equalTo("TERMINATED"));
    }

    private static String template(int taskCount, int fleetCapacity, boolean laterFails) {
        return """
            {
              "Resources": {
                "Groups": {
                  "Type": "AWS::EMR::Cluster",
                  "Properties": {
                    "Name": "groups", "ReleaseLabel": "emr-7.5.0", "JobFlowRole": "EMR_EC2_DefaultRole",
                    "ServiceRole": "EMR_DefaultRole",
                    "Instances": {"KeepJobFlowAliveWhenNoSteps": true,
                      "MasterInstanceGroup": {"InstanceCount": 1, "InstanceType": "m5.xlarge"},
                      "CoreInstanceGroup": {"InstanceCount": 1, "InstanceType": "m5.xlarge"}}
                  }
                },
                "Fleets": {
                  "Type": "AWS::EMR::Cluster",
                  "Properties": {
                    "Name": "fleets", "ReleaseLabel": "emr-7.5.0", "JobFlowRole": "EMR_EC2_DefaultRole",
                    "ServiceRole": "EMR_DefaultRole",
                    "Instances": {"KeepJobFlowAliveWhenNoSteps": true,
                      "MasterInstanceFleet": {"TargetOnDemandCapacity": 1,
                        "InstanceTypeConfigs": [{"InstanceType": "m5.xlarge"}]},
                      "CoreInstanceFleet": {"TargetOnDemandCapacity": 1,
                        "InstanceTypeConfigs": [{"InstanceType": "m5.xlarge"}]}}
                  }
                },
                "Step": {
                  "Type": "AWS::EMR::Step",
                  "Properties": {
                    "JobFlowId": {"Ref": "Groups"}, "Name": "pi", "ActionOnFailure": "CONTINUE",
                    "HadoopJarStep": {"Jar": "command-runner.jar", "Args": ["spark-example", "SparkPi"]}
                  }
                },
                "Group": {
                  "Type": "AWS::EMR::InstanceGroupConfig",
                  "Properties": {
                    "JobFlowId": {"Ref": "Groups"}, "InstanceRole": "TASK", "InstanceType": "m5.xlarge",
                    "InstanceCount": %d, "Name": "spot"
                  }
                },
                "Fleet": {
                  "Type": "AWS::EMR::InstanceFleetConfig",
                  "Properties": {
                    "ClusterId": {"Ref": "Fleets"}, "InstanceFleetType": "TASK", "Name": "tasks",
                    "TargetOnDemandCapacity": %d, "InstanceTypeConfigs": [{"InstanceType": "m5.xlarge"}]
                  }
                }%s
              },
              "Outputs": {
                "Groups": {"Value": {"Ref": "Groups"}},
                "Fleets": {"Value": {"Ref": "Fleets"}},
                "StepRef": {"Value": {"Ref": "Step"}},
                "StepId": {"Value": {"Fn::GetAtt": ["Step", "Id"]}},
                "GroupRef": {"Value": {"Ref": "Group"}},
                "GroupId": {"Value": {"Fn::GetAtt": ["Group", "InstanceGroupId"]}},
                "FleetRef": {"Value": {"Ref": "Fleet"}},
                "FleetId": {"Value": {"Fn::GetAtt": ["Fleet", "InstanceFleetId"]}}
              }
            }
            """.formatted(taskCount, fleetCapacity, laterFails ? CfnStackWaits.failingResourceAfter("Fleet") : "");
    }

    private static void groupCount(String clusterId, String groupId, int count) {
        emr("ListInstanceGroups", "{\"ClusterId\": \"" + clusterId + "\"}").then().statusCode(200)
            .body("InstanceGroups.find { it.Id == '" + groupId + "' }.RequestedInstanceCount", equalTo(count));
    }

    private static void fleetCapacity(String clusterId, String fleetId, int capacity) {
        emr("ListInstanceFleets", "{\"ClusterId\": \"" + clusterId + "\"}").then().statusCode(200)
            .body("InstanceFleets.find { it.Id == '" + fleetId + "' }.TargetOnDemandCapacity", equalTo(capacity));
    }
}
