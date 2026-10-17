package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.services.redshift.RedshiftIamDbUserResolver;
import io.github.hectorvent.floci.services.redshift.TempCredential;
import io.github.hectorvent.floci.services.redshiftserverless.model.ConfigParameter;
import io.github.hectorvent.floci.services.redshiftserverless.model.CustomDomainAssociation;
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import io.github.hectorvent.floci.services.redshiftserverless.model.PricePerformanceTarget;
import io.github.hectorvent.floci.services.redshiftserverless.model.RedshiftServerlessSnapshot;
import io.github.hectorvent.floci.services.redshiftserverless.model.UsageLimit;
import io.github.hectorvent.floci.services.redshiftserverless.model.Workgroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Redshift Serverless JSON 1.1 handler. Dispatched from
 * {@link io.github.hectorvent.floci.core.common.AwsJson11Controller}
 * under the {@code RedshiftServerless.} target prefix.
 */
@ApplicationScoped
public class RedshiftServerlessJsonHandler {

    private static final Logger LOG = Logger.getLogger(RedshiftServerlessJsonHandler.class);

    private final RedshiftServerlessService service;
    private final ObjectMapper objectMapper;
    private final RedshiftIamDbUserResolver iamDbUserResolver;
    private final RedshiftServerlessUsageLimitService usageLimits;
    private final RedshiftServerlessAdminJsonHandler admin;
    private final RedshiftServerlessCustomDomainService customDomains;

