package io.floci.aws.services.emr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsEndpoints;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.MemoizedSupplier;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.core.common.SparkConnectEndpoint;
import io.floci.aws.core.common.SparkConnectProxy;
import io.floci.aws.core.common.SparkConnectTargets;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.core.storage.StorageJson;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceState;
import io.floci.aws.services.ec2.model.Placement;
import io.floci.aws.services.emr.model.EmrBlockPublicAccess;
import io.floci.aws.services.emr.model.EmrCluster;
import io.floci.aws.services.emr.model.EmrErrorDetail;
import io.floci.aws.services.emr.model.EmrInstanceFleet;
import io.floci.aws.services.emr.model.EmrInstanceGroup;
import io.floci.aws.services.emr.model.EmrSession;
import io.floci.aws.services.emr.model.EmrStep;
import io.floci.aws.services.emr.model.SecurityConfiguration;
import io.floci.aws.services.emrserverless.EmrServerlessSparkRunner;
import io.floci.aws.services.emrserverless.SessionServers;
import io.floci.aws.services.emrserverless.SparkConnectSession;
import io.floci.aws.services.emrserverless.SparkConnectSessionOwner;
import io.floci.aws.services.emrserverless.SparkJobRunner;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PrimitiveIterator;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EMR management-plane business logic. Clusters and steps go through the states of
 * {@link EmrClusterLifecycle}, timed by {@code cluster-startup-delay-seconds},
 * {@code step-duration-seconds} and {@code cluster-termination-delay-seconds}, all 0 by default, which
 * makes a cluster ready, and a step complete, before the call that started it returns. A call brings
 * each cluster it reads up to the time it is made, and the service wakes at each cluster's next
 * change, so the change's event goes out without anyone asking. Events are published once the call
 * that made the change no longer holds the service.
 *
 * <p>In the default {@code immediate} runner mode nothing runs. In the {@code docker} one each step
 * runs, in a container of its own, from when it goes RUNNING until its Spark driver exits, on a thread
 * of its own that ends the step under the service's lock: COMPLETED on exit code 0, FAILED otherwise,
 * with its ActionOnFailure taken. A cluster's bootstrap actions run there too, while it is
 * BOOTSTRAPPING, and leave the image its steps then run in.
 */
@ApplicationScoped
public class EmrService implements Resettable, StoredResourceArns, ContainerTeardown, SparkConnectTargets {

    private static final Logger LOG = Logger.getLogger(EmrService.class);
    private static final String UPPER_ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SESSION_PASSWORD_BYTES = 24;
    // No source gives how long GetClusterSessionCredentials' credentials last; Floci's expire in an hour.
    private static final Duration SESSION_CREDENTIALS_VALIDITY = Duration.ofHours(1);
    /**
     * How long a cluster's history is kept after it ends: "The logs are available for 30 days after the application
     * ends".
     */
    static final Duration HISTORY_KEPT = Duration.ofDays(30);
    // How long after its 30 days a cluster's kept history is looked at again, to remove it.
    private static final Duration HISTORY_SWEEP_MARGIN = Duration.ofMinutes(1);
    // How long the command that starts a cluster's primary node for its application UIs or its Trino may run: it runs
    // nothing.
    private static final Duration PRIMARY_NODE_START = Duration.ofMinutes(5);
    // AWS's answer to a change on a terminated cluster, for AddJobFlowSteps and ModifyInstanceFleet; Floci
    // gives it to AddInstanceGroups and AddInstanceFleet too, for one shutting down.
    private static final String JOB_FLOW_SHUT_DOWN =
            "A job flow that is shutting down, terminated, or finished may not be modified.";
    // Copies a stored resource for a call to answer from; the Studios' service copies its own with it too.
    static final ObjectMapper SNAPSHOT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final String RUNNER_DOCKER = "docker";
    // The service a step's driver belongs to, which names it and labels it.
    private static final String SERVICE = "emr";
    // The resource of a cluster's ARN, before its id.
    static final String CLUSTER_RESOURCE = "cluster/";
    /** What follows a cluster's ARN in the ARN of one of its sessions, the session's id following. */
    static final String SESSION_SEGMENT = "/session/";
    // The reason of a cluster a failed bootstrap action ended, as Terraform reported a real one's
    // (hashicorp/terraform-provider-aws#4378) and the Management Guide's VPC errors page quotes it.
    private static final String BOOTSTRAP_ACTION_FAILED =
            "On the master instance (%s), bootstrap action %d returned a non-zero return code";
    // No source gives AWS's wording for these, nor for a core or task node's action that failed, which ends a cluster
    // once "too many instances fail their bootstrap actions"; they are Floci's, the last after the primary node's.
    private static final String BOOTSTRAP_ACTION_NOT_READ =
            "On the %s instance (%s), bootstrap action %d could not be read from %s: %s";
    private static final String NODE_ACTION_FAILED =
            "On the %s instance (%s), bootstrap action %d returned a non-zero return code";
    private static final String BOOTSTRAP_NOT_RUN = "Floci could not run the bootstrap actions: %s";
    static final String BOOTSTRAP_INTERRUPTED = "Floci stopped while the cluster's bootstrap actions were running.";
    // The ErrorDetail codes of an action that failed on the primary node and of one whose script it could not download,
    // each with a Management Guide page of its own name.
    static final String BOOTSTRAP_FAILURE_PRIMARY_WITH_NON_ZERO_CODE = "BOOTSTRAP_FAILURE_PRIMARY_WITH_NON_ZERO_CODE";
    static final String BOOTSTRAP_FAILURE_BA_DOWNLOAD_FAILED_PRIMARY = "BOOTSTRAP_FAILURE_BA_DOWNLOAD_FAILED_PRIMARY";
    private static final String ERROR_CODE_PAGE = "https://docs.aws.amazon.com/emr/latest/ManagementGuide/%s.html";
    // The release whose EMR Serverless Hive image runs the HDFS of a cluster of the AWS runtime for Apache Spark,
    // which publishes none. Hadoop keeps its wire protocols compatible across a major version's minor releases, so
    // a newer client, such as the runtime's, reaches its NameNode.
    static final String SPARK_RUNTIME_HDFS_RELEASE = "emr-7.5.0";
    // Separates a security configuration key's Region from its name.
    private static final String KEY_SEPARATOR = "::";

    private final AccountAwareStorageBackend<EmrCluster> clusterStore;
    // Clusters' Spark Connect sessions, by session id.
    private final AccountAwareStorageBackend<EmrSession> sessionStore;
    // A security configuration is Regional: keyed by Region and name (securityConfigurationKey).
    private final AccountAwareStorageBackend<SecurityConfiguration> secConfigStore;
    // Block public access is an account setting per Region, so this store is keyed by Region.
    private final StorageBackend<String, EmrBlockPublicAccess> blockPublicAccessStore;
    private final RegionResolver regionResolver;
    private final EmrEventPublisher eventPublisher;
    private final EmrClusterLifecycle lifecycle;
    private final Clock clock;
    private final Wakeups wakeups;
    // Each cluster's next wakeup, by account and id, so a call that reads a cluster does not add another.
    private final Map<String, Instant> wakeupsDue = new ConcurrentHashMap<>();
    // The docker runner mode's; null in the immediate one.
    private final SparkJobRunner sparkRunner;
    private final BootstrapRunner bootstrapRunner;
    private final ClusterStorage clusterStorage;
    private final ReleaseImages releaseImages;
    private final Executor stepExecutor;
    // The commands other EMR services run on a cluster ({@link #runOnCluster}), by run id, with their cluster's id.
    private final Map<String, String> clusterRuns = new HashMap<>();
    // The clusters whose primary node an application UI link is starting, by account and id, and how many it has.
    private final Set<String> primaryNodeStarts = ConcurrentHashMap.newKeySet();
    private final AtomicLong primaryNodeStartCount = new AtomicLong();
    // The Spark Connect servers of the sessions, in the docker runner mode, and the tokens given for every session.
    private final SessionServers sessionServers;
    private final SessionOwner sessionOwner = new SessionOwner();
    private final IamService iamService;
    // Where a step's driver runs: image-template, with the release label for %s, and docker-network; null when
    // not set.
    private final String imageTemplate;
    private final String hiveImageTemplate;
    private final String dockerNetwork;
    // Whether a cluster whose Applications name Pig gets Apache Pig in its image (floci.services.emr.pig.enabled).
    private final boolean pigEnabled;
    // Whether a cluster whose Applications name Trino runs it (floci.services.emr.trino.enabled), from which image, the
    // release's Trino version for %s, and with how much memory.
    private final boolean trinoEnabled;
    private final String trinoImageTemplate;
    private final int trinoMemoryMb;
    // What the NodeManager of a cluster that runs YARN offers its containers.
    private final ClusterStorage.Yarn nodeManager;
    private final int maxWorkerNodes;
    // The steps a thread is running, by account and id; a RUNNING container step not here was left by a restart.
    private final Set<String> executing = new HashSet<>();
    // Likewise the clusters a thread is bootstrapping.
    private final Set<String> bootstrapping = new HashSet<>();
    // The images of ended clusters a thread is removing. A cluster names its image until it is gone, so one
    // Docker refused to remove is tried again the next time the cluster is read or stored.
    private final Set<String> imagesBeingRemoved = new HashSet<>();
    // A node whose actions fail runs them again once, in a fresh container (see bootstrapNode).
    private static final int NODE_BOOTSTRAP_ATTEMPTS = 2;
    // Each core and task node's bootstrap runs on a thread of its own, beside the primary node's, as on an instance of
    // its own.
    private static final Executor NODE_BOOTSTRAPS = Executors.newCachedThreadPool(
            Thread.ofPlatform().daemon().name("emr-node-bootstrap-", 0).factory());
    // Likewise the clusters, by account and id, whose storage a thread is removing.
    private final Set<String> storageBeingRemoved = new HashSet<>();
    // What one thread at a time holds, by account and cluster, to bootstrap the nodes a resize added to the cluster,
    // and the clusters whose added nodes' bootstraps are running, which the cluster's end stops.
    private final ConcurrentHashMap<String, Object> nodeBootstrapLocks = new ConcurrentHashMap<>();
    private final Set<String> nodesBootstrapping = ConcurrentHashMap.newKeySet();
    // Raised by each reset, so a step's thread from before it writes nothing back after the wipe.
    private long generation;
    // How many teardowns, by a reset or a shutdown, have begun: a step handed to its thread before one starts no HDFS
    // and no driver after it.
    private final AtomicLong teardowns = new AtomicLong();
    // From a teardown until its reset is over, no step or bootstrap is handed to a thread: a cluster a wakeup moves on
    // meanwhile would start containers after the teardowns that remove them. After a shutdown's, never again.
    private volatile boolean tornDown;

    private static final Set<String> COMPUTE_LIMITS_UNIT_TYPES = Set.of("InstanceFleetUnits", "Instances", "VCPU");
    private static final Set<String> SCALING_STRATEGIES = Set.of("DEFAULT", "ADVANCED");
    private static final int MIN_PERFORMANCE_INDEX = 1;
    private static final int MAX_PERFORMANCE_INDEX = 100;
    private static final List<Integer> PERFORMANCE_INDEXES = List.of(1, 25, 50, 75, 100);
    private static final String MERGE = "MERGE";
    private static final List<String> RECONFIGURATION_TYPES = List.of("OVERWRITE", MERGE);
    private static final long MIN_IDLE_TIMEOUT_SECONDS = 60;
    private static final long MAX_IDLE_TIMEOUT_SECONDS = 604_800;
    // StepConcurrencyLevel: "You can specify a minimum of 1 step and a maximum of 256 steps" (ModifyCluster), "The
    // maximum value is 256" (RunJobFlow). And "only 256 steps can be RUNNING or PENDING at any given time" (Management
    // Guide), and "With Amazon EMR releases 7.14.0 and later, the maximum number of steps you can cancel per request
    // is 100. With earlier releases, the maximum is 256 steps per request" ("Cancel steps"). No source gives AWS's
    // answer to any of them, so their words are Floci's.
    private static final int MAX_STEP_CONCURRENCY = 256;
    private static final int MAX_ACTIVE_STEPS = 256;
    private static final int MAX_CANCELLED_STEPS = 256;
    private static final int MAX_CANCELLED_STEPS_FROM_7_14 = 100;
    private static final String TERMINATE_PROCESS = "TERMINATE_PROCESS";
    private static final List<String> STEP_CANCELLATION_OPTIONS = List.of("SEND_INTERRUPT", TERMINATE_PROCESS);
    // A release label's major, minor and patch versions.
    private static final Pattern RELEASE_VERSION = Pattern.compile("emr-(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})");
    private static final int MAX_PORT = 65_535;
    // The AWS default: block public access enabled in every Region, with port 22 as an exception.
    private static final String DEFAULT_BLOCK_PUBLIC_ACCESS =
            "{\"BlockPublicSecurityGroupRules\":true,"
                    + "\"PermittedPublicSecurityGroupRuleRanges\":[{\"MinRange\":22,\"MaxRange\":22}]}";
    private final String defaultReleaseLabel;
    // Floci's own address, which GetSessionEndpoint's endpoint is on.
    private final String baseUrl;

    /**
     * When the service next brings a cluster up to date by itself, and where it then sends the events
     * that queued; a test runs both when it chooses.
     */
    interface Wakeups {

        void at(Instant due, Runnable task);

        /** Sends a wakeup's events, by default on the wakeup's own thread. */
        default void deliver(Runnable delivery) {
            delivery.run();
        }

        default void close() {
        }

