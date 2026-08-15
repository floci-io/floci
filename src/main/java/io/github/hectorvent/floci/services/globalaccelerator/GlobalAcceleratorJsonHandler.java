package io.github.hectorvent.floci.services.globalaccelerator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.globalaccelerator.model.EndpointGroup;
import io.github.hectorvent.floci.services.globalaccelerator.model.PortOverride;
import io.github.hectorvent.floci.services.globalaccelerator.model.PortRange;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Global Accelerator management plane.
 *
 * <p>Operations that are not implemented fall through to a clean
 * {@code UnknownOperationException} rather than a stub success, so callers fail fast
 * instead of stranding a waiter.
 */
@ApplicationScoped
public class GlobalAcceleratorJsonHandler {

    private final GlobalAcceleratorService service;
    private final ObjectMapper mapper;

    @Inject
    public GlobalAcceleratorJsonHandler(GlobalAcceleratorService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region) throws Exception {
        return switch (action) {
            case "CreateAccelerator" -> Response.ok(Map.of("Accelerator", service.createAccelerator(
                    text(request, "Name"),
                    text(request, "IpAddressType"),
                    stringList(request.path("IpAddresses")),
                    bool(request, "Enabled"),
                    tagMap(request.path("Tags"))))).build();
            case "DescribeAccelerator" -> Response.ok(Map.of("Accelerator",
                    service.describeAccelerator(text(request, "AcceleratorArn")))).build();
            case "UpdateAccelerator" -> Response.ok(Map.of("Accelerator", service.updateAccelerator(
                    text(request, "AcceleratorArn"),
                    text(request, "Name"),
                    text(request, "IpAddressType"),
                    stringList(request.path("IpAddresses")),
                    bool(request, "Enabled")))).build();
            case "DeleteAccelerator" -> {
                service.deleteAccelerator(text(request, "AcceleratorArn"));
                yield Response.ok(Map.of()).build();
            }
            case "ListAccelerators" -> Response.ok(Map.of("Accelerators", service.listAccelerators())).build();
            case "DescribeAcceleratorAttributes" -> Response.ok(Map.of("AcceleratorAttributes",
                    service.describeAcceleratorAttributes(text(request, "AcceleratorArn")))).build();
            case "UpdateAcceleratorAttributes" -> Response.ok(Map.of("AcceleratorAttributes",
                    service.updateAcceleratorAttributes(
                            text(request, "AcceleratorArn"),
                            bool(request, "FlowLogsEnabled"),
                            text(request, "FlowLogsS3Bucket"),
                            text(request, "FlowLogsS3Prefix")))).build();

            case "CreateListener" -> Response.ok(Map.of("Listener", service.createListener(
                    text(request, "AcceleratorArn"),
                    portRanges(request.path("PortRanges")),
                    text(request, "Protocol"),
                    text(request, "ClientAffinity")))).build();
            case "DescribeListener" -> Response.ok(Map.of("Listener",
                    service.describeListener(text(request, "ListenerArn")))).build();
            case "UpdateListener" -> Response.ok(Map.of("Listener", service.updateListener(
                    text(request, "ListenerArn"),
                    portRanges(request.path("PortRanges")),
                    text(request, "Protocol"),
                    text(request, "ClientAffinity")))).build();
            case "DeleteListener" -> {
                service.deleteListener(text(request, "ListenerArn"));
                yield Response.ok(Map.of()).build();
            }
            case "ListListeners" -> Response.ok(Map.of("Listeners",
                    service.listListeners(text(request, "AcceleratorArn")))).build();

            case "CreateEndpointGroup" -> Response.ok(Map.of("EndpointGroup", service.createEndpointGroup(
                    text(request, "ListenerArn"),
                    text(request, "EndpointGroupRegion"),
                    nodeOrNull(request, "EndpointConfigurations"),
                    floatValue(request, "TrafficDialPercentage"),
                    integer(request, "HealthCheckPort"),
                    text(request, "HealthCheckProtocol"),
                    text(request, "HealthCheckPath"),
                    integer(request, "HealthCheckIntervalSeconds"),
                    integer(request, "ThresholdCount"),
                    portOverrides(request.path("PortOverrides"))))).build();
            case "DescribeEndpointGroup" -> Response.ok(Map.of("EndpointGroup",
                    service.describeEndpointGroup(text(request, "EndpointGroupArn")))).build();
            case "UpdateEndpointGroup" -> Response.ok(Map.of("EndpointGroup", service.updateEndpointGroup(
                    text(request, "EndpointGroupArn"),
                    nodeOrNull(request, "EndpointConfigurations"),
                    floatValue(request, "TrafficDialPercentage"),
                    integer(request, "HealthCheckPort"),
                    text(request, "HealthCheckProtocol"),
                    text(request, "HealthCheckPath"),
                    integer(request, "HealthCheckIntervalSeconds"),
                    integer(request, "ThresholdCount"),
                    portOverrides(request.path("PortOverrides"))))).build();
            case "DeleteEndpointGroup" -> {
                service.deleteEndpointGroup(text(request, "EndpointGroupArn"));
                yield Response.ok(Map.of()).build();
            }
            case "ListEndpointGroups" -> {
                String listenerArn = text(request, "ListenerArn");
                service.describeListener(listenerArn);
                yield Response.ok(Map.of("EndpointGroups", service.listEndpointGroups(listenerArn))).build();
            }
            case "AddEndpoints" -> {
                String endpointGroupArn = text(request, "EndpointGroupArn");
                EndpointGroup group = service.addEndpoints(endpointGroupArn,
                        nodeOrNull(request, "EndpointConfigurations"));
                yield Response.ok(Map.of(
                        "EndpointDescriptions", group.getEndpointDescriptions(),
                        "EndpointGroupArn", endpointGroupArn)).build();
            }
            case "RemoveEndpoints" -> {
                service.removeEndpoints(text(request, "EndpointGroupArn"),
                        endpointIdentifiers(request.path("EndpointIdentifiers")));
                yield Response.ok(Map.of()).build();
            }

            case "TagResource" -> {
                service.tagResource(text(request, "ResourceArn"), tagMap(request.path("Tags")));
                yield Response.ok(Map.of()).build();
            }
            case "UntagResource" -> {
                service.untagResource(text(request, "ResourceArn"), stringList(request.path("TagKeys")));
                yield Response.ok(Map.of()).build();
            }
            case "ListTagsForResource" -> Response.ok(Map.of("Tags",
                    tagList(service.listTagsForResource(text(request, "ResourceArn"))))).build();

            default -> throw new AwsException("UnknownOperationException",
                    "Operation " + action + " is not supported by floci", 400);
        };
    }

