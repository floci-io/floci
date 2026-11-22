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
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CloudFormation AWS::SQS::QueuePolicy")
class CloudFormationSqsQueuePolicyTest {

    private static CloudFormationClient cloudFormation;
    private static SqsClient sqs;
    private static String stackName;
    private static String queueAName;
    private static String queueBName;

    @BeforeAll
    static void setup() {
        cloudFormation = TestFixtures.cloudFormationClient();
        sqs = TestFixtures.sqsClient();
        stackName = TestFixtures.uniqueName("compat-cfn-sqs-policy");
        queueAName = TestFixtures.uniqueName("compat-cfn-qa");
        queueBName = TestFixtures.uniqueName("compat-cfn-qb");
    }

    @AfterAll
    static void cleanup() {
        if (cloudFormation != null) {
            try {
                cloudFormation.deleteStack(DeleteStackRequest.builder().stackName(stackName).build());
                waitForDeleted(stackName, 30);
            } catch (Exception e) {
                System.err.println("CloudFormation SQS QueuePolicy cleanup skipped: " + e.getMessage());
            }
            cloudFormation.close();
        }
        if (sqs != null) {
            sqs.close();
        }
    }

    @Test
    void appliesUpdatesAndClearsQueuePolicyThroughAwsSdk() throws InterruptedException {
        cloudFormation.createStack(CreateStackRequest.builder()
                .stackName(stackName)
                .templateBody(templateWithPolicy("QueueA", "sqs:SendMessage"))
                .build());
        assertThat(waitForTerminal(stackName, 30)).isEqualTo("CREATE_COMPLETE");

        Map<String, String> outputs = stackOutputs();
        String queueAUrl = outputs.get("QueueAUrl");
        String queueBUrl = outputs.get("QueueBUrl");

        assertThat(queuePolicy(queueAUrl))
                .isNotNull()
                .contains("sqs:SendMessage");
        assertThat(queuePolicy(queueBUrl)).isNull();

        // Update the policy to target QueueB with a new action: QueueA should be cleared.
        cloudFormation.updateStack(r -> r
                .stackName(stackName)
                .templateBody(templateWithPolicy("QueueB", "sqs:ReceiveMessage")));
        assertThat(waitForTerminal(stackName, 30)).isEqualTo("UPDATE_COMPLETE");

        assertThat(queuePolicy(queueAUrl)).isNull();
        assertThat(queuePolicy(queueBUrl))
                .isNotNull()
                .contains("sqs:ReceiveMessage");

        // Removing the QueuePolicy resource from the stack clears the policy on QueueB.
        cloudFormation.updateStack(r -> r
                .stackName(stackName)
                .templateBody(templateWithoutPolicy()));
        assertThat(waitForTerminal(stackName, 30)).isEqualTo("UPDATE_COMPLETE");

        assertThat(queuePolicy(queueBUrl)).isNull();

        cloudFormation.deleteStack(DeleteStackRequest.builder().stackName(stackName).build());
        waitForDeleted(stackName, 30);
    }

    private static Map<String, String> stackOutputs() {
        Stack stack = cloudFormation.describeStacks(
                DescribeStacksRequest.builder().stackName(stackName).build()).stacks().get(0);
        return stack.outputs().stream()
                .collect(Collectors.toMap(Output::outputKey, Output::outputValue));
    }

    private static String queuePolicy(String queueUrl) {
        Map<QueueAttributeName, String> attrs = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.POLICY)
                        .build())
                .attributes();
        return attrs.get(QueueAttributeName.POLICY);
    }

    private static String templateWithPolicy(String targetLogicalId, String action) {
        return """
                {
                  "Resources": {
                    "QueueA": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {"QueueName": "%s"}
                    },
                    "QueueB": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {"QueueName": "%s"}
                    },
                    "MyPolicy": {
                      "Type": "AWS::SQS::QueuePolicy",
                      "Properties": {
                        "Queues": [{"Ref": "%s"}],
                        "PolicyDocument": {
                          "Version": "2012-10-17",
                          "Statement": [
                            {
                              "Effect": "Allow",
                              "Principal": {"AWS": "arn:aws:iam::000000000000:root"},
                              "Action": "%s",
                              "Resource": {"Fn::GetAtt": ["%s", "Arn"]}
                            }
                          ]
                        }
                      }
                    }
                  },
                  "Outputs": {
                    "QueueAUrl": {"Value": {"Ref": "QueueA"}},
                    "QueueBUrl": {"Value": {"Ref": "QueueB"}}
                  }
                }
                """.formatted(queueAName, queueBName, targetLogicalId, action, targetLogicalId);
    }

    private static String templateWithoutPolicy() {
        return """
                {
                  "Resources": {
                    "QueueA": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {"QueueName": "%s"}
                    },
                    "QueueB": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {"QueueName": "%s"}
                    }
                  },
                  "Outputs": {
                    "QueueAUrl": {"Value": {"Ref": "QueueA"}},
                    "QueueBUrl": {"Value": {"Ref": "QueueB"}}
                  }
                }
                """.formatted(queueAName, queueBName);
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