    @Inject
    public RedshiftServerlessJsonHandler(RedshiftServerlessService service, ObjectMapper objectMapper,
                                         RedshiftIamDbUserResolver iamDbUserResolver,
                                         RedshiftServerlessUsageLimitService usageLimits,
                                         RedshiftServerlessAdminJsonHandler admin,
                                         RedshiftServerlessCustomDomainService customDomains) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.iamDbUserResolver = iamDbUserResolver;
        this.usageLimits = usageLimits;
        this.admin = admin;
        this.customDomains = customDomains;
    }

    public Response handle(String action, JsonNode request, String region) {
        return handle(action, request, region, null);
    }

    public Response handle(String action, JsonNode request, String region, String authorizationHeader) {
        LOG.debugv("Redshift Serverless action: {0}", action);
        try {
            Supplier<Response> operation = switch (action) {
                case "CreateNamespace" -> () -> handleCreateNamespace(request, region);
                case "GetNamespace" -> () -> handleGetNamespace(request, region);
                case "ListNamespaces" -> () -> handleListNamespaces(request, region);
                case "UpdateNamespace" -> () -> handleUpdateNamespace(request, region);
                case "DeleteNamespace" -> () -> handleDeleteNamespace(request, region);
                case "CreateWorkgroup" -> () -> handleCreateWorkgroup(request, region);
                case "GetWorkgroup" -> () -> handleGetWorkgroup(request, region);
                case "ListWorkgroups" -> () -> handleListWorkgroups(request, region);
                case "UpdateWorkgroup" -> () -> handleUpdateWorkgroup(request, region);
                case "DeleteWorkgroup" -> () -> handleDeleteWorkgroup(request, region);
                case "CreateSnapshot" -> () -> handleCreateSnapshot(request, region);
                case "GetSnapshot" -> () -> handleGetSnapshot(request, region);
                case "ListSnapshots" -> () -> handleListSnapshots(request, region);
                case "UpdateSnapshot" -> () -> handleUpdateSnapshot(request, region);
                case "DeleteSnapshot" -> () -> handleDeleteSnapshot(request, region);
                case "RestoreFromSnapshot" -> () -> handleRestoreFromSnapshot(request, region);
                case "CreateUsageLimit" -> () -> handleCreateUsageLimit(request, region);
                case "GetUsageLimit" -> () -> handleGetUsageLimit(request, region);
                case "ListUsageLimits" -> () -> handleListUsageLimits(request, region);
                case "UpdateUsageLimit" -> () -> handleUpdateUsageLimit(request, region);
                case "DeleteUsageLimit" -> () -> handleDeleteUsageLimit(request, region);
                case "GetCredentials" -> () -> handleGetCredentials(request, region, authorizationHeader);
                case "ListTagsForResource" -> () -> handleListTagsForResource(request, region);
                case "TagResource" -> () -> handleTagResource(request, region);
                case "UntagResource" -> () -> handleUntagResource(request, region);
                default -> admin.operation(action, request, region);
            };
            if (operation == null) {
                return Response.status(400)
                        .entity(new AwsErrorResponse("UnknownOperationException",
                                "Operation " + action + " is not supported."))
                        .build();
            }
            RedshiftServerlessRequestShapes.check(action, request);
            return operation.get();
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        } catch (Exception e) {
            LOG.errorf(e, "Redshift Serverless error processing action %s", action);
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    private Response handleCreateNamespace(JsonNode request, String region) {
        Namespace namespace = service.createNamespace(
                ServerlessSupport.text(request, "namespaceName"),
                ServerlessSupport.text(request, "adminUsername"),
                ServerlessSupport.text(request, "adminUserPassword"),
                ServerlessSupport.text(request, "dbName"),
                ServerlessSupport.text(request, "kmsKeyId"),
                ServerlessSupport.text(request, "defaultIamRoleArn"),
                ServerlessSupport.stringList(request, "iamRoles"),
                ServerlessSupport.stringList(request, "logExports"),
                parseTagList(request.path("tags"), "tags"),
                region);
        return namespaceResponse(namespace);
    }

    private Response handleGetNamespace(JsonNode request, String region) {
        return namespaceResponse(service.getNamespace(ServerlessSupport.text(request, "namespaceName"), region));
    }

    private Response handleListNamespaces(JsonNode request, String region) {
        PaginatedResult<Namespace> page = service.listNamespaces(
                region, ServerlessSupport.maxResults(request), ServerlessSupport.text(request, "nextToken"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("namespaces");
        page.items().forEach(namespace -> items.add(namespaceNode(namespace)));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response handleUpdateNamespace(JsonNode request, String region) {
        Namespace namespace = service.updateNamespace(
                ServerlessSupport.text(request, "namespaceName"),
                ServerlessSupport.text(request, "adminUsername"),
                ServerlessSupport.text(request, "adminUserPassword"),
                ServerlessSupport.text(request, "kmsKeyId"),
                ServerlessSupport.text(request, "defaultIamRoleArn"),
                ServerlessSupport.stringList(request, "iamRoles"),
                ServerlessSupport.stringList(request, "logExports"),
                region);
        return namespaceResponse(namespace);
    }

    private Response handleDeleteNamespace(JsonNode request, String region) {
        return namespaceResponse(service.deleteNamespace(ServerlessSupport.text(request, "namespaceName"), region));
    }

    private Response handleCreateWorkgroup(JsonNode request, String region) {
        Workgroup workgroup = service.createWorkgroup(
                ServerlessSupport.text(request, "workgroupName"),
                ServerlessSupport.text(request, "namespaceName"),
                parseWorkgroupSettings(request),
                parseTagList(request.path("tags"), "tags"),
                region);
        return workgroupResponse(workgroup, region);
    }

    private Response handleGetWorkgroup(JsonNode request, String region) {
        return workgroupResponse(service.getWorkgroup(ServerlessSupport.text(request, "workgroupName"), region), region);
    }

    private Response handleListWorkgroups(JsonNode request, String region) {
        PaginatedResult<Workgroup> page = service.listWorkgroups(
                region, ServerlessSupport.maxResults(request), ServerlessSupport.text(request, "nextToken"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("workgroups");
        page.items().forEach(workgroup -> items.add(workgroupNode(workgroup, region)));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response handleUpdateWorkgroup(JsonNode request, String region) {
        return workgroupResponse(service.updateWorkgroup(
                ServerlessSupport.text(request, "workgroupName"), parseWorkgroupSettings(request), region), region);
    }

    private Response handleDeleteWorkgroup(JsonNode request, String region) {
        return workgroupResponse(service.deleteWorkgroup(ServerlessSupport.text(request, "workgroupName"), region), region);
    }

    /**
     * {@code expiration} and {@code nextRefreshTime} carry no timestamp format trait in the API
     * model, so they use the awsJson1.1 default of epoch seconds as a JSON number. Floci never
     * refreshes an authorization early, so the refresh time is the expiry.
     */
    private Response handleGetCredentials(JsonNode request, String region, String authorizationHeader) {
        TempCredential credential = service.getCredentials(
                workgroupNameOf(request, region),
                ServerlessSupport.text(request, "dbName"),
                ServerlessSupport.integer(request, "durationSeconds"),
                iamDbUserResolver.resolveDbUser(authorizationHeader),
                region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("dbUser", credential.dbUser());
        response.put("dbPassword", credential.password());
        response.put("expiration", credential.expiresAt().getEpochSecond());
        response.put("nextRefreshTime", credential.expiresAt().getEpochSecond());
        return Response.ok(response).build();
    }

    /**
     * Resolves the workgroup name from {@code workgroupName}, or from {@code customDomainName} when given.
     * AWS requires exactly one of the two to identify the target workgroup.
     */
    private String workgroupNameOf(JsonNode request, String region) {
        String workgroupName = ServerlessSupport.text(request, "workgroupName");
        String customDomainName = ServerlessSupport.text(request, "customDomainName");
        if (workgroupName != null && customDomainName != null) {
            throw ServerlessSupport.validation("workgroupName and customDomainName cannot both be specified.");
        }
        if (workgroupName == null && customDomainName == null) {
            throw ServerlessSupport.validation(
                    "The custom domain name or the workgroup name must be included in the request.");
        }
        if (customDomainName != null) {
            return customDomains.findForCustomDomain(customDomainName, region)
                    .map(CustomDomainAssociation::getWorkgroupName)
                    .orElseThrow(() -> ServerlessSupport.notFound("The custom domain " + customDomainName + " was not found."));
        }
        return workgroupName;
    }

    private Response handleCreateSnapshot(JsonNode request, String region) {
        return snapshotResponse(service.createSnapshot(
                ServerlessSupport.text(request, "snapshotName"), ServerlessSupport.text(request, "namespaceName"),
                ServerlessSupport.integer(request, "retentionPeriod"), parseTagList(request.path("tags"), "tags"), region));
    }

    /**
     * GetSnapshot also accepts {@code snapshotArn}, which must name a snapshot of this account
     * and Region.
     */
    private Response handleGetSnapshot(JsonNode request, String region) {
        return snapshotResponse(service.getSnapshot(snapshotNameOf(request, region), region));
    }

    private Response handleListSnapshots(JsonNode request, String region) {
        PaginatedResult<RedshiftServerlessSnapshot> page = service.listSnapshots(
                ServerlessSupport.text(request, "namespaceName"), ServerlessSupport.text(request, "namespaceArn"), ServerlessSupport.text(request, "ownerAccount"),
                ServerlessSupport.epochSeconds(request, "startTime"), ServerlessSupport.epochSeconds(request, "endTime"), region,
                ServerlessSupport.maxResults(request), ServerlessSupport.text(request, "nextToken"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("snapshots");
        page.items().forEach(snapshot -> items.add(snapshotNode(snapshot)));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response handleUpdateSnapshot(JsonNode request, String region) {
        return snapshotResponse(service.updateSnapshot(
                ServerlessSupport.text(request, "snapshotName"), ServerlessSupport.integer(request, "retentionPeriod"), region));
    }

    private Response handleDeleteSnapshot(JsonNode request, String region) {
        return snapshotResponse(service.deleteSnapshot(ServerlessSupport.text(request, "snapshotName"), region));
    }

    private Response handleRestoreFromSnapshot(JsonNode request, String region) {
        RedshiftServerlessService.RestoreResult result = service.restoreFromSnapshot(
                ServerlessSupport.text(request, "namespaceName"), ServerlessSupport.text(request, "workgroupName"),
                snapshotNameOf(request, region), ServerlessSupport.text(request, "ownerAccount"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("namespace", namespaceNode(result.namespace()));
        response.put("ownerAccount", result.snapshot().getOwnerAccount());
        response.put("snapshotName", result.snapshot().getSnapshotName());
        return Response.ok(response).build();
    }

    /** The snapshot name from {@code snapshotName}, or recovered from {@code snapshotArn} when absent. */
    private String snapshotNameOf(JsonNode request, String region) {
        String snapshotName = ServerlessSupport.text(request, "snapshotName");
        String snapshotArn = ServerlessSupport.text(request, "snapshotArn");
        if (snapshotName != null && snapshotArn != null) {
            throw ServerlessSupport.validation("snapshotName and snapshotArn cannot both be specified.");
        }
        return snapshotArn == null ? snapshotName : service.snapshotNameFromArn(snapshotArn, region);
    }

    private Response handleCreateUsageLimit(JsonNode request, String region) {
        return usageLimitResponse(usageLimits.createUsageLimit(
                ServerlessSupport.text(request, "resourceArn"),
                ServerlessSupport.text(request, "usageType"),
                parseLong(request, "amount"),
                ServerlessSupport.text(request, "period"),
                ServerlessSupport.text(request, "breachAction"),
                region));
    }

    private Response handleGetUsageLimit(JsonNode request, String region) {
        return usageLimitResponse(usageLimits.getUsageLimit(ServerlessSupport.text(request, "usageLimitId"), region));
    }

    private Response handleListUsageLimits(JsonNode request, String region) {
        PaginatedResult<UsageLimit> page = usageLimits.listUsageLimits(
                ServerlessSupport.text(request, "resourceArn"), ServerlessSupport.text(request, "usageType"), region,
                ServerlessSupport.maxResults(request), ServerlessSupport.text(request, "nextToken"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("usageLimits");
        page.items().forEach(limit -> items.add(usageLimitNode(limit)));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response handleUpdateUsageLimit(JsonNode request, String region) {
        return usageLimitResponse(usageLimits.updateUsageLimit(
                ServerlessSupport.text(request, "usageLimitId"),
                parseLong(request, "amount"),
                ServerlessSupport.text(request, "breachAction"),
                region));
    }

    private Response handleDeleteUsageLimit(JsonNode request, String region) {
        return usageLimitResponse(usageLimits.deleteUsageLimit(ServerlessSupport.text(request, "usageLimitId"), region));
    }

    private Response usageLimitResponse(UsageLimit limit) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("usageLimit", usageLimitNode(limit));
        return Response.ok(response).build();
    }

    /** {@code UsageLimit} has no timestamp member, so the creation date the store keeps is not emitted. */
    private ObjectNode usageLimitNode(UsageLimit limit) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("usageLimitId", limit.getUsageLimitId());
        node.put("usageLimitArn", limit.getUsageLimitArn());
        node.put("resourceArn", limit.getResourceArn());
        node.put("usageType", limit.getUsageType());
        node.put("amount", limit.getAmount());
        node.put("period", limit.getPeriod());
        node.put("breachAction", limit.getBreachAction());
        return node;
    }

    private Response handleListTagsForResource(JsonNode request, String region) {
        Map<String, String> tags = service.listTagsForResource(ServerlessSupport.text(request, "resourceArn"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("tags", tagListNode(tags));
        return Response.ok(response).build();
    }

    private Response handleTagResource(JsonNode request, String region) {
        service.tagResource(ServerlessSupport.text(request, "resourceArn"), parseTagList(request.path("tags"), "tags"), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request, String region) {
        service.untagResource(ServerlessSupport.text(request, "resourceArn"), ServerlessSupport.stringList(request, "tagKeys"), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ArrayNode tagListNode(Map<String, String> tags) {
        ArrayNode node = objectMapper.createArrayNode();
        tags.forEach((key, value) -> {
            ObjectNode tag = node.addObject();
            tag.put("key", key);
            tag.put("value", value);
        });
        return node;
    }

    private Response namespaceResponse(Namespace namespace) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("namespace", namespaceNode(namespace));
        return Response.ok(response).build();
    }

    /**
     * {@code adminUserPassword} is deliberately absent: AWS never returns it on any namespace
     * operation, and the Terraform provider treats a returned value as a permanent diff.
     */
    private ObjectNode namespaceNode(Namespace namespace) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("namespaceName", namespace.getNamespaceName());
        node.put("namespaceId", namespace.getNamespaceId());
        node.put("namespaceArn", namespace.getNamespaceArn());
        node.put("dbName", namespace.getDbName());
        node.put("kmsKeyId", namespace.getKmsKeyId());
        node.put("status", namespace.getStatus());
        if (namespace.getAdminUsername() != null) {
            node.put("adminUsername", namespace.getAdminUsername());
        }
        if (namespace.getDefaultIamRoleArn() != null) {
            node.put("defaultIamRoleArn", namespace.getDefaultIamRoleArn());
        }
        if (namespace.getCreationDate() != null) {
            node.put("creationDate", ServerlessSupport.ISO_8601.format(namespace.getCreationDate()));
        }
        ArrayNode iamRoles = node.putArray("iamRoles");
        namespace.getIamRoles().forEach(iamRoles::add);
        ArrayNode logExports = node.putArray("logExports");
        namespace.getLogExports().forEach(logExports::add);
        return node;
    }

    private Response snapshotResponse(RedshiftServerlessSnapshot snapshot) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("snapshot", snapshotNode(snapshot));
        return Response.ok(response).build();
    }

    private ObjectNode snapshotNode(RedshiftServerlessSnapshot snapshot) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("snapshotName", snapshot.getSnapshotName());
        node.put("snapshotArn", snapshot.getSnapshotArn());
        node.put("namespaceName", snapshot.getNamespaceName());
        node.put("namespaceArn", snapshot.getNamespaceArn());
        node.put("ownerAccount", snapshot.getOwnerAccount());
        node.put("status", snapshot.getStatus());
        node.put("adminUsername", snapshot.getAdminUsername());
        if (snapshot.getKmsKeyId() != null) {
            node.put("kmsKeyId", snapshot.getKmsKeyId());
        }
        if (snapshot.getRetentionPeriod() != null) {
            node.put("snapshotRetentionPeriod", snapshot.getRetentionPeriod());
            long elapsedDays = Duration.between(snapshot.getSnapshotCreateTime(), Instant.now()).toDays();
            node.put("snapshotRemainingDays", Math.max(0, snapshot.getRetentionPeriod() - elapsedDays));
        }
        if (snapshot.getSnapshotCreateTime() != null) {
            node.put("snapshotCreateTime", ServerlessSupport.ISO_8601.format(snapshot.getSnapshotCreateTime()));
        }
        return node;
    }

    private Response workgroupResponse(Workgroup workgroup, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("workgroup", workgroupNode(workgroup, region));
        return Response.ok(response).build();
    }

    /**
     * Members the emulator does not model ({@code workgroupVersion}, {@code patchVersion},
     * {@code crossAccountVpcs} and {@code endpoint.vpcEndpoints}) are
     * omitted rather than invented; all of them are optional in the API model.
     */
    private ObjectNode workgroupNode(Workgroup workgroup, String region) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("workgroupName", workgroup.getWorkgroupName());
        node.put("workgroupId", workgroup.getWorkgroupId());
        node.put("workgroupArn", workgroup.getWorkgroupArn());
        node.put("namespaceName", workgroup.getNamespaceName());
        node.put("status", workgroup.getStatus());
        customDomains.findForWorkgroup(workgroup.getWorkgroupName(), region)
                .ifPresent(association -> ServerlessSupport.populateCustomDomain(node, association));
        if (workgroup.getBaseCapacity() != null) {
            node.put("baseCapacity", workgroup.getBaseCapacity());
        }
        if (workgroup.getMaxCapacity() != null) {
            node.put("maxCapacity", workgroup.getMaxCapacity());
        }
        node.put("enhancedVpcRouting", workgroup.isEnhancedVpcRouting());
        node.put("publiclyAccessible", workgroup.isPubliclyAccessible());
        node.put("extraComputeForAutomaticOptimization", workgroup.isExtraComputeForAutomaticOptimization());
        node.put("port", workgroup.getPort());
        node.put("ipAddressType", workgroup.getIpAddressType());
        node.put("trackName", workgroup.getTrackName());
        if (workgroup.getPendingTrackName() != null) {
            node.put("pendingTrackName", workgroup.getPendingTrackName());
        }
        ArrayNode configParameters = node.putArray("configParameters");
        for (ConfigParameter parameter : workgroup.getConfigParameters()) {
            ObjectNode entry = configParameters.addObject();
            entry.put("parameterKey", parameter.getParameterKey());
            entry.put("parameterValue", parameter.getParameterValue());
        }
        ArrayNode securityGroupIds = node.putArray("securityGroupIds");
        workgroup.getSecurityGroupIds().forEach(securityGroupIds::add);
        ArrayNode subnetIds = node.putArray("subnetIds");
        workgroup.getSubnetIds().forEach(subnetIds::add);
        PricePerformanceTarget target = workgroup.getPricePerformanceTarget();
        if (target != null) {
            ObjectNode targetNode = node.putObject("pricePerformanceTarget");
            targetNode.put("status", target.getStatus());
            if (target.getLevel() != null) {
                targetNode.put("level", target.getLevel());
            }
        }
        if (workgroup.getEndpoint() != null) {
            ObjectNode endpointNode = node.putObject("endpoint");
            endpointNode.put("address", workgroup.getEndpoint().getAddress());
            endpointNode.put("port", workgroup.getEndpoint().getPort());
        }
        if (workgroup.getCreationDate() != null) {
            node.put("creationDate", ServerlessSupport.ISO_8601.format(workgroup.getCreationDate()));
        }
        return node;
    }

    private WorkgroupSettings parseWorkgroupSettings(JsonNode request) {
        return new WorkgroupSettings(
                ServerlessSupport.integer(request, "baseCapacity"),
                ServerlessSupport.integer(request, "maxCapacity"),
                ServerlessSupport.bool(request, "enhancedVpcRouting"),
                ServerlessSupport.bool(request, "publiclyAccessible"),
                ServerlessSupport.bool(request, "extraComputeForAutomaticOptimization"),
                parseConfigParameters(request.path("configParameters")),
                ServerlessSupport.stringList(request, "securityGroupIds"),
                ServerlessSupport.stringList(request, "subnetIds"),
                ServerlessSupport.integer(request, "port"),
                parsePricePerformanceTarget(request.path("pricePerformanceTarget")),
                ServerlessSupport.text(request, "ipAddressType"),
                ServerlessSupport.text(request, "trackName"));
    }

    private List<ConfigParameter> parseConfigParameters(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw ServerlessSupport.validation("configParameters must be an array of parameters.");
        }
        List<ConfigParameter> parameters = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isObject()) {
                throw ServerlessSupport.validation("configParameters must contain only parameter objects.");
            }
            parameters.add(new ConfigParameter(ServerlessSupport.text(element, "parameterKey"), ServerlessSupport.text(element, "parameterValue")));
        }
        return parameters;
    }

    private PricePerformanceTarget parsePricePerformanceTarget(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw ServerlessSupport.validation("pricePerformanceTarget must be an object.");
        }
        return new PricePerformanceTarget(ServerlessSupport.text(node, "status"), ServerlessSupport.integer(node, "level"));
    }

    private Long parseLong(JsonNode request, String field) {
        JsonNode node = request.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw ServerlessSupport.validation(field + " must be an integer.");
        }
        return node.asLong();
    }

    private Map<String, String> parseTagList(JsonNode tagsNode, String field) {
        if (tagsNode == null || tagsNode.isMissingNode() || tagsNode.isNull()) {
            return null;
        }
        if (!tagsNode.isArray()) {
            throw ServerlessSupport.validation(field + " must be an array of tags.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        for (JsonNode tag : tagsNode) {
            if (!tag.isObject()) {
                throw ServerlessSupport.validation(field + " must contain only tag objects.");
            }
            String key = ServerlessSupport.text(tag, "key");
            String value = ServerlessSupport.text(tag, "value");
            if (key != null) {
                tags.put(key, value);
            }
        }
        return tags;
    }
}
