package com.floci.test;

import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Stack;

import java.util.List;
import java.util.stream.Collectors;

/** Stack waits shared by the CloudFormation tests that drive one stack through create, update and delete. */
final class CloudFormationStackTestHelper {

    private static final long TIMEOUT_NANOS = 300_000_000_000L;

    private CloudFormationStackTestHelper() {
    }

    /** Waits for {@code expected}, failing fast with the failed events when the stack settles anywhere else. */
    static void awaitStatus(CloudFormationClient cfn, String stackId, String stackName, String expected)
            throws InterruptedException {
        TestFixtures.await(() -> {
            Stack stack = cfn.describeStacks(r -> r.stackName(stackId)).stacks().get(0);
            String status = stack.stackStatusAsString();
            if (expected.equals(status)) {
                return true;
            }
            if (!status.endsWith("_IN_PROGRESS") && (status.contains("FAILED") || status.contains("ROLLBACK"))) {
                throw new AssertionError(stackName + " reached " + status + " instead of " + expected + ": "
                        + stack.stackStatusReason() + failedEvents(cfn, stackId));
            }
            return false;
        }, stackName, expected, TIMEOUT_NANOS, pollMillis());
    }

    /** Deletes the stack and waits until it is gone; a null {@code stackId} means none was created. */
    static void deleteStack(CloudFormationClient cfn, String stackId, String stackName) throws InterruptedException {
        if (stackId == null) {
            return;
        }
        cfn.deleteStack(r -> r.stackName(stackId));
        TestFixtures.await(() -> {
            try {
                List<Stack> stacks = cfn.describeStacks(r -> r.stackName(stackId)).stacks();
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
        }, stackName, "stack deletion", TIMEOUT_NANOS, pollMillis());
    }

    private static String failedEvents(CloudFormationClient cfn, String stackId) {
        return cfn.describeStackEvents(r -> r.stackName(stackId)).stackEvents().stream()
                .filter(event -> event.resourceStatusAsString().endsWith("_FAILED"))
                .map(event -> event.logicalResourceId() + " " + event.resourceStatusAsString() + ": "
                        + event.resourceStatusReason())
                .collect(Collectors.joining("; ", " [", "]"));
    }

    private static long pollMillis() {
        return TestFixtures.isRealAws() ? 2_000 : 100;
    }
}
