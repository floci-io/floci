package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ecs.container.HostVolumePolicy;
import io.github.hectorvent.floci.services.ecs.model.Attribute;
import io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.ecs.model.CapacityProvider;
import io.github.hectorvent.floci.services.ecs.model.CapacityProviderStrategyItem;
import io.github.hectorvent.floci.services.ecs.model.ClusterSetting;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.ContainerDependency;
import io.github.hectorvent.floci.services.ecs.model.ContainerInstance;
import io.github.hectorvent.floci.services.ecs.model.ContainerOverride;
import io.github.hectorvent.floci.services.ecs.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.ecs.model.CreateServiceRequest;
import io.github.hectorvent.floci.services.ecs.model.CreateTaskSetRequest;
import io.github.hectorvent.floci.services.ecs.model.RegisterContainerInstanceRequest;
import io.github.hectorvent.floci.services.ecs.model.EnvironmentFile;
import io.github.hectorvent.floci.services.ecs.model.Failure;
import io.github.hectorvent.floci.services.ecs.model.EphemeralStorage;
import io.github.hectorvent.floci.services.ecs.model.FirelensConfiguration;
import io.github.hectorvent.floci.services.ecs.model.HealthCheck;
import io.github.hectorvent.floci.services.ecs.model.EcsCluster;
import io.github.hectorvent.floci.services.ecs.model.EcsLoadBalancer;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.KeyValuePair;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.ListTasksRequest;
import io.github.hectorvent.floci.services.ecs.model.LogConfiguration;
import io.github.hectorvent.floci.services.ecs.model.MountPoint;
import io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.PortMapping;
import io.github.hectorvent.floci.services.ecs.model.ProtectedTask;
import io.github.hectorvent.floci.services.ecs.model.RegisterTaskDefinitionRequest;
import io.github.hectorvent.floci.services.ecs.model.RunTaskRequest;
import io.github.hectorvent.floci.services.ecs.model.RuntimePlatform;
import io.github.hectorvent.floci.services.ecs.model.ServiceDeployment;
import io.github.hectorvent.floci.services.ecs.model.ServiceRevision;
import io.github.hectorvent.floci.services.ecs.model.Secret;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.ecs.model.TaskOverride;
import io.github.hectorvent.floci.services.ecs.model.TaskSet;
import io.github.hectorvent.floci.services.ecs.model.EfsVolumeConfiguration;
import io.github.hectorvent.floci.services.ecs.model.UpdateServiceRequest;
import io.github.hectorvent.floci.services.ecs.model.Volume;
import io.github.hectorvent.floci.services.ecs.model.VolumeFrom;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@ApplicationScoped
public class EcsJsonHandler {

    private final EcsService service;
    private final EcsResponseWriter writer;
    private final ObjectMapper objectMapper;
    private final HostVolumePolicy hostVolumePolicy;

    @Inject
    public EcsJsonHandler(EcsService service, EcsResponseWriter writer, ObjectMapper objectMapper,
                          HostVolumePolicy hostVolumePolicy) {
        this.service = service;
        this.writer = writer;
        this.objectMapper = objectMapper;
        this.hostVolumePolicy = hostVolumePolicy;
    }

