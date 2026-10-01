package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Delivers a matched event to one rule target, applying the target's {@code RetryPolicy} and
 * {@code DeadLetterConfig}. The first attempt runs on the caller's thread; retryable failures are
 * retried with exponential backoff (1s doubling, capped at 300s) against the target's current
 * definition until {@code MaximumRetryAttempts} or {@code MaximumEventAgeInSeconds} (AWS defaults
 * 185 and 86400) is exhausted. Exhausted or non-retryable events go to the standard SQS queue in
 * the rule's region named by {@code DeadLetterConfig}, with the AWS dead-letter message attributes.
 */
@ApplicationScoped
public class TargetDispatcher implements Resettable {

    private static final Logger LOG = Logger.getLogger(TargetDispatcher.class);

    private static final int DEFAULT_MAXIMUM_RETRY_ATTEMPTS = 185;
    private static final int DEFAULT_MAXIMUM_EVENT_AGE_SECONDS = 86400;
    private static final long MAXIMUM_BACKOFF_SECONDS = 300;
    private static final String EXHAUSTED_BY_ATTEMPTS = "MaximumRetryAttempts";
    private static final String EXHAUSTED_BY_AGE = "MaximumEventAgeInSeconds";
    private static final Set<String> RETRYABLE_ERROR_CODES = Set.of("THROTTLING", "ERROR_FROM_TARGET", "INTERNAL_ERROR");

    private final EventBridgeInvoker invoker;
    private final SqsService sqsService;
    private final String baseUrl;
    private final Clock clock;
    private final ScheduledExecutorService executor;
    private final PriorityBlockingQueue<Delivery> pending =
            new PriorityBlockingQueue<>(11, Comparator.comparing(Delivery::nextAttemptAt));
    private final AtomicLong generation = new AtomicLong();

