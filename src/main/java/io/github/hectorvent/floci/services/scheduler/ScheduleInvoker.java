package io.github.hectorvent.floci.services.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftDataService;
import io.github.hectorvent.floci.services.scheduler.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.scheduler.model.EcsParameters;
import io.github.hectorvent.floci.services.scheduler.model.EventBridgeParameters;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.scheduler.model.Target;
import io.github.hectorvent.floci.services.sns.SnsMessageAttributes;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import io.github.hectorvent.floci.services.stepfunctions.StepFunctionsService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Delivers an EventBridge Scheduler target invocation to the underlying service.
 * Supports templated SQS, Lambda, SNS, Step Functions, and EventBridge PutEvents targets, plus
 * universal targets ({@code arn:aws:scheduler:::aws-sdk:<service>:<action>}) for
 * {@code sns:publish}, {@code sqs:sendMessage}, {@code redshiftdata:executeStatement} and
 * {@code redshiftdata:batchExecuteStatement}. Mirrors the subset handled by
 * {@code EventBridgeInvoker} but using Scheduler's {@link Target} model (raw
 * {@code input} string, no JSONPath/template).
 */
@ApplicationScoped
public class ScheduleInvoker {

    private static final Logger LOG = Logger.getLogger(ScheduleInvoker.class);

    static final String CONTEXT_SCHEDULE_ARN = "<aws.scheduler.schedule-arn>";
    static final String CONTEXT_SCHEDULED_TIME = "<aws.scheduler.scheduled-time>";
    static final String CONTEXT_EXECUTION_ID = "<aws.scheduler.execution-id>";
    static final String CONTEXT_ATTEMPT_NUMBER = "<aws.scheduler.attempt-number>";

    private final SqsService sqsService;
    private final LambdaService lambdaService;
    private final SnsService snsService;
    private final EventBridgeService eventBridgeService;
    private final EcsService ecsService;
    private final StepFunctionsService stepFunctionsService;
    private final RedshiftDataService redshiftDataService;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String defaultRegion;
    private final ServiceRegistry serviceRegistry;
    private final Executor redshiftDataExecutor;

    @Inject
    public ScheduleInvoker(SqsService sqsService,
                           LambdaService lambdaService,
                           SnsService snsService,
                           EventBridgeService eventBridgeService,
                           EcsService ecsService,
                           StepFunctionsService stepFunctionsService,
                           RedshiftDataService redshiftDataService,
                           ObjectMapper objectMapper,
                           EmulatorConfig config,
                           ServiceRegistry serviceRegistry) {
        this(sqsService, lambdaService, snsService, eventBridgeService, ecsService, stepFunctionsService,
                redshiftDataService, objectMapper, config, serviceRegistry, newRedshiftDataExecutor());
    }

    ScheduleInvoker(SqsService sqsService,
                    LambdaService lambdaService,
                    SnsService snsService,
                    EventBridgeService eventBridgeService,
                    EcsService ecsService,
                    StepFunctionsService stepFunctionsService,
                    RedshiftDataService redshiftDataService,
                    ObjectMapper objectMapper,
                    EmulatorConfig config,
                    ServiceRegistry serviceRegistry,
                    Executor redshiftDataExecutor) {
        this.sqsService = sqsService;
        this.lambdaService = lambdaService;
        this.snsService = snsService;
        this.eventBridgeService = eventBridgeService;
        this.ecsService = ecsService;
        this.stepFunctionsService = stepFunctionsService;
        this.redshiftDataService = redshiftDataService;
        this.objectMapper = objectMapper;
        this.baseUrl = config.baseUrl();
        this.defaultRegion = config.defaultRegion();
        this.serviceRegistry = serviceRegistry;
        this.redshiftDataExecutor = redshiftDataExecutor;
    }

