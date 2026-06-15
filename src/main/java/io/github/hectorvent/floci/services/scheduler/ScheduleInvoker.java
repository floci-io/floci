package io.github.hectorvent.floci.services.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.scheduler.model.Target;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Delivers an EventBridge Scheduler target invocation to the underlying service.
 * Supports templated SQS, Lambda, SNS, and EventBridge PutEvents targets, plus
 * universal targets ({@code arn:aws:scheduler:::aws-sdk:<service>:<action>}) for
 * {@code sns:publish} and {@code sqs:sendMessage}. Mirrors the subset handled by
 * {@code EventBridgeInvoker} but using Scheduler's {@link Target} model (raw
 * {@code input} string, no JSONPath/template).
 */
@ApplicationScoped
public class ScheduleInvoker {

    private static final Logger LOG = Logger.getLogger(ScheduleInvoker.class);

    private final SqsService sqsService;
    private final LambdaService lambdaService;
    private final SnsService snsService;
    private final EventBridgeService eventBridgeService;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @Inject
    public ScheduleInvoker(SqsService sqsService,
                           LambdaService lambdaService,
                           SnsService snsService,
                           EventBridgeService eventBridgeService,
                           ObjectMapper objectMapper,
                           EmulatorConfig config) {
        this.sqsService = sqsService;
        this.lambdaService = lambdaService;
        this.snsService = snsService;
        this.eventBridgeService = eventBridgeService;
        this.objectMapper = objectMapper;
        this.baseUrl = config.baseUrl();
    }

    public void invoke(Target target, String region) {
        if (target == null || target.getArn() == null) {
            return;
        }
        String arn = target.getArn();
        String payload = target.getInput() != null ? target.getInput() : "{}";

        // Universal targets (arn:aws:scheduler:::aws-sdk:<service>:<action>) carry the
        // real resource identifiers inside Input, not in the target ARN. Detect and
        // dispatch them before the ARN-substring routing below, which would otherwise
        // mis-match (e.g. "aws-sdk:sns:publish" contains ":sns:").
        int sdkIdx = arn.indexOf(":aws-sdk:");
        if (sdkIdx >= 0) {
            invokeUniversalTarget(arn.substring(sdkIdx + ":aws-sdk:".length()), payload, region);
            return;
        }

        String targetRegion = extractRegion(arn, region);
        if (arn.contains(":sqs:")) {
            String queueUrl = AwsArnUtils.arnToQueueUrl(arn, baseUrl);
            String messageGroupId = target.getSqsParameters() != null
                    ? target.getSqsParameters().getMessageGroupId() : null;
            sqsService.sendMessage(queueUrl, payload, 0, messageGroupId, null, targetRegion);
            LOG.debugv("Scheduler delivered to SQS: {0}", arn);
        } else if (arn.contains(":lambda:") || arn.contains(":function:")) {
            String fnName = arn.substring(arn.lastIndexOf(':') + 1);
            lambdaService.invoke(targetRegion, fnName, payload.getBytes(), InvocationType.Event);
            LOG.debugv("Scheduler delivered to Lambda: {0}", arn);
        } else if (arn.contains(":sns:")) {
            snsService.publish(arn, null, payload, "Scheduler", targetRegion);
            LOG.debugv("Scheduler delivered to SNS: {0}", arn);
        } else if (isEventBridgePutEventsArn(arn)) {
            deliverToEventBridge(arn, payload, targetRegion);
            LOG.debugv("Scheduler delivered to EventBridge: {0}", arn);
        } else {
            LOG.warnv("Scheduler: unsupported target ARN type: {0}", arn);
        }
    }

    /**
     * Dispatches an EventBridge Scheduler universal target ({@code aws-sdk:<service>:<action>}),
     * reading the call parameters from the target's {@code Input} payload. Supports the
     * common {@code sns:publish} and {@code sqs:sendMessage} actions; other actions are
     * logged as unsupported.
     */
    private void invokeUniversalTarget(String serviceAction, String input, String region) {
        JsonNode params;
        try {
            params = objectMapper.readTree(input == null || input.isBlank() ? "{}" : input);
        } catch (Exception e) {
            LOG.warnv("Scheduler: universal target {0} has unparseable Input: {1}", serviceAction, e.getMessage());
            return;
        }
        switch (serviceAction) {
            case "sns:publish" -> {
                String topicArn = text(params, "TopicArn");
                String targetArn = text(params, "TargetArn");
                String message = text(params, "Message");
                String subject = text(params, "Subject");
                String messageGroupId = text(params, "MessageGroupId");
                String messageDeduplicationId = text(params, "MessageDeduplicationId");
                String snsRegion = extractRegion(topicArn != null ? topicArn : targetArn, region);
                snsService.publish(topicArn, targetArn, null, message, subject, null,
                        messageGroupId, messageDeduplicationId, snsRegion);
                LOG.debugv("Scheduler delivered to SNS (universal target): {0}", topicArn);
            }
            case "sqs:sendMessage" -> {
                String queueUrl = text(params, "QueueUrl");
                String body = text(params, "MessageBody");
                String messageGroupId = text(params, "MessageGroupId");
                String messageDeduplicationId = text(params, "MessageDeduplicationId");
                sqsService.sendMessage(queueUrl, body, 0, messageGroupId, messageDeduplicationId, region);
                LOG.debugv("Scheduler delivered to SQS (universal target): {0}", queueUrl);
            }
            default -> LOG.warnv("Scheduler: unsupported universal target action: {0}", serviceAction);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText() : null;
    }

    private boolean isEventBridgePutEventsArn(String arn) {
        return arn.contains(":events:") && arn.contains(":event-bus/");
    }

    private void deliverToEventBridge(String busArn, String payload, String region) {
        String busName = busArn.substring(busArn.indexOf(":event-bus/") + ":event-bus/".length());
        Map<String, Object> entry = new HashMap<>();
        entry.put("EventBusName", busName);
        entry.put("Source", "aws.scheduler");
        entry.put("DetailType", "Scheduled Event");
        entry.put("Detail", asDetail(payload));
        eventBridgeService.putEvents(List.of(entry), region);
    }

    private String asDetail(String payload) {
        try {
            objectMapper.readTree(payload);
            return payload;
        } catch (Exception e) {
            try {
                return objectMapper.writeValueAsString(Map.of("payload", payload));
            } catch (Exception inner) {
                return "{}";
            }
        }
    }

    private static String extractRegion(String arn, String defaultRegion) {
        if (arn == null) {
            return defaultRegion;
        }
        String[] parts = arn.split(":");
        return parts.length >= 4 && !parts[3].isEmpty() ? parts[3] : defaultRegion;
    }
}
