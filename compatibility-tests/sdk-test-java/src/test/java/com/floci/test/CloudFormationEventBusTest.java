package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.CreateStackRequest;
import software.amazon.awssdk.services.cloudformation.model.DeleteStackRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.DescribeEventBusRequest;
import software.amazon.awssdk.services.eventbridge.model.DescribeEventBusResponse;
import software.amazon.awssdk.services.eventbridge.model.ListTagsForResourceRequest;
import software.amazon.awssdk.services.eventbridge.model.ResourceNotFoundException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudFormation AWS::Events::EventBus")
class CloudFormationEventBusTest {

    private static CloudFormationClient cloudFormation;
    private static EventBridgeClient eventBridge;
    private static String stackName;
    private static String busName;

    @BeforeAll
    static void setup() {
        cloudFormation = TestFixtures.cloudFormationClient();
        eventBridge = TestFixtures.eventBridgeClient();
        stackName = TestFixtures.uniqueName("compat-cfn-eventbus");
        busName = TestFixtures.uniqueName("compat-eventbus");
    }

    @AfterAll
    static void cleanup() {
        if (cloudFormation != null) {
            try {
                cloudFormation.deleteStack(
                        DeleteStackRequest.builder().stackName(stackName).build());
            } catch (Exception e) {
                System.err.println("CloudFormation EventBus cleanup skipped: " + e.getMessage());
            }
            cloudFormation.close();
        }
        if (eventBridge != null) {
            eventBridge.close();
        }
    }

    @Test
    void createsValidatesUpdatesAndDeletesEventBusThroughAwsSdk() throws InterruptedException {
        cloudFormation.createStack(CreateStackRequest.builder()
                .stackName(stackName)
                .templateBody(template("before", "old", "remove"))
                .build());
        assertThat(waitForTerminal(stackName, 30)).isEqualTo("CREATE_COMPLETE");

        Stack createdStack = cloudFormation.describeStacks(
                DescribeStacksRequest.builder().stackName(stackName).build())
                .stacks().get(0);
        Map<String, String> outputs = createdStack.outputs().stream()
                .collect(Collectors.toMap(Output::outputKey, Output::outputValue));
        String busArn = "arn:aws:events:us-east-1:000000000000:event-bus/" + busName;
        assertThat(outputs)
                .containsEntry("BusRef", busName)
                .containsEntry("BusName", busName)
                .containsEntry("BusArn", busArn);

        DescribeEventBusResponse created = eventBridge.describeEventBus(
                DescribeEventBusRequest.builder().name(busName).build());
        assertThat(created.name()).isEqualTo(busName);
        assertThat(created.arn()).isEqualTo(busArn);
        assertThat(created.description()).isEqualTo("before");
        assertThat(tags(busArn))
                .containsEntry("keep", "old")
                .containsEntry("remove", "old");

        cloudFormation.updateStack(r -> r
                .stackName(stackName)
                .templateBody(template("before", "old", "remove")));
        assertThat(waitForTerminal(stackName, 30)).isEqualTo("UPDATE_COMPLETE");

        cloudFormation.updateStack(r -> r
                .stackName(stackName)
                .templateBody(template("after", "new", "add")));
        assertThat(waitForTerminal(stackName, 30)).isEqualTo("UPDATE_ROLLBACK_COMPLETE");

        DescribeEventBusResponse afterRejectedUpdate = eventBridge.describeEventBus(
                DescribeEventBusRequest.builder().name(busName).build());
        assertThat(afterRejectedUpdate.description()).isEqualTo("before");
        assertThat(tags(busArn))
                .containsEntry("keep", "old")
                .containsEntry("remove", "old")
                .doesNotContainKey("add");

        cloudFormation.deleteStack(DeleteStackRequest.builder().stackName(stackName).build());
        waitForDeleted(stackName, 30);
        assertThatThrownBy(() -> eventBridge.describeEventBus(
                DescribeEventBusRequest.builder().name(busName).build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static Map<String, String> tags(String busArn) {
        return eventBridge.listTagsForResource(ListTagsForResourceRequest.builder()
                        .resourceARN(busArn)
                        .build())
                .tags().stream()
                .collect(Collectors.toMap(
                        software.amazon.awssdk.services.eventbridge.model.Tag::key,
                        software.amazon.awssdk.services.eventbridge.model.Tag::value));
    }

    private static String template(String description, String keepValue, String secondTagKey) {
        return """
                {
                  "Resources": {
                    "Bus": {
                      "Type": "AWS::Events::EventBus",
                      "Properties": {
                        "Name": "%s",
                        "Description": "%s",
                        "Tags": [
                          {"Key": "keep", "Value": "%s"},
                          {"Key": "%s", "Value": "%s"}
                        ]
                      }
                    },
                    "Rule": {
                      "Type": "AWS::Events::Rule",
                      "Properties": {
                        "Name": "%s",
                        "EventBusName": {"Ref": "Bus"},
                        "EventPattern": {"source": ["com.example.orders"]},
                        "State": "ENABLED"
                      }
                    }
                  },
                  "Outputs": {
                    "BusRef": {"Value": {"Ref": "Bus"}},
                    "BusArn": {"Value": {"Fn::GetAtt": ["Bus", "Arn"]}},
                    "BusName": {"Value": {"Fn::GetAtt": ["Bus", "Name"]}}
                  }
                }
                """.formatted(
                        busName, description, keepValue, secondTagKey, keepValue, busName + "-rule");
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
