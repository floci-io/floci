package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest;
import software.amazon.awssdk.services.lambda.model.DeleteFunctionRequest;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.Runtime;
import software.amazon.awssdk.services.swf.SwfClient;
import software.amazon.awssdk.services.swf.model.ChildPolicy;
import software.amazon.awssdk.services.swf.model.Decision;
import software.amazon.awssdk.services.swf.model.DecisionType;
import software.amazon.awssdk.services.swf.model.EventType;
import software.amazon.awssdk.services.swf.model.GetWorkflowExecutionHistoryRequest;
import software.amazon.awssdk.services.swf.model.HistoryEvent;
import software.amazon.awssdk.services.swf.model.LambdaFunctionCompletedEventAttributes;
import software.amazon.awssdk.services.swf.model.LambdaFunctionScheduledEventAttributes;
import software.amazon.awssdk.services.swf.model.PollForDecisionTaskRequest;
import software.amazon.awssdk.services.swf.model.PollForDecisionTaskResponse;
import software.amazon.awssdk.services.swf.model.RegisterDomainRequest;
import software.amazon.awssdk.services.swf.model.RegisterWorkflowTypeRequest;
import software.amazon.awssdk.services.swf.model.RespondDecisionTaskCompletedRequest;
import software.amazon.awssdk.services.swf.model.ScheduleLambdaFunctionDecisionAttributes;
import software.amazon.awssdk.services.swf.model.StartWorkflowExecutionRequest;
import software.amazon.awssdk.services.swf.model.TaskList;
import software.amazon.awssdk.services.swf.model.WorkflowExecution;
import software.amazon.awssdk.services.swf.model.WorkflowType;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives SWF's ScheduleLambdaFunction decision through the AWS SDK against a real Lambda
 * function that Floci runs in a container.
 *
 * <p>The point of doing this at the SDK layer is that the generated model has to accept what
 * the emulator returns: the Lambda history events must deserialize into
 * {@code LambdaFunctionScheduled/Started/Completed} with their typed
 * {@code *EventAttributes} members populated, which a hand-rolled HTTP assertion cannot
 * verify.
 */
