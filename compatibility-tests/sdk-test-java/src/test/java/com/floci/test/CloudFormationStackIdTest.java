package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.ChangeSetType;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.CreateChangeSetResponse;
import software.amazon.awssdk.services.cloudformation.model.DescribeChangeSetResponse;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.UpdateStackResponse;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudFormation StackName given as the stack ID")
class CloudFormationStackIdTest {

    private static final String TEMPLATE = """
            {
              "Parameters": {
                "ParameterName": {"Type": "String"},
                "ParameterValue": {"Type": "String"}
              },
              "Resources": {
                "Param": {
                  "Type": "AWS::SSM::Parameter",
                  "Properties": {
                    "Name": {"Ref": "ParameterName"},
                    "Type": "String",
                    "Value": {"Ref": "ParameterValue"}
                  }
                }
              }
            }
            """;

    private static CloudFormationClient cfn;
    private static SsmClient ssm;
    private String stackName;
    private String parameterName;
    private boolean stackCreated;

    @BeforeAll
    static void clients() {
        cfn = TestFixtures.cloudFormationClient();
        ssm = TestFixtures.ssmClient();
    }

    @BeforeEach
    void setup() {
        stackName = TestFixtures.uniqueName("compat-cfn-stack-id");
        parameterName = "/" + stackName;
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        deleteStack();
    }

    @AfterAll
    static void closeClients() {
        cfn.close();
        ssm.close();
    }

    @Test
    @DisplayName("UpdateStack accepts the stack ID as StackName")
    void updateStackByStackId() throws InterruptedException {
        String stackId = createStack("first");

        UpdateStackResponse updated = cfn.updateStack(r -> r.stackName(stackId).templateBody(TEMPLATE)
                .parameters(parameters("second")));

        assertThat(updated.stackId()).isEqualTo(stackId);
        awaitStatus("UPDATE_COMPLETE");
        assertThat(parameterValue()).isEqualTo("second");
    }

    @Test
    @DisplayName("CreateChangeSet of type UPDATE accepts the stack ID as StackName")
    void createUpdateChangeSetByStackId() throws InterruptedException {
        String stackId = createStack("first");

        CreateChangeSetResponse created = cfn.createChangeSet(r -> r.stackName(stackId)
                .changeSetName("update-by-stack-id").changeSetType(ChangeSetType.UPDATE)
                .templateBody(TEMPLATE).parameters(parameters("second")));

        assertThat(created.stackId()).isEqualTo(stackId);
        await(() -> "CREATE_COMPLETE".equals(describeChangeSet(created.id(), stackId).statusAsString()),
                "change set CREATE_COMPLETE");
        DescribeChangeSetResponse described = describeChangeSet(created.id(), stackId);
        assertThat(described.stackName()).isEqualTo(stackName);
        assertThat(described.stackId()).isEqualTo(stackId);

        cfn.executeChangeSet(r -> r.changeSetName(created.id()).stackName(stackId));
        awaitStatus("UPDATE_COMPLETE");
        assertThat(parameterValue()).isEqualTo("second");
    }

    @Test
    @DisplayName("A deleted stack's ID does not update a new stack of the same name")
    void staleStackIdIsRejected() throws InterruptedException {
        String staleStackId = createStack("first");
        String refusal = "Stack:" + staleStackId + " is in DELETE_COMPLETE state and can not be updated.";
        deleteStack();
        assertThatThrownBy(() -> cfn.updateStack(r -> r.stackName(staleStackId).templateBody(TEMPLATE)
                .parameters(parameters("second"))))
                .isInstanceOfSatisfying(CloudFormationException.class, e -> {
                    assertThat(e.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
                    assertThat(e.awsErrorDetails().errorMessage()).isEqualTo(refusal);
                });
        String currentStackId = createStack("first");
        assertThat(currentStackId).isNotEqualTo(staleStackId);

        assertThatThrownBy(() -> cfn.updateStack(r -> r.stackName(staleStackId).templateBody(TEMPLATE)
                .parameters(parameters("second"))))
                .isInstanceOfSatisfying(CloudFormationException.class, e -> {
                    assertThat(e.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
                    assertThat(e.awsErrorDetails().errorMessage()).isEqualTo(refusal);
                });
        assertThatThrownBy(() -> cfn.createChangeSet(r -> r.stackName(staleStackId)
                .changeSetName("update-by-stale-stack-id").changeSetType(ChangeSetType.UPDATE)
                .templateBody(TEMPLATE).parameters(parameters("second"))))
                .isInstanceOfSatisfying(CloudFormationException.class, e -> {
                    assertThat(e.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
                    assertThat(e.awsErrorDetails().errorMessage()).isEqualTo(refusal);
                });

        Stack stack = cfn.describeStacks(r -> r.stackName(stackName)).stacks().get(0);
        assertThat(stack.stackId()).isEqualTo(currentStackId);
        assertThat(stack.stackStatusAsString()).isEqualTo("CREATE_COMPLETE");
        assertThat(parameterValue()).isEqualTo("first");
    }

    private String createStack(String value) throws InterruptedException {
        String stackId = cfn.createStack(r -> r.stackName(stackName).templateBody(TEMPLATE)
                .parameters(parameters(value))).stackId();
        stackCreated = true;
        awaitStatus("CREATE_COMPLETE");
        assertThat(parameterValue()).isEqualTo(value);
        return stackId;
    }

    private List<Parameter> parameters(String value) {
        return List.of(
                Parameter.builder().parameterKey("ParameterName").parameterValue(parameterName).build(),
                Parameter.builder().parameterKey("ParameterValue").parameterValue(value).build());
    }

    private DescribeChangeSetResponse describeChangeSet(String changeSetId, String stackId) {
        return cfn.describeChangeSet(r -> r.changeSetName(changeSetId).stackName(stackId));
    }

    private String parameterValue() {
        return ssm.getParameter(r -> r.name(parameterName)).parameter().value();
    }

    private void awaitStatus(String expected) throws InterruptedException {
        await(() -> {
            Stack stack = cfn.describeStacks(r -> r.stackName(stackName)).stacks().get(0);
            String status = stack.stackStatusAsString();
            if (status.endsWith("_FAILED") || status.contains("ROLLBACK")) {
                throw new AssertionError(stackName + " reached " + status + ": " + stack.stackStatusReason());
            }
            return expected.equals(status);
        }, expected);
    }

    private void deleteStack() throws InterruptedException {
        if (!stackCreated) {
            return;
        }
        cfn.deleteStack(r -> r.stackName(stackName));
        await(() -> {
            try {
                List<Stack> stacks = cfn.describeStacks(r -> r.stackName(stackName)).stacks();
                if (stacks.isEmpty()) {
                    return true;
                }
                Stack stack = stacks.get(0);
                if ("DELETE_FAILED".equals(stack.stackStatusAsString())) {
                    throw new AssertionError(stackName + " deletion failed: " + stack.stackStatusReason());
                }
                // The name is free only once DescribeStacks no longer finds it.
                return false;
            } catch (CloudFormationException e) {
                if ("ValidationError".equals(e.awsErrorDetails().errorCode())
                        && e.getMessage().contains("does not exist")) {
                    return true;
                }
                throw e;
            }
        }, "stack deletion");
        stackCreated = false;
    }

    private void await(BooleanSupplier condition, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + (TestFixtures.isRealAws() ? 600_000_000_000L : 60_000_000_000L);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(TestFixtures.isRealAws() ? 5_000 : 100);
        }
        throw new AssertionError(stackName + " timed out waiting for " + expected);
    }
}
