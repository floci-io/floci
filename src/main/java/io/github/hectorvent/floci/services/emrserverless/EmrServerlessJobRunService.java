package io.github.hectorvent.floci.services.emrserverless;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.emrserverless.model.Application;
import io.github.hectorvent.floci.services.emrserverless.model.JobDriver;
import io.github.hectorvent.floci.services.emrserverless.model.JobRun;
import io.github.hectorvent.floci.services.emrserverless.model.JobRunAttemptSummary;
import io.github.hectorvent.floci.services.emrserverless.model.JobRunRecord;
import io.github.hectorvent.floci.services.emrserverless.model.JobRunSummary;
import io.github.hectorvent.floci.services.emrserverless.model.RuntimeConfiguration;
import io.github.hectorvent.floci.services.emrserverless.model.StartJobRunRequest;
import io.github.hectorvent.floci.services.emrserverless.model.WorkerTypeSpecification;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EMR Serverless job runs. In the default {@code immediate} runner mode nothing executes: a run
 * follows the job run states and GetJobRun's shape, and settles lazily when read. Times are kept to
 * the millisecond, as AWS reports them. With the default {@code job-run-duration-seconds} of 0 a
 * BATCH run is SUCCESS as soon as it starts; a positive duration keeps it RUNNING that long, which
 * makes CancelJobRun and the execution timeout observable. A STREAMING run, which EMR Serverless
 * "expects to run for a long time", stays RUNNING until it is cancelled.
 *
 * <p>In the {@code docker} runner mode a Spark run executes: it is SCHEDULED while its container is
 * prepared, RUNNING once its Spark driver runs, and ends as the driver does, SUCCESS on exit code 0
 * and FAILED otherwise. Cancelling it, or its execution timeout, makes it CANCELLING until the driver
 * is stopped, then CANCELLED.
 *
 * <p>Every public method is synchronized: admitting a run (look up its client token, then write
 * it), settling one and cancelling one each read and then write a run, and must not interleave. A
 * container run's changes come from the thread that runs it, under the same lock.
 */
@ApplicationScoped
public class EmrServerlessJobRunService implements Resettable {

    private static final Logger LOG = Logger.getLogger(EmrServerlessJobRunService.class);

    static final String STATE_SCHEDULED = "SCHEDULED";
    static final String STATE_RUNNING = "RUNNING";
    static final String STATE_SUCCESS = "SUCCESS";
    static final String STATE_FAILED = "FAILED";
    static final String STATE_CANCELLING = "CANCELLING";
    static final String STATE_CANCELLED = "CANCELLED";
    private static final String MODE_BATCH = "BATCH";
    private static final String MODE_STREAMING = "STREAMING";
    private static final String RUNNER_DOCKER = "docker";
    // The service a container run's driver belongs to, which names it and labels it.
    private static final String SERVICE = "emr-serverless";
    private static final String SPARK_DEFAULTS = "spark-defaults";
    private static final String S3_MONITORING = "s3MonitoringConfiguration";
    private static final String CLOUD_WATCH_LOGGING = "cloudWatchLoggingConfiguration";
    // "If you don't specify the optional values, then CloudWatch publishes the logs to a default log group".
    private static final String DEFAULT_LOG_GROUP = "/aws/emr-serverless";
    private static final String SPARK_DRIVER = "SPARK_DRIVER";
    // The worker type a Spark application's driver runs as.
    private static final String DRIVER_WORKER_TYPE = "Driver";
    // No source gives AWS's wording for these; they are Floci's.
    static final String HIVE_NOT_RUN = "Floci runs Spark job runs only, and this is a Hive job run.";
    static final String INTERRUPTED = "Floci stopped while the job run was in progress.";

    private static final Set<String> TERMINAL_STATES = Set.of(STATE_SUCCESS, STATE_FAILED, STATE_CANCELLED);
    private static final List<String> STATES = List.of("SUBMITTED", "PENDING", "SCHEDULED", STATE_RUNNING,
            STATE_SUCCESS, "FAILED", "CANCELLING", STATE_CANCELLED, "QUEUED");
    // JobRunStateSet holds at most 8 states.
    private static final int MAX_FILTER_STATES = 8;
    private static final List<String> MODES = List.of(MODE_BATCH, MODE_STREAMING);
    // JobRun.executionTimeoutMinutes: "If you didn't specify a timeout, this value defaults to 720 minutes."
    private static final long DEFAULT_EXECUTION_TIMEOUT_MINUTES = 720;
    // The model's Duration, which bounds executionTimeoutMinutes.
    private static final long MAX_EXECUTION_TIMEOUT_MINUTES = 1_000_000;
    // RetryPolicy's members: at least 1 in the model, and "Accepted values are 1 to 10, inclusive" for each in
    // the user guide's "Job resiliency".
    private static final List<String> RETRY_LIMITS = List.of("maxAttempts", "maxFailedAttemptsPerHour");
    private static final int MAX_RETRY_LIMIT = 10;
    // The EMR Serverless 7.11.0 release notes: for BATCH job runs the maximum "is 7 days from this release
    // onwards", and the timeout "can no longer be set to 0".
    private static final long MAX_BATCH_EXECUTION_TIMEOUT_MINUTES = 10_080;
    private static final Pattern RELEASE_LABEL = Pattern.compile("emr-(\\d{1,6})\\.(\\d{1,6})(?:\\..*)?");
    // The AWS runtime for Apache Spark, emr-spark-8.0.0 on (and emr-spark-8.0-preview).
    private static final Pattern SPARK_RUNTIME_RELEASE_LABEL =
            Pattern.compile("emr-spark-(\\d{1,6})\\.(\\d{1,6})(?:[.-].*)?");
    // A run's first attempt; a retried run's later ones count on from it.
    private static final int FIRST_ATTEMPT = 1;
    // maxAttempts: "The default is 1, which means to not rerun the job."
    private static final int DEFAULT_MAX_ATTEMPTS = 1;
    // maxFailedAttemptsPerHour: "The default is five."
    private static final int DEFAULT_MAX_FAILED_ATTEMPTS_PER_HOUR = 5;
    private static final int MAX_LIST_RESULTS = 50;
    // IAMRoleArn's pattern, as the model and AWS's validation message spell it.
    private static final String ROLE_ARN_PATTERN =
            "arn:(aws[a-zA-Z0-9-]*):iam::([0-9]{12}):(role((\\u002F)|(\\u002F[\\u0021-\\u007F]+\\u002F))[\\w+=,.@-]+)";
    private static final Pattern ROLE_ARN = Pattern.compile(ROLE_ARN_PATTERN);

