package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsJson11Controller;
import io.github.hectorvent.floci.services.redshiftserverless.model.RedshiftServerlessNamespace;
import io.github.hectorvent.floci.services.redshiftserverless.model.RedshiftServerlessSnapshot;
import io.github.hectorvent.floci.services.redshiftserverless.model.RedshiftServerlessWorkgroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redshift Serverless JSON 1.1 handler. Dispatched from {@link AwsJson11Controller} under the
 * {@code RedshiftServerless.} target prefix (verified against
 * {@code aws-sdk-go-v2/service/redshiftserverless} {@code serializers.go}, which sets
 * {@code X-Amz-Target: RedshiftServerless.<Operation>} and signs as {@code redshift-serverless}).
 * <p>
 * Scoped to the call surface SPE-71256's serverless switchover controller and its Crossplane
 * chart actually touch: namespace/workgroup CRUD, snapshot listing/creation, and
 * {@code RestoreFromSnapshot} — not the full ~50-operation API.
 */
@ApplicationScoped
public class RedshiftServerlessJsonHandler {

    private static final Logger LOG = Logger.getLogger(RedshiftServerlessJsonHandler.class);

    private final RedshiftServerlessService service;
    private final ObjectMapper objectMapper;

    @Inject
    public RedshiftServerlessJsonHandler(RedshiftServerlessService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("RedshiftServerless action: {0}", action);
        try {
            return switch (action) {
                case "CreateNamespace" -> handleCreateNamespace(request, region);
                case "GetNamespace" -> handleGetNamespace(request, region);
                case "UpdateNamespace" -> handleUpdateNamespace(request, region);
                case "DeleteNamespace" -> handleDeleteNamespace(request, region);
                case "ListNamespaces" -> handleListNamespaces(region);
                case "CreateWorkgroup" -> handleCreateWorkgroup(request, region);
                case "GetWorkgroup" -> handleGetWorkgroup(request, region);
                case "UpdateWorkgroup" -> handleUpdateWorkgroup(request, region);
                case "DeleteWorkgroup" -> handleDeleteWorkgroup(request, region);
                case "ListWorkgroups" -> handleListWorkgroups(region);
                case "CreateSnapshot" -> handleCreateSnapshot(request, region);
                case "GetSnapshot" -> handleGetSnapshot(request, region);
                case "DeleteSnapshot" -> handleDeleteSnapshot(request, region);
                case "ListSnapshots" -> handleListSnapshots(request, region);
                case "RestoreFromSnapshot" -> handleRestoreFromSnapshot(request, region);
                case "TagResource" -> handleTagResource(request);
                case "UntagResource" -> handleUntagResource(request);
                case "ListTagsForResource" -> handleListTagsForResource(request);
                default -> Response.status(400)
                        .entity(new AwsErrorResponse("InvalidRequestException",
                                "Operation " + action + " is not supported."))
                        .build();
            };
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus())
                    .entity(new AwsErrorResponse(e.jsonType(), e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.errorv(e, "RedshiftServerless error processing action {0}", action);
            return Response.status(500)
                    .entity(new AwsErrorResponse("InternalServerException", e.getMessage()))
                    .build();
        }
    }

    // ──────────────────────────── Namespaces ────────────────────────────

    private Response handleCreateNamespace(JsonNode request, String region) {
        RedshiftServerlessNamespace namespace = new RedshiftServerlessNamespace();
        namespace.setNamespaceName(text(request, "namespaceName"));
        namespace.setAdminUsername(text(request, "adminUsername"));
        namespace.setDbName(text(request, "dbName"));
        namespace.setDefaultIamRoleArn(text(request, "defaultIamRoleArn"));
        namespace.setIamRoles(stringList(request.path("iamRoles")));
        namespace.setKmsKeyId(text(request, "kmsKeyId"));
        namespace.setLogExports(stringList(request.path("logExports")));

        RedshiftServerlessNamespace created = service.createNamespace(namespace, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("namespace", namespaceNode(created));
        return Response.ok(response).build();
    }

    private Response handleGetNamespace(JsonNode request, String region) {
        RedshiftServerlessNamespace namespace = service.getNamespace(text(request, "namespaceName"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("namespace", namespaceNode(namespace));
        return Response.ok(response).build();
    }

    private Response handleUpdateNamespace(JsonNode request, String region) {
        RedshiftServerlessNamespace patch = new RedshiftServerlessNamespace();
        patch.setAdminUsername(text(request, "adminUsername"));
        patch.setDbName(text(request, "dbName"));
        patch.setDefaultIamRoleArn(text(request, "defaultIamRoleArn"));
        patch.setIamRoles(stringList(request.path("iamRoles")));
        patch.setKmsKeyId(text(request, "kmsKeyId"));
        patch.setLogExports(stringList(request.path("logExports")));

        RedshiftServerlessNamespace updated =
                service.updateNamespace(text(request, "namespaceName"), region, patch);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("namespace", namespaceNode(updated));
        return Response.ok(response).build();
    }

    private Response handleDeleteNamespace(JsonNode request, String region) {
        String namespaceName = text(request, "namespaceName");
        RedshiftServerlessNamespace namespace = service.getNamespace(namespaceName, region);
        service.deleteNamespace(namespaceName, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("namespace", namespaceNode(namespace));
        return Response.ok(response).build();
    }

    private Response handleListNamespaces(String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("namespaces");
        for (RedshiftServerlessNamespace n : service.listNamespaces(region)) {
            arr.add(namespaceNode(n));
        }
        return Response.ok(response).build();
    }

    // ──────────────────────────── Workgroups ────────────────────────────

    private Response handleCreateWorkgroup(JsonNode request, String region) {
        RedshiftServerlessWorkgroup workgroup = new RedshiftServerlessWorkgroup();
        workgroup.setWorkgroupName(text(request, "workgroupName"));
        workgroup.setNamespaceName(text(request, "namespaceName"));
        workgroup.setBaseCapacity(intOrNull(request, "baseCapacity"));
        workgroup.setMaxCapacity(intOrNull(request, "maxCapacity"));
        if (request.has("enhancedVpcRouting")) {
            workgroup.setEnhancedVpcRouting(request.path("enhancedVpcRouting").asBoolean());
        }
        if (request.has("publiclyAccessible")) {
            workgroup.setPubliclyAccessible(request.path("publiclyAccessible").asBoolean());
        }
        workgroup.setSecurityGroupIds(stringList(request.path("securityGroupIds")));
        workgroup.setSubnetIds(stringList(request.path("subnetIds")));
        workgroup.setPort(intOrNull(request, "port"));

        RedshiftServerlessWorkgroup created = service.createWorkgroup(workgroup, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("workgroup", workgroupNode(created));
        return Response.ok(response).build();
    }

    private Response handleGetWorkgroup(JsonNode request, String region) {
        RedshiftServerlessWorkgroup workgroup = service.getWorkgroup(text(request, "workgroupName"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("workgroup", workgroupNode(workgroup));
        return Response.ok(response).build();
    }

    private Response handleUpdateWorkgroup(JsonNode request, String region) {
        RedshiftServerlessWorkgroup patch = new RedshiftServerlessWorkgroup();
        patch.setBaseCapacity(intOrNull(request, "baseCapacity"));
        patch.setMaxCapacity(intOrNull(request, "maxCapacity"));
        if (request.has("enhancedVpcRouting")) {
            patch.setEnhancedVpcRouting(request.path("enhancedVpcRouting").asBoolean());
        }
        if (request.has("publiclyAccessible")) {
            patch.setPubliclyAccessible(request.path("publiclyAccessible").asBoolean());
        }
        patch.setSecurityGroupIds(stringList(request.path("securityGroupIds")));
        patch.setSubnetIds(stringList(request.path("subnetIds")));
        patch.setPort(intOrNull(request, "port"));

        RedshiftServerlessWorkgroup updated =
                service.updateWorkgroup(text(request, "workgroupName"), region, patch);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("workgroup", workgroupNode(updated));
        return Response.ok(response).build();
    }

    private Response handleDeleteWorkgroup(JsonNode request, String region) {
        String workgroupName = text(request, "workgroupName");
        RedshiftServerlessWorkgroup workgroup = service.getWorkgroup(workgroupName, region);
        service.deleteWorkgroup(workgroupName, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("workgroup", workgroupNode(workgroup));
        return Response.ok(response).build();
    }

    private Response handleListWorkgroups(String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("workgroups");
        for (RedshiftServerlessWorkgroup w : service.listWorkgroups(region)) {
            arr.add(workgroupNode(w));
        }
        return Response.ok(response).build();
    }

    // ──────────────────────────── Snapshots ────────────────────────────

    private Response handleCreateSnapshot(JsonNode request, String region) {
        RedshiftServerlessSnapshot snapshot = service.createSnapshot(
                text(request, "snapshotName"), text(request, "namespaceName"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("snapshot", snapshotNode(snapshot));
        return Response.ok(response).build();
    }

    private Response handleGetSnapshot(JsonNode request, String region) {
        String snapshotName = text(request, "snapshotName");
        if (snapshotName == null) {
            // Real GetSnapshot also accepts snapshotArn; Floci's ARNs embed the name as the
            // trailing resource segment, so recover it from there rather than tracking a second
            // ARN->name index.
            String arn = text(request, "snapshotArn");
            snapshotName = arn != null && arn.contains("/") ? arn.substring(arn.lastIndexOf('/') + 1) : null;
        }
        RedshiftServerlessSnapshot snapshot = service.getSnapshot(snapshotName, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("snapshot", snapshotNode(snapshot));
        return Response.ok(response).build();
    }

    private Response handleDeleteSnapshot(JsonNode request, String region) {
        String snapshotName = text(request, "snapshotName");
        RedshiftServerlessSnapshot snapshot = service.getSnapshot(snapshotName, region);
        service.deleteSnapshot(snapshotName, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("snapshot", snapshotNode(snapshot));
        return Response.ok(response).build();
    }

    private Response handleListSnapshots(JsonNode request, String region) {
        String namespaceName = text(request, "namespaceName");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("snapshots");
        for (RedshiftServerlessSnapshot s : service.listSnapshots(region, namespaceName)) {
            arr.add(snapshotNode(s));
        }
        return Response.ok(response).build();
    }

    // ──────────────────────────── Restore ────────────────────────────

    private Response handleRestoreFromSnapshot(JsonNode request, String region) {
        RedshiftServerlessNamespace namespace = service.restoreFromSnapshot(
                text(request, "namespaceName"), text(request, "workgroupName"),
                text(request, "snapshotName"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("namespace", namespaceNode(namespace));
        response.put("ownerAccount", namespace.getAccountId());
        response.put("snapshotName", text(request, "snapshotName"));
        return Response.ok(response).build();
    }

    // ──────────────────────────── Tags ────────────────────────────

    private Response handleTagResource(JsonNode request) {
        service.tagResource(text(request, "resourceArn"), parseTags(request.path("tags")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request) {
        service.untagResource(text(request, "resourceArn"), stringList(request.path("tagKeys")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListTagsForResource(JsonNode request) {
        Map<String, String> tags = service.listTags(text(request, "resourceArn"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("tags");
        tags.forEach((k, v) -> {
            ObjectNode tag = arr.addObject();
            tag.put("key", k);
            tag.put("value", v);
        });
        return Response.ok(response).build();
    }

    // ──────────────────────────── Node builders ────────────────────────────

    private ObjectNode namespaceNode(RedshiftServerlessNamespace n) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("namespaceId", n.getNamespaceId());
        node.put("namespaceName", n.getNamespaceName());
        node.put("namespaceArn", n.getNamespaceArn());
        copyText(n.getAdminUsername(), node, "adminUsername");
        copyText(n.getDbName(), node, "dbName");
        copyText(n.getDefaultIamRoleArn(), node, "defaultIamRoleArn");
        copyText(n.getKmsKeyId(), node, "kmsKeyId");
        putArray(node, "iamRoles", n.getIamRoles());
        putLogExports(node, n.getLogExports());
        node.put("status", n.getStatus());
        putEpoch(node, "creationDate", n.getCreationDate());
        return node;
    }

    private ObjectNode workgroupNode(RedshiftServerlessWorkgroup w) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("workgroupId", w.getWorkgroupId());
        node.put("workgroupName", w.getWorkgroupName());
        node.put("workgroupArn", w.getWorkgroupArn());
        node.put("namespaceName", w.getNamespaceName());
        if (w.getBaseCapacity() != null) {
            node.put("baseCapacity", w.getBaseCapacity());
        }
        if (w.getMaxCapacity() != null) {
            node.put("maxCapacity", w.getMaxCapacity());
        }
        node.put("enhancedVpcRouting", Boolean.TRUE.equals(w.getEnhancedVpcRouting()));
        node.put("publiclyAccessible", Boolean.TRUE.equals(w.getPubliclyAccessible()));
        putArray(node, "securityGroupIds", w.getSecurityGroupIds());
        putArray(node, "subnetIds", w.getSubnetIds());
        if (w.getPort() != null) {
            node.put("port", w.getPort());
        }
        node.put("status", w.getStatus());
        if (w.getEndpointAddress() != null) {
            ObjectNode endpoint = node.putObject("endpoint");
            endpoint.put("address", w.getEndpointAddress());
            endpoint.put("port", w.getEndpointPort() != null ? w.getEndpointPort() : 5439);
            endpoint.putArray("vpcEndpoints");
        }
        putEpoch(node, "creationDate", w.getCreationDate());
        return node;
    }

    private ObjectNode snapshotNode(RedshiftServerlessSnapshot s) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("snapshotName", s.getSnapshotName());
        node.put("snapshotArn", s.getSnapshotArn());
        node.put("namespaceName", s.getNamespaceName());
        node.put("namespaceArn", s.getNamespaceArn());
        node.put("ownerAccount", s.getOwnerAccount());
        node.put("status", s.getStatus());
        putEpoch(node, "snapshotCreateTime", s.getSnapshotCreateTime());
        return node;
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private void putLogExports(ObjectNode node, List<String> logExports) {
        ArrayNode arr = node.putArray("logExports");
        logExports.forEach(arr::add);
    }

    private void putArray(ObjectNode node, String field, List<String> values) {
        ArrayNode arr = node.putArray(field);
        values.forEach(arr::add);
    }

    private void copyText(String value, ObjectNode node, String field) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private void putEpoch(ObjectNode node, String field, Instant instant) {
        if (instant != null) {
            node.put(field, instant.getEpochSecond());
        }
    }

    private Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                String key = tag.path("key").asText(null);
                if (key != null) {
                    tags.put(key, tag.path("value").asText(null));
                }
            }
        } else if (tagsNode.isObject()) {
            tagsNode.fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText(null)));
        }
        return tags;
    }

    private List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(v -> values.add(v.asText()));
        }
        return values;
    }

    private String text(JsonNode request, String field) {
        JsonNode node = request.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText(null);
    }

    private Integer intOrNull(JsonNode request, String field) {
        JsonNode node = request.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asInt();
    }
}
