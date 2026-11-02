package io.github.hectorvent.floci.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.eventbridgev2.model.SubscriberV2;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Delivers an event a subscriber's filters matched to its target, an Amazon SQS queue: one message per event,
 * whose body {@code Transformer.Type} decides, {@code RAW} the event's data unchanged and {@code WITH_METADATA}
 * its data, metadata and system metadata, with the queue parameters {@code SqsParameters} gives.
 */
@ApplicationScoped
public class EventBridgeV2Delivery {

    private static final Logger LOG = Logger.getLogger(EventBridgeV2Delivery.class);

    private final SqsService sqsService;
    private final String baseUrl;

    @Inject
    public EventBridgeV2Delivery(SqsService sqsService, EmulatorConfig config) {
        this.sqsService = sqsService;
        this.baseUrl = config.baseUrl();
    }

    /** Sends the event to the subscriber's queue; a delivery that fails is logged and the event dropped. */
    public void deliver(SubscriberV2 subscriber, PublishedEvent event) {
        String queueArn = subscriber.getInvokeConfiguration().path("TargetArn").asText();
        try {
            JsonNode parameters = subscriber.getInvokeConfiguration().path("SqsParameters");
            String groupId = parameter(parameters, "MessageGroupId", event);
            String deduplicationId = parameter(parameters, "MessageDeduplicationId", event);
            String delay = parameter(parameters, "DelaySeconds", event);
            Map<String, MessageAttributeValue> attributes = messageAttributes(parameters.path("MessageAttributes"), event);
            String region = AwsArnUtils.parse(queueArn).region();
            sqsService.sendMessage(AwsArnUtils.arnToQueueUrl(queueArn, baseUrl), body(subscriber, event),
                    delay == null ? null : Integer.valueOf(delay), groupId, deduplicationId,
                    attributes.isEmpty() ? null : attributes, region);
            LOG.debugv("EventBridge V2 subscriber {0} delivered to {1}", subscriber.getArn(), queueArn);
        } catch (AwsException | IllegalArgumentException e) {
            LOG.warnv("EventBridge V2 subscriber {0} failed to deliver to {1}: {2}", subscriber.getArn(), queueArn,
                    e.getMessage());
        }
    }

    static String body(SubscriberV2 subscriber, PublishedEvent event) {
        JsonNode transformer = subscriber.getTransformer();
        String type = transformer == null ? "RAW" : transformer.path("Type").asText("RAW");
        if ("WITH_METADATA".equals(type)) {
            return event.withMetadata().toString();
        }
        JsonNode data = event.data();
        return data.isTextual() ? data.textValue() : data.toString();
    }

    private static String parameter(JsonNode parameters, String name, PublishedEvent event) {
        JsonNode value = parameters.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        return resolve(value.asText(), name, event);
    }

    private static Map<String, MessageAttributeValue> messageAttributes(JsonNode attributes, PublishedEvent event) {
        Map<String, MessageAttributeValue> resolved = new LinkedHashMap<>();
        attributes.properties().forEach(attribute -> {
            JsonNode value = attribute.getValue();
            String dataType = value.path("DataType").asText("String");
            String name = resolve(attribute.getKey(), "MessageAttributes", event);
            if (value.hasNonNull("BinaryValue")) {
                resolved.put(name, new MessageAttributeValue(Base64.getDecoder().decode(value.get("BinaryValue").asText()),
                        dataType));
            } else {
                resolved.put(name, new MessageAttributeValue(
                        resolve(value.path("StringValue").asText(""), "MessageAttributes." + name, event), dataType));
            }
        });
        return resolved;
    }

    private static String resolve(String value, String parameter, PublishedEvent event) {
        Optional<String> resolved = EventExpressions.resolve(value, event);
        return resolved.orElseThrow(() -> new IllegalArgumentException(
                "SqsParameters." + parameter + " expression " + value + " produced no value"));
    }
}