    private final AccountAwareStorageBackend<JobRunRecord> store;
    private final EmrServerlessService applications;
    private final IamService iamService;
    private final RegionResolver regionResolver;
    private final RequestContext requestContext;
    private final ObjectMapper objectMapper;
    private final int runDurationSeconds;
    private final Clock clock;
    // Null in the immediate runner mode.
    private final SparkJobRunner sparkRunner;
    private final Executor runExecutor;
    // Where a container run's driver runs: image-template, with the release label for %s, and docker-network;
    // null when not set.
    private final String imageTemplate;
    private final String dockerNetwork;
    // Container runs a thread is running; one not here that has not ended was left by a restart.
    private final Set<String> executing = new HashSet<>();
    // Raised by each reset, so a run's thread from before it writes nothing back after the wipe.
    private long generation;

    @Inject
    public EmrServerlessJobRunService(StorageFactory storageFactory, EmrServerlessService applications,
                                      IamService iamService, RegionResolver regionResolver,
                                      RequestContext requestContext, ObjectMapper objectMapper,
                                      EmulatorConfig config, Clock clock, EmrServerlessSparkRunner sparkRunner) {
        this(storageFactory.create("emrserverless", "emr-serverless-job-runs.json", new TypeReference<>() {}),
                applications, iamService, regionResolver, requestContext, objectMapper,
                config.services().emrserverless().jobRunDurationSeconds(), clock,
                RUNNER_DOCKER.equalsIgnoreCase(config.services().emrserverless().runnerMode()) ? sparkRunner : null,
                Executors.newCachedThreadPool(Thread.ofPlatform().daemon().name("emr-serverless-run-", 0).factory()),
                config.services().emrserverless().imageTemplate().orElse(null),
                config.services().emrserverless().dockerNetwork().orElse(null));
        // Before any run of this process starts: a driver an earlier one left belongs to a run read as FAILED.
        if (this.sparkRunner != null && config.services().emrserverless().reconcileContainersOnStartup()) {
            sparkRunner.removeLeftoverDrivers(SERVICE);
        }
    }

    EmrServerlessJobRunService(AccountAwareStorageBackend<JobRunRecord> store, EmrServerlessService applications,
                               IamService iamService, RegionResolver regionResolver, RequestContext requestContext,
                               ObjectMapper objectMapper, int runDurationSeconds, Clock clock) {
        this(store, applications, iamService, regionResolver, requestContext, objectMapper, runDurationSeconds, clock,
                null, Runnable::run);
    }

    EmrServerlessJobRunService(AccountAwareStorageBackend<JobRunRecord> store, EmrServerlessService applications,
                               IamService iamService, RegionResolver regionResolver, RequestContext requestContext,
                               ObjectMapper objectMapper, int runDurationSeconds, Clock clock,
                               SparkJobRunner sparkRunner, Executor runExecutor) {
        this(store, applications, iamService, regionResolver, requestContext, objectMapper, runDurationSeconds, clock,
                sparkRunner, runExecutor, null, null);
    }

    private EmrServerlessJobRunService(AccountAwareStorageBackend<JobRunRecord> store,
                                       EmrServerlessService applications, IamService iamService,
                                       RegionResolver regionResolver, RequestContext requestContext,
                                       ObjectMapper objectMapper, int runDurationSeconds, Clock clock,
                                       SparkJobRunner sparkRunner, Executor runExecutor,
                                       String imageTemplate, String dockerNetwork) {
        if (runDurationSeconds < 0) {
            throw new IllegalArgumentException(
                    "floci.services.emrserverless.job-run-duration-seconds must not be negative: " + runDurationSeconds);
        }
        this.store = store;
        this.applications = applications;
        this.iamService = iamService;
        this.regionResolver = regionResolver;
        this.requestContext = requestContext;
        this.objectMapper = objectMapper;
        this.runDurationSeconds = runDurationSeconds;
        this.clock = clock;
        this.sparkRunner = sparkRunner;
        this.runExecutor = runExecutor;
        this.imageTemplate = imageTemplate;
        this.dockerNetwork = dockerNetwork;
    }

    @PreDestroy
    void shutdown() {
        if (runExecutor instanceof ExecutorService executor) {
            executor.shutdownNow();
        }
    }

    /**
     * Starts a run, or returns the run an earlier request with the same client token started. The
     * token is StartJobRun's idempotency token; reused with a different request it answers
     * {@code ConflictException} "Request did not match the original", as AWS does.
     */
    public synchronized JobRun startJobRun(String applicationId, StartJobRunRequest request) {
        StartJobRunRequest body = request != null ? request : new StartJobRunRequest();
        validate(body);
        applications.getApplication(applicationId);
        JsonNode original = requestOf(applicationId, body);
        JobRunRecord existing = runWithClientToken(body.getClientToken());
        if (existing != null) {
            if (!original.equals(existing.getRequest())) {
                throw new AwsException("ConflictException", "Request did not match the original", 409);
            }
            return settle(existing).getJobRun();
        }
        // Before admission, which may auto-start the application: a refused run leaves it as it was.
        if (body.getTags() != null) {
            EmrServerlessService.validateTags(body.getTags());
        }
        JobRunRecord record = applications.admitJobRun(applicationId, app -> checkBatchTimeout(app, body), app -> {
            JobRunRecord admitted = newRun(app, body, original);
            if (sparkRunner != null) {
                launch(admitted, app);
            }
            return admitted;
        });
        LOG.infov("Started EMR Serverless job run {0} on application {1}", record.getJobRun().getJobRunId(),
                applicationId);
        return settle(record).getJobRun();
    }

    /**
     * The run, as its {@code attempt} saw it; without one, "the latest attempt". No source gives AWS's
     * answer for an attempt a run never made; ResourceNotFoundException, in Floci's words, is the modeled
     * error that fits.
     */
    public synchronized JobRun getJobRun(String applicationId, String jobRunId, Integer attempt) {
        if (attempt != null) {
            String violation = SmithyValidationErrors.outOfRange("attempt", attempt, FIRST_ATTEMPT,
                    Integer.MAX_VALUE);
            if (violation != null) {
                throw SmithyValidationErrors.validationException(violation);
            }
        }
        applications.getApplication(applicationId);
        JobRunRecord record = findRun(applicationId, jobRunId);
        // Refused before the run settles: settling never makes an attempt, only a run's thread does.
        if (attempt != null && attempts(record).stream().noneMatch(made -> attempt.equals(made.getAttempt()))) {
            throw new AwsException("ResourceNotFoundException",
                    "Job run " + jobRunId + " has no attempt " + attempt, 404);
        }
        settle(record);
        JobRun run = attempts(record).stream()
                .filter(made -> attempt == null ? made == record.getJobRun() : attempt.equals(made.getAttempt()))
                .findFirst()
                .orElseThrow();
        // A copy: a container run's thread may change the stored run while the response is written.
        return objectMapper.convertValue(run, JobRun.class);
    }

