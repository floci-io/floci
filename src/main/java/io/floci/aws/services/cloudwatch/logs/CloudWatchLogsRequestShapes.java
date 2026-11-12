package io.floci.aws.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestConstraintCheck;
import io.floci.aws.core.common.VendoredPatterns;
import io.floci.aws.core.common.VendoredResources;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks delivery and saved-query requests against the constraints vendored in
 * {@code aws/logs-delivery-request-shapes.json} by {@code tools/aws/regen_logs_delivery_shapes.py},
 * answering {@code InvalidParameterException} for saved queries and {@code ValidationException}
 * for deliveries, in the shared constraint-message form:
 * {@code 1 validation error detected: Value at 'limit' failed to satisfy constraint: Member must have
 * value less than or equal to 50}. An element of a list is reported at the list, with every
 * constraint on its elements: {@code Value at 'deliverySourceNames' failed to satisfy constraint:
 * Member must satisfy constraint: [Member must have length less than or equal to 60, ...]}.
 */
final class CloudWatchLogsRequestShapes {

    private static final JsonNode MODEL = VendoredResources.read("aws/logs-delivery-request-shapes.json",
            "Logs request shapes", input -> new ObjectMapper().readTree(input));

    private static final RequestConstraintCheck CHECK = RequestConstraintCheck.of(MODEL,
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return violation(path, "Member must be a " + type);
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, Number size) {
                    String measure = "integer".equals(type) || "long".equals(type) ? "value" : "length";
                    return max != null && size.longValue() > max
                            ? violation(path, "Member must have " + measure + " less than or equal to " + max)
                            : violation(path, "Member must have " + measure + " greater than or equal to " + min);
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return violation(path, "Member must satisfy regular expression pattern: " + pattern);
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return violation(path, "Member must satisfy enum value set: " + values);
                }

                @Override
                public AwsException required(String path) {
                    return violation(path, "Member must not be null");
                }
            });

    private CloudWatchLogsRequestShapes() {
    }

    static void check(String operation, JsonNode request) {
        CHECK.check(operation, request);
    }

    /** The member's path without the operation name the check starts it with. */
    private static AwsException violation(String path, String constraint) {
        String operation = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
        String errorCode = switch (operation) {
            case "PutQueryDefinition", "DescribeQueryDefinitions", "DeleteQueryDefinition" -> "InvalidParameterException";
            default -> "ValidationException";
        };
        String member = path.contains(".") ? path.substring(path.indexOf('.') + 1) : path;
        if (member.endsWith("[]")) {
            member = member.substring(0, member.length() - 2);
            List<String> constraints = elementConstraints(path);
            if (!constraints.isEmpty()) {
                constraint = "Member must satisfy constraint: " + constraints;
            }
        }
        return new AwsException(errorCode, "1 validation error detected: Value at '" + member
                + "' failed to satisfy constraint: " + constraint, 400);
    }

    /** Every constraint on the elements of the top-level list at {@code path}, in the order AWS lists them. */
    private static List<String> elementConstraints(String path) {
        String operation = path.substring(0, path.indexOf('.'));
        String list = path.substring(path.indexOf('.') + 1, path.length() - 2);
        JsonNode shapes = MODEL.path("shapes");
        String input = MODEL.path("operations").path(operation).asText();
        String listShape = shapes.path(input).path("members").path(list).asText();
        JsonNode element = shapes.path(shapes.path(listShape).path("member").asText());
        String measure = "integer".equals(element.path("type").asText()) ? "value" : "length";
        List<String> constraints = new ArrayList<>();
        if (element.has("max")) {
            constraints.add("Member must have " + measure + " less than or equal to " + element.get("max").asLong());
        }
        if (element.has("min")) {
            constraints.add("Member must have " + measure + " greater than or equal to " + element.get("min").asLong());
        }
        if (element.has("pattern")) {
            constraints.add("Member must satisfy regular expression pattern: " + VendoredPatterns.quoted(element));
        }
        return constraints;
    }
}
