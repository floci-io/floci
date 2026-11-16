package io.floci.aws.services.glue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.RegionScopedStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.glue.model.ExecutionProperty;
import io.floci.aws.services.glue.model.Job;
import io.floci.aws.services.glue.model.JobRun;
import io.floci.aws.services.glue.model.JobRunBookkeeping;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Glue job runs. By default no script executes: a run follows Glue's state machine and its response shape,
 * and settles lazily when read. With the default {@code job-run-duration-seconds} of 0 a run is
 * SUCCEEDED as soon as it starts; a positive duration keeps it RUNNING until that much time has
 * passed, which is what makes BatchStopJobRun and MaxConcurrentRuns observable.
 *
 * <p>With {@code runner-mode} {@code docker}, a Spark ({@code glueetl}) job whose script is in S3 runs that
 * script in a container instead: the run is RUNNING until the script ends, and then SUCCEEDED or FAILED by
 * its exit code, TIMEOUT at the job's timeout, or STOPPED by BatchStopJobRun. The script's end, the timeout
 * and a stop compete for one terminal transition: whichever settles the run first wins, the others are ignored.
 *
 * <p>Every public method is synchronized: the store has no atomic compare and set, so admitting a
 * run (count the live runs, check the limit, write the run), stopping one, settling one and
 * removing a deleted job's runs must not interleave.
 */
@ApplicationScoped
public class GlueJobRunService implements Resettable {

    private static final Logger LOG = Logger.getLogger(GlueJobRunService.class);

    static final String STATE_WAITING = "WAITING";
    static final String STATE_RUNNING = "RUNNING";
    static final String STATE_SUCCEEDED = "SUCCEEDED";
    static final String STATE_STOPPED = "STOPPED";
    static final String STATE_TIMEOUT = "TIMEOUT";
    static final String STATE_FAILED = "FAILED";

    private static final Set<String> TERMINAL_STATES =
            Set.of(STATE_SUCCEEDED, STATE_STOPPED, STATE_FAILED, STATE_TIMEOUT, "ERROR", "EXPIRED");

    // ExecutionProperty.MaxConcurrentRuns defaults to 1 when a job does not set it.
    private static final int DEFAULT_MAX_CONCURRENT_RUNS = 1;
    private static final int MAX_JOB_RUNS_PAGE_SIZE = 200;
    private static final String LOG_GROUP_NAME = "/aws-glue/jobs";
    private static final String RUNNER_DOCKER = "docker";
    private static final String SPARK_COMMAND = "glueetl";
    private static final String MODE_STREAMING = "STREAMING";
    private static final String JOB_LANGUAGE_ARGUMENT = "--job-language";
    private static final String SCALA = "scala";

    private final StorageBackend<String, JobRun> runStore;
    // Per run: the order in which Floci saw it finish (a conditional trigger's position; unlike
    // CompletedOn it never ties), its trigger chain origin, and that origin's triggered run count.
    private final StorageBackend<String, JobRunBookkeeping> bookkeepingStore;
    private long nextCompletionOrder;
    private final GlueService glueService;
    private final int runDurationSeconds;
    private final Clock clock;
    private final RegionResolver regionResolver;
    // Null in the immediate runner mode, where no script executes.
    private final GlueScriptRunner scriptRunner;
    private final Executor runExecutor;
    private final String image;
    private final String dockerNetwork;
    // Script runs a thread is running, read and written only inside this service's synchronized methods; one not
    // here that has not ended was left by a restart.
    private final Set<String> executing = new HashSet<>();
    // Script runs queued in WAITING in this process; one not here was left by a restart.
    private final Set<String> waiting = new HashSet<>();
    // Raised by each reset so an in-flight script thread from before it writes nothing back after the wipe.
    private long generation;

