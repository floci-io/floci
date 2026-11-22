package io.floci.aws.services.emrcontainers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
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
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.eks.EksService;
import io.floci.aws.services.eks.model.AccessConfig;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.emrserverless.EmrServerlessSparkRunner;
import io.floci.aws.services.emrserverless.SparkJobRunner;
import io.floci.aws.services.iam.IamService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
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
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EMR on EKS ({@code emr-containers}): virtual clusters, job runs, job templates, managed endpoints
 * and security configurations, as control-plane records. In the {@code immediate} runner mode a job run
 * follows the job run states without running anything: it completes as soon as it is submitted, or after
 * {@code floci.services.emrcontainers.job-run-duration-seconds}. In the {@code docker} one it runs its Spark
 * job in a container ({@link EmrContainersSparkJobs}): SUBMITTED while the container starts, RUNNING once its
 * driver runs, and COMPLETED or FAILED as its driver exits. Either way it fails as AWS documents when
 * Floci's EKS has no cluster of the virtual cluster's name. A virtual cluster's concurrent job
 * limits hold a run beyond its running limit in a queue, and refuse one beyond its queue depth.
 *
 * <p>Every resource is stored as the JSON AWS answers for it, under its ARN. Members whose name
 * starts with {@code _} are Floci's own and never leave the service.
 */
@ApplicationScoped
public class EmrContainersService implements Resettable, StoredResourceArns {

    static final String SERVICE = "emr-containers";
    private static final Logger LOG = Logger.getLogger(EmrContainersService.class);
    static final String VIRTUAL_CLUSTERS = "/virtualclusters/";
    static final String JOB_RUNS = "/jobruns/";
    static final String ENDPOINTS = "/endpoints/";
    static final String JOB_TEMPLATES = "/jobtemplates/";
    static final String SECURITY_CONFIGURATIONS = "/securityconfigurations/";

    static final int VIRTUAL_CLUSTER_ID_LENGTH = 25;
    static final int JOB_RUN_ID_LENGTH = 19;
    static final int ENDPOINT_ID_LENGTH = 13;

    private static final String CLIENT_TOKEN = "_clientToken";
    private static final String DUE_AT = "_dueAt";
    // When a run whose driver failed starts its next attempt, while it waits for its retry's backoff.
    private static final String RETRY_AT = "_retryAt";
    // The id of the Spark application a run submits, the same for each of its attempts: a retry restarts the driver
    // pod, which the guide tells apart by its suffix alone.
    private static final String SPARK_APPLICATION_ID = "_sparkApplicationId";
    // "an exponential retry interval of (10 seconds, 20 seconds, 40 seconds ...) which is capped at 6 minutes"
    // (EMR on EKS Development Guide, "Using job retry policies").
    private static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(10);
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(6);
    private static final String INVALID_MAX_ATTEMPTS =
            "Retry policy configuration's parameter value of maxAttempts is invalid";
    private static final int DRIVER_POD_SUFFIX_LENGTH = 5;
    // Where a job run held in its virtual cluster's queue stands in it, the first to start lowest.
    private static final String QUEUE_POSITION = "_queuePosition";
    private static final String ACCESS_ENTRY_INTEGRATED = "_eksAccessEntryIntegrated";
    private static final Set<String> ACCESS_ENTRY_MODES = Set.of("API", "API_AND_CONFIG_MAP");
    private static final String RUNNING = "RUNNING";
    private static final String PENDING = "PENDING";
    private static final String SUBMITTED = "SUBMITTED";
    private static final String CANCEL_PENDING = "CANCEL_PENDING";
    private static final String TERMINATED = "TERMINATED";
    private static final String COMPLETED = "COMPLETED";
    private static final String CANCELLED = "CANCELLED";
    private static final String FAILED = "FAILED";
    private static final String ACTIVE = "ACTIVE";
    private static final Set<String> FINISHED_JOB_RUN_STATES = Set.of(COMPLETED, CANCELLED, FAILED);
    private static final Set<String> QUEUED_JOB_RUN_STATES = Set.of(PENDING, SUBMITTED);
    // The states of a run whose container runs, or is about to, in the docker runner mode: each holds a running slot.
    private static final Set<String> LAUNCHED_JOB_RUN_STATES = Set.of(SUBMITTED, RUNNING, CANCEL_PENDING);
    private static final String RUNNER_DOCKER = "docker";
    // AWS's answer for a run whose Spark driver exits with an error, as DescribeJobRun gave it (apache/iceberg#4804).
    private static final String DRIVER_FAILED = "Jobrun failed. Main Spark container terminated with errors. Please "
            + "refer logs uploaded to S3/CloudWatch based on your monitoring configuration.";
    // AWS's "Common errors when running jobs": a submission that failed for an internal error, and a run whose
    // monitoring an internal error ended, as a Floci restart ends a running driver's.
    private static final String SUBMISSION_FAILED = "JobRun submission failed due to an internal error.";
    private static final String MONITORING_FAILED = "Trouble monitoring your JobRun due to an internal error";
    // The same page's answer for a run whose StartJobRun parameters are not valid.
    private static final String INVALID_SUBMISSION = "JobRun submission failed.";
    private static final String USER_ERROR = "USER_ERROR";
    private static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    private static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    // The development guide's concurrent job limits: "By default, the per-virtual-cluster concurrency limit is set
    // to 1000 concurrent job runs", and "buffered jobs fail after 30 minutes if no slot frees up".
    private static final int DEFAULT_MAX_CONCURRENT_JOB_RUNS = 1000;
    private static final Duration QUEUE_TIMEOUT = Duration.ofMinutes(30);
    private static final Set<String> FINISHED_ENDPOINT_STATES = Set.of(TERMINATED, "TERMINATED_WITH_ERRORS");
    private static final String TOKEN_CREDENTIAL = "TOKEN";
    private static final int DEFAULT_SESSION_SECONDS = 15 * 60;
    private static final int MAX_SESSION_SECONDS = 12 * 60 * 60;
    private static final int MAX_TAGS = 50;
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final String ID_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final Pattern TEMPLATE_PARAMETER = Pattern.compile("\\$\\{([.\\-_#A-Za-z0-9]+)}");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final Pattern VIRTUAL_CLUSTER_ARN = Pattern.compile("/virtualclusters/([0-9a-z]+)");
    private static final Pattern NESTED_ARN =
            Pattern.compile("/virtualclusters/([0-9a-z]+)/(jobruns|endpoints)/([0-9a-z]+)");

    private final AccountAwareStorageBackend<ObjectNode> virtualClusters;
    private final AccountAwareStorageBackend<ObjectNode> jobRuns;
    private final AccountAwareStorageBackend<ObjectNode> endpoints;
    private final AccountAwareStorageBackend<ObjectNode> jobTemplates;
    private final AccountAwareStorageBackend<ObjectNode> securityConfigurations;
    private final ObjectMapper mapper;
    private final RegionResolver regionResolver;
    private final EksService eksService;
    private final IamService iamService;
    private final RequestContext requestContext;
    private final Clock clock;
    private final int jobRunDurationSeconds;
    // The runner of the docker runner mode, or null in the immediate one.
    private final SparkJobRunner sparkRunner;
    private final Executor runExecutor;
    // image-template and docker-network, each null when not set.
    private final String imageTemplate;
    private final String dockerNetwork;
    // Where a retry waits for its backoff in the docker runner mode; null in the immediate one.
    private final Delays delays;
    // The ARNs of the runs whose containers a thread of this process runs; a launched run not here was left by a
    // restart.
    private final Set<String> executing = new HashSet<>();
    // Raised by each reset, so a run's thread from before it writes nothing back after the wipe.
    private long generation;

    @Inject
    public EmrContainersService(StorageFactory storageFactory, ObjectMapper mapper, RegionResolver regionResolver,
                                EksService eksService, IamService iamService, RequestContext requestContext,
                                Clock clock, EmulatorConfig config, EmrServerlessSparkRunner sparkRunner) {
        this(storage(storageFactory, "emr-containers-virtual-clusters.json"),
                storage(storageFactory, "emr-containers-job-runs.json"),
                storage(storageFactory, "emr-containers-endpoints.json"),
                storage(storageFactory, "emr-containers-job-templates.json"),
                storage(storageFactory, "emr-containers-security-configurations.json"),
                mapper, regionResolver, eksService, iamService, requestContext, clock,
                config.services().emrcontainers().jobRunDurationSeconds(),
                RUNNER_DOCKER.equalsIgnoreCase(config.services().emrcontainers().runnerMode()) ? sparkRunner : null,
                Executors.newCachedThreadPool(Thread.ofPlatform().daemon().name("emr-containers-run-", 0).factory()),
                config.services().emrcontainers().imageTemplate().orElse(null),
                config.services().emrcontainers().dockerNetwork().orElse(null),
                RUNNER_DOCKER.equalsIgnoreCase(config.services().emrcontainers().runnerMode()) ? Delays.timer() : null);
        // Before any run of this process starts: a driver an earlier one left belongs to a run read as FAILED.
        if (this.sparkRunner != null && config.services().emrcontainers().reconcileContainersOnStartup()) {
            sparkRunner.removeLeftoverDrivers(SERVICE);
        }
    }

