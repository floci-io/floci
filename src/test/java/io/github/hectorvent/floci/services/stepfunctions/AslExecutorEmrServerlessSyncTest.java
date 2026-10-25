package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.emrserverless.EmrServerlessController;
import io.github.hectorvent.floci.services.emrserverless.EmrServerlessJobRunService;
import io.github.hectorvent.floci.services.emrserverless.EmrServerlessService;
import io.github.hectorvent.floci.services.emrserverless.model.Application;
import io.github.hectorvent.floci.services.emrserverless.model.JobRun;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code .sync} waits of the {@code emr-serverless:} integrations, on an application or job run
 * held in a state Floci's instant lifecycle never shows. Time is a test clock the waits move forward.
 */
class AslExecutorEmrServerlessSyncTest {

    private static final String REGION = "eu-west-3";
    private static final String APPLICATION_ID = "00syncapp0000001";
    private static final String JOB_RUN_ID = "00syncrun0000001";
    private static final String APPLICATION_ARN =
            "arn:aws:emr-serverless:eu-west-3:000000000000:/applications/" + APPLICATION_ID;
    private static final String JOB_RUN_ARN = APPLICATION_ARN + "/jobruns/" + JOB_RUN_ID;
    private static final String START_JOB_RUN = """
            {"ApplicationId": "00syncapp0000001", "ExecutionRoleArn": "arn:aws:iam::000000000000:role/job",
             "JobDriver": {"SparkSubmit": {"EntryPoint": "s3://bucket/app.py"}}}
            """;
    private static final String APPLICATION = """
            {"ApplicationId": "00syncapp0000001"}
            """;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AtomicLong testNanos = new AtomicLong();
    private EmrServerlessService applications;
    private EmrServerlessJobRunService jobRuns;
    private StepFunctionsService sfnService;
    private AslExecutor executor;
    private List<HistoryEvent> history;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        applications = mock(EmrServerlessService.class);
        jobRuns = mock(EmrServerlessJobRunService.class);
        when(jobRuns.startJobRun(eq(APPLICATION_ID), any())).thenReturn(jobRun("RUNNING"));
        when(applications.createApplication(any())).thenReturn(application("CREATING"));
        sfnService = mock(StepFunctionsService.class);
        when(sfnService.describeExecution(any())).thenAnswer(invocation ->
                AslExecutorFixture.execution(invocation.getArgument(0), "RUNNING"));
        Instance<StepFunctionsService> instance = mock(Instance.class);
        when(instance.get()).thenReturn(sfnService);
        executor = AslExecutorFixture.builder()
                .objectMapper(objectMapper)
                .sfnService(instance)
                .customResourceLiveness(null)
                .emrServerlessTaskInvoker(new EmrServerlessTaskInvoker(
                        new EmrServerlessController(applications, jobRuns), objectMapper))
                .nanoTime(testNanos::get)
                .sleeper(testNanos::addAndGet)
                .build();
    }

    @Test
    void startJobRunSyncWaitsUntilTheRunSucceedsAndAnswersWithTheStartingCall() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        when(jobRuns.getJobRun(APPLICATION_ID, JOB_RUN_ID, null))
                .thenAnswer(invocation -> jobRun(polls.incrementAndGet() < 3 ? "RUNNING" : "SUCCESS"));

        Execution execution = run(task("startJobRun.sync"), START_JOB_RUN);

        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        assertEquals(3, polls.get());
        JsonNode output = objectMapper.readTree(execution.getOutput());
        assertEquals(objectMapper.createObjectNode().put("ApplicationId", APPLICATION_ID)
                .put("JobRunId", JOB_RUN_ID).put("Arn", JOB_RUN_ARN), output);
        HistoryEvent submitted = history.stream().filter(event -> event.getType().equals("TaskSubmitted"))
                .findFirst().orElseThrow();
        assertEquals(output, objectMapper.readTree(String.valueOf(
                submitted.getDetails().get("output"))));
    }

    @Test
    void startJobRunSyncFailsWithTheRunWhenTheRunFails() throws Exception {
        JobRun failed = jobRun("FAILED");
        failed.setStateDetails("Job failed, please check complete logs in configured logging destination.");
        failed.setTags(Map.of("team", "data"));
        failed.setConfigurationOverrides(Map.of("applicationConfiguration", List.of(Map.of(
                "classification", "spark-defaults", "properties", Map.of("spark.executor.memory", "4g")))));
        when(jobRuns.getJobRun(APPLICATION_ID, JOB_RUN_ID, null)).thenReturn(failed);

        Execution execution = run(task("startJobRun.sync"), START_JOB_RUN);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.TaskFailed", execution.getError());
        JsonNode run = objectMapper.readTree(execution.getCause()).path("JobRun");
        assertEquals("FAILED", run.path("State").asText());
        assertEquals(failed.getStateDetails(), run.path("StateDetails").asText());
        assertEquals("data", run.path("Tags").path("team").asText());
        assertEquals("4g", run.path("ConfigurationOverrides").path("ApplicationConfiguration").path(0)
                .path("Properties").path("spark.executor.memory").asText());
        verify(jobRuns, never()).cancelJobRun(anyString(), anyString());
    }

    @Test
    void startJobRunSyncCancelsItsRunWhenTheTaskTimesOut() {
        when(jobRuns.getJobRun(APPLICATION_ID, JOB_RUN_ID, null)).thenReturn(jobRun("RUNNING"));
        when(jobRuns.cancelJobRun(APPLICATION_ID, JOB_RUN_ID)).thenReturn(jobRun("CANCELLED"));

        Execution execution = run(timingOut("startJobRun.sync"), START_JOB_RUN);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Timeout", execution.getError());
        verify(jobRuns).cancelJobRun(APPLICATION_ID, JOB_RUN_ID);
    }

    @Test
    void startJobRunSyncStillTimesOutWhenEmrServerlessRefusesTheCancel() {
        when(jobRuns.getJobRun(APPLICATION_ID, JOB_RUN_ID, null)).thenReturn(jobRun("RUNNING"));
        when(jobRuns.cancelJobRun(APPLICATION_ID, JOB_RUN_ID))
                .thenThrow(new AwsException("ValidationException", "Job run is not in a cancellable state", 400));

        Execution execution = run(timingOut("startJobRun.sync"), START_JOB_RUN);

        assertEquals("States.Timeout", execution.getError());
        verify(jobRuns).cancelJobRun(APPLICATION_ID, JOB_RUN_ID);
    }

    @Test
    void anInternalErrorCancellingTheRunFailsTheTaskWithIt() {
        when(jobRuns.getJobRun(APPLICATION_ID, JOB_RUN_ID, null)).thenReturn(jobRun("RUNNING"));
        when(jobRuns.cancelJobRun(APPLICATION_ID, JOB_RUN_ID))
                .thenThrow(new AwsException("InternalServerException", "Request processing failed", 500));

        Execution execution = run(timingOut("startJobRun.sync"), START_JOB_RUN);

        assertEquals("EmrServerless.InternalServerException", execution.getError());
        assertEquals("Request processing failed", execution.getCause());
    }

    @Test
    void aFaultWhileCancellingTheRunIsNotPassedOver() {
        when(jobRuns.getJobRun(APPLICATION_ID, JOB_RUN_ID, null)).thenReturn(jobRun("RUNNING"));
        when(jobRuns.cancelJobRun(APPLICATION_ID, JOB_RUN_ID)).thenThrow(new IllegalStateException("store closed"));

        Execution execution = run(timingOut("startJobRun.sync"), START_JOB_RUN);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertEquals("store closed", execution.getCause());
    }

    @Test
    void cancelJobRunSyncFailsWhenTheRunSucceedsInstead() throws Exception {
        when(jobRuns.cancelJobRun(APPLICATION_ID, JOB_RUN_ID)).thenReturn(jobRun("CANCELLING"));
        when(jobRuns.getJobRun(APPLICATION_ID, JOB_RUN_ID, null)).thenReturn(jobRun("SUCCESS"));

        Execution execution = run(task("cancelJobRun.sync"),
                "{\"ApplicationId\": \"" + APPLICATION_ID + "\", \"JobRunId\": \"" + JOB_RUN_ID + "\"}");

        assertEquals("States.TaskFailed", execution.getError());
        assertEquals("SUCCESS", objectMapper.readTree(execution.getCause()).path("JobRun").path("State").asText());
    }

    @Test
    void createApplicationSyncDeletesTheApplicationWhenTheTaskTimesOut() {
        Application creating = application("CREATING");
        creating.setClientToken("kept-by-floci");
        when(applications.getApplication(APPLICATION_ID)).thenReturn(creating);

        Execution execution = run(timingOut("createApplication.sync"),
                "{\"Name\": \"sync\", \"ReleaseLabel\": \"emr-7.5.0\", \"Type\": \"SPARK\"}");

        assertEquals("States.Timeout", execution.getError());
        verify(applications).deleteApplication(APPLICATION_ID);
    }

    @Test
    void createApplicationSyncFailsWithTheApplicationWhenItTerminates() throws Exception {
        Application terminated = application("TERMINATED");
        terminated.setClientToken("kept-by-floci");
        when(applications.getApplication(APPLICATION_ID)).thenReturn(terminated);

        Execution execution = run(task("createApplication.sync"),
                "{\"Name\": \"sync\", \"ReleaseLabel\": \"emr-7.5.0\", \"Type\": \"SPARK\"}");

        assertEquals("States.TaskFailed", execution.getError());
        JsonNode application = objectMapper.readTree(execution.getCause()).path("Application");
        assertEquals("TERMINATED", application.path("State").asText());
        assertFalse(application.has("ClientToken") || application.has("clientToken"), execution.getCause());
    }

    @Test
    void startApplicationSyncStopsTheApplicationWhenTheExecutionIsStopped() {
        when(applications.getApplication(APPLICATION_ID)).thenReturn(application("STARTING"));
        AtomicInteger parentReads = new AtomicInteger();
        when(sfnService.describeExecution(any())).thenAnswer(invocation ->
                AslExecutorFixture.execution(invocation.getArgument(0),
                        parentReads.incrementAndGet() < 3 ? "RUNNING" : "ABORTED"));

        run(task("startApplication.sync"), APPLICATION);

        verify(applications).startApplication(APPLICATION_ID);
        verify(applications).stopApplication(APPLICATION_ID);
        assertEquals(List.of(), history.stream().map(HistoryEvent::getType)
                .filter(type -> type.equals("TaskFailed") || type.equals("TaskSucceeded")).toList());
    }

    @Test
    void stopApplicationSyncLeavesTheApplicationAsItIsWhenTheTaskTimesOut() {
        when(applications.getApplication(APPLICATION_ID)).thenReturn(application("STOPPING"));

        Execution execution = run(timingOut("stopApplication.sync"), APPLICATION);

        assertEquals("States.Timeout", execution.getError());
        verify(applications).stopApplication(APPLICATION_ID);
        verify(applications, never()).startApplication(anyString());
        verify(applications, never()).deleteApplication(anyString());
    }

    private static String timingOut(String api) {
        return """
                {"StartAt": "EmrServerless", "States": {"EmrServerless": {"Type": "Task",
                  "Resource": "arn:aws:states:::emr-serverless:%s", "TimeoutSeconds": 1, "End": true}}}
                """.formatted(api);
    }

    private static String task(String api) {
        return """
                {"StartAt": "EmrServerless", "States": {"EmrServerless": {"Type": "Task",
                  "Resource": "arn:aws:states:::emr-serverless:%s", "End": true}}}
                """.formatted(api);
    }

    private static JobRun jobRun(String state) {
        JobRun run = new JobRun();
        run.setApplicationId(APPLICATION_ID);
        run.setJobRunId(JOB_RUN_ID);
        run.setArn(JOB_RUN_ARN);
        run.setState(state);
        run.setCreatedAt(Instant.parse("2026-10-22T12:00:00Z"));
        run.setUpdatedAt(Instant.parse("2026-10-22T12:00:00Z"));
        return run;
    }

    private static Application application(String state) {
        Application application = new Application();
        application.setApplicationId(APPLICATION_ID);
        application.setArn(APPLICATION_ARN);
        application.setName("sync");
        application.setState(state);
        return application;
    }

    private Execution run(String definition, String input) {
        history = new ArrayList<>();
        return AslExecutorFixture.runSync(executor, REGION, "emr-serverless-sync-test", definition, input, history);
    }
}
