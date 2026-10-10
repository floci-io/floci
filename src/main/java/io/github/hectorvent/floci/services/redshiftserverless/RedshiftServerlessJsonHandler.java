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
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import io.github.hectorvent.floci.services.redshiftserverless.model.PricePerformanceTarget;
import io.github.hectorvent.floci.services.redshiftserverless.model.RedshiftServerlessSnapshot;
import io.github.hectorvent.floci.services.redshiftserverless.model.Workgroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redshift Serverless JSON 1.1 handler. Dispatched from
 * {@link io.github.hectorvent.floci.core.common.AwsJson11Controller}
 * under the {@code RedshiftServerless.} target prefix.
 */
@ApplicationScoped
public class RedshiftServerlessJsonHandler {

    private static final Logger LOG = Logger.getLogger(RedshiftServerlessJsonHandler.class);

    /**
     * {@code Namespace.creationDate} carries {@code TimestampFormatTrait(ISO_8601)}, which
     * overrides the epoch-seconds default that awsJson1.1 would otherwise apply. Do not
     * generalise: roughly half the timestamp members in this model carry no format trait and
     * use the epoch default, so check each member's trait before emitting it. Emitting a number here is accepted by the CLI, because
     * botocore coerces it, but strict SDKs reject the response outright.
     */
    private static final DateTimeFormatter CREATION_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final RedshiftServerlessService service;
    private final ObjectMapper objectMapper;
    private final RedshiftIamDbUserResolver iamDbUserResolver;

    @Inject
    public RedshiftServerlessJsonHandler(RedshiftServerlessService service, ObjectMapper objectMapper,
                                         RedshiftIamDbUserResolver iamDbUserResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.iamDbUserResolver = iamDbUserResolver;
    }

    public Response handle(String action, JsonNode request, String region) {
        return handle(action, request, region, null);
    }