    EmrContainersService(AccountAwareStorageBackend<ObjectNode> virtualClusters,
                         AccountAwareStorageBackend<ObjectNode> jobRuns,
                         AccountAwareStorageBackend<ObjectNode> endpoints,
                         AccountAwareStorageBackend<ObjectNode> jobTemplates,
                         AccountAwareStorageBackend<ObjectNode> securityConfigurations,
                         ObjectMapper mapper, RegionResolver regionResolver, EksService eksService,
                         IamService iamService, RequestContext requestContext, Clock clock,
                         int jobRunDurationSeconds) {
        this(virtualClusters, jobRuns, endpoints, jobTemplates, securityConfigurations, mapper, regionResolver,
                eksService, iamService, requestContext, clock, jobRunDurationSeconds, null, Runnable::run, null, null,
                null);
    }

    EmrContainersService(AccountAwareStorageBackend<ObjectNode> virtualClusters,
                         AccountAwareStorageBackend<ObjectNode> jobRuns,
                         AccountAwareStorageBackend<ObjectNode> endpoints,
                         AccountAwareStorageBackend<ObjectNode> jobTemplates,
                         AccountAwareStorageBackend<ObjectNode> securityConfigurations,
                         ObjectMapper mapper, RegionResolver regionResolver, EksService eksService,
                         IamService iamService, RequestContext requestContext, Clock clock,
                         int jobRunDurationSeconds, SparkJobRunner sparkRunner, Executor runExecutor,
                         String imageTemplate, String dockerNetwork, Delays delays) {
        this.virtualClusters = virtualClusters;
        this.jobRuns = jobRuns;
        this.endpoints = endpoints;
        this.jobTemplates = jobTemplates;
        this.securityConfigurations = securityConfigurations;
        this.mapper = mapper;
        this.regionResolver = regionResolver;
        this.eksService = eksService;
        this.iamService = iamService;
        this.requestContext = requestContext;
        this.clock = clock;
        this.jobRunDurationSeconds = jobRunDurationSeconds;
        this.sparkRunner = sparkRunner;
        this.runExecutor = runExecutor;
        this.imageTemplate = imageTemplate;
        this.dockerNetwork = dockerNetwork;
        this.delays = delays;
    }

    @PreDestroy
    void shutdown() {
        if (runExecutor instanceof ExecutorService executor) {
            executor.shutdownNow();
        }
        if (delays != null) {
            delays.close();
        }
    }

    /** Runs a task once its delay has passed, as a retry waits for its backoff; a test runs them when it chooses. */
    interface Delays {

        void after(Duration delay, Runnable task);

        default void close() {
        }

