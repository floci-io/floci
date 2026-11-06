package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackResource;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.GetFunctionEventInvokeConfigResponse;
import software.amazon.awssdk.services.lambda.model.ResourceNotFoundException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives an {@code AWS::Lambda::EventInvokeConfig} through the CloudFormation SDK and reads it
 * back through the Lambda SDK: the physical id is {@code FunctionName|Qualifier}, a settings
 * change updates in place, a qualifier change replaces the configuration and removes the
 * displaced one, and the stack delete removes it.
 */
@DisplayName("CloudFormation AWS::Lambda::EventInvokeConfig")
class CloudFormationLambdaEventInvokeConfigTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/cfn-lambda-role";
    private static final String DLQ_ARN = "arn:aws:sqs:us-east-1:000000000000:compat-async-dlq";

    private static CloudFormationClient cloudFormation;
    private static LambdaClient lambda;
    private static String functionStackName;
    private static String stackName;
    private static String functionName;

    @BeforeAll
    static void setup() {
        cloudFormation = TestFixtures.cloudFormationClient();
        lambda = TestFixtures.lambdaClient();
        functionStackName = TestFixtures.uniqueName("compat-cfn-eic-fn");
        stackName = TestFixtures.uniqueName("compat-cfn-eic");
        functionName = TestFixtures.uniqueName("eic-fn");
    }

    @AfterAll
    static void cleanup() {
        if (cloudFormation != null) {
            for (String name : List.of(stackName, functionStackName)) {
                try {
                    cloudFormation.deleteStack(r -> r.stackName(name));
                    waitForDeleted(name, 60);
                } catch (Exception e) {
                    System.err.println("CloudFormation event invoke config cleanup skipped: " + e.getMessage());
                }
            }
            cloudFormation.close();
        }
        if (lambda != null) {
            lambda.close();
        }
    }

    @Test
    void configurationFollowsTheTemplateThroughCreateUpdateReplacementAndDelete() throws InterruptedException {
        cloudFormation.createStack(r -> r.stackName(functionStackName).templateBody(functionTemplate()));
        assertThat(waitForTerminal(functionStackName, 60)).isEqualTo("CREATE_COMPLETE");
        String version = output(functionStackName, "Version");

        cloudFormation.createStack(r -> r
                .stackName(stackName)
                .templateBody(configTemplate())
                .parameters(parameters("$LATEST", "1")));
        assertThat(waitForTerminal(stackName, 60)).isEqualTo("CREATE_COMPLETE");

        assertThat(output(stackName, "ConfigRef"))
                .as("Ref is the composite FunctionName|Qualifier identifier")
                .isEqualTo(functionName + "|$LATEST");
        StackResource resource = cloudFormation.describeStackResources(r -> r.stackName(stackName))
                .stackResources().stream()
                .filter(sr -> "AWS::Lambda::EventInvokeConfig".equals(sr.resourceType()))
                .findFirst()
                .orElseThrow();
        assertThat(resource.physicalResourceId()).isEqualTo(functionName + "|$LATEST");

        GetFunctionEventInvokeConfigResponse created = get("$LATEST");
        assertThat(created.functionArn()).endsWith(":function:" + functionName + ":$LATEST");
        assertThat(created.maximumRetryAttempts()).isEqualTo(1);
        assertThat(created.maximumEventAgeInSeconds()).isEqualTo(300);
        assertThat(created.destinationConfig().onFailure().destination()).isEqualTo(DLQ_ARN);

        cloudFormation.updateStack(r -> r
                .stackName(stackName)
                .templateBody(configTemplate())
                .parameters(parameters("$LATEST", "0")));
        assertThat(waitForTerminal(stackName, 60)).isEqualTo("UPDATE_COMPLETE");
        assertThat(output(stackName, "ConfigRef"))
                .as("a settings change updates the configuration in place")
                .isEqualTo(functionName + "|$LATEST");
        GetFunctionEventInvokeConfigResponse updated = get("$LATEST");
        assertThat(updated.maximumRetryAttempts()).isEqualTo(0);
        assertThat(updated.maximumEventAgeInSeconds()).isEqualTo(300);

        cloudFormation.updateStack(r -> r
                .stackName(stackName)
                .templateBody(configTemplate())
                .parameters(parameters(version, "0")));
        assertThat(waitForTerminal(stackName, 60)).isEqualTo("UPDATE_COMPLETE");
        assertThat(output(stackName, "ConfigRef"))
                .as("a qualifier change replaces the configuration")
                .isEqualTo(functionName + "|" + version);
        assertThat(get(version).functionArn()).endsWith(":function:" + functionName + ":" + version);
        assertThatThrownBy(() -> get("$LATEST"))
                .as("the displaced configuration is deleted once the update commits")
                .isInstanceOf(ResourceNotFoundException.class);

        cloudFormation.deleteStack(r -> r.stackName(stackName));
        waitForDeleted(stackName, 60);
        assertThatThrownBy(() -> get(version)).isInstanceOf(ResourceNotFoundException.class);
    }

    private static GetFunctionEventInvokeConfigResponse get(String qualifier) {
        return lambda.getFunctionEventInvokeConfig(r -> r.functionName(functionName).qualifier(qualifier));
    }

    private static String functionTemplate() {
        return """
                {
                  "Resources": {
                    "Fn": {
                      "Type": "AWS::Lambda::Function",
                      "Properties": {
                        "FunctionName": "%s",
                        "Runtime": "nodejs20.x",
                        "Handler": "index.handler",
                        "Role": "%s",
                        "Code": {"ZipFile": "exports.handler = async (e) => ({ statusCode: 200 });"}
                      }
                    },
                    "Ver": {
                      "Type": "AWS::Lambda::Version",
                      "Properties": {"FunctionName": {"Ref": "Fn"}}
                    }
                  },
                  "Outputs": {
                    "Version": {"Value": {"Fn::GetAtt": ["Ver", "Version"]}}
                  }
                }
                """.formatted(functionName, ROLE);
    }

    private static String configTemplate() {
        return """
                {
                  "Parameters": {
                    "Qualifier": {"Type": "String"},
                    "Retries": {"Type": "String"}
                  },
                  "Resources": {
                    "AsyncConfig": {
                      "Type": "AWS::Lambda::EventInvokeConfig",
                      "Properties": {
                        "FunctionName": "%s",
                        "Qualifier": {"Ref": "Qualifier"},
                        "MaximumRetryAttempts": {"Ref": "Retries"},
                        "MaximumEventAgeInSeconds": 300,
                        "DestinationConfig": {"OnFailure": {"Destination": "%s"}}
                      }
                    }
                  },
                  "Outputs": {
                    "ConfigRef": {"Value": {"Ref": "AsyncConfig"}}
                  }
                }
                """.formatted(functionName, DLQ_ARN);
    }

    private static List<Parameter> parameters(String qualifier, String retries) {
        return List.of(
                Parameter.builder().parameterKey("Qualifier").parameterValue(qualifier).build(),
                Parameter.builder().parameterKey("Retries").parameterValue(retries).build());
    }

    private static String output(String stack, String key) {
        Stack described = cloudFormation.describeStacks(
                DescribeStacksRequest.builder().stackName(stack).build()).stacks().get(0);
        return described.outputs().stream()
                .filter(o -> key.equals(o.outputKey()))
                .map(Output::outputValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("stack " + stack + " has no output " + key));
    }

    private static String waitForTerminal(String name, int maxSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            List<Stack> stacks = cloudFormation.describeStacks(
                    DescribeStacksRequest.builder().stackName(name).build()).stacks();
            if (!stacks.isEmpty()) {
                String status = stacks.get(0).stackStatusAsString();
                if (!status.endsWith("_IN_PROGRESS")) {
                    return status;
                }
            }
            Thread.sleep(500);
        }
        throw new AssertionError(
                "Stack " + name + " did not reach a terminal state within " + maxSeconds + "s");
    }

    private static void waitForDeleted(String name, int maxSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<Stack> stacks = cloudFormation.describeStacks(
                        DescribeStacksRequest.builder().stackName(name).build()).stacks();
                if (stacks.isEmpty()
                        || "DELETE_COMPLETE".equals(stacks.get(0).stackStatusAsString())) {
                    return;
                }
            } catch (CloudFormationException e) {
                if (e.getMessage() != null && e.getMessage().contains("does not exist")) {
                    return;
                }
                throw e;
            }
            Thread.sleep(500);
        }
        throw new AssertionError(
                "Stack " + name + " was not deleted within " + maxSeconds + "s");
    }
}
