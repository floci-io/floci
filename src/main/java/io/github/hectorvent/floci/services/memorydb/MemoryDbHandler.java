package io.github.hectorvent.floci.services.memorydb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsJson11Controller;
import io.github.hectorvent.floci.services.memorydb.model.AuthMode;
import io.github.hectorvent.floci.services.memorydb.model.Cluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MemoryDB JSON 1.1 handler. Dispatched from {@link AwsJson11Controller} under the
 * {@code AmazonMemoryDB.} target prefix.
 */
@ApplicationScoped
public class MemoryDbHandler {

    private static final Logger LOG = Logger.getLogger(MemoryDbHandler.class);

    private final MemoryDbService service;
    private final ObjectMapper objectMapper;

    @Inject
    public MemoryDbHandler(MemoryDbService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("MemoryDB action: {0}", action);
        try {
            return switch (action) {
                case "CreateCluster" -> handleCreateCluster(request, region);
                case "DescribeClusters" -> handleDescribeClusters(request);
                case "UpdateCluster" -> handleUpdateCluster(request);
                case "DeleteCluster" -> handleDeleteCluster(request);
                case "ListTags" -> handleListTags(request);
                case "TagResource" -> handleTagResource(request);
                case "UntagResource" -> handleUntagResource(request);
                default -> Response.status(400)
                        .entity(new AwsErrorResponse("UnknownOperationException",
                                "Operation " + action + " is not supported."))
                        .build();
            };
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus())
                    .entity(new AwsErrorResponse(e.jsonType(), e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.errorv("MemoryDB error processing action {0}: {1}", action, e.getMessage());
            return Response.status(500)
                    .entity(new AwsErrorResponse("InternalFailure", e.getMessage()))
                    .build();
        }
    }

    private Response handleCreateCluster(JsonNode request, String region) {
        Cluster spec = new Cluster();
        spec.setName(text(request, "ClusterName"));
        spec.setDescription(text(request, "Description"));
        spec.setNodeType(text(request, "NodeType"));
        if (request.hasNonNull("NumShards")) {
            spec.setNumberOfShards(request.get("NumShards").asInt());
        }
        spec.setEngine(text(request, "Engine"));
        spec.setEngineVersion(text(request, "EngineVersion"));
        spec.setAclName(text(request, "ACLName"));
        spec.setTlsEnabled(request.path("TLSEnabled").asBoolean(false));
        spec.setAuthToken(text(request, "AuthToken"));
        spec.setAuthMode(resolveAuthMode(request));
        spec.setTags(parseTags(request.path("Tags")));
        Cluster created = service.createCluster(spec, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Cluster", clusterNode(created));
        return Response.ok(response).build();
    }

    private Response handleDescribeClusters(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Clusters");
        for (Cluster cluster : service.describeClusters(text(request, "ClusterName"))) {
            arr.add(clusterNode(cluster));
        }
        return Response.ok(response).build();
    }

    private Response handleUpdateCluster(JsonNode request) {
        Cluster updated = service.updateCluster(text(request, "ClusterName"), text(request, "Description"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Cluster", clusterNode(updated));
        return Response.ok(response).build();
    }

    private Response handleDeleteCluster(JsonNode request) {
        Cluster deleted = service.deleteCluster(text(request, "ClusterName"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Cluster", clusterNode(deleted));
        return Response.ok(response).build();
    }

    private Response handleListTags(JsonNode request) {
        Map<String, String> tags = service.listTags(text(request, "ResourceArn"));
        return Response.ok(tagListResponse(tags)).build();
    }

    private Response handleTagResource(JsonNode request) {
        Map<String, String> tags = service.tagResource(text(request, "ResourceArn"),
                parseTags(request.path("Tags")));
        return Response.ok(tagListResponse(tags)).build();
    }

    private Response handleUntagResource(JsonNode request) {
        List<String> keys = new java.util.ArrayList<>();
        request.path("TagKeys").forEach(k -> keys.add(k.asText()));
        Map<String, String> tags = service.untagResource(text(request, "ResourceArn"), keys);
        return Response.ok(tagListResponse(tags)).build();
    }

    // ──────────────────────────── Builders ────────────────────────────

    private ObjectNode clusterNode(Cluster cluster) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", cluster.getName());
        if (cluster.getDescription() != null) {
            node.put("Description", cluster.getDescription());
        }
        node.put("Status", cluster.getStatus().wireValue());
        node.put("NodeType", cluster.getNodeType());
        node.put("NumberOfShards", cluster.getNumberOfShards());
        node.put("Engine", cluster.getEngine());
        node.put("EngineVersion", cluster.getEngineVersion());
        node.put("ACLName", cluster.getAclName());
        node.put("TLSEnabled", cluster.isTlsEnabled());
        node.put("ARN", cluster.getArn());
        if (cluster.getClusterEndpoint() != null) {
            ObjectNode endpoint = node.putObject("ClusterEndpoint");
            endpoint.put("Address", cluster.getClusterEndpoint().address());
            endpoint.put("Port", cluster.getClusterEndpoint().port());
        }
        return node;
    }

    private ObjectNode tagListResponse(Map<String, String> tags) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("TagList");
        tags.forEach((k, v) -> {
            ObjectNode tag = objectMapper.createObjectNode();
            tag.put("Key", k);
            tag.put("Value", v);
            arr.add(tag);
        });
        return response;
    }

    // ──────────────────────────── Parsing ────────────────────────────

    private AuthMode resolveAuthMode(JsonNode request) {
        if (request.hasNonNull("AuthToken") && !request.get("AuthToken").asText().isBlank()) {
            return AuthMode.PASSWORD;
        }
        return AuthMode.NO_AUTH;
    }

    private Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                String key = tag.path("Key").asText(null);
                if (key != null) {
                    tags.put(key, tag.path("Value").asText(null));
                }
            }
        }
        return tags;
    }

    private String text(JsonNode request, String field) {
        JsonNode node = request.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText(null);
    }
}
