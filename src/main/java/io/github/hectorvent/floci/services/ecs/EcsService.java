package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskHandle;
import io.github.hectorvent.floci.services.ecs.exec.EcsExecChannelHandler;
import io.github.hectorvent.floci.services.ecs.exec.EcsExecSessionRegistry;
import io.github.hectorvent.floci.services.ecs.exec.ExecSession;
import io.github.hectorvent.floci.services.ecs.model.Attribute;
import io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.ecs.model.CapacityProvider;
import io.github.hectorvent.floci.services.ecs.model.CapacityProviderStrategyItem;
import io.github.hectorvent.floci.services.ecs.model.ClusterSetting;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.ContainerDependency;
import io.github.hectorvent.floci.services.ecs.model.ContainerImage;
import io.github.hectorvent.floci.services.ecs.model.ContainerInstance;
import io.github.hectorvent.floci.services.ecs.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.ecs.model.CreateServiceRequest;
import io.github.hectorvent.floci.services.ecs.model.CreateTaskSetRequest;
import io.github.hectorvent.floci.services.ecs.model.Deployment;
import io.github.hectorvent.floci.services.ecs.model.EphemeralStorage;
import io.github.hectorvent.floci.services.ecs.model.Failure;
import io.github.hectorvent.floci.services.ecs.model.ContainerOverride;
import io.github.hectorvent.floci.services.ecs.model.EcsCluster;
import io.github.hectorvent.floci.services.ecs.model.EcsLoadBalancer;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.FirelensConfiguration;
import io.github.hectorvent.floci.services.ecs.model.KeyValuePair;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.ListTasksRequest;
import io.github.hectorvent.floci.services.ecs.model.ManagedAgent;
import io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.ProtectedTask;
import io.github.hectorvent.floci.services.ecs.model.RegisterContainerInstanceRequest;
import io.github.hectorvent.floci.services.ecs.model.RegisterTaskDefinitionRequest;
import io.github.hectorvent.floci.services.ecs.model.RunTaskRequest;
import io.github.hectorvent.floci.services.ecs.model.RuntimePlatform;
import io.github.hectorvent.floci.services.ecs.model.ServiceDeployment;
import io.github.hectorvent.floci.services.ecs.model.ServiceEvent;
import io.github.hectorvent.floci.services.ecs.model.ServiceRevision;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.ecs.model.TaskNetworkInterface;
import io.github.hectorvent.floci.services.ecs.model.TaskOverride;
import io.github.hectorvent.floci.services.ecs.model.TaskSet;
import io.github.hectorvent.floci.services.ecs.model.TaskStatus;
import io.github.hectorvent.floci.services.ecs.model.UpdateServiceRequest;
import io.github.hectorvent.floci.services.ecs.model.Volume;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.Comparator;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.Function;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;

@ApplicationScoped
public class EcsService implements ContainerTeardown, ResourceProvider, Resettable {

    private static final Logger LOG = Logger.getLogger(EcsService.class);
    private static final String DEFAULT_CLUSTER = "default";

    private final RegionResolver regionResolver;
    private final EcsContainerManager containerManager;
    private final EcsLoadBalancerRegistrar lbRegistrar;
    private final StorageFactory storageFactory;
    private final EcsEventPublisher eventPublisher;
    private final EcsExecSessionRegistry execSessions;
    // Null for a service assembled without CDI, which then registers nothing in Cloud Map:
    // what every such caller already expects.
    private final EcsServiceDiscoveryRegistrar discoveryRegistrar;
    private final boolean dockerMode;
    private final String baseUrl;
    // Replaced by afterReset() after a state reset, whose container teardown shuts this scheduler down.
    private volatile ScheduledExecutorService reconciler = newReconciler();
    private final Object reconcilerLock = new Object();

    // region::clusterName → EcsCluster
    private Map<String, EcsCluster> clusters = new ConcurrentHashMap<>();
    // family:revision → TaskDefinition
    private Map<String, TaskDefinition> taskDefinitions = new ConcurrentHashMap<>();
    // family → latest revision number
    private Map<String, Integer> latestRevisions = new ConcurrentHashMap<>();
    // taskArn → EcsTask
    private final Map<String, EcsTask> tasks = new ConcurrentHashMap<>();
    // taskArn → EcsTaskHandle (running containers or unresolved log readers)
    private final Map<String, EcsTaskHandle> taskHandles = new ConcurrentHashMap<>();
    // region::clusterName/serviceName → EcsServiceModel
    private Map<String, EcsServiceModel> services = new ConcurrentHashMap<>();
    private AccountAwareStorageBackend<EcsServiceModel> servicesStore;

    public static final String DEFAULT_SCHEDULING_STRATEGY = "REPLICA";
    public static final String SCHEDULING_DAEMON = "DAEMON";
    public static final String DEFAULT_DEPLOYMENT_CONTROLLER = "ECS";
    /** CreateService defaults availabilityZoneRebalancing to ENABLED when omitted. */
    public static final String DEFAULT_AZ_REBALANCING_ON_CREATE = "ENABLED";
    /** A service that never had a value (persisted before the field existed) reads as DISABLED. */
    public static final String DEFAULT_AZ_REBALANCING_UNSET = "DISABLED";
    /** The Fargate platform version {@code LATEST} resolves to. */
    public static final String DEFAULT_PLATFORM_VERSION = "1.4.0";
    public static final String PLATFORM_VERSION_LATEST = "LATEST";
    /** The platform family a Linux task reports, as DescribeTasks answers it. */
    public static final String DEFAULT_PLATFORM_FAMILY = "Linux";
    public static final String CONNECTIVITY_CONNECTED = "CONNECTED";
    public static final String HEALTH_STATUS_UNKNOWN = "UNKNOWN";
    public static final String HEALTH_STATUS_HEALTHY = "HEALTHY";
    public static final String HEALTH_STATUS_UNHEALTHY = "UNHEALTHY";
    public static final String STOP_CODE_TASK_FAILED_TO_START = "TaskFailedToStart";
    public static final String STOP_CODE_ESSENTIAL_CONTAINER_EXITED = "EssentialContainerExited";
    public static final String STOP_CODE_USER_INITIATED = "UserInitiated";
    public static final String STOP_CODE_SERVICE_SCHEDULER_INITIATED = "ServiceSchedulerInitiated";
    public static final String PROPAGATE_TAGS_SERVICE = "SERVICE";
    public static final String PROPAGATE_TAGS_TASK_DEFINITION = "TASK_DEFINITION";
    public static final String PROPAGATE_TAGS_NONE = "NONE";
    /** RunTask places at most ten tasks in one call, and StartTask at most ten instances. */
    public static final int MAX_TASKS_PER_RUN = 10;
    /** A listing returns at most a hundred ARNs per page. */
    private static final int MAX_LIST_RESULTS = 100;
    /** ListServices answers with ten ARNs per page when the request names no maxResults. */
    private static final int DEFAULT_SERVICE_PAGE_SIZE = 10;
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
    public static final String STATUS_DELETE_IN_PROGRESS = "DELETE_IN_PROGRESS";
    /** The attribute every task carries, naming the architecture it actually runs on. */
    private static final String CPU_ARCHITECTURE_ATTRIBUTE = "ecs.cpu-architecture";
    /** The two built-in capacity providers, which place Fargate tasks rather than EC2 ones. */
    private static final Set<String> FARGATE_CAPACITY_PROVIDERS = Set.of("FARGATE", "FARGATE_SPOT");
    /** The bounds the ECS model puts on a capacity provider strategy and on its entries. */
    private static final int MAX_CAPACITY_PROVIDERS_PER_STRATEGY = 20;
    private static final int MAX_CAPACITY_PROVIDER_WEIGHT = 1000;
    private static final int MAX_CAPACITY_PROVIDER_BASE = 100_000;
    // region::clusterArn/instanceArn → ContainerInstance
    private final Map<String, ContainerInstance> containerInstances = new ConcurrentHashMap<>();
    // name → CapacityProvider (excludes built-ins FARGATE, FARGATE_SPOT)
    private Map<String, CapacityProvider> capacityProviders = new ConcurrentHashMap<>();
    // taskSetArn → TaskSet
    private final Map<String, TaskSet> taskSets = new ConcurrentHashMap<>();
    // serviceDeploymentArn → ServiceDeployment
    private final Map<String, ServiceDeployment> serviceDeployments = new ConcurrentHashMap<>();
    // serviceRevisionArn → ServiceRevision
    private final Map<String, ServiceRevision> serviceRevisions = new ConcurrentHashMap<>();
    // targetArn → List<Attribute>
    private Map<String, List<Attribute>> attributes = new ConcurrentHashMap<>();
    // name → value (account-level settings)
    // principalArn::name → value. The root principal's row doubles as the account default, which
    // is how AWS describes it: "the account settings for the root user or the default setting".
    private Map<String, String> accountSettings = new ConcurrentHashMap<>();
    // deploymentIds for which SERVICE_DEPLOYMENT_IN_PROGRESS has already been emitted this process.
    private final Set<String> inProgressEmitted = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Inject
    public EcsService(RegionResolver regionResolver, EcsContainerManager containerManager,
                      EmulatorConfig config, EcsLoadBalancerRegistrar lbRegistrar,
                      StorageFactory storageFactory, EcsEventPublisher eventPublisher,
                      EcsExecSessionRegistry execSessions,
                      EcsServiceDiscoveryRegistrar discoveryRegistrar) {
        this.regionResolver = regionResolver;
        this.containerManager = containerManager;
        this.dockerMode = !config.services().ecs().mock();
        this.baseUrl = config.effectiveBaseUrl();
        this.lbRegistrar = lbRegistrar;
        this.storageFactory = storageFactory;
        this.eventPublisher = eventPublisher;
        this.execSessions = execSessions;
        this.discoveryRegistrar = discoveryRegistrar;
    }

    /** With an exec session registry of its own, for callers that assemble the service without CDI. */
    public EcsService(RegionResolver regionResolver, EcsContainerManager containerManager,
                      EmulatorConfig config, EcsLoadBalancerRegistrar lbRegistrar,
                      StorageFactory storageFactory, EcsEventPublisher eventPublisher) {
        this(regionResolver, containerManager, config, lbRegistrar, storageFactory, eventPublisher,
                new EcsExecSessionRegistry(), null);
    }

    @PostConstruct
    void init() {
        initializeStorage();
        scheduleReconciliation(reconciler);
    }

    private void scheduleReconciliation(ScheduledExecutorService scheduler) {
        scheduler.scheduleAtFixedRate(this::reconcile, 5, 5, TimeUnit.SECONDS);
    }

    private static ScheduledExecutorService newReconciler() {
        return Executors.newSingleThreadScheduledExecutor(
                r -> { Thread t = new Thread(r, "ecs-reconciler"); t.setDaemon(true); return t; });
    }

    void initializeStorage() {
        if (storageFactory == null) {
            return; // keeps non-CDI unit tests working
        }
        this.clusters = storageBacked("ecs-clusters.json",
                new TypeReference<Map<String, EcsCluster>>() {});
        this.taskDefinitions = storageBacked("ecs-task-definitions.json",
                new TypeReference<Map<String, TaskDefinition>>() {});
        this.latestRevisions = storageBacked("ecs-latest-revisions.json",
                new TypeReference<Map<String, Integer>>() {});
        this.servicesStore = storageFactory.create("ecs", "ecs-services.json",
                new TypeReference<Map<String, EcsServiceModel>>() {});
        this.services = new StorageBackedMap<>(this.servicesStore);
        this.capacityProviders = storageBacked("ecs-capacity-providers.json",
                new TypeReference<Map<String, CapacityProvider>>() {});
        this.attributes = storageBacked("ecs-attributes.json",
                new TypeReference<Map<String, List<Attribute>>>() {});
        this.accountSettings = storageBacked("ecs-account-settings.json",
                new TypeReference<Map<String, String>>() {});
    }

    private <V> Map<String, V> storageBacked(String fileName, TypeReference<Map<String, V>> typeReference) {
        return new StorageBackedMap<>(storageFactory.create("ecs", fileName, typeReference));
    }

    /**
     * Re-persists a durable entity whose fields were mutated in place. {@link StorageBackedMap}
     * only flags the backend dirty on {@code put}/{@code remove}, so an in-place mutation of a
     * value obtained via {@code get}/{@code values()} must be written back to be captured by the
     * periodic flush. The entry is located by ARN so callers don't need to recompute its key.
     */
    private static <V> void persistByArn(Map<String, V> map, V value, Function<V, String> arnFn) {
        String arn = arnFn.apply(value);
        if (arn == null) {
            return;
        }
        for (Map.Entry<String, V> entry : map.entrySet()) {
            if (arn.equals(arnFn.apply(entry.getValue()))) {
                map.put(entry.getKey(), value);
                return;
            }
        }
    }

    private void persistCluster(String region, EcsCluster cluster) {
        if (cluster != null) {
            clusters.put(clusterKey(region, cluster.getClusterName()), cluster);
        }
    }

    @PreDestroy
    void shutdown() {
        stopManagedContainers();
    }

