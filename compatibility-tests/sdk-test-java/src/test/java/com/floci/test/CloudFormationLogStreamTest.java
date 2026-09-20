package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.LogStream;
import software.amazon.awssdk.services.cloudwatchlogs.model.OutputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CloudFormation AWS::Logs::LogStream")
class CloudFormationLogStreamTest {

    private static CloudFormationClient cfn;
    private static CloudWatchLogsClient logs;
    private String stackName;
    private boolean stackCreated;
    private final List<String> logGroups = new ArrayList<>();

    @BeforeAll
    static void clients() {
        cfn = TestFixtures.cloudFormationClient();
        logs = TestFixtures.cloudWatchLogsClient();
    }

    @BeforeEach
    void setup() {
        stackName = TestFixtures.uniqueName("compat-cfn-log-stream");
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        try {
            deleteStack();
        } finally {
            for (String group : logGroups) {
                try {
                    logs.deleteLogGroup(r -> r.logGroupName(group));
                } catch (ResourceNotFoundException ignored) {
                    // Stack rollback may already have removed a group created by the template.
                }
            }
        }
    }

    @AfterAll
    static void closeClients() {
        cfn.close();
        logs.close();
    }

    @Test
    void createsWritableStreamFromGroupReferenceAndDeletesItFromRetainedGroup() throws InterruptedException {
        String group = "/test/" + stackName;
        logGroups.add(group);
        String template = """
                {
                  "Resources": {
                    "Group": {
                      "Type": "AWS::Logs::LogGroup",
                      "DeletionPolicy": "Retain",
                      "Properties": {"LogGroupName": "%s"}
                    },
                    "Stream": {
                      "Type": "AWS::Logs::LogStream",
                      "Properties": {
                        "LogGroupName": {"Ref": "Group"},
                        "LogStreamName": "application"
                      }
                    }
                  },
                  "Outputs": {"StreamRef": {"Value": {"Ref": "Stream"}}}
                }
                """.formatted(group);

        createStack(template, List.of());
        assertIdentity("application");
        assertThat(streamNames(group)).containsExactly("application");
        assertWritable(group, output(), "created through CloudFormation");

        deleteStack();
        assertThat(streamNames(group))
                .as("the retained group survives, but its CloudFormation-managed stream is deleted")
                .isEmpty();
    }

    @Test
    void movingNamedStreamToAnotherGroupRemovesTheOldStreamAndPreservesUnrelatedStreams()
            throws InterruptedException {
        String firstGroup = createLogGroup("first");
        String secondGroup = createLogGroup("second");
        logs.createLogStream(r -> r.logGroupName(firstGroup).logStreamName("unrelated"));
        logs.createLogStream(r -> r.logGroupName(secondGroup).logStreamName("unrelated"));
        String template = """
                {
                  "Parameters": {"GroupName": {"Type": "String"}},
                  "Resources": {
                    "Stream": {
                      "Type": "AWS::Logs::LogStream",
                      "Properties": {
                        "LogGroupName": {"Ref": "GroupName"},
                        "LogStreamName": "application"
                      }
                    }
                  },
                  "Outputs": {"StreamRef": {"Value": {"Ref": "Stream"}}}
                }
                """;

        createStack(template, groupParameter(firstGroup));
        assertIdentity("application");
        assertWritable(firstGroup, output(), "before replacement");

        cfn.updateStack(r -> r.stackName(stackName).templateBody(template)
                .parameters(groupParameter(secondGroup)));
        awaitStatus("UPDATE_COMPLETE");
        assertIdentity("application");
        assertThat(streamNames(firstGroup)).containsExactly("unrelated");
        assertThat(streamNames(secondGroup)).containsExactlyInAnyOrder("application", "unrelated");
        assertWritable(secondGroup, output(), "after replacement");

        deleteStack();
        assertThat(streamNames(firstGroup)).containsExactly("unrelated");
        assertThat(streamNames(secondGroup)).containsExactly("unrelated");
    }

    @Test
    void noValueGeneratesANameUntilTheConditionSelectsAnExplicitName() throws InterruptedException {
        String group = createLogGroup("conditional");
        String template = """
                {
                  "Parameters": {"UseName": {"Type": "String"}},
                  "Conditions": {"Named": {"Fn::Equals": [{"Ref": "UseName"}, "true"]}},
                  "Resources": {
                    "Stream": {
                      "Type": "AWS::Logs::LogStream",
                      "Properties": {
                        "LogGroupName": "%s",
                        "LogStreamName": {"Fn::If": ["Named", "application", {"Ref": "AWS::NoValue"}]}
                      }
                    }
                  },
                  "Outputs": {"StreamRef": {"Value": {"Ref": "Stream"}}}
                }
                """.formatted(group);

        createStack(template, List.of(Parameter.builder().parameterKey("UseName").parameterValue("false").build()));
        String generated = output();
        assertThat(generated).startsWith(stackName + "-Stream-");
        assertIdentity(generated);
        assertThat(streamNames(group)).containsExactly(generated);
        assertWritable(group, generated, "generated from NoValue");

        cfn.updateStack(r -> r.stackName(stackName).templateBody(template)
                .parameters(Parameter.builder().parameterKey("UseName").parameterValue("true").build()));
        awaitStatus("UPDATE_COMPLETE");
        assertIdentity("application");
        assertThat(streamNames(group)).containsExactly("application");

        deleteStack();
        assertThat(streamNames(group)).isEmpty();
    }

    private void createStack(String template, List<Parameter> parameters) throws InterruptedException {
        cfn.createStack(r -> r.stackName(stackName).templateBody(template).parameters(parameters));
        stackCreated = true;
        awaitStatus("CREATE_COMPLETE");
    }

    private String createLogGroup(String suffix) {
        String group = "/test/" + stackName + "/" + suffix;
        logs.createLogGroup(r -> r.logGroupName(group));
        logGroups.add(group);
        return group;
    }

    private static List<Parameter> groupParameter(String group) {
        return List.of(Parameter.builder().parameterKey("GroupName").parameterValue(group).build());
    }

    private List<String> streamNames(String group) {
        return logs.describeLogStreamsPaginator(r -> r.logGroupName(group)).logStreams().stream()
                .map(LogStream::logStreamName).toList();
    }

    private String output() {
        return cfn.describeStacks(r -> r.stackName(stackName)).stacks().get(0).outputs().stream()
                .filter(o -> "StreamRef".equals(o.outputKey())).map(Output::outputValue)
                .findFirst().orElseThrow();
    }

    private void assertIdentity(String streamName) {
        assertThat(output()).as("Ref is the log stream name").isEqualTo(streamName);
        assertThat(cfn.describeStackResource(r -> r.stackName(stackName).logicalResourceId("Stream"))
                .stackResourceDetail().physicalResourceId()).isEqualTo(streamName);
    }

    private void assertWritable(String group, String stream, String message) throws InterruptedException {
        logs.putLogEvents(r -> r.logGroupName(group).logStreamName(stream)
                .logEvents(InputLogEvent.builder().timestamp(Instant.now().toEpochMilli()).message(message).build()));
        await(() -> logs.getLogEvents(r -> r.logGroupName(group).logStreamName(stream).startFromHead(true))
                .events().stream().map(OutputLogEvent::message).toList().equals(List.of(message)),
                "the event written to " + group + "/" + stream);
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
                return "DELETE_COMPLETE".equals(stack.stackStatusAsString());
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
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(stackName + " timed out waiting for " + expected);
    }
}
