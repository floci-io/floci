package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The checks DynamoDB runs on the RequestItems map of BatchGetItem and BatchWriteItem. */
final class DynamoDbBatchRequestItems {

    private static final List<String> TABLE_NAME_RULES =
            SmithyValidationErrors.constraintSet(3, 255, "[a-zA-Z0-9_.-]+", null);
    private static final List<String> TABLE_ARN_RULES = List.of("Valid resource name or resource arn must be given. "
            + "Valid resource arn format is 'arn:<awsPartition>:<vendor>:<region>:<subscriber>:resourceType/resourceName', "
            + "where Resource name must have length less than or equal to 255, "
            + "Resource name must have length greater than or equal to 3, "
            + "Resource name must satisfy regular expression pattern: [a-zA-Z0-9_.-]+");
    private static final List<String> KEYS_AND_ATTRIBUTES_MEMBERS = List.of("AttributesToGet", "ConsistentRead",
            "ExpressionAttributeNames", "Keys", "ProjectionExpression");
    private static final ObjectWriter ECHO_WRITER =
            new ObjectMapper().writer().without(JsonWriteFeature.WRITE_HEX_UPPER_CASE);

    private DynamoDbBatchRequestItems() {}

    static void requireValidLengths(JsonNode requestItems, boolean write) {
        List<String> violations = new ArrayList<>();
        addIfPresent(violations, SmithyValidationErrors.sizeOutOfRange("RequestItems",
                requestItems.properties().size(), 1, write ? 25 : 100));
        for (Map.Entry<String, JsonNode> table : requestItems.properties()) {
            String name = table.getKey();
            String path = "RequestItems." + name;
            addIfPresent(violations, SmithyValidationErrors.sizeOutOfRange(path,
                    name.codePointCount(0, name.length()), 1, 1024));
            if (write) {
                addListSize(violations, path + ".member", table.getValue(), 25);
            } else {
                addListSize(violations, path + ".member.AttributesToGet", table.getValue().get("AttributesToGet"),
                        Integer.MAX_VALUE);
                addListSize(violations, path + ".member.Keys", table.getValue().get("Keys"), 100);
            }
        }
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
    }

    static String tableNamesViolation(JsonNode requestItems, String region, boolean write) {
        for (Map.Entry<String, JsonNode> table : requestItems.properties()) {
            String name = table.getKey();
            boolean arn = AwsArnUtils.looksLikeArn(name);
            boolean valid = arn
                    ? DynamoDbTableNames.isTableArn(name, region)
                    : DynamoDbTableNames.nameViolations(name).isEmpty();
            if (!valid) {
                // The first bad name picks the rules DynamoDB lists.
                return SmithyValidationErrors.Violations.QUOTED.mapKeys("requestItems", echo(requestItems, write),
                        arn ? TABLE_ARN_RULES : TABLE_NAME_RULES);
            }
        }
        return null;
    }

    // DynamoDB lists the members of a table entry by name and leaves out null and unknown ones.
    private static String echo(JsonNode requestItems, boolean write) {
        ObjectNode echo = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, JsonNode> table : requestItems.properties()) {
            echo.set(table.getKey(), write ? writeRequestsEcho(table.getValue()) : keysAndAttributesEcho(table.getValue()));
        }
        try {
            return ECHO_WRITER.writeValueAsString(echo);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ObjectNode keysAndAttributesEcho(JsonNode tableRequest) {
        ObjectNode echo = JsonNodeFactory.instance.objectNode();
        for (String member : KEYS_AND_ATTRIBUTES_MEMBERS) {
            JsonNode value = tableRequest.get(member);
            if (value != null && !value.isNull()) {
                echo.set(member, value);
            }
        }
        return echo;
    }

    private static ArrayNode writeRequestsEcho(JsonNode writeRequests) {
        ArrayNode echo = JsonNodeFactory.instance.arrayNode();
        for (JsonNode writeRequest : writeRequests) {
            ObjectNode request = echo.addObject();
            addRequestEcho(request, writeRequest, "DeleteRequest", "Key");
            addRequestEcho(request, writeRequest, "PutRequest", "Item");
        }
        return echo;
    }

    private static void addRequestEcho(ObjectNode echo, JsonNode writeRequest, String member, String attributes) {
        JsonNode request = writeRequest.get(member);
        if (request == null || request.isNull()) {
            return;
        }
        ObjectNode requestEcho = echo.putObject(member);
        JsonNode value = request.get(attributes);
        if (value != null && !value.isNull()) {
            requestEcho.set(attributes, value);
        }
    }

    private static void addListSize(List<String> violations, String path, JsonNode list, int max) {
        if (list != null && list.isArray()) {
            addIfPresent(violations, SmithyValidationErrors.sizeOutOfRange(path, list.size(), 1, max));
        }
    }

    private static void addIfPresent(List<String> violations, String violation) {
        if (violation != null) {
            violations.add(violation);
        }
    }
}
