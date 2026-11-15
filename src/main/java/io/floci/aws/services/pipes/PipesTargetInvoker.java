package io.floci.aws.services.pipes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.common.ServiceRegistry;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.floci.aws.services.pipes.model.Pipe;
import io.floci.aws.services.redshiftdata.RedshiftDataTargetSubmitter;
import io.floci.aws.services.redshiftdata.RedshiftTargetAuthorizer;
import io.floci.aws.services.sns.SnsService;
import io.floci.aws.services.sqs.SqsService;
import io.floci.aws.services.stepfunctions.StepFunctionsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class PipesTargetInvoker {

    private static final Logger LOG = Logger.getLogger(PipesTargetInvoker.class);
    private static final Pattern TEMPLATE_PLACEHOLDER =
            Pattern.compile("<(\\$[^>]+|aws\\.pipes\\.event\\.ingestion-time)>");
    private static final Pattern ARRAY_INDEX = Pattern.compile("^(.*?)\\[(\\d+)]$");
    private static final String PIPES_PRINCIPAL = ServicePrincipals.of("pipes");
    private static final Pattern DYNAMIC_PARAMETER_PATH =
            Pattern.compile("\\$(?:\\.[\\w*/-]+|\\[\\d+\\])+");

    private final LambdaService lambdaService;
    private final SqsService sqsService;
    private final SnsService snsService;
    private final EventBridgeService eventBridgeService;
    private final StepFunctionsService stepFunctionsService;
    private final RedshiftDataTargetSubmitter redshiftSubmitter;
    private final RedshiftTargetAuthorizer redshiftTargetAuthorizer;
    private final ServiceRegistry serviceRegistry;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @Inject
    public PipesTargetInvoker(LambdaService lambdaService,
                              SqsService sqsService,
                              SnsService snsService,
                              EventBridgeService eventBridgeService,
                              StepFunctionsService stepFunctionsService,
                              RedshiftDataTargetSubmitter redshiftSubmitter,
                              RedshiftTargetAuthorizer redshiftTargetAuthorizer,
                              ServiceRegistry serviceRegistry,
                              ObjectMapper objectMapper,
                              EmulatorConfig config) {
        this.lambdaService = lambdaService;
        this.sqsService = sqsService;
        this.snsService = snsService;
        this.eventBridgeService = eventBridgeService;
        this.stepFunctionsService = stepFunctionsService;
        this.redshiftSubmitter = redshiftSubmitter;
        this.redshiftTargetAuthorizer = redshiftTargetAuthorizer;
        this.serviceRegistry = serviceRegistry;
        this.objectMapper = objectMapper;
        this.baseUrl = config.effectiveBaseUrl();
    }

    /** Whether {@code targetArn} names a Redshift cluster or a Redshift Serverless workgroup. */
    static boolean isRedshiftTarget(String targetArn) {
        return AwsArnUtils.isArnFor(targetArn, "redshift") || AwsArnUtils.isArnFor(targetArn, "redshift-serverless");
    }

    public void invoke(Pipe pipe, String payload, String region) {
        String targetArn = pipe.getTarget();

        // A dynamic path in the Redshift parameters reads the source event as it arrived, and AWS does not
        // let it name a value an input transformation produced, so this does not go through the template.
        if (isRedshiftTarget(targetArn)) {
            invokeRedshiftData(pipe, payload, region);
            return;
        }

        JsonNode tp = pipe.getTargetParameters();
        // A target's dynamic parameters read the event as it arrived, never what the template renders.
        String event = payload;
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
            invokeEventBridge(targetArn, payload, region, tp, event);
        } else if (targetArn.contains(":states:")) {
            invokeStepFunctions(targetArn, payload, region);
        } else {
            LOG.warnv("Pipe {0}: unsupported target ARN type: {1}", pipe.getName(), targetArn);
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
            // AWS also skips the target when the enrichment returns an empty string "", an empty
            // object {} or an empty array []; only a non-empty array such as [{}] invokes the target
            // (with an empty-payload element). Parse so whitespace variants ({ }, [ ]) are handled too.
            try {
                JsonNode node = objectMapper.readTree(resp);
                boolean empty = node.isTextual()
                        ? node.asText().isEmpty()
                        : node.isContainerNode() && node.isEmpty();
                if (empty) {
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

    private void invokeEventBridge(String arn, String payload, String region, JsonNode tp, String event) {
        String busName = AwsArnUtils.resourceIfArnFor(arn, "events")
                .filter(resource -> resource.startsWith("event-bus/"))
                .map(resource -> resource.substring("event-bus/".length()))
                .orElse(arn);
        String ebRegion = extractRegionFromArn(arn, region);
        String source = "aws.pipes";
        String detailType = "PipeForwarded";
        if (tp != null) {
            JsonNode ebParams = tp.path("EventBridgeEventBusParameters");
            if (ebParams.has("Source")) {
                source = resolveDynamicParameter(ebParams.get("Source").asText(), event, "target parameter Source");
            }
            if (ebParams.has("DetailType")) {
                detailType = resolveDynamicParameter(ebParams.get("DetailType").asText(), event,
                        "target parameter DetailType");
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
     * A Redshift target always runs its {@code Sqls} as one BatchExecuteStatement, under the pipe's role.
     * The Data API accepts the statements and the pipe does not wait for them, so a statement that later
     * fails does not fail the delivery or get retried: only a refusal or a rejected request does.
     */
    private void invokeRedshiftData(Pipe pipe, String payload, String region) {
        if (serviceRegistry != null && !serviceRegistry.isServiceEnabled("redshift-data")) {
            throw new AwsException("ServiceNotAvailableException", "Service redshift-data is not enabled.", 400);
        }
        JsonNode targetParameters = pipe.getTargetParameters();
        JsonNode parameters = targetParameters == null
                ? MissingNode.getInstance() : targetParameters.path("RedshiftDataParameters");
        JsonNode sqlNodes = parameters.path("Sqls");
        if (!sqlNodes.isArray() || sqlNodes.isEmpty()) {
            throw new AwsException("ValidationException",
                    "Pipe " + pipe.getName() + " has a Redshift target without RedshiftDataParameters.Sqls.", 400);
        }
        List<String> sqls = new ArrayList<>();
        sqlNodes.forEach(sql -> sqls.add(sql.asText()));

        String targetArn = pipe.getTarget();
        String targetRegion = extractRegionFromArn(targetArn, region);
        String account = pipe.getAccountId() != null ? pipe.getAccountId() : AwsArnUtils.parse(targetArn).accountId();
        RedshiftDataTargetSubmitter.Statement statement = new RedshiftDataTargetSubmitter.Statement(
                text(parameters, "Database"), text(parameters, "DbUser"), text(parameters, "SecretManagerArn"),
                null, sqls, text(parameters, "StatementName"),
                parameters.path("WithEvent").isBoolean() ? parameters.get("WithEvent").asBoolean() : null);
        redshiftSubmitter.submit(targetArn, statement, pipe.getRoleArn(), account, targetRegion,
                value -> resolveDynamicParameter(value, payload, "Redshift target path"),
                permissions -> redshiftTargetAuthorizer.refusal(
                        PIPES_PRINCIPAL, pipe.getRoleArn(), account, pipe.getArn(), permissions));
        LOG.debugv("Pipe delivered to Redshift Data API: {0}", targetArn);
    }

    private static String text(JsonNode parent, String field) {
        JsonNode node = parent.path(field);
        return node.isTextual() ? node.asText() : null;
    }

    /**
     * A dynamic target parameter's value: as written, or where the whole value is a JSON path, what
     * that path names in {@code payload}, a JSON string as its text. A path naming nothing fails the
     * delivery, named as {@code what}, so nothing reaches the target under a literal or empty value.
     */
    private String resolveDynamicParameter(String value, String payload, String what) {
        if (value == null || !DYNAMIC_PARAMETER_PATH.matcher(value).matches()) {
            return value;
        }
        String extracted = extractJsonPath(value, payload);
        if (extracted == null) {
            throw new AwsException("ValidationException", "Cannot resolve " + what + ": " + value, 400);
        }
        return unquoted(extracted);
    }

    /** The text of a JSON string as {@link #extractJsonPath} gives it; any other JSON as it is. */
    private String unquoted(String extracted) {
        try {
            JsonNode node = objectMapper.readTree(extracted);
            return node.isTextual() ? node.asText() : node.toString();
        } catch (JsonProcessingException e) {
            return extracted;
        }
    }

    String applyInputTemplate(String template, String payload) {
        Matcher m = TEMPLATE_PLACEHOLDER.matcher(template);
        // A template that is one placeholder substitutes a string verbatim: <$.Detail> naming a serialized
        // object puts that object on the bus, where added quotes would be refused as MalformedDetail.
        boolean wholeTemplate = m.matches();
        m.reset();
        String ingestionTime = objectMapper.valueToTree(Instant.now().toString()).toString();
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String jsonPath = m.group(1);
            boolean quoted = isInsideJsonString(template, m.start());
            String value = "aws.pipes.event.ingestion-time".equals(jsonPath)
                    ? ingestionTime
                    : extractJsonPath(jsonPath, payload);
            if (value == null) {
                value = quoted ? "" : objectMapper.valueToTree("").toString();
            } else if (wholeTemplate) {
                value = unquoted(value);
            } else if (quoted && value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                // A JSON string placeholder already has quotes from extractJsonPath. The
                // surrounding template quotes must remain the only pair of quotes.
                try {
                    String text = objectMapper.readTree(value).asText();
                    value = objectMapper.writeValueAsString(text);
                    value = value.substring(1, value.length() - 1);
                } catch (JsonProcessingException ignored) {
                    // Keep the extracted representation when it is not a JSON string.
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean isInsideJsonString(String template, int position) {
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < position; i++) {
            char c = template.charAt(i);
            if (c == '\\' && !escaped) {
                escaped = true;
                continue;
            }
            if (c == '"' && !escaped) {
                quoted = !quoted;
            }
            escaped = false;
        }
        return quoted;
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
                    // A leading index such as "$[0].x" has no name before the bracket
                    if (!am.group(1).isEmpty()) {
                        segments.add(am.group(1));
                    }
                    segments.add(am.group(2));
                } else if (!seg.isEmpty()) {
                    segments.add(seg);
                }
            }

            JsonNode current = objectMapper.readTree(json);
            String previousSegment = null;
            JsonNode container = null;
            boolean sourceFieldDecoded = false;
            for (String segment : segments) {
                // AWS Pipes parses an encoded source body when traversing into it.
                if (!sourceFieldDecoded && isEncodedSourceField(previousSegment) && current.isTextual()) {
                    try {
                        JsonNode decoded = objectMapper.readTree(
                                decodedSourceText(previousSegment, current.asText(), container));
                        if (decoded != null && (decoded.isObject() || decoded.isArray())) {
                            current = decoded;
                        }
                        sourceFieldDecoded = true;
                    } catch (Exception ignored) {
                        return null;
                    }
                }
                JsonNode next = current.isArray()
                        ? current.path(Integer.parseInt(segment))
                        : current.path(segment);
                if (next.isMissingNode() || next.isNull()) {
                    return null;
                }
                container = current;
                current = next;
                previousSegment = segment;
            }
            if (!sourceFieldDecoded && isEncodedSourceField(previousSegment) && current.isTextual()) {
                // SQS bodies commonly contain a JSON document as a string. Pipes input
                // transformation resolves that document when the body itself is selected.
                String text = decodedSourceText(previousSegment, current.asText(), container);
                try {
                    JsonNode decoded = objectMapper.readTree(text);
                    if (decoded != null && (decoded.isObject() || decoded.isArray())) {
                        return decoded.toString();
                    }
                } catch (JsonProcessingException ignored) {
                    // Keep ordinary textual fields as JSON strings.
                }
                if (!text.equals(current.asText())) {
                    return objectMapper.writeValueAsString(text);
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

    /**
     * Kinesis {@code data} and Kafka {@code key} and {@code value} arrive as base64, so a path into them has to
     * read the decoded bytes. Only the fields of a Kinesis or Kafka source record qualify: a field of the same
     * name in an enrichment response or an SQS body is ordinary text and stays as it is, and so does text that
     * is not base64.
     *
     * @param container the object that holds {@code field}
     */
    public static String decodedSourceText(String field, String text, JsonNode container) {
        if (!isBase64SourceField(field, container)) {
            return text;
        }
        try {
            return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException expected) {
            // Not base64, so the field holds plain text such as an inline JSON document.
            return text;
        }
    }

    private static boolean isBase64SourceField(String field, JsonNode container) {
        if ("data".equals(field)) {
            return container != null && "aws:kinesis".equals(container.path("eventSource").asText());
        }
        if ("key".equals(field) || "value".equals(field)) {
            return container != null && isKafkaEventSource(container.path("eventSource").asText());
        }
        return false;
    }

    private static boolean isKafkaEventSource(String eventSource) {
        return "aws:kafka".equals(eventSource) || "SelfManagedKafka".equals(eventSource);
    }

    private static boolean isEncodedSourceField(String segment) {
        return "body".equals(segment) || "data".equals(segment)
                || "key".equals(segment) || "value".equals(segment);
    }

    private static String extractRegionFromArn(String arn, String defaultRegion) {
        return AwsArnUtils.regionOrDefault(arn, defaultRegion);
    }
}