    @Inject
    public GlueJobRunService(StorageFactory storageFactory, GlueService glueService, EmulatorConfig config,
                             RegionResolver regionResolver, DockerGlueScriptRunner dockerRunner) {
        this(new RegionScopedStorage<>(storageFactory, regionResolver, "glue", "job_runs.json", new TypeReference<>() {}),
                new RegionScopedStorage<>(storageFactory, regionResolver,
                        "glue", "job_run_bookkeeping.json", new TypeReference<>() {}),
                glueService, config.services().glue().jobRunDurationSeconds(), Clock.systemUTC(), regionResolver,
                RUNNER_DOCKER.equalsIgnoreCase(config.services().glue().runnerMode()) ? dockerRunner : null,
                Executors.newCachedThreadPool(Thread.ofPlatform().daemon().name("glue-job-run-", 0).factory()),
                config.services().glue().image(), config.services().glue().dockerNetwork().orElse(null));
        // Before any run of this process starts: a container an earlier one left belongs to a run read as FAILED.
        if (scriptRunner != null) {
            scriptRunner.removeLeftoverContainers();
        }
    }

    GlueJobRunService(StorageBackend<String, JobRun> runStore, StorageBackend<String, JobRunBookkeeping> bookkeepingStore,
                      GlueService glueService, int runDurationSeconds, Clock clock) {
        this(runStore, bookkeepingStore, glueService, runDurationSeconds, clock, null, null, Runnable::run, null, null);
    }

    GlueJobRunService(StorageBackend<String, JobRun> runStore, StorageBackend<String, JobRunBookkeeping> bookkeepingStore,
                      GlueService glueService, int runDurationSeconds, Clock clock, RegionResolver regionResolver,
                      GlueScriptRunner scriptRunner, Executor runExecutor, String image, String dockerNetwork) {
        if (runDurationSeconds < 0) {
            throw new IllegalArgumentException(
                    "floci.services.glue.job-run-duration-seconds must not be negative: " + runDurationSeconds);
        }
        this.runStore = runStore;
        this.bookkeepingStore = bookkeepingStore;
        this.glueService = glueService;
        this.runDurationSeconds = runDurationSeconds;
        this.clock = clock;
        this.regionResolver = regionResolver;
        this.scriptRunner = scriptRunner;
        this.runExecutor = runExecutor;
        this.image = image;
        this.dockerNetwork = dockerNetwork;
    }

    @PreDestroy
    void shutdown() {
        if (runExecutor instanceof ExecutorService executor) {
            executor.shutdownNow();
        }
    }

    /**
     * Starts a run of {@code jobName}. {@code overrides} carries the per-run request fields
     * (Arguments, Timeout, WorkerType, NumberOfWorkers, MaxCapacity, AllocatedCapacity,
     * SecurityConfiguration, NotificationProperty, ExecutionClass, JobRunQueuingEnabled); an
     * unset field falls back to the job definition.
     */
    public synchronized JobRun startJobRun(String jobName, String previousRunId, JobRun overrides) {
        return startJobRun(jobName, previousRunId, overrides, null, null);
    }

