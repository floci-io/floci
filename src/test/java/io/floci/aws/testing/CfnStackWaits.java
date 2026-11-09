package io.floci.aws.testing;

import io.restassured.path.xml.XmlPath;
import io.restassured.path.xml.element.Node;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.Stack;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * DeleteStack answers before the stack is gone. The deletion runs on an executor, so a test
 * that looks at the stack's resources right after the answer races it.
 */
public final class CfnStackWaits {

    private CfnStackWaits() {
    }

    /** What DescribeStacks reported: the status and, when present, the StackStatusReason. */
    public record StackState(String status, String reason) {
    }

    private static final Set<String> TERMINAL = Set.of(
            "CREATE_COMPLETE", "CREATE_FAILED", "ROLLBACK_COMPLETE", "ROLLBACK_FAILED",
            "UPDATE_COMPLETE", "UPDATE_FAILED", "UPDATE_ROLLBACK_COMPLETE", "UPDATE_ROLLBACK_FAILED",
            "DELETE_COMPLETE", "DELETE_FAILED");

    private static final Set<String> OPERATION_STARTS = Set.of("CREATE_IN_PROGRESS", "UPDATE_IN_PROGRESS");

    /**
     * Waits until DescribeStacks reports a terminal status for a create or update and returns
     * it, so the caller decides what counts as success. A DescribeStacks that is not 200 (the
     * stack does not exist) fails at once with the error body rather than polling the clock out;
     * ten seconds without a terminal status fails too.
     */
    public static StackState awaitTerminal(String stackName) {
        return awaitTerminal(stackName, null);
    }

