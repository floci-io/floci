package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.lambda.model.FunctionEventInvokeConfig;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Delivers the result of an asynchronous Lambda invocation to the {@code OnSuccess} or
 * {@code OnFailure} destination of the function's event invoke configuration, or its configured
 * dead-letter queue when no failure destination is set.
 *
 * <p>Every destination carries the same asynchronous invocation record AWS sends, the envelope
 * around the event and the response that real CDK applications write EventBridge rules against
 * ({@code detail.responsePayload.<field>}). An EventBridge destination puts that record on the
 * bus as the event {@code detail}; SQS, SNS and Lambda destinations receive it as the message or
 * the event payload.
 *
 * <p>This lives outside {@link LambdaExecutorService} and reaches its collaborators through
 * {@link Instance} because the dependency graph runs the other way: {@code SnsService} invokes
 * its Lambda subscribers and {@code EventBridgeInvoker} its Lambda targets, so injecting those
 * services into the executor eagerly would close a CDI cycle. The lookup happens per delivery,
 * which is also when a destination is first known to exist.
 *
 * <p>Retries and event age are applied before delivery to {@code OnFailure}, so its record's
 * {@code approximateInvokeCount} reflects the attempts executed.
 */
@ApplicationScoped
public class AsyncInvokeDestinationRouter {

    private static final Logger LOG = Logger.getLogger(AsyncInvokeDestinationRouter.class);

    /**
     * Milliseconds, always three digits. {@code Instant.toString()} emits micro or nanosecond
     * precision on JDK 9+, and emits NO fractional part at all when the nanos happen to be zero,
     * so a consumer parsing the record with a fixed {@code .SSS} pattern breaks roughly once in a
     * billion records. AWS always emits milliseconds. Same formatter shape as
     * {@code ElasticBeanstalkQueryHandler}.
     */
    private static final DateTimeFormatter RECORD_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final String RECORD_VERSION = "1.0";
    private static final String EVENT_SOURCE = "lambda";
    private static final String SUCCESS_DETAIL_TYPE = "Lambda Function Invocation Result - Success";
    private static final String FAILURE_DETAIL_TYPE = "Lambda Function Invocation Result - Failure";
    private static final String SUCCESS_CONDITION = "Success";
    // AWS documents RetriesExhausted in its example, but does not establish a distinct condition
    // for maximum-age expiration, so Floci keeps the documented condition for both failures.
    private static final String FAILURE_CONDITION = "RetriesExhausted";
    private static final String DEFAULT_VERSION = "$LATEST";
    /** Default fallback invoke count when not explicitly provided by the caller. */
    private static final int APPROXIMATE_INVOKE_COUNT = 1;

    private final Instance<LambdaService> lambdaService;
    private final Instance<EventBridgeService> eventBridgeService;
    private final Instance<SqsService> sqsService;
    private final Instance<SnsService> snsService;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @Inject
    public AsyncInvokeDestinationRouter(Instance<LambdaService> lambdaService,
                                        Instance<EventBridgeService> eventBridgeService,
                                        Instance<SqsService> sqsService,
                                        Instance<SnsService> snsService,
                                        ObjectMapper objectMapper,
                                        EmulatorConfig config) {
        this.lambdaService = lambdaService;
        this.eventBridgeService = eventBridgeService;
        this.sqsService = sqsService;
        this.snsService = snsService;
        this.objectMapper = objectMapper;
        this.baseUrl = config.baseUrl();
    }

    /**
     * Delivers the result of one asynchronous invocation to the destination configured for its
     * outcome, and does nothing at all when the function has no event invoke configuration or
     * that configuration names no destination for this side.
     *
     * <p>Never throws. The caller answered the invoke with 202 long before the function
     * finished, so a destination that rejects the record has nowhere left to be reported and is
     * logged instead.
     *
     * @param chainDepth how many destination deliveries led to this invocation. The delivery runs
     *                   one hop further along, so a chain that leads back into Lambda, through a
     *                   function ARN or through SNS or EventBridge, is stopped at the bound in
     *                   {@link LambdaInvocationChain} instead of running forever
     */
    public void route(LambdaFunction fn, byte[] requestPayload, InvokeResult result, int chainDepth) {
        route(fn, requestPayload, result, APPROXIMATE_INVOKE_COUNT, chainDepth, null);
    }

