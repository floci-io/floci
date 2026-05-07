package io.github.hectorvent.floci.services.codedeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.codedeploy.model.Application;
import io.github.hectorvent.floci.services.codedeploy.model.Deployment;
import io.github.hectorvent.floci.services.codedeploy.model.DeploymentConfig;
import io.github.hectorvent.floci.services.codedeploy.model.DeploymentGroup;
import io.github.hectorvent.floci.services.codedeploy.model.OnPremisesInstance;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.ecs.model.TaskSet;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.ssm.SsmCommandService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@ApplicationScoped
public class CodeDeployService {

    private static final Logger LOG = Logger.getLogger(CodeDeployService.class);

    private final LambdaService lambdaService;
    private final EcsService ecsService;
    private final ElbV2Service elbV2Service;
    private final SsmCommandService ssmCommandService;
    private final Ec2Service ec2Service;
    private final ObjectMapper mapper;
    private final ObjectMapper yamlMapper;

    @Inject
    public CodeDeployService(LambdaService lambdaService, EcsService ecsService,
                             ElbV2Service elbV2Service, SsmCommandService ssmCommandService,
                             Ec2Service ec2Service, ObjectMapper mapper) {
        this.lambdaService = lambdaService;
        this.ecsService = ecsService;
        this.elbV2Service = elbV2Service;
        this.ssmCommandService = ssmCommandService;
        this.ec2Service = ec2Service;
        this.mapper = mapper;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    // key: region -> name -> application
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Application>> applications = new ConcurrentHashMap<>();
    // key: region -> appName -> groupName -> group
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, DeploymentGroup>>> deploymentGroups = new ConcurrentHashMap<>();
    // key: region -> configName -> config (pre-seeded with built-ins)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, DeploymentConfig>> deploymentConfigs = new ConcurrentHashMap<>();
    // key: resourceArn -> tags
    private final ConcurrentHashMap<String, Map<String, String>> tags = new ConcurrentHashMap<>();
    // key: region -> deploymentId -> Deployment
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Deployment>> deployments = new ConcurrentHashMap<>();
    // key: region -> deploymentId -> targetId -> DeploymentTarget wrapper
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, Map<String, Object>>>> deploymentTargets = new ConcurrentHashMap<>();
    // key: region -> instanceName -> OnPremisesInstance
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, OnPremisesInstance>> onPremisesInstances = new ConcurrentHashMap<>();
    // key: lifecycleEventHookExecutionId -> CompletableFuture<status>
    private final ConcurrentHashMap<String, CompletableFuture<String>> hookFutures = new ConcurrentHashMap<>();
    // key: deploymentId -> stop flag
    private final ConcurrentHashMap<String, AtomicBoolean> stopFlags = new ConcurrentHashMap<>();

    private static final class AppSpecInfo {
        String functionName;
        String aliasName;
        String currentVersion;
        String targetVersion;
        String beforeAllowTraffic;
        String afterAllowTraffic;
    }

    private static final class ServerAppSpecInfo {
        String os;
        Map<String, List<Map<String, Object>>> hooks; // hookName → [{location, timeout, runas}]
    }

    private static final class EcsAppSpecInfo {
        String taskDefinition;
        String containerName;
        int containerPort;
        String beforeInstall;
        String afterInstall;
        String beforeAllowTraffic;
        String afterAllowTraffic;
    }

    private static final class TrafficRoutingInfo {
        final String type;
        final int percentage;
        final int intervalSeconds;

        TrafficRoutingInfo(String type, int percentage, int intervalSeconds) {
            this.type = type;
            this.percentage = percentage;
            this.intervalSeconds = intervalSeconds;
        }
    }

    private static final List<String> BUILT_IN_CONFIG_NAMES = List.of(
            "CodeDeployDefault.OneAtATime",
            "CodeDeployDefault.HalfAtATime",
            "CodeDeployDefault.AllAtOnce",
            "CodeDeployDefault.LambdaAllAtOnce",
            "CodeDeployDefault.LambdaCanary10Percent5Minutes",
            "CodeDeployDefault.LambdaCanary10Percent10Minutes",
            "CodeDeployDefault.LambdaCanary10Percent15Minutes",
            "CodeDeployDefault.LambdaCanary10Percent30Minutes",
            "CodeDeployDefault.LambdaLinear10PercentEvery1Minute",
            "CodeDeployDefault.LambdaLinear10PercentEvery2Minutes",
            "CodeDeployDefault.LambdaLinear10PercentEvery3Minutes",
            "CodeDeployDefault.LambdaLinear10PercentEvery10Minutes",
            "CodeDeployDefault.ECSAllAtOnce",
            "CodeDeployDefault.ECSCanary10Percent5Minutes",
            "CodeDeployDefault.ECSCanary10Percent15Minutes",
            "CodeDeployDefault.ECSLinear10PercentEvery1Minutes",
            "CodeDeployDefault.ECSLinear10PercentEvery3Minutes"
    );

    private Map<String, Application> applicationsFor(String region) {
        return applications.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private Map<String, ConcurrentHashMap<String, DeploymentGroup>> deploymentGroupsFor(String region) {
        return deploymentGroups.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private Map<String, DeploymentConfig> deploymentConfigsFor(String region) {
        return deploymentConfigs.computeIfAbsent(region, r -> {
            ConcurrentHashMap<String, DeploymentConfig> store = new ConcurrentHashMap<>();
            double now = Instant.now().toEpochMilli() / 1000.0;
            for (String name : BUILT_IN_CONFIG_NAMES) {
                store.put(name, buildBuiltInConfig(name, now));
            }
            return store;
        });
    }

    private DeploymentConfig buildBuiltInConfig(String name, double now) {
        DeploymentConfig cfg = new DeploymentConfig();
        cfg.setDeploymentConfigId("d-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase());
        cfg.setDeploymentConfigName(name);
        cfg.setCreateTime(now);

        if (name.startsWith("CodeDeployDefault.Lambda")) {
            cfg.setComputePlatform("Lambda");
            if (name.equals("CodeDeployDefault.LambdaAllAtOnce")) {
                cfg.setTrafficRoutingConfig(Map.of("type", "AllAtOnce"));
            } else if (name.contains("Canary")) {
                int pct = 10;
                int minutes = extractMinutes(name);
                cfg.setTrafficRoutingConfig(Map.of("type", "TimeBasedCanary",
                        "timeBasedCanary", Map.of("canaryPercentage", pct, "canaryInterval", minutes)));
            } else if (name.contains("Linear")) {
                int pct = 10;
                int minutes = extractMinutes(name);
                cfg.setTrafficRoutingConfig(Map.of("type", "TimeBasedLinear",
                        "timeBasedLinear", Map.of("linearPercentage", pct, "linearInterval", minutes)));
            }
        } else if (name.startsWith("CodeDeployDefault.ECS")) {
            cfg.setComputePlatform("ECS");
            if (name.equals("CodeDeployDefault.ECSAllAtOnce")) {
                cfg.setTrafficRoutingConfig(Map.of("type", "AllAtOnce"));
            } else if (name.contains("Canary")) {
                int pct = 10;
                int minutes = extractMinutes(name);
                cfg.setTrafficRoutingConfig(Map.of("type", "TimeBasedCanary",
                        "timeBasedCanary", Map.of("canaryPercentage", pct, "canaryInterval", minutes)));
            } else if (name.contains("Linear")) {
                int pct = 10;
                int minutes = extractMinutes(name);
                cfg.setTrafficRoutingConfig(Map.of("type", "TimeBasedLinear",
                        "timeBasedLinear", Map.of("linearPercentage", pct, "linearInterval", minutes)));
            }
        } else {
            cfg.setComputePlatform("Server");
            if (name.equals("CodeDeployDefault.AllAtOnce")) {
                cfg.setMinimumHealthyHosts(Map.of("type", "FLEET_PERCENT", "value", 0));
            } else if (name.equals("CodeDeployDefault.HalfAtATime")) {
                cfg.setMinimumHealthyHosts(Map.of("type", "FLEET_PERCENT", "value", 50));
            } else {
                cfg.setMinimumHealthyHosts(Map.of("type", "HOST_COUNT", "value", 1));
            }
        }
        return cfg;
    }

    private int extractMinutes(String name) {
        // e.g. "…Every1Minute" -> 1, "…5Minutes" -> 5
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)Minute").matcher(name);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    // ---- Applications ----

    public Application createApplication(String region, String name, String computePlatform,
                                          List<Map<String, String>> tags) {
        Map<String, Application> store = applicationsFor(region);
        if (store.containsKey(name)) {
            throw new AwsException("ApplicationAlreadyExistsException",
                    "Application already exists: " + name, 400);
        }
        Application app = new Application();
        app.setApplicationId(UUID.randomUUID().toString());
        app.setApplicationName(name);
        app.setCreateTime(Instant.now().toEpochMilli() / 1000.0);
        app.setLinkedToGitHub(false);
        app.setComputePlatform(computePlatform != null ? computePlatform : "Server");
        store.put(name, app);

        if (tags != null && !tags.isEmpty()) {
            String arn = applicationArn(region, name);
            applyTags(arn, tags);
        }
        return app;
    }

    public Application getApplication(String region, String name) {
        Application app = applicationsFor(region).get(name);
        if (app == null) {
            throw new AwsException("ApplicationDoesNotExistException",
                    "Application does not exist: " + name, 400);
        }
        return app;
    }

    public void updateApplication(String region, String currentName, String newName) {
        Map<String, Application> store = applicationsFor(region);
        Application app = store.get(currentName);
        if (app == null) {
            throw new AwsException("ApplicationDoesNotExistException",
                    "Application does not exist: " + currentName, 400);
        }
        if (newName != null && !newName.equals(currentName)) {
            if (store.containsKey(newName)) {
                throw new AwsException("ApplicationAlreadyExistsException",
                        "Application already exists: " + newName, 400);
            }
            store.remove(currentName);
            app.setApplicationName(newName);
            store.put(newName, app);
        }
    }

    public void deleteApplication(String region, String name) {
        if (applicationsFor(region).remove(name) == null) {
            throw new AwsException("ApplicationDoesNotExistException",
                    "Application does not exist: " + name, 400);
        }
    }

    public List<String> listApplications(String region) {
        return new ArrayList<>(applicationsFor(region).keySet());
    }

    public List<Application> batchGetApplications(String region, List<String> names) {
        Map<String, Application> store = applicationsFor(region);
        return names.stream()
                .map(n -> {
                    Application a = store.get(n);
                    if (a == null) {
                        throw new AwsException("ApplicationDoesNotExistException",
                                "Application does not exist: " + n, 400);
                    }
                    return a;
                })
                .collect(Collectors.toList());
    }

    // ---- Deployment Groups ----

    public DeploymentGroup createDeploymentGroup(String region, String appName, String groupName,
                                                  String deploymentConfigName, String serviceRoleArn,
                                                  Map<String, Object> fields) {
        getApplication(region, appName);
        Map<String, ConcurrentHashMap<String, DeploymentGroup>> appGroups = deploymentGroupsFor(region);
        ConcurrentHashMap<String, DeploymentGroup> groupStore = appGroups.computeIfAbsent(appName, a -> new ConcurrentHashMap<>());
        if (groupStore.containsKey(groupName)) {
            throw new AwsException("DeploymentGroupAlreadyExistsException",
                    "Deployment group already exists: " + groupName, 400);
        }

        DeploymentGroup group = new DeploymentGroup();
        group.setApplicationName(appName);
        group.setDeploymentGroupId(UUID.randomUUID().toString());
        group.setDeploymentGroupName(groupName);
        group.setDeploymentConfigName(deploymentConfigName != null ? deploymentConfigName : "CodeDeployDefault.OneAtATime");
        group.setServiceRoleArn(serviceRoleArn);
        applyGroupFields(group, fields);
        groupStore.put(groupName, group);
        return group;
    }

    public DeploymentGroup getDeploymentGroup(String region, String appName, String groupName) {
        getApplication(region, appName);
        Map<String, ConcurrentHashMap<String, DeploymentGroup>> appGroups = deploymentGroupsFor(region);
        ConcurrentHashMap<String, DeploymentGroup> groupStore = appGroups.get(appName);
        DeploymentGroup group = groupStore != null ? groupStore.get(groupName) : null;
        if (group == null) {
            throw new AwsException("DeploymentGroupDoesNotExistException",
                    "Deployment group does not exist: " + groupName, 400);
        }
        return group;
    }

    public DeploymentGroup updateDeploymentGroup(String region, String appName,
                                                  String currentGroupName, String newGroupName,
                                                  String deploymentConfigName, String serviceRoleArn,
                                                  Map<String, Object> fields) {
        DeploymentGroup group = getDeploymentGroup(region, appName, currentGroupName);
        ConcurrentHashMap<String, DeploymentGroup> groupStore = deploymentGroupsFor(region)
                .computeIfAbsent(appName, a -> new ConcurrentHashMap<>());

        if (deploymentConfigName != null) { group.setDeploymentConfigName(deploymentConfigName); }
        if (serviceRoleArn != null) { group.setServiceRoleArn(serviceRoleArn); }
        applyGroupFields(group, fields);

        if (newGroupName != null && !newGroupName.equals(currentGroupName)) {
            groupStore.remove(currentGroupName);
            group.setDeploymentGroupName(newGroupName);
            groupStore.put(newGroupName, group);
        }
        return group;
    }

    public void deleteDeploymentGroup(String region, String appName, String groupName) {
        getApplication(region, appName);
        Map<String, ConcurrentHashMap<String, DeploymentGroup>> appGroups = deploymentGroupsFor(region);
        ConcurrentHashMap<String, DeploymentGroup> groupStore = appGroups.get(appName);
        if (groupStore == null || groupStore.remove(groupName) == null) {
            throw new AwsException("DeploymentGroupDoesNotExistException",
                    "Deployment group does not exist: " + groupName, 400);
        }
    }

    public List<String> listDeploymentGroups(String region, String appName) {
        getApplication(region, appName);
        Map<String, ConcurrentHashMap<String, DeploymentGroup>> appGroups = deploymentGroupsFor(region);
        ConcurrentHashMap<String, DeploymentGroup> groupStore = appGroups.get(appName);
        return groupStore != null ? new ArrayList<>(groupStore.keySet()) : List.of();
    }

    public List<DeploymentGroup> batchGetDeploymentGroups(String region, String appName, List<String> names) {
        getApplication(region, appName);
        Map<String, ConcurrentHashMap<String, DeploymentGroup>> appGroups = deploymentGroupsFor(region);
        ConcurrentHashMap<String, DeploymentGroup> groupStore = appGroups.get(appName);
        if (groupStore == null) {
            return List.of();
        }
        return names.stream()
                .map(groupStore::get)
                .filter(g -> g != null)
                .collect(Collectors.toList());
    }

    // ---- Deployment Configs ----

    public DeploymentConfig createDeploymentConfig(String region, String name,
                                                    Map<String, Object> minimumHealthyHosts,
                                                    String computePlatform,
                                                    Map<String, Object> trafficRoutingConfig,
                                                    Map<String, Object> zonalConfig) {
        Map<String, DeploymentConfig> store = deploymentConfigsFor(region);
        if (store.containsKey(name)) {
            throw new AwsException("DeploymentConfigAlreadyExistsException",
                    "Deployment configuration already exists: " + name, 400);
        }
        if (name.startsWith("CodeDeployDefault.")) {
            throw new AwsException("InvalidDeploymentConfigNameException",
                    "Cannot create a deployment configuration starting with 'CodeDeployDefault.'", 400);
        }
        DeploymentConfig cfg = new DeploymentConfig();
        cfg.setDeploymentConfigId(UUID.randomUUID().toString());
        cfg.setDeploymentConfigName(name);
        cfg.setMinimumHealthyHosts(minimumHealthyHosts);
        cfg.setCreateTime(Instant.now().toEpochMilli() / 1000.0);
        cfg.setComputePlatform(computePlatform != null ? computePlatform : "Server");
        cfg.setTrafficRoutingConfig(trafficRoutingConfig);
        cfg.setZonalConfig(zonalConfig);
        store.put(name, cfg);
        return cfg;
    }

    public DeploymentConfig getDeploymentConfig(String region, String name) {
        DeploymentConfig cfg = deploymentConfigsFor(region).get(name);
        if (cfg == null) {
            throw new AwsException("DeploymentConfigDoesNotExistException",
                    "Deployment configuration does not exist: " + name, 400);
        }
        return cfg;
    }

    public void deleteDeploymentConfig(String region, String name) {
        if (name.startsWith("CodeDeployDefault.")) {
            throw new AwsException("InvalidDeploymentConfigNameException",
                    "Cannot delete a built-in deployment configuration.", 400);
        }
        if (deploymentConfigsFor(region).remove(name) == null) {
            throw new AwsException("DeploymentConfigDoesNotExistException",
                    "Deployment configuration does not exist: " + name, 400);
        }
    }

    public List<String> listDeploymentConfigs(String region) {
        return new ArrayList<>(deploymentConfigsFor(region).keySet());
    }

    // ---- Deployments ----

    private Map<String, Deployment> deploymentsFor(String region) {
        return deployments.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private Map<String, ConcurrentHashMap<String, Map<String, Object>>> deploymentTargetsFor(String region) {
        return deploymentTargets.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private Map<String, OnPremisesInstance> onPremisesFor(String region) {
        return onPremisesInstances.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    public String createDeployment(String region, String appName, String groupName,
                                   String configName, Map<String, Object> revision, String description) {
        Application app = getApplication(region, appName);
        DeploymentGroup group = getDeploymentGroup(region, appName, groupName);
        // Compute platform is authoritative on the Application; the deployment group may also carry it
        String computePlatform = group.getComputePlatform();
        if (computePlatform == null) {
            computePlatform = app.getComputePlatform();
        }

        if ("ECS".equals(computePlatform)) {
            return createEcsDeployment(region, appName, groupName, group, configName, revision, description);
        }

        if ("Server".equals(computePlatform)) {
            return createServerDeployment(region, appName, groupName, group, configName, revision, description);
        }

        AppSpecInfo appSpec = parseAppSpec(revision);
        String effectiveConfig = configName != null ? configName : group.getDeploymentConfigName();

        String deploymentId = generateDeploymentId();
        double now = Instant.now().toEpochMilli() / 1000.0;

        Deployment deployment = new Deployment();
        deployment.setDeploymentId(deploymentId);
        deployment.setApplicationName(appName);
        deployment.setDeploymentGroupName(groupName);
        deployment.setDeploymentConfigName(effectiveConfig);
        deployment.setStatus("Queued");
        deployment.setRevision(revision);
        deployment.setCreateTime(now);
        deployment.setDescription(description);
        deployment.setCreator("user");
        deployment.setComputePlatform("Lambda");
        deploymentsFor(region).put(deploymentId, deployment);

        // Build initial target map
        String targetId = appSpec.functionName + ":" + appSpec.aliasName;
        String targetArn = AwsArnUtils.Arn.of("lambda", region, "000000000000", "function:" + appSpec.functionName + ":" + appSpec.aliasName).toString();
        Map<String, Object> lambdaTargetMap = new ConcurrentHashMap<>();
        lambdaTargetMap.put("deploymentId", deploymentId);
        lambdaTargetMap.put("targetId", targetId);
        lambdaTargetMap.put("targetArn", targetArn);
        lambdaTargetMap.put("status", "Pending");
        lambdaTargetMap.put("lastUpdatedAt", now);
        lambdaTargetMap.put("lifecycleEvents", new CopyOnWriteArrayList<>());

        Map<String, Object> targetMap = new ConcurrentHashMap<>();
        targetMap.put("deploymentTargetType", "LambdaFunction");
        targetMap.put("lambdaTarget", lambdaTargetMap);
        ConcurrentHashMap<String, Map<String, Object>> targets = new ConcurrentHashMap<>();
        targets.put(targetId, targetMap);
        deploymentTargetsFor(region).put(deploymentId, targets);

        AtomicBoolean stopFlag = new AtomicBoolean(false);
        stopFlags.put(deploymentId, stopFlag);

        String finalEffectiveConfig = effectiveConfig;
        Thread.ofVirtual().name("codedeploy-" + deploymentId).start(
                () -> runStateMachine(region, deployment, appSpec, lambdaTargetMap, stopFlag, finalEffectiveConfig));

        return deploymentId;
    }

    public Deployment getDeployment(String region, String deploymentId) {
        Deployment d = deploymentsFor(region).get(deploymentId);
        if (d == null) {
            throw new AwsException("DeploymentDoesNotExistException",
                    "Deployment does not exist: " + deploymentId, 400);
        }
        return d;
    }

    public List<String> listDeployments(String region, String appName, String groupName, List<String> statuses) {
        return deploymentsFor(region).values().stream()
                .filter(d -> appName == null || appName.equals(d.getApplicationName()))
                .filter(d -> groupName == null || groupName.equals(d.getDeploymentGroupName()))
                .filter(d -> statuses == null || statuses.isEmpty() || statuses.contains(d.getStatus()))
                .map(Deployment::getDeploymentId)
                .collect(Collectors.toList());
    }

    public Map<String, String> stopDeployment(String region, String deploymentId) {
        Deployment d = getDeployment(region, deploymentId);
        String status = d.getStatus();
        if ("Succeeded".equals(status) || "Failed".equals(status) || "Stopped".equals(status)) {
            return Map.of("status", "Succeeded", "statusMessage", "Deployment is already in a terminal state.");
        }
        AtomicBoolean flag = stopFlags.get(deploymentId);
        if (flag != null) {
            flag.set(true);
        }
        return Map.of("status", "Pending", "statusMessage", "Stop request submitted.");
    }

    public List<Deployment> batchGetDeployments(String region, List<String> ids) {
        Map<String, Deployment> store = deploymentsFor(region);
        return ids.stream()
                .map(id -> {
                    Deployment d = store.get(id);
                    if (d == null) {
                        throw new AwsException("DeploymentDoesNotExistException",
                                "Deployment does not exist: " + id, 400);
                    }
                    return d;
                })
                .collect(Collectors.toList());
    }

    public List<String> listDeploymentTargets(String region, String deploymentId) {
        getDeployment(region, deploymentId);
        Map<String, Map<String, Object>> targets = deploymentTargetsFor(region).get(deploymentId);
        if (targets == null) {
            return List.of();
        }
        return new ArrayList<>(targets.keySet());
    }

    public List<Map<String, Object>> batchGetDeploymentTargets(String region, String deploymentId, List<String> targetIds) {
        getDeployment(region, deploymentId);
        Map<String, Map<String, Object>> targets = deploymentTargetsFor(region).get(deploymentId);
        if (targets == null) {
            return List.of();
        }
        if (targetIds.isEmpty()) {
            return new ArrayList<>(targets.values());
        }
        return targetIds.stream()
                .map(targets::get)
                .filter(t -> t != null)
                .collect(Collectors.toList());
    }

    public String putLifecycleEventHookExecutionStatus(String deploymentId, String executionId, String status) {
        CompletableFuture<String> future = hookFutures.get(executionId);
        if (future != null && !future.isDone()) {
            future.complete(status);
        }
        return executionId;
    }

    // ---- Deployment state machine ----

    @SuppressWarnings("unchecked")
    // ---- On-Premises Instances ----

    public OnPremisesInstance registerOnPremisesInstance(String region, String instanceName,
                                                          String iamSessionArn, String iamUserArn) {
        Map<String, OnPremisesInstance> store = onPremisesFor(region);
        OnPremisesInstance inst = new OnPremisesInstance();
        inst.setInstanceName(instanceName);
        inst.setInstanceArn(AwsArnUtils.Arn.of("codedeploy", region, "000000000000",
                "instance/" + instanceName).toString());
        inst.setIamSessionArn(iamSessionArn);
        inst.setIamUserArn(iamUserArn);
        inst.setRegisterTime(Instant.now().toEpochMilli() / 1000.0);
        inst.setRegistrationStatus("Registered");
        store.put(instanceName, inst);
        return inst;
    }

    public void deregisterOnPremisesInstance(String region, String instanceName) {
        OnPremisesInstance inst = requireOnPremisesInstance(region, instanceName);
        inst.setDeregisterTime(Instant.now().toEpochMilli() / 1000.0);
        inst.setRegistrationStatus("Deregistered");
    }

    public OnPremisesInstance getOnPremisesInstance(String region, String instanceName) {
        return requireOnPremisesInstance(region, instanceName);
    }

    public List<OnPremisesInstance> batchGetOnPremisesInstances(String region, List<String> names) {
        return names.stream().map(n -> requireOnPremisesInstance(region, n)).collect(Collectors.toList());
    }

    public List<String> listOnPremisesInstances(String region, String registrationStatus,
                                                 List<Map<String, String>> tagFilters) {
        return onPremisesFor(region).values().stream()
                .filter(i -> registrationStatus == null || registrationStatus.equals(i.getRegistrationStatus()))
                .filter(i -> tagFilters == null || tagFilters.isEmpty() || matchesTagFilters(i.getTags(), tagFilters))
                .map(OnPremisesInstance::getInstanceName)
                .collect(Collectors.toList());
    }

    public void addTagsToOnPremisesInstances(String region, List<String> instanceNames, List<Map<String, String>> newTags) {
        for (String name : instanceNames) {
            OnPremisesInstance inst = requireOnPremisesInstance(region, name);
            for (Map<String, String> t : newTags) {
                inst.getTags().removeIf(e -> e.get("Key").equals(t.get("Key")));
                inst.getTags().add(t);
            }
        }
    }

    public void removeTagsFromOnPremisesInstances(String region, List<String> instanceNames, List<Map<String, String>> tagsToRemove) {
        for (String name : instanceNames) {
            OnPremisesInstance inst = requireOnPremisesInstance(region, name);
            for (Map<String, String> t : tagsToRemove) {
                inst.getTags().removeIf(e -> e.get("Key").equals(t.get("Key")));
            }
        }
    }

    private OnPremisesInstance requireOnPremisesInstance(String region, String instanceName) {
        OnPremisesInstance inst = onPremisesFor(region).get(instanceName);
        if (inst == null) {
            throw new AwsException("InstanceNameRequiredException",
                    "On-premises instance not found: " + instanceName, 400);
        }
        return inst;
    }

    // ---- Server Platform Deployment ----

    @SuppressWarnings("unchecked")
    private String createServerDeployment(String region, String appName, String groupName,
                                          DeploymentGroup group, String configName,
                                          Map<String, Object> revision, String description) {
        ServerAppSpecInfo appSpec = parseServerAppSpec(revision);
        String effectiveConfig = configName != null ? configName : group.getDeploymentConfigName();

        String deploymentId = generateDeploymentId();
        double now = Instant.now().toEpochMilli() / 1000.0;

        Deployment deployment = new Deployment();
        deployment.setDeploymentId(deploymentId);
        deployment.setApplicationName(appName);
        deployment.setDeploymentGroupName(groupName);
        deployment.setDeploymentConfigName(effectiveConfig);
        deployment.setStatus("Queued");
        deployment.setRevision(revision);
        deployment.setCreateTime(now);
        deployment.setDescription(description);
        deployment.setCreator("user");
        deployment.setComputePlatform("Server");
        deploymentsFor(region).put(deploymentId, deployment);

        // Resolve target instances
        List<String> instanceIds = resolveServerTargets(region, group);
        if (instanceIds.isEmpty()) {
            deployment.setStatus("Failed");
            deployment.setCompleteTime(now);
            deployment.setErrorInformation(Map.of("code", "NoInstancesReachable",
                    "message", "No instances found for deployment group"));
            return deploymentId;
        }

        ConcurrentHashMap<String, Map<String, Object>> allTargets = new ConcurrentHashMap<>();
        List<Map<String, Object>> instanceTargetMaps = new ArrayList<>();
        for (String instanceId : instanceIds) {
            Map<String, Object> instanceTargetMap = new ConcurrentHashMap<>();
            instanceTargetMap.put("deploymentId", deploymentId);
            instanceTargetMap.put("targetId", instanceId);
            instanceTargetMap.put("targetArn", instanceId);
            instanceTargetMap.put("status", "Pending");
            instanceTargetMap.put("lastUpdatedAt", now);
            instanceTargetMap.put("lifecycleEvents", new CopyOnWriteArrayList<>());

            Map<String, Object> targetWrapper = new ConcurrentHashMap<>();
            targetWrapper.put("deploymentTargetType", "InstanceTarget");
            targetWrapper.put("instanceTarget", instanceTargetMap);
            allTargets.put(instanceId, targetWrapper);
            instanceTargetMaps.add(instanceTargetMap);
        }
        deploymentTargetsFor(region).put(deploymentId, allTargets);

        AtomicBoolean stopFlag = new AtomicBoolean(false);
        stopFlags.put(deploymentId, stopFlag);

        Thread.ofVirtual().name("codedeploy-server-" + deploymentId).start(
                () -> runServerStateMachine(region, deployment, appSpec, instanceIds,
                        instanceTargetMaps, stopFlag));
        return deploymentId;
    }

    private void runServerStateMachine(String region, Deployment deployment,
                                       ServerAppSpecInfo appSpec, List<String> instanceIds,
                                       List<Map<String, Object>> instanceTargetMaps,
                                       AtomicBoolean stopFlag) {
        String deploymentId = deployment.getDeploymentId();
        try {
            deployment.setStatus("InProgress");
            deployment.setStartTime(Instant.now().toEpochMilli() / 1000.0);
            instanceTargetMaps.forEach(m -> updateTargetStatus(m, "InProgress"));

            boolean anyFailed = false;
            for (int i = 0; i < instanceIds.size(); i++) {
                if (stopFlag.get()) {
                    instanceTargetMaps.forEach(m -> updateTargetStatus(m, "Skipped"));
                    deployment.setStatus("Stopped");
                    deployment.setCompleteTime(Instant.now().toEpochMilli() / 1000.0);
                    return;
                }
                String instanceId = instanceIds.get(i);
                Map<String, Object> targetMap = instanceTargetMaps.get(i);
                boolean ok = runInstanceDeployment(region, deployment, appSpec, instanceId, targetMap, stopFlag);
                if (!ok) {
                    anyFailed = true;
                }
            }

            if (anyFailed) {
                deployment.setStatus("Failed");
                deployment.setErrorInformation(Map.of("code", "DeploymentFailed",
                        "message", "One or more instances failed deployment"));
            } else {
                deployment.setStatus("Succeeded");
            }
            deployment.setCompleteTime(Instant.now().toEpochMilli() / 1000.0);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            instanceTargetMaps.forEach(m -> updateTargetStatus(m, "Skipped"));
            deployment.setStatus("Stopped");
            deployment.setCompleteTime(Instant.now().toEpochMilli() / 1000.0);
        } catch (Exception e) {
            LOG.warnv("Server deployment {0} failed: {1}", deploymentId, e.getMessage());
            instanceTargetMaps.forEach(m -> updateTargetStatus(m, "Failed"));
            deployment.setStatus("Failed");
            deployment.setCompleteTime(Instant.now().toEpochMilli() / 1000.0);
            deployment.setErrorInformation(Map.of("code", "DeploymentFailed",
                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        } finally {
            stopFlags.remove(deploymentId);
        }
    }

    private boolean runInstanceDeployment(String region, Deployment deployment,
                                           ServerAppSpecInfo appSpec, String instanceId,
                                           Map<String, Object> targetMap,
                                           AtomicBoolean stopFlag) throws InterruptedException {
        List<String> lifecycleOrder = List.of(
                "ApplicationStop", "DownloadBundle", "BeforeInstall",
                "Install", "AfterInstall", "ApplicationStart", "ValidateService");

        for (String eventName : lifecycleOrder) {
            if (stopFlag.get()) {
                updateTargetStatus(targetMap, "Skipped");
                return false;
            }

            List<Map<String, Object>> hookSteps = appSpec.hooks != null
                    ? appSpec.hooks.get(eventName) : null;

            Map<String, Object> event = addLifecycleEvent(targetMap, eventName);

            // DownloadBundle and Install are infrastructure steps — always succeed
            if ("DownloadBundle".equals(eventName) || "Install".equals(eventName)) {
                finishLifecycleEvent(event, "Succeeded");
                continue;
            }

            if (hookSteps == null || hookSteps.isEmpty()) {
                finishLifecycleEvent(event, "Skipped");
                continue;
            }

            boolean stepOk = executeHookStepsOnInstance(region, instanceId, hookSteps, event);
            if (!stepOk) {
                updateTargetStatus(targetMap, "Failed");
                return false;
            }
        }

        updateTargetStatus(targetMap, "Succeeded");
        return true;
    }

    @SuppressWarnings("unchecked")
    private boolean executeHookStepsOnInstance(String region, String instanceId,
                                               List<Map<String, Object>> hookSteps,
                                               Map<String, Object> event) throws InterruptedException {
        for (Map<String, Object> step : hookSteps) {
            String location = (String) step.get("location");
            int timeout = toInt(step.get("timeout"), 300);
            String runas = (String) step.getOrDefault("runas", "root");

            if (location == null) {
                continue;
            }

            // Check if instance is registered with SSM
            boolean hasSsm = ssmCommandService.isInstanceRegistered(instanceId, region);
            if (!hasSsm) {
                LOG.debugv("Instance {0} not in SSM, marking hook {1} as Succeeded", instanceId, location);
                continue;
            }

            try {
                String script = "sh " + location;
                if (!"root".equals(runas)) {
                    script = "sudo -u " + runas + " sh " + location;
                }
                String commandId = ssmCommandService.sendCommandToInstance(
                        instanceId, "AWS-RunShellScript", Map.of("commands", List.of(script)),
                        timeout, region);

                // Poll until done (max timeout seconds, capped at 30s for emulator)
                long deadline = System.currentTimeMillis() + Math.min(timeout * 1000L, 30_000L);
                String invocationStatus = "InProgress";
                while (System.currentTimeMillis() < deadline && "InProgress".equals(invocationStatus)) {
                    Thread.sleep(500);
                    invocationStatus = ssmCommandService.getCommandInvocationStatus(commandId, instanceId, region);
                }

                if (!"Success".equals(invocationStatus) && !"InProgress".equals(invocationStatus)) {
                    finishLifecycleEvent(event, "Failed");
                    return false;
                }
            } catch (Exception e) {
                LOG.debugv("SSM execution failed for {0} on {1}: {2}", location, instanceId, e.getMessage());
                // Graceful degradation: if SSM fails, treat as succeeded
            }
        }
        finishLifecycleEvent(event, "Succeeded");
        return true;
    }

    @SuppressWarnings("unchecked")
    private ServerAppSpecInfo parseServerAppSpec(Map<String, Object> revision) {
        if (revision == null) {
            throw new AwsException("InvalidRevisionException", "Revision is required", 400);
        }

        String content = null;
        Object appSpecContent = revision.get("appSpecContent");
        if (appSpecContent instanceof Map<?, ?> asc) {
            content = (String) ((Map<String, Object>) asc).get("content");
        }

        ServerAppSpecInfo info = new ServerAppSpecInfo();
        info.os = "linux";
        info.hooks = new java.util.LinkedHashMap<>();

        if (content == null || content.isBlank()) {
            return info;
        }

        try {
            JsonNode root = yamlMapper.readTree(content);
            if (root.has("os")) {
                info.os = root.get("os").asText("linux");
            }
            JsonNode hooksNode = root.get("hooks");
            if (hooksNode != null && hooksNode.isObject()) {
                hooksNode.fields().forEachRemaining(entry -> {
                    String hookName = entry.getKey();
                    JsonNode steps = entry.getValue();
                    List<Map<String, Object>> stepList = new ArrayList<>();
                    if (steps.isArray()) {
                        steps.forEach(s -> {
                            Map<String, Object> step = new java.util.LinkedHashMap<>();
                            if (s.has("location")) { step.put("location", s.get("location").asText()); }
                            if (s.has("timeout")) { step.put("timeout", s.get("timeout").asInt(300)); }
                            if (s.has("runas")) { step.put("runas", s.get("runas").asText("root")); }
                            stepList.add(step);
                        });
                    }
                    info.hooks.put(hookName, stepList);
                });
            }
        } catch (Exception e) {
            throw new AwsException("InvalidRevisionException",
                    "Failed to parse Server AppSpec: " + e.getMessage(), 400);
        }
        return info;
    }

    @SuppressWarnings("unchecked")
    private List<String> resolveServerTargets(String region, DeploymentGroup group) {
        List<String> instanceIds = new ArrayList<>();

        // EC2 instances by tag filters
        List<Map<String, String>> ec2TagFilters = group.getEc2TagFilters();
        if (ec2TagFilters != null && !ec2TagFilters.isEmpty()) {
            Map<String, List<String>> filters = new java.util.LinkedHashMap<>();
            for (Map<String, String> filter : ec2TagFilters) {
                String key = filter.get("Key");
                String value = filter.get("Value");
                if (key != null && value != null) {
                    filters.computeIfAbsent("tag:" + key, k -> new ArrayList<>()).add(value);
                }
            }
            try {
                ec2Service.describeInstances(region, null, filters).stream()
                        .flatMap(r -> r.getInstances().stream())
                        .map(inst -> inst.getInstanceId())
                        .filter(id -> id != null)
                        .forEach(instanceIds::add);
            } catch (Exception e) {
                LOG.debugv("EC2 tag filter lookup failed: {0}", e.getMessage());
            }
        }

        // On-premises instances by tag filters
        List<Map<String, String>> onPremFilters = group.getOnPremisesInstanceTagFilters();
        if (onPremFilters != null && !onPremFilters.isEmpty()) {
            onPremisesFor(region).values().stream()
                    .filter(inst -> "Registered".equals(inst.getRegistrationStatus()))
                    .filter(inst -> matchesTagFilters(inst.getTags(), onPremFilters))
                    .map(OnPremisesInstance::getInstanceName)
                    .forEach(instanceIds::add);
        }

        // If no filters specified, include all registered on-premises instances
        if ((ec2TagFilters == null || ec2TagFilters.isEmpty())
                && (onPremFilters == null || onPremFilters.isEmpty())) {
            onPremisesFor(region).values().stream()
                    .filter(inst -> "Registered".equals(inst.getRegistrationStatus()))
                    .map(OnPremisesInstance::getInstanceName)
                    .forEach(instanceIds::add);
        }

        return instanceIds;
    }

    private boolean matchesTagFilters(List<Map<String, String>> instanceTags,
                                       List<Map<String, String>> filters) {
        for (Map<String, String> filter : filters) {
            String key = filter.get("Key");
            String value = filter.get("Value");
            if (key == null) { continue; }
            boolean found = instanceTags.stream()
                    .anyMatch(t -> key.equals(t.get("Key"))
                            && (value == null || value.equals(t.get("Value"))));
            if (!found) { return false; }
        }
        return true;
    }

    private AppSpecInfo parseAppSpec(Map<String, Object> revision) {
        if (revision == null) {
            throw new AwsException("InvalidRevisionException", "Revision is required", 400);
        }
        Object appSpecContent = revision.get("appSpecContent");
        if (!(appSpecContent instanceof Map)) {
            throw new AwsException("InvalidRevisionException", "Missing appSpecContent in revision", 400);
        }
        String content = (String) ((Map<String, Object>) appSpecContent).get("content");
        if (content == null || content.isBlank()) {
            throw new AwsException("InvalidRevisionException", "Missing content in appSpecContent", 400);
        }

        AppSpecInfo info = new AppSpecInfo();
        try {
            JsonNode root = mapper.readTree(content);
            JsonNode resources = root.get("Resources");
            if (resources != null && resources.isArray() && !resources.isEmpty()) {
                JsonNode firstResource = resources.get(0);
                if (firstResource.isObject()) {
                    JsonNode resourceNode = firstResource.fields().next().getValue();
                    JsonNode props = resourceNode.path("Properties");
                    info.functionName = props.path("Name").asText(null);
                    info.aliasName = props.path("Alias").asText(null);
                    info.currentVersion = props.path("CurrentVersion").asText(null);
                    info.targetVersion = props.path("TargetVersion").asText(null);
                }
            }
            JsonNode hooks = root.get("Hooks");
            if (hooks != null && hooks.isArray()) {
                for (JsonNode hook : hooks) {
                    if (hook.has("BeforeAllowTraffic")) {
                        info.beforeAllowTraffic = hook.get("BeforeAllowTraffic").asText(null);
                    }
                    if (hook.has("AfterAllowTraffic")) {
                        info.afterAllowTraffic = hook.get("AfterAllowTraffic").asText(null);
                    }
                }
            }
        } catch (Exception e) {
            throw new AwsException("InvalidRevisionException", "Failed to parse AppSpec content: " + e.getMessage(), 400);
        }

        if (info.functionName == null || info.aliasName == null || info.targetVersion == null) {
            throw new AwsException("InvalidRevisionException",
                    "AppSpec must specify Name, Alias, and TargetVersion", 400);
        }
        return info;
    }

    @SuppressWarnings("unchecked")
    private EcsAppSpecInfo parseEcsAppSpec(Map<String, Object> revision) {
        if (revision == null) {
            throw new AwsException("InvalidRevisionException", "Revision is required", 400);
        }
        Object appSpecContent = revision.get("appSpecContent");
        if (!(appSpecContent instanceof Map)) {
            throw new AwsException("InvalidRevisionException", "Missing appSpecContent in revision", 400);
        }
        String content = (String) ((Map<String, Object>) appSpecContent).get("content");
        if (content == null || content.isBlank()) {
            throw new AwsException("InvalidRevisionException", "Missing content in appSpecContent", 400);
        }

        EcsAppSpecInfo info = new EcsAppSpecInfo();
        try {
            JsonNode root = mapper.readTree(content);
            JsonNode resources = root.get("Resources");
            if (resources != null && resources.isArray() && !resources.isEmpty()) {
                JsonNode firstResource = resources.get(0);
                if (firstResource.isObject()) {
                    JsonNode resourceNode = firstResource.fields().next().getValue();
                    JsonNode props = resourceNode.path("Properties");
                    info.taskDefinition = props.path("TaskDefinition").asText(null);
                    JsonNode lbInfo = props.path("LoadBalancerInfo");
                    if (!lbInfo.isMissingNode()) {
                        info.containerName = lbInfo.path("ContainerName").asText(null);
                        info.containerPort = lbInfo.path("ContainerPort").asInt(80);
                    }
                }
            }
            JsonNode hooks = root.get("Hooks");
            if (hooks != null && hooks.isArray()) {
                for (JsonNode hook : hooks) {
                    if (hook.has("BeforeInstall")) {
                        info.beforeInstall = hook.get("BeforeInstall").asText(null);
                    }
                    if (hook.has("AfterInstall")) {
                        info.afterInstall = hook.get("AfterInstall").asText(null);
                    }
                    if (hook.has("BeforeAllowTraffic")) {
                        info.beforeAllowTraffic = hook.get("BeforeAllowTraffic").asText(null);
                    }
                    if (hook.has("AfterAllowTraffic")) {
                        info.afterAllowTraffic = hook.get("AfterAllowTraffic").asText(null);
                    }
                }
            }
        } catch (Exception e) {
            throw new AwsException("InvalidRevisionException", "Failed to parse ECS AppSpec: " + e.getMessage(), 400);
        }

        if (info.taskDefinition == null) {
            throw new AwsException("InvalidRevisionException", "ECS AppSpec must specify TaskDefinition", 400);
        }
        return info;
    }

    @SuppressWarnings("unchecked")
    private String createEcsDeployment(String region, String appName, String groupName,
                                       DeploymentGroup group, String configName,
                                       Map<String, Object> revision, String description) {
        EcsAppSpecInfo appSpec = parseEcsAppSpec(revision);
        String effectiveConfig = configName != null ? configName : group.getDeploymentConfigName();

        String deploymentId = generateDeploymentId();
        double now = Instant.now().toEpochMilli() / 1000.0;

        Deployment deployment = new Deployment();
        deployment.setDeploymentId(deploymentId);
        deployment.setApplicationName(appName);
        deployment.setDeploymentGroupName(groupName);
        deployment.setDeploymentConfigName(effectiveConfig);
        deployment.setStatus("Queued");
        deployment.setRevision(revision);
        deployment.setCreateTime(now);
        deployment.setDescription(description);
        deployment.setCreator("user");
        deployment.setComputePlatform("ECS");
        deploymentsFor(region).put(deploymentId, deployment);

        // Determine ECS cluster/service from deployment group
        String clusterName = "default";
        String serviceName = null;
        List<Map<String, Object>> ecsSvcs = group.getEcsServices();
        if (ecsSvcs != null && !ecsSvcs.isEmpty()) {
            Map<String, Object> svc = ecsSvcs.get(0);
            clusterName = (String) svc.getOrDefault("clusterName", "default");
            serviceName = (String) svc.get("serviceName");
        }
        if (serviceName == null) {
            throw new AwsException("InvalidDeploymentConfigException",
                    "ECS deployment group must specify ecsServices", 400);
        }

        // Determine blue/green TG ARNs from loadBalancerInfo
        String blueTgArn = null;
        String greenTgArn = null;
        List<String> listenerArns = new ArrayList<>();
        Map<String, Object> lbInfo = group.getLoadBalancerInfo();
        if (lbInfo != null) {
            List<Map<String, Object>> pairList = (List<Map<String, Object>>) lbInfo.get("targetGroupPairInfoList");
            if (pairList != null && !pairList.isEmpty()) {
                Map<String, Object> pair = pairList.get(0);
                List<Map<String, Object>> tgList = (List<Map<String, Object>>) pair.get("targetGroups");
                if (tgList != null && tgList.size() >= 2) {
                    String blueName = (String) tgList.get(0).get("name");
                    String greenName = (String) tgList.get(1).get("name");
                    TargetGroup blueTg = elbV2Service.getTargetGroupByName(region, blueName);
                    TargetGroup greenTg = elbV2Service.getTargetGroupByName(region, greenName);
                    if (blueTg != null) { blueTgArn = blueTg.getTargetGroupArn(); }
                    if (greenTg != null) { greenTgArn = greenTg.getTargetGroupArn(); }
                }
                Map<String, Object> prodRoute = (Map<String, Object>) pair.get("prodTrafficRoute");
                if (prodRoute != null) {
                    List<String> arns = (List<String>) prodRoute.get("listenerArns");
                    if (arns != null) { listenerArns.addAll(arns); }
                }
            }
        }

        String targetId = clusterName + ":" + serviceName;
        String targetArn = AwsArnUtils.Arn.of("ecs", region, "000000000000", "service/" + clusterName + "/" + serviceName).toString();

        Map<String, Object> ecsTargetMap = new ConcurrentHashMap<>();
        ecsTargetMap.put("deploymentId", deploymentId);
        ecsTargetMap.put("targetId", targetId);
        ecsTargetMap.put("targetArn", targetArn);
        ecsTargetMap.put("status", "Pending");
        ecsTargetMap.put("lastUpdatedAt", now);
        ecsTargetMap.put("lifecycleEvents", new CopyOnWriteArrayList<>());
        ecsTargetMap.put("taskSetsInfo", new CopyOnWriteArrayList<>());

        Map<String, Object> targetMap = new ConcurrentHashMap<>();
        targetMap.put("deploymentTargetType", "ECSTarget");
        targetMap.put("ecsTarget", ecsTargetMap);
        ConcurrentHashMap<String, Map<String, Object>> ecsTargets = new ConcurrentHashMap<>();
        ecsTargets.put(targetId, targetMap);
        deploymentTargetsFor(region).put(deploymentId, ecsTargets);

        AtomicBoolean stopFlag = new AtomicBoolean(false);
        stopFlags.put(deploymentId, stopFlag);

        final String finalCluster = clusterName;
        final String finalService = serviceName;
        final String finalBlueTgArn = blueTgArn;
        final String finalGreenTgArn = greenTgArn;
        final List<String> finalListenerArns = listenerArns;
        Thread.ofVirtual().name("codedeploy-ecs-" + deploymentId).start(
                () -> runEcsStateMachine(region, deployment, appSpec, ecsTargetMap, stopFlag,
                        effectiveConfig, finalCluster, finalService,
                        finalBlueTgArn, finalGreenTgArn, finalListenerArns));

        return deploymentId;
    }

    private void runEcsStateMachine(String region, Deployment deployment, EcsAppSpecInfo appSpec,
                                    Map<String, Object> ecsTargetMap, AtomicBoolean stopFlag,
                                    String configName, String clusterName, String serviceName,
                                    String blueTgArn, String greenTgArn,
                                    List<String> listenerArns) {
        String deploymentId = deployment.getDeploymentId();
        TaskSet greenTaskSet = null;
        TaskSet blueTaskSet = null;
        try {
            deployment.setStatus("InProgress");
            deployment.setStartTime(Instant.now().toEpochMilli() / 1000.0);
            updateEcsTargetStatus(ecsTargetMap, "InProgress");

            if (stopFlag.get()) { finishEcsStopped(deployment, ecsTargetMap); return; }

            // Find existing primary (blue) task set
            List<TaskSet> existing = ecsService.describeTaskSets(clusterName, serviceName, null, region);
            blueTaskSet = existing.stream()
                    .filter(ts -> "PRIMARY".equals(ts.getStatus()))
                    .findFirst()
                    .orElse(existing.isEmpty() ? null : existing.get(0));

            if (appSpec.beforeInstall != null) {
                boolean ok = invokeHook(region, deployment, appSpec.beforeInstall,
                        "BeforeInstall", ecsTargetMap, stopFlag);
                if (!ok) {
                    finishEcsFailed(deployment, ecsTargetMap, "BeforeInstallHookFailed",
                            "Hook function reported failure: BeforeInstall");
                    return;
                }
            }

            if (stopFlag.get()) { finishEcsStopped(deployment, ecsTargetMap); return; }

            // Install: create green task set
            Map<String, Object> installEvent = addLifecycleEvent(ecsTargetMap, "Install");
            try {
                greenTaskSet = ecsService.createTaskSet(clusterName, serviceName,
                        appSpec.taskDefinition, null, 100.0, "PERCENT", deploymentId, region);
                appendTaskSetInfo(ecsTargetMap, greenTaskSet, greenTgArn, 0.0);
                finishLifecycleEvent(installEvent, "Succeeded");
            } catch (Exception e) {
                finishLifecycleEvent(installEvent, "Failed");
                finishEcsFailed(deployment, ecsTargetMap, "InstallFailed", e.getMessage());
                return;
            }

            if (stopFlag.get()) { finishEcsStopped(deployment, ecsTargetMap); return; }

            if (appSpec.afterInstall != null) {
                boolean ok = invokeHook(region, deployment, appSpec.afterInstall,
                        "AfterInstall", ecsTargetMap, stopFlag);
                if (!ok) {
                    finishEcsFailed(deployment, ecsTargetMap, "AfterInstallHookFailed",
                            "Hook function reported failure: AfterInstall");
                    return;
                }
            }

            if (stopFlag.get()) { finishEcsStopped(deployment, ecsTargetMap); return; }

            if (appSpec.beforeAllowTraffic != null) {
                boolean ok = invokeHook(region, deployment, appSpec.beforeAllowTraffic,
                        "BeforeAllowTraffic", ecsTargetMap, stopFlag);
                if (!ok) {
                    finishEcsFailed(deployment, ecsTargetMap, "BeforeAllowTrafficHookFailed",
                            "Hook function reported failure: BeforeAllowTraffic");
                    return;
                }
            }

            if (stopFlag.get()) { finishEcsStopped(deployment, ecsTargetMap); return; }

            // AllowTraffic: shift ELB traffic blue → green
            if (!listenerArns.isEmpty() && blueTgArn != null && greenTgArn != null) {
                executeEcsAllowTraffic(region, deployment, configName, ecsTargetMap,
                        listenerArns, blueTgArn, greenTgArn, stopFlag);
            } else {
                Map<String, Object> allowEvent = addLifecycleEvent(ecsTargetMap, "AllowTraffic");
                finishLifecycleEvent(allowEvent, "Succeeded");
            }

            if (stopFlag.get()) { finishEcsStopped(deployment, ecsTargetMap); return; }

            if (appSpec.afterAllowTraffic != null) {
                boolean ok = invokeHook(region, deployment, appSpec.afterAllowTraffic,
                        "AfterAllowTraffic", ecsTargetMap, stopFlag);
                if (!ok) {
                    finishEcsFailed(deployment, ecsTargetMap, "AfterAllowTrafficHookFailed",
                            "Hook function reported failure: AfterAllowTraffic");
                    return;
                }
            }

            // Promote green as primary
            if (greenTaskSet != null) {
                ecsService.updateServicePrimaryTaskSet(clusterName, serviceName,
                        greenTaskSet.getTaskSetArn(), region);
            }

            // Terminate blue task set
            if (blueTaskSet != null) {
                Map<String, Object> terminateEvent = addLifecycleEvent(ecsTargetMap, "TerminateBlueInstances");
                try {
                    ecsService.deleteTaskSet(clusterName, serviceName,
                            blueTaskSet.getTaskSetArn(), true, region);
                    finishLifecycleEvent(terminateEvent, "Succeeded");
                } catch (Exception e) {
                    LOG.debugv("Could not delete blue task set: {0}", e.getMessage());
                    finishLifecycleEvent(terminateEvent, "Succeeded");
                }
            }

            updateEcsTargetStatus(ecsTargetMap, "Succeeded");
            deployment.setStatus("Succeeded");
            deployment.setCompleteTime(Instant.now().toEpochMilli() / 1000.0);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finishEcsStopped(deployment, ecsTargetMap);
        } catch (Exception e) {
            LOG.warnv("ECS deployment {0} failed: {1}", deploymentId, e.getMessage());
            finishEcsFailed(deployment, ecsTargetMap, "DeploymentFailed", e.getMessage());
        } finally {
            stopFlags.remove(deploymentId);
        }
    }

    private void executeEcsAllowTraffic(String region, Deployment deployment, String configName,
                                        Map<String, Object> ecsTargetMap, List<String> listenerArns,
                                        String blueTgArn, String greenTgArn,
                                        AtomicBoolean stopFlag) throws InterruptedException {
        TrafficRoutingInfo trc = getTrafficRoutingInfo(region, configName);
        Map<String, Object> event = addLifecycleEvent(ecsTargetMap, "AllowTraffic");
        try {
            switch (trc.type) {
                case "TimeBasedCanary" -> {
                    for (String listenerArn : listenerArns) {
                        elbV2Service.shiftListenerForward(region, listenerArn,
                                blueTgArn, greenTgArn, trc.percentage);
                    }
                    long waitMs = Math.min(trc.intervalSeconds * 1000L, 5000L);
                    if (waitMs > 0 && !stopFlag.get()) {
                        Thread.sleep(waitMs);
                    }
                    if (!stopFlag.get()) {
                        for (String listenerArn : listenerArns) {
                            elbV2Service.shiftListenerForward(region, listenerArn,
                                    blueTgArn, greenTgArn, 100);
                        }
                    }
                }
                case "TimeBasedLinear" -> {
                    int steps = (int) Math.ceil(100.0 / trc.percentage);
                    for (int step = 1; step <= steps && !stopFlag.get(); step++) {
                        int pct = Math.min(step * trc.percentage, 100);
                        for (String listenerArn : listenerArns) {
                            elbV2Service.shiftListenerForward(region, listenerArn,
                                    blueTgArn, greenTgArn, pct);
                        }
                        if (pct < 100) {
                            long waitMs = Math.min(trc.intervalSeconds * 1000L, 2000L);
                            if (waitMs > 0) {
                                Thread.sleep(waitMs);
                            }
                        }
                    }
                }
                default -> {
                    for (String listenerArn : listenerArns) {
                        elbV2Service.shiftListenerForward(region, listenerArn,
                                blueTgArn, greenTgArn, 100);
                    }
                }
            }
            finishLifecycleEvent(event, "Succeeded");
        } catch (InterruptedException e) {
            finishLifecycleEvent(event, "Failed");
            throw e;
        } catch (Exception e) {
            finishLifecycleEvent(event, "Failed");
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private void appendTaskSetInfo(Map<String, Object> ecsTargetMap, TaskSet ts,
                                   String tgArn, double trafficWeight) {
        Map<String, Object> tsInfo = new ConcurrentHashMap<>();
        tsInfo.put("identifer", ts.getId());
        tsInfo.put("desiredCount", ts.getComputedDesiredCount());
        tsInfo.put("pendingCount", ts.getPendingCount());
        tsInfo.put("runningCount", ts.getRunningCount());
        tsInfo.put("status", ts.getStatus());
        tsInfo.put("trafficWeight", trafficWeight);
        if (tgArn != null) {
            tsInfo.put("targetGroup", Map.of("arn", tgArn));
        }
        List<Map<String, Object>> taskSetsInfo = (List<Map<String, Object>>) ecsTargetMap.get("taskSetsInfo");
        if (taskSetsInfo != null) {
            taskSetsInfo.add(tsInfo);
        }
    }

    private void updateEcsTargetStatus(Map<String, Object> ecsTargetMap, String status) {
        ecsTargetMap.put("status", status);
        ecsTargetMap.put("lastUpdatedAt", Instant.now().toEpochMilli() / 1000.0);
    }

    private void finishEcsStopped(Deployment deployment, Map<String, Object> ecsTargetMap) {
        updateEcsTargetStatus(ecsTargetMap, "Skipped");
        deployment.setStatus("Stopped");
        deployment.setCompleteTime(Instant.now().toEpochMilli() / 1000.0);
    }

    private void finishEcsFailed(Deployment deployment, Map<String, Object> ecsTargetMap,
                                  String errorCode, String message) {
        updateEcsTargetStatus(ecsTargetMap, "Failed");
        deployment.setStatus("Failed");
        deployment.setCompleteTime(Instant.now().toEpochMilli() / 1000.0);
        deployment.setErrorInformation(Map.of("code", errorCode, "message", message != null ? message : ""));
    }

    @SuppressWarnings("unchecked")
    private TrafficRoutingInfo getTrafficRoutingInfo(String region, String configName) {
        if (configName == null) {
            return new TrafficRoutingInfo("AllAtOnce", 100, 0);
        }
        DeploymentConfig cfg = deploymentConfigsFor(region).get(configName);
        if (cfg == null || cfg.getTrafficRoutingConfig() == null) {
            return new TrafficRoutingInfo("AllAtOnce", 100, 0);
        }
        Map<String, Object> trc = (Map<String, Object>) cfg.getTrafficRoutingConfig();
        String type = (String) trc.getOrDefault("type", "AllAtOnce");

        if ("TimeBasedCanary".equals(type)) {
            Map<String, Object> canary = (Map<String, Object>) trc.get("timeBasedCanary");
            if (canary != null) {
                int pct = toInt(canary.get("canaryPercentage"), 10);
                int minutes = toInt(canary.get("canaryInterval"), 5);
                return new TrafficRoutingInfo(type, pct, minutes * 60);
            }
        } else if ("TimeBasedLinear".equals(type)) {
            Map<String, Object> linear = (Map<String, Object>) trc.get("timeBasedLinear");
            if (linear != null) {
                int pct = toInt(linear.get("linearPercentage"), 10);
                int minutes = toInt(linear.get("linearInterval"), 1);
                return new TrafficRoutingInfo(type, pct, minutes * 60);
            }
        }
        return new TrafficRoutingInfo("AllAtOnce", 100, 0);
    }

    private void runStateMachine(String region, Deployment deployment, AppSpecInfo appSpec,
                                  Map<String, Object> lambdaTargetMap, AtomicBoolean stopFlag, String configName) {
        String deploymentId = deployment.getDeploymentId();
        try {
            deployment.setStatus("InProgress");
            deployment.setStartTime(Instant.now().toEpochMilli() / 1000.0);
            updateTargetStatus(lambdaTargetMap, "InProgress");

            if (stopFlag.get()) { finishStopped(deployment, lambdaTargetMap); return; }

            if (appSpec.beforeAllowTraffic != null) {
                boolean ok = invokeHook(region, deployment, appSpec.beforeAllowTraffic,
                        "BeforeAllowTraffic", lambdaTargetMap, stopFlag);
                if (!ok) { finishFailed(deployment, lambdaTargetMap, "BeforeAllowTrafficHookFailed",
                        "Hook function reported failure: BeforeAllowTraffic"); return; }
            }

            if (stopFlag.get()) { finishStopped(deployment, lambdaTargetMap); return; }

            executeAllowTraffic(region, deployment, appSpec, configName, lambdaTargetMap, stopFlag);

            if (stopFlag.get()) { finishStopped(deployment, lambdaTargetMap); return; }

            if (appSpec.afterAllowTraffic != null) {
                boolean ok = invokeHook(region, deployment, appSpec.afterAllowTraffic,
                        "AfterAllowTraffic", lambdaTargetMap, stopFlag);
                if (!ok) { finishFailed(deployment, lambdaTargetMap, "AfterAllowTrafficHookFailed",
                        "Hook function reported failure: AfterAllowTraffic"); return; }
            }

            updateTargetStatus(lambdaTargetMap, "Succeeded");
            deployment.setStatus("Succeeded");
            deployment.setCompleteTime(Instant.now().toEpochMilli() / 1000.0);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finishStopped(deployment, lambdaTargetMap);
        } catch (Exception e) {
            LOG.warnv("Deployment {0} failed: {1}", deploymentId, e.getMessage());
            finishFailed(deployment, lambdaTargetMap, "DeploymentFailed", e.getMessage());
        } finally {
            stopFlags.remove(deploymentId);
        }
    }

    private void executeAllowTraffic(String region, Deployment deployment, AppSpecInfo appSpec,
                                      String configName, Map<String, Object> lambdaTargetMap,
                                      AtomicBoolean stopFlag) throws InterruptedException {
        TrafficRoutingInfo trc = getTrafficRoutingInfo(region, configName);
        Map<String, Object> event = addLifecycleEvent(lambdaTargetMap, "AllowTraffic");

        try {
            switch (trc.type) {
                case "TimeBasedCanary" -> {
                    // Step 1: shift canaryPercentage to targetVersion
                    double canaryWeight = trc.percentage / 100.0;
                    Map<String, Double> routing = Map.of(appSpec.targetVersion, canaryWeight);
                    lambdaService.updateAlias(region, appSpec.functionName, appSpec.aliasName,
                            appSpec.currentVersion, null, routing);

                    // Wait the canary interval (capped at 5s for emulator speed)
                    long waitMs = Math.min(trc.intervalSeconds * 1000L, 5000L);
                    if (waitMs > 0 && !stopFlag.get()) {
                        Thread.sleep(waitMs);
                    }
                    if (stopFlag.get()) { return; }

                    // Step 2: flip 100% to targetVersion
                    lambdaService.updateAlias(region, appSpec.functionName, appSpec.aliasName,
                            appSpec.targetVersion, null, Map.of());
                }
                case "TimeBasedLinear" -> {
                    int steps = (int) Math.ceil(100.0 / trc.percentage);
                    for (int step = 1; step <= steps && !stopFlag.get(); step++) {
                        int pct = Math.min(step * trc.percentage, 100);
                        if (pct >= 100) {
                            lambdaService.updateAlias(region, appSpec.functionName, appSpec.aliasName,
                                    appSpec.targetVersion, null, Map.of());
                        } else {
                            double weight = pct / 100.0;
                            lambdaService.updateAlias(region, appSpec.functionName, appSpec.aliasName,
                                    appSpec.currentVersion, null, Map.of(appSpec.targetVersion, weight));
                            long waitMs = Math.min(trc.intervalSeconds * 1000L, 2000L);
                            if (waitMs > 0) {
                                Thread.sleep(waitMs);
                            }
                        }
                    }
                }
                default -> {
                    // AllAtOnce: flip immediately
                    lambdaService.updateAlias(region, appSpec.functionName, appSpec.aliasName,
                            appSpec.targetVersion, null, Map.of());
                }
            }
            finishLifecycleEvent(event, "Succeeded");
        } catch (Exception e) {
            finishLifecycleEvent(event, "Failed");
            throw e;
        }
    }

    private boolean invokeHook(String region, Deployment deployment, String hookFunctionName,
                                String lifecycleEventName, Map<String, Object> lambdaTargetMap,
                                AtomicBoolean stopFlag) throws InterruptedException {
        String executionId = UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();
        hookFutures.put(executionId, future);

        Map<String, Object> event = addLifecycleEvent(lambdaTargetMap, lifecycleEventName);

        try {
            String payload = "{\"DeploymentId\":\"" + deployment.getDeploymentId()
                    + "\",\"LifecycleEventHookExecutionId\":\"" + executionId + "\"}";

            try {
                InvokeResult result = lambdaService.invoke(region, hookFunctionName,
                        payload.getBytes(), InvocationType.RequestResponse);
                if (!future.isDone()) {
                    // Lambda didn't call PutLifecycleEventHookExecutionStatus; decide from invocation result
                    future.complete(result.getFunctionError() == null ? "Succeeded" : "Failed");
                }
            } catch (Exception e) {
                LOG.debugv("Hook Lambda {0} not invokable: {1}", hookFunctionName, e.getMessage());
                future.complete("Succeeded");
            }

            String status = "Succeeded";
            try {
                status = future.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                status = "Succeeded";
            }

            finishLifecycleEvent(event, status);
            return "Succeeded".equals(status);
        } finally {
            hookFutures.remove(executionId);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> addLifecycleEvent(Map<String, Object> lambdaTargetMap, String name) {
        Map<String, Object> event = new ConcurrentHashMap<>();
        event.put("lifecycleEventName", name);
        event.put("startTime", Instant.now().toEpochMilli() / 1000.0);
        event.put("status", "InProgress");
        List<Map<String, Object>> events = (List<Map<String, Object>>) lambdaTargetMap.get("lifecycleEvents");
        if (events != null) {
            events.add(event);
        }
        lambdaTargetMap.put("lastUpdatedAt", Instant.now().toEpochMilli() / 1000.0);
        return event;
    }

    private void finishLifecycleEvent(Map<String, Object> event, String status) {
        event.put("endTime", Instant.now().toEpochMilli() / 1000.0);
        event.put("status", status);
    }

    private void updateTargetStatus(Map<String, Object> lambdaTargetMap, String status) {
        lambdaTargetMap.put("status", status);
        lambdaTargetMap.put("lastUpdatedAt", Instant.now().toEpochMilli() / 1000.0);
    }

    private void finishStopped(Deployment deployment, Map<String, Object> lambdaTargetMap) {
        updateTargetStatus(lambdaTargetMap, "Skipped");
        deployment.setStatus("Stopped");
        deployment.setCompleteTime(Instant.now().toEpochMilli() / 1000.0);
    }

    private void finishFailed(Deployment deployment, Map<String, Object> lambdaTargetMap,
                               String errorCode, String message) {
        updateTargetStatus(lambdaTargetMap, "Failed");
        deployment.setStatus("Failed");
        deployment.setCompleteTime(Instant.now().toEpochMilli() / 1000.0);
        deployment.setErrorInformation(Map.of("code", errorCode, "message", message != null ? message : ""));
    }

    private String generateDeploymentId() {
        String hex = UUID.randomUUID().toString().replace("-", "").substring(0, 9).toUpperCase();
        return "d-" + hex;
    }

    private int toInt(Object val, int def) {
        if (val instanceof Number n) { return n.intValue(); }
        if (val instanceof String s) { try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {} }
        return def;
    }

    // ---- Tags ----

    public void tagResource(String arn, List<Map<String, String>> tagList) {
        Map<String, String> tagMap = tags.computeIfAbsent(arn, k -> new ConcurrentHashMap<>());
        for (Map<String, String> t : tagList) {
            tagMap.put(t.get("Key"), t.get("Value"));
        }
    }

    public void untagResource(String arn, List<String> tagKeys) {
        Map<String, String> tagMap = tags.get(arn);
        if (tagMap != null) {
            tagKeys.forEach(tagMap::remove);
        }
    }

    public List<Map<String, String>> listTagsForResource(String arn) {
        Map<String, String> tagMap = tags.getOrDefault(arn, Map.of());
        return tagMap.entrySet().stream()
                .map(e -> Map.of("Key", e.getKey(), "Value", e.getValue()))
                .collect(Collectors.toList());
    }

    public String applicationArn(String region, String name) {
        return AwsArnUtils.Arn.of("codedeploy", region, "000000000000", "application:" + name).toString();
    }

    public String deploymentGroupArn(String region, String appName, String groupName) {
        return AwsArnUtils.Arn.of("codedeploy", region, "000000000000", "deploymentgroup:" + appName + "/" + groupName).toString();
    }

    @SuppressWarnings("unchecked")
    private void applyGroupFields(DeploymentGroup group, Map<String, Object> fields) {
        if (fields == null) { return; }
        Object ec2TagFilters = fields.get("ec2TagFilters");
        if (ec2TagFilters instanceof List<?> list) {
            group.setEc2TagFilters((List<Map<String, String>>) list);
        }
        Object onPremTagFilters = fields.get("onPremisesInstanceTagFilters");
        if (onPremTagFilters instanceof List<?> list) {
            group.setOnPremisesInstanceTagFilters((List<Map<String, String>>) list);
        }
        Object asg = fields.get("autoScalingGroups");
        if (asg instanceof List<?> list) {
            group.setAutoScalingGroups((List<Map<String, Object>>) list);
        }
        setMapField(group, fields, "deploymentStyle", DeploymentGroup::setDeploymentStyle);
        setMapField(group, fields, "blueGreenDeploymentConfiguration", DeploymentGroup::setBlueGreenDeploymentConfiguration);
        setMapField(group, fields, "loadBalancerInfo", DeploymentGroup::setLoadBalancerInfo);
        setMapField(group, fields, "ec2TagSet", DeploymentGroup::setEc2TagSet);
        setMapField(group, fields, "onPremisesTagSet", DeploymentGroup::setOnPremisesTagSet);
        setMapField(group, fields, "alarmConfiguration", DeploymentGroup::setAlarmConfiguration);
        setMapField(group, fields, "autoRollbackConfiguration", DeploymentGroup::setAutoRollbackConfiguration);
        Object triggerConfigs = fields.get("triggerConfigurations");
        if (triggerConfigs instanceof List<?> list) {
            group.setTriggerConfigurations((List<Map<String, Object>>) list);
        }
        Object ecsServices = fields.get("ecsServices");
        if (ecsServices instanceof List<?> list) {
            group.setEcsServices((List<Map<String, Object>>) list);
        }
        if (fields.containsKey("computePlatform")) {
            group.setComputePlatform((String) fields.get("computePlatform"));
        }
        if (fields.containsKey("outdatedInstancesStrategy")) {
            group.setOutdatedInstancesStrategy((String) fields.get("outdatedInstancesStrategy"));
        }
        if (fields.containsKey("terminationHookEnabled")) {
            group.setTerminationHookEnabled((Boolean) fields.get("terminationHookEnabled"));
        }
    }

    @SuppressWarnings("unchecked")
    private void setMapField(DeploymentGroup group, Map<String, Object> fields, String key,
                              java.util.function.BiConsumer<DeploymentGroup, Map<String, Object>> setter) {
        Object val = fields.get(key);
        if (val instanceof Map<?, ?> m) {
            setter.accept(group, (Map<String, Object>) m);
        }
    }

    private void applyTags(String arn, List<Map<String, String>> tagList) {
        Map<String, String> tagMap = tags.computeIfAbsent(arn, k -> new ConcurrentHashMap<>());
        for (Map<String, String> t : tagList) {
            String key = t.containsKey("Key") ? t.get("Key") : t.get("key");
            String value = t.containsKey("Value") ? t.get("Value") : t.get("value");
            if (key != null) { tagMap.put(key, value != null ? value : ""); }
        }
    }
}
