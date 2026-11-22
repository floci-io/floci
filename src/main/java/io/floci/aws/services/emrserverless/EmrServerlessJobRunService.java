package io.floci.aws.services.emrserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.core.common.docker.DockerPlatforms;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.emrserverless.model.Application;
import io.floci.aws.services.emrserverless.model.DriverLogDestinations;
import io.floci.aws.services.emrserverless.model.ImageConfiguration;
import io.floci.aws.services.emrserverless.model.JobDriver;
import io.floci.aws.services.emrserverless.model.JobRun;
import io.floci.aws.services.emrserverless.model.JobRunAttemptSummary;
import io.floci.aws.services.emrserverless.model.JobRunRecord;
import io.floci.aws.services.emrserverless.model.JobRunSummary;
import io.floci.aws.services.emrserverless.model.MaximumCapacity;
import io.floci.aws.services.emrserverless.model.RunWorkers;
import io.floci.aws.services.emrserverless.model.RuntimeConfiguration;
import io.floci.aws.services.emrserverless.model.Session;
import io.floci.aws.services.emrserverless.model.StartJobRunRequest;
import io.floci.aws.services.emrserverless.model.UpdateApplicationRequest;
import io.floci.aws.services.emrserverless.model.WorkerTypeSpecification;
import io.floci.aws.services.iam.IamService;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
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
 * <p>In either mode a run beyond its application's {@code maxConcurrentRuns} waits QUEUED until one of the
 * application's runs ends, or until its queue timeout ({@link EmrServerlessScheduler}).
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
    static final String STATE_QUEUED = "QUEUED";
    private static final String MODE_BATCH = "BATCH";
    private static final String MODE_STREAMING = "STREAMING";
    // JobRunExecutionIamPolicy's members, and the model's bounds on them: PolicyDocument's and Arn's lengths, and
    // PolicyArnList's size.
    private static final String INLINE_POLICY = "policy";
    private static final String POLICY_ARNS = "policyArns";
    private static final int MAX_POLICY_LENGTH = 2048;
    private static final int MIN_ARN_LENGTH = 20;
    private static final int MAX_POLICY_ARNS = 10;
    // CancelJobRun's grace period: its default for a streaming run, and its range.
    private static final int DEFAULT_STREAMING_GRACE_SECONDS = 120;
    private static final int MIN_GRACE_SECONDS = 15;
    private static final int MAX_GRACE_SECONDS = 1800;
    private static final String RUNNER_DOCKER = "docker";
    // The service a container run's driver belongs to, which names it and labels it.
    static final String SERVICE = "emr-serverless";
    private static final String SPARK_DEFAULTS = "spark-defaults";
    private static final String SPARK_CONNECT_SERVER = "org.apache.spark.sql.connect.service.SparkConnectServer";
    // Where an emr- release's image keeps the Spark Connect server, as emr-7.13.0's does.
    static final String SPARK_CONNECT_JAR = "/usr/lib/spark/connector/lib/spark-connect.jar";
    // spark-submit's application for a class among Spark's own jars.
    private static final String SPARK_INTERNAL = "spark-internal";
    private static final String SPARK_CONNECT_PORT = "spark.connect.grpc.binding.port";
    private static final String SPARK_CONNECT_ADDRESS = "spark.connect.grpc.binding.address";
    private static final String S3_MONITORING = "s3MonitoringConfiguration";
    private static final String CLOUD_WATCH_LOGGING = "cloudWatchLoggingConfiguration";
    private static final String MANAGED_PERSISTENCE = "managedPersistenceMonitoringConfiguration";
    // "If you don't specify the optional values, then CloudWatch publishes the logs to a default log group".
    private static final String DEFAULT_LOG_GROUP = "/aws/emr-serverless";
    private static final String SPARK_DRIVER = "SPARK_DRIVER";
    private static final String HIVE_DRIVER = "HIVE_DRIVER";
    private static final String HIVE_TYPE = "HIVE";
    // The classifications a Hive run's settings come from, "Hadoop's core-site.xml", "Tez's tez-site.xml" and "Hive's
    // hive-site.xml", in the order Hive loads the files, a later one winning. emrfs-site sets EMRFS, which Floci's
    // S3A replaces.
    private static final List<String> HIVE_CLASSIFICATIONS = List.of("core-site", "tez-site", "hive-site");
    // The worker types a Spark application's driver and a Hive application's run as.
    private static final String DRIVER_WORKER_TYPE = "Driver";
    private static final String HIVE_DRIVER_WORKER_TYPE = "HiveDriver";
    private static final String EXECUTOR_WORKER_TYPE = "Executor";
    // spark-submit's option for a Spark property, and its short form; and its option for spark.archives.
    private static final String CONF_OPTION = "--conf";
    private static final String CONF_SHORT_OPTION = "-c";
    private static final String ARCHIVES_OPTION = "--archives";
    // "Option that adds environment variables to the Spark driver."
    private static final String DRIVER_ENV_PREFIX = "spark.emr-serverless.driverEnv.";
    private static final String SPARK_ARCHIVES = "spark.archives";
    // No source gives AWS's wording for these; they are Floci's.
    static final String DRIVER_NOT_THE_APPLICATIONS = "Floci runs a Spark application's sparkSubmit job runs and a"
            + " Hive application's hive job runs, and this job run's driver is not its application's type.";
    static final String INTERRUPTED = "Floci stopped while the job run was in progress.";

    private static final Set<String> TERMINAL_STATES = Set.of(STATE_SUCCESS, STATE_FAILED, STATE_CANCELLED);
    private static final List<String> STATES = List.of("SUBMITTED", "PENDING", "SCHEDULED", STATE_RUNNING,
            STATE_SUCCESS, "FAILED", "CANCELLING", STATE_CANCELLED, STATE_QUEUED);
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
    static final Pattern RELEASE_LABEL = Pattern.compile("emr-(\\d{1,6})\\.(\\d{1,6})(?:\\..*)?");
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
    // Where a container run's driver runs: image-template for a Spark run and hive-image-template for a Hive one,
    // with the release label for %s, and docker-network; null when not set.
    private final String imageTemplate;
    private final String hiveImageTemplate;
    private final String dockerNetwork;
    // How many executor containers a Spark run may have at once: executor-containers, 0 for Spark's local mode.
    private final int executorContainers;
    // Whether a container run runs for its application's architecture: honour-architectures, off by default.
    private final boolean honourArchitectures;
    // Container runs a thread is running; one not here that has not ended was left by a restart.
    private final Set<String> executing = new HashSet<>();
    // Raised by each reset, so a run's thread from before it writes nothing back after the wipe.
    private long generation;
    // The latest sequence a run was given, nextSequence.
    private long lastSequence;
    // Each account's run ids by application, so that settling one application reads its runs alone; built from the
    // store the first time an account's are needed, kept as runs start, and dropped by a reset.
    private final Map<String, Map<String, Set<String>>> runIdsByApplication = new HashMap<>();

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
                config.services().emrserverless().hiveImageTemplate().orElse(null),
                config.services().emrserverless().dockerNetwork().orElse(null),
                config.services().emrserverless().executorContainers(),
                config.services().emrserverless().honourArchitectures());
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
                sparkRunner, runExecutor, null, null, null);
    }

    EmrServerlessJobRunService(AccountAwareStorageBackend<JobRunRecord> store, EmrServerlessService applications,
                               IamService iamService, RegionResolver regionResolver, RequestContext requestContext,
                               ObjectMapper objectMapper, int runDurationSeconds, Clock clock,
                               SparkJobRunner sparkRunner, Executor runExecutor,
                               String imageTemplate, String hiveImageTemplate, String dockerNetwork) {
        this(store, applications, iamService, regionResolver, requestContext, objectMapper, runDurationSeconds, clock,
                sparkRunner, runExecutor, imageTemplate, hiveImageTemplate, dockerNetwork, 0);
    }

    EmrServerlessJobRunService(AccountAwareStorageBackend<JobRunRecord> store, EmrServerlessService applications,
                               IamService iamService, RegionResolver regionResolver, RequestContext requestContext,
                               ObjectMapper objectMapper, int runDurationSeconds, Clock clock,
                               SparkJobRunner sparkRunner, Executor runExecutor,
                               String imageTemplate, String hiveImageTemplate, String dockerNetwork,
                               int executorContainers) {
        this(store, applications, iamService, regionResolver, requestContext, objectMapper, runDurationSeconds, clock,
                sparkRunner, runExecutor, imageTemplate, hiveImageTemplate, dockerNetwork, executorContainers, false);
    }

    EmrServerlessJobRunService(AccountAwareStorageBackend<JobRunRecord> store, EmrServerlessService applications,
                               IamService iamService, RegionResolver regionResolver, RequestContext requestContext,
                               ObjectMapper objectMapper, int runDurationSeconds, Clock clock,
                               SparkJobRunner sparkRunner, Executor runExecutor,
                               String imageTemplate, String hiveImageTemplate, String dockerNetwork,
                               int executorContainers, boolean honourArchitectures) {
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
        this.hiveImageTemplate = hiveImageTemplate;
        this.dockerNetwork = dockerNetwork;
        this.executorContainers = executorContainers;
        this.honourArchitectures = honourArchitectures;
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
        JsonNode original = EmrServerlessService.retriedRequest(objectMapper, applicationId, body);
        JobRunRecord existing = EmrServerlessService.startedWithClientToken(store.scan(key -> true),
                body.getClientToken(), JobRunRecord::getClientToken, record -> inRequestRegion(record.getJobRun()));
        if (existing != null) {
            if (!sameRun(existing.getRequest(), applicationId, body)) {
                throw new AwsException("ConflictException", "Request did not match the original", 409);
            }
            return settle(existing).getJobRun();
        }
        // Before admission, which may auto-start the application: a refused run leaves it as it was.
        if (body.getTags() != null) {
            EmrServerlessService.validateTags(body.getTags());
        }
        List<JobRunRecord> runs = settleApplication(applicationId, null);
        JobRunRecord record = applications.admit(applicationId, "a job run is submitted",
                app -> checkBatchTimeout(app, body), app -> {
            EmrServerlessScheduler.Limits limits = EmrServerlessScheduler.limits(app);
            boolean queued = limits != null && active(runs).size() >= limits.maxConcurrentRuns();
            JobRunRecord admitted = newRun(app, body, original, queued);
            if (sparkRunner != null && !queued) {
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

    /**
     * What a run's dashboard page shows of one attempt, which must still be there: the attempt, as GetJobRun gives
     * it, and where its driver's logs go.
     */
    public synchronized DashboardView dashboard(String applicationId, String jobRunId, int attempt) {
        JobRun run = getJobRun(applicationId, jobRunId, attempt);
        DriverLogDestinations logs = findRun(applicationId, jobRunId).getDriverLogs().get(attempt);
        if (logs == null) {
            // A run stored before Floci kept its attempts' destinations: where its application now sends them.
            logs = driverLogDestinations(applications.getApplication(applicationId), run);
        } else if (logs.managedStorage() == null) {
            // An attempt stored before Floci kept whether its logs were in managed storage: as the run's own
            // configuration says, or else as its application's now does.
            logs = new DriverLogDestinations(logs.s3Folder(), logs.logGroup(), logs.stdoutStream(),
                    logs.stderrStream(), managedStorage(applications.getApplication(applicationId), run));
        }
        return new DashboardView(run, logs);
    }

    /** An attempt of a run, and where its driver's logs go, as the attempt was admitted. */
    public record DashboardView(JobRun run, DriverLogDestinations logs) {
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
        List<String> violations = EmrServerlessService.listViolations(maxResults, MAX_LIST_RESULTS, states, STATES,
                MAX_FILTER_STATES);
        if (mode != null && !MODES.contains(mode)) {
            violations.add(SmithyValidationErrors.notInEnum(mode, "mode", MODES));
        }
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
        List<JobRunRecord> matching = new ArrayList<>();
        for (JobRunRecord record : settleApplication(applicationId, null)) {
            JobRun run = record.getJobRun();
            if (!inRequestRegion(run)) {
                continue;
            }
            if ((states != null && !states.isEmpty() && !states.contains(run.getState()))
                    || (createdAtAfter != null && !run.getCreatedAt().isAfter(createdAtAfter))
                    || (createdAtBefore != null && !run.getCreatedAt().isBefore(createdAtBefore))
                    || (mode != null && !mode.equals(run.getMode() != null ? run.getMode() : MODE_BATCH))) {
                continue;
            }
            matching.add(record);
        }
        String namespace = String.join("|", "ListJobRuns", applicationId,
                String.valueOf(states != null ? states : List.of()), String.valueOf(createdAtAfter),
                String.valueOf(createdAtBefore), String.valueOf(mode));
        PaginatedResult<JobRunRecord> page = EmrServerlessService.pageByState(matching,
                record -> record.getJobRun().getJobRunId(), record -> record.getJobRun().getState(),
                record -> record.getJobRun().getCreatedAt(), JobRunRecord::getSequence, states, maxResults,
                MAX_LIST_RESULTS, nextToken, namespace);
        return new PaginatedResult<>(page.items().stream().map(EmrServerlessJobRunService::toSummary).toList(),
                page.nextToken());
    }

    /**
     * Stops the application once none of its runs is still going, as StopApplication's reference has it: "All
     * scheduled and running jobs must be completed or cancelled before stopping an application." AWS's answer
     * otherwise is not recorded, so the refusal is the one error the model declares for it, carrying that
     * sentence. Under this service's lock, as StartJobRun's admission is, so no run starts between the two.
     */
    public synchronized void stopApplication(String applicationId) {
        applications.getApplication(applicationId);
        for (JobRunRecord record : settleApplication(applicationId, null)) {
            JobRun run = record.getJobRun();
            if (inRequestRegion(run) && !isTerminal(run)) {
                throw new AwsException("ValidationException", "All scheduled and running jobs must be completed "
                        + "or cancelled before stopping an application.", 400);
            }
        }
        applications.stopApplication(applicationId);
    }

    /** Cancels a run that has not finished, with the default grace period of {@link #gracePeriod}. */
    public synchronized JobRun cancelJobRun(String applicationId, String jobRunId) {
        return cancelJobRun(applicationId, jobRunId, null);
    }

    /**
     * Cancels a run that has not finished. A finished run is refused, with AWS's wording. A container
     * run is CANCELLING until its driver has stopped: at once, or after the grace period of {@link #gracePeriod}, in
     * which its driver gets SIGTERM and may end by itself, and is stopped when the period ends if it has not.
     */
    public synchronized JobRun cancelJobRun(String applicationId, String jobRunId, Integer gracePeriodSeconds) {
        checkGracePeriod(gracePeriodSeconds);
        applications.getApplication(applicationId);
        JobRunRecord record = settle(findRun(applicationId, jobRunId));
        if (isTerminal(record.getJobRun())) {
            throw new AwsException("ValidationException", "Job run is not in a cancellable state", 400);
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (STATE_QUEUED.equals(record.getJobRun().getState())) {
            // Nothing runs it yet, and it holds no running slot.
            leaveQueue(record.getJobRun(), now);
            finish(record, STATE_CANCELLED, now);
            LOG.infov("Cancelled queued EMR Serverless job run {0} on application {1}", jobRunId, applicationId);
            return record.getJobRun();
        }
        if (record.isContainerRun()) {
            JobRun run = record.getJobRun();
            // "Subsequent cancel requests during the CANCELLING state are ignored."
            if (!STATE_CANCELLING.equals(run.getState())) {
                long grace = gracePeriod(run, gracePeriodSeconds);
                markCancelling(record);
                if (grace > 0) {
                    record.setShutdownDeadline(now.plusSeconds(grace));
                    store.put(jobRunId, record);
                    runExecutor.execute(() -> sparkRunner.interrupt(jobRunId));
                } else {
                    runExecutor.execute(() -> sparkRunner.stop(jobRunId));
                }
                LOG.infov("Cancelling EMR Serverless job run {0} on application {1} with a grace period of {2}s",
                        jobRunId, applicationId, grace);
            }
            return run;
        }
        finish(record, STATE_CANCELLED, now);
        LOG.infov("Cancelled EMR Serverless job run {0} on application {1}", jobRunId, applicationId);
        // Its running slot goes to the first queued run now.
        settleApplication(applicationId, record);
        return record.getJobRun();
    }

    /**
     * Updates the application, as {@link EmrServerlessService#updateApplication} does, with its runs brought up to the
     * moment first under the limits they had, so that a higher {@code maxConcurrentRuns} starts queued runs from now.
     */
    public synchronized Application updateApplication(String applicationId, UpdateApplicationRequest request) {
        settleApplication(applicationId, null);
        Application app = applications.updateApplication(applicationId, request);
        settleApplication(applicationId, null);
        return app;
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

    /** The job run {@code jobRunId} of {@code account} as stored, read in place, for IAM's lookup of stored tags. */
    Optional<JobRun> storedRun(String account, String jobRunId) {
        return AccountAwareStorageBackend.readInAccount(store, account, jobRunId).map(JobRunRecord::getJobRun);
    }

    /** GetJobRun's {@code attempt}, an integer in the query string. */
    static Integer parseAttempt(String value) {
        return queryInteger(value, "attempt");
    }

    /** An integer {@code member} of the query string, null where it is not given; another value is refused. */
    static Integer queryInteger(String value, String member) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationException", member + " must be an integer.", 400);
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

    /**
     * Sets on {@code run} what a run takes from the request that starts it, as it holds it: its name, role, policy,
     * configuration overrides, driver, tags (none where the request gives none), execution timeout
     * ({@value #DEFAULT_EXECUTION_TIMEOUT_MINUTES} minutes where it gives none), mode and retry policy.
     */
    private static void requested(JobRun run, StartJobRunRequest request) {
        run.setName(request.getName());
        run.setExecutionRole(request.getExecutionRoleArn());
        run.setExecutionIamPolicy(executionIamPolicy(request.getExecutionIamPolicy()));
        run.setConfigurationOverrides(request.getConfigurationOverrides());
        run.setJobDriver(request.getJobDriver());
        run.setTags(request.getTags() != null ? new LinkedHashMap<>(request.getTags()) : new LinkedHashMap<>());
        run.setExecutionTimeoutMinutes(request.getExecutionTimeoutMinutes() != null
                ? request.getExecutionTimeoutMinutes() : DEFAULT_EXECUTION_TIMEOUT_MINUTES);
        run.setMode(request.getMode());
        run.setRetryPolicy(request.getRetryPolicy());
        run.setFlexExecution(flexExecution(request.getFlexExecution()));
    }

    // StartJobRun's executionIamPolicy as the model's JobRunExecutionIamPolicy holds it: its policy and policyArns.
    private static Map<String, Object> executionIamPolicy(Map<String, Object> given) {
        if (given == null) {
            return null;
        }
        Map<String, Object> policy = new LinkedHashMap<>();
        for (String member : List.of(INLINE_POLICY, POLICY_ARNS)) {
            if (given.get(member) != null) {
                policy.put(member, given.get(member));
            }
        }
        return policy;
    }

    /**
     * What is wrong with StartJobRun's executionIamPolicy, against the model's constraints: an inline policy of 1 to
     * 2,048 characters, and at most 10 policy ARNs of 20 to 2,048 characters each. A member of another JSON type is
     * refused with SerializationException, as retryPolicy's are.
     */
    private static List<String> executionIamPolicyViolations(Map<String, Object> given) {
        List<String> violations = new ArrayList<>();
        Object policy = given != null ? given.get(INLINE_POLICY) : null;
        Object arns = given != null ? given.get(POLICY_ARNS) : null;
        if (policy != null && !(policy instanceof String)) {
            throw new AwsException("SerializationException", "executionIamPolicy.policy must be a string", 400);
        }
        if (arns != null && !(arns instanceof List<?> list && list.stream().allMatch(String.class::isInstance))) {
            throw new AwsException("SerializationException",
                    "executionIamPolicy.policyArns must be a list of strings", 400);
        }
        if (policy instanceof String document) {
            String violation = SmithyValidationErrors.Violations.WITHOUT_VALUES.lengthOutOfRange(
                    "executionIamPolicy.policy", document, 1, MAX_POLICY_LENGTH);
            if (violation != null) {
                violations.add(violation);
            }
        }
        if (arns instanceof List<?> list) {
            String size = SmithyValidationErrors.sizeOutOfRange("executionIamPolicy.policyArns", list.size(), 0,
                    MAX_POLICY_ARNS);
            if (size != null) {
                violations.add(size);
            }
            for (int i = 0; i < list.size(); i++) {
                String violation = SmithyValidationErrors.lengthOutOfRange(
                        "executionIamPolicy.policyArns." + (i + 1) + ".member", (String) list.get(i), MIN_ARN_LENGTH,
                        MAX_POLICY_LENGTH);
                if (violation != null) {
                    violations.add(violation);
                }
            }
        }
        return violations;
    }

    // StartJobRun's flexExecution as the model's FlexExecution holds it: its one member, enabled, where it is given.
    private static Map<String, Object> flexExecution(Map<String, Object> given) {
        if (given == null) {
            return null;
        }
        Map<String, Object> flex = new LinkedHashMap<>();
        if (given.get("enabled") != null) {
            flex.put("enabled", given.get("enabled"));
        }
        return flex;
    }

    /**
     * Whether a retry asks for the run its original started: on the same application, with what the two would have
     * the run hold alike, {@link #requested}. A member the run holds the same way whether it is left out or given,
     * the execution timeout at its default or the tags empty, is alike; one the run would hold otherwise, such as a
     * mode given where the original left it out, is not.
     */
    private boolean sameRun(JsonNode original, String applicationId, StartJobRunRequest retried) {
        ObjectNode first = ((ObjectNode) original).deepCopy();
        String firstApplication = first.path("applicationId").asText(null);
        first.remove("applicationId");
        return applicationId.equals(firstApplication)
                && held(objectMapper.convertValue(first, StartJobRunRequest.class)).equals(held(retried));
    }

    private JsonNode held(StartJobRunRequest request) {
        JobRun run = new JobRun();
        requested(run, request);
        return objectMapper.valueToTree(run);
    }

    // The sequence of a run created at now, after the last this service gave.
    private long nextSequence(Instant now) {
        lastSequence = EmrServerlessService.nextSequence(lastSequence, now);
        return lastSequence;
    }

    private JobRunRecord newRun(Application app, StartJobRunRequest request, JsonNode original, boolean queued) {
        AwsArnUtils.Arn applicationArn = AwsArnUtils.parse(app.getArn());
        String jobRunId = EmrServerlessService.newId();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);

        JobRun run = new JobRun();
        run.setApplicationId(app.getApplicationId());
        run.setJobRunId(jobRunId);
        run.setArn(AwsArnUtils.Arn.of("emr-serverless", applicationArn.region(), applicationArn.accountId(),
                applicationArn.resource() + "/jobruns/" + jobRunId).toString());
        run.setCreatedBy(EmrServerlessService.createdBy(iamService, requestContext, regionResolver,
                applicationArn.accountId()));
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        requested(run, request);
        run.setState(queued ? STATE_QUEUED : sparkRunner != null ? STATE_SCHEDULED : STATE_RUNNING);
        run.setStateDetails("");
        run.setReleaseLabel(app.getReleaseLabel());
        run.setNetworkConfiguration(app.getNetworkConfiguration());
        run.setAttempt(FIRST_ATTEMPT);
        run.setAttemptCreatedAt(now);
        run.setAttemptUpdatedAt(now);
        applyImages(run, app);
        if (sparkRunner == null && !queued) {
            run.setStartedAt(now);
        }

        JobRunRecord record = new JobRunRecord();
        record.setJobRun(run);
        record.setSequence(nextSequence(now));
        record.setClientToken(request.getClientToken());
        record.setRequest(original);
        record.setApplicationType(app.getType());
        record.setWorkers(workers(app, run));
        record.setContainerRun(sparkRunner != null);
        keepDriverLogs(record, app);
        store.put(jobRunId, record);
        indexRun(run);
        return record;
    }

    /**
     * Keeps where the run's latest attempt sends its driver's logs, from the configuration it is admitted with, so that
     * a later change to its application's monitoring configuration moves neither the logs nor what its dashboard says
     * of them.
     */
    private static void keepDriverLogs(JobRunRecord record, Application app) {
        JobRun run = record.getJobRun();
        record.getDriverLogs().put(run.getAttempt(), driverLogDestinations(app, run));
    }

    private static DriverLogDestinations driverLogDestinations(Application app, JobRun run) {
        SparkJobRunner.CloudWatchLogs cloudWatch = driverCloudWatchLogs(app, run);
        return new DriverLogDestinations(driverLogFolder(app, run), cloudWatch != null ? cloudWatch.logGroup() : null,
                cloudWatch != null ? cloudWatch.stdoutStream() : null,
                cloudWatch != null ? cloudWatch.stderrStream() : null, managedStorage(app, run));
    }

    /**
     * Whether a run's logs are kept in EMR Serverless's managed storage: its {@code
     * managedPersistenceMonitoringConfiguration}, or else its application's, "Enables managed logging and defaults to
     * true. If set to false, managed logging will be turned off." Its application UIs need it: with logs in an S3
     * bucket alone, the application UI is "Not supported", and "Otherwise, you can't use the built-in application UIs"
     * (EMR Serverless user guide, "Storing logs").
     */
    static boolean managedStorage(Application app, JobRun run) {
        return !(monitoring(app, run, MANAGED_PERSISTENCE) instanceof Map<?, ?> configuration
                && Boolean.FALSE.equals(configuration.get("enabled")));
    }

    // Where the run's latest attempt sends its driver's logs, as it was admitted.
    private static DriverLogDestinations keptDriverLogs(JobRunRecord record, Application app) {
        DriverLogDestinations logs = record.getDriverLogs().get(record.getJobRun().getAttempt());
        return logs != null ? logs : driverLogDestinations(app, record.getJobRun());
    }

    private static SparkJobRunner.CloudWatchLogs cloudWatchLogs(DriverLogDestinations logs) {
        return logs.logGroup() != null
                ? new SparkJobRunner.CloudWatchLogs(logs.logGroup(), logs.stdoutStream(), logs.stderrStream()) : null;
    }

    /**
     * Hands a container run to a thread of its own, which runs it to its end. A run whose driver is not its
     * application's type, a {@code hive} one on a Spark application or a {@code sparkSubmit} one on a Hive
     * application, fails without running: AWS documents no answer for one, and its application's image could
     * not run it.
     */
    private void launch(JobRunRecord record, Application app) {
        JobRun run = record.getJobRun();
        JobDriver.Hive hive = run.getJobDriver().getHive();
        if (!driverIsTheApplications(run, app)) {
            run.setStateDetails(DRIVER_NOT_THE_APPLICATIONS);
            finish(record, STATE_FAILED, run.getCreatedAt());
            return;
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(run.getArn());
        // A STREAMING run "expects to run for a long time", so only a BATCH run's timeout ends it.
        Long minutes = run.getExecutionTimeoutMinutes();
        Duration timeout = MODE_STREAMING.equals(run.getMode()) || minutes == null || minutes == 0
                ? Duration.ZERO : Duration.ofMinutes(minutes);
        DriverLogDestinations logs = keptDriverLogs(record, app);
        SparkJobRunner.Job job;
        if (hive != null) {
            job = new SparkJobRunner.Job(SERVICE, run.getJobRunId(), arn.accountId(), arn.region(),
                    run.getReleaseLabel(), SparkJobRunner.Engine.HIVE, hiveImageTemplate, customImage(app),
                    dockerNetwork, run.getExecutionRole(), Map.of(), hiveArguments(app, run, hive), null, timeout,
                    logs.s3Folder(), cloudWatchLogs(logs), Map.of(), List.of(), null, Map.of(),
                    Map.of(), Map.of(), Map.of(), Map.of(), null);
        } else {
            JobDriver.SparkSubmit sparkSubmit = run.getJobDriver().getSparkSubmit();
            Map<String, String> sparkDefaults = sparkDefaults(app, run);
            Map<String, String> sparkProperties = sparkProperties(sparkDefaults, sparkSubmit);
            job = new SparkJobRunner.Job(SERVICE, run.getJobRunId(), arn.accountId(), arn.region(),
                    run.getReleaseLabel(), SparkJobRunner.Engine.SPARK, imageTemplate, customImage(app),
                    dockerNetwork, run.getExecutionRole(), sparkDefaults, application(sparkSubmit), null, timeout,
                    logs.s3Folder(), cloudWatchLogs(logs), driverEnvironment(sparkProperties),
                    archives(sparkProperties), null, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                    executors(app, executorContainers));
            // Its event logs, kept for its Spark History Server once it ends, as EMR Serverless keeps them in its
            // managed storage, where the run writes them.
            if (servesDriverUis() && logs.inManagedStorage() && writesEventLogs(sparkProperties)) {
                job = job.keepingEventLogs();
            }
        }
        // With honour-architectures on, its containers run for the application's architecture, "x86_64 is the default
        // option" on EMR Serverless; with it off, or where the application names none, for the Docker host's own.
        SparkJobRunner.Job onPlatform = job.withPlatform(
                honourArchitectures ? DockerPlatforms.forArchitecture(app.getArchitecture()) : null);
        // Where a cancel may give it a grace period, its driver runs under an init process, which passes it the
        // SIGTERM that starts the period.
        SparkJobRunner.Job launched = gracefulCancels(run.getReleaseLabel())
                ? onPlatform.asInterruptible() : onPlatform;
        executing.add(run.getJobRunId());
        long launchedIn = generation;
        runExecutor.execute(() -> execute(launched, launchedIn));
    }

    /** The runner of the docker runner mode, or null in the immediate one. */
    SparkJobRunner containerRunner() {
        return sparkRunner;
    }

    /**
     * Whether the drivers of its Spark runs and sessions serve their Spark UI for Floci to open: in the docker runner
     * mode, on a network where a container has an address of its own.
     */
    boolean servesDriverUis() {
        return sparkRunner != null && sparkRunner.servesDriverUis(dockerNetwork);
    }

    /**
     * Whether a driver with these Spark properties writes its event logs where the release's image has them, which a
     * kept history holds: unless it turns them off ({@code spark.eventLog.enabled}) or sends them elsewhere
     * ({@code spark.eventLog.dir}).
     */
    static boolean writesEventLogs(Map<String, String> properties) {
        String enabled = properties.get("spark.eventLog.enabled");
        String dir = properties.get("spark.eventLog.dir");
        if (enabled != null && !Boolean.parseBoolean(enabled.trim())) {
            return false;
        }
        if (dir == null) {
            return true;
        }
        String path = dir.trim().replaceFirst("^file:(//)?", "");
        return EmrServerlessHistories.EVENT_LOGS.equals(path.endsWith("/") ? path.substring(0, path.length() - 1)
                : path);
    }

    /** The image a Spark History Server of a release's kept histories runs: that release's, as its runs' drivers. */
    String historyImage(String releaseLabel) {
        return EmrServerlessSparkRunner.image(imageTemplate, releaseLabel);
    }

    /** The Docker network its runs' containers are on, null for the global default. */
    String dockerNetwork() {
        return dockerNetwork;
    }

    /**
     * The runs, of every account, whose kept history stays ({@link EmrServerlessHistories}): those that have not ended,
     * and those that ended after {@code cutoff}.
     */
    synchronized Set<String> historiesKept(Instant cutoff) {
        List<JobRun> runs = store.scanAllAccountEntries(key -> true).stream()
                .map(entry -> entry.value().getJobRun()).toList();
        return EmrServerlessHistories.stillKept(runs, JobRun::getJobRunId, EmrServerlessJobRunService::isTerminal,
                JobRun::getEndedAt, cutoff);
    }

    /**
     * What runs a session's Spark Connect server in a container, as a run's driver runs: in the application's image,
     * on its network, with the session's execution role and its executors as a run's are, for {@code limit} at most.
     * Its {@code spark-defaults} are the application's, then the session's {@code runtimeConfiguration}, merged as a
     * run's {@code applicationConfiguration} is, with the server's port {@code port}; a
     * {@code spark.connect.grpc.binding.address} is dropped, as "The Spark configuration
     * spark.connect.grpc.binding.address is reserved by EMR Serverless and cannot be overridden by users" (EMR
     * Serverless user guide, "Run interactive sessions with Amazon EMR Serverless through Spark Connect").
     */
    SparkJobRunner.Job sessionJob(Application app, Session session, Duration limit, int port) {
        Object overrides = session.getConfigurationOverrides() != null
                ? session.getConfigurationOverrides().get("runtimeConfiguration") : null;
        Map<String, String> sparkDefaults = classificationProperties(app, overrides, session.getReleaseLabel(),
                SPARK_DEFAULTS);
        sparkDefaults.remove(SPARK_CONNECT_ADDRESS);
        sparkDefaults.put(SPARK_CONNECT_PORT, String.valueOf(port));
        AwsArnUtils.Arn arn = AwsArnUtils.parse(session.getArn());
        SparkJobRunner.Job job = new SparkJobRunner.Job(SERVICE, session.getSessionId(), arn.accountId(),
                arn.region(), session.getReleaseLabel(), SparkJobRunner.Engine.SPARK, imageTemplate, customImage(app),
                dockerNetwork, session.getExecutionRoleArn(), sparkDefaults,
                sparkConnectServer(session.getReleaseLabel()), null, limit, null, null,
                driverEnvironment(sparkDefaults), archives(sparkDefaults), null, Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), executors(app, executorContainers));
        SparkJobRunner.Job listening = job.withPlatform(
                honourArchitectures ? DockerPlatforms.forArchitecture(app.getArchitecture()) : null).listeningOn(port);
        return servesDriverUis() && writesEventLogs(sparkDefaults) ? listening.keepingEventLogs() : listening;
    }

    /**
     * What starts the Spark Connect server on {@code spark-submit}'s command line: its class, and, on an emr- release,
     * whose Spark 3.5 keeps the server in {@value #SPARK_CONNECT_JAR}, that jar as the application; the AWS runtime
     * for Apache Spark's Spark 4 carries the server among its own jars.
     */
    static List<String> sparkConnectServer(String releaseLabel) {
        return List.of("--class", SPARK_CONNECT_SERVER,
                sparkRuntime(releaseLabel) ? SPARK_INTERNAL : SPARK_CONNECT_JAR);
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
     * The custom image a run's driver starts from, or null for the release's: its driver's worker type's,
     * {@code Driver} for a Spark application and {@code HiveDriver} for a Hive one, else the application's, which
     * "EMR Serverless applies ... to all worker types". Tez runs a Hive run's tasks in the driver's container, so a
     * {@code TezTask} image is not used; a Spark run's executors have their own, {@link #executors}.
     */
    static String customImage(Application app) {
        return workerImage(app, driverWorkerType(app.getType()));
    }

    private static String driverWorkerType(String applicationType) {
        return HIVE_TYPE.equalsIgnoreCase(applicationType) ? HIVE_DRIVER_WORKER_TYPE : DRIVER_WORKER_TYPE;
    }

    /**
     * The image configurations an attempt of a run applies: its application's, and its worker types', as they stand
     * when the attempt is made. Floci resolves a custom image for each attempt, as "EMR 7.14 and later" do ("New
     * custom images take effect for new workloads immediately") and "New job attempts will pick new images", so the
     * run's {@code workerTypeSpecifications} hold the attempt's own, as AWS's do "when the
     * applicationLevelDigestResolution is false for the application". Each is a copy holding the image's URI, with no
     * digest until the attempt's runner resolves that image, {@link #imageDigestResolved}.
     */
    private static void applyImages(JobRun run, Application app) {
        run.setImageConfiguration(pinned(app, "") != null ? copyOf(pinned(app, ""))
                : appliedImage(app.getImageConfiguration()));
        Map<String, WorkerTypeSpecification> workerTypes = new LinkedHashMap<>();
        Map<String, WorkerTypeSpecification> specifications = new LinkedHashMap<>();
        if (app.getWorkerTypeSpecifications() != null) {
            specifications.putAll(app.getWorkerTypeSpecifications());
        }
        if (app.getPinnedImages() != null) {
            app.getPinnedImages().keySet().stream().filter(key -> !key.isEmpty())
                    .forEach(key -> specifications.putIfAbsent(key, null));
        }
        if (!specifications.isEmpty()) {
            specifications.forEach((workerType, specification) -> {
                ImageConfiguration image = pinned(app, workerType) != null ? copyOf(pinned(app, workerType))
                        : specification != null ? appliedImage(specification.getImageConfiguration()) : null;
                if (image != null) {
                    WorkerTypeSpecification applied = new WorkerTypeSpecification();
                    applied.setImageConfiguration(image);
                    workerTypes.put(workerType, applied);
                }
            });
        }
        run.setWorkerTypeSpecifications(workerTypes.isEmpty() ? null : workerTypes);
    }

    private static ImageConfiguration appliedImage(ImageConfiguration image) {
        if (image == null || image.getImageUri() == null) {
            return null;
        }
        ImageConfiguration applied = new ImageConfiguration();
        applied.setImageUri(image.getImageUri());
        return applied;
    }

    // The image the application resolved for a worker type ("" its own) as it started, which its runs take until it
    // starts again (EmrServerlessService.pinImages); null where it resolved none.
    private static ImageConfiguration pinned(Application app, String key) {
        return app.getPinnedImages() != null ? app.getPinnedImages().get(key) : null;
    }

    private static ImageConfiguration copyOf(ImageConfiguration image) {
        ImageConfiguration copy = new ImageConfiguration();
        copy.setImageUri(image.getImageUri());
        copy.setResolvedImageDigest(image.getResolvedImageDigest());
        return copy;
    }

    // The image a run pulls for a resolved one: its repository by its digest, as its tag may since name another.
    private static String byDigest(ImageConfiguration image) {
        String uri = image.getImageUri();
        if (uri.contains("@")) {
            return uri;
        }
        int colon = uri.lastIndexOf(':');
        String repository = colon > uri.lastIndexOf('/') ? uri.substring(0, colon) : uri;
        return repository + "@" + image.getResolvedImageDigest();
    }

    // The image configuration an attempt applies to a worker type: the worker type's own, else the run's, as
    // workerImage chose its image.
    private static ImageConfiguration appliedImage(JobRun run, String workerType) {
        WorkerTypeSpecification worker = run.getWorkerTypeSpecifications() != null
                ? run.getWorkerTypeSpecifications().get(workerType) : null;
        return worker != null ? worker.getImageConfiguration() : run.getImageConfiguration();
    }

    // A worker type's custom image, else the application's, else null for the release's: the one the application
    // resolved as it started where it resolved it then, by its digest, else the one it names now.
    private static String workerImage(Application app, String workerType) {
        if (pinned(app, workerType) != null) {
            return byDigest(pinned(app, workerType));
        }
        WorkerTypeSpecification worker = app.getWorkerTypeSpecifications() != null
                ? app.getWorkerTypeSpecifications().get(workerType) : null;
        if (worker != null && worker.getImageConfiguration() != null
                && worker.getImageConfiguration().getImageUri() != null) {
            return worker.getImageConfiguration().getImageUri();
        }
        if (pinned(app, "") != null) {
            return byDigest(pinned(app, ""));
        }
        return app.getImageConfiguration() != null ? app.getImageConfiguration().getImageUri() : null;
    }

    /**
     * Where a Spark run's executors run: in containers of their own, at most {@code containers} at once, in the
     * {@code Executor} worker type's custom image, else the application's, and within the application's maximum
     * capacity, which its runs share; or, with {@code containers} 0, in the driver's container, which Spark's local
     * mode runs them in.
     */
    static SparkJobRunner.Executors executors(Application app, int containers) {
        if (containers <= 0) {
            return null;
        }
        MaximumCapacity capacity = app.getMaximumCapacity();
        return new SparkJobRunner.Executors(containers, workerImage(app, EXECUTOR_WORKER_TYPE),
                capacity != null ? capacityAmount(capacity.getCpu()) : 0,
                capacity != null ? capacityAmount(capacity.getMemory()) : 0,
                capacity != null ? capacityAmount(capacity.getDisk()) : 0, app.getArn());
    }

    // A maximum capacity's amount, as "400 vCPU" or "1024GB" give it, or 0 for none.
    static long capacityAmount(String value) {
        if (value == null) {
            return 0;
        }
        int digits = 0;
        while (digits < value.length() && Character.isDigit(value.charAt(digits))) {
            digits++;
        }
        if (digits == 0) {
            return 0;
        }
        return digits > 18 ? Long.MAX_VALUE : Long.parseLong(value.substring(0, digits));
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
     * What follows Floci's own settings on a Hive run's {@code hive} command line, in the user guide's order, a later
     * setting winning: its {@code core-site}, {@code tez-site} and {@code hive-site} properties, each a
     * {@code --hiveconf}, then its {@code initQueryFile} and {@code query}, which Hive runs in that order, then its
     * {@code parameters}, split as a shell would split them, whose {@code --hiveconf} settings "take priority".
     */
    static List<String> hiveArguments(Application app, JobRun run, JobDriver.Hive hive) {
        List<String> arguments = new ArrayList<>();
        for (String classification : HIVE_CLASSIFICATIONS) {
            arguments.addAll(EmrServerlessSparkRunner.hiveSettings(classificationProperties(app, run, classification)));
        }
        if (hive.getInitQueryFile() != null) {
            arguments.add("-i");
            arguments.add(hive.getInitQueryFile());
        }
        arguments.add("-f");
        arguments.add(hive.getQuery());
        arguments.addAll(EmrServerlessSparkRunner.splitParameters(hive.getParameters()));
        return arguments;
    }

    /**
     * A run's Spark properties as its driver gets them: its {@code spark-defaults}, then the {@code --conf}
     * settings of its {@code sparkSubmitParameters}, which {@code spark-submit} lets win, a later one over an
     * earlier. A setting without an {@code =}, which {@code spark-submit} refuses, sets nothing. The last
     * {@code --archives} option is {@code spark.archives}, winning over any setting of it, as
     * {@code spark-submit} has it.
     */
    static Map<String, String> sparkProperties(Map<String, String> sparkDefaults, JobDriver.SparkSubmit sparkSubmit) {
        Map<String, String> properties = new LinkedHashMap<>(sparkDefaults);
        List<String> parameters = EmrServerlessSparkRunner.splitParameters(sparkSubmit.getSparkSubmitParameters());
        String archivesOption = null;
        for (int i = 0; i < parameters.size(); i++) {
            String parameter = parameters.get(i);
            String setting = null;
            if (parameter.startsWith(CONF_OPTION + "=")) {
                setting = parameter.substring(CONF_OPTION.length() + 1);
            } else if ((CONF_OPTION.equals(parameter) || CONF_SHORT_OPTION.equals(parameter))
                    && i + 1 < parameters.size()) {
                setting = parameters.get(++i);
            } else if (parameter.startsWith(ARCHIVES_OPTION + "=")) {
                archivesOption = parameter.substring(ARCHIVES_OPTION.length() + 1);
            } else if (ARCHIVES_OPTION.equals(parameter) && i + 1 < parameters.size()) {
                archivesOption = parameters.get(++i);
            }
            int equals = setting != null ? setting.indexOf('=') : -1;
            if (equals > 0) {
                properties.put(setting.substring(0, equals), setting.substring(equals + 1));
            }
        }
        if (archivesOption != null) {
            properties.put(SPARK_ARCHIVES, archivesOption);
        }
        return properties;
    }

    /** The environment variables a run's driver gets: its {@code spark.emr-serverless.driverEnv.<KEY>} properties. */
    static Map<String, String> driverEnvironment(Map<String, String> sparkProperties) {
        Map<String, String> environment = new LinkedHashMap<>();
        sparkProperties.forEach((key, value) -> {
            if (key.startsWith(DRIVER_ENV_PREFIX) && key.length() > DRIVER_ENV_PREFIX.length()) {
                environment.put(key.substring(DRIVER_ENV_PREFIX.length()), value);
            }
        });
        return environment;
    }

    /**
     * A run's {@code spark.archives}: a comma-separated list, of which Spark takes each entry trimmed and drops
     * the empty ones.
     */
    static List<String> archives(Map<String, String> sparkProperties) {
        String archives = sparkProperties.get(SPARK_ARCHIVES);
        if (archives == null) {
            return List.of();
        }
        return Arrays.stream(archives.split(",")).map(String::trim).filter(archive -> !archive.isEmpty()).toList();
    }

    /**
     * A reset has begun: the runs' threads from before it write nothing more, so the storage wipe that
     * follows leaves none of their runs behind. Its teardown has already stopped their drivers.
     */
    @Override
    public synchronized void beforeReset() {
        generation++;
        executing.clear();
        runIdsByApplication.clear();
    }

    @Override
    public synchronized void clear() {
        // The runs live in storage, which the reset has already wiped; ids read before the wipe go with them.
        runIdsByApplication.clear();
    }

    // Whether a run's driver may still start, or go on: a cancelled run is past it, and so is one a reset removed.
    private synchronized boolean stillGoing(String jobRunId, long launchedIn) {
        return launchedIn == generation && store.get(jobRunId)
                .map(record -> STATE_SCHEDULED.equals(record.getJobRun().getState())
                        || STATE_RUNNING.equals(record.getJobRun().getState()) || shuttingDown(record))
                .orElse(false);
    }

    /**
     * The inline policy of a run's executionIamPolicy, which scopes its credentials: "The resulting job IAM role
     * permissions will be an intersection of this policy and the policy associated with your job execution role".
     * Null where it has none. Its policyArns are not applied, as Floci's IAM applies no managed session policy, an
     * assumed role's included.
     */
    private synchronized String inlinePolicy(String jobRunId) {
        return store.get(jobRunId).map(record -> record.getJobRun().getExecutionIamPolicy())
                .map(policy -> policy.get(INLINE_POLICY)).filter(String.class::isInstance).map(String.class::cast)
                .orElse(null);
    }

    // A run cancelled with a grace period that has not ended, whose driver may still end by itself.
    private boolean shuttingDown(JobRunRecord record) {
        return STATE_CANCELLING.equals(record.getJobRun().getState()) && record.getShutdownDeadline() != null
                && clock.instant().isBefore(record.getShutdownDeadline());
    }

    /**
     * The grace period, in seconds, of a cancel of {@code run}. From Amazon EMR release 7.9.0 a RUNNING run is given
     * one, "during which the job can perform cleanup operations prior to final termination", the one asked for or else
     * the default: "The default behavior for shutdown is to have no grace period" for a batch run, and a streaming run
     * has a "120-second grace period enabled by default". "Grace period shutdown is only available for jobs in
     * RUNNING state", so another run, or one on an earlier release, ends at once (EMR Serverless User Guide, "EMR
     * Serverless job run cancellation with grace period").
     */
    private static long gracePeriod(JobRun run, Integer asked) {
        if (!STATE_RUNNING.equals(run.getState()) || !gracefulCancels(run.getReleaseLabel())) {
            return 0;
        }
        if (asked != null) {
            return asked;
        }
        return MODE_STREAMING.equals(run.getMode()) ? DEFAULT_STREAMING_GRACE_SECONDS : 0;
    }

    /**
     * Refuses a grace period other than 0, "Immediate termination", or one in the "Valid Range for Shutdown Grace
     * period: 15-1800 seconds". No source gives AWS's error for one outside it, so the wording is Floci's.
     */
    private static void checkGracePeriod(Integer seconds) {
        if (seconds != null && seconds != 0 && (seconds < MIN_GRACE_SECONDS || seconds > MAX_GRACE_SECONDS)) {
            throw new AwsException("ValidationException", "shutdownGracePeriodInSeconds must be 0, or from "
                    + MIN_GRACE_SECONDS + " to " + MAX_GRACE_SECONDS + " seconds.", 400);
        }
    }

    // "This feature is supported with Amazon EMR releases 7.9.0 and higher", the AWS runtime for Apache Spark's too.
    private static boolean gracefulCancels(String releaseLabel) {
        return releaseAtLeast(RELEASE_LABEL, releaseLabel, 7, 9) || sparkRuntime(releaseLabel);
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

    /**
     * The run's latest attempt resolved its {@code workerType}'s custom image to the image whose manifest digest is
     * {@code digest}: the image configuration that worker type took its image from, its own or the run's, both copied
     * from the application the attempt's job was made from, gets it as its {@code resolvedImageDigest}. Another naming
     * the same image does not: its image may not run, as an {@code Executor} image while the executors run in the
     * driver's container, or was pulled apart, maybe after a push. One the driver and the executors both take their
     * image from holds the driver's.
     */
    private synchronized void imageDigestResolved(String jobRunId, SparkJobRunner.WorkerType workerType, String digest,
                                                  long launchedIn) {
        if (launchedIn != generation) {
            return;
        }
        store.get(jobRunId).ifPresent(record -> {
            JobRun run = record.getJobRun();
            ImageConfiguration driver = appliedImage(run, driverWorkerType(record.getApplicationType()));
            ImageConfiguration applied = workerType == SparkJobRunner.WorkerType.DRIVER
                    ? driver : appliedImage(run, EXECUTOR_WORKER_TYPE);
            if (applied == null || (workerType == SparkJobRunner.WorkerType.EXECUTOR && applied == driver)) {
                return;
            }
            applied.setResolvedImageDigest(digest);
            store.put(jobRunId, record);
        });
    }

    // How long each of a run's executors' workers ran, for its attempt's resource utilization.
    private synchronized void keepExecutorLifetimes(String jobRunId, long launchedIn, List<Duration> lifetimes) {
        if (launchedIn != generation) {
            return;
        }
        store.get(jobRunId).ifPresent(record -> {
            record.setExecutorLifetimes(lifetimes.stream().map(Duration::toMillis).toList());
            store.put(jobRunId, record);
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
        // Its running slot goes to the first queued run.
        settleApplication(run.getApplicationId(), record);
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
        end(record, failed, STATE_FAILED, now);
        record.setExecutorLifetimes(null);
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
        run.setTotalResourceUtilization(null);
        run.setBilledResourceUtilization(null);
        applyImages(run, app);
        keepDriverLogs(record, app);
        store.put(run.getJobRunId(), record);
        LOG.infov("EMR Serverless job run {0} failed its attempt {1}; attempt {2} starts", run.getJobRunId(),
                failed.getAttempt(), run.getAttempt());
        launch(record, app);
    }

    /**
     * The workers a Spark run asks for ({@link EmrServerlessWorkers}), from its properties as {@code spark-submit} gets
     * them; none for a Hive run, whose Tez tasks come and go with its query.
     */
    private static RunWorkers workers(Application app, JobRun run) {
        JobDriver.SparkSubmit sparkSubmit = run.getJobDriver() != null ? run.getJobDriver().getSparkSubmit() : null;
        return sparkSubmit != null ? EmrServerlessWorkers.plan(sparkProperties(sparkDefaults(app, run), sparkSubmit),
                sparkSubmit.getSparkSubmitParameters()) : null;
    }

    /** The {@code spark-defaults} properties for a run, as {@link #classificationProperties} merges them. */
    private static Map<String, String> sparkDefaults(Application app, JobRun run) {
        return classificationProperties(app, run, SPARK_DEFAULTS);
    }

    /**
     * A {@code classification}'s properties for a run: the application's runtime configuration, then the
     * run's own {@code applicationConfiguration}. The two merge by classification, so a run's
     * classification replaces the application's: "Only the properties included in StartJobRun is
     * considered for this classification." From emr-spark-8.1.0 they merge by property, the run's winning.
     */
    private static Map<String, String> classificationProperties(Application app, JobRun run, String classification) {
        Object overrides = run.getConfigurationOverrides() != null
                ? run.getConfigurationOverrides().get("applicationConfiguration") : null;
        return classificationProperties(app, overrides, run.getReleaseLabel(), classification);
    }

    /**
     * A {@code classification}'s properties: the application's runtime configuration, then {@code overrides}, a
     * workload's own configuration list on {@code releaseLabel}, merged as {@link #classificationProperties} merges a
     * run's.
     */
    private static Map<String, String> classificationProperties(Application app, Object overrides,
                                                                String releaseLabel, String classification) {
        Map<String, String> properties = new LinkedHashMap<>();
        if (app.getRuntimeConfiguration() != null) {
            for (RuntimeConfiguration configuration : app.getRuntimeConfiguration()) {
                addProperties(properties, configuration.getMembers(), classification);
            }
        }
        if (overrides instanceof List<?> configurations) {
            boolean replaces = !releaseAtLeast(SPARK_RUNTIME_RELEASE_LABEL, releaseLabel, 8, 1);
            for (Object configuration : configurations) {
                if (configuration instanceof Map<?, ?> members
                        && classification.equals(members.get("classification"))) {
                    if (replaces) {
                        properties.clear();
                        replaces = false;
                    }
                    addProperties(properties, members, classification);
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
     * enabled" from release 7.1.0, and the driver's in {@code SPARK_DRIVER/}, or a Hive run's in
     * {@code HIVE_DRIVER/}: "EMR Serverless continuously uploads the Hive driver to the HIVE_DRIVER folder".
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
     * and {@code .../SPARK_DRIVER/stderr}, {@code HIVE_DRIVER} for a Hive run, the worker type {@code logTypes}
     * names its driver by. A {@code logTypes} that names some types takes only those of the
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
            driverTypes = logTypes.get(driverWorkerType(run)) instanceof List<?> types ? types : List.of();
        }
        String stdout = publishes(driverTypes, "stdout") ? driver + "stdout" : null;
        String stderr = publishes(driverTypes, "stderr") ? driver + "stderr" : null;
        return stdout == null && stderr == null ? null : new SparkJobRunner.CloudWatchLogs(logGroup, stdout, stderr);
    }

    // Whether the driver's log types, as logTypes lists them for its worker type, take this one; both without a list.
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
                + driverWorkerType(run);
    }

    // The worker type a run's driver logs as: HIVE_DRIVER for a Hive run, SPARK_DRIVER for a Spark one.
    private static String driverWorkerType(JobRun run) {
        return run.getJobDriver() != null && run.getJobDriver().getHive() != null ? HIVE_DRIVER : SPARK_DRIVER;
    }

    // A STREAMING run is retried, and a BATCH run whose retryPolicy allows more than one attempt.
    private static boolean retried(JobRun run) {
        return MODE_STREAMING.equals(run.getMode()) || retryLimit(run, "maxAttempts", DEFAULT_MAX_ATTEMPTS) > 1;
    }

    // "EMR Serverless releases 7.1.0 and higher support retry attempts for streaming jobs and batch jobs."
    private static boolean retriesAttempts(String releaseLabel) {
        return releaseAtLeast(RELEASE_LABEL, releaseLabel, 7, 1) || sparkRuntime(releaseLabel);
    }

    private static void addProperties(Map<String, String> properties, Map<?, ?> configuration, String classification) {
        if (classification.equals(configuration.get("classification"))
                && configuration.get("properties") instanceof Map<?, ?> values) {
            values.forEach((key, value) -> properties.put(String.valueOf(key), String.valueOf(value)));
        }
    }

    private JobRunRecord settle(JobRunRecord record) {
        settleApplication(record.getJobRun().getApplicationId(), record);
        return record;
    }

    /**
     * The application's runs, {@code given} among them as the caller holds it, brought up to now, each change stored
     * in one write. What happened since is replayed in order. A BATCH run of the immediate runner mode is SUCCESS once
     * it has been RUNNING for the configured duration, or CANCELLED at its execution timeout when that comes first: "If
     * the job run exceeds this duration, Amazon EMR Serverless cancels it automatically." The running slot a run frees
     * goes at that moment to the first queued run: "Remaining jobs are queued until one of the running jobs
     * terminates." A run still queued at its queue timeout "times out", which the job run states make a reason to be
     * cancelled. A container run left without the thread that runs it was stopped by Floci's own shutdown, and is
     * FAILED. A slot free now, after a cancel, an update or a container run's end, goes to the queue now.
     */
    private List<JobRunRecord> settleApplication(String applicationId, JobRunRecord given) {
        List<JobRunRecord> runs = runsOf(applicationId, given);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Application app = applications.findLive(applicationId).orElse(null);
        Replay replay = new Replay(app, app != null ? EmrServerlessScheduler.limits(app) : null);
        List<JobRunRecord> queued = new ArrayList<>();
        for (JobRunRecord record : runs) {
            JobRun run = record.getJobRun();
            if (isTerminal(run)) {
                continue;
            }
            if (STATE_QUEUED.equals(run.getState())) {
                queued.add(record);
            } else if (record.isContainerRun() && !executing.contains(run.getJobRunId())) {
                run.setStateDetails(INTERRUPTED);
                end(record, run, STATE_FAILED, now);
                replay.changed.add(record);
            } else {
                replay.running(record);
            }
        }
        queued.sort(Comparator.comparingLong(JobRunRecord::getSequence));
        replay.queue.addAll(queued);
        replay.run(now);
        if (!replay.changed.isEmpty()) {
            Map<String, JobRunRecord> writes = new LinkedHashMap<>();
            replay.changed.forEach(record -> writes.put(record.getJobRun().getJobRunId(), record));
            store.putAll(writes);
        }
        // Once the changes are stored, and each counted as going first: a thread that runs at once would read them, and
        // read a container run that has no thread as one Floci's shutdown stopped.
        replay.launched.forEach(record -> executing.add(record.getJobRun().getJobRunId()));
        replay.launched.forEach(record -> launch(record, app));
        return runs;
    }

    /**
     * The runs of the application {@code applicationId}, {@code given} as the caller holds it, read by their ids alone.
     * Each account's ids are taken from its stored runs the first time they are needed, and kept as runs start.
     */
    private List<JobRunRecord> runsOf(String applicationId, JobRunRecord given) {
        Map<String, Set<String>> byApplication = runIdsByApplication.computeIfAbsent(regionResolver.getAccountId(),
                account -> {
                    Map<String, Set<String>> built = new HashMap<>();
                    for (JobRunRecord record : store.scan(key -> true)) {
                        built.computeIfAbsent(record.getJobRun().getApplicationId(), id -> new LinkedHashSet<>())
                                .add(record.getJobRun().getJobRunId());
                    }
                    return built;
                });
        List<JobRunRecord> runs = new ArrayList<>();
        for (String jobRunId : byApplication.getOrDefault(applicationId, Set.of())) {
            if (given != null && given.getJobRun().getJobRunId().equals(jobRunId)) {
                runs.add(given);
            } else {
                store.get(jobRunId).ifPresent(runs::add);
            }
        }
        return runs;
    }

    // Keeps a new run's id with its application's, once the account's ids have been taken from its stored runs.
    private void indexRun(JobRun run) {
        Map<String, Set<String>> byApplication = runIdsByApplication.get(regionResolver.getAccountId());
        if (byApplication != null) {
            byApplication.computeIfAbsent(run.getApplicationId(), id -> new LinkedHashSet<>()).add(run.getJobRunId());
        }
    }

    /** One settling of an application's runs: what holds a running slot, what waits for one, and what changed. */
    private final class Replay {
        private final Application app;
        private final EmrServerlessScheduler.Limits limits;
        // The runs that end on their own, by when; the others that hold a slot, which their thread or a cancel ends.
        private final PriorityQueue<Timed> timed = new PriorityQueue<>(Comparator.comparing(Timed::at));
        private int untimed;
        private final Deque<JobRunRecord> queue = new ArrayDeque<>();
        // By identity: whatever the records' own equality.
        private final Set<JobRunRecord> changed = Collections.newSetFromMap(new IdentityHashMap<>());
        private final List<JobRunRecord> launched = new ArrayList<>();

        Replay(Application app, EmrServerlessScheduler.Limits limits) {
            this.app = app;
            this.limits = limits;
        }

        void running(JobRunRecord record) {
            Ending ending = timedEnding(record);
            if (ending != null) {
                timed.add(new Timed(record, ending));
            } else {
                untimed++;
            }
        }

        /**
         * Replays the endings and queue timeouts due by {@code now} in order, each freed slot going to the queue at
         * that moment, and then the slots free now; a run started now that ends at once frees its slot again.
         */
        void run(Instant now) {
            while (true) {
                Timed first = timed.peek();
                Instant endsAt = first != null && !first.at().isAfter(now) ? first.at() : null;
                JobRunRecord head = queue.peekFirst();
                Instant timesOutAt = head != null && limits != null
                        ? head.getJobRun().getCreatedAt().plus(limits.queueTimeout()) : null;
                if (timesOutAt != null && !timesOutAt.isAfter(now)
                        && (endsAt == null || timesOutAt.isBefore(endsAt))) {
                    queue.pollFirst();
                    JobRun run = head.getJobRun();
                    leaveQueue(run, timesOutAt);
                    // No source gives AWS's stateDetails for it; the wording is Floci's.
                    run.setStateDetails("Job run timed out after " + limits.queueTimeout().toMinutes()
                            + " minutes in the queue.");
                    end(head, run, STATE_CANCELLED, timesOutAt);
                    changed.add(head);
                } else if (endsAt != null) {
                    timed.poll();
                    end(first.record(), first.record().getJobRun(), first.ending().state(), endsAt);
                    changed.add(first.record());
                    startQueued(endsAt);
                } else if (!startQueued(now)) {
                    return;
                }
            }
        }

        /**
         * Starts the first queued runs at the moment {@code at} while the limit leaves a running slot, and says whether
         * it took any from the queue: RUNNING in the immediate runner mode, SCHEDULED in the docker one, its driver
         * launched once the changes are stored. A run whose driver is not its application's type fails as it leaves
         * the queue, as {@link #launch} fails one, and takes no slot.
         */
        boolean startQueued(Instant at) {
            boolean took = false;
            while (app != null && !queue.isEmpty()
                    && (limits == null || timed.size() + untimed < limits.maxConcurrentRuns())) {
                JobRunRecord record = queue.pollFirst();
                JobRun run = record.getJobRun();
                leaveQueue(run, at);
                run.setUpdatedAt(at);
                run.setAttemptUpdatedAt(at);
                record.setContainerRun(sparkRunner != null);
                changed.add(record);
                took = true;
                if (sparkRunner == null) {
                    run.setState(STATE_RUNNING);
                    run.setStartedAt(at);
                    running(record);
                } else if (!driverIsTheApplications(run, app)) {
                    run.setStateDetails(DRIVER_NOT_THE_APPLICATIONS);
                    end(record, run, STATE_FAILED, at);
                } else {
                    run.setState(STATE_SCHEDULED);
                    launched.add(record);
                    untimed++;
                }
            }
            return took;
        }
    }

    // A run that ends on its own, and how.
    private record Timed(JobRunRecord record, Ending ending) {
        Instant at() {
            return ending.at();
        }
    }

    // A run leaves the QUEUED state at the moment at, after waiting there since it was created.
    private static void leaveQueue(JobRun run, Instant at) {
        run.setQueuedDurationMilliseconds(Duration.between(run.getCreatedAt(), at).toMillis());
    }

    /** Whether a run's driver is its application's type: a {@code hive} one on a Hive application, else Spark's. */
    private static boolean driverIsTheApplications(JobRun run, Application app) {
        return (run.getJobDriver().getHive() != null) == HIVE_TYPE.equalsIgnoreCase(app.getType());
    }

    // The runs that hold a running slot: those that have not ended and are not queued.
    private static List<JobRunRecord> active(List<JobRunRecord> runs) {
        return runs.stream()
                .filter(record -> !isTerminal(record.getJobRun())
                        && !STATE_QUEUED.equals(record.getJobRun().getState()))
                .toList();
    }

    /**
     * When a run of the immediate runner mode ends, and how: a BATCH run after the configured duration, or at its
     * execution timeout when that comes first. Null for a container run, which its thread ends, and for a STREAMING
     * run, which runs until it is cancelled.
     */
    private Ending timedEnding(JobRunRecord record) {
        JobRun run = record.getJobRun();
        if (record.isContainerRun() || MODE_STREAMING.equals(run.getMode()) || run.getStartedAt() == null) {
            return null;
        }
        long timeoutSeconds = run.getExecutionTimeoutMinutes() == null || run.getExecutionTimeoutMinutes() == 0
                ? Long.MAX_VALUE : run.getExecutionTimeoutMinutes() * 60;
        if (runDurationSeconds > timeoutSeconds) {
            return new Ending(run.getStartedAt().plusSeconds(timeoutSeconds), STATE_CANCELLED);
        }
        return new Ending(run.getStartedAt().plusSeconds(runDurationSeconds), STATE_SUCCESS);
    }

    private record Ending(Instant at, String state) {
    }

    private void finish(JobRunRecord record, String state, Instant endedAt) {
        end(record, record.getJobRun(), state, endedAt);
        store.put(record.getJobRun().getJobRunId(), record);
    }

    // Ends an attempt of a run, as of endedAt, with what its workers used.
    private void end(JobRunRecord record, JobRun run, String state, Instant endedAt) {
        run.setState(state);
        run.setEndedAt(endedAt);
        run.setUpdatedAt(endedAt);
        run.setAttemptUpdatedAt(endedAt);
        // A container run cancelled or failed before its driver ran never started.
        run.setTotalExecutionDurationSeconds(run.getStartedAt() != null
                ? (int) Duration.between(run.getStartedAt(), endedAt).toSeconds() : 0);
        EmrServerlessWorkers.account(run, workersOf(record), record.getExecutorLifetimes());
    }

    /**
     * The workers a run asked for: as it was admitted, or, for a Spark run stored before Floci kept them, from its
     * application and request now, while its application is live.
     */
    private RunWorkers workersOf(JobRunRecord record) {
        JobRun run = record.getJobRun();
        if (record.getWorkers() == null && run.getJobDriver() != null && run.getJobDriver().getSparkSubmit() != null) {
            applications.findLive(run.getApplicationId()).ifPresent(app -> record.setWorkers(workers(app, run)));
        }
        return record.getWorkers();
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

    private boolean inRequestRegion(JobRun run) {
        return regionResolver.getRegion().equals(AwsArnUtils.parse(run.getArn()).region());
    }

    static boolean isTerminal(JobRun run) {
        return TERMINAL_STATES.contains(run.getState());
    }

    private static void validate(StartJobRunRequest request) {
        Map<String, Integer> retryLimits = retryLimits(request.getRetryPolicy());
        // FlexExecution's one member, "enabled", a boolean; another JSON type is refused as retryPolicy's are.
        Object flex = request.getFlexExecution() != null ? request.getFlexExecution().get("enabled") : null;
        if (flex != null && !(flex instanceof Boolean)) {
            throw new AwsException("SerializationException", "flexExecution.enabled must be a boolean", 400);
        }
        List<String> violations = executionIamPolicyViolations(request.getExecutionIamPolicy());
        if (request.getClientToken() == null) {
            violations.add(SmithyValidationErrors.notNull("clientToken"));
        }
        String roleViolation = executionRoleViolation(request.getExecutionRoleArn());
        if (roleViolation != null) {
            violations.add(roleViolation);
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

    /** What is wrong with a request's {@code executionRoleArn}, which the model requires: null when nothing is. */
    static String executionRoleViolation(String executionRoleArn) {
        if (executionRoleArn == null) {
            return SmithyValidationErrors.notNull("executionRoleArn");
        }
        return ROLE_ARN.matcher(executionRoleArn).matches() ? null
                : "Value at 'executionRoleArn' failed to satisfy constraint: "
                        + "Member must satisfy regular expression pattern: " + ROLE_ARN_PATTERN;
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
     * From Amazon EMR release 7.11.0 a BATCH run's timeout is 1 to 10080 minutes, on the AWS runtime for Apache
     * Spark's releases too, as the 7.11.0 release notes' "from this release onwards" reads and the release list puts
     * them after EMR Serverless 7.14.0; an earlier release, or a STREAMING run, keeps the model's 0 to 1,000,000, 0
     * meaning none. No source gives AWS's error, so the wording is Floci's.
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
        return releaseAtLeast(RELEASE_LABEL, releaseLabel, 7, 11) || sparkRuntime(releaseLabel);
    }

    /**
     * Whether {@code releaseLabel} is a release of the AWS runtime for Apache Spark, emr-spark-8.0 or later, which
     * starts at emr-spark-8.0-preview and comes after EMR Serverless 7.14.0 in the release list.
     */
    public static boolean sparkRuntime(String releaseLabel) {
        return releaseAtLeast(SPARK_RUNTIME_RELEASE_LABEL, releaseLabel, 8, 0);
    }

    // Whether releaseLabel is of the family labelPattern matches, at major.minor or later.
    static boolean releaseAtLeast(Pattern labelPattern, String releaseLabel, int major, int minor) {
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
        summary.setFlexExecution(run.getFlexExecution());
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
        summary.setFlexExecution(attempt.getFlexExecution());
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
        public void imageResolved(SparkJobRunner.WorkerType workerType, String digest) {
            RequestScopes.runAs(job.accountId(), job.region(),
                    () -> imageDigestResolved(job.runId(), workerType, digest, launchedIn));
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

        @Override
        public void executorsRan(List<Duration> lifetimes) {
            RequestScopes.runAs(job.accountId(), job.region(),
                    () -> keepExecutorLifetimes(job.runId(), launchedIn, lifetimes));
        }

        @Override
        public String sessionPolicy() {
            return RequestScopes.callAs(job.accountId(), job.region(), () -> inlinePolicy(job.runId()));
        }
    }
}