    public void route(LambdaFunction fn, byte[] requestPayload, InvokeResult result, int chainDepth,
                      String invokedQualifier) {
        route(fn, requestPayload, result, APPROXIMATE_INVOKE_COUNT, chainDepth, invokedQualifier);
    }

    public void route(LambdaFunction fn, byte[] requestPayload, InvokeResult result,
                      int approximateInvokeCount, int chainDepth) {
        route(fn, requestPayload, result, approximateInvokeCount, chainDepth, null);
    }

    public void route(LambdaFunction fn, byte[] requestPayload, InvokeResult result,
                      int approximateInvokeCount, int chainDepth, String invokedQualifier) {
        // A runtime that never started, timed out, or crashed is reported as a function error by
        // the executor, so this one test covers a handler error and a failed runtime alike.
        boolean failed = result.getFunctionError() != null;
        FunctionEventInvokeConfig.Destination destination;
        try {
            destination = destinationFor(fn, failed, invokedQualifier);
        } catch (Exception e) {
            LOG.warnv("Could not read the event invoke configuration of {0}: {1}",
                    fn.getFunctionArn(), e.getMessage());
            return;
        }
        if (destination == null || destination.getDestination() == null
                || destination.getDestination().isBlank()) {
            if (failed && fn.getDeadLetterTargetArn() != null && !fn.getDeadLetterTargetArn().isBlank()) {
                deliverToDeadLetterQueue(fn, requestPayload, result);
            }
            return;
        }

        String arn = destination.getDestination();
        try {
            deliver(arn, fn, buildRecord(fn, requestPayload, result, failed, approximateInvokeCount),
                    failed, chainDepth);
        } catch (Exception e) {
            LOG.warnv("Failed to deliver the Lambda {0} destination record for {1} to {2}: {3}",
                    side(failed), fn.getFunctionArn(), arn, e.getMessage());
        }
    }

    private void deliverToDeadLetterQueue(LambdaFunction fn, byte[] requestPayload, InvokeResult result) {
        String arn = fn.getDeadLetterTargetArn();
        if (!AwsArnUtils.isArn(arn)) {
            LOG.warnv("Invalid DeadLetterConfig TargetArn: {0}", arn);
            return;
        }

        String destinationAccount = AwsArnUtils.accountOrDefault(arn, fn.getAccountId());
        String region = AwsArnUtils.regionOrDefault(arn,
                AwsArnUtils.regionOrDefault(fn.getFunctionArn(), null));
        String body = requestPayload != null ? new String(requestPayload, StandardCharsets.UTF_8) : "";
        String requestId = result.getRequestId() != null ? result.getRequestId() : "";
        String errorCode = String.valueOf(result.getStatusCode());
        String errorMessage = extractErrorMessage(result);

        Map<String, MessageAttributeValue> attributes = new LinkedHashMap<>();
        attributes.put("RequestID", new MessageAttributeValue(requestId, "String"));
        attributes.put("ErrorCode", new MessageAttributeValue(errorCode, "Number"));
        attributes.put("ErrorMessage", new MessageAttributeValue(errorMessage, "String"));

        try {
            boolean delivered = switch (AwsArnUtils.parse(arn).service()) {
                case "sqs" -> {
                    RequestScopes.runAs(destinationAccount, () ->
                            sqsService.get().sendMessage(AwsArnUtils.arnToQueueUrl(arn, baseUrl),
                                    body, 0, null, null, attributes, region));
                    yield true;
                }
                case "sns" -> {
                    RequestScopes.runAs(destinationAccount, () ->
                            snsService.get().publish(arn, null, null, body, "Lambda", attributes, region));
                    yield true;
                }
                default -> {
                    LOG.warnv("Unsupported DeadLetterConfig service, dropping the record: {0}", arn);
                    yield false;
                }
            };
            if (delivered) {
                LOG.debugv("Lambda DeadLetterConfig delivered to {0}", arn);
            }
        } catch (Exception e) {
            LOG.warnv("Failed to deliver the Lambda DeadLetterConfig for {0} to {1}: {2}",
                    fn.getFunctionArn(), arn, e.getMessage());
        }
    }