    public Response handle(String action, JsonNode request, String region, String authorizationHeader) {
        LOG.debugv("Redshift Serverless action: {0}", action);
        try {
            return switch (action) {
                case "CreateNamespace" -> handleCreateNamespace(request, region);
                case "GetNamespace" -> handleGetNamespace(request, region);
                case "ListNamespaces" -> handleListNamespaces(request, region);
                case "UpdateNamespace" -> handleUpdateNamespace(request, region);
                case "DeleteNamespace" -> handleDeleteNamespace(request, region);
                case "CreateWorkgroup" -> handleCreateWorkgroup(request, region);
                case "GetWorkgroup" -> handleGetWorkgroup(request, region);
                case "ListWorkgroups" -> handleListWorkgroups(request, region);
                case "UpdateWorkgroup" -> handleUpdateWorkgroup(request, region);
                case "DeleteWorkgroup" -> handleDeleteWorkgroup(request, region);
                case "CreateSnapshot" -> handleCreateSnapshot(request, region);
                case "GetSnapshot" -> handleGetSnapshot(request, region);
                case "ListSnapshots" -> handleListSnapshots(request, region);
                case "DeleteSnapshot" -> handleDeleteSnapshot(request, region);
                case "RestoreFromSnapshot" -> handleRestoreFromSnapshot(request, region);
                case "GetCredentials" -> handleGetCredentials(request, region, authorizationHeader);
                case "ListTagsForResource" -> handleListTagsForResource(request, region);
                case "TagResource" -> handleTagResource(request, region);
                case "UntagResource" -> handleUntagResource(request, region);
                default -> Response.status(400)
                        .entity(new AwsErrorResponse("UnknownOperationException",
                                "Operation " + action + " is not supported."))
                        .build();
            };
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        } catch (Exception e) {
            LOG.errorf(e, "Redshift Serverless error processing action %s", action);
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    private Response handleCreateNamespace(JsonNode request, String region) {
        Namespace namespace = service.createNamespace(
                text(request, "namespaceName"),
                text(request, "adminUsername"),
                text(request, "adminUserPassword"),
                text(request, "dbName"),
                text(request, "kmsKeyId"),
                text(request, "defaultIamRoleArn"),
                parseStringList(request.path("iamRoles"), "iamRoles"),
                parseStringList(request.path("logExports"), "logExports"),
                parseTagList(request.path("tags"), "tags"),
                region);
        return namespaceResponse(namespace);
    }

    private Response handleGetNamespace(JsonNode request, String region) {
        return namespaceResponse(service.getNamespace(text(request, "namespaceName"), region));
    }

    private Response handleListNamespaces(JsonNode request, String region) {
        PaginatedResult<Namespace> page = service.listNamespaces(
                region, parseMaxResults(request), text(request, "nextToken"));
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
                text(request, "namespaceName"),
                text(request, "adminUsername"),
                text(request, "adminUserPassword"),
                text(request, "kmsKeyId"),
                text(request, "defaultIamRoleArn"),
                parseStringList(request.path("iamRoles"), "iamRoles"),
                parseStringList(request.path("logExports"), "logExports"),
                region);
        return namespaceResponse(namespace);
    }

    private Response handleDeleteNamespace(JsonNode request, String region) {
        return namespaceResponse(service.deleteNamespace(text(request, "namespaceName"), region));
    }

    private Response handleCreateWorkgroup(JsonNode request, String region) {
        Workgroup workgroup = service.createWorkgroup(
                text(request, "workgroupName"),
                text(request, "namespaceName"),
                parseWorkgroupSettings(request),
                parseTagList(request.path("tags"), "tags"),
                region);
        return workgroupResponse(workgroup);
    }

    private Response handleGetWorkgroup(JsonNode request, String region) {
        return workgroupResponse(service.getWorkgroup(text(request, "workgroupName"), region));
    }

    private Response handleListWorkgroups(JsonNode request, String region) {
        PaginatedResult<Workgroup> page = service.listWorkgroups(
                region, parseMaxResults(request), text(request, "nextToken"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("workgroups");
        page.items().forEach(workgroup -> items.add(workgroupNode(workgroup)));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response handleUpdateWorkgroup(JsonNode request, String region) {
        return workgroupResponse(service.updateWorkgroup(
                text(request, "workgroupName"), parseWorkgroupSettings(request), region));
    }

    private Response handleDeleteWorkgroup(JsonNode request, String region) {
        return workgroupResponse(service.deleteWorkgroup(text(request, "workgroupName"), region));
    }

    /**
     * {@code expiration} and {@code nextRefreshTime} carry no timestamp format trait in the API
     * model, so they use the awsJson1.1 default of epoch seconds as a JSON number. Floci never
     * refreshes an authorization early, so the refresh time is the expiry.
     */
    private Response handleGetCredentials(JsonNode request, String region, String authorizationHeader) {
        if (request.hasNonNull("customDomainName")) {
            throw validation("customDomainName is not supported; use workgroupName.");
        }
        TempCredential credential = service.getCredentials(
                text(request, "workgroupName"),
                text(request, "dbName"),
                parseInteger(request, "durationSeconds"),
                iamDbUserResolver.resolveDbUser(authorizationHeader),
                region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("dbUser", credential.dbUser());
        response.put("dbPassword", credential.password());
        response.put("expiration", credential.expiresAt().getEpochSecond());
        response.put("nextRefreshTime", credential.expiresAt().getEpochSecond());
        return Response.ok(response).build();
    }

    private Response handleCreateSnapshot(JsonNode request, String region) {
        return snapshotResponse(service.createSnapshot(
                text(request, "snapshotName"), text(request, "namespaceName"),
                parseInteger(request, "retentionPeriod"), parseTagList(request.path("tags"), "tags"), region));
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
                text(request, "namespaceName"), text(request, "namespaceArn"), text(request, "ownerAccount"),
                parseEpochSeconds(request, "startTime"), parseEpochSeconds(request, "endTime"), region,
                parseMaxResults(request), text(request, "nextToken"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("snapshots");
        page.items().forEach(snapshot -> items.add(snapshotNode(snapshot)));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response handleDeleteSnapshot(JsonNode request, String region) {
        return snapshotResponse(service.deleteSnapshot(text(request, "snapshotName"), region));
    }

    private Response handleRestoreFromSnapshot(JsonNode request, String region) {
        RedshiftServerlessService.RestoreResult result = service.restoreFromSnapshot(
                text(request, "namespaceName"), text(request, "workgroupName"),
                snapshotNameOf(request, region), text(request, "ownerAccount"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("namespace", namespaceNode(result.namespace()));
        response.put("ownerAccount", result.snapshot().getOwnerAccount());
        response.put("snapshotName", result.snapshot().getSnapshotName());
        return Response.ok(response).build();
    }

    /** The snapshot name from {@code snapshotName}, or recovered from {@code snapshotArn} when absent. */
    private String snapshotNameOf(JsonNode request, String region) {
        String snapshotName = text(request, "snapshotName");
        String snapshotArn = text(request, "snapshotArn");
        if (snapshotName != null && snapshotArn != null) {
            throw validation("snapshotName and snapshotArn cannot both be specified.");
        }
        return snapshotArn == null ? snapshotName : service.snapshotNameFromArn(snapshotArn, region);
    }

    private Response handleListTagsForResource(JsonNode request, String region) {
        Map<String, String> tags = service.listTagsForResource(text(request, "resourceArn"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("tags", tagListNode(tags));
        return Response.ok(response).build();
    }

    private Response handleTagResource(JsonNode request, String region) {
        service.tagResource(text(request, "resourceArn"), parseTagList(request.path("tags"), "tags"), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request, String region) {
        service.untagResource(text(request, "resourceArn"), parseStringList(request.path("tagKeys"), "tagKeys"), region);
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
            node.put("creationDate", CREATION_DATE_FORMAT.format(namespace.getCreationDate()));
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
            node.put("snapshotCreateTime", CREATION_DATE_FORMAT.format(snapshot.getSnapshotCreateTime()));
        }
        return node;
    }

    private Response workgroupResponse(Workgroup workgroup) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("workgroup", workgroupNode(workgroup));
        return Response.ok(response).build();
    }

    /**
     * Members the emulator does not model ({@code workgroupVersion}, {@code patchVersion},
     * {@code crossAccountVpcs}, the custom domain members and {@code endpoint.vpcEndpoints}) are
     * omitted rather than invented; all of them are optional in the API model.
     */
    private ObjectNode workgroupNode(Workgroup workgroup) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("workgroupName", workgroup.getWorkgroupName());
        node.put("workgroupId", workgroup.getWorkgroupId());
        node.put("workgroupArn", workgroup.getWorkgroupArn());
        node.put("namespaceName", workgroup.getNamespaceName());
        node.put("status", workgroup.getStatus());
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
            node.put("creationDate", CREATION_DATE_FORMAT.format(workgroup.getCreationDate()));
        }
        return node;
    }

    private WorkgroupSettings parseWorkgroupSettings(JsonNode request) {
        return new WorkgroupSettings(
                parseInteger(request, "baseCapacity"),
                parseInteger(request, "maxCapacity"),
                parseBoolean(request, "enhancedVpcRouting"),
                parseBoolean(request, "publiclyAccessible"),
                parseBoolean(request, "extraComputeForAutomaticOptimization"),
                parseConfigParameters(request.path("configParameters")),
                parseStringList(request.path("securityGroupIds"), "securityGroupIds"),
                parseStringList(request.path("subnetIds"), "subnetIds"),
                parseInteger(request, "port"),
                parsePricePerformanceTarget(request.path("pricePerformanceTarget")),
                text(request, "ipAddressType"),
                text(request, "trackName"));
    }

    private Integer parseInteger(JsonNode request, String field) {
        JsonNode node = request.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw validation(field + " must be an integer.");
        }
        return node.asInt();
    }

    private Boolean parseBoolean(JsonNode request, String field) {
        JsonNode node = request.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isBoolean()) {
            throw validation(field + " must be a boolean.");
        }
        return node.asBoolean();
    }

    private List<ConfigParameter> parseConfigParameters(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw validation("configParameters must be an array of parameters.");
        }
        List<ConfigParameter> parameters = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isObject()) {
                throw validation("configParameters must contain only parameter objects.");
            }
            parameters.add(new ConfigParameter(text(element, "parameterKey"), text(element, "parameterValue")));
        }
        return parameters;
    }

    private PricePerformanceTarget parsePricePerformanceTarget(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw validation("pricePerformanceTarget must be an object.");
        }
        return new PricePerformanceTarget(text(node, "status"), parseInteger(node, "level"));
    }

    /** A timestamp request member, which awsJson1.1 sends as epoch seconds unless the model says otherwise. */
    private Instant parseEpochSeconds(JsonNode request, String field) {
        JsonNode node = request.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw validation(field + " must be a timestamp.");
        }
        return Instant.ofEpochMilli(Math.round(node.asDouble() * 1000));
    }

    private Integer parseMaxResults(JsonNode request) {
        JsonNode node = request.path("maxResults");
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw validation("maxResults must be an integer.");
        }
        return node.asInt();
    }

    /**
     * Absent means absent and wrong means wrong: only a missing or null member returns null, so
     * that callers can distinguish "omitted, keep the stored value" from "supplied". Treating a
     * present member of the wrong type as absent would let a malformed UpdateNamespace silently
     * keep the old roles instead of reporting the request as invalid.
     */
    private List<String> parseStringList(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw validation(field + " must be an array of strings.");
        }
        List<String> list = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isTextual()) {
                throw validation(field + " must contain only strings.");
            }
            list.add(element.textValue());
        }
        return list;
    }

    private Map<String, String> parseTagList(JsonNode tagsNode, String field) {
        if (tagsNode == null || tagsNode.isMissingNode() || tagsNode.isNull()) {
            return null;
        }
        if (!tagsNode.isArray()) {
            throw validation(field + " must be an array of tags.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        for (JsonNode tag : tagsNode) {
            if (!tag.isObject()) {
                throw validation(field + " must contain only tag objects.");
            }
            String key = tag.path("key").asText(null);
            if (key != null) {
                tags.put(key, tag.path("value").asText(null));
            }
        }
        return tags;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static String text(JsonNode request, String field) {
        JsonNode node = request == null ? null : request.get(field);
        return node != null && node.isTextual() ? node.textValue() : null;
    }
}
