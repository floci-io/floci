package io.floci.aws.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.services.cloudwatch.logs.model.Delivery;
import io.floci.aws.services.cloudwatch.logs.model.DeliveryDestination;
import io.floci.aws.services.cloudwatch.logs.model.DeliverySource;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The wire shapes of the vended-log delivery operations, reached through
 * {@link CloudWatchLogsHandler}: request members in the JSON 1.1 form the Logs API takes,
 * responses as the API reference lists them.
 */
class CloudWatchLogsDeliveryHandler {

    private final CloudWatchLogsDeliveryService deliveries;
    private final ObjectMapper objectMapper;

    CloudWatchLogsDeliveryHandler(CloudWatchLogsDeliveryService deliveries, ObjectMapper objectMapper) {
        this.deliveries = deliveries;
        this.objectMapper = objectMapper;
    }

    Response handle(String action, JsonNode request, String region) {
        CloudWatchLogsRequestShapes.check(action, request);
        return switch (action) {
            case "PutDeliverySource" -> putDeliverySource(request, region);
            case "GetDeliverySource" -> wrapped("deliverySource",
                    source(deliveries.getDeliverySource(text(request, "name"), region)));
            case "DeleteDeliverySource" -> {
                deliveries.deleteDeliverySource(text(request, "name"), region);
                yield empty();
            }
            case "DescribeDeliverySources" -> page("deliverySources",
                    deliveries.describeDeliverySources(limit(request), text(request, "nextToken"),
                            stringList(request.get("resourceArns")), region),
                    this::source);
            case "PutDeliveryDestination" -> putDeliveryDestination(request, region);
            case "GetDeliveryDestination" -> wrapped("deliveryDestination",
                    destination(deliveries.getDeliveryDestination(text(request, "name"), region)));
            case "DeleteDeliveryDestination" -> {
                deliveries.deleteDeliveryDestination(text(request, "name"), region);
                yield empty();
            }
            case "DescribeDeliveryDestinations" -> page("deliveryDestinations",
                    deliveries.describeDeliveryDestinations(limit(request), text(request, "nextToken"),
                            stringList(request.get("names")), region),
                    this::destination);
            case "CreateDelivery" -> createDelivery(request, region);
            case "GetDelivery" -> wrapped("delivery", delivery(deliveries.getDelivery(text(request, "id"), region)));
            case "UpdateDeliveryConfiguration" -> updateDeliveryConfiguration(request, region);
            case "DeleteDelivery" -> {
                deliveries.deleteDelivery(text(request, "id"), region);
                yield empty();
            }
            case "DescribeDeliveries" -> page("deliveries",
                    deliveries.describeDeliveries(limit(request), text(request, "nextToken"),
                            stringList(request.get("deliverySourceNames")), region),
                    this::deliverySummary);
            default -> throw new IllegalStateException("not a delivery action: " + action);
        };
    }

    private Response putDeliverySource(JsonNode request, String region) {
        DeliverySource source = deliveries.putDeliverySource(text(request, "name"), text(request, "resourceArn"),
                text(request, "logType"), stringMap(request.get("deliverySourceConfiguration")),
                stringMap(request.get("tags")), region);
        return wrapped("deliverySource", source(source));
    }

    private Response putDeliveryDestination(JsonNode request, String region) {
        JsonNode configuration = request.path("deliveryDestinationConfiguration");
        DeliveryDestination destination = deliveries.putDeliveryDestination(text(request, "name"),
                text(request, "outputFormat"), text(configuration, "destinationResourceArn"),
                text(request, "deliveryDestinationType"), stringMap(request.get("tags")), region);
        return wrapped("deliveryDestination", destination(destination));
    }

    private Response createDelivery(JsonNode request, String region) {
        JsonNode s3 = request.path("s3DeliveryConfiguration");
        Delivery delivery = deliveries.createDelivery(text(request, "deliverySourceName"),
                text(request, "deliveryDestinationArn"), stringList(request.get("recordFields")),
                text(request, "fieldDelimiter"), bool(s3, "enableHiveCompatiblePath"), text(s3, "suffixPath"),
                stringMap(request.get("tags")), region);
        return wrapped("delivery", delivery(delivery));
    }