    /**
     * As {@link #startJobRun(String, String, JobRun)}, for a run a trigger starts on behalf of
     * {@code originRunId} (a null origin makes the run its own origin), inside {@code workflowRunId}
     * when the trigger belongs to a workflow.
     */
    public synchronized JobRun startJobRun(String jobName, String previousRunId, JobRun overrides,
                                           String originRunId, String workflowRunId) {
        Job job = glueService.getJob(jobName);
        // The job's own Timeout is stored as given by CreateJob/UpdateJob, so check what the run inherits.
        Integer timeout = firstNonNull(overrides.getTimeout(), job.getTimeout());
        if (timeout != null && timeout < 1) {
            throw new AwsException("InvalidInputException", "Timeout must be at least 1 minute.", 400);
        }
        if (previousRunId != null) {
            findRun(jobName, previousRunId);
        }

        boolean queuingEnabled = Boolean.TRUE.equals(firstNonNull(
                overrides.getJobRunQueuingEnabled(), job.getJobRunQueuingEnabled()));
        long active = runsOf(jobName).stream().filter(run -> !isTerminal(settle(run))).count();
        if (!queuingEnabled && active >= maxConcurrentRuns(job)) {
            throw new AwsException("ConcurrentRunsExceededException",
                    "Concurrent runs exceeded for " + jobName, 400);
        }

        Instant now = clock.instant();
        JobRun run = new JobRun();
        run.setId(newRunId());
        run.setAttempt(0);
        run.setPreviousRunId(previousRunId);
        run.setTriggerName(overrides.getTriggerName());
        run.setJobName(job.getName());
        run.setJobMode(job.getJobMode());
        run.setJobRunQueuingEnabled(queuingEnabled);
        run.setStartedOn(now);
        run.setLastModifiedOn(now);
        run.setArguments(overrides.getArguments());
        run.setAllocatedCapacity(firstNonNull(overrides.getAllocatedCapacity(), job.getAllocatedCapacity()));
        run.setTimeout(timeout);
        run.setMaxCapacity(firstNonNull(overrides.getMaxCapacity(), job.getMaxCapacity()));
        run.setWorkerType(firstNonNull(overrides.getWorkerType(), job.getWorkerType()));
        run.setNumberOfWorkers(firstNonNull(overrides.getNumberOfWorkers(), job.getNumberOfWorkers()));
        run.setSecurityConfiguration(
                firstNonNull(overrides.getSecurityConfiguration(), job.getSecurityConfiguration()));
        run.setNotificationProperty(
                firstNonNull(overrides.getNotificationProperty(), job.getNotificationProperty()));
        run.setExecutionClass(firstNonNull(overrides.getExecutionClass(), job.getExecutionClass()));
        run.setGlueVersion(job.getGlueVersion());
        run.setLogGroupName(LOG_GROUP_NAME);
        run.setExecutionTime(0);
        Map<String, String> arguments = scriptArguments(job, run);
        boolean script = scriptRunner != null && runsScript(job, arguments);
        boolean queuedWaiting = script && queuingEnabled && active >= maxConcurrentRuns(job);
        run.setJobRunState(queuedWaiting ? STATE_WAITING : STATE_RUNNING);
        runStore.put(run.getId(), run);
        JobRunBookkeeping bookkeeping = new JobRunBookkeeping();
        bookkeeping.setOriginRunId(originRunId != null ? originRunId : run.getId());
        bookkeeping.setWorkflowRunId(workflowRunId);
        bookkeeping.setScriptRun(script);
        bookkeepingStore.put(run.getId(), bookkeeping);
        if (queuedWaiting) {
            waiting.add(run.getId());
            LOG.infov("Queued Glue job run {0} for job {1} in WAITING", run.getId(), jobName);
            return run;
        }
        if (script) {
            launch(run, job, arguments);
        }
        LOG.infov("Started Glue job run {0} for job {1}", run.getId(), jobName);
        return settle(run);
    }

    public synchronized JobRun getJobRun(String jobName, String runId) {
        glueService.getJob(jobName);
        if (runId == null) {
            throw new AwsException("InvalidInputException", "RunId is required.", 400);
        }
        return settle(findRun(jobName, runId));
    }

    public synchronized GlueService.Page<JobRun> getJobRuns(String jobName, Integer maxResults,
                                                          String nextToken) {
        glueService.getJob(jobName);
        if (maxResults != null && (maxResults < 1 || maxResults > MAX_JOB_RUNS_PAGE_SIZE)) {
            throw new AwsException("InvalidInputException",
                    "MaxResults must be between 1 and " + MAX_JOB_RUNS_PAGE_SIZE, 400);
        }
        List<JobRun> runs = new ArrayList<>();
        for (JobRun run : runsOf(jobName)) {
            runs.add(settle(run));
        }
        runs.sort(Comparator.comparing(JobRun::getStartedOn).reversed().thenComparing(JobRun::getId));
        return glueService.paginate(runs, maxResults, nextToken);
    }

