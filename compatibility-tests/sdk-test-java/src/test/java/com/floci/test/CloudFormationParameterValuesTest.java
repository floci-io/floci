package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudFormation parameters without a value")
class CloudFormationParameterValuesTest {

    private static final String TEMPLATE = """
            {
              "Parameters": {"Env": {"Type": "String"}},
              "Resources": {
                "Param": {
                  "Type": "AWS::SSM::Parameter",
                  "Properties": {"Name": {"Fn::Sub": "/${AWS::StackName}"}, "Type": "String",
                    "Value": {"Ref": "Env"}, "Description": "%s"}
                }
              }
            }
            """;

    private static CloudFormationClient cfn;
    private static SsmClient ssm;
    private String stackName;

    @BeforeAll
    static void clients() {
        cfn = TestFixtures.cloudFormationClient();
        ssm = TestFixtures.ssmClient();
    }

    @BeforeEach
    void setup() {
        stackName = TestFixtures.uniqueName("compat-cfn-param-values");
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        // Also when the stack was refused: DeleteStack of a name with no stack does nothing.
        cfn.deleteStack(r -> r.stackName(stackName));
        TestFixtures.await(() -> {
            try {
                List<Stack> stacks = cfn.describeStacks(r -> r.stackName(stackName)).stacks();
                return stacks.isEmpty() || "DELETE_COMPLETE".equals(stacks.get(0).stackStatusAsString());
            } catch (CloudFormationException e) {
                return e.getMessage().contains("does not exist");
            }
        }, stackName, "deleted", TestFixtures.isRealAws() ? 600_000_000_000L : 60_000_000_000L,
                TestFixtures.isRealAws() ? 5_000 : 100);
    }

    @AfterAll
    static void closeClients() {
        cfn.close();
        ssm.close();
    }

    @Test
    @DisplayName("CreateStack refuses a parameter with no value and no Default, and creates nothing")
    void createStackRefusesAParameterWithoutAValue() {
        assertThatThrownBy(() -> cfn.createStack(r -> r.stackName(stackName).templateBody(TEMPLATE.formatted("one"))))
                .isInstanceOfSatisfying(CloudFormationException.class, e -> {
                    assertThat(e.statusCode()).isEqualTo(400);
                    assertThat(e.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
                    assertThat(e.awsErrorDetails().errorMessage()).isEqualTo("Parameters: [Env] must have values");
                });
        assertThatThrownBy(() -> cfn.describeStacks(r -> r.stackName(stackName)))
                .isInstanceOf(CloudFormationException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("UpdateStack needs the value again, or UsePreviousValue to keep the deployed one")
    void updateStackNeedsAValueOrUsePreviousValue() throws InterruptedException {
        cfn.createStack(r -> r.stackName(stackName).templateBody(TEMPLATE.formatted("one"))
                .parameters(Parameter.builder().parameterKey("Env").parameterValue("first").build()));
        awaitStatus("CREATE_COMPLETE");

        assertThatThrownBy(() -> cfn.updateStack(r -> r.stackName(stackName).templateBody(TEMPLATE.formatted("two"))))
                .isInstanceOfSatisfying(CloudFormationException.class, e -> {
                    assertThat(e.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
                    assertThat(e.awsErrorDetails().errorMessage()).isEqualTo("Parameters: [Env] must have values");
                });
        assertThat(cfn.describeStacks(r -> r.stackName(stackName)).stacks().get(0).stackStatusAsString())
                .isEqualTo("CREATE_COMPLETE");

        cfn.updateStack(r -> r.stackName(stackName).templateBody(TEMPLATE.formatted("two"))
                .parameters(Parameter.builder().parameterKey("Env").usePreviousValue(true).build()));
        awaitStatus("UPDATE_COMPLETE");
        assertThat(ssm.getParameter(r -> r.name("/" + stackName)).parameter().value()).isEqualTo("first");
    }

    private void awaitStatus(String expected) throws InterruptedException {
        TestFixtures.await(() -> {
            Stack stack = cfn.describeStacks(r -> r.stackName(stackName)).stacks().get(0);
            String status = stack.stackStatusAsString();
            if (status.endsWith("_FAILED") || status.contains("ROLLBACK")) {
                throw new AssertionError(stackName + " reached " + status + ": " + stack.stackStatusReason());
            }
            return expected.equals(status);
        }, stackName, expected, TestFixtures.isRealAws() ? 600_000_000_000L : 60_000_000_000L,
                TestFixtures.isRealAws() ? 5_000 : 100);
    }
}
