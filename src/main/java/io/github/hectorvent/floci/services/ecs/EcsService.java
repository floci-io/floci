package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskHandle;
import io.github.hectorvent.floci.services.ecs.model.Attribute;
import io.github.hectorvent.floci.services.ecs.model.CapacityProvider;
import io.github.hectorvent.floci.services.ecs.model.ClusterSetting;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.ContainerInstance;
import io.github.hectorvent.floci.services.ecs.model.ContainerOverride;
import io.github.hectorvent.floci.services.ecs.model.EcsCluster;
import io.github.hectorvent.floci.services.ecs.model.EcsLoadBalancer;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.ProtectedTask;
import io.github.hectorvent.floci.services.ecs.model.ServiceDeployment;
import io.github.hectorvent.floci.services.ecs.model.ServiceRevision;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.ecs.model.TaskSet;
import io.github.hectorvent.floci.services.ecs.model.TaskStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@ApplicationScoped
public class EcsService {

    private static final Logger LOG = Logger.getLogger(EcsService.class);
    private static final String DEFAULT_CLUSTER = "default";

    private final RegionResolver regionResolver;
    private final EcsContainerManager containerManager;
    private final EcsLoadBalancerRegistrar lbRegistrar;
    private final StorageFactory storageFactory;
    private final boolean dockerMode;
    private final String baseUrl;
    private final ScheduledExecutorService reconciler = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "ecs-reconciler"); t.setDaemon(true); return t; });

    // region::clusterName → EcsCluster
    private Map<String, EcsCluster> clusters = new ConcurrentHashMap<>();
    // family:revision → TaskDefinition
    private Map<String, TaskDefinition> taskDefinitions = new ConcurrentHashMap<>();
    // family → latest revision number
    private Map<String, Integer> latestRevisions = new ConcurrentHashMap<>();
    // taskArn → EcsTask
    private final Map<String, EcsTask> tasks = new ConcurrentHashMap<>();
    // taskArn → EcsTaskHandle (running containers)
    private final Map<String, EcsTaskHandle> taskHandles = new ConcurrentHashMap<>();
    // region::clusterName/serviceName → EcsServiceModel
    private Map<String, EcsServiceModel> services = new ConcurrentHashMap<>();
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
    private Map<String, String> accountSettings = new ConcurrentHashMap<>();

    @Inject
    public EcsService(RegionResolver regionResolver, EcsContainerManager containerManager,
                      EmulatorConfig config, EcsLoadBalancerRegistrar lbRegistrar,
                      StorageFactory storageFactory) {
        this.regionResolver = regionResolver;
        this.containerManager = containerManager;
        this.dockerMode = !config.services().ecs().mock();
        this.baseUrl = config.effectiveBaseUrl();
        this.lbRegistrar = lbRegistrar;
        this.storageFactory = storageFactory;
    }

    @PostConstruct
    void init() {
        initializeStorage();
        reconciler.scheduleAtFixedRate(this::reconcile, 5, 5, TimeUnit.SECONDS);
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
        this.services = storageBacked("ecs-services.json",
                new TypeReference<Map<String, EcsServiceModel>>() {});
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
        reconciler.shutdownNow();
    }

    // ── Clusters ─────────────────────────────────────────────────────────────

    public EcsCluster createCluster(String clusterName, String region) {
        return createCluster(clusterName, null, region);
    }

    public EcsCluster createCluster(String clusterName, Map<String, String> tags, String region) {
        String name = (clusterName == null || clusterName.isBlank()) ? DEFAULT_CLUSTER : clusterName;
        String key = clusterKey(region, name);
        if (clusters.containsKey(key)) {
            return clusters.get(key);
        }
        EcsCluster cluster = new EcsCluster();
        cluster.setClusterName(name);
        cluster.setClusterArn(regionResolver.buildArn("ecs", region, "cluster/" + name));
        cluster.setStatus("ACTIVE");
        if (tags != null && !tags.isEmpty()) {
            cluster.setTags(new LinkedHashMap<>(tags));
        }
        clusters.put(key, cluster);
        LOG.infov("Created ECS cluster: {0} in {1}", name, region);
        return cluster;
    }

    public List<EcsCluster> describeClusters(List<String> clusterIds, String region) {
        if (clusterIds == null || clusterIds.isEmpty()) {
            return List.of(getOrCreateDefaultCluster(region));
        }
        List<EcsCluster> result = new ArrayList<>();
        for (String id : clusterIds) {
            EcsCluster cluster = resolveCluster(id, region);
            if (cluster != null) {
                result.add(cluster);
            }
        }
        return result;
    }

    public List<String> listClusters(String region) {
        String prefix = region + "::";
        return clusters.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(e -> e.getValue().getClusterArn())
                .toList();
    }

    public EcsCluster deleteCluster(String clusterId, String region) {
        EcsCluster cluster = resolveClusterOrThrow(clusterId, region);
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
        EcsCluster cluster = resolveClusterOrThrow(clusterRef, region);
        if (settings != null) {
            cluster.setSettings(settings);
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
        if (requiresCompatibilities != null && requiresCompatibilities.contains("FARGATE")) {
            if (networkMode != NetworkMode.awsvpc) {
                throw new AwsException("ClientException", "Fargate only supports network mode 'awsvpc'.", 400);
            }
            if (cpu == null) {
                throw new AwsException("ClientException", "Fargate requires that 'cpu' be defined at the task level.", 400);
            }
            if (memory == null) {
                throw new AwsException("ClientException", "Fargate requires that 'memory' be defined at the task level.", 400);
            }
            if (!isValidFargateCpuMemory(cpu, memory)) {
                throw new AwsException("ClientException", "No Fargate configuration exists for given values.", 400);
            }
        }
        int revision = latestRevisions.merge(family, 1, Integer::sum);

        TaskDefinition td = new TaskDefinition();
        td.setFamily(family);
        td.setRevision(revision);
        td.setStatus("ACTIVE");
        td.setNetworkMode(networkMode != null ? networkMode : NetworkMode.bridge);
        td.setCpu(cpu);
        td.setMemory(memory);
        td.setTaskRoleArn(taskRoleArn);
        td.setExecutionRoleArn(executionRoleArn);
        td.setContainerDefinitions(containerDefs != null ? containerDefs : List.of());
        td.setRequiresCompatibilities(requiresCompatibilities);

        if (requiresCompatibilities != null && !requiresCompatibilities.isEmpty()) {
            td.setCompatibilities(new java.util.ArrayList<>(requiresCompatibilities));
        } else {
            td.setCompatibilities(java.util.List.of("EC2"));
        }

        td.setTaskDefinitionArn(regionResolver.buildArn("ecs", region,
                "task-definition/" + family + ":" + revision));
        if (tags != null && !tags.isEmpty()) {
            td.setTags(new LinkedHashMap<>(tags));
        }

        taskDefinitions.put(family + ":" + revision, td);
        LOG.infov("Registered task definition: {0}:{1}", family, revision);
        return td;
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

    public TaskDefinition describeTaskDefinition(String taskDefinitionRef, String region) {
        return resolveTaskDefinitionOrThrow(taskDefinitionRef, region);
    }

    public List<String> listTaskDefinitions(String familyPrefix, String status) {
        return taskDefinitions.values().stream()
                .filter(td -> familyPrefix == null || td.getFamily().startsWith(familyPrefix))
                .filter(td -> status == null || status.equals(td.getStatus()))
                .map(TaskDefinition::getTaskDefinitionArn)
                .sorted()
                .toList();
    }

    public List<String> listTaskDefinitionFamilies(String familyPrefix) {
        return latestRevisions.keySet().stream()
                .filter(f -> familyPrefix == null || f.startsWith(familyPrefix))
                .sorted()
                .toList();
    }

    public TaskDefinition deregisterTaskDefinition(String taskDefinitionRef, String region) {
        TaskDefinition td = resolveTaskDefinitionOrThrow(taskDefinitionRef, region);
        td.setStatus("INACTIVE");
        taskDefinitions.put(td.getFamily() + ":" + td.getRevision(), td);
        return td;
    }

    public List<TaskDefinition> deleteTaskDefinitions(List<String> taskDefinitionRefs, String region) {
        List<TaskDefinition> deleted = new ArrayList<>();
        for (String ref : taskDefinitionRefs) {
            TaskDefinition td = resolveTaskDefinitionOrThrow(ref, region);
            if (!"INACTIVE".equals(td.getStatus())) {
                throw new AwsException("InvalidParameterException",
                        "Task definition " + ref + " must be INACTIVE before deletion.", 400);
            }
            taskDefinitions.remove(td.getFamily() + ":" + td.getRevision());
            deleted.add(td);
        }
        return deleted;
    }

    // ── Tasks ─────────────────────────────────────────────────────────────────

    public List<EcsTask> runTask(String clusterRef, String taskDefinitionRef, int count,
                                  LaunchType launchType, String group, String startedBy,
                                  List<ContainerOverride> containerOverrides, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        TaskDefinition taskDef = resolveTaskDefinitionOrThrow(taskDefinitionRef, region);
        return launchTasks(cluster, taskDef, count, launchType, group, startedBy, null,
                containerOverrides, region);
    }

    public List<EcsTask> startTask(String clusterRef, List<String> containerInstanceRefs,
                                    String taskDefinitionRef, String group, String startedBy, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        TaskDefinition taskDef = resolveTaskDefinitionOrThrow(taskDefinitionRef, region);
        List<EcsTask> result = new ArrayList<>();
        for (String instanceRef : containerInstanceRefs) {
            ContainerInstance instance = resolveContainerInstanceOrThrow(cluster.getClusterArn(), instanceRef);
            List<EcsTask> launched = launchTasks(cluster, taskDef, 1, LaunchType.EC2,
                    group, startedBy, instance.getContainerInstanceArn(), null, region);
            result.addAll(launched);
        }
        return result;
    }

    private List<EcsTask> launchTasks(EcsCluster cluster, TaskDefinition taskDef, int count,
                                       LaunchType launchType, String group, String startedBy,
                                       String containerInstanceArn,
                                       List<ContainerOverride> containerOverrides, String region) {
        // Fail loudly instead of silently launching zero containers and leaving
        // a task that looks RUNNING with nothing behind it.
        if (taskDef.getContainerDefinitions() == null || taskDef.getContainerDefinitions().isEmpty()) {
            LOG.warnv("Task definition {0} has no container definitions; refusing to launch tasks",
                    taskDef.getTaskDefinitionArn());
            throw new AwsException("ClientException",
                    "Task definition " + taskDef.getTaskDefinitionArn() + " has no container definitions.", 400);
        }
        List<EcsTask> launched = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String taskId = UUID.randomUUID().toString().replace("-", "");
            String taskArn = regionResolver.buildArn("ecs", region,
                    "task/" + cluster.getClusterName() + "/" + taskId);

            EcsTask task = new EcsTask();
            task.setTaskArn(taskArn);
            task.setClusterArn(cluster.getClusterArn());
            task.setTaskDefinitionArn(taskDef.getTaskDefinitionArn());
            task.setLaunchType(launchType != null ? launchType : LaunchType.FARGATE);
            task.setGroup(group);
            task.setStartedBy(startedBy);
            task.setLastStatus(TaskStatus.PENDING.name());
            task.setDesiredStatus(TaskStatus.RUNNING.name());
            task.setCpu(taskDef.getCpu());
            task.setMemory(taskDef.getMemory());
            task.setCreatedAt(Instant.now());
            task.setContainers(List.of());
            task.setContainerInstanceArn(containerInstanceArn);

            tasks.put(taskArn, task);

            if (dockerMode) {
                try {
                    EcsTaskHandle handle = containerManager.startTask(task, taskDef, containerOverrides, region);
                    taskHandles.put(taskArn, handle);
                    cluster.setRunningTasksCount(cluster.getRunningTasksCount() + 1);
                    LOG.infov("Started ECS task (docker): {0}", taskArn);
                    registerTaskWithLoadBalancers(task, cluster, group, region);
                } catch (Exception e) {
                    LOG.errorv("Failed to start ECS task {0}: {1}", taskArn, e.getMessage());
                    task.setLastStatus(TaskStatus.STOPPED.name());
                    task.setDesiredStatus(TaskStatus.STOPPED.name());
                    task.setStoppedReason("Failed to start: " + e.getMessage());
                    task.setStoppedAt(Instant.now());
                }
            } else {
                task.setLastStatus(TaskStatus.RUNNING.name());
                task.setStartedAt(Instant.now());
                cluster.setRunningTasksCount(cluster.getRunningTasksCount() + 1);
                LOG.infov("Started ECS task (mock): {0}", taskArn);
            }

            launched.add(task);
        }
        persistCluster(region, cluster);
        return launched;
    }

    public EcsTask stopTask(String clusterRef, String taskRef, String reason, String region) {
        EcsTask task = resolveTaskOrThrow(taskRef, region);
        if (TaskStatus.STOPPED.name().equals(task.getLastStatus())) {
            return task;
        }
        task.setDesiredStatus(TaskStatus.STOPPED.name());
        task.setLastStatus(TaskStatus.STOPPING.name());
        task.setStoppedReason(reason != null ? reason : "Stopped by user");

        deregisterTaskFromLoadBalancers(task, region);

        Map<String, Integer> exitCodes = Map.of();
        if (dockerMode) {
            EcsTaskHandle handle = taskHandles.remove(task.getTaskArn());
            exitCodes = containerManager.stopTaskAndCollectExitCodes(handle);
        }

        task.setLastStatus(TaskStatus.STOPPED.name());
        task.setStoppedAt(Instant.now());
        if (task.getContainers() != null) {
            final Map<String, Integer> codes = exitCodes;
            task.getContainers().forEach(c -> {
                c.setLastStatus("STOPPED");
                Integer code = codes.get(c.getName());
                if (code != null) {
                    c.setExitCode(code);
                }
            });
        }

        EcsCluster cluster = resolveClusterByArn(task.getClusterArn());
        if (cluster != null && cluster.getRunningTasksCount() > 0) {
            cluster.setRunningTasksCount(cluster.getRunningTasksCount() - 1);
            persistCluster(region, cluster);
        }

        LOG.infov("Stopped ECS task: {0}", task.getTaskArn());
        return task;
    }

    /** Registers a freshly-started task's containers as ELBv2 targets if its service is load-balanced. */
    private void registerTaskWithLoadBalancers(EcsTask task, EcsCluster cluster, String group, String region) {
        if (group == null) {
            return;
        }
        EcsServiceModel svc = services.get(serviceKey(region, cluster.getClusterName(), group));
        if (svc != null && !svc.getLoadBalancers().isEmpty()) {
            lbRegistrar.registerTask(task, svc, region);
        }
    }

    /** Deregisters a stopping task's containers from any ELBv2 target groups its service declared. */
    private void deregisterTaskFromLoadBalancers(EcsTask task, String region) {
        // Gated on dockerMode for symmetry with the register hook (inside launchTasks'
        // dockerMode branch): mock-mode tasks have no containers and never registered.
        if (!dockerMode || task.getGroup() == null) {
            return;
        }
        EcsCluster cluster = resolveClusterByArn(task.getClusterArn());
        if (cluster == null) {
            return;
        }
        EcsServiceModel svc = services.get(serviceKey(region, cluster.getClusterName(), task.getGroup()));
        if (svc != null && !svc.getLoadBalancers().isEmpty()) {
            lbRegistrar.deregisterTask(task, svc, region);
        }
    }

    public List<EcsTask> describeTasks(String clusterRef, List<String> taskRefs, String region) {
        List<EcsTask> result = new ArrayList<>();
        for (String ref : taskRefs) {
            EcsTask task = resolveTask(ref, region);
            if (task != null) {
                result.add(task);
            }
        }
        return result;
    }

    public List<String> listTasks(String clusterRef, String family, String desiredStatus,
                                   String serviceName, String region) {
        String clusterArn = clusterRef != null
                ? resolveClusterOrDefault(clusterRef, region).getClusterArn()
                : null;

        return tasks.values().stream()
                .filter(t -> clusterArn == null || t.getClusterArn().equals(clusterArn))
                .filter(t -> family == null || t.getTaskDefinitionArn().contains("/" + family + ":"))
                .filter(t -> desiredStatus == null || desiredStatus.equals(t.getDesiredStatus()))
                .filter(t -> serviceName == null || serviceName.equals(t.getGroup()))
                .map(EcsTask::getTaskArn)
                .toList();
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
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        resolveTaskDefinitionOrThrow(taskDefinition, region);

        String key = serviceKey(region, cluster.getClusterName(), serviceName);
        if (services.containsKey(key)) {
            EcsServiceModel existingSvc = services.get(key);

            if ("ACTIVE".equals(existingSvc.getStatus())) {
                throw new AwsException("InvalidParameterException",
                        "Creation of service was not idempotent.", 400);
            }
        }

        EcsServiceModel svc = new EcsServiceModel();
        svc.setServiceArn(regionResolver.buildArn("ecs", region,
                "service/" + cluster.getClusterName() + "/" + serviceName));
        svc.setServiceName(serviceName);
        svc.setClusterArn(cluster.getClusterArn());
        svc.setTaskDefinition(taskDefinition);
        svc.setLaunchType(launchType != null ? launchType : LaunchType.FARGATE);
        if (desiredCount < 0) {
            throw new AwsException("InvalidParameterException", "desiredCount cannot be a negative number.", 400);
        }
        svc.setDesiredCount(desiredCount);
        svc.setLoadBalancers(loadBalancers);
        svc.setNetworkConfiguration(networkConfiguration);
        svc.setStatus("ACTIVE");
        svc.setCreatedAt(Instant.now());
        if (tags != null && !tags.isEmpty()) {
            svc.setTags(new LinkedHashMap<>(tags));
        }

        services.put(key, svc);
        cluster.setActiveServicesCount(cluster.getActiveServicesCount() + 1);
        persistCluster(region, cluster);
        recordServiceDeployment(svc, taskDefinition, region);
        LOG.infov("Created ECS service: {0} in cluster {1}", serviceName, cluster.getClusterName());
        return svc;
    }

    public EcsServiceModel updateService(String clusterRef, String serviceName, String taskDefinition,
                                          Integer desiredCount, NetworkConfiguration networkConfiguration,
                                          String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);

        serviceName = extractServiceName(serviceName);

        String key = serviceKey(region, cluster.getClusterName(), serviceName);
        EcsServiceModel svc = services.get(key);
        if (svc == null) {
            throw new AwsException("ServiceNotFoundException", "Service " + serviceName + " not found.", 404);
        }
        if ("INACTIVE".equals(svc.getStatus())) {
            throw new AwsException("ServiceNotActiveException",
                    "Service " + serviceName + " is not active.", 400);
        }
        if (desiredCount != null) {
            if (desiredCount < 0) {
                throw new AwsException("InvalidParameterException", "desiredCount cannot be a negative number.", 400);
            }
            svc.setDesiredCount(desiredCount);
        }
        if (networkConfiguration != null) {
            svc.setNetworkConfiguration(networkConfiguration);
        }
        if (taskDefinition != null) {
            resolveTaskDefinitionOrThrow(taskDefinition, region);
            svc.setTaskDefinition(taskDefinition);
            recordServiceDeployment(svc, taskDefinition, region);
        }
        services.put(key, svc);
        return svc;
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
                .filter(t -> t.getClusterArn().equals(cluster.getClusterArn()))
                .filter(t -> svc.getServiceArn().equals(t.getGroup())
                        || svc.getServiceName().equals(t.getGroup()))
                .filter(t -> !TaskStatus.STOPPED.name().equals(t.getLastStatus()))
                .forEach(t -> {
                    try {
                        stopTask(cluster.getClusterName(), t.getTaskArn(), "Service deleted", region);
                    } catch (Exception e) {
                        LOG.warnv("Failed to stop task {0} on service delete: {1}",
                                t.getTaskArn(), e.getMessage());
                    }
                });
        return svc;
    }

    public List<EcsServiceModel> describeServices(String clusterRef, List<String> serviceIds, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        List<EcsServiceModel> result = new ArrayList<>();
        for (String id : serviceIds) {
            EcsServiceModel svc = resolveService(cluster.getClusterName(), id, region);
            if (svc != null) {
                result.add(svc);
            }
        }
        return result;
    }

    public List<String> listServices(String clusterRef, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        String prefix = serviceKeyPrefix(region, cluster.getClusterName());
        return services.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .filter(e -> !"INACTIVE".equals(e.getValue().getStatus()))
                .map(e -> e.getValue().getServiceArn())
                .toList();
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

    public Map.Entry<String, String> putAccountSetting(String name, String value) {
        accountSettings.put(name, value);
        return Map.entry(name, value);
    }

    public Map.Entry<String, String> putAccountSettingDefault(String name, String value) {
        accountSettings.put(name, value);
        return Map.entry(name, value);
    }

    public Map.Entry<String, String> deleteAccountSetting(String name) {
        String removed = accountSettings.remove(name);
        return Map.entry(name, removed != null ? removed : "");
    }

    public List<Map.Entry<String, String>> listAccountSettings(String filterName, String filterValue) {
        return accountSettings.entrySet().stream()
                .filter(e -> filterName == null || filterName.equals(e.getKey()))
                .filter(e -> filterValue == null || filterValue.equals(e.getValue()))
                .toList();
    }

    // ── Attributes ────────────────────────────────────────────────────────────

    public List<Attribute> putAttributes(String clusterRef, List<Attribute> attrs, String region) {
        List<Attribute> stored = new ArrayList<>();
        for (Attribute attr : attrs) {
            String targetId = attr.targetId();
            List<Attribute> existing = attributes.computeIfAbsent(targetId, k -> new ArrayList<>());
            existing.removeIf(a -> a.name().equals(attr.name()));
            existing.add(attr);
            attributes.put(targetId, existing);
            stored.add(attr);
        }
        return stored;
    }

    public List<Attribute> deleteAttributes(String clusterRef, List<Attribute> attrs, String region) {
        List<Attribute> deleted = new ArrayList<>();
        for (Attribute attr : attrs) {
            String targetId = attr.targetId();
            List<Attribute> existing = attributes.get(targetId);
            if (existing != null) {
                existing.removeIf(a -> {
                    if (a.name().equals(attr.name())) {
                        deleted.add(a);
                        return true;
                    }
                    return false;
                });
                if (existing.isEmpty()) {
                    attributes.remove(targetId);
                } else {
                    attributes.put(targetId, existing);
                }
            }
        }
        return deleted;
    }

    public List<Attribute> listAttributes(String clusterRef, String targetType,
                                           String attributeName, String attributeValue, String region) {
        return attributes.values().stream()
                .flatMap(List::stream)
                .filter(a -> targetType == null || targetType.equals(a.targetType()))
                .filter(a -> attributeName == null || attributeName.equals(a.name()))
                .filter(a -> attributeValue == null || attributeValue.equals(a.value()))
                .toList();
    }

    // ── Container Instances ───────────────────────────────────────────────────

    public ContainerInstance registerContainerInstance(String clusterRef, String instanceIdentityDocument,
                                                        List<Attribute> instanceAttributes, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        String instanceId = "i-floci-" + UUID.randomUUID().toString().substring(0, 8);
        String instanceArn = regionResolver.buildArn("ecs", region,
                "container-instance/" + cluster.getClusterName() + "/" + UUID.randomUUID());

        ContainerInstance instance = new ContainerInstance();
        instance.setContainerInstanceArn(instanceArn);
        instance.setEc2InstanceId(instanceId);
        instance.setStatus("ACTIVE");
        instance.setAgentVersion("1.0.0");
        instance.setAgentConnected(true);
        if (instanceAttributes != null) {
            instance.setAttributes(new ArrayList<>(instanceAttributes));
        }

        String key = containerInstanceKey(cluster.getClusterArn(), instanceArn);
        containerInstances.put(key, instance);
        cluster.setRegisteredContainerInstancesCount(cluster.getRegisteredContainerInstancesCount() + 1);
        persistCluster(region, cluster);
        LOG.infov("Registered container instance: {0} in cluster {1}", instanceArn, cluster.getClusterName());
        return instance;
    }

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

        String key = containerInstanceKey(cluster.getClusterArn(), instance.getContainerInstanceArn());
        containerInstances.remove(key);
        instance.setStatus("INACTIVE");
        cluster.setRegisteredContainerInstancesCount(
                Math.max(0, cluster.getRegisteredContainerInstancesCount() - 1));
        persistCluster(region, cluster);
        return instance;
    }

    public List<ContainerInstance> describeContainerInstances(String clusterRef,
                                                               List<String> instanceRefs, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        List<ContainerInstance> result = new ArrayList<>();
        for (String ref : instanceRefs) {
            ContainerInstance instance = resolveContainerInstance(cluster.getClusterArn(), ref);
            if (instance != null) {
                result.add(instance);
            }
        }
        return result;
    }

    public List<String> listContainerInstances(String clusterRef, String status, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        String prefix = containerInstanceKey(cluster.getClusterArn(), "");
        return containerInstances.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .filter(e -> status == null || status.equals(e.getValue().getStatus()))
                .map(e -> e.getValue().getContainerInstanceArn())
                .toList();
    }

    public ContainerInstance updateContainerAgent(String clusterRef, String instanceRef, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        return resolveContainerInstanceOrThrow(cluster.getClusterArn(), instanceRef);
    }

    public List<ContainerInstance> updateContainerInstancesState(String clusterRef,
                                                                  List<String> instanceRefs,
                                                                  String status, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        List<ContainerInstance> updated = new ArrayList<>();
        for (String ref : instanceRefs) {
            ContainerInstance instance = resolveContainerInstanceOrThrow(cluster.getClusterArn(), ref);
            instance.setStatus(status);
            updated.add(instance);
        }
        return updated;
    }

    // ── Capacity Providers ────────────────────────────────────────────────────

    public CapacityProvider createCapacityProvider(String name, Map<String, Object> asgProvider,
                                                    Map<String, String> tags, String region) {
        if (capacityProviders.containsKey(name)) {
            throw new AwsException("InvalidParameterException",
                    "A capacity provider with name " + name + " already exists.", 400);
        }
        CapacityProvider cp = new CapacityProvider();
        cp.setName(name);
        cp.setCapacityProviderArn(regionResolver.buildArn("ecs", region, "capacity-provider/" + name));
        cp.setStatus("ACTIVE");
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
        capacityProviders.put(cp.getName(), cp);
        return cp;
    }

    public CapacityProvider deleteCapacityProvider(String nameOrArn) {
        CapacityProvider cp = resolveCapacityProviderOrThrow(nameOrArn);
        cp.setStatus("DELETE_IN_PROGRESS");
        capacityProviders.remove(cp.getName());
        return cp;
    }

    public List<CapacityProvider> describeCapacityProviders(List<String> providers) {
        if (providers == null || providers.isEmpty()) {
            List<CapacityProvider> result = new ArrayList<>(List.of(builtInFargate(), builtInFargateSpot()));
            result.addAll(capacityProviders.values());
            return result;
        }
        return providers.stream()
                .map(p -> {
                    if ("FARGATE".equals(p)) { return builtInFargate(); }
                    if ("FARGATE_SPOT".equals(p)) { return builtInFargateSpot(); }
                    return capacityProviders.getOrDefault(p,
                            capacityProviders.values().stream()
                                    .filter(cp -> cp.getCapacityProviderArn().equals(p))
                                    .findFirst().orElse(null));
                })
                .filter(cp -> cp != null)
                .toList();
    }

    private CapacityProvider builtInFargate() {
        CapacityProvider cp = new CapacityProvider();
        cp.setName("FARGATE");
        cp.setStatus("ACTIVE");
        return cp;
    }

    private CapacityProvider builtInFargateSpot() {
        CapacityProvider cp = new CapacityProvider();
        cp.setName("FARGATE_SPOT");
        cp.setStatus("ACTIVE");
        return cp;
    }

    // ── Task Sets ─────────────────────────────────────────────────────────────

    public TaskSet createTaskSet(String clusterRef, String serviceRef, String taskDefinitionRef,
                                  LaunchType launchType, double scaleValue, String scaleUnit,
                                  String externalId, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        EcsServiceModel svc = resolveServiceOrThrow(cluster.getClusterName(), serviceRef, region);
        TaskDefinition taskDef = resolveTaskDefinitionOrThrow(taskDefinitionRef, region);

        String setId = "ecs-svc/" + UUID.randomUUID().toString().replace("-", "");
        String taskSetArn = regionResolver.buildArn("ecs", region, "task-set/"
                + cluster.getClusterName() + "/" + svc.getServiceName() + "/" + setId);

        TaskSet ts = new TaskSet();
        ts.setId(setId);
        ts.setTaskSetArn(taskSetArn);
        ts.setServiceArn(svc.getServiceArn());
        ts.setClusterArn(cluster.getClusterArn());
        ts.setTaskDefinition(taskDef.getTaskDefinitionArn());
        ts.setStatus("ACTIVE");
        ts.setScaleValue(scaleValue);
        ts.setScaleUnit(scaleUnit != null ? scaleUnit : "PERCENT");
        ts.setLaunchType(launchType != null ? launchType : LaunchType.FARGATE);
        ts.setExternalId(externalId);
        ts.setStabilityStatus("STEADY_STATE");
        ts.setCreatedAt(Instant.now());
        ts.setUpdatedAt(Instant.now());

        taskSets.put(taskSetArn, ts);
        return ts;
    }

    public TaskSet updateTaskSet(String clusterRef, String serviceRef, String taskSetRef,
                                  double scaleValue, String scaleUnit, String region) {
        TaskSet ts = resolveTaskSetOrThrow(taskSetRef);
        ts.setScaleValue(scaleValue);
        ts.setScaleUnit(scaleUnit != null ? scaleUnit : "PERCENT");
        ts.setUpdatedAt(Instant.now());
        return ts;
    }

    public TaskSet deleteTaskSet(String clusterRef, String serviceRef, String taskSetRef,
                                  boolean force, String region) {
        TaskSet ts = resolveTaskSetOrThrow(taskSetRef);
        ts.setStatus("DRAINING");
        taskSets.remove(ts.getTaskSetArn());
        return ts;
    }

    public List<TaskSet> describeTaskSets(String clusterRef, String serviceRef,
                                           List<String> taskSetRefs, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        EcsServiceModel svc = resolveServiceOrThrow(cluster.getClusterName(), serviceRef, region);
        Stream<TaskSet> stream = taskSets.values().stream()
                .filter(ts -> ts.getServiceArn().equals(svc.getServiceArn()));
        if (taskSetRefs != null && !taskSetRefs.isEmpty()) {
            stream = stream.filter(ts -> taskSetRefs.contains(ts.getTaskSetArn())
                    || taskSetRefs.contains(ts.getId()));
        }
        return stream.toList();
    }

    public TaskSet updateServicePrimaryTaskSet(String clusterRef, String serviceRef,
                                                String primaryTaskSetRef, String region) {
        EcsCluster cluster = resolveClusterOrDefault(clusterRef, region);
        EcsServiceModel svc = resolveServiceOrThrow(cluster.getClusterName(), serviceRef, region);
        TaskSet primary = resolveTaskSetOrThrow(primaryTaskSetRef);

        taskSets.values().stream()
                .filter(ts -> ts.getServiceArn().equals(svc.getServiceArn()))
                .forEach(ts -> ts.setStatus(ts.getTaskSetArn().equals(primary.getTaskSetArn())
                        ? "PRIMARY" : "ACTIVE"));

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

    private void recordServiceDeployment(EcsServiceModel svc, String taskDefinition, String region) {
        String deploymentId = UUID.randomUUID().toString().replace("-", "");
        String deploymentArn = regionResolver.buildArn("ecs", region,
                "service-deployment/" + deploymentId);
        String revisionId = UUID.randomUUID().toString().replace("-", "");
        String revisionArn = regionResolver.buildArn("ecs", region,
                "service-revision/" + revisionId);

        ServiceDeployment deployment = new ServiceDeployment();
        deployment.setServiceDeploymentArn(deploymentArn);
        deployment.setServiceArn(svc.getServiceArn());
        deployment.setClusterArn(svc.getClusterArn());
        deployment.setTaskDefinition(taskDefinition);
        deployment.setStatus("SUCCESSFUL");
        deployment.setCreatedAt(Instant.now());
        deployment.setUpdatedAt(Instant.now());
        serviceDeployments.put(deploymentArn, deployment);

        ServiceRevision revision = new ServiceRevision();
        revision.setServiceRevisionArn(revisionArn);
        revision.setServiceArn(svc.getServiceArn());
        revision.setClusterArn(svc.getClusterArn());
        revision.setTaskDefinition(taskDefinition);
        revision.setLaunchType(svc.getLaunchType());
        revision.setCreatedAt(Instant.now());
        serviceRevisions.put(revisionArn, revision);
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
        reconcileTasks();
        reconcileServices();
    }

    private void reconcileTasks() {
        for (String taskArn : taskHandles.keySet()) {
            try {
                reconcileTask(taskArn);
            } catch (Exception e) {
                LOG.debugv("Error reconciling ECS task {0}: {1}", taskArn, e.getMessage());
            }
        }
    }

    private void reconcileTask(String taskArn) {
        EcsTask task = tasks.get(taskArn);
        if (task == null || !TaskStatus.RUNNING.name().equals(task.getLastStatus())) {
            return;
        }

        EcsTaskHandle handle = taskHandles.get(taskArn);
        if (handle == null) {
            return;
        }

        // Inspect every container; abort if any are still running.
        Map<String, Integer> exitCodes = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : handle.getContainerIds().entrySet()) {
            Integer code = containerManager.getExitCodeIfStopped(entry.getValue());
            if (code == null) {
                return;
            }
            exitCodes.put(entry.getKey(), code);
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
            });
        }

        task.setLastStatus(TaskStatus.STOPPED.name());
        task.setDesiredStatus(TaskStatus.STOPPED.name());
        task.setStoppedAt(Instant.now());
        task.setStoppedReason("Essential container in task exited");

        EcsCluster cluster = resolveClusterByArn(task.getClusterArn());
        if (cluster != null && cluster.getRunningTasksCount() > 0) {
            cluster.setRunningTasksCount(cluster.getRunningTasksCount() - 1);
        }

        LOG.infov("ECS task {0} reconciled to STOPPED (all containers exited)", taskArn);
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

    private void reconcileService(String key, EcsServiceModel svc) {
        if (!"ACTIVE".equals(svc.getStatus())) {
            return;
        }

        String region = extractRegionFromServiceKey(key);
        String clusterName = extractClusterNameFromServiceKey(key);

        long running = tasks.values().stream()
                .filter(t -> t.getClusterArn().endsWith(":cluster/" + clusterName))
                .filter(t -> svc.getServiceArn().equals(t.getGroup())
                        || svc.getServiceName().equals(t.getGroup()))
                .filter(t -> TaskStatus.RUNNING.name().equals(t.getLastStatus()))
                .count();

        svc.setRunningCount((int) running);

        if (running < svc.getDesiredCount()) {
            int toStart = svc.getDesiredCount() - (int) running;
            for (int i = 0; i < toStart; i++) {
                try {
                    List<EcsTask> launched = runTask(clusterName, svc.getTaskDefinition(), 1,
                            svc.getLaunchType(), svc.getServiceName(), "ecs-svc", null, region);
                    LOG.infov("Service reconciler started task {0} for service {1}",
                            launched.getFirst().getTaskArn(), svc.getServiceName());
                } catch (Exception e) {
                    LOG.warnv("Service reconciler failed to start task for {0}: {1}",
                            svc.getServiceName(), e.getMessage());
                }
            }
        } else if (running > svc.getDesiredCount()) {
            int toStop = (int) running - svc.getDesiredCount();
            tasks.values().stream()
                    .filter(t -> t.getClusterArn().endsWith(":cluster/" + clusterName))
                    .filter(t -> svc.getServiceName().equals(t.getGroup()))
                    .filter(t -> TaskStatus.RUNNING.name().equals(t.getLastStatus()))
                    .limit(toStop)
                    .forEach(t -> {
                        try {
                            stopTask(clusterName, t.getTaskArn(), "Service scale-in", region);
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

    private TaskSet resolveTaskSetOrThrow(String ref) {
        TaskSet ts = taskSets.get(ref);
        if (ts != null) { return ts; }
        ts = taskSets.values().stream()
                .filter(t -> t.getId().equals(ref))
                .findFirst().orElse(null);
        if (ts == null) {
            throw new AwsException("InvalidParameterException", "Task set not found: " + ref, 400);
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
}
