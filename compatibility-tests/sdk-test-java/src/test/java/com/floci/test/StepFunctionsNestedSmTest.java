package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Compatibility tests for nested state machine execution via optimised integrations:
 *   arn:aws:states:::states:startExecution          (fire-and-forget)
 *   arn:aws:states:::states:startExecution.sync     (wait, return full execution envelope)
 *   arn:aws:states:::states:startExecution.sync:2   (wait, return only child output)
 *
 * Covers Issue #254.
 */
@DisplayName("SFN Nested State Machine Execution")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StepFunctionsNestedSmTest {

    private static final String ROLE_ARN = System.getenv("SFN_ROLE_ARN") != null
            ? System.getenv("SFN_ROLE_ARN")
            : "arn:aws:iam::000000000000:role/service-role/test-role";

    private static SfnClient sfn;
    private static String childSmArn;
    private static String suffix;

    @BeforeAll
    static void setup() {
        sfn = TestFixtures.sfnClient();
        suffix = TestFixtures.uniqueName("nested");

        // Child SM: Pass state returning a fixed result
        String childDef = """
                {
                  "StartAt": "AddResult",
                  "States": {
                    "AddResult": {
                      "Type": "Pass",
                      "Result": {"computed": true, "value": 42},
                      "End": true
                    }
                  }
                }
                """;
        childSmArn = sfn.createStateMachine(b -> b
                .name("child-sm-" + suffix)
                .definition(childDef)
                .roleArn(ROLE_ARN)).stateMachineArn();
    }

    @AfterAll
    static void cleanup() {
        if (sfn != null) {
            try { sfn.deleteStateMachine(b -> b.stateMachineArn(childSmArn)); } catch (Exception ignored) {}
            sfn.close();
        }
    }

    // ──────────────────────────── .sync:2 ────────────────────────────

    @Test
    @Order(1)
    void sync2_parentReceivesChildOutputDirectly() throws InterruptedException {
        String parentDef = """
                {
                  "StartAt": "InvokeChild",
                  "States": {
                    "InvokeChild": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::states:startExecution.sync:2",
                      "Parameters": {
                        "StateMachineArn": "%s",
                        "Input": {"trigger": "sync2"}
                      },
                      "End": true
                    }
                  }
                }
                """.formatted(childSmArn);

        String parentSmArn = sfn.createStateMachine(b -> b
                .name("parent-sync2-" + suffix)
                .definition(parentDef)
                .roleArn(ROLE_ARN)).stateMachineArn();

        try {
            String execArn = sfn.startExecution(b -> b
                    .stateMachineArn(parentSmArn)
                    .input("{}")).executionArn();

            DescribeExecutionResponse result = pollUntilDone(execArn);

            assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
            // Output must be the child's parsed output object, not an envelope
            assertThat(result.output())
                    .contains("\"computed\":true")
                    .contains("\"value\":42")
                    .doesNotContain("executionArn");
        } finally {
            try { sfn.deleteStateMachine(b -> b.stateMachineArn(parentSmArn)); } catch (Exception ignored) {}
        }
    }

    // ──────────────────────────── .sync ────────────────────────────

    @Test
    @Order(2)
    void sync_parentReceivesFullExecutionEnvelope() throws InterruptedException {
        String parentDef = """
                {
                  "StartAt": "InvokeChild",
                  "States": {
                    "InvokeChild": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::states:startExecution.sync",
                      "Parameters": {
                        "StateMachineArn": "%s",
                        "Input": {"trigger": "sync"}
                      },
                      "End": true
                    }
                  }
                }
                """.formatted(childSmArn);

        String parentSmArn = sfn.createStateMachine(b -> b
                .name("parent-sync-" + suffix)
                .definition(parentDef)
                .roleArn(ROLE_ARN)).stateMachineArn();

        try {
            String execArn = sfn.startExecution(b -> b
                    .stateMachineArn(parentSmArn)
                    .input("{}")).executionArn();

            DescribeExecutionResponse result = pollUntilDone(execArn);

            assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
            // Output must be the full envelope: executionArn, status, output (as a string), etc.
            assertThat(result.output())
                    .contains("executionArn")
                    .contains("\"SUCCEEDED\"")
                    .contains("\"output\"");
        } finally {
            try { sfn.deleteStateMachine(b -> b.stateMachineArn(parentSmArn)); } catch (Exception ignored) {}
        }
    }

    // ──────────────────────────── fire-and-forget ────────────────────────────

    @Test
    @Order(3)
    void fireAndForget_parentReceivesExecutionArnAndStartDate() throws InterruptedException {
        String parentDef = """
                {
                  "StartAt": "InvokeChild",
                  "States": {
                    "InvokeChild": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::states:startExecution",
                      "Parameters": {
                        "StateMachineArn": "%s",
                        "Input": {"trigger": "fnf"}
                      },
                      "End": true
                    }
                  }
                }
                """.formatted(childSmArn);

        String parentSmArn = sfn.createStateMachine(b -> b
                .name("parent-fnf-" + suffix)
                .definition(parentDef)
                .roleArn(ROLE_ARN)).stateMachineArn();

        try {
            String execArn = sfn.startExecution(b -> b
                    .stateMachineArn(parentSmArn)
                    .input("{}")).executionArn();

            DescribeExecutionResponse result = pollUntilDone(execArn);

            assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
            // Output must be { executionArn, startDate } — no child output
            assertThat(result.output())
                    .contains("executionArn")
                    .contains("startDate")
                    .doesNotContain("\"SUCCEEDED\"");
        } finally {
            try { sfn.deleteStateMachine(b -> b.stateMachineArn(parentSmArn)); } catch (Exception ignored) {}
        }
    }

    // ──────────────────────────── helper ────────────────────────────

    private DescribeExecutionResponse pollUntilDone(String execArn) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            DescribeExecutionResponse resp = sfn.describeExecution(b -> b.executionArn(execArn));
            if (resp.status() != ExecutionStatus.RUNNING) {
                return resp;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Execution did not complete within 30s: " + execArn);
    }
}
