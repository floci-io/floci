package io.floci.aws.services.codedeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.services.codedeploy.model.Application;
import io.floci.aws.services.codedeploy.model.Deployment;
import io.floci.aws.services.codedeploy.model.DeploymentConfig;
import io.floci.aws.services.codedeploy.model.DeploymentGroup;
import io.floci.aws.services.codedeploy.model.OnPremisesInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class CodeDeployJsonHandler {

    private static final Set<String> TAG_FILTER_TYPES = Set.of("KEY_ONLY", "VALUE_ONLY", "KEY_AND_VALUE");

    private final CodeDeployService service;
    private final ObjectMapper mapper;

    @Inject
    public CodeDeployJsonHandler(CodeDeployService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region) throws Exception {
        return switch (action) {
            case "CreateApplication" -> createApplication(request, region);
            case "GetApplication" -> getApplication(request, region);
            case "UpdateApplication" -> updateApplication(request, region);
            case "DeleteApplication" -> deleteApplication(request, region);
            case "ListApplications" -> listApplications(request, region);
            case "BatchGetApplications" -> batchGetApplications(request, region);
            case "CreateDeploymentGroup" -> createDeploymentGroup(request, region);
            case "GetDeploymentGroup" -> getDeploymentGroup(request, region);
            case "UpdateDeploymentGroup" -> updateDeploymentGroup(request, region);
            case "DeleteDeploymentGroup" -> deleteDeploymentGroup(request, region);
            case "ListDeploymentGroups" -> listDeploymentGroups(request, region);
            case "BatchGetDeploymentGroups" -> batchGetDeploymentGroups(request, region);
            case "CreateDeploymentConfig" -> createDeploymentConfig(request, region);
            case "GetDeploymentConfig" -> getDeploymentConfig(request, region);
            case "DeleteDeploymentConfig" -> deleteDeploymentConfig(request, region);
            case "ListDeploymentConfigs" -> listDeploymentConfigs(region);
            case "TagResource" -> tagResource(request);
            case "UntagResource" -> untagResource(request);
            case "ListTagsForResource" -> listTagsForResource(request);
            case "CreateDeployment" -> createDeployment(request, region);
            case "GetDeployment" -> getDeployment(request, region);
            case "ListDeployments" -> listDeployments(request, region);
            case "StopDeployment" -> stopDeployment(request, region);
            case "ContinueDeployment" -> Response.ok(Map.of()).build();
            case "BatchGetDeployments" -> batchGetDeployments(request, region);
            case "ListDeploymentTargets" -> listDeploymentTargets(request, region);
            case "BatchGetDeploymentTargets" -> batchGetDeploymentTargets(request, region);
            case "PutLifecycleEventHookExecutionStatus" -> putLifecycleEventHookExecutionStatus(request);
            case "RegisterOnPremisesInstance" -> registerOnPremisesInstance(request, region);
            case "DeregisterOnPremisesInstance" -> deregisterOnPremisesInstance(request, region);
            case "GetOnPremisesInstance" -> getOnPremisesInstance(request, region);
            case "BatchGetOnPremisesInstances" -> batchGetOnPremisesInstances(request, region);
            case "ListOnPremisesInstances" -> listOnPremisesInstances(request, region);
            case "AddTagsToOnPremisesInstances" -> addTagsToOnPremisesInstances(request, region);
            case "RemoveTagsFromOnPremisesInstances" -> removeTagsFromOnPremisesInstances(request, region);
            default -> throw new AwsException("InvalidAction", "Action " + action + " is not supported", 400);
        };
    }

    private Response createApplication(JsonNode req, String region) {
        String name = req.path("applicationName").asText(null);
        String computePlatform = req.has("computePlatform") ? req.path("computePlatform").asText() : null;
        List<Map<String, String>> tags = parseOptionalTags(req, "tags");
        Application app = service.createApplication(region, name, computePlatform, tags);
        return Response.ok(Map.of("applicationId", app.getApplicationId())).build();
    }

    private Response getApplication(JsonNode req, String region) {
        String name = req.path("applicationName").asText(null);
        Application app = service.getApplication(region, name);
        return Response.ok(Map.of("application", app)).build();
    }

    private Response updateApplication(JsonNode req, String region) {
        String currentName = req.path("applicationName").asText(null);
        String newName = req.has("newApplicationName") ? req.path("newApplicationName").asText() : null;
        service.updateApplication(region, currentName, newName);
        return Response.ok(Map.of()).build();
    }

    private Response deleteApplication(JsonNode req, String region) {
        String name = req.path("applicationName").asText(null);
        service.deleteApplication(region, name);
        return Response.ok(Map.of()).build();
    }

    private Response listApplications(JsonNode request, String region) {
        String token = Pagination.parseNextToken(request, "nextToken", "InvalidNextTokenException");
        PaginatedResult<String> page = service.listApplications(region, token);
        Map<String, Object> response = new HashMap<>();
        response.put("applications", page.items());
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response batchGetApplications(JsonNode req, String region) {
        List<String> names = new ArrayList<>();
        req.path("applicationNames").forEach(n -> names.add(n.asText()));
        List<Application> apps = service.batchGetApplications(region, names);
        return Response.ok(Map.of("applicationsInfo", apps)).build();
    }

    private Response createDeploymentGroup(JsonNode req, String region) throws Exception {
        String appName = req.path("applicationName").asText(null);
        String groupName = req.path("deploymentGroupName").asText(null);
        String deploymentConfigName = req.has("deploymentConfigName") ? req.path("deploymentConfigName").asText() : null;
        String serviceRoleArn = req.has("serviceRoleArn") ? req.path("serviceRoleArn").asText() : null;
        Map<String, Object> fields = extractGroupFields(req);
        List<Map<String, String>> tags = parseOptionalTags(req, "tags");
        DeploymentGroup group = service.createDeploymentGroup(region, appName, groupName,
                deploymentConfigName, serviceRoleArn, fields, tags);
        return Response.ok(Map.of("deploymentGroupId", group.getDeploymentGroupId())).build();
    }

    private Response getDeploymentGroup(JsonNode req, String region) {
        String appName = req.path("applicationName").asText(null);
        String groupName = req.path("deploymentGroupName").asText(null);
        DeploymentGroup group = service.getDeploymentGroup(region, appName, groupName);
        return Response.ok(Map.of("deploymentGroupInfo", group)).build();
    }

    private Response updateDeploymentGroup(JsonNode req, String region) throws Exception {
        String appName = req.path("applicationName").asText(null);
        String currentGroupName = req.path("currentDeploymentGroupName").asText(null);
        String newGroupName = req.has("newDeploymentGroupName") ? req.path("newDeploymentGroupName").asText() : null;
        String deploymentConfigName = req.has("deploymentConfigName") ? req.path("deploymentConfigName").asText() : null;
        String serviceRoleArn = req.has("serviceRoleArn") ? req.path("serviceRoleArn").asText() : null;
        Map<String, Object> fields = extractGroupFields(req);
        DeploymentGroup group = service.updateDeploymentGroup(region, appName, currentGroupName, newGroupName,
                deploymentConfigName, serviceRoleArn, fields);
        return Response.ok(Map.of()).build();
    }

    private Response deleteDeploymentGroup(JsonNode req, String region) {
        String appName = req.path("applicationName").asText(null);
        String groupName = req.path("deploymentGroupName").asText(null);
        service.deleteDeploymentGroup(region, appName, groupName);
        return Response.ok(Map.of("hooksNotCleanedUp", List.of())).build();
    }

    private Response listDeploymentGroups(JsonNode req, String region) {
        String appName = req.path("applicationName").asText(null);
        List<String> groups = service.listDeploymentGroups(region, appName);
        return Response.ok(Map.of("applicationName", appName, "deploymentGroups", groups)).build();
    }

    private Response batchGetDeploymentGroups(JsonNode req, String region) {
        String appName = req.path("applicationName").asText(null);
        List<String> names = new ArrayList<>();
        req.path("deploymentGroupNames").forEach(n -> names.add(n.asText()));
        List<DeploymentGroup> found = service.batchGetDeploymentGroups(region, appName, names);
        List<String> foundNames = found.stream().map(DeploymentGroup::getDeploymentGroupName).toList();
        List<String> notFound = names.stream().filter(n -> !foundNames.contains(n)).toList();
        return Response.ok(Map.of("deploymentGroupsInfo", found, "errorMessage", "")).build();
    }

    private Response createDeploymentConfig(JsonNode req, String region) throws Exception {
        String name = req.path("deploymentConfigName").asText(null);
        Map<String, Object> minimumHealthyHosts = req.has("minimumHealthyHosts")
                ? mapper.treeToValue(req.get("minimumHealthyHosts"), Map.class) : null;
        String computePlatform = req.has("computePlatform") ? req.path("computePlatform").asText() : null;
        Map<String, Object> trafficRoutingConfig = req.has("trafficRoutingConfig")
                ? mapper.treeToValue(req.get("trafficRoutingConfig"), Map.class) : null;
        Map<String, Object> zonalConfig = req.has("zonalConfig")
                ? mapper.treeToValue(req.get("zonalConfig"), Map.class) : null;
        DeploymentConfig cfg = service.createDeploymentConfig(region, name, minimumHealthyHosts,
                computePlatform, trafficRoutingConfig, zonalConfig);
        return Response.ok(Map.of("deploymentConfigId", cfg.getDeploymentConfigId())).build();
    }

    private Response getDeploymentConfig(JsonNode req, String region) {
        String name = req.path("deploymentConfigName").asText(null);
        DeploymentConfig cfg = service.getDeploymentConfig(region, name);
        return Response.ok(Map.of("deploymentConfigInfo", cfg)).build();
    }

    private Response deleteDeploymentConfig(JsonNode req, String region) {
        String name = req.path("deploymentConfigName").asText(null);
        service.deleteDeploymentConfig(region, name);
        return Response.ok(Map.of()).build();
    }

    private Response listDeploymentConfigs(String region) {
        return Response.ok(Map.of("deploymentConfigsList", service.listDeploymentConfigs(region))).build();
    }

    private Response tagResource(JsonNode req) {
        String arn = req.path("ResourceArn").asText(null);
        List<Map<String, String>> tags = parseTags(req, "Tags");
        service.tagResource(arn, tags);
        return Response.ok(Map.of()).build();
    }

    private Response untagResource(JsonNode req) {
        String arn = req.path("ResourceArn").asText(null);
        List<String> keys = new ArrayList<>();
        req.path("TagKeys").forEach(k -> keys.add(k.asText()));
        service.untagResource(arn, keys);
        return Response.ok(Map.of()).build();
    }

    private Response listTagsForResource(JsonNode req) {
        String arn = req.path("ResourceArn").asText(null);
        List<Map<String, String>> tags = service.listTagsForResource(arn);
        return Response.ok(Map.of("Tags", tags)).build();
    }

    private Response createDeployment(JsonNode req, String region) throws Exception {
        String appName = req.path("applicationName").asText(null);
        String groupName = req.path("deploymentGroupName").asText(null);
        String configName = req.has("deploymentConfigName") ? req.path("deploymentConfigName").asText() : null;
        String description = req.has("description") ? req.path("description").asText() : null;
        Map<String, Object> revision = req.has("revision")
                ? mapper.treeToValue(req.get("revision"), Map.class) : null;
        String deploymentId = service.createDeployment(region, appName, groupName, configName, revision, description);
        return Response.ok(Map.of("deploymentId", deploymentId)).build();
    }

    private Response getDeployment(JsonNode req, String region) {
        String id = req.path("deploymentId").asText(null);
        Deployment d = service.getDeployment(region, id);
        return Response.ok(Map.of("deploymentInfo", d)).build();
    }

    private Response listDeployments(JsonNode req, String region) {
        String appName = req.has("applicationName") ? req.path("applicationName").asText() : null;
        String groupName = req.has("deploymentGroupName") ? req.path("deploymentGroupName").asText() : null;
        List<String> statuses = new ArrayList<>();
        req.path("includeOnlyStatuses").forEach(n -> statuses.add(n.asText()));
        List<String> ids = service.listDeployments(region, appName, groupName, statuses);
        return Response.ok(Map.of("deployments", ids)).build();
    }

    private Response stopDeployment(JsonNode req, String region) {
        String id = req.path("deploymentId").asText(null);
        Map<String, String> result = service.stopDeployment(region, id);
        return Response.ok(result).build();
    }

    private Response batchGetDeployments(JsonNode req, String region) {
        List<String> ids = new ArrayList<>();
        req.path("deploymentIds").forEach(n -> ids.add(n.asText()));
        List<Deployment> deploymentList = service.batchGetDeployments(region, ids);
        return Response.ok(Map.of("deploymentsInfo", deploymentList)).build();
    }

    private Response listDeploymentTargets(JsonNode req, String region) {
        String deploymentId = req.path("deploymentId").asText(null);
        List<String> targetIds = service.listDeploymentTargets(region, deploymentId);
        return Response.ok(Map.of("targetIds", targetIds)).build();
    }

    private Response batchGetDeploymentTargets(JsonNode req, String region) {
        String deploymentId = req.path("deploymentId").asText(null);
        List<String> targetIds = new ArrayList<>();
        req.path("targetIds").forEach(n -> targetIds.add(n.asText()));
        List<Map<String, Object>> targets = service.batchGetDeploymentTargets(region, deploymentId, targetIds);
        return Response.ok(Map.of("deploymentTargets", targets)).build();
    }

    private Response putLifecycleEventHookExecutionStatus(JsonNode req) {
        String deploymentId = req.path("deploymentId").asText(null);
        String executionId = req.path("lifecycleEventHookExecutionId").asText(null);
        String status = req.path("status").asText("Succeeded");
        String id = service.putLifecycleEventHookExecutionStatus(deploymentId, executionId, status);
        return Response.ok(Map.of("lifecycleEventHookExecutionId", id)).build();
    }

    private Response registerOnPremisesInstance(JsonNode req, String region) {
        String instanceName = req.path("instanceName").asText(null);
        String iamSessionArn = req.has("iamSessionArn") ? req.path("iamSessionArn").asText() : null;
        String iamUserArn = req.has("iamUserArn") ? req.path("iamUserArn").asText() : null;
        service.registerOnPremisesInstance(region, instanceName, iamSessionArn, iamUserArn);
        return Response.ok(Map.of()).build();
    }

    private Response deregisterOnPremisesInstance(JsonNode req, String region) {
        String instanceName = req.path("instanceName").asText(null);
        service.deregisterOnPremisesInstance(region, instanceName);
        return Response.ok(Map.of()).build();
    }

    private Response getOnPremisesInstance(JsonNode req, String region) {
        String instanceName = req.path("instanceName").asText(null);
        OnPremisesInstance inst = service.getOnPremisesInstance(region, instanceName);
        return Response.ok(Map.of("instanceInfo", inst)).build();
    }

    private Response batchGetOnPremisesInstances(JsonNode req, String region) {
        List<String> names = new ArrayList<>();
        req.path("instanceNames").forEach(n -> names.add(n.asText()));
        List<OnPremisesInstance> found = service.batchGetOnPremisesInstances(region, names);
        List<String> foundNames = found.stream().map(OnPremisesInstance::getInstanceName).toList();
        List<String> missing = names.stream().filter(n -> !foundNames.contains(n)).toList();
        return Response.ok(Map.of("instanceInfos", found, "instanceNames", missing)).build();
    }

    private Response listOnPremisesInstances(JsonNode req, String region) {
        String registrationStatus = req.has("registrationStatus") ? req.path("registrationStatus").asText() : null;
        List<Map<String, String>> tagFilters = parseTagFilters(req);
        List<String> names = service.listOnPremisesInstances(region, registrationStatus, tagFilters);
        return Response.ok(Map.of("instanceNames", names)).build();
    }

    private Response addTagsToOnPremisesInstances(JsonNode req, String region) {
        List<Map<String, String>> tags = parseTags(req, "tags", "InvalidTagException");
        List<String> names = new ArrayList<>();
        req.path("instanceNames").forEach(n -> names.add(n.asText()));
        service.addTagsToOnPremisesInstances(region, names, tags);
        return Response.ok(Map.of()).build();
    }

    private Response removeTagsFromOnPremisesInstances(JsonNode req, String region) {
        List<Map<String, String>> tags = parseTags(req, "tags", "InvalidTagException");
        List<String> names = new ArrayList<>();
        req.path("instanceNames").forEach(n -> names.add(n.asText()));
        service.removeTagsFromOnPremisesInstances(region, names, tags);
        return Response.ok(Map.of()).build();
    }

    private Map<String, Object> extractGroupFields(JsonNode req) throws Exception {
        Map<String, Object> fields = new HashMap<>();
        for (String field : new String[]{"ec2TagFilters", "onPremisesInstanceTagFilters", "autoScalingGroups",
                "deploymentStyle", "blueGreenDeploymentConfiguration", "loadBalancerInfo",
                "ec2TagSet", "onPremisesTagSet", "alarmConfiguration", "autoRollbackConfiguration",
                "triggerConfigurations", "ecsServices", "computePlatform",
                "outdatedInstancesStrategy", "terminationHookEnabled"}) {
            if (req.has(field)) {
                fields.put(field, mapper.treeToValue(req.get(field), Object.class));
            }
        }
        return fields;
    }

    private List<Map<String, String>> parseTagFilters(JsonNode req) {
        if (!req.has("tagFilters") || req.get("tagFilters").isNull()) {
            return List.of();
        }
        JsonNode filterList = req.get("tagFilters");
        if (!filterList.isArray()) {
            throw new AwsException("InvalidTagFilterException", "The specified tag filters are not valid.", 400);
        }
        List<Map<String, String>> result = new ArrayList<>();
        for (JsonNode filter : filterList) {
            if (!filter.isObject()) {
                throw new AwsException("InvalidTagFilterException", "The specified tag filters are not valid.", 400);
            }
            Map<String, String> values = new HashMap<>();
            for (String member : List.of("Key", "Value", "Type")) {
                if (filter.hasNonNull(member)) {
                    JsonNode value = filter.get(member);
                    if (!value.isTextual()
                            || (member.equals("Type") && !TAG_FILTER_TYPES.contains(value.asText()))) {
                        throw new AwsException("InvalidTagFilterException", "The specified tag filters are not valid.", 400);
                    }
                    values.put(member, value.asText());
                }
            }
            result.add(values);
        }
        return result;
    }

    private List<Map<String, String>> parseTags(JsonNode req, String fieldName) {
        return parseTags(req, fieldName, "InvalidTagsToAddException");
    }

    private List<Map<String, String>> parseOptionalTags(JsonNode req, String fieldName) {
        return req.path(fieldName).isNull() ? List.of() : parseTags(req, fieldName);
    }

    private List<Map<String, String>> parseTags(JsonNode req, String fieldName, String invalidType) {
        if (!req.has(fieldName)) {
            return List.of();
        }
        JsonNode tagList = req.get(fieldName);
        if (!tagList.isArray()) {
            throw new AwsException(invalidType, "The specified tags are not valid.", 400);
        }
        List<Map<String, String>> result = new ArrayList<>();
        for (JsonNode tag : tagList) {
            if (!tag.isObject() || !tag.path("Key").isTextual()
                    || tag.has("Value") && !tag.get("Value").isTextual()) {
                throw new AwsException(invalidType, "The specified tags are not valid.", 400);
            }
            result.add(Map.of("Key", tag.get("Key").asText(), "Value", tag.path("Value").asText("")));
        }
        return result;
    }
}