    @Inject
    public TargetDispatcher(EventBridgeInvoker invoker, SqsService sqsService, EmulatorConfig config, Clock clock) {
        this(invoker, sqsService, config.baseUrl(), clock, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "eventbridge-target-retry");
            thread.setDaemon(true);
            return thread;
        }));
    }

    TargetDispatcher(EventBridgeInvoker invoker, SqsService sqsService, String baseUrl, Clock clock,
                     ScheduledExecutorService executor) {
        this.invoker = invoker;
        this.sqsService = sqsService;
        this.baseUrl = baseUrl;
        this.clock = clock;
        this.executor = executor;
        if (executor != null) {
            executor.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
        }
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public void clear() {
        generation.incrementAndGet();
        pending.clear();
    }

    public void dispatch(String ruleArn, Target target, String eventJson, String region,
                         Supplier<List<Target>> currentTargets) {
        Instant now = clock.instant();
        try {
            attempt(new Delivery(ruleArn, target.getId(), eventJson, region, currentTargets, generation.get(), now, 0,
                    null, null, now), target, now);
        } catch (RuntimeException e) {
            LOG.warnv("EventBridge rule {0} could not dispatch to target {1}: {2}",
                    ruleArn, target.getId(), e.getMessage());
        }
    }

    public void dropPendingRetries(String ruleArn, List<String> targetIds) {
        pending.removeIf(delivery -> delivery.ruleArn().equals(ruleArn) && targetIds.contains(delivery.targetId()));
    }

    void tick() {
        while (true) {
            Delivery delivery = pending.peek();
            Instant now = clock.instant();
            if (delivery == null || now.isBefore(delivery.nextAttemptAt()) || !pending.remove(delivery)) {
                return;
            }
            try {
                RequestScopes.runAs(AwsArnUtils.parse(delivery.ruleArn()).accountId(), () -> retry(delivery, now));
            } catch (RuntimeException e) {
                LOG.warnv("EventBridge retry for rule {0} target {1} failed: {2}",
                        delivery.ruleArn(), delivery.targetId(), e.getMessage());
            }
        }
    }

    private void retry(Delivery delivery, Instant now) {
        if (delivery.generation() != generation.get()) {
            return;
        }
        Target target = delivery.currentTargets().get().stream()
                .filter(candidate -> delivery.targetId().equals(candidate.getId()))
                .findFirst()
                .orElse(null);
        if (target == null) {
            LOG.debugv("Dropped pending retry for rule {0} target {1}: target or rule removed",
                    delivery.ruleArn(), delivery.targetId());
            return;
        }
        if (!now.isBefore(expiresAt(delivery, target))) {
            deadLetter(delivery, target, EXHAUSTED_BY_AGE);
            return;
        }
        if (delivery.retryAttempts() >= maximumRetryAttempts(target)) {
            deadLetter(delivery, target, EXHAUSTED_BY_ATTEMPTS);
            return;
        }
        attempt(delivery.nextRetry(), target, now);
    }

    private void attempt(Delivery delivery, Target target, Instant now) {
        try {
            invoker.invokeTarget(target, delivery.eventJson(), delivery.region());
        } catch (Exception e) {
            if (delivery.generation() != generation.get()) {
                return;
            }
            String errorCode = classify(e);
            String message = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage()
                    : e.getClass().getSimpleName();
            LOG.warnv("EventBridge rule {0} target {1} delivery failed with {2}: {3}",
                    delivery.ruleArn(), target.getId(), errorCode, message);
            long backoffSeconds = Math.min(MAXIMUM_BACKOFF_SECONDS, 1L << Math.min(delivery.retryAttempts(), 9));
            Instant backoff = now.plusSeconds(backoffSeconds);
            Instant expiresAt = expiresAt(delivery, target);
            Delivery failed = delivery.failed(errorCode, message, backoff.isBefore(expiresAt) ? backoff : expiresAt);
            if (!RETRYABLE_ERROR_CODES.contains(errorCode)) {
                deadLetter(failed, target, null);
            } else if (failed.retryAttempts() >= maximumRetryAttempts(target)) {
                deadLetter(failed, target, EXHAUSTED_BY_ATTEMPTS);
            } else {
                pending.add(failed);
            }
        }
    }

    // A heuristic mapping onto the AWS dead-letter ERROR_CODE values; AWS does not document it.
    private static String classify(Exception e) {
        if (!(e instanceof AwsException aws)) {
            return "INTERNAL_ERROR";
        }
        String code = aws.getErrorCode() != null ? aws.getErrorCode() : "";
        if (aws.getHttpStatus() == 429 || code.contains("Throttl") || code.contains("TooManyRequests")) {
            return "THROTTLING";
        }
        if (aws.getHttpStatus() >= 500) {
            return "ERROR_FROM_TARGET";
        }
        if (aws.getHttpStatus() == 403 || code.startsWith("AccessDenied")) {
            return "NO_PERMISSIONS";
        }
        if (code.contains("NotFound") || code.contains("NonExistent") || code.contains("DoesNotExist")) {
            return "NO_RESOURCE";
        }
        return "INVALID_PARAMETER";
    }

    private void deadLetter(Delivery delivery, Target target, String condition) {
        LOG.warnv("EventBridge rule {0} did not deliver an event to target {1}: error {2}, condition {3}, retries {4}",
                delivery.ruleArn(), target.getId(), delivery.errorCode(), condition, delivery.retryAttempts());
        String queueArn = target.getDeadLetterConfig() != null ? target.getDeadLetterConfig().arn() : null;
        if (queueArn == null) {
            return;
        }
        if (!isStandardSqsQueueArn(queueArn) || !AwsArnUtils.parse(queueArn).region().equals(delivery.region())) {
            LOG.warnv("Skipping dead-letter delivery for rule {0}: {1} is not a standard SQS queue in {2}",
                    delivery.ruleArn(), queueArn, delivery.region());
            return;
        }
        Map<String, MessageAttributeValue> attributes = new LinkedHashMap<>();
        attributes.put("RULE_ARN", stringAttribute(delivery.ruleArn()));
        attributes.put("TARGET_ARN", stringAttribute(target.getArn()));
        attributes.put("ERROR_CODE", stringAttribute(delivery.errorCode()));
        attributes.put("ERROR_MESSAGE", stringAttribute(delivery.errorMessage()));
        if (condition != null) {
            attributes.put("EXHAUSTED_RETRY_CONDITION", stringAttribute(condition));
        }
        attributes.put("RETRY_ATTEMPTS", stringAttribute(String.valueOf(delivery.retryAttempts())));
        try {
            sqsService.sendMessage(AwsArnUtils.arnToQueueUrl(queueArn, baseUrl), delivery.eventJson(), 0, null, null,
                    attributes, delivery.region());
        } catch (RuntimeException e) {
            LOG.warnv("EventBridge rule {0} target {1} dead-letter delivery to {2} failed: {3}",
                    delivery.ruleArn(), target.getId(), queueArn, e.getMessage());
        }
    }

    private static Instant expiresAt(Delivery delivery, Target target) {
        Target.RetryPolicy retryPolicy = target.getRetryPolicy();
        int maximumAge = retryPolicy != null && retryPolicy.maximumEventAgeInSeconds() != null
                ? retryPolicy.maximumEventAgeInSeconds()
                : DEFAULT_MAXIMUM_EVENT_AGE_SECONDS;
        return delivery.firstAttemptAt().plusSeconds(maximumAge);
    }

    private static int maximumRetryAttempts(Target target) {
        Target.RetryPolicy retryPolicy = target.getRetryPolicy();
        return retryPolicy != null && retryPolicy.maximumRetryAttempts() != null
                ? retryPolicy.maximumRetryAttempts()
                : DEFAULT_MAXIMUM_RETRY_ATTEMPTS;
    }

    private static boolean isStandardSqsQueueArn(String arn) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            return "sqs".equals(parsed.service())
                    && !parsed.resource().isBlank()
                    && !parsed.resource().endsWith(".fifo");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static MessageAttributeValue stringAttribute(String value) {
        return new MessageAttributeValue(value, "String");
    }

    private record Delivery(String ruleArn, String targetId, String eventJson, String region,
                            Supplier<List<Target>> currentTargets, long generation, Instant firstAttemptAt,
                            int retryAttempts, String errorCode, String errorMessage, Instant nextAttemptAt) {

        Delivery failed(String code, String message, Instant retryAt) {
            return new Delivery(ruleArn, targetId, eventJson, region, currentTargets, generation, firstAttemptAt,
                    retryAttempts, code, message, retryAt);
        }

        Delivery nextRetry() {
            return new Delivery(ruleArn, targetId, eventJson, region, currentTargets, generation, firstAttemptAt,
                    retryAttempts + 1, errorCode, errorMessage, nextAttemptAt);
        }
    }
}