    private String extractErrorMessage(InvokeResult result) {
        String message = null;
        if (result.getPayload() != null && result.getPayload().length > 0) {
            try {
                JsonNode node = objectMapper.readTree(result.getPayload());
                if (node.hasNonNull("errorMessage")) {
                    message = node.get("errorMessage").asText();
                }
            } catch (Exception ignored) {
                // Non-JSON payloads fall back to the function error below.
            }
        }
        if (message == null) {
            message = result.getFunctionError() != null ? result.getFunctionError() : "Unknown error";
        }
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1024) {
            message = new String(bytes, 0, 1024, StandardCharsets.UTF_8);
        }
        return message;
    }

    private FunctionEventInvokeConfig.Destination destinationFor(LambdaFunction fn, boolean failed,
                                                                  String invokedQualifier) {
        FunctionEventInvokeConfig config = (invokedQualifier == null
                ? lambdaService.get().findEventInvokeConfig(fn)
                : lambdaService.get().findEventInvokeConfig(fn, invokedQualifier)).orElse(null);
        if (config == null || config.getDestinationConfig() == null) {
            return null;
        }
        return failed
                ? config.getDestinationConfig().getOnFailure()
                : config.getDestinationConfig().getOnSuccess();
    }

    /**
     * The record timestamp, always with exactly three fractional digits.
     *
     * <p>Package-private so a test can pin it at a ZERO-NANOSECOND instant. That input is the
     * only one that separates this from {@code truncatedTo(MILLIS).toString()}, which looks
     * equivalent and drops the fraction entirely on an exact second. A test that only formats
     * {@code now()} passes against both roughly 999 times in 1000.
     */
    static String formatRecordTimestamp(Instant instant) {
        return RECORD_TIMESTAMP.format(instant);
    }

    /**
     * The asynchronous invocation record, the shape AWS delivers to every destination kind. The
     * function ARN carries the executed version, as it does in a real record.
     */
    private ObjectNode buildRecord(LambdaFunction fn, byte[] requestPayload,
                                   InvokeResult result, boolean failed, int approximateInvokeCount) {
        String version = executedVersion(fn);
        ObjectNode record = objectMapper.createObjectNode();
        record.put("version", RECORD_VERSION);
        record.put("timestamp", formatRecordTimestamp(Instant.now()));

        ObjectNode requestContext = record.putObject("requestContext");
        requestContext.put("requestId", result.getRequestId());
        requestContext.put("functionArn", qualifiedFunctionArn(fn, version));
        requestContext.put("condition", failed ? FAILURE_CONDITION : SUCCESS_CONDITION);
        requestContext.put("approximateInvokeCount", approximateInvokeCount);

        record.set("requestPayload", payloadNode(requestPayload));

        ObjectNode responseContext = record.putObject("responseContext");
        responseContext.put("statusCode", result.getStatusCode());
        responseContext.put("executedVersion", version);
        // Present only on a failure record, carrying the same Handled/Unhandled value the
        // synchronous invoke reports in its X-Amz-Function-Error header.
        if (result.getFunctionError() != null) {
            responseContext.put("functionError", result.getFunctionError());
        }

        record.set("responsePayload", payloadNode(result.getPayload()));
        return record;
    }

    private void deliver(String arn, LambdaFunction fn, ObjectNode record, boolean failed, int chainDepth) {
        String region = AwsArnUtils.regionOrDefault(arn,
                AwsArnUtils.regionOrDefault(fn.getFunctionArn(), null));
        String accountId = AwsArnUtils.accountOrDefault(arn, fn.getAccountId());
        String body = record.toString();

        // The invocation ran on a pool thread that carries no request context, so the account the
        // destination lives in has to be re-established before any account-aware store is read.
        // The whole delivery runs one hop further along the chain, which is what bounds a
        // destination that comes back into Lambda through SNS or EventBridge rather than naming a
        // function outright: those deliver to their Lambda targets on this very thread.
        boolean delivered = LambdaInvocationChain.callAtDepth(chainDepth + 1, () ->
                switch (AwsArnUtils.isArn(arn) ? AwsArnUtils.parse(arn).service() : "") {
                    case "events" -> {
                        RequestScopes.runAs(accountId, () ->
                                putOnEventBus(arn, body, region, failed, fn.getFunctionArn()));
                        yield true;
                    }
                    case "sqs" -> {
                        RequestScopes.runAs(accountId, () ->
                                sqsService.get().sendMessage(AwsArnUtils.arnToQueueUrl(arn, baseUrl), body, 0, region));
                        yield true;
                    }
                    case "sns" -> {
                        RequestScopes.runAs(accountId, () ->
                                snsService.get().publish(arn, null, body, "Lambda", region));
                        yield true;
                    }
                    case "lambda" -> {
                        RequestScopes.runAs(accountId, () ->
                                lambdaService.get().invokeArnFromDestination(
                                        arn, body.getBytes(StandardCharsets.UTF_8), chainDepth + 1));
                        yield true;
                    }
                    default -> {
                        LOG.warnv("Unsupported Lambda {0} destination, dropping the record: {1}",
                                side(failed), arn);
                        yield false;
                    }
                });
        if (delivered) {
            LOG.debugv("Lambda {0} destination delivered to {1}", side(failed), arn);
        }
    }

    /**
     * Puts the record on the bus as the event {@code detail}. The source and detail type are the
     * fixed ones AWS uses, which is what a rule on a Lambda destination matches against.
     *
     * <p>{@code Resources} carries the invoked function and the destination, as AWS fills it.
     * Without it a rule whose pattern matches on {@code resources} never fires, and by a worse
     * route than a plain mismatch: {@code EventBridgeService.matchesPattern} casts
     * {@code event.get("Resources")} to an ArrayNode unconditionally, so an ABSENT member throws
     * NPE, which is swallowed by the surrounding catch, logged as {@code Failed to parse event
     * pattern} and reported as no-match. The record is then dropped at the bus rather than at the
     * destination, and the log blames the caller's pattern. Rules matching on {@code detail} are
     * unaffected, which is why this stayed invisible.
     */
    private void putOnEventBus(String busArn, String detail, String region, boolean failed,
                               String functionArn) {
        Map<String, Object> entry = new LinkedHashMap<>();
        // PutEvents takes a full event-bus ARN as EventBusName and validates it.
        entry.put("EventBusName", busArn);
        entry.put("Source", EVENT_SOURCE);
        entry.put("DetailType", failed ? FAILURE_DETAIL_TYPE : SUCCESS_DETAIL_TYPE);
        entry.put("Detail", detail);
        ArrayNode resources = objectMapper.createArrayNode();
        if (functionArn != null && !functionArn.isBlank()) {
            resources.add(functionArn);
        }
        resources.add(busArn);
        entry.put("Resources", resources);
        // A null account routes the bus lookup through the request context just established,
        // the only path that carries the un-prefixed legacy-key fallback.
        EventBridgeService.PutEventsResult result =
                eventBridgeService.get().putEvents(List.of(entry), region, null);
        if (result.failedCount() > 0) {
            LOG.warnv("EventBridge rejected the Lambda {0} destination record for {1}: {2}",
                    side(failed), busArn, result.entries());
        }
    }

    /**
     * The payload as JSON when it parses as JSON, and as a JSON string otherwise. AWS records the
     * event and the response verbatim and both are JSON for every runtime, so the string arm only
     * catches a runtime that wrote something else, which is still worth reporting.
     */
    private JsonNode payloadNode(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            return objectMapper.getNodeFactory().textNode(new String(payload, StandardCharsets.UTF_8));
        }
    }

    private static String qualifiedFunctionArn(LambdaFunction fn, String version) {
        String arn = fn.getFunctionArn();
        return arn.endsWith(":" + version) ? arn : arn + ":" + version;
    }

    private static String executedVersion(LambdaFunction fn) {
        String version = fn.getVersion();
        return version == null || version.isBlank() ? DEFAULT_VERSION : version;
    }

    private static String side(boolean failed) {
        return failed ? "OnFailure" : "OnSuccess";
    }
}
