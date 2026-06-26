package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ecs.model.Attribute;
import io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.ecs.model.CapacityProvider;
import io.github.hectorvent.floci.services.ecs.model.ClusterSetting;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.ContainerInstance;
import io.github.hectorvent.floci.services.ecs.model.ContainerOverride;
import io.github.hectorvent.floci.services.ecs.model.EcsCluster;
import io.github.hectorvent.floci.services.ecs.model.EcsLoadBalancer;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.KeyValuePair;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.MountPoint;
import io.github.hectorvent.floci.services.ecs.model.NetworkBinding;
import io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.PortMapping;
import io.github.hectorvent.floci.services.ecs.model.ProtectedTask;
import io.github.hectorvent.floci.services.ecs.model.ServiceDeployment;
import io.github.hectorvent.floci.services.ecs.model.ServiceRevision;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.ecs.model.TaskSet;
import io.github.hectorvent.floci.services.ecs.model.EfsVolumeConfiguration;
import io.github.hectorvent.floci.services.ecs.model.Volume;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class EcsJsonHandler {

    private final EcsService service;
    private final ObjectMapper objectMapper;

    @Inject
    public EcsJsonHandler(EcsService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
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
            // Task Definitions
            case "RegisterTaskDefinition" -> handleRegisterTaskDefinition(request, region);
            case "DescribeTaskDefinition" -> handleDescribeTaskDefinition(request, region);
            case "ListTaskDefinitions" -> handleListTaskDefinitions(request, region);
            case "ListTaskDefinitionFamilies" -> handleListTaskDefinitionFamilies(request, region);
            case "DeregisterTaskDefinition" -> handleDeregisterTaskDefinition(request, region);
            case "DeleteTaskDefinitions" -> handleDeleteTaskDefinitions(request, region);
            // Tasks
            case "RunTask" -> handleRunTask(request, region);
            case "StartTask" -> handleStartTask(request, region);
            case "StopTask" -> handleStopTask(request, region);
            case "DescribeTasks" -> handleDescribeTasks(request, region);
            case "ListTasks" -> handleListTasks(request, region);
            case "UpdateTaskProtection" -> handleUpdateTaskProtection(request, region);
            case "GetTaskProtection" -> handleGetTaskProtection(request, region);
            // Services
            case "CreateService" -> handleCreateService(request, region);
            case "UpdateService" -> handleUpdateService(request, region);
            case "DeleteService" -> handleDeleteService(request, region);
            case "DescribeServices" -> handleDescribeServices(request, region);
            case "ListServices" -> handleListServices(request, region);
            case "ListServicesByNamespace" -> handleListServicesByNamespace(request, region);
            // Tags
            case "TagResource" -> handleTagResource(request, region);
            case "UntagResource" -> handleUntagResource(request, region);
            case "ListTagsForResource" -> handleListTagsForResource(request, region);
            // Account Settings
            case "PutAccountSetting" -> handlePutAccountSetting(request, region);
            case "PutAccountSettingDefault" -> handlePutAccountSettingDefault(request, region);
            case "DeleteAccountSetting" -> handleDeleteAccountSetting(request, region);
            case "ListAccountSettings" -> handleListAccountSettings(request, region);
            // Attributes
            case "PutAttributes" -> handlePutAttributes(request, region);
            case "DeleteAttributes" -> handleDeleteAttributes(request, region);
            case "ListAttributes" -> handleListAttributes(request, region);
            // Container Instances
            case "RegisterContainerInstance" -> handleRegisterContainerInstance(request, region);
            case "DeregisterContainerInstance" -> handleDeregisterContainerInstance(request, region);
            case "DescribeContainerInstances" -> handleDescribeContainerInstances(request, region);
            case "ListContainerInstances" -> handleListContainerInstances(request, region);
            case "UpdateContainerAgent" -> handleUpdateContainerAgent(request, region);
            case "UpdateContainerInstancesState" -> handleUpdateContainerInstancesState(request, region);
            // Capacity Providers
            case "CreateCapacityProvider" -> handleCreateCapacityProvider(request, region);
            case "UpdateCapacityProvider" -> handleUpdateCapacityProvider(request, region);
            case "DeleteCapacityProvider" -> handleDeleteCapacityProvider(request, region);
            case "DescribeCapacityProviders" -> handleDescribeCapacityProviders(request, region);
            // Task Sets
            case "CreateTaskSet" -> handleCreateTaskSet(request, region);
            case "UpdateTaskSet" -> handleUpdateTaskSet(request, region);
            case "DeleteTaskSet" -> handleDeleteTaskSet(request, region);
            case "DescribeTaskSets" -> handleDescribeTaskSets(request, region);
            case "UpdateServicePrimaryTaskSet" -> handleUpdateServicePrimaryTaskSet(request, region);
            // Service Deployments & Revisions
            case "DescribeServiceDeployments" -> handleDescribeServiceDeployments(request, region);
            case "ListServiceDeployments" -> handleListServiceDeployments(request, region);
            case "DescribeServiceRevisions" -> handleDescribeServiceRevisions(request, region);
            // Stubs
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
        String name = req.path("clusterName").asText(null);
        Map<String, String> tags = parseTagMap(req.path("tags"));
        EcsCluster cluster = service.createCluster(name, tags, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("cluster", clusterNode(cluster));
        return Response.ok(resp).build();
    }

    private Response handleDescribeClusters(JsonNode req, String region) {
        List<String> ids = jsonArrayToList(req.path("clusters"));
        List<EcsCluster> found = service.describeClusters(ids, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        found.forEach(c -> arr.add(clusterNode(c)));
        resp.set("clusters", arr);
        resp.set("failures", objectMapper.createArrayNode());
        return Response.ok(resp).build();
    }

    private Response handleListClusters(JsonNode req, String region) {
        List<String> arns = service.listClusters(region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        arns.forEach(arr::add);
        resp.set("clusterArns", arr);
        return Response.ok(resp).build();
    }

    private Response handleDeleteCluster(JsonNode req, String region) {
        String clusterId = req.path("cluster").asText();
        EcsCluster cluster = service.deleteCluster(clusterId, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("cluster", clusterNode(cluster));
        return Response.ok(resp).build();
    }

    private Response handleUpdateCluster(JsonNode req, String region) {
        String clusterRef = req.path("cluster").asText();
        List<ClusterSetting> settings = parseClusterSettings(req.path("settings"));
        EcsCluster cluster = service.updateCluster(clusterRef, settings, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("cluster", clusterNode(cluster));
        return Response.ok(resp).build();
    }

    private Response handleUpdateClusterSettings(JsonNode req, String region) {
        String clusterRef = req.path("cluster").asText();
        List<ClusterSetting> settings = parseClusterSettings(req.path("settings"));
        EcsCluster cluster = service.updateClusterSettings(clusterRef, settings, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("cluster", clusterNode(cluster));
        return Response.ok(resp).build();
    }

    private Response handlePutClusterCapacityProviders(JsonNode req, String region) {
        String clusterRef = req.path("cluster").asText();
        List<String> providers = jsonArrayToList(req.path("capacityProviders"));
        List<Map<String, Object>> defaultStrategy = parseRawObjectList(req.path("defaultCapacityProviderStrategy"));
        EcsCluster cluster = service.putClusterCapacityProviders(clusterRef, providers, defaultStrategy, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("cluster", clusterNode(cluster));
        return Response.ok(resp).build();
    }

    // ── Task Definitions ──────────────────────────────────────────────────────

    private Response handleRegisterTaskDefinition(JsonNode req, String region) {
        String family = req.path("family").asText();
        List<ContainerDefinition> containerDefs = parseContainerDefinitions(req.path("containerDefinitions"));
        NetworkMode networkMode = parseEnum(req, "networkMode", NetworkMode.class);
        String cpu = req.has("cpu") ? req.path("cpu").asText() : null;
        String memory = req.has("memory") ? req.path("memory").asText() : null;
        String taskRoleArn = req.hasNonNull("taskRoleArn") ? req.path("taskRoleArn").asText() : null;
        String executionRoleArn = req.hasNonNull("executionRoleArn") ? req.path("executionRoleArn").asText() : null;
        List<String> requiresCompatibilities = jsonArrayToList(req.path("requiresCompatibilities"));
        Map<String, String> tags = parseTagMap(req.path("tags"));

        TaskDefinition td = service.registerTaskDefinition(family, containerDefs, networkMode, cpu, memory,
                taskRoleArn, executionRoleArn, requiresCompatibilities, tags, region);
        // Task-level volumes are not part of registerTaskDefinition's signature; set them on the
        // returned (and stored) task definition so they round-trip and reach RunTask launches.
        td.setVolumes(parseVolumes(req.path("volumes")));

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("taskDefinition", taskDefinitionNode(td));
        return Response.ok(resp).build();
    }

    private Response handleDescribeTaskDefinition(JsonNode req, String region) {
        String tdRef = req.path("taskDefinition").asText();
        TaskDefinition td = service.describeTaskDefinition(tdRef, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("taskDefinition", taskDefinitionNode(td));
        return Response.ok(resp).build();
    }

    private Response handleListTaskDefinitions(JsonNode req, String region) {
        String familyPrefix = req.has("familyPrefix") ? req.path("familyPrefix").asText() : null;
        String status = req.has("status") ? req.path("status").asText() : null;
        List<String> arns = service.listTaskDefinitions(familyPrefix, status);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        arns.forEach(arr::add);
        resp.set("taskDefinitionArns", arr);
        return Response.ok(resp).build();
    }

    private Response handleListTaskDefinitionFamilies(JsonNode req, String region) {
        String familyPrefix = req.has("familyPrefix") ? req.path("familyPrefix").asText() : null;
        List<String> families = service.listTaskDefinitionFamilies(familyPrefix);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        families.forEach(arr::add);
        resp.set("families", arr);
        return Response.ok(resp).build();
    }

    private Response handleDeregisterTaskDefinition(JsonNode req, String region) {
        String tdRef = req.path("taskDefinition").asText();
        TaskDefinition td = service.deregisterTaskDefinition(tdRef, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("taskDefinition", taskDefinitionNode(td));
        return Response.ok(resp).build();
    }

    private Response handleDeleteTaskDefinitions(JsonNode req, String region) {
        List<String> refs = jsonArrayToList(req.path("taskDefinitions"));
        List<TaskDefinition> deleted = service.deleteTaskDefinitions(refs, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        deleted.forEach(td -> arr.add(taskDefinitionNode(td)));
        resp.set("taskDefinitions", arr);
        resp.set("failures", objectMapper.createArrayNode());
        return Response.ok(resp).build();
    }

    // ── Tasks ─────────────────────────────────────────────────────────────────

    private Response handleRunTask(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String taskDefinition = req.path("taskDefinition").asText();
        int count = req.path("count").asInt(1);
        LaunchType launchType = parseEnum(req, "launchType", LaunchType.class);
        String group = req.has("group") ? req.path("group").asText() : null;
        String startedBy = req.has("startedBy") ? req.path("startedBy").asText() : null;
        List<ContainerOverride> containerOverrides =
                parseContainerOverrides(req.path("overrides").path("containerOverrides"));

        List<EcsTask> launched = service.runTask(cluster, taskDefinition, count,
                launchType, group, startedBy, containerOverrides, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        launched.forEach(t -> arr.add(taskNode(t)));
        resp.set("tasks", arr);
        resp.set("failures", objectMapper.createArrayNode());
        return Response.ok(resp).build();
    }

    private Response handleStartTask(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<String> instances = jsonArrayToList(req.path("containerInstances"));
        String taskDefinition = req.path("taskDefinition").asText();
        String group = req.has("group") ? req.path("group").asText() : null;
        String startedBy = req.has("startedBy") ? req.path("startedBy").asText() : null;

        List<EcsTask> launched = service.startTask(cluster, instances, taskDefinition, group, startedBy, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        launched.forEach(t -> arr.add(taskNode(t)));
        resp.set("tasks", arr);
        resp.set("failures", objectMapper.createArrayNode());
        return Response.ok(resp).build();
    }

    private Response handleStopTask(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String task = req.path("task").asText();
        String reason = req.has("reason") ? req.path("reason").asText() : null;

        EcsTask stopped = service.stopTask(cluster, task, reason, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("task", taskNode(stopped));
        return Response.ok(resp).build();
    }

    private Response handleDescribeTasks(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<String> taskRefs = jsonArrayToList(req.path("tasks"));
        List<EcsTask> found = service.describeTasks(cluster, taskRefs, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        found.forEach(t -> arr.add(taskNode(t)));
        resp.set("tasks", arr);
        resp.set("failures", objectMapper.createArrayNode());
        return Response.ok(resp).build();
    }

    private Response handleListTasks(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String family = req.has("family") ? req.path("family").asText() : null;
        String desiredStatus = req.has("desiredStatus") ? req.path("desiredStatus").asText() : null;
        String serviceName = req.has("serviceName") ? req.path("serviceName").asText() : null;

        List<String> arns = service.listTasks(cluster, family, desiredStatus, serviceName, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        arns.forEach(arr::add);
        resp.set("taskArns", arr);
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
        result.forEach(pt -> arr.add(protectedTaskNode(pt)));
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
        result.forEach(pt -> arr.add(protectedTaskNode(pt)));
        resp.set("protectedTasks", arr);
        resp.set("failures", objectMapper.createArrayNode());
        return Response.ok(resp).build();
    }

    // ── Services ──────────────────────────────────────────────────────────────

    private Response handleCreateService(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String serviceName = req.path("serviceName").asText();
        String taskDefinition = req.path("taskDefinition").asText();
        int desiredCount = req.path("desiredCount").asInt(1);
        LaunchType launchType = parseEnum(req, "launchType", LaunchType.class);
        List<EcsLoadBalancer> loadBalancers = parseLoadBalancers(req.path("loadBalancers"));
        NetworkConfiguration networkConfiguration = parseNetworkConfiguration(req.path("networkConfiguration"));
        Map<String, String> tags = parseTagMap(req.path("tags"));

        EcsServiceModel svc = service.createService(cluster, serviceName, taskDefinition,
                desiredCount, launchType, loadBalancers, networkConfiguration, tags, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("service", serviceNode(svc));
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
            result.add(m);
        }
        return result;
    }

    private NetworkConfiguration parseNetworkConfiguration(JsonNode node) {
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

    private Response handleUpdateService(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String serviceName = req.path("service").asText();
        String taskDefinition = req.has("taskDefinition") ? req.path("taskDefinition").asText() : null;
        Integer desiredCount = req.has("desiredCount") ? req.path("desiredCount").asInt() : null;
        NetworkConfiguration networkConfiguration = parseNetworkConfiguration(req.path("networkConfiguration"));

        EcsServiceModel svc = service.updateService(cluster, serviceName, taskDefinition, desiredCount,
                networkConfiguration, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("service", serviceNode(svc));
        return Response.ok(resp).build();
    }

    private Response handleDeleteService(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String serviceName = req.path("service").asText();
        boolean force = req.path("force").asBoolean(false);

        EcsServiceModel svc = service.deleteService(cluster, serviceName, force, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("service", serviceNode(svc));
        return Response.ok(resp).build();
    }

    private Response handleDescribeServices(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<String> serviceIds = jsonArrayToList(req.path("services"));

        List<EcsServiceModel> found = service.describeServices(cluster, serviceIds, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        found.forEach(s -> arr.add(serviceNode(s)));
        resp.set("services", arr);
        resp.set("failures", objectMapper.createArrayNode());
        return Response.ok(resp).build();
    }

    private Response handleListServices(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<String> arns = service.listServices(cluster, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        arns.forEach(arr::add);
        resp.set("serviceArns", arr);
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
        resp.set("tags", tagsNode(tags));
        return Response.ok(resp).build();
    }

    // ── Account Settings ──────────────────────────────────────────────────────

    private Response handlePutAccountSetting(JsonNode req, String region) {
        String name = req.path("name").asText();
        String value = req.path("value").asText();
        var entry = service.putAccountSetting(name, value);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("setting", settingNode(entry.getKey(), entry.getValue()));
        return Response.ok(resp).build();
    }

    private Response handlePutAccountSettingDefault(JsonNode req, String region) {
        String name = req.path("name").asText();
        String value = req.path("value").asText();
        var entry = service.putAccountSettingDefault(name, value);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("setting", settingNode(entry.getKey(), entry.getValue()));
        return Response.ok(resp).build();
    }

    private Response handleDeleteAccountSetting(JsonNode req, String region) {
        String name = req.path("name").asText();
        var entry = service.deleteAccountSetting(name);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("setting", settingNode(entry.getKey(), entry.getValue()));
        return Response.ok(resp).build();
    }

    private Response handleListAccountSettings(JsonNode req, String region) {
        String filterName = req.has("name") ? req.path("name").asText() : null;
        String filterValue = req.has("value") ? req.path("value").asText() : null;
        var settings = service.listAccountSettings(filterName, filterValue);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        settings.forEach(e -> arr.add(settingNode(e.getKey(), e.getValue())));
        resp.set("settings", arr);
        return Response.ok(resp).build();
    }

    // ── Attributes ────────────────────────────────────────────────────────────

    private Response handlePutAttributes(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<Attribute> attrs = parseAttributes(req.path("attributes"));
        List<Attribute> stored = service.putAttributes(cluster, attrs, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        stored.forEach(a -> arr.add(attributeNode(a)));
        resp.set("attributes", arr);
        return Response.ok(resp).build();
    }

    private Response handleDeleteAttributes(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<Attribute> attrs = parseAttributes(req.path("attributes"));
        List<Attribute> deleted = service.deleteAttributes(cluster, attrs, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        deleted.forEach(a -> arr.add(attributeNode(a)));
        resp.set("attributes", arr);
        return Response.ok(resp).build();
    }

    private Response handleListAttributes(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String targetType = req.has("targetType") ? req.path("targetType").asText() : null;
        String attributeName = req.has("attributeName") ? req.path("attributeName").asText() : null;
        String attributeValue = req.has("attributeValue") ? req.path("attributeValue").asText() : null;
        List<Attribute> result = service.listAttributes(cluster, targetType, attributeName, attributeValue, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        result.forEach(a -> arr.add(attributeNode(a)));
        resp.set("attributes", arr);
        return Response.ok(resp).build();
    }

    // ── Container Instances ───────────────────────────────────────────────────

    private Response handleRegisterContainerInstance(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String instanceIdentityDocument = req.has("instanceIdentityDocument")
                ? req.path("instanceIdentityDocument").asText() : null;
        List<Attribute> attrs = parseAttributes(req.path("attributes"));
        ContainerInstance instance = service.registerContainerInstance(cluster, instanceIdentityDocument, attrs, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("containerInstance", containerInstanceNode(instance));
        return Response.ok(resp).build();
    }

    private Response handleDeregisterContainerInstance(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String containerInstance = req.path("containerInstance").asText();
        boolean force = req.path("force").asBoolean(false);
        ContainerInstance instance = service.deregisterContainerInstance(cluster, containerInstance, force, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("containerInstance", containerInstanceNode(instance));
        return Response.ok(resp).build();
    }

    private Response handleDescribeContainerInstances(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<String> instanceRefs = jsonArrayToList(req.path("containerInstances"));
        List<ContainerInstance> found = service.describeContainerInstances(cluster, instanceRefs, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        found.forEach(ci -> arr.add(containerInstanceNode(ci)));
        resp.set("containerInstances", arr);
        resp.set("failures", objectMapper.createArrayNode());
        return Response.ok(resp).build();
    }

    private Response handleListContainerInstances(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String status = req.has("status") ? req.path("status").asText() : null;
        List<String> arns = service.listContainerInstances(cluster, status, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        arns.forEach(arr::add);
        resp.set("containerInstanceArns", arr);
        return Response.ok(resp).build();
    }

    private Response handleUpdateContainerAgent(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String containerInstance = req.path("containerInstance").asText();
        ContainerInstance instance = service.updateContainerAgent(cluster, containerInstance, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("containerInstance", containerInstanceNode(instance));
        return Response.ok(resp).build();
    }

    private Response handleUpdateContainerInstancesState(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        List<String> instanceRefs = jsonArrayToList(req.path("containerInstances"));
        String status = req.path("status").asText();
        List<ContainerInstance> updated = service.updateContainerInstancesState(cluster, instanceRefs, status, region);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        updated.forEach(ci -> arr.add(containerInstanceNode(ci)));
        resp.set("containerInstances", arr);
        resp.set("failures", objectMapper.createArrayNode());
        return Response.ok(resp).build();
    }

    // ── Capacity Providers ────────────────────────────────────────────────────

    private Response handleCreateCapacityProvider(JsonNode req, String region) {
        String name = req.path("name").asText();
        Map<String, Object> asgProvider = parseRawObject(req.path("autoScalingGroupProvider"));
        Map<String, String> tags = parseTagMap(req.path("tags"));
        CapacityProvider cp = service.createCapacityProvider(name, asgProvider, tags, region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("capacityProvider", capacityProviderNode(cp));
        return Response.ok(resp).build();
    }

    private Response handleUpdateCapacityProvider(JsonNode req, String region) {
        String name = req.path("name").asText();
        Map<String, Object> asgProvider = parseRawObject(req.path("autoScalingGroupProvider"));
        CapacityProvider cp = service.updateCapacityProvider(name, asgProvider);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("capacityProvider", capacityProviderNode(cp));
        return Response.ok(resp).build();
    }

    private Response handleDeleteCapacityProvider(JsonNode req, String region) {
        String nameOrArn = req.path("capacityProvider").asText();
        CapacityProvider cp = service.deleteCapacityProvider(nameOrArn);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("capacityProvider", capacityProviderNode(cp));
        return Response.ok(resp).build();
    }

    private Response handleDescribeCapacityProviders(JsonNode req, String region) {
        List<String> providers = req.has("capacityProviders") ? jsonArrayToList(req.path("capacityProviders")) : null;
        List<CapacityProvider> found = service.describeCapacityProviders(providers);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        found.forEach(cp -> arr.add(capacityProviderNode(cp)));
        resp.set("capacityProviders", arr);
        return Response.ok(resp).build();
    }

    // ── Task Sets ─────────────────────────────────────────────────────────────

    private Response handleCreateTaskSet(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String service = req.path("service").asText();
        String taskDefinition = req.path("taskDefinition").asText();
        LaunchType launchType = parseEnum(req, "launchType", LaunchType.class);
        double scaleValue = req.path("scale").path("value").asDouble(100.0);
        String scaleUnit = req.path("scale").path("unit").asText("PERCENT");
        String externalId = req.has("externalId") ? req.path("externalId").asText() : null;

        TaskSet ts = this.service.createTaskSet(cluster, service, taskDefinition, launchType,
                scaleValue, scaleUnit, externalId, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("taskSet", taskSetNode(ts));
        return Response.ok(resp).build();
    }

    private Response handleUpdateTaskSet(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String svc = req.path("service").asText();
        String taskSet = req.path("taskSet").asText();
        double scaleValue = req.path("scale").path("value").asDouble(100.0);
        String scaleUnit = req.path("scale").path("unit").asText("PERCENT");

        TaskSet ts = service.updateTaskSet(cluster, svc, taskSet, scaleValue, scaleUnit, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("taskSet", taskSetNode(ts));
        return Response.ok(resp).build();
    }

    private Response handleDeleteTaskSet(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String svc = req.path("service").asText();
        String taskSet = req.path("taskSet").asText();
        boolean force = req.path("force").asBoolean(false);

        TaskSet ts = service.deleteTaskSet(cluster, svc, taskSet, force, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("taskSet", taskSetNode(ts));
        return Response.ok(resp).build();
    }

    private Response handleDescribeTaskSets(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String svc = req.path("service").asText();
        List<String> taskSetRefs = req.has("taskSets") ? jsonArrayToList(req.path("taskSets")) : null;

        List<TaskSet> found = service.describeTaskSets(cluster, svc, taskSetRefs, region);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        found.forEach(ts -> arr.add(taskSetNode(ts)));
        resp.set("taskSets", arr);
        return Response.ok(resp).build();
    }

    private Response handleUpdateServicePrimaryTaskSet(JsonNode req, String region) {
        String cluster = req.has("cluster") ? req.path("cluster").asText() : null;
        String svc = req.path("service").asText();
        String primaryTaskSet = req.path("primaryTaskSet").asText();

        TaskSet ts = service.updateServicePrimaryTaskSet(cluster, svc, primaryTaskSet, region);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("taskSet", taskSetNode(ts));
        return Response.ok(resp).build();
    }

    // ── Service Deployments & Revisions ───────────────────────────────────────

    private Response handleDescribeServiceDeployments(JsonNode req, String region) {
        List<String> arns = jsonArrayToList(req.path("serviceDeploymentArns"));
        List<ServiceDeployment> found = service.describeServiceDeployments(arns);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        found.forEach(d -> arr.add(serviceDeploymentNode(d)));
        resp.set("serviceDeployments", arr);
        return Response.ok(resp).build();
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
            if (d.getUpdatedAt() != null) { brief.put("finishedAt", d.getUpdatedAt().toEpochMilli() / 1000.0); }
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
        found.forEach(r -> arr.add(serviceRevisionNode(r)));
        resp.set("serviceRevisions", arr);
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

    // ── JSON serialization ────────────────────────────────────────────────────

    private ObjectNode clusterNode(EcsCluster c) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("clusterArn", c.getClusterArn());
        n.put("clusterName", c.getClusterName());
        n.put("status", c.getStatus());
        n.put("registeredContainerInstancesCount", c.getRegisteredContainerInstancesCount());
        n.put("runningTasksCount", c.getRunningTasksCount());
        n.put("pendingTasksCount", c.getPendingTasksCount());
        n.put("activeServicesCount", c.getActiveServicesCount());
        if (c.getSettings() != null && !c.getSettings().isEmpty()) {
            ArrayNode settings = objectMapper.createArrayNode();
            c.getSettings().forEach(s -> {
                ObjectNode sn = objectMapper.createObjectNode();
                sn.put("name", s.name());
                sn.put("value", s.value());
                settings.add(sn);
            });
            n.set("settings", settings);
        }
        if (c.getCapacityProviders() != null) {
            ArrayNode cp = objectMapper.createArrayNode();
            c.getCapacityProviders().forEach(cp::add);
            n.set("capacityProviders", cp);
        }
        if (c.getTags() != null && !c.getTags().isEmpty()) {
            n.set("tags", tagsNode(c.getTags()));
        }
        return n;
    }

    private ObjectNode taskDefinitionNode(TaskDefinition td) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("taskDefinitionArn", td.getTaskDefinitionArn());
        n.put("family", td.getFamily());
        n.put("revision", td.getRevision());
        n.put("status", td.getStatus());
        if (td.getNetworkMode() != null) {
            n.put("networkMode", td.getNetworkMode().name());
        }
        if (td.getCpu() != null) { n.put("cpu", td.getCpu()); }
        if (td.getMemory() != null) { n.put("memory", td.getMemory()); }
        if (td.getTaskRoleArn() != null) { n.put("taskRoleArn", td.getTaskRoleArn()); }
        if (td.getExecutionRoleArn() != null) { n.put("executionRoleArn", td.getExecutionRoleArn()); }
        if (td.getRequiresCompatibilities() != null && !td.getRequiresCompatibilities().isEmpty()) {
            ArrayNode arr = objectMapper.createArrayNode();
            td.getRequiresCompatibilities().forEach(arr::add);
            n.set("requiresCompatibilities", arr);
        }
        if (td.getCompatibilities() != null && !td.getCompatibilities().isEmpty()) {
            ArrayNode arr = objectMapper.createArrayNode();
            td.getCompatibilities().forEach(arr::add);
            n.set("compatibilities", arr);
        }

        ArrayNode containers = objectMapper.createArrayNode();
        if (td.getContainerDefinitions() != null) {
            for (var def : td.getContainerDefinitions()) {
                containers.add(containerDefinitionNode(def));
            }
        }
        n.set("containerDefinitions", containers);
        if (td.getVolumes() != null && !td.getVolumes().isEmpty()) {
            ArrayNode vols = objectMapper.createArrayNode();
            for (Volume v : td.getVolumes()) {
                ObjectNode vNode = objectMapper.createObjectNode();
                vNode.put("name", v.name());
                if (v.hostSourcePath() != null) {
                    ObjectNode host = objectMapper.createObjectNode();
                    host.put("sourcePath", v.hostSourcePath());
                    vNode.set("host", host);
                }
                if (v.efs() != null) {
                    EfsVolumeConfiguration e = v.efs();
                    ObjectNode efs = objectMapper.createObjectNode();
                    efs.put("fileSystemId", e.fileSystemId());
                    if (e.rootDirectory() != null) {
                        efs.put("rootDirectory", e.rootDirectory());
                    }
                    if (e.transitEncryption() != null) {
                        efs.put("transitEncryption", e.transitEncryption());
                    }
                    if (e.transitEncryptionPort() != null) {
                        efs.put("transitEncryptionPort", e.transitEncryptionPort());
                    }
                    if (e.accessPointId() != null || e.iam() != null) {
                        ObjectNode auth = objectMapper.createObjectNode();
                        if (e.accessPointId() != null) {
                            auth.put("accessPointId", e.accessPointId());
                        }
                        if (e.iam() != null) {
                            auth.put("iam", e.iam());
                        }
                        efs.set("authorizationConfig", auth);
                    }
                    vNode.set("efsVolumeConfiguration", efs);
                }
                vols.add(vNode);
            }
            n.set("volumes", vols);
        }
        if (td.getTags() != null && !td.getTags().isEmpty()) {
            n.set("tags", tagsNode(td.getTags()));
        }
        return n;
    }

    private ObjectNode containerDefinitionNode(ContainerDefinition def) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("name", def.getName());
        n.put("image", def.getImage());
        n.put("essential", def.isEssential());
        if (def.getCpu() != null) { n.put("cpu", def.getCpu()); }
        if (def.getMemory() != null) { n.put("memory", def.getMemory()); }

        if (def.getPortMappings() != null && !def.getPortMappings().isEmpty()) {
            ArrayNode pms = objectMapper.createArrayNode();
            for (PortMapping pm : def.getPortMappings()) {
                ObjectNode pmNode = objectMapper.createObjectNode();
                pmNode.put("containerPort", pm.containerPort());
                pmNode.put("hostPort", pm.hostPort());
                pmNode.put("protocol", pm.protocol());
                pms.add(pmNode);
            }
            n.set("portMappings", pms);
        }

        if (def.getEnvironment() != null && !def.getEnvironment().isEmpty()) {
            ArrayNode envArr = objectMapper.createArrayNode();
            for (KeyValuePair kv : def.getEnvironment()) {
                ObjectNode kvNode = objectMapper.createObjectNode();
                kvNode.put("name", kv.name());
                kvNode.put("value", kv.value());
                envArr.add(kvNode);
            }
            n.set("environment", envArr);
        }

        if (def.getMountPoints() != null && !def.getMountPoints().isEmpty()) {
            ArrayNode mps = objectMapper.createArrayNode();
            for (MountPoint mp : def.getMountPoints()) {
                ObjectNode mpNode = objectMapper.createObjectNode();
                mpNode.put("sourceVolume", mp.sourceVolume());
                mpNode.put("containerPath", mp.containerPath());
                mpNode.put("readOnly", mp.readOnly());
                mps.add(mpNode);
            }
            n.set("mountPoints", mps);
        }

        return n;
    }

    private ObjectNode taskNode(EcsTask t) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("taskArn", t.getTaskArn());
        n.put("clusterArn", t.getClusterArn());
        n.put("taskDefinitionArn", t.getTaskDefinitionArn());
        n.put("lastStatus", t.getLastStatus());
        n.put("desiredStatus", t.getDesiredStatus());
        if (t.getLaunchType() != null) { n.put("launchType", t.getLaunchType().name()); }
        if (t.getCpu() != null) { n.put("cpu", t.getCpu()); }
        if (t.getMemory() != null) { n.put("memory", t.getMemory()); }
        if (t.getGroup() != null) { n.put("group", t.getGroup()); }
        if (t.getStartedBy() != null) { n.put("startedBy", t.getStartedBy()); }
        if (t.getContainerInstanceArn() != null) { n.put("containerInstanceArn", t.getContainerInstanceArn()); }
        if (t.getCreatedAt() != null) { n.put("createdAt", t.getCreatedAt().toEpochMilli() / 1000.0); }
        if (t.getStartedAt() != null) { n.put("startedAt", t.getStartedAt().toEpochMilli() / 1000.0); }
        if (t.getStoppedAt() != null) { n.put("stoppedAt", t.getStoppedAt().toEpochMilli() / 1000.0); }
        if (t.getStoppedReason() != null) { n.put("stoppedReason", t.getStoppedReason()); }

        ArrayNode containers = objectMapper.createArrayNode();
        if (t.getContainers() != null) {
            for (var c : t.getContainers()) {
                ObjectNode cn = objectMapper.createObjectNode();
                cn.put("containerArn", c.getContainerArn());
                cn.put("taskArn", c.getTaskArn());
                cn.put("name", c.getName());
                cn.put("image", c.getImage());
                cn.put("lastStatus", c.getLastStatus());
                if (c.getExitCode() != null) { cn.put("exitCode", c.getExitCode()); }
                if (c.getReason() != null) { cn.put("reason", c.getReason()); }

                ArrayNode bindings = objectMapper.createArrayNode();
                if (c.getNetworkBindings() != null) {
                    for (NetworkBinding nb : c.getNetworkBindings()) {
                        ObjectNode bn = objectMapper.createObjectNode();
                        bn.put("bindIP", nb.bindIP());
                        bn.put("containerPort", nb.containerPort());
                        bn.put("hostPort", nb.hostPort());
                        bn.put("protocol", nb.protocol());
                        bindings.add(bn);
                    }
                }
                cn.set("networkBindings", bindings);
                containers.add(cn);
            }
        }
        n.set("containers", containers);
        if (t.getTags() != null && !t.getTags().isEmpty()) {
            n.set("tags", tagsNode(t.getTags()));
        }
        return n;
    }

    private ObjectNode serviceNode(EcsServiceModel s) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("serviceArn", s.getServiceArn());
        n.put("serviceName", s.getServiceName());
        n.put("clusterArn", s.getClusterArn());
        n.put("taskDefinition", s.getTaskDefinition());
        n.put("desiredCount", s.getDesiredCount());
        n.put("runningCount", s.getRunningCount());
        n.put("pendingCount", s.getPendingCount());
        n.put("status", s.getStatus());
        if (s.getLaunchType() != null) { n.put("launchType", s.getLaunchType().name()); }
        if (s.getCreatedAt() != null) { n.put("createdAt", s.getCreatedAt().toEpochMilli() / 1000.0); }
        if (s.getNamespace() != null) { n.put("namespace", s.getNamespace()); }
        if (s.getTags() != null && !s.getTags().isEmpty()) {
            n.set("tags", tagsNode(s.getTags()));
        }
        if (s.getLoadBalancers() != null && !s.getLoadBalancers().isEmpty()) {
            ArrayNode lbs = objectMapper.createArrayNode();
            for (EcsLoadBalancer lb : s.getLoadBalancers()) {
                ObjectNode ln = objectMapper.createObjectNode();
                if (lb.getTargetGroupArn() != null) { ln.put("targetGroupArn", lb.getTargetGroupArn()); }
                if (lb.getLoadBalancerName() != null) { ln.put("loadBalancerName", lb.getLoadBalancerName()); }
                if (lb.getContainerName() != null) { ln.put("containerName", lb.getContainerName()); }
                if (lb.getContainerPort() != null) { ln.put("containerPort", lb.getContainerPort()); }
                lbs.add(ln);
            }
            n.set("loadBalancers", lbs);
        }
        if (s.getNetworkConfiguration() != null
                && s.getNetworkConfiguration().getAwsvpcConfiguration() != null) {
            AwsVpcConfiguration awsvpc = s.getNetworkConfiguration().getAwsvpcConfiguration();
            ObjectNode awsvpcNode = objectMapper.createObjectNode();
            ArrayNode subnets = objectMapper.createArrayNode();
            awsvpc.getSubnets().forEach(subnets::add);
            awsvpcNode.set("subnets", subnets);
            ArrayNode securityGroups = objectMapper.createArrayNode();
            awsvpc.getSecurityGroups().forEach(securityGroups::add);
            awsvpcNode.set("securityGroups", securityGroups);
            if (awsvpc.getAssignPublicIp() != null) {
                awsvpcNode.put("assignPublicIp", awsvpc.getAssignPublicIp());
            }
            ObjectNode networkConfig = objectMapper.createObjectNode();
            networkConfig.set("awsvpcConfiguration", awsvpcNode);
            n.set("networkConfiguration", networkConfig);
        }
        return n;
    }

    private ObjectNode containerInstanceNode(ContainerInstance ci) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("containerInstanceArn", ci.getContainerInstanceArn());
        n.put("ec2InstanceId", ci.getEc2InstanceId());
        n.put("status", ci.getStatus());
        n.put("runningTasksCount", ci.getRunningTasksCount());
        n.put("pendingTasksCount", ci.getPendingTasksCount());
        n.put("agentVersion", ci.getAgentVersion());
        n.put("agentConnected", ci.isAgentConnected());
        if (ci.getAttributes() != null && !ci.getAttributes().isEmpty()) {
            ArrayNode attrs = objectMapper.createArrayNode();
            ci.getAttributes().forEach(a -> attrs.add(attributeNode(a)));
            n.set("attributes", attrs);
        }
        if (ci.getTags() != null && !ci.getTags().isEmpty()) {
            n.set("tags", tagsNode(ci.getTags()));
        }
        return n;
    }

    private ObjectNode capacityProviderNode(CapacityProvider cp) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("name", cp.getName());
        n.put("status", cp.getStatus());
        if (cp.getCapacityProviderArn() != null) { n.put("capacityProviderArn", cp.getCapacityProviderArn()); }
        if (cp.getTags() != null && !cp.getTags().isEmpty()) {
            n.set("tags", tagsNode(cp.getTags()));
        }
        return n;
    }

    private ObjectNode taskSetNode(TaskSet ts) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("id", ts.getId());
        n.put("taskSetArn", ts.getTaskSetArn());
        n.put("serviceArn", ts.getServiceArn());
        n.put("clusterArn", ts.getClusterArn());
        n.put("taskDefinition", ts.getTaskDefinition());
        n.put("status", ts.getStatus());
        n.put("computedDesiredCount", ts.getComputedDesiredCount());
        n.put("pendingCount", ts.getPendingCount());
        n.put("runningCount", ts.getRunningCount());
        n.put("stabilityStatus", ts.getStabilityStatus());
        if (ts.getLaunchType() != null) { n.put("launchType", ts.getLaunchType().name()); }
        if (ts.getExternalId() != null) { n.put("externalId", ts.getExternalId()); }
        ObjectNode scale = objectMapper.createObjectNode();
        scale.put("value", ts.getScaleValue());
        scale.put("unit", ts.getScaleUnit());
        n.set("scale", scale);
        if (ts.getCreatedAt() != null) { n.put("createdAt", ts.getCreatedAt().toEpochMilli() / 1000.0); }
        if (ts.getUpdatedAt() != null) { n.put("updatedAt", ts.getUpdatedAt().toEpochMilli() / 1000.0); }
        if (ts.getTags() != null && !ts.getTags().isEmpty()) {
            n.set("tags", tagsNode(ts.getTags()));
        }
        return n;
    }

    private ObjectNode serviceDeploymentNode(ServiceDeployment d) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("serviceDeploymentArn", d.getServiceDeploymentArn());
        n.put("serviceArn", d.getServiceArn());
        n.put("clusterArn", d.getClusterArn());
        n.put("taskDefinition", d.getTaskDefinition());
        n.put("status", d.getStatus());
        if (d.getCreatedAt() != null) { n.put("createdAt", d.getCreatedAt().toEpochMilli() / 1000.0); }
        if (d.getUpdatedAt() != null) { n.put("updatedAt", d.getUpdatedAt().toEpochMilli() / 1000.0); }
        return n;
    }

    private ObjectNode serviceRevisionNode(ServiceRevision r) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("serviceRevisionArn", r.getServiceRevisionArn());
        n.put("serviceArn", r.getServiceArn());
        n.put("clusterArn", r.getClusterArn());
        n.put("taskDefinition", r.getTaskDefinition());
        if (r.getLaunchType() != null) { n.put("launchType", r.getLaunchType().name()); }
        if (r.getCreatedAt() != null) { n.put("createdAt", r.getCreatedAt().toEpochMilli() / 1000.0); }
        return n;
    }

    private ObjectNode protectedTaskNode(ProtectedTask pt) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("taskArn", pt.taskArn());
        n.put("protectionEnabled", pt.protectionEnabled());
        if (pt.expirationDate() != null) {
            n.put("expirationDate", pt.expirationDate().toEpochMilli() / 1000.0);
        }
        return n;
    }

    private ObjectNode attributeNode(Attribute a) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("name", a.name());
        if (a.value() != null) { n.put("value", a.value()); }
        if (a.targetType() != null) { n.put("targetType", a.targetType()); }
        if (a.targetId() != null) { n.put("targetId", a.targetId()); }
        return n;
    }

    private ObjectNode settingNode(String name, String value) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("name", name);
        n.put("value", value);
        return n;
    }

    private ArrayNode tagsNode(Map<String, String> tags) {
        ArrayNode arr = objectMapper.createArrayNode();
        tags.forEach((k, v) -> {
            ObjectNode tag = objectMapper.createObjectNode();
            tag.put("key", k);
            tag.put("value", v);
            arr.add(tag);
        });
        return arr;
    }

    // ── Parsing helpers ───────────────────────────────────────────────────────

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
            def.setMountPoints(parseMountPoints(item.path("mountPoints")));

            if (item.has("command") && item.path("command").isArray()) {
                List<String> cmd = new ArrayList<>();
                item.path("command").forEach(c -> cmd.add(c.asText()));
                def.setCommand(cmd);
            }
            if (item.has("entryPoint") && item.path("entryPoint").isArray()) {
                List<String> ep = new ArrayList<>();
                item.path("entryPoint").forEach(e -> ep.add(e.asText()));
                def.setEntryPoint(ep);
            }

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
            result.add(new PortMapping(containerPort, hostPort, protocol));
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

    private List<Volume> parseVolumes(JsonNode node) {
        List<Volume> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            String hostSourcePath = item.path("host").path("sourcePath").asText(null);
            EfsVolumeConfiguration efs = parseEfsVolumeConfiguration(item.path("efsVolumeConfiguration"));
            result.add(new Volume(item.path("name").asText(), hostSourcePath, efs));
        }
        return result;
    }

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
        return new EfsVolumeConfiguration(
                fileSystemId,
                node.path("rootDirectory").asText(null),
                node.path("transitEncryption").asText(null),
                transitEncryptionPort,
                auth.path("accessPointId").asText(null),
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

    private List<ContainerOverride> parseContainerOverrides(JsonNode node) {
        List<ContainerOverride> result = new ArrayList<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            ContainerOverride co = new ContainerOverride();
            co.setName(item.path("name").asText());
            if (item.has("command") && item.path("command").isArray()) {
                List<String> cmd = new ArrayList<>();
                item.path("command").forEach(c -> cmd.add(c.asText()));
                co.setCommand(cmd);
            }
            co.setEnvironment(parseKeyValuePairs(item.path("environment")));
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

    private Map<String, String> parseTagMap(JsonNode node) {
        Map<String, String> result = new HashMap<>();
        if (!node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            result.put(item.path("key").asText(), item.path("value").asText());
        }
        return result;
    }

    private Map<String, Object> parseRawObject(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        return objectMapper.convertValue(node, Map.class);
    }

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

    private <T extends Enum<T>> T parseEnum(JsonNode req, String field, Class<T> enumClass) {
        if (!req.has(field)) {
            return null;
        }
        String val = req.path(field).asText();
        try {
            return Enum.valueOf(enumClass, val);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
