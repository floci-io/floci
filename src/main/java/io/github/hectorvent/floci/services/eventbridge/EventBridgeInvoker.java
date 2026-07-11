package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.batch.BatchService;
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

    private static final int MAX_BUS_TO_BUS_DEPTH = 5;
    private static final ThreadLocal<Integer> BUS_TO_BUS_DEPTH = ThreadLocal.withInitial(() -> 0);

    private final LambdaService lambdaService;
    private final SqsService sqsService;
    private final SnsService snsService;
    private final BatchService batchService;
    private final FirehoseService firehoseService;
    private final EventBridgeService eventBridgeService;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @Inject
    public EventBridgeInvoker(LambdaService lambdaService,
                              SqsService sqsService,
                              SnsService snsService,
                              BatchService batchService,
                              FirehoseService firehoseService,
                              EventBridgeService eventBridgeService,
                              ObjectMapper objectMapper,
                              EmulatorConfig config) {
        this.lambdaService = lambdaService;
        this.sqsService = sqsService;
        this.snsService = snsService;
        this.batchService = batchService;
        this.firehoseService = firehoseService;
        this.eventBridgeService = eventBridgeService;
        this.objectMapper = objectMapper;
        this.baseUrl = config.baseUrl();
    }

    EventBridgeInvoker(LambdaService lambdaService,
                       SqsService sqsService,
                       SnsService snsService,
                       ObjectMapper objectMapper,
                       EmulatorConfig config) {
        this(lambdaService, sqsService, snsService, null, null, null, objectMapper, config);
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
                String fnName = arn.substring(arn.lastIndexOf(':') + 1);
                String fnRegion = extractRegionFromArn(arn, region);
                lambdaService.invoke(fnRegion, fnName, payload.getBytes(), InvocationType.Event);
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
                // Fixed ceiling breaks bus-to-bus cycles; relies on putEvents delivering targets synchronously.
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
                String detailBody;
                if (inputOverridden) {
                    try {
                        objectMapper.readTree(payload);
                    } catch (Exception e) {
                        LOG.warnv("EventBridge event-bus target {0} requires JSON Detail; dropping non-JSON input: {1}",
                                arn, e.getMessage());
                        return;
                    }
                    detailBody = payload;
                } else {
                    JsonNode detail = envelope.get("detail");
                    detailBody = detail != null ? detail.toString() : "{}";
                }
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
                BUS_TO_BUS_DEPTH.set(depth + 1);
                try {
                    var result = eventBridgeService.putEvents(List.of(entry), targetRegion);
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
        String result = template;
        for (var e : transformer.getInputPathsMap().entrySet()) {
            String value = extractJsonPath(e.getValue(), eventJson);
            result = result.replace("<" + e.getKey() + ">", value != null ? value : "");
        }
        return result;
    }

    String extractJsonPath(String jsonPath, String eventJson) {
        if (jsonPath == null || eventJson == null) {
            return null;
        }
        try {
            String pointer = (jsonPath.startsWith("$") ? jsonPath.substring(1) : jsonPath)
                    .replace('.', '/');
            JsonNode node = objectMapper.readTree(eventJson).at(pointer);
            if (node.isMissingNode() || node.isNull()) {
                return null;
            }
            return node.isTextual() ? node.asText() : node.toString();
        } catch (Exception e) {
            LOG.warnv("Failed to extract JSONPath {0}: {1}", jsonPath, e.getMessage());
            return null;
        }
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