    /** Builds its own response writer, for callers that assemble the handler without CDI. */
    public EcsJsonHandler(EcsService service, ObjectMapper objectMapper, HostVolumePolicy hostVolumePolicy) {
        this(service, new EcsResponseWriter(service, objectMapper), objectMapper, hostVolumePolicy);
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            // Clusters
            case "CreateCluster" -> handleCreateCluster(request, region);
            case "DescribeClusters" -> handleDescribeClusters(request, region);
            case "ListClusters" -> handleListClusters(request, region);
            case "DeleteCluster" -> handleDeleteCluster(request, region);
            case "UpdateCluster" -> handleUpdateCluster(request, region);
            case "UpdateClusterSettings" -> handleUpdateClusterSettings(request, region);
            case "PutClusterCapacityProviders" -> handlePutClusterCapacityProviders(request, region);

            case "RegisterTaskDefinition" -> handleRegisterTaskDefinition(request, region);
            case "DescribeTaskDefinition" -> handleDescribeTaskDefinition(request, region);
            case "ListTaskDefinitions" -> handleListTaskDefinitions(request, region);
            case "ListTaskDefinitionFamilies" -> handleListTaskDefinitionFamilies(request, region);
            case "DeregisterTaskDefinition" -> handleDeregisterTaskDefinition(request, region);
            case "DeleteTaskDefinitions" -> handleDeleteTaskDefinitions(request, region);

            case "RunTask" -> handleRunTask(request, region);
            case "StartTask" -> handleStartTask(request, region);
            case "StopTask" -> handleStopTask(request, region);
            case "DescribeTasks" -> handleDescribeTasks(request, region);
            case "ListTasks" -> handleListTasks(request, region);
            case "UpdateTaskProtection" -> handleUpdateTaskProtection(request, region);
            case "GetTaskProtection" -> handleGetTaskProtection(request, region);

            case "CreateService" -> handleCreateService(request, region);
            case "UpdateService" -> handleUpdateService(request, region);
            case "DeleteService" -> handleDeleteService(request, region);
            case "DescribeServices" -> handleDescribeServices(request, region);
            case "ListServices" -> handleListServices(request, region);
            case "ListServicesByNamespace" -> handleListServicesByNamespace(request, region);

            case "TagResource" -> handleTagResource(request, region);
            case "UntagResource" -> handleUntagResource(request, region);
            case "ListTagsForResource" -> handleListTagsForResource(request, region);

            case "PutAccountSetting" -> handlePutAccountSetting(request, region);
            case "PutAccountSettingDefault" -> handlePutAccountSettingDefault(request, region);
            case "DeleteAccountSetting" -> handleDeleteAccountSetting(request, region);
            case "ListAccountSettings" -> handleListAccountSettings(request, region);

            case "PutAttributes" -> handlePutAttributes(request, region);
            case "DeleteAttributes" -> handleDeleteAttributes(request, region);
            case "ListAttributes" -> handleListAttributes(request, region);

            case "RegisterContainerInstance" -> handleRegisterContainerInstance(request, region);
            case "DeregisterContainerInstance" -> handleDeregisterContainerInstance(request, region);
            case "DescribeContainerInstances" -> handleDescribeContainerInstances(request, region);
            case "ListContainerInstances" -> handleListContainerInstances(request, region);
            case "UpdateContainerAgent" -> handleUpdateContainerAgent(request, region);
            case "UpdateContainerInstancesState" -> handleUpdateContainerInstancesState(request, region);

            case "CreateCapacityProvider" -> handleCreateCapacityProvider(request, region);
            case "UpdateCapacityProvider" -> handleUpdateCapacityProvider(request, region);
            case "DeleteCapacityProvider" -> handleDeleteCapacityProvider(request, region);
            case "DescribeCapacityProviders" -> handleDescribeCapacityProviders(request, region);

            case "CreateTaskSet" -> handleCreateTaskSet(request, region);
            case "UpdateTaskSet" -> handleUpdateTaskSet(request, region);
            case "DeleteTaskSet" -> handleDeleteTaskSet(request, region);
            case "DescribeTaskSets" -> handleDescribeTaskSets(request, region);
            case "UpdateServicePrimaryTaskSet" -> handleUpdateServicePrimaryTaskSet(request, region);

            case "DescribeServiceDeployments" -> handleDescribeServiceDeployments(request, region);
            case "ListServiceDeployments" -> handleListServiceDeployments(request, region);
            case "DescribeServiceRevisions" -> handleDescribeServiceRevisions(request, region);

            case "SubmitTaskStateChange" -> handleSubmitTaskStateChange(request, region);
            case "SubmitContainerStateChange" -> handleSubmitContainerStateChange(request, region);
            case "SubmitAttachmentStateChanges" -> handleSubmitAttachmentStateChanges(request, region);
            case "DiscoverPollEndpoint" -> handleDiscoverPollEndpoint(request, region);

            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation",
                            "Operation " + action + " is not supported."))
                    .build();
        };
    }

    // ── Clusters ──────────────────────────────────────────────────────────────

    private Response handleCreateCluster(JsonNode req, String region) {
        CreateClusterRequest request = new CreateClusterRequest();
        request.setClusterName(req.has("clusterName") ? req.path("clusterName").asText() : null);
        request.setTags(parseTagMap(req.path("tags")));
        request.setSettings(parseClusterSettings(req.path("settings")));
        request.setConfiguration(parseRawObject(req.path("configuration")));
        request.setServiceConnectDefaults(parseRawObject(req.path("serviceConnectDefaults")));
        if (req.has("capacityProviders")) {
            request.setCapacityProviders(jsonArrayToList(req.path("capacityProviders")));
        }
        if (req.has("defaultCapacityProviderStrategy")) {
            request.setDefaultCapacityProviderStrategy(
                    parseRawObjectList(req.path("defaultCapacityProviderStrategy")));
        }
        EcsCluster cluster = service.createCluster(request, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("cluster", writer.clusterNode(cluster));
        return Response.ok(resp).build();
    }

    private Response handleDescribeClusters(JsonNode req, String region) {
        List<String> clusterIds = jsonArrayToList(req.path("clusters"));
        Set<String> include = parseClusterIncludes(req.path("include"));
        EcsService.DescribeClustersResult found = service.describeClustersDetailed(clusterIds, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        found.clusters().forEach(c -> arr.add(writer.clusterNode(c, include)));
        resp.set("clusters", arr);
        ArrayNode failures = objectMapper.createArrayNode();
        found.failures().forEach(f -> failures.add(writer.failureNode(f)));
        resp.set("failures", failures);
        return Response.ok(resp).build();
    }

    /** Rejects an unknown include the way ECS does, rather than silently returning less. */
    private Set<String> parseClusterIncludes(JsonNode node) {
        List<String> requested = jsonArrayToList(node);
        validateIncludes(requested, EcsResponseWriter.ALL_CLUSTER_INCLUDES);
        return Set.copyOf(requested);
    }

    /** The two fields DescribeContainerInstances withholds unless the request asks for them. */
    private static final Set<String> CONTAINER_INSTANCE_INCLUDES =
            Set.of("TAGS", "CONTAINER_INSTANCE_HEALTH");

    private static void validateIncludes(List<String> requested, Set<String> allowed) {
        for (String value : requested) {
            if (!allowed.contains(value)) {
                throw new AwsException("InvalidParameterException",
                        "Invalid include value: " + value, 400);
            }
        }
    }

    private Response handleListClusters(JsonNode req, String region) {
        Integer maxResults = req.hasNonNull("maxResults") ? req.path("maxResults").asInt() : null;
        String nextToken = req.hasNonNull("nextToken") ? req.path("nextToken").asText() : null;
        EcsService.ListPage page = service.listClusters(maxResults, nextToken, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        page.arns().forEach(arr::add);
        resp.set("clusterArns", arr);
        if (page.nextToken() != null) {
            resp.put("nextToken", page.nextToken());
        }
        return Response.ok(resp).build();
    }

    private Response handleDeleteCluster(JsonNode req, String region) {
        String clusterId = req.path("cluster").asText();
        EcsCluster cluster = service.deleteCluster(clusterId, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("cluster", writer.clusterNode(cluster));
        return Response.ok(resp).build();
    }

    private Response handleUpdateCluster(JsonNode req, String region) {
        String clusterId = req.path("cluster").asText();
        List<ClusterSetting> settings = parseClusterSettings(req.path("settings"));
        EcsCluster cluster = service.updateCluster(clusterId, settings,
                parseRawObject(req.path("configuration")),
                parseRawObject(req.path("serviceConnectDefaults")), region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("cluster", writer.clusterNode(cluster));
        return Response.ok(resp).build();
    }

    private Response handleUpdateClusterSettings(JsonNode req, String region) {
        String clusterId = req.path("cluster").asText();
        List<ClusterSetting> settings = parseClusterSettings(req.path("settings"));
        EcsCluster cluster = service.updateClusterSettings(clusterId, settings, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("cluster", writer.clusterNode(cluster));
        return Response.ok(resp).build();
    }

    private Response handlePutClusterCapacityProviders(JsonNode req, String region) {
        String clusterId = req.path("cluster").asText();
        List<String> providers = jsonArrayToList(req.path("capacityProviders"));
        List<Map<String, Object>> defaultStrategy = parseRawObjectList(req.path("defaultCapacityProviderStrategy"));
        EcsCluster cluster = service.putClusterCapacityProviders(clusterId, providers, defaultStrategy, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("cluster", writer.clusterNode(cluster));
        return Response.ok(resp).build();
    }

    // ── Task definitions ──────────────────────────────────────────────────────

    /** Members of RegisterTaskDefinition the parser consumes; the rest round-trips verbatim. */
    private static final Set<String> TASK_DEFINITION_CONSUMED = Set.of(
            "family", "containerDefinitions", "networkMode", "cpu", "memory", "taskRoleArn",
            "executionRoleArn", "requiresCompatibilities", "volumes", "runtimePlatform",
            "ephemeralStorage", "pidMode", "ipcMode", "tags");

    private Response handleRegisterTaskDefinition(JsonNode req, String region) {
        RegisterTaskDefinitionRequest request = new RegisterTaskDefinitionRequest();
        request.setFamily(req.path("family").asText());
        request.setContainerDefinitions(parseContainerDefinitions(req.path("containerDefinitions")));
        request.setNetworkMode(parseEnum(req, "networkMode", NetworkMode.class));
        request.setCpu(req.has("cpu") ? req.path("cpu").asText() : null);
        request.setMemory(req.has("memory") ? req.path("memory").asText() : null);
        request.setTaskRoleArn(req.hasNonNull("taskRoleArn") ? req.path("taskRoleArn").asText() : null);
        request.setExecutionRoleArn(req.hasNonNull("executionRoleArn")
                ? req.path("executionRoleArn").asText() : null);
        request.setRequiresCompatibilities(parseCompatibilities(req.path("requiresCompatibilities")));
        request.setVolumes(parseVolumes(req.path("volumes")));
        request.setRuntimePlatform(parseRuntimePlatform(req.path("runtimePlatform")));
        request.setEphemeralStorage(parseEphemeralStorage(req.path("ephemeralStorage")));
        request.setPidMode(parseChoice(req, "pidMode", PID_MODES));
        request.setIpcMode(parseChoice(req, "ipcMode", IPC_MODES));
        request.setTags(parseTagMap(req.path("tags")));
        request.setUnparsed(EcsJsonPassthrough.capture(req, objectMapper, TASK_DEFINITION_CONSUMED));

        TaskDefinition td = service.registerTaskDefinition(request, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("taskDefinition", writer.taskDefinitionNode(td));
        if (td.getTags() != null && !td.getTags().isEmpty()) {
            resp.set("tags", writer.tagsNode(td.getTags()));
        }
        return Response.ok(resp).build();
    }

    private Response handleDescribeTaskDefinition(JsonNode req, String region) {
        String tdRef = req.path("taskDefinition").asText();
        TaskDefinition td = service.describeTaskDefinition(tdRef, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("taskDefinition", writer.taskDefinitionNode(td));
        // DescribeTaskDefinition reports tags at the top level, and only when asked for them.
        if (jsonArrayToList(req.path("include")).contains("TAGS")
                && td.getTags() != null && !td.getTags().isEmpty()) {
            resp.set("tags", writer.tagsNode(td.getTags()));
        }
        return Response.ok(resp).build();
    }

    private Response handleListTaskDefinitions(JsonNode req, String region) {
        String familyPrefix = req.has("familyPrefix") ? req.path("familyPrefix").asText() : null;
        String status = parseChoice(req, "status", TASK_DEFINITION_STATUSES);
        String sort = parseChoice(req, "sort", SORT_ORDERS);
        Integer maxResults = req.hasNonNull("maxResults") ? req.path("maxResults").asInt() : null;
        String nextToken = req.hasNonNull("nextToken") ? req.path("nextToken").asText() : null;

        EcsService.ListPage page = service.listTaskDefinitions(familyPrefix, status, sort,
                maxResults, nextToken);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        page.arns().forEach(arr::add);
        resp.set("taskDefinitionArns", arr);
        if (page.nextToken() != null) {
            resp.put("nextToken", page.nextToken());
        }
        return Response.ok(resp).build();
    }

    private Response handleListTaskDefinitionFamilies(JsonNode req, String region) {
        String familyPrefix = req.has("familyPrefix") ? req.path("familyPrefix").asText() : null;
        String status = parseChoice(req, "status", FAMILY_STATUSES);
        Integer maxResults = req.hasNonNull("maxResults") ? req.path("maxResults").asInt() : null;
        String nextToken = req.hasNonNull("nextToken") ? req.path("nextToken").asText() : null;

        EcsService.ListPage page = service.listTaskDefinitionFamilies(familyPrefix, status,
                maxResults, nextToken);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        page.arns().forEach(arr::add);
        resp.set("families", arr);
        if (page.nextToken() != null) {
            resp.put("nextToken", page.nextToken());
        }
        return Response.ok(resp).build();
    }

    private Response handleDeregisterTaskDefinition(JsonNode req, String region) {
        String tdRef = req.path("taskDefinition").asText();
        TaskDefinition td = service.deregisterTaskDefinition(tdRef, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("taskDefinition", writer.taskDefinitionNode(td));
        return Response.ok(resp).build();
    }

    private Response handleDeleteTaskDefinitions(JsonNode req, String region) {
        List<String> refs = jsonArrayToList(req.path("taskDefinitions"));
        List<TaskDefinition> deleted = service.deleteTaskDefinitions(refs, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        deleted.forEach(td -> arr.add(writer.taskDefinitionNode(td)));
        resp.set("taskDefinitions", arr);
        resp.set("failures", objectMapper.createArrayNode());
        return Response.ok(resp).build();
    }

    // ── Tasks ─────────────────────────────────────────────────────────────────

    private Response handleRunTask(JsonNode req, String region) {
        RunTaskRequest request = parseRunTaskRequest(req);
        List<EcsTask> launched = service.runTask(request, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        launched.forEach(t -> arr.add(writer.taskNode(t)));
        resp.set("tasks", arr);
        resp.set("failures", objectMapper.createArrayNode());
        return Response.ok(resp).build();
    }

    private Response handleStartTask(JsonNode req, String region) {
        RunTaskRequest request = parseRunTaskRequest(req);
        request.setContainerInstances(jsonArrayToList(req.path("containerInstances")));
        List<EcsTask> launched = service.startTask(request, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        launched.forEach(t -> arr.add(writer.taskNode(t)));
        resp.set("tasks", arr);
        resp.set("failures", objectMapper.createArrayNode());
        return Response.ok(resp).build();
    }

    private RunTaskRequest parseRunTaskRequest(JsonNode req) {
        RunTaskRequest request = new RunTaskRequest();
        request.setCluster(req.has("cluster") ? req.path("cluster").asText() : null);
        request.setTaskDefinition(req.path("taskDefinition").asText());
        request.setCount(req.path("count").asInt(1));
        request.setLaunchType(parseEnum(req, "launchType", LaunchType.class));
        request.setCapacityProviderStrategy(parseCapacityProviderStrategy(req.path("capacityProviderStrategy")));
        request.setGroup(req.has("group") ? req.path("group").asText() : null);
        request.setStartedBy(req.has("startedBy") ? req.path("startedBy").asText() : null);
        request.setOverrides(parseTaskOverride(req.path("overrides")));
        request.setNetworkConfiguration(parseNetworkConfiguration(req.path("networkConfiguration")));
        request.setPlatformVersion(req.hasNonNull("platformVersion")
                ? req.path("platformVersion").asText() : null);
        request.setEnableExecuteCommand(req.path("enableExecuteCommand").asBoolean(false));
        request.setEnableECSManagedTags(req.path("enableECSManagedTags").asBoolean(false));
        request.setPropagateTags(parseChoice(req, "propagateTags", PROPAGATE_TAGS));
        request.setReferenceId(req.has("referenceId") ? req.path("referenceId").asText() : null);
        request.setTags(parseTagMap(req.path("tags")));
        return request;
    }

    private Response handleStopTask(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String task = req.path("task").asText();
        String reason = req.has("reason") ? req.path("reason").asText() : null;

        EcsTask stopped = service.stopTask(cluster, task, reason, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("task", writer.taskNode(stopped));
        return Response.ok(resp).build();
    }

    private Response handleDescribeTasks(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<String> taskRefs = jsonArrayToList(req.path("tasks"));
        boolean includeTags = jsonArrayToList(req.path("include")).contains("TAGS");
        EcsService.DescribeTasksResult found = service.describeTasksDetailed(cluster, taskRefs, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        found.tasks().forEach(t -> arr.add(writer.taskNode(t, includeTags)));
        resp.set("tasks", arr);
        ArrayNode failures = objectMapper.createArrayNode();
        found.failures().forEach(f -> failures.add(writer.failureNode(f)));
        resp.set("failures", failures);
        return Response.ok(resp).build();
    }

    private Response handleListTasks(JsonNode req, String region) {
        ListTasksRequest request = new ListTasksRequest();
        request.setCluster(req.has("cluster") ? req.path("cluster").asText() : null);
        request.setContainerInstance(req.hasNonNull("containerInstance")
                ? req.path("containerInstance").asText() : null);
        request.setDesiredStatus(parseChoice(req, "desiredStatus", DESIRED_STATUSES));
        request.setFamily(req.has("family") ? req.path("family").asText() : null);
        request.setLaunchType(parseEnum(req, "launchType", LaunchType.class));
        request.setServiceName(req.has("serviceName") ? req.path("serviceName").asText() : null);
        request.setStartedBy(req.hasNonNull("startedBy") ? req.path("startedBy").asText() : null);
        request.setMaxResults(req.hasNonNull("maxResults") ? req.path("maxResults").asInt() : null);
        request.setNextToken(req.hasNonNull("nextToken") ? req.path("nextToken").asText() : null);

        EcsService.ListPage result = service.listTasks(request, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        result.arns().forEach(arr::add);
        resp.set("taskArns", arr);
        if (result.nextToken() != null) {
            resp.put("nextToken", result.nextToken());
        }
        return Response.ok(resp).build();
    }

    private Response handleUpdateTaskProtection(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<String> taskRefs = jsonArrayToList(req.path("tasks"));
        boolean protectionEnabled = req.path("protectionEnabled").asBoolean(false);
        Integer expiresInMinutes = req.has("expiresInMinutes") ? req.path("expiresInMinutes").asInt() : null;

        List<ProtectedTask> result = service.updateTaskProtection(cluster, taskRefs, protectionEnabled,
                expiresInMinutes, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        result.forEach(pt -> arr.add(writer.protectedTaskNode(pt)));
        resp.set("protectedTasks", arr);
        resp.set("failures", objectMapper.createArrayNode());
        return Response.ok(resp).build();
    }

    private Response handleGetTaskProtection(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<String> taskRefs = jsonArrayToList(req.path("tasks"));

        List<ProtectedTask> result = service.getTaskProtection(cluster, taskRefs, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        result.forEach(pt -> arr.add(writer.protectedTaskNode(pt)));
        resp.set("protectedTasks", arr);
        resp.set("failures", objectMapper.createArrayNode());
        return Response.ok(resp).build();
    }

    // ── Services ──────────────────────────────────────────────────────────────

    /**
     * Members of CreateService the parser consumes. {@code clientToken} is deliberately not
     * round-tripped: it is a request-only member and has no place on the Service shape.
     */
    private static final Set<String> SERVICE_CONSUMED = Set.of(
            "cluster", "serviceName", "taskDefinition", "service", "desiredCount", "launchType",
            "capacityProviderStrategy", "platformVersion", "loadBalancers", "serviceRegistries",
            "networkConfiguration", "tags", "schedulingStrategy", "deploymentController",
            "availabilityZoneRebalancing", "serviceConnectConfiguration", "deploymentConfiguration",
            "enableExecuteCommand", "enableECSManagedTags", "propagateTags",
            "healthCheckGracePeriodSeconds", "role", "clientToken", "forceNewDeployment");

    private Response handleCreateService(JsonNode req, String region) {
        CreateServiceRequest request = new CreateServiceRequest();
        request.setCluster(req.has("cluster") ? req.path("cluster").asText() : null);
        request.setServiceName(req.path("serviceName").asText());
        request.setTaskDefinition(req.path("taskDefinition").asText());
        request.setDesiredCount(req.path("desiredCount").asInt(1));
        request.setLaunchType(parseEnum(req, "launchType", LaunchType.class));
        request.setCapacityProviderStrategy(parseCapacityProviderStrategy(req.path("capacityProviderStrategy")));
        request.setPlatformVersion(req.hasNonNull("platformVersion")
                ? req.path("platformVersion").asText() : null);
        request.setLoadBalancers(parseLoadBalancers(req.path("loadBalancers")));
        request.setServiceRegistries(parseRawObjectList(req.path("serviceRegistries")));
        request.setNetworkConfiguration(parseNetworkConfiguration(req.path("networkConfiguration")));
        request.setTags(parseTagMap(req.path("tags")));
        request.setSchedulingStrategy(parseChoice(req, "schedulingStrategy", SCHEDULING_STRATEGIES));
        request.setDeploymentControllerType(parseChoice(req.path("deploymentController"), "type",
                "deploymentController.type", DEPLOYMENT_CONTROLLER_TYPES));
        request.setAvailabilityZoneRebalancing(parseChoice(req, "availabilityZoneRebalancing", AZ_REBALANCING));
        request.setServiceConnectConfiguration(parseRawObject(req.path("serviceConnectConfiguration")));
        request.setDeploymentConfiguration(parseRawObject(req.path("deploymentConfiguration")));
        request.setEnableExecuteCommand(req.path("enableExecuteCommand").asBoolean(false));
        request.setEnableECSManagedTags(req.path("enableECSManagedTags").asBoolean(false));
        request.setPropagateTags(parseChoice(req, "propagateTags", PROPAGATE_TAGS));
        request.setHealthCheckGracePeriodSeconds(req.hasNonNull("healthCheckGracePeriodSeconds")
                ? req.path("healthCheckGracePeriodSeconds").asInt() : null);
        request.setRoleArn(req.hasNonNull("role") ? req.path("role").asText() : null);
        request.setUnparsed(EcsJsonPassthrough.capture(req, objectMapper, SERVICE_CONSUMED));

        EcsServiceModel svc = service.createService(request, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("service", writer.serviceNode(svc));
        return Response.ok(resp).build();
    }

    private Response handleUpdateService(JsonNode req, String region) {
        UpdateServiceRequest request = new UpdateServiceRequest();
        request.setCluster(req.has("cluster") ? req.path("cluster").asText() : null);
        request.setService(req.path("service").asText());
        request.setTaskDefinition(req.has("taskDefinition") ? req.path("taskDefinition").asText() : null);
        request.setDesiredCount(req.has("desiredCount") ? req.path("desiredCount").asInt() : null);
        request.setNetworkConfiguration(parseNetworkConfiguration(req.path("networkConfiguration")));
        request.setAvailabilityZoneRebalancing(parseChoice(req, "availabilityZoneRebalancing", AZ_REBALANCING));
        request.setForceNewDeployment(req.path("forceNewDeployment").asBoolean(false));
        request.setServiceConnectConfiguration(parseRawObject(req.path("serviceConnectConfiguration")));
        request.setCapacityProviderStrategy(parseCapacityProviderStrategy(req.path("capacityProviderStrategy")));
        request.setPlatformVersion(req.hasNonNull("platformVersion")
                ? req.path("platformVersion").asText() : null);
        request.setEnableExecuteCommand(req.hasNonNull("enableExecuteCommand")
                ? req.path("enableExecuteCommand").asBoolean() : null);
        request.setEnableECSManagedTags(req.hasNonNull("enableECSManagedTags")
                ? req.path("enableECSManagedTags").asBoolean() : null);
        request.setPropagateTags(parseChoice(req, "propagateTags", PROPAGATE_TAGS));
        request.setHealthCheckGracePeriodSeconds(req.hasNonNull("healthCheckGracePeriodSeconds")
                ? req.path("healthCheckGracePeriodSeconds").asInt() : null);
        request.setDeploymentConfiguration(parseRawObject(req.path("deploymentConfiguration")));
        if (req.has("loadBalancers")) {
            request.setLoadBalancers(parseLoadBalancers(req.path("loadBalancers")));
        }
        if (req.has("serviceRegistries")) {
            request.setServiceRegistries(parseRawObjectList(req.path("serviceRegistries")));
        }
        request.setUnparsed(EcsJsonPassthrough.capture(req, objectMapper, SERVICE_CONSUMED));

        EcsServiceModel svc = service.updateService(request, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("service", writer.serviceNode(svc));
        return Response.ok(resp).build();
    }

    private List<EcsLoadBalancer> parseLoadBalancers(JsonNode node) {
        List<EcsLoadBalancer> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode lb : node) {
            String targetGroupArn = lb.hasNonNull("targetGroupArn")
                    ? lb.path("targetGroupArn").asText() : null;
            String loadBalancerName = lb.hasNonNull("loadBalancerName")
                    ? lb.path("loadBalancerName").asText() : null;
            String containerName = lb.hasNonNull("containerName")
                    ? lb.path("containerName").asText() : null;
            Integer containerPort = lb.hasNonNull("containerPort")
                    ? lb.path("containerPort").asInt() : null;

            // AWS rejects malformed loadBalancers entries with InvalidParameterException.
            // containerName + containerPort are always required; an entry must target
            // either a target group (ALB/NLB) or a classic load balancer by name.
            if (containerName == null || containerName.isBlank()) {
                throw new AwsException("InvalidParameterException",
                        "loadBalancers entry is missing the required containerName.", 400);
            }
            if (containerPort == null) {
                throw new AwsException("InvalidParameterException",
                        "loadBalancers entry is missing the required containerPort.", 400);
            }
            boolean hasTargetGroup = targetGroupArn != null && !targetGroupArn.isBlank();
            boolean hasLoadBalancerName = loadBalancerName != null && !loadBalancerName.isBlank();
            if (!hasTargetGroup && !hasLoadBalancerName) {
                throw new AwsException("InvalidParameterException",
                        "loadBalancers entry must specify either targetGroupArn or loadBalancerName.", 400);
            }

            EcsLoadBalancer m = new EcsLoadBalancer();
            m.setTargetGroupArn(targetGroupArn);
            m.setLoadBalancerName(loadBalancerName);
            m.setContainerName(containerName);
            m.setContainerPort(containerPort);
            // The blue/green listener and alternate target group wiring, kept raw: Floci shifts no
            // traffic, but a service created with it has to read back with it.
            m.setAdvancedConfiguration(parseRawObject(lb.path("advancedConfiguration")));
            result.add(m);
        }
        return result;
    }

    /** Parse an ECS {@code networkConfiguration} node (camelCase, as the data-plane API uses).
     *  Public so the Step Functions ecs:runTask integration can reuse it after recasing its
     *  PascalCase input, rather than duplicating the awsvpc parsing. */
    public NetworkConfiguration parseNetworkConfiguration(JsonNode node) {
        if (node == null || !node.isObject() || !node.hasNonNull("awsvpcConfiguration")) {
            return null;
        }
        JsonNode awsvpc = node.path("awsvpcConfiguration");
        AwsVpcConfiguration awsvpcConfig = new AwsVpcConfiguration();
        awsvpcConfig.setSubnets(jsonArrayToList(awsvpc.path("subnets")));
        awsvpcConfig.setSecurityGroups(jsonArrayToList(awsvpc.path("securityGroups")));
        if (awsvpc.hasNonNull("assignPublicIp")) {
            awsvpcConfig.setAssignPublicIp(awsvpc.path("assignPublicIp").asText());
        }
        NetworkConfiguration networkConfiguration = new NetworkConfiguration();
        networkConfiguration.setAwsvpcConfiguration(awsvpcConfig);
        return networkConfiguration;
    }

    private Response handleDeleteService(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String serviceName = req.path("service").asText();
        boolean force = req.path("force").asBoolean(false);

        EcsServiceModel svc = service.deleteService(cluster, serviceName, force, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("service", writer.serviceNode(svc));
        return Response.ok(resp).build();
    }

    private Response handleDescribeServices(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<String> serviceIds = jsonArrayToList(req.path("services"));

        boolean includeTags = jsonArrayToList(req.path("include")).contains("TAGS");
        EcsService.DescribeServicesResult found =
                service.describeServicesDetailed(cluster, serviceIds, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        found.services().forEach(s -> arr.add(writer.serviceNode(s, includeTags)));
        resp.set("services", arr);
        ArrayNode failures = objectMapper.createArrayNode();
        found.failures().forEach(f -> failures.add(writer.failureNode(f)));
        resp.set("failures", failures);
        return Response.ok(resp).build();
    }

    private Response handleListServices(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        LaunchType launchType = parseEnum(req, "launchType", LaunchType.class);
        String schedulingStrategy = parseChoice(req, "schedulingStrategy", SCHEDULING_STRATEGIES);
        Integer maxResults = req.hasNonNull("maxResults") ? req.path("maxResults").asInt() : null;
        String nextToken = req.hasNonNull("nextToken") ? req.path("nextToken").asText() : null;

        EcsService.ListPage page = service.listServices(cluster, launchType, schedulingStrategy,
                maxResults, nextToken, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        page.arns().forEach(arr::add);
        resp.set("serviceArns", arr);
        if (page.nextToken() != null) {
            resp.put("nextToken", page.nextToken());
        }
        return Response.ok(resp).build();
    }

    private Response handleListServicesByNamespace(JsonNode req, String region) {
        String namespace = req.path("namespace").asText();
        List<String> arns = service.listServicesByNamespace(namespace, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        arns.forEach(arr::add);
        resp.set("serviceArns", arr);
        return Response.ok(resp).build();
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    private Response handleTagResource(JsonNode req, String region) {
        String resourceArn = req.path("resourceArn").asText();
        Map<String, String> tags = parseTagMap(req.path("tags"));
        service.tagResource(resourceArn, tags);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode req, String region) {
        String resourceArn = req.path("resourceArn").asText();
        List<String> tagKeys = jsonArrayToList(req.path("tagKeys"));
        service.untagResource(resourceArn, tagKeys);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListTagsForResource(JsonNode req, String region) {
        String resourceArn = req.path("resourceArn").asText();
        Map<String, String> tags = service.listTagsForResource(resourceArn);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("tags", writer.tagsNode(tags));
        return Response.ok(resp).build();
    }

    // ── Account Settings ──────────────────────────────────────────────────────

    private Response handlePutAccountSetting(JsonNode req, String region) {
        EcsService.AccountSetting setting = service.putAccountSetting(
                req.path("name").asText(), req.path("value").asText(), principalArn(req));
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("setting", writer.settingNode(setting));
        return Response.ok(resp).build();
    }

    private Response handlePutAccountSettingDefault(JsonNode req, String region) {
        EcsService.AccountSetting setting = service.putAccountSettingDefault(
                req.path("name").asText(), req.path("value").asText());
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("setting", writer.settingNode(setting));
        return Response.ok(resp).build();
    }

    private Response handleDeleteAccountSetting(JsonNode req, String region) {
        EcsService.AccountSetting setting =
                service.deleteAccountSetting(req.path("name").asText(), principalArn(req));
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("setting", writer.settingNode(setting));
        return Response.ok(resp).build();
    }

    private Response handleListAccountSettings(JsonNode req, String region) {
        EcsService.AccountSettingPage page = service.listAccountSettings(
                req.hasNonNull("name") ? req.path("name").asText() : null,
                req.hasNonNull("value") ? req.path("value").asText() : null,
                principalArn(req),
                req.path("effectiveSettings").asBoolean(false),
                req.hasNonNull("maxResults") ? req.path("maxResults").asInt() : null,
                req.hasNonNull("nextToken") ? req.path("nextToken").asText() : null);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        page.settings().forEach(s -> arr.add(writer.settingNode(s)));
        resp.set("settings", arr);
        if (page.nextToken() != null) {
            resp.put("nextToken", page.nextToken());
        }
        return Response.ok(resp).build();
    }

    private static String principalArn(JsonNode req) {
        return req.hasNonNull("principalArn") ? req.path("principalArn").asText() : null;
    }

    // ── Attributes ────────────────────────────────────────────────────────────

    private Response handlePutAttributes(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<Attribute> attrs = parseAttributes(req.path("attributes"));
        List<Attribute> stored = service.putAttributes(cluster, attrs, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        stored.forEach(a -> arr.add(writer.attributeNode(a)));
        resp.set("attributes", arr);
        return Response.ok(resp).build();
    }

    private Response handleDeleteAttributes(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<Attribute> attrs = parseAttributes(req.path("attributes"));
        List<Attribute> deleted = service.deleteAttributes(cluster, attrs, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        deleted.forEach(a -> arr.add(writer.attributeNode(a)));
        resp.set("attributes", arr);
        return Response.ok(resp).build();
    }

    private Response handleListAttributes(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String targetType = req.hasNonNull("targetType") ? req.path("targetType").asText() : null;
        String attributeName = req.hasNonNull("attributeName") ? req.path("attributeName").asText() : null;
        String attributeValue = req.hasNonNull("attributeValue") ? req.path("attributeValue").asText() : null;
        Integer maxResults = req.hasNonNull("maxResults") ? req.path("maxResults").asInt() : null;
        String nextToken = req.hasNonNull("nextToken") ? req.path("nextToken").asText() : null;

        EcsService.ListAttributesPage page = service.listAttributes(cluster, targetType,
                attributeName, attributeValue, maxResults, nextToken, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        page.attributes().forEach(a -> arr.add(writer.attributeNode(a)));
        resp.set("attributes", arr);
        if (page.nextToken() != null) {
            resp.put("nextToken", page.nextToken());
        }
        return Response.ok(resp).build();
    }

    // ── Container Instances ───────────────────────────────────────────────────

    private Response handleRegisterContainerInstance(JsonNode req, String region) {
        RegisterContainerInstanceRequest request = new RegisterContainerInstanceRequest();
        request.setCluster(req.has("cluster") ? req.path("cluster").asText() : null);
        request.setContainerInstanceArn(req.hasNonNull("containerInstanceArn")
                ? req.path("containerInstanceArn").asText() : null);
        request.setInstanceIdentityDocument(req.hasNonNull("instanceIdentityDocument")
                ? req.path("instanceIdentityDocument").asText() : null);
        request.setAttributes(parseAttributes(req.path("attributes")));
        if (req.has("totalResources")) {
            request.setTotalResources(parseRawObjectList(req.path("totalResources")));
        }
        request.setVersionInfo(parseRawObject(req.path("versionInfo")));
        if (req.has("platformDevices")) {
            request.setPlatformDevices(parseRawObjectList(req.path("platformDevices")));
        }
        request.setTags(parseTagMap(req.path("tags")));
        ContainerInstance instance = service.registerContainerInstance(request, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("containerInstance", writer.containerInstanceNode(instance));
        return Response.ok(resp).build();
    }

    private Response handleDeregisterContainerInstance(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String containerInstance = req.path("containerInstance").asText();
        boolean force = req.path("force").asBoolean(false);
        ContainerInstance instance = service.deregisterContainerInstance(cluster, containerInstance, force, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("containerInstance", writer.containerInstanceNode(instance));
        return Response.ok(resp).build();
    }

    private Response handleDescribeContainerInstances(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<String> instanceRefs = jsonArrayToList(req.path("containerInstances"));
        List<String> include = jsonArrayToList(req.path("include"));
        validateIncludes(include, CONTAINER_INSTANCE_INCLUDES);
        boolean includeTags = include.contains("TAGS");
        boolean includeHealth = include.contains("CONTAINER_INSTANCE_HEALTH");
        EcsService.DescribeContainerInstancesResult found =
                service.describeContainerInstancesDetailed(cluster, instanceRefs, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        found.instances().forEach(ci ->
                arr.add(writer.containerInstanceNode(ci, includeTags, includeHealth)));
        resp.set("containerInstances", arr);
        ArrayNode failures = objectMapper.createArrayNode();
        found.failures().forEach(f -> failures.add(writer.failureNode(f)));
        resp.set("failures", failures);
        return Response.ok(resp).build();
    }

    private Response handleListContainerInstances(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String status = parseChoice(req, "status", CONTAINER_INSTANCE_STATUSES);
        Integer maxResults = req.hasNonNull("maxResults") ? req.path("maxResults").asInt() : null;
        String nextToken = req.hasNonNull("nextToken") ? req.path("nextToken").asText() : null;
        EcsService.ListPage page =
                service.listContainerInstances(cluster, status, maxResults, nextToken, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        page.arns().forEach(arr::add);
        resp.set("containerInstanceArns", arr);
        if (page.nextToken() != null) {
            resp.put("nextToken", page.nextToken());
        }
        return Response.ok(resp).build();
    }

    private Response handleUpdateContainerAgent(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String containerInstance = req.path("containerInstance").asText();
        ContainerInstance instance = service.updateContainerAgent(cluster, containerInstance, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("containerInstance", writer.containerInstanceNode(instance));
        return Response.ok(resp).build();
    }

    private Response handleUpdateContainerInstancesState(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<String> instanceRefs = jsonArrayToList(req.path("containerInstances"));
        String status = req.path("status").asText();
        EcsService.UpdateContainerInstancesStateResult updated =
                service.updateContainerInstancesState(cluster, instanceRefs, status, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        updated.instances().forEach(ci -> arr.add(writer.containerInstanceNode(ci)));
        resp.set("containerInstances", arr);
        ArrayNode failures = objectMapper.createArrayNode();
        updated.failures().forEach(f -> failures.add(writer.failureNode(f)));
        resp.set("failures", failures);
        return Response.ok(resp).build();
    }

    // ── Capacity Providers ────────────────────────────────────────────────────

    private Response handleCreateCapacityProvider(JsonNode req, String region) {
        String name = req.path("name").asText();
        Map<String, Object> asgProvider = parseRawObject(req.path("autoScalingGroupProvider"));
        Map<String, String> tags = parseTagMap(req.path("tags"));
        CapacityProvider cp = service.createCapacityProvider(name, asgProvider, tags, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("capacityProvider", writer.capacityProviderNode(cp));
        return Response.ok(resp).build();
    }

    private Response handleUpdateCapacityProvider(JsonNode req, String region) {
        String name = req.path("name").asText();
        Map<String, Object> asgProvider = parseRawObject(req.path("autoScalingGroupProvider"));
        CapacityProvider cp = service.updateCapacityProvider(name, asgProvider);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("capacityProvider", writer.capacityProviderNode(cp));
        return Response.ok(resp).build();
    }

    private Response handleDeleteCapacityProvider(JsonNode req, String region) {
        String nameOrArn = req.path("capacityProvider").asText();
        CapacityProvider cp = service.deleteCapacityProvider(nameOrArn);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("capacityProvider", writer.capacityProviderNode(cp));
        return Response.ok(resp).build();
    }

    private Response handleDescribeCapacityProviders(JsonNode req, String region) {
        List<String> providers = req.has("capacityProviders") ? jsonArrayToList(req.path("capacityProviders")) : null;
        boolean includeTags = jsonArrayToList(req.path("include")).contains("TAGS");
        EcsService.DescribeCapacityProvidersResult result =
                service.describeCapacityProvidersDetailed(providers, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        result.capacityProviders().forEach(cp -> arr.add(writer.capacityProviderNode(cp, includeTags)));
        resp.set("capacityProviders", arr);
        ArrayNode failures = objectMapper.createArrayNode();
        result.failures().forEach(f -> failures.add(writer.failureNode(f)));
        resp.set("failures", failures);
        return Response.ok(resp).build();
    }

    // ── Task Sets ─────────────────────────────────────────────────────────────

    private Response handleCreateTaskSet(JsonNode req, String region) {
        CreateTaskSetRequest request = new CreateTaskSetRequest();
        request.setCluster(req.has("cluster") ? req.path("cluster").asText() : null);
        request.setService(req.path("service").asText());
        request.setTaskDefinition(req.path("taskDefinition").asText());
        request.setLaunchType(parseEnum(req, "launchType", LaunchType.class));
        request.setCapacityProviderStrategy(
                parseCapacityProviderStrategy(req.path("capacityProviderStrategy")));
        request.setPlatformVersion(req.hasNonNull("platformVersion")
                ? req.path("platformVersion").asText() : null);
        if (req.hasNonNull("scale")) {
            request.setScaleValue(req.path("scale").path("value").asDouble(100.0));
            request.setScaleUnit(req.path("scale").hasNonNull("unit")
                    ? req.path("scale").path("unit").asText() : null);
        }
        request.setExternalId(req.has("externalId") ? req.path("externalId").asText() : null);
        request.setNetworkConfiguration(parseNetworkConfiguration(req.path("networkConfiguration")));
        request.setLoadBalancers(parseLoadBalancers(req.path("loadBalancers")));
        request.setServiceRegistries(req.has("serviceRegistries")
                ? parseRawObjectList(req.path("serviceRegistries")) : null);
        request.setTags(parseTagMap(req.path("tags")));

        TaskSet ts = service.createTaskSet(request, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("taskSet", writer.taskSetNode(ts));
        return Response.ok(resp).build();
    }

    private Response handleUpdateTaskSet(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String svc = req.path("service").asText();
        String taskSet = req.path("taskSet").asText();
        double scaleValue = req.path("scale").path("value").asDouble(100.0);
        String scaleUnit = req.path("scale").hasNonNull("unit")
                ? req.path("scale").path("unit").asText() : null;

        TaskSet ts = service.updateTaskSet(cluster, svc, taskSet, scaleValue, scaleUnit, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("taskSet", writer.taskSetNode(ts));
        return Response.ok(resp).build();
    }

    private Response handleDeleteTaskSet(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String svc = req.path("service").asText();
        String taskSet = req.path("taskSet").asText();
        boolean force = req.path("force").asBoolean(false);

        TaskSet ts = service.deleteTaskSet(cluster, svc, taskSet, force, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("taskSet", writer.taskSetNode(ts));
        return Response.ok(resp).build();
    }

    private Response handleDescribeTaskSets(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String svc = req.path("service").asText();
        List<String> taskSetRefs = req.has("taskSets") ? jsonArrayToList(req.path("taskSets")) : null;
        boolean includeTags = jsonArrayToList(req.path("include")).contains("TAGS");

        EcsService.DescribeTaskSetsResult found =
                service.describeTaskSetsDetailed(cluster, svc, taskSetRefs, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        found.taskSets().forEach(ts -> arr.add(writer.taskSetNode(ts, includeTags)));
        resp.set("taskSets", arr);
        ArrayNode failures = objectMapper.createArrayNode();
        found.failures().forEach(f -> failures.add(writer.failureNode(f)));
        resp.set("failures", failures);
        return Response.ok(resp).build();
    }

    private Response handleUpdateServicePrimaryTaskSet(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String svc = req.path("service").asText();
        String primaryTaskSet = req.path("primaryTaskSet").asText();

        TaskSet ts = service.updateServicePrimaryTaskSet(cluster, svc, primaryTaskSet, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("taskSet", writer.taskSetNode(ts));
        return Response.ok(resp).build();
    }

    // ── Service Deployments & Revisions ───────────────────────────────────────

    private Response handleDescribeServiceDeployments(JsonNode req, String region) {
        List<String> arns = jsonArrayToList(req.path("serviceDeploymentArns"));
        List<ServiceDeployment> found = service.describeServiceDeployments(arns);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        found.forEach(d -> arr.add(writer.serviceDeploymentNode(d)));
        resp.set("serviceDeployments", arr);
        resp.set("failures", missingFailures(arns,
                found.stream().map(ServiceDeployment::getServiceDeploymentArn).toList()));
        return Response.ok(resp).build();
    }

    /** The references that resolved to nothing, reported as ECS reports them rather than dropped. */
    private ArrayNode missingFailures(List<String> requested, List<String> resolved) {
        ArrayNode failures = objectMapper.createArrayNode();
        requested.stream()
                .filter(arn -> !resolved.contains(arn))
                .forEach(arn -> failures.add(writer.failureNode(Failure.missing(arn))));
        return failures;
    }

    private Response handleListServiceDeployments(JsonNode req, String region) {
        String svc = req.path("service").asText();
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<String> statusFilter = req.has("status") ? jsonArrayToList(req.path("status")) : null;

        List<ServiceDeployment> deployments = service.listServiceDeploymentsDetailed(svc, cluster, statusFilter, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        deployments.forEach(d -> {
            ObjectNode brief = objectMapper.createObjectNode();
            brief.put("serviceDeploymentArn", d.getServiceDeploymentArn());
            brief.put("serviceArn", d.getServiceArn());
            brief.put("clusterArn", d.getClusterArn());
            brief.put("status", d.getStatus());
            if (d.getCreatedAt() != null) { brief.put("createdAt", d.getCreatedAt().toEpochMilli() / 1000.0); }
            if (d.getStartedAt() != null) { brief.put("startedAt", d.getStartedAt().toEpochMilli() / 1000.0); }
            if (d.getFinishedAt() != null) { brief.put("finishedAt", d.getFinishedAt().toEpochMilli() / 1000.0); }
            if (d.getTargetServiceRevisionArn() != null) {
                brief.put("targetServiceRevisionArn", d.getTargetServiceRevisionArn());
            }
            arr.add(brief);
        });
        resp.set("serviceDeployments", arr);
        return Response.ok(resp).build();
    }

    private Response handleDescribeServiceRevisions(JsonNode req, String region) {
        List<String> arns = jsonArrayToList(req.path("serviceRevisionArns"));
        List<ServiceRevision> found = service.describeServiceRevisions(arns);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        found.forEach(r -> arr.add(writer.serviceRevisionNode(r)));
        resp.set("serviceRevisions", arr);
        resp.set("failures", missingFailures(arns,
                found.stream().map(ServiceRevision::getServiceRevisionArn).toList()));
        return Response.ok(resp).build();
    }

    // ── Stubs ─────────────────────────────────────────────────────────────────

    private Response handleSubmitTaskStateChange(JsonNode req, String region) {
        String ack = service.submitTaskStateChange();
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("acknowledgment", ack);
        return Response.ok(resp).build();
    }

    private Response handleSubmitContainerStateChange(JsonNode req, String region) {
        String ack = service.submitContainerStateChange();
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("acknowledgment", ack);
        return Response.ok(resp).build();
    }

    private Response handleSubmitAttachmentStateChanges(JsonNode req, String region) {
        String ack = service.submitAttachmentStateChanges();
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("acknowledgment", ack);
        return Response.ok(resp).build();
    }

    private Response handleDiscoverPollEndpoint(JsonNode req, String region) {
        String baseUrl = service.getBaseUrl();
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("endpoint", baseUrl);
        resp.put("telemetryEndpoint", baseUrl);
        resp.put("serviceConnectEndpoint", baseUrl);
        return Response.ok(resp).build();
    }

    /** Renders an ECS task to its data-plane JSON shape, for the Step Functions ecs:runTask
     *  integration ({@link io.github.hectorvent.floci.services.stepfunctions.AslExecutor}). */
    public ObjectNode taskNode(EcsTask task) {
        return writer.taskNode(task);
    }

    // ── Parsing helpers ───────────────────────────────────────────────────────

    /** Members of a container definition the parser consumes; the rest round-trips verbatim. */
    private static final Set<String> CONTAINER_DEFINITION_CONSUMED = Set.of(
            "name", "image", "essential", "cpu", "memory", "memoryReservation", "portMappings",
            "environment", "environmentFiles", "secrets", "mountPoints", "volumesFrom", "dependsOn",
            "logConfiguration", "firelensConfiguration", "healthCheck", "command", "entryPoint",
            "startTimeout", "stopTimeout", "user", "workingDirectory", "hostname",
            "readonlyRootFilesystem", "privileged", "disableNetworking", "interactive",
            "pseudoTerminal", "links", "dnsServers", "dnsSearchDomains", "dockerSecurityOptions",
            "dockerLabels", "repositoryCredentials");

    private List<ContainerDefinition> parseContainerDefinitions(JsonNode node) {
        List<ContainerDefinition> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            ContainerDefinition def = new ContainerDefinition();
            def.setName(item.path("name").asText());
            def.setImage(item.path("image").asText());
            def.setEssential(item.path("essential").asBoolean(true));
            if (item.has("cpu")) { def.setCpu(item.path("cpu").asInt()); }
            if (item.has("memory")) { def.setMemory(item.path("memory").asInt()); }
            if (item.has("memoryReservation")) { def.setMemoryReservation(item.path("memoryReservation").asInt()); }

            def.setPortMappings(parsePortMappings(item.path("portMappings")));
            def.setEnvironment(parseKeyValuePairs(item.path("environment")));
            if (item.has("environmentFiles")) {
                def.setEnvironmentFiles(parseEnvironmentFiles(item.path("environmentFiles")));
            }
            if (item.has("secrets")) {
                def.setSecrets(parseSecrets(item.path("secrets")));
            }
            def.setMountPoints(parseMountPoints(item.path("mountPoints")));
            def.setVolumesFrom(parseVolumesFrom(item.path("volumesFrom")));
            if (item.has("dependsOn")) {
                def.setDependsOn(parseDependsOn(item.path("dependsOn"), def.getName()));
            }
            def.setLogConfiguration(parseLogConfiguration(item.path("logConfiguration")));
            def.setFirelensConfiguration(parseFirelensConfiguration(
                    item.path("firelensConfiguration"), result.size() + 1));
            if (item.has("healthCheck")) {
                def.setHealthCheck(parseHealthCheck(item.path("healthCheck")));
            }

            if (item.has("command") && item.path("command").isArray()) {
                def.setCommand(jsonArrayToList(item.path("command")));
            }
            if (item.has("entryPoint") && item.path("entryPoint").isArray()) {
                def.setEntryPoint(jsonArrayToList(item.path("entryPoint")));
            }

            if (item.hasNonNull("startTimeout")) { def.setStartTimeout(item.path("startTimeout").asInt()); }
            if (item.hasNonNull("stopTimeout")) { def.setStopTimeout(item.path("stopTimeout").asInt()); }
            if (item.hasNonNull("user")) { def.setUser(item.path("user").asText()); }
            if (item.hasNonNull("workingDirectory")) {
                def.setWorkingDirectory(item.path("workingDirectory").asText());
            }
            if (item.hasNonNull("hostname")) { def.setHostname(item.path("hostname").asText()); }
            if (item.hasNonNull("readonlyRootFilesystem")) {
                def.setReadonlyRootFilesystem(item.path("readonlyRootFilesystem").asBoolean());
            }
            if (item.hasNonNull("privileged")) { def.setPrivileged(item.path("privileged").asBoolean()); }
            if (item.hasNonNull("disableNetworking")) {
                def.setDisableNetworking(item.path("disableNetworking").asBoolean());
            }
            if (item.hasNonNull("interactive")) { def.setInteractive(item.path("interactive").asBoolean()); }
            if (item.hasNonNull("pseudoTerminal")) {
                def.setPseudoTerminal(item.path("pseudoTerminal").asBoolean());
            }
            if (item.has("links")) { def.setLinks(jsonArrayToList(item.path("links"))); }
            if (item.has("dnsServers")) { def.setDnsServers(jsonArrayToList(item.path("dnsServers"))); }
            if (item.has("dnsSearchDomains")) {
                def.setDnsSearchDomains(jsonArrayToList(item.path("dnsSearchDomains")));
            }
            if (item.has("dockerSecurityOptions")) {
                def.setDockerSecurityOptions(jsonArrayToList(item.path("dockerSecurityOptions")));
            }
            if (item.path("dockerLabels").isObject()) {
                Map<String, String> labels = new LinkedHashMap<>();
                item.path("dockerLabels").fields()
                        .forEachRemaining(entry -> labels.put(entry.getKey(), entry.getValue().asText()));
                def.setDockerLabels(labels);
            }
            if (item.path("repositoryCredentials").hasNonNull("credentialsParameter")) {
                def.setRepositoryCredentialsParameter(
                        item.path("repositoryCredentials").path("credentialsParameter").asText());
            }
            def.setUnparsed(EcsJsonPassthrough.capture(item, objectMapper, CONTAINER_DEFINITION_CONSUMED));

            result.add(def);
        }
        return result;
    }

    private List<PortMapping> parsePortMappings(JsonNode node) {
        List<PortMapping> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            int containerPort = item.path("containerPort").asInt(0);
            int hostPort = item.path("hostPort").asInt(0);
            String protocol = item.path("protocol").asText("tcp");
            String name = item.hasNonNull("name") ? item.path("name").asText() : null;
            String appProtocol = item.hasNonNull("appProtocol") ? item.path("appProtocol").asText() : null;
            String containerPortRange = item.hasNonNull("containerPortRange")
                    ? item.path("containerPortRange").asText() : null;
            result.add(new PortMapping(containerPort, hostPort, protocol, name, appProtocol,
                    containerPortRange));
        }
        return result;
    }

    private List<KeyValuePair> parseKeyValuePairs(JsonNode node) {
        List<KeyValuePair> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            result.add(new KeyValuePair(item.path("name").asText(), item.path("value").asText()));
        }
        return result;
    }

    private List<EnvironmentFile> parseEnvironmentFiles(JsonNode node) {
        List<EnvironmentFile> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            result.add(new EnvironmentFile(item.path("value").asText(), item.path("type").asText("s3")));
        }
        return result;
    }

    /**
     * Parses {@code dependsOn}, rejecting a condition ECS does not define. An unknown condition
     * would otherwise silently become a plain start-ordering edge.
     */
    private List<ContainerDependency> parseDependsOn(JsonNode node, String containerName) {
        List<ContainerDependency> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            String condition = item.path("condition").asText();
            if (!DEPENDENCY_CONDITIONS.contains(condition)) {
                throw new AwsException("ClientException",
                        "Container '" + containerName + "' depends on container '"
                                + item.path("containerName").asText() + "' with an invalid condition: "
                                + condition + ". Valid values: "
                                + String.join(", ", new TreeSet<>(DEPENDENCY_CONDITIONS)) + ".", 400);
            }
            result.add(new ContainerDependency(item.path("containerName").asText(), condition));
        }
        return result;
    }

    private List<Secret> parseSecrets(JsonNode node) {
        List<Secret> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            result.add(new Secret(item.path("name").asText(), item.path("valueFrom").asText()));
        }
        return result;
    }

    private List<VolumeFrom> parseVolumesFrom(JsonNode node) {
        List<VolumeFrom> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            result.add(new VolumeFrom(
                    item.path("sourceContainer").asText(),
                    item.path("readOnly").asBoolean(false)));
        }
        return result;
    }

    private RuntimePlatform parseRuntimePlatform(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String cpuArchitecture = node.path("cpuArchitecture").asText(null);
        String operatingSystemFamily = node.path("operatingSystemFamily").asText(null);
        if (cpuArchitecture == null && operatingSystemFamily == null) {
            return null;
        }
        return new RuntimePlatform(cpuArchitecture, operatingSystemFamily);
    }

    private EphemeralStorage parseEphemeralStorage(JsonNode node) {
        if (node == null || !node.isObject() || !node.hasNonNull("sizeInGiB")) {
            return null;
        }
        return new EphemeralStorage(node.path("sizeInGiB").asInt());
    }

    private List<CapacityProviderStrategyItem> parseCapacityProviderStrategy(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        List<CapacityProviderStrategyItem> result = new ArrayList<>();
        for (JsonNode item : node) {
            result.add(new CapacityProviderStrategyItem(
                    item.path("capacityProvider").asText(),
                    item.path("weight").asInt(0),
                    item.path("base").asInt(0)));
        }
        return result;
    }

    private TaskOverride parseTaskOverride(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        TaskOverride overrides = new TaskOverride();
        overrides.setContainerOverrides(parseContainerOverrides(node.path("containerOverrides")));
        if (node.hasNonNull("cpu")) { overrides.setCpu(node.path("cpu").asText()); }
        if (node.hasNonNull("memory")) { overrides.setMemory(node.path("memory").asText()); }
        if (node.hasNonNull("taskRoleArn")) { overrides.setTaskRoleArn(node.path("taskRoleArn").asText()); }
        if (node.hasNonNull("executionRoleArn")) {
            overrides.setExecutionRoleArn(node.path("executionRoleArn").asText());
        }
        overrides.setEphemeralStorage(parseEphemeralStorage(node.path("ephemeralStorage")));
        return overrides.isEmpty() ? null : overrides;
    }

    private LogConfiguration parseLogConfiguration(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String logDriver = node.path("logDriver").asText(null);
        if (logDriver == null) {
            return null;
        }
        Map<String, String> options = null;
        if (node.path("options").isObject()) {
            Map<String, String> parsed = new LinkedHashMap<>();
            node.path("options").fields()
                    .forEachRemaining(entry -> parsed.put(entry.getKey(), entry.getValue().asText()));
            options = parsed;
        }
        List<Secret> secretOptions = node.has("secretOptions") ? parseSecrets(node.path("secretOptions")) : null;
        return new LogConfiguration(logDriver, options, secretOptions);
    }

    private FirelensConfiguration parseFirelensConfiguration(JsonNode node, int containerIndex) {
        if (node == null || !node.isObject()) {
            return null;
        }
        if (!node.hasNonNull("type")) {
            throw new AwsException("ClientException",
                    "1 validation error detected: Value null at 'containerDefinitions." + containerIndex
                            + ".member.firelensConfiguration.type' failed to satisfy constraint: Member must not be null",
                    400);
        }
        String type = node.path("type").asText();
        if (!"fluentd".equals(type) && !"fluentbit".equals(type)) {
            throw new AwsException("ClientException",
                    "1 validation error detected: Value '" + type + "' at 'containerDefinitions." + containerIndex
                            + ".member.firelensConfiguration.type' failed to satisfy constraint: "
                            + "Member must satisfy enum value set: [fluentd, fluentbit]",
                    400);
        }
        Map<String, String> options = null;
        if (node.path("options").isObject()) {
            LinkedHashMap<String, String> parsed = new LinkedHashMap<>();
            node.path("options").fields()
                    .forEachRemaining(entry -> parsed.put(entry.getKey(), entry.getValue().asText()));
            options = parsed;
        }
        return new FirelensConfiguration(type, options);
    }

    private HealthCheck parseHealthCheck(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        if (!node.hasNonNull("command") || !node.path("command").isArray() || node.path("command").isEmpty()) {
            throw new AwsException("ClientException", "HealthCheck command is required.", 400);
        }
        List<String> command = jsonArrayToList(node.path("command"));
        Integer interval = node.has("interval") ? node.path("interval").asInt() : null;
        Integer timeout = node.has("timeout") ? node.path("timeout").asInt() : null;
        Integer retries = node.has("retries") ? node.path("retries").asInt() : null;
        Integer startPeriod = node.has("startPeriod") ? node.path("startPeriod").asInt() : null;
        return new HealthCheck(command, interval, timeout, retries, startPeriod);
    }

    private List<Volume> parseVolumes(JsonNode node) {
        List<Volume> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            String hostSourcePath = item.path("host").path("sourcePath").asText(null);
            if (hostSourcePath != null && !hostSourcePath.isBlank()) {
                hostVolumePolicy.validate(hostSourcePath);
            }
            EfsVolumeConfiguration efs = parseEfsVolumeConfiguration(item.path("efsVolumeConfiguration"));
            // "host" stays out of the consumed set so that a volume declaring an empty one reads
            // back with it; a host that did carry a sourcePath is written from the typed field and
            // the passthrough leaves it alone.
            result.add(new Volume(item.path("name").asText(), hostSourcePath, efs,
                    EcsJsonPassthrough.capture(item, objectMapper, VOLUME_CONSUMED)));
        }
        return result;
    }

    /** Volume members the parser consumes; the rest round-trips verbatim. */
    private static final Set<String> VOLUME_CONSUMED = Set.of("name", "efsVolumeConfiguration");

    private EfsVolumeConfiguration parseEfsVolumeConfiguration(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String fileSystemId = node.path("fileSystemId").asText(null);
        if (fileSystemId == null) {
            return null;
        }
        Integer transitEncryptionPort = node.path("transitEncryptionPort").isNumber()
                ? node.path("transitEncryptionPort").asInt() : null;
        JsonNode auth = node.path("authorizationConfig");
        String rootDirectory = node.path("rootDirectory").asText(null);
        String accessPointId = auth.path("accessPointId").asText(null);
        if (accessPointId != null && !accessPointId.isBlank()
                && rootDirectory != null && !rootDirectory.isBlank() && !"/".equals(rootDirectory)) {
            throw new AwsException("InvalidParameterException",
                    "Root directory must either be omitted or set to '/' when an EFS access point "
                            + "is specified in authorizationConfig.accessPointId.", 400);
        }
        return new EfsVolumeConfiguration(
                fileSystemId,
                rootDirectory,
                node.path("transitEncryption").asText(null),
                transitEncryptionPort,
                accessPointId,
                auth.path("iam").asText(null));
    }

    private List<MountPoint> parseMountPoints(JsonNode node) {
        List<MountPoint> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            result.add(new MountPoint(
                    item.path("sourceVolume").asText(),
                    item.path("containerPath").asText(),
                    item.path("readOnly").asBoolean(false)));
        }
        return result;
    }

    /** Parses ECS container overrides from data-plane JSON. Reused by the Step Functions
     *  ecs:runTask integration ({@link io.github.hectorvent.floci.services.stepfunctions.AslExecutor}). */
    public List<ContainerOverride> parseContainerOverrides(JsonNode node) {
        List<ContainerOverride> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            ContainerOverride co = new ContainerOverride();
            co.setName(item.path("name").asText());
            if (item.has("command") && item.path("command").isArray()) {
                co.setCommand(jsonArrayToList(item.path("command")));
            }
            co.setEnvironment(parseKeyValuePairs(item.path("environment")));
            if (item.has("environmentFiles")) {
                co.setEnvironmentFiles(parseEnvironmentFiles(item.path("environmentFiles")));
            }
            if (item.hasNonNull("cpu")) { co.setCpu(item.path("cpu").asInt()); }
            if (item.hasNonNull("memory")) { co.setMemory(item.path("memory").asInt()); }
            if (item.hasNonNull("memoryReservation")) {
                co.setMemoryReservation(item.path("memoryReservation").asInt());
            }
            result.add(co);
        }
        return result;
    }

    private List<ClusterSetting> parseClusterSettings(JsonNode node) {
        List<ClusterSetting> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            result.add(new ClusterSetting(item.path("name").asText(), item.path("value").asText()));
        }
        return result;
    }

    private List<Attribute> parseAttributes(JsonNode node) {
        List<Attribute> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            result.add(new Attribute(
                    item.path("name").asText(),
                    item.has("value") ? item.path("value").asText() : null,
                    item.has("targetType") ? item.path("targetType").asText() : null,
                    item.has("targetId") ? item.path("targetId").asText() : null
            ));
        }
        return result;
    }

    /**
     * Parses a {@code tags} list, holding it to the limits ECS documents for every operation that
     * takes one: at most 50 tags, a key of 1 to 128 characters and a value of up to 256. The count
     * is taken from the request rather than the parsed map, so 51 entries that collapse to fewer
     * distinct keys are still rejected.
     */
    private Map<String, String> parseTagMap(JsonNode node) {
        Map<String, String> result = new HashMap<>();
        if (!node.isArray()) {
            return result;
        }
        if (node.size() > MAX_TAGS_PER_RESOURCE) {
            throw new AwsException("InvalidParameterException",
                    "Too many tags specified. A resource takes at most "
                            + MAX_TAGS_PER_RESOURCE + " tags.", 400);
        }
        for (JsonNode item : node) {
            String key = item.path("key").asText();
            String value = item.path("value").asText();
            if (key.isEmpty() || key.length() > MAX_TAG_KEY_LENGTH) {
                throw new AwsException("InvalidParameterException",
                        "Tag keys must be between 1 and " + MAX_TAG_KEY_LENGTH
                                + " characters long.", 400);
            }
            if (value.length() > MAX_TAG_VALUE_LENGTH) {
                throw new AwsException("InvalidParameterException",
                        "Tag values can be up to " + MAX_TAG_VALUE_LENGTH
                                + " characters long.", 400);
            }
            result.put(key, value);
        }
        return result;
    }

    private static final int MAX_TAGS_PER_RESOURCE = 50;
    private static final int MAX_TAG_KEY_LENGTH = 128;
    private static final int MAX_TAG_VALUE_LENGTH = 256;

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseRawObject(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isObject()) {
            return null;
        }
        return objectMapper.convertValue(node, Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseRawObjectList(JsonNode node) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            result.add(objectMapper.convertValue(item, Map.class));
        }
        return result;
    }

    private List<String> jsonArrayToList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> result.add(n.asText()));
        }
        return result;
    }

    /**
     * Parses an enum-typed member, rejecting a value the enum does not have. Treating an
     * unrecognised value as absent is worse than it sounds: a {@code launchType} of {@code "EC22"}
     * would silently fall through to Floci's default and place the task on Fargate, which is not
     * what the caller asked for and not what AWS answers.
     */
    private <T extends Enum<T>> T parseEnum(JsonNode req, String field, Class<T> enumClass) {
        if (!req.hasNonNull(field)) {
            return null;
        }
        String val = req.path(field).asText();
        try {
            return Enum.valueOf(enumClass, val);
        } catch (IllegalArgumentException e) {
            List<String> valid = Arrays.stream(enumClass.getEnumConstants()).map(Enum::name).toList();
            throw new AwsException("InvalidParameterException",
                    "Invalid " + field + ": " + val + ". Valid values: "
                            + String.join(", ", valid) + ".", 400);
        }
    }

    /** Rejects a compatibility the enum does not have, rather than carrying it into the model. */
    private List<String> parseCompatibilities(JsonNode node) {
        List<String> requested = jsonArrayToList(node);
        for (String value : requested) {
            if (!COMPATIBILITIES.contains(value)) {
                throw new AwsException("InvalidParameterException",
                        "Invalid requiresCompatibilities value: " + value + ". Valid values: "
                                + String.join(", ", new TreeSet<>(COMPATIBILITIES)) + ".", 400);
            }
        }
        return requested;
    }

    private static final Set<String> COMPATIBILITIES =
            Set.of("EC2", "FARGATE", "EXTERNAL", "MANAGED_INSTANCES");
    private static final Set<String> PID_MODES = Set.of("host", "task");
    private static final Set<String> IPC_MODES = Set.of("host", "task", "none");
    private static final Set<String> CONTAINER_INSTANCE_STATUSES =
            Set.of("ACTIVE", "DRAINING", "REGISTERING", "DEREGISTERING", "REGISTRATION_FAILED");
    private static final Set<String> SCHEDULING_STRATEGIES = Set.of("REPLICA", "DAEMON");
    private static final Set<String> DEPLOYMENT_CONTROLLER_TYPES = Set.of("ECS", "CODE_DEPLOY", "EXTERNAL");
    private static final Set<String> AZ_REBALANCING = Set.of("ENABLED", "DISABLED");
    private static final Set<String> PROPAGATE_TAGS = Set.of("TASK_DEFINITION", "SERVICE", "NONE");
    private static final Set<String> DESIRED_STATUSES = Set.of("RUNNING", "PENDING", "STOPPED");
    private static final Set<String> TASK_DEFINITION_STATUSES =
            Set.of("ACTIVE", "INACTIVE", "DELETE_IN_PROGRESS");
    private static final Set<String> SORT_ORDERS = Set.of("ASC", "DESC");
    private static final Set<String> FAMILY_STATUSES = Set.of("ACTIVE", "INACTIVE", "ALL");
    private static final Set<String> DEPENDENCY_CONDITIONS = Set.of(
            ContainerDependency.START, ContainerDependency.COMPLETE,
            ContainerDependency.SUCCESS, ContainerDependency.HEALTHY);

    /** Optional enum-valued string field: absent → {@code null}; present but not in {@code allowed} → 400. */
    private static String parseChoice(JsonNode node, String field, Set<String> allowed) {
        return parseChoice(node, field, field, allowed);
    }

    private static String parseChoice(JsonNode node, String field, String displayName, Set<String> allowed) {
        if (node == null || node.isMissingNode() || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.path(field).asText();
        if (!allowed.contains(value)) {
            throw new AwsException("InvalidParameterException",
                    "Invalid " + displayName + ": " + value + ". Valid values: "
                            + String.join(", ", new TreeSet<>(allowed)) + ".", 400);
        }
        return value;
    }
}