    private static String text(JsonNode request, String field) {
        JsonNode node = request.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private static Boolean bool(JsonNode request, String field) {
        JsonNode node = request.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asBoolean();
    }

    private static Integer integer(JsonNode request, String field) {
        JsonNode node = request.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asInt();
    }

    private static Float floatValue(JsonNode request, String field) {
        JsonNode node = request.path(field);
        return node.isMissingNode() || node.isNull() ? null : (float) node.asDouble();
    }

    private static JsonNode nodeOrNull(JsonNode request, String field) {
        JsonNode node = request.path(field);
        return node.isMissingNode() || node.isNull() ? null : node;
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        node.forEach(element -> values.add(element.asText()));
        return values;
    }

    private static List<String> endpointIdentifiers(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> endpointIds = new ArrayList<>();
        node.forEach(element -> endpointIds.add(element.path("EndpointId").asText()));
        return endpointIds;
    }

    private List<PortRange> portRanges(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isArray()) {
            return null;
        }
        return mapper.convertValue(node, new TypeReference<List<PortRange>>() {});
    }

    private List<PortOverride> portOverrides(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isArray()) {
            return null;
        }
        return mapper.convertValue(node, new TypeReference<List<PortOverride>>() {});
    }

    private static Map<String, String> tagMap(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        Map<String, String> tags = new LinkedHashMap<>();
        for (JsonNode tag : node) {
            String key = tag.path("Key").asText(null);
            if (key == null) {
                throw new AwsException("InvalidArgumentException", "Every tag must carry a Key.", 400);
            }
            tags.put(key, tag.path("Value").asText(""));
        }
        return tags;
    }

    private ArrayNode tagList(Map<String, String> tags) {
        ArrayNode list = mapper.createArrayNode();
        tags.forEach((key, value) -> {
            ObjectNode tag = list.addObject();
            tag.put("Key", key);
            tag.put("Value", value);
        });
        return list;
    }
}
