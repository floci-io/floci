package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.swf.SwfClient;
import software.amazon.awssdk.services.swf.model.ActivityTaskCompletedEventAttributes;
import software.amazon.awssdk.services.swf.model.ActivityType;
import software.amazon.awssdk.services.swf.model.ChildPolicy;
import software.amazon.awssdk.services.swf.model.CloseStatus;
import software.amazon.awssdk.services.swf.model.Decision;
import software.amazon.awssdk.services.swf.model.DecisionType;
import software.amazon.awssdk.services.swf.model.DescribeDomainRequest;
import software.amazon.awssdk.services.swf.model.DescribeDomainResponse;
import software.amazon.awssdk.services.swf.model.DescribeWorkflowExecutionRequest;
import software.amazon.awssdk.services.swf.model.DescribeWorkflowExecutionResponse;
import software.amazon.awssdk.services.swf.model.DescribeWorkflowTypeRequest;
import software.amazon.awssdk.services.swf.model.DescribeWorkflowTypeResponse;
import software.amazon.awssdk.services.swf.model.DomainAlreadyExistsException;
import software.amazon.awssdk.services.swf.model.EventType;
import software.amazon.awssdk.services.swf.model.ExecutionStatus;
import software.amazon.awssdk.services.swf.model.GetWorkflowExecutionHistoryRequest;
import software.amazon.awssdk.services.swf.model.GetWorkflowExecutionHistoryResponse;
import software.amazon.awssdk.services.swf.model.PollForActivityTaskResponse;
import software.amazon.awssdk.services.swf.model.PollForDecisionTaskResponse;
import software.amazon.awssdk.services.swf.model.HistoryEvent;
import software.amazon.awssdk.services.swf.model.ListDomainsRequest;
import software.amazon.awssdk.services.swf.model.PollForActivityTaskRequest;
import software.amazon.awssdk.services.swf.model.PollForDecisionTaskRequest;
import software.amazon.awssdk.services.swf.model.RegisterActivityTypeRequest;
import software.amazon.awssdk.services.swf.model.RegisterDomainRequest;
import software.amazon.awssdk.services.swf.model.RegisterWorkflowTypeRequest;
import software.amazon.awssdk.services.swf.model.RegistrationStatus;
import software.amazon.awssdk.services.swf.model.RespondActivityTaskCompletedRequest;
import software.amazon.awssdk.services.swf.model.RespondDecisionTaskCompletedRequest;
import software.amazon.awssdk.services.swf.model.ScheduleActivityTaskDecisionAttributes;
import software.amazon.awssdk.services.swf.model.SignalWorkflowExecutionRequest;
import software.amazon.awssdk.services.swf.model.StartWorkflowExecutionRequest;
import software.amazon.awssdk.services.swf.model.TaskList;
import software.amazon.awssdk.services.swf.model.TerminateWorkflowExecutionRequest;
import software.amazon.awssdk.services.swf.model.TypeAlreadyExistsException;
import software.amazon.awssdk.services.swf.model.UnknownResourceException;
import software.amazon.awssdk.services.swf.model.WorkflowExecution;
import software.amazon.awssdk.services.swf.model.WorkflowExecutionAlreadyStartedException;
import software.amazon.awssdk.services.swf.model.WorkflowType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the SWF emulation with the real AWS SDK v2 client.
 *
 * <p>This is the check the raw-HTTP tests cannot make: the SDK must deserialize every
 * response into its generated model (epoch-second timestamps into {@code Instant},
 * event attributes into the right {@code *EventAttributes} member) and map each fault
 * onto its typed exception class. A response that is merely "valid JSON" fails here.
 */
