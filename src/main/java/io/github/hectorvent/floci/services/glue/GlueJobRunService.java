package io.github.hectorvent.floci.services.glue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.glue.model.ExecutionProperty;
import io.github.hectorvent.floci.services.glue.model.Job;
import io.github.hectorvent.floci.services.glue.model.JobRun;
import io.github.hectorvent.floci.services.glue.model.JobRunBookkeeping;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Glue job runs. No script executes: a run follows Glue's state machine and its response shape,
 * and settles lazily when read. With the default {@code job-run-duration-seconds} of 0 a run is
 * SUCCEEDED as soon as it starts; a positive duration keeps it RUNNING until that much time has
 * passed, which is what makes BatchStopJobRun and MaxConcurrentRuns observable.
 *
 * <p>Every public method is synchronized: the store has no atomic compare and set, so admitting a
 * run (count the live runs, check the limit, write the run), stopping one, settling one and
 * removing a deleted job's runs must not interleave.
 */
@ApplicationScoped
public class GlueJobRunService {

    private static final Logger LOG = Logger.getLogger(GlueJobRunService.class);

    static final String STATE_RUNNING = "RUNNING";
    static final String STATE_SUCCEEDED = "SUCCEEDED";
    static final String STATE_STOPPED = "STOPPED";
    static final String STATE_TIMEOUT = "TIMEOUT";

    private static final Set<String> TERMINAL_STATES =
            Set.of(STATE_SUCCEEDED, STATE_STOPPED, "FAILED", STATE_TIMEOUT, "ERROR", "EXPIRED");

    // ExecutionProperty.MaxConcurrentRuns defaults to 1 when a job does not set it.
    private static final int DEFAULT_MAX_CONCURRENT_RUNS = 1;
    private static final int MAX_JOB_RUNS_PAGE_SIZE = 200;
    private static final String LOG_GROUP_NAME = "/aws-glue/jobs";

    private final StorageBackend<String, JobRun> runStore;
    // Per run: the order in which Floci saw it finish (a conditional trigger's position; unlike
    // CompletedOn it never ties), its trigger chain origin, and that origin's triggered run count.
    private final StorageBackend<String, JobRunBookkeeping> bookkeepingStore;
    private long nextCompletionOrder;
    private final GlueService glueService;
    private final int runDurationSeconds;
    private final Clock clock;

    @Inject
    public GlueJobRunService(StorageFactory storageFactory, GlueService glueService, EmulatorConfig config) {
        this(storageFactory.create("glue", "job_runs.json", new TypeReference<>() {}),
                storageFactory.create("glue", "job_run_bookkeeping.json", new TypeReference<>() {}),
                glueService, config.services().glue().jobRunDurationSeconds(), Clock.systemUTC());
    }

    GlueJobRunService(StorageBackend<String, JobRun> runStore, StorageBackend<String, JobRunBookkeeping> bookkeepingStore,
                      GlueService glueService, int runDurationSeconds, Clock clock) {
        if (runDurationSeconds < 0) {
            throw new IllegalArgumentException(
                    "floci.services.glue.job-run-duration-seconds must not be negative: " + runDurationSeconds);
        }
        this.runStore = runStore;
        this.bookkeepingStore = bookkeepingStore;
        this.glueService = glueService;
        this.runDurationSeconds = runDurationSeconds;
        this.clock = clock;
    }

    /**
     * Starts a run of {@code jobName}. {@code overrides} carries the per-run request fields
     * (Arguments, Timeout, WorkerType, NumberOfWorkers, MaxCapacity, AllocatedCapacity,
     * SecurityConfiguration, NotificationProperty, ExecutionClass, JobRunQueuingEnabled); an
     * unset field falls back to the job definition.
     */
    public synchronized JobRun startJobRun(String jobName, String previousRunId, JobRun overrides) {
        return startJobRun(jobName, previousRunId, overrides, null);
    }

    /**
     * As {@link #startJobRun(String, String, JobRun)}, for a run a trigger starts on behalf of
     * {@code originRunId}; a null origin makes the run its own origin.
     */
    public synchronized JobRun startJobRun(String jobName, String previousRunId, JobRun overrides,
                                           String originRunId) {
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
        run.setJobRunState(STATE_RUNNING);
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
        runStore.put(run.getId(), run);
        JobRunBookkeeping bookkeeping = new JobRunBookkeeping();
        bookkeeping.setOriginRunId(originRunId != null ? originRunId : run.getId());
        bookkeepingStore.put(run.getId(), bookkeeping);
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
            complete(run, STATE_STOPPED, clock.instant(), null);
            stopped.add(new StoppedRun(jobName, runId));
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
                    completions.add(new GlueRunCompletion(position, run.getCompletedOn(), run.getJobRunState(),
                            bookkeepingOf(run.getId()).getOriginRunId()));
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

    /** Runs belong to their job: deleting the job removes them, as it does on AWS. */
    public synchronized void deleteRuns(String jobName) {
        for (JobRun run : runsOf(jobName)) {
            runStore.delete(run.getId());
            bookkeepingStore.delete(run.getId());
        }
    }

    private JobRun settle(JobRun run) {
        if (isTerminal(run)) {
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