    public synchronized StopResult batchStopJobRun(String jobName, List<String> runIds) {
        if (jobName == null) {
            throw new AwsException("InvalidInputException", "JobName is required.", 400);
        }
        if (runIds == null || runIds.isEmpty()) {
            throw new AwsException("InvalidInputException", "JobRunIds is required.", 400);
        }
        List<StoppedRun> stopped = new ArrayList<>();
        List<StopError> errors = new ArrayList<>();
        for (String runId : runIds) {
            JobRun run = runStore.get(runId)
                    .filter(candidate -> jobName.equals(candidate.getJobName()))
                    .orElse(null);
            if (run == null) {
                errors.add(new StopError(jobName, runId, "EntityNotFoundException",
                        "Job run " + runId + " not found for job " + jobName));
                continue;
            }
            settle(run);
            if (isTerminal(run)) {
                errors.add(new StopError(jobName, runId, "InvalidInputException",
                        "Job run " + runId + " is not running; its state is " + run.getJobRunState()));
                continue;
            }
            waiting.remove(runId);
            complete(run, STATE_STOPPED, clock.instant(), null);
            if (scriptRunner != null) {
                scriptRunner.stop(runId);
            }
            stopped.add(new StoppedRun(jobName, runId));
        }
        if (!stopped.isEmpty() && scriptRunner != null) {
            promoteWaitingRuns(jobName);
        }
        return new StopResult(stopped, errors);
    }

    /**
     * The job's finished runs after {@code afterPosition} (all when null or empty), in the order Floci saw
     * them finish; what a conditional trigger on the job processes. Every run that has finished by now
     * is settled first, so a run settled later always comes after the last position a trigger has seen.
     */
    public synchronized List<GlueRunCompletion> completionsAfter(String jobName, String afterPosition) {
        List<GlueRunCompletion> completions = new ArrayList<>();
        for (JobRun run : runsOf(jobName)) {
            settle(run);
            if (isTerminal(run)) {
                String position = positionOf(run);
                if (afterPosition == null || afterPosition.isEmpty() || position.compareTo(afterPosition) > 0) {
                    JobRunBookkeeping bookkeeping = bookkeepingOf(run.getId());
                    completions.add(new GlueRunCompletion(position, run.getCompletedOn(), run.getJobRunState(),
                            bookkeeping.getOriginRunId(), bookkeeping.getWorkflowRunId()));
                }
            }
        }
        completions.sort(Comparator.comparing(GlueRunCompletion::position));
        return completions;
    }

    // Zero padded so that comparing positions as strings follows the completion order.
    private String positionOf(JobRun run) {
        return String.format("%020d", bookkeepingOf(run.getId()).getCompletionOrder());
    }

    /** The run count an origin job run recorded before counts moved out of run bookkeeping; 0 if none. */
    public synchronized int legacyTriggeredRuns(String originRunId) {
        return bookkeepingStore.get(originRunId).map(JobRunBookkeeping::getLegacyTriggeredRuns).orElse(0);
    }

    /**
     * Whether a run of the job could start now without {@code ConcurrentRunsExceededException}, as
     * {@link #startJobRun} decides it for a run that does not override JobRunQueuingEnabled.
     */
    public synchronized boolean hasCapacity(String jobName) {
        Job job = glueService.getJob(jobName);
        if (Boolean.TRUE.equals(job.getJobRunQueuingEnabled())) {
            return true;
        }
        long active = runsOf(jobName).stream().filter(run -> !isTerminal(settle(run))).count();
        return active < maxConcurrentRuns(job);
    }

    /** The runs, settled, that belong to a workflow run, oldest first. */
    public synchronized List<JobRun> runsInWorkflowRun(String workflowRunId) {
        List<JobRun> runs = new ArrayList<>();
        for (JobRun run : runStore.scan(key -> true)) {
            if (workflowRunId.equals(bookkeepingOf(run.getId()).getWorkflowRunId())) {
                runs.add(settle(run));
            }
        }
        runs.sort(Comparator.comparing(JobRun::getStartedOn).thenComparing(JobRun::getId));
        return runs;
    }