    /**
     * Stops the Docker containers of all still-running tasks on emulator shutdown and on a
     * state reset. Task state is transient (memory-only), so without this the containers
     * outlive the process as orphans. The reconciler is shut down first, and any in-flight
     * tick awaited, so it cannot restart drained tasks between this teardown and the final
     * storage flush. A reset brings it back in {@link #afterReset()}. Handles are claimed
     * atomically to avoid racing an explicit StopTask.
     */
    @Override
    public void stopManagedContainers() {
        ScheduledExecutorService current = reconciler;
        current.shutdownNow();
        try {
            current.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (String taskArn : new ArrayList<>(taskHandles.keySet())) {
            EcsTaskHandle claimed = taskHandles.remove(taskArn);
            if (claimed == null) {
                continue;
            }
            try {
                containerManager.stopTask(claimed);
            } catch (Exception e) {
                LOG.warnv("Failed to stop ECS task {0} on shutdown: {1}", taskArn, e.getMessage());
            }
        }
        // Task state is memory-only, so an ENI left behind here would outlive every task that
        // could claim it and reload from EC2's store as an orphan on the next start.
        for (EcsTask task : tasks.values()) {
            try {
                containerManager.releaseTaskNetwork(task, taskRegion(task));
            } catch (Exception e) {
                LOG.warnv("Failed to release the ENI of ECS task {0} on shutdown: {1}",
                        task.getTaskArn(), e.getMessage());
            }
        }
    }

    @Override
    public void clear() {
        // Nothing to wipe: the stores hold the ECS state and afterReset() restarts the reconciler.
    }

    /**
     * Runs at the end of every state reset, never on shutdown. The reset's teardown stopped the
     * reconciler, so without a new one no service would be reconciled again until the emulator
     * restarted. This hook rather than {@code clear()} because the controller runs it even when
     * the storage wipe or another service's {@code clear()} threw, and a failed reset must not
     * leave ECS without a reconciler for good.
     */
    @Override
    public void afterReset() {
        synchronized (reconcilerLock) {
            if (reconciler.isShutdown()) {
                ScheduledExecutorService replacement = newReconciler();
                scheduleReconciliation(replacement);
                reconciler = replacement;
            }
        }
    }

    boolean isReconcilerShutdown() {
        return reconciler.isShutdown();
    }

    // ── Clusters ─────────────────────────────────────────────────────────────

    public EcsCluster createCluster(String clusterName, String region) {
        return createCluster(clusterName, null, region);
    }

    public EcsCluster createCluster(String clusterName, Map<String, String> tags, String region) {
        return createCluster(clusterName, tags, null, region);
    }

    /**
     * Creates a cluster, applying any {@code settings} the request carried.
     *
     * <p>Real AWS accepts {@code settings} (containerInsights and friends) on CreateCluster itself,
     * not only through UpdateClusterSettings. Dropping them here meant a client that configured the
     * cluster at creation, such as terraform-provider-aws's {@code setting} block, saw
     * DescribeClusters come back without them and proposed the same change on every subsequent
     * plan, forever (issue #2806).
     */
    public EcsCluster createCluster(String clusterName, Map<String, String> tags,
                                    List<ClusterSetting> settings, String region) {
        CreateClusterRequest request = new CreateClusterRequest();
        request.setClusterName(clusterName);
        request.setTags(tags);
        request.setSettings(settings);
        return createCluster(request, region);
    }

    public EcsCluster createCluster(CreateClusterRequest request, String region) {
        String name = (request.getClusterName() == null || request.getClusterName().isBlank())
                ? DEFAULT_CLUSTER : request.getClusterName();
        String key = clusterKey(region, name);
        if (clusters.containsKey(key)) {
            return clusters.get(key);
        }
        EcsCluster cluster = new EcsCluster();
        cluster.setClusterName(name);
        cluster.setClusterArn(regionResolver.buildArn("ecs", region, "cluster/" + name));
        cluster.setStatus(STATUS_ACTIVE);
        if (request.getTags() != null && !request.getTags().isEmpty()) {
            cluster.setTags(new LinkedHashMap<>(request.getTags()));
        }
        if (request.getSettings() != null && !request.getSettings().isEmpty()) {
            cluster.setSettings(new ArrayList<>(request.getSettings()));
        }
        cluster.setConfiguration(request.getConfiguration());
        cluster.setServiceConnectDefaults(request.getServiceConnectDefaults());
        cluster.setCapacityProviders(request.getCapacityProviders());
        cluster.setDefaultCapacityProviderStrategy(request.getDefaultCapacityProviderStrategy());
        clusters.put(key, cluster);
        LOG.infov("Created ECS cluster: {0} in {1}", name, region);
        return cluster;
    }

    public List<EcsCluster> describeClusters(List<String> clusterIds, String region) {
        return describeClustersDetailed(clusterIds, region).clusters();
    }

    public record DescribeClustersResult(List<EcsCluster> clusters, List<Failure> failures) {}

    /**
     * Resolves each reference, reporting the ones that do not exist as {@code MISSING} failures.
     * As with DescribeServices, ECS answers a describe for an unknown cluster with a failure entry
     * rather than an error, and a client that sees neither a cluster nor a failure cannot tell the
     * difference between "does not exist" and "the call did not happen".
     */
    public DescribeClustersResult describeClustersDetailed(List<String> clusterIds, String region) {
        if (clusterIds == null || clusterIds.isEmpty()) {
            return new DescribeClustersResult(List.of(getOrCreateDefaultCluster(region)), List.of());
        }
        List<EcsCluster> result = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        for (String id : clusterIds) {
            EcsCluster cluster = resolveCluster(id, region);
            if (cluster != null) {
                result.add(cluster);
            } else {
                failures.add(Failure.missing(id != null && id.startsWith("arn:")
                        ? id : regionResolver.buildArn("ecs", region, "cluster/" + id)));
            }
        }
        return new DescribeClustersResult(result, failures);
    }

    /**
     * The task and service counts DescribeClusters reports under {@code STATISTICS}, separated by
     * launch type.
     *
     * <p>The API reference spells one of the eight names {@code RunningFargateTasksCount}; every
     * other name in that list, and the value real ECS returns, is lower camel case, so Floci
     * follows the wire and not the typo. Values are strings, as ECS returns them.
     */
    public List<KeyValuePair> clusterStatistics(EcsCluster cluster) {
        String clusterArn = cluster.getClusterArn();
        List<EcsTask> clusterTasks = tasks.values().stream()
                .filter(t -> clusterArn.equals(t.getClusterArn()))
                .toList();
        List<EcsServiceModel> clusterServices = services.values().stream()
                .filter(s -> clusterArn.equals(s.getClusterArn()))
                .toList();
        return List.of(
                statistic("runningEC2TasksCount", countTasks(clusterTasks, "RUNNING", LaunchType.EC2)),
                statistic("runningFargateTasksCount", countTasks(clusterTasks, "RUNNING", LaunchType.FARGATE)),
                statistic("pendingEC2TasksCount", countTasks(clusterTasks, "PENDING", LaunchType.EC2)),
                statistic("pendingFargateTasksCount", countTasks(clusterTasks, "PENDING", LaunchType.FARGATE)),
                statistic("activeEC2ServiceCount", countServices(clusterServices, STATUS_ACTIVE, LaunchType.EC2)),
                statistic("activeFargateServiceCount", countServices(clusterServices, STATUS_ACTIVE, LaunchType.FARGATE)),
                statistic("drainingEC2ServiceCount", countServices(clusterServices, "DRAINING", LaunchType.EC2)),
                statistic("drainingFargateServiceCount", countServices(clusterServices, "DRAINING", LaunchType.FARGATE)));
    }

    private static KeyValuePair statistic(String name, long value) {
        return new KeyValuePair(name, String.valueOf(value));
    }

    private static long countTasks(List<EcsTask> clusterTasks, String status, LaunchType launchType) {
        return clusterTasks.stream()
                .filter(t -> status.equals(t.getLastStatus()))
                .filter(t -> launchType == effectiveLaunchType(t.getLaunchType()))
                .count();
    }

    private static long countServices(List<EcsServiceModel> clusterServices, String status,
                                       LaunchType launchType) {
        return clusterServices.stream()
                .filter(s -> status.equals(s.getStatus()))
                .filter(s -> launchType == effectiveLaunchType(s.getLaunchType()))
                .count();
    }

    /** A task or service that named no launch type placed on EC2, which is the ECS default. */
    private static LaunchType effectiveLaunchType(LaunchType launchType) {
        return launchType == null ? LaunchType.EC2 : launchType;
    }

    public List<String> listClusters(String region) {
        return listClusters(null, null, region).arns();
    }

    public ListPage listClusters(Integer maxResults, String nextToken, String region) {
        String prefix = region + "::";
        List<String> arns = clusters.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(e -> e.getValue().getClusterArn())
                .toList();
        return paginate(arns, maxResults, nextToken);
    }

    /**
     * Deletes a cluster, refusing while anything is still registered against it: "You must
     * deregister all container instances from this cluster before you may delete it", and a
     * cluster that still has services or running tasks reports its own exception for each.
     */
    public EcsCluster deleteCluster(String clusterId, String region) {
        EcsCluster cluster = resolveClusterOrThrow(clusterId, region);
        long activeServices = services.values().stream()
                .filter(s -> cluster.getClusterArn().equals(s.getClusterArn()))
                .filter(s -> !"INACTIVE".equals(s.getStatus()))
                .count();
        if (activeServices > 0) {
            throw new AwsException("ClusterContainsServicesException",
                    "The cluster cannot be deleted while services are active.", 400);
        }
        String instancePrefix = containerInstanceKey(cluster.getClusterArn(), "");
        long registeredInstances = containerInstances.entrySet().stream()
                .filter(e -> e.getKey().startsWith(instancePrefix))
                .map(Map.Entry::getValue)
                .filter(ci -> !STATUS_INACTIVE.equals(ci.getStatus()))
                .count();
        if (registeredInstances > 0) {
            throw new AwsException("ClusterContainsContainerInstancesException",
                    "The cluster cannot be deleted while container instances are active.", 400);
        }
        long runningTasks = tasks.values().stream()
                .filter(t -> t.getClusterArn().equals(cluster.getClusterArn()))
                .filter(t -> !TaskStatus.STOPPED.name().equals(t.getLastStatus()))
                .count();
        if (runningTasks > 0) {
            throw new AwsException("ClusterContainsTasksException",
                    "The cluster cannot be deleted because it contains running tasks.", 400);
        }
        clusters.remove(clusterKey(region, cluster.getClusterName()));
        cluster.setStatus("INACTIVE");
        return cluster;
    }

    public EcsCluster updateCluster(String clusterRef, List<ClusterSetting> settings, String region) {
        return updateCluster(clusterRef, settings, null, null, region);
    }

    /**
     * UpdateCluster replaces only the members the request named: unlike UpdateClusterSettings, it
     * leaves the ones it omits alone.
     */
    public EcsCluster updateCluster(String clusterRef, List<ClusterSetting> settings,
                                     Map<String, Object> configuration,
                                     Map<String, Object> serviceConnectDefaults, String region) {
        EcsCluster cluster = resolveClusterOrThrow(clusterRef, region);
        if (settings != null) {
            cluster.setSettings(settings);
        }
        if (configuration != null) {
            cluster.setConfiguration(configuration);
        }
        if (serviceConnectDefaults != null) {
            cluster.setServiceConnectDefaults(serviceConnectDefaults);
        }
        persistCluster(region, cluster);
        return cluster;
    }

    public EcsCluster updateClusterSettings(String clusterRef, List<ClusterSetting> settings, String region) {
        EcsCluster cluster = resolveClusterOrThrow(clusterRef, region);
        cluster.setSettings(settings);
        persistCluster(region, cluster);
        return cluster;
    }

    public EcsCluster putClusterCapacityProviders(String clusterRef, List<String> providers,
                                                   List<Map<String, Object>> defaultStrategy, String region) {
        EcsCluster cluster = resolveClusterOrThrow(clusterRef, region);
        cluster.setCapacityProviders(providers);
        cluster.setDefaultCapacityProviderStrategy(defaultStrategy);
        persistCluster(region, cluster);
        return cluster;
    }

    // ── Task Definitions ──────────────────────────────────────────────────────

    public TaskDefinition registerTaskDefinition(String family, List<ContainerDefinition> containerDefs,
                                                  NetworkMode networkMode, String cpu, String memory,
                                                  String taskRoleArn, String executionRoleArn,
                                                  List<String> requiresCompatibilities,
                                                  String region) {
        return registerTaskDefinition(family, containerDefs, networkMode, cpu, memory,
                taskRoleArn, executionRoleArn, requiresCompatibilities, null, region);
    }

    public TaskDefinition registerTaskDefinition(String family, List<ContainerDefinition> containerDefs,
                                                  NetworkMode networkMode, String cpu, String memory,
                                                  String taskRoleArn, String executionRoleArn,
                                                  List<String> requiresCompatibilities,
                                                  Map<String, String> tags, String region) {
        RegisterTaskDefinitionRequest request = new RegisterTaskDefinitionRequest();
        request.setFamily(family);
        request.setContainerDefinitions(containerDefs);
        request.setNetworkMode(networkMode);
        request.setCpu(cpu);
        request.setMemory(memory);
        request.setTaskRoleArn(taskRoleArn);
        request.setExecutionRoleArn(executionRoleArn);
        request.setRequiresCompatibilities(requiresCompatibilities);
        request.setTags(tags);
        return registerTaskDefinition(request, region);
    }

    public TaskDefinition registerTaskDefinition(RegisterTaskDefinitionRequest request, String region) {
        List<ContainerDefinition> containerDefs = request.getContainerDefinitions();
        boolean fargate = request.isFargate();
        validateTaskSize(request, fargate);
        validateEphemeralStorage(request.getEphemeralStorage());
        validateContainerDependencies(containerDefs);
        validateFirelensS3Config(containerDefs, fargate);
        if (fargate) {
            validateFargateUnsupportedParameters(request);
        }
        String family = request.getFamily();
        int revision = latestRevisions.merge(family, 1, Integer::sum);

        TaskDefinition td = new TaskDefinition();
        td.setFamily(family);
        td.setRevision(revision);
        td.setStatus("ACTIVE");
        td.setNetworkMode(request.getNetworkMode() != null ? request.getNetworkMode() : NetworkMode.bridge);
        td.setCpu(request.getCpu());
        td.setMemory(request.getMemory());
        td.setTaskRoleArn(request.getTaskRoleArn());
        td.setExecutionRoleArn(request.getExecutionRoleArn());
        td.setContainerDefinitions(containerDefs != null ? containerDefs : List.of());
        td.setRequiresCompatibilities(request.getRequiresCompatibilities());
        td.setVolumes(request.getVolumes());
        td.setRuntimePlatform(request.getRuntimePlatform());
        td.setEphemeralStorage(request.getEphemeralStorage());
        td.setPidMode(request.getPidMode());
        td.setIpcMode(request.getIpcMode());
        td.setUnparsed(request.getUnparsed());
        td.setRegisteredAt(Instant.now());
        td.setRegisteredBy(regionResolver.buildArn("iam", region, "root"));

        td.setCompatibilities(deriveCompatibilities(request, fargate));
        td.setRequiresAttributes(deriveRequiresAttributes(request));

        td.setTaskDefinitionArn(regionResolver.buildArn("ecs", region,
                "task-definition/" + family + ":" + revision));
        if (request.getTags() != null && !request.getTags().isEmpty()) {
            td.setTags(new LinkedHashMap<>(request.getTags()));
        }

        taskDefinitions.put(family + ":" + revision, td);
        LOG.infov("Registered task definition: {0}:{1}", family, revision);
        return td;
    }

    /**
     * The launch types a registered definition is actually compatible with, which is not the same
     * as the {@code requiresCompatibilities} it was validated against: "Amazon ECS validates the
     * task definition parameters with those supported by the launch type", so a definition that
     * satisfies Fargate's rules reports FARGATE even when it never asked for it.
     *
     * <p>EXTERNAL covers the same ground as EC2 minus {@code awsvpc}, which ECS Anywhere instances
     * do not support.
     */
    private List<String> deriveCompatibilities(RegisterTaskDefinitionRequest request, boolean fargate) {
        List<String> compatibilities = new ArrayList<>();
        compatibilities.add("EC2");
        if (fargate || isFargateCompatible(request)) {
            compatibilities.add("FARGATE");
        }
        if (request.getNetworkMode() != NetworkMode.awsvpc) {
            compatibilities.add("EXTERNAL");
        }
        return compatibilities;
    }

    /** Runs Fargate's own rules over a definition that did not ask for them, to see if it passes. */
    private boolean isFargateCompatible(RegisterTaskDefinitionRequest request) {
        if (request.getNetworkMode() != NetworkMode.awsvpc) {
            return false;
        }
        try {
            validateTaskSize(request, true);
            validateEphemeralStorage(request.getEphemeralStorage());
            validateFirelensS3Config(request.getContainerDefinitions(), true);
            validateFargateUnsupportedParameters(request);
            return true;
        } catch (AwsException notFargateCompatible) {
            LOG.debugv("Task definition {0} is not Fargate compatible: {1}",
                    request.getFamily(), notFargateCompatible.getMessage());
            return false;
        }
    }

    /** The Docker remote API version every task definition needs, per the RegisterTaskDefinition sample. */
    private static final String BASE_AGENT_CAPABILITY = "com.amazonaws.ecs.capability.docker-remote-api.1.18";

    /**
     * The container instance capabilities a definition needs to be placed on EC2.
     *
     * <p>ECS derives these from the agent's capability model, which AWS does not publish in full.
     * Floci derives the subset whose names appear verbatim in the AWS documentation: the base
     * Docker remote API version, the log driver, ECR authentication, the task IAM role (which has
     * a separate name under {@code host} networking), privileged containers, and the task ENI
     * capability that {@code awsvpc} placement requires.
     */
    private List<Attribute> deriveRequiresAttributes(RegisterTaskDefinitionRequest request) {
        Set<String> names = new LinkedHashSet<>();
        names.add(BASE_AGENT_CAPABILITY);
        NetworkMode networkMode = request.getNetworkMode() != null
                ? request.getNetworkMode() : NetworkMode.bridge;
        if (networkMode == NetworkMode.awsvpc) {
            names.add("ecs.capability.task-eni");
        }
        if (request.getTaskRoleArn() != null) {
            names.add(networkMode == NetworkMode.host
                    ? "com.amazonaws.ecs.capability.task-iam-role-network-host"
                    : "com.amazonaws.ecs.capability.task-iam-role");
        }
        if (request.getContainerDefinitions() != null) {
            for (ContainerDefinition def : request.getContainerDefinitions()) {
                if (def.getLogConfiguration() != null && def.getLogConfiguration().logDriver() != null) {
                    names.add("com.amazonaws.ecs.capability.logging-driver."
                            + def.getLogConfiguration().logDriver());
                }
                if (def.getImage() != null && def.getImage().contains(".dkr.ecr.")) {
                    names.add("com.amazonaws.ecs.capability.ecr-auth");
                }
                if (Boolean.TRUE.equals(def.getPrivileged())) {
                    names.add("com.amazonaws.ecs.capability.privileged-container");
                }
            }
        }
        return names.stream().map(name -> new Attribute(name, null, null, null)).toList();
    }

    /**
     * The task-level size rules. Fargate requires both values and accepts only the documented
     * pairs; Windows on Fargate additionally has no sub-vCPU size.
     */
    private void validateTaskSize(RegisterTaskDefinitionRequest request, boolean fargate) {
        if (!fargate) {
            return;
        }
        if (request.getNetworkMode() != NetworkMode.awsvpc) {
            throw new AwsException("ClientException", "Fargate only supports network mode 'awsvpc'.", 400);
        }
        if (request.getCpu() == null) {
            throw new AwsException("ClientException",
                    "Fargate requires that 'cpu' be defined at the task level.", 400);
        }
        if (request.getMemory() == null) {
            throw new AwsException("ClientException",
                    "Fargate requires that 'memory' be defined at the task level.", 400);
        }
        if (!isValidFargateCpuMemory(request.getCpu(), request.getMemory())) {
            throw new AwsException("ClientException", "No Fargate configuration exists for given values.", 400);
        }
        RuntimePlatform platform = request.getRuntimePlatform();
        boolean windows = platform != null && platform.operatingSystemFamily() != null
                && platform.operatingSystemFamily().startsWith("WINDOWS");
        if (windows && parseCpu(request.getCpu()) < 1024) {
            throw new AwsException("ClientException", "No Fargate configuration exists for given values.", 400);
        }
    }

    /**
     * Fargate's ephemeral storage is 20 GiB by default and can be expanded up to 200 GiB, so an
     * explicit request below the default or above the ceiling is rejected the way AWS rejects it.
     */
    private static void validateEphemeralStorage(EphemeralStorage storage) {
        if (storage == null) {
            return;
        }
        if (storage.sizeInGiB() <= EphemeralStorage.DEFAULT_SIZE_IN_GIB
                || storage.sizeInGiB() > EphemeralStorage.MAX_SIZE_IN_GIB) {
            throw new AwsException("ClientException",
                    "Invalid setting for ephemeral storage. Size must be between "
                            + (EphemeralStorage.DEFAULT_SIZE_IN_GIB + 1) + " GiB and "
                            + EphemeralStorage.MAX_SIZE_IN_GIB + " GiB.", 400);
        }
    }

    /**
     * A {@code dependsOn} entry may only name another container of the same task definition, and
     * the graph they form has to be acyclic: a cycle would leave every container in it waiting for
     * another, so ECS rejects it at registration rather than at launch.
     */
    private static void validateContainerDependencies(List<ContainerDefinition> containerDefs) {
        if (containerDefs == null) {
            return;
        }
        Map<String, ContainerDefinition> byName = new LinkedHashMap<>();
        containerDefs.forEach(def -> byName.put(def.getName(), def));
        for (ContainerDefinition def : containerDefs) {
            if (def.getDependsOn() == null) {
                continue;
            }
            for (ContainerDependency dependency : def.getDependsOn()) {
                if (dependency.containerName().equals(def.getName())) {
                    throw new AwsException("ClientException",
                            "Container '" + def.getName() + "' cannot depend on itself.", 400);
                }
                if (!byName.containsKey(dependency.containerName())) {
                    throw new AwsException("ClientException",
                            "Container '" + def.getName() + "' depends on container '"
                                    + dependency.containerName() + "' which does not exist.", 400);
                }
            }
        }
        Set<String> resolved = new HashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (ContainerDefinition def : containerDefs) {
            rejectDependencyCycle(def, byName, visiting, resolved);
        }
    }

    private static void rejectDependencyCycle(ContainerDefinition def,
                                              Map<String, ContainerDefinition> byName,
                                              Set<String> visiting, Set<String> resolved) {
        if (resolved.contains(def.getName())) {
            return;
        }
        if (!visiting.add(def.getName())) {
            throw new AwsException("ClientException",
                    "The container dependencies form a cycle: "
                            + String.join(" -> ", visiting) + " -> " + def.getName() + ".", 400);
        }
        if (def.getDependsOn() != null) {
            for (ContainerDependency dependency : def.getDependsOn()) {
                rejectDependencyCycle(byName.get(dependency.containerName()), byName, visiting, resolved);
            }
        }
        visiting.remove(def.getName());
        resolved.add(def.getName());
    }

    /**
     * The task definition parameters that are not valid in a Fargate task, as the Fargate task
     * definition differences document lists them: {@code disableNetworking},
     * {@code dnsSearchDomains}, {@code dnsServers}, {@code dockerSecurityOptions},
     * {@code extraHosts}, {@code gpu}, {@code ipcMode}, {@code links},
     * {@code placementConstraints}, {@code privileged}, {@code maxSwap} and {@code swappiness},
     * plus the {@code pidMode} values other than {@code task} and the host bind mounts Fargate
     * cannot provide. ECS rejects each of them at registration rather than failing the task later.
     */
    private static void validateFargateUnsupportedParameters(RegisterTaskDefinitionRequest request) {
        if (request.getIpcMode() != null) {
            throw new AwsException("ClientException",
                    "Fargate compatible task definitions do not support ipcMode.", 400);
        }
        if (request.getPidMode() != null && !"task".equals(request.getPidMode())) {
            throw new AwsException("ClientException",
                    "Fargate compatible task definitions only support the 'task' pidMode.", 400);
        }
        if (notEmpty(request.getUnparsed(), "placementConstraints")) {
            throw new AwsException("ClientException",
                    "Fargate compatible task definitions do not support placementConstraints.", 400);
        }
        if (request.getVolumes() != null) {
            for (Volume volume : request.getVolumes()) {
                if (volume.hostSourcePath() != null && !volume.hostSourcePath().isBlank()) {
                    throw new AwsException("ClientException",
                            "Fargate compatible task definitions do not support host volume sourcePath.", 400);
                }
            }
        }
        if (request.getContainerDefinitions() == null) {
            return;
        }
        for (ContainerDefinition def : request.getContainerDefinitions()) {
            rejectOnFargate(Boolean.TRUE.equals(def.getPrivileged()), "privileged");
            rejectOnFargate(Boolean.TRUE.equals(def.getDisableNetworking()), "disableNetworking");
            rejectOnFargate(def.getLinks() != null && !def.getLinks().isEmpty(), "links");
            rejectOnFargate(def.getDnsServers() != null && !def.getDnsServers().isEmpty(), "dnsServers");
            rejectOnFargate(def.getDnsSearchDomains() != null && !def.getDnsSearchDomains().isEmpty(),
                    "dnsSearchDomains");
            rejectOnFargate(def.getDockerSecurityOptions() != null && !def.getDockerSecurityOptions().isEmpty(),
                    "dockerSecurityOptions");
            rejectOnFargate(notEmpty(def.getUnparsed(), "extraHosts"), "extraHosts");
            validateFargateLinuxParameters(def);
            validateFargateResourceRequirements(def);
        }
    }

    private static void rejectOnFargate(boolean present, String parameter) {
        if (present) {
            throw new AwsException("ClientException",
                    "Fargate compatible task definitions do not support " + parameter + ".", 400);
        }
    }

    /** {@code maxSwap} and {@code swappiness} have no meaning on Fargate's managed kernel. */
    @SuppressWarnings("unchecked")
    private static void validateFargateLinuxParameters(ContainerDefinition def) {
        Object linuxParameters = def.getUnparsed() == null ? null : def.getUnparsed().get("linuxParameters");
        if (!(linuxParameters instanceof Map<?, ?> parameters)) {
            return;
        }
        rejectOnFargate(parameters.get("maxSwap") != null, "maxSwap");
        rejectOnFargate(parameters.get("swappiness") != null, "swappiness");
    }

    /** A Fargate task cannot ask for a GPU; only {@code InferenceAccelerator} requirements remain. */
    private static void validateFargateResourceRequirements(ContainerDefinition def) {
        Object requirements = def.getUnparsed() == null ? null : def.getUnparsed().get("resourceRequirements");
        if (!(requirements instanceof List<?> entries)) {
            return;
        }
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> requirement && "GPU".equals(requirement.get("type"))) {
                throw new AwsException("ClientException",
                        "Fargate compatible task definitions do not support gpu.", 400);
            }
        }
    }

    private static boolean notEmpty(Map<String, Object> members, String key) {
        Object value = members == null ? null : members.get(key);
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        return value != null;
    }

    /**
     * RegisterTaskDefinition-time validation of a FireLens config that comes from S3, in the
     * order ECS applies it.
     *
     * <p>The Fargate platform cannot pull the object itself, so ECS rejects the combination
     * outright with this exact wording. A Fargate task can still take its config from S3 the way
     * AWS documents, by giving the aws-for-fluent-bit init process its
     * {@code aws_fluent_bit_init_s3_*} environment variables, which ECS never inspects. An
     * EC2-compatible task definition keeps {@code s3}: the agent pulls it, and Floci reads the
     * object from its own S3 at launch.
     *
     * <p>A {@code config-file-value} that does not name an S3 object is rejected as an ARN syntax
     * error, which is what the RegisterTaskDefinition API returns for this field.
     */
    private static void validateFirelensS3Config(List<ContainerDefinition> containerDefs, boolean fargate) {
        if (containerDefs == null) {
            return;
        }
        for (ContainerDefinition def : containerDefs) {
            FirelensConfiguration firelens = def.getFirelensConfiguration();
            if (firelens == null || firelens.options() == null
                    || !"s3".equals(firelens.options().get("config-file-type"))) {
                continue;
            }
            if (fargate) {
                throw new AwsException("ClientException",
                        "Fargate launch type does not support FirelensConfiguration config file from 's3'", 400);
            }
            if (AwsArnUtils.parseS3ObjectArn(firelens.options().get("config-file-value")) == null) {
                throw new AwsException("ClientException", "Invalid arn syntax", 400);
            }
        }
    }

    private boolean isValidFargateCpuMemory(String cpuStr, String memStr) {
        if (cpuStr == null || memStr == null) return false;
        int cpu = parseCpu(cpuStr);
        int memory = parseMemory(memStr);
        if (cpu <= 0 || memory <= 0) return false;

        if (cpu == 256) return memory == 512 || memory == 1024 || memory == 2048;
        if (cpu == 512) return memory >= 1024 && memory <= 4096 && memory % 1024 == 0;
        if (cpu == 1024) return memory >= 2048 && memory <= 8192 && memory % 1024 == 0;
        if (cpu == 2048) return memory >= 4096 && memory <= 16384 && memory % 1024 == 0;
        if (cpu == 4096) return memory >= 8192 && memory <= 30720 && memory % 1024 == 0;
        if (cpu == 8192) return memory >= 16384 && memory <= 61440 && memory % 4096 == 0;
        if (cpu == 16384) return memory >= 32768 && memory <= 122880 && memory % 8192 == 0;
        // 32 vCPU offers three fixed sizes rather than a range: 60 GB, 120 GB and 244 GB.
        if (cpu == 32768) return memory == 61440 || memory == 122880 || memory == 249856;

        return false;
    }

    private int parseCpu(String cpu) {
        if (cpu.toUpperCase().endsWith("VCPU")) {
            try {
                double v = Double.parseDouble(cpu.substring(0, cpu.length() - 4).trim());
                return (int) Math.round(v * 1024);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        try {
            return Integer.parseInt(cpu.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private int parseMemory(String mem) {
        if (mem.toUpperCase().endsWith("GB")) {
            try {
                double v = Double.parseDouble(mem.substring(0, mem.length() - 2).trim());
                return (int) Math.round(v * 1024);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        if (mem.toUpperCase().endsWith("MB") || mem.toUpperCase().endsWith("MIB")) {
            try {
                double v = Double.parseDouble(mem.replaceAll("(?i)mib|mb", "").trim());
                return (int) Math.round(v);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        try {
            return Integer.parseInt(mem.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Writes back a task definition mutated after {@link #registerTaskDefinition} returned it.
     * A backend that serializes on {@code put} (persistent, hybrid, WAL) stored the value as it was
     * at registration, so fields set on the returned instance need this to survive a restart.
     */
    public void persistTaskDefinition(TaskDefinition td) {
        taskDefinitions.put(td.getFamily() + ":" + td.getRevision(), td);
    }

    public TaskDefinition describeTaskDefinition(String taskDefinitionRef, String region) {
        return resolveTaskDefinitionOrThrow(taskDefinitionRef, region);
    }

    public List<String> listTaskDefinitions(String familyPrefix, String status) {
        return listTaskDefinitions(familyPrefix, status, null, null, null).arns();
    }

    /**
     * Lists task definition ARNs.
     *
     * <p>Only {@code ACTIVE} revisions are listed unless another status is asked for, and the
     * order is lexicographic by family then numeric by revision, so the newest revision of a
     * family comes last (or first under {@code DESC}). Listing by ARN string alone would put
     * revision 10 before revision 2.
     */
    public ListPage listTaskDefinitions(String familyPrefix, String status, String sort,
                                                Integer maxResults, String nextToken) {
        String effectiveStatus = status != null ? status : STATUS_ACTIVE;
        Comparator<TaskDefinition> order = Comparator
                .comparing(TaskDefinition::getFamily)
                .thenComparingInt(TaskDefinition::getRevision);
        if ("DESC".equals(sort)) {
            order = order.reversed();
        }
        List<String> arns = taskDefinitions.values().stream()
                .filter(td -> familyPrefix == null || td.getFamily().startsWith(familyPrefix))
                .filter(td -> effectiveStatus.equals(td.getStatus()))
                .sorted(order)
                .map(TaskDefinition::getTaskDefinitionArn)
                .toList();
        return paginate(arns, maxResults, nextToken);
    }

    public List<String> listTaskDefinitionFamilies(String familyPrefix) {
        return listTaskDefinitionFamilies(familyPrefix, null, null, null).arns();
    }

    /**
     * Lists task definition family names.
     *
     * <p>Both active and inactive families are listed unless a status narrows it: {@code ACTIVE}
     * keeps only the families that still have an ACTIVE revision, {@code INACTIVE} keeps only
     * those that have none. A family survives the deregistration of all its revisions, which is
     * why the unfiltered listing still names it.
     */
    public ListPage listTaskDefinitionFamilies(String familyPrefix, String status,
                                                Integer maxResults, String nextToken) {
        List<String> families = latestRevisions.keySet().stream()
                .filter(f -> familyPrefix == null || f.startsWith(familyPrefix))
                .filter(f -> matchesFamilyStatus(f, status))
                .sorted()
                .toList();
        return paginate(families, maxResults, nextToken);
    }

    private boolean matchesFamilyStatus(String family, String status) {
        if (status == null || "ALL".equals(status)) {
            return true;
        }
        boolean hasActiveRevision = taskDefinitions.values().stream()
                .anyMatch(td -> family.equals(td.getFamily()) && STATUS_ACTIVE.equals(td.getStatus()));
        return STATUS_ACTIVE.equals(status) == hasActiveRevision;
    }

    public TaskDefinition deregisterTaskDefinition(String taskDefinitionRef, String region) {
        TaskDefinition td = resolveTaskDefinitionOrThrow(taskDefinitionRef, region);
        td.setStatus(STATUS_INACTIVE);
        td.setDeregisteredAt(Instant.now());
        taskDefinitions.put(td.getFamily() + ":" + td.getRevision(), td);
        return td;
    }

    /**
     * Moves task definition revisions to {@code DELETE_IN_PROGRESS}.
     *
     * <p>A revision has to be deregistered first, and has to be named with its revision rather
     * than by family. The revision is not dropped from the store: AWS keeps describing a
     * {@code DELETE_IN_PROGRESS} revision, and tasks and services already on it keep running,
     * which is also why it can no longer be used to start anything new.
     */
    public List<TaskDefinition> deleteTaskDefinitions(List<String> taskDefinitionRefs, String region) {
        if (taskDefinitionRefs.size() > MAX_TASKS_PER_RUN) {
            throw new AwsException("InvalidParameterException",
                    "You can specify up to " + MAX_TASKS_PER_RUN + " task definitions.", 400);
        }
        List<TaskDefinition> deleted = new ArrayList<>();
        for (String ref : taskDefinitionRefs) {
            if (!namesARevision(ref)) {
                throw new AwsException("InvalidParameterException",
                        "You must specify a revision to delete a task definition: " + ref, 400);
            }
            TaskDefinition td = resolveTaskDefinitionOrThrow(ref, region);
            if (!STATUS_INACTIVE.equals(td.getStatus())) {
                throw new AwsException("InvalidParameterException",
                        "Task definition " + ref + " must be INACTIVE before deletion.", 400);
            }
            td.setStatus(STATUS_DELETE_IN_PROGRESS);
            td.setDeleteRequestedAt(Instant.now());
            taskDefinitions.put(td.getFamily() + ":" + td.getRevision(), td);
            deleted.add(td);
        }
        return deleted;
    }

    /** A reference names a revision when it carries one, as {@code family:1} or an ARN does. */
    private static boolean namesARevision(String ref) {
        if (ref == null) {
            return false;
        }
        String tail = ref.startsWith("arn:") ? ref.substring(ref.lastIndexOf('/') + 1) : ref;
        int colon = tail.lastIndexOf(':');
        if (colon < 0) {
            return false;
        }
        try {
            Integer.parseInt(tail.substring(colon + 1));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ── Tasks ─────────────────────────────────────────────────────────────────

    public List<EcsTask> runTask(String clusterRef, String taskDefinitionRef, int count,
                                  LaunchType launchType, String group, String startedBy,
                                  List<ContainerOverride> containerOverrides,
                                  NetworkConfiguration networkConfiguration, String region) {
        RunTaskRequest request = new RunTaskRequest();
        request.setCluster(clusterRef);
        request.setTaskDefinition(taskDefinitionRef);
        request.setCount(count);
        request.setLaunchType(launchType);
        request.setGroup(group);
        request.setStartedBy(startedBy);
        request.setNetworkConfiguration(networkConfiguration);
        if (containerOverrides != null && !containerOverrides.isEmpty()) {
            TaskOverride overrides = new TaskOverride();
            overrides.setContainerOverrides(containerOverrides);
            request.setOverrides(overrides);
        }
        return runTask(request, region);
    }

    public List<EcsTask> runTask(RunTaskRequest request, String region) {
        // RunTask places at most 10 tasks per call, and propagating a service's tags is a service
        // concept: both are rejected rather than quietly ignored.
        if (request.getCount() < 1 || request.getCount() > MAX_TASKS_PER_RUN) {
            throw new AwsException("InvalidParameterException",
                    "count must be between 1 and " + MAX_TASKS_PER_RUN + ".", 400);
        }
        if (PROPAGATE_TAGS_SERVICE.equals(request.getPropagateTags())) {
            throw new AwsException("InvalidParameterException",
                    "The SERVICE option is not supported when running a task.", 400);
        }
        EcsCluster cluster = resolveClusterOrDefault(request.getCluster(), region);
        TaskDefinition taskDef = resolveLaunchableTaskDefinition(request.getTaskDefinition(), region);
        return launchTasks(cluster, taskDef, request, null, null, region);
    }

    public List<EcsTask> startTask(String clusterRef, List<String> containerInstanceRefs,
                                    String taskDefinitionRef, String group, String startedBy, String region) {
        RunTaskRequest request = new RunTaskRequest();
        request.setCluster(clusterRef);
        request.setTaskDefinition(taskDefinitionRef);
        request.setGroup(group);
        request.setStartedBy(startedBy);
        request.setContainerInstances(containerInstanceRefs);
        return startTask(request, region);
    }

    public List<EcsTask> startTask(RunTaskRequest request, String region) {
        List<String> instanceRefs = request.getContainerInstances() != null
                ? request.getContainerInstances() : List.of();
        // StartTask places onto instances the caller names, so naming none is a malformed request,
        // and ECS takes at most ten of them per call.
        if (instanceRefs.isEmpty()) {
            throw new AwsException("InvalidParameterException",
                    "containerInstances is required and cannot be empty.", 400);
        }
        if (instanceRefs.size() > MAX_TASKS_PER_RUN) {
            throw new AwsException("InvalidParameterException",
                    "You can specify up to " + MAX_TASKS_PER_RUN + " container instances.", 400);
        }
        EcsCluster cluster = resolveClusterOrDefault(request.getCluster(), region);
        TaskDefinition taskDef = resolveLaunchableTaskDefinition(request.getTaskDefinition(), region);
        List<EcsTask> result = new ArrayList<>();
        // StartTask places one task onto each named container instance, which is EC2 by definition.
        request.setCount(1);
        request.setLaunchType(LaunchType.EC2);
        request.setCapacityProviderStrategy(null);
        for (String instanceRef : instanceRefs) {
            ContainerInstance instance = resolveContainerInstanceOrThrow(cluster.getClusterArn(), instanceRef);
            result.addAll(launchTasks(cluster, taskDef, request,
                    instance.getContainerInstanceArn(), null, region));
        }
        return result;
    }

    /**
     * @param owningServiceArn ARN of the service this task is being launched for, or {@code null}
     *                         for a caller-driven {@code RunTask}/{@code StartTask}. Only the
     *                         service reconciler supplies it; it is what later identifies the
     *                         task as service-owned, since the caller-supplied {@code group}
     *                         cannot be trusted for that.
     */
    private List<EcsTask> launchTasks(EcsCluster cluster, TaskDefinition taskDef, RunTaskRequest request,
                                       String containerInstanceArn,
                                       String owningServiceArn, String region) {
        // Fail loudly instead of silently launching zero containers and leaving
        // a task that looks RUNNING with nothing behind it.
        if (taskDef.getContainerDefinitions() == null || taskDef.getContainerDefinitions().isEmpty()) {
            LOG.warnv("Task definition {0} has no container definitions; refusing to launch tasks",
                    taskDef.getTaskDefinitionArn());
            throw new AwsException("ClientException",
                    "Task definition " + taskDef.getTaskDefinitionArn() + " has no container definitions.", 400);
        }
        int count = Math.max(1, request.getCount());
        NetworkConfiguration networkConfiguration = request.getNetworkConfiguration();
        validateLaunchNetworking(taskDef, networkConfiguration);
        List<Placement> placements = resolvePlacements(cluster, request, count);
        List<ContainerOverride> containerOverrides = request.getContainerOverrides();
        List<EcsTask> launched = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String taskId = UUID.randomUUID().toString().replace("-", "");
            String taskArn = regionResolver.buildArn("ecs", region,
                    "task/" + cluster.getClusterName() + "/" + taskId);
            Placement placement = placements.get(i);

            EcsTask task = new EcsTask();
            task.setTaskArn(taskArn);
            task.setClusterArn(cluster.getClusterArn());
            task.setTaskDefinitionArn(taskDef.getTaskDefinitionArn());
            task.setLaunchType(placement.launchType());
            task.setCapacityProviderName(placement.capacityProviderName());
            task.setGroup(request.getGroup() != null ? request.getGroup() : "family:" + taskDef.getFamily());
            task.setStartedBy(request.getStartedBy());
            task.setOwningServiceArn(owningServiceArn);
            task.setLastStatus(TaskStatus.PENDING.name());
            task.setDesiredStatus(TaskStatus.RUNNING.name());
            task.setCpu(effectiveCpu(taskDef, request));
            task.setMemory(effectiveMemory(taskDef, request));
            task.setCreatedAt(Instant.now());
            task.setContainers(List.of());
            task.setContainerInstanceArn(containerInstanceArn);
            task.setNetworkConfiguration(networkConfiguration);
            task.setOverrides(request.getOverrides());
            task.setEnableExecuteCommand(request.isEnableExecuteCommand());
            task.setAttributes(List.of(new Attribute(CPU_ARCHITECTURE_ATTRIBUTE, hostCpuArchitecture(),
                    null, null)));
            applyPlatform(task, taskDef, request);
            Map<String, String> taskTags = PROPAGATE_TAGS_TASK_DEFINITION.equals(request.getPropagateTags())
                    ? taskDef.getTags() : request.getTags();
            if (taskTags != null && !taskTags.isEmpty()) {
                task.setTags(new LinkedHashMap<>(taskTags));
            }
            // Before the task is visible: an awsvpc task that cannot get its ENI (an unknown
            // subnet, say) never existed, the way AWS rejects the request rather than placing it.
            containerManager.attachTaskNetwork(task, taskDef, region);

            tasks.put(taskArn, task);

            if (dockerMode) {
                try {
                    task.setPullStartedAt(Instant.now());
                    EcsTaskHandle handle = containerManager.startTask(task, taskDef, containerOverrides, region);
                    task.setPullStoppedAt(Instant.now());
                    boolean stopRequested;
                    synchronized (task) {
                        taskHandles.put(taskArn, handle);
                        markTaskRunning(task);
                        cluster.setRunningTasksCount(cluster.getRunningTasksCount() + 1);
                        LOG.infov("Started ECS task (docker): {0}", taskArn);
                        stopRequested = TaskStatus.STOPPED.name().equals(task.getDesiredStatus());
                        if (!stopRequested) {
                            registerTaskWithLoadBalancers(task, cluster, region);
                            registerTaskForServiceDiscovery(task, cluster, region);
                        }
                        if (eventPublisher != null) {
                            eventPublisher.emitTaskLadder(task, TaskStatus.PENDING, TaskStatus.RUNNING, region);
                        }
                    }
                    if (stopRequested) {
                        stopTask(cluster.getClusterName(), taskArn, task.getStoppedReason(), task.getStopCode(), region);
                    }
                } catch (Exception e) {
                    LOG.errorv("Failed to start ECS task {0}: {1}", taskArn, e.getMessage());
                    task.setLastStatus(TaskStatus.STOPPED.name());
                    task.setDesiredStatus(TaskStatus.STOPPED.name());
                    // A ResourceInitializationError is already AWS's exact stopped-reason wording
                    // (e.g. a secret that could not be resolved), so pass it through verbatim.
                    // Other start failures keep the generic prefix.
                    boolean resourceInitError = e instanceof AwsException ae
                            && "ResourceInitializationError".equals(ae.getErrorCode());
                    task.setStoppedReason(resourceInitError
                            ? e.getMessage()
                            : "Failed to start: " + e.getMessage());
                    task.setStopCode(STOP_CODE_TASK_FAILED_TO_START);
                    task.setStoppedAt(Instant.now());
                    task.bumpVersion();
                    containerManager.releaseTaskNetwork(task, region);
                    if (eventPublisher != null) {
                        eventPublisher.emitTaskLadder(task, TaskStatus.PENDING, TaskStatus.STOPPED, region);
                    }
                }
            } else {
                task.setContainers(mockContainers(task, taskDef, region));
                markTaskRunning(task);
                cluster.setRunningTasksCount(cluster.getRunningTasksCount() + 1);
                LOG.infov("Started ECS task (mock): {0}", taskArn);
                if (eventPublisher != null) {
                    eventPublisher.emitTaskLadder(task, TaskStatus.PENDING, TaskStatus.RUNNING, region);
                }
            }

            launched.add(task);
        }
        persistCluster(region, cluster);
        return launched;
    }

    /**
     * The container models a mock-mode task reports. Nothing is running behind them, so they carry
     * no runtime id, but a task with an empty {@code containers} list is unlike anything AWS
     * returns and breaks every client that reads the list.
     */
    private List<Container> mockContainers(EcsTask task, TaskDefinition taskDef, String region) {
        String taskId = task.getTaskArn().substring(task.getTaskArn().lastIndexOf('/') + 1);
        List<Container> containers = new ArrayList<>();
        for (ContainerDefinition def : taskDef.getContainerDefinitions()) {
            Container container = new Container();
            container.setTaskArn(task.getTaskArn());
            container.setName(def.getName());
            container.setImage(def.getImage());
            container.setLastStatus(TaskStatus.RUNNING.name());
            container.setContainerArn(regionResolver.buildArn("ecs", region,
                    "container/" + taskId + "/" + def.getName()));
            container.setNetworkBindings(List.of());
            if (def.getCpu() != null) {
                container.setCpu(String.valueOf(def.getCpu()));
            }
            if (def.getMemory() != null) {
                container.setMemory(String.valueOf(def.getMemory()));
            }
            if (task.getNetworkInterfaceId() != null) {
                container.setNetworkInterfaces(List.of(new TaskNetworkInterface(
                        task.getAttachmentId(), task.getPrivateIpAddress(), null)));
            }
            if (task.isEnableExecuteCommand()) {
                container.setManagedAgents(List.of(ManagedAgent.executeCommandAgent(Instant.now())));
            }
            containers.add(container);
        }
        return containers;
    }

    /**
     * The state a task reaches once its containers are up: RUNNING, connected, and healthy as far
     * as ECS can tell. {@code healthStatus} stays UNKNOWN unless a container declares a health
     * check, which is what AWS reports for a task nothing is probing.
     */
    private static void markTaskRunning(EcsTask task) {
        task.setLastStatus(TaskStatus.RUNNING.name());
        task.setStartedAt(Instant.now());
        task.setConnectivity(CONNECTIVITY_CONNECTED);
        task.setConnectivityAt(Instant.now());
        task.setHealthStatus(HEALTH_STATUS_UNKNOWN);
        task.bumpVersion();
    }

    /**
     * Launches one task on behalf of a service, stamping it with the service's ARN so later
     * reconciliation can recognize it as service-owned without trusting the caller-settable
     * {@code group}. This is the only path that sets {@code owningServiceArn}.
     */
    private EcsTask launchServiceTask(EcsCluster cluster, EcsServiceModel svc, LaunchType launchType,
                                       String containerInstanceArn, String region) {
        TaskDefinition taskDef = resolveTaskDefinitionOrThrow(svc.getTaskDefinition(), region);
        RunTaskRequest request = new RunTaskRequest();
        request.setCount(1);
        request.setLaunchType(launchType);
        // AWS labels a service's tasks "service:<name>" and attributes them to the deployment.
        request.setGroup("service:" + svc.getServiceName());
        request.setStartedBy(deploymentId(svc));
        request.setNetworkConfiguration(svc.getNetworkConfiguration());
        request.setPlatformVersion(svc.getPlatformVersion());
        request.setEnableExecuteCommand(svc.isEnableExecuteCommand());
        // A service pinned to a capacity provider places its tasks through it, unless the
        // reconciler is placing a DAEMON task on a named container instance.
        if (containerInstanceArn == null) {
            request.setCapacityProviderStrategy(svc.getCapacityProviderStrategy());
        }
        if (EcsService.PROPAGATE_TAGS_SERVICE.equals(svc.getPropagateTags())) {
            request.setTags(svc.getTags());
        }
        if (PROPAGATE_TAGS_TASK_DEFINITION.equals(svc.getPropagateTags())) {
            request.setPropagateTags(PROPAGATE_TAGS_TASK_DEFINITION);
        }
        EcsTask task = launchTasks(cluster, taskDef, request, containerInstanceArn,
                svc.getServiceArn(), region).getFirst();
        task.setDeploymentId(deploymentId(svc));
        return task;
    }

    /** Where a task runs: a launch type, or the capacity provider that chose one for it. */
    private record Placement(LaunchType launchType, String capacityProviderName) {}

    /**
     * Decides where each of {@code count} tasks runs, in request order.
     *
     * <p>A capacity provider strategy wins over a launch type (the two are mutually exclusive on
     * the wire), and an absent strategy falls back to the cluster's default one. With neither,
     * the task keeps Floci's FARGATE default: a local cluster has no container instances, so EC2
     * would have nowhere to place it.
     */
    private List<Placement> resolvePlacements(EcsCluster cluster, RunTaskRequest request, int count) {
        List<CapacityProviderStrategyItem> strategy = request.getCapacityProviderStrategy();
        if (strategy != null && !strategy.isEmpty() && request.getLaunchType() != null) {
            throw new AwsException("InvalidParameterException",
                    "You cannot specify both a launch type and a capacity provider strategy in the "
                            + "same request. Specify only one.", 400);
        }
        if ((strategy == null || strategy.isEmpty()) && request.getLaunchType() == null) {
            strategy = clusterDefaultStrategy(cluster);
        }
        if (strategy == null || strategy.isEmpty()) {
            LaunchType launchType = request.getLaunchType() != null ? request.getLaunchType() : LaunchType.FARGATE;
            return Collections.nCopies(count, new Placement(launchType, null));
        }
        return distribute(strategy, count);
    }

    /** The cluster's {@code defaultCapacityProviderStrategy}, which is stored as raw JSON. */
    private List<CapacityProviderStrategyItem> clusterDefaultStrategy(EcsCluster cluster) {
        List<Map<String, Object>> raw = cluster.getDefaultCapacityProviderStrategy();
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<CapacityProviderStrategyItem> result = new ArrayList<>();
        for (Map<String, Object> entry : raw) {
            Object provider = entry.get("capacityProvider");
            if (provider == null) {
                continue;
            }
            result.add(new CapacityProviderStrategyItem(provider.toString(),
                    intValue(entry.get("weight")), intValue(entry.get("base"))));
        }
        return result;
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /**
     * Checks a strategy against the bounds the ECS model puts on it: at most twenty providers,
     * each entry's {@code weight} between 0 and 1,000 and its {@code base} between 0 and 100,000,
     * and only one entry carrying a base.
     *
     * <p>A provider weighing 0 places nothing beyond its base, so a strategy of several providers
     * that all weigh 0 has nowhere to put the tasks no base covers and the model has RunTask and
     * CreateService fail on it. A lone entry weighing 0 is left alone: the model scopes that rule
     * to a strategy naming more than one provider.
     */
    private void validateCapacityProviderStrategy(List<CapacityProviderStrategyItem> strategy) {
        if (strategy.size() > MAX_CAPACITY_PROVIDERS_PER_STRATEGY) {
            throw new AwsException("InvalidParameterException",
                    "A capacity provider strategy can contain a maximum of "
                            + MAX_CAPACITY_PROVIDERS_PER_STRATEGY + " capacity providers.", 400);
        }
        for (CapacityProviderStrategyItem item : strategy) {
            if (item.weight() < 0 || item.weight() > MAX_CAPACITY_PROVIDER_WEIGHT) {
                throw new AwsException("InvalidParameterException",
                        "The weight of a capacity provider strategy entry must be between 0 and "
                                + MAX_CAPACITY_PROVIDER_WEIGHT + ".", 400);
            }
            if (item.base() < 0 || item.base() > MAX_CAPACITY_PROVIDER_BASE) {
                throw new AwsException("InvalidParameterException",
                        "The base of a capacity provider strategy entry must be between 0 and "
                                + MAX_CAPACITY_PROVIDER_BASE + ".", 400);
            }
        }
        if (strategy.stream().filter(item -> item.base() > 0).count() > 1) {
            throw new AwsException("InvalidParameterException",
                    "Only one capacity provider in a capacity provider strategy can have a base defined.", 400);
        }
        if (strategy.size() > 1 && strategy.stream().allMatch(item -> item.weight() == 0)) {
            throw new AwsException("InvalidParameterException",
                    "At least one capacity provider in a capacity provider strategy must have a "
                            + "weight greater than zero.", 400);
        }
        strategy.forEach(item -> requireCapacityProvider(item.capacityProvider()));
    }

    /**
     * Spreads {@code count} tasks over a strategy the way ECS does: the single entry that declares
     * a {@code base} is filled first, then whatever is left is split by weight. Leftovers from the
     * integer split go to the heaviest entries, so a 1:1 strategy with an odd count still places
     * every task.
     */
    private List<Placement> distribute(List<CapacityProviderStrategyItem> strategy, int count) {
        validateCapacityProviderStrategy(strategy);

        List<Placement> placements = new ArrayList<>();
        for (CapacityProviderStrategyItem item : strategy) {
            for (int i = 0; i < item.base() && placements.size() < count; i++) {
                placements.add(placementFor(item.capacityProvider()));
            }
        }
        int remaining = count - placements.size();
        if (remaining <= 0) {
            return placements;
        }
        int totalWeight = strategy.stream().mapToInt(CapacityProviderStrategyItem::weight).sum();
        if (totalWeight <= 0) {
            // Validation already rejected a multi-entry strategy whose weights are all 0, so the
            // only way to get here is a lone entry that carries no weight: it is the one provider
            // the request named, and the tasks its base did not cover go on it.
            for (int i = 0; i < remaining; i++) {
                placements.add(placementFor(strategy.getFirst().capacityProvider()));
            }
            return placements;
        }
        int[] shares = new int[strategy.size()];
        int assigned = 0;
        for (int i = 0; i < strategy.size(); i++) {
            shares[i] = remaining * strategy.get(i).weight() / totalWeight;
            assigned += shares[i];
        }
        List<Integer> byWeightDescending = new ArrayList<>();
        for (int i = 0; i < strategy.size(); i++) {
            byWeightDescending.add(i);
        }
        byWeightDescending.sort(Comparator.comparingInt((Integer i) -> strategy.get(i).weight()).reversed());
        for (int i = 0; assigned < remaining; i++) {
            shares[byWeightDescending.get(i % byWeightDescending.size())]++;
            assigned++;
        }
        for (int i = 0; i < strategy.size(); i++) {
            for (int placed = 0; placed < shares[i]; placed++) {
                placements.add(placementFor(strategy.get(i).capacityProvider()));
            }
        }
        return placements;
    }

    /** FARGATE and FARGATE_SPOT place Fargate tasks; any other provider is backed by an ASG. */
    private static Placement placementFor(String capacityProvider) {
        LaunchType launchType = FARGATE_CAPACITY_PROVIDERS.contains(capacityProvider)
                ? LaunchType.FARGATE : LaunchType.EC2;
        return new Placement(launchType, capacityProvider);
    }

    /** Accepts the two built-in Fargate providers as well as anything CreateCapacityProvider made. */
    private void requireCapacityProvider(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterException",
                    "A capacity provider strategy entry must name a capacity provider.", 400);
        }
        if (FARGATE_CAPACITY_PROVIDERS.contains(name) || capacityProviders.containsKey(name)) {
            return;
        }
        boolean knownByArn = capacityProviders.values().stream()
                .anyMatch(cp -> name.equals(cp.getCapacityProviderArn()));
        if (!knownByArn) {
            throw new AwsException("ClientException",
                    "The specified capacity provider '" + name + "' was not found. "
                            + "Specify a valid capacity provider and try again.", 400);
        }
    }

    /** An awsvpc task cannot be placed without the subnets its ENI goes into. */
    private static void validateLaunchNetworking(TaskDefinition taskDef, NetworkConfiguration networkConfiguration) {
        if (taskDef.getNetworkMode() != NetworkMode.awsvpc) {
            return;
        }
        boolean hasSubnets = networkConfiguration != null
                && networkConfiguration.getAwsvpcConfiguration() != null
                && networkConfiguration.getAwsvpcConfiguration().getSubnets() != null
                && !networkConfiguration.getAwsvpcConfiguration().getSubnets().isEmpty();
        if (!hasSubnets) {
            throw new AwsException("InvalidParameterException",
                    "Network Configuration must be provided when networkMode 'awsvpc' is specified.", 400);
        }
    }

    private static String effectiveCpu(TaskDefinition taskDef, RunTaskRequest request) {
        TaskOverride overrides = request.getOverrides();
        if (overrides != null && overrides.getCpu() != null) {
            return overrides.getCpu();
        }
        return taskDef.getCpu();
    }

    private static String effectiveMemory(TaskDefinition taskDef, RunTaskRequest request) {
        TaskOverride overrides = request.getOverrides();
        if (overrides != null && overrides.getMemory() != null) {
            return overrides.getMemory();
        }
        return taskDef.getMemory();
    }

    /**
     * The architecture a task reports running on. A task definition's {@code runtimePlatform} does
     * not move it anywhere: every local task runs on the host, so the host's architecture is what
     * the attribute says.
     */
    private static String hostCpuArchitecture() {
        String architecture = System.getProperty("os.arch", "");
        return switch (architecture) {
            case "aarch64", "arm64" -> "arm64";
            default -> "x86_64";
        };
    }

    /**
     * Stamps the Fargate platform members. {@code LATEST} resolves to a concrete version the way
     * AWS resolves it, since a client that asked for LATEST still reads back the version it got.
     * An EC2 task has neither member.
     */
    private static void applyPlatform(EcsTask task, TaskDefinition taskDef, RunTaskRequest request) {
        if (task.getLaunchType() != LaunchType.FARGATE) {
            return;
        }
        String requested = request.getPlatformVersion();
        task.setPlatformVersion(requested == null || requested.isBlank() || PLATFORM_VERSION_LATEST.equals(requested)
                ? DEFAULT_PLATFORM_VERSION : requested);
        task.setPlatformFamily(platformFamilyOf(taskDef));
        TaskOverride overrides = request.getOverrides();
        EphemeralStorage ephemeralStorage = overrides != null && overrides.getEphemeralStorage() != null
                ? overrides.getEphemeralStorage() : taskDef.getEphemeralStorage();
        task.setEphemeralStorage(ephemeralStorage != null
                ? ephemeralStorage : new EphemeralStorage(EphemeralStorage.DEFAULT_SIZE_IN_GIB));
    }

    public EcsTask stopTask(String clusterRef, String taskRef, String reason, String region) {
        return stopTask(clusterRef, taskRef, reason, STOP_CODE_USER_INITIATED, region);
    }

    /**
     * @param stopCode why the task is going away, as the {@code stopCode} AWS reports: a caller's
     *                 StopTask is {@code UserInitiated}, anything the reconciler decides is
     *                 {@code ServiceSchedulerInitiated}.
     */
    private EcsTask stopTask(String clusterRef, String taskRef, String reason, String stopCode, String region) {
        EcsTask task = resolveTaskOrThrow(taskRef, region);
        synchronized (task) {
            return stopTaskLocked(task, reason, stopCode, region);
        }
    }

    private EcsTask stopTaskLocked(EcsTask task, String reason, String stopCode, String region) {
        if (TaskStatus.STOPPED.name().equals(task.getLastStatus())) {
            return task;
        }
        if (!TaskStatus.STOPPING.name().equals(task.getLastStatus())) {
            task.setDesiredStatus(TaskStatus.STOPPED.name());
            task.setLastStatus(TaskStatus.STOPPING.name());
            task.setStoppingAt(Instant.now());
            task.setStoppedReason(reason != null ? reason : "Stopped by user");
            task.setStopCode(stopCode);
            task.bumpVersion();
            deregisterTaskFromLoadBalancers(task, region);
            deregisterTaskFromServiceDiscovery(task, region);
        }

        Map<String, Integer> exitCodes = Map.of();
        Map<String, Instant> finishedAt = Map.of();
        if (dockerMode) {
            EcsTaskHandle handle = taskHandles.get(task.getTaskArn());
            if (handle == null) {
                handle = recoverTaskHandle(task, region);
                if (handle == null) {
                    LOG.warnv("Cannot stop ECS task {0}: no Docker container IDs are available", task.getTaskArn());
                    return task;
                }
            }
            try {
                exitCodes = containerManager.stopTaskAndCollectExitCodes(handle);
            } catch (Exception e) {
                LOG.warnv(e, "Could not stop ECS task {0}; retrying on the next reconciliation tick",
                        task.getTaskArn());
                return task;
            }
            if (exitCodes.size() != handle.getContainerIds().size()
                    || !handle.allContainersRemoved()) {
                LOG.warnv("ECS task {0} still has containers pending removal; retrying on the next reconciliation tick",
                        task.getTaskArn());
                return task;
            }
            taskHandles.remove(task.getTaskArn(), handle);
            retainUnresolvedLogHandle(task.getTaskArn(), handle);
            finishedAt = handle.getFinishedAt();
        }

        task.setLastStatus(TaskStatus.STOPPED.name());
        task.setStoppedAt(Instant.now());
        task.setExecutionStoppedAt(Instant.now());
        task.bumpVersion();
        containerManager.releaseTaskNetwork(task, region);
        if (task.getContainers() != null) {
            final Map<String, Integer> codes = exitCodes;
            final Map<String, Instant> finishTimes = finishedAt;
            task.getContainers().forEach(c -> {
                c.setLastStatus("STOPPED");
                Integer code = codes.get(c.getName());
                if (code != null) {
                    c.setExitCode(code);
                }
                Instant finished = finishTimes.get(c.getName());
                if (finished != null) {
                    c.setFinishedAt(finished);
                }
            });
        }

        EcsCluster cluster = resolveClusterByArn(task.getClusterArn());
        if (cluster != null && cluster.getRunningTasksCount() > 0) {
            cluster.setRunningTasksCount(cluster.getRunningTasksCount() - 1);
            persistCluster(region, cluster);
        }

        LOG.infov("Stopped ECS task: {0}", task.getTaskArn());
        if (eventPublisher != null) {
            eventPublisher.emitTaskLadder(task, TaskStatus.RUNNING, TaskStatus.STOPPED, region);
        }
        return task;
    }

    /** Registers a freshly-started task's containers as ELBv2 targets if its service is load-balanced. */
    private void registerTaskWithLoadBalancers(EcsTask task, EcsCluster cluster, String region) {
        EcsServiceModel svc = owningService(task, cluster);
        if (svc != null && !svc.getLoadBalancers().isEmpty()) {
            lbRegistrar.registerTask(task, svc, region);
        }
    }

    /** Registers a freshly-started task in Cloud Map if its service declared service registries. */
    private void registerTaskForServiceDiscovery(EcsTask task, EcsCluster cluster, String region) {
        if (discoveryRegistrar == null) {
            return;
        }
        EcsServiceModel svc = owningService(task, cluster);
        if (svc != null && discoveryRegistrar.hasRegistries(svc)) {
            discoveryRegistrar.registerTask(task, svc, region);
        }
    }

    /**
     * Resolves the service that actually launched {@code task}, or {@code null} for a
     * caller-driven task. Keyed off the reconciler-stamped {@code owningServiceArn} rather than
     * the caller-supplied {@code group}, so a {@code RunTask} cannot name an unrelated service
     * and have its containers registered into that service's target groups.
     */
    private EcsServiceModel owningService(EcsTask task, EcsCluster cluster) {
        if (task.getOwningServiceArn() == null) {
            return null;
        }
        return services.values().stream()
                .filter(svc -> ownedBy(task, svc, cluster))
                .findFirst()
                .orElse(null);
    }

    /** Deregisters a stopping task's containers from any ELBv2 target groups its service declared. */
    private void deregisterTaskFromLoadBalancers(EcsTask task, String region) {
        // Gated on dockerMode for symmetry with the register hook (inside launchTasks'
        // dockerMode branch): mock-mode tasks have no containers and never registered.
        if (!dockerMode || task.getOwningServiceArn() == null) {
            return;
        }
        EcsCluster cluster = resolveClusterByArn(task.getClusterArn());
        if (cluster == null) {
            return;
        }
        EcsServiceModel svc = owningService(task, cluster);
        if (svc != null && !svc.getLoadBalancers().isEmpty()) {
            lbRegistrar.deregisterTask(task, svc, region);
        }
    }

    /** Deregisters a stopping task from the Cloud Map services it actually joined. */
    private void deregisterTaskFromServiceDiscovery(EcsTask task, String region) {
        if (discoveryRegistrar == null || !dockerMode || task.getServiceDiscoveryServiceIds().isEmpty()) {
            return;
        }
        discoveryRegistrar.deregisterTask(task, region);
    }

    // ── ECS Exec ──────────────────────────────────────────────────────────────

    /** The resolved target of an {@code ExecuteCommand}, with the session the client connects to. */
    public record ExecuteCommandResult(EcsTask task, Container container, ExecSession session) {}

    /**
     * Opens an ECS Exec session against one of a task's containers.
     *
     * <p>The gate is the same as AWS's: the task must be running with execute-command enabled, and
     * the container must actually be there to exec into. The returned session carries a stream URL
     * and a single-use token, which {@code session-manager-plugin} then uses to open the data
     * channel.
     */
    public ExecuteCommandResult executeCommand(String clusterRef, String taskRef, String containerName,
                                                String command, boolean interactive, String region) {
        if (command == null || command.isBlank()) {
            throw new AwsException("InvalidParameterException",
                    "The command cannot be empty.", 400);
        }
        // ECS only ever opens interactive sessions, so a request that asks for anything else is
        // asking for something the API cannot do.
        if (!interactive) {
            throw new AwsException("InvalidParameterException",
                    "Amazon ECS only supports initiating interactive sessions, so you must specify "
                            + "true for the interactive parameter.", 400);
        }
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        EcsTask task = resolveTaskOrThrow(taskRef, region);
        if (!cluster.getClusterArn().equals(task.getClusterArn())) {
            throw new AwsException("InvalidParameterException",
                    "The task " + task.getTaskArn() + " is not part of the cluster "
                            + cluster.getClusterArn() + ".", 400);
        }
        if (!TaskStatus.RUNNING.name().equals(task.getLastStatus())) {
            throw new AwsException("TargetNotConnectedException",
                    "The execute command failed because the task is not running.", 400);
        }
        if (!task.isEnableExecuteCommand()) {
            throw new AwsException("InvalidParameterException",
                    "The execute command failed because execute command was not enabled when the task "
                            + "was run or the execute command agent isn't running. Wait and try again "
                            + "or run a new task with execute command enabled and try again.", 400);
        }
        Container container = resolveExecContainer(task, containerName);
        if (container.getRuntimeId() == null || container.getRuntimeId().isBlank()) {
            throw new AwsException("TargetNotConnectedException",
                    "The execute command failed because the execute command agent is not running in "
                            + "container " + container.getName() + ".", 400);
        }
        ExecSession session = execSessions.create(task.getTaskArn(), task.getClusterArn(),
                container.getName(), container.getContainerArn(), container.getRuntimeId(),
                List.of("/bin/sh", "-c", command), interactive);
        LOG.infov("Opened an ECS Exec session on {0} container {1}", task.getTaskArn(), container.getName());
        return new ExecuteCommandResult(task, container, session);
    }

    private static Container resolveExecContainer(EcsTask task, String containerName) {
        List<Container> containers = task.getContainers() != null ? task.getContainers() : List.of();
        if (containers.isEmpty()) {
            throw new AwsException("TargetNotConnectedException",
                    "The execute command failed because the task has no running containers.", 400);
        }
        if (containerName == null || containerName.isBlank()) {
            if (containers.size() > 1) {
                throw new AwsException("InvalidParameterException",
                        "The task has more than one container, so the container to run the command "
                                + "in must be named.", 400);
            }
            return containers.getFirst();
        }
        return containers.stream()
                .filter(container -> containerName.equals(container.getName()))
                .findFirst()
                .orElseThrow(() -> new AwsException("InvalidParameterException",
                        "The container " + containerName + " is not part of the task.", 400));
    }

    /** The {@code ws://} URL a client opens for an exec session's data channel. */
    public String execStreamUrl(ExecSession session) {
        String websocketBase = baseUrl.startsWith("https://")
                ? "wss://" + baseUrl.substring("https://".length())
                : "ws://" + baseUrl.substring(baseUrl.indexOf("://") + 3);
        return websocketBase + EcsExecChannelHandler.CHANNEL_PATH_PREFIX + session.sessionId();
    }

    /** A container reached through the task metadata endpoint, with the task it belongs to. */
    public record MetadataTarget(EcsTask task, Container container, TaskDefinition taskDefinition) {}

    /**
     * Resolves the {@code ECS_CONTAINER_METADATA_URI_V4} id a container was given at launch. The id
     * is minted per container and never reused, so it identifies both the container and its task.
     */
    public Optional<MetadataTarget> findByMetadataId(String metadataId) {
        if (metadataId == null || metadataId.isBlank()) {
            return Optional.empty();
        }
        for (EcsTask task : tasks.values()) {
            if (task.getContainers() == null) {
                continue;
            }
            for (Container container : task.getContainers()) {
                if (metadataId.equals(container.getMetadataId())) {
                    return Optional.of(new MetadataTarget(task, container,
                            taskDefinitionOf(task)));
                }
            }
        }
        return Optional.empty();
    }

    /** The task's definition, or null when it has since been deleted. */
    private TaskDefinition taskDefinitionOf(EcsTask task) {
        try {
            return resolveTaskDefinitionOrThrow(task.getTaskDefinitionArn(), taskRegion(task));
        } catch (AwsException e) {
            LOG.debugv("Task {0} references a task definition that is gone: {1}",
                    task.getTaskArn(), e.getMessage());
            return null;
        }
    }

    public List<EcsTask> describeTasks(String clusterRef, List<String> taskRefs, String region) {
        return describeTasksDetailed(clusterRef, taskRefs, region).tasks();
    }

    public record DescribeTasksResult(List<EcsTask> tasks, List<Failure> failures) {}

    /**
     * Resolves each reference, reporting the ones that do not exist as {@code MISSING} failures.
     * ECS answers a describe for an unknown task with a failure entry rather than an error, and the
     * {@code TasksRunning} and {@code TasksStopped} waiters treat a MISSING failure as terminal: a
     * describe that returns neither the task nor a failure leaves them polling until they time out.
     */
    public DescribeTasksResult describeTasksDetailed(String clusterRef, List<String> taskRefs,
                                                      String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        List<EcsTask> result = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        for (String ref : taskRefs) {
            EcsTask task = resolveTask(ref, region);
            if (task != null) {
                result.add(task);
            } else {
                failures.add(Failure.missing(ref != null && ref.startsWith("arn:")
                        ? ref
                        : regionResolver.buildArn("ecs", region,
                                "task/" + cluster.getClusterName() + "/" + ref)));
            }
        }
        return new DescribeTasksResult(result, failures);
    }

    public List<String> listTasks(String clusterRef, String family, String desiredStatus,
                                   String serviceName, String region) {
        ListTasksRequest request = new ListTasksRequest();
        request.setCluster(clusterRef);
        request.setFamily(family);
        request.setDesiredStatus(desiredStatus);
        request.setServiceName(serviceName);
        return listTasks(request, region).arns();
    }

    /** A page of ARNs, with the token that continues the listing. */
    public record ListPage(List<String> arns, String nextToken) {}

    /**
     * Lists task ARNs under the request's filters.
     *
     * <p>The default filter is a desired status of {@code RUNNING}, as on AWS: a listing that has
     * not asked for stopped tasks does not get them. {@code PENDING} matches nothing, because ECS
     * only ever sets a task's desired status to RUNNING or STOPPED.
     */
    public ListPage listTasks(ListTasksRequest request, String region) {
        String startedBy = request.getStartedBy();
        if (startedBy != null && !startedBy.isBlank() && request.hasOtherFilters()) {
            throw new AwsException("InvalidParameterException",
                    "When you specify startedBy as a filter, it must be the only filter used.", 400);
        }
        String desiredStatus = request.getDesiredStatus() != null
                ? request.getDesiredStatus() : TaskStatus.RUNNING.name();

        String clusterRef = request.getCluster();
        String serviceName = request.getServiceName();
        // Resolving the default cluster also creates and persists it, and ListTasks is a read:
        // when no cluster is named, look the default up without materializing it. A service
        // filter then simply matches nothing if that cluster does not exist yet.
        EcsCluster cluster = clusterRef != null
                ? resolveClusterOrDefault(clusterRef, region)
                : (serviceName != null ? clusters.get(clusterKey(region, DEFAULT_CLUSTER)) : null);
        String clusterArn = clusterRef != null ? cluster.getClusterArn() : null;
        // A serviceName filter selects the service's own tasks, so it resolves through the
        // reconciler-stamped ownership rather than the caller-supplied group.
        EcsServiceModel svc = serviceName != null && cluster != null
                ? services.get(serviceKey(region, cluster.getClusterName(), serviceName))
                : null;
        String family = request.getFamily();
        LaunchType launchType = request.getLaunchType();
        String containerInstance = request.getContainerInstance();

        List<String> matching = tasks.values().stream()
                .filter(t -> clusterArn == null || t.getClusterArn().equals(clusterArn))
                .filter(t -> family == null || t.getTaskDefinitionArn().contains("/" + family + ":"))
                .filter(t -> desiredStatus.equals(t.getDesiredStatus()))
                .filter(t -> launchType == null || launchType == t.getLaunchType())
                .filter(t -> startedBy == null || startedBy.isBlank() || startedBy.equals(t.getStartedBy()))
                .filter(t -> containerInstance == null
                        || matchesContainerInstance(t, containerInstance, clusterArn))
                .filter(t -> serviceName == null || (svc != null && ownedBy(t, svc, cluster)))
                .map(EcsTask::getTaskArn)
                .toList();

        return paginate(matching, request.getMaxResults(), request.getNextToken());
    }

    /** A container instance filter takes the instance's id or its full ARN. */
    private boolean matchesContainerInstance(EcsTask task, String containerInstance, String clusterArn) {
        String taskInstanceArn = task.getContainerInstanceArn();
        if (taskInstanceArn == null) {
            return false;
        }
        return taskInstanceArn.equals(containerInstance)
                || taskInstanceArn.endsWith("/" + containerInstance);
    }

    /**
     * Cuts a listing into the page the caller asked for. The token is the opaque offset AWS's
     * tokens are, and the page size is capped the way ListTasks caps it.
     */
    private static ListPage paginate(List<String> arns, Integer maxResults, String nextToken) {
        return paginate(arns, maxResults, nextToken, MAX_LIST_RESULTS);
    }

    /**
     * @param defaultPageSize the page size when the request names none, which is not the same for
     *                        every listing: ListServices answers with ten, the rest with a hundred.
     */
    private static ListPage paginate(List<String> arns, Integer maxResults, String nextToken,
                                      int defaultPageSize) {
        int offset = decodeListToken(nextToken);
        int pageSize = maxResults != null && maxResults > 0
                ? Math.min(maxResults, MAX_LIST_RESULTS) : defaultPageSize;
        if (offset >= arns.size()) {
            return new ListPage(List.of(), null);
        }
        int end = Math.min(offset + pageSize, arns.size());
        String token = end < arns.size()
                ? Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8))
                : null;
        return new ListPage(List.copyOf(arns.subList(offset, end)), token);
    }

    private static int decodeListToken(String nextToken) {
        if (nextToken == null || nextToken.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(new String(Base64.getUrlDecoder().decode(nextToken),
                    StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterException", "The nextToken is not valid.", 400);
        }
    }

    // ── Task Protection ───────────────────────────────────────────────────────

    public List<ProtectedTask> updateTaskProtection(String clusterRef, List<String> taskRefs,
                                                     boolean protectionEnabled, Integer expiresInMinutes,
                                                     String region) {
        List<ProtectedTask> result = new ArrayList<>();
        for (String ref : taskRefs) {
            EcsTask task = resolveTaskOrThrow(ref, region);
            task.setProtectionEnabled(protectionEnabled);
            Instant expiration = null;
            if (protectionEnabled && expiresInMinutes != null) {
                expiration = Instant.now().plusSeconds(expiresInMinutes * 60L);
                task.setProtectedUntil(expiration);
            } else {
                task.setProtectedUntil(null);
            }
            result.add(new ProtectedTask(task.getTaskArn(), protectionEnabled, expiration));
        }
        return result;
    }

    public List<ProtectedTask> getTaskProtection(String clusterRef, List<String> taskRefs, String region) {
        List<ProtectedTask> result = new ArrayList<>();
        for (String ref : taskRefs) {
            EcsTask task = resolveTaskOrThrow(ref, region);
            result.add(new ProtectedTask(task.getTaskArn(), task.isProtectionEnabled(), task.getProtectedUntil()));
        }
        return result;
    }

    // ── Services ──────────────────────────────────────────────────────────────

    public EcsServiceModel createService(String clusterRef, String serviceName, String taskDefinition,
                                          int desiredCount, LaunchType launchType,
                                          List<EcsLoadBalancer> loadBalancers,
                                          NetworkConfiguration networkConfiguration, String region) {
        return createService(clusterRef, serviceName, taskDefinition, desiredCount, launchType,
                loadBalancers, networkConfiguration, null, region);
    }

    public EcsServiceModel createService(String clusterRef, String serviceName, String taskDefinition,
                                          int desiredCount, LaunchType launchType,
                                          List<EcsLoadBalancer> loadBalancers,
                                          NetworkConfiguration networkConfiguration,
                                          Map<String, String> tags, String region) {
        return createService(clusterRef, serviceName, taskDefinition, desiredCount, launchType,
                loadBalancers, networkConfiguration, tags, null, null, null, region);
    }

    /**
     * @param schedulingStrategy          {@code REPLICA} (default) or {@code DAEMON}
     * @param deploymentControllerType    {@code ECS} (default), {@code CODE_DEPLOY} or {@code EXTERNAL}
     * @param availabilityZoneRebalancing {@code ENABLED} or {@code DISABLED} (default)
     */
    public EcsServiceModel createService(String clusterRef, String serviceName, String taskDefinition,
                                          int desiredCount, LaunchType launchType,
                                          List<EcsLoadBalancer> loadBalancers,
                                          NetworkConfiguration networkConfiguration,
                                          Map<String, String> tags, String schedulingStrategy,
                                          String deploymentControllerType, String availabilityZoneRebalancing,
                                          String region) {
        return createService(clusterRef, serviceName, taskDefinition, desiredCount, launchType,
                loadBalancers, networkConfiguration, tags, schedulingStrategy, deploymentControllerType,
                availabilityZoneRebalancing, null, region);
    }

    /**
     * @param schedulingStrategy            {@code REPLICA} (default) or {@code DAEMON}
     * @param deploymentControllerType      {@code ECS} (default), {@code CODE_DEPLOY} or {@code EXTERNAL}
     * @param availabilityZoneRebalancing   {@code ENABLED} or {@code DISABLED} (default)
     * @param serviceConnectConfiguration   reported back on each {@code deployments[]} entry
     */
    public EcsServiceModel createService(String clusterRef, String serviceName, String taskDefinition,
                                          int desiredCount, LaunchType launchType,
                                          List<EcsLoadBalancer> loadBalancers,
                                          NetworkConfiguration networkConfiguration,
                                          Map<String, String> tags, String schedulingStrategy,
                                          String deploymentControllerType, String availabilityZoneRebalancing,
                                          Map<String, Object> serviceConnectConfiguration,
                                          String region) {
        CreateServiceRequest request = new CreateServiceRequest();
        request.setCluster(clusterRef);
        request.setServiceName(serviceName);
        request.setTaskDefinition(taskDefinition);
        request.setDesiredCount(desiredCount);
        request.setLaunchType(launchType);
        request.setLoadBalancers(loadBalancers);
        request.setNetworkConfiguration(networkConfiguration);
        request.setTags(tags);
        request.setSchedulingStrategy(schedulingStrategy);
        request.setDeploymentControllerType(deploymentControllerType);
        request.setAvailabilityZoneRebalancing(availabilityZoneRebalancing);
        request.setServiceConnectConfiguration(serviceConnectConfiguration);
        return createService(request, region);
    }

    public EcsServiceModel createService(CreateServiceRequest request, String region) {
        EcsCluster cluster = resolveClusterOrDefault(request.getCluster(), region);
        // AWS resolves family / family:revision at create time and stores the ARN; the
        // reconciler compares it with each task's taskDefinitionArn, so pin it here.
        TaskDefinition taskDef = resolveLaunchableTaskDefinition(request.getTaskDefinition(), region);
        String taskDefinition = taskDef.getTaskDefinitionArn();
        String serviceName = request.getServiceName();

        String key = serviceKey(region, cluster.getClusterName(), serviceName);
        if (services.containsKey(key)) {
            EcsServiceModel existingSvc = services.get(key);

            if ("ACTIVE".equals(existingSvc.getStatus())) {
                throw new AwsException("InvalidParameterException",
                        "Creation of service was not idempotent.", 400);
            }
        }
        if (request.getLaunchType() != null && request.getCapacityProviderStrategy() != null
                && !request.getCapacityProviderStrategy().isEmpty()) {
            throw new AwsException("InvalidParameterException",
                    "You cannot specify both a launch type and a capacity provider strategy in the "
                            + "same request. Specify only one.", 400);
        }
        validateLaunchNetworking(taskDef, request.getNetworkConfiguration());

        EcsServiceModel svc = new EcsServiceModel();
        svc.setServiceArn(regionResolver.buildArn("ecs", region,
                "service/" + cluster.getClusterName() + "/" + serviceName));
        svc.setServiceName(serviceName);
        svc.setClusterArn(cluster.getClusterArn());
        svc.setTaskDefinition(taskDefinition);
        svc.setCapacityProviderStrategy(request.getCapacityProviderStrategy());
        svc.setLaunchType(serviceLaunchType(cluster, request));
        int desiredCount = request.getDesiredCount();
        if (desiredCount < 0) {
            throw new AwsException("InvalidParameterException", "desiredCount cannot be a negative number.", 400);
        }
        svc.setDesiredCount(desiredCount);
        svc.setLoadBalancers(request.getLoadBalancers());
        svc.setServiceRegistries(request.getServiceRegistries());
        svc.setNetworkConfiguration(request.getNetworkConfiguration());
        // AWS echoes these on every DescribeServices; clients that persist them (Terraform's
        // aws_ecs_service reads all three, and schedulingStrategy is ForceNew) treat a missing
        // value as drift and replace the service on every apply.
        String strategy = request.getSchedulingStrategy() != null
                ? request.getSchedulingStrategy() : DEFAULT_SCHEDULING_STRATEGY;
        String controller = request.getDeploymentControllerType() != null
                ? request.getDeploymentControllerType() : DEFAULT_DEPLOYMENT_CONTROLLER;
        if (SCHEDULING_DAEMON.equals(strategy)
                && (svc.getLaunchType() == LaunchType.FARGATE || !DEFAULT_DEPLOYMENT_CONTROLLER.equals(controller))) {
            throw new AwsException("InvalidParameterException",
                    "Tasks using the Fargate launch type or the CODE_DEPLOY or EXTERNAL deployment "
                            + "controller types don't support the DAEMON scheduling strategy.", 400);
        }
        svc.setSchedulingStrategy(strategy);
        svc.setDeploymentController(controller);
        svc.setAvailabilityZoneRebalancing(request.getAvailabilityZoneRebalancing() != null
                ? request.getAvailabilityZoneRebalancing() : DEFAULT_AZ_REBALANCING_ON_CREATE);
        svc.setServiceConnectConfiguration(request.getServiceConnectConfiguration());
        svc.setDeploymentConfiguration(request.getDeploymentConfiguration());
        svc.setEnableExecuteCommand(request.isEnableExecuteCommand());
        svc.setEnableECSManagedTags(request.isEnableECSManagedTags());
        // Both have documented defaults a created service reports back.
        svc.setPropagateTags(request.getPropagateTags() != null
                ? request.getPropagateTags() : PROPAGATE_TAGS_NONE);
        svc.setHealthCheckGracePeriodSeconds(request.getHealthCheckGracePeriodSeconds() != null
                ? request.getHealthCheckGracePeriodSeconds() : 0);
        validateServiceRole(request, taskDef);
        svc.setRoleArn(request.getRoleArn());
        svc.setUnparsed(request.getUnparsed());
        applyServicePlatform(svc, taskDef, request.getPlatformVersion());
        svc.setStatus("ACTIVE");
        svc.setCreatedAt(Instant.now());
        svc.setLastDeploymentAt(svc.getCreatedAt());
        svc.setDeploymentId(newDeploymentId());
        if (request.getTags() != null && !request.getTags().isEmpty()) {
            svc.setTags(new LinkedHashMap<>(request.getTags()));
        }

        services.put(key, svc);
        cluster.setActiveServicesCount(cluster.getActiveServicesCount() + 1);
        persistCluster(region, cluster);
        recordServiceDeployment(svc, taskDefinition, region);
        if (eventPublisher != null) {
            eventPublisher.emitDeploymentStateChange(svc, "SERVICE_DEPLOYMENT_STARTED",
                    "ECS deployment " + svc.getDeploymentId() + " started.", region);
        }
        LOG.infov("Created ECS service: {0} in cluster {1}", serviceName, cluster.getClusterName());
        return svc;
    }

    public EcsServiceModel updateService(String clusterRef, String serviceName, String taskDefinition,
                                          Integer desiredCount, NetworkConfiguration networkConfiguration,
                                          String region) {
        return updateService(clusterRef, serviceName, taskDefinition, desiredCount, networkConfiguration,
                null, region);
    }

    public EcsServiceModel updateService(String clusterRef, String serviceName, String taskDefinition,
                                          Integer desiredCount, NetworkConfiguration networkConfiguration,
                                          String availabilityZoneRebalancing, String region) {
        return updateService(clusterRef, serviceName, taskDefinition, desiredCount, networkConfiguration,
                availabilityZoneRebalancing, false, region);
    }

    public EcsServiceModel updateService(String clusterRef, String serviceName, String taskDefinition,
                                          Integer desiredCount, NetworkConfiguration networkConfiguration,
                                          String availabilityZoneRebalancing, boolean forceNewDeployment,
                                          String region) {
        return updateService(clusterRef, serviceName, taskDefinition, desiredCount, networkConfiguration,
                availabilityZoneRebalancing, forceNewDeployment, null, region);
    }

    public EcsServiceModel updateService(String clusterRef, String serviceName, String taskDefinition,
                                          Integer desiredCount, NetworkConfiguration networkConfiguration,
                                          String availabilityZoneRebalancing, boolean forceNewDeployment,
                                          Map<String, Object> serviceConnectConfiguration,
                                          String region) {
        UpdateServiceRequest request = new UpdateServiceRequest();
        request.setCluster(clusterRef);
        request.setService(serviceName);
        request.setTaskDefinition(taskDefinition);
        request.setDesiredCount(desiredCount);
        request.setNetworkConfiguration(networkConfiguration);
        request.setAvailabilityZoneRebalancing(availabilityZoneRebalancing);
        request.setForceNewDeployment(forceNewDeployment);
        request.setServiceConnectConfiguration(serviceConnectConfiguration);
        return updateService(request, region);
    }

    public EcsServiceModel updateService(UpdateServiceRequest request, String region) {
        EcsCluster cluster = resolveClusterOrDefault(request.getCluster(), region);

        String serviceName = extractServiceName(request.getService());
        boolean forceNewDeployment = request.isForceNewDeployment();

        String key = serviceKey(region, cluster.getClusterName(), serviceName);
        EcsServiceModel svc = services.get(key);
        if (svc == null) {
            throw new AwsException("ServiceNotFoundException", "Service " + serviceName + " not found.", 404);
        }
        if ("INACTIVE".equals(svc.getStatus())) {
            throw new AwsException("ServiceNotActiveException",
                    "Service " + serviceName + " is not active.", 400);
        }
        if (request.getDesiredCount() != null) {
            if (request.getDesiredCount() < 0) {
                throw new AwsException("InvalidParameterException", "desiredCount cannot be a negative number.", 400);
            }
            svc.setDesiredCount(request.getDesiredCount());
        }
        // The members whose change starts new tasks, as UpdateService documents them: the network
        // configuration, the load balancers, the service registries, the Service Connect
        // configuration, the task definition and the platform version. The rest (desired count,
        // deployment configuration, the exec and managed-tag switches, placement, propagateTags,
        // the capacity provider strategy) are applied without rolling anything.
        boolean rollingChange = false;
        if (request.getNetworkConfiguration() != null) {
            rollingChange |= networkConfigurationChanged(svc.getNetworkConfiguration(),
                    request.getNetworkConfiguration());
            svc.setNetworkConfiguration(request.getNetworkConfiguration());
        }
        if (request.getAvailabilityZoneRebalancing() != null) {
            svc.setAvailabilityZoneRebalancing(request.getAvailabilityZoneRebalancing());
        }
        if (request.getCapacityProviderStrategy() != null) {
            svc.setCapacityProviderStrategy(request.getCapacityProviderStrategy());
            svc.setLaunchType(request.getCapacityProviderStrategy().isEmpty()
                    ? svc.getLaunchType()
                    : placementFor(request.getCapacityProviderStrategy().getFirst().capacityProvider()).launchType());
        }
        if (request.getEnableExecuteCommand() != null) {
            svc.setEnableExecuteCommand(request.getEnableExecuteCommand());
        }
        if (request.getEnableECSManagedTags() != null) {
            svc.setEnableECSManagedTags(request.getEnableECSManagedTags());
        }
        if (request.getPropagateTags() != null) {
            svc.setPropagateTags(request.getPropagateTags());
        }
        if (request.getHealthCheckGracePeriodSeconds() != null) {
            svc.setHealthCheckGracePeriodSeconds(request.getHealthCheckGracePeriodSeconds());
        }
        if (request.getDeploymentConfiguration() != null) {
            svc.setDeploymentConfiguration(request.getDeploymentConfiguration());
        }
        if (request.getLoadBalancers() != null) {
            rollingChange |= loadBalancersChanged(svc.getLoadBalancers(), request.getLoadBalancers());
            svc.setLoadBalancers(request.getLoadBalancers());
        }
        if (request.getServiceRegistries() != null) {
            boolean registriesChanged = !request.getServiceRegistries().equals(svc.getServiceRegistries());
            rollingChange |= registriesChanged;
            svc.setServiceRegistries(request.getServiceRegistries());
            if (registriesChanged) {
                reconcileServiceDiscoveryRegistries(svc, cluster, region);
            }
        }
        if (request.getUnparsed() != null) {
            svc.setUnparsed(mergedUnparsed(svc.getUnparsed(), request.getUnparsed()));
        }
        // UpdateServiceRequest.serviceConnectConfiguration is documented as "This parameter
        // triggers a new service deployment", so a real change rolls the deployment the way a
        // task-definition change does. An omitted parameter is not a change and rolls nothing.
        Map<String, Object> serviceConnectConfiguration = request.getServiceConnectConfiguration();
        boolean serviceConnectChanged = serviceConnectConfiguration != null
                && !serviceConnectConfiguration.equals(svc.getServiceConnectConfiguration());
        if (serviceConnectConfiguration != null) {
            svc.setServiceConnectConfiguration(serviceConnectConfiguration);
        }
        boolean taskDefChanged = false;
        String platformVersionBefore = svc.getPlatformVersion();
        if (request.getTaskDefinition() != null) {
            TaskDefinition resolved = resolveLaunchableTaskDefinition(request.getTaskDefinition(), region);
            taskDefChanged = !resolved.getTaskDefinitionArn().equals(svc.getTaskDefinition());
            svc.setTaskDefinition(resolved.getTaskDefinitionArn());
            applyServicePlatform(svc, resolved, request.getPlatformVersion() != null
                    ? request.getPlatformVersion() : svc.getPlatformVersion());
        } else if (request.getPlatformVersion() != null) {
            svc.setPlatformVersion(PLATFORM_VERSION_LATEST.equals(request.getPlatformVersion())
                    ? DEFAULT_PLATFORM_VERSION : request.getPlatformVersion());
        }
        // UpdateServiceRequest.platformVersion is documented as "This parameter triggers a new
        // service deployment". Compared after resolution, so a request that asks for LATEST on a
        // service already running the version LATEST resolves to is not a change.
        rollingChange |= !Objects.equals(platformVersionBefore, svc.getPlatformVersion());
        if (taskDefChanged || forceNewDeployment || serviceConnectChanged || rollingChange) {
            svc.setDeploymentId(newDeploymentId());
            svc.setLastDeploymentAt(Instant.now());
            recordServiceDeployment(svc, svc.getTaskDefinition(), region);
            if (eventPublisher != null) {
                eventPublisher.emitDeploymentStateChange(svc, "SERVICE_DEPLOYMENT_STARTED",
                        "ECS deployment " + svc.getDeploymentId() + " started.", region);
            }
        }
        services.put(key, svc);
        return svc;
    }

    private void reconcileServiceDiscoveryRegistries(EcsServiceModel svc, EcsCluster cluster, String region) {
        if (!dockerMode || discoveryRegistrar == null) {
            return;
        }
        for (EcsTask task : tasks.values()) {
            if (!ownedBy(task, svc, cluster)) {
                continue;
            }
            synchronized (task) {
                if (!TaskStatus.RUNNING.name().equals(task.getLastStatus())) {
                    continue;
                }
                discoveryRegistrar.deregisterTask(task, region);
                discoveryRegistrar.registerTask(task, svc, region);
            }
        }
    }

    /**
     * Folds an update's passthrough members into the ones the service already carries. UpdateService
     * replaces only the members a request names, so a service created with {@code
     * placementConstraints} and updated with only {@code placementStrategy} keeps its constraints.
     * A member the update does name is replaced outright, which is how an empty array clears one.
     */
    private static Map<String, Object> mergedUnparsed(Map<String, Object> current,
                                                       Map<String, Object> update) {
        if (current == null || current.isEmpty()) {
            return update;
        }
        Map<String, Object> merged = new LinkedHashMap<>(current);
        merged.putAll(update);
        return merged;
    }

    /** Whether an update's awsvpc configuration differs from the one the service already had. */
    private static boolean networkConfigurationChanged(NetworkConfiguration current,
                                                       NetworkConfiguration updated) {
        AwsVpcConfiguration before = current == null ? null : current.getAwsvpcConfiguration();
        AwsVpcConfiguration after = updated == null ? null : updated.getAwsvpcConfiguration();
        if (before == null || after == null) {
            return before != after;
        }
        return !Objects.equals(before.getSubnets(), after.getSubnets())
                || !Objects.equals(before.getSecurityGroups(), after.getSecurityGroups())
                || !Objects.equals(before.getAssignPublicIp(), after.getAssignPublicIp());
    }

    /** Whether an update's load balancers differ from the ones the service already had. */
    private static boolean loadBalancersChanged(List<EcsLoadBalancer> current,
                                                 List<EcsLoadBalancer> updated) {
        return !loadBalancerKeys(current).equals(loadBalancerKeys(updated));
    }

    private static List<String> loadBalancerKeys(List<EcsLoadBalancer> loadBalancers) {
        if (loadBalancers == null) {
            return List.of();
        }
        return loadBalancers.stream()
                .map(lb -> lb.getTargetGroupArn() + "|" + lb.getLoadBalancerName() + "|"
                        + lb.getContainerName() + "|" + lb.getContainerPort())
                .toList();
    }

    /**
     * A {@code role} is only permitted on a load-balanced service whose task definition does not
     * use {@code awsvpc}: with awsvpc, or without a load balancer, ECS uses the service-linked
     * role instead and rejects one given here.
     */
    private static void validateServiceRole(CreateServiceRequest request, TaskDefinition taskDef) {
        if (request.getRoleArn() == null || request.getRoleArn().isBlank()) {
            return;
        }
        if (request.getLoadBalancers() == null || request.getLoadBalancers().isEmpty()) {
            throw new AwsException("InvalidParameterException",
                    "The role parameter is only permitted when the service uses a load balancer.", 400);
        }
        if (taskDef.getNetworkMode() == NetworkMode.awsvpc) {
            throw new AwsException("InvalidParameterException",
                    "The role parameter is not permitted for a task definition that uses the "
                            + "awsvpc network mode.", 400);
        }
    }

    /**
     * The launch type a service's tasks get. An explicit one wins; otherwise the first entry of
     * the service's own capacity provider strategy, then the cluster's default strategy, decides
     * it, and with neither the service keeps Floci's FARGATE default.
     */
    private LaunchType serviceLaunchType(EcsCluster cluster, CreateServiceRequest request) {
        if (request.getLaunchType() != null) {
            return request.getLaunchType();
        }
        List<CapacityProviderStrategyItem> strategy = request.getCapacityProviderStrategy();
        if (strategy == null || strategy.isEmpty()) {
            strategy = clusterDefaultStrategy(cluster);
        }
        if (strategy == null || strategy.isEmpty()) {
            return LaunchType.FARGATE;
        }
        validateCapacityProviderStrategy(strategy);
        return placementFor(strategy.getFirst().capacityProvider()).launchType();
    }

    /** A Fargate service reports the platform its tasks run on; an EC2 service reports neither. */
    private static void applyServicePlatform(EcsServiceModel svc, TaskDefinition taskDef, String platformVersion) {
        if (svc.getLaunchType() != LaunchType.FARGATE) {
            svc.setPlatformVersion(null);
            svc.setPlatformFamily(null);
            return;
        }
        svc.setPlatformVersion(platformVersion == null || platformVersion.isBlank()
                || PLATFORM_VERSION_LATEST.equals(platformVersion)
                ? DEFAULT_PLATFORM_VERSION : platformVersion);
        svc.setPlatformFamily(platformFamilyOf(taskDef));
    }

    /**
     * The platform family a Fargate task or service reports.
     *
     * <p>A Linux task reports {@code Linux}, which is what DescribeTasks answers with. A Windows
     * task reports the Windows Server family from the task definition's
     * {@code runtimePlatform.operatingSystemFamily}, because that is the value a Windows task has
     * to match against its container image.
     */
    private static String platformFamilyOf(TaskDefinition taskDef) {
        String operatingSystemFamily = taskDef != null && taskDef.getRuntimePlatform() != null
                ? taskDef.getRuntimePlatform().operatingSystemFamily() : null;
        if (operatingSystemFamily == null || operatingSystemFamily.isBlank()
                || operatingSystemFamily.startsWith("LINUX")) {
            return DEFAULT_PLATFORM_FAMILY;
        }
        return operatingSystemFamily;
    }

    public EcsServiceModel deleteService(String clusterRef, String serviceName, boolean force, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);

        serviceName = extractServiceName(serviceName);

        String key = serviceKey(region, cluster.getClusterName(), serviceName);
        EcsServiceModel svc = services.get(key);
        if (svc == null) {
            throw new AwsException("ServiceNotFoundException", "Service " + serviceName + " not found.", 404);
        }
        if ("INACTIVE".equals(svc.getStatus())) {
            return svc;
        }
        if (!force && svc.getDesiredCount() > 0) {
            throw new AwsException("InvalidParameterException",
                    "The service cannot be stopped. Update the service to 0 tasks or use the force flag.", 400);
        }
        svc.setStatus("INACTIVE");
        svc.setDesiredCount(0);
        cluster.setActiveServicesCount(Math.max(0, cluster.getActiveServicesCount() - 1));
        services.put(key, svc);
        persistCluster(region, cluster);
        // Stop tasks before removing the service from the map, so the per-task
        // ELBv2 deregistration hook can still resolve the service's loadBalancers.
        tasks.values().stream()
                .filter(t -> ownedBy(t, svc, cluster))
                .filter(t -> !TaskStatus.STOPPED.name().equals(t.getLastStatus()))
                .forEach(t -> {
                    try {
                        stopTask(cluster.getClusterName(), t.getTaskArn(), "Service deleted",
                                STOP_CODE_SERVICE_SCHEDULER_INITIATED, region);
                    } catch (Exception e) {
                        LOG.warnv("Failed to stop task {0} on service delete: {1}",
                                t.getTaskArn(), e.getMessage());
                    }
                });
        return svc;
    }

    /** The services that resolved, plus a {@code MISSING} failure for each reference that did not. */
    public record DescribeServicesResult(List<EcsServiceModel> services, List<Failure> failures) {}

    public List<EcsServiceModel> describeServices(String clusterRef, List<String> serviceIds, String region) {
        return describeServicesDetailed(clusterRef, serviceIds, region).services();
    }

    /**
     * Resolves each reference, reporting the ones that do not exist as {@code MISSING} failures
     * rather than dropping them. ECS answers a describe for an unknown service with a failure
     * entry, not an error, and its {@code ServicesStable} waiter fails fast on
     * {@code failures[].reason == "MISSING"} — so silently returning an empty list leaves the
     * waiter polling for its full timeout instead of reporting the service does not exist.
     */
    public DescribeServicesResult describeServicesDetailed(String clusterRef, List<String> serviceIds,
                                                           String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        List<EcsServiceModel> result = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        for (String id : serviceIds) {
            EcsServiceModel svc = resolveService(cluster.getClusterName(), id, region);
            if (svc != null) {
                result.add(svc);
            } else {
                failures.add(Failure.missing(missingServiceArn(cluster, id, region)));
            }
        }
        return new DescribeServicesResult(result, failures);
    }

    /** Echoes an ARN the caller supplied, otherwise builds the ARN the service would have had. */
    private String missingServiceArn(EcsCluster cluster, String serviceRef, String region) {
        if (serviceRef != null && serviceRef.startsWith("arn:")) {
            return serviceRef;
        }
        return regionResolver.buildArn("ecs", region,
                "service/" + cluster.getClusterName() + "/" + serviceRef);
    }

    public List<String> listServices(String clusterRef, String region) {
        return listServices(clusterRef, null, null, null, null, region).arns();
    }

    /**
     * Lists service ARNs in a cluster, filtered by launch type and scheduling strategy.
     *
     * <p>ListServices pages ten at a time when the request names no {@code maxResults}, unlike the
     * other listings, which page a hundred at a time.
     */
    public ListPage listServices(String clusterRef, LaunchType launchType, String schedulingStrategy,
                                  Integer maxResults, String nextToken, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        String prefix = serviceKeyPrefix(region, cluster.getClusterName());
        List<String> arns = services.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .filter(svc -> !"INACTIVE".equals(svc.getStatus()))
                .filter(svc -> launchType == null || launchType == svc.getLaunchType())
                .filter(svc -> schedulingStrategy == null
                        || schedulingStrategy.equals(svc.getSchedulingStrategy() != null
                                ? svc.getSchedulingStrategy() : DEFAULT_SCHEDULING_STRATEGY))
                .map(EcsServiceModel::getServiceArn)
                .toList();
        return paginate(arns, maxResults, nextToken, DEFAULT_SERVICE_PAGE_SIZE);
    }

    public List<String> listServicesByNamespace(String namespace, String region) {
        return services.values().stream()
                .filter(s -> namespace.equals(s.getNamespace()))
                .filter(s -> !"INACTIVE".equals(s.getStatus()))
                .map(EcsServiceModel::getServiceArn)
                .toList();
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    public void tagResource(String resourceArn, Map<String, String> tags) {
        Object resource = findByArn(resourceArn);
        if (resource == null) {
            throw new AwsException("InvalidParameterException", "Resource not found: " + resourceArn, 400);
        }
        mergeTagsOnResource(resource, tags);
    }

    public void untagResource(String resourceArn, List<String> tagKeys) {
        Object resource = findByArn(resourceArn);
        if (resource == null) {
            throw new AwsException("InvalidParameterException", "Resource not found: " + resourceArn, 400);
        }
        removeTagsFromResource(resource, tagKeys);
    }

    public Map<String, String> listTagsForResource(String resourceArn) {
        Object resource = findByArn(resourceArn);
        if (resource == null) {
            throw new AwsException("InvalidParameterException", "Resource not found: " + resourceArn, 400);
        }
        return getTagsFromResource(resource);
    }

    private Object findByArn(String arn) {
        for (EcsCluster c : clusters.values()) {
            if (arn.equals(c.getClusterArn())) { return c; }
        }
        for (TaskDefinition td : taskDefinitions.values()) {
            if (arn.equals(td.getTaskDefinitionArn())) { return td; }
        }
        for (EcsTask t : tasks.values()) {
            if (arn.equals(t.getTaskArn())) { return t; }
        }
        for (EcsServiceModel s : services.values()) {
            if (arn.equals(s.getServiceArn())) { return s; }
        }
        for (ContainerInstance ci : containerInstances.values()) {
            if (arn.equals(ci.getContainerInstanceArn())) { return ci; }
        }
        for (CapacityProvider cp : capacityProviders.values()) {
            if (arn.equals(cp.getCapacityProviderArn())) { return cp; }
        }
        return null;
    }

    private void mergeTagsOnResource(Object resource, Map<String, String> tags) {
        if (resource instanceof EcsCluster c) { c.getTags().putAll(tags); }
        else if (resource instanceof TaskDefinition td) { td.getTags().putAll(tags); }
        else if (resource instanceof EcsTask t) { t.getTags().putAll(tags); }
        else if (resource instanceof EcsServiceModel s) { s.getTags().putAll(tags); }
        else if (resource instanceof ContainerInstance ci) { ci.getTags().putAll(tags); }
        else if (resource instanceof CapacityProvider cp) { cp.getTags().putAll(tags); }
        persistTaggedResource(resource);
    }

    private void removeTagsFromResource(Object resource, List<String> tagKeys) {
        if (resource instanceof EcsCluster c) { tagKeys.forEach(c.getTags()::remove); }
        else if (resource instanceof TaskDefinition td) { tagKeys.forEach(td.getTags()::remove); }
        else if (resource instanceof EcsTask t) { tagKeys.forEach(t.getTags()::remove); }
        else if (resource instanceof EcsServiceModel s) { tagKeys.forEach(s.getTags()::remove); }
        else if (resource instanceof ContainerInstance ci) { tagKeys.forEach(ci.getTags()::remove); }
        else if (resource instanceof CapacityProvider cp) { tagKeys.forEach(cp.getTags()::remove); }
        persistTaggedResource(resource);
    }

    /** Writes back a tag mutation to the owning durable map. Tasks and container instances are
     *  transient (not persisted), so they need no write-back. */
    private void persistTaggedResource(Object resource) {
        switch (resource) {
            case EcsCluster c -> persistByArn(clusters, c, EcsCluster::getClusterArn);
            case TaskDefinition td -> persistByArn(taskDefinitions, td, TaskDefinition::getTaskDefinitionArn);
            case EcsServiceModel s -> persistByArn(services, s, EcsServiceModel::getServiceArn);
            case CapacityProvider cp -> persistByArn(capacityProviders, cp, CapacityProvider::getCapacityProviderArn);
            default -> { /* EcsTask / ContainerInstance are transient */ }
        }
    }

    private Map<String, String> getTagsFromResource(Object resource) {
        return switch (resource) {
            case EcsCluster c -> c.getTags();
            case TaskDefinition td -> td.getTags();
            case EcsTask t -> t.getTags();
            case EcsServiceModel s -> s.getTags();
            case ContainerInstance ci -> ci.getTags();
            case CapacityProvider cp -> cp.getTags();
            default -> Map.of();
        };
    }

    // ── Account Settings ──────────────────────────────────────────────────────

    /** The account setting names ECS accepts, in the order ListAccountSettings reports them. */
    private static final List<String> ACCOUNT_SETTING_NAMES = List.of(
            "serviceLongArnFormat", "taskLongArnFormat", "containerInstanceLongArnFormat",
            "awsvpcTrunking", "containerInsights", "fargateFIPSMode", "tagResourceAuthorization",
            "fargateTaskRetirementWaitPeriod", "guardDutyActivate", "defaultLogDriverMode",
            "fargateEventWindows");

    /**
     * What each setting reads as when neither the principal nor the account default set it.
     * {@code defaultLogDriverMode} is {@code non-blocking} because ECS changed that default on
     * June 25 2025, which the API reference records in its own note.
     */
    private static final Map<String, String> ACCOUNT_SETTING_DEFAULTS = Map.ofEntries(
            Map.entry("serviceLongArnFormat", "enabled"),
            Map.entry("taskLongArnFormat", "enabled"),
            Map.entry("containerInstanceLongArnFormat", "enabled"),
            Map.entry("awsvpcTrunking", "disabled"),
            Map.entry("containerInsights", "disabled"),
            Map.entry("fargateFIPSMode", "disabled"),
            Map.entry("tagResourceAuthorization", "disabled"),
            Map.entry("fargateTaskRetirementWaitPeriod", "7"),
            Map.entry("guardDutyActivate", "disabled"),
            Map.entry("defaultLogDriverMode", "non-blocking"),
            Map.entry("fargateEventWindows", "disabled"));

    /** GuardDuty owns this one on the account's behalf, so its setting reports as aws_managed. */
    private static final String AWS_MANAGED_SETTING = "guardDutyActivate";
    /** ListAccountSettings pages ten at a time, and never accepts more than ten. */
    private static final int MAX_ACCOUNT_SETTING_RESULTS = 10;

    /** One row of the account-setting table, which is what the {@code Setting} shape carries. */
    public record AccountSetting(String name, String value, String principalArn, String type) {}

    /**
     * Sets one principal's account setting. A request that names no principal sets it for the
     * caller, which here is the account root, because Floci does not authenticate a separate user.
     */
    public AccountSetting putAccountSetting(String name, String value, String principalArn) {
        validateAccountSetting(name, value);
        String principal = principalArn != null ? principalArn : rootPrincipalArn();
        accountSettings.put(accountSettingKey(principal, name), value);
        return new AccountSetting(name, value, principal, settingType(name));
    }

    /**
     * Sets the account-wide default, which every principal without an explicit setting reads. AWS
     * stores that on the root user, so setting the default and setting the root user's own value
     * are the same write.
     */
    public AccountSetting putAccountSettingDefault(String name, String value) {
        return putAccountSetting(name, value, rootPrincipalArn());
    }

    public AccountSetting deleteAccountSetting(String name, String principalArn) {
        requireAccountSettingName(name);
        String principal = principalArn != null ? principalArn : rootPrincipalArn();
        String removed = accountSettings.remove(accountSettingKey(principal, name));
        return new AccountSetting(name,
                removed != null ? removed : effectiveAccountSetting(principal, name),
                principal, settingType(name));
    }

    /** A page of account settings, with the token that continues the listing. */
    public record AccountSettingPage(List<AccountSetting> settings, String nextToken) {}

    /**
     * Lists a principal's account settings. With {@code effectiveSettings}, every name is reported
     * at the value that principal actually reads: its own setting, else the account default, else
     * the setting's built-in default. Without it, only the settings that principal explicitly set
     * are returned, which is why an untouched account lists nothing.
     */
    public AccountSettingPage listAccountSettings(String filterName, String filterValue,
                                                   String principalArn, boolean effectiveSettings,
                                                   Integer maxResults, String nextToken) {
        if (filterName != null) {
            requireAccountSettingName(filterName);
        }
        if (filterValue != null && filterName == null) {
            throw new AwsException("InvalidParameterException",
                    "You must also specify an account setting name to filter by value.", 400);
        }
        String principal = principalArn != null ? principalArn : rootPrincipalArn();
        List<AccountSetting> settings = new ArrayList<>();
        for (String name : ACCOUNT_SETTING_NAMES) {
            String explicit = accountSettings.get(accountSettingKey(principal, name));
            if (explicit != null) {
                settings.add(new AccountSetting(name, explicit, principal, settingType(name)));
            } else if (effectiveSettings) {
                settings.add(new AccountSetting(name, effectiveAccountSetting(principal, name),
                        principal, settingType(name)));
            }
        }
        List<AccountSetting> filtered = settings.stream()
                .filter(s -> filterName == null || filterName.equals(s.name()))
                .filter(s -> filterValue == null || filterValue.equals(s.value()))
                .toList();

        int offset = decodeListToken(nextToken);
        int pageSize = maxResults != null && maxResults > 0
                ? Math.min(maxResults, MAX_ACCOUNT_SETTING_RESULTS) : MAX_ACCOUNT_SETTING_RESULTS;
        if (offset >= filtered.size()) {
            return new AccountSettingPage(List.of(), null);
        }
        int end = Math.min(offset + pageSize, filtered.size());
        String token = end < filtered.size()
                ? Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8))
                : null;
        return new AccountSettingPage(List.copyOf(filtered.subList(offset, end)), token);
    }

    private String effectiveAccountSetting(String principal, String name) {
        String explicit = accountSettings.get(accountSettingKey(principal, name));
        if (explicit != null) {
            return explicit;
        }
        String accountDefault = accountSettings.get(accountSettingKey(rootPrincipalArn(), name));
        return accountDefault != null ? accountDefault : ACCOUNT_SETTING_DEFAULTS.get(name);
    }

    private static String settingType(String name) {
        return AWS_MANAGED_SETTING.equals(name) ? "aws_managed" : "user";
    }

    private static String accountSettingKey(String principalArn, String name) {
        return principalArn + "::" + name;
    }

    private String rootPrincipalArn() {
        return regionResolver.buildGlobalArn("iam", "root");
    }

    private static void requireAccountSettingName(String name) {
        if (!ACCOUNT_SETTING_NAMES.contains(name)) {
            throw new AwsException("InvalidParameterException",
                    "Invalid account setting name: " + name, 400);
        }
    }

    /**
     * Most settings are an opt-in flag, but two are not: the Fargate retirement wait is a number of
     * calendar days, and the default log driver mode names a delivery mode.
     */
    private static void validateAccountSetting(String name, String value) {
        requireAccountSettingName(name);
        Set<String> allowed = switch (name) {
            case "fargateTaskRetirementWaitPeriod" -> Set.of("0", "7", "14");
            case "defaultLogDriverMode" -> Set.of("blocking", "non-blocking");
            case "containerInsights" -> Set.of("enabled", "disabled", "enhanced", "on", "off");
            default -> Set.of("enabled", "disabled", "on", "off");
        };
        if (!allowed.contains(value)) {
            throw new AwsException("InvalidParameterException",
                    "Invalid value for account setting " + name + ": " + value, 400);
        }
    }

    // ── Attributes ────────────────────────────────────────────────────────────

    /** The only target an attribute can be applied to. */
    private static final String ATTRIBUTE_TARGET_TYPE = "container-instance";
    /** "You can specify up to 10 custom attributes for each resource." */
    private static final int MAX_ATTRIBUTES_PER_TARGET = 10;

    /**
     * Applies attributes to container instances of one cluster. Attributes are stored per cluster,
     * not per target id alone: the same instance id in two clusters is two different targets, and
     * ListAttributes is documented as listing "the attributes for Amazon ECS resources within a
     * specified target type and cluster".
     */
    public List<Attribute> putAttributes(String clusterRef, List<Attribute> attrs, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        if (attrs.size() > MAX_ATTRIBUTES_PER_TARGET) {
            throw new AwsException("AttributeLimitExceededException",
                    "You can specify up to " + MAX_ATTRIBUTES_PER_TARGET
                            + " attributes in a single call.", 400);
        }
        List<Attribute> stored = new ArrayList<>();
        for (Attribute attr : attrs) {
            requireAttributeTarget(cluster, attr);
            String key = attributeKey(cluster, attr.targetId());
            List<Attribute> existing = attributes.computeIfAbsent(key, k -> new ArrayList<>());
            existing.removeIf(a -> a.name().equals(attr.name()));
            if (existing.size() >= MAX_ATTRIBUTES_PER_TARGET) {
                throw new AwsException("AttributeLimitExceededException",
                        "You can apply up to " + MAX_ATTRIBUTES_PER_TARGET
                                + " custom attributes for each resource.", 400);
            }
            existing.add(attr);
            attributes.put(key, existing);
            stored.add(attr);
        }
        return stored;
    }

    public List<Attribute> deleteAttributes(String clusterRef, List<Attribute> attrs, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        List<Attribute> deleted = new ArrayList<>();
        for (Attribute attr : attrs) {
            requireAttributeTarget(cluster, attr);
            String key = attributeKey(cluster, attr.targetId());
            List<Attribute> existing = attributes.get(key);
            if (existing != null) {
                existing.removeIf(a -> {
                    if (a.name().equals(attr.name())) {
                        deleted.add(a);
                        return true;
                    }
                    return false;
                });
                if (existing.isEmpty()) {
                    attributes.remove(key);
                } else {
                    attributes.put(key, existing);
                }
            }
        }
        return deleted;
    }

    /**
     * An attribute names a container instance of this cluster, and ECS answers one that does not
     * exist with a {@code TargetNotFoundException} rather than storing an orphan.
     */
    private void requireAttributeTarget(EcsCluster cluster, Attribute attr) {
        if (attr.targetType() != null && !ATTRIBUTE_TARGET_TYPE.equals(attr.targetType())) {
            throw new AwsException("InvalidParameterException",
                    "Invalid target type: " + attr.targetType(), 400);
        }
        if (resolveContainerInstance(cluster.getClusterArn(), attr.targetId()) == null) {
            throw new AwsException("TargetNotFoundException",
                    "The specified target was not found: " + attr.targetId(), 400);
        }
    }

    private static String attributeKey(EcsCluster cluster, String targetId) {
        return cluster.getClusterArn() + "/" + targetId;
    }

    /** {@code targetType} is required, and {@code container-instance} is its only valid value. */
    public ListAttributesPage listAttributes(String clusterRef, String targetType,
                                              String attributeName, String attributeValue,
                                              Integer maxResults, String nextToken, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        if (!ATTRIBUTE_TARGET_TYPE.equals(targetType)) {
            throw new AwsException("InvalidParameterException",
                    "targetType is required and must be " + ATTRIBUTE_TARGET_TYPE + ".", 400);
        }
        if (attributeValue != null && attributeName == null) {
            throw new AwsException("InvalidParameterException",
                    "You must also specify an attribute name to filter by value.", 400);
        }
        String prefix = cluster.getClusterArn() + "/";
        List<Attribute> matches = attributes.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .flatMap(e -> e.getValue().stream())
                .filter(a -> attributeName == null || attributeName.equals(a.name()))
                .filter(a -> attributeValue == null || attributeValue.equals(a.value()))
                .toList();

        int offset = decodeListToken(nextToken);
        int pageSize = maxResults != null && maxResults > 0
                ? Math.min(maxResults, MAX_LIST_RESULTS) : MAX_LIST_RESULTS;
        if (offset >= matches.size()) {
            return new ListAttributesPage(List.of(), null);
        }
        int end = Math.min(offset + pageSize, matches.size());
        String token = end < matches.size()
                ? Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(String.valueOf(end).getBytes(StandardCharsets.UTF_8))
                : null;
        return new ListAttributesPage(List.copyOf(matches.subList(offset, end)), token);
    }

    /** A page of attributes, with the token that continues the listing. */
    public record ListAttributesPage(List<Attribute> attributes, String nextToken) {}

    // ── Container Instances ───────────────────────────────────────────────────

    /** The statuses a container instance can report. */
    private static final Set<String> CONTAINER_INSTANCE_STATUSES = Set.of(
            "ACTIVE", "DRAINING", "REGISTERING", "DEREGISTERING", "REGISTRATION_FAILED", "INACTIVE");
    /** "The only valid values for this action are ACTIVE and DRAINING." */
    private static final Set<String> UPDATABLE_CONTAINER_INSTANCE_STATUSES = Set.of("ACTIVE", "DRAINING");
    /** "A list of up to 10 container instance IDs or full ARN entries." */
    private static final int MAX_CONTAINER_INSTANCES_PER_UPDATE = 10;
    /** What the agent reports when a registration carried no versionInfo of its own. */
    private static final Map<String, Object> DEFAULT_VERSION_INFO =
            Map.of("agentVersion", "1.0.0", "agentHash", "floci", "dockerVersion", "DockerVersion: 24.0.0");

    public ContainerInstance registerContainerInstance(String clusterRef, String instanceIdentityDocument,
                                                        List<Attribute> instanceAttributes, String region) {
        RegisterContainerInstanceRequest request = new RegisterContainerInstanceRequest();
        request.setCluster(clusterRef);
        request.setInstanceIdentityDocument(instanceIdentityDocument);
        request.setAttributes(instanceAttributes);
        return registerContainerInstance(request, region);
    }

    /**
     * Registers a container instance, keeping the resources, version info and tags the agent sent:
     * {@code totalResources} is what the instance reports it has, and ECS echoes it back as both
     * {@code registeredResources} and, until tasks are placed, {@code remainingResources}.
     */
    public ContainerInstance registerContainerInstance(RegisterContainerInstanceRequest request,
                                                        String region) {
        EcsCluster cluster = resolveClusterOrDefault(request.getCluster(), region);
        String instanceId = "i-floci-" + UUID.randomUUID().toString().substring(0, 8);
        String instanceArn = request.getContainerInstanceArn() != null
                ? request.getContainerInstanceArn()
                : regionResolver.buildArn("ecs", region,
                        "container-instance/" + cluster.getClusterName() + "/" + UUID.randomUUID());

        ContainerInstance instance = new ContainerInstance();
        instance.setContainerInstanceArn(instanceArn);
        instance.setEc2InstanceId(instanceId);
        instance.setStatus(STATUS_ACTIVE);
        instance.setAgentConnected(true);
        instance.setVersion(1);
        instance.setRegisteredAt(Instant.now());
        instance.setVersionInfo(request.getVersionInfo() != null
                ? request.getVersionInfo() : DEFAULT_VERSION_INFO);
        instance.setRegisteredResources(request.getTotalResources());
        instance.setRemainingResources(request.getTotalResources());
        if (request.getAttributes() != null) {
            instance.setAttributes(new ArrayList<>(request.getAttributes()));
        }
        if (request.getTags() != null && !request.getTags().isEmpty()) {
            instance.setTags(new LinkedHashMap<>(request.getTags()));
        }

        String key = containerInstanceKey(cluster.getClusterArn(), instanceArn);
        containerInstances.put(key, instance);
        refreshRegisteredInstanceCount(cluster, region);
        LOG.infov("Registered container instance: {0} in cluster {1}", instanceArn, cluster.getClusterName());
        return instance;
    }

    /**
     * Deregisters an instance, leaving it in the cluster as {@code INACTIVE} rather than dropping
     * it: AWS keeps answering DescribeContainerInstances for a deregistered instance, and only
     * ACTIVE and DRAINING instances count towards the cluster's registered count.
     */
    public ContainerInstance deregisterContainerInstance(String clusterRef, String instanceRef,
                                                          boolean force, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        ContainerInstance instance = resolveContainerInstanceOrThrow(cluster.getClusterArn(), instanceRef);

        if (!force) {
            long running = tasks.values().stream()
                    .filter(t -> instance.getContainerInstanceArn().equals(t.getContainerInstanceArn()))
                    .filter(t -> TaskStatus.RUNNING.name().equals(t.getLastStatus()))
                    .count();
            if (running > 0) {
                throw new AwsException("InvalidParameterException",
                        "Container instance has running tasks. Use force=true to deregister.", 400);
            }
        }

        instance.setStatus(STATUS_INACTIVE);
        instance.setAgentConnected(false);
        instance.setVersion(instance.getVersion() + 1);
        refreshRegisteredInstanceCount(cluster, region);
        return instance;
    }

    /** "This includes container instances in both ACTIVE and DRAINING status." */
    private void refreshRegisteredInstanceCount(EcsCluster cluster, String region) {
        String prefix = containerInstanceKey(cluster.getClusterArn(), "");
        long registered = containerInstances.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .filter(ci -> STATUS_ACTIVE.equals(ci.getStatus()) || "DRAINING".equals(ci.getStatus()))
                .count();
        cluster.setRegisteredContainerInstancesCount((int) registered);
        persistCluster(region, cluster);
    }

    public List<ContainerInstance> describeContainerInstances(String clusterRef,
                                                               List<String> instanceRefs, String region) {
        return describeContainerInstancesDetailed(clusterRef, instanceRefs, region).instances();
    }

    public record DescribeContainerInstancesResult(List<ContainerInstance> instances,
                                                    List<Failure> failures) {}

    public DescribeContainerInstancesResult describeContainerInstancesDetailed(
            String clusterRef, List<String> instanceRefs, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        List<ContainerInstance> result = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        for (String ref : instanceRefs) {
            ContainerInstance instance = resolveContainerInstance(cluster.getClusterArn(), ref);
            if (instance != null) {
                result.add(instance);
            } else {
                failures.add(Failure.missing(ref.startsWith("arn:") ? ref
                        : containerInstanceArnFor(cluster, ref, region)));
            }
        }
        return new DescribeContainerInstancesResult(result, failures);
    }

    private String containerInstanceArnFor(EcsCluster cluster, String ref, String region) {
        return regionResolver.buildArn("ecs", region,
                "container-instance/" + cluster.getClusterName() + "/" + ref);
    }

    public List<String> listContainerInstances(String clusterRef, String status, String region) {
        return listContainerInstances(clusterRef, status, null, null, region).arns();
    }

    /**
     * Lists container instance ARNs. A request that names no status gets every instance other than
     * the {@code INACTIVE} ones, which is AWS's documented default rather than everything.
     */
    public ListPage listContainerInstances(String clusterRef, String status, Integer maxResults,
                                            String nextToken, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        if (status != null && !CONTAINER_INSTANCE_STATUSES.contains(status)) {
            throw new AwsException("InvalidParameterException",
                    "Invalid container instance status: " + status, 400);
        }
        String prefix = containerInstanceKey(cluster.getClusterArn(), "");
        List<String> arns = containerInstances.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .filter(ci -> status != null
                        ? status.equals(ci.getStatus())
                        : !STATUS_INACTIVE.equals(ci.getStatus()))
                .map(ContainerInstance::getContainerInstanceArn)
                .toList();
        return paginate(arns, maxResults, nextToken);
    }

    public ContainerInstance updateContainerAgent(String clusterRef, String instanceRef, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        ContainerInstance instance = resolveContainerInstanceOrThrow(cluster.getClusterArn(), instanceRef);
        instance.setAgentUpdateStatus("UPDATED");
        instance.setVersion(instance.getVersion() + 1);
        return instance;
    }

    public record UpdateContainerInstancesStateResult(List<ContainerInstance> instances,
                                                       List<Failure> failures) {}

    /**
     * Moves instances between {@code ACTIVE} and {@code DRAINING}, the only two states this action
     * sets. "A container instance can't be changed to DRAINING until it has reached an ACTIVE
     * status", and an instance the cluster does not have comes back as a failure, not an error.
     */
    public UpdateContainerInstancesStateResult updateContainerInstancesState(
            String clusterRef, List<String> instanceRefs, String status, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        if (!UPDATABLE_CONTAINER_INSTANCE_STATUSES.contains(status)) {
            throw new AwsException("InvalidParameterException",
                    "The only valid container instance states for this action are "
                            + "ACTIVE and DRAINING.", 400);
        }
        if (instanceRefs.size() > MAX_CONTAINER_INSTANCES_PER_UPDATE) {
            throw new AwsException("InvalidParameterException",
                    "You can update at most " + MAX_CONTAINER_INSTANCES_PER_UPDATE
                            + " container instances in a single call.", 400);
        }
        List<ContainerInstance> updated = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        for (String ref : instanceRefs) {
            ContainerInstance instance = resolveContainerInstance(cluster.getClusterArn(), ref);
            if (instance == null) {
                failures.add(Failure.missing(ref.startsWith("arn:") ? ref
                        : containerInstanceArnFor(cluster, ref, region)));
                continue;
            }
            if ("DRAINING".equals(status) && !STATUS_ACTIVE.equals(instance.getStatus())) {
                throw new AwsException("InvalidParameterException",
                        "Container instance " + instance.getContainerInstanceArn()
                                + " must be ACTIVE before it can be set to DRAINING.", 400);
            }
            instance.setStatus(status);
            instance.setVersion(instance.getVersion() + 1);
            updated.add(instance);
        }
        refreshRegisteredInstanceCount(cluster, region);
        return new UpdateContainerInstancesStateResult(updated, failures);
    }

    // ── Capacity Providers ────────────────────────────────────────────────────

    /** The two providers Fargate predefines, which exist in every account and cannot be deleted. */
    private static final Set<String> RESERVED_CAPACITY_PROVIDERS = Set.of("FARGATE", "FARGATE_SPOT");
    private static final List<String> RESERVED_CAPACITY_PROVIDER_PREFIXES =
            List.of("aws", "ecs", "fargate");
    private static final int MAX_CAPACITY_PROVIDER_NAME_LENGTH = 255;
    private static final Pattern CAPACITY_PROVIDER_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");
    /** The only provider type Floci mints: a created provider always wraps an Auto Scaling group. */
    private static final String CAPACITY_PROVIDER_TYPE_EC2 = "EC2_AUTOSCALING";

    public CapacityProvider createCapacityProvider(String name, Map<String, Object> asgProvider,
                                                    Map<String, String> tags, String region) {
        validateCapacityProviderName(name);
        if (capacityProviders.containsKey(name)) {
            throw new AwsException("InvalidParameterException",
                    "A capacity provider with name " + name + " already exists.", 400);
        }
        CapacityProvider cp = new CapacityProvider();
        cp.setName(name);
        cp.setCapacityProviderArn(regionResolver.buildArn("ecs", region, "capacity-provider/" + name));
        cp.setStatus("ACTIVE");
        cp.setUpdateStatus("CREATE_COMPLETE");
        cp.setType(CAPACITY_PROVIDER_TYPE_EC2);
        cp.setAutoScalingGroupProvider(asgProvider);
        if (tags != null) {
            cp.setTags(tags);
        }
        capacityProviders.put(name, cp);
        return cp;
    }

    public CapacityProvider updateCapacityProvider(String name, Map<String, Object> asgProvider) {
        CapacityProvider cp = resolveCapacityProviderOrThrow(name);
        cp.setAutoScalingGroupProvider(asgProvider);
        cp.setUpdateStatus("UPDATE_COMPLETE");
        capacityProviders.put(cp.getName(), cp);
        return cp;
    }

    /**
     * Deletes a capacity provider. "The FARGATE and FARGATE_SPOT capacity providers are reserved
     * and can't be deleted", and "only capacity providers that aren't associated with a cluster
     * can be deleted": both come back as InvalidParameterException rather than removing anything.
     *
     * <p>The deleted provider is reported with {@code updateStatus: DELETE_IN_PROGRESS} and an
     * unchanged {@code status}: DELETE_IN_PROGRESS is not one of the four values
     * {@code CapacityProviderStatus} takes, so reporting it there hands the SDK an enum it
     * cannot map.
     */
    public CapacityProvider deleteCapacityProvider(String nameOrArn) {
        if (RESERVED_CAPACITY_PROVIDERS.contains(nameOrArn)) {
            throw new AwsException("InvalidParameterException",
                    "The " + nameOrArn + " capacity provider is reserved and can't be deleted.", 400);
        }
        CapacityProvider cp = resolveCapacityProviderOrThrow(nameOrArn);
        String attachedTo = clusterUsingCapacityProvider(cp.getName());
        if (attachedTo != null) {
            throw new AwsException("InvalidParameterException",
                    "The capacity provider " + cp.getName() + " is associated with cluster "
                            + attachedTo + " and can't be deleted. Remove it with "
                            + "PutClusterCapacityProviders or delete the cluster first.", 400);
        }
        String usedBy = serviceUsingCapacityProvider(cp);
        if (usedBy != null) {
            throw new AwsException("InvalidParameterException",
                    "The capacity provider " + cp.getName() + " is in the capacity provider "
                            + "strategy of service " + usedBy + " and can't be deleted. Remove it "
                            + "from the service's capacity provider strategy with UpdateService "
                            + "first.", 400);
        }
        cp.setUpdateStatus("DELETE_IN_PROGRESS");
        capacityProviders.remove(cp.getName());
        return cp;
    }

    public record DescribeCapacityProvidersResult(List<CapacityProvider> capacityProviders,
                                                   List<Failure> failures) {}

    public List<CapacityProvider> describeCapacityProviders(List<String> providers) {
        return describeCapacityProvidersDetailed(providers, regionResolver.getDefaultRegion())
                .capacityProviders();
    }

    /**
     * Describes capacity providers, reporting a name that matches none as a {@code MISSING}
     * failure rather than dropping it from the answer.
     */
    public DescribeCapacityProvidersResult describeCapacityProvidersDetailed(List<String> providers,
                                                                              String region) {
        if (providers == null || providers.isEmpty()) {
            List<CapacityProvider> result =
                    new ArrayList<>(List.of(builtInFargate(region), builtInFargateSpot(region)));
            result.addAll(capacityProviders.values());
            return new DescribeCapacityProvidersResult(result, List.of());
        }
        List<CapacityProvider> found = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        for (String ref : providers) {
            CapacityProvider cp = switch (ref) {
                case "FARGATE" -> builtInFargate(region);
                case "FARGATE_SPOT" -> builtInFargateSpot(region);
                default -> capacityProviders.getOrDefault(ref,
                        capacityProviders.values().stream()
                                .filter(p -> ref.equals(p.getCapacityProviderArn()))
                                .findFirst().orElse(null));
            };
            if (cp == null) {
                failures.add(Failure.missing(ref.startsWith("arn:") ? ref
                        : regionResolver.buildArn("ecs", region, "capacity-provider/" + ref)));
                continue;
            }
            found.add(cp);
        }
        return new DescribeCapacityProvidersResult(found, failures);
    }

    /** The cluster a capacity provider is attached to, or {@code null} when none is. */
    private String clusterUsingCapacityProvider(String name) {
        return clusters.values().stream()
                .filter(c -> c.getCapacityProviders() != null && c.getCapacityProviders().contains(name))
                .map(EcsCluster::getClusterName)
                .findFirst().orElse(null);
    }

    /**
     * The first active service whose capacity provider strategy still names {@code cp}, by name or
     * by ARN, or {@code null}. DeleteCapacityProvider requires the provider to be out of every
     * service's strategy, not only out of every cluster. A deleted service is INACTIVE and holds
     * nothing back.
     */
    private String serviceUsingCapacityProvider(CapacityProvider cp) {
        return services.values().stream()
                .filter(svc -> !"INACTIVE".equals(svc.getStatus()))
                .filter(svc -> svc.getCapacityProviderStrategy() != null
                        && svc.getCapacityProviderStrategy().stream()
                                .anyMatch(item -> cp.getName().equals(item.capacityProvider())
                                        || cp.getCapacityProviderArn().equals(item.capacityProvider())))
                .map(EcsServiceModel::getServiceName)
                .findFirst().orElse(null);
    }

    /**
     * "Up to 255 characters are allowed. They include letters (both upper and lowercase letters),
     * numbers, underscores (_), and hyphens (-). The name can't be prefixed with aws, ecs, or
     * fargate."
     */
    private static void validateCapacityProviderName(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterException",
                    "Capacity provider name is required.", 400);
        }
        if (name.length() > MAX_CAPACITY_PROVIDER_NAME_LENGTH
                || !CAPACITY_PROVIDER_NAME_PATTERN.matcher(name).matches()) {
            throw new AwsException("InvalidParameterException",
                    "Capacity provider names can be up to " + MAX_CAPACITY_PROVIDER_NAME_LENGTH
                            + " characters long and contain letters, numbers, underscores and "
                            + "hyphens only.", 400);
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String reserved : RESERVED_CAPACITY_PROVIDER_PREFIXES) {
            if (lower.startsWith(reserved)) {
                throw new AwsException("InvalidParameterException",
                        "Capacity provider names can't be prefixed with \"aws\", \"ecs\" or "
                                + "\"fargate\".", 400);
            }
        }
    }

    private CapacityProvider builtInFargate(String region) {
        return builtInCapacityProvider("FARGATE", region);
    }

    private CapacityProvider builtInFargateSpot(String region) {
        return builtInCapacityProvider("FARGATE_SPOT", region);
    }

    private CapacityProvider builtInCapacityProvider(String name, String region) {
        CapacityProvider cp = new CapacityProvider();
        cp.setName(name);
        cp.setStatus("ACTIVE");
        cp.setType(name);
        cp.setCapacityProviderArn(regionResolver.buildArn("ecs", region, "capacity-provider/" + name));
        return cp;
    }

    // ── Task Sets ─────────────────────────────────────────────────────────────

    /** The deployment controllers a task set can live under; a ROLLING service manages its own. */
    private static final Set<String> TASK_SET_CONTROLLERS = Set.of("EXTERNAL", "CODE_DEPLOY");
    /** The only unit {@code Scale} accepts, so the computed count is always a percentage. */
    private static final String SCALE_UNIT_PERCENT = "PERCENT";
    /** The stability a task set reports while Floci places no tasks of its own for one. */
    private static final String STABILITY_STATUS_STEADY_STATE = "STEADY_STATE";

    public TaskSet createTaskSet(String clusterRef, String serviceRef, String taskDefinitionRef,
                                  LaunchType launchType, double scaleValue, String scaleUnit,
                                  String externalId, String region) {
        CreateTaskSetRequest request = new CreateTaskSetRequest();
        request.setCluster(clusterRef);
        request.setService(serviceRef);
        request.setTaskDefinition(taskDefinitionRef);
        request.setLaunchType(launchType);
        request.setScaleValue(scaleValue);
        request.setScaleUnit(scaleUnit);
        request.setExternalId(externalId);
        return createTaskSet(request, region);
    }

    /**
     * Creates a task set, which only a service using the {@code EXTERNAL} or {@code CODE_DEPLOY}
     * deployment controller can have: a ROLLING service runs its own deployments, and AWS refuses
     * the call rather than creating a task set nothing will ever place.
     */
    public TaskSet createTaskSet(CreateTaskSetRequest request, String region) {
        EcsCluster cluster = resolveClusterOrDefault(request.getCluster(), region);
        EcsServiceModel svc = resolveServiceOrThrow(cluster.getClusterName(), request.getService(), region);
        TaskDefinition taskDef = resolveTaskDefinitionOrThrow(request.getTaskDefinition(), region);

        String controller = svc.getDeploymentController() != null
                ? svc.getDeploymentController() : "ECS";
        if (!TASK_SET_CONTROLLERS.contains(controller)) {
            throw new AwsException("InvalidParameterException",
                    "Task sets can only be created for services using the EXTERNAL or CODE_DEPLOY "
                            + "deployment controller type.", 400);
        }
        if (request.getLaunchType() != null && request.getCapacityProviderStrategy() != null
                && !request.getCapacityProviderStrategy().isEmpty()) {
            throw new AwsException("InvalidParameterException",
                    "You cannot specify both a launch type and a capacity provider strategy.", 400);
        }
        if (request.getScaleUnit() != null && !SCALE_UNIT_PERCENT.equals(request.getScaleUnit())) {
            throw new AwsException("InvalidParameterException",
                    "Invalid scale unit: " + request.getScaleUnit(), 400);
        }

        String setId = "ecs-svc/" + UUID.randomUUID().toString().replace("-", "");
        String taskSetArn = regionResolver.buildArn("ecs", region, "task-set/"
                + cluster.getClusterName() + "/" + svc.getServiceName() + "/" + setId);

        TaskSet ts = new TaskSet();
        ts.setId(setId);
        ts.setTaskSetArn(taskSetArn);
        ts.setServiceArn(svc.getServiceArn());
        ts.setClusterArn(cluster.getClusterArn());
        ts.setTaskDefinition(taskDef.getTaskDefinitionArn());
        ts.setStatus(STATUS_ACTIVE);
        ts.setScaleValue(request.getScaleValue() != null ? request.getScaleValue() : 100.0);
        ts.setScaleUnit(SCALE_UNIT_PERCENT);
        ts.setCapacityProviderStrategy(request.getCapacityProviderStrategy());
        ts.setLaunchType(taskSetLaunchType(request, cluster));
        ts.setExternalId(request.getExternalId());
        ts.setStartedBy(request.getStartedBy());
        ts.setNetworkConfiguration(request.getNetworkConfiguration() != null
                ? request.getNetworkConfiguration() : svc.getNetworkConfiguration());
        ts.setLoadBalancers(request.getLoadBalancers());
        ts.setServiceRegistries(request.getServiceRegistries());
        if (request.getTags() != null && !request.getTags().isEmpty()) {
            ts.setTags(new LinkedHashMap<>(request.getTags()));
        }
        if (ts.getLaunchType() == LaunchType.FARGATE) {
            String requested = request.getPlatformVersion();
            ts.setPlatformVersion(requested == null || requested.isBlank()
                    || PLATFORM_VERSION_LATEST.equals(requested) ? DEFAULT_PLATFORM_VERSION : requested);
            ts.setPlatformFamily(platformFamilyOf(taskDef));
        }
        ts.setCreatedAt(Instant.now());
        ts.setUpdatedAt(Instant.now());
        applyTaskSetScale(ts, svc);

        taskSets.put(taskSetArn, ts);
        return ts;
    }

    /**
     * A task set that names neither a launch type nor a capacity provider strategy takes the
     * cluster's default strategy, and falls back to EC2 when the cluster has none, as AWS does.
     */
    private LaunchType taskSetLaunchType(CreateTaskSetRequest request, EcsCluster cluster) {
        if (request.getLaunchType() != null) {
            return request.getLaunchType();
        }
        List<CapacityProviderStrategyItem> strategy = request.getCapacityProviderStrategy();
        if (strategy == null || strategy.isEmpty()) {
            strategy = clusterDefaultStrategy(cluster);
        }
        if (strategy == null || strategy.isEmpty()) {
            return LaunchType.EC2;
        }
        return strategy.stream()
                .map(CapacityProviderStrategyItem::capacityProvider)
                .anyMatch(FARGATE_CAPACITY_PROVIDERS::contains) ? LaunchType.FARGATE : LaunchType.EC2;
    }

    /**
     * Stamps {@code computedDesiredCount}, which AWS derives from the service's desired count and
     * the task set's scale percentage, always rounding up: a computed 1.2 becomes 2 tasks.
     *
     * <p>Floci places no tasks for a task set, so its running and pending counts stay at zero. A
     * stability status derived from them would leave every set on a service with a desired count
     * above zero in {@code STABILIZING} for ever, hanging anything that waits for the set to
     * stabilise, so the set is reported {@code STEADY_STATE} until Floci tracks tasks for it.
     */
    private void applyTaskSetScale(TaskSet ts, EcsServiceModel svc) {
        ts.setComputedDesiredCount(
                (int) Math.ceil(svc.getDesiredCount() * ts.getScaleValue() / 100.0));
        ts.setStabilityStatus(STABILITY_STATUS_STEADY_STATE);
        ts.setStabilityStatusAt(Instant.now());
    }

    public TaskSet updateTaskSet(String clusterRef, String serviceRef, String taskSetRef,
                                  double scaleValue, String scaleUnit, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        EcsServiceModel svc = resolveServiceOrThrow(cluster.getClusterName(), serviceRef, region);
        TaskSet ts = resolveTaskSetOrThrow(svc, taskSetRef);
        if (scaleUnit != null && !SCALE_UNIT_PERCENT.equals(scaleUnit)) {
            throw new AwsException("InvalidParameterException",
                    "Invalid scale unit: " + scaleUnit, 400);
        }
        ts.setScaleValue(scaleValue);
        ts.setScaleUnit(SCALE_UNIT_PERCENT);
        ts.setUpdatedAt(Instant.now());
        applyTaskSetScale(ts, svc);
        return ts;
    }

    /**
     * Deletes a task set. Without {@code force}, AWS refuses one that has not been scaled down to
     * zero, so a deploy tool that forgot to drain the set is told rather than silently losing it.
     */
    public TaskSet deleteTaskSet(String clusterRef, String serviceRef, String taskSetRef,
                                  boolean force, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        EcsServiceModel svc = resolveServiceOrThrow(cluster.getClusterName(), serviceRef, region);
        TaskSet ts = resolveTaskSetOrThrow(svc, taskSetRef);
        if (!force && (ts.getComputedDesiredCount() > 0 || ts.getRunningCount() > 0
                || ts.getPendingCount() > 0)) {
            throw new AwsException("InvalidParameterException",
                    "The task set cannot be deleted because it has not been scaled down to zero. "
                            + "Use the force flag to delete it anyway.", 400);
        }
        ts.setStatus("DRAINING");
        ts.setUpdatedAt(Instant.now());
        taskSets.remove(ts.getTaskSetArn());
        return ts;
    }

    public List<TaskSet> describeTaskSets(String clusterRef, String serviceRef,
                                           List<String> taskSetRefs, String region) {
        return describeTaskSetsDetailed(clusterRef, serviceRef, taskSetRefs, region).taskSets();
    }

    /**
     * The task sets of a service, which {@code DescribeServices} reports on the service itself:
     * a client driving a blue/green deploy reads them from there rather than making a second
     * DescribeTaskSets call.
     */
    public List<TaskSet> taskSetsFor(EcsServiceModel svc) {
        if (svc == null) {
            return List.of();
        }
        return taskSets.values().stream()
                .filter(ts -> svc.getServiceArn().equals(ts.getServiceArn()))
                .sorted(Comparator.comparing(TaskSet::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    public record DescribeTaskSetsResult(List<TaskSet> taskSets, List<Failure> failures) {}

    /** Reports a reference that names no task set of this service as a {@code MISSING} failure. */
    public DescribeTaskSetsResult describeTaskSetsDetailed(String clusterRef, String serviceRef,
                                                           List<String> taskSetRefs, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        EcsServiceModel svc = resolveServiceOrThrow(cluster.getClusterName(), serviceRef, region);
        List<TaskSet> ofService = taskSets.values().stream()
                .filter(ts -> ts.getServiceArn().equals(svc.getServiceArn()))
                .toList();
        if (taskSetRefs == null || taskSetRefs.isEmpty()) {
            return new DescribeTaskSetsResult(ofService, List.of());
        }
        List<TaskSet> found = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        for (String ref : taskSetRefs) {
            TaskSet match = ofService.stream()
                    .filter(ts -> ref.equals(ts.getTaskSetArn()) || ref.equals(ts.getId()))
                    .findFirst().orElse(null);
            if (match != null) {
                found.add(match);
            } else {
                failures.add(Failure.missing(ref.startsWith("arn:") ? ref
                        : regionResolver.buildArn("ecs", region, "task-set/"
                                + cluster.getClusterName() + "/" + svc.getServiceName() + "/" + ref)));
            }
        }
        return new DescribeTaskSetsResult(found, failures);
    }

    public TaskSet updateServicePrimaryTaskSet(String clusterRef, String serviceRef,
                                                String primaryTaskSetRef, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        EcsServiceModel svc = resolveServiceOrThrow(cluster.getClusterName(), serviceRef, region);
        TaskSet primary = resolveTaskSetOrThrow(svc, primaryTaskSetRef);

        taskSets.values().stream()
                .filter(ts -> ts.getServiceArn().equals(svc.getServiceArn()))
                .forEach(ts -> ts.setStatus(ts.getTaskSetArn().equals(primary.getTaskSetArn())
                        ? "PRIMARY" : STATUS_ACTIVE));

        primary.setUpdatedAt(Instant.now());
        return primary;
    }

    // ── Service Deployments & Revisions ───────────────────────────────────────

    public List<ServiceDeployment> describeServiceDeployments(List<String> deploymentArns) {
        return deploymentArns.stream()
                .map(arn -> serviceDeployments.get(arn))
                .filter(d -> d != null)
                .toList();
    }

    public List<String> listServiceDeployments(String serviceRef, String clusterRef,
                                                List<String> statusFilter, String region) {
        return listServiceDeploymentsDetailed(serviceRef, clusterRef, statusFilter, region)
                .stream().map(ServiceDeployment::getServiceDeploymentArn).toList();
    }

    public List<ServiceDeployment> listServiceDeploymentsDetailed(String serviceRef, String clusterRef,
                                                                   List<String> statusFilter, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        EcsServiceModel svc = resolveServiceOrThrow(cluster.getClusterName(), serviceRef, region);

        return serviceDeployments.values().stream()
                .filter(d -> d.getServiceArn().equals(svc.getServiceArn()))
                .filter(d -> statusFilter == null || statusFilter.isEmpty()
                        || statusFilter.contains(d.getStatus()))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .toList();
    }

    public List<ServiceRevision> describeServiceRevisions(List<String> revisionArns) {
        return revisionArns.stream()
                .map(arn -> serviceRevisions.get(arn))
                .filter(r -> r != null)
                .toList();
    }

    /**
     * The deployments of a service, derived from its current state rather than tracked as a
     * rollout. An ACTIVE service always has exactly one PRIMARY deployment: AWS's own
     * {@code ServicesStable} waiter accepts on
     * {@code length(services[?!(length(deployments) == `1` && runningCount == desiredCount)]) == `0`},
     * so reporting none leaves every SDK waiter polling until it times out.
     *
     * <p>A task-definition change is applied in place rather than draining a previous
     * deployment, so there is never a second ACTIVE entry beside the PRIMARY one. An INACTIVE
     * service has no deployments.
     */
    public List<Deployment> deploymentsFor(EcsServiceModel svc) {
        if (svc == null || !"ACTIVE".equals(svc.getStatus())) {
            return List.of();
        }

        String deploymentId = deploymentId(svc);
        boolean converged = svc.getRunningCount() >= svc.getDesiredCount();

        Deployment d = new Deployment();
        d.setId(deploymentId);
        d.setStatus("PRIMARY");
        d.setTaskDefinition(svc.getTaskDefinition());
        d.setDesiredCount(svc.getDesiredCount());
        d.setPendingCount(svc.getPendingCount());
        d.setRunningCount(svc.getRunningCount());
        d.setFailedTasks(0);
        d.setRolloutState(converged ? "COMPLETED" : "IN_PROGRESS");
        d.setRolloutStateReason("ECS deployment " + deploymentId
                + (converged ? " completed." : " in progress."));
        // A deployment reports the placement it runs under the same way the service does: a
        // capacity provider strategy when there is one, a launch type otherwise, never both.
        if (svc.getCapacityProviderStrategy() != null && !svc.getCapacityProviderStrategy().isEmpty()) {
            d.setCapacityProviderStrategy(svc.getCapacityProviderStrategy());
        } else {
            d.setLaunchType(svc.getLaunchType());
        }
        d.setPlatformVersion(svc.getPlatformVersion());
        d.setPlatformFamily(svc.getPlatformFamily());
        d.setNetworkConfiguration(svc.getNetworkConfiguration());
        d.setServiceConnectConfiguration(svc.getServiceConnectConfiguration());
        // The deployment's own start time, not the service's: a task-definition change mints a
        // new deployment id, so reporting service creation here would contradict it. Older
        // persisted services predate the field and fall back to the service creation time.
        Instant startedAt = svc.getLastDeploymentAt() != null
                ? svc.getLastDeploymentAt()
                : svc.getCreatedAt();
        d.setCreatedAt(startedAt);
        d.setUpdatedAt(startedAt);
        return List.of(d);
    }

    /**
     * The service's event log, derived from its current state for the same reason
     * {@link #deploymentsFor} derives its deployments: Floci applies a change in place instead of
     * running a rollout, so there is no history to replay. A converged service reports the one
     * event tools actually poll for, "has reached a steady state"; one still converging reports
     * none.
     *
     * <p>The event id is derived from the deployment it belongs to rather than minted per call,
     * so a client that persists it does not see a new event on every describe.
     */
    public List<ServiceEvent> eventsFor(EcsServiceModel svc) {
        if (svc == null || !"ACTIVE".equals(svc.getStatus())
                || svc.getRunningCount() < svc.getDesiredCount()) {
            return List.of();
        }
        String deploymentId = deploymentId(svc);
        Instant at = svc.getLastDeploymentAt() != null ? svc.getLastDeploymentAt() : svc.getCreatedAt();
        return List.of(new ServiceEvent(
                UUID.nameUUIDFromBytes((deploymentId + ":steady-state").getBytes(StandardCharsets.UTF_8))
                        .toString(),
                at,
                "(service " + svc.getServiceName() + ") has reached a steady state."));
    }

    private static String newDeploymentId() {
        return "ecs-svc/" + java.util.UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * A stable {@code ecs-svc/<id>} identifier derived from the service ARN and its task
     * definition. Deployments are derived per request, so a random id would differ on every
     * {@code DescribeServices} call and read as perpetual drift in clients that persist it.
     * Deriving it from persisted state keeps it identical across calls and across restarts,
     * while still rolling over when the task definition changes.
     */
    private String deploymentId(EcsServiceModel svc) {
        if (svc.getDeploymentId() != null) {
            return svc.getDeploymentId();
        }
        // Legacy fallback: services persisted before deploymentId was stored derive a stable
        // id from the service ARN and task definition, so it still rolls over on a task-def change.
        String seed = svc.getServiceArn() + ":" + svc.getTaskDefinition();
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < seed.length(); i++) {
            hash = (hash ^ seed.charAt(i)) * 0x100000001b3L;
        }
        return "ecs-svc/" + Long.toUnsignedString(hash);
    }

    /**
     * Records the deployment and the revision it targets. The revision is the snapshot of the
     * service's configuration, so it is copied out of the service rather than read back from it
     * later; the deployment links to it, which is how a caller gets from
     * {@code DescribeServiceDeployments} to what was actually deployed.
     *
     * <p>Floci applies a change in place instead of rolling it, so a deployment is finished the
     * moment it is recorded: {@code startedAt} and {@code finishedAt} are both its creation time.
     */
    private void recordServiceDeployment(EcsServiceModel svc, String taskDefinition, String region) {
        String deploymentId = UUID.randomUUID().toString().replace("-", "");
        String deploymentArn = regionResolver.buildArn("ecs", region,
                "service-deployment/" + deploymentId);
        String revisionId = UUID.randomUUID().toString().replace("-", "");
        String revisionArn = regionResolver.buildArn("ecs", region,
                "service-revision/" + revisionId);
        Instant now = Instant.now();

        List<String> priorRevisions = serviceRevisions.values().stream()
                .filter(r -> svc.getServiceArn().equals(r.getServiceArn()))
                .sorted(Comparator.comparing(ServiceRevision::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .limit(1)
                .map(ServiceRevision::getServiceRevisionArn)
                .toList();

        ServiceDeployment deployment = new ServiceDeployment();
        deployment.setServiceDeploymentArn(deploymentArn);
        deployment.setServiceArn(svc.getServiceArn());
        deployment.setClusterArn(svc.getClusterArn());
        deployment.setTaskDefinition(taskDefinition);
        deployment.setStatus("SUCCESSFUL");
        deployment.setCreatedAt(now);
        deployment.setStartedAt(now);
        deployment.setFinishedAt(now);
        deployment.setUpdatedAt(now);
        deployment.setTargetServiceRevisionArn(revisionArn);
        deployment.setSourceServiceRevisionArns(priorRevisions);
        serviceDeployments.put(deploymentArn, deployment);

        ServiceRevision revision = new ServiceRevision();
        revision.setServiceRevisionArn(revisionArn);
        revision.setServiceArn(svc.getServiceArn());
        revision.setClusterArn(svc.getClusterArn());
        revision.setTaskDefinition(taskDefinition);
        revision.setLaunchType(svc.getLaunchType());
        revision.setCapacityProviderStrategy(svc.getCapacityProviderStrategy());
        revision.setPlatformVersion(svc.getPlatformVersion());
        revision.setPlatformFamily(svc.getPlatformFamily());
        revision.setLoadBalancers(svc.getLoadBalancers());
        revision.setServiceRegistries(svc.getServiceRegistries());
        revision.setNetworkConfiguration(svc.getNetworkConfiguration());
        revision.setServiceConnectConfiguration(svc.getServiceConnectConfiguration());
        revision.setContainerImages(containerImagesOf(taskDefinition, region));
        revision.setCreatedAt(now);
        serviceRevisions.put(revisionArn, revision);
    }

    /** The images the revision's task definition pins, one entry per container definition. */
    private List<ContainerImage> containerImagesOf(String taskDefinitionRef, String region) {
        TaskDefinition taskDef;
        try {
            taskDef = resolveTaskDefinitionOrThrow(taskDefinitionRef, region);
        } catch (AwsException expected) {
            // The revision records what it can. A service can outlive the revision it was created
            // on, and a snapshot with no images is better than failing the whole call.
            LOG.debugv("No task definition for service revision images: {0}", taskDefinitionRef);
            return List.of();
        }
        if (taskDef.getContainerDefinitions() == null) {
            return List.of();
        }
        return taskDef.getContainerDefinitions().stream()
                .map(def -> new ContainerImage(def.getName(), def.getImage(), null))
                .toList();
    }

    /** The counts a service revision summary reports, which track the service they belong to. */
    public EcsServiceModel serviceByArn(String serviceArn) {
        if (serviceArn == null) {
            return null;
        }
        return services.values().stream()
                .filter(s -> serviceArn.equals(s.getServiceArn()))
                .findFirst().orElse(null);
    }

    public ServiceRevision serviceRevisionByArn(String revisionArn) {
        return revisionArn == null ? null : serviceRevisions.get(revisionArn);
    }

    /** The service's most recent deployment, which is the one {@code Service} points at. */
    public ServiceDeployment currentServiceDeployment(EcsServiceModel svc) {
        if (svc == null) {
            return null;
        }
        return serviceDeployments.values().stream()
                .filter(d -> svc.getServiceArn().equals(d.getServiceArn()))
                .max(Comparator.comparing(ServiceDeployment::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    // ── Stub operations ────────────────────────────────────────────────────────

    public String submitTaskStateChange() {
        return "ACK_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public String submitContainerStateChange() {
        return "ACK_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public String submitAttachmentStateChanges() {
        return "ACK_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    // ── Service Reconciliation ────────────────────────────────────────────────

    void reconcile() {
        try {
            reconcileTasks();
        } catch (Exception e) {
            LOG.warnv(e, "ECS task reconciliation tick failed: {0}", e.getMessage());
        }
        for (String accountId : reconcilableAccountIds()) {
            RequestScopes.runAs(accountId, () -> {
                try {
                    reconcileServices();
                } catch (Exception e) {
                    LOG.warnv(e, "ECS service reconciliation tick failed for account {0}: {1}",
                            accountId, e.getMessage());
                }
            });
        }
    }

    private Set<String> reconcilableAccountIds() {
        Set<String> accountIds = new LinkedHashSet<>();
        accountIds.add(regionResolver.getAccountId());
        if (servicesStore != null) {
            try {
                for (AccountAwareStorageBackend.AccountEntry<EcsServiceModel> entry
                        : servicesStore.scanAllAccountEntries(key -> true)) {
                    accountIds.add(entry.accountId());
                }
            } catch (Exception e) {
                LOG.warnv(e, "Could not enumerate ECS service accounts: {0}", e.getMessage());
            }
        }
        return accountIds;
    }

    private void reconcileTasks() {
        for (String taskArn : taskHandles.keySet()) {
            RequestScopes.runAs(taskAccountId(taskArn), () -> {
                try {
                    reconcileTask(taskArn);
                } catch (Exception e) {
                    LOG.debugv("Error reconciling ECS task {0}: {1}", taskArn, e.getMessage());
                }
            });
        }
    }

    private String taskAccountId(String taskArn) {
        try {
            String accountId = AwsArnUtils.parse(taskArn).accountId();
            if (accountId != null && !accountId.isBlank()) {
                return accountId;
            }
        } catch (IllegalArgumentException e) {
            LOG.warnv("Could not parse an account from ECS task ARN {0}, reconciling it in the default account: {1}",
                    taskArn, e.getMessage());
        }
        return regionResolver.getAccountId();
    }

    private void reconcileTask(String taskArn) {
        EcsTask task = tasks.get(taskArn);
        if (task == null) {
            return;
        }
        synchronized (task) {
            reconcileTaskLocked(taskArn, task);
        }
    }

    private void reconcileTaskLocked(String taskArn, EcsTask task) {
        if (TaskStatus.STOPPING.name().equals(task.getLastStatus())) {
            stopTask(task.getClusterArn(), taskArn, task.getStoppedReason(), task.getStopCode(), taskRegion(task));
            return;
        }

        if (TaskStatus.STOPPED.name().equals(task.getLastStatus())) {
            reconcileStoppedTask(taskArn);
            return;
        }

        if (!TaskStatus.RUNNING.name().equals(task.getLastStatus())) {
            return;
        }

        EcsTaskHandle handle = taskHandles.get(taskArn);
        if (handle == null) {
            return;
        }

        updateHealth(task, handle);

        // Inspect every container; abort if any are still running. A container that exited on its
        // own has a finish time of its own, read here while the daemon still remembers it.
        Map<String, Integer> exitCodes = new LinkedHashMap<>();
        Map<String, Instant> finishedAt = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : handle.getContainerIds().entrySet()) {
            Integer code = containerManager.getExitCodeIfStopped(entry.getValue());
            if (code == null) {
                return;
            }
            exitCodes.put(entry.getKey(), code);
            Instant finished = containerManager.getFinishedAtIfStopped(entry.getValue());
            if (finished != null) {
                finishedAt.put(entry.getKey(), finished);
            }
        }

        // All containers have exited. Atomically claim the handle to avoid
        // racing with an explicit stopTask() call.
        EcsTaskHandle claimed = taskHandles.remove(taskArn);
        if (claimed == null) {
            return;
        }

        // Close log streams and remove the stopped Docker containers without re-inspecting.
        containerManager.cleanupStoppedTask(claimed);

        if (task.getContainers() != null) {
            task.getContainers().forEach(c -> {
                c.setLastStatus("STOPPED");
                Integer code = exitCodes.get(c.getName());
                if (code != null) {
                    c.setExitCode(code);
                }
                Instant finished = finishedAt.get(c.getName());
                if (finished != null) {
                    c.setFinishedAt(finished);
                }
            });
        }

        task.setLastStatus(TaskStatus.STOPPED.name());
        task.setDesiredStatus(TaskStatus.STOPPED.name());
        task.setStoppingAt(Instant.now());
        task.setStoppedAt(Instant.now());
        task.setExecutionStoppedAt(Instant.now());
        task.setStoppedReason("Essential container in task exited");
        task.setStopCode(STOP_CODE_ESSENTIAL_CONTAINER_EXITED);
        task.bumpVersion();

        EcsCluster cluster = resolveClusterByArn(task.getClusterArn());
        if (cluster != null && cluster.getRunningTasksCount() > 0) {
            cluster.setRunningTasksCount(cluster.getRunningTasksCount() - 1);
        }

        LOG.infov("ECS task {0} reconciled to STOPPED (all containers exited)", taskArn);
        String region = taskRegion(task);
        containerManager.releaseTaskNetwork(task, region);
        if (eventPublisher != null) {
            eventPublisher.emitTaskLadder(task, TaskStatus.RUNNING, TaskStatus.STOPPED, region);
        }
    }

    /**
     * Refreshes a running task's health from its containers' health checks. The task is HEALTHY
     * only when every container that has a health check reports healthy, UNHEALTHY as soon as one
     * does not, and UNKNOWN when nothing is being probed, which is how ECS aggregates it.
     */
    private void updateHealth(EcsTask task, EcsTaskHandle handle) {
        if (task.getContainers() == null || task.getContainers().isEmpty()) {
            return;
        }
        boolean anyUnhealthy = false;
        boolean anyHealthy = false;
        for (Container container : task.getContainers()) {
            String dockerId = handle.getContainerIds().get(container.getName());
            if (dockerId == null) {
                continue;
            }
            String health = containerManager.ecsHealthStatus(dockerId);
            container.setHealthStatus(health);
            anyUnhealthy |= HEALTH_STATUS_UNHEALTHY.equals(health);
            anyHealthy |= HEALTH_STATUS_HEALTHY.equals(health);
        }
        String aggregate = anyUnhealthy ? HEALTH_STATUS_UNHEALTHY
                : anyHealthy ? HEALTH_STATUS_HEALTHY : HEALTH_STATUS_UNKNOWN;
        if (!aggregate.equals(task.getHealthStatus())) {
            task.setHealthStatus(aggregate);
            task.bumpVersion();
        }
    }

    /** The region a task lives in, read off its ARN, falling back to the default region. */
    private String taskRegion(EcsTask task) {
        if (task.getTaskArn() != null) {
            try {
                String region = AwsArnUtils.parse(task.getTaskArn()).region();
                if (region != null && !region.isBlank()) {
                    return region;
                }
            } catch (Exception e) {
                LOG.warnv(e, "Could not parse region from task ARN {0}, falling back to default region",
                        task.getTaskArn());
            }
        }
        return regionResolver != null ? regionResolver.getDefaultRegion() : "us-east-1";
    }

    private void reconcileStoppedTask(String taskArn) {
        EcsTaskHandle claimed = taskHandles.remove(taskArn);
        if (claimed == null) {
            return;
        }

        try {
            containerManager.stopTaskAndCollectExitCodes(claimed);
        } finally {
            // A task can be reported STOPPED even if Docker rejected both teardown attempts.
            // Keep only the affected log readers and retry their teardown on later reconciliation ticks.
            retainUnresolvedLogHandle(taskArn, claimed);
        }
    }

    private void retainUnresolvedLogHandle(String taskArn, EcsTaskHandle handle) {
        if (handle != null && handle.hasOpenLogStreams()) {
            taskHandles.put(taskArn, handle);
        }
    }

    private EcsTaskHandle recoverTaskHandle(EcsTask task, String region) {
        if (task.getContainers() == null || task.getContainers().isEmpty()) {
            return null;
        }
        Map<String, String> containerIds = new LinkedHashMap<>();
        for (Container container : task.getContainers()) {
            String dockerId = container.getDockerId() != null
                    ? container.getDockerId() : container.getRuntimeId();
            if (dockerId == null) {
                return null;
            }
            containerIds.put(container.getName(), dockerId);
        }
        Map<String, Integer> stopTimeouts = new LinkedHashMap<>();
        try {
            TaskDefinition definition = resolveTaskDefinitionOrThrow(task.getTaskDefinitionArn(), region);
            for (ContainerDefinition container : definition.getContainerDefinitions()) {
                if (container.getStopTimeout() != null) {
                    stopTimeouts.put(container.getName(), container.getStopTimeout());
                }
            }
        } catch (AwsException e) {
            LOG.warnv(e, "Could not load task definition for {0}; using the default container stop timeout",
                    task.getTaskArn());
        }
        EcsTaskHandle recovered = new EcsTaskHandle(task.getTaskArn(), containerIds, Map.of(),
                null, task.getNetworkInterfaceId(), region, stopTimeouts);
        EcsTaskHandle existing = taskHandles.putIfAbsent(task.getTaskArn(), recovered);
        return existing != null ? existing : recovered;
    }

    /**
     * DAEMON scheduling: exactly one task on each ACTIVE container instance of the cluster,
     * none anywhere else. {@code desiredCount} is derived from the instance count, as AWS
     * reports it; {@code runningCount} counts only tasks that are actually RUNNING.
     * Placement constraints are not evaluated.
     */
    private void reconcileDaemonService(String key, EcsServiceModel svc, String clusterName, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterName, region);
        String prefix = containerInstanceKey(cluster.getClusterArn(), "");
        List<ContainerInstance> activeInstances = containerInstances.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .filter(ci -> "ACTIVE".equals(ci.getStatus()))
                .toList();
        Set<String> activeArns = activeInstances.stream()
                .map(ContainerInstance::getContainerInstanceArn)
                .collect(Collectors.toSet());

        // Every task that still occupies its instance: RUNNING, PENDING (Docker start in
        // flight) and STOPPING (teardown in flight, possibly stranded). Only STOPPED frees the
        // slot — otherwise a stranded STOPPING task would get a duplicate next to it.
        // RUNNING tasks are preferred as the instance's daemon when there are several.
        List<EcsTask> liveTasks = tasks.values().stream()
                .filter(t -> ownedBy(t, svc, cluster))
                .filter(t -> !TaskStatus.STOPPED.name().equals(t.getLastStatus()))
                .sorted(Comparator.comparingInt(t -> daemonTaskRank(t.getLastStatus())))
                .toList();

        Set<String> covered = new HashSet<>();
        int running = 0;
        for (EcsTask t : liveTasks) {
            String instanceArn = t.getContainerInstanceArn();
            boolean keep = instanceArn != null && activeArns.contains(instanceArn) && covered.add(instanceArn);
            if (keep) {
                if (TaskStatus.RUNNING.name().equals(t.getLastStatus())) {
                    running++;
                }
                continue;
            }
            if (TaskStatus.STOPPING.name().equals(t.getLastStatus())) {
                continue; // teardown already in flight
            }
            try {
                stopTask(clusterName, t.getTaskArn(), instanceArn == null || !activeArns.contains(instanceArn)
                        ? "Daemon task's container instance is no longer active"
                        : "Daemon service already has a task on this container instance",
                        STOP_CODE_SERVICE_SCHEDULER_INITIATED, region);
            } catch (Exception e) {
                LOG.warnv("Service reconciler failed to stop daemon task {0}: {1}", t.getTaskArn(), e.getMessage());
            }
        }
        for (ContainerInstance ci : activeInstances) {
            if (covered.contains(ci.getContainerInstanceArn())) {
                continue;
            }
            try {
                EcsTask launched = launchServiceTask(cluster, svc, LaunchType.EC2,
                        ci.getContainerInstanceArn(), region);
                // A Docker start failure comes back already STOPPED: the slot stays uncovered and
                // the next tick retries, and it must not count towards runningCount.
                if (!TaskStatus.STOPPED.name().equals(launched.getLastStatus())) {
                    covered.add(ci.getContainerInstanceArn());
                    if (TaskStatus.RUNNING.name().equals(launched.getLastStatus())) {
                        running++;
                    }
                    LOG.infov("Service reconciler started daemon task {0} for service {1} on {2}",
                            launched.getTaskArn(), svc.getServiceName(), ci.getContainerInstanceArn());
                }
            } catch (Exception e) {
                LOG.warnv("Service reconciler failed to start daemon task for {0}: {1}",
                        svc.getServiceName(), e.getMessage());
            }
        }
        svc.setDesiredCount(activeInstances.size());
        svc.setRunningCount(running);
        services.put(key, svc);
    }

    private static int daemonTaskRank(String lastStatus) {
        if (TaskStatus.RUNNING.name().equals(lastStatus)) {
            return 0;
        }
        if (TaskStatus.PENDING.name().equals(lastStatus)) {
            return 1;
        }
        return 2;
    }

    /**
     * The service's task definition as an ARN. Services created before the ARN was pinned at
     * create/update time may still hold a raw {@code family} / {@code family:revision}
     * reference; resolve it once and store the ARN so the comparison with
     * {@link EcsTask#getTaskDefinitionArn()} is exact. A dangling reference is left as-is and
     * simply matches nothing, which is the pre-existing behaviour for an unresolvable service.
     */
    private String pinnedTaskDefinitionArn(EcsServiceModel svc, String key, String region) {
        String ref = svc.getTaskDefinition();
        if (ref == null || ref.startsWith("arn:")) {
            return ref;
        }
        try {
            String arn = resolveTaskDefinitionOrThrow(ref, region).getTaskDefinitionArn();
            svc.setTaskDefinition(arn);
            services.put(key, svc);
            return arn;
        } catch (AwsException e) {
            return ref;
        }
    }

    void reconcileServices() {
        for (Map.Entry<String, EcsServiceModel> entry : services.entrySet()) {
            try {
                reconcileService(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                LOG.debugv("Error reconciling ECS service {0}: {1}", entry.getKey(), e.getMessage());
            }
        }
    }

    /**
     * Whether a task is genuinely owned by {@code svc}. Both halves matter: {@code
     * owningServiceArn} is stamped only by {@link #launchServiceTask} and so cannot be forged
     * through a {@code RunTask} {@code group}, and the cluster is compared by full ARN rather
     * than by a name suffix, which would otherwise conflate same-named clusters across regions
     * or accounts.
     */
    /**
     * A task belongs to a superseded deployment. New tasks carry the service's deploymentId and
     * are compared on that; tasks launched before the id was stamped (legacy state) fall back to
     * the task-definition-ARN comparison, preserving the pre-existing rollout on a task-def change.
     */
    private static boolean isStaleForDeployment(EcsTask t, String currentDeploymentId,
                                                String currentTaskDefinitionArn) {
        if (t.getDeploymentId() != null) {
            return !currentDeploymentId.equals(t.getDeploymentId());
        }
        return currentTaskDefinitionArn != null
                && !currentTaskDefinitionArn.equals(t.getTaskDefinitionArn());
    }

    private static boolean ownedBy(EcsTask task, EcsServiceModel svc, EcsCluster cluster) {
        return svc.getServiceArn() != null
                && svc.getServiceArn().equals(task.getOwningServiceArn())
                && cluster.getClusterArn().equals(task.getClusterArn());
    }

    private void reconcileService(String key, EcsServiceModel svc) {
        if (!"ACTIVE".equals(svc.getStatus())) {
            return;
        }

        String region = extractRegionFromServiceKey(key);
        String clusterName = extractClusterNameFromServiceKey(key);

        if (SCHEDULING_DAEMON.equals(svc.getSchedulingStrategy())) {
            reconcileDaemonService(key, svc, clusterName, region);
            return;
        }

        EcsCluster cluster = resolveClusterOrDefault(clusterName, region);
        List<EcsTask> runningTasks = tasks.values().stream()
                .filter(t -> ownedBy(t, svc, cluster))
                .filter(t -> TaskStatus.RUNNING.name().equals(t.getLastStatus()))
                .toList();
        long running = runningTasks.size();
        String currentDeploymentId = deploymentId(svc);
        String currentTaskDefinitionArn = pinnedTaskDefinitionArn(svc, key, region);
        List<EcsTask> staleTasks = runningTasks.stream()
                .filter(t -> isStaleForDeployment(t, currentDeploymentId, currentTaskDefinitionArn))
                .toList();
        long current = running - staleTasks.size();

        svc.setRunningCount((int) running);

        if (eventPublisher != null) {
            String deploymentId = currentDeploymentId;
            boolean converged = current >= svc.getDesiredCount();
            if (converged) {
                if (!deploymentId.equals(svc.getLastCompletedDeploymentId())) {
                    svc.setLastCompletedDeploymentId(deploymentId);
                    services.put(key, svc);
                    eventPublisher.emitDeploymentStateChange(svc, "SERVICE_DEPLOYMENT_COMPLETED",
                            "ECS deployment " + deploymentId + " completed.", region);
                }
            } else if (inProgressEmitted.add(deploymentId)) {
                eventPublisher.emitDeploymentStateChange(svc, "SERVICE_DEPLOYMENT_IN_PROGRESS",
                        "ECS deployment " + deploymentId + " in progress.", region);
            }
        }

        if (current < svc.getDesiredCount()) {
            int toStart = svc.getDesiredCount() - (int) current;
            for (int i = 0; i < toStart; i++) {
                try {
                    EcsTask launched = launchServiceTask(cluster, svc, svc.getLaunchType(), null, region);
                    LOG.infov("Service reconciler started task {0} for service {1}",
                            launched.getTaskArn(), svc.getServiceName());
                } catch (Exception e) {
                    LOG.warnv("Service reconciler failed to start task for {0}: {1}",
                            svc.getServiceName(), e.getMessage());
                }
            }
        } else if (running > svc.getDesiredCount()) {
            int toStop = (int) running - svc.getDesiredCount();
            // Drain stale tasks first: once their replacements are RUNNING this is the second
            // half of the rolling deployment, not a scale-in.
            Stream.concat(staleTasks.stream(),
                            runningTasks.stream().filter(t -> !staleTasks.contains(t)))
                    .limit(toStop)
                    .forEach(t -> {
                        boolean stale = staleTasks.contains(t);
                        try {
                            stopTask(clusterName, t.getTaskArn(),
                                    stale ? "Service deployment replaced task definition " + t.getTaskDefinitionArn()
                                          : "Service scale-in",
                                    STOP_CODE_SERVICE_SCHEDULER_INITIATED, region);
                        } catch (Exception e) {
                            LOG.warnv("Service reconciler failed to stop task {0}: {1}",
                                    t.getTaskArn(), e.getMessage());
                        }
                    });
        }
    }

    // ── Resolution helpers ────────────────────────────────────────────────────

    private EcsCluster getOrCreateDefaultCluster(String region) {
        String key = clusterKey(region, DEFAULT_CLUSTER);
        return clusters.computeIfAbsent(key, k -> {
            EcsCluster c = new EcsCluster();
            c.setClusterName(DEFAULT_CLUSTER);
            c.setClusterArn(regionResolver.buildArn("ecs", region, "cluster/" + DEFAULT_CLUSTER));
            c.setStatus("ACTIVE");
            return c;
        });
    }

    private EcsCluster resolveClusterOrDefault(String clusterRef, String region) {
        if (clusterRef == null || clusterRef.isBlank() || DEFAULT_CLUSTER.equals(clusterRef)) {
            return getOrCreateDefaultCluster(region);
        }
        EcsCluster cluster = resolveCluster(clusterRef, region);
        if (cluster == null) {
            throw new AwsException("ClusterNotFoundException", "Cluster not found: " + clusterRef, 400);
        }
        return cluster;
    }

    private EcsCluster resolveCluster(String clusterRef, String region) {
        EcsCluster byName = clusters.get(clusterKey(region, clusterRef));
        if (byName != null) {
            return byName;
        }
        return clusters.values().stream()
                .filter(c -> c.getClusterArn().equals(clusterRef))
                .findFirst().orElse(null);
    }

    private EcsCluster resolveClusterOrThrow(String clusterRef, String region) {
        EcsCluster cluster = resolveCluster(clusterRef, region);
        if (cluster == null) {
            throw new AwsException("ClusterNotFoundException", "Cluster not found: " + clusterRef, 400);
        }
        return cluster;
    }

    private EcsCluster resolveClusterByArn(String clusterArn) {
        return clusters.values().stream()
                .filter(c -> c.getClusterArn().equals(clusterArn))
                .findFirst().orElse(null);
    }

    private TaskDefinition resolveTaskDefinitionOrThrow(String ref, String region) {
        TaskDefinition td = taskDefinitions.get(ref);
        if (td != null) { return td; }
        td = taskDefinitions.values().stream()
                .filter(d -> d.getTaskDefinitionArn().equals(ref))
                .findFirst().orElse(null);
        if (td != null) { return td; }
        Integer latest = latestRevisions.get(ref);
        if (latest != null) {
            td = taskDefinitions.get(ref + ":" + latest);
            if (td != null) { return td; }
        }
        throw new AwsException("ClientException", "Unable to describe task definition: " + ref, 400);
    }

    /**
     * Resolves a task definition for something that is about to run on it. A revision on its way
     * out cannot start new work: AWS refuses to run a task or create a service on a
     * {@code DELETE_IN_PROGRESS} revision, while the tasks already on it keep running.
     */
    private TaskDefinition resolveLaunchableTaskDefinition(String ref, String region) {
        TaskDefinition td = resolveTaskDefinitionOrThrow(ref, region);
        if (STATUS_DELETE_IN_PROGRESS.equals(td.getStatus())) {
            throw new AwsException("ClientException",
                    "The task definition " + td.getTaskDefinitionArn() + " is being deleted and "
                            + "cannot be used to start new tasks.", 400);
        }
        return td;
    }

    private EcsTask resolveTask(String ref, String region) {
        EcsTask task = tasks.get(ref);
        if (task != null) { return task; }
        return tasks.values().stream()
                .filter(t -> t.getTaskArn().endsWith("/" + ref))
                .findFirst().orElse(null);
    }

    private EcsTask resolveTaskOrThrow(String ref, String region) {
        EcsTask task = resolveTask(ref, region);
        if (task == null) {
            throw new AwsException("InvalidParameterException", "Task not found: " + ref, 400);
        }
        return task;
    }

    private EcsServiceModel resolveService(String clusterName, String serviceId, String region) {
        EcsServiceModel svc = services.get(serviceKey(region, clusterName, serviceId));
        if (svc != null) { return svc; }
        return services.values().stream()
                .filter(s -> s.getServiceArn().equals(serviceId) || s.getServiceName().equals(serviceId))
                .findFirst().orElse(null);
    }

    private EcsServiceModel resolveServiceOrThrow(String clusterName, String serviceId, String region) {
        EcsServiceModel svc = resolveService(clusterName, serviceId, region);
        if (svc == null) {
            throw new AwsException("ServiceNotFoundException", "Service not found: " + serviceId, 404);
        }
        return svc;
    }

    private ContainerInstance resolveContainerInstance(String clusterArn, String ref) {
        String prefix = containerInstanceKey(clusterArn, "");
        return containerInstances.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .filter(ci -> ci.getContainerInstanceArn().equals(ref)
                        || ci.getContainerInstanceArn().endsWith("/" + ref))
                .findFirst().orElse(null);
    }

    private ContainerInstance resolveContainerInstanceOrThrow(String clusterArn, String ref) {
        ContainerInstance instance = resolveContainerInstance(clusterArn, ref);
        if (instance == null) {
            throw new AwsException("InvalidParameterException",
                    "Container instance not found: " + ref, 400);
        }
        return instance;
    }

    private CapacityProvider resolveCapacityProviderOrThrow(String nameOrArn) {
        CapacityProvider cp = capacityProviders.get(nameOrArn);
        if (cp != null) { return cp; }
        cp = capacityProviders.values().stream()
                .filter(p -> p.getCapacityProviderArn().equals(nameOrArn))
                .findFirst().orElse(null);
        if (cp == null) {
            throw new AwsException("InvalidParameterException",
                    "Capacity provider not found: " + nameOrArn, 400);
        }
        return cp;
    }

    /**
     * Resolves a task set of this service by ARN or id. A reference that names none is a
     * {@code TaskSetNotFoundException}, which is the error the SDKs model for it; the task sets of
     * another service do not count, because task sets are scoped to a cluster and a service.
     */
    private TaskSet resolveTaskSetOrThrow(EcsServiceModel svc, String ref) {
        TaskSet ts = taskSets.values().stream()
                .filter(t -> t.getServiceArn().equals(svc.getServiceArn()))
                .filter(t -> t.getTaskSetArn().equals(ref) || t.getId().equals(ref))
                .findFirst().orElse(null);
        if (ts == null) {
            throw new AwsException("TaskSetNotFoundException", "Task set not found: " + ref, 400);
        }
        return ts;
    }

    // ── Key helpers ───────────────────────────────────────────────────────────

    private static String clusterKey(String region, String clusterName) {
        return region + "::" + clusterName;
    }

    private static String serviceKey(String region, String clusterName, String serviceName) {
        return region + "::" + clusterName + "/" + serviceName;
    }

    private static String serviceKeyPrefix(String region, String clusterName) {
        return region + "::" + clusterName + "/";
    }

    private static String containerInstanceKey(String clusterArn, String instanceArn) {
        return clusterArn + "/" + instanceArn;
    }

    private static String extractRegionFromServiceKey(String key) {
        return key.substring(0, key.indexOf("::"));
    }

    private static String extractServiceName(String serviceName) {
        if (serviceName != null && serviceName.contains("/")) {
            return serviceName.substring(serviceName.lastIndexOf("/") + 1);
        }
        return serviceName;
    }

    private static String extractClusterNameFromServiceKey(String key) {
        String after = key.substring(key.indexOf("::") + 2);
        int slash = after.indexOf('/');
        return slash >= 0 ? after.substring(0, slash) : after;
    }

    // ─── Resource Explorer 2 ───────────────────────────────────────────────────

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (EcsCluster cluster : clusters.values()) {
            addExplorerResource(resources, cluster.getClusterArn(), "ecs:cluster", null, cluster.getTags());
        }
        for (EcsServiceModel service : services.values()) {
            addExplorerResource(resources, service.getServiceArn(), "ecs:service",
                    service.getCreatedAt(), service.getTags());
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(
                new SupportedResourceType("ecs:cluster", "ecs", true),
                new SupportedResourceType("ecs:service", "ecs", true));
    }

    private static void addExplorerResource(List<ExplorerResource> out, String arn, String resourceType,
                                            Instant createdAt, Map<String, String> tags) {
        if (arn == null) {
            return;
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        out.add(new ExplorerResource(arn, resourceType, "ecs",
                parsed.region(), parsed.accountId(),
                createdAt != null ? createdAt : Instant.now(),
                tags != null ? tags : Map.of()));
    }
}
