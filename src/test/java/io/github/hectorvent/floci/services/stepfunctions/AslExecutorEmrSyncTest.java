package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.emr.EmrHandler;
import io.github.hectorvent.floci.services.emr.EmrService;
import io.github.hectorvent.floci.services.emr.model.EmrCluster;
import io.github.hectorvent.floci.services.emr.model.EmrStep;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code .sync} waits of the {@code elasticmapreduce:} integrations, on a cluster or step held in
 * a state Floci's instant lifecycle never shows. Time is a test clock the waits move forward.
 */
class AslExecutorEmrSyncTest {

    private static final String REGION = "eu-west-3";
    private static final String CLUSTER_ID = "j-SYNCTEST00001";
    private static final String STEP_ID = "s-SYNCTEST00001";
    private static final String ADD_STEP = """
            {"ClusterId": "j-SYNCTEST00001",
             "Step": {"Name": "job", "HadoopJarStep": {"Jar": "command-runner.jar", "Args": ["true"]}}}
            """;
    private static final String CREATE_CLUSTER = """
            {"Name": "sync", "Instances": {"KeepJobFlowAliveWhenNoSteps": true}}
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong testNanos = new AtomicLong();
    private EmrService emrService;
    private StepFunctionsService sfnService;
    private AslExecutor executor;
    private List<HistoryEvent> history;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        emrService = mock(EmrService.class);
        when(emrService.addJobFlowSteps(eq(CLUSTER_ID), any())).thenReturn(List.of(STEP_ID));
        when(emrService.runJobFlow(any(), anyString())).thenAnswer(invocation -> {
            EmrCluster cluster = invocation.getArgument(0);
            cluster.setId(CLUSTER_ID);
            return cluster;
        });
        sfnService = mock(StepFunctionsService.class);
        when(sfnService.describeExecution(any())).thenAnswer(invocation ->
                AslExecutorFixture.execution(invocation.getArgument(0), "RUNNING"));
        Instance<StepFunctionsService> instance = mock(Instance.class);
        when(instance.get()).thenReturn(sfnService);
        executor = AslExecutorFixture.builder()
                .objectMapper(objectMapper)
                .sfnService(instance)
                .customResourceLiveness(null)
                .emrTaskInvoker(new EmrTaskInvoker(new EmrHandler(emrService, objectMapper), objectMapper))
                .nanoTime(testNanos::get)
                .sleeper(testNanos::addAndGet)
                .build();
    }

    @Test
    void addStepSyncCancelsItsStepWhenTheTaskTimesOut() {
        when(emrService.describeStep(CLUSTER_ID, STEP_ID)).thenReturn(step("RUNNING"));

        Execution execution = run("""
                {"StartAt": "Add", "States": {"Add": {"Type": "Task",
                  "Resource": "arn:aws:states:::elasticmapreduce:addStep.sync", "TimeoutSeconds": 1, "End": true}}}
                """, ADD_STEP);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Timeout", execution.getError());
        verify(emrService).cancelSteps(CLUSTER_ID, List.of(STEP_ID));
    }

    @Test
    void addStepSyncFailsWithTheStepWhenTheStepFails() throws Exception {
        EmrStep failed = step("FAILED");
        failed.setStateChangeReason("Step failed");
        when(emrService.describeStep(CLUSTER_ID, STEP_ID)).thenReturn(failed);

        Execution execution = run(task("addStep.sync"), ADD_STEP);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.TaskFailed", execution.getError());
        JsonNode cause = objectMapper.readTree(execution.getCause());
        assertEquals(STEP_ID, cause.path("Step").path("Id").asText());
        assertEquals("FAILED", cause.path("Step").path("Status").path("State").asText());
        verify(emrService, never()).cancelSteps(any(), any());
    }

    @Test
    void addStepSyncWaitsUntilTheStepCompletes() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        when(emrService.describeStep(CLUSTER_ID, STEP_ID))
                .thenAnswer(invocation -> step(polls.incrementAndGet() < 3 ? "RUNNING" : "COMPLETED"));

        Execution execution = run(task("addStep.sync"), ADD_STEP);

        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        assertEquals(3, polls.get());
        JsonNode output = objectMapper.readTree(execution.getOutput());
        assertEquals(List.of("Step", "StepId"), fieldNames(output));
        assertEquals("COMPLETED", output.path("Step").path("Status").path("State").asText());
        assertEquals(STEP_ID, output.path("StepId").asText());
    }

    @Test
    void createClusterSyncFailsWhenTheClusterTerminatesInsteadOfWaiting() throws Exception {
        when(emrService.describeCluster(CLUSTER_ID)).thenReturn(cluster("TERMINATED_WITH_ERRORS"));

        Execution execution = run(task("createCluster.sync"), CREATE_CLUSTER);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.TaskFailed", execution.getError());
        JsonNode cause = objectMapper.readTree(execution.getCause());
        assertEquals("TERMINATED_WITH_ERRORS", cause.path("Cluster").path("Status").path("State").asText());
    }

    @Test
    void stopExecutionLeavesTheClusterOfACreateClusterSyncRunning() {
        when(emrService.describeCluster(CLUSTER_ID)).thenReturn(cluster("STARTING"));
        AtomicInteger parentReads = new AtomicInteger();
        when(sfnService.describeExecution(any())).thenAnswer(invocation ->
                AslExecutorFixture.execution(invocation.getArgument(0),
                        parentReads.incrementAndGet() < 3 ? "RUNNING" : "ABORTED"));

        Execution execution = run(task("createCluster.sync"), CREATE_CLUSTER);

        assertNull(execution.getError());
        verify(emrService, never()).terminateJobFlows(any());
        assertEquals(List.of(), history.stream().map(HistoryEvent::getType)
                .filter(type -> type.equals("TaskFailed") || type.equals("TaskSucceeded")).toList());
    }

    private static String task(String api) {
        return """
                {"StartAt": "Emr", "States": {"Emr": {"Type": "Task",
                  "Resource": "arn:aws:states:::elasticmapreduce:%s", "End": true}}}
                """.formatted(api);
    }

    private static EmrStep step(String state) {
        EmrStep step = new EmrStep();
        step.setId(STEP_ID);
        step.setName("job");
        step.setActionOnFailure("CONTINUE");
        step.setState(state);
        return step;
    }

    private static EmrCluster cluster(String state) {
        EmrCluster cluster = new EmrCluster();
        cluster.setId(CLUSTER_ID);
        cluster.setName("sync");
        cluster.setState(state);
        return cluster;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private Execution run(String definition, String input) {
        history = new ArrayList<>();
        return AslExecutorFixture.runSync(executor, REGION, "emr-sync-test", definition, input, history);
    }
}