    /** Runs belong to their job: deleting the job removes them, as it does on AWS. */
    public synchronized void deleteRuns(String jobName) {
        for (JobRun run : runsOf(jobName)) {
            waiting.remove(run.getId());
            if (scriptRunner != null) {
                scriptRunner.stop(run.getId());
            }
            runStore.delete(run.getId());
            bookkeepingStore.delete(run.getId());
        }
    }

    /**
     * A reset has begun: runs' threads from before it write nothing more, so the storage wipe that follows
     * leaves none of their runs behind. Container teardown has already stopped their containers.
     */
    @Override
    public synchronized void beforeReset() {
        generation++;
        executing.clear();
        waiting.clear();
        nextCompletionOrder = 0;
    }

    @Override
    public void clear() {
        // Runs and bookkeeping live in storage, which the reset has already wiped.
    }

    /** Whether the job is a Spark batch job whose script is in S3, which is what a container can run. */
    private static boolean runsScript(Job job, Map<String, String> arguments) {
        // The container runs a Python batch Spark script; streaming and Scala jobs keep the simulation.
        return job.getCommand() != null && SPARK_COMMAND.equals(job.getCommand().getName())
                && !MODE_STREAMING.equalsIgnoreCase(job.getJobMode())
                && job.getCommand().getScriptLocation() != null
                && job.getCommand().getScriptLocation().startsWith("s3://")
                && !SCALA.equalsIgnoreCase(arguments.get(JOB_LANGUAGE_ARGUMENT));
    }

    /** The job's defaults, then the run's own, then non-overridable defaults, then the two the Glue library reads. */
    private static Map<String, String> scriptArguments(Job job, JobRun run) {
        Map<String, String> arguments = new LinkedHashMap<>();
        if (job.getDefaultArguments() != null) {
            arguments.putAll(job.getDefaultArguments());
        }
        if (run.getArguments() != null) {
            arguments.putAll(run.getArguments());
        }
        if (job.getNonOverridableArguments() != null) {
            arguments.putAll(job.getNonOverridableArguments());
        }
        arguments.put("--JOB_NAME", job.getName());
        arguments.put("--JOB_RUN_ID", run.getId());
        return arguments;
    }

    private void launch(JobRun run, Job job, Map<String, String> arguments) {
        Duration timeout = run.getTimeout() == null ? Duration.ZERO : Duration.ofMinutes(run.getTimeout());
        GlueScriptRunner.Run request = new GlueScriptRunner.Run(run.getId(), job.getName(),
                regionResolver.getAccountId(), regionResolver.getRegion(), job.getRole(),
                job.getCommand().getScriptLocation(), GlueScriptRunner.argumentList(arguments), image,
                dockerNetwork, run.getStartedOn(), timeout);
        executing.add(run.getId());
        long launchedIn = generation;
        try {
            runExecutor.execute(() -> execute(request, launchedIn));
        } catch (RejectedExecutionException e) {
            // Floci is shutting down.
            executing.remove(run.getId());
            complete(run, STATE_FAILED, clock.instant(), "Floci is shutting down");
        }
    }

    private void execute(GlueScriptRunner.Run request, long launchedIn) {
        GlueScriptRunner.Outcome outcome = null;
        try {
            // This thread is in no request, so the run's account and Region are set for the store it reads.
            outcome = scriptRunner.run(request, () -> RequestScopes.callAs(request.accountId(), request.region(),
                    () -> cancelled(request.runId(), launchedIn)));
        } catch (Throwable t) {
            LOG.warnv(t, "Glue job run {0} could not run", request.runId());
            String message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            outcome = GlueScriptRunner.Outcome.couldNotStart(message);
            if (t instanceof Error err) {
                throw err;
            }
        } finally {
            GlueScriptRunner.Outcome ended = outcome != null
                    ? outcome
                    : GlueScriptRunner.Outcome.couldNotStart("Job run execution failed unexpectedly");
            // Floci's shutdown interrupts this thread; its flag would fail the store's write, and it ends here anyway.
            Thread.interrupted();
            RequestScopes.runAs(request.accountId(), request.region(),
                    () -> ended(request.runId(), ended, launchedIn));
        }
    }

