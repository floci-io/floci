package io.github.hectorvent.floci.services.cloudformation;

import io.restassured.path.xml.XmlPath;
import io.restassured.response.Response;

import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * DeleteStack answers before the stack is gone. The deletion runs on an executor, so a test
 * that looks at the stack's resources right after the answer races it.
 */
final class CfnStackWaits {

    private CfnStackWaits() {
    }

    /** What DescribeStacks reported: the status and, when present, the StackStatusReason. */
    record StackState(String status, String reason) {
    }

    private static final Set<String> TERMINAL = Set.of(
            "CREATE_COMPLETE", "CREATE_FAILED", "ROLLBACK_COMPLETE", "ROLLBACK_FAILED",
            "UPDATE_COMPLETE", "UPDATE_FAILED", "UPDATE_ROLLBACK_COMPLETE", "UPDATE_ROLLBACK_FAILED",
            "DELETE_COMPLETE", "DELETE_FAILED");

    /**
     * Waits until DescribeStacks reports a terminal status for a create or update and returns
     * it, so the caller decides what counts as success. A DescribeStacks that is not 200 (the
     * stack does not exist) fails at once with the error body rather than polling the clock out;
     * ten seconds without a terminal status fails too.
     */
    static StackState awaitTerminal(String stackName) {
        long deadline = System.currentTimeMillis() + 10_000;
        StackState last = new StackState(null, "");
        while (System.currentTimeMillis() < deadline) {
            Response response = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName)
            .when()
                .post("/");
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
     * Waits until DescribeStacks answers 400, which is how a missing stack is reported. Fails on
     * DELETE_FAILED or after ten seconds.
     */
    static void awaitStackDeleted(String stackName) {
        long deadline = System.currentTimeMillis() + 10_000;
        String body = "";
        while (System.currentTimeMillis() < deadline) {
            Response response = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName)
            .when()
                .post("/");
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
