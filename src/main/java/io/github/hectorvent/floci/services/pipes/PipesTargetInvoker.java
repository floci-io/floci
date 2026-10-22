package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
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
import java.util.stream.Collectors;

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
    private final KinesisService kinesisService;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @Inject
    public PipesTargetInvoker(LambdaService lambdaService,
                              SqsService sqsService,
                              SnsService snsService,
                              EventBridgeService eventBridgeService,
                              StepFunctionsService stepFunctionsService,
                              KinesisService kinesisService,
                              ObjectMapper objectMapper,
                              EmulatorConfig config) {
        this.lambdaService = lambdaService;
        this.sqsService = sqsService;
        this.snsService = snsService;
        this.eventBridgeService = eventBridgeService;
        this.stepFunctionsService = stepFunctionsService;
        this.kinesisService = kinesisService;
        this.objectMapper = objectMapper;
        this.baseUrl = config.effectiveBaseUrl();
    }

    /**
     * Whether the target takes a whole batch in one request as a bare JSON array: Lambda and Step
     * Functions have no batch API, so AWS sends them the batch array, even for a batch of one. Every
     * other target is sent one request per record.
     */
    static boolean receivesBatchArray(String targetArn) {
        return targetArn.contains(":lambda:") || targetArn.contains(":function:") || targetArn.contains(":states:");
    }

    /**
     * Delivers {@code payload} to the pipe's target: the batch array for a target that
     * {@link #receivesBatchArray receives one}, a single record for any other.
     */
    public void invoke(Pipe pipe, String payload, String region) {
        String targetArn = pipe.getTarget();
        String event = payload;

        JsonNode tp = pipe.getTargetParameters();
        if (tp != null && tp.has("InputTemplate")) {
            String template = tp.get("InputTemplate").asText();
            payload = receivesBatchArray(targetArn)
                    ? applyInputTemplateToEachRecord(template, payload)
                    : applyInputTemplate(template, payload);
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
        } else if (targetArn.contains(":kinesis:")) {
            invokeKinesis(pipe, targetArn, event, payload, region);
        } else {
            // AWS accepts these targets at CreatePipe, so the pipe exists; failing the delivery sends
            // the batch to the DLQ or leaves it for a retry instead of consuming it undelivered.
            throw new AwsException("InternalException",
                    "Pipe " + pipe.getName() + " uses a target type that is not emulated: " + targetArn, 500);
        }
    }

    /**
     * Applies a Pipe's enrichment step (AWS EventBridge Pipes: source → filter → ENRICHMENT → target).
     * The enrichment is invoked with the filtered events and its response becomes the target input.
     * Only Lambda enrichments are emulated. Returns the response payload to forward, or {@code null}
     * when the enrichment returns an empty response — AWS skips the target in that case.
     * Returns {@code payload} unchanged when no enrichment is configured.
     */
    public String applyEnrichment(Pipe pipe, String payload, String region) {
        String enrichment = pipe.getEnrichment();
        if (enrichment == null || enrichment.isBlank()) {
            return payload;
        }
        JsonNode ep = pipe.getEnrichmentParameters();
        if (ep != null && ep.path("InputTemplate").isTextual()) {
            payload = applyInputTemplateToEachRecord(ep.get("InputTemplate").asText(), payload);
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
            // AWS also skips the target when the enrichment returns an empty object {} or empty
            // array []; only a non-empty array such as [{}] invokes the target (with an
            // empty-payload element). Parse so whitespace variants ({ }, [ ]) are handled too.
            try {
                JsonNode node = objectMapper.readTree(resp);
                if ((node.isObject() || node.isArray()) && node.isEmpty()) {
                    return null;
                }
            } catch (JsonProcessingException e) {
                // Non-JSON textual enrichment response — forward as-is.
            }
            LOG.debugv("Pipe {0}: enrichment {1} produced target payload", pipe.getName(), enrichment);
            return resp;
        }
        // API destination, API Gateway and Step Functions Express enrichments are valid on AWS but
        // not emulated here. Fail rather than silently delivering the unenriched payload — the
        // caller routes the batch to the pipe's dead-letter queue.
        throw new AwsException("InternalException",
                "Pipe " + pipe.getName() + " uses an unsupported enrichment type (only Lambda is "
                        + "emulated): " + enrichment, 500);
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
     * Function reference out of a bare name or a full/partial function ARN
     * ("arn:aws:lambda:region:acct:function:NAME[:qualifier]"). The qualifier stays, so Lambda
     * invokes the version or alias it names.
     */
    static String lambdaFunctionName(String ref) {
        if (ref == null) {
            return null;
        }
        int fi = ref.indexOf(":function:");
        return fi >= 0 ? ref.substring(fi + ":function:".length()) : ref;
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

        EventBridgeService.PutEventsResult result = eventBridgeService.putEvents(List.of(entry), ebRegion);

        if (result != null && result.failedCount() > 0) {
            String details = result.entries().stream()
                    .filter(e -> e.get("ErrorCode") != null)
                    .map(e -> e.get("ErrorCode") + ": " + e.get("ErrorMessage"))
                    .collect(Collectors.joining("; "));
            throw new RuntimeException(
                    "EventBridge PutEvents rejected " + result.failedCount()
                            + " entry(ies): " + details);
        }
        LOG.debugv("Pipe delivered to EventBridge: {0}", arn);
    }

    private void invokeStepFunctions(String arn, String payload, String region) {
        String sfnRegion = extractRegionFromArn(arn, region);
        String executionName = "pipes-" + UUID.randomUUID();
        stepFunctionsService.startExecution(arn, executionName, payload, sfnRegion);
        LOG.debugv("Pipe delivered to Step Functions: {0}", arn);
    }

    /**
     * Puts one record on the target stream under {@code KinesisStreamParameters.PartitionKey}, which
     * AWS requires and which may be a JSON path into the event.
     */
    private void invokeKinesis(Pipe pipe, String arn, String event, String payload, String region) {
        JsonNode tp = pipe.getTargetParameters();
        String partitionKeyParameter = tp == null ? null
                : tp.path("KinesisStreamParameters").path("PartitionKey").asText(null);
        String partitionKey = resolveDynamicParameter(partitionKeyParameter, event);
        if (partitionKey == null || partitionKey.isEmpty()) {
            throw new AwsException("ValidationException", "Pipe " + pipe.getName()
                    + " needs a TargetParameters.KinesisStreamParameters.PartitionKey that resolves to a value "
                    + "for its Kinesis stream target, got " + partitionKeyParameter, 400);
        }
        String streamName = arn.substring(arn.lastIndexOf('/') + 1);
        kinesisService.putRecordForAccount(AwsArnUtils.accountOrDefault(arn, null), streamName,
                payload.getBytes(StandardCharsets.UTF_8), partitionKey, extractRegionFromArn(arn, region));
        LOG.debugv("Pipe delivered to Kinesis: {0}", arn);
    }

    /**
     * A target parameter's value. A JSON path is read from the event the target is invoked for, before
     * any input transformation, as AWS resolves dynamic path parameters; any other value is literal. A
     * path that matches nothing resolves to null.
     */
    String resolveDynamicParameter(String value, String event) {
        if (value == null || !value.startsWith("$.")) {
            return value;
        }
        JsonNode node = extractJsonPathNode(value, event);
        if (node == null) {
            return null;
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }

    /**
     * Applies an input template to each record of a batch array, as AWS transforms a batch record by
     * record rather than as a whole. A template output that is not JSON becomes a string record.
     */
    private String applyInputTemplateToEachRecord(String template, String batch) {
        JsonNode records;
        try {
            records = objectMapper.readTree(batch);
        } catch (JsonProcessingException e) {
            LOG.debugv("Pipe batch is not JSON, applying the input template to it whole: {0}", e.getOriginalMessage());
            return applyInputTemplate(template, batch);
        }
        if (records == null || !records.isArray()) {
            return applyInputTemplate(template, batch);
        }
        ArrayNode transformed = objectMapper.createArrayNode();
        for (JsonNode record : records) {
            transformed.add(templateOutputNode(applyInputTemplate(template, record.toString())));
        }
        return transformed.toString();
    }

    private JsonNode templateOutputNode(String output) {
        try {
            JsonNode node = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(output);
            if (node != null && !node.isMissingNode()) {
                return node;
            }
        } catch (JsonProcessingException e) {
            LOG.debugv("Pipe input template output is not JSON, delivering it as a string: {0}",
                    e.getOriginalMessage());
        }
        return TextNode.valueOf(output);
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
        JsonNode node = extractJsonPathNode(jsonPath, json);
        if (node == null) {
            return null;
        }
        try {
            return node.isValueNode() ? objectMapper.writeValueAsString(node) : node.toString();
        } catch (JsonProcessingException e) {
            LOG.warnv("Failed to render JSONPath {0}: {1}", jsonPath, e.getOriginalMessage());
            return null;
        }
    }

    /** The node a JSON path selects in {@code json}, or null when it selects nothing. */
    private JsonNode extractJsonPathNode(String jsonPath, String json) {
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
            return current;
        } catch (Exception e) {
            LOG.warnv("Failed to extract JSONPath {0}: {1}", jsonPath, e.getMessage());
            return null;
        }
    }

    private static String extractRegionFromArn(String arn, String defaultRegion) {
        return AwsArnUtils.regionOrDefault(arn, defaultRegion);
    }
}
