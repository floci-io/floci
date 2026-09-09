package com.floci.test;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackResourcesRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackResource;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeStateMachineResponse;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CloudFormation Step Functions update")
class CloudFormationStepFunctionsUpdateTest {

    private static final Logger LOGGER =
            Logger.getLogger(CloudFormationStepFunctionsUpdateTest.class.getName());
    private static final String ROLE_ARN =
            "arn:aws:iam::000000000000:role/cfn-step-functions-update-role";

    private static CloudFormationClient cloudFormation;
    private static SfnClient stepFunctions;
    private static String stackName;

    @BeforeAll
    static void setup() {
        cloudFormation = TestFixtures.cloudFormationClient();
        stepFunctions = TestFixtures.sfnClient();
        stackName = TestFixtures.uniqueName("cfn-sfn-update");
    }

    @AfterAll
    static void cleanup() {
        if (cloudFormation != null) {
            if (stackName != null) {
                try {
                    cloudFormation.deleteStack(request -> request.stackName(stackName));
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to clean up CloudFormation update test", e);
                }
            }
            cloudFormation.close();
        }
        if (stepFunctions != null) {
            stepFunctions.close();
        }
    }

    @Test
    void sdkUpdatesStateMachineInPlace() throws InterruptedException {
        String stateMachineName = TestFixtures.uniqueName("cfn-managed-sfn");
        String initialDefinition =
                "{\"StartAt\":\"Initial\",\"States\":{\"Initial\":"
                        + "{\"Type\":\"Pass\",\"End\":true}}}";
        String updatedDefinition =
                "{\"StartAt\":\"Updated\",\"States\":{\"Updated\":"
                        + "{\"Type\":\"Pass\",\"End\":true}}}";

        cloudFormation.createStack(request -> request
                .stackName(stackName)
                .templateBody(template(
                        stateMachineName, initialDefinition, "initial")));
        assertThat(waitForTerminalStatus(stackName, 30))
                .isEqualTo("CREATE_COMPLETE");

        StackResource initialResource = stateMachineResource(stackName);
        String stateMachineArn = initialResource.physicalResourceId();
        DescribeStateMachineResponse initial =
                stepFunctions.describeStateMachine(
                        request -> request.stateMachineArn(stateMachineArn));

        cloudFormation.updateStack(request -> request
                .stackName(stackName)
                .templateBody(template(
                        stateMachineName, updatedDefinition, "updated")));
        assertThat(waitForTerminalStatus(stackName, 30))
                .isEqualTo("UPDATE_COMPLETE");

        StackResource updatedResource = stateMachineResource(stackName);
        assertThat(updatedResource.physicalResourceId())
                .isEqualTo(stateMachineArn);
        assertThat(updatedResource.resourceStatusAsString())
                .isEqualTo("UPDATE_COMPLETE");

        DescribeStateMachineResponse updated =
                stepFunctions.describeStateMachine(
                        request -> request.stateMachineArn(stateMachineArn));
        assertThat(updated.definition()).isEqualTo(updatedDefinition);
        assertThat(updated.revisionId()).isNotEqualTo(initial.revisionId());
    }

    private static StackResource stateMachineResource(String name) {
        List<StackResource> resources = cloudFormation.describeStackResources(
                DescribeStackResourcesRequest.builder()
                        .stackName(name)
                        .build()).stackResources();
        return resources.stream()
                .filter(resource -> "AWS::StepFunctions::StateMachine"
                        .equals(resource.resourceType()))
                .findFirst()
                .orElseThrow();
    }

    private static String waitForTerminalStatus(
            String name, int maxSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            List<Stack> stacks = cloudFormation.describeStacks(
                    DescribeStacksRequest.builder()
                            .stackName(name)
                            .build()).stacks();
            if (!stacks.isEmpty()) {
                String status = stacks.get(0).stackStatusAsString();
                if (!status.endsWith("_IN_PROGRESS")) {
                    return status;
                }
            }
            Thread.sleep(250);
        }
        throw new AssertionError(
                "Stack " + name
                        + " did not reach a terminal state within "
                        + maxSeconds + " seconds");
    }

    private static String template(
            String stateMachineName,
            String definition,
            String marker) {
        String escapedDefinition = definition.replace("\\", "\\\\")
                .replace("\"", "\\\"");
        return """
                {
                  "Resources": {
                    "StateMachine": {
                      "Type": "AWS::StepFunctions::StateMachine",
                      "Properties": {
                        "StateMachineName": "%s",
                        "RoleArn": "%s",
                        "DefinitionString": "%s"
                      }
                    }
                  },
                  "Outputs": {
                    "Marker": {"Value": "%s"}
                  }
                }
                """.formatted(
                        stateMachineName,
                        ROLE_ARN,
                        escapedDefinition,
                        marker);
    }
}