@DisplayName("SWF workflow lifecycle")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SwfTest {

    private static final String DOMAIN = "sdk-swf-" + System.currentTimeMillis();
    private static final String TASK_LIST = "sdk-swf-tl";
    private static final WorkflowType WORKFLOW_TYPE =
            WorkflowType.builder().name("SdkWorkflow").version("1.0").build();
    private static final ActivityType ACTIVITY_TYPE =
            ActivityType.builder().name("SdkActivity").version("1.0").build();

    private static SwfClient swf;
    private static String runId;

    @BeforeAll
    static void setup() {
        swf = TestFixtures.swfClient();
    }

    @AfterAll
    static void cleanup() {
        if (swf != null) {
            swf.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Register domain and describe it")
    void registerDomain() {
        swf.registerDomain(RegisterDomainRequest.builder()
                .name(DOMAIN)
                .description("sdk compatibility domain")
                .workflowExecutionRetentionPeriodInDays("7")
                .build());

        DescribeDomainResponse described = swf.describeDomain(
                DescribeDomainRequest.builder().name(DOMAIN).build());

        assertThat(described.domainInfo().name()).isEqualTo(DOMAIN);
        assertThat(described.domainInfo().status()).isEqualTo(RegistrationStatus.REGISTERED);
        assertThat(described.domainInfo().arn()).contains(":/domain/" + DOMAIN);
        assertThat(described.configuration().workflowExecutionRetentionPeriodInDays()).isEqualTo("7");

        assertThat(swf.listDomains(ListDomainsRequest.builder()
                        .registrationStatus(RegistrationStatus.REGISTERED).build())
                .domainInfos())
                .anyMatch(info -> DOMAIN.equals(info.name()));
    }

    @Test
    @Order(2)
    @DisplayName("Duplicate domain raises DomainAlreadyExistsException")
    void duplicateDomainIsTyped() {
        // The fault must arrive with the namespaced __type SWF uses, or the SDK falls back
        // to a generic SwfException and this assertion fails.
        assertThatThrownBy(() -> swf.registerDomain(RegisterDomainRequest.builder()
                .name(DOMAIN)
                .workflowExecutionRetentionPeriodInDays("7")
                .build()))
                .isInstanceOf(DomainAlreadyExistsException.class);
    }

    @Test
    @Order(3)
    @DisplayName("Unknown domain raises UnknownResourceException")
    void unknownDomainIsTyped() {
        assertThatThrownBy(() -> swf.describeDomain(
                DescribeDomainRequest.builder().name("sdk-swf-missing").build()))
                .isInstanceOf(UnknownResourceException.class)
                .hasMessageContaining("Unknown domain: sdk-swf-missing");
    }

    @Test
    @Order(4)
    @DisplayName("Register workflow and activity types with defaults")
    void registerTypes() {
        swf.registerWorkflowType(RegisterWorkflowTypeRequest.builder()
                .domain(DOMAIN)
                .name(WORKFLOW_TYPE.name())
                .version(WORKFLOW_TYPE.version())
                .defaultTaskList(TaskList.builder().name(TASK_LIST).build())
                .defaultTaskStartToCloseTimeout("60")
                .defaultExecutionStartToCloseTimeout("3600")
                .defaultChildPolicy(ChildPolicy.TERMINATE)
                .build());

        swf.registerActivityType(RegisterActivityTypeRequest.builder()
                .domain(DOMAIN)
                .name(ACTIVITY_TYPE.name())
                .version(ACTIVITY_TYPE.version())
                .defaultTaskList(TaskList.builder().name(TASK_LIST).build())
                .defaultTaskScheduleToStartTimeout("60")
                .defaultTaskStartToCloseTimeout("300")
                .build());

        DescribeWorkflowTypeResponse described = swf.describeWorkflowType(
                DescribeWorkflowTypeRequest.builder().domain(DOMAIN).workflowType(WORKFLOW_TYPE).build());

        assertThat(described.typeInfo().status()).isEqualTo(RegistrationStatus.REGISTERED);
        // creationDate arrives as an epoch-second number; the SDK models it as Instant, so a
        // formatted date on the wire would fail to deserialize.
        assertThat(described.typeInfo().creationDate()).isNotNull();
        assertThat(described.configuration().defaultTaskList().name()).isEqualTo(TASK_LIST);
        assertThat(described.configuration().defaultChildPolicy()).isEqualTo(ChildPolicy.TERMINATE);

        assertThatThrownBy(() -> swf.registerWorkflowType(RegisterWorkflowTypeRequest.builder()
                .domain(DOMAIN)
                .name(WORKFLOW_TYPE.name())
                .version(WORKFLOW_TYPE.version())
                .build()))
                .isInstanceOf(TypeAlreadyExistsException.class);
    }

    @Test
    @Order(5)
    @DisplayName("Start execution seeds the initial history")
    void startExecution() {
        runId = swf.startWorkflowExecution(StartWorkflowExecutionRequest.builder()
                        .domain(DOMAIN)
                        .workflowId("sdk-wf-1")
                        .workflowType(WORKFLOW_TYPE)
                        .input("{\"sdk\":true}")
                        .tagList("sdk", "compat")
                        .build())
                .runId();
        assertThat(runId).isNotBlank();

        GetWorkflowExecutionHistoryResponse history = swf.getWorkflowExecutionHistory(GetWorkflowExecutionHistoryRequest.builder()
                .domain(DOMAIN)
                .execution(execution())
                .build());

        assertThat(history.events()).hasSize(2);
        HistoryEvent started = history.events().get(0);
        assertThat(started.eventId()).isEqualTo(1L);
        assertThat(started.eventType()).isEqualTo(EventType.WORKFLOW_EXECUTION_STARTED);
        assertThat(started.eventTimestamp()).isNotNull();
        assertThat(started.workflowExecutionStartedEventAttributes().input()).isEqualTo("{\"sdk\":true}");
        assertThat(started.workflowExecutionStartedEventAttributes().taskList().name()).isEqualTo(TASK_LIST);
        assertThat(started.workflowExecutionStartedEventAttributes().tagList()).contains("sdk", "compat");
        assertThat(history.events().get(1).eventType()).isEqualTo(EventType.DECISION_TASK_SCHEDULED);

        assertThatThrownBy(() -> swf.startWorkflowExecution(StartWorkflowExecutionRequest.builder()
                .domain(DOMAIN)
                .workflowId("sdk-wf-1")
                .workflowType(WORKFLOW_TYPE)
                .build()))
                .isInstanceOf(WorkflowExecutionAlreadyStartedException.class);
    }

    @Test
    @Order(6)
    @DisplayName("Decider schedules an activity, worker completes it, decider closes the execution")
    void deciderAndWorkerHandshake() {
        PollForDecisionTaskResponse decisionTask = pollDecisionTask();
        assertThat(decisionTask.taskToken()).isNotBlank();
        assertThat(decisionTask.workflowExecution().runId()).isEqualTo(runId);
        assertThat(decisionTask.workflowType().name()).isEqualTo(WORKFLOW_TYPE.name());
        assertThat(decisionTask.previousStartedEventId()).isZero();
        assertThat(decisionTask.events()).extracting(HistoryEvent::eventType)
                .contains(EventType.DECISION_TASK_STARTED);

        swf.respondDecisionTaskCompleted(RespondDecisionTaskCompletedRequest.builder()
                .taskToken(decisionTask.taskToken())
                .executionContext("sdk-step-1")
                .decisions(Decision.builder()
                        .decisionType(DecisionType.SCHEDULE_ACTIVITY_TASK)
                        .scheduleActivityTaskDecisionAttributes(
                                ScheduleActivityTaskDecisionAttributes.builder()
                                        .activityId("sdk-act-1")
                                        .activityType(ACTIVITY_TYPE)
                                        .input("{\"work\":1}")
                                        .build())
                        .build())
                .build());

        PollForActivityTaskResponse activityTask = swf.pollForActivityTask(PollForActivityTaskRequest.builder()
                .domain(DOMAIN)
                .taskList(TaskList.builder().name(TASK_LIST).build())
                .identity("sdk-worker")
                .build());
        assertThat(activityTask.taskToken()).isNotBlank();
        assertThat(activityTask.activityId()).isEqualTo("sdk-act-1");
        assertThat(activityTask.activityType().name()).isEqualTo(ACTIVITY_TYPE.name());
        assertThat(activityTask.input()).isEqualTo("{\"work\":1}");

        swf.respondActivityTaskCompleted(RespondActivityTaskCompletedRequest.builder()
                .taskToken(activityTask.taskToken())
                .result("{\"done\":true}")
                .build());

        PollForDecisionTaskResponse afterActivity = pollDecisionTask();
        assertThat(afterActivity.events()).extracting(HistoryEvent::eventType)
                .contains(EventType.ACTIVITY_TASK_COMPLETED);
        ActivityTaskCompletedEventAttributes completed = afterActivity.events().stream()
                .filter(event -> event.eventType() == EventType.ACTIVITY_TASK_COMPLETED)
                .findFirst()
                .orElseThrow()
                .activityTaskCompletedEventAttributes();
        assertThat(completed.result()).isEqualTo("{\"done\":true}");

        swf.respondDecisionTaskCompleted(RespondDecisionTaskCompletedRequest.builder()
                .taskToken(afterActivity.taskToken())
                .decisions(Decision.builder()
                        .decisionType(DecisionType.COMPLETE_WORKFLOW_EXECUTION)
                        .completeWorkflowExecutionDecisionAttributes(attrs -> attrs.result("{\"ok\":true}"))
                        .build())
                .build());

        DescribeWorkflowExecutionResponse described = swf.describeWorkflowExecution(
                DescribeWorkflowExecutionRequest.builder().domain(DOMAIN).execution(execution()).build());
        assertThat(described.executionInfo().executionStatus()).isEqualTo(ExecutionStatus.CLOSED);
        assertThat(described.executionInfo().closeStatus()).isEqualTo(CloseStatus.COMPLETED);
        assertThat(described.executionInfo().closeTimestamp()).isNotNull();
        assertThat(described.openCounts().openActivityTasks()).isZero();
        assertThat(described.latestExecutionContext()).isEqualTo("sdk-step-1");
    }

    @Test
    @Order(7)
    @DisplayName("History deserializes every event into its typed attributes")
    void historyIsFullyTyped() {
        List<HistoryEvent> events = swf.getWorkflowExecutionHistory(
                        GetWorkflowExecutionHistoryRequest.builder()
                                .domain(DOMAIN).execution(execution()).build())
                .events();

        assertThat(events).extracting(HistoryEvent::eventType).containsExactly(
                EventType.WORKFLOW_EXECUTION_STARTED,
                EventType.DECISION_TASK_SCHEDULED,
                EventType.DECISION_TASK_STARTED,
                EventType.DECISION_TASK_COMPLETED,
                EventType.ACTIVITY_TASK_SCHEDULED,
                EventType.ACTIVITY_TASK_STARTED,
                EventType.ACTIVITY_TASK_COMPLETED,
                EventType.DECISION_TASK_SCHEDULED,
                EventType.DECISION_TASK_STARTED,
                EventType.DECISION_TASK_COMPLETED,
                EventType.WORKFLOW_EXECUTION_COMPLETED);

        // eventIds are contiguous from 1, as SWF guarantees.
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).eventId()).isEqualTo(i + 1L);
        }

        HistoryEvent scheduled = events.get(4);
        assertThat(scheduled.activityTaskScheduledEventAttributes().activityId()).isEqualTo("sdk-act-1");
        assertThat(scheduled.activityTaskScheduledEventAttributes().startToCloseTimeout()).isEqualTo("300");
        assertThat(scheduled.activityTaskScheduledEventAttributes().taskList().name()).isEqualTo(TASK_LIST);
    }

    @Test
    @Order(8)
    @DisplayName("Signal and terminate drive a second execution")
    void signalAndTerminate() {
        String signalRunId = swf.startWorkflowExecution(StartWorkflowExecutionRequest.builder()
                        .domain(DOMAIN)
                        .workflowId("sdk-wf-2")
                        .workflowType(WORKFLOW_TYPE)
                        .build())
                .runId();
        WorkflowExecution second = WorkflowExecution.builder()
                .workflowId("sdk-wf-2").runId(signalRunId).build();

        swf.signalWorkflowExecution(SignalWorkflowExecutionRequest.builder()
                .domain(DOMAIN)
                .workflowId("sdk-wf-2")
                .runId(signalRunId)
                .signalName("sdk-signal")
                .input("{\"resume\":true}")
                .build());

        assertThat(swf.getWorkflowExecutionHistory(GetWorkflowExecutionHistoryRequest.builder()
                        .domain(DOMAIN).execution(second).build())
                .events())
                .filteredOn(event -> event.eventType() == EventType.WORKFLOW_EXECUTION_SIGNALED)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.workflowExecutionSignaledEventAttributes().signalName())
                            .isEqualTo("sdk-signal");
                    assertThat(event.workflowExecutionSignaledEventAttributes().input())
                            .isEqualTo("{\"resume\":true}");
                });

        swf.terminateWorkflowExecution(TerminateWorkflowExecutionRequest.builder()
                .domain(DOMAIN)
                .workflowId("sdk-wf-2")
                .runId(signalRunId)
                .reason("sdk-test")
                .build());

        assertThat(swf.describeWorkflowExecution(DescribeWorkflowExecutionRequest.builder()
                        .domain(DOMAIN).execution(second).build())
                .executionInfo().closeStatus())
                .isEqualTo(CloseStatus.TERMINATED);
    }

    @Test
    @Order(9)
    @DisplayName("Counts and closed-execution listing reflect the finished work")
    void countsAndListing() {
        assertThat(swf.countClosedWorkflowExecutions(builder -> builder
                        .domain(DOMAIN)
                        .startTimeFilter(filter -> filter.oldestDate(java.time.Instant.EPOCH)))
                .count())
                .isGreaterThanOrEqualTo(2);

        assertThat(swf.listClosedWorkflowExecutions(builder -> builder
                        .domain(DOMAIN)
                        .startTimeFilter(filter -> filter.oldestDate(java.time.Instant.EPOCH)))
                .executionInfos())
                .extracting(info -> info.execution().workflowId())
                .contains("sdk-wf-1", "sdk-wf-2");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static WorkflowExecution execution() {
        return WorkflowExecution.builder().workflowId("sdk-wf-1").runId(runId).build();
    }

    /**
     * Polls until a task arrives. The emulator answers an idle poll immediately with an
     * empty token rather than long-polling, so a decider loops instead of blocking.
     */
    private static PollForDecisionTaskResponse pollDecisionTask() {
        for (int attempt = 0; attempt < 20; attempt++) {
            PollForDecisionTaskResponse task = swf.pollForDecisionTask(PollForDecisionTaskRequest.builder()
                    .domain(DOMAIN)
                    .taskList(TaskList.builder().name(TASK_LIST).build())
                    .identity("sdk-decider")
                    .build());
            if (task.taskToken() != null && !task.taskToken().isEmpty()) {
                return task;
            }
        }
        throw new AssertionError("no decision task became available");
    }
}
