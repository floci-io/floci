package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.SsrfProtection;
import io.github.hectorvent.floci.services.batch.BatchService;
import io.github.hectorvent.floci.services.ecs.EcsJsonHandler;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.ecs.model.ContainerOverride;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.eventbridge.model.ApiDestination;
import io.github.hectorvent.floci.services.eventbridge.model.Connection;
import io.github.hectorvent.floci.services.eventbridge.model.EcsParameters;
import io.github.hectorvent.floci.services.eventbridge.model.HttpParameters;
import io.github.hectorvent.floci.services.eventbridge.model.InputTransformer;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.firehose.FirehoseService;
import io.github.hectorvent.floci.services.firehose.model.Record;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.stepfunctions.StepFunctionsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class EventBridgeInvoker {

    private static final Logger LOG = Logger.getLogger(EventBridgeInvoker.class);

    // AWS can't route an event from a sender bus on to a third bus; the second hop is dropped.
    private static final int MAX_BUS_TO_BUS_DEPTH = 1;
    private static final int MAX_IDLE_RATE_WINDOWS = 256;
    // Headers the JDK HttpClient manages itself; setting them by hand throws IllegalArgumentException.
    private static final Set<String> RESTRICTED_HEADERS =
            Set.of("connection", "content-length", "expect", "host", "transfer-encoding", "upgrade");
    private static final ThreadLocal<Integer> BUS_TO_BUS_DEPTH = ThreadLocal.withInitial(() -> 0);

    private final LambdaService lambdaService;
    private final SqsService sqsService;
    private final SnsService snsService;
    private final BatchService batchService;
    private final FirehoseService firehoseService;
    private final EventBridgeService eventBridgeService;
    private final EcsService ecsService;
    private final EcsJsonHandler ecsJsonHandler;
    private final StepFunctionsService stepFunctionsService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final Map<String, RateWindow> rateWindows = new ConcurrentHashMap<>();

    @Inject
    public EventBridgeInvoker(LambdaService lambdaService,
                              SqsService sqsService,
                              SnsService snsService,
                              BatchService batchService,
                              FirehoseService firehoseService,
                              EventBridgeService eventBridgeService,
                              EcsService ecsService,
                              EcsJsonHandler ecsJsonHandler,
                              StepFunctionsService stepFunctionsService,
                              RegionResolver regionResolver,
                              ObjectMapper objectMapper,
                              EmulatorConfig config) {
        this(lambdaService, sqsService, snsService, batchService, firehoseService, eventBridgeService,
                ecsService, ecsJsonHandler, stepFunctionsService, regionResolver, objectMapper, config,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    EventBridgeInvoker(LambdaService lambdaService,
                       SqsService sqsService,
                       SnsService snsService,
                       BatchService batchService,
                       FirehoseService firehoseService,
                       EventBridgeService eventBridgeService,
                       EcsService ecsService,
                       EcsJsonHandler ecsJsonHandler,
                       StepFunctionsService stepFunctionsService,
                       RegionResolver regionResolver,
                       ObjectMapper objectMapper,
                       EmulatorConfig config,
                       HttpClient httpClient) {
        this.lambdaService = lambdaService;
        this.sqsService = sqsService;
        this.snsService = snsService;
        this.batchService = batchService;
        this.firehoseService = firehoseService;
        this.eventBridgeService = eventBridgeService;
        this.ecsService = ecsService;
        this.ecsJsonHandler = ecsJsonHandler;
        this.stepFunctionsService = stepFunctionsService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.baseUrl = config.baseUrl();
        this.httpClient = httpClient;
    }

    EventBridgeInvoker(LambdaService lambdaService,
                       SqsService sqsService,
                       SnsService snsService,
                       ObjectMapper objectMapper,
                       EmulatorConfig config) {
        this(lambdaService, sqsService, snsService,
                null /* batch */, null /* firehose */, null /* eventBridge */, null /* ecs */,
                null /* ecsJsonHandler */, null /* stepFunctions */, null /* regionResolver */, objectMapper, config);
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
                AwsArnUtils.Arn streamArn = AwsArnUtils.parse(arn);
                String streamName = streamArn.resource().substring("deliverystream/".length());
                // AWS puts the (input-transformed) event JSON as the record Data verbatim,
                // without appending a newline; the delivery-side NDJSON flush handles separation.
                Record record = new Record(payload.getBytes(StandardCharsets.UTF_8));
                if (regionResolver == null || regionResolver.getRegion() == null) {
                    // Preserve the standalone/test mode where no request ownership context exists.
                    firehoseService.putRecord(streamName, record);
                } else {
                    firehoseService.putRecord(streamArn.accountId(), streamArn.region(), streamName, record);
                }
                LOG.debugv("EventBridge delivered to Firehose: {0}", arn);
            } else if (isStateMachineArn(arn)) {
                String targetRegion = extractRegionFromArn(arn, region);
                String targetAccount = AwsArnUtils.parse(arn).accountId();
                RequestScopes.runAs(targetAccount,
                        () -> stepFunctionsService.startExecution(arn, null, payload, targetRegion));
                LOG.debugv("EventBridge started Step Functions execution: {0}", arn);
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
                    EventBridgeService.PutEventsResult result =
                            eventBridgeService.putEvents(List.of(entry), targetRegion, forwardAccount);
                    if (result.failedCount() > 0) {
                        Map<String, String> rejection = result.entries().getFirst();
                        throw new AwsException(rejection.get("ErrorCode"), rejection.get("ErrorMessage"), 400);
                    }
                    LOG.debugv("EventBridge delivered to EventBus: {0}", arn);
                } finally {
                    if (depth == 0) {
                        BUS_TO_BUS_DEPTH.remove();
                    } else {
                        BUS_TO_BUS_DEPTH.set(depth);
                    }
                }
            } else if (arn.contains(":events:") && arn.contains(":api-destination/")) {
                deliverToApiDestination(target, payload, region);
            } else {
                LOG.warnv("EventBridge: unsupported target ARN type: {0}", arn);
            }
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
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

    private static boolean isStateMachineArn(String arn) {
        if (!AwsArnUtils.isArnFor(arn, "states")) {
            return false;
        }
        String resource = AwsArnUtils.parse(arn).resource();
        String prefix = "stateMachine:";
        return resource.startsWith(prefix) && resource.indexOf(':', prefix.length()) < 0;
    }

    // Failures are thrown, not just logged, so TargetDispatcher can retry the retryable ones and dead-letter the rest.
    private void deliverToApiDestination(Target target, String payload, String region) {
        if (eventBridgeService == null) {
            LOG.warnv("EventBridge API Destination target missing EventBridge service: {0}", target.getArn());
            return;
        }
        String arn = target.getArn();
        ApiDestination destination = eventBridgeService.findApiDestinationByArn(arn, region);
        if (destination == null) {
            throw new AwsException("ResourceNotFoundException", "API destination " + arn + " does not exist.", 400);
        }
        acquireRatePermit(destination);

        Connection connection = eventBridgeService.findConnectionByArn(destination.getConnectionArn(), region);

        String rawUrl = destination.getInvocationEndpoint();
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new AwsException("InvalidParameterException",
                    "API destination " + destination.getName() + " has no InvocationEndpoint.", 400);
        }

        HttpParameters httpParameters = target.getHttpParameters();
        JsonNode authNode = readAuthParameters(connection);
        JsonNode invocationHttp = authNode.path("InvocationHttpParameters");

        // The connection's parameters are applied last so a target cannot override what the connection configures
        Map<String, String> queryParams = new LinkedHashMap<>();
        if (httpParameters != null && httpParameters.getQueryStringParameters() != null) {
            queryParams.putAll(httpParameters.getQueryStringParameters());
        }
        queryParams.putAll(readKeyValues(invocationHttp.path("QueryStringParameters")));
        String resolvedUrl = appendQueryParameters(
                substitutePathParameters(rawUrl,
                        httpParameters != null ? httpParameters.getPathParameterValues() : null),
                queryParams);

        URI uri = parseDeliveryUri(resolvedUrl);

        Map<String, String> headers = new LinkedHashMap<>();
        if (httpParameters != null && httpParameters.getHeaderParameters() != null) {
            httpParameters.getHeaderParameters().forEach((name, value) -> putHeader(headers, name, value));
        }
        readKeyValues(invocationHttp.path("HeaderParameters")).forEach((name, value) -> putHeader(headers, name, value));
        applyConnectionAuth(connection, authNode, headers);

        String method = destination.getHttpMethod() != null ? destination.getHttpMethod().toUpperCase() : "POST";
        boolean hasBody = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
        if (hasBody && !containsHeader(headers, "Content-Type")) {
            headers.put("Content-Type", "application/json; charset=utf-8");
        }
        String body = hasBody
                ? mergeBodyParameters(payload, readKeyValues(invocationHttp.path("BodyParameters")))
                : null;

        HttpRequest request;
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(5));
            applyHeaders(requestBuilder, headers);
            requestBuilder.method(method, hasBody
                    ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                    : HttpRequest.BodyPublishers.noBody());
            request = requestBuilder.build();
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterException", "The request to " + describe(uri)
                    + " is not valid (" + e.getClass().getSimpleName() + ").", 400);
        }

        HttpResponse<String> response = send(request, uri);
        LOG.debugv("API Destination {0} response status: {1}", destination.getName(), response.statusCode());
        requireSuccess(response.statusCode(), "API destination " + destination.getName(), uri);
    }

    private HttpResponse<String> send(HttpRequest request, URI uri) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AwsException("ServiceUnavailableException", "Interrupted while calling " + describe(uri), 503);
        } catch (IOException e) {
            throw new AwsException("ServiceUnavailableException", "Could not reach " + describe(uri) + ": "
                    + e.getClass().getSimpleName(), 503);
        }
    }

    // 429 and 5xx are retried by TargetDispatcher (THROTTLING / ERROR_FROM_TARGET); any other non-2xx is permanent
    private static void requireSuccess(int status, String what, URI uri) {
        if (status / 100 == 2) {
            return;
        }
        if (status == 429) {
            throw new AwsException("ThrottlingException", what + " was throttled by " + describe(uri), 429);
        }
        if (status >= 500) {
            throw new AwsException("ServiceUnavailableException",
                    what + " got HTTP " + status + " from " + describe(uri), 502);
        }
        throw new AwsException("InvalidParameterException",
                what + " got HTTP " + status + " from " + describe(uri), 400);
    }

    // Fixed one-second window per destination; an event over the limit is throttled and retried with backoff
    void acquireRatePermit(ApiDestination destination) {
        Integer limit = destination.getInvocationRateLimitPerSecond();
        if (limit == null) {
            return;
        }
        long nowSecond = Instant.now().getEpochSecond();
        // Windows of deleted or idle destinations would otherwise pile up, so stale ones are swept once the map grows
        if (rateWindows.size() > MAX_IDLE_RATE_WINDOWS) {
            rateWindows.values().removeIf(stale -> nowSecond - stale.second() > 1);
        }
        // The whole check-and-count runs inside compute, which is atomic per key and also against the sweep above,
        // so no delivery can keep counting on a window that was removed from the map
        boolean[] allowed = new boolean[1];
        rateWindows.compute(destination.getArn(), (key, current) -> {
            RateWindow window = current == null || current.second() != nowSecond ? new RateWindow(nowSecond, 0) : current;
            if (window.used() >= limit) {
                return window;
            }
            allowed[0] = true;
            return new RateWindow(nowSecond, window.used() + 1);
        });
        if (!allowed[0]) {
            throw new AwsException("ThrottlingException", "API destination " + destination.getName()
                    + " exceeded its InvocationRateLimitPerSecond of " + limit + ".", 429);
        }
    }

    private record RateWindow(long second, int used) {
    }

    // Only substitutes '*' inside the path: a user-supplied value must not be able to change the host, query or fragment.
    static String substitutePathParameters(String url, List<String> values) {
        if (values == null || values.isEmpty()) {
            return url;
        }
        int authorityStart = url.indexOf("://");
        int pathStart = authorityStart < 0 ? -1 : url.indexOf('/', authorityStart + 3);
        if (pathStart < 0) {
            return url;
        }
        int pathEnd = url.length();
        for (int i = pathStart; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '?' || c == '#') {
                pathEnd = i;
                break;
            }
        }
        StringBuilder path = new StringBuilder(url.substring(pathStart, pathEnd));
        int valueIndex = 0;
        int star = path.indexOf("*");
        while (star >= 0 && valueIndex < values.size()) {
            String value = values.get(valueIndex++);
            String encoded = value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
            path.replace(star, star + 1, encoded);
            star = path.indexOf("*", star + encoded.length());
        }
        return url.substring(0, pathStart) + path + url.substring(pathEnd);
    }

    static String appendQueryParameters(String url, Map<String, String> queryParams) {
        if (queryParams.isEmpty()) {
            return url;
        }
        // The query must precede any fragment, otherwise the parameters become part of the fragment and never reach the server
        int fragmentStart = url.indexOf('#');
        String base = fragmentStart < 0 ? url : url.substring(0, fragmentStart);
        String fragment = fragmentStart < 0 ? "" : url.substring(fragmentStart);
        StringBuilder sb = new StringBuilder(base);
        if (!base.contains("?")) {
            sb.append('?');
        } else if (!base.endsWith("?") && !base.endsWith("&")) {
            sb.append('&');
        }
        boolean first = true;
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.append(fragment).toString();
    }

    // Throws a permanent error when the URL is unsafe to call; its message never carries the URL, which may hold secrets
    private URI parseDeliveryUri(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterException", "The resolved URL is not a valid URI.", 400);
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new AwsException("InvalidParameterException", "Unsupported URL scheme " + scheme + ".", 400);
        }
        String host = uri.getHost();
        if (host == null) {
            throw new AwsException("InvalidParameterException", "The resolved URL has no valid host.", 400);
        }
        try {
            SsrfProtection.rejectMetadataAddresses(InetAddress.getAllByName(host), host);
        } catch (UnknownHostException expected) {
            // httpClient.send reports the resolution failure itself. DNS is resolved again at send time,
            // so rebinding remains a residual risk: the JDK HttpClient cannot pin the address that was checked
        } catch (IOException e) {
            throw new AwsException("InvalidParameterException",
                    "Refusing to call " + describe(uri) + ": " + e.getMessage(), 400);
        }
        return uri;
    }

    private static String describe(URI uri) {
        String port = uri.getPort() >= 0 ? ":" + uri.getPort() : "";
        return uri.getScheme() + "://" + uri.getHost() + port + (uri.getRawPath() != null ? uri.getRawPath() : "");
    }

    private JsonNode readAuthParameters(Connection connection) {
        if (connection.getAuthParameters() == null) {
            return MissingNode.getInstance();
        }
        try {
            return objectMapper.readTree(connection.getAuthParameters());
        } catch (JsonProcessingException e) {
            LOG.warnv("Failed to parse authParameters of connection {0}: {1}",
                    connection.getName(), e.getOriginalMessage());
            return MissingNode.getInstance();
        }
    }

    private static Map<String, String> readKeyValues(JsonNode params) {
        Map<String, String> result = new LinkedHashMap<>();
        for (JsonNode param : params) {
            String key = param.path("Key").asText(null);
            String value = param.path("Value").asText(null);
            if (key != null && value != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    // Header names are case-insensitive: a later value replaces an earlier one that differs only by case
    private static void putHeader(Map<String, String> headers, String name, String value) {
        headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
        headers.put(name, value);
    }

    private static boolean containsHeader(Map<String, String> headers, String name) {
        return headers.keySet().stream().anyMatch(name::equalsIgnoreCase);
    }

    private static void applyHeaders(HttpRequest.Builder requestBuilder, Map<String, String> headers) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey();
            if (RESTRICTED_HEADERS.contains(name.toLowerCase())) {
                LOG.debugv("API Destination header {0} is managed by the HTTP client; skipping", name);
                continue;
            }
            try {
                requestBuilder.header(name, header.getValue());
            } catch (IllegalArgumentException e) {
                LOG.warnv("API Destination header {0} is not valid; skipping", name);
            }
        }
    }

    private String mergeBodyParameters(String payload, Map<String, String> bodyParams) {
        String body = payload != null ? payload : "";
        if (bodyParams.isEmpty()) {
            return body;
        }
        try {
            JsonNode parsed = body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
            if (!(parsed instanceof ObjectNode objectNode)) {
                LOG.debug("API Destination body is not a JSON object; connection BodyParameters not applied");
                return body;
            }
            bodyParams.forEach(objectNode::put);
            return objectMapper.writeValueAsString(objectNode);
        } catch (JsonProcessingException e) {
            LOG.debugv("API Destination body is not JSON; connection BodyParameters not applied: {0}",
                    e.getOriginalMessage());
            return body;
        }
    }

    // Throws when the Connection needs an Authorization that cannot be obtained, so the event is never sent unauthenticated
    private void applyConnectionAuth(Connection connection, JsonNode authNode, Map<String, String> headers) {
        String authType = connection.getAuthorizationType();
        if (authType == null) {
            return;
        }
        switch (authType) {
            case "BASIC" -> {
                JsonNode basic = authNode.path("BasicAuthParameters");
                if (!basic.isMissingNode()) {
                    putHeader(headers, "Authorization", basicAuthorization(
                            basic.path("Username").asText(""), basic.path("Password").asText("")));
                }
            }
            case "API_KEY" -> {
                JsonNode apiKey = authNode.path("ApiKeyAuthParameters");
                String name = apiKey.path("ApiKeyName").asText(null);
                String value = apiKey.path("ApiKeyValue").asText(null);
                if (name != null && value != null) {
                    putHeader(headers, name, value);
                }
            }
            case "OAUTH_CLIENT_CREDENTIALS" ->
                    putHeader(headers, "Authorization", fetchOAuthAuthorization(authNode.path("OAuthParameters")));
            default -> LOG.warnv("Unsupported connection authorization type: {0}", authType);
        }
    }

    private static String basicAuthorization(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    // RFC 6749 client-credentials exchange; returns "<token_type> <access_token>" and throws when no token is obtained
    private String fetchOAuthAuthorization(JsonNode oauth) {
        String endpoint = oauth.path("AuthorizationEndpoint").asText(null);
        if (endpoint == null || endpoint.isBlank()) {
            throw new AwsException("InvalidParameterException", "The OAuth connection has no AuthorizationEndpoint.", 400);
        }
        JsonNode oauthHttp = oauth.path("OAuthHttpParameters");
        String method = oauth.path("HttpMethod").asText("POST").toUpperCase();
        boolean hasBody = !"GET".equals(method);

        URI uri = parseDeliveryUri(appendQueryParameters(endpoint,
                readKeyValues(oauthHttp.path("QueryStringParameters"))));

        Map<String, String> headers = readKeyValues(oauthHttp.path("HeaderParameters"));
        if (!containsHeader(headers, "Authorization")) {
            JsonNode client = oauth.path("ClientParameters");
            headers.put("Authorization", basicAuthorization(
                    client.path("ClientID").asText(""), client.path("ClientSecret").asText("")));
        }
        if (hasBody && !containsHeader(headers, "Content-Type")) {
            headers.put("Content-Type", "application/x-www-form-urlencoded");
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        form.putAll(readKeyValues(oauthHttp.path("BodyParameters")));
        StringBuilder formBody = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (!formBody.isEmpty()) {
                formBody.append('&');
            }
            formBody.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }

        HttpRequest request;
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(5));
            applyHeaders(requestBuilder, headers);
            requestBuilder.method(method, hasBody
                    ? HttpRequest.BodyPublishers.ofString(formBody.toString(), StandardCharsets.UTF_8)
                    : HttpRequest.BodyPublishers.noBody());
            request = requestBuilder.build();
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterException", "The OAuth token request to " + describe(uri)
                    + " is not valid (" + e.getClass().getSimpleName() + ").", 400);
        }

        HttpResponse<String> response = send(request, uri);
        requireSuccess(response.statusCode(), "The OAuth token request", uri);
        String accessToken;
        String tokenType;
        try {
            JsonNode token = objectMapper.readTree(response.body());
            accessToken = token.path("access_token").asText(null);
            tokenType = token.path("token_type").asText("Bearer");
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidParameterException",
                    "The OAuth token response from " + describe(uri) + " is not JSON.", 400);
        }
        if (accessToken == null || accessToken.isBlank()) {
            throw new AwsException("InvalidParameterException",
                    "The OAuth token response from " + describe(uri) + " has no access_token.", 400);
        }
        return tokenType + " " + accessToken;
    }
}
