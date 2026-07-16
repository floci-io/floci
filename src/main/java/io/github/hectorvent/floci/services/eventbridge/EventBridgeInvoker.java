package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
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
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class EventBridgeInvoker {

    private static final Logger LOG = Logger.getLogger(EventBridgeInvoker.class);

    private final LambdaService lambdaService;
    private final SqsService sqsService;
    private final SnsService snsService;
    private final BatchService batchService;
    private final FirehoseService firehoseService;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @Inject
    public EventBridgeInvoker(LambdaService lambdaService,
                              SqsService sqsService,
                              SnsService snsService,
                              BatchService batchService,
                              FirehoseService firehoseService,
                              ObjectMapper objectMapper,
                              EmulatorConfig config) {
        this.lambdaService = lambdaService;
        this.sqsService = sqsService;
        this.snsService = snsService;
        this.batchService = batchService;
        this.firehoseService = firehoseService;
        this.objectMapper = objectMapper;
        this.baseUrl = config.baseUrl();
    }

    EventBridgeInvoker(LambdaService lambdaService,
                       SqsService sqsService,
                       SnsService snsService,
                       ObjectMapper objectMapper,
                       EmulatorConfig config) {
        this(lambdaService, sqsService, snsService, null, null, objectMapper, config);
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
