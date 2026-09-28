package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A stack being deleted moves from the live map to the retained-deleted one. Handing it over in
 * the wrong order leaves a window in which it is in neither, and a ListStacks landing there loses
 * a stack that both exists and is retained.
 */
@QuarkusTest
@TestProfile(CloudFormationDeletedStackListingRaceTest.LongRetentionProfile.class)
class CloudFormationDeletedStackListingRaceTest {

    /**
     * Retention long enough that it cannot end this test. The shipped 30 seconds are measured on
     * the test {@code MutableClock}, which advances a millisecond on every read, and the pollers
     * below read it thousands of times: left at the default they expire the retained record
     * themselves and lose the stack for a reason that has nothing to do with the handoff.
     */
    public static final class LongRetentionProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.cloudformation.deleted-stack-retention-seconds", "86400");
        }
    }

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    /**
     * The window is one map removal plus a storage delete wide, so a single reader on a single
     * delete rarely lands in it. Several readers across many deletes make it dependable: on the
     * unfixed ordering this fails on every run, and it still takes only a few seconds.
     */
    private static final int ATTEMPTS = 60;
    private static final int POLLERS = 4;
    /** Generous: a poller checks the stop flag every pass, so overrunning this means it is stuck. */
    private static final long POLLER_SHUTDOWN_MILLIS = 10_000;

    @Inject
    CloudFormationService cloudFormationService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listStacks_whileADeleteHandsTheStackOver_neverLosesIt() throws Exception {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            String stackName = "list-race-stack-" + attempt;
            String stackId = createStack(stackName, attempt);

            AtomicBoolean stop = new AtomicBoolean();
            AtomicReference<String> lost = new AtomicReference<>();
            List<Thread> pollers = new ArrayList<>();
            for (int p = 0; p < POLLERS; p++) {
                Thread poller = new Thread(() -> {
                    while (!stop.get() && !Thread.currentThread().isInterrupted()) {
                        Stack listed = RequestScopes.callAs(ACCOUNT, () ->
                                cloudFormationService.listStacks(REGION).stream()
                                        .filter(s -> stackId.equals(s.getStackId()))
                                        .findFirst()
                                        .orElse(null));
                        if (listed == null) {
                            lost.compareAndSet(null, "ListStacks lost " + stackId + " mid-delete");
                            return;
                        }
                    }
                }, "list-race-poller-" + p);
                // Daemon as a backstop: a poller left running by some future edit that throws
                // where nothing throws today must not be able to hold the test JVM open.
                poller.setDaemon(true);
                pollers.add(poller);
                poller.start();
            }

            List<String> stillRunning = new ArrayList<>();
            try {
                deleteStack(stackName);
                awaitDeleted(stackId);
            } finally {
                // In a finally so a failing delete stops the pollers on its way out rather than
                // leaving them spinning, which would bury the failure that caused it.
                stop.set(true);
                for (Thread poller : pollers) {
                    poller.join(POLLER_SHUTDOWN_MILLIS);
                    if (poller.isAlive()) {
                        poller.interrupt();
                        stillRunning.add(poller.getName());
                    }
                }
            }

            // After the try, never inside the finally: an assertion there would replace whatever
            // the delete threw with its own failure.
            assertTrue(stillRunning.isEmpty(), "pollers did not stop: " + stillRunning);
            assertNull(lost.get(), lost.get());
        }
    }

    private String createStack(String stackName, int attempt) {
        String template = """
            {
              "Resources": {
                "Q1": { "Type": "AWS::SQS::Queue", "Properties": { "QueueName": "list-race-q1-%1$d" } },
                "Q2": { "Type": "AWS::SQS::Queue", "Properties": { "QueueName": "list-race-q2-%1$d" } },
                "T1": { "Type": "AWS::SNS::Topic", "Properties": { "TopicName": "list-race-t1-%1$d" } }
              }
            }
            """.formatted(attempt);

        String response = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"))
            .extract().asString();

        return response.substring(response.indexOf("<StackId>") + "<StackId>".length(),
                response.indexOf("</StackId>"));
    }

    private void deleteStack(String stackName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private void awaitDeleted(String stackId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String body = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackId)
            .when()
                .post("/")
            .then()
                .extract().asString();
            if (body.contains("<StackStatus>DELETE_COMPLETE</StackStatus>")) {
                return;
            }
            Thread.sleep(2);
        }
        fail("stack " + stackId + " never reached DELETE_COMPLETE");
    }
}