    /** A run's attempts, oldest first: "information about all attempts related to a job run". */
    public synchronized PaginatedResult<JobRunAttemptSummary> listJobRunAttempts(String applicationId,
                                                                               String jobRunId,
                                                                               Integer maxResults,
                                                                               String nextToken) {
        if (maxResults != null) {
            String violation = SmithyValidationErrors.outOfRange("maxResults", maxResults, 1, MAX_LIST_RESULTS);
            if (violation != null) {
                throw SmithyValidationErrors.validationException(violation);
            }
        }
        applications.getApplication(applicationId);
        JobRunRecord record = settle(findRun(applicationId, jobRunId));
        PaginatedResult<JobRun> page = Pagination.paginate(attempts(record),
                made -> String.format("%010d", made.getAttempt()), maxResults != null ? maxResults : MAX_LIST_RESULTS,
                nextToken, String.join("|", "ListJobRunAttempts", applicationId, jobRunId),
                token -> new AwsException("ValidationException", "Invalid nextToken.", 400));
        return new PaginatedResult<>(
                page.items().stream().map(made -> toAttemptSummary(record, made)).toList(),
                page.nextToken());
    }

    /**
     * The application's runs, newest first. ListJobRuns models no ResourceNotFoundException, so an
     * unknown application has no runs rather than an error. With several {@code states}, "the
     * resulting list will be grouped by the state", in the order the filter names them. A run
     * started without a mode is a BATCH run. A {@code nextToken} resumes only the listing it came
     * from: its cursor holds a state group, which means nothing under another application or filter.
     */
    public synchronized PaginatedResult<JobRunSummary> listJobRuns(String applicationId, List<String> states,
                                                                   Instant createdAtAfter, Instant createdAtBefore,
                                                                   String mode, Integer maxResults, String nextToken) {
        List<String> violations = new ArrayList<>();
        if (maxResults != null) {
            String violation = SmithyValidationErrors.outOfRange("maxResults", maxResults, 1, MAX_LIST_RESULTS);
            if (violation != null) {
                violations.add(violation);
            }
        }
        violations.addAll(EmrServerlessService.statesViolations(states, STATES, MAX_FILTER_STATES));
        if (mode != null && !MODES.contains(mode)) {
            violations.add(SmithyValidationErrors.notInEnum(mode, "mode", MODES));
        }
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
        List<JobRunRecord> matching = new ArrayList<>();
        Map<String, String> cursors = new HashMap<>();
        for (JobRunRecord record : store.scan(key -> true)) {
            JobRun run = record.getJobRun();
            if (!applicationId.equals(run.getApplicationId()) || !inRequestRegion(run)) {
                continue;
            }
            settle(record);
            if ((states != null && !states.isEmpty() && !states.contains(run.getState()))
                    || (createdAtAfter != null && !run.getCreatedAt().isAfter(createdAtAfter))
                    || (createdAtBefore != null && !run.getCreatedAt().isBefore(createdAtBefore))
                    || (mode != null && !mode.equals(run.getMode() != null ? run.getMode() : MODE_BATCH))) {
                continue;
            }
            int group = states == null || states.isEmpty() ? 0 : states.indexOf(run.getState());
            cursors.put(run.getJobRunId(), String.format("%02d%020d%s", group,
                    Long.MAX_VALUE - run.getCreatedAt().toEpochMilli(), run.getJobRunId()));
            matching.add(record);
        }
        String namespace = String.join("|", "ListJobRuns", applicationId,
                String.valueOf(states != null ? states : List.of()), String.valueOf(createdAtAfter),
                String.valueOf(createdAtBefore), String.valueOf(mode));
        PaginatedResult<JobRunRecord> page = Pagination.paginate(matching,
                record -> cursors.get(record.getJobRun().getJobRunId()),
                maxResults != null ? maxResults : MAX_LIST_RESULTS, nextToken, namespace,
                token -> new AwsException("ValidationException", "Invalid nextToken.", 400));
        return new PaginatedResult<>(page.items().stream().map(EmrServerlessJobRunService::toSummary).toList(),
                page.nextToken());
    }

    /**
     * Cancels a run that has not finished. A finished run is refused, with AWS's wording. A container
     * run is CANCELLING until its driver has stopped.
     */
    public synchronized JobRun cancelJobRun(String applicationId, String jobRunId) {
        applications.getApplication(applicationId);
        JobRunRecord record = settle(findRun(applicationId, jobRunId));
        if (isTerminal(record.getJobRun())) {
            throw new AwsException("ValidationException", "Job run is not in a cancellable state", 400);
        }
        if (record.isContainerRun()) {
            JobRun run = record.getJobRun();
            if (!STATE_CANCELLING.equals(run.getState())) {
                markCancelling(record);
                runExecutor.execute(() -> sparkRunner.stop(jobRunId));
                LOG.infov("Cancelling EMR Serverless job run {0} on application {1}", jobRunId, applicationId);
            }
            return run;
        }
        finish(record, STATE_CANCELLED, clock.instant().truncatedTo(ChronoUnit.MILLIS));
        LOG.infov("Cancelled EMR Serverless job run {0} on application {1}", jobRunId, applicationId);
        return record.getJobRun();
    }

    public synchronized Map<String, String> listTags(EmrServerlessService.ResourceArn resource) {
        JobRun run = runFor(resource).getJobRun();
        return run.getTags() != null ? new LinkedHashMap<>(run.getTags()) : new LinkedHashMap<>();
    }

    public synchronized void tagResource(EmrServerlessService.ResourceArn resource, Map<String, String> tags) {
        JobRunRecord record = runFor(resource);
        record.getJobRun().setTags(EmrServerlessService.withTags(record.getJobRun().getTags(), tags));
        store.put(record.getJobRun().getJobRunId(), record);
    }

    public synchronized void untagResource(EmrServerlessService.ResourceArn resource, List<String> tagKeys) {
        JobRunRecord record = runFor(resource);
        record.getJobRun().setTags(EmrServerlessService.withoutTags(record.getJobRun().getTags(), tagKeys));
        store.put(record.getJobRun().getJobRunId(), record);
    }