    public static StackState awaitTerminal(String stackName, String authorization) {
        long deadline = System.currentTimeMillis() + 10_000;
        StackState last = new StackState(null, "");
        while (System.currentTimeMillis() < deadline) {
            RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName);
            if (authorization != null) {
                request.header("Authorization", authorization);
            }
            Response response = request.when().post("/");
            if (response.statusCode() != 200) {
                fail("DescribeStacks " + stackName + " answered " + response.statusCode() + ": " + response.asString());
            }
            XmlPath xml = response.xmlPath();
            String status = xml.getString("**.find { it.name() == 'StackStatus' }");
            String reason = xml.getString("**.find { it.name() == 'StackStatusReason' }");
            last = new StackState(status == null || status.isEmpty() ? null : status, reason == null ? "" : reason);
            if (last.status() != null && TERMINAL.contains(last.status())) {
                return last;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for stack " + stackName, e);
            }
        }
        fail("Stack " + stackName + " reached no terminal status within ten seconds, last: " + last);
        return last;
    }

    /**
     * Waits until DescribeStacks reports {@code expectedStatus} and returns that state. Any other
     * terminal status fails at once with its reason, as do a DescribeStacks that is not 200 and ten
     * seconds without the expected status.
     */
    public static StackState awaitStatus(String stackName, String expectedStatus) {
        return awaitStatus(stackName, expectedStatus, null);
    }

    public static StackState awaitStatus(String stackName, String expectedStatus, String authorization) {
        long deadline = System.currentTimeMillis() + 10_000;
        StackState last = new StackState(null, "");
        while (System.currentTimeMillis() < deadline) {
            RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName);
            if (authorization != null) {
                request.header("Authorization", authorization);
            }
            Response response = request.when().post("/");
            if (response.statusCode() != 200) {
                fail("DescribeStacks " + stackName + " answered " + response.statusCode() + ": " + response.asString());
            }
            XmlPath xml = response.xmlPath();
            String status = xml.getString("**.find { it.name() == 'StackStatus' }");
            String reason = xml.getString("**.find { it.name() == 'StackStatusReason' }");
            last = new StackState(status == null || status.isEmpty() ? null : status, reason == null ? "" : reason);
            if (hasExpectedStatus(stackName, expectedStatus, last)) {
                return last;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for stack " + stackName, e);
            }
        }
        fail("Stack " + stackName + " did not reach " + expectedStatus + " within ten seconds, last: " + last);
        return last;
    }

    /** SDK counterpart of {@link #awaitStatus(String, String)}, retaining the complete stack response. */
    public static Stack awaitStatus(CloudFormationClient client, String stackName, String expectedStatus) {
        DescribeStacksRequest request = DescribeStacksRequest.builder().stackName(stackName).build();
        return await("Stack " + stackName + " to reach " + expectedStatus)
                .atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).until(() -> {
                    List<Stack> stacks = client.describeStacks(request).stacks();
                    if (stacks.isEmpty()) {
                        fail("DescribeStacks " + stackName + " returned no stacks");
                    }
                    return stacks.getFirst();
                }, stack -> hasExpectedStatus(stackName, expectedStatus,
                        new StackState(stack.stackStatusAsString(),
                                stack.stackStatusReason() == null ? "" : stack.stackStatusReason())));
    }

    /** Creates {@code stackName} from {@code template} through the SDK and waits for {@code expectedStatus}. */
    public static Stack createStack(CloudFormationClient client, String stackName, String template,
                                    String expectedStatus) {
        client.createStack(request -> request.stackName(stackName).templateBody(template));
        return awaitStatus(client, stackName, expectedStatus);
    }

    /** Updates {@code stackName} to {@code template} through the SDK and waits for {@code expectedStatus}. */
    public static Stack updateStack(CloudFormationClient client, String stackName, String template,
                                    String expectedStatus) {
        client.updateStack(request -> request.stackName(stackName).templateBody(template));
        return awaitStatus(client, stackName, expectedStatus);
    }

    /**
     * A resource to append to a template's {@code Resources}, after the one named {@code logicalId}:
     * an instance that names a launch template that does not exist, so it fails once that resource
     * is done, and an update that changed it rolls back.
     */
    public static String failingResourceAfter(String logicalId) {
        return """
                ,
                  "Later": {"Type": "AWS::EC2::Instance", "DependsOn": "%s", "Properties": {"LaunchTemplate": {
                    "LaunchTemplateId": "lt-0123456789abcdef0", "Version": "1"}}}""".formatted(logicalId);
    }

    /** The value of {@code stack}'s output {@code key}; fails when the stack has no such output. */
    public static String output(Stack stack, String key) {
        return stack.outputs().stream().filter(output -> key.equals(output.outputKey())).findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Stack " + stack.stackName() + " has no output " + key + ": " + stack.outputs()))
                .outputValue();
    }

    private static boolean hasExpectedStatus(String stackName, String expectedStatus, StackState state) {
        if (expectedStatus.equals(state.status())) {
            return true;
        }
        if (state.status() != null && TERMINAL.contains(state.status())) {
            fail("Stack " + stackName + " reached " + state.status() + " instead of " + expectedStatus
                    + ": " + state.reason());
        }
        return false;
    }

    /**
     * The reason on the first resource event that failed a create or update in the stack's latest
     * operation, the one its last stack-level CREATE_IN_PROGRESS or UPDATE_IN_PROGRESS began. The
     * stack's own reason only names the failed resource, as on AWS, so the failure's message is read
     * here. Fails when DescribeStackEvents is not 200 or no resource failed in that operation.
     */
    public static String failedResourceReason(String stackName) {
        return failedResourceReason(stackName, null);
    }

    public static String failedResourceReason(String stackName, String authorization) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stackName);
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        Response response = request.when().post("/");
        if (response.statusCode() != 200) {
            fail("DescribeStackEvents " + stackName + " answered " + response.statusCode() + ": " + response.asString());
        }
        List<Node> newestFirst = response.xmlPath().getList("**.findAll { it.name() == 'member' }", Node.class);
        String reason = null;
        for (Node event : newestFirst) {
            String status = text(event, "ResourceStatus");
            if (text(event, "StackName").equals(text(event, "LogicalResourceId"))
                    && text(event, "StackId").equals(text(event, "PhysicalResourceId"))) {
                if (OPERATION_STARTS.contains(status)) {
                    break;
                }
            } else if ("CREATE_FAILED".equals(status) || "UPDATE_FAILED".equals(status)) {
                reason = text(event, "ResourceStatusReason");
            }
        }
        if (reason == null) {
            fail("Stack " + stackName + " has no failed resource event in its latest operation: " + response.asString());
        }
        return reason;
    }

    private static String text(Node event, String name) {
        Node child = event.getNode(name);
        return child == null ? "" : child.value();
    }

    public static void deleteStack(String stackName) {
        deleteStack(stackName, null);
    }

    public static void deleteStack(String stackName, String authorization) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName);
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        request.when().post("/").then().statusCode(200);
        awaitStackDeleted(stackName, authorization);
    }

    /**
     * Sends ContinueUpdateRollback and checks it was accepted; the caller waits for the outcome.
     * {@code authorization} may be null, and each of {@code resourcesToSkip} is sent as a
     * ResourcesToSkip member.
     */
    public static void continueUpdateRollback(String stackName, String authorization, String... resourcesToSkip) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ContinueUpdateRollback")
            .formParam("StackName", stackName);
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        for (int i = 0; i < resourcesToSkip.length; i++) {
            request.formParam("ResourcesToSkip.member." + (i + 1), resourcesToSkip[i]);
        }
        request.when().post("/").then().statusCode(200);
    }

    /**
     * Waits until DescribeStacks answers 400, which is how a missing stack is reported. Fails on
     * DELETE_FAILED or after ten seconds.
     */
    public static void awaitStackDeleted(String stackName) {
        awaitStackDeleted(stackName, null);
    }

    public static void awaitStackDeleted(String stackName, String authorization) {
        long deadline = System.currentTimeMillis() + 10_000;
        String body = "";
        while (System.currentTimeMillis() < deadline) {
            RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName);
            if (authorization != null) {
                request.header("Authorization", authorization);
            }
            Response response = request.when().post("/");
            body = response.asString();
            assertThat(body, not(containsString("<StackStatus>DELETE_FAILED</StackStatus>")));
            if (response.statusCode() == 400) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for stack " + stackName + " to be deleted", e);
            }
        }
        fail("Stack " + stackName + " was not deleted within ten seconds: " + body);
    }
}
