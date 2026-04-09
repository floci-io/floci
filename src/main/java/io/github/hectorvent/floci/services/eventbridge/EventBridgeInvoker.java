package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.eventbridge.model.InputTransformer;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class EventBridgeInvoker {

    private static final Logger LOG = Logger.getLogger(EventBridgeInvoker.class);

    private final LambdaService lambdaService;
    private final SqsService sqsService;
    private final SnsService snsService;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @Inject
    public EventBridgeInvoker(LambdaService lambdaService,
                              SqsService sqsService,
                              SnsService snsService,
                              ObjectMapper objectMapper,
                              EmulatorConfig config) {
        this.lambdaService = lambdaService;
        this.sqsService = sqsService;
        this.snsService = snsService;
        this.objectMapper = objectMapper;
        this.baseUrl = config.baseUrl();
    }

    public void invokeTarget(Target target, String eventJson, String region) {
        String arn = target.getArn();
        String payload;
        if (target.getInput() != null) {
            payload = target.getInput();
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
                sqsService.sendMessage(queueUrl, payload, 0);
                LOG.debugv("EventBridge delivered to SQS: {0}", arn);
            } else if (arn.contains(":sns:")) {
                String topicRegion = extractRegionFromArn(arn, region);
                snsService.publish(arn, null, payload, "EventBridge", topicRegion);
                LOG.debugv("EventBridge delivered to SNS: {0}", arn);
            } else {
                LOG.warnv("EventBridge: unsupported target ARN type: {0}", arn);
            }
        } catch (Exception e) {
            LOG.warnv("EventBridge failed to deliver to target {0}: {1}", arn, e.getMessage());
        }
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

    private static String extractRegionFromArn(String arn, String defaultRegion) {
        String[] parts = arn.split(":");
        return parts.length >= 4 && !parts[3].isEmpty() ? parts[3] : defaultRegion;
    }
}