    /** GetJobRun's {@code attempt}, an integer in the query string. */
    static Integer parseAttempt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationException", "attempt must be an integer.", 400);
        }
    }

    /**
     * A ListJobRuns time filter. Query-string timestamps are ISO 8601 in restJson1; epoch seconds
     * are accepted too.
     */
    static Instant parseTimestamp(String value, String member) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException notIso) {
            try {
                return Instant.ofEpochMilli(Math.round(Double.parseDouble(value) * 1000));
            } catch (NumberFormatException notEpoch) {
                throw new AwsException("ValidationException", member + " must be a timestamp.", 400);
            }
        }
    }

    private JobRunRecord newRun(Application app, StartJobRunRequest request, JsonNode original) {
        AwsArnUtils.Arn applicationArn = AwsArnUtils.parse(app.getArn());
        String jobRunId = EmrServerlessService.newId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);

        JobRun run = new JobRun();
        run.setApplicationId(app.getApplicationId());
        run.setJobRunId(jobRunId);
        run.setName(request.getName());
        run.setArn(AwsArnUtils.Arn.of("emr-serverless", applicationArn.region(), applicationArn.accountId(),
                applicationArn.resource() + "/jobruns/" + jobRunId).toString());
        run.setCreatedBy(createdBy(applicationArn.accountId()));
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        run.setExecutionRole(request.getExecutionRoleArn());
        run.setExecutionIamPolicy(request.getExecutionIamPolicy());
        run.setState(sparkRunner != null ? STATE_SCHEDULED : STATE_RUNNING);
        run.setStateDetails("");
        run.setReleaseLabel(app.getReleaseLabel());
        run.setConfigurationOverrides(request.getConfigurationOverrides());
        run.setJobDriver(request.getJobDriver());
        run.setTags(request.getTags() != null ? new LinkedHashMap<>(request.getTags()) : new LinkedHashMap<>());
        run.setNetworkConfiguration(app.getNetworkConfiguration());
        run.setExecutionTimeoutMinutes(request.getExecutionTimeoutMinutes() != null
                ? request.getExecutionTimeoutMinutes() : DEFAULT_EXECUTION_TIMEOUT_MINUTES);
        run.setMode(request.getMode());
        run.setRetryPolicy(request.getRetryPolicy());
        run.setAttempt(FIRST_ATTEMPT);
        run.setAttemptCreatedAt(now);
        run.setAttemptUpdatedAt(now);
        if (sparkRunner == null) {
            run.setStartedAt(now);
        }

        JobRunRecord record = new JobRunRecord();
        record.setJobRun(run);
        record.setClientToken(request.getClientToken());
        record.setRequest(original);
        record.setApplicationType(app.getType());
        record.setContainerRun(sparkRunner != null);
        store.put(jobRunId, record);
        return record;
    }

    /** Hands a container run to a thread of its own, which runs it to its end. */
    private void launch(JobRunRecord record, Application app) {
        JobRun run = record.getJobRun();
        if (run.getJobDriver().getSparkSubmit() == null) {
            run.setStateDetails(HIVE_NOT_RUN);
            finish(record, STATE_FAILED, run.getCreatedAt());
            return;
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(run.getArn());
        // A STREAMING run "expects to run for a long time", so only a BATCH run's timeout ends it.
        Long minutes = run.getExecutionTimeoutMinutes();
        Duration timeout = MODE_STREAMING.equals(run.getMode()) || minutes == null || minutes == 0
                ? Duration.ZERO : Duration.ofMinutes(minutes);
        SparkJobRunner.Job job = new SparkJobRunner.Job(SERVICE, run.getJobRunId(), arn.accountId(), arn.region(),
                run.getReleaseLabel(), imageTemplate, customImage(app), dockerNetwork, run.getExecutionRole(),
                sparkDefaults(app, run), application(run.getJobDriver().getSparkSubmit()), null, timeout,
                driverLogFolder(app, run), driverCloudWatchLogs(app, run));
        executing.add(run.getJobRunId());
        long launchedIn = generation;
        runExecutor.execute(() -> execute(job, launchedIn));
    }

    private void execute(SparkJobRunner.Job job, long launchedIn) {
        SparkJobRunner.Outcome outcome;
        try {
            outcome = sparkRunner.run(job, new RunProgress(job, launchedIn));
        } catch (RuntimeException e) {
            LOG.warnv(e, "EMR Serverless job run {0} could not run", job.runId());
            outcome = SparkJobRunner.Outcome.couldNotStart(e.getMessage());
        }
        SparkJobRunner.Outcome ended = outcome;
        RequestScopes.runAs(job.accountId(), job.region(), () -> ended(job.runId(), ended, launchedIn));
    }

    /**
     * The custom image a run's driver starts from, or null for the release's: the {@code Driver} worker type's,
     * else the application's, which "EMR Serverless applies ... to all worker types". Local mode runs the
     * executors in the driver's container, so an {@code Executor} image is not used.
     */
    static String customImage(Application app) {
        WorkerTypeSpecification driver = app.getWorkerTypeSpecifications() != null
                ? app.getWorkerTypeSpecifications().get(DRIVER_WORKER_TYPE) : null;
        if (driver != null && driver.getImageConfiguration() != null
                && driver.getImageConfiguration().getImageUri() != null) {
            return driver.getImageConfiguration().getImageUri();
        }
        return app.getImageConfiguration() != null ? app.getImageConfiguration().getImageUri() : null;
    }

    /**
     * What follows Floci's own settings on a run's {@code spark-submit} command line: its
     * {@code sparkSubmitParameters}, split as a shell would split them, then its entry point and the
     * entry point's arguments.
     */
    static List<String> application(JobDriver.SparkSubmit sparkSubmit) {
        List<String> application = new ArrayList<>(
                EmrServerlessSparkRunner.splitParameters(sparkSubmit.getSparkSubmitParameters()));
        application.add(sparkSubmit.getEntryPoint());
        if (sparkSubmit.getEntryPointArguments() != null) {
            application.addAll(sparkSubmit.getEntryPointArguments());
        }
        return application;
    }

    /**
     * A reset has begun: the runs' threads from before it write nothing more, so the storage wipe that
     * follows leaves none of their runs behind. Its teardown has already stopped their drivers.
     */
    @Override
    public synchronized void beforeReset() {
        generation++;
        executing.clear();
    }

    @Override
    public void clear() {
        // The runs live in storage, which the reset has already wiped.
    }

    // Whether a run's driver may still start, or go on: a cancelled run is past it, and so is one a reset removed.
    private synchronized boolean stillGoing(String jobRunId, long launchedIn) {
        return launchedIn == generation && store.get(jobRunId)
                .map(record -> STATE_SCHEDULED.equals(record.getJobRun().getState())
                        || STATE_RUNNING.equals(record.getJobRun().getState()))
                .orElse(false);
    }

    private synchronized void driverStarted(String jobRunId, long launchedIn) {
        if (launchedIn != generation) {
            return;
        }
        store.get(jobRunId).filter(record -> STATE_SCHEDULED.equals(record.getJobRun().getState()))
                .ifPresent(record -> {
                    JobRun run = record.getJobRun();
                    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
                    run.setState(STATE_RUNNING);
                    run.setStartedAt(now);
                    run.setUpdatedAt(now);
                    run.setAttemptUpdatedAt(now);
                    store.put(jobRunId, record);
                    LOG.infov("EMR Serverless job run {0} is running", jobRunId);
                });
    }

    // A run past its timeout is CANCELLING while its driver is stopped: "the job run has timed out".
    private synchronized void driverTimedOut(String jobRunId, long launchedIn) {
        if (launchedIn != generation) {
            return;
        }
        store.get(jobRunId).filter(record -> STATE_RUNNING.equals(record.getJobRun().getState()))
                .ifPresent(this::markCancelling);
    }

    private void markCancelling(JobRunRecord record) {
        JobRun run = record.getJobRun();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        run.setState(STATE_CANCELLING);
        run.setUpdatedAt(now);
        run.setAttemptUpdatedAt(now);
        store.put(run.getJobRunId(), record);
    }

    /**
     * Ends a container run as its driver did. A cancelled run, or one past its timeout ("the job run
     * has timed out" is a reason to be CANCELLING), is CANCELLED; a driver stopped otherwise was
     * stopped by Floci's own shutdown.
     */
    private synchronized void ended(String jobRunId, SparkJobRunner.Outcome outcome, long launchedIn) {
        if (launchedIn != generation) {
            return;
        }
        executing.remove(jobRunId);
        JobRunRecord record = store.get(jobRunId).orElse(null);
        if (record == null || isTerminal(record.getJobRun())) {
            return;
        }
        JobRun run = record.getJobRun();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (STATE_CANCELLING.equals(run.getState()) || outcome.timedOut()) {
            finish(record, STATE_CANCELLED, now);
        } else if (outcome.exitCode() != null && outcome.exitCode() == 0) {
            finish(record, STATE_SUCCESS, now);
        } else {
            // No source gives AWS's wording for a driver that fails; this is Floci's.
            String details = outcome.stopped() ? INTERRUPTED
                    : outcome.failure() != null ? "Job run could not start: " + outcome.failure()
                    : "Job run failed: the Spark driver exited with code " + outcome.exitCode() + ".";
            // A driver Floci's own shutdown stopped did not fail, and is not tried again. The next attempt is
            // admitted while its application cannot be deleted, as the first was.
            if (!outcome.stopped() && retriesAfterFailure(record, now)
                    && applications.whileLive(run.getApplicationId(), app -> retry(record, app, details, now))) {
                return;
            }
            run.setStateDetails(details);
            finish(record, STATE_FAILED, now);
        }
        LOG.infov("EMR Serverless job run {0} ended {1}", jobRunId, run.getState());
    }

    /**
     * Whether a run whose latest attempt has just failed is tried again. "EMR Serverless releases 7.1.0 and
     * higher include support for job resiliency". A BATCH run is rerun until it has made {@code maxAttempts}
     * attempts, by default 1, "which means to not rerun the job". "EMR Serverless indefinitely retries
     * streaming jobs if they fail", until "the maximum number of failed attempts allowed with an hour before
     * EMR Serverless stops retrying", {@code maxFailedAttemptsPerHour}, by default five, is reached: this
     * failure and those of the hour before it count.
     */
    private static boolean retriesAfterFailure(JobRunRecord record, Instant now) {
        JobRun run = record.getJobRun();
        if (!retriesAttempts(run.getReleaseLabel())) {
            return false;
        }
        if (!MODE_STREAMING.equals(run.getMode())) {
            return run.getAttempt() < retryLimit(run, "maxAttempts", DEFAULT_MAX_ATTEMPTS);
        }
        Instant hourBefore = now.minus(Duration.ofHours(1));
        long failedWithinTheHour = 1 + record.getEarlierAttempts().stream()
                .filter(earlier -> STATE_FAILED.equals(earlier.getState()) && earlier.getEndedAt().isAfter(hourBefore))
                .count();
        return failedWithinTheHour < retryLimit(run, "maxFailedAttemptsPerHour", DEFAULT_MAX_FAILED_ATTEMPTS_PER_HOUR);
    }

    // A member of the run's retryPolicy, or its default when the run has none.
    private static int retryLimit(JobRun run, String member, int fallback) {
        Object limit = run.getRetryPolicy() != null ? run.getRetryPolicy().get(member) : null;
        return limit instanceof Number number ? number.intValue() : fallback;
    }

    /**
     * Ends the run's latest attempt FAILED, kept as it then stood, and starts the next: SCHEDULED again,
     * its driver started over ("Batch jobs don't have checkpoints, so EMR Serverless always re-runs the job
     * from the beginning"), "its own set of logs" under its own attempt number.
     */
    private void retry(JobRunRecord record, Application app, String details, Instant now) {
        JobRun run = record.getJobRun();
        JobRun failed = objectMapper.convertValue(run, JobRun.class);
        failed.setStateDetails(details);
        end(failed, STATE_FAILED, now);
        record.getEarlierAttempts().add(failed);
        run.setAttempt(run.getAttempt() + 1);
        run.setAttemptCreatedAt(now);
        run.setAttemptUpdatedAt(now);
        run.setUpdatedAt(now);
        run.setState(STATE_SCHEDULED);
        run.setStateDetails("");
        run.setStartedAt(null);
        run.setEndedAt(null);
        run.setTotalExecutionDurationSeconds(null);
        store.put(run.getJobRunId(), record);
        LOG.infov("EMR Serverless job run {0} failed its attempt {1}; attempt {2} starts", run.getJobRunId(),
                failed.getAttempt(), run.getAttempt());
        launch(record, app);
    }

    /**
     * The {@code spark-defaults} properties for a run: the application's runtime configuration, then the
     * run's own {@code applicationConfiguration}. The two merge by classification, so a run's
     * {@code spark-defaults} replaces the application's: "Only the properties included in StartJobRun is
     * considered for this classification." From emr-spark-8.1.0 they merge by property, the run's winning.
     */
    private static Map<String, String> sparkDefaults(Application app, JobRun run) {
        Map<String, String> properties = new LinkedHashMap<>();
        if (app.getRuntimeConfiguration() != null) {
            for (RuntimeConfiguration configuration : app.getRuntimeConfiguration()) {
                addSparkDefaults(properties, configuration.getMembers());
            }
        }
        Object overrides = run.getConfigurationOverrides() != null
                ? run.getConfigurationOverrides().get("applicationConfiguration") : null;
        if (overrides instanceof List<?> configurations) {
            boolean replaces = !releaseAtLeast(SPARK_RUNTIME_RELEASE_LABEL, run.getReleaseLabel(), 8, 1);
            for (Object configuration : configurations) {
                if (configuration instanceof Map<?, ?> members
                        && SPARK_DEFAULTS.equals(members.get("classification"))) {
                    if (replaces) {
                        properties.clear();
                        replaces = false;
                    }
                    addSparkDefaults(properties, members);
                }
            }
        }
        return properties;
    }

    /**
     * The S3 folder a run's driver logs go to, or null without an S3 log destination: the run's
     * {@code s3MonitoringConfiguration} or else the application's, an empty one at the run removing it. Under
     * its {@code logUri} the user guide puts a run's logs at {@code /applications/<applicationId>/jobs/<jobId>},
     * with {@code /attempts/<attemptNumber>} after it for "all streaming jobs and any batch jobs that have retry
     * enabled" from release 7.1.0, and the driver's in {@code SPARK_DRIVER/}.
     */
    static String driverLogFolder(Application app, JobRun run) {
        String logUri = monitoring(app, run, S3_MONITORING) instanceof Map<?, ?> configuration
                && configuration.get("logUri") instanceof String uri ? uri : null;
        return EmrServerlessSparkRunner.logFolder(logUri, driverLogPath(run).substring(1) + "/");
    }

    /**
     * Where in CloudWatch Logs a run's driver output goes, or null when CloudWatch logging is not enabled.
     * The run's {@code cloudWatchLoggingConfiguration} supersedes the application's, and one with
     * {@code enabled} false removes it. The user guide's defaults: the log group {@code /aws/emr-serverless}
     * and the log stream {@code /applications/applicationId/jobs/jobId/worker-type}, after the
     * {@code logStreamNamePrefix}, with the attempt as for S3, and "By default, EMR Serverless publishes only
     * the driver stdout and stderr logs". Each goes to a stream of its own under the driver's, as the
     * clients that read them (Dagster's EMR Serverless client) name them: {@code .../SPARK_DRIVER/stdout}
     * and {@code .../SPARK_DRIVER/stderr}. A {@code logTypes} that names some types takes only those of the
     * driver's it names, none when it names only other workers': "The types of logs that you want to publish
     * to CloudWatch. If you don't specify any log types, driver STDOUT and STDERR logs will be published".
     */
    static SparkJobRunner.CloudWatchLogs driverCloudWatchLogs(Application app, JobRun run) {
        if (!(monitoring(app, run, CLOUD_WATCH_LOGGING) instanceof Map<?, ?> configuration)
                || !Boolean.TRUE.equals(configuration.get("enabled"))) {
            return null;
        }
        String logGroup = configuration.get("logGroupName") instanceof String name ? name : DEFAULT_LOG_GROUP;
        String prefix = configuration.get("logStreamNamePrefix") instanceof String named ? named : "";
        String driver = prefix + driverLogPath(run) + "/";
        List<?> driverTypes = null;
        if (configuration.get("logTypes") instanceof Map<?, ?> logTypes && !logTypes.isEmpty()) {
            driverTypes = logTypes.get(SPARK_DRIVER) instanceof List<?> types ? types : List.of();
        }
        String stdout = publishes(driverTypes, "stdout") ? driver + "stdout" : null;
        String stderr = publishes(driverTypes, "stderr") ? driver + "stderr" : null;
        return stdout == null && stderr == null ? null : new SparkJobRunner.CloudWatchLogs(logGroup, stdout, stderr);
    }

    // Whether the driver's log types, as logTypes lists them for SPARK_DRIVER, take this one; both without a list.
    private static boolean publishes(List<?> driverTypes, String logType) {
        return driverTypes == null
                || driverTypes.stream().anyMatch(type -> logType.equalsIgnoreCase(String.valueOf(type)));
    }

    /**
     * One of a run's monitoring configurations, {@code type}: the run's, which supersedes the application's
     * ("The granularity level when the merge occurs ... MonitoringConfiguration - Configuration type"), or
     * else the application's; null when neither has one.
     */
    private static Object monitoring(Application app, JobRun run, String type) {
        Object overrides = run.getConfigurationOverrides() != null
                ? run.getConfigurationOverrides().get("monitoringConfiguration") : null;
        if (overrides instanceof Map<?, ?> monitoring && monitoring.containsKey(type)) {
            return monitoring.get(type);
        }
        return app.getMonitoringConfiguration() != null
                ? app.getMonitoringConfiguration().getProperties().get(type) : null;
    }

    // Where under a log destination the user guide puts a run's driver logs, the attempt for a retried run.
    private static String driverLogPath(JobRun run) {
        String attempts = retried(run) && retriesAttempts(run.getReleaseLabel()) ? "/attempts/" + run.getAttempt() : "";
        return "/applications/" + run.getApplicationId() + "/jobs/" + run.getJobRunId() + attempts + "/"
                + SPARK_DRIVER;
    }

    // A STREAMING run is retried, and a BATCH run whose retryPolicy allows more than one attempt.
    private static boolean retried(JobRun run) {
        return MODE_STREAMING.equals(run.getMode()) || retryLimit(run, "maxAttempts", DEFAULT_MAX_ATTEMPTS) > 1;
    }

    // "EMR Serverless releases 7.1.0 and higher support retry attempts for streaming jobs and batch jobs."
    private static boolean retriesAttempts(String releaseLabel) {
        return releaseAtLeast(RELEASE_LABEL, releaseLabel, 7, 1)
                || SPARK_RUNTIME_RELEASE_LABEL.matcher(releaseLabel != null ? releaseLabel : "").matches();
    }

    private static void addSparkDefaults(Map<String, String> properties, Map<?, ?> configuration) {
        if (SPARK_DEFAULTS.equals(configuration.get("classification"))
                && configuration.get("properties") instanceof Map<?, ?> values) {
            values.forEach((key, value) -> properties.put(String.valueOf(key), String.valueOf(value)));
        }
    }

    /**
     * Finishes a BATCH run that has been RUNNING for the configured duration, or cancels it at its
     * execution timeout when that comes first: "If the job run exceeds this duration, Amazon EMR
     * Serverless cancels it automatically."
     */
    private JobRunRecord settle(JobRunRecord record) {
        JobRun run = record.getJobRun();
        if (record.isContainerRun()) {
            // Its thread ends it; without one, Floci stopped while it ran and nothing is left to.
            if (!isTerminal(run) && !executing.contains(run.getJobRunId())) {
                run.setStateDetails(INTERRUPTED);
                finish(record, STATE_FAILED, clock.instant().truncatedTo(ChronoUnit.MILLIS));
            }
            return record;
        }
        if (isTerminal(run) || MODE_STREAMING.equals(run.getMode())) {
            return record;
        }
        long timeoutSeconds = run.getExecutionTimeoutMinutes() == null || run.getExecutionTimeoutMinutes() == 0
                ? Long.MAX_VALUE : run.getExecutionTimeoutMinutes() * 60;
        if (runDurationSeconds > timeoutSeconds) {
            Instant timedOutAt = run.getStartedAt().plusSeconds(timeoutSeconds);
            if (!clock.instant().isBefore(timedOutAt)) {
                finish(record, STATE_CANCELLED, timedOutAt);
            }
            return record;
        }
        Instant finishedAt = run.getStartedAt().plusSeconds(runDurationSeconds);
        if (!clock.instant().isBefore(finishedAt)) {
            finish(record, STATE_SUCCESS, finishedAt);
        }
        return record;
    }

    private void finish(JobRunRecord record, String state, Instant endedAt) {
        end(record.getJobRun(), state, endedAt);
        store.put(record.getJobRun().getJobRunId(), record);
    }

    private static void end(JobRun run, String state, Instant endedAt) {
        run.setState(state);
        run.setEndedAt(endedAt);
        run.setUpdatedAt(endedAt);
        run.setAttemptUpdatedAt(endedAt);
        // A container run cancelled or failed before its driver ran never started.
        run.setTotalExecutionDurationSeconds(run.getStartedAt() != null
                ? (int) Duration.between(run.getStartedAt(), endedAt).toSeconds() : 0);
    }

    // Every attempt of a run, oldest first: the earlier ones, then the latest.
    private static List<JobRun> attempts(JobRunRecord record) {
        List<JobRun> attempts = new ArrayList<>(record.getEarlierAttempts());
        attempts.add(record.getJobRun());
        return attempts;
    }

    private JobRunRecord findRun(String applicationId, String jobRunId) {
        return store.get(jobRunId)
                .filter(record -> applicationId.equals(record.getJobRun().getApplicationId()))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Job run " + jobRunId + " not found", 404));
    }

    // The run a tagging ARN names. The ARN must be the run's own, so one naming another account names nothing,
    // and a deleted application's runs name nothing, as the application's own ARN does.
    private JobRunRecord runFor(EmrServerlessService.ResourceArn resource) {
        return store.get(resource.jobRunId())
                .filter(record -> resource.applicationId().equals(record.getJobRun().getApplicationId()))
                .filter(record -> resource.arn().equals(record.getJobRun().getArn()))
                .filter(record -> applications.isLive(resource.applicationId()))
                .orElseThrow(() -> EmrServerlessService.resourceNotFound(resource.arn()));
    }

    private JobRunRecord runWithClientToken(String clientToken) {
        for (JobRunRecord record : store.scan(key -> true)) {
            if (clientToken.equals(record.getClientToken()) && inRequestRegion(record.getJobRun())) {
                return record;
            }
        }
        return null;
    }

    /**
     * What a retry must repeat: the application and every member of the body but the token itself.
     * Read back from its JSON text, so that its numbers compare equal to those of a stored request
     * once storage has written and read it.
     */
    private JsonNode requestOf(String applicationId, StartJobRunRequest request) {
        ObjectNode node;
        try {
            node = (ObjectNode) objectMapper.readTree(objectMapper.writeValueAsString(request));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize a StartJobRun request", e);
        }
        node.remove("clientToken");
        node.put("applicationId", applicationId);
        return node;
    }

    // The caller, as GetCallerIdentity reports it; the account root for a key IAM does not know.
    private String createdBy(String accountId) {
        String accessKeyId = requestContext != null ? requestContext.getAccessKeyId() : null;
        return iamService.resolveCallerArn(accessKeyId)
                .orElseGet(() -> regionResolver.buildGlobalArn("iam", accountId, "root"));
    }

    private boolean inRequestRegion(JobRun run) {
        return regionResolver.getRegion().equals(AwsArnUtils.parse(run.getArn()).region());
    }

    private static boolean isTerminal(JobRun run) {
        return TERMINAL_STATES.contains(run.getState());
    }

    private static void validate(StartJobRunRequest request) {
        Map<String, Integer> retryLimits = retryLimits(request.getRetryPolicy());
        List<String> violations = new ArrayList<>();
        if (request.getClientToken() == null) {
            violations.add(SmithyValidationErrors.notNull("clientToken"));
        }
        if (request.getExecutionRoleArn() == null) {
            violations.add(SmithyValidationErrors.notNull("executionRoleArn"));
        } else if (!ROLE_ARN.matcher(request.getExecutionRoleArn()).matches()) {
            violations.add("Value at 'executionRoleArn' failed to satisfy constraint: "
                    + "Member must satisfy regular expression pattern: " + ROLE_ARN_PATTERN);
        }
        if (request.getMode() != null && !MODES.contains(request.getMode())) {
            violations.add(SmithyValidationErrors.notInEnum(request.getMode(), "mode", MODES));
        }
        if (request.getExecutionTimeoutMinutes() != null) {
            String violation = SmithyValidationErrors.outOfRange("executionTimeoutMinutes",
                    request.getExecutionTimeoutMinutes(), 0, MAX_EXECUTION_TIMEOUT_MINUTES);
            if (violation != null) {
                violations.add(violation);
            }
        }
        // No source gives AWS's wording above 10, which the model does not bound; this is the model's form.
        retryLimits.forEach((member, limit) -> {
            String violation = SmithyValidationErrors.outOfRange("retryPolicy." + member, limit, 1, MAX_RETRY_LIMIT);
            if (violation != null) {
                violations.add(violation);
            }
        });
        JobDriver driver = request.getJobDriver();
        if (driver != null && driver.getSparkSubmit() != null && driver.getSparkSubmit().getEntryPoint() == null) {
            violations.add(SmithyValidationErrors.notNull("jobDriver.sparkSubmit.entryPoint"));
        }
        if (driver != null && driver.getHive() != null && driver.getHive().getQuery() == null) {
            violations.add(SmithyValidationErrors.notNull("jobDriver.hive.query"));
        }
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
        // StartJobRun's shape leaves jobDriver optional, but GetJobRun's JobRun requires one, so a run is
        // refused without it. JobDriver is a union, "so only one of the following members can be
        // specified". No source gives AWS's wording for a run without a driver, or with both.
        if (driver == null || (driver.getSparkSubmit() == null) == (driver.getHive() == null)) {
            throw new AwsException("ValidationException", "jobDriver must contain exactly one union member", 400);
        }
    }

    /**
     * The members of a run's {@code retryPolicy} it gives, each an integer in the model. A restJson1 service
     * refuses a body member of another type, a string, a boolean or a fraction where an integer goes, with
     * SerializationException, before any constraint (Smithy's restJson1 malformed-request tests); the message
     * is Floci's.
     */
    private static Map<String, Integer> retryLimits(Map<String, Object> retryPolicy) {
        Map<String, Integer> limits = new LinkedHashMap<>();
        for (String member : RETRY_LIMITS) {
            Object value = retryPolicy != null ? retryPolicy.get(member) : null;
            if (value instanceof Integer limit) {
                limits.put(member, limit);
            } else if (value != null) {
                throw new AwsException("SerializationException", "retryPolicy." + member + " must be an integer", 400);
            }
        }
        return limits;
    }


    /**
     * From Amazon EMR release 7.11.0 a BATCH run's timeout is 1 to 10080 minutes; an earlier release, or
     * a STREAMING run, keeps the model's 0 to 1,000,000, 0 meaning none. No source gives AWS's error, so
     * the wording is Floci's.
     */
    private static void checkBatchTimeout(Application app, StartJobRunRequest request) {
        Long minutes = request.getExecutionTimeoutMinutes();
        if (minutes == null || MODE_STREAMING.equals(request.getMode())
                || !boundsBatchTimeouts(app.getReleaseLabel())) {
            return;
        }
        if (minutes == 0 || minutes > MAX_BATCH_EXECUTION_TIMEOUT_MINUTES) {
            throw new AwsException("ValidationException", "executionTimeoutMinutes must be between 1 and "
                    + MAX_BATCH_EXECUTION_TIMEOUT_MINUTES + " for a BATCH job run on release emr-7.11.0 or later.",
                    400);
        }
    }

    private static boolean boundsBatchTimeouts(String releaseLabel) {
        return releaseAtLeast(RELEASE_LABEL, releaseLabel, 7, 11);
    }

    // Whether releaseLabel is of the family labelPattern matches, at major.minor or later.
    private static boolean releaseAtLeast(Pattern labelPattern, String releaseLabel, int major, int minor) {
        Matcher matcher = labelPattern.matcher(releaseLabel != null ? releaseLabel : "");
        if (!matcher.matches()) {
            return false;
        }
        int labelMajor = Integer.parseInt(matcher.group(1));
        return labelMajor > major || (labelMajor == major && Integer.parseInt(matcher.group(2)) >= minor);
    }

    private static JobRunSummary toSummary(JobRunRecord record) {
        JobRun run = record.getJobRun();
        JobRunSummary summary = new JobRunSummary();
        summary.setApplicationId(run.getApplicationId());
        summary.setId(run.getJobRunId());
        summary.setName(run.getName());
        summary.setMode(run.getMode());
        summary.setArn(run.getArn());
        summary.setCreatedBy(run.getCreatedBy());
        summary.setCreatedAt(run.getCreatedAt());
        summary.setUpdatedAt(run.getUpdatedAt());
        summary.setExecutionRole(run.getExecutionRole());
        summary.setState(run.getState());
        summary.setStateDetails(run.getStateDetails());
        summary.setReleaseLabel(run.getReleaseLabel());
        summary.setType(record.getApplicationType());
        summary.setAttempt(run.getAttempt());
        summary.setAttemptCreatedAt(run.getAttemptCreatedAt());
        summary.setAttemptUpdatedAt(run.getAttemptUpdatedAt());
        return summary;
    }

    // An attempt of a run: "createdAt", "The date and time when the job run attempt was created", and
    // "updatedAt" when it was last updated are the attempt's; "jobCreatedAt" is the run's.
    private static JobRunAttemptSummary toAttemptSummary(JobRunRecord record, JobRun attempt) {
        JobRunAttemptSummary summary = new JobRunAttemptSummary();
        summary.setApplicationId(attempt.getApplicationId());
        summary.setId(attempt.getJobRunId());
        summary.setName(attempt.getName());
        summary.setMode(attempt.getMode());
        summary.setArn(attempt.getArn());
        summary.setCreatedBy(attempt.getCreatedBy());
        summary.setJobCreatedAt(attempt.getCreatedAt());
        summary.setCreatedAt(attempt.getAttemptCreatedAt());
        summary.setUpdatedAt(attempt.getAttemptUpdatedAt());
        summary.setExecutionRole(attempt.getExecutionRole());
        summary.setState(attempt.getState());
        summary.setStateDetails(attempt.getStateDetails());
        summary.setReleaseLabel(attempt.getReleaseLabel());
        summary.setType(record.getApplicationType());
        summary.setAttempt(attempt.getAttempt());
        return summary;
    }

    /** What the thread running a container run tells the service, and asks it, as that run. */
    private final class RunProgress implements SparkJobRunner.Progress {

        private final SparkJobRunner.Job job;
        private final long launchedIn;

        RunProgress(SparkJobRunner.Job job, long launchedIn) {
            this.job = job;
            this.launchedIn = launchedIn;
        }

        @Override
        public void started() {
            RequestScopes.runAs(job.accountId(), job.region(), () -> driverStarted(job.runId(), launchedIn));
        }

        @Override
        public void timedOut() {
            RequestScopes.runAs(job.accountId(), job.region(), () -> driverTimedOut(job.runId(), launchedIn));
        }

        @Override
        public boolean cancelled() {
            return !RequestScopes.callAs(job.accountId(), job.region(),
                    () -> stillGoing(job.runId(), launchedIn));
        }
    }
}
