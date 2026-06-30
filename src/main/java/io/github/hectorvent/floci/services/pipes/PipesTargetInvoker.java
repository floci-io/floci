package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.pipes.model.Pipe;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.stepfunctions.StepFunctionsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class PipesTargetInvoker {

    private static final Logger LOG = Logger.getLogger(PipesTargetInvoker.class);
    private static final Pattern TEMPLATE_PLACEHOLDER = Pattern.compile("<(\\$[^>]+)>");
    private static final Pattern ARRAY_INDEX = Pattern.compile("^(.+?)\\[(\\d+)]$");

    private final LambdaService lambdaService;
    private final SqsService sqsService;
    private final SnsService snsService;
    private final EventBridgeService eventBridgeService;
    private final StepFunctionsService stepFunctionsService;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @Inject
    public PipesTargetInvoker(LambdaService lambdaService,
                              SqsService sqsService,
                              SnsService snsService,
                              EventBridgeService eventBridgeService,
                              StepFunctionsService stepFunctionsService,
                              ObjectMapper objectMapper,
                              EmulatorConfig config) {
        this.lambdaService = lambdaService;
        this.sqsService = sqsService;
        this.snsService = snsService;
        this.eventBridgeService = eventBridgeService;
        this.stepFunctionsService = stepFunctionsService;
        this.objectMapper = objectMapper;
        this.baseUrl = config.effectiveBaseUrl();
    }

    public void invoke(Pipe pipe, String payload, String region) {
        String targetArn = pipe.getTarget();

        JsonNode tp = pipe.getTargetParameters();
        if (tp != null && tp.has("InputTemplate")) {
            payload = applyInputTemplate(tp.get("InputTemplate").asText(), payload);
        }

        if (targetArn.contains(":lambda:") || targetArn.contains(":function:")) {
            invokeLambda(targetArn, payload, region);
        } else if (targetArn.contains(":sqs:")) {
            invokeSqs(targetArn, payload, region);
        } else if (targetArn.contains(":sns:")) {
            invokeSns(targetArn, payload, region);
        } else if (targetArn.contains(":events:")) {
            invokeEventBridge(targetArn, payload, region, tp);
        } else if (targetArn.contains(":states:")) {
            invokeStepFunctions(targetArn, payload, region);
        } else {
            LOG.warnv("Pipe {0}: unsupported target ARN type: {1}", pipe.getName(), targetArn);
        }
    }

    /**
     * Applies a Pipe's enrichment step (AWS EventBridge Pipes: source → filter → ENRICHMENT → target).
     * The enrichment is invoked with the filtered events and its response becomes the target input.
     * Only Lambda enrichments are supported (DPS uses one). Returns the response payload to forward,
     * or {@code null} when the enrichment returns an empty response — AWS skips the target in that case.
     * Returns {@code payload} unchanged when no enrichment is configured.
     */
    public String applyEnrichment(Pipe pipe, String payload, String region) {
        String enrichment = pipe.getEnrichment();
        if (enrichment == null || enrichment.isBlank()) {
            return payload;
        }
        JsonNode ep = pipe.getEnrichmentParameters();
        if (ep != null && ep.path("InputTemplate").isTextual()) {
            payload = applyInputTemplate(ep.get("InputTemplate").asText(), payload);
        }
        if (enrichment.contains(":lambda:") || enrichment.contains(":function:")) {
            String fnName = lambdaFunctionName(enrichment);
            String fnRegion = extractRegionFromArn(enrichment, region);
            InvokeResult result = lambdaService.invoke(
                    fnRegion, fnName, payload.getBytes(StandardCharsets.UTF_8), InvocationType.RequestResponse);
            if (result.getFunctionError() != null) {
                throw new AwsException("InternalException",
                        "Pipe enrichment Lambda " + fnName + " failed: " + result.getFunctionError(), 500);
            }
            byte[] out = result.getPayload();
            String resp = out == null ? "" : new String(out, StandardCharsets.UTF_8).trim();
            if (resp.isEmpty() || "null".equals(resp)) {
                return null;
            }
            LOG.debugv("Pipe {0}: enrichment {1} produced target payload", pipe.getName(), enrichment);
            return resp;
        }
        LOG.warnv("Pipe {0}: unsupported enrichment ARN type (only Lambda supported): {1}",
                pipe.getName(), enrichment);
        return payload;
    }

    private void invokeLambda(String arn, String payload, String region) {
        String fnName = lambdaFunctionName(arn);
        String fnRegion = extractRegionFromArn(arn, region);
        InvokeResult result = lambdaService.invoke(
                fnRegion, fnName, payload.getBytes(StandardCharsets.UTF_8), InvocationType.RequestResponse);
        // A target Lambda that returns a FunctionError is a failed delivery, not a success — surface it
        // so the caller routes the source record to the DLQ instead of silently consuming it.
        if (result.getFunctionError() != null) {
            throw new AwsException("InternalException",
                    "Pipe target Lambda " + fnName + " returned an error: " + result.getFunctionError(), 500);
        }
        LOG.debugv("Pipe delivered to Lambda: {0}", arn);
    }

    /**
     * Extracts a Lambda function name from a bare name, a name with a version/alias qualifier
     * (e.g. "name:$LATEST"), or a full/partial function ARN
     * ("arn:aws:lambda:region:acct:function:NAME[:qualifier]"). Taking the last ':'-segment is
     * wrong for a qualified ARN — it yields the qualifier instead of the name.
     */
    static String lambdaFunctionName(String ref) {
        if (ref == null) {
            return null;
        }
        String fn = ref;
        int fi = ref.indexOf(":function:");
        if (fi >= 0) {
            fn = ref.substring(fi + ":function:".length());
        }
        int colon = fn.indexOf(':');
        if (colon >= 0) {
            fn = fn.substring(0, colon);
        }
        return fn;
    }

    private void invokeSqs(String arn, String payload, String region) {
        String queueUrl = AwsArnUtils.arnToQueueUrl(arn, baseUrl);
        sqsService.sendMessage(queueUrl, payload, 0, region);
        LOG.debugv("Pipe delivered to SQS: {0}", arn);
    }

    private void invokeSns(String arn, String payload, String region) {
        String topicRegion = extractRegionFromArn(arn, region);
        snsService.publish(arn, null, payload, "Pipes", topicRegion);
        LOG.debugv("Pipe delivered to SNS: {0}", arn);
    }

    private void invokeEventBridge(String arn, String payload, String region, JsonNode tp) {
        String busName = arn.substring(arn.lastIndexOf('/') + 1);
        String ebRegion = extractRegionFromArn(arn, region);
        String source = "aws.pipes";
        String detailType = "PipeForwarded";
        if (tp != null) {
            JsonNode ebParams = tp.path("EventBridgeEventBusParameters");
            if (ebParams.has("Source")) {
                source = ebParams.get("Source").asText();
            }
            if (ebParams.has("DetailType")) {
                detailType = ebParams.get("DetailType").asText();
            }
        }
        Map<String, Object> entry = Map.of(
                "EventBusName", busName,
                "Source", source,
                "DetailType", detailType,
                "Detail", payload
        );
        eventBridgeService.putEvents(List.of(entry), ebRegion);
        LOG.debugv("Pipe delivered to EventBridge: {0}", arn);
    }

    private void invokeStepFunctions(String arn, String payload, String region) {
        String sfnRegion = extractRegionFromArn(arn, region);
        String executionName = "pipes-" + UUID.randomUUID();
        stepFunctionsService.startExecution(arn, executionName, payload, sfnRegion);
        LOG.debugv("Pipe delivered to Step Functions: {0}", arn);
    }

    String applyInputTemplate(String template, String payload) {
        Matcher m = TEMPLATE_PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String jsonPath = m.group(1);
            String value = extractJsonPath(jsonPath, payload);
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : ""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    String extractJsonPath(String jsonPath, String json) {
        if (jsonPath == null || json == null) {
            return null;
        }
        try {
            String path = jsonPath.startsWith("$.") ? jsonPath.substring(2)
                    : jsonPath.startsWith("$") ? jsonPath.substring(1)
                    : jsonPath;
            // Split into segments on dots, but expand array indices into separate segments
            // e.g. "Records[0].body" -> ["Records", "0", "body"]
            String[] rawSegments = path.split("\\.");
            List<String> segments = new ArrayList<>();
            for (String seg : rawSegments) {
                Matcher am = ARRAY_INDEX.matcher(seg);
                if (am.matches()) {
                    segments.add(am.group(1));
                    segments.add(am.group(2));
                } else if (!seg.isEmpty()) {
                    segments.add(seg);
                }
            }

            JsonNode current = objectMapper.readTree(json);
            for (String segment : segments) {
                // AWS Pipes auto-parses string fields containing valid JSON
                if (current.isTextual()) {
                    try {
                        current = objectMapper.readTree(current.asText());
                    } catch (Exception ignored) {
                        return null;
                    }
                }
                if (current.isArray()) {
                    current = current.path(Integer.parseInt(segment));
                } else {
                    current = current.path(segment);
                }
                if (current.isMissingNode() || current.isNull()) {
                    return null;
                }
            }
            if (current.isValueNode()) {
                return objectMapper.writeValueAsString(current);
            }
            return current.toString();
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