    private static ExecutorService newRedshiftDataExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "scheduler-redshift-data");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        if (redshiftDataExecutor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }

    /**
     * Delivers the first attempt of one occurrence of {@code schedule}, under a fresh execution id.
     * See {@link #invoke(Schedule, Instant, String, int)}.
     */
    public String invoke(Schedule schedule, Instant scheduledAt) {
        return invoke(schedule, scheduledAt, newExecutionId(), 1);
    }

    /**
     * Delivers one attempt of the occurrence of {@code schedule} scheduled at {@code scheduledAt}
     * to its target and returns the JSON request that was sent (see {@link #materializeRequest}).
     * {@code executionId} and the 1-based {@code attemptNumber} fill the context attributes of
     * the target's {@code Input}.
     */
    public String invoke(Schedule schedule, Instant scheduledAt, String executionId, int attemptNumber) {
        Target target = schedule.getTarget();
        if (target == null || target.getArn() == null) {
            return "{}";
        }
        String arn = target.getArn();
        String region = regionOf(schedule, defaultRegion);
        String input = contextualInput(schedule, scheduledAt, executionId, attemptNumber);
        if (isUniversalTarget(arn)) {
            invokeUniversalTarget(arn.substring(arn.indexOf(":aws-sdk:") + ":aws-sdk:".length()),
                    input, region, schedule.getAccountId());
            return universalTargetRequest(input);
        }

        TargetKind kind = TargetKind.of(target);
        String payload = templatedPayload(schedule, input, scheduledAt);
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
        return materializeRequest(schedule, scheduledAt, newExecutionId(), 1);
    }

    /** The request {@link #invoke(Schedule, Instant, String, int)} sends for the same attempt. */
    public String materializeRequest(Schedule schedule, Instant scheduledAt, String executionId,
                                     int attemptNumber) {
        Target target = schedule.getTarget();
        if (target == null || target.getArn() == null) {
            return "{}";
        }
        String input = contextualInput(schedule, scheduledAt, executionId, attemptNumber);
        if (isUniversalTarget(target.getArn())) {
            return universalTargetRequest(input);
        }
        return templatedTargetRequest(target, TargetKind.of(target),
                templatedPayload(schedule, input, scheduledAt));
    }

    static String formatScheduledTime(Instant scheduledAt) {
        return scheduledAt.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /** A Scheduler-style execution id: 16 lowercase hex characters. */
    public static String newExecutionId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * The target's {@code Input} with the Scheduler context attributes replaced: the schedule ARN,
     * the scheduled time as UTC seconds with a {@code Z} suffix, the execution id and the 1-based
     * attempt number. Null when the target has no {@code Input}, so the default event is untouched.
     */
    static String contextualInput(Schedule schedule, Instant scheduledAt, String executionId,
                                  int attemptNumber) {
        String input = schedule.getTarget().getInput();
        if (input == null || input.indexOf('<') < 0) {
            return input;
        }
        String scheduledTime = scheduledAt == null
                ? ""
                : formatScheduledTime(scheduledAt);
        return input
                .replace(CONTEXT_SCHEDULE_ARN, schedule.getArn() == null ? "" : schedule.getArn())
                .replace(CONTEXT_SCHEDULED_TIME, scheduledTime)
                .replace(CONTEXT_EXECUTION_ID, executionId == null ? "" : executionId)
                .replace(CONTEXT_ATTEMPT_NUMBER, String.valueOf(attemptNumber));
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

    private String universalTargetRequest(String input) {
        return validJsonOrString(input != null ? input : "{}");
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
            AwsVpcConfiguration vpc = ecs.getNetworkConfiguration().getAwsvpcConfiguration();
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
    private String templatedPayload(Schedule schedule, String input, Instant scheduledAt) {
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
        event.put("time", formatScheduledTime(scheduledAt));
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
        AwsVpcConfiguration sourceVpc = source.getAwsvpcConfiguration();
        // The ECS model's AwsVpcConfiguration clashes with the scheduler model's, which is imported.
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
     * as unsupported. {@code scheduleAccountId} scopes the Redshift Data calls, whose cluster and
     * statement storage is account-aware and the dispatcher thread carries no request account.
     */
    private void invokeUniversalTarget(String serviceAction, String input, String region,
                                       String scheduleAccountId) {
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
            case "redshiftdata:executeStatement" -> {
                requireRedshiftData();
                submitRedshiftData(scheduleAccountId, region, () -> redshiftDataService.submitStatement(params, region));
                LOG.debugv("Scheduler submitted to the Redshift Data API (universal target): {0}", serviceAction);
            }
            case "redshiftdata:batchExecuteStatement" -> {
                requireRedshiftData();
                submitRedshiftData(scheduleAccountId, region, () -> redshiftDataService.submitBatch(params, region));
                LOG.debugv("Scheduler submitted to the Redshift Data API (universal target): {0}", serviceAction);
            }
            default -> throw new UnsupportedOperationException(
                    "Scheduler: unsupported universal target action: " + serviceAction);
        }
    }

    private void requireRedshiftData() {
        if (!serviceRegistry.isServiceEnabled("redshift-data")) {
            throw new AwsException("ServiceNotAvailableException", "Service redshift-data is not enabled.", 400);
        }
    }

    /**
     * The Data API is asynchronous: AWS returns once the statement is submitted. The request check
     * runs here, so a rejected request still fails the occurrence and is retried, while the SQL runs
     * on its own thread so a slow statement cannot hold up the single dispatcher thread. Both halves
     * run as the schedule's account, which neither thread carries.
     */
    private void submitRedshiftData(String scheduleAccountId, String region, Supplier<Runnable> submit) {
        Runnable statement = RequestScopes.callAs(scheduleAccountId, region, submit);
        redshiftDataExecutor.execute(() -> {
            try {
                RequestScopes.runAs(scheduleAccountId, region, statement);
            } catch (RuntimeException e) {
                LOG.warnv("Scheduled Redshift Data statement failed: {0}", e.getMessage());
            }
        });
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
            String binaryValueText = valueNode.path("BinaryValue").asText(null);
            if (dataType == null) {
                return;
            }
            if (binaryValueText != null) {
                // Unlike the SQS JSON protocol, AWS does not base64-decode BinaryValue in a universal
                // target Input: the attribute bytes are the UTF-8 encoding of the JSON string, and a
                // base64 string is delivered as its own text.
                byte[] binaryValue = binaryValueText.getBytes(StandardCharsets.UTF_8);
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

    /** The region a schedule lives in, taken from its ARN; {@code defaultRegion} answers for one without. */
    static String regionOf(Schedule schedule, String defaultRegion) {
        return AwsArnUtils.regionOrDefault(schedule.getArn(), defaultRegion);
    }
}