        /** Delays on a daemon thread of their own. */
        static Delays timer() {
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("emr-containers-retries").factory());
            return new Delays() {
                @Override
                public void after(Duration delay, Runnable task) {
                    executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
                }

                @Override
                public void close() {
                    executor.shutdownNow();
                }
            };
        }
    }

    private static AccountAwareStorageBackend<ObjectNode> storage(StorageFactory storageFactory, String file) {
        return storageFactory.create("emrcontainers", file, new TypeReference<Map<String, ObjectNode>>() {});
    }

    // ── Virtual clusters ─────────────────────────────────────────────────────────

    public synchronized ObjectNode createVirtualCluster(ObjectNode request) {
        EmrContainersRequestShapes.check("CreateVirtualCluster", request);
        String clientToken = request.get("clientToken").textValue();
        Optional<ObjectNode> existing = byClientToken(virtualClusters, arn(VIRTUAL_CLUSTERS), clientToken);
        if (existing.isPresent()) {
            return pick(existing.get(), "id", "name", "arn");
        }
        if (request.hasNonNull("securityConfigurationId")) {
            String securityConfigurationId = request.get("securityConfigurationId").textValue();
            if (securityConfigurations.get(arn(SECURITY_CONFIGURATIONS + securityConfigurationId)).isEmpty()) {
                throw notFound("Security configuration " + securityConfigurationId + " doesn't exist.");
            }
        }
        String id = newId(VIRTUAL_CLUSTER_ID_LENGTH);
        String arn = arn(VIRTUAL_CLUSTERS + id);
        ObjectNode cluster = mapper.createObjectNode();
        cluster.put("id", id);
        cluster.set("name", request.get("name"));
        cluster.put("arn", arn);
        cluster.put("state", RUNNING);
        cluster.set("containerProvider", request.get("containerProvider").deepCopy());
        cluster.put("createdAt", timestamp(now()));
        cluster.set("tags", tags(request.get("tags")));
        copy(request, cluster, "securityConfigurationId", "sessionEnabled", "schedulerConfiguration");
        cluster.put(CLIENT_TOKEN, clientToken);
        cluster.put(ACCESS_ENTRY_INTEGRATED, accessEntryIntegrated(request.get("containerProvider")));
        virtualClusters.put(arn, cluster);
        return pick(cluster, "id", "name", "arn");
    }

    public synchronized ObjectNode describeVirtualCluster(String id) {
        EmrContainersRequestShapes.check("DescribeVirtualCluster", object("id", id));
        ObjectNode cluster = virtualCluster(id).orElseThrow(() -> notFound(virtualClusterMissing(id)));
        return wrap("virtualCluster", withSchedulerStatus(cluster));
    }

    public synchronized ObjectNode listVirtualClusters(ListQuery query, String containerProviderId,
                                                       String containerProviderType,
                                                       Boolean eksAccessEntryIntegrated) {
        ObjectNode shape = query.shape();
        putText(shape, "containerProviderId", containerProviderId);
        putText(shape, "containerProviderType", containerProviderType);
        if (eksAccessEntryIntegrated != null) {
            shape.put("eksAccessEntryIntegrated", eksAccessEntryIntegrated);
        }
        EmrContainersRequestShapes.check("ListVirtualClusters", shape);
        List<ObjectNode> matching = virtualClusters.scan(key -> key.startsWith(arn(VIRTUAL_CLUSTERS))).stream()
                .filter(query::matches)
                .filter(cluster -> containerProviderId == null
                        || containerProviderId.equals(cluster.path("containerProvider").path("id").textValue()))
                .filter(cluster -> containerProviderType == null
                        || containerProviderType.equals(cluster.path("containerProvider").path("type").textValue()))
                .filter(cluster -> eksAccessEntryIntegrated == null
                        || eksAccessEntryIntegrated == cluster.path(ACCESS_ENTRY_INTEGRATED).asBoolean())
                .map(this::withSchedulerStatus)
                .toList();
        return page(matching, query, "virtualClusters");
    }

    public synchronized ObjectNode deleteVirtualCluster(String id) {
        EmrContainersRequestShapes.check("DeleteVirtualCluster", object("id", id));
        ObjectNode cluster = virtualCluster(id).orElseThrow(() -> invalid(virtualClusterMissing(id)));
        if (!TERMINATED.equals(cluster.path("state").textValue())) {
            ObjectNode terminated = cluster.deepCopy();
            terminated.put("state", TERMINATED);
            // AWS: "When a virtual cluster is terminated, tags are cleaned and no longer accessible."
            terminated.set("tags", mapper.createObjectNode());
            virtualClusters.put(terminated.get("arn").textValue(), terminated);
        }
        return object("id", id);
    }

    public synchronized ObjectNode updateVirtualCluster(String id, ObjectNode request) {
        request.put("id", id);
        EmrContainersRequestShapes.check("UpdateVirtualCluster", request);
        ObjectNode cluster = virtualCluster(id).orElseThrow(() -> notFound(virtualClusterMissing(id)));
        requireState(cluster, RUNNING, "VirtualCluster", "update");
        // The runs stand as the limits they had until now left them; the new ones hold from now on.
        settledRuns(cluster);
        ObjectNode updated = cluster.deepCopy();
        JsonNode scheduler = request.get("schedulerConfiguration");
        if (scheduler != null && !scheduler.isNull()) {
            // An empty object clears the limits; leaving the member out keeps them.
            if (scheduler.isEmpty()) {
                updated.remove("schedulerConfiguration");
            } else {
                updated.set("schedulerConfiguration", scheduler.deepCopy());
            }
        }
        virtualClusters.put(updated.get("arn").textValue(), updated);
        // A higher running limit, or none, starts queued runs now.
        settledRuns(updated);
        return wrap("virtualCluster", withSchedulerStatus(updated));
    }

    /**
     * Whether a new virtual cluster is EKS access entry integrated, "the virtual cluster created using access entry
     * API operations from Amazon EKS": the development guide has EMR configure its access that way when the EKS
     * cluster's authenticationMode admits access entries, and only for virtual clusters created from then on. A
     * cluster Floci's EKS does not have reads as not integrated.
     */
    private boolean accessEntryIntegrated(JsonNode containerProvider) {
        String eksCluster = containerProvider.path("id").textValue();
        return eksService.findAuthenticationCluster(regionResolver.getAccountId(), regionResolver.getRegion(),
                        eksCluster)
                .map(Cluster::getAccessConfig)
                .map(AccessConfig::authenticationMode)
                .filter(ACCESS_ENTRY_MODES::contains)
                .isPresent();
    }

    // ── Job runs ─────────────────────────────────────────────────────────────────

    public synchronized ObjectNode startJobRun(String virtualClusterId, ObjectNode request) {
        request.put("virtualClusterId", virtualClusterId);
        EmrContainersRequestShapes.check("StartJobRun", request);
        ObjectNode cluster = virtualCluster(virtualClusterId)
                .orElseThrow(() -> notFound(virtualClusterMissing(virtualClusterId)));
        requireState(cluster, RUNNING, "JobRun", "create");
        String clusterArn = cluster.get("arn").textValue();
        String clientToken = request.get("clientToken").textValue();
        Optional<ObjectNode> existing = jobRuns.scan(key -> key.startsWith(clusterArn + JOB_RUNS)).stream()
                .filter(run -> clientToken.equals(run.path("clientToken").textValue()))
                .findFirst();
        if (existing.isPresent()) {
            return pick(existing.get(), "id", "name", "arn", "virtualClusterId");
        }

        ObjectNode template = mapper.createObjectNode();
        if (request.hasNonNull("jobTemplateId")) {
            template = jobTemplateData(request.get("jobTemplateId").textValue(), request.get("jobTemplateParameters"));
        }
        List<String> missing = new ArrayList<>();
        for (String member : List.of("executionRoleArn", "releaseLabel", "jobDriver")) {
            if (!request.hasNonNull(member) && !template.hasNonNull(member)) {
                missing.add(SmithyValidationErrors.notNull(member));
            }
        }
        if (!missing.isEmpty()) {
            throw SmithyValidationErrors.validationException(missing);
        }
        // A template's configurationOverrides follow its parametric model, whose monitoring destinations need not name
        // their log group or log URI; the run they make follows StartJobRun's, which a request's own already met.
        if (!request.hasNonNull("configurationOverrides") && template.hasNonNull("configurationOverrides")) {
            EmrContainersRequestShapes.checkShape("ConfigurationOverrides", template.get("configurationOverrides"));
        }

        String id = newId(JOB_RUN_ID_LENGTH);
        String arn = clusterArn + JOB_RUNS + id;
        Instant now = now();
        ObjectNode run = mapper.createObjectNode();
        run.put("id", id);
        copy(request, run, "name");
        run.put("virtualClusterId", virtualClusterId);
        run.put("arn", arn);
        run.put("clientToken", clientToken);
        for (String member : List.of("executionRoleArn", "releaseLabel", "configurationOverrides", "jobDriver")) {
            JsonNode value = request.hasNonNull(member) ? request.get(member) : template.get(member);
            if (value != null && !value.isNull()) {
                run.set(member, value.deepCopy());
            }
        }
        run.put("createdAt", timestamp(now));
        run.put("createdBy", createdBy());
        run.put(SPARK_APPLICATION_ID, sparkApplicationId());
        ObjectNode tags = tags(template.get("jobTags"));
        tags.setAll(tags(request.get("tags")));
        if (tags.size() > MAX_TAGS) {
            throw tooManyTags();
        }
        run.set("tags", tags);
        // "The valid maxAttempts range is from 1 to 2,147,483,647", and "If maxAttempts of the
        // retryPolicyConfiguration is set to 1, it means that no retries will be done": the policy and its execution
        // "will not be visible when retry policy is disabled in the job" (EMR on EKS Development Guide, "Using job
        // retry policies").
        JsonNode retryPolicy = request.get("retryPolicyConfiguration");
        if (retryPolicy != null && !retryPolicy.isNull()) {
            JsonNode maxAttempts = retryPolicy.path("maxAttempts");
            if (!maxAttempts.isIntegralNumber() || !maxAttempts.canConvertToInt() || maxAttempts.intValue() < 1) {
                throw invalid(INVALID_MAX_ATTEMPTS);
            }
            if (maxAttempts.intValue() > 1) {
                run.set("retryPolicyConfiguration", retryPolicy.deepCopy());
                run.set("retryPolicyExecution", object("currentAttemptCount", 1));
            }
        }

        // Concurrent job limits are "enforced at StartJobRun, before any pods are created": a run beyond the
        // running limit waits in the queue, and "once the queue is full, StartJobRun rejects further submissions".
        List<ObjectNode> runs = settledRuns(cluster);
        boolean slotFree = runs.stream().filter(EmrContainersService::holdsSlot).count()
                < maxConcurrentJobRuns(cluster);
        long queued = runs.stream().filter(EmrContainersService::waitsForSlot).count();
        JsonNode maxInQueue = cluster.path("schedulerConfiguration").path("maxInQueueJobRuns");
        if (!slotFree && maxInQueue.isIntegralNumber() && queued >= maxInQueue.longValue()) {
            // The guide gives ValidationException, not its message; the wording is Floci's.
            throw invalid("Virtual cluster " + virtualClusterId + " has " + queued + " job runs in its queue, its "
                    + "maxInQueueJobRuns, and accepts no more until one of them starts.");
        }
        String eksCluster = cluster.path("containerProvider").path("id").textValue();
        if (eksService.findAuthenticationCluster(regionResolver.getAccountId(), regionResolver.getRegion(), eksCluster)
                .isEmpty()) {
            // AWS's "Common errors when running jobs": a job run on a cluster EKS does not have.
            finish(run, FAILED, now);
            run.put("stateDetails", "Cluster " + eksCluster + " does not exist.");
            run.put("failureReason", "CLUSTER_UNAVAILABLE");
        } else if (!slotFree) {
            // Held before it is submitted to the virtual cluster: "waiting to be submitted to the virtual cluster".
            run.put("state", PENDING);
            run.put(QUEUE_POSITION, runs.stream().mapToLong(held -> held.path(QUEUE_POSITION).asLong()).max()
                    .orElse(0) + 1);
        } else {
            start(run, now);
        }
        jobRuns.put(arn, run);
        launch(List.of(run));
        return pick(run, "id", "name", "arn", "virtualClusterId");
    }

    public synchronized ObjectNode describeJobRun(String virtualClusterId, String id) {
        EmrContainersRequestShapes.check("DescribeJobRun", object("id", id).put("virtualClusterId", virtualClusterId));
        ObjectNode run = jobRun(virtualClusterId, id).orElseThrow(() -> notFound(jobRunMissing(id)));
        return wrap("jobRun", run);
    }

    public synchronized ObjectNode listJobRuns(String virtualClusterId, ListQuery query, String name) {
        ObjectNode shape = query.shape().put("virtualClusterId", virtualClusterId);
        putText(shape, "name", name);
        EmrContainersRequestShapes.check("ListJobRuns", shape);
        List<ObjectNode> matching = settledRuns(virtualClusterId).stream()
                .filter(query::matches)
                .filter(run -> name == null || name.equals(run.path("name").textValue()))
                .toList();
        return page(matching, query, "jobRuns");
    }

    public synchronized ObjectNode cancelJobRun(String virtualClusterId, String id) {
        EmrContainersRequestShapes.check("CancelJobRun", object("id", id).put("virtualClusterId", virtualClusterId));
        ObjectNode run = jobRun(virtualClusterId, id).orElseThrow(() -> invalid(jobRunMissing(id)));
        String state = run.path("state").textValue();
        if (FINISHED_JOB_RUN_STATES.contains(state)) {
            throw invalid("Job run " + id + " is " + state + " and can't be cancelled.");
        }
        ObjectNode cancelled = run.deepCopy();
        if (CANCEL_PENDING.equals(state)) {
            return object("id", id).put("virtualClusterId", virtualClusterId);
        } else if (launched(run) && !run.has(RETRY_AT)) {
            // "CANCEL_PENDING - A job run has been requested for cancellation. Amazon EMR on EKS is trying to cancel
            // the job on the virtual cluster", until its driver is stopped.
            cancelled.put("state", CANCEL_PENDING);
            jobRuns.put(cancelled.get("arn").textValue(), cancelled);
            runExecutor.execute(() -> sparkRunner.stop(id));
            return object("id", id).put("virtualClusterId", virtualClusterId);
        }
        // A queued run, or one between its attempts, whose next attempt the backoff's end then finds cancelled.
        finish(cancelled, CANCELLED, now());
        jobRuns.put(cancelled.get("arn").textValue(), cancelled);
        executing.remove(cancelled.get("arn").textValue());
        // A running slot it frees goes to the queue's first run now.
        settledRuns(virtualClusterId);
        return object("id", id).put("virtualClusterId", virtualClusterId);
    }

    // ── Job templates ────────────────────────────────────────────────────────────

    public synchronized ObjectNode createJobTemplate(ObjectNode request) {
        EmrContainersRequestShapes.check("CreateJobTemplate", request);
        String clientToken = request.get("clientToken").textValue();
        Optional<ObjectNode> existing = byClientToken(jobTemplates, arn(JOB_TEMPLATES), clientToken);
        if (existing.isPresent()) {
            return pick(existing.get(), "id", "name", "arn", "createdAt");
        }
        String id = newId(JOB_RUN_ID_LENGTH);
        String arn = arn(JOB_TEMPLATES + id);
        ObjectNode template = mapper.createObjectNode();
        template.set("name", request.get("name"));
        template.put("id", id);
        template.put("arn", arn);
        template.put("createdAt", timestamp(now()));
        template.put("createdBy", createdBy());
        template.set("tags", tags(request.get("tags")));
        template.set("jobTemplateData", request.get("jobTemplateData").deepCopy());
        copy(request, template, "kmsKeyArn");
        template.put(CLIENT_TOKEN, clientToken);
        jobTemplates.put(arn, template);
        return pick(template, "id", "name", "arn", "createdAt");
    }

    public synchronized ObjectNode describeJobTemplate(String id) {
        EmrContainersRequestShapes.check("DescribeJobTemplate", object("id", id));
        ObjectNode template = jobTemplates.get(arn(JOB_TEMPLATES + id))
                .orElseThrow(() -> notFound(jobTemplateMissing(id)));
        return wrap("jobTemplate", template);
    }

    public synchronized ObjectNode listJobTemplates(ListQuery query) {
        EmrContainersRequestShapes.check("ListJobTemplates", query.shape());
        List<ObjectNode> matching = jobTemplates.scan(key -> key.startsWith(arn(JOB_TEMPLATES))).stream()
                .filter(query::matches)
                .toList();
        return page(matching, query, "templates");
    }

    public synchronized ObjectNode deleteJobTemplate(String id) {
        EmrContainersRequestShapes.check("DeleteJobTemplate", object("id", id));
        String arn = arn(JOB_TEMPLATES + id);
        if (jobTemplates.get(arn).isEmpty()) {
            throw invalid(jobTemplateMissing(id));
        }
        jobTemplates.delete(arn);
        return object("id", id);
    }

    // ── Managed endpoints ────────────────────────────────────────────────────────

    public synchronized ObjectNode createManagedEndpoint(String virtualClusterId, ObjectNode request) {
        request.put("virtualClusterId", virtualClusterId);
        EmrContainersRequestShapes.check("CreateManagedEndpoint", request);
        ObjectNode cluster = virtualCluster(virtualClusterId)
                .orElseThrow(() -> notFound(virtualClusterMissing(virtualClusterId)));
        requireState(cluster, RUNNING, "Endpoint", "create");
        String prefix = cluster.get("arn").textValue() + ENDPOINTS;
        String clientToken = request.get("clientToken").textValue();
        Optional<ObjectNode> existing = byClientToken(endpoints, prefix, clientToken);
        if (existing.isPresent()) {
            return pick(existing.get(), "id", "name", "arn", "virtualClusterId");
        }
        String id = newId(ENDPOINT_ID_LENGTH);
        String arn = prefix + id;
        ObjectNode endpoint = mapper.createObjectNode();
        endpoint.put("id", id);
        endpoint.set("name", request.get("name"));
        endpoint.put("arn", arn);
        endpoint.put("virtualClusterId", virtualClusterId);
        endpoint.set("type", request.get("type"));
        endpoint.put("state", ACTIVE);
        endpoint.set("releaseLabel", request.get("releaseLabel"));
        endpoint.set("executionRoleArn", request.get("executionRoleArn"));
        copy(request, endpoint, "certificateArn", "configurationOverrides");
        endpoint.put("createdAt", timestamp(now()));
        endpoint.set("tags", tags(request.get("tags")));
        endpoint.put(CLIENT_TOKEN, clientToken);
        endpoints.put(arn, endpoint);
        return pick(endpoint, "id", "name", "arn", "virtualClusterId");
    }

    public synchronized ObjectNode describeManagedEndpoint(String virtualClusterId, String id) {
        EmrContainersRequestShapes.check("DescribeManagedEndpoint",
                object("id", id).put("virtualClusterId", virtualClusterId));
        ObjectNode endpoint = endpoint(virtualClusterId, id).orElseThrow(() -> notFound(endpointMissing(id)));
        return wrap("endpoint", endpoint);
    }

    public synchronized ObjectNode listManagedEndpoints(String virtualClusterId, ListQuery query, List<String> types) {
        ObjectNode shape = query.shape().put("virtualClusterId", virtualClusterId);
        putList(shape, "types", types);
        EmrContainersRequestShapes.check("ListManagedEndpoints", shape);
        String prefix = arn(VIRTUAL_CLUSTERS + virtualClusterId + ENDPOINTS);
        List<ObjectNode> matching = endpoints.scan(key -> key.startsWith(prefix)).stream()
                .filter(query::matches)
                .filter(endpoint -> types == null || types.isEmpty()
                        || types.contains(endpoint.path("type").textValue()))
                .toList();
        return page(matching, query, "endpoints");
    }

    public synchronized ObjectNode deleteManagedEndpoint(String virtualClusterId, String id) {
        EmrContainersRequestShapes.check("DeleteManagedEndpoint",
                object("id", id).put("virtualClusterId", virtualClusterId));
        ObjectNode endpoint = endpoint(virtualClusterId, id).orElseThrow(() -> invalid(endpointMissing(id)));
        if (!FINISHED_ENDPOINT_STATES.contains(endpoint.path("state").textValue())) {
            ObjectNode terminated = endpoint.deepCopy();
            terminated.put("state", TERMINATED);
            endpoints.put(terminated.get("arn").textValue(), terminated);
        }
        return object("id", id).put("virtualClusterId", virtualClusterId);
    }

    public synchronized ObjectNode getManagedEndpointSessionCredentials(String virtualClusterId, String endpointId,
                                                                        ObjectNode request) {
        request.put("virtualClusterIdentifier", virtualClusterId);
        request.put("endpointIdentifier", endpointId);
        EmrContainersRequestShapes.check("GetManagedEndpointSessionCredentials", request);
        ObjectNode endpoint = endpoint(virtualClusterId, endpointId)
                .orElseThrow(() -> notFound(endpointMissing(endpointId)));
        if (!ACTIVE.equals(endpoint.path("state").textValue())) {
            throw invalid("Endpoint " + endpointId + " is " + endpoint.path("state").textValue()
                    + " and can't issue session credentials.");
        }
        // The API reference: TOKEN is the only credential type, and the default.
        if (!TOKEN_CREDENTIAL.equals(request.get("credentialType").textValue())) {
            throw invalid("Credential type " + request.get("credentialType").textValue() + " is not supported.");
        }
        // The API reference: "The default duration is 15 minutes and the maximum is 12 hours."
        int seconds = request.hasNonNull("durationInSeconds")
                ? request.get("durationInSeconds").intValue() : DEFAULT_SESSION_SECONDS;
        if (seconds < 1 || seconds > MAX_SESSION_SECONDS) {
            throw invalid("durationInSeconds must be between 1 and " + MAX_SESSION_SECONDS + ".");
        }
        ObjectNode credentials = object("token", sessionToken());
        ObjectNode response = object("id", newId(JOB_RUN_ID_LENGTH));
        response.set("credentials", credentials);
        response.set("endpointCredentials", credentials.deepCopy());
        response.put("expiresAt", timestamp(now().plusSeconds(seconds)));
        return response;
    }

    // ── Security configurations ──────────────────────────────────────────────────

    public synchronized ObjectNode createSecurityConfiguration(ObjectNode request) {
        EmrContainersRequestShapes.check("CreateSecurityConfiguration", request);
        String clientToken = request.get("clientToken").textValue();
        Optional<ObjectNode> existing = byClientToken(securityConfigurations, arn(SECURITY_CONFIGURATIONS),
                clientToken);
        if (existing.isPresent()) {
            return pick(existing.get(), "id", "name", "arn");
        }
        String id = newId(JOB_RUN_ID_LENGTH);
        String arn = arn(SECURITY_CONFIGURATIONS + id);
        ObjectNode configuration = mapper.createObjectNode();
        configuration.put("id", id);
        configuration.set("name", request.get("name"));
        configuration.put("arn", arn);
        configuration.put("createdAt", timestamp(now()));
        configuration.put("createdBy", createdBy());
        configuration.set("securityConfigurationData", request.get("securityConfigurationData").deepCopy());
        configuration.set("tags", tags(request.get("tags")));
        configuration.put(CLIENT_TOKEN, clientToken);
        securityConfigurations.put(arn, configuration);
        return pick(configuration, "id", "name", "arn");
    }

    public synchronized ObjectNode describeSecurityConfiguration(String id) {
        EmrContainersRequestShapes.check("DescribeSecurityConfiguration", object("id", id));
        ObjectNode configuration = securityConfigurations.get(arn(SECURITY_CONFIGURATIONS + id))
                .orElseThrow(() -> notFound(securityConfigurationMissing(id)));
        return wrap("securityConfiguration", configuration);
    }

    public synchronized ObjectNode listSecurityConfigurations(ListQuery query) {
        EmrContainersRequestShapes.check("ListSecurityConfigurations", query.shape());
        List<ObjectNode> matching = securityConfigurations.scan(key -> key.startsWith(arn(SECURITY_CONFIGURATIONS)))
                .stream()
                .filter(query::matches)
                .toList();
        return page(matching, query, "securityConfigurations");
    }

    public synchronized ObjectNode deleteSecurityConfiguration(String id) {
        EmrContainersRequestShapes.check("DeleteSecurityConfiguration", object("id", id));
        String arn = arn(SECURITY_CONFIGURATIONS + id);
        if (securityConfigurations.get(arn).isEmpty()) {
            throw invalid(securityConfigurationMissing(id));
        }
        securityConfigurations.delete(arn);
        return object("id", id);
    }

    // ── Tags ─────────────────────────────────────────────────────────────────────

    public synchronized Map<String, String> listTags(String resourceArn) {
        EmrContainersRequestShapes.check("ListTagsForResource", object("resourceArn", resourceArn));
        return tagMap(taggable(resourceArn).resource());
    }

    public synchronized void tagResource(String resourceArn, Map<String, String> tags) {
        ObjectNode request = object("resourceArn", resourceArn);
        request.set("tags", mapper.valueToTree(tags));
        EmrContainersRequestShapes.check("TagResource", request);
        Taggable target = activeTaggable(resourceArn);
        ObjectNode updated = target.resource().deepCopy();
        ObjectNode merged = tags(updated.get("tags"));
        merged.setAll(tags(request.get("tags")));
        if (merged.size() > MAX_TAGS) {
            throw tooManyTags();
        }
        updated.set("tags", merged);
        target.storage().put(resourceArn, updated);
    }

    public synchronized void untagResource(String resourceArn, List<String> tagKeys) {
        ObjectNode request = object("resourceArn", resourceArn);
        putList(request, "tagKeys", tagKeys == null ? List.of() : tagKeys);
        EmrContainersRequestShapes.check("UntagResource", request);
        Taggable target = activeTaggable(resourceArn);
        ObjectNode updated = target.resource().deepCopy();
        ObjectNode remaining = tags(updated.get("tags"));
        if (tagKeys != null) {
            remaining.remove(tagKeys);
        }
        updated.set("tags", remaining);
        target.storage().put(resourceArn, updated);
    }

    @Override
    public String namespace() {
        return "emr-containers";
    }

    /** A request names each resource by the id its ARN ends in: nothing to find. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    /**
     * The tags of the virtual cluster, job run, managed endpoint, job template or security configuration whose ARN is
     * {@code arn}, in the request's account, where the handlers read: an ARN of another account names none. Each is
     * stored under its ARN, so it is read in place under the ARN itself, a resource stored before storage was
     * account-aware included, and matched on the ARN it records. Every write stores a new copy of the resource, so
     * the read is of one whole set.
     */
    @Override
    public Map<String, String> storedTags(String arn) {
        if (!AwsArnUtils.isArnFor(arn, "emr-containers")) {
            return Map.of();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        AccountAwareStorageBackend<ObjectNode> store = storeOf(parsed.resource());
        if (store == null || !parsed.accountId().equals(regionResolver.getAccountId())) {
            return Map.of();
        }
        return AccountAwareStorageBackend.readInAccount(store, parsed.accountId(), arn)
                .filter(resource -> arn.equals(resource.path("arn").textValue()))
                .map(EmrContainersService::tagMap)
                .orElse(Map.of());
    }

    /** The store holding the kind of resource an ARN's resource part names, or null for none of them. */
    private AccountAwareStorageBackend<ObjectNode> storeOf(String resource) {
        Matcher nested = NESTED_ARN.matcher(resource);
        if (nested.matches()) {
            return "jobruns".equals(nested.group(2)) ? jobRuns : endpoints;
        }
        if (VIRTUAL_CLUSTER_ARN.matcher(resource).matches()) {
            return virtualClusters;
        }
        if (resource.startsWith(JOB_TEMPLATES)) {
            return jobTemplates;
        }
        return resource.startsWith(SECURITY_CONFIGURATIONS) ? securityConfigurations : null;
    }

    /**
     * A reset has begun: the runs' threads from before it write nothing more, so the storage wipe that follows leaves
     * none of their runs behind. Its teardown stops their drivers.
     */
    @Override
    public synchronized void beforeReset() {
        generation++;
        executing.clear();
    }

    @Override
    public synchronized void clear() {
        generation++;
        executing.clear();
        virtualClusters.clear();
        jobRuns.clear();
        endpoints.clear();
        jobTemplates.clear();
        securityConfigurations.clear();
    }

    // ── Paging and filtering ─────────────────────────────────────────────────────

    /** The query members every List operation shares, parsed from the query string. */
    public record ListQuery(Instant createdAfter, Instant createdBefore, List<String> states, Integer maxResults,
                            String nextToken) {

        public static ListQuery of(String createdAfter, String createdBefore, List<String> states,
                                   String maxResults, String nextToken) {
            return new ListQuery(instant("createdAfter", createdAfter), instant("createdBefore", createdBefore),
                    states == null || states.isEmpty() ? null : List.copyOf(states),
                    Pagination.parseMaxResults(maxResults, "ValidationException"), nextToken);
        }

        /** The members the request shapes check, as the request carries them; timestamps are parsed above. */
        ObjectNode shape() {
            ObjectNode shape = JsonNodeFactory.instance.objectNode();
            putList(shape, "states", states);
            if (maxResults != null) {
                shape.put("maxResults", maxResults);
            }
            putText(shape, "nextToken", nextToken);
            return shape;
        }

        boolean matches(ObjectNode resource) {
            Instant createdAt = Instant.parse(resource.get("createdAt").textValue());
            return (createdAfter == null || !createdAt.isBefore(createdAfter))
                    && (createdBefore == null || !createdAt.isAfter(createdBefore))
                    && (states == null || states.contains(resource.path("state").textValue()));
        }

        private static Instant instant(String member, String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException notIso) {
                try {
                    BigDecimal seconds = new BigDecimal(value);
                    return Instant.ofEpochMilli(seconds.movePointRight(3).longValue());
                } catch (NumberFormatException notEpoch) {
                    throw new AwsException("ValidationException", "Invalid value for " + member + ": " + value, 400);
                }
            }
        }
    }

    private ObjectNode page(List<ObjectNode> resources, ListQuery query, String member) {
        if (query.maxResults() != null && query.maxResults() < 1) {
            throw invalid("maxResults must be at least 1.");
        }
        int limit = query.maxResults() != null ? query.maxResults() : DEFAULT_PAGE_SIZE;
        PaginatedResult<ObjectNode> page = Pagination.paginate(resources, resource -> resource.get("id").textValue(),
                limit, query.nextToken(), member, token -> invalid("Invalid nextToken."));
        ArrayNode items = mapper.createArrayNode();
        for (ObjectNode resource : page.items()) {
            items.add(view(resource));
        }
        ObjectNode response = mapper.createObjectNode();
        response.set(member, items);
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return response;
    }

    // ── Lookups ──────────────────────────────────────────────────────────────────

    private Optional<ObjectNode> virtualCluster(String id) {
        return virtualClusters.get(arn(VIRTUAL_CLUSTERS + id));
    }

    private Optional<ObjectNode> jobRun(String virtualClusterId, String id) {
        settledRuns(virtualClusterId);
        return jobRuns.get(arn(VIRTUAL_CLUSTERS + virtualClusterId + JOB_RUNS + id));
    }

    private Optional<ObjectNode> endpoint(String virtualClusterId, String id) {
        return endpoints.get(arn(VIRTUAL_CLUSTERS + virtualClusterId + ENDPOINTS + id));
    }

    private Optional<ObjectNode> byClientToken(AccountAwareStorageBackend<ObjectNode> storage, String prefix,
                                               String clientToken) {
        return storage.scan(key -> key.startsWith(prefix)).stream()
                .filter(resource -> clientToken.equals(resource.path(CLIENT_TOKEN).textValue()))
                .findFirst();
    }

    /**
     * A resource TagResource can name, as the model's ARN pattern limits it to: a virtual cluster, or
     * one of its job runs or managed endpoints.
     */
    private record Taggable(AccountAwareStorageBackend<ObjectNode> storage, ObjectNode resource, boolean active) {}

    private Taggable taggable(String resourceArn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException malformed) {
            throw invalid("Invalid resourceArn: " + resourceArn);
        }
        // A resource is the request's Region's, as every other operation finds it: another Region's ARN names none.
        if (!regionResolver.getRegion().equals(parsed.region())) {
            throw resourceMissing(resourceArn);
        }
        String resource = parsed.resource();
        if (VIRTUAL_CLUSTER_ARN.matcher(resource).matches()) {
            ObjectNode cluster = virtualClusters.get(resourceArn).orElseThrow(() -> resourceMissing(resourceArn));
            return new Taggable(virtualClusters, cluster, !TERMINATED.equals(cluster.path("state").textValue()));
        }
        Matcher nested = NESTED_ARN.matcher(resource);
        if (nested.matches() && "jobruns".equals(nested.group(2))) {
            settledRuns(nested.group(1));
            ObjectNode run = jobRuns.get(resourceArn).orElseThrow(() -> resourceMissing(resourceArn));
            return new Taggable(jobRuns, run, !FINISHED_JOB_RUN_STATES.contains(run.path("state").textValue()));
        }
        if (nested.matches()) {
            ObjectNode endpoint = endpoints.get(resourceArn).orElseThrow(() -> resourceMissing(resourceArn));
            return new Taggable(endpoints, endpoint,
                    !FINISHED_ENDPOINT_STATES.contains(endpoint.path("state").textValue()));
        }
        throw resourceMissing(resourceArn);
    }

    /** AWS: "You can tag new or existing virtual clusters and job runs that are in active states." */
    private Taggable activeTaggable(String resourceArn) {
        Taggable target = taggable(resourceArn);
        if (!target.active()) {
            throw invalid("Resource " + resourceArn + " is " + target.resource().path("state").textValue()
                    + " and can't be tagged.");
        }
        return target;
    }

    // ── Job templates in a job run ───────────────────────────────────────────────

    /**
     * The template's job data with every {@code ${name}} replaced by the parameter's value in the
     * request, or else its default. A declared parameter with neither is refused, as is a value a
     * {@code NUMBER} parameter cannot read as a number; a placeholder no parameter declares stays.
     */
    private ObjectNode jobTemplateData(String templateId, JsonNode parameters) {
        ObjectNode template = jobTemplates.get(arn(JOB_TEMPLATES + templateId))
                .orElseThrow(() -> notFound(jobTemplateMissing(templateId)));
        ObjectNode data = template.get("jobTemplateData").deepCopy();
        JsonNode declared = data.remove("parameterConfiguration");
        Map<String, String> values = new HashMap<>();
        if (declared != null) {
            for (Map.Entry<String, JsonNode> parameter : declared.properties()) {
                String name = parameter.getKey();
                JsonNode given = parameters == null ? null : parameters.get(name);
                String value = given != null && !given.isNull()
                        ? given.asText() : parameter.getValue().path("defaultValue").textValue();
                if (value == null) {
                    throw invalid("Job template parameter " + name + " requires a value.");
                }
                boolean number = "NUMBER".equals(parameter.getValue().path("type").textValue());
                if (number && !NUMBER.matcher(value).matches()) {
                    throw invalid("Job template parameter " + name + " must be a number.");
                }
                values.put(name, value);
            }
        }
        return (ObjectNode) substitute(data, values);
    }

    private JsonNode substitute(JsonNode node, Map<String, String> values) {
        if (node.isTextual()) {
            Matcher matcher = TEMPLATE_PARAMETER.matcher(node.textValue());
            StringBuilder replaced = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(replaced,
                        Matcher.quoteReplacement(values.getOrDefault(matcher.group(1), matcher.group())));
            }
            matcher.appendTail(replaced);
            return TextNode.valueOf(replaced.toString());
        }
        if (node.isObject()) {
            ObjectNode copy = mapper.createObjectNode();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                copy.set(field.getKey(), substitute(field.getValue(), values));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = mapper.createArrayNode();
            for (JsonNode item : node) {
                copy.add(substitute(item, values));
            }
            return copy;
        }
        return node;
    }

    // ── States ───────────────────────────────────────────────────────────────────

    private List<ObjectNode> settledRuns(String virtualClusterId) {
        return virtualCluster(virtualClusterId).map(this::settledRuns).orElseGet(List::of);
    }

    /**
     * The virtual cluster's job runs as they stand now, each change stored. They are replayed in the order things
     * happened: a RUNNING run completes once its duration has passed, and the running slot it frees goes at that
     * moment to the run first in the queue, "As running slots free up, queued job runs start automatically"; a run
     * still queued 30 minutes after it was submitted fails. A slot free now, once the limit is raised or a run is
     * cancelled, goes to the queue now. A run whose container runs holds its slot until its driver ends, and one
     * whose container no thread of this process runs, as after a restart, has failed; one an earlier process left
     * between its attempts waits out the rest of its backoff.
     */
    private List<ObjectNode> settledRuns(ObjectNode cluster) {
        String prefix = cluster.get("arn").textValue() + JOB_RUNS;
        List<ObjectNode> runs = jobRuns.scan(key -> key.startsWith(prefix)).stream()
                .map(ObjectNode::deepCopy)
                .toList();
        Instant now = now();
        // By identity: a node's equality is its content, which changes here.
        Set<ObjectNode> changed = Collections.newSetFromMap(new IdentityHashMap<>());
        List<String> leftovers = new ArrayList<>();
        for (ObjectNode run : runs) {
            String arn = run.get("arn").textValue();
            if (launched(run) && !executing.contains(arn) && run.has(RETRY_AT) && delays != null) {
                // Between its attempts when an earlier process stopped, so no driver of it runs: its backoff goes on
                // from where it was, and its next attempt starts once it has passed, at once if it already has.
                Duration left = Duration.ofMillis(Math.max(0, run.get(RETRY_AT).longValue() - now.toEpochMilli()));
                awaitNextAttempt(arn, left, generation);
            } else if (launched(run) && !executing.contains(arn)) {
                finish(run, FAILED, now);
                run.put("stateDetails", MONITORING_FAILED);
                run.put("failureReason", INTERNAL_ERROR);
                changed.add(run);
                leftovers.add(run.get("id").textValue());
            }
        }
        int held = (int) runs.stream().filter(EmrContainersService::launched).count();
        // The running runs by when they complete, and the queue in order: a run's DUE_AT never changes in there.
        PriorityQueue<ObjectNode> running = new PriorityQueue<>(Comparator.comparing(EmrContainersService::dueAt));
        runs.stream()
                .filter(run -> RUNNING.equals(run.path("state").textValue()) && run.has(DUE_AT))
                .forEach(running::add);
        Deque<ObjectNode> queue = new ArrayDeque<>(runs.stream()
                .filter(run -> PENDING.equals(run.path("state").textValue()) && run.has(QUEUE_POSITION))
                .sorted(Comparator.comparingLong(run -> run.get(QUEUE_POSITION).longValue()))
                .toList());
        int limit = maxConcurrentJobRuns(cluster);
        while (true) {
            ObjectNode ending = running.peek();
            Instant endsAt = ending != null && !dueAt(ending).isAfter(now) ? dueAt(ending) : null;
            ObjectNode first = queue.peekFirst();
            Instant timesOutAt = first != null ? Instant.parse(first.get("createdAt").textValue()).plus(QUEUE_TIMEOUT)
                    : null;
            if (timesOutAt != null && !timesOutAt.isAfter(now) && (endsAt == null || timesOutAt.isBefore(endsAt))) {
                queue.pollFirst();
                finish(first, FAILED, timesOutAt);
                // No source gives AWS's stateDetails for it; the wording is Floci's.
                first.put("stateDetails", "Job run waited " + QUEUE_TIMEOUT.toMinutes() + " minutes in the queue "
                        + "of virtual cluster " + cluster.get("id").textValue() + " without starting.");
                changed.add(first);
            } else if (endsAt != null) {
                running.poll();
                finish(ending, COMPLETED, endsAt);
                changed.add(ending);
                held += startQueued(running, queue, limit - held, endsAt, changed);
            } else {
                break;
            }
        }
        startQueued(running, queue, limit - held, now, changed);
        // In one write: a persistent store rewrites its file on each.
        if (!changed.isEmpty()) {
            Map<String, ObjectNode> writes = new LinkedHashMap<>();
            changed.forEach(run -> writes.put(run.get("arn").textValue(), run));
            jobRuns.putAll(writes);
        }
        // A driver of theirs the startup's cleanup left goes, off the request's thread.
        if (sparkRunner != null) {
            leftovers.forEach(id -> runExecutor.execute(() -> sparkRunner.removeLeftoverDriver(SERVICE, id)));
        }
        // Once stored: a run's thread reads it.
        launch(changed);
        return runs;
    }

    /**
     * Starts the queue's first runs at the moment {@code at} while the running limit, less the slots launched runs
     * hold, leaves a slot. Answers how many of them were launched, each holding a slot until its driver ends.
     */
    private int startQueued(PriorityQueue<ObjectNode> running, Deque<ObjectNode> queue, int limit, Instant at,
                            Set<ObjectNode> changed) {
        int launched = 0;
        while (running.size() + launched < limit && !queue.isEmpty()) {
            ObjectNode run = queue.pollFirst();
            start(run, at);
            changed.add(run);
            if (run.has(DUE_AT)) {
                running.add(run);
            } else if (launched(run)) {
                launched++;
            }
        }
        return launched;
    }

    /**
     * A run starts. In the docker runner mode it is SUBMITTED, "successfully submitted to the virtual cluster", until
     * its driver runs, or FAILED where its job driver names no entry point to run; in the immediate one it is RUNNING
     * for the configured duration, or completed at once without one.
     */
    private void start(ObjectNode run, Instant at) {
        run.remove(QUEUE_POSITION);
        if (sparkRunner != null && EmrContainersSparkJobs.entryPoint(run) == null) {
            // Kept, not refused: AWS's "Common errors when running jobs" gives a run whose StartJobRun parameters "are
            // not valid" as one DescribeJobRun answers FAILED, "JobRun submission failed." with VALIDATION_ERROR.
            finish(run, FAILED, at);
            run.put("stateDetails", INVALID_SUBMISSION);
            run.put("failureReason", VALIDATION_ERROR);
        } else if (sparkRunner != null) {
            run.put("state", SUBMITTED);
        } else if (jobRunDurationSeconds <= 0) {
            finish(run, COMPLETED, at);
        } else {
            run.put("state", RUNNING);
            run.put(DUE_AT, at.plusSeconds(jobRunDurationSeconds).toEpochMilli());
        }
    }

    // Whether a run's container runs, or is about to: it has no set end, unlike a RUNNING run of the immediate mode.
    private static boolean launched(ObjectNode run) {
        return LAUNCHED_JOB_RUN_STATES.contains(run.path("state").textValue()) && !run.has(DUE_AT);
    }

    // Whether a run holds one of its virtual cluster's running slots: it runs, or its container is about to.
    private static boolean holdsSlot(ObjectNode run) {
        return RUNNING.equals(run.path("state").textValue()) || launched(run);
    }

    // Whether a run waits in its virtual cluster's queue: queued, and holding no running slot.
    private static boolean waitsForSlot(ObjectNode run) {
        return QUEUED_JOB_RUN_STATES.contains(run.path("state").textValue()) && !holdsSlot(run);
    }

    /**
     * Hands each of {@code runs} that was just submitted in the docker runner mode to a thread of its own, which runs
     * its Spark job to its end. Each is counted as running before any starts: a thread that runs at once reads the
     * others. One that cannot be handed off, its thread refused as Floci shuts down, fails, and its slot goes to the
     * queue.
     */
    private void launch(Collection<ObjectNode> runs) {
        if (sparkRunner == null) {
            return;
        }
        List<ObjectNode> submitted = runs.stream()
                .filter(run -> SUBMITTED.equals(run.path("state").textValue()))
                .filter(run -> !executing.contains(run.get("arn").textValue()))
                .toList();
        submitted.forEach(run -> executing.add(run.get("arn").textValue()));
        long launchedIn = generation;
        Set<String> freed = new LinkedHashSet<>();
        for (ObjectNode run : submitted) {
            String arn = run.get("arn").textValue();
            try {
                String applicationId = run.path(SPARK_APPLICATION_ID).textValue();
                if (applicationId == null) {
                    // A run stored before runs kept their application's id gets one now, for its later attempts too.
                    applicationId = sparkApplicationId();
                    ObjectNode withId = run.deepCopy();
                    withId.put(SPARK_APPLICATION_ID, applicationId);
                    jobRuns.put(arn, withId);
                }
                SparkJobRunner.Job job = EmrContainersSparkJobs.job(run, imageTemplate, dockerNetwork,
                        applicationId, driverPod(run));
                runExecutor.execute(() -> execute(arn, job, launchedIn));
            } catch (RuntimeException e) {
                LOG.warnv(e, "EMR on EKS job run {0} could not be handed to a thread", run.get("id").textValue());
                executing.remove(arn);
                ObjectNode failed = run.deepCopy();
                finish(failed, FAILED, now());
                failed.put("stateDetails", submissionFailed(e.getMessage()));
                failed.put("failureReason", INTERNAL_ERROR);
                jobRuns.put(arn, failed);
                freed.add(run.get("virtualClusterId").textValue());
            }
        }
        freed.forEach(this::settledRuns);
    }

    // AWS's sentence, then what kept Floci from starting the run's driver, which is Floci's own.
    private static String submissionFailed(String reason) {
        return SUBMISSION_FAILED + " Floci could not start its driver: " + reason;
    }

    private void execute(String arn, SparkJobRunner.Job job, long launchedIn) {
        SparkJobRunner.Outcome outcome;
        try {
            outcome = sparkRunner.run(job, new RunProgress(arn, job, launchedIn));
        } catch (RuntimeException e) {
            LOG.warnv(e, "EMR on EKS job run {0} could not run", job.runId());
            outcome = SparkJobRunner.Outcome.couldNotStart(e.getMessage());
        }
        SparkJobRunner.Outcome ended = outcome;
        RequestScopes.runAs(job.accountId(), job.region(), () -> ended(arn, ended, launchedIn));
    }

    // The attempt a run is on, 1 for its first, as its retryPolicyExecution counts them.
    private static int attempt(ObjectNode run) {
        return run.path("retryPolicyExecution").path("currentAttemptCount").asInt(1);
    }

    // How many attempts a run may make: its retry policy's maxAttempts, 1 where retries are disabled.
    private static int maxAttempts(ObjectNode run) {
        return run.path("retryPolicyConfiguration").path("maxAttempts").asInt(1);
    }

    /**
     * Starts a run's next attempt once its backoff has passed: "Retry policies cause a job driver pod to be restarted
     * automatically if it fails", after "an exponential retry interval of (10 seconds, 20 seconds, 40 seconds ...)
     * which is capped at 6 minutes". Until then it is SUBMITTED, again "submitted to the virtual cluster", and keeps
     * its slot; this process still follows it, so it is no leftover of a restart.
     */
    private void retry(String arn, ObjectNode run, Instant now, long launchedIn) {
        Duration delay = retryDelay(attempt(run));
        run.put("state", SUBMITTED);
        run.put(RETRY_AT, now.plus(delay).toEpochMilli());
        jobRuns.put(arn, run);
        awaitNextAttempt(arn, delay, launchedIn);
        LOG.infov("EMR on EKS job run {0} failed attempt {1}, retrying in {2}s", run.get("id").textValue(),
                attempt(run), delay.toSeconds());
    }

    // A run between its attempts holds its slot, this process following it, until its next attempt starts after delay.
    private void awaitNextAttempt(String arn, Duration delay, long launchedIn) {
        executing.add(arn);
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        delays.after(delay, () -> RequestScopes.runAs(parsed.accountId(), parsed.region(),
                () -> nextAttempt(arn, launchedIn)));
    }

    // The backoff after a run's attempt-th attempt failed: 10 seconds, doubling after each failure, at most 6 minutes.
    static Duration retryDelay(int attempt) {
        long seconds = FIRST_RETRY_DELAY.toSeconds() << Math.min(attempt - 1, 16);
        return Duration.ofSeconds(Math.min(seconds, MAX_RETRY_DELAY.toSeconds()));
    }

    // A run's backoff has passed: its next attempt starts, its driver in a container again, unless it was cancelled.
    private synchronized void nextAttempt(String arn, long launchedIn) {
        if (launchedIn != generation) {
            return;
        }
        jobRuns.get(arn).filter(run -> SUBMITTED.equals(run.path("state").textValue()) && run.has(RETRY_AT))
                .ifPresent(stored -> {
                    ObjectNode run = stored.deepCopy();
                    run.remove(RETRY_AT);
                    run.with("retryPolicyExecution").put("currentAttemptCount", attempt(run) + 1);
                    jobRuns.put(arn, run);
                    executing.remove(arn);
                    launch(List.of(run));
                });
    }

    /**
     * The name a run's driver pod has, which names the folder of its logs: "Driver pod names follow the format
     * spark-<job id>-driver-<random-suffix>", "The random-suffix is only present if the retry policy is enabled for the
     * job", a suffix of each attempt's own.
     */
    static String driverPod(ObjectNode run) {
        String pod = "spark-" + run.get("id").textValue() + "-driver";
        return maxAttempts(run) > 1 ? pod + "-" + newId(DRIVER_POD_SUFFIX_LENGTH) : pod;
    }

    // A run's driver runs: "In Spark applications, this means that the Spark driver process is in the running state."
    private synchronized void driverStarted(String arn, long launchedIn) {
        if (launchedIn != generation) {
            return;
        }
        jobRuns.get(arn).filter(run -> SUBMITTED.equals(run.path("state").textValue())).ifPresent(run -> {
            ObjectNode running = run.deepCopy();
            running.put("state", RUNNING);
            jobRuns.put(arn, running);
        });
    }

    // Whether a run's driver may still start, or go on: a cancelled run is past it, and so is one a reset removed.
    private synchronized boolean stillGoing(String arn, long launchedIn) {
        return launchedIn == generation && jobRuns.get(arn)
                .map(run -> SUBMITTED.equals(run.path("state").textValue())
                        || RUNNING.equals(run.path("state").textValue()))
                .orElse(false);
    }

    /**
     * Ends a run as its driver did: COMPLETED when it "succeeds (exits with code 0)", FAILED when it "fails (exits
     * with a non-zero code)", or could not start, and CANCELLED when it was cancelled. A driver stopped otherwise was
     * stopped by Floci's shutdown. The running slot it frees goes to the queue's first run.
     */
    private synchronized void ended(String arn, SparkJobRunner.Outcome outcome, long launchedIn) {
        if (launchedIn != generation) {
            return;
        }
        executing.remove(arn);
        ObjectNode stored = jobRuns.get(arn).orElse(null);
        if (stored == null || FINISHED_JOB_RUN_STATES.contains(stored.path("state").textValue())) {
            return;
        }
        ObjectNode run = stored.deepCopy();
        Instant now = now();
        if (CANCEL_PENDING.equals(run.path("state").textValue())) {
            finish(run, CANCELLED, now);
        } else if (outcome.exitCode() != null && outcome.exitCode() == 0) {
            finish(run, COMPLETED, now);
        } else if (outcome.exitCode() != null && attempt(run) < maxAttempts(run)) {
            retry(arn, run, now, launchedIn);
            return;
        } else {
            finish(run, FAILED, now);
            if (outcome.exitCode() != null) {
                run.put("stateDetails", DRIVER_FAILED);
                run.put("failureReason", USER_ERROR);
            } else {
                run.put("stateDetails", outcome.failure() != null ? submissionFailed(outcome.failure())
                        : MONITORING_FAILED);
                run.put("failureReason", INTERNAL_ERROR);
            }
        }
        jobRuns.put(arn, run);
        LOG.infov("EMR on EKS job run {0} ended {1}", run.get("id").textValue(), run.get("state").textValue());
        settledRuns(run.get("virtualClusterId").textValue());
    }

    private static Instant dueAt(ObjectNode run) {
        return Instant.ofEpochMilli(run.get(DUE_AT).longValue());
    }

    // How many of the virtual cluster's runs may be RUNNING at once: its maxConcurrentJobRuns, or AWS's default.
    private static int maxConcurrentJobRuns(ObjectNode cluster) {
        JsonNode limit = cluster.path("schedulerConfiguration").path("maxConcurrentJobRuns");
        return limit.isIntegralNumber() ? limit.intValue() : DEFAULT_MAX_CONCURRENT_JOB_RUNS;
    }

    private void finish(ObjectNode run, String state, Instant at) {
        run.put("state", state);
        run.put("finishedAt", timestamp(at));
        run.remove(DUE_AT);
        run.remove(QUEUE_POSITION);
        run.remove(RETRY_AT);
    }

    /**
     * AWS's "Common errors when running jobs": "Virtual cluster state {state} is not valid to create
     * resource JobRun."; the same wording names the resource and the action for the others.
     */
    private void requireState(ObjectNode cluster, String state, String resource, String action) {
        String current = cluster.path("state").textValue();
        if (!state.equals(current)) {
            throw invalid("Virtual cluster state " + current + " is not valid to " + action + " resource "
                    + resource + ".");
        }
    }

    private ObjectNode withSchedulerStatus(ObjectNode cluster) {
        JsonNode scheduler = cluster.get("schedulerConfiguration");
        if (scheduler == null || scheduler.isEmpty()) {
            return cluster;
        }
        List<ObjectNode> runs = settledRuns(cluster);
        ObjectNode status = mapper.createObjectNode();
        status.put("currentInQueueJobRuns", runs.stream().filter(EmrContainersService::waitsForSlot).count());
        status.put("currentConcurrentJobRuns", runs.stream().filter(EmrContainersService::holdsSlot).count());
        ObjectNode withStatus = cluster.deepCopy();
        withStatus.set("schedulerStatus", status);
        return withStatus;
    }

    // ── JSON helpers ─────────────────────────────────────────────────────────────

    private ObjectNode wrap(String member, ObjectNode resource) {
        ObjectNode response = mapper.createObjectNode();
        response.set(member, view(resource));
        return response;
    }

    /** The resource as AWS answers it: without Floci's own members. */
    private static ObjectNode view(ObjectNode resource) {
        ObjectNode copy = resource.deepCopy();
        List<String> internal = new ArrayList<>();
        copy.fieldNames().forEachRemaining(name -> {
            if (name.startsWith("_")) {
                internal.add(name);
            }
        });
        copy.remove(internal);
        return copy;
    }

    private ObjectNode pick(ObjectNode resource, String... members) {
        ObjectNode picked = mapper.createObjectNode();
        copy(resource, picked, members);
        return picked;
    }

    private static void copy(JsonNode from, ObjectNode to, String... members) {
        for (String member : members) {
            JsonNode value = from.get(member);
            if (value != null && !value.isNull()) {
                to.set(member, value.deepCopy());
            }
        }
    }

    private ObjectNode object(String member, String value) {
        return mapper.createObjectNode().put(member, value);
    }

    private ObjectNode object(String member, int value) {
        return mapper.createObjectNode().put(member, value);
    }

    private ObjectNode tags(JsonNode tags) {
        return tags != null && tags.isObject() ? ((ObjectNode) tags).deepCopy() : mapper.createObjectNode();
    }

    private static Map<String, String> tagMap(ObjectNode resource) {
        Map<String, String> tags = new HashMap<>();
        resource.path("tags").properties().forEach(tag -> tags.put(tag.getKey(), tag.getValue().asText()));
        return tags;
    }

    private static void putText(ObjectNode node, String member, String value) {
        if (value != null) {
            node.put(member, value);
        }
    }

    private static void putList(ObjectNode node, String member, List<String> values) {
        if (values != null) {
            ArrayNode array = node.putArray(member);
            values.forEach(array::add);
        }
    }

    // ── Identity, time and errors ────────────────────────────────────────────────

    private String arn(String resource) {
        return regionResolver.buildArn(SERVICE, regionResolver.getRegion(), regionResolver.getAccountId(), resource);
    }

    private String createdBy() {
        String accessKeyId = requestContext != null ? requestContext.getAccessKeyId() : null;
        return iamService.resolveCallerArn(accessKeyId)
                .orElseGet(() -> regionResolver.buildGlobalArn("iam", regionResolver.getAccountId(), "root"));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    private static String timestamp(Instant instant) {
        return instant.toString();
    }

    /** An id of {@code length} lowercase letters and digits, as the model's {@code [0-9a-z]+} allows. */
    static String newId(int length) {
        StringBuilder id = new StringBuilder(length);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            id.append(ID_ALPHABET.charAt(random.nextInt(ID_ALPHABET.length())));
        }
        return id.toString();
    }

    /**
     * An id of the form a Spark application on Kubernetes gets, {@code spark-} and 32 hexadecimal digits, which names
     * a run's driver logs as its Spark application's id names them on EMR on EKS.
     */
    static String sparkApplicationId() {
        return "spark-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String sessionToken() {
        byte[] bytes = new byte[48];
        ThreadLocalRandom.current().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String virtualClusterMissing(String id) {
        // AWS's "Common errors when running jobs" for StartJobRun.
        return "Virtual cluster " + id + " doesn't exist.";
    }

    private static String jobRunMissing(String id) {
        return "Job run " + id + " doesn't exist.";
    }

    private static String endpointMissing(String id) {
        return "Endpoint " + id + " doesn't exist.";
    }

    private static String jobTemplateMissing(String id) {
        return "Job template " + id + " doesn't exist.";
    }

    private static String securityConfigurationMissing(String id) {
        return "Security configuration " + id + " doesn't exist.";
    }

    private static AwsException tooManyTags() {
        // The development guide's tag restrictions: "Maximum number of tags per resource – 50".
        return invalid("A resource can have at most " + MAX_TAGS + " tags.");
    }

    private static AwsException resourceMissing(String resourceArn) {
        return notFound("Resource " + resourceArn + " doesn't exist.");
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 400);
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    /** What a run's thread tells the service, and asks it, while its Spark job runs. */
    private final class RunProgress implements SparkJobRunner.Progress {

        private final String arn;
        private final SparkJobRunner.Job job;
        private final long launchedIn;

        RunProgress(String arn, SparkJobRunner.Job job, long launchedIn) {
            this.arn = arn;
            this.job = job;
            this.launchedIn = launchedIn;
        }

        @Override
        public void started() {
            RequestScopes.runAs(job.accountId(), job.region(), () -> driverStarted(arn, launchedIn));
        }

        // A run of EMR on EKS has no timeout of its own.
        @Override
        public void timedOut() {
        }

        @Override
        public boolean cancelled() {
            return !RequestScopes.callAs(job.accountId(), job.region(), () -> stillGoing(arn, launchedIn));
        }
    }
}