@DisplayName("SWF Lambda decisions via the AWS SDK")
class SwfLambdaTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime());
    private static final String DOMAIN = "sdk-swf-lambda-" + SUFFIX;
    private static final String FUNCTION = "sdk-swf-fn-" + SUFFIX;
    private static final String TASK_LIST = "sdk-lam-tl-" + SUFFIX;
    private static final String ROLE = "arn:aws:iam::000000000000:role/swf-lambda-role";

    private static SwfClient swf;
    private static LambdaClient lambda;

    @BeforeAll
    static void setUp() throws Exception {
        swf = TestFixtures.swfClient();
        lambda = TestFixtures.lambdaClient();

        lambda.createFunction(CreateFunctionRequest.builder()
                .functionName(FUNCTION)
                .runtime(Runtime.PYTHON3_12)
                .role(ROLE)
                .handler("handler.handler")
                .timeout(30)
                .code(FunctionCode.builder().zipFile(SdkBytes.fromByteArray(handlerZip())).build())
                .build());

        swf.registerDomain(RegisterDomainRequest.builder()
                .name(DOMAIN)
                .workflowExecutionRetentionPeriodInDays("1")
                .build());

        swf.registerWorkflowType(RegisterWorkflowTypeRequest.builder()
                .domain(DOMAIN)
                .name("LambdaWf")
                .version("1.0")
                .defaultTaskList(TaskList.builder().name(TASK_LIST).build())
                .defaultTaskStartToCloseTimeout("300")
                .defaultExecutionStartToCloseTimeout("900")
                .defaultChildPolicy(ChildPolicy.TERMINATE)
                // SWF needs a role to invoke Lambda with; without one the invocation never starts.
                .defaultLambdaRole(ROLE)
                .build());
    }

    @AfterAll
    static void tearDown() {
        try {
            lambda.deleteFunction(DeleteFunctionRequest.builder().functionName(FUNCTION).build());
        } catch (RuntimeException ignored) {
            // Best effort: leaving the function behind must not fail the suite.
        }
    }

    private static byte[] handlerZip() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            zip.putNextEntry(new ZipEntry("handler.py"));
            zip.write(("def handler(event, context):\n"
                    + "    return {'doubled': [n * 2 for n in event.get('numbers', [])]}\n").getBytes());
            zip.closeEntry();
        }
        return baos.toByteArray();
    }

    @Test
    @DisplayName("ScheduleLambdaFunction runs the function and the SDK reads back its result")
    void scheduleLambdaFunction() {
        String runId = swf.startWorkflowExecution(StartWorkflowExecutionRequest.builder()
                        .domain(DOMAIN)
                        .workflowId("sdk-wf-lambda")
                        .workflowType(WorkflowType.builder().name("LambdaWf").version("1.0").build())
                        .build())
                .runId();
        assertNotNull(runId);

        PollForDecisionTaskResponse task = null;
        for (int attempt = 0; attempt < 20 && task == null; attempt++) {
            PollForDecisionTaskResponse candidate = swf.pollForDecisionTask(
                    PollForDecisionTaskRequest.builder()
                            .domain(DOMAIN)
                            .taskList(TaskList.builder().name(TASK_LIST).build())
                            .identity("sdk-decider")
                            .build());
            if (candidate.taskToken() != null && !candidate.taskToken().isEmpty()) {
                task = candidate;
            }
        }
        assertNotNull(task, "no decision task became available");

        swf.respondDecisionTaskCompleted(RespondDecisionTaskCompletedRequest.builder()
                .taskToken(task.taskToken())
                .decisions(Decision.builder()
                        .decisionType(DecisionType.SCHEDULE_LAMBDA_FUNCTION)
                        .scheduleLambdaFunctionDecisionAttributes(
                                ScheduleLambdaFunctionDecisionAttributes.builder()
                                        .id("sdk-lam-1")
                                        .name(FUNCTION)
                                        .control("sdk-ctl")
                                        .input("{\"numbers\": [1, 2, 3]}")
                                        .startToCloseTimeout("30")
                                        .build())
                        .build())
                .build());

        List<HistoryEvent> events = swf.getWorkflowExecutionHistory(
                        GetWorkflowExecutionHistoryRequest.builder()
                                .domain(DOMAIN)
                                .execution(WorkflowExecution.builder()
                                        .workflowId("sdk-wf-lambda").runId(runId).build())
                                .build())
                .events();

        HistoryEvent scheduled = eventOf(events, EventType.LAMBDA_FUNCTION_SCHEDULED);
        HistoryEvent started = eventOf(events, EventType.LAMBDA_FUNCTION_STARTED);
        HistoryEvent completed = eventOf(events, EventType.LAMBDA_FUNCTION_COMPLETED);

        // The generated model must populate the typed attribute members.
        LambdaFunctionScheduledEventAttributes scheduledAttrs =
                scheduled.lambdaFunctionScheduledEventAttributes();
        assertEquals("sdk-lam-1", scheduledAttrs.id());
        assertEquals(FUNCTION, scheduledAttrs.name());
        assertEquals("sdk-ctl", scheduledAttrs.control());
        assertEquals("30", scheduledAttrs.startToCloseTimeout());
        assertEquals(scheduled.eventId(),
                started.lambdaFunctionStartedEventAttributes().scheduledEventId());

        LambdaFunctionCompletedEventAttributes completedAttrs =
                completed.lambdaFunctionCompletedEventAttributes();
        assertEquals(scheduled.eventId(), completedAttrs.scheduledEventId());
        assertEquals(started.eventId(), completedAttrs.startedEventId());

        // Proof the container actually executed: the handler doubled the decider's input.
        assertTrue(completedAttrs.result().contains("2")
                        && completedAttrs.result().contains("4")
                        && completedAttrs.result().contains("6"),
                "expected the handler to double [1,2,3], got: " + completedAttrs.result());

        assertEquals(EventType.DECISION_TASK_SCHEDULED, events.get(events.size() - 1).eventType());
    }

    private static HistoryEvent eventOf(List<HistoryEvent> events, EventType type) {
        return events.stream()
                .filter(e -> type.equals(e.eventType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " in "
                        + events.stream().map(HistoryEvent::eventType).toList()));
    }
}