        /**
         * Wakeups on a daemon thread, each at its time by {@code clock}, and their events sent on another,
         * in the order they queued: a rule's target can take seconds to answer, and no cluster's change
         * waits for it.
         */
        static Wakeups timer(Clock clock) {
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("emr-lifecycle").factory());
            ExecutorService deliveries = Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("emr-events").factory());
            return new Wakeups() {
                @Override
                public void at(Instant due, Runnable task) {
                    long delay = Math.max(0, Duration.between(clock.instant(), due).toMillis());
                    executor.schedule(task, delay, TimeUnit.MILLISECONDS);
                }

                @Override
                public void deliver(Runnable delivery) {
                    deliveries.execute(delivery);
                }

                @Override
                public void close() {
                    executor.shutdownNow();
                    deliveries.shutdownNow();
                }
            };
        }
    }

    // The Studios, whose ARNs and tags this service answers for too, as IAM asks one service per namespace; looked up
    // as they are needed, and absent in a test that builds this service itself.
    @Inject
    Provider<EmrStudioService> studios;

    // The notebook executions, whose tags this service answers for too; looked up as they are needed.
    @Inject
    Provider<EmrNotebookExecutionService> notebookExecutions;

    @Inject
    public EmrService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver,
                      EmrEventPublisher eventPublisher, EmrServerlessSparkRunner sparkRunner,
                      EmrBootstrapRunner bootstrapRunner, EmrClusterStorage clusterStorage,
                      EmrReleaseImages releaseImages, IamService iamService, SparkConnectProxy proxy) {
        this(storageFactory, config, regionResolver, eventPublisher, Clock.systemUTC(),
                Wakeups.timer(Clock.systemUTC()),
                RUNNER_DOCKER.equalsIgnoreCase(config.services().emr().runnerMode()) ? sparkRunner : null,
                RUNNER_DOCKER.equalsIgnoreCase(config.services().emr().runnerMode()) ? bootstrapRunner : null,
                RUNNER_DOCKER.equalsIgnoreCase(config.services().emr().runnerMode()) ? clusterStorage : null,
                RUNNER_DOCKER.equalsIgnoreCase(config.services().emr().runnerMode()) ? releaseImages : null,
                Executors.newCachedThreadPool(Thread.ofPlatform().daemon().name("emr-step-", 0).factory()),
                iamService,
                new SessionServers(SessionServers.Kind.SPARK_CONNECT,
                        RUNNER_DOCKER.equalsIgnoreCase(config.services().emr().runnerMode()) ? sparkRunner : null,
                        Executors.newCachedThreadPool(Thread.ofPlatform().daemon().name("emr-session-", 0).factory()),
                        SessionServers.checkThread("emr-session-checks"), SessionServers.sparkConnectProbe(proxy),
                        Clock.systemUTC()));
    }

    EmrService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver,
               EmrEventPublisher eventPublisher, Clock clock, Wakeups wakeups) {
        this(storageFactory, config, regionResolver, eventPublisher, clock, wakeups, null, null, null, null,
                Runnable::run, null);
    }

    EmrService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver,
               EmrEventPublisher eventPublisher, Clock clock, Wakeups wakeups, SparkJobRunner sparkRunner,
               BootstrapRunner bootstrapRunner, ClusterStorage clusterStorage, ReleaseImages releaseImages,
               Executor stepExecutor, IamService iamService) {
        this(storageFactory, config, regionResolver, eventPublisher, clock, wakeups, sparkRunner, bootstrapRunner,
                clusterStorage, releaseImages, stepExecutor, iamService,
                new SessionServers(SessionServers.Kind.SPARK_CONNECT, sparkRunner, stepExecutor, null,
                        (host, port) -> "not probed", clock));
    }

    EmrService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver,
               EmrEventPublisher eventPublisher, Clock clock, Wakeups wakeups, SparkJobRunner sparkRunner,
               BootstrapRunner bootstrapRunner, ClusterStorage clusterStorage, ReleaseImages releaseImages,
               Executor stepExecutor, IamService iamService, SessionServers sessionServers) {
        this.sessionServers = sessionServers;
        this.clusterStore = storageFactory.create("emr", "emr-clusters.json",
                new TypeReference<Map<String, EmrCluster>>() {});
        this.sessionStore = storageFactory.create("emr", "emr-sessions.json",
                new TypeReference<Map<String, EmrSession>>() {});
        this.secConfigStore = storageFactory.create("emr", "emr-security-configs.json",
                new TypeReference<Map<String, SecurityConfiguration>>() {});
        this.blockPublicAccessStore = storageFactory.create("emr", "emr-block-public-access.json",
                new TypeReference<Map<String, EmrBlockPublicAccess>>() {});
        this.regionResolver = regionResolver;
        migrateLegacySecurityConfigurations();
        this.eventPublisher = eventPublisher;
        this.defaultReleaseLabel = config.services().emr().defaultReleaseLabel();
        this.baseUrl = config.effectiveBaseUrl();
        this.sparkRunner = sparkRunner;
        this.bootstrapRunner = bootstrapRunner;
        this.clusterStorage = clusterStorage;
        this.releaseImages = releaseImages;
        this.stepExecutor = stepExecutor;
        this.iamService = iamService;
        this.imageTemplate = config.services().emr().imageTemplate().orElse(null);
        this.hiveImageTemplate = config.services().emr().hiveImageTemplate().orElse(null);
        this.dockerNetwork = config.services().emr().dockerNetwork().orElse(null);
        this.pigEnabled = config.services().emr().pig().enabled();
        this.trinoEnabled = config.services().emr().trino().enabled();
        this.trinoImageTemplate = trinoEnabled ? config.services().emr().trino().image() : null;
        this.trinoMemoryMb = trinoEnabled ? config.services().emr().trino().memoryMb() : 0;
        this.nodeManager = new ClusterStorage.Yarn(config.services().emr().yarnNodeMemoryMb(),
                config.services().emr().yarnNodeVcores());
        this.maxWorkerNodes = config.services().emr().yarnMaxWorkerNodes();
        this.lifecycle = new EmrClusterLifecycle(config.services().emr().clusterStartupDelaySeconds(),
                config.services().emr().stepDurationSeconds(),
                config.services().emr().clusterTerminationDelaySeconds(), eventPublisher,
                sparkRunner != null ? new ContainerSteps() : null,
                bootstrapRunner != null ? new ContainerBootstrap() : null);
        this.clock = clock;
        this.wakeups = wakeups;
        // Before any step of this process can start, the next step a restarted cluster's wakeup below starts
        // included: a driver an earlier process left belongs to a step read as FAILED, and the sweep removes
        // every driver of this deployment it finds.
        if (sparkRunner != null && config.services().emr().reconcileContainersOnStartup()) {
            sparkRunner.removeLeftoverDrivers(SERVICE);
        }
        // After a restart, a stored cluster with a change still to come wakes the service as before, and one
        // with a step a container ran, or bootstrap actions, which no thread runs any more, wakes it at once to
        // fail them. The images bootstrap left for clusters that are gone, or have ended, go first: a wakeup
        // runs on the timer's thread, and a cluster it moved on could keep an image the sweep never knew of. The
        // storage of the clusters that are gone, or have ended, goes then too.
        Set<String> imagesInUse = new HashSet<>();
        Set<String> clustersUp = new HashSet<>();
        List<Runnable> wakeAtStart = new ArrayList<>();
        for (AccountAwareStorageBackend.AccountEntry<EmrCluster> entry
                : clusterStore.scanAllAccountEntries(k -> true)) {
            EmrCluster cluster = entry.value();
            boolean interrupted = cluster.getSteps().stream().anyMatch(this::leftByARestart)
                    || bootstrapRunner != null && bootstrapsInContainer(cluster);
            Instant due = interrupted ? clock.instant() : lifecycle.nextChange(cluster);
            if (due != null) {
                wakeAtStart.add(() -> wakeAt(entry.accountId(), cluster, due));
            }
            if (cluster.getBootstrapImage() != null && !EmrClusterLifecycle.isTerminated(cluster.getState())) {
                imagesInUse.add(cluster.getBootstrapImage());
            }
            if (!EmrClusterLifecycle.isTerminated(cluster.getState())) {
                imagesInUse.addAll(cluster.getNodeBootstrapImages().values());
            }
            if (!EmrClusterLifecycle.isTerminated(cluster.getState())) {
                clustersUp.add(cluster.getId());
            }
        }
        Set<String> histories = historiesKept();
        if (bootstrapRunner != null && config.services().emr().reconcileContainersOnStartup()) {
            bootstrapRunner.removeImages(imagesInUse);
        }
        if (clusterStorage != null && config.services().emr().reconcileContainersOnStartup()) {
            clusterStorage.removeAllBut(clustersUp);
            clusterStorage.removeHistoriesBut(histories);
        }
        wakeAtStart.forEach(Runnable::run);
    }

    // A normal-scoped bean is built on first use, so without this a cluster left mid-change by a restart
    // would not move on, nor send its events, until the next EMR call.
    void onStart(@Observes StartupEvent ignored) {
        LOG.debugv("EMR service ready");
    }

    @PreDestroy
    void shutdown() {
        wakeups.close();
        if (stepExecutor instanceof ExecutorService executor) {
            executor.shutdownNow();
        }
        sessionServers.shutdown();
    }

    /**
     * A reset's or a shutdown's teardown has begun: a step handed to its thread before it, whose driver the runner's
     * teardown cannot stop yet, starts no HDFS and no driver after it, as its progress reads it, but stops as a
     * stopped driver does. This runs before the teardowns that stop containers.
     */
    @Override
    public synchronized void stopManagedContainers() {
        teardowns.incrementAndGet();
        tornDown = true;
    }

    /** A reset is over: steps and bootstraps are handed to threads again. */
    @Override
    public synchronized void afterReset() {
        tornDown = false;
    }

    @Override
    public int teardownOrder() {
        return -1;
    }

    /**
     * A reset has begun: the steps' and bootstraps' threads from before it write nothing more, so the storage
     * wipe that follows leaves none of their clusters behind, and the images bootstrap left, and the clusters'
     * own storage, go with them. Its teardown has already stopped their containers.
     */
    @Override
    public synchronized void beforeReset() {
        generation++;
        executing.clear();
        bootstrapping.clear();
        clusterRuns.clear();
        sessionServers.reset();
        if (bootstrapRunner != null) {
            bootstrapRunner.removeImages(Set.of());
        }
        if (clusterStorage != null) {
            clusterStorage.removeAllBut(Set.of());
            clusterStorage.removeHistoriesBut(Set.of());
        }
    }

    @Override
    public void clear() {
        // The clusters live in storage, which the reset has already wiped.
    }

    // ──────────────────────────── Cluster lifecycle ────────────────────────────

    public EmrCluster runJobFlow(EmrCluster cluster, String region) {
        return call(() -> {
            // Only a missing Name fails AWS's framework validation: the member is REQUIRED but its
            // constraint is len=[0..256], so an explicit empty string is accepted by real EMR.
            if (cluster.getName() == null) {
                throw SmithyValidationErrors.validationException(SmithyValidationErrors.notNull("name"));
            }
            // "The RunJobFlow request can contain InstanceFleets parameters or InstanceGroups parameters, but not
            // both", as "instance fleets and instance groups cannot coexist in a cluster".
            if (!cluster.getInstanceGroups().isEmpty() && !cluster.getInstanceFleets().isEmpty()) {
                throw new AwsException("ValidationException", "The RunJobFlow request can contain InstanceFleets"
                        + " parameters or InstanceGroups parameters, but not both.", 400);
            }
            if (outOfStepConcurrencyRange(cluster.getStepConcurrencyLevel())) {
                throw new AwsException("ValidationException",
                        stepConcurrencyOutOfRange(cluster.getStepConcurrencyLevel()), 400);
            }
            // Each of its steps is PENDING as it starts.
            if (cluster.getSteps().size() > MAX_ACTIVE_STEPS) {
                throw new AwsException("ValidationException", tooManyActiveSteps(cluster.getSteps().size()), 400);
            }
            // A security configuration of the cluster's Region is created first and named here; no source gives
            // AWS's answer for one that is not there, so the code is RunJobFlow's own for its input and the wording
            // that of the other calls.
            if (cluster.getSecurityConfiguration() != null
                    && secConfigStore.get(securityConfigurationKey(region, cluster.getSecurityConfiguration()))
                    .isEmpty()) {
                throw new AwsException("ValidationException",
                        "Security configuration does not exist: " + cluster.getSecurityConfiguration(), 400);
            }
            // A security configuration applies to the cluster as it is created: deleting or reusing its name later
            // changes nothing about the cluster.
            cluster.setRuntimeRoleEnabled(runtimeRoleEnabled(region, cluster.getSecurityConfiguration()));
            String id = "j-" + randomId(13);
            cluster.setId(id);
            cluster.setRegion(region);
            cluster.setClusterArn(regionResolver.buildArn("elasticmapreduce", region, "cluster/" + id));
            if (cluster.getReleaseLabel() == null) {
                cluster.setReleaseLabel(defaultReleaseLabel);
            }
            // RunJobFlow "uses the latest validated Amazon Linux release" where none is asked for, and DescribeCluster
            // shows it: the latest its release lists, or none where Floci's catalog lists none for it.
            if (cluster.getOsReleaseLabel() == null) {
                cluster.setOsReleaseLabel(EmrReleaseCatalog.release(cluster.getReleaseLabel())
                        .map(EmrReleaseCatalog.Release::osReleases).filter(labels -> !labels.isEmpty())
                        .map(List::getFirst).orElse(null));
            }
            cluster.setInstanceCollectionType(cluster.getInstanceFleets().isEmpty()
                    ? "INSTANCE_GROUP" : "INSTANCE_FLEET");
            cluster.setAutoTerminate(!cluster.isKeepJobFlowAliveWhenNoSteps());
            cluster.setMasterPublicDnsName(masterDnsName(region));
            // Their states and what runs of them follow the cluster's, from its start (see EmrClusterLifecycle).
            for (EmrInstanceGroup g : cluster.getInstanceGroups()) {
                g.setId("ig-" + randomId(13));
            }
            for (EmrInstanceFleet f : cluster.getInstanceFleets()) {
                f.setId("if-" + randomId(13));
            }
            cluster.setProvisionedCoreNodes(coreNodes(cluster));
            cluster.getSteps().forEach(step -> step.setId("s-" + randomId(13)));
            lifecycle.start(cluster, now(), region);
            store(cluster);
            return cluster;
        });
    }

    /** The master node's private DNS name, derived from the region so a stored name never goes stale. */
    static String masterDnsName(String region) {
        return AwsRegions.ec2PrivateIpDnsName("10.0.0.1", region);
    }

    public EmrCluster describeCluster(String id) {
        return call(() -> snapshotOf(requireCluster(id)));
    }

    /**
     * The cluster of that id in the request's Region, as DescribeCluster gives it, or empty when there is none: what a
     * notebook execution or an application UI asks of the cluster it names, which may have gone since.
     */
    public Optional<EmrCluster> findDescribedCluster(String id) {
        return id == null ? Optional.empty() : call(() -> findCluster(id).map(EmrService::snapshotOf));
    }

    public List<EmrCluster> listClusters(String region, List<String> states,
                                          Instant createdAfter, Instant createdBefore) {
        return call(() -> {
            List<EmrCluster> result = new ArrayList<>();
            for (EmrCluster c : clusterStore.scan(k -> true)) {
                if (!region.equals(c.getRegion())) {
                    continue;
                }
                settle(c);
                if (states != null && !states.isEmpty() && !states.contains(c.getState())) {
                    continue;
                }
                Instant createdAt = c.getCreationDateTime();
                if (createdAt != null) {
                    createdAt = createdAt.truncatedTo(ChronoUnit.MILLIS);
                }
                if (createdAfter != null && (createdAt == null || !createdAt.isAfter(createdAfter))) {
                    continue;
                }
                if (createdBefore != null && (createdAt == null || !createdAt.isBefore(createdBefore))) {
                    continue;
                }
                result.add(snapshotOf(c));
            }
            return result;
        });
    }

    public void terminateJobFlows(List<String> ids) {
        // A request naming a protected cluster still terminates the others before it fails.
        run(() -> {
            // AWS terminates the unprotected clusters in the request and then fails it with a
            // ValidationException if any were termination protected.
            boolean anyProtected = false;
            for (String id : ids) {
                EmrCluster cluster = findCluster(id).orElse(null);
                if (cluster == null || isShutDown(cluster)) {
                    continue;
                }
                if (cluster.isTerminationProtected()) {
                    anyProtected = true;
                    continue;
                }
                lifecycle.terminate(cluster, "USER_REQUEST", "Terminated by user request", now(), regionOf(cluster));
                store(cluster);
            }
            if (anyProtected) {
                throw new AwsException("ValidationException",
                        "Could not shut down one or more job flows since they are termination protected.", 400);
            }
        });
    }

    public void setTerminationProtection(List<String> ids, boolean value) {
        run(() -> mutateClusters(ids, c -> c.setTerminationProtected(value)));
    }

    public void setVisibleToAllUsers(List<String> ids, boolean value) {
        run(() -> mutateClusters(ids, c -> c.setVisibleToAllUsers(value)));
    }

    public void setKeepJobFlowAliveWhenNoSteps(List<String> ids, boolean value) {
        run(() -> mutateClusters(ids, c -> {
            c.setKeepJobFlowAliveWhenNoSteps(value);
            c.setAutoTerminate(!value);
        }));
    }

    public void setUnhealthyNodeReplacement(List<String> ids, boolean value) {
        run(() -> mutateClusters(ids, c -> c.setUnhealthyNodeReplacement(value)));
    }

    public int modifyCluster(String id, Integer stepConcurrencyLevel) {
        return call(() -> {
            EmrCluster cluster = requireCluster(id);
            rejectShutDownCluster(cluster, EmrService::jobFlowShutDown);
            if (stepConcurrencyLevel != null && outOfStepConcurrencyRange(stepConcurrencyLevel)) {
                throw invalid(stepConcurrencyOutOfRange(stepConcurrencyLevel));
            }
            if (stepConcurrencyLevel != null) {
                cluster.setStepConcurrencyLevel(stepConcurrencyLevel);
                lifecycle.concurrencyChanged(cluster, now(), regionOf(cluster));
                store(cluster);
            }
            return cluster.getStepConcurrencyLevel();
        });
    }

    // ──────────────────────────── Steps ────────────────────────────

    public List<String> addJobFlowSteps(String clusterId, List<EmrStep> steps) {
        return call(() -> {
            // AWS's own wording for an unknown job flow here, not DescribeCluster's: a botocore ClientError
            // (raster-foundry/raster-foundry#352) and a CloudTrail record on AWS re:Post both show it.
            EmrCluster cluster = clusterId == null ? requireCluster(null) : findCluster(clusterId).orElseThrow(
                    () -> new AwsException("ValidationException", "Specified job flow ID does not exist.", 400));
            if (isShutDown(cluster)) {
                throw jobFlowShutDownValidation();
            }
            // "If a cluster's StepConcurrencyLevel is greater than 1, do not use AddJobFlowSteps to submit a step with
            // this parameter set to CANCEL_AND_WAIT or TERMINATE_CLUSTER. The step is not submitted and the action
            // fails with a message that the ActionOnFailure setting is not valid", and the guide: "You cannot add a
            // step with an ActionOnFailure other than CONTINUE while the step concurrency level of the cluster is
            // greater than 1". A step that names none takes TERMINATE_CLUSTER, as it is submitted, so it is refused
            // too.
            if (cluster.getStepConcurrencyLevel() > 1) {
                for (EmrStep step : steps) {
                    String action = step.getActionOnFailure() != null ? step.getActionOnFailure()
                            : EmrClusterLifecycle.DEFAULT_ACTION_ON_FAILURE;
                    if (!"CONTINUE".equals(action)) {
                        throw new AwsException("ValidationException", "The ActionOnFailure setting " + action
                                + " is not valid for a step added to a cluster whose StepConcurrencyLevel is greater"
                                + " than 1: only CONTINUE is.", 400);
                    }
                }
            }
            long active = cluster.getSteps().stream().filter(EmrService::activeStep).count() + steps.size();
            if (active > MAX_ACTIVE_STEPS) {
                throw new AwsException("ValidationException", tooManyActiveSteps(active), 400);
            }
            steps.forEach(step -> step.setId("s-" + randomId(13)));
            cluster.getSteps().addAll(steps);
            lifecycle.submit(cluster, steps, now(), regionOf(cluster));
            store(cluster);
            return steps.stream().map(EmrStep::getId).toList();
        });
    }

    public EmrStep describeStep(String clusterId, String stepId) {
        return call(() -> {
            EmrCluster cluster = snapshotOf(requireCluster(clusterId));
            return stepOf(cluster, stepId).orElseThrow(() -> new AwsException("InvalidRequestException",
                    "Step " + stepId + " does not exist.", 400));
        });
    }

    public List<EmrStep> listSteps(String clusterId, List<String> states, List<String> stepIds) {
        return call(() -> {
            EmrCluster cluster = snapshotOf(requireCluster(clusterId));
            List<EmrStep> result = new ArrayList<>();
            // AWS returns steps in reverse submission order (newest first).
            List<EmrStep> steps = cluster.getSteps();
            for (int i = steps.size() - 1; i >= 0; i--) {
                EmrStep step = steps.get(i);
                if (states != null && !states.isEmpty() && !states.contains(step.getState())) {
                    continue;
                }
                if (stepIds != null && !stepIds.isEmpty() && !stepIds.contains(step.getId())) {
                    continue;
                }
                result.add(step);
            }
            return result;
        });
    }

    public record CancelStepInfo(String stepId, String status, String reason) {}

    public List<CancelStepInfo> cancelSteps(String clusterId, List<String> stepIds) {
        return cancelSteps(clusterId, stepIds, null);
    }

    /**
     * Cancels the steps with {@code option}, SEND_INTERRUPT (the default, for null) or TERMINATE_PROCESS, the model's
     * StepCancellationOption ({@link EmrClusterLifecycle#cancel}).
     */
    public List<CancelStepInfo> cancelSteps(String clusterId, List<String> stepIds, String option) {
        if (option != null && !STEP_CANCELLATION_OPTIONS.contains(option)) {
            throw SmithyValidationErrors.validationException(
                    SmithyValidationErrors.notInEnum(option, "stepCancellationOption", STEP_CANCELLATION_OPTIONS));
        }
        boolean terminateProcess = TERMINATE_PROCESS.equals(option);
        return call(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            // CancelSteps is "Available only in Amazon EMR versions 4.8.0 and later, excluding version 5.0.0".
            if (!cancelsSteps(cluster.getReleaseLabel())) {
                throw invalid("CancelSteps is available only in Amazon EMR versions 4.8.0 and later, excluding"
                        + " version 5.0.0, and cluster " + clusterId + " runs " + cluster.getReleaseLabel() + ".");
            }
            int most = releaseAtLeast(cluster.getReleaseLabel(), 7, 14, 0)
                    ? MAX_CANCELLED_STEPS_FROM_7_14 : MAX_CANCELLED_STEPS;
            if (stepIds.size() > most) {
                throw invalid("A CancelSteps request can cancel at most " + most + " steps on release "
                        + cluster.getReleaseLabel() + ", and this one names " + stepIds.size() + ".");
            }
            List<CancelStepInfo> result = new ArrayList<>();
            for (String stepId : stepIds) {
                EmrStep step = stepOf(cluster, stepId).orElse(null);
                String refusal = step == null ? "Step does not exist."
                        : lifecycle.cancel(cluster, step, now(), regionOf(cluster), terminateProcess);
                result.add(refusal == null
                        ? new CancelStepInfo(stepId, "SUBMITTED", null)
                        : new CancelStepInfo(stepId, "FAILED", refusal));
            }
            store(cluster);
            return result;
        });
    }

    // ──────────────────────────── Sessions ────────────────────────────

    /**
     * Starts a Spark Connect session on the cluster ({@link EmrSessions}), or, for a retry with the same client
     * request token, returns the one it started: "If you retry a request that completed successfully using the same
     * client request token, the service returns the original response", which said SUBMITTED. A retry is the same
     * request when it asks for the same session, whatever members it leaves at their defaults; a token reused for a
     * different one is refused, in Floci's own wording, as the model declares no conflict error. The answer is a copy
     * of the session, in the state StartSession reports.
     */
    public EmrSession startSession(String clusterId, EmrSessions.Request request) {
        EmrSessions.validate(request);
        return call(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            List<EmrSession> sessions = settledSessions(cluster);
            String parameters = sessionParameters(cluster.getId(), request);
            EmrSession existing = EmrSessions.startedWithToken(sessions, request.clientRequestToken());
            if (existing != null) {
                if (!sameRequest(existing.getRequest(), parameters)) {
                    throw invalid("ClientRequestToken " + request.clientRequestToken()
                            + " was used with a different request.");
                }
                EmrSession original = sessionCopy(existing);
                original.setState(EmrSessions.SUBMITTED);
                return original;
            }
            EmrSessions.checkStartable(cluster, sessions, SNAPSHOT_MAPPER);
            String id = "is-" + randomId(13);
            long sequence = sessions.stream().mapToLong(EmrSession::getSequence).max().orElse(0) + 1;
            EmrSession session = EmrSessions.start(cluster, request, id,
                    regionResolver.buildArn("elasticmapreduce", regionOf(cluster),
                            CLUSTER_RESOURCE + cluster.getId() + SESSION_SEGMENT + id),
                    regionResolver.getAccountId(), now(), sequence, parameters);
            session.setContainerSession(runsSessionServers());
            sessionStore.put(id, session);
            if (session.isContainerSession()) {
                launchSessionServer(cluster, session);
            }
            return sessionCopy(session);
        });
    }

    public EmrSession getSession(String clusterId, String sessionId) {
        return call(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            return sessionCopy(EmrSessions.find(cluster, settledSessions(cluster), sessionId));
        });
    }

    /**
     * The cluster's sessions, "Newer sessions are returned first", filtered by {@code states}, at most
     * {@code maxResults} (1 to 100) to a page. A {@code nextToken} resumes only the listing it came from.
     */
    public PaginatedResult<EmrSession> listSessions(String clusterId, List<String> states, Integer maxResults,
                                                    String nextToken) {
        if (maxResults != null) {
            String violation = SmithyValidationErrors.outOfRange("maxResults", maxResults, 1,
                    EmrSessions.MAX_LIST_RESULTS);
            if (violation != null) {
                throw SmithyValidationErrors.validationException(violation);
            }
        }
        EmrSessions.validateStates(states);
        return call(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            List<EmrSession> matching = settledSessions(cluster).stream()
                    .filter(session -> states == null || states.isEmpty() || states.contains(session.getState()))
                    .toList();
            String namespace = String.join("|", "ListSessions", cluster.getId(),
                    String.valueOf(states != null ? states : List.of()));
            // Newest first: the later of a cluster's sessions has the higher sequence.
            PaginatedResult<EmrSession> page = Pagination.paginate(matching,
                    session -> String.format("%020d", Long.MAX_VALUE - session.getSequence()),
                    maxResults != null ? maxResults : EmrSessions.MAX_LIST_RESULTS, nextToken, namespace,
                    token -> invalid("Invalid NextToken."));
            return new PaginatedResult<>(page.items().stream().map(EmrService::sessionCopy).toList(),
                    page.nextToken());
        });
    }

    /**
     * Terminates a session that has not ended: at once where no server runs for it, else TERMINATING until its server
     * has stopped ({@link EmrSessions#terminate}).
     */
    public EmrSession terminateSession(String clusterId, String sessionId) {
        return call(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            EmrSession session = EmrSessions.find(cluster, settledSessions(cluster), sessionId);
            if (sessionServers.runs(session.getId())) {
                EmrSessions.terminating(session, now());
                sessionStore.put(session.getId(), session);
                sessionServers.stop(session.getId());
            } else {
                EmrSessions.terminate(session, now());
                sessionStore.put(session.getId(), session);
            }
            return sessionCopy(session);
        });
    }

    /**
     * Where a client connects to a session that has not ended, with a token that expires in an hour, as
     * "Authentication tokens expire after 1 hour". The endpoint is Floci's own address, where
     * {@link SparkConnectProxy} takes a call carrying the token on to the session's server: Spark Connect's
     * {@code sc://} URL holds a host and a port but no path, so the token names the session. AWS's connection string
     * adds the session's id as its {@code authorization} parameter, which the proxy drops. A session that is
     * TERMINATING is refused as one that has ended.
     */
    public SparkConnectEndpoint sessionEndpoint(String clusterId, String sessionId) {
        return call(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            EmrSession session = EmrSessions.find(cluster, settledSessions(cluster), sessionId);
            EmrSessions.requireNotEnded(session);
            if (EmrSessions.TERMINATING.equals(session.getState())) {
                throw invalid("Session " + session.getId() + " has already ended.");
            }
            SparkConnectEndpoint endpoint = SparkConnectEndpoint.issue(baseUrl, "", now(),
                    EmrSessions.TOKEN_VALIDITY);
            sessionServers.grant(endpoint.authToken(), session.getId(), session.getAccountId(), regionOf(cluster),
                    endpoint.authTokenExpiresAt());
            return endpoint;
        });
    }

    /** Where a Spark Connect call carrying {@code token} goes: a session's server, {@link SessionServers}. */
    @Override
    public Optional<Route> route(String token) {
        return sessionServers.route(token, sessionOwner);
    }

    // Whether a session runs a Spark Connect server: in the docker runner mode, where its cluster has storage.
    private boolean runsSessionServers() {
        return sparkRunner != null && clusterStorage != null;
    }

    /** Whether {@link #runOnCluster} runs anything: in the docker runner mode, as steps run. */
    public boolean runsOnClusters() {
        return runsSessionServers();
    }

    /** What a command run on a cluster ({@link #runOnCluster}) tells its caller, and asks it, as its account. */
    public interface ClusterRun {

        /** The command's container runs. */
        void started();

        /** The command has ended, as {@code outcome} says; the cluster's end stops it, which says it was stopped. */
        void ended(SparkJobRunner.Outcome outcome);

        /** Whether the run is past starting, or going on. */
        boolean cancelled();
    }

    /**
     * Runs {@code command} on the cluster as a step's command runs, its {@code spark-submit} a YARN application there
     * with the cluster's Spark settings, as {@code runId}, with {@code environment}, as {@code role} (else the
     * cluster's instance profile), for at most {@code limit}, telling {@code run}. The cluster keeps its storage while
     * it runs, and its end stops it, as a reset's or a shutdown's teardown does. A command that cannot be handed to its
     * thread, its cluster shut down or a teardown begun, has ended before this returns, as stopped, or as one that
     * could not start. A notebook execution runs its notebook so.
     */
    public void runOnCluster(String clusterId, String runId, String role, List<String> command,
                             Map<String, String> environment, Duration limit, ClusterRun run) {
        CommandLaunch launch;
        try {
            launch = call(() -> commandLaunch(clusterId, runId, role, command, environment, limit));
        } catch (RuntimeException e) {
            run.ended(SparkJobRunner.Outcome.couldNotStart(reasonOf(e)));
            return;
        }
        if (launch == null) {
            run.ended(SparkJobRunner.Outcome.halted());
            return;
        }
        try {
            stepExecutor.execute(() -> runCommand(launch, run));
        } catch (RuntimeException e) {
            run.ended(SparkJobRunner.Outcome.couldNotStart(reasonOf(e)));
            forgetCommand(runId);
        }
    }

    // A command that runs no more no longer holds what its cluster kept.
    private void forgetCommand(String runId) {
        run(() -> {
            String clusterId = clusterRuns.remove(runId);
            if (clusterId != null) {
                clusterStore.get(clusterId).ifPresent(this::releaseEnded);
            }
        });
    }

    /**
     * A command handed to its thread: its job, what sets its cluster up, its cluster, and the reset and the teardowns
     * it was handed off after.
     */
    private record CommandLaunch(SparkJobRunner.Job job, ClusterPreparation preparation, String clusterId,
                                 long launchedIn, long teardownsBefore) {
    }

    // A command's launch, under the service's lock, its cluster keeping its storage from now; null where the cluster
    // has shut down or a teardown has begun, which would stop it at once.
    private CommandLaunch commandLaunch(String clusterId, String runId, String role, List<String> command,
                                        Map<String, String> environment, Duration limit) {
        EmrCluster cluster = requireCluster(clusterId);
        if (tornDown || shutDownAt(cluster, now()) != null) {
            return null;
        }
        String accountId = regionResolver.getAccountId();
        String region = regionOf(cluster);
        OnCluster onCluster = onCluster(cluster, accountId, region, true);
        clusterStore.put(cluster.getId(), cluster);
        SparkJobRunner.Job job = new SparkJobRunner.Job(SERVICE, runId, accountId, region,
                cluster.getReleaseLabel(), SparkJobRunner.Engine.SPARK, onCluster.image(), null, dockerNetwork,
                role != null ? role : instanceProfileRole(cluster, accountId),
                EmrSparkSteps.sparkDefaults(cluster.getConfigurations(), SNAPSHOT_MAPPER), List.of(), command,
                limit, null, null, environment, List.of(), onCluster.storage(),
                EmrSparkSteps.coreSite(cluster.getConfigurations(), SNAPSHOT_MAPPER),
                EmrSparkSteps.sparkLog4j2(cluster.getConfigurations(), SNAPSHOT_MAPPER),
                EmrSparkSteps.mapredSite(cluster.getConfigurations(), SNAPSHOT_MAPPER),
                EmrSparkSteps.hadoopLog4j(cluster.getConfigurations(), SNAPSHOT_MAPPER),
                EmrSparkSteps.hadoopEnvironment(cluster.getConfigurations(), SNAPSHOT_MAPPER), null);
        ClusterPreparation preparation = new ClusterPreparation(cluster.getId(), accountId, region,
                onCluster.releaseImage(), onCluster.hdfs(), onCluster.primary());
        clusterRuns.put(runId, cluster.getId());
        return new CommandLaunch(job, preparation, cluster.getId(), generation, teardowns.get());
    }

    private static String reasonOf(RuntimeException e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    /**
     * The web interfaces of a cluster's primary node an on-cluster application UI link opens, by port, and the name
     * its daemons write into the links of their pages, the node's private DNS name.
     */
    public record WebInterfaces(String primaryHost, Set<Integer> ports) {
    }

    /**
     * The web interfaces Floci serves of a cluster's primary node, where it runs YARN there, in the docker runner
     * mode: its ResourceManager, its MapReduce JobHistory server, where the node works its NodeManager, on a cluster
     * with Spark its Spark History Server, and on one with Hadoop its YARN timeline server, and with Tez its Tez UI;
     * empty elsewhere.
     */
    public Optional<WebInterfaces> webInterfaces(EmrCluster cluster) {
        if (!runsOnClusters() || !runsYarn(cluster)) {
            return Optional.empty();
        }
        Set<Integer> ports = new LinkedHashSet<>(List.of(EmrClusterStorage.RESOURCE_MANAGER_WEB_PORT,
                EmrServerlessSparkRunner.JOB_HISTORY_WEB_PORT));
        ClusterStorage.Yarn yarn = yarnOf(cluster);
        if (yarn.primaryWorks()) {
            ports.add(EmrClusterStorage.NODE_MANAGER_WEB_PORT);
        }
        if (yarn.sparkHistory()) {
            ports.add(EmrServerlessSparkRunner.SPARK_HISTORY_WEB_PORT);
        }
        if (yarn.timelineServer()) {
            ports.add(EmrClusterStorage.TIMELINE_WEB_PORT);
        }
        if (yarn.tezUi()) {
            ports.add(EmrClusterStorage.TEZ_UI_PORT);
        }
        return Optional.of(new WebInterfaces(masterPrivateDnsName(cluster), Set.copyOf(ports)));
    }

    /** Where Floci reaches a web interface's port on the cluster's primary node, once the node is ready. */
    public Optional<ContainerLifecycleManager.EndpointInfo> webInterface(String clusterId, int port) {
        return clusterStorage == null ? Optional.empty() : clusterStorage.webInterface(clusterId, port);
    }

    /**
     * A cluster's Trino as clients outside the cluster reach it: the cluster's account, Region, id and state, and the
     * port Trino listens on.
     */
    public record TrinoCluster(String accountId, String region, String clusterId, String state, int port) {
    }

    /**
     * The cluster of that id, whichever account and Region holds it, as the host clients reach its Trino at names it
     * ({@link EmrTrino#address}); empty where there is none, or it runs no Trino of Floci's. Looked up by its id in
     * each account that holds clusters, not by a scan, as every request to its Trino asks.
     */
    public Optional<TrinoCluster> trinoCluster(String clusterId) {
        AccountAwareStorageBackend.AccountEntry<EmrCluster> entry = call(() -> clusterStore
                .findAllAccountEntries(clusterId).stream().findFirst().orElse(null));
        if (entry == null || entry.value().getRegion() == null) {
            return Optional.empty();
        }
        String accountId = entry.accountId();
        String region = entry.value().getRegion();
        return RequestScopes.callAs(accountId, region, () -> findDescribedCluster(entry.key()))
                .filter(this::runsTrino)
                .map(cluster -> new TrinoCluster(accountId, region, cluster.getId(), cluster.getState(),
                        EmrTrino.port(EmrSparkSteps.trinoConfig(cluster.getConfigurations(), SNAPSHOT_MAPPER))));
    }

    /** Where Floci reaches the cluster's Trino on {@code port}, once it has answered queries. */
    public Optional<ContainerLifecycleManager.EndpointInfo> trinoInterface(String clusterId, int port) {
        return clusterStorage == null ? Optional.empty() : clusterStorage.trinoInterface(clusterId, port);
    }

    /**
     * Starts the cluster's primary node, its HDFS and YARN, and its Trino where it runs Floci's, as its first step
     * would, by running nothing on it ({@link #runOnCluster}), unless such a start is under way already: an
     * application UI link, or a client of its Trino, may ask for it before any step has run, and a cluster with Trino
     * starts it as it becomes ready.
     */
    public void startPrimaryNode(String clusterId) {
        String key = regionResolver.getAccountId() + "/" + clusterId;
        if (!primaryNodeStarts.add(key)) {
            return;
        }
        runOnCluster(clusterId, "ui-" + clusterId + "-" + primaryNodeStartCount.incrementAndGet(), null,
                List.of("true"), Map.of(), PRIMARY_NODE_START, new ClusterRun() {
                    @Override
                    public void started() {
                        // The node is ready before the command's container starts.
                    }

                    @Override
                    public void ended(SparkJobRunner.Outcome outcome) {
                        primaryNodeStarts.remove(key);
                        if (outcome.failure() != null) {
                            LOG.warnv("Could not start the primary node of EMR cluster {0}: {1}", clusterId,
                                    outcome.failure());
                        }
                    }

                    @Override
                    public boolean cancelled() {
                        return false;
                    }
                });
    }

    /**
     * Whether a cluster that has ended still has its kept history, which its history interfaces show for
     * {@link #HISTORY_KEPT} after its end, once its storage has gone: until then its primary node may still hold the
     * timeline server's store, and its event logs are not yet copied.
     */
    public boolean historyKept(EmrCluster cluster) {
        return clusterStorage != null && releaseImages != null && EmrClusterLifecycle.isTerminated(cluster.getState())
                && !cluster.isStorageCreated() && cluster.getEndDateTime() != null
                && now().isBefore(cluster.getEndDateTime().plus(HISTORY_KEPT))
                && clusterStorage.historyKept(cluster.getId());
    }

    /**
     * Whether a cluster that is ending, or has ended with its storage not yet gone, will have its kept history once
     * the storage has gone, as {@link #historyKept} has it then.
     */
    public boolean historyComing(EmrCluster cluster) {
        return clusterStorage != null && releaseImages != null && cluster.isStorageCreated()
                && (EmrClusterLifecycle.TERMINATING.equals(cluster.getState())
                        || EmrClusterLifecycle.isTerminated(cluster.getState()))
                && (cluster.getEndDateTime() == null || now().isBefore(cluster.getEndDateTime().plus(HISTORY_KEPT)))
                && clusterStorage.historyKept(cluster.getId());
    }

    /**
     * The history interfaces of a cluster that has ended, as its primary node ran them: its Spark History Server on a
     * cluster with Spark, its YARN timeline server on one with Hadoop, and its Tez UI on one with Tez.
     */
    public WebInterfaces historyInterfaces(EmrCluster cluster) {
        ClusterStorage.Yarn yarn = yarnOf(cluster);
        Set<Integer> ports = new LinkedHashSet<>();
        if (yarn.sparkHistory()) {
            ports.add(EmrServerlessSparkRunner.SPARK_HISTORY_WEB_PORT);
        }
        if (yarn.timelineServer()) {
            ports.add(EmrClusterStorage.TIMELINE_WEB_PORT);
        }
        if (yarn.tezUi()) {
            ports.add(EmrClusterStorage.TEZ_UI_PORT);
        }
        return new WebInterfaces(masterPrivateDnsName(cluster), Set.copyOf(ports));
    }

    /**
     * Where Floci reaches one of the history interfaces of a cluster that has ended, its kept history served by a
     * container started in the image its steps ran in when it is not running; empty until it answers.
     */
    public Optional<ContainerLifecycleManager.EndpointInfo> historyInterface(EmrCluster cluster, int port) {
        Supplier<String> image = releaseImage(cluster);
        if (clusterStorage == null || image == null) {
            return Optional.empty();
        }
        return clusterStorage.historyInterface(cluster.getId(), regionResolver.getAccountId(), regionOf(cluster),
                image, releaseImages::released, masterPrivateDnsName(cluster), yarnOf(cluster), port);
    }

    /** Stops the container serving a cluster's kept history, which a later link starts again. */
    public void stopHistory(String clusterId) {
        if (clusterStorage != null) {
            clusterStorage.stopHistory(clusterId);
        }
    }

    /** Removes the kept histories of the clusters that are gone, or ended more than {@link #HISTORY_KEPT} ago. */
    public void removeExpiredHistories() {
        if (clusterStorage != null) {
            clusterStorage.removeHistoriesBut(historiesKept());
        }
    }

    // The clusters, of every account, whose history is kept: those up, and those that ended within HISTORY_KEPT.
    private Set<String> historiesKept() {
        Instant cutoff = now().minus(HISTORY_KEPT);
        Set<String> kept = new HashSet<>();
        for (AccountAwareStorageBackend.AccountEntry<EmrCluster> entry
                : clusterStore.scanAllAccountEntries(k -> true)) {
            EmrCluster cluster = entry.value();
            if (!EmrClusterLifecycle.isTerminated(cluster.getState())
                    || cluster.getEndDateTime() == null || cluster.getEndDateTime().isAfter(cutoff)) {
                kept.add(cluster.getId());
            }
        }
        return kept;
    }

    /** Stops a command {@link #runOnCluster} runs, which then ends as stopped. */
    public void stopOnCluster(String runId) {
        if (sparkRunner != null) {
            sparkRunner.stop(runId);
        }
    }

    // A command's thread on its cluster, as a step's: its setup, its run, then its caller told, and what its cluster
    // kept released once nothing of it runs any more.
    private void runCommand(CommandLaunch launch, ClusterRun run) {
        SparkJobRunner.Job job = launch.job();
        ClusterPreparation preparation = launch.preparation();
        SparkJobRunner.Outcome outcome;
        SparkJobRunner.Progress progress = new SparkJobRunner.Progress() {
            @Override
            public void started() {
                RequestScopes.runAs(job.accountId(), job.region(), run::started);
            }

            @Override
            public void timedOut() {
                // The outcome says so.
            }

            // Past starting, or going on, once a teardown has begun, a reset has removed it, its cluster has shut down,
            // or its caller has stopped it.
            @Override
            public boolean cancelled() {
                return teardowns.get() != launch.teardownsBefore()
                        || !RequestScopes.callAs(job.accountId(), job.region(), () -> commandGoing(launch))
                        || RequestScopes.callAs(job.accountId(), job.region(), run::cancelled);
            }
        };
        try {
            outcome = sparkRunner.run(preparation.prepare(job, progress::cancelled), progress);
        } catch (RuntimeException e) {
            outcome = progress.cancelled() ? SparkJobRunner.Outcome.halted()
                    : SparkJobRunner.Outcome.couldNotStart(reasonOf(e));
        } finally {
            preparation.release();
        }
        SparkJobRunner.Outcome ended = outcome;
        try {
            RequestScopes.runAs(job.accountId(), job.region(), () -> run.ended(ended));
        } finally {
            RequestScopes.runAs(job.accountId(), job.region(), () -> forgetCommand(job.runId()));
        }
    }

    /**
     * Runs a session's Spark Connect server on its cluster, as a step runs, a YARN application there: its driver in a
     * container on the cluster, in the image its steps run in, reached through Floci. Its {@code spark-defaults} are
     * the cluster's, then the session's engine configurations, "applied to the Spark Connect Server for that session
     * only", with the server's port; it runs as the session's runtime role, else the cluster's instance profile. The
     * server is in Spark's own jars on the AWS runtime for Apache Spark, which sessions need.
     */
    private void launchSessionServer(EmrCluster cluster, EmrSession session) {
        String accountId = regionResolver.getAccountId();
        String region = regionOf(cluster);
        OnCluster onCluster = onCluster(cluster, accountId, region, true);
        // The cluster keeps its storage from now, which its end releases once its servers have gone.
        clusterStore.put(cluster.getId(), cluster);
        Map<String, String> sparkDefaults = new LinkedHashMap<>(
                EmrSparkSteps.sparkDefaults(cluster.getConfigurations(), SNAPSHOT_MAPPER));
        if (session.getEngineConfigurations() != null) {
            sparkDefaults.putAll(EmrSparkSteps.sparkDefaults(session.getEngineConfigurations(), SNAPSHOT_MAPPER));
        }
        sparkDefaults.put("spark.connect.grpc.binding.port", String.valueOf(EmrSessions.SPARK_CONNECT_PORT));
        String role = session.getExecutionRoleArn() != null ? session.getExecutionRoleArn()
                : instanceProfileRole(cluster, accountId);
        SparkJobRunner.Job job = new SparkJobRunner.Job(SERVICE, session.getId(), accountId, region,
                cluster.getReleaseLabel(), SparkJobRunner.Engine.SPARK, onCluster.image(), null, dockerNetwork, role,
                sparkDefaults, List.of("--class", EmrSessions.SPARK_CONNECT_SERVER, "spark-internal"), null,
                Duration.ZERO, null, null, Map.of(), List.of(), onCluster.storage(),
                EmrSparkSteps.coreSite(cluster.getConfigurations(), SNAPSHOT_MAPPER),
                EmrSparkSteps.sparkLog4j2(cluster.getConfigurations(), SNAPSHOT_MAPPER),
                EmrSparkSteps.mapredSite(cluster.getConfigurations(), SNAPSHOT_MAPPER),
                EmrSparkSteps.hadoopLog4j(cluster.getConfigurations(), SNAPSHOT_MAPPER),
                EmrSparkSteps.hadoopEnvironment(cluster.getConfigurations(), SNAPSHOT_MAPPER), null)
                .listeningOn(EmrSessions.SPARK_CONNECT_PORT);
        sessionServers.launch(job, new ClusterPreparation(cluster.getId(), accountId, region,
                onCluster.releaseImage(), onCluster.hdfs(), onCluster.primary()), sessionOwner);
    }

    /**
     * The cluster's sessions, as stored, oldest first, once the cluster and each of them is brought up to date: kept
     * apart from the cluster, so that its sessions, ended ones included, weigh on no other read or write of it.
     */
    private List<EmrSession> settledSessions(EmrCluster cluster) {
        settle(cluster);
        Instant now = now();
        List<EmrSession> sessions = sessionStore.scan(key -> true).stream()
                .filter(session -> cluster.getId().equals(session.getClusterId()))
                .sorted(Comparator.comparingLong(EmrSession::getSequence))
                .toList();
        for (EmrSession session : sessions) {
            if (session.isContainerSession() && !EmrSessions.ended(session)
                    && !sessionServers.runs(session.getId())) {
                // Its thread ends it; without one, Floci stopped while its server ran, and nothing is left to. A
                // server container the startup's cleanup left behind goes now.
                boolean terminating = EmrSessions.TERMINATING.equals(session.getState());
                EmrSessions.end(session, terminating ? EmrSessions.TERMINATED : EmrSessions.FAILED,
                        terminating ? EmrSessions.USER_REQUEST : EmrSessions.INTERRUPTED, now);
                sessionStore.put(session.getId(), session);
                if (sparkRunner != null) {
                    sparkRunner.removeLeftoverDriver(SERVICE, session.getId());
                }
            } else if (EmrSessions.settle(cluster, session, now)) {
                sessionStore.put(session.getId(), session);
                // An ended session's server goes with it, its tokens and its check.
                if (EmrSessions.ended(session)) {
                    sessionServers.stop(session.getId());
                }
            }
        }
        return sessions;
    }

    /**
     * What a retry must ask for: the cluster and the session's parameters as the session would hold them, a member
     * left out, given as null or empty, or at its default, alike.
     */
    private static String sessionParameters(String clusterId, EmrSessions.Request request) {
        ObjectNode parameters = SNAPSHOT_MAPPER.createObjectNode();
        parameters.put("ClusterId", clusterId);
        parameters.put("Name", request.name());
        parameters.put("ExecutionRoleArn", request.executionRoleArn());
        parameters.set("EngineConfigurations", rawJson(request.engineConfigurations()));
        parameters.set("MonitoringConfiguration", rawJson(request.monitoringConfiguration()));
        parameters.put("SessionIdleTimeoutInMinutes", request.idleTimeoutMinutes());
        parameters.set("Tags", SNAPSHOT_MAPPER.valueToTree(request.tags() != null ? request.tags() : Map.of()));
        return parameters.toString();
    }

    private static boolean isJsonObject(String json) {
        try {
            return SNAPSHOT_MAPPER.readTree(json).isObject();
        } catch (JsonProcessingException notJson) {
            return false;
        }
    }

    private static JsonNode rawJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return SNAPSHOT_MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read a session's stored configuration", e);
        }
    }

    // Whether a retry's parameters are the original's, member for member whatever their order.
    private static boolean sameRequest(String stored, String retried) {
        return Objects.equals(rawJson(stored), rawJson(retried));
    }

    /**
     * Why a session takes no Spark Connect call now, or null while it does, IDLE or BUSY. No source gives AWS's answer
     * for a session that has not started or has ended, so the wording is Floci's.
     */
    private String sessionRefusal(String sessionId) {
        return call(() -> {
            EmrSession stored = sessionStore.get(sessionId).orElse(null);
            EmrCluster cluster = stored != null ? clusterStore.get(stored.getClusterId()).orElse(null) : null;
            if (stored == null || cluster == null) {
                return String.format(EmrSessions.NOT_READY, sessionId, EmrSessions.TERMINATED);
            }
            EmrSession session = settledSessions(cluster).stream()
                    .filter(candidate -> sessionId.equals(candidate.getId())).findFirst().orElse(stored);
            if (!session.isContainerSession()) {
                return String.format(EmrSessions.NO_SERVER, sessionId);
            }
            boolean ready = EmrSessions.IDLE.equals(session.getState())
                    || EmrSessions.BUSY.equals(session.getState());
            return ready ? null : String.format(EmrSessions.NOT_READY, sessionId, session.getState());
        });
    }

    // Checks an idle session again once its idle timeout would have passed, so it ends then, its server with it; a busy
    // one has no deadline, so its check goes.
    private void checkSessionLater(EmrSession session) {
        if (session.getIdleSince() == null || session.getSessionIdleTimeoutInMinutes() <= 0) {
            sessionServers.cancelCheck(session.getId());
            return;
        }
        String clusterId = session.getClusterId();
        sessionServers.checkAt(session.getId(),
                session.getIdleSince().plus(Duration.ofMinutes(session.getSessionIdleTimeoutInMinutes())),
                () -> run(() -> clusterStore.get(clusterId).ifPresent(this::settledSessions)));
    }

    /** The service as its sessions' servers' owner ({@link SparkConnectSessionOwner}), under its lock. */
    private final class SessionOwner extends SparkConnectSessionOwner<EmrSession> {

        @Override
        protected <T> T locked(Supplier<T> body) {
            return call(body);
        }

        @Override
        protected Optional<EmrSession> find(String sessionId) {
            return sessionStore.get(sessionId);
        }

        @Override
        protected SparkConnectSession session(EmrSession stored) {
            return stored;
        }

        @Override
        protected void save(EmrSession stored) {
            sessionStore.put(stored.getId(), stored);
        }

        @Override
        protected Instant now() {
            return EmrService.this.now();
        }

        @Override
        protected void end(EmrSession stored, String state, String reason) {
            EmrSessions.end(stored, state, reason, now());
            sessionStore.put(stored.getId(), stored);
        }

        /**
         * The session's ServerUrl, "The Spark Connect server URL for the session", which a client uses "with the
         * Credentials returned by GetSessionEndpoint to connect directly to the session": Floci's own address in Spark
         * Connect's {@code sc://} form, where {@link SparkConnectProxy} takes a call carrying GetSessionEndpoint's token
         * on to the session's server, so that no client reaches the server without one. The model gives no example of
         * AWS's.
         */
        @Override
        protected void ready(EmrSession stored) {
            URI base = URI.create(baseUrl);
            int port = base.getPort() != -1 ? base.getPort() : "https".equals(base.getScheme()) ? 443 : 80;
            stored.setServerUrl("sc://" + base.getHost() + ":" + port);
        }

        // The reason it has, as TerminateSession or its cluster's end gave it.
        @Override
        protected String endReason(EmrSession stored, SparkJobRunner.Outcome outcome) {
            return stored.getStateChangeReason();
        }

        @Override
        protected void checkLater(EmrSession stored) {
            checkSessionLater(stored);
        }

        @Override
        public String refusal(String sessionId) {
            return sessionRefusal(sessionId);
        }

        @Override
        public void gone(String sessionId) {
            // What the session's cluster kept may go now that its server no longer holds it.
            run(() -> sessionStore.get(sessionId).flatMap(session -> clusterStore.get(session.getClusterId()))
                    .ifPresent(EmrService.this::releaseEnded));
        }
    }

    private static EmrSession sessionCopy(EmrSession session) {
        return SNAPSHOT_MAPPER.convertValue(session, EmrSession.class);
    }

    // ──────────────────────────── Instance groups / fleets ────────────────────────────

    public List<String> addInstanceGroups(String clusterId, List<EmrInstanceGroup> groups) {
        return call(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            rejectShutDownCluster(cluster, EmrService::jobFlowShutDownValidation);
            if (!cluster.getInstanceFleets().isEmpty()) {
                throw new AwsException("ValidationException", fleetsAndGroupsApart(clusterId, "instance fleets"), 400);
            }
            List<String> ids = new ArrayList<>();
            for (EmrInstanceGroup group : groups) {
                group.setId("ig-" + randomId(13));
                // As the cluster's others are while it is as it is.
                EmrClusterLifecycle.groupIn(group, EmrClusterLifecycle.instancesState(cluster.getState()));
                cluster.getInstanceGroups().add(group);
                ids.add(group.getId());
            }
            clusterStore.put(clusterId, cluster);
            resizeNodes(cluster);
            return ids;
        });
    }

    /**
     * One InstanceGroupModifyConfig: a null count or configurations leaves that setting as it is, a null
     * reconfiguration type is OVERWRITE, and {@code instancesToTerminate} are EC2InstanceIdsToTerminate, none for null.
     */
    public record InstanceGroupModification(String instanceGroupId, Integer instanceCount, String configurations,
                                            String reconfigurationType, List<String> instancesToTerminate,
                                            JsonNode shrinkPolicy) {

        public InstanceGroupModification(String instanceGroupId, Integer instanceCount, String configurations) {
            this(instanceGroupId, instanceCount, configurations, null, null, null);
        }

        public InstanceGroupModification(String instanceGroupId, Integer instanceCount, String configurations,
                                         String reconfigurationType) {
            this(instanceGroupId, instanceCount, configurations, reconfigurationType, null, null);
        }

        public InstanceGroupModification(String instanceGroupId, Integer instanceCount, String configurations,
                                         String reconfigurationType, List<String> instancesToTerminate) {
            this(instanceGroupId, instanceCount, configurations, reconfigurationType, instancesToTerminate, null);
        }
    }

    /**
     * Resizes or reconfigures instance groups in place. ClusterId is optional in the request, so
     * without it each group is looked up across the account's clusters in the Region. Every modification is
     * checked before any is applied. A group's EC2InstanceIdsToTerminate end those of its instances: "After you
     * terminate the instances, the instance group will not return to its original requested size", so its requested
     * count goes down by as many, and the others keep their ids, addresses and nodes. An InstanceCount beside them is
     * the group's size after that.
     */
    public void modifyInstanceGroups(String clusterId, String region, List<InstanceGroupModification> modifications) {
        run(() -> {
            if (modifications.isEmpty()) {
                throw invalid("InstanceGroups must contain at least one instance group.");
            }
            Map<String, EmrCluster> touched = new LinkedHashMap<>();
            List<EmrInstanceGroup> groups = new ArrayList<>();
            List<List<Integer>> terminations = new ArrayList<>();
            Map<String, Set<Integer>> endings = new HashMap<>();
            for (int position = 1; position <= modifications.size(); position++) {
                InstanceGroupModification mod = modifications.get(position - 1);
                EmrCluster found = clusterId != null
                        ? requireCluster(clusterId) : clusterOwning(region, mod.instanceGroupId());
                // AWS's answer here, recorded by Terraform users
                // (hashicorp/terraform-provider-aws#1355 and #9400).
                if (!runsOrWaits(found)) {
                    throw new AwsException("ValidationException",
                            "An instance group may only be modified when the cluster is running or waiting.", 400);
                }
                // One copy per cluster, so two groups of the same cluster are changed and stored together.
                EmrCluster cluster = touched.computeIfAbsent(found.getId(), id -> found);
                EmrInstanceGroup group = requireInstanceGroup(cluster, mod.instanceGroupId());
                checkReconfigurationType(mod.reconfigurationType(), position, cluster.getReleaseLabel());
                if (mod.instanceCount() != null) {
                    if (mod.instanceCount() < 0) {
                        throw invalid("InstanceCount must not be negative.");
                    }
                    if ("MASTER".equals(group.getInstanceGroupType())
                            && mod.instanceCount() != group.getRequestedInstanceCount()) {
                        throw invalid("The instance count of a master instance group cannot be modified.");
                    }
                }
                List<Integer> named = ordinalsToTerminate(cluster, group, mod.instancesToTerminate());
                List<Integer> ending = new ArrayList<>(named);
                // What the request's earlier entries for the same group end, so a shrink counts from what they leave.
                Set<Integer> endingOfGroup = endings.computeIfAbsent(cluster.getId() + "/" + group.getId(),
                        key -> new LinkedHashSet<>());
                endingOfGroup.addAll(named);
                if (mod.instanceCount() != null && mod.shrinkPolicy() != null) {
                    List<Integer> shrink = shrinkOrdinals(cluster, group, mod.instanceCount(), mod.shrinkPolicy(),
                            endingOfGroup);
                    ending.addAll(shrink);
                    endingOfGroup.addAll(shrink);
                }
                terminations.add(ending);
                groups.add(group);
            }
            for (int i = 0; i < modifications.size(); i++) {
                InstanceGroupModification mod = modifications.get(i);
                EmrInstanceGroup group = groups.get(i);
                // Those another entry for the same group has not ended already.
                List<Integer> ended = terminations.get(i).stream()
                        .filter(ordinal -> !group.getTerminatedOrdinals().contains(ordinal)).toList();
                if (!ended.isEmpty()) {
                    List<Integer> ordinals = new ArrayList<>(group.getTerminatedOrdinals());
                    ordinals.addAll(ended);
                    Collections.sort(ordinals);
                    group.setTerminatedOrdinals(ordinals);
                    group.setRequestedInstanceCount(Math.max(0, group.getRequestedInstanceCount() - ended.size()));
                    group.setRunningInstanceCount(Math.max(0, group.getRunningInstanceCount() - ended.size()));
                }
                if (mod.instanceCount() != null) {
                    group.setRequestedInstanceCount(mod.instanceCount());
                    group.setRunningInstanceCount(mod.instanceCount());
                }
                if (mod.shrinkPolicy() != null) {
                    group.setShrinkPolicy(mod.shrinkPolicy().toString());
                }
                if (mod.configurations() != null) {
                    group.setConfigurations(MERGE.equals(mod.reconfigurationType())
                            ? mergedConfigurations(group.getConfigurations(), mod.configurations())
                            : mod.configurations());
                    group.setConfigurationsVersion(group.getConfigurationsVersion() + 1);
                }
            }
            touched.forEach(clusterStore::put);
            touched.values().forEach(this::resizeNodes);
        });
    }

    /**
     * The ordinals of the group's instances that {@code instanceIds} name, each one of its instances now as
     * ListInstances gives it; none for no ids. A master group's are refused as its count is, and an id that is not one
     * of the group's instances with ValidationException, as ModifyInstanceGroups' other refusals are recorded; no
     * source gives AWS's wording, so it is Floci's.
     */
    private static List<Integer> ordinalsToTerminate(EmrCluster cluster, EmrInstanceGroup group,
                                                     List<String> instanceIds) {
        if (instanceIds == null || instanceIds.isEmpty()) {
            return List.of();
        }
        if ("MASTER".equals(group.getInstanceGroupType())) {
            throw invalid("The instance count of a master instance group cannot be modified.");
        }
        Map<String, Integer> current = runningOrdinals(cluster, group);
        List<Integer> ordinals = new ArrayList<>();
        for (String instanceId : new LinkedHashSet<>(instanceIds)) {
            Integer ordinal = current.get(instanceId);
            if (ordinal == null) {
                throw new AwsException("ValidationException", "Instance " + instanceId
                        + " is not a running instance of instance group " + group.getId() + ".", 400);
            }
            ordinals.add(ordinal);
        }
        return ordinals;
    }

    /** The group's instances as ListInstances gives them now: each EC2 instance id and its ordinal, in that order. */
    private static Map<String, Integer> runningOrdinals(EmrCluster cluster, EmrInstanceGroup group) {
        List<EmrHandler.ListedInstances> listed = EmrHandler.listedInstances(cluster, false);
        Map<String, Integer> current = new LinkedHashMap<>();
        for (EmrHandler.ListedInstances set : listed) {
            if (group.getId().equals(set.groupId())) {
                long first = firstIndex(listed, set);
                PrimitiveIterator.OfLong ordinals = set.ordinalsFrom(1);
                for (long k = 1; k <= set.count(); k++) {
                    long ordinal = ordinals.nextLong();
                    current.put(ec2InstanceId(first + ordinal - 1), (int) ordinal);
                }
            }
        }
        return current;
    }

    /**
     * The ordinals a shrink to {@code count} ends under a ShrinkPolicy's InstanceResizePolicy, beyond those already
     * {@code ending}, by EC2InstanceIdsToTerminate or the request's earlier entries for the group: first its "Specific
     * list of instances to be terminated when shrinking an instance group", then the group's last instances, passing
     * its "Specific list of instances to be protected when shrinking an instance group". Ended this way, as named
     * instances are, their ids are not taken again. An id to terminate that is not one of the group's running
     * instances is refused, as EC2InstanceIdsToTerminate refuses it, and so is a shrink the protected instances leave
     * short, rather than ending one of them. No source gives AWS's answer to either, so the wording is Floci's.
     */
    private static List<Integer> shrinkOrdinals(EmrCluster cluster, EmrInstanceGroup group, int count,
                                                JsonNode shrinkPolicy, Set<Integer> ending) {
        Map<String, Integer> running = runningOrdinals(cluster, group);
        int shrinkBy = running.size() - ending.size() - count;
        JsonNode resize = shrinkPolicy.path("InstanceResizePolicy");
        if (shrinkBy <= 0 || !resize.isObject()) {
            return List.of();
        }
        Set<String> protect = new HashSet<>();
        resize.path("InstancesToProtect").forEach(id -> protect.add(id.asText()));
        List<Integer> chosen = new ArrayList<>();
        for (JsonNode id : resize.path("InstancesToTerminate")) {
            Integer ordinal = running.get(id.asText());
            if (ordinal == null) {
                throw new AwsException("ValidationException", "Instance " + id.asText()
                        + " is not a running instance of instance group " + group.getId() + ".", 400);
            }
            if (chosen.size() < shrinkBy && !ending.contains(ordinal) && !chosen.contains(ordinal)) {
                chosen.add(ordinal);
            }
        }
        List<Map.Entry<String, Integer>> fromTheEnd = new ArrayList<>(running.entrySet());
        Collections.reverse(fromTheEnd);
        for (Map.Entry<String, Integer> instance : fromTheEnd) {
            if (chosen.size() >= shrinkBy) {
                break;
            }
            Integer ordinal = instance.getValue();
            if (!protect.contains(instance.getKey()) && !ending.contains(ordinal) && !chosen.contains(ordinal)) {
                chosen.add(ordinal);
            }
        }
        if (chosen.size() < shrinkBy) {
            throw new AwsException("ValidationException", "Instance group " + group.getId() + " cannot shrink to "
                    + count + " instances without ending one its shrink policy protects.", 400);
        }
        return chosen;
    }

    /**
     * The index, in the cluster's instance numbering, of the first place {@code set} takes: 1 for the primary node's
     * group or fleet, and after the places of those before it for the others.
     */
    static long firstIndex(List<EmrHandler.ListedInstances> listed, EmrHandler.ListedInstances set) {
        EmrHandler.ListedInstances primary = listed.stream().filter(other -> other.number() == 0).findFirst()
                .orElse(null);
        if (set == primary) {
            return 1;
        }
        long next = primary == null ? 1 : 1 + primary.span();
        for (EmrHandler.ListedInstances other : listed) {
            if (other == set) {
                return next;
            }
            if (other != primary) {
                next += other.span();
            }
        }
        throw new IllegalArgumentException("The instances are not the cluster's");
    }

    /**
     * A reconfiguration type is OVERWRITE or MERGE, the model's enum. MERGE is "supported for Amazon EMR releases
     * 5.35.0 and 6.6.0 and later", and OVERWRITE is "the only one available in Amazon EMR releases earlier than 5.35.0
     * and 6.6.0" (Release Guide, "Reconfigure an instance group in a running cluster"). No source gives AWS's refusal
     * of MERGE on an earlier release, so its wording is Floci's.
     */
    private static void checkReconfigurationType(String type, int position, String releaseLabel) {
        if (type == null) {
            return;
        }
        if (!RECONFIGURATION_TYPES.contains(type)) {
            throw SmithyValidationErrors.validationException(SmithyValidationErrors.notInEnum(type,
                    "instanceGroups." + position + ".member.reconfigurationType", RECONFIGURATION_TYPES));
        }
        boolean merges = releaseAtLeast(releaseLabel, 6, 6, 0)
                || (releaseAtLeast(releaseLabel, 5, 35, 0) && !releaseAtLeast(releaseLabel, 6, 0, 0));
        if (MERGE.equals(type) && !merges) {
            throw new AwsException("ValidationException", "MERGE reconfiguration is supported for Amazon EMR releases"
                    + " 5.35.0 and 6.6.0 and later, and the cluster runs " + releaseLabel + ".", 400);
        }
    }

    /**
     * An instance group's configurations after a MERGE reconfiguration: it "merges the newly submitted configurations
     * with configurations that already exist on the cluster. This option only adds or modifies the new configurations
     * that you submit. It preserves existing configurations." A classification it names takes its properties over the
     * existing ones of that classification, and its nested configurations merged the same way; one it does not name
     * stays, and a new one is added.
     */
    static String mergedConfigurations(String existing, String added) {
        try {
            ArrayNode merged = existing != null && SNAPSHOT_MAPPER.readTree(existing) instanceof ArrayNode held
                    ? held : SNAPSHOT_MAPPER.createArrayNode();
            return merged(merged, (ArrayNode) SNAPSHOT_MAPPER.readTree(added)).toString();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored configurations are not JSON", e);
        }
    }

    private static ArrayNode merged(ArrayNode existing, ArrayNode added) {
        ArrayNode merged = existing.deepCopy();
        for (JsonNode configuration : added) {
            String classification = configuration.path("Classification").asText(null);
            ObjectNode match = null;
            for (JsonNode held : merged) {
                if (classification != null && held instanceof ObjectNode object
                        && classification.equals(held.path("Classification").asText(null))) {
                    match = object;
                }
            }
            if (match == null || !configuration.isObject()) {
                merged.add(configuration.deepCopy());
                continue;
            }
            if (configuration.path("Properties").isObject()) {
                JsonNode properties = match.path("Properties");
                ObjectNode into = properties.isObject() ? (ObjectNode) properties : match.putObject("Properties");
                into.setAll((ObjectNode) configuration.get("Properties").deepCopy());
            }
            if (configuration.path("Configurations").isArray()) {
                JsonNode nested = match.path("Configurations");
                match.set("Configurations", merged(nested.isArray() ? (ArrayNode) nested
                        : SNAPSHOT_MAPPER.createArrayNode(), (ArrayNode) configuration.get("Configurations")));
            }
        }
        return merged;
    }

    @Override
    public String namespace() {
        return "elasticmapreduce";
    }

    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    /**
     * The cluster an instance group a {@code ModifyInstanceGroups} names by its id alone is in, the one
     * {@link #modifyInstanceGroups} finds it in and changes it there, read under the service's lock, as
     * {@code addInstanceGroups} adds to a cluster's groups; and the cluster a tag call's {@code ResourceId} names.
     */
    @Override
    public List<String> through(String member, String value, String region) {
        return switch (member) {
            case "InstanceGroups[].InstanceGroupId" ->
                    call(() -> clusterWithGroup(region, value).map(EmrCluster::getClusterArn).stream().toList());
            // A tag call's ResourceId is a cluster's id, or a Studio's: the Studio, or the cluster the tag handlers
            // find by it (findCluster), one recorded with no Region included.
            case "ResourceId" -> EmrStudioService.isStudioId(value)
                    ? studioService().map(service -> service.studioArn(value, region)).stream()
                            .flatMap(Optional::stream).toList()
                    : call(() -> clusterStore.get(value)
                            .filter(cluster -> cluster.getRegion() == null || cluster.getRegion().equals(region))
                            .map(EmrCluster::getClusterArn).stream().toList());
            default -> List.of();
        };
    }

    public List<EmrInstanceGroup> listInstanceGroups(String clusterId) {
        return call(() -> snapshotOf(requireCluster(clusterId)).getInstanceGroups());
    }

    public String addInstanceFleet(String clusterId, EmrInstanceFleet fleet) {
        return call(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            rejectShutDownCluster(cluster, EmrService::jobFlowShutDown);
            if (!cluster.getInstanceGroups().isEmpty()) {
                throw invalid(fleetsAndGroupsApart(clusterId, "instance groups"));
            }
            fleet.setId("if-" + randomId(13));
            EmrClusterLifecycle.fleetIn(fleet, EmrClusterLifecycle.instancesState(cluster.getState()));
            cluster.getInstanceFleets().add(fleet);
            clusterStore.put(clusterId, cluster);
            resizeNodes(cluster);
            return fleet.getId();
        });
    }

    /**
     * What a ModifyInstanceFleet request changes, each member null when it leaves it as it is: the target capacities,
     * the ResizeSpecifications, the InstanceTypeConfigs (raw JSON) with their instance types, and the Context.
     */
    public record InstanceFleetModification(Integer targetOnDemandCapacity, Integer targetSpotCapacity,
                                            String resizeSpecifications, String instanceTypeConfigs,
                                            List<String> instanceTypes, String context) {
    }

    /**
     * The instance types of {@code types} that {@code named} leaves out, each once, in their order: those a
     * ModifyInstanceFleet request must still name, or that it adds to a fleet's own.
     */
    public static List<String> instanceTypesLeftOut(List<String> types, List<String> named) {
        return types.stream().filter(type -> !named.contains(type)).distinct().toList();
    }

    /** Resizes an instance fleet; a null target leaves that capacity as it is. */
    public void modifyInstanceFleet(String clusterId, String instanceFleetId,
                                    Integer targetOnDemandCapacity, Integer targetSpotCapacity) {
        modifyInstanceFleet(clusterId, instanceFleetId, new InstanceFleetModification(targetOnDemandCapacity,
                targetSpotCapacity, null, null, null, null));
    }

    /**
     * Changes a fleet's target capacities, and the ResizeSpecifications, InstanceTypeConfigs and Context the request
     * gives, which ListInstanceFleets gives back. The configs replace the fleet's own, as the Management Guide's
     * "Reconfiguring instance fleets" has it: "Not including fields can result in overwriting specifications you
     * declared at launch". They must name every instance type the fleet has: "You must include all instance types
     * that are part of your instance fleet in the modify request". A type they add joins the fleet's after its own.
     */
    public void modifyInstanceFleet(String clusterId, String instanceFleetId, InstanceFleetModification modification) {
        Integer targetOnDemandCapacity = modification.targetOnDemandCapacity();
        Integer targetSpotCapacity = modification.targetSpotCapacity();
        run(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            if (instanceFleetId == null) {
                throw invalid("InstanceFleetId is required.");
            }
            // AWS's answer for a cluster terminated a while before, ErrorCode member included
            // (hashicorp/terraform-provider-aws#31375, 2023); its wording covers one shutting down.
            if (isShutDown(cluster)) {
                throw jobFlowShutDown();
            }
            // And for one in any other state but running or waiting, a STARTING or BOOTSTRAPPING one
            // included, ErrorCode member too (hashicorp/terraform-provider-aws#15548, 2020).
            if (!runsOrWaits(cluster)) {
                throw new AwsException("InvalidRequestException",
                        "An instance fleet may only be modified when the cluster is running or waiting.", 400,
                        Map.of("ErrorCode", "MODIFY_INSTANCE_FLEET_OF_RUNNING_JOBFLOW_ONLY"));
            }
            EmrInstanceFleet fleet = cluster.getInstanceFleets().stream()
                    .filter(f -> instanceFleetId.equals(f.getId()))
                    .findFirst()
                    .orElseThrow(() -> invalid("Instance fleet " + instanceFleetId + " is not in cluster "
                            + clusterId + "."));
            int onDemand = targetOnDemandCapacity != null ? targetOnDemandCapacity : fleet.getTargetOnDemandCapacity();
            int spot = targetSpotCapacity != null ? targetSpotCapacity : fleet.getTargetSpotCapacity();
            if (onDemand < 0 || spot < 0) {
                throw invalid("Target capacities must not be negative.");
            }
            if ("MASTER".equals(fleet.getInstanceFleetType())
                    && (onDemand != fleet.getTargetOnDemandCapacity() || spot != fleet.getTargetSpotCapacity())) {
                throw invalid("The target capacity of a master instance fleet cannot be modified.");
            }
            List<String> instanceTypes = null;
            if (modification.instanceTypes() != null) {
                List<String> missing = instanceTypesLeftOut(fleet.getInstanceTypes(), modification.instanceTypes());
                if (!missing.isEmpty()) {
                    throw invalid("The instance type configs must include every instance type of instance fleet "
                            + instanceFleetId + "; missing " + String.join(", ", missing) + ".");
                }
                instanceTypes = new ArrayList<>(fleet.getInstanceTypes());
                instanceTypes.addAll(instanceTypesLeftOut(modification.instanceTypes(), fleet.getInstanceTypes()));
            }
            fleet.setTargetOnDemandCapacity(onDemand);
            fleet.setTargetSpotCapacity(spot);
            fleet.setProvisionedOnDemandCapacity(onDemand);
            fleet.setProvisionedSpotCapacity(spot);
            if (modification.resizeSpecifications() != null) {
                fleet.setResizeSpecifications(modification.resizeSpecifications());
            }
            if (instanceTypes != null) {
                fleet.setInstanceTypeConfigs(modification.instanceTypeConfigs());
                fleet.setInstanceTypes(instanceTypes);
            }
            if (modification.context() != null) {
                fleet.setContext(modification.context());
            }
            clusterStore.put(clusterId, cluster);
            resizeNodes(cluster);
        });
    }

    /** The cluster's bootstrap actions as given to RunJobFlow, raw JSON; null when it had none. */
    public String listBootstrapActions(String clusterId) {
        return call(() -> requireCluster(clusterId).getBootstrapActions());
    }

    public List<EmrInstanceFleet> listInstanceFleets(String clusterId) {
        return call(() -> snapshotOf(requireCluster(clusterId)).getInstanceFleets());
    }

    public EmrCluster getClusterForInstances(String clusterId) {
        return call(() -> snapshotOf(requireCluster(clusterId)));
    }

    // ──────────────────────────── Security configurations ────────────────────────────

    public SecurityConfiguration createSecurityConfiguration(String name, String json, String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidRequestException", "Name is required.", 400);
        }
        if (json != null && !isJsonObject(json)) {
            // A configuration that is no JSON object could not be read when a cluster names it; no source gives AWS's
            // refusal, so the wording is Floci's.
            throw new AwsException("InvalidRequestException", "SecurityConfiguration must be a JSON object.", 400);
        }
        String key = securityConfigurationKey(region, name);
        if (secConfigStore.get(key).isPresent()) {
            throw new AwsException("InvalidRequestException",
                    "Security configuration already exists: " + name, 400);
        }
        SecurityConfiguration sc = new SecurityConfiguration();
        sc.setName(name);
        sc.setSecurityConfiguration(json);
        sc.setCreationDateTime(Instant.now());
        secConfigStore.put(key, sc);
        return sc;
    }

    /**
     * Whether the cluster's security configuration turned on runtime roles as the cluster was created, its
     * {@code AuthorizationConfiguration.IAMConfiguration.EnableApplicationScopedIAMRole}; for a cluster stored before
     * Floci kept that, its configuration as it now stands. False for a cluster with none.
     */
    public boolean runtimeRoleEnabled(EmrCluster cluster) {
        return cluster.getRuntimeRoleEnabled() != null ? cluster.getRuntimeRoleEnabled()
                : runtimeRoleEnabled(regionOf(cluster), cluster.getSecurityConfiguration());
    }

    // Whether the named security configuration of the Region turns on runtime roles; false for none, or one deleted.
    private boolean runtimeRoleEnabled(String region, String securityConfiguration) {
        if (securityConfiguration == null) {
            return false;
        }
        return secConfigStore.get(securityConfigurationKey(region, securityConfiguration))
                .map(SecurityConfiguration::getSecurityConfiguration)
                .map(EmrService::rawJson)
                .map(json -> json.path("AuthorizationConfiguration").path("IAMConfiguration")
                        .path("EnableApplicationScopedIAMRole").asBoolean(false))
                .orElse(false);
    }

    /**
     * GetClusterSessionCredentials: "temporary, HTTP basic credentials that are associated with a given runtime IAM
     * role and used by a cluster with fine-grained access control activated". The cluster must be RUNNING or WAITING,
     * a runtime role enabled one ({@link #runtimeRoleEnabled}), on "Amazon EMR release 6.11 or higher", from which a
     * Workspace attaches to a cluster with a runtime role. Each refusal is InvalidRequestException in Floci's own
     * wording. Floci runs no endpoint that checks the credentials, and keeps none; no source gives how long AWS's
     * last, and Floci's expire in an hour.
     */
    public ClusterSessionCredentials clusterSessionCredentials(String clusterId, String executionRoleArn) {
        if (executionRoleArn != null && (executionRoleArn.length() < 20 || executionRoleArn.length() > 2048)) {
            throw SmithyValidationErrors.validationException(
                    SmithyValidationErrors.lengthOutOfRange("executionRoleArn", executionRoleArn, 20, 2048));
        }
        // The model leaves ExecutionRoleArn optional, but the credentials are "associated with a given runtime IAM
        // role", and with none there is none to associate them with; AWS's answer is not recorded.
        if (executionRoleArn == null) {
            throw invalid("ExecutionRoleArn is required: the credentials are for a runtime role.");
        }
        return call(() -> {
            EmrCluster cluster = settle(requireCluster(clusterId));
            if (!runsOrWaits(cluster)) {
                throw invalid("Cluster " + clusterId + " is " + cluster.getState()
                        + ", and its endpoints are there only while it is RUNNING or WAITING.");
            }
            if (!releaseAtLeast(cluster.getReleaseLabel(), 6, 11, 0)) {
                throw invalid("Cluster session credentials need a cluster of release emr-6.11.0 or later.");
            }
            if (!runtimeRoleEnabled(cluster)) {
                throw invalid("Cluster " + clusterId + " is not a runtime role enabled cluster: its security"
                        + " configuration does not turn on EnableApplicationScopedIAMRole.");
            }
            byte[] password = new byte[SESSION_PASSWORD_BYTES];
            RANDOM.nextBytes(password);
            return new ClusterSessionCredentials("emr-" + randomId(16).toLowerCase(),
                    HexFormat.of().formatHex(password), now().plus(SESSION_CREDENTIALS_VALIDITY));
        });
    }

    /** GetClusterSessionCredentials' username and password, and when they expire. */
    public record ClusterSessionCredentials(String username, String password, Instant expiresAt) {
    }

    public SecurityConfiguration describeSecurityConfiguration(String name, String region) {
        return secConfigStore.get(securityConfigurationKey(region, name)).orElseThrow(() -> new AwsException(
                "InvalidRequestException", "Security configuration does not exist: " + name, 400));
    }

    public void deleteSecurityConfiguration(String name, String region) {
        String key = securityConfigurationKey(region, name);
        if (secConfigStore.get(key).isEmpty()) {
            throw new AwsException("InvalidRequestException",
                    "Security configuration does not exist: " + name, 400);
        }
        secConfigStore.delete(key);
    }

    private static String securityConfigurationKey(String region, String name) {
        return region + KEY_SEPARATOR + name;
    }

    /**
     * Moves the security configurations stored before they were keyed by Region from their bare name onto the
     * default Region's key: they recorded no Region, and every Region saw them. Every other Region where one of its
     * account's clusters names one gets a copy of its own, so the cluster's configuration is still described there.
     */
    private void migrateLegacySecurityConfigurations() {
        // Built only if a configuration is still stored under its bare name.
        Supplier<Map<String, Set<String>>> regionsUsing = new MemoizedSupplier<>(this::securityConfigurationUses);
        secConfigStore.migrateLegacyEntriesToRegions(key -> !isRegionalKey(key),
                regionResolver.getDefaultRegion(),
                (accountId, sc) -> regionsUsing.get().getOrDefault(accountId + "/" + sc.getName(), Set.of()),
                (region, sc) -> sc.getName() == null ? null : securityConfigurationKey(region, sc.getName()),
                StorageJson::copy);
    }

    /**
     * Whether {@code key} is a Region's configuration's, {@code <Region>::<name>}. A name may hold "::" itself, so a key
     * stored before is told apart by what precedes the first one: a Region {@link RegionResolver#isKnownRegion}
     * admits, as {@code RegionScopedStorage} tells its keys apart.
     */
    private static boolean isRegionalKey(String key) {
        int separator = key.indexOf(KEY_SEPARATOR);
        return separator > 0 && RegionResolver.isKnownRegion(key.substring(0, separator));
    }

    /** The Regions where each account's clusters name each security configuration, by account and name. */
    private Map<String, Set<String>> securityConfigurationUses() {
        Map<String, Set<String>> uses = new HashMap<>();
        for (AccountAwareStorageBackend.AccountEntry<EmrCluster> cluster : clusterStore.scanAllAccountEntries(k -> true)) {
            String name = cluster.value().getSecurityConfiguration();
            String region = cluster.value().getRegion();
            if (name != null && region != null) {
                uses.computeIfAbsent(cluster.accountId() + "/" + name, ignored -> new LinkedHashSet<>()).add(region);
            }
        }
        return uses;
    }

    // ──────────────────────────── Release catalogue ────────────────────────────

    private static final int MAX_RESULTS = 100;
    private static final int INSTANCE_TYPE_PAGE = 50;

    /**
     * The catalogue's releases, in ListReleaseLabels' order. Floci's configured default release label is always
     * among them, with no applications when the catalogue does not describe it, so a non-default
     * setting never makes Floci's own default unknown. A blank setting names no release, so it adds none.
     */
    List<EmrReleaseCatalog.Release> releases() {
        if (defaultReleaseLabel == null || defaultReleaseLabel.isBlank()
                || EmrReleaseCatalog.release(defaultReleaseLabel).isPresent()) {
            return EmrReleaseCatalog.RELEASES;
        }
        List<EmrReleaseCatalog.Release> all = new ArrayList<>(EmrReleaseCatalog.RELEASES);
        all.add(new EmrReleaseCatalog.Release(defaultReleaseLabel, List.of(), List.of()));
        all.sort((a, b) -> EmrReleaseCatalog.LISTING_ORDER.compare(a.label(), b.label()));
        return all;
    }

    /**
     * Release labels, in the catalogue's order, narrowed by {@code prefix} (such as {@code emr-6}) and by
     * {@code application}, a name or {@code name@version} (such as {@code spark@3.5.2}) matched
     * without regard to case. A token belongs to the filters it was issued for, so one replayed
     * with other filters, or altered, is refused.
     */
    public PaginatedResult<String> listReleaseLabels(String prefix, String application, String nextToken,
                                                     Integer maxResults) {
        if (maxResults != null && (maxResults < 1 || maxResults > MAX_RESULTS)) {
            throw new AwsException("InvalidRequestException",
                    "MaxResults must be between 1 and " + MAX_RESULTS + ".", 400);
        }
        String appName = null;
        String appVersion = null;
        if (application != null && !application.isBlank()) {
            int at = application.indexOf('@');
            appName = (at < 0 ? application : application.substring(0, at)).trim();
            appVersion = at < 0 ? null : application.substring(at + 1).trim();
        }
        String name = appName;
        String version = appVersion;
        List<String> labels = releases().stream()
                .filter(r -> prefix == null || prefix.isBlank() || r.label().startsWith(prefix))
                .filter(r -> name == null || r.applications().stream().anyMatch(a -> a.name().equalsIgnoreCase(name)
                        && (version == null || version.isEmpty() || a.version().equalsIgnoreCase(version))))
                .map(EmrReleaseCatalog.Release::label)
                .toList();
        String namespace = "ListReleaseLabels|" + Objects.toString(prefix, "") + "|" + Objects.toString(application, "");
        return Pagination.paginate(labels, positionIn(labels), maxResults != null ? maxResults : MAX_RESULTS,
                nextToken, namespace, EmrService::invalidToken);
    }

    /** A release's applications and OS releases; no label means the newest numbered release, the first listed. */
    public EmrReleaseCatalog.Release describeReleaseLabel(String label) {
        if (label == null || label.isBlank()) {
            return releases().get(0);
        }
        // AWS's recorded answer: InvalidRequestException with this ErrorCode and a message naming no label.
        return findRelease(label).orElseThrow(() -> new AwsException("InvalidRequestException",
                "The release label isn't valid.", 400, Map.of("ErrorCode", "InvalidInputParameter")));
    }

    public PaginatedResult<EmrReleaseCatalog.InstanceType> listSupportedInstanceTypes(String label, String marker) {
        // AWS's recorded answer, a missing label reading "null"; its ErrorCode and wording differ from
        // DescribeReleaseLabel's.
        if (label == null || label.isBlank() || findRelease(label).isEmpty()) {
            throw new AwsException("InvalidRequestException", "The supplied release label is invalid: " + label + ".",
                    400, Map.of("ErrorCode", "INVALID_RELEASE_LABEL"));
        }
        List<EmrReleaseCatalog.InstanceType> types = EmrReleaseCatalog.INSTANCE_TYPES;
        return Pagination.paginate(types, positionIn(types), INSTANCE_TYPE_PAGE, marker,
                "ListSupportedInstanceTypes|" + label, EmrService::invalidToken);
    }

    private Optional<EmrReleaseCatalog.Release> findRelease(String label) {
        return releases().stream().filter(r -> r.label().equals(label)).findFirst();
    }

    /**
     * A cursor that keeps the catalogue's own order: {@link Pagination} sorts by cursor, and the
     * catalogue is in ListReleaseLabels' order rather than alphabetical.
     */
    private static <T> Function<T, String> positionIn(List<T> items) {
        Map<T, String> positions = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            positions.put(items.get(i), String.format("%06d", i));
        }
        return positions::get;
    }

    private static AwsException invalidToken(String token) {
        return new AwsException("InvalidRequestException", "Invalid pagination token: " + token, 400);
    }

    /** The security configurations of {@code region}. */
    public List<SecurityConfiguration> listSecurityConfigurations(String region) {
        String prefix = region + KEY_SEPARATOR;
        return new ArrayList<>(secConfigStore.scan(k -> k.startsWith(prefix)));
    }

    // ──────────────────────────── Tags ────────────────────────────

    /** Puts a new map, the held tags with {@code tags} added, in place of the cluster's, so a lookup reads it whole. */
    public void addTags(String resourceId, Map<String, String> tags) {
        run(() -> {
            EmrCluster cluster = requireCluster(resourceId);
            rejectTerminatedTagging(cluster);
            Map<String, String> updated = new LinkedHashMap<>(cluster.getTags());
            updated.putAll(tags);
            cluster.setTags(updated);
            clusterStore.put(resourceId, cluster);
        });
    }

    /** Puts a new map, the held tags without {@code keys}, in place of the cluster's, so a lookup reads it whole. */
    public void removeTags(String resourceId, List<String> keys) {
        run(() -> {
            EmrCluster cluster = requireCluster(resourceId);
            rejectTerminatedTagging(cluster);
            Map<String, String> updated = new LinkedHashMap<>(cluster.getTags());
            keys.forEach(updated::remove);
            cluster.setTags(updated);
            clusterStore.put(resourceId, cluster);
        });
    }

    /**
     * The tags held on the cluster, session, Studio or notebook execution stored under {@code arn}, in the account it
     * names, read in place: the cluster of that id in the ARN's Region, or one recorded with no Region, as the tag
     * handlers find it, the session of that id recording that ARN, the tags it started with, or the Studio or execution
     * of that id in the ARN's Region; empty for an ARN naming nothing stored or naming anything else.
     */
    @Override
    public Map<String, String> storedTags(String arn) {
        if (!AwsArnUtils.isArnFor(arn, "elasticmapreduce")) {
            return Map.of();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String resource = parsed.resource();
        if (resource.startsWith(EmrStudioService.STUDIO_RESOURCE)) {
            return studioService().map(service -> service.storedTags(arn)).orElse(Map.of());
        }
        if (resource.startsWith(EmrNotebookExecutionService.EXECUTION_RESOURCE)) {
            return notebookExecutions != null ? notebookExecutions.get().storedTags(arn) : Map.of();
        }
        int session = resource.startsWith(CLUSTER_RESOURCE) ? resource.indexOf(SESSION_SEGMENT) : -1;
        if (session > 0) {
            return AccountAwareStorageBackend.readInAccount(sessionStore, parsed.accountId(),
                            resource.substring(session + SESSION_SEGMENT.length()))
                    .filter(stored -> arn.equals(stored.getArn()))
                    .map(stored -> heldTags(stored.getTags()))
                    .orElse(Map.of());
        }
        String clusterId = resourceIdOf(parsed, CLUSTER_RESOURCE);
        if (clusterId == null || !arn.equals(AwsArnUtils.Arn.of("elasticmapreduce", parsed.region(),
                parsed.accountId(), resource).toString())) {
            return Map.of();
        }
        return AccountAwareStorageBackend.readInAccount(clusterStore, parsed.accountId(), clusterId)
                .filter(cluster -> cluster.getRegion() == null || cluster.getRegion().equals(parsed.region()))
                .map(cluster -> heldTags(cluster.getTags()))
                .orElse(Map.of());
    }

    /** The id after {@code prefix} in the resource of an ARN naming one resource of that type; null for another. */
    static String resourceIdOf(AwsArnUtils.Arn arn, String prefix) {
        String resource = arn.resource();
        return resource.startsWith(prefix) && resource.indexOf('/', prefix.length()) < 0
                ? resource.substring(prefix.length()) : null;
    }

    /** A resource's tags as IAM's conditions read them: those with both a key and a value. */
    static Map<String, String> heldTags(Map<String, String> tags) {
        Map<String, String> held = new LinkedHashMap<>();
        if (tags != null) {
            tags.forEach((key, value) -> {
                if (key != null && value != null) {
                    held.put(key, value);
                }
            });
        }
        return held;
    }

    private Optional<EmrStudioService> studioService() {
        return studios != null ? Optional.of(studios.get()) : Optional.empty();
    }

    // ──────────────────────────── Helpers ────────────────────────────

    // ──────────────────────────── Scaling and termination policies ────────────────────────────

    public void putManagedScalingPolicy(String clusterId, JsonNode policy) {
        run(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            rejectShutDownCluster(cluster, EmrService::jobFlowShutDownValidation);
            validateManagedScalingPolicy(policy);
            cluster.setManagedScalingPolicy(policy.toString());
            clusterStore.put(clusterId, cluster);
        });
    }

    /**
     * The cluster's managed scaling policy as raw JSON, or null when it has none. A cluster shutting down or ended
     * answers with the job flow message instead, as AWS was recorded doing (terraform-provider-aws#15541).
     */
    public String getManagedScalingPolicy(String clusterId) {
        return call(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            rejectShutDownCluster(cluster, EmrService::jobFlowShutDownValidation);
            return cluster.getManagedScalingPolicy();
        });
    }

    public void removeManagedScalingPolicy(String clusterId) {
        run(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            rejectShutDownCluster(cluster, EmrService::jobFlowShutDownValidation);
            cluster.setManagedScalingPolicy(null);
            clusterStore.put(clusterId, cluster);
        });
    }

    /** AutoTerminationPolicy is optional in the request; an absent one is stored as an empty policy. */
    public void putAutoTerminationPolicy(String clusterId, JsonNode policy) {
        run(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            rejectShutDownCluster(cluster, EmrService::jobFlowShutDownValidation);
            validateAutoTerminationPolicy(policy);
            cluster.setAutoTerminationPolicy(
                    policy == null || policy.isMissingNode() || policy.isNull() ? "{}" : policy.toString());
            clusterStore.put(clusterId, cluster);
        });
    }

    /** The cluster's auto termination policy as raw JSON, or null when it has none. */
    public String getAutoTerminationPolicy(String clusterId) {
        return call(() -> requireCluster(clusterId).getAutoTerminationPolicy());
    }

    public void removeAutoTerminationPolicy(String clusterId) {
        run(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            rejectShutDownCluster(cluster, EmrService::jobFlowShutDownValidation);
            cluster.setAutoTerminationPolicy(null);
            clusterStore.put(clusterId, cluster);
        });
    }

    /** Attaches an automatic scaling policy to a core or task instance group and returns the group. */
    public EmrInstanceGroup putAutoScalingPolicy(String clusterId, String instanceGroupId, JsonNode policy) {
        return call(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            rejectShutDownCluster(cluster, EmrService::jobFlowShutDownValidation);
            EmrInstanceGroup group = requireInstanceGroup(cluster, instanceGroupId);
            validateAutoScalingPolicy(policy, group.getInstanceGroupType());
            group.setAutoScalingPolicy(policy.toString());
            clusterStore.put(clusterId, cluster);
            return group;
        });
    }

    public void removeAutoScalingPolicy(String clusterId, String instanceGroupId) {
        run(() -> {
            EmrCluster cluster = requireCluster(clusterId);
            rejectShutDownCluster(cluster, EmrService::jobFlowShutDownValidation);
            requireInstanceGroup(cluster, instanceGroupId).setAutoScalingPolicy(null);
            clusterStore.put(clusterId, cluster);
        });
    }

    // ──────────────────────────── Block public access ────────────────────────────

    /**
     * The account's block public access configuration in {@code region}. Until one is put, it is the
     * AWS default: enabled for every Region, with port 22 as an exception. The default is stored the
     * first time it is read, so its metadata stays the same on later reads.
     */
    public synchronized EmrBlockPublicAccess getBlockPublicAccess(String region) {
        return blockPublicAccessStore.get(region).orElseGet(() -> {
            EmrBlockPublicAccess setting = new EmrBlockPublicAccess();
            setting.setConfiguration(DEFAULT_BLOCK_PUBLIC_ACCESS);
            setting.setCreationDateTime(Instant.now());
            setting.setCreatedByArn(regionResolver.buildGlobalArn("iam", "root"));
            blockPublicAccessStore.put(region, setting);
            return setting;
        });
    }

    public synchronized void putBlockPublicAccess(String region, JsonNode configuration) {
        validateBlockPublicAccess(configuration);
        EmrBlockPublicAccess setting = new EmrBlockPublicAccess();
        setting.setConfiguration(configuration.toString());
        setting.setCreationDateTime(Instant.now());
        setting.setCreatedByArn(regionResolver.buildGlobalArn("iam", "root"));
        blockPublicAccessStore.put(region, setting);
    }

    // ──────────────────────────── Policy validation (from the API model) ────────────────────────────

    /** ComputeLimits is required, with UnitType, MinimumCapacityUnits and MaximumCapacityUnits. */
    static void validateManagedScalingPolicy(JsonNode policy) {
        if (policy == null || !policy.isObject()) {
            throw invalid("ManagedScalingPolicy is required.");
        }
        JsonNode limits = policy.path("ComputeLimits");
        if (!limits.isObject()) {
            throw invalid("ManagedScalingPolicy.ComputeLimits is required.");
        }
        if (!COMPUTE_LIMITS_UNIT_TYPES.contains(limits.path("UnitType").asText(""))) {
            throw invalid("ComputeLimits.UnitType must be one of " + COMPUTE_LIMITS_UNIT_TYPES + ".");
        }
        if (!limits.path("MinimumCapacityUnits").isInt() || !limits.path("MaximumCapacityUnits").isInt()) {
            throw invalid("ComputeLimits.MinimumCapacityUnits and MaximumCapacityUnits are required.");
        }
        int min = limits.get("MinimumCapacityUnits").asInt();
        int max = limits.get("MaximumCapacityUnits").asInt();
        if (min < 0 || min > max) {
            throw invalid("ComputeLimits.MinimumCapacityUnits must be between 0 and MaximumCapacityUnits.");
        }
        for (String optional : List.of("MaximumOnDemandCapacityUnits", "MaximumCoreCapacityUnits")) {
            JsonNode value = limits.path(optional);
            if (!value.isMissingNode() && (!value.isInt() || value.asInt() < 0 || value.asInt() > max)) {
                throw invalid("ComputeLimits." + optional + " must be between 0 and MaximumCapacityUnits.");
            }
        }
        JsonNode strategy = policy.path("ScalingStrategy");
        if (!strategy.isMissingNode() && !SCALING_STRATEGIES.contains(strategy.asText(""))) {
            throw invalid("ScalingStrategy must be one of " + SCALING_STRATEGIES + ".");
        }
        checkUtilizationPerformanceIndex(policy.path("UtilizationPerformanceIndex"));
    }

    /**
     * The model bounds UtilizationPerformanceIndex to 1 to 100, and of those "Possible values are 1, 25, 50, 75 and
     * 100. If you set the index to values other than these, it results in a validation error" (Management Guide,
     * "Advanced Scaling for Amazon EMR"). The guide gives no message for the latter, so its wording is Floci's.
     */
    private static void checkUtilizationPerformanceIndex(JsonNode index) {
        if (index.isMissingNode() || index.isNull()) {
            return;
        }
        String path = "managedScalingPolicy.utilizationPerformanceIndex";
        if (!index.isIntegralNumber() || !index.canConvertToInt()) {
            throw invalid("ManagedScalingPolicy.UtilizationPerformanceIndex must be an integer.");
        }
        String range = SmithyValidationErrors.outOfRange(path, index.intValue(), MIN_PERFORMANCE_INDEX,
                MAX_PERFORMANCE_INDEX);
        if (range != null) {
            throw SmithyValidationErrors.validationException(range);
        }
        if (!PERFORMANCE_INDEXES.contains(index.intValue())) {
            throw new AwsException("ValidationException", "UtilizationPerformanceIndex must be one of "
                    + PERFORMANCE_INDEXES + ".", 400);
        }
    }

    /** IdleTimeout, when given, is 60 to 604800 seconds. */
    static void validateAutoTerminationPolicy(JsonNode policy) {
        if (policy == null || policy.isMissingNode() || policy.isNull()) {
            return;
        }
        if (!policy.isObject()) {
            throw invalid("AutoTerminationPolicy must be an object.");
        }
        JsonNode idle = policy.path("IdleTimeout");
        if (!idle.isMissingNode() && (!idle.canConvertToLong()
                || idle.asLong() < MIN_IDLE_TIMEOUT_SECONDS || idle.asLong() > MAX_IDLE_TIMEOUT_SECONDS)) {
            throw invalid("AutoTerminationPolicy.IdleTimeout must be between " + MIN_IDLE_TIMEOUT_SECONDS
                    + " and " + MAX_IDLE_TIMEOUT_SECONDS + " seconds.");
        }
    }

    /**
     * Constraints (MinCapacity not above MaxCapacity) and at least one rule with Name, Action and
     * Trigger are required; only core and task instance groups scale automatically.
     */
    static void validateAutoScalingPolicy(JsonNode policy, String instanceGroupType) {
        if (policy == null || !policy.isObject()) {
            throw invalid("AutoScalingPolicy is required.");
        }
        if (!"CORE".equals(instanceGroupType) && !"TASK".equals(instanceGroupType)) {
            throw invalid("An automatic scaling policy can only be attached to a core or task instance group.");
        }
        JsonNode constraints = policy.path("Constraints");
        if (!constraints.path("MinCapacity").isInt() || !constraints.path("MaxCapacity").isInt()) {
            throw invalid("AutoScalingPolicy.Constraints.MinCapacity and MaxCapacity are required.");
        }
        if (constraints.get("MinCapacity").asInt() < 0
                || constraints.get("MinCapacity").asInt() > constraints.get("MaxCapacity").asInt()) {
            throw invalid("Constraints.MinCapacity must be between 0 and MaxCapacity.");
        }
        JsonNode rules = policy.path("Rules");
        if (!rules.isArray() || rules.isEmpty()) {
            throw invalid("AutoScalingPolicy.Rules must contain at least one rule.");
        }
        for (JsonNode rule : rules) {
            if (rule.path("Name").asText("").isEmpty() || !rule.path("Action").isObject() || !rule.path("Trigger").isObject()) {
                throw invalid("Each scaling rule needs a Name, an Action and a Trigger.");
            }
        }
    }

    /** BlockPublicSecurityGroupRules is required; each port range needs MinRange, within 0 to 65535. */
    static void validateBlockPublicAccess(JsonNode configuration) {
        if (configuration == null || !configuration.isObject()
                || !configuration.path("BlockPublicSecurityGroupRules").isBoolean()) {
            throw invalid("BlockPublicAccessConfiguration.BlockPublicSecurityGroupRules is required.");
        }
        JsonNode ranges = configuration.path("PermittedPublicSecurityGroupRuleRanges");
        if (ranges.isMissingNode()) {
            return;
        }
        if (!ranges.isArray()) {
            throw invalid("PermittedPublicSecurityGroupRuleRanges must be a list.");
        }
        for (JsonNode range : ranges) {
            if (!range.path("MinRange").isInt()) {
                throw invalid("Each port range needs a MinRange.");
            }
            int min = range.get("MinRange").asInt();
            JsonNode maxRange = range.path("MaxRange");
            if (!maxRange.isMissingNode() && !maxRange.isInt()) {
                throw invalid("Port range MaxRange must be an integer.");
            }
            int max = maxRange.isInt() ? maxRange.asInt() : min;
            if (min < 0 || max > MAX_PORT || min > max) {
                throw invalid("Port range " + min + "-" + max + " must lie within 0-" + MAX_PORT + ", MinRange first.");
            }
        }
    }

    /** Each BootstrapActionConfig needs a Name and a ScriptBootstrapAction with a Path. */
    static void validateBootstrapActions(JsonNode actions) {
        if (!actions.isArray()) {
            throw invalid("BootstrapActions must be a list.");
        }
        for (JsonNode action : actions) {
            if (action.path("Name").asText("").isEmpty()
                    || action.path("ScriptBootstrapAction").path("Path").asText("").isEmpty()) {
                throw invalid("Each bootstrap action needs a Name and a ScriptBootstrapAction.Path.");
            }
        }
    }

    private EmrCluster clusterOwning(String region, String instanceGroupId) {
        if (instanceGroupId == null) {
            throw invalid("InstanceGroupId is required.");
        }
        return clusterWithGroup(region, instanceGroupId)
                .map(this::settle)
                .orElseThrow(() -> invalid("Instance group " + instanceGroupId + " is not valid."));
    }

    /** The caller's cluster in the Region that has the instance group, as stored. */
    private Optional<EmrCluster> clusterWithGroup(String region, String instanceGroupId) {
        return clusterStore.scan(k -> true).stream()
                .filter(c -> region.equals(c.getRegion()))
                .filter(c -> c.getInstanceGroups().stream().anyMatch(g -> instanceGroupId.equals(g.getId())))
                .findFirst();
    }

    /**
     * What a listing's marker resumes only, as a JSON list: the operation, the account, the Region and each filter, an
     * absent filter told apart from any value. A marker from another account, Region or filter is then refused, rather
     * than resuming this listing at its cursor.
     */
    static String listingNamespace(String operation, String accountId, String region, String... filters) {
        List<String> parts = new ArrayList<>(Arrays.asList(operation, accountId, region));
        parts.addAll(Arrays.asList(filters));
        try {
            return SNAPSHOT_MAPPER.writeValueAsString(parts);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write a listing's namespace", e);
        }
    }

    static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    private static boolean outOfStepConcurrencyRange(int level) {
        return level < 1 || level > MAX_STEP_CONCURRENCY;
    }

    private static String stepConcurrencyOutOfRange(int level) {
        return "The StepConcurrencyLevel " + level + " is not valid: it must be from 1 to " + MAX_STEP_CONCURRENCY
                + ".";
    }

    private static String tooManyActiveSteps(long active) {
        return "Only " + MAX_ACTIVE_STEPS + " steps can be PENDING or RUNNING at any given time, and this would make "
                + active + ".";
    }

    // A step PENDING or RUNNING, one whose cancel is pending among them, as it still runs.
    private static boolean activeStep(EmrStep step) {
        return EmrClusterLifecycle.PENDING.equals(step.getState())
                || EmrClusterLifecycle.RUNNING.equals(step.getState())
                || EmrClusterLifecycle.CANCEL_PENDING.equals(step.getState());
    }

    // Whether a cluster of releaseLabel takes CancelSteps: from 4.8.0 on but 5.0.0; a label not of the emr-x.y.z form
    // is taken as current.
    static boolean cancelsSteps(String releaseLabel) {
        return releaseAtLeast(releaseLabel, 4, 8, 0) && !"emr-5.0.0".equals(releaseLabel);
    }

    /**
     * Whether {@code releaseLabel} is {@code major.minor.patch} or later; a label not of the emr-x.y.z form, such as
     * the AWS runtime for Apache Spark's, is taken as current.
     */
    static boolean releaseAtLeast(String releaseLabel, int major, int minor, int patch) {
        Matcher version = RELEASE_VERSION.matcher(releaseLabel == null ? "" : releaseLabel);
        if (!version.matches()) {
            return true;
        }
        int[] label = {Integer.parseInt(version.group(1)), Integer.parseInt(version.group(2)),
                Integer.parseInt(version.group(3))};
        return Arrays.compare(label, new int[]{major, minor, patch}) >= 0;
    }

    // Why a cluster gets no instance group or fleet of the other kind: "The configuration option you choose applies to
    // all nodes, it applies for the lifetime of the cluster, and instance fleets and instance groups cannot coexist in
    // a cluster."
    private static String fleetsAndGroupsApart(String clusterId, String uses) {
        return "Cluster " + clusterId + " uses " + uses + ", and instance fleets and instance groups cannot coexist"
                + " in a cluster.";
    }

    /**
     * ModifyInstanceFleet's answer for a job flow that is shutting down or ended, ErrorCode member included.
     * AddInstanceFleet and ModifyCluster give it too, as their models also declare InvalidRequestException.
     */
    private static AwsException jobFlowShutDown() {
        return new AwsException("InvalidRequestException", JOB_FLOW_SHUT_DOWN, 400,
                Map.of("ErrorCode", "INVALID_JOB_SHUT_DOWN"));
    }

    /**
     * AddJobFlowSteps' and GetManagedScalingPolicy's answer for a job flow that is shutting down or ended. The
     * operations whose models, like theirs, declare no InvalidRequestException give it too: AddInstanceGroups, the
     * Set* setters, and Put and Remove of the managed scaling, auto-termination and automatic scaling policies.
     */
    private static AwsException jobFlowShutDownValidation() {
        return new AwsException("ValidationException", JOB_FLOW_SHUT_DOWN, 400);
    }

    /**
     * Refuses a call on a cluster that is shutting down or ended with {@code answer}, the job flow message in the
     * code the operation's model points at. AWS's answer is recorded for AddJobFlowSteps, ModifyInstanceFleet and
     * GetManagedScalingPolicy, and Terraform's provider expects it from RemoveManagedScalingPolicy; the other
     * operations calling this, documented for running clusters, take it from them.
     */
    private static void rejectShutDownCluster(EmrCluster cluster, Supplier<AwsException> answer) {
        if (isShutDown(cluster)) {
            throw answer.get();
        }
    }

    /**
     * AddTags and RemoveTags on an ended cluster: the Management Guide's "you cannot add, edit, or remove tags
     * from a terminated cluster" names no error, so the message is the one a tagging tool matches from AWS, in
     * the InvalidRequestException both models declare.
     */
    private static void rejectTerminatedTagging(EmrCluster cluster) {
        if (EmrClusterLifecycle.isTerminated(cluster.getState())) {
            throw invalid("Tags cannot be modified on terminated clusters.");
        }
    }

    private static EmrInstanceGroup requireInstanceGroup(EmrCluster cluster, String instanceGroupId) {
        if (instanceGroupId == null) {
            throw invalid("InstanceGroupId is required.");
        }
        return cluster.getInstanceGroups().stream()
                .filter(group -> instanceGroupId.equals(group.getId()))
                .findFirst()
                .orElseThrow(() -> invalid("Instance group " + instanceGroupId + " is not in cluster " + cluster.getId() + "."));
    }

    private EmrCluster requireCluster(String id) {
        if (id == null) {
            throw new AwsException("InvalidRequestException", "ClusterId is required.", 400);
        }
        return findCluster(id).orElseThrow(() -> new AwsException(
                "InvalidRequestException", "Cluster id '" + id + "' is not valid.", 400));
    }

    /**
     * Clusters are stored by id for every region; a request sees only its own region's, and one from
     * another region reads as an unknown id. A cluster recorded without a region stays reachable.
     */
    private Optional<EmrCluster> findCluster(String id) {
        String region = regionResolver.getRegion();
        return clusterStore.get(id).filter(c -> c.getRegion() == null || c.getRegion().equals(region))
                .map(this::settle);
    }

    /**
     * The Set* setters: an unknown id is passed over, and one cluster shutting down or ended refuses the
     * request before any of the clusters it names is changed.
     */
    private void mutateClusters(List<String> ids, Consumer<EmrCluster> mutation) {
        List<EmrCluster> clusters = ids.stream().map(this::findCluster).flatMap(Optional::stream).toList();
        clusters.forEach(c -> rejectShutDownCluster(c, EmrService::jobFlowShutDownValidation));
        for (EmrCluster c : clusters) {
            mutation.accept(c);
            clusterStore.put(c.getId(), c);
        }
    }

    /**
     * When the cluster began to shut down, if it has: as it began terminating, or else as it ended, or else
     * {@code now}; null while it runs. A session or notebook execution on it ends then.
     */
    static Instant shutDownAt(EmrCluster cluster, Instant now) {
        if (!isShutDown(cluster)) {
            return null;
        }
        return cluster.getTerminatingDateTime() != null ? cluster.getTerminatingDateTime()
                : cluster.getEndDateTime() != null ? cluster.getEndDateTime() : now;
    }

    /** Adds the violation of an enum member whose value is not one of {@code allowed}, as AWS's validation words it. */
    static void checkEnum(List<String> violations, String member, String value, List<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            violations.add(SmithyValidationErrors.notInEnum(value, member, allowed));
        }
    }

    /** Shutting down or ended: "A job flow that is shutting down, terminated, or finished may not be modified." */
    static boolean isShutDown(EmrCluster cluster) {
        return EmrClusterLifecycle.TERMINATING.equals(cluster.getState())
                || EmrClusterLifecycle.isTerminated(cluster.getState());
    }

    /** The only states in which a cluster's instance groups and fleets may be modified. */
    private static boolean runsOrWaits(EmrCluster cluster) {
        return EmrClusterLifecycle.RUNNING.equals(cluster.getState())
                || EmrClusterLifecycle.WAITING.equals(cluster.getState());
    }

    /**
     * Runs {@code body} holding the service, then publishes the events it queued once it no longer
     * does, so a rule's target that calls EMR never waits on the call that made the change. Every
     * call that reads a cluster runs this way, since reading one brings it up to date.
     */
    private <T> T call(Supplier<T> body) {
        try {
            synchronized (this) {
                return body.get();
            }
        } finally {
            eventPublisher.publishQueued();
        }
    }

    private void run(Runnable body) {
        call(() -> {
            body.run();
            return null;
        });
    }

    /**
     * A copy of the cluster for a call to answer from: the timer goes on changing the stored one, and an
     * answer built from it after the service is let go could mix two of its states.
     */
    private static EmrCluster snapshotOf(EmrCluster cluster) {
        return SNAPSHOT_MAPPER.convertValue(cluster, EmrCluster.class);
    }

    /**
     * The cluster as it stands now: every change due by now made, stored if there was one. A step a
     * container ran that no thread runs any more, since Floci stopped while it ran, is FAILED.
     */
    private EmrCluster settle(EmrCluster cluster) {
        boolean interrupted = failInterruptedSteps(cluster) | failInterruptedBootstrap(cluster);
        boolean changed = lifecycle.advance(cluster, now(), regionOf(cluster)) || interrupted;
        if (changed) {
            clusterStore.put(cluster.getId(), cluster);
        }
        releaseEnded(cluster);
        wakeForNextChange(cluster);
        return cluster;
    }

    /** A cluster whose bootstrap no thread runs any more, since Floci stopped while it ran, ends. */
    private boolean failInterruptedBootstrap(EmrCluster cluster) {
        if (bootstrapRunner == null || !bootstrapsInContainer(cluster)
                || bootstrapping.contains(clusterKey(regionResolver.getAccountId(), cluster.getId()))) {
            return false;
        }
        lifecycle.bootstrapEnded(cluster, null, EmrClusterLifecycle.INTERNAL_ERROR, BOOTSTRAP_INTERRUPTED, List.of(),
                now(), regionOf(cluster));
        return true;
    }

    private static boolean bootstrapsInContainer(EmrCluster cluster) {
        return EmrClusterLifecycle.BOOTSTRAPPING.equals(cluster.getState()) && cluster.isBootstrapInContainer();
    }

    // What an ended cluster kept goes once no step of it still runs in a container: its bootstrap's image and
    // its storage. Its sessions end as it shuts down, their servers with them, whether or not a session is read.
    private void releaseEnded(EmrCluster cluster) {
        endSessionsOf(cluster);
        releaseBootstrapImage(cluster);
        releaseStorage(cluster);
    }

    // A cluster shutting down ends its sessions that have not ended, as "Terminating the cluster also ends any sessions
    // still running on it", and stops their servers.
    private void endSessionsOf(EmrCluster cluster) {
        Instant now = now();
        if (EmrService.shutDownAt(cluster, now) == null) {
            return;
        }
        // The commands run on it stop too, and end as stopped.
        clusterRuns.forEach((runId, clusterId) -> {
            if (clusterId.equals(cluster.getId())) {
                stopOnCluster(runId);
            }
        });
        if (!cluster.isSessionEnabled()) {
            return;
        }
        for (EmrSession session : sessionStore.scan(key -> true)) {
            if (cluster.getId().equals(session.getClusterId()) && EmrSessions.settle(cluster, session, now)) {
                sessionStore.put(session.getId(), session);
                if (EmrSessions.ended(session)) {
                    sessionServers.stop(session.getId());
                }
            }
        }
    }

    // Whether a server of the cluster's sessions, or a command run on it, is still there, its container perhaps still
    // on its storage.
    private boolean sessionServersRunning(EmrCluster cluster) {
        if (clusterRuns.containsValue(cluster.getId())) {
            return true;
        }
        return cluster.isSessionEnabled() && sessionStore.scan(key -> true).stream()
                .anyMatch(session -> cluster.getId().equals(session.getClusterId())
                        && sessionServers.runs(session.getId()));
    }

    private boolean stepsExecuting(EmrCluster cluster, String accountId) {
        return cluster.getSteps().stream().anyMatch(step -> executing.contains(stepKey(accountId, step.getId())));
    }

    /**
     * Once a cluster has ended and no step of it still runs in a container, the image its bootstrap left
     * goes, off the service's lock. The cluster names it until it is gone, and while a thread removes it, it
     * is not removed again.
     */
    private void releaseBootstrapImage(EmrCluster cluster) {
        List<String> images = new ArrayList<>(cluster.getNodeBootstrapImages().values());
        images.addAll(cluster.getRetiredBootstrapImages());
        if (cluster.getBootstrapImage() != null) {
            images.add(cluster.getBootstrapImage());
        }
        if (images.isEmpty() || bootstrapRunner == null || !EmrClusterLifecycle.isTerminated(cluster.getState())) {
            return;
        }
        String accountId = regionResolver.getAccountId();
        if (stepsExecuting(cluster, accountId)) {
            return;
        }
        String clusterId = cluster.getId();
        String region = regionOf(cluster);
        for (String image : images) {
            if (imagesBeingRemoved.add(image)) {
                stepExecutor.execute(() -> {
                    boolean gone = bootstrapRunner.removeImage(image);
                    RequestScopes.runAs(accountId, region,
                            () -> run(() -> bootstrapImageRemoved(clusterId, image, gone)));
                });
            }
        }
    }

    // A thread's removal of a cluster's image has ended: the cluster names it no more once it is gone.
    private void bootstrapImageRemoved(String clusterId, String image, boolean gone) {
        imagesBeingRemoved.remove(image);
        if (!gone) {
            return;
        }
        clusterStore.get(clusterId).ifPresent(cluster -> {
            boolean primary = image.equals(cluster.getBootstrapImage());
            if (primary) {
                cluster.setBootstrapImage(null);
            }
            boolean node = cluster.getNodeBootstrapImages().values().removeIf(image::equals);
            boolean retired = cluster.getRetiredBootstrapImages().remove(image);
            if (primary || node || retired) {
                clusterStore.put(clusterId, cluster);
            }
        });
    }

    /**
     * Once a cluster has ended and no step of it still runs in a container, its storage goes, off the service's
     * lock, as its bootstrap's image does: the cluster keeps it until it is gone, and while a thread removes it,
     * it is not removed again.
     */
    private void releaseStorage(EmrCluster cluster) {
        if (!cluster.isStorageCreated() || clusterStorage == null
                || !EmrClusterLifecycle.isTerminated(cluster.getState())) {
            return;
        }
        String accountId = regionResolver.getAccountId();
        String key = clusterKey(accountId, cluster.getId());
        // A session's server still there keeps it too; once the last has gone, the release is tried again.
        if (stepsExecuting(cluster, accountId) || sessionServersRunning(cluster) || !storageBeingRemoved.add(key)) {
            return;
        }
        String clusterId = cluster.getId();
        String region = regionOf(cluster);
        // Its kept history goes once its 30 days have passed, whether or not a link ever asked for it.
        Instant ended = cluster.getEndDateTime() != null ? cluster.getEndDateTime() : now();
        wakeups.at(ended.plus(HISTORY_KEPT).plus(HISTORY_SWEEP_MARGIN), this::removeExpiredHistories);
        stepExecutor.execute(() -> {
            // What its history interfaces show goes into its kept history first, which outlives the rest; a copy that
            // failed keeps the storage that holds it, which the cluster's next release, or a restart, tries again.
            boolean gone = clusterStorage.keepHistory(clusterId) && clusterStorage.remove(clusterId);
            RequestScopes.runAs(accountId, region, () -> run(() -> storageRemoved(clusterId, key, gone)));
        });
    }

    // A thread's removal of a cluster's storage has ended: the cluster keeps it no more once it is gone.
    private void storageRemoved(String clusterId, String key, boolean gone) {
        storageBeingRemoved.remove(key);
        if (!gone) {
            return;
        }
        clusterStore.get(clusterId).filter(EmrCluster::isStorageCreated).ifPresent(cluster -> {
            cluster.setStorageCreated(false);
            clusterStore.put(clusterId, cluster);
        });
    }

    private boolean failInterruptedSteps(EmrCluster cluster) {
        boolean ended = false;
        for (EmrStep step : List.copyOf(cluster.getSteps())) {
            if (leftByARestart(step)) {
                lifecycle.stepEnded(cluster, step, EmrSparkSteps.INTERRUPTED, now(), regionOf(cluster));
                ended = true;
            }
        }
        return ended;
    }

    /**
     * Whether a step a container ran is one no thread runs any more, since Floci stopped while it ran: it ends,
     * FAILED, or CANCELLED when its cancel was pending. Without the docker runner mode only one whose cancel was
     * pending is, as nothing would end it; a RUNNING one completes by time there.
     */
    private boolean leftByARestart(EmrStep step) {
        if (sparkRunner == null) {
            return EmrClusterLifecycle.CANCEL_PENDING.equals(step.getState());
        }
        return runsInContainer(step) && !executing.contains(stepKey(regionResolver.getAccountId(), step.getId()));
    }

    // A step a container runs: RUNNING, or CANCEL_PENDING until its driver is gone.
    private static boolean runsInContainer(EmrStep step) {
        return EmrClusterLifecycle.holdsAPlace(step) && step.isRunInContainer();
    }

    private static Optional<EmrStep> stepOf(EmrCluster cluster, String stepId) {
        return cluster.getSteps().stream().filter(step -> step.getId().equals(stepId)).findFirst();
    }

    private static String stepKey(String accountId, String stepId) {
        return accountId + "/" + stepId;
    }

    private static String clusterKey(String accountId, String clusterId) {
        return accountId + "/" + clusterId;
    }

    /**
     * The role a step's driver runs as: the step's runtime role, or else the role of the cluster's EC2
     * instance profile, its {@code JobFlowRole}, given by name or ARN; null when IAM knows neither, and the
     * driver then has the account's placeholder credentials. The guide's "Runtime roles for Amazon EMR
     * steps": without them, clusters "ran Amazon EMR jobs or queries with permissions based on the IAM
     * policy attached to the instance profile that you used to launch the cluster".
     */
    private String executionRole(EmrCluster cluster, EmrStep step, String accountId) {
        return step.getExecutionRoleArn() != null
                ? step.getExecutionRoleArn() : instanceProfileRole(cluster, accountId);
    }

    /**
     * The role of the cluster's EC2 instance profile, its {@code JobFlowRole}, given by name or by the profile's own
     * ARN, as IAM's passed-role check finds it ({@link IamService#instanceProfileNamed}), or null.
     */
    private String instanceProfileRole(EmrCluster cluster, String accountId) {
        String profile = cluster.getJobFlowRole();
        if (profile == null || iamService == null) {
            return null;
        }
        return IamService.instanceProfileNamed(iamService, accountId, profile)
                .filter(found -> found.getRoleNames() != null && found.getRoleNames().size() == 1)
                .flatMap(found -> iamService.findRole(accountId, found.getRoleNames().getFirst()))
                .map(IamRole::getArn)
                .orElse(null);
    }

    /**
     * Ends a step its thread ran, COMPLETED when {@code failure} is null and FAILED otherwise, or CANCELLED when a
     * cancel had made it CANCEL_PENDING, unless a reset came since it started or the step is past running:
     * cancelled, or on a cluster that ended.
     */
    private void stepEnded(StepLaunch launch, String failure) {
        if (launch.launchedIn() != generation) {
            return;
        }
        // Read while the step still counts as running, so it is not taken for one a restart left.
        Optional<EmrCluster> found = findCluster(launch.clusterId());
        executing.remove(stepKey(launch.accountId(), launch.stepId()));
        EmrStep step = found.flatMap(cluster -> stepOf(cluster, launch.stepId())).orElse(null);
        if (step == null || !EmrClusterLifecycle.holdsAPlace(step)) {
            // A step of a cluster that has ended no longer runs: what the cluster kept may go with it, its last one.
            found.ifPresent(this::releaseEnded);
            return;
        }
        EmrCluster cluster = found.get();
        lifecycle.stepEnded(cluster, step, failure, now(), regionOf(cluster));
        store(cluster);
        LOG.infov("EMR step {0} of cluster {1} ended {2}", step.getId(), cluster.getId(), step.getState());
    }

    /**
     * A step's driver was stopped. A cancel has made the step CANCEL_PENDING, which is CANCELLED now that its driver
     * is gone, and makes room for the next; its cluster's end has ended it already. One still RUNNING, or
     * CANCEL_PENDING, that Floci itself stopped, by a reset's teardown or its shutdown, is left as it is, still
     * counted as running: the reset wipes it, a restart ends it, and what its end leads to, which could start the
     * next step after the teardown has passed, does not happen now.
     */
    private void driverStopped(StepLaunch launch) {
        if (stillRunning(launch)) {
            return;
        }
        Optional<EmrCluster> found = clusterStore.get(launch.clusterId());
        Optional<EmrStep> cancelPending = found.flatMap(cluster -> stepOf(cluster, launch.stepId()))
                .filter(step -> EmrClusterLifecycle.CANCEL_PENDING.equals(step.getState()));
        if (cancelPending.isPresent() && teardowns.get() != launch.teardownsBefore()) {
            return;
        }
        executing.remove(stepKey(launch.accountId(), launch.stepId()));
        if (cancelPending.isPresent() && launch.launchedIn() == generation) {
            EmrCluster cluster = found.get();
            lifecycle.stepEnded(cluster, cancelPending.get(), null, now(), regionOf(cluster));
            store(cluster);
            return;
        }
        found.ifPresent(this::releaseEnded);
    }

    /**
     * Why a step whose driver ended this way, by itself or before it could start, failed, or null when it
     * did not. No source gives AWS's wording for these; they are Floci's.
     */
    private static String failureOf(SparkJobRunner.Job job, SparkJobRunner.Outcome outcome) {
        if (outcome.exitCode() != null) {
            String ended = job.command() != null ? "The step's command" : "The Spark driver";
            return outcome.exitCode() == 0 ? null : ended + " exited with code " + outcome.exitCode() + ".";
        }
        return "The step could not start: " + outcome.failure();
    }

    // Whether a step's driver may still start, or go on: one cancelled, on a cluster that ended, or a reset
    // removed, is past it.
    private synchronized boolean stillRunning(StepLaunch launch) {
        return launch.launchedIn() == generation && clusterStore.get(launch.clusterId())
                .flatMap(cluster -> stepOf(cluster, launch.stepId()))
                .filter(step -> EmrClusterLifecycle.RUNNING.equals(step.getState())).isPresent();
    }

    /**
     * Whether a step's driver may go on, in its reset: RUNNING, or, once its driver runs, interrupted and free to end
     * on its own. One interrupted before its driver started has no process to signal, so it does not start.
     */
    private synchronized boolean mayGoOn(StepLaunch launch, boolean driverStarted) {
        return launch.launchedIn() == generation && clusterStore.get(launch.clusterId())
                .flatMap(cluster -> stepOf(cluster, launch.stepId()))
                .filter(step -> EmrClusterLifecycle.RUNNING.equals(step.getState()) || (driverStarted
                        && EmrClusterLifecycle.CANCEL_PENDING.equals(step.getState()) && step.isInterrupted()))
                .isPresent();
    }

    // Whether a command's cluster still runs, in the reset it was handed off in.
    private synchronized boolean commandGoing(CommandLaunch launch) {
        return launch.launchedIn() == generation && clusterStore.get(launch.clusterId())
                .filter(cluster -> shutDownAt(cluster, now()) == null).isPresent();
    }

    private synchronized boolean resetSince(StepLaunch launch) {
        return launch.launchedIn() != generation;
    }

    /**
     * Where a container runs on the cluster, a step's or a session's server's: the cluster's storage, where it is made
     * (null for none); the image, the one its bootstrap left, which holds what its release's does, else the template's;
     * the release's image, resolved on the container's thread, for a cluster whose bootstrap left none; what starts
     * the cluster's HDFS on its primary node; and that node.
     */
    private record OnCluster(SparkJobRunner.Storage storage, String image, Supplier<String> releaseImage,
                             BiFunction<String, BooleanSupplier, SparkJobRunner.Storage> hdfs, Instance primary) {
    }

    /**
     * Where a container runs on the cluster, {@link OnCluster}, with the cluster's storage made where {@code storage}
     * says one needs it and Floci keeps clusters' storage. Every EMR cluster runs HDFS on its primary node, which the
     * container's thread starts when it is not running: a NameNode and a DataNode, on a cluster with Hive or HCatalog
     * its metastore service, and on one that runs YARN a ResourceManager and a NodeManager, in a container of the
     * image its steps run in, as an EMR cluster's nodes hold every application it installs, or else of the release's
     * Hive image, the one with HDFS's daemons.
     */
    private OnCluster onCluster(EmrCluster cluster, String accountId, String region, boolean storage) {
        SparkJobRunner.Storage volumes = null;
        if (storage && clusterStorage != null) {
            volumes = clusterStorage.volumes(cluster.getId());
            cluster.setStorageCreated(true);
            String known = cluster.getId();
            clusterStorage.identify(known, node -> nodeIdentity(known, accountId, region, node));
        }
        String image = cluster.getBootstrapImage() != null ? cluster.getBootstrapImage() : imageTemplate;
        Supplier<String> releaseImage = cluster.getBootstrapImage() == null ? releaseImage(cluster) : null;
        String clusterId = cluster.getId();
        String hdfsHost = masterPrivateDnsName(cluster);
        String hdfsImage = hdfsImage(cluster.getReleaseLabel());
        Supplier<ClusterStorage.Yarn> nodeYarn = runsYarn(cluster) ? currentYarn(cluster, accountId, region) : null;
        String trinoImage = trinoImage(cluster);
        ClusterStorage.Trino trino = trinoImage == null ? null : new ClusterStorage.Trino(trinoImage, trinoMemoryMb,
                EmrSparkSteps.trinoConnectors(cluster.getConfigurations(), SNAPSHOT_MAPPER),
                EmrSparkSteps.trinoConfig(cluster.getConfigurations(), SNAPSHOT_MAPPER),
                EmrSparkSteps.trinoNode(cluster.getConfigurations(), SNAPSHOT_MAPPER),
                EmrSparkSteps.trinoLog(cluster.getConfigurations(), SNAPSHOT_MAPPER),
                EmrSparkSteps.trinoEnvironment(cluster.getConfigurations(), SNAPSHOT_MAPPER),
                EmrSparkSteps.trinoExchangeManager(cluster.getConfigurations(), SNAPSHOT_MAPPER));
        ClusterStorage.HiveMetastore hiveMetastore = withHiveImage(cluster)
                ? new ClusterStorage.HiveMetastore(instanceProfileRole(cluster, accountId),
                        EmrSparkSteps.hiveMetastoreSettings(cluster.getConfigurations(), SNAPSHOT_MAPPER), trino)
                : null;
        BiFunction<String, BooleanSupplier, SparkJobRunner.Storage> hdfs = volumes == null ? null
                : (stepImage, stopped) -> clusterStorage.hdfs(clusterId, accountId, region, hdfsHost,
                        nodeYarn != null ? stepImage : hdfsImage, dockerNetwork, hiveMetastore, nodeYarn, stopped);
        return new OnCluster(volumes, image, releaseImage, hdfs, nodeInstance(cluster, accountId, 0, 1));
    }

    /** A step handed to a thread: whose it is, where, and under which reset. */
    private record StepLaunch(String accountId, String region, String clusterId, String stepId, long launchedIn,
                              long teardownsBefore) {}

    /** Starts each step a cluster runs on a thread of its own, which ends the step once it is done. */
    private final class ContainerSteps implements EmrClusterLifecycle.StepExecution {

        @Override
        public void start(EmrCluster cluster, EmrStep step, String region) {
            String accountId = regionResolver.getAccountId();
            if (tornDown) {
                // Left RUNNING and counted as running, as a step whose driver the teardown stopped: the reset wipes it,
                // a restart fails it.
                executing.add(stepKey(accountId, step.getId()));
                return;
            }
            EmrSparkSteps.HiveCommand hive = EmrSparkSteps.hive(step, cluster.getReleaseLabel(),
                    cluster.getApplications(), cluster.getConfigurations(), SNAPSHOT_MAPPER);
            boolean yarn = runsYarn(cluster);
            boolean pig = withPig(cluster);
            boolean pigMissing = !pig && EmrSparkSteps.installsPig(cluster.getReleaseLabel(), cluster.getApplications(),
                    SNAPSHOT_MAPPER);
            List<String> application = hive == null ? EmrSparkSteps.application(step, yarn) : null;
            List<String> command = hive == null && application == null ? EmrSparkSteps.command(step, pigMissing)
                    : null;
            OnCluster onCluster = onCluster(cluster, accountId, region,
                    hive != null || application != null || command != null);
            SparkJobRunner.Storage storage = onCluster.storage();
            String image = onCluster.image();
            SparkJobRunner.Job job;
            if (hive != null) {
                job = new SparkJobRunner.Job(SERVICE, step.getId(), accountId, region, cluster.getReleaseLabel(),
                        SparkJobRunner.Engine.HIVE, image, null, dockerNetwork,
                        executionRole(cluster, step, accountId), Map.of(), hive.arguments(), hive.runner(),
                        Duration.ZERO, EmrSparkSteps.logFolder(cluster, step), null,
                        EmrSparkSteps.hiveEnvironment(cluster.getConfigurations(), SNAPSHOT_MAPPER), List.of(),
                        storage, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null).asInterruptible();
            } else if (application == null && command == null) {
                job = null;
            } else {
                job = new SparkJobRunner.Job(SERVICE, step.getId(), accountId, region, cluster.getReleaseLabel(),
                        SparkJobRunner.Engine.SPARK, image, null, dockerNetwork,
                        executionRole(cluster, step, accountId),
                        EmrSparkSteps.sparkDefaults(cluster.getConfigurations(), SNAPSHOT_MAPPER),
                        application != null ? application : List.of(), command, Duration.ZERO,
                        EmrSparkSteps.logFolder(cluster, step), null,
                        stepEnvironment(step, cluster, command != null && pig,
                                runsTrino(cluster) ? EmrTrino.server(masterPrivateDnsName(cluster),
                                        EmrTrino.port(EmrSparkSteps.trinoConfig(cluster.getConfigurations(),
                                                SNAPSHOT_MAPPER))) : null),
                        List.of(),
                        storage, EmrSparkSteps.coreSite(cluster.getConfigurations(), SNAPSHOT_MAPPER),
                        EmrSparkSteps.sparkLog4j2(cluster.getConfigurations(), SNAPSHOT_MAPPER),
                        EmrSparkSteps.mapredSite(cluster.getConfigurations(), SNAPSHOT_MAPPER),
                        EmrSparkSteps.hadoopLog4j(cluster.getConfigurations(), SNAPSHOT_MAPPER),
                        EmrSparkSteps.hadoopEnvironment(cluster.getConfigurations(), SNAPSHOT_MAPPER), null)
                        .asInterruptible();
            }
            StepLaunch launch = new StepLaunch(accountId, region, cluster.getId(), step.getId(), generation,
                    teardowns.get());
            executing.add(stepKey(accountId, step.getId()));
            String containerLogs = yarn ? EmrSparkSteps.containerLogFolder(cluster, step) : null;
            stepExecutor.execute(() -> runStep(job, launch, onCluster.releaseImage(), onCluster.hdfs(), containerLogs,
                    onCluster.primary()));
        }

        @Override
        public void stop(EmrStep step, boolean terminateProcess) {
            if (terminateProcess) {
                sparkRunner.stop(step.getId());
            } else {
                sparkRunner.interrupt(step.getId());
            }
        }

        // A cluster with Trino starts its primary node, and Trino on it, as it becomes ready, steps or none.
        @Override
        public void ready(EmrCluster cluster, String region) {
            if (tornDown || !runsTrino(cluster)) {
                return;
            }
            String accountId = regionResolver.getAccountId();
            String clusterId = cluster.getId();
            LOG.infov("EMR cluster {0} starts its Trino, which clients outside the cluster reach at {1}", clusterId,
                    EmrTrino.address(baseUrl, clusterId));
            try {
                // On a thread of its own, which reads the cluster under the service's lock, so once this is stored.
                stepExecutor.execute(() -> RequestScopes.runAs(accountId, region, () -> startPrimaryNode(clusterId)));
            } catch (RuntimeException e) {
                LOG.warnv(e, "Could not start the primary node of EMR cluster {0} for its Trino: {1}", clusterId,
                        e.getMessage());
            }
        }
    }

    /**
     * What a step's thread, or a session's server's, does on the cluster before its driver runs: the release's image
     * resolved, where the cluster names none of its own; and, for a job on the cluster's storage, that storage made,
     * the primary node's instance metadata placed, the actions of nodes a resize added run, and its HDFS running, which
     * the job then runs on. A job a cancel, its cluster's end or a reset has passed makes none of it. Afterwards the
     * image may go once a newer one of its kind is made, and the metadata address to another.
     */
    private final class ClusterPreparation implements SessionServers.Preparation {

        private final String clusterId;
        private final String accountId;
        private final String region;
        private final Supplier<String> releaseImage;
        private final BiFunction<String, BooleanSupplier, SparkJobRunner.Storage> hdfs;
        private final Instance primary;
        private String image;
        private String address;

        ClusterPreparation(String clusterId, String accountId, String region, Supplier<String> releaseImage,
                           BiFunction<String, BooleanSupplier, SparkJobRunner.Storage> hdfs, Instance primary) {
            this.clusterId = clusterId;
            this.accountId = accountId;
            this.region = region;
            this.releaseImage = releaseImage;
            this.hdfs = hdfs;
            this.primary = primary;
        }

        @Override
        public SparkJobRunner.Job prepare(SparkJobRunner.Job job, BooleanSupplier cancelled) {
            image = releaseImage != null && !cancelled.getAsBoolean() ? releaseImage.get() : null;
            SparkJobRunner.Job running = image != null ? job.inImage(image) : job;
            // The cluster's storage is there, its HDFS running, before its first container mounts it. That container
            // reads the primary node's instance metadata, as a step on EMR's primary node does.
            if (job.storage() != null && !cancelled.getAsBoolean()) {
                clusterStorage.create(clusterId, accountId, region);
                MetadataPlace place = metadataPlace(clusterId, accountId, region, running.imageTemplate(),
                        running.releaseLabel(), primary);
                address = place != null ? place.address() : null;
                // A node a resize added since the cluster's bootstrap runs its actions before it starts.
                bootstrapAddedNodes(clusterId, accountId, region);
                SparkJobRunner.Storage storage = hdfs.apply(running.imageTemplate(), cancelled);
                running = running.onStorage(address != null ? storage.withMetadataAddress(address) : storage);
            }
            return running;
        }

        @Override
        public void release() {
            if (image != null) {
                releaseImages.released(image);
            }
            if (address != null) {
                clusterStorage.releaseMetadataAddress(address);
            }
        }
    }

    // A step's thread: its driver runs to its end, or the step fails at once when Floci does not run its kind. A
    // cluster with Hive's or YARN's image is made first, when it is not there yet, so a failure to make it fails the
    // step.
    private void runStep(SparkJobRunner.Job job, StepLaunch launch, Supplier<String> releaseImage,
                         BiFunction<String, BooleanSupplier, SparkJobRunner.Storage> hdfs, String containerLogs,
                         Instance primary) {
        String failure;
        if (job == null) {
            failure = EmrSparkSteps.NOT_RUN;
        } else {
            SparkJobRunner.Outcome outcome;
            StepProgress progress = new StepProgress(launch);
            ClusterPreparation preparation = new ClusterPreparation(launch.clusterId(), launch.accountId(),
                    launch.region(), releaseImage, hdfs, primary);
            try {
                outcome = sparkRunner.run(preparation.prepare(job, progress::cancelled), progress);
            } catch (ClusterStorage.Stopped e) {
                // It stopped while its HDFS started, before its driver did, as a driver stopped then would.
                outcome = SparkJobRunner.Outcome.halted();
            } catch (RuntimeException e) {
                if (progress.cancelled() || Thread.currentThread().isInterrupted()) {
                    // A teardown, a cancel or Floci's shutdown cut its start short, as it would have a driver.
                    LOG.warnv(e, "EMR step {0} stopped while it started, as this failed: {1}", launch.stepId(),
                            e.getMessage());
                    outcome = SparkJobRunner.Outcome.halted();
                } else {
                    LOG.warnv(e, "EMR step {0} could not run", launch.stepId());
                    outcome = SparkJobRunner.Outcome.couldNotStart(e.getMessage());
                }
            } finally {
                preparation.release();
            }
            // A reset since the step started has swept the storage and wiped its cluster, so nothing would remove
            // volumes made, or mounted, after its sweep: they go now, the step's container being gone.
            if (job.storage() != null && resetSince(launch)) {
                clusterStorage.remove(launch.clusterId());
            }
            if (outcome.stopped()) {
                // A cancelled step's cluster-mode driver wrote only to its application's logs, which go in S3 before its
                // cluster's storage can; one that never ran has none, and Floci's own teardown leaves them on the node.
                if (containerLogs != null && progress.driverStarted && teardowns.get() == launch.teardownsBefore()) {
                    clusterStorage.archiveContainerLogs(launch.clusterId(), launch.accountId(), launch.region(),
                            containerLogs);
                }
                RequestScopes.runAs(launch.accountId(), launch.region(), () -> run(() -> driverStopped(launch)));
                return;
            }
            // Before the step ends, so its cluster-mode driver's output is there once it has: only the client's is in
            // its own stdout and stderr, as on EMR.
            if (containerLogs != null && outcome.failure() == null) {
                clusterStorage.archiveContainerLogs(launch.clusterId(), launch.accountId(), launch.region(),
                        containerLogs);
            }
            failure = failureOf(job, outcome);
        }
        // A teardown since the step was handed off ends it as it does a driver, whatever it ran to: ending it now
        // could start the next step after the teardown has passed. The service's lock orders the two.
        RequestScopes.runAs(launch.accountId(), launch.region(), () -> run(() -> {
            if (teardowns.get() != launch.teardownsBefore()) {
                driverStopped(launch);
            } else {
                stepEnded(launch, failure);
            }
        }));
    }

    /** A cluster handed to a bootstrap thread: whose it is, where, and under which reset. */
    private record BootstrapLaunch(String accountId, String region, String clusterId, long launchedIn,
                                   long teardownsBefore) {}

    /** Runs each cluster's bootstrap actions on a thread of its own, which ends its BOOTSTRAPPING. */
    private final class ContainerBootstrap implements EmrClusterLifecycle.BootstrapExecution {

        @Override
        public void start(EmrCluster cluster, String region) {
            String accountId = regionResolver.getAccountId();
            if (tornDown) {
                // Left BOOTSTRAPPING and counted as bootstrapping, as one the teardown stopped: the reset wipes it, a
                // restart ends it.
                bootstrapping.add(clusterKey(accountId, cluster.getId()));
                return;
            }
            String logFolder = bootstrapLogFolder(cluster, masterInstanceId(cluster));
            BootstrapRunner.Bootstrap bootstrap = new BootstrapRunner.Bootstrap(cluster.getId(), accountId, region,
                    cluster.getReleaseLabel(), imageTemplate, dockerNetwork, instanceProfileRole(cluster, accountId),
                    bootstrapActions(cluster), logFolder);
            BootstrapLaunch launch = new BootstrapLaunch(accountId, region, cluster.getId(), generation,
                    teardowns.get());
            Supplier<String> releaseImage = releaseImage(cluster);
            bootstrapping.add(clusterKey(accountId, cluster.getId()));
            Instance primary = nodeInstance(cluster, accountId, 0, 1);
            // Its core and task nodes run them too, each as itself, where they run in containers of their own.
            List<BootstrapNode> nodes = nodesToBootstrap(cluster, accountId);
            stepExecutor.execute(() -> runBootstrap(bootstrap, launch, releaseImage, primary, nodes));
        }

        @Override
        public void stop(EmrCluster cluster) {
            // Off the service's lock, as stopping removes the container; the bootstrap reads the end itself too. Past
            // BOOTSTRAPPING only the bootstraps of the nodes a resize added may be running.
            String id = cluster.getId();
            if (EmrClusterLifecycle.BOOTSTRAPPING.equals(cluster.getState())
                    || nodesBootstrapping.contains(clusterKey(regionResolver.getAccountId(), id))) {
                stepExecutor.execute(() -> bootstrapRunner.stop(id));
            }
        }
    }

    /**
     * What makes, on the thread that needs it, the image a cluster that installs Hive, or runs YARN, starts from, its
     * release's EMR Serverless image with its EMR Serverless Hive image's HDFS, MapReduce and YARN, and Hive for a
     * cluster with Hive, and returns the image's id: an EMR cluster's nodes hold every application it installs, so its
     * bootstrap actions, its steps and its primary node's YARN all run in it. A release of the AWS runtime for Apache
     * Spark has no EMR Serverless Hive image, so {@link #SPARK_RUNTIME_HDFS_RELEASE}'s gives its Hadoop. Null for a
     * cluster with neither, which starts from the release's EMR Serverless image.
     */
    Supplier<String> releaseImage(EmrCluster cluster) {
        if (releaseImages == null) {
            return null;
        }
        boolean withHive = withHiveImage(cluster);
        boolean withPig = withPig(cluster);
        if (!withHive && !withPig && !runsYarn(cluster)) {
            return null;
        }
        String release = cluster.getReleaseLabel();
        String spark = EmrServerlessSparkRunner.image(imageTemplate, release);
        String hive = hdfsImage(release);
        String trino = trinoImage(cluster);
        return () -> releaseImages.image(release, spark, hive, withHive, withPig, trino);
    }

    // Whether a cluster's image has Hive, and its primary node Hive's metastore service: it installs Hive or HCatalog,
    // or runs Floci's Trino, with which EMR installs hive-client and hcatalog-server.
    private boolean withHiveImage(EmrCluster cluster) {
        return EmrSparkSteps.installsHive(cluster.getReleaseLabel(), cluster.getApplications(), SNAPSHOT_MAPPER)
                || trinoImage(cluster) != null;
    }

    // Whether a cluster runs Floci's Trino: in the docker runner mode, on a network of the cluster's own, where its
    // primary node runs the metastore, with an image for its Trino (trinoImage).
    private boolean runsTrino(EmrCluster cluster) {
        return runsOnClusters() && clusterStorage.ownNetwork(dockerNetwork) && trinoImage(cluster) != null;
    }

    // The image a cluster's Trino runs from: it installs Trino, floci.services.emr.trino.enabled runs it, and its
    // release's Trino version is known (EmrTrino.image); null otherwise.
    private String trinoImage(EmrCluster cluster) {
        if (!trinoEnabled || !EmrSparkSteps.installsTrino(cluster.getReleaseLabel(), cluster.getApplications(),
                SNAPSHOT_MAPPER)) {
            return null;
        }
        return EmrTrino.image(trinoImageTemplate, cluster.getReleaseLabel());
    }

    // Whether a cluster's image has Floci's Pig: it installs Pig, and floci.services.emr.pig.enabled adds it.
    private boolean withPig(EmrCluster cluster) {
        return pigEnabled && EmrSparkSteps.installsPig(cluster.getReleaseLabel(), cluster.getApplications(),
                SNAPSHOT_MAPPER);
    }

    // The variables a step that is not a Hive step runs with: its cluster's spark-env exports and its own Properties
    // (see EmrSparkSteps.environment), for a command on a cluster with Floci's Pig, its Pig configuration, and on one
    // with Floci's Trino, where its trino-cli reaches the coordinator, unless trinoServer is null.
    private static Map<String, String> stepEnvironment(EmrStep step, EmrCluster cluster, boolean pig,
                                                       String trinoServer) {
        Map<String, String> variables = EmrSparkSteps.environment(step, cluster.getConfigurations(), SNAPSHOT_MAPPER);
        if (pig) {
            variables.putAll(EmrSparkSteps.pigEnvironment(cluster.getConfigurations(), SNAPSHOT_MAPPER));
        }
        if (trinoServer != null) {
            variables.put(EmrTrino.SERVER_VARIABLE, trinoServer);
        }
        return variables;
    }

    /**
     * The YARN a cluster runs: the service's capacity on each node, its core and task nodes, its HDFS's copies. Its
     * primary node holds a DataNode and a NodeManager unless the cluster was created with core nodes that run, so one
     * created without keeps both as core nodes are added, the blocks it holds staying in reach.
     */
    // The YARN timeline server runs on a cluster with Hadoop, and the Tez UI on one with Tez too, as their persistent
    // interfaces are there (EmrAppUiService), but for a release of the AWS runtime for Apache Spark; the Tez UI only
    // where the cluster's image has Tez, which one with Hive, Floci's Trino or Floci's Pig has (releaseImage).
    private ClusterStorage.Yarn yarnOf(EmrCluster cluster) {
        List<ClusterStorage.Node> nodes = workerNodes(cluster);
        boolean timeline = EmrSparkSteps.names(cluster.getApplications(), SNAPSHOT_MAPPER, "Hadoop")
                && !(cluster.getReleaseLabel() != null
                        && EmrServerlessSparkRunner.sparkRuntime(cluster.getReleaseLabel()));
        boolean tezImage = withHiveImage(cluster) || withPig(cluster);
        long coreNodes = nodes.stream().filter(ClusterStorage.Node::core).count();
        return nodeManager.withHadoopFolder(EmrReleaseImages.hadoopFolder(cluster.getReleaseLabel()))
                .onNodes(nodes, (int) Math.max(1, Math.min(replication(cluster), coreNodes)),
                        EmrSparkSteps.nodeLogFolder(cluster))
                .withPrimaryWorking(cluster.getProvisionedCoreNodes() == 0 || coreNodes == 0)
                .withSparkHistory(EmrSparkSteps.names(cluster.getApplications(), SNAPSHOT_MAPPER, "Spark"))
                .withHistories(timeline, timeline && tezImage
                        && EmrSparkSteps.names(cluster.getApplications(), SNAPSHOT_MAPPER, "Tez"));
    }

    // What a cluster asks of YARN when it is read, which may be later than now; what it asks now, if it has gone by then.
    private Supplier<ClusterStorage.Yarn> currentYarn(EmrCluster cluster, String accountId, String region) {
        String clusterId = cluster.getId();
        ClusterStorage.Yarn asked = yarnOf(cluster);
        return () -> RequestScopes.callAs(accountId, region,
                () -> call(() -> clusterStore.get(clusterId).map(this::yarnOf).orElse(asked)));
    }

    // A resize reaches the nodes of a cluster a step has started them on, on another thread, as they take a while;
    // on any other cluster the first step starts those it asks for then.
    private void resizeNodes(EmrCluster cluster) {
        if (cluster.isStorageCreated() && runsYarn(cluster)) {
            String accountId = regionResolver.getAccountId();
            String region = regionOf(cluster);
            Supplier<ClusterStorage.Yarn> yarn = currentYarn(cluster, accountId, region);
            String clusterId = cluster.getId();
            stepExecutor.execute(() -> {
                // A node it adds runs the cluster's bootstrap actions before it starts; one that failed them ends it.
                try {
                    bootstrapAddedNodes(clusterId, accountId, region);
                } catch (RuntimeException e) {
                    LOG.warnv(e, "Could not bootstrap the nodes added to EMR cluster {0}: {1}", clusterId,
                            e.getMessage());
                    return;
                }
                if (RequestScopes.callAs(accountId, region,
                        () -> call(() -> clusterStore.get(clusterId).filter(EmrService::nodesMayStart).isPresent()))) {
                    clusterStorage.resize(clusterId, yarn);
                }
            });
        }
    }

    // A cluster's core and task nodes as containers: "Core nodes run the Data Node daemon" and "a core node runs YARN
    // NodeManager daemons", and "Task nodes don't run the Data Node daemon". No more than the setting allows, core
    // nodes first, as they hold HDFS, and no fewer core nodes than HDFS keeps copies, as "Amazon EMR will not allow
    // clusters to scale core nodes below dfs.replication". A fleet's unit of capacity is one node. Neither kind is
    // built past the setting, however many a cluster asks for.
    List<ClusterStorage.Node> workerNodes(EmrCluster cluster) {
        int floor = Math.min(cluster.getProvisionedCoreNodes(), replication(cluster));
        List<ClusterStorage.Node> core = new ArrayList<>();
        List<ClusterStorage.Node> task = new ArrayList<>();
        long asked = 0;
        for (EmrInstanceGroup group : cluster.getInstanceGroups()) {
            boolean isCore = "CORE".equals(group.getInstanceGroupType());
            if (isCore || "TASK".equals(group.getInstanceGroupType())) {
                int count = group.getRequestedInstanceCount();
                count = isCore ? Math.max(count, floor) : count;
                asked += Math.max(count, 0);
                addNodes(cluster, groupNumber(cluster.getInstanceGroups(), group), count, isCore, isCore ? core : task,
                        maxWorkerNodes, group.getTerminatedOrdinals());
            }
        }
        for (EmrInstanceFleet fleet : cluster.getInstanceFleets()) {
            int number = fleetNumber(cluster.getInstanceFleets(), fleet);
            if (number > 0) {
                boolean isCore = "CORE".equals(fleet.getInstanceFleetType());
                long capacity = (long) fleet.getTargetOnDemandCapacity() + fleet.getTargetSpotCapacity();
                long count = isCore ? Math.max(capacity, floor) : capacity;
                asked += Math.max(count, 0);
                addNodes(cluster, number, count, isCore, isCore ? core : task, maxWorkerNodes, List.of());
            }
        }
        List<ClusterStorage.Node> nodes = new ArrayList<>(core);
        nodes.addAll(task);
        if (asked > maxWorkerNodes) {
            LOG.infov("EMR cluster {0} asks for {1} core and task nodes, and runs the first {2}, as yarn-max-worker-nodes"
                    + " says", cluster.getId(), asked, maxWorkerNodes);
            return List.copyOf(nodes.subList(0, Math.min(nodes.size(), maxWorkerNodes)));
        }
        return nodes;
    }

    // Up to count nodes of the group, as long as the list holds fewer than limit, none at an ordinal terminated holds.
    private static void addNodes(EmrCluster cluster, int group, long count, boolean core,
                                 List<ClusterStorage.Node> nodes, int limit, List<Integer> terminated) {
        PrimitiveIterator.OfLong ordinals = ordinalsFrom(terminated, 1);
        for (long k = 1; k <= count && nodes.size() < limit; k++) {
            long ordinal = ordinals.nextLong();
            String id = group + "-" + ordinal;
            nodes.add(new ClusterStorage.Node(id,
                    AwsRegions.ec2PrivateIpDnsName(privateIpAddress(group, ordinal), cluster.getRegion()), core,
                    cluster.getNodeBootstrapImages().get(id)));
        }
    }

    // "Amazon EMR sets this value based on the number of core nodes the cluster is provisioned with": 1 "for clusters
    // that are provisioned with less than four core nodes", 2 "for clusters that are provisioned with less than ten
    // core nodes", 3 "for all other clusters". "To overwrite the default value, use the hdfs-site classification."
    private int replication(EmrCluster cluster) {
        String configured = EmrSparkSteps.hdfsSite(cluster.getConfigurations(), SNAPSHOT_MAPPER).get("dfs.replication");
        if (configured != null) {
            Integer copies = aboveZero(configured);
            if (copies != null) {
                return copies;
            }
            LOG.warnv("The dfs.replication of EMR cluster {0}, {1}, is not a whole number above zero, so it keeps EMR''s"
                    + " default", cluster.getId(), configured);
        }
        int core = cluster.getProvisionedCoreNodes();
        return core < 4 ? 1 : core < 10 ? 2 : 3;
    }

    // The whole number a setting gives, when it is one above zero, else null.
    private static Integer aboveZero(String setting) {
        try {
            int number = Integer.parseInt(setting.trim());
            return number > 0 ? number : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int coreNodes(EmrCluster cluster) {
        int groups = cluster.getInstanceGroups().stream().filter(group -> "CORE".equals(group.getInstanceGroupType()))
                .mapToInt(EmrInstanceGroup::getRequestedInstanceCount).sum();
        int fleets = cluster.getInstanceFleets().stream().filter(fleet -> "CORE".equals(fleet.getInstanceFleetType()))
                .mapToInt(fleet -> fleet.getTargetOnDemandCapacity() + fleet.getTargetSpotCapacity()).sum();
        return groups + fleets;
    }

    /**
     * Whether a cluster's primary node runs YARN, which its steps' Spark runs on: "the primary node runs the YARN
     * ResourceManager service", and a core node "YARN NodeManager daemons, Hadoop MapReduce tasks, and Spark executors".
     * It does on a network of the cluster's own, as its metastore does. A cluster bootstrapped by an earlier Floci runs
     * from an image that may hold no YARN, so it keeps to local mode, its HDFS in the release's Hive image, as it did.
     */
    private boolean runsYarn(EmrCluster cluster) {
        return releaseImages != null && clusterStorage != null && clusterStorage.ownNetwork(dockerNetwork)
                && (cluster.getBootstrapImage() == null || cluster.isBootstrapImageRunsYarn());
    }

    /**
     * The image a cluster runs its HDFS in: its release's EMR Serverless Hive image, which has HDFS's daemons, or, for
     * a release of the AWS runtime for Apache Spark, which has no Hive image, {@link #SPARK_RUNTIME_HDFS_RELEASE}'s.
     */
    private String hdfsImage(String releaseLabel) {
        String release = EmrServerlessSparkRunner.sparkRuntime(releaseLabel) ? SPARK_RUNTIME_HDFS_RELEASE
                : releaseLabel;
        return EmrServerlessSparkRunner.image(hiveImageTemplate, release, SparkJobRunner.Engine.HIVE);
    }

    // A cluster's BootstrapActionConfig list, as RunJobFlow stored it once it had checked it.
    private static List<BootstrapRunner.Action> bootstrapActions(EmrCluster cluster) {
        List<BootstrapRunner.Action> actions = new ArrayList<>();
        try {
            for (JsonNode action : SNAPSHOT_MAPPER.readTree(cluster.getBootstrapActions())) {
                JsonNode script = action.path("ScriptBootstrapAction");
                List<String> args = new ArrayList<>();
                script.path("Args").forEach(arg -> args.add(arg.asText()));
                actions.add(new BootstrapRunner.Action(action.path("Name").asText(), script.path("Path").asText(),
                        args));
            }
        } catch (JsonProcessingException ignored) {
            // RunJobFlow stored the list as it came, and checked that it is one.
        }
        return actions;
    }

    /**
     * A link-local address on the cluster's network, where its instance metadata relay, started first in the image the
     * container runs, answers a container given it as {@code instance}; null where the cluster's containers have no
     * such network. A relay that cannot start is thrown, so what would read it fails, as no EC2 instance runs without
     * its metadata.
     */
    private MetadataPlace metadataPlace(String clusterId, String accountId, String region, String imageTemplate,
                                        String releaseLabel, Instance instance) {
        if (clusterStorage == null || instance == null) {
            return null;
        }
        try {
            String network = clusterStorage.metadataNetwork(clusterId, accountId, region,
                    EmrServerlessSparkRunner.image(imageTemplate, releaseLabel), dockerNetwork);
            String address = network == null ? null : clusterStorage.metadataAddress(clusterId, instance);
            return address == null ? null : new MetadataPlace(network, address);
        } catch (RuntimeException e) {
            throw new IllegalStateException("The instance metadata of EMR cluster " + clusterId + " could not start: "
                    + e.getMessage(), e);
        }
    }

    /** Where a container reads its node's instance metadata: its link-local address on its cluster's network. */
    private record MetadataPlace(String network, String address) {}

    // A cluster's bootstrap thread: its actions run to their end, which then ends its BOOTSTRAPPING. They run in
    // a cluster with Hive's image, which is made first when it is not there yet, unless the cluster ended, or a reset
    // removed it, before the thread ran; a failure to make it ends the cluster as one to run the bootstrap does.
    private void runBootstrap(BootstrapRunner.Bootstrap bootstrap, BootstrapLaunch launch,
                              Supplier<String> releaseImage, Instance primary, List<BootstrapNode> nodes) {
        // A teardown since the bootstrap was handed off stops it as it does a step, before it runs or as it goes.
        BooleanSupplier cancelled = () -> teardowns.get() != launch.teardownsBefore()
                || !RequestScopes.callAs(launch.accountId(), launch.region(), () -> stillBootstrapping(launch));
        BootstrapRunner.Outcome outcome;
        String image = null;
        String address = null;
        boolean metadataMade = false;
        List<CompletableFuture<NodeRun>> onNodes = List.of();
        List<NodeRun> nodeRuns = List.of();
        // Set once the primary node's actions have failed, which ends the cluster, so its nodes' stop then.
        AtomicBoolean primaryFailed = new AtomicBoolean();
        BooleanSupplier nodesCancelled = () -> primaryFailed.get() || cancelled.getAsBoolean();
        try {
            try {
                image = releaseImage != null && !cancelled.getAsBoolean() ? releaseImage.get() : null;
                BootstrapRunner.Bootstrap running = image != null ? bootstrap.inImage(image) : bootstrap;
                // The actions run as on the primary node, whose instance metadata they read.
                MetadataPlace place = null;
                if (!cancelled.getAsBoolean()) {
                    metadataMade = clusterStorage != null && primary != null
                            && clusterStorage.metadataServed(dockerNetwork);
                    place = metadataPlace(running.clusterId(), running.accountId(), running.region(),
                            running.imageTemplate(), running.releaseLabel(), primary);
                }
                address = place != null ? place.address() : null;
                onNodes = startNodeBootstraps(running, nodes, place != null ? place.network() : null,
                        nodesCancelled);
                outcome = bootstrapRunner.run(place != null ? running.onMetadata(place.network(), place.address())
                        : running, cancelled);
            } catch (RuntimeException e) {
                LOG.warnv(e, "The bootstrap actions of EMR cluster {0} could not run", launch.clusterId());
                outcome = BootstrapRunner.Outcome.couldNotRun(e.getMessage());
            }
            if (outcome.image() == null && !outcome.stopped() && !onNodes.isEmpty()) {
                primaryFailed.set(true);
                bootstrapRunner.stop(launch.clusterId());
            }
        } finally {
            // The nodes' bootstraps end before the image they run in is let go.
            nodeRuns = onNodes.stream().map(CompletableFuture::join).toList();
            // An image it kept is built on this one, which Docker then keeps, so a newer one may remove it only as that
            // image goes.
            if (image != null) {
                releaseImages.released(image);
            }
            if (address != null) {
                clusterStorage.releaseMetadataAddress(address);
            }
        }
        BootstrapRunner.Outcome ended = outcome;
        boolean storageMade = metadataMade;
        List<NodeRun> runs = nodeRuns;
        // One that ended, however, after a teardown began ends as one Floci stopped, which starts no step: the
        // service's lock orders the two.
        AtomicBoolean tornDownSince = new AtomicBoolean();
        RequestScopes.runAs(launch.accountId(), launch.region(), () -> run(() -> {
            tornDownSince.set(teardowns.get() != launch.teardownsBefore());
            bootstrapEnded(bootstrap, launch, tornDownSince.get() ? BootstrapRunner.Outcome.halted() : ended,
                    storageMade, tornDownSince.get() ? List.of() : runs);
        }));
        // The images it committed as the teardown began go, as no cluster starts from them.
        if (tornDownSince.get()) {
            if (ended.image() != null) {
                bootstrapRunner.removeImage(ended.image());
            }
            runs.stream().map(run -> run.outcome().image()).filter(Objects::nonNull)
                    .forEach(bootstrapRunner::removeImage);
        }
    }

    // Under the service's lock: the images nodes' bootstraps left are the cluster's, each the one its node runs from
    // where the cluster still has the node, else kept until the cluster ends, as one a resize took away while its
    // bootstrap ran, so one added again in its place runs the actions again, as a new instance would.
    private void recordNodeImages(EmrCluster cluster, Map<String, String> images) {
        Set<String> wanted = new HashSet<>();
        workerNodes(cluster).forEach(node -> wanted.add(node.id()));
        images.forEach((node, image) -> {
            if (!wanted.contains(node)) {
                cluster.getRetiredBootstrapImages().add(image);
                return;
            }
            String replaced = cluster.getNodeBootstrapImages().put(node, image);
            if (replaced != null && !replaced.equals(image)) {
                cluster.getRetiredBootstrapImages().add(replaced);
            }
        });
    }

    /** A core or task node a cluster's bootstrap actions run on: the node, the EC2 instance it is, its log folder. */
    private record BootstrapNode(ClusterStorage.Node node, Instance instance, String logFolder) {}

    /** How a core or task node's bootstrap ended. */
    private record NodeRun(BootstrapNode node, BootstrapRunner.Outcome outcome) {}

    /** Why a cluster ends: its reason's code and message. */
    private record EndReason(String code, String message) {}

    // "logs/<cluster-id>/node/<instance-id>/bootstrap-actions/<action-number>/stdout.gz", each node's under its own
    // instance.
    private static String bootstrapLogFolder(EmrCluster cluster, String instanceId) {
        return EmrServerlessSparkRunner.logFolder(cluster.getLogUri(),
                cluster.getId() + "/node/" + instanceId + "/bootstrap-actions/");
    }

    // A cluster's core and task nodes that run its bootstrap actions and have no image of their own yet: on a cluster
    // whose nodes run in containers of their own (see runsYarn), none elsewhere.
    private List<BootstrapNode> nodesToBootstrap(EmrCluster cluster, String accountId) {
        if (!runsYarn(cluster)) {
            return List.of();
        }
        List<BootstrapNode> nodes = new ArrayList<>();
        for (ClusterStorage.Node node : workerNodes(cluster)) {
            if (node.image() == null) {
                int dash = node.id().indexOf('-');
                Instance instance = nodeInstance(cluster, accountId, Integer.parseInt(node.id().substring(0, dash)),
                        Long.parseLong(node.id().substring(dash + 1)));
                nodes.add(new BootstrapNode(node, instance, bootstrapLogFolder(cluster, instance.getInstanceId())));
            }
        }
        return nodes;
    }

    // Each node's bootstrap, on a thread of its own, in running's image, as that node, on network where the cluster's
    // relay serves its metadata.
    private List<CompletableFuture<NodeRun>> startNodeBootstraps(BootstrapRunner.Bootstrap running,
                                                                List<BootstrapNode> nodes, String network,
                                                                BooleanSupplier cancelled) {
        List<CompletableFuture<NodeRun>> started = new ArrayList<>();
        for (BootstrapNode node : nodes) {
            BootstrapRunner.Bootstrap onNode = running.onNode(node.node().id(), node.node().core() ? "CORE" : "TASK",
                    node.logFolder());
            started.add(CompletableFuture.supplyAsync(
                    () -> new NodeRun(node, bootstrapNode(onNode, node.instance(), network, cancelled)),
                    NODE_BOOTSTRAPS));
        }
        return started;
    }

    // A node's bootstrap, at an address of its own on network, where the relay answers it as instance. One whose
    // actions fail runs them again in a fresh container, as EMR "attempts to reallocate the failed instances".
    private BootstrapRunner.Outcome bootstrapNode(BootstrapRunner.Bootstrap bootstrap, Instance instance,
                                                  String network, BooleanSupplier cancelled) {
        BootstrapRunner.Outcome outcome = BootstrapRunner.Outcome.halted();
        for (int attempt = 1; attempt <= NODE_BOOTSTRAP_ATTEMPTS && !cancelled.getAsBoolean(); attempt++) {
            String address = null;
            try {
                address = network != null ? clusterStorage.metadataAddress(bootstrap.clusterId(), instance) : null;
                outcome = bootstrapRunner.run(address != null ? bootstrap.onMetadata(network, address) : bootstrap,
                        cancelled);
            } catch (RuntimeException e) {
                LOG.warnv(e, "The bootstrap actions of node {0} of EMR cluster {1} could not run", bootstrap.node(),
                        bootstrap.clusterId());
                outcome = BootstrapRunner.Outcome.couldNotRun(e.getMessage());
            } finally {
                if (address != null) {
                    clusterStorage.releaseMetadataAddress(address);
                }
            }
            if (outcome.image() != null || outcome.stopped()) {
                break;
            }
            LOG.infov("The bootstrap actions of node {0} of EMR cluster {1} failed (attempt {2})", bootstrap.node(),
                    bootstrap.clusterId(), attempt);
        }
        return outcome;
    }

    // The reason a cluster ends as a node's bootstrap failed, which AWS gives no words for: an action that exited with
    // another code than 0, one whose script could not be read, or a bootstrap that could not run.
    private static EndReason nodeFailure(NodeRun failed, BootstrapRunner.Bootstrap bootstrap) {
        BootstrapRunner.Outcome outcome = failed.outcome();
        String role = failed.node().node().core() ? "core" : "task";
        String instance = failed.node().instance().getInstanceId();
        if (outcome.exitCode() != null) {
            return new EndReason(EmrClusterLifecycle.BOOTSTRAP_FAILURE,
                    NODE_ACTION_FAILED.formatted(role, instance, outcome.failedAction()));
        }
        if (outcome.failedAction() != null) {
            return new EndReason(EmrClusterLifecycle.BOOTSTRAP_FAILURE, BOOTSTRAP_ACTION_NOT_READ.formatted(role,
                    instance, outcome.failedAction(), bootstrap.actions().get(outcome.failedAction() - 1).path(),
                    outcome.failure()));
        }
        return new EndReason(EmrClusterLifecycle.INTERNAL_ERROR, BOOTSTRAP_NOT_RUN.formatted(outcome.failure()));
    }

    // Removes images no cluster names, off the service's lock.
    private void dropImages(String image, Collection<String> nodeImages) {
        List<String> images = new ArrayList<>(nodeImages);
        if (image != null) {
            images.add(image);
        }
        for (String dropped : images) {
            stepExecutor.execute(() -> bootstrapRunner.removeImage(dropped));
        }
    }

    /**
     * Runs a cluster's bootstrap actions on each of its core and task nodes that has no image of its own yet, one a
     * resize added since its bootstrap, before it starts, as "If you add nodes to a running cluster, bootstrap actions
     * also run on those nodes in the same way", each as at the cluster's start; and lets the images of the nodes it no
     * longer has go, so one added again runs them again, as a new instance would. One whose actions fail twice ends the
     * cluster, as at its start.
     */
    private void bootstrapAddedNodes(String clusterId, String accountId, String region) {
        if (bootstrapRunner == null) {
            return;
        }
        synchronized (nodeBootstrapLocks.computeIfAbsent(clusterKey(accountId, clusterId), key -> new Object())) {
            AddedNodes added = RequestScopes.callAs(accountId, region,
                    () -> call(() -> addedNodes(clusterId, accountId)));
            if (added == null) {
                return;
            }
            if (added.nodes().isEmpty()) {
                return;
            }
            long teardown = teardowns.get();
            BooleanSupplier cancelled = () -> teardowns.get() != teardown
                    || !RequestScopes.callAs(accountId, region, () -> call(() -> clusterStore.get(clusterId)
                            .filter(EmrService::nodesMayStart).isPresent()));
            String image = null;
            List<NodeRun> runs;
            String key = clusterKey(accountId, clusterId);
            nodesBootstrapping.add(key);
            try {
                image = added.releaseImage() != null ? added.releaseImage().get() : null;
                BootstrapRunner.Bootstrap running = image != null ? added.bootstrap().inImage(image)
                        : added.bootstrap();
                String network = clusterStorage.metadataNetwork(clusterId, accountId, region,
                        EmrServerlessSparkRunner.image(running.imageTemplate(), running.releaseLabel()), dockerNetwork);
                runs = startNodeBootstraps(running, added.nodes(), network, cancelled).stream()
                        .map(CompletableFuture::join).toList();
            } catch (RuntimeException e) {
                // As a bootstrap that cannot run at the cluster's start: the nodes cannot start, and the cluster ends.
                LOG.warnv(e, "The bootstrap actions of the nodes added to EMR cluster {0} could not run", clusterId);
                String why = e.getMessage();
                runs = added.nodes().stream().map(node -> new NodeRun(node, BootstrapRunner.Outcome.couldNotRun(why)))
                        .toList();
            } finally {
                nodesBootstrapping.remove(key);
                if (image != null) {
                    releaseImages.released(image);
                }
            }
            List<NodeRun> ended = runs;
            RequestScopes.runAs(accountId, region,
                    () -> run(() -> addedNodesBootstrapped(clusterId, ended, added.bootstrap())));
        }
    }

    /** The nodes a cluster's bootstrap actions run on as a resize added them. */
    private record AddedNodes(BootstrapRunner.Bootstrap bootstrap, Supplier<String> releaseImage,
                              List<BootstrapNode> nodes) {}

    // Under the service's lock: the nodes bootstrapAddedNodes runs a cluster's bootstrap actions on; null for a cluster
    // whose nodes run none of their own.
    private AddedNodes addedNodes(String clusterId, String accountId) {
        Optional<EmrCluster> found = clusterStore.get(clusterId).filter(EmrService::nodesMayStart);
        if (found.isEmpty() || found.get().getBootstrapImage() == null || !runsYarn(found.get())) {
            return null;
        }
        EmrCluster cluster = found.get();
        // A node it no longer has keeps its image until the cluster ends, which one being decommissioned may run from.
        Set<String> wanted = new HashSet<>();
        workerNodes(cluster).forEach(node -> wanted.add(node.id()));
        List<String> retired = cluster.getRetiredBootstrapImages();
        if (cluster.getNodeBootstrapImages().entrySet().removeIf(entry -> !wanted.contains(entry.getKey())
                && retired.add(entry.getValue()))) {
            clusterStore.put(clusterId, cluster);
        }
        BootstrapRunner.Bootstrap bootstrap = new BootstrapRunner.Bootstrap(clusterId, accountId, regionOf(cluster),
                cluster.getReleaseLabel(), imageTemplate, dockerNetwork, instanceProfileRole(cluster, accountId),
                bootstrapActions(cluster), null);
        return new AddedNodes(bootstrap, releaseImage(cluster), nodesToBootstrap(cluster, accountId));
    }

    // Whether a cluster may still start nodes: not one that is ending or has ended.
    private static boolean nodesMayStart(EmrCluster cluster) {
        return !EmrClusterLifecycle.TERMINATING.equals(cluster.getState())
                && !EmrClusterLifecycle.isTerminated(cluster.getState());
    }

    // Under the service's lock: the images the added nodes' bootstraps left are the cluster's, and one whose actions
    // failed twice ends it; a cluster that is gone keeps none.
    private void addedNodesBootstrapped(String clusterId, List<NodeRun> runs, BootstrapRunner.Bootstrap bootstrap) {
        Map<String, String> images = new LinkedHashMap<>();
        NodeRun failed = null;
        for (NodeRun run : runs) {
            if (run.outcome().image() != null) {
                images.put(run.node().node().id(), run.outcome().image());
            } else if (failed == null && !run.outcome().stopped()) {
                failed = run;
            }
        }
        Optional<EmrCluster> found = clusterStore.get(clusterId);
        if (found.isEmpty()) {
            dropImages(null, images.values());
            return;
        }
        EmrCluster cluster = found.get();
        recordNodeImages(cluster, images);
        if (failed != null && nodesMayStart(cluster)) {
            EndReason reason = nodeFailure(failed, bootstrap);
            lifecycle.terminate(cluster, reason.code(), reason.message(), now(), regionOf(cluster));
        }
        store(cluster);
    }

    // Whether a cluster's bootstrap may still start, or go on: one that ended, or a reset removed, is past it.
    private synchronized boolean stillBootstrapping(BootstrapLaunch launch) {
        return launch.launchedIn() == generation
                && clusterStore.get(launch.clusterId()).filter(EmrService::bootstrapsInContainer).isPresent();
    }

    /**
     * Ends a cluster's BOOTSTRAPPING as its bootstrap ended: RUNNING when every action succeeded, or ended,
     * with errors, when one failed or they could not run. A cluster that ended meanwhile, or that a reset
     * since removed, stays as it is, and an image its bootstrap left goes. One Floci itself stopped, by a
     * reset's teardown or its shutdown, is left BOOTSTRAPPING and still counted as bootstrapping: the reset
     * wipes it, and a restart ends it.
     */
    private void bootstrapEnded(BootstrapRunner.Bootstrap bootstrap, BootstrapLaunch launch,
                                BootstrapRunner.Outcome outcome, boolean storageMade, List<NodeRun> nodeRuns) {
        Optional<EmrCluster> current = launch.launchedIn() == generation
                ? findCluster(launch.clusterId()) : Optional.empty();
        Optional<EmrCluster> found = current.filter(EmrService::bootstrapsInContainer);
        if (storageMade && current.isPresent() && !current.get().isStorageCreated()) {
            // The network and the instance metadata relay its bootstrap had are the cluster's storage, which goes as
            // the cluster ends, though no step of it runs, and now on one that ended meanwhile.
            EmrCluster made = current.get();
            made.setStorageCreated(true);
            clusterStore.put(made.getId(), made);
            releaseStorage(made);
        }
        // The images the core and task nodes' bootstraps left, and the first of them whose actions failed.
        Map<String, String> nodeImages = new LinkedHashMap<>();
        NodeRun failedNode = null;
        boolean nodeStopped = false;
        for (NodeRun run : nodeRuns) {
            if (run.outcome().image() != null) {
                nodeImages.put(run.node().node().id(), run.outcome().image());
            } else if (run.outcome().stopped()) {
                nodeStopped = true;
            } else if (failedNode == null) {
                failedNode = run;
            }
        }
        // A node's bootstrap stopped as the primary node's failed ends the cluster with the primary node's failure.
        if ((outcome.stopped() || nodeStopped && outcome.image() != null) && found.isPresent()) {
            dropImages(outcome.image(), nodeImages.values());
            return;
        }
        bootstrapping.remove(clusterKey(launch.accountId(), launch.clusterId()));
        if (found.isEmpty()) {
            if (current.isPresent() && (outcome.image() != null || !nodeImages.isEmpty())) {
                // The cluster ended meanwhile: the images go as an ended cluster's do.
                if (outcome.image() != null) {
                    current.get().setBootstrapImage(outcome.image());
                }
                recordNodeImages(current.get(), nodeImages);
                store(current.get());
            } else if (current.isEmpty()) {
                dropImages(outcome.image(), nodeImages.values());
            }
            return;
        }
        EmrCluster cluster = found.get();
        // Each node's image is the cluster's, which it runs from, or which goes as the cluster ends.
        recordNodeImages(cluster, nodeImages);
        String master = masterInstanceId(cluster);
        String code = EmrClusterLifecycle.BOOTSTRAP_FAILURE;
        String message;
        List<EmrErrorDetail> errorDetails = List.of();
        if (outcome.image() != null && failedNode != null) {
            // A node failed where the primary node did not: the cluster ends, the primary node's image going with it.
            cluster.setBootstrapImage(outcome.image());
            EndReason reason = nodeFailure(failedNode, bootstrap);
            lifecycle.bootstrapEnded(cluster, null, reason.code(), reason.message(), List.of(), now(),
                    regionOf(cluster));
            store(cluster);
            LOG.infov("The bootstrap actions of EMR cluster {0} failed on node {1}; it is {2}", cluster.getId(),
                    failedNode.node().node().id(), cluster.getState());
            return;
        }
        if (outcome.image() != null) {
            code = null;
            message = null;
            // Asked before the image is the cluster's, while it says only that the release's image made this one.
            cluster.setBootstrapImageRunsYarn(runsYarn(cluster));
        } else if (outcome.exitCode() != null) {
            message = BOOTSTRAP_ACTION_FAILED.formatted(master, outcome.failedAction());
            errorDetails = List.of(bootstrapErrorDetail(BOOTSTRAP_FAILURE_PRIMARY_WITH_NON_ZERO_CODE, master,
                    outcome.failedAction(), outcome.exitCode(), bootstrap, message));
        } else if (outcome.failedAction() != null) {
            message = BOOTSTRAP_ACTION_NOT_READ.formatted("master", master, outcome.failedAction(),
                    bootstrap.actions().get(outcome.failedAction() - 1).path(), outcome.failure());
            errorDetails = List.of(bootstrapErrorDetail(BOOTSTRAP_FAILURE_BA_DOWNLOAD_FAILED_PRIMARY, master,
                    outcome.failedAction(), null, bootstrap, message));
        } else {
            code = EmrClusterLifecycle.INTERNAL_ERROR;
            message = BOOTSTRAP_NOT_RUN.formatted(outcome.failure());
        }
        lifecycle.bootstrapEnded(cluster, outcome.image(), code, message, errorDetails, now(), regionOf(cluster));
        store(cluster);
        LOG.infov("The bootstrap actions of EMR cluster {0} ended; it is {1}", cluster.getId(), cluster.getState());
    }

    /**
     * The ErrorDetail of bootstrap action {@code action}, counted from 1, that failed on the primary node
     * {@code master} for {@code code}, with the ErrorData its Management Guide page gives: the primary node, the
     * action, its return code when it ran ({@code returnCode}, null when it did not), its script and the page itself.
     * The model makes ErrorData a list of maps, and no source shows how the keys spread over them: Floci puts them all
     * in one, which a client that reads the first map and one that merges them all read alike. ErrorMessage is
     * "Description of the error with a link to more information in the Amazon EMR documentation", whose text AWS does
     * not give: Floci's is the cluster's reason, {@code message}, and the page.
     */
    private static EmrErrorDetail bootstrapErrorDetail(String code, String master, int action, Integer returnCode,
                                                       BootstrapRunner.Bootstrap bootstrap, String message) {
        String page = ERROR_CODE_PAGE.formatted(code);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("primary-instance-id", master);
        data.put("bootstrap-action", String.valueOf(action));
        if (returnCode != null) {
            data.put("return-code", String.valueOf(returnCode));
        }
        data.put("amazon-s3-path", bootstrap.actions().get(action - 1).path());
        data.put("public-doc", page);
        String sentence = message.endsWith(".") ? message : message + ".";
        return new EmrErrorDetail(code, List.of(data), sentence + " For more information, see " + page);
    }

    /** The EC2 instance id ListInstances gives a cluster's instance {@code index}, counted from 1. */
    static String ec2InstanceId(long index) {
        return "i-" + String.format("%017d", index);
    }

    /** The public DNS name ListInstances gives a cluster's instance {@code index}, counted from 1, in a Region. */
    static String publicDnsName(long index, String region) {
        return AwsEndpoints.ec2PublicDns(publicIpAddress(index), region);
    }

    /**
     * The public address ListInstances gives a cluster's instance {@code index}, counted from 1, the one its public DNS
     * name names: in 203.0.113.0/24, RFC 5737's documentation range, for the first 254, and past them in
     * 100.64.0.0/10, RFC 6598's shared address space, so no instance gets an address someone else holds.
     */
    static String publicIpAddress(long index) {
        if (index <= 254) {
            return "203.0.113." + index;
        }
        long n = index - 255;
        return "100." + (64 + n / (254 * 256)) + "." + (n / 254) % 256 + "." + (n % 254 + 1);
    }

    /**
     * The id ListInstances gives the {@code device}-th EBS volume, counted from 0, of cluster {@code clusterId}'s
     * instance {@code index}: 17 hex digits, as EC2's are, the first 8 from the cluster, so two clusters' volumes
     * differ.
     */
    static String ebsVolumeId(String clusterId, long index, int device) {
        return "vol-" + String.format("%08x%09x", clusterId.hashCode(), index * 100 + device + 1);
    }

    /**
     * The ordinal, counted from 1, of a group's {@code k}-th instance, when EC2InstanceIdsToTerminate has ended its
     * instances {@code terminated} (ascending): the {@code k}-th ordinal that is not one of them, so an instance keeps
     * its ordinal, and with it its id and addresses, while another of its group is ended.
     */
    static long ordinalAt(List<Integer> terminated, long k) {
        long ordinal = k;
        for (int gone : terminated) {
            if (gone > ordinal) {
                break;
            }
            ordinal++;
        }
        return ordinal;
    }

    /**
     * A group's instance ordinals from its {@code k}-th on, in order, as {@link #ordinalAt} gives each: the terminated
     * ones before the {@code k}-th are passed once, and each later one as the walk reaches it, so a page of instances
     * costs its own length and not that times the group's terminations.
     */
    static PrimitiveIterator.OfLong ordinalsFrom(List<Integer> terminated, long k) {
        long first = ordinalAt(terminated, k);
        return new PrimitiveIterator.OfLong() {
            private long next = first;
            // The terminated ordinals passed so far: as many as the first ordinal is past k.
            private int passed = (int) (first - k);

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public long nextLong() {
                long current = next;
                next = current + 1;
                while (passed < terminated.size() && terminated.get(passed) == next) {
                    next++;
                    passed++;
                }
                return current;
            }
        };
    }

    /**
     * The private IPv4 address of the instance {@code ordinal}, counted from 1, of a cluster's instance group numbered
     * {@code group}: an instance keeps it while its cluster's other groups resize, as an EC2 instance does.
     */
    static String privateIpAddress(int group, long ordinal) {
        return "10." + group + "." + (ordinal - 1) / 254 + "." + ((ordinal - 1) % 254 + 1);
    }

    /**
     * A cluster's instance group's number in its instances' addresses: 0 for the primary node's group, the first MASTER
     * group, and from 1 for the others in the order the cluster has them, which only adds to the end.
     */
    static int groupNumber(List<EmrInstanceGroup> groups, EmrInstanceGroup group) {
        return numberAmong(groups, group, EmrService::isMasterGroup);
    }

    /** A cluster's instance fleet's number in its instances' addresses, as {@link #groupNumber} gives a group's. */
    static int fleetNumber(List<EmrInstanceFleet> fleets, EmrInstanceFleet fleet) {
        return numberAmong(fleets, fleet, EmrService::isMasterFleet);
    }

    private static <T> int numberAmong(List<T> all, T one, Predicate<T> isMaster) {
        T primary = all.stream().filter(isMaster).findFirst().orElse(null);
        if (one == primary) {
            return 0;
        }
        int number = 1;
        for (T other : all) {
            if (other == one) {
                return number;
            }
            if (other != primary) {
                number++;
            }
        }
        throw new IllegalArgumentException("The instance group or fleet is not one of the cluster's");
    }

    /**
     * The EC2 instance that a cluster's instance {@code ordinal}, counted from 1, of its group or fleet numbered
     * {@code group} is, as ListInstances gives it and its instance metadata answers: its id, the cluster's account as
     * its owner, its instance type, private address and name, public name, the cluster's availability zone, and the
     * cluster's EC2 instance profile ({@code JobFlowRole}).
     */
    Instance nodeInstance(EmrCluster cluster, String accountId, int group, long ordinal) {
        List<EmrHandler.ListedInstances> listed = EmrHandler.listedInstances(cluster, false);
        long index = ordinal;
        String instanceType = null;
        String imageId = cluster.getCustomAmiId();
        for (EmrHandler.ListedInstances set : listed) {
            if (set.number() == group) {
                index = firstIndex(listed, set) + ordinal - 1;
                instanceType = set.instanceType();
                // A group's own CustomAmiId is the one its instances run, over the cluster's.
                for (EmrInstanceGroup instanceGroup : cluster.getInstanceGroups()) {
                    if (set.groupId() != null && set.groupId().equals(instanceGroup.getId())
                            && instanceGroup.getCustomAmiId() != null) {
                        imageId = instanceGroup.getCustomAmiId();
                    }
                }
                break;
            }
        }
        Instance instance = new Instance();
        instance.setInstanceId(ec2InstanceId(index));
        instance.setOwnerId(accountId);
        instance.setInstanceType(instanceType);
        instance.setImageId(imageId);
        String address = privateIpAddress(group, ordinal);
        instance.setPrivateIpAddress(address);
        instance.setPrivateDnsName(AwsRegions.ec2PrivateIpDnsName(address, cluster.getRegion()));
        instance.setPublicDnsName(publicDnsName(index, cluster.getRegion()));
        instance.setPublicIpAddress(publicIpAddress(index));
        instance.setPlacement(new Placement(availabilityZone(cluster)));
        instance.setRegion(cluster.getRegion());
        instance.setState(InstanceState.running());
        String profile = cluster.getJobFlowRole();
        if (profile != null && !profile.isBlank()) {
            instance.setIamInstanceProfileArn(AwsArnUtils.looksLikeArn(profile) ? profile
                    : AwsArnUtils.Arn.global(regionResolver.partitionForRegion(cluster.getRegion()), "iam", accountId,
                            "instance-profile/" + profile).toString());
            // As IAM shapes a profile's id, which the metadata reports where IAM holds no such profile.
            instance.setIamInstanceProfileId("AIPA" + instance.getInstanceId().substring(2));
        }
        return instance;
    }

    // The EC2 instance a cluster's node, named as ClusterStorage.identify has it, is as the cluster stands now; null for
    // a cluster that is gone.
    private Instance nodeIdentity(String clusterId, String accountId, String region, String node) {
        int dash = node.indexOf('-');
        int group = Integer.parseInt(node.substring(0, dash));
        long ordinal = Long.parseLong(node.substring(dash + 1));
        return RequestScopes.callAs(accountId, region, () -> call(() -> clusterStore.get(clusterId)
                .map(cluster -> nodeInstance(cluster, accountId, group, ordinal)).orElse(null)));
    }

    /**
     * The availability zone a cluster's instances are in: the one DescribeCluster's {@code Ec2InstanceAttributes} gives,
     * else its Region's first.
     */
    static String availabilityZone(EmrCluster cluster) {
        String recorded = null;
        if (cluster.getEc2Attributes() != null) {
            try {
                recorded = SNAPSHOT_MAPPER.readTree(cluster.getEc2Attributes()).path("Ec2AvailabilityZone").asText(null);
            } catch (JsonProcessingException ignored) {
                // RunJobFlow stored them as an object; one an earlier Floci stored without a zone gets the Region's.
            }
        }
        return recorded != null && !recorded.isBlank() ? recorded : cluster.getRegion() + "a";
    }

    // The primary instance's: the first of the MASTER group or fleet, which ListInstances numbers before the others.
    static String masterInstanceId(EmrCluster cluster) {
        return ec2InstanceId(1);
    }

    // The primary instance's private DNS name, as ListInstances gives it, which names the cluster's HDFS: the same
    // while the other groups resize, so an hdfs:// path naming it keeps leading there.
    static String masterPrivateDnsName(EmrCluster cluster) {
        return AwsRegions.ec2PrivateIpDnsName(privateIpAddress(0, 1), cluster.getRegion());
    }

    static boolean isMasterGroup(EmrInstanceGroup group) {
        return "MASTER".equals(group.getInstanceGroupType());
    }

    static boolean isMasterFleet(EmrInstanceFleet fleet) {
        return "MASTER".equals(fleet.getInstanceFleetType());
    }

    /** What a step's thread asks the service while its driver goes: a step has no timeout to report. */
    private final class StepProgress implements SparkJobRunner.Progress {

        private final StepLaunch launch;
        // Whether the step's driver has started, so it may have left an application's logs on the cluster's nodes.
        private volatile boolean driverStarted;

        StepProgress(StepLaunch launch) {
            this.launch = launch;
        }

        @Override
        public void started() {
            // The step has been RUNNING since it started; the driver's start changes nothing a caller sees.
            driverStarted = true;
        }

        @Override
        public void timedOut() {
            // A step runs until its driver ends.
        }

        @Override
        public boolean cancelled() {
            return teardowns.get() != launch.teardownsBefore()
                    || !RequestScopes.callAs(launch.accountId(), launch.region(),
                            () -> mayGoOn(launch, driverStarted));
        }
    }

    /** Stores a cluster a call changed, once every change that falls due by now is made. */
    private void store(EmrCluster cluster) {
        lifecycle.advance(cluster, now(), regionOf(cluster));
        releaseEnded(cluster);
        clusterStore.put(cluster.getId(), cluster);
        wakeForNextChange(cluster);
    }

    private void wakeForNextChange(EmrCluster cluster) {
        Instant due = lifecycle.nextChange(cluster);
        if (due != null) {
            wakeAt(regionResolver.getAccountId(), cluster, due);
        }
    }

    /**
     * Brings the cluster up to date at {@code due}, as {@code accountId} in its Region, unless an
     * earlier wakeup will: that one sets the next. Its events go out by {@link Wakeups#deliver}.
     */
    private void wakeAt(String accountId, EmrCluster cluster, Instant due) {
        String key = accountId + "/" + cluster.getId();
        Instant set = wakeupsDue.get(key);
        if (set != null && !set.isAfter(due)) {
            return;
        }
        wakeupsDue.put(key, due);
        String id = cluster.getId();
        String region = regionOf(cluster);
        wakeups.at(due, () -> {
            wakeupsDue.remove(key, due);
            try {
                RequestScopes.runAs(accountId, region, () -> {
                    synchronized (this) {
                        findCluster(id);
                    }
                });
            } catch (RuntimeException e) {
                LOG.warnv(e, "EMR could not bring cluster {0} up to date", id);
            }
            wakeups.deliver(eventPublisher::publishQueued);
        });
    }

    private String regionOf(EmrCluster cluster) {
        return cluster.getRegion() != null ? cluster.getRegion() : regionResolver.getRegion();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    /** {@code length} random upper-case letters and digits, as EMR's resource ids carry after their prefix. */
    static String randomId(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(UPPER_ALNUM.charAt(RANDOM.nextInt(UPPER_ALNUM.length())));
        }
        return sb.toString();
    }
}
