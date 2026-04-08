package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Compatibility tests for SFN Activity APIs and waitForTaskToken mechanics.
 *
 * Part A — Activity CRUD:
 *   CreateActivity, DescribeActivity, ListActivities, DeleteActivity
 *
 * Part B — Activity task roundtrip:
 *   GetActivityTask (long-poll) unblocks once the SM enters the activity task state,
 *   SendTaskSuccess resumes the execution.
 *
 * Part C — waitForTaskToken:
 *   $$.Task.Token is resolved in Parameters before the activity is invoked,
 *   SendTaskSuccess with that token unblocks the execution.
 *
 * Covers Issue #91.
 */
@DisplayName("SFN Activities and waitForTaskToken")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StepFunctionsActivityTest {

    private static final String ROLE_ARN = System.getenv("SFN_ROLE_ARN") != null
            ? System.getenv("SFN_ROLE_ARN")
            : "arn:aws:iam::000000000000:role/service-role/test-role";

    private static SfnClient sfn;
    private static String activityArn;
    private static String activityName;

    @BeforeAll
    static void setup() {
        sfn = TestFixtures.sfnClient();
        activityName = TestFixtures.uniqueName("activity");
    }

    @AfterAll
    static void cleanup() {
        if (sfn != null) {
            if (activityArn != null) {
                try { sfn.deleteActivity(b -> b.activityArn(activityArn)); } catch (Exception ignored) {}
            }
            sfn.close();
        }
    }

    // ──────────────────────────── Part A: Activity CRUD ────────────────────────────

    @Test
    @Order(1)
    void createActivity_returnsArnAndCreationDate() {
        CreateActivityResponse resp = sfn.createActivity(b -> b.name(activityName));
        activityArn = resp.activityArn();

        assertThat(activityArn).isNotNull().contains(activityName);
        assertThat(resp.creationDate()).isNotNull();
    }

    @Test
    @Order(2)
    void describeActivity_returnsCorrectFields() {
        DescribeActivityResponse resp = sfn.describeActivity(b -> b.activityArn(activityArn));

        assertThat(resp.activityArn()).isEqualTo(activityArn);
        assertThat(resp.name()).isEqualTo(activityName);
        assertThat(resp.creationDate()).isNotNull();
    }

    @Test
    @Order(3)
    void listActivities_containsCreatedActivity() {
        ListActivitiesResponse resp = sfn.listActivities();

        assertThat(resp.activities())
                .anyMatch(a -> activityArn.equals(a.activityArn()) && activityName.equals(a.name()));
    }

    @Test
    @Order(4)
    void deleteActivity_removesItFromList() {
        sfn.deleteActivity(b -> b.activityArn(activityArn));
        activityArn = null; // prevent @AfterAll from double-deleting

        ListActivitiesResponse resp = sfn.listActivities();
        assertThat(resp.activities())
                .noneMatch(a -> activityName.equals(a.name()));
    }

    // ──────────────────────────── Part B: Activity task roundtrip ────────────────────────────

    /**
     * Full roundtrip:
     * 1. Create a fresh activity and a SM with that activity as its task resource.
     * 2. Start execution (async).
     * 3. A background worker calls GetActivityTask (long-poll), then SendTaskSuccess.
     * 4. Verify execution completes SUCCEEDED with the expected output.
     */
    @Test
    @Order(5)
    void activityTaskRoundtrip_executionSucceedsWithWorkerOutput() throws Exception {
        String name = TestFixtures.uniqueName("roundtrip");
        String arn = sfn.createActivity(b -> b.name(name)).activityArn();

        String smDef = """
                {
                  "StartAt": "DoWork",
                  "States": {
                    "DoWork": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true
                    }
                  }
                }
                """.formatted(arn);

        String smArn = sfn.createStateMachine(b -> b
                .name(TestFixtures.uniqueName("roundtrip-sm"))
                .definition(smDef)
                .roleArn(ROLE_ARN)).stateMachineArn();

        try {
            String execArn = sfn.startExecution(b -> b
                    .stateMachineArn(smArn)
                    .input("{\"job\":\"process\"}")).executionArn();

            // Background worker: poll for task, then report success
            CompletableFuture<Void> worker = CompletableFuture.runAsync(() -> {
                GetActivityTaskResponse task = sfn.getActivityTask(b -> b.activityArn(arn));
                assertThat(task.taskToken()).isNotEmpty();
                sfn.sendTaskSuccess(b -> b
                        .taskToken(task.taskToken())
                        .output("{\"result\":\"done\"}"));
            });

            worker.get(90, TimeUnit.SECONDS);

            DescribeExecutionResponse result = pollUntilDone(execArn);
            assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
            assertThat(result.output()).contains("\"result\":\"done\"");
        } finally {
            try { sfn.deleteStateMachine(b -> b.stateMachineArn(smArn)); } catch (Exception ignored) {}
            try { sfn.deleteActivity(b -> b.activityArn(arn)); } catch (Exception ignored) {}
        }
    }

    // ──────────────────────────── Part C: waitForTaskToken ────────────────────────────

    /**
     * waitForTaskToken with an activity resource:
     * - $$.Task.Token is injected into Parameters before the activity task is enqueued.
     * - The worker receives the token via GetActivityTask input, then calls SendTaskSuccess.
     * - Execution output equals the SendTaskSuccess output.
     */
    @Test
    @Order(6)
    void waitForTaskToken_contextTokenInjectedAndExecutionResumes() throws Exception {
        String name = TestFixtures.uniqueName("wftt");
        String arn = sfn.createActivity(b -> b.name(name)).activityArn();

        String smDef = """
                {
                  "StartAt": "PauseAndWait",
                  "States": {
                    "PauseAndWait": {
                      "Type": "Task",
                      "Resource": "%s.waitForTaskToken",
                      "Parameters": {
                        "token.$": "$$.Task.Token",
                        "payload.$": "$.data"
                      },
                      "End": true
                    }
                  }
                }
                """.formatted(arn);

        String smArn = sfn.createStateMachine(b -> b
                .name(TestFixtures.uniqueName("wftt-sm"))
                .definition(smDef)
                .roleArn(ROLE_ARN)).stateMachineArn();

        try {
            String execArn = sfn.startExecution(b -> b
                    .stateMachineArn(smArn)
                    .input("{\"data\":\"hello\"}")).executionArn();

            CompletableFuture<Void> worker = CompletableFuture.runAsync(() -> {
                // GetActivityTask receives { "token": "<uuid>", "payload": "hello" }
                GetActivityTaskResponse task = sfn.getActivityTask(b -> b.activityArn(arn));
                assertThat(task.taskToken()).isNotEmpty();
                // The activity input must contain the injected token and payload fields
                assertThat(task.input()).contains("\"token\"").contains("\"payload\"");

                sfn.sendTaskSuccess(b -> b
                        .taskToken(task.taskToken())
                        .output("{\"processed\":true}"));
            });

            worker.get(90, TimeUnit.SECONDS);

            DescribeExecutionResponse result = pollUntilDone(execArn);
            assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
            assertThat(result.output()).contains("\"processed\":true");
        } finally {
            try { sfn.deleteStateMachine(b -> b.stateMachineArn(smArn)); } catch (Exception ignored) {}
            try { sfn.deleteActivity(b -> b.activityArn(arn)); } catch (Exception ignored) {}
        }
    }

    // ──────────────────────────── helper ────────────────────────────

    private DescribeExecutionResponse pollUntilDone(String execArn) throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            DescribeExecutionResponse resp = sfn.describeExecution(b -> b.executionArn(execArn));
            if (resp.status() != ExecutionStatus.RUNNING) {
                return resp;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Execution did not complete within 60s: " + execArn);
    }
}
