package io.github.hectorvent.floci.services.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.scheduler.model.EventBridgeParameters;
import io.github.hectorvent.floci.services.scheduler.model.EcsParameters;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.scheduler.model.Target;
import io.github.hectorvent.floci.services.sns.SnsMessageAttributes;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import io.github.hectorvent.floci.services.stepfunctions.StepFunctionsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Delivers an EventBridge Scheduler target invocation to the underlying service.
 * Supports templated SQS, Lambda, SNS, Step Functions, and EventBridge PutEvents targets, plus
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
    private final EcsService ecsService;
    private final StepFunctionsService stepFunctionsService;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @Inject
    public ScheduleInvoker(SqsService sqsService,
                           LambdaService lambdaService,
                           SnsService snsService,
                           EventBridgeService eventBridgeService,
                           EcsService ecsService,
                           StepFunctionsService stepFunctionsService,
                           ObjectMapper objectMapper,
                           EmulatorConfig config) {
        this.sqsService = sqsService;
        this.lambdaService = lambdaService;
        this.snsService = snsService;
        this.eventBridgeService = eventBridgeService;
        this.ecsService = ecsService;
        this.stepFunctionsService = stepFunctionsService;
        this.objectMapper = objectMapper;
        this.baseUrl = config.baseUrl();
    }

    /**
     * Delivers one occurrence of {@code schedule}, scheduled at {@code scheduledAt}, to its target
     * and returns the JSON request that was sent (see {@link #materializeRequest}).
     */
    public String invoke(Schedule schedule, Instant scheduledAt) {
        Target target = schedule.getTarget();
        if (target == null || target.getArn() == null) {
            return "{}";
        }
        String arn = target.getArn();
        String region = regionOf(schedule);
        if (isUniversalTarget(arn)) {
            invokeUniversalTarget(arn.substring(arn.indexOf(":aws-sdk:") + ":aws-sdk:".length()),
                    target.getInput(), region);
            return universalTargetRequest(target);
        }

        TargetKind kind = TargetKind.of(target);
        String payload = templatedPayload(schedule, scheduledAt);
        String targetRegion = extractRegion(arn, region);
        switch (kind) {
            case SQS -> {
                String queueUrl = AwsArnUtils.arnToQueueUrl(arn, baseUrl);
                String messageGroupId = target.getSqsParameters() != null
                        ? target.getSqsParameters().getMessageGroupId() : null;
                sqsService.sendMessage(queueUrl, payload, 0, messageGroupId, null, targetRegion);
                LOG.debugv("Scheduler delivered to SQS: {0}", arn);
            }
            case LAMBDA -> {
                lambdaService.invokeArn(arn, payload.getBytes(), InvocationType.Event);
                LOG.debugv("Scheduler delivered to Lambda: {0}", arn);
            }
            case SNS -> {
                snsService.publish(arn, null, payload, "Scheduler", targetRegion);
                LOG.debugv("Scheduler delivered to SNS: {0}", arn);
            }
            case ECS_RUN_TASK -> {
                deliverToEcsRunTask(target, targetRegion);
                LOG.debugv("Scheduler delivered to ECS RunTask: {0}", arn);
            }
            case STEP_FUNCTIONS -> {
                String targetAccount = AwsArnUtils.parse(arn).accountId();
                RequestScopes.runAs(targetAccount,
                        () -> stepFunctionsService.startExecution(arn, null, payload, targetRegion));
                LOG.debugv("Scheduler started Step Functions execution: {0}", arn);
            }
            case EVENT_BRIDGE -> {
                deliverToEventBridge(target, payload, targetRegion);
                LOG.debugv("Scheduler delivered to EventBridge: {0}", arn);
            }
            case UNSUPPORTED ->
                    throw new UnsupportedOperationException("Scheduler: unsupported target ARN type: " + arn);
        }
        return templatedTargetRequest(target, kind, payload);
    }

    /**
     * Returns the JSON request sent to the target service, for invocation diagnostics and DLQs.
     * Templated targets use the field names of the target service's API (SQS SendMessage, Lambda
     * Invoke, SNS Publish, ECS RunTask, Step Functions StartExecution, EventBridge PutEvents), as
     * in the dead-letter queue example of the Scheduler user guide.
     */
    public String materializeRequest(Schedule schedule, Instant scheduledAt) {
        Target target = schedule.getTarget();
        if (target == null || target.getArn() == null) {
            return "{}";
        }
        if (isUniversalTarget(target.getArn())) {
            return universalTargetRequest(target);
        }
        return templatedTargetRequest(target, TargetKind.of(target), templatedPayload(schedule, scheduledAt));
    }

    /** The templated target types Floci delivers to, in the order their ARNs are recognised. */
    private enum TargetKind {
        SQS, LAMBDA, SNS, ECS_RUN_TASK, STEP_FUNCTIONS, EVENT_BRIDGE, UNSUPPORTED;

        static TargetKind of(Target target) {
            String arn = target.getArn();
            if (arn.contains(":sqs:")) {
                return SQS;
            }
            if (arn.contains(":lambda:") || arn.contains(":function:")) {
                return LAMBDA;
            }
            if (arn.contains(":sns:")) {
                return SNS;
            }
            if (arn.contains(":ecs:") && target.getEcsParameters() != null) {
                return ECS_RUN_TASK;
            }
            if (isStateMachineArn(arn)) {
                return STEP_FUNCTIONS;
            }
            if (isEventBridgePutEventsArn(arn)) {
                return EVENT_BRIDGE;
            }
            return UNSUPPORTED;
        }
    }

    /**
     * Universal targets (arn:aws:scheduler:::aws-sdk:&lt;service&gt;:&lt;action&gt;) carry the real
     * resource identifiers inside Input, not in the target ARN. They are detected before the
     * ARN-substring routing of {@link TargetKind}, which would otherwise mis-match (e.g.
     * "aws-sdk:sns:publish" contains ":sns:").
     */
    private static boolean isUniversalTarget(String arn) {
        return arn.contains(":aws-sdk:");
    }

    private String universalTargetRequest(Target target) {
        return validJsonOrString(target.getInput() != null ? target.getInput() : "{}");
    }

    private String templatedTargetRequest(Target target, TargetKind kind, String payload) {
        String arn = target.getArn();
        Map<String, Object> request = new LinkedHashMap<>();
        switch (kind) {
            case SQS -> {
                request.put("MessageBody", payload);
                request.put("QueueUrl", AwsArnUtils.arnToQueueUrl(arn, baseUrl));
                if (target.getSqsParameters() != null
                        && target.getSqsParameters().getMessageGroupId() != null) {
                    request.put("MessageGroupId", target.getSqsParameters().getMessageGroupId());
                }
            }
            case LAMBDA -> {
                request.put("FunctionName", arn);
                request.put("InvocationType", InvocationType.Event.name());
                request.put("Payload", payload);
            }
            case SNS -> {
                request.put("TopicArn", arn);
                request.put("Message", payload);
            }
            case ECS_RUN_TASK -> request.putAll(ecsRunTaskRequest(target));
            case STEP_FUNCTIONS -> {
                request.put("stateMachineArn", arn);
                request.put("input", payload);
            }
            case EVENT_BRIDGE -> request.put("Entries", List.of(eventBridgeEntry(target, payload)));
            case UNSUPPORTED -> {
                return validJsonOrString(payload);
            }
        }
        return writeJson(request);
    }

    private static Map<String, Object> ecsRunTaskRequest(Target target) {
        EcsParameters ecs = target.getEcsParameters();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("cluster", target.getArn());
        request.put("taskDefinition", ecs.getTaskDefinitionArn());
        request.put("count", ecs.getTaskCount() != null ? ecs.getTaskCount() : 1);
        putIfPresent(request, "launchType", ecs.getLaunchType());
        putIfPresent(request, "group", ecs.getGroup());
        if (ecs.getNetworkConfiguration() != null && ecs.getNetworkConfiguration().getAwsvpcConfiguration() != null) {
            io.github.hectorvent.floci.services.scheduler.model.AwsVpcConfiguration vpc =
                    ecs.getNetworkConfiguration().getAwsvpcConfiguration();
            Map<String, Object> awsvpc = new LinkedHashMap<>();
            putIfPresent(awsvpc, "subnets", vpc.getSubnets());
            putIfPresent(awsvpc, "securityGroups", vpc.getSecurityGroups());
            putIfPresent(awsvpc, "assignPublicIp", vpc.getAssignPublicIp());
            request.put("networkConfiguration", Map.of("awsvpcConfiguration", awsvpc));
        }
        return request;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /**
     * The payload a templated target receives: the target's {@code Input}, or Scheduler's default
     * notification when no {@code Input} was configured (API reference, {@code Target.Input}).
     */
    private String templatedPayload(Schedule schedule, Instant scheduledAt) {
        String input = schedule.getTarget().getInput();
        return input != null ? input : defaultScheduledEvent(schedule, scheduledAt);
    }

    /**
     * Scheduler's default notification: an EventBridge-style {@code Scheduled Event} whose
     * {@code resources} names the schedule and whose {@code detail} is the string "{}". The event
     * id is stable per occurrence so retries and the dead-letter body carry the same event.
     */
    private String defaultScheduledEvent(Schedule schedule, Instant scheduledAt) {
        AwsArnUtils.Arn scheduleArn = AwsArnUtils.parse(schedule.getArn());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("version", "0");
        event.put("id", UUID.nameUUIDFromBytes(
                (schedule.getArn() + "@" + scheduledAt).getBytes(StandardCharsets.UTF_8)).toString());
        event.put("detail-type", "Scheduled Event");
        event.put("source", "aws.scheduler");
        event.put("account", scheduleArn.accountId());
        event.put("time", scheduledAt.truncatedTo(ChronoUnit.SECONDS).toString());
        event.put("region", scheduleArn.region());
        event.put("resources", List.of(schedule.getArn()));
        event.put("detail", "{}");
        return writeJson(event);
    }

    private String validJsonOrString(String payload) {
        try {
            objectMapper.readTree(payload);
            return payload;
        } catch (Exception e) {
            return writeJson(Map.of("Input", payload));
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void deliverToEcsRunTask(Target target, String region) {
        EcsParameters ecs = target.getEcsParameters();
        ecsService.runTask(
                target.getArn(),
                ecs.getTaskDefinitionArn(),
                ecs.getTaskCount() != null ? ecs.getTaskCount() : 1,
                parseLaunchType(ecs.getLaunchType()),
                null,
                ecs.getGroup() != null ? ecs.getGroup() : "scheduler",
                List.of(),
                ecsNetworkConfiguration(ecs.getNetworkConfiguration()),
                region);
    }

    private static LaunchType parseLaunchType(String launchType) {
        if (launchType == null || launchType.isBlank()) {
            return null;
        }
        try {
            return LaunchType.valueOf(launchType);
        } catch (IllegalArgumentException e) {
            LOG.warnv("Scheduler: unsupported ECS LaunchType: {0}", launchType);
            return null;
        }
    }

    private static io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration ecsNetworkConfiguration(
            io.github.hectorvent.floci.services.scheduler.model.NetworkConfiguration source) {
        if (source == null || source.getAwsvpcConfiguration() == null) {
            return null;
        }
        io.github.hectorvent.floci.services.scheduler.model.AwsVpcConfiguration sourceVpc = source.getAwsvpcConfiguration();
        io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration targetVpc =
                new io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration();
        targetVpc.setSubnets(sourceVpc.getSubnets());
        targetVpc.setSecurityGroups(sourceVpc.getSecurityGroups());
        targetVpc.setAssignPublicIp(sourceVpc.getAssignPublicIp());

        io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration target =
                new io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration();
        target.setAwsvpcConfiguration(targetVpc);
        return target;
    }

    /**
     * Dispatches an EventBridge Scheduler universal target ({@code aws-sdk:<service>:<action>}),
     * reading the call parameters from the target's {@code Input} payload. Supports the
     * common {@code sns:publish} and {@code sqs:sendMessage} actions; other actions fail
     * as unsupported.
     */
    private void invokeUniversalTarget(String serviceAction, String input, String region) {
        JsonNode params;
        try {
            params = objectMapper.readTree(input == null || input.isBlank() ? "{}" : input);
        } catch (Exception e) {
            throw new AwsException("InvalidParameterValue",
                    "Universal target Input is not valid JSON", 400);
        }
        switch (serviceAction) {
            case "sns:publish" -> {
                String topicArn = text(params, "TopicArn");
                String targetArn = text(params, "TargetArn");
                String message = text(params, "Message");
                String subject = text(params, "Subject");
                String messageGroupId = text(params, "MessageGroupId");
                String messageDeduplicationId = text(params, "MessageDeduplicationId");
                Map<String, MessageAttributeValue> messageAttributes = SnsMessageAttributes.parse(params.path("MessageAttributes"));
                String snsRegion = extractRegion(topicArn != null ? topicArn : targetArn, region);
                snsService.publish(topicArn, targetArn, null, message, subject, messageAttributes,
                        messageGroupId, messageDeduplicationId, snsRegion);
                LOG.debugv("Scheduler delivered to SNS (universal target): {0}", topicArn);
            }
            case "sqs:sendMessage" -> {
                String queueUrl = text(params, "QueueUrl");
                String body = text(params, "MessageBody");
                String messageGroupId = text(params, "MessageGroupId");
                String messageDeduplicationId = text(params, "MessageDeduplicationId");
                Map<String, MessageAttributeValue> messageAttributes =
                        parseUniversalSqsMessageAttributes(params.path("MessageAttributes"));
                sqsService.sendMessage(queueUrl, body, 0, messageGroupId, messageDeduplicationId,
                        messageAttributes, region);
                LOG.debugv("Scheduler delivered to SQS (universal target): {0}", queueUrl);
            }
            default -> throw new UnsupportedOperationException(
                    "Scheduler: unsupported universal target action: " + serviceAction);
        }
    }

    private static Map<String, MessageAttributeValue> parseUniversalSqsMessageAttributes(JsonNode attrsNode) {
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        if (attrsNode == null || !attrsNode.isObject()) {
            return attributes;
        }
        attrsNode.fields().forEachRemaining(entry -> {
            JsonNode valueNode = entry.getValue();
            String dataType = valueNode.path("DataType").asText(null);
            String stringValue = valueNode.path("StringValue").asText(null);
            String binaryValueBase64 = valueNode.path("BinaryValue").asText(null);
            if (dataType == null) {
                return;
            }
            if (binaryValueBase64 != null) {
                byte[] binaryValue;
                try {
                    binaryValue = Base64.getDecoder().decode(binaryValueBase64);
                } catch (IllegalArgumentException e) {
                    throw new AwsException("InvalidParameterValue",
                            "Invalid binary value for message attribute '" + entry.getKey()
                                    + "': not valid base64.", 400);
                }
                attributes.put(entry.getKey(), new MessageAttributeValue(binaryValue, dataType));
            } else if (stringValue != null) {
                attributes.put(entry.getKey(), new MessageAttributeValue(
                        stringValue, dataType));
            }
        });
        return attributes;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText() : null;
    }

    private static boolean isEventBridgePutEventsArn(String arn) {
        return arn.contains(":events:") && arn.contains(":event-bus/");
    }

    private static boolean isStateMachineArn(String arn) {
        if (!AwsArnUtils.isArnFor(arn, "states")) {
            return false;
        }
        String resource = AwsArnUtils.parse(arn).resource();
        String prefix = "stateMachine:";
        return resource.startsWith(prefix) && resource.indexOf(':', prefix.length()) < 0;
    }

    private void deliverToEventBridge(Target target, String payload, String region) {
        eventBridgeService.putEvents(List.of(eventBridgeEntry(target, payload)), region);
    }

    /** One PutEvents entry for an event-bus target, as delivered and as reported in diagnostics. */
    private Map<String, Object> eventBridgeEntry(Target target, String payload) {
        String busArn = target.getArn();
        String busName = busArn.substring(busArn.indexOf(":event-bus/") + ":event-bus/".length());

        // AWS requires DetailType and Source on the target's EventBridgeParameters for
        // event-bus targets; honor them. Keep the historical defaults as a fallback for
        // schedules created without those parameters.
        EventBridgeParameters ebp = target.getEventBridgeParameters();
        String source = ebp != null && ebp.getSource() != null && !ebp.getSource().isBlank()
                ? ebp.getSource() : "aws.scheduler";
        String detailType = ebp != null && ebp.getDetailType() != null && !ebp.getDetailType().isBlank()
                ? ebp.getDetailType() : "Scheduled Event";

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("EventBusName", busName);
        entry.put("Source", source);
        entry.put("DetailType", detailType);
        entry.put("Detail", asDetail(payload));
        return entry;
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
        return AwsArnUtils.regionOrDefault(arn, defaultRegion);
    }

    /** The region a schedule lives in, taken from its ARN. */
    static String regionOf(Schedule schedule) {
        return AwsArnUtils.regionOrDefault(schedule.getArn(), "us-east-1");
    }
}