    private Response updateDeliveryConfiguration(JsonNode request, String region) {
        JsonNode s3 = request.path("s3DeliveryConfiguration");
        deliveries.updateDeliveryConfiguration(text(request, "id"), stringList(request.get("recordFields")),
                text(request, "fieldDelimiter"), bool(s3, "enableHiveCompatiblePath"), text(s3, "suffixPath"),
                region);
        return empty();
    }

    private ObjectNode source(DeliverySource source) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", source.getName());
        node.put("arn", source.getArn());
        ArrayNode resourceArns = node.putArray("resourceArns");
        source.getResourceArns().forEach(resourceArns::add);
        node.put("service", source.getService());
        node.put("logType", source.getLogType());
        if (!source.getDeliverySourceConfiguration().isEmpty()) {
            node.set("deliverySourceConfiguration", objectMapper.valueToTree(source.getDeliverySourceConfiguration()));
        }
        return node;
    }

    private ObjectNode destination(DeliveryDestination destination) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", destination.getName());
        node.put("arn", destination.getArn());
        node.put("deliveryDestinationType", destination.getDeliveryDestinationType());
        if (destination.getOutputFormat() != null) {
            node.put("outputFormat", destination.getOutputFormat());
        }
        if (destination.getDestinationResourceArn() != null) {
            node.putObject("deliveryDestinationConfiguration")
                    .put("destinationResourceArn", destination.getDestinationResourceArn());
        }
        return node;
    }

    private ObjectNode delivery(Delivery delivery) {
        ObjectNode node = deliverySummary(delivery);
        if (!delivery.getRecordFields().isEmpty()) {
            ArrayNode fields = node.putArray("recordFields");
            delivery.getRecordFields().forEach(fields::add);
        }
        // An empty delimiter, the default, is left out, as AWS leaves it out.
        if (delivery.getFieldDelimiter() != null && !delivery.getFieldDelimiter().isEmpty()) {
            node.put("fieldDelimiter", delivery.getFieldDelimiter());
        }
        if (delivery.getEnableHiveCompatiblePath() != null || delivery.getSuffixPath() != null) {
            ObjectNode s3 = node.putObject("s3DeliveryConfiguration");
            if (delivery.getEnableHiveCompatiblePath() != null) {
                s3.put("enableHiveCompatiblePath", delivery.getEnableHiveCompatiblePath());
            }
            if (delivery.getSuffixPath() != null) {
                s3.put("suffixPath", delivery.getSuffixPath());
            }
        }
        return node;
    }

    /**
     * A delivery as DescribeDeliveries lists it: without the record fields, delimiter and S3
     * configuration GetDelivery adds. Tags are read through ListTagsForResource only, as on AWS.
     */
    private ObjectNode deliverySummary(Delivery delivery) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", delivery.getId());
        node.put("arn", delivery.getArn());
        node.put("deliverySourceName", delivery.getDeliverySourceName());
        node.put("deliveryDestinationArn", delivery.getDeliveryDestinationArn());
        node.put("deliveryDestinationType", delivery.getDeliveryDestinationType());
        return node;
    }

    private Response wrapped(String member, ObjectNode value) {
        ObjectNode out = objectMapper.createObjectNode();
        out.set(member, value);
        return Response.ok(out).build();
    }

    private <T> Response page(String member, PaginatedResult<T> page, Function<T, ObjectNode> render) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode items = out.putArray(member);
        page.items().forEach(item -> items.add(render.apply(item)));
        if (page.nextToken() != null) {
            out.put("nextToken", page.nextToken());
        }
        return Response.ok(out).build();
    }

    private Response empty() {
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    /** The page size; the vendored constraints have already checked it is an integer from 1 to 50. */
    private static Integer limit(JsonNode request) {
        JsonNode limit = request.get("limit");
        return limit == null || limit.isNull() ? null : limit.asInt();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asBoolean();
    }

    private static Map<String, String> stringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Map<String, String> map = new LinkedHashMap<>();
        node.properties().forEach(entry -> map.put(entry.getKey(), entry.getValue().asText()));
        return map;
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> list = new ArrayList<>();
        node.forEach(value -> list.add(value.asText()));
        return list;
    }
}
