package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.eventbridgev2.model.EventSourceV2;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static io.floci.aws.services.eventbridgev2.EventBridgeV2Service.invalidInput;

/**
 * The request rules of the AWS service and partner event sources that AWS checks after the constraints of its
 * model, and the forms their configurations take.
 */
final class ServiceEventsConfigurations {
    static final String AWS_SERVICE = "AwsServiceEventsConfiguration";
    static final String PARTNER = "PartnerEventsConfiguration";
    static final String WEBHOOK_TYPE = "WEBHOOK_EVENTS";
    static final String AWS_SERVICE_TYPE = "AWS_SERVICE_EVENTS";

    private static final List<String> RESERVED_PATTERN_KEYS = List.of("source", "account", "region");
    private static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private ServiceEventsConfigurations() {
    }

    /** The {@code Type} of the source in a listing, from the one configuration member it holds. */
    static String typeOf(EventSourceV2 source) {
        return source.getConfiguration().has(AWS_SERVICE) ? AWS_SERVICE_TYPE : WEBHOOK_TYPE;
    }

    static boolean isAwsService(EventSourceV2 source) {
        return source.getConfiguration().has(AWS_SERVICE);
    }

    /**
     * Checks the {@code Pattern} of a source's configuration. A service source may not set the keys its forwarding
     * rule sets itself, and a partner source may.
     */
    static void checkPattern(JsonNode variant, boolean refuseReservedKeys) {
        String text = variant.path("Pattern").textValue();
        if (text == null || text.isEmpty()) {
            return;
        }
        JsonNode pattern;
        try {
            pattern = STRICT.readTree(text);
        } catch (JsonProcessingException e) {
            throw invalidInput("Pattern is not valid JSON");
        }
        if (pattern == null || !pattern.isObject()) {
            throw invalidInput("Pattern must be a JSON object");
        }
        if (!refuseReservedKeys) {
            return;
        }
        Iterator<String> keys = pattern.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            if (RESERVED_PATTERN_KEYS.contains(key)) {
                throw invalidInput("Pattern must not set the key '" + key + "'; the forwarding rule sets it from "
                        + "the requested AwsService and the source account and region");
            }
        }
    }

    /** Checks that the dead-letter queue lives in the caller's Region and account and is a standard queue. */
    static void checkOnFailure(JsonNode variant, String region, String accountId) {
        String arn = variant.path("OnFailureConfiguration").path("Arn").textValue();
        if (arn == null) {
            return;
        }
        AwsArnUtils.Arn queue = AwsArnUtils.parse(arn);
        if (!queue.region().equals(region)) {
            throw invalidInput("OnFailureConfiguration.Arn region '" + queue.region()
                    + "' must match the caller's region '" + region + "'");
        }
        if (!queue.accountId().equals(accountId)) {
            throw invalidInput("OnFailureConfiguration.Arn account '" + queue.accountId()
                    + "' must match the caller's account '" + accountId + "'");
        }
        if (queue.resource().endsWith(".fifo")) {
            throw invalidInput("OnFailureConfiguration.Arn must name a standard SQS queue: FIFO queues are not "
                    + "supported as EventSource dead-letter queues");
        }
    }

    static void checkPartnerRegion(JsonNode variant, String region) {
        String arn = variant.path("PartnerEventSourceArn").textValue();
        String arnRegion = AwsArnUtils.parse(arn).region();
        if (!arnRegion.equals(region)) {
            throw invalidInput("PartnerEventSourceArn region '" + arnRegion + "' must match the caller's region '"
                    + region + "'");
        }
    }

    /** The error for a partner source that Floci does not model, named as AWS words it. */
    static AwsException partnerSourceMissing(JsonNode variant) {
        String arn = variant.path("PartnerEventSourceArn").textValue();
        String name = AwsArnUtils.parse(arn).resource().substring("event-source/".length());
        return invalidInput("The partner event source " + name + " could not be described: Event source " + name
                + " does not exist.");
    }

    static AwsException provisioningFailed() {
        return invalidInput("EventSource forwarding could not be provisioned: InvalidEventPatternException");
    }

    /**
     * The pattern of the forwarding rule: the service, account and Region first, then the members of the
     * source's own pattern in the order it gives them.
     */
    static String forwardingPattern(JsonNode variant, String accountId, String region) {
        ObjectNode pattern = JsonNodeFactory.instance.objectNode();
        pattern.putArray("source").add(variant.get("AwsService").textValue());
        pattern.putArray("account").add(accountId);
        pattern.putArray("region").add(region);
        JsonNode own = ownPattern(variant);
        for (Map.Entry<String, JsonNode> member : own.properties()) {
            pattern.set(member.getKey(), member.getValue());
        }
        return pattern.toString();
    }

    static boolean givesPattern(JsonNode variant) {
        String text = variant.path("Pattern").textValue();
        return text != null && !text.isEmpty();
    }

    /** The source's own pattern, empty when it gives none. */
    static JsonNode ownPattern(JsonNode variant) {
        String text = variant.path("Pattern").textValue();
        if (text == null || text.isEmpty()) {
            return JsonNodeFactory.instance.objectNode();
        }
        try {
            return STRICT.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The pattern of a checked event source is not JSON", e);
        }
    }

    /**
     * Whether a pattern the source gave can stand as an event pattern on its own: it names at least one member,
     * and every member is an array or an object.
     */
    static boolean isUsablePattern(JsonNode pattern) {
        return !pattern.isEmpty() && membersAreFilters(pattern);
    }

    private static boolean membersAreFilters(JsonNode pattern) {
        for (JsonNode member : pattern) {
            if (member.isObject() ? !membersAreFilters(member) : !member.isArray()) {
                return false;
            }
        }
        return true;
    }

    /** The {@code Configuration} a describe returns: as given, less the members the model does not declare. */
    static JsonNode echo(JsonNode variant) {
        ObjectNode copy = (ObjectNode) EventBridgeV2RequestShapes.modeled("AwsServiceEventsSourceConfiguration",
                variant);
        WebhookEventsConfigurations.dropNulls(copy);
        ObjectNode configuration = copy.objectNode();
        configuration.set(AWS_SERVICE, copy);
        return configuration;
    }
}
