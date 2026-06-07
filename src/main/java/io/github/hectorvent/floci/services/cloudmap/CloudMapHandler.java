package io.github.hectorvent.floci.services.cloudmap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsJson11Controller;
import io.github.hectorvent.floci.services.cloudmap.model.Instance;
import io.github.hectorvent.floci.services.cloudmap.model.Namespace;
import io.github.hectorvent.floci.services.cloudmap.model.Operation;
import io.github.hectorvent.floci.services.cloudmap.model.Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cloud Map (servicediscovery) JSON 1.1 handler. Dispatched from
 * {@link AwsJson11Controller} under the {@code Route53AutoNaming_v20170314.} target prefix.
 */
@ApplicationScoped
public class CloudMapHandler {

    private static final Logger LOG = Logger.getLogger(CloudMapHandler.class);

    private final CloudMapService service;
    private final ObjectMapper objectMapper;

    @Inject
    public CloudMapHandler(CloudMapService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("CloudMap action: {0}", action);
        try {
            return switch (action) {
                case "CreateHttpNamespace" -> opResponse(service.createHttpNamespace(
                        text(request, "Name"), text(request, "CreatorRequestId"),
                        text(request, "Description"), parseTags(request), region));
                case "CreatePublicDnsNamespace" -> opResponse(service.createPublicDnsNamespace(
                        text(request, "Name"), text(request, "CreatorRequestId"),
                        text(request, "Description"), parseTags(request), region));
                case "CreatePrivateDnsNamespace" -> opResponse(service.createPrivateDnsNamespace(
                        text(request, "Name"), text(request, "Vpc"), text(request, "CreatorRequestId"),
                        text(request, "Description"), parseTags(request), region));
                case "GetNamespace" -> handleGetNamespace(request);
                case "ListNamespaces" -> handleListNamespaces(region);
                case "DeleteNamespace" -> opResponse(service.deleteNamespace(text(request, "Id"), region));
                case "GetOperation" -> handleGetOperation(request);
                case "ListOperations" -> handleListOperations(request, region);
                case "CreateService" -> handleCreateService(request, region);
                case "GetService" -> handleGetService(request);
                case "ListServices" -> handleListServices(request, region);
                case "DeleteService" -> handleDeleteService(request);
                case "RegisterInstance" -> opResponse(service.registerInstance(
                        text(request, "ServiceId"), text(request, "InstanceId"),
                        text(request, "CreatorRequestId"), parseAttributes(request.path("Attributes")), region));
                case "DeregisterInstance" -> opResponse(service.deregisterInstance(
                        text(request, "ServiceId"), text(request, "InstanceId"), region));
                case "GetInstance" -> handleGetInstance(request);
                case "ListInstances" -> handleListInstances(request);
                case "GetInstancesHealthStatus" -> handleGetInstancesHealthStatus(request);
                case "DiscoverInstances" -> handleDiscoverInstances(request, region);
                case "DiscoverInstancesRevision" -> handleDiscoverInstancesRevision(request, region);
                case "TagResource" -> handleTagResource(request);
                case "UntagResource" -> handleUntagResource(request);
                case "ListTagsForResource" -> handleListTagsForResource(request);
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
            LOG.errorv("CloudMap error processing action {0}: {1}", action, e.getMessage());
            return Response.status(500)
                    .entity(new AwsErrorResponse("InternalFailure", e.getMessage()))
                    .build();
        }
    }

    // ──────────────────────────── Namespaces ────────────────────────────

    private Response handleGetNamespace(JsonNode request) {
        Namespace ns = service.getNamespace(text(request, "Id"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Namespace", buildNamespaceNode(ns));
        return Response.ok(response).build();
    }

    private Response handleListNamespaces(String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Namespaces");
        for (Namespace ns : service.listNamespaces(region)) {
            arr.add(buildNamespaceNode(ns));
        }
        return Response.ok(response).build();
    }

    // ──────────────────────────── Operations ────────────────────────────

    private Response handleGetOperation(JsonNode request) {
        Operation op = service.getOperation(text(request, "OperationId"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Operation", buildOperationNode(op));
        return Response.ok(response).build();
    }

    private Response handleListOperations(JsonNode request, String region) {
        Map<String, List<String>> filters = parseFilters(request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Operations");
        for (Operation op : service.listOperations(region, filters)) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("Id", op.getId());
            node.put("Status", op.getStatus());
            arr.add(node);
        }
        return Response.ok(response).build();
    }

    // ──────────────────────────── Services ────────────────────────────

    private Response handleCreateService(JsonNode request, String region) {
        Service created = service.createService(
                text(request, "Name"), text(request, "NamespaceId"), text(request, "CreatorRequestId"),
                text(request, "Description"), rawJson(request.path("DnsConfig")),
                rawJson(request.path("HealthCheckConfig")), rawJson(request.path("HealthCheckCustomConfig")),
                text(request, "Type"), parseTags(request), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Service", buildServiceNode(created));
        return Response.ok(response).build();
    }

    private Response handleGetService(JsonNode request) {
        Service s = service.getService(text(request, "Id"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Service", buildServiceNode(s));
        return Response.ok(response).build();
    }

    private Response handleListServices(JsonNode request, String region) {
        Map<String, List<String>> filters = parseFilters(request);
        String namespaceId = filters.containsKey("NAMESPACE_ID") && !filters.get("NAMESPACE_ID").isEmpty()
                ? filters.get("NAMESPACE_ID").get(0) : null;
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Services");
        for (Service s : service.listServices(region, namespaceId)) {
            arr.add(buildServiceSummaryNode(s));
        }
        return Response.ok(response).build();
    }

    private Response handleDeleteService(JsonNode request) {
        service.deleteService(text(request, "Id"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    // ──────────────────────────── Instances ────────────────────────────

    private Response handleGetInstance(JsonNode request) {
        Instance instance = service.getInstance(text(request, "ServiceId"), text(request, "InstanceId"));
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode node = response.putObject("Instance");
        node.put("Id", instance.getInstanceId());
        if (instance.getCreatorRequestId() != null) {
            node.put("CreatorRequestId", instance.getCreatorRequestId());
        }
        node.set("Attributes", attributesNode(instance.getAttributes()));
        return Response.ok(response).build();
    }

    private Response handleListInstances(JsonNode request) {
        List<Instance> instances = service.listInstances(text(request, "ServiceId"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Instances");
        for (Instance i : instances) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("Id", i.getInstanceId());
            node.set("Attributes", attributesNode(i.getAttributes()));
            arr.add(node);
        }
        return Response.ok(response).build();
    }

    private Response handleGetInstancesHealthStatus(JsonNode request) {
        List<String> ids = new ArrayList<>();
        request.path("Instances").forEach(n -> ids.add(n.asText()));
        Map<String, String> status = service.getInstancesHealthStatus(text(request, "ServiceId"), ids);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode statusNode = response.putObject("Status");
        status.forEach(statusNode::put);
        return Response.ok(response).build();
    }

    // ──────────────────────────── Discovery ────────────────────────────

    private Response handleDiscoverInstances(JsonNode request, String region) {
        Integer maxResults = request.has("MaxResults") ? request.path("MaxResults").asInt() : null;
        CloudMapService.DiscoverResult result = service.discoverInstances(
                text(request, "NamespaceName"), text(request, "ServiceName"),
                text(request, "HealthStatus"), parseAttributes(request.path("QueryParameters")),
                maxResults, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Instances");
        for (Instance i : result.instances()) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("InstanceId", i.getInstanceId());
            node.put("NamespaceName", text(request, "NamespaceName"));
            node.put("ServiceName", text(request, "ServiceName"));
            node.put("HealthStatus", i.getHealthStatus());
            node.set("Attributes", attributesNode(i.getAttributes()));
            arr.add(node);
        }
        response.put("InstancesRevision", result.revision());
        return Response.ok(response).build();
    }

    private Response handleDiscoverInstancesRevision(JsonNode request, String region) {
        long revision = service.discoverInstancesRevision(
                text(request, "NamespaceName"), text(request, "ServiceName"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("InstancesRevision", revision);
        return Response.ok(response).build();
    }

    // ──────────────────────────── Tags ────────────────────────────

    private Response handleTagResource(JsonNode request) {
        service.tagResource(text(request, "ResourceARN"), parseTags(request));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request) {
        List<String> keys = new ArrayList<>();
        request.path("TagKeys").forEach(k -> keys.add(k.asText()));
        service.untagResource(text(request, "ResourceARN"), keys);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListTagsForResource(JsonNode request) {
        Map<String, String> tags = service.listTagsForResource(text(request, "ResourceARN"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Tags");
        tags.forEach((k, v) -> {
            ObjectNode tagNode = objectMapper.createObjectNode();
            tagNode.put("Key", k);
            tagNode.put("Value", v);
            arr.add(tagNode);
        });
        return Response.ok(response).build();
    }

    // ──────────────────────────── Builders ────────────────────────────

    private Response opResponse(Operation op) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("OperationId", op.getId());
        return Response.ok(response).build();
    }

    private ObjectNode buildNamespaceNode(Namespace ns) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", ns.getId());
        node.put("Arn", ns.getArn());
        node.put("Name", ns.getName());
        node.put("Type", ns.getType());
        if (ns.getDescription() != null) {
            node.put("Description", ns.getDescription());
        }
        node.put("ServiceCount", ns.getServiceCount());
        if (ns.getCreateDate() != null) {
            node.put("CreateDate", ns.getCreateDate().getEpochSecond());
        }
        if (ns.getCreatorRequestId() != null) {
            node.put("CreatorRequestId", ns.getCreatorRequestId());
        }
        node.set("Properties", buildNamespaceProperties(ns));
        return node;
    }

    private ObjectNode buildNamespaceProperties(Namespace ns) {
        ObjectNode props = objectMapper.createObjectNode();
        if (!"HTTP".equals(ns.getType())) {
            ObjectNode dns = props.putObject("DnsProperties");
            if (ns.getHostedZoneId() != null) {
                dns.put("HostedZoneId", ns.getHostedZoneId());
            }
            dns.putObject("SOA").put("TTL", 15);
        }
        props.putObject("HttpProperties").put("HttpName", ns.getName());
        return props;
    }

    private ObjectNode buildServiceNode(Service s) {
        ObjectNode node = buildServiceSummaryNode(s);
        if (s.getNamespaceId() != null) {
            node.put("NamespaceId", s.getNamespaceId());
        }
        if (s.getCreatorRequestId() != null) {
            node.put("CreatorRequestId", s.getCreatorRequestId());
        }
        return node;
    }

    private ObjectNode buildServiceSummaryNode(Service s) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", s.getId());
        node.put("Arn", s.getArn());
        node.put("Name", s.getName());
        if (s.getType() != null) {
            node.put("Type", s.getType());
        }
        if (s.getDescription() != null) {
            node.put("Description", s.getDescription());
        }
        node.put("InstanceCount", s.getInstanceCount());
        setRawJson(node, "DnsConfig", s.getDnsConfig());
        setRawJson(node, "HealthCheckConfig", s.getHealthCheckConfig());
        setRawJson(node, "HealthCheckCustomConfig", s.getHealthCheckCustomConfig());
        if (s.getCreateDate() != null) {
            node.put("CreateDate", s.getCreateDate().getEpochSecond());
        }
        return node;
    }

    private ObjectNode buildOperationNode(Operation op) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", op.getId());
        node.put("Type", op.getType());
        node.put("Status", op.getStatus());
        if (op.getErrorMessage() != null) {
            node.put("ErrorMessage", op.getErrorMessage());
        }
        if (op.getErrorCode() != null) {
            node.put("ErrorCode", op.getErrorCode());
        }
        if (op.getCreateDate() != null) {
            node.put("CreateDate", op.getCreateDate().getEpochSecond());
        }
        if (op.getUpdateDate() != null) {
            node.put("UpdateDate", op.getUpdateDate().getEpochSecond());
        }
        ObjectNode targets = node.putObject("Targets");
        op.getTargets().forEach(targets::put);
        return node;
    }

    private ObjectNode attributesNode(Map<String, String> attributes) {
        ObjectNode node = objectMapper.createObjectNode();
        if (attributes != null) {
            attributes.forEach(node::put);
        }
        return node;
    }

    // ──────────────────────────── Parsing ────────────────────────────

    private Map<String, List<String>> parseFilters(JsonNode request) {
        Map<String, List<String>> filters = new LinkedHashMap<>();
        JsonNode filtersNode = request.path("Filters");
        if (filtersNode.isArray()) {
            for (JsonNode f : filtersNode) {
                String name = f.path("Name").asText(null);
                if (name == null) {
                    continue;
                }
                List<String> values = new ArrayList<>();
                f.path("Values").forEach(v -> values.add(v.asText()));
                filters.put(name, values);
            }
        }
        return filters;
    }

    private Map<String, String> parseAttributes(JsonNode attributesNode) {
        Map<String, String> attributes = new LinkedHashMap<>();
        if (attributesNode != null && attributesNode.isObject()) {
            attributesNode.fields().forEachRemaining(e -> attributes.put(e.getKey(), e.getValue().asText()));
        }
        return attributes;
    }

    private Map<String, String> parseTags(JsonNode request) {
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode tagsNode = request.path("Tags");
        if (tagsNode.isArray()) {
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

    private String rawJson(JsonNode node) {
        return (node != null && node.isObject() && !node.isEmpty()) ? node.toString() : null;
    }

    private void setRawJson(ObjectNode target, String field, String rawJson) {
        if (rawJson == null) {
            return;
        }
        try {
            target.set(field, objectMapper.readTree(rawJson));
        } catch (Exception e) {
            LOG.warnv("Failed to parse stored {0} JSON; field omitted. Value: {1}", field, rawJson);
        }
    }
}