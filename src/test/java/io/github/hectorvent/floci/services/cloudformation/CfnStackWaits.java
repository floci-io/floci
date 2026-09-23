package io.github.hectorvent.floci.services.cloudformation;

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

    /**
     * Waits until DescribeStacks no longer knows the stack. Fails on DELETE_FAILED or after ten
     * seconds.
     */
    static void awaitStackDeleted(String stackName) {
        long deadline = System.currentTimeMillis() + 10_000;
        String body = "";
        while (System.currentTimeMillis() < deadline) {
            body = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName)
            .when()
                .post("/")
            .then()
                .extract().body().asString();
            assertThat(body, not(containsString("<StackStatus>DELETE_FAILED</StackStatus>")));
            if (body.contains("Stack with id " + stackName + " does not exist")) {
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
