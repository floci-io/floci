package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.batch.BatchService;
import io.github.hectorvent.floci.services.ecs.EcsJsonHandler;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.ecs.model.ContainerOverride;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.eventbridge.model.EcsParameters;
import io.github.hectorvent.floci.services.eventbridge.model.InputTransformer;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.firehose.FirehoseService;
import io.github.hectorvent.floci.services.firehose.model.Record;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class EventBridgeInvoker {

    private static final Logger LOG = Logger.getLogger(EventBridgeInvoker.class);

    // AWS can't route an event from a sender bus on to a third bus; the second hop is dropped.
    private static final int MAX_BUS_TO_BUS_DEPTH = 1;
    private static final ThreadLocal<Integer> BUS_TO_BUS_DEPTH = ThreadLocal.withInitial(() -> 0);

    private final LambdaService lambdaService;
    private final SqsService sqsService;
    private final SnsService snsService;
    private final BatchService batchService;
    private final FirehoseService firehoseService;
    private final EventBridgeService eventBridgeService;
    private final EcsService ecsService;
    private final EcsJsonHandler ecsJsonHandler;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @Inject
    public EventBridgeInvoker(LambdaService lambdaService,
                              SqsService sqsService,
                              SnsService snsService,
                              BatchService batchService,
                              FirehoseService firehoseService,
                              EventBridgeService eventBridgeService,
                              EcsService ecsService,
                              EcsJsonHandler ecsJsonHandler,
                              RegionResolver regionResolver,
                              ObjectMapper objectMapper,
                              EmulatorConfig config) {
        this.lambdaService = lambdaService;
        this.sqsService = sqsService;
        this.snsService = snsService;
        this.batchService = batchService;
        this.firehoseService = firehoseService;
        this.eventBridgeService = eventBridgeService;
        this.ecsService = ecsService;
        this.ecsJsonHandler = ecsJsonHandler;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.baseUrl = config.baseUrl();
    }

    EventBridgeInvoker(LambdaService lambdaService,
                       SqsService sqsService,
                       SnsService snsService,
                       ObjectMapper objectMapper,
                       EmulatorConfig config) {
        this(lambdaService, sqsService, snsService,
                null /* batch */, null /* firehose */, null /* eventBridge */, null /* ecs */,
                null /* ecsJsonHandler */, null /* regionResolver */, objectMapper, config);
    }

    public void invokeTarget(Target target, String eventJson, String region) {
        String arn = target.getArn();
        String payload;
        if (target.getInput() != null) {
            payload = target.getInput();
        } else if (target.getInputPath() != null) {
            payload = applyInputPath(target.getInputPath(), eventJson);
        } else if (target.getInputTransformer() != null) {
            payload = applyInputTransformer(target.getInputTransformer(), eventJson);
        } else {
            payload = eventJson;
        }

        try {
            if (arn.contains(":lambda:") || arn.contains(":function:")) {
                lambdaService.invokeArn(arn, payload.getBytes(), InvocationType.Event);
                LOG.debugv("EventBridge delivered to Lambda: {0}", arn);
            } else if (arn.contains(":sqs:")) {
                String queueUrl = AwsArnUtils.arnToQueueUrl(arn, baseUrl);
                String messageGroupId = target.getSqsParameters() != null
                        ? target.getSqsParameters().getMessageGroupId() : null;
                sqsService.sendMessage(queueUrl, payload, 0, messageGroupId, null, region);
                LOG.debugv("EventBridge delivered to SQS: {0}", arn);
            } else if (arn.contains(":sns:")) {
                String topicRegion = extractRegionFromArn(arn, region);
                snsService.publish(arn, null, payload, "EventBridge", topicRegion);
                LOG.debugv("EventBridge delivered to SNS: {0}", arn);
            } else if (arn.contains(":batch:") && arn.contains(":job-queue/")) {
                if (batchService == null || target.getBatchParameters() == null) {
                    LOG.warnv("EventBridge Batch target missing Batch service or parameters: {0}", arn);
                    return;
                }
                String targetRegion = extractRegionFromArn(arn, region);
                batchService.submitFromEventBridge(
                        arn,
                        target.getBatchParameters().getJobDefinition(),
                        target.getBatchParameters().getJobName(),
                        parametersFromBatchPayload(payload),
                        target.getBatchParameters().getRetryStrategy(),
                        targetRegion
                );
                LOG.debugv("EventBridge delivered to Batch: {0}", arn);
            } else if (arn.contains(":ecs:") && arn.contains(":cluster/")) {
                if (ecsService == null || target.getEcsParameters() == null) {
                    LOG.warnv("EventBridge ECS target missing ECS service or EcsParameters: {0}", arn);
                    return;
                }
                String targetRegion = extractRegionFromArn(arn, region);
                boolean inputOverridden = target.getInput() != null
                        || target.getInputPath() != null
                        || target.getInputTransformer() != null;
                deliverToEcsRunTask(target, payload, inputOverridden, targetRegion);
                LOG.debugv("EventBridge delivered to ECS RunTask: {0}", arn);
            } else if (arn.contains(":firehose:") && arn.contains(":deliverystream/")) {
                if (firehoseService == null) {
                    LOG.warnv("EventBridge Firehose target missing Firehose service: {0}", arn);
                    return;
                }
                String streamName = arn.substring(
                        arn.indexOf(":deliverystream/") + ":deliverystream/".length());
                // AWS puts the (input-transformed) event JSON as the record Data verbatim,
                // without appending a newline; the delivery-side NDJSON flush handles separation.
                firehoseService.putRecord(streamName, new Record(payload.getBytes(StandardCharsets.UTF_8)));
                LOG.debugv("EventBridge delivered to Firehose: {0}", arn);
            } else if (arn.contains(":events:") && arn.contains(":event-bus/")) {
                if (eventBridgeService == null) {
                    LOG.warnv("EventBridge event-bus target missing EventBridge service: {0}", arn);
                    return;
                }
                // Relies on putEvents delivering targets synchronously.
                int depth = BUS_TO_BUS_DEPTH.get();
                if (depth >= MAX_BUS_TO_BUS_DEPTH) {
                    LOG.warnv("EventBridge bus-to-bus depth {0} exceeded at target {1}; dropping", depth, arn);
                    return;
                }
                String targetRegion = extractRegionFromArn(arn, region);
                // Input overrides shape only Detail; the rest of the entry comes from the original
                // event envelope, matching AWS event-bus target semantics.
                JsonNode envelope = objectMapper.readTree(eventJson);
                boolean inputOverridden = target.getInput() != null
                        || target.getInputPath() != null
                        || target.getInputTransformer() != null;
                JsonNode detailNode;
                if (inputOverridden) {
                    try {
                        detailNode = objectMapper.readTree(payload);
                    } catch (Exception e) {
                        LOG.warnv("EventBridge event-bus target {0} requires JSON Detail; dropping non-JSON input: {1}",
                                arn, e.getMessage());
                        return;
                    }
                } else {
                    detailNode = envelope.get("detail");
                    if (detailNode == null) {
                        detailNode = objectMapper.createObjectNode();
                    }
                }
                // readTree accepts any well-formed JSON value; AWS emits an event only when
                // Detail is an object, and both arms can produce a scalar or array.
                if (!detailNode.isObject()) {
                    LOG.warnv("EventBridge event-bus target {0} requires a JSON object Detail; dropping: {1}",
                            arn, detailNode);
                    return;
                }
                String detailBody = detailNode.toString();
                Map<String, Object> entry = new HashMap<>();
                // putEvents accepts a full event-bus ARN as EventBusName and validates it.
                entry.put("EventBusName", arn);
                entry.put("Source", envelope.path("source").asText(""));
                entry.put("DetailType", envelope.path("detail-type").asText(""));
                entry.put("Detail", detailBody);
                // AWS keeps the originating account/region; blank falls back inside putEvents.
                entry.put("Region", envelope.path("region").asText(""));
                entry.put("Account", envelope.path("account").asText(""));
                if (envelope.hasNonNull("resources") && envelope.get("resources").isArray()) {
                    entry.put("Resources", envelope.get("resources"));
                }
                // null routes through RequestContext, the only path carrying the legacy-key
                // fallback; Arn.accountId() is "" when the ARN omits the account segment.
                String targetAccount = AwsArnUtils.parse(arn).accountId();
                String currentAccount = regionResolver != null ? regionResolver.getAccountId() : null;
                String forwardAccount = targetAccount == null || targetAccount.isBlank()
                        || targetAccount.equals(currentAccount)
                        ? null
                        : targetAccount;
                BUS_TO_BUS_DEPTH.set(depth + 1);
                try {
                    var result = eventBridgeService.putEvents(List.of(entry), targetRegion, forwardAccount);
                    if (result.failedCount() > 0) {
                        LOG.warnv("EventBridge event-bus target {0} rejected event: {1}", arn, result.entries());
                    } else {
                        LOG.debugv("EventBridge delivered to EventBus: {0}", arn);
                    }
                } finally {
                    if (depth == 0) {
                        BUS_TO_BUS_DEPTH.remove();
                    } else {
                        BUS_TO_BUS_DEPTH.set(depth);
                    }
                }
            } else {
                LOG.warnv("EventBridge: unsupported target ARN type: {0}", arn);
            }
        } catch (Exception e) {
            LOG.warnv("EventBridge failed to deliver to target {0}: {1}", arn, e.getMessage());
        }
    }

    /**
     * AWS maps an ECS target's (input-transformed) payload 1-to-1 onto the RunTask
     * {@code TaskOverride} structure. Floci's override model only carries
     * {@code containerOverrides}, so that member is parsed out and passed through; a
     * payload that isn't the input-transformed shape (no Input/InputPath/InputTransformer
     * configured) or isn't parseable as JSON launches the task without overrides, matching
     * the documented no-override case rather than failing the whole delivery.
     */
    private void deliverToEcsRunTask(Target target, String payload, boolean inputOverridden, String region) {
        EcsParameters ecs = target.getEcsParameters();
        List<ContainerOverride> containerOverrides = List.of();
        if (inputOverridden) {
            try {
                containerOverrides = ecsJsonHandler.parseContainerOverrides(
                        objectMapper.readTree(payload).path("containerOverrides"));
            } catch (Exception e) {
                LOG.warnv("EventBridge ECS target {0} InputTransformer output is not a valid TaskOverride, "
                        + "launching without container overrides: {1}", target.getArn(), e.getMessage());
            }
        }
        ecsService.runTask(
                target.getArn(),
                ecs.getTaskDefinitionArn(),
                ecs.getTaskCount() != null ? ecs.getTaskCount() : 1,
                parseLaunchType(ecs.getLaunchType()),
                null,
                ecs.getGroup() != null ? ecs.getGroup() : "eventbridge",
                containerOverrides,
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
            LOG.warnv("EventBridge: unsupported ECS LaunchType: {0}", launchType);
            return null;
        }
    }

    private static io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration ecsNetworkConfiguration(
            io.github.hectorvent.floci.services.eventbridge.model.NetworkConfiguration source) {
        if (source == null || source.getAwsvpcConfiguration() == null) {
            return null;
        }
        io.github.hectorvent.floci.services.eventbridge.model.AwsVpcConfiguration sourceVpc = source.getAwsvpcConfiguration();
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

    String applyInputPath(String inputPath, String eventJson) {
        if (inputPath == null || "$".equals(inputPath)) {
            return eventJson;
        }
        String extracted = extractJsonPath(inputPath, eventJson);
        return extracted != null ? extracted : eventJson;
    }

    String applyInputTransformer(InputTransformer transformer, String eventJson) {
        String template = transformer.getInputTemplate();
        if (template == null) {
            return eventJson;
        }
        Map<String, JsonNode> resolved = new LinkedHashMap<>();
        for (var e : transformer.getInputPathsMap().entrySet()) {
            resolved.put(e.getKey(), extractNode(e.getValue(), eventJson));
        }
        StringBuilder out = new StringBuilder(template.length() + 32);
        boolean inString = false;
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c == '<') {
                int close = template.indexOf('>', i + 1);
                if (close >= 0) {
                    String name = template.substring(i + 1, close);
                    if (resolved.containsKey(name)) {
                        JsonNode node = resolved.get(name);
                        out.append(inString ? rawValue(node) : jsonValue(node));
                        i = close;
                        continue;
                    }
                }
                out.append(c);
                continue;
            }
            if (c == '"' && !isEscaped(template, i)) {
                inString = !inString;
            }
            out.append(c);
        }
        return out.toString();
    }

    // JSON representation for a value-position placeholder: strings quoted+escaped, objects/arrays/
    // numbers/bools literal JSON, missing/null empty.
    private String jsonValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        return node.toString();
    }

    // Raw value for a placeholder inside a quoted string: JSON-escaped, no surrounding quotes.
    private String rawValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        String raw = node.isValueNode() ? node.asText() : node.toString();
        try {
            String quoted = objectMapper.writeValueAsString(raw); // "escaped"
            return quoted.substring(1, quoted.length() - 1);       // strip surrounding quotes
        } catch (Exception e) {
            LOG.warnv("Failed to JSON-escape raw template value ''{0}'': {1}", raw, e.getMessage());
            return raw;
        }
    }

    private static boolean isEscaped(String s, int i) {
        int backslashes = 0;
        for (int j = i - 1; j >= 0 && s.charAt(j) == '\\'; j--) {
            backslashes++;
        }
        return (backslashes & 1) == 1;
    }

    JsonNode extractNode(String jsonPath, String eventJson) {
        if (jsonPath == null || eventJson == null) {
            return MissingNode.getInstance();
        }
        try {
            return objectMapper.readTree(eventJson).at(toPointer(jsonPath));
        } catch (Exception e) {
            LOG.warnv("Failed to extract JSONPath {0}: {1}", jsonPath, e.getMessage());
            return MissingNode.getInstance();
        }
    }

    private static String toPointer(String jsonPath) {
        return (jsonPath.startsWith("$") ? jsonPath.substring(1) : jsonPath).replace('.', '/');
    }

    String extractJsonPath(String jsonPath, String eventJson) {
        JsonNode node = extractNode(jsonPath, eventJson);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    private Map<String, String> parametersFromBatchPayload(String payload) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (payload == null || payload.isBlank()) {
            return parameters;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode parametersNode = node.path("Parameters");
            if (parametersNode.isObject()) {
                parametersNode.fields().forEachRemaining(entry -> {
                    JsonNode value = entry.getValue();
                    parameters.put(entry.getKey(), value.isTextual() ? value.asText() : value.toString());
                });
            }
        } catch (Exception e) {
            LOG.debugv("EventBridge Batch payload is not a JSON object with Parameters: {0}", e.getMessage());
        }
        return parameters;
    }

    private static String extractRegionFromArn(String arn, String defaultRegion) {
        return AwsArnUtils.regionOrDefault(arn, defaultRegion);
    }
}