    /** Whether the run is over or gone, as after BatchStopJobRun, deleting its job, or a reset. */
    private synchronized boolean cancelled(String runId, long launchedIn) {
        return launchedIn != generation || runStore.get(runId).map(GlueJobRunService::isTerminal).orElse(true);
    }

    /** The script's container ended: settles the run, unless a stop, reset, or deleting its job settled it first. */
    private synchronized void ended(String runId, GlueScriptRunner.Outcome outcome, long launchedIn) {
        if (launchedIn != generation) {
            return;
        }
        executing.remove(runId);
        JobRun run = runStore.get(runId).orElse(null);
        if (run == null || isTerminal(run)) {
            return;
        }
        Instant now = clock.instant();
        if (outcome.stopped()) {
            // BatchStopJobRun settles its run before it stops the script, so a run still live here was stopped by a
            // reset or Floci's shutdown: not by the caller.
            complete(run, STATE_FAILED, now, "Floci stopped the job run before its script ended");
        } else if (outcome.timedOut()) {
            complete(run, STATE_TIMEOUT, now, "Job run exceeded the timeout of " + run.getTimeout() + " minutes");
        } else if (outcome.failure() != null) {
            complete(run, STATE_FAILED, now, outcome.failure());
        } else if (outcome.exitCode() != null && outcome.exitCode() == 0) {
            complete(run, STATE_SUCCEEDED, now, null);
        } else {
            complete(run, STATE_FAILED, now, "The job script exited with code " + outcome.exitCode());
        }
        promoteWaitingRuns(run.getJobName());
    }

    private void promoteWaitingRuns(String jobName) {
        if (scriptRunner == null) {
            return;
        }
        Job job;
        try {
            job = glueService.getJob(jobName);
        } catch (AwsException e) {
            // The job was deleted while its run was ending.
            return;
        }
        List<JobRun> runs = runsOf(jobName);
        long running = 0;
        List<JobRun> queued = new ArrayList<>();
        for (JobRun candidate : runs) {
            settle(candidate);
            if (STATE_RUNNING.equals(candidate.getJobRunState())) {
                running++;
            } else if (STATE_WAITING.equals(candidate.getJobRunState())) {
                queued.add(candidate);
            }
        }
        if (queued.isEmpty()) {
            return;
        }
        queued.sort(Comparator.comparing(JobRun::getStartedOn).thenComparing(JobRun::getId));
        int limit = maxConcurrentRuns(job);
        for (JobRun next : queued) {
            if (running >= limit) {
                break;
            }
            waiting.remove(next.getId());
            Instant now = clock.instant();
            next.setJobRunState(STATE_RUNNING);
            next.setStartedOn(now);
            next.setLastModifiedOn(now);
            Map<String, String> arguments = scriptArguments(job, next);
            if (!runsScript(job, arguments)) {
                JobRunBookkeeping entry = bookkeepingOf(next.getId());
                entry.setScriptRun(false);
                bookkeepingStore.put(next.getId(), entry);
                runStore.put(next.getId(), next);
                settle(next);
                if (!isTerminal(next)) {
                    running++;
                }
                continue;
            }
            runStore.put(next.getId(), next);
            launch(next, job, arguments);
            if (isTerminal(next)) {
                // RejectedExecutionException means Floci is shutting down.
                break;
            }
            running++;
            LOG.infov("Promoted queued Glue job run {0} for job {1} to RUNNING", next.getId(), jobName);
        }
    }

