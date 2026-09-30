package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Capability;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackEvent;
import software.amazon.awssdk.services.cloudformation.model.StackResource;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.LogGroup;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudFormation DeleteStack after UPDATE_ROLLBACK_FAILED")
class CloudFormationDeleteAfterFailedRollbackTest {

    private static CloudFormationClient cfn;
    private static IamClient iam;
    private static CloudWatchLogsClient logs;
    private String stackName;
    private String roleName;
    private String logGroup;
    private String outsideGroup;

    @BeforeAll
    static void clients() {
        cfn = TestFixtures.cloudFormationClient();
        iam = TestFixtures.iamClient();
        logs = TestFixtures.cloudWatchLogsClient();
    }

    @BeforeEach
    void setup() {
        stackName = TestFixtures.uniqueName("compat-cfn-delete-rollback-failed");
        roleName = stackName + "-role";
        logGroup = "/test/" + stackName + "/lg";
        outsideGroup = "/test/" + stackName + "/outside";
    }

    @AfterEach
    void cleanup() {
        try {
            cfn.deleteStack(r -> r.stackName(stackName));
        } catch (CloudFormationException ignored) {
            // The test deletes the stack itself; this only covers an assertion failing first.
        }
        try {
            iam.deleteRole(r -> r.roleName(roleName));
        } catch (NoSuchEntityException ignored) {
            // Deleted by DeleteStack, which is what the test asserts.
        }
        for (String group : List.of(logGroup, outsideGroup)) {
            try {
                logs.deleteLogGroup(r -> r.logGroupName(group));
            } catch (ResourceNotFoundException ignored) {
                // Deleted by DeleteStack, which is what the test asserts.
            }
        }
    }

    @AfterAll
    static void closeClients() {
        cfn.close();
        iam.close();
        logs.close();
    }

    @Test
    @DisplayName("deletes resources a failed update rollback left UPDATE_FAILED")
    void deletesUpdateFailedResources() throws InterruptedException {
        logs.createLogGroup(r -> r.logGroupName(outsideGroup));

        String stackId = cfn.createStack(r -> r.stackName(stackName)
                .templateBody(template(false))
                .capabilities(Capability.CAPABILITY_NAMED_IAM)).stackId();
        assertThat(awaitStable()).isEqualTo("CREATE_COMPLETE");

        cfn.updateStack(r -> r.stackName(stackName)
                .templateBody(template(true))
                .capabilities(Capability.CAPABILITY_NAMED_IAM));
        assertThat(awaitStable()).isEqualTo("UPDATE_ROLLBACK_FAILED");
        assertThat(cfn.describeStackResources(r -> r.stackName(stackName)).stackResources())
                .filteredOn(resource -> List.of("Role", "LogGroup").contains(resource.logicalResourceId()))
                .extracting(StackResource::resourceStatusAsString)
                .as("precondition: the rollback could not restore the role or the log group")
                .containsExactlyInAnyOrder("UPDATE_FAILED", "UPDATE_FAILED");

        cfn.deleteStack(r -> r.stackName(stackName));
        awaitDeleted();

        assertThatThrownBy(() -> iam.getRole(r -> r.roleName(roleName)))
                .isInstanceOf(NoSuchEntityException.class);
        assertThat(logGroupNames(logGroup)).isEmpty();
        assertThat(logGroupNames(outsideGroup))
                .as("the group the update collided with was never the stack's")
                .containsExactly(outsideGroup);

        List<StackEvent> events = cfn.describeStackEventsPaginator(r -> r.stackName(stackId))
                .stackEvents().stream().toList();
        assertThat(events)
                .filteredOn(event -> "DELETE_COMPLETE".equals(event.resourceStatusAsString()))
                .extracting(StackEvent::logicalResourceId)
                .contains("Role", "LogGroup");
    }

    private List<String> logGroupNames(String prefix) {
        return logs.describeLogGroupsPaginator(r -> r.logGroupNamePrefix(prefix)).logGroups().stream()
                .map(LogGroup::logGroupName).toList();
    }

    /** The role and log group; the update changes both and adds a group that collides with outsideGroup. */
    private String template(boolean failingUpdate) {
        String roleExtra = failingUpdate ? ", \"Description\": \"updated\"" : "";
        String groupExtra = failingUpdate ? ", \"RetentionInDays\": 7" : "";
        String dup = failingUpdate ? """
                ,
                "Dup": {
                  "Type": "AWS::Logs::LogGroup",
                  "DependsOn": ["Role", "LogGroup"],
                  "Properties": {"LogGroupName": "%s"}
                }""".formatted(outsideGroup) : "";
        return """
                {
                  "Resources": {
                    "Role": {
                      "Type": "AWS::IAM::Role",
                      "Properties": {
                        "RoleName": "%s",
                        "AssumeRolePolicyDocument": {
                          "Version": "2012-10-17",
                          "Statement": [{
                            "Effect": "Allow",
                            "Principal": {"Service": "lambda.amazonaws.com"},
                            "Action": "sts:AssumeRole"
                          }]
                        }%s
                      }
                    },
                    "LogGroup": {
                      "Type": "AWS::Logs::LogGroup",
                      "Properties": {"LogGroupName": "%s"%s}
                    }%s
                  }
                }
                """.formatted(roleName, roleExtra, logGroup, groupExtra, dup);
    }

    private String awaitStable() throws InterruptedException {
        return await(() -> {
            Stack stack = cfn.describeStacks(r -> r.stackName(stackName)).stacks().get(0);
            String status = stack.stackStatusAsString();
            return status.endsWith("_IN_PROGRESS") ? null : status;
        }, "a stable status");
    }

    private void awaitDeleted() throws InterruptedException {
        String status = await(() -> {
            try {
                List<Stack> stacks = cfn.describeStacks(r -> r.stackName(stackName)).stacks();
                String current = stacks.isEmpty() ? "DELETE_COMPLETE" : stacks.get(0).stackStatusAsString();
                return current.endsWith("_IN_PROGRESS") ? null : current;
            } catch (CloudFormationException e) {
                if ("ValidationError".equals(e.awsErrorDetails().errorCode())
                        && e.getMessage().contains("does not exist")) {
                    return "DELETE_COMPLETE";
                }
                throw e;
            }
        }, "stack deletion");
        assertThat(status).isEqualTo("DELETE_COMPLETE");
    }

    private String await(Supplier<String> status, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            String current = status.get();
            if (current != null) {
                return current;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(stackName + " timed out waiting for " + expected);
    }
}
