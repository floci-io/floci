package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CreateStackRequest;
import software.amazon.awssdk.services.cloudformation.model.DeleteStackRequest;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.UpdateStackRequest;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.GetFunctionConfigurationResponse;
import software.amazon.awssdk.services.lambda.model.LogFormat;
import software.amazon.awssdk.services.lambda.model.ResourceNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AWS::Lambda::Function.DurableConfig through the CloudFormation and Lambda SDK clients: created,
 * updated in place, and removed by replacing the function.
 */
@DisplayName("CloudFormation Lambda DurableConfig")
class CloudFormationLambdaDurableConfigTest {

    private static final String STACK_NAME = TestFixtures.uniqueName("compat-cfn-durable");
    private static final String ROLE = "arn:aws:iam::000000000000:role/cfn-lambda-role";

    private static CloudFormationClient cfn;
    private static LambdaClient lambda;

    @BeforeAll
    static void setup() {
        cfn = TestFixtures.cloudFormationClient();
        lambda = TestFixtures.lambdaClient();
    }

    @AfterAll
    static void cleanup() {
        if (cfn != null) {
            try {
                cfn.deleteStack(DeleteStackRequest.builder().stackName(STACK_NAME).build());
            } catch (Exception ignored) {
                // Cleanup only. A failed delete must not hide the test result.
            }
            cfn.close();
        }
        if (lambda != null) {
            lambda.close();
        }
    }

    @Test
    @DisplayName("DurableConfig is created, updated in place and removed by replacement")
    void durableConfigLifecycle() throws InterruptedException {
        cfn.createStack(CreateStackRequest.builder()
                .stackName(STACK_NAME)
                .templateBody(template("""
                        "DurableConfig": {"ExecutionTimeout": {"Ref": "ExecutionTimeout"}, "RetentionPeriodInDays": 3}
                        """))
                .parameters(executionTimeout("60"))
                .build());
        assertThat(waitForTerminal()).isEqualTo("CREATE_COMPLETE");
        String created = functionName();
        GetFunctionConfigurationResponse durable = lambda.getFunctionConfiguration(r -> r.functionName(created));
        assertThat(durable.timeout()).as("a durable function created without Timeout").isEqualTo(60);
        assertThat(durable.durableConfig().executionTimeout()).isEqualTo(60);
        assertThat(durable.durableConfig().retentionPeriodInDays()).isEqualTo(3);
        assertThat(durable.loggingConfig().logFormat()).isEqualTo(LogFormat.JSON);

        cfn.updateStack(UpdateStackRequest.builder()
                .stackName(STACK_NAME)
                .templateBody(template("""
                        "DurableConfig": {"ExecutionTimeout": {"Ref": "ExecutionTimeout"}}
                        """))
                .parameters(executionTimeout("120"))
                .build());
        assertThat(waitForTerminal()).isEqualTo("UPDATE_COMPLETE");
        assertThat(functionName()).as("a changed DurableConfig updates in place").isEqualTo(created);
        GetFunctionConfigurationResponse updated = lambda.getFunctionConfiguration(r -> r.functionName(created));
        assertThat(updated.durableConfig().executionTimeout()).isEqualTo(120);
        assertThat(updated.durableConfig().retentionPeriodInDays()).isEqualTo(14);

        cfn.updateStack(UpdateStackRequest.builder()
                .stackName(STACK_NAME)
                .templateBody(template("\"Description\": \"plain\""))
                .parameters(executionTimeout("120"))
                .build());
        assertThat(waitForTerminal()).isEqualTo("UPDATE_COMPLETE");
        String replaced = functionName();
        assertThat(replaced).as("removing DurableConfig replaces the function").isNotEqualTo(created);
        assertThat(lambda.getFunctionConfiguration(r -> r.functionName(replaced)).durableConfig()).isNull();
        assertThatThrownBy(() -> lambda.getFunctionConfiguration(r -> r.functionName(created)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static String template(String functionProperty) {
        return """
                {
                  "Parameters": {"ExecutionTimeout": {"Type": "Number"}},
                  "Resources": {
                    "Fn": {
                      "Type": "AWS::Lambda::Function",
                      "Properties": {
                        "Runtime": "python3.14",
                        "Handler": "index.handler",
                        "Role": "%s",
                        "Code": {"ZipFile": "def handler(e, c): return 'ok'"},
                        %s
                      }
                    }
                  },
                  "Outputs": {"Name": {"Value": {"Ref": "Fn"}}}
                }
                """.formatted(ROLE, functionProperty);
    }

    private static Parameter executionTimeout(String value) {
        return Parameter.builder().parameterKey("ExecutionTimeout").parameterValue(value).build();
    }

    private static Stack stack() {
        return cfn.describeStacks(r -> r.stackName(STACK_NAME)).stacks().get(0);
    }

    private static String functionName() {
        return stack().outputs().get(0).outputValue();
    }

    private static String waitForTerminal() throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            String status = stack().stackStatusAsString();
            if (!status.endsWith("_IN_PROGRESS")) {
                return status;
            }
            Thread.sleep(500);
        }
        return stack().stackStatusAsString();
    }
}