    private JobRun settle(JobRun run) {
        if (isTerminal(run)) {
            return run;
        }
        if (bookkeepingOf(run.getId()).isScriptRun()) {
            if (STATE_WAITING.equals(run.getJobRunState())) {
                if (!waiting.contains(run.getId())) {
                    complete(run, STATE_FAILED, clock.instant(), "Floci restarted while the job run was waiting");
                }
                return run;
            }
            // The script's end settles the run, not a clock; one no thread runs was left by a restart.
            if (!executing.contains(run.getId())) {
                complete(run, STATE_FAILED, clock.instant(), "Floci restarted while the job run was running");
            }
            return run;
        }
        Instant startedOn = run.getStartedOn();
        long timeoutSeconds = run.getTimeout() == null ? Long.MAX_VALUE : run.getTimeout() * 60L;
        if (runDurationSeconds >= timeoutSeconds) {
            Instant timedOutAt = startedOn.plusSeconds(timeoutSeconds);
            if (!clock.instant().isBefore(timedOutAt)) {
                complete(run, STATE_TIMEOUT, timedOutAt,
                        "Job run exceeded the timeout of " + run.getTimeout() + " minutes");
            }
            return run;
        }
        Instant finishedAt = startedOn.plusSeconds(runDurationSeconds);
        if (!clock.instant().isBefore(finishedAt)) {
            complete(run, STATE_SUCCEEDED, finishedAt, null);
        }
        return run;
    }

    private void complete(JobRun run, String state, Instant completedOn, String errorMessage) {
        run.setJobRunState(state);
        run.setCompletedOn(completedOn);
        run.setLastModifiedOn(completedOn);
        run.setExecutionTime((int) Duration.between(run.getStartedOn(), completedOn).toSeconds());
        run.setErrorMessage(errorMessage);
        runStore.put(run.getId(), run);
        JobRunBookkeeping bookkeeping = bookkeepingOf(run.getId());
        bookkeeping.setCompletionOrder(nextCompletionOrder());
        bookkeepingStore.put(run.getId(), bookkeeping);
    }

    private long nextCompletionOrder() {
        if (nextCompletionOrder == 0) {
            long highest = 0;
            for (JobRunBookkeeping bookkeeping : bookkeepingStore.scan(key -> true)) {
                highest = Math.max(highest, bookkeeping.getCompletionOrder());
            }
            nextCompletionOrder = highest + 1;
        }
        return nextCompletionOrder++;
    }

    private JobRunBookkeeping bookkeepingOf(String runId) {
        return bookkeepingStore.get(runId).orElseGet(() -> {
            JobRunBookkeeping fresh = new JobRunBookkeeping();
            fresh.setOriginRunId(runId);
            return fresh;
        });
    }

    private JobRun findRun(String jobName, String runId) {
        return runStore.get(runId)
                .filter(run -> jobName.equals(run.getJobName()))
                .orElseThrow(() -> new AwsException("EntityNotFoundException",
                        "Job run " + runId + " not found for job " + jobName, 400));
    }

    private List<JobRun> runsOf(String jobName) {
        List<JobRun> runs = new ArrayList<>();
        for (JobRun run : runStore.scan(key -> true)) {
            if (jobName.equals(run.getJobName())) {
                runs.add(run);
            }
        }
        return runs;
    }

    private static boolean isTerminal(JobRun run) {
        return TERMINAL_STATES.contains(run.getJobRunState());
    }

    private static int maxConcurrentRuns(Job job) {
        ExecutionProperty property = job.getExecutionProperty();
        if (property == null || property.getMaxConcurrentRuns() == null) {
            return DEFAULT_MAX_CONCURRENT_RUNS;
        }
        return property.getMaxConcurrentRuns();
    }

    // Glue job run ids are "jr_" followed by 64 hex characters.
    private static String newRunId() {
        return "jr_" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    private static <T> T firstNonNull(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }

    public record StoppedRun(String jobName, String jobRunId) {}

    public record StopError(String jobName, String jobRunId, String errorCode, String errorMessage) {}

    public record StopResult(List<StoppedRun> stopped, List<StopError> errors) {}
}
