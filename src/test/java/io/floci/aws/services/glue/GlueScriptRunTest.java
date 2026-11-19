package io.floci.aws.services.glue;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.glue.model.Job;
import io.floci.aws.services.glue.model.JobCommand;
import io.floci.aws.services.glue.model.JobRun;
import io.floci.aws.services.glue.model.JobRunBookkeeping;
import io.floci.aws.services.glue.schemaregistry.GlueSchemaRegistryService;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.resourcegroupstagging.ResourceGroupsTaggingService;
import io.floci.aws.testing.InMemoryStorageFactory;
import io.floci.aws.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Glue job runs in the docker runner mode: a script's end, a timeout and a stop settle the run. */
class GlueScriptRunTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/glue";
    private static final String SCRIPT = "s3://glue-demo/scripts/job.py";

    private GlueService glueService;
    private RegionResolver regionResolver;
    private MutableClock clock;
    private InMemoryStorage<String, JobRun> runStore;
    private InMemoryStorage<String, JobRunBookkeeping> bookkeeping;
    private FakeRunner runner;
    private final List<Runnable> queued = new ArrayList<>();
    private GlueJobRunService service;

    @BeforeEach
    void setUp() {
        regionResolver = new RegionResolver(REGION, ACCOUNT_ID);
        StorageFactory storageFactory = new InMemoryStorageFactory(ACCOUNT_ID);
        glueService = new GlueService(storageFactory, new GlueSchemaRegistryService(storageFactory, regionResolver),
                regionResolver, new ResourceGroupsTaggingService(storageFactory),
                new KmsService(storageFactory, regionResolver));
        clock = new MutableClock();
        runStore = new InMemoryStorage<>();
        bookkeeping = new InMemoryStorage<>();
        runner = new FakeRunner();
        service = newService();
    }

    private GlueJobRunService newService() {
        Executor held = queued::add;
        return new GlueJobRunService(runStore, bookkeeping, glueService, 0, clock, regionResolver, runner, held,
                "glue-image:5", "glue-net");
    }

    private void createJob(String name, String command, String script, Map<String, String> defaults, Integer timeout) {
        Job job = new Job();
        job.setName(name);
        job.setRole(ROLE);
        JobCommand jobCommand = new JobCommand();
        jobCommand.setName(command);
        jobCommand.setScriptLocation(script);
        job.setCommand(jobCommand);
        job.setGlueVersion("5.0");
        job.setWorkerType("G.1X");
        job.setNumberOfWorkers(2);
        job.setDefaultArguments(defaults);
        job.setTimeout(timeout);
        glueService.createJob(job, null, REGION);
    }

    private void runQueued() {
        List<Runnable> tasks = new ArrayList<>(queued);
        queued.clear();
        tasks.forEach(Runnable::run);
    }

    private String state(String job, String runId) {
        return service.getJobRun(job, runId).getJobRunState();
    }

    @Test
    void runsTheScriptWithTheJobsRoleAndTheMergedArguments() {
        createJob("etl", "glueetl", SCRIPT, Map.of("--bucket", "glue-demo", "--mode", "default"), 30);
        JobRun overrides = new JobRun();
        overrides.setArguments(Map.of("--mode", "run"));

        JobRun run = service.startJobRun("etl", null, overrides);
        runQueued();

        GlueScriptRunner.Run request = runner.requests.getFirst();
        assertEquals(run.getId(), request.runId());
        assertEquals(ROLE, request.role());
        assertEquals(SCRIPT, request.scriptLocation());
        assertEquals("glue-image:5", request.image());
        assertEquals("glue-net", request.dockerNetwork());
        assertEquals(Duration.ofMinutes(30), request.timeout());
        assertEquals(ACCOUNT_ID, request.accountId());
        assertEquals(REGION, request.region());
        Map<String, String> arguments = pairs(request.arguments());
        assertEquals("glue-demo", arguments.get("--bucket"));
        assertEquals("run", arguments.get("--mode"));
        assertEquals("etl", arguments.get("--JOB_NAME"));
        assertEquals(run.getId(), arguments.get("--JOB_RUN_ID"));
    }

    @Test
    void staysRunningUntilTheScriptEnds() {
        createJob("etl", "glueetl", SCRIPT, null, null);

        JobRun run = service.startJobRun("etl", null, new JobRun());
        clock.advance(Duration.ofHours(1));

        assertEquals("RUNNING", state("etl", run.getId()));
        runQueued();
        assertEquals("SUCCEEDED", state("etl", run.getId()));
    }

    @Test
    void failsTheRunWithTheScriptsExitCode() {
        createJob("etl", "glueetl", SCRIPT, null, null);
        runner.outcome = GlueScriptRunner.Outcome.exitedWith(3);

        JobRun run = service.startJobRun("etl", null, new JobRun());
        runQueued();

        JobRun ended = service.getJobRun("etl", run.getId());
        assertEquals("FAILED", ended.getJobRunState());
        assertTrue(ended.getErrorMessage().contains("code 3"));
    }

    @Test
    void failsTheRunWhenTheScriptCouldNotStart() {
        createJob("etl", "glueetl", SCRIPT, null, null);
        runner.outcome = GlueScriptRunner.Outcome.couldNotStart("Could not read the script");

        JobRun run = service.startJobRun("etl", null, new JobRun());
        runQueued();

        JobRun ended = service.getJobRun("etl", run.getId());
        assertEquals("FAILED", ended.getJobRunState());
        assertEquals("Could not read the script", ended.getErrorMessage());
    }

    @Test
    void timesTheRunOutWhenTheRunnerSaysItRanOutOfTime() {
        createJob("etl", "glueetl", SCRIPT, null, 5);
        runner.outcome = GlueScriptRunner.Outcome.outOfTime();

        JobRun run = service.startJobRun("etl", null, new JobRun());
        runQueued();

        JobRun ended = service.getJobRun("etl", run.getId());
        assertEquals("TIMEOUT", ended.getJobRunState());
        assertTrue(ended.getErrorMessage().contains("5 minutes"));
    }

    @Test
    void stopsTheRunAndTheScriptsContainer() {
        createJob("etl", "glueetl", SCRIPT, null, null);
        JobRun run = service.startJobRun("etl", null, new JobRun());

        GlueJobRunService.StopResult result = service.batchStopJobRun("etl", List.of(run.getId()));

        assertEquals(1, result.stopped().size());
        assertEquals("STOPPED", state("etl", run.getId()));
        assertEquals(List.of(run.getId()), runner.stopped);
    }

    @Test
    void aScriptThatEndsAfterAStopLeavesTheRunStopped() {
        createJob("etl", "glueetl", SCRIPT, null, null);
        JobRun run = service.startJobRun("etl", null, new JobRun());
        service.batchStopJobRun("etl", List.of(run.getId()));

        runQueued();

        assertEquals("STOPPED", state("etl", run.getId()));
    }

    @Test
    void doesNotStartTheScriptOfARunThatWasStoppedFirst() {
        createJob("etl", "glueetl", SCRIPT, null, null);
        JobRun run = service.startJobRun("etl", null, new JobRun());
        service.batchStopJobRun("etl", List.of(run.getId()));

        runner.cancelledSeen = null;
        runQueued();

        assertEquals(Boolean.TRUE, runner.cancelledSeen);
    }

    @Test
    void failsARunAnEarlierProcessLeftRunning() {
        createJob("etl", "glueetl", SCRIPT, null, null);
        JobRun run = service.startJobRun("etl", null, new JobRun());

        GlueJobRunService restarted = new GlueJobRunService(runStore, bookkeeping, glueService, 0, clock,
                regionResolver, runner, queued::add, "glue-image:5", null);
        JobRun read = restarted.getJobRun("etl", run.getId());

        assertEquals("FAILED", read.getJobRunState());
    }

    @Test
    void leavesOtherJobsToTheSimulation() {
        createJob("shell", "pythonshell", SCRIPT, null, null);
        createJob("local", "glueetl", "/tmp/job.py", null, null);

        JobRun shell = service.startJobRun("shell", null, new JobRun());
        JobRun local = service.startJobRun("local", null, new JobRun());

        assertEquals("SUCCEEDED", shell.getJobRunState());
        assertEquals("SUCCEEDED", local.getJobRunState());
        assertTrue(queued.isEmpty());
        assertNull(runner.cancelledSeen);
    }

    @Test
    void aRunOverridesTheJobsTimeoutForTheScript() {
        createJob("etl", "glueetl", SCRIPT, null, 30);
        JobRun overrides = new JobRun();
        overrides.setTimeout(7);

        service.startJobRun("etl", null, overrides);
        runQueued();

        assertEquals(Duration.ofMinutes(7), runner.requests.getFirst().timeout());
    }

    @Test
    void aJobWithoutATimeoutRunsItsScriptUnderTheDefaultCreateJobGives() {
        createJob("etl", "glueetl", SCRIPT, null, null);

        service.startJobRun("etl", null, new JobRun());
        runQueued();

        assertEquals(Duration.ofMinutes(glueService.getJob("etl").getTimeout()), runner.requests.getFirst().timeout());
    }

    @Test
    void aRunnerThatThrowsFailsTheRunWithItsMessage() {
        createJob("etl", "glueetl", SCRIPT, null, null);
        runner.failure = new IllegalStateException("docker is down");

        JobRun run = service.startJobRun("etl", null, new JobRun());
        runQueued();

        JobRun ended = service.getJobRun("etl", run.getId());
        assertEquals("FAILED", ended.getJobRunState());
        assertEquals("docker is down", ended.getErrorMessage());
    }

    @Test
    void aRunTheExecutorRefusesFailsAtOnce() {
        createJob("etl", "glueetl", SCRIPT, null, null);
        Executor refusing = task -> {
            throw new RejectedExecutionException("shut down");
        };
        GlueJobRunService stopping = new GlueJobRunService(runStore, bookkeeping, glueService, 0, clock,
                regionResolver, runner, refusing, "glue-image:5", null);

        JobRun run = stopping.startJobRun("etl", null, new JobRun());

        assertEquals("FAILED", run.getJobRunState());
        assertEquals("Floci is shutting down", run.getErrorMessage());
    }

    @Test
    void aRunningScriptCountsAgainstMaxConcurrentRuns() {
        createJob("etl", "glueetl", SCRIPT, null, null);
        service.startJobRun("etl", null, new JobRun());

        assertFalse(service.hasCapacity("etl"));
        AwsException refused = assertThrows(AwsException.class, () -> service.startJobRun("etl", null, new JobRun()));
        assertEquals("ConcurrentRunsExceededException", refused.getErrorCode());

        runQueued();
        assertTrue(service.hasCapacity("etl"));
    }

    @Test
    void aRunsEndComesAfterTheLastPositionATriggerHasSeen() {
        createJob("etl", "glueetl", SCRIPT, null, null);
        JobRun run = service.startJobRun("etl", null, new JobRun());
        assertTrue(service.completionsAfter("etl", null).isEmpty());

        runQueued();

        List<GlueRunCompletion> completions = service.completionsAfter("etl", null);
        assertEquals(1, completions.size());
        assertEquals("SUCCEEDED", completions.getFirst().state());
        assertTrue(service.completionsAfter("etl", completions.getFirst().position()).isEmpty());
        assertEquals("SUCCEEDED", state("etl", run.getId()));
    }

    @Test
    void deletingTheJobStopsItsScriptAndALateEndChangesNothing() {
        createJob("etl", "glueetl", SCRIPT, null, null);
        JobRun run = service.startJobRun("etl", null, new JobRun());

        service.deleteRuns("etl");
        runQueued();

        assertEquals(List.of(run.getId()), runner.stopped);
        assertEquals(Boolean.TRUE, runner.cancelledSeen);
        assertTrue(runStore.get(run.getId()).isEmpty());
    }

    @Test
    void aStoppedRunIsNotStoppedTwiceAndKeepsItsFirstEnd() {
        createJob("etl", "glueetl", SCRIPT, null, null);
        JobRun run = service.startJobRun("etl", null, new JobRun());
        service.batchStopJobRun("etl", List.of(run.getId()));

        GlueJobRunService.StopResult again = service.batchStopJobRun("etl", List.of(run.getId()));

        assertEquals(1, again.errors().size());
        assertEquals(List.of(run.getId()), runner.stopped);
    }

    @Test
    void aScalaJobKeepsTheSimulationBecauseItsScriptIsNotPython() {
        createJob("scala", "glueetl", SCRIPT, Map.of("--job-language", "scala"), null);

        JobRun run = service.startJobRun("scala", null, new JobRun());

        assertEquals("SUCCEEDED", run.getJobRunState());
        assertTrue(queued.isEmpty());
    }

    @Test
    void aRunsOwnJobLanguageCanMakeItScalaToo() {
        createJob("etl", "glueetl", SCRIPT, null, null);
        JobRun overrides = new JobRun();
        overrides.setArguments(Map.of("--job-language", "Scala"));

        service.startJobRun("etl", null, overrides);

        assertTrue(queued.isEmpty());
    }

    @Test
    void aRunStoppedByAResetOrShutdownRatherThanTheCallerFails() {
        createJob("etl", "glueetl", SCRIPT, null, null);
        runner.outcome = GlueScriptRunner.Outcome.halted();

        JobRun run = service.startJobRun("etl", null, new JobRun());
        runQueued();

        JobRun ended = service.getJobRun("etl", run.getId());
        assertEquals("FAILED", ended.getJobRunState());
        assertEquals("Floci stopped the job run before its script ended", ended.getErrorMessage());
    }

    @Test
    void aStreamingJobKeepsTheSimulationEvenWithGlueEtlAndS3Script() {
        Job job = new Job();
        job.setName("stream");
        job.setRole(ROLE);
        JobCommand command = new JobCommand();
        command.setName("glueetl");
        command.setScriptLocation(SCRIPT);
        job.setCommand(command);
        job.setJobMode("STREAMING");
        glueService.createJob(job, null, REGION);

        JobRun run = service.startJobRun("stream", null, new JobRun());

        assertEquals("SUCCEEDED", run.getJobRunState());
        assertTrue(queued.isEmpty());
        assertTrue(runner.requests.isEmpty());
    }

    @Test
    void aResetIgnoresAnInFlightScriptRunOutcome() {
        createJob("etl", "glueetl", SCRIPT, null, null);
        JobRun run = service.startJobRun("etl", null, new JobRun());
        runner.onRun = () -> {
            service.beforeReset();
            runStore.clear();
            bookkeeping.clear();
        };

        runQueued();

        assertTrue(runStore.get(run.getId()).isEmpty());
        assertTrue(bookkeeping.get(run.getId()).isEmpty());
    }

    @Test
    void aRunnerErrorStillEndsTheRunFailedAndFreesCapacity() {
        createJob("etl", "glueetl", SCRIPT, null, null);
        runner.error = new AssertionError("unexpected linkage error");

        JobRun run = service.startJobRun("etl", null, new JobRun());
        assertThrows(AssertionError.class, this::runQueued);

        JobRun ended = service.getJobRun("etl", run.getId());
        assertEquals("FAILED", ended.getJobRunState());
        assertEquals("unexpected linkage error", ended.getErrorMessage());
        assertTrue(service.hasCapacity("etl"));
    }

    @Test
    void queuedRunsWaitInWaitingUntilAConcurrencySlotFrees() {
        createJob("etl", "glueetl", SCRIPT, null, null);
        JobRun queuing = new JobRun();
        queuing.setJobRunQueuingEnabled(true);

        JobRun first = service.startJobRun("etl", null, queuing);
        JobRun second = service.startJobRun("etl", null, queuing);
        JobRun third = service.startJobRun("etl", null, queuing);

        assertEquals("RUNNING", first.getJobRunState());
        assertEquals("WAITING", state("etl", second.getId()));
        assertEquals("WAITING", state("etl", third.getId()));
        assertEquals(1, queued.size());

        // Finishing the first run launches only the second, keeping the third in WAITING.
        runQueued();
        assertEquals("SUCCEEDED", state("etl", first.getId()));
        assertEquals("RUNNING", state("etl", second.getId()));
        assertEquals("WAITING", state("etl", third.getId()));
        assertEquals(1, queued.size());

        // Stopping the second run frees the slot and launches the third.
        service.batchStopJobRun("etl", List.of(second.getId()));
        assertEquals("STOPPED", state("etl", second.getId()));
        assertEquals("RUNNING", state("etl", third.getId()));
        assertEquals(2, queued.size());

        runQueued();
        assertEquals("STOPPED", state("etl", second.getId()));
        assertEquals("SUCCEEDED", state("etl", third.getId()));
    }

    @Test
    void aWaitingRunLeftByAnEarlierProcessFailsWhenReadAfterRestart() {
        createJob("etl", "glueetl", SCRIPT, null, null);
        JobRun queuing = new JobRun();
        queuing.setJobRunQueuingEnabled(true);
        service.startJobRun("etl", null, queuing);
        JobRun waitingRun = service.startJobRun("etl", null, queuing);
        assertEquals("WAITING", waitingRun.getJobRunState());

        GlueJobRunService restarted = new GlueJobRunService(runStore, bookkeeping, glueService, 0, clock,
                regionResolver, runner, queued::add, "glue-image:5", null);
        JobRun read = restarted.getJobRun("etl", waitingRun.getId());

        assertEquals("FAILED", read.getJobRunState());
    }

    @Test
    void fallsBackToSimulationWhenRunnerIsUnavailable() {
        createJob("etl", "glueetl", SCRIPT, null, null);
        runner.available = false;

        JobRun run = service.startJobRun("etl", null, new JobRun());

        assertEquals("SUCCEEDED", run.getJobRunState());
        assertTrue(queued.isEmpty());
        assertTrue(runner.requests.isEmpty());
    }

    private static Map<String, String> pairs(List<String> arguments) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < arguments.size(); i += 2) {
            map.put(arguments.get(i), arguments.get(i + 1));
        }
        return map;
    }

    private static final class FakeRunner implements GlueScriptRunner {
        final List<Run> requests = new ArrayList<>();
        final List<String> stopped = new ArrayList<>();
        GlueScriptRunner.Outcome outcome = Outcome.exitedWith(0);
        boolean available = true;
        Boolean cancelledSeen;
        RuntimeException failure;
        Error error;
        Runnable onRun;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public Outcome run(Run run, Progress progress) {
            requests.add(run);
            if (onRun != null) {
                onRun.run();
            }
            if (error != null) {
                throw error;
            }
            if (failure != null) {
                throw failure;
            }
            cancelledSeen = progress.cancelled();
            return cancelledSeen ? Outcome.halted() : outcome;
        }

        @Override
        public void stop(String runId) {
            stopped.add(runId);
        }

        @Override
        public void removeLeftoverContainers() {
        }
    }
}
