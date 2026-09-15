package io.github.hectorvent.floci.services.scheduler;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.scheduler.SchedulerExpressionParser.Kind;
import io.github.hectorvent.floci.services.scheduler.model.DeadLetterConfig;
import io.github.hectorvent.floci.services.scheduler.model.RetryPolicy;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.scheduler.model.Target;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fires EventBridge Scheduler targets when schedules are due.
 *
 * A single background thread ticks on a fixed interval, scans all persisted
 * schedules, and invokes the target of any schedule whose next fire time has
 * passed. Per-schedule "last fire" state is kept in memory (by schedule ARN);
 * restarts reset it, matching the emulator's loose durability expectations.
 *
 * Scope of the initial implementation:
 * <ul>
 *   <li>Expression kinds: {@code at(...)}, {@code rate(...)}, {@code cron(...)}
 *       with optional {@code ScheduleExpressionTimezone}.</li>
 *   <li>Gating: {@code State=DISABLED}, {@code StartDate}/{@code EndDate}.</li>
 *   <li>Completion: {@code ActionAfterCompletion=DELETE} removes one-time
 *       {@code at(...)} schedules once their occurrence is delivered or exhausted.</li>
 *   <li>Targets: whatever {@link ScheduleInvoker} can deliver to (SQS, Lambda,
 *       SNS, EventBridge, ECS). Unsupported targets count as failed invocations.</li>
 *   <li>Failures: each occurrence (schedule ARN + scheduled time) is retried on
 *       later ticks until its {@code RetryPolicy} is exhausted, using the AWS
 *       defaults (86400 seconds, 185 retries) for missing fields. Exhausted
 *       occurrences are sent to the standard SQS queue named by
 *       {@code DeadLetterConfig} with the AWS dead-letter message attributes.
 *       Pending retries do not delay later occurrences and are dropped when the
 *       schedule is disabled, updated, or deleted.</li>
 * </ul>
 */
@ApplicationScoped
public class ScheduleDispatcher implements Resettable {

    private static final Logger LOG = Logger.getLogger(ScheduleDispatcher.class);

    static final int DEFAULT_MAXIMUM_EVENT_AGE_SECONDS = 86400;
    static final int DEFAULT_MAXIMUM_RETRY_ATTEMPTS = 185;
    private static final String DEFAULT_ERROR_CODE = "AWS.Scheduler.InternalServerError";
    private static final String EXHAUSTED_BY_ATTEMPTS = "MaximumRetryAttempts";
    private static final String EXHAUSTED_BY_AGE = "MaximumEventAgeInSeconds";

    private final SchedulerService schedulerService;
    private final ScheduleInvoker invoker;
    private final SqsService sqsService;
    private final String baseUrl;
    private final long tickIntervalSeconds;
    private final boolean enabled;
    private final ScheduledExecutorService executor;
    private final ConcurrentHashMap<String, Instant> lastFireByArn = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> firedOnceByArn = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Occurrence, Delivery> pendingRetries = new ConcurrentHashMap<>();

    @Inject
    public ScheduleDispatcher(SchedulerService schedulerService,
                              ScheduleInvoker invoker,
                              SqsService sqsService,
                              EmulatorConfig config) {
        this.schedulerService = schedulerService;
        this.invoker = invoker;
        this.sqsService = sqsService;
        this.baseUrl = config.baseUrl();
        this.tickIntervalSeconds = config.services().scheduler().tickIntervalSeconds();
        this.enabled = config.services().scheduler().enabled()
                && config.services().scheduler().invocationEnabled();
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scheduler-dispatcher");
            t.setDaemon(true);
            return t;
        });
    }

    void onStart(@Observes StartupEvent ignored) {
        if (!enabled) {
            LOG.info("Scheduler dispatcher disabled by configuration");
            return;
        }
        executor.scheduleAtFixedRate(this::tickSafely, tickIntervalSeconds, tickIntervalSeconds, TimeUnit.SECONDS);
        LOG.infov("Scheduler dispatcher started (tick every {0}s)", tickIntervalSeconds);
    }

    void onStop(@Observes ShutdownEvent ignored) {
        executor.shutdownNow();
    }

    public void clear() {
        lastFireByArn.clear();
        firedOnceByArn.clear();
        pendingRetries.clear();
    }

    void tickSafely() {
        try {
            tick(Instant.now());
        } catch (Throwable t) {
            LOG.warnv("Scheduler dispatcher tick failed: {0}", t.getMessage());
        }
    }

    void tick(Instant now) {
        List<Schedule> schedules = schedulerService.listAllSchedules();
        // Retry only occurrences that failed on earlier ticks, and retry them after evaluation so a
        // schedule deleted on completion is not evaluated again from this tick's stale listing.
        Set<Occurrence> dueRetries = Set.copyOf(pendingRetries.keySet());
        for (Schedule schedule : schedules) {
            try {
                evaluate(schedule, now);
            } catch (Exception e) {
                LOG.warnv("Failed to evaluate schedule {0}: {1}", schedule.getArn(), e.getMessage());
            }
        }
        retryPending(dueRetries, schedules, now);
    }

    private void retryPending(Set<Occurrence> dueRetries, List<Schedule> schedules, Instant now) {
        if (dueRetries.isEmpty()) {
            return;
        }
        Map<String, Schedule> schedulesByArn = new HashMap<>();
        for (Schedule schedule : schedules) {
            schedulesByArn.put(schedule.getArn(), schedule);
        }
        for (Occurrence occurrence : dueRetries) {
            Delivery delivery = pendingRetries.get(occurrence);
            if (delivery == null) {
                continue;
            }
            Schedule schedule = schedulesByArn.get(occurrence.scheduleArn());
            if (isStale(schedule, delivery)) {
                pendingRetries.remove(occurrence);
                LOG.debugv("Dropped pending retry for {0}: schedule deleted, disabled, or updated",
                        occurrence.scheduleArn());
                continue;
            }
            if (delivery.nextAttemptAt() != null && now.isBefore(delivery.nextAttemptAt())) {
                continue;
            }
            try {
                if (hasExpired(schedule, occurrence, now)) {
                    exhaust(schedule, occurrence, delivery, EXHAUSTED_BY_AGE);
                } else {
                    attempt(schedule, occurrence, delivery.nextRetry(), now);
                }
            } catch (Exception e) {
                LOG.warnv("Failed to retry schedule {0}: {1}", occurrence.scheduleArn(), e.getMessage());
            }
        }
    }

    private void evaluate(Schedule schedule, Instant now) {
        if (!"ENABLED".equalsIgnoreCase(schedule.getState())) {
            return;
        }
        if (schedule.getStartDate() != null && now.isBefore(schedule.getStartDate())) {
            return;
        }
        if (schedule.getEndDate() != null && now.isAfter(schedule.getEndDate())) {
            return;
        }
        if (schedule.getScheduleExpression() == null || schedule.getTarget() == null) {
            return;
        }

        Kind kind;
        try {
            kind = SchedulerExpressionParser.classify(schedule.getScheduleExpression());
        } catch (IllegalArgumentException e) {
            LOG.warnv("Unsupported expression on schedule {0}: {1}",
                    schedule.getArn(), schedule.getScheduleExpression());
            return;
        }

        Instant nextFire = computeNextFire(schedule, kind, now);
        if (nextFire == null || now.isBefore(nextFire)) {
            return;
        }

        // Record the fire before delivering so a failing occurrence never holds back the next one.
        recordFire(schedule, now);
        String requestBody = invoker.materializeRequest(schedule.getTarget(), regionOf(schedule));
        attempt(schedule, new Occurrence(schedule.getArn(), nextFire),
                Delivery.first(kind, schedule, requestBody), now);
    }

    private Instant computeNextFire(Schedule schedule, Kind kind, Instant now) {
        String expr = schedule.getScheduleExpression();
        String tz = schedule.getScheduleExpressionTimezone();
        String arn = schedule.getArn();

        return switch (kind) {
            case AT -> {
                if (firedOnceByArn.containsKey(arn)) {
                    yield null;
                }
                yield SchedulerExpressionParser.parseAt(expr, tz);
            }
            case RATE -> {
                long intervalMs = SchedulerExpressionParser.parseRateMillis(expr);
                Instant base = lastFireByArn.getOrDefault(arn, schedule.getCreationDate() != null
                        ? schedule.getCreationDate()
                        : now);
                yield base.plusMillis(intervalMs);
            }
            case CRON -> {
                Instant base = lastFireByArn.getOrDefault(arn, schedule.getCreationDate() != null
                        ? schedule.getCreationDate()
                        : now.minusSeconds(1));
                yield SchedulerExpressionParser.nextCronFire(expr, base, tz);
            }
        };
    }

    private void attempt(Schedule schedule, Occurrence occurrence, Delivery delivery, Instant now) {
        try {
            invoker.invoke(schedule.getTarget(), regionOf(schedule));
        } catch (Exception e) {
            LOG.warnv("Schedule {0} invocation failed: {1}", schedule.getArn(), e.getMessage());
            Delivery failed = delivery.failedWith(e, now);
            if (failed.retryAttempts() >= maximumRetryAttempts(schedule)) {
                exhaust(schedule, occurrence, failed, EXHAUSTED_BY_ATTEMPTS);
            } else if (hasExpired(schedule, occurrence, now)) {
                exhaust(schedule, occurrence, failed, EXHAUSTED_BY_AGE);
            } else {
                pendingRetries.put(occurrence, failed);
            }
            return;
        }
        LOG.infov("Fired schedule {0} in group {1}", schedule.getName(), schedule.getGroupName());
        resolve(schedule, occurrence, delivery);
    }

    private void exhaust(Schedule schedule, Occurrence occurrence, Delivery delivery, String condition) {
        LOG.warnv("Schedule {0} occurrence {1} exhausted by {2} after {3} retries",
                schedule.getArn(), occurrence.scheduledAt(), condition, delivery.retryAttempts());
        sendToDeadLetter(schedule, occurrence, delivery, condition);
        resolve(schedule, occurrence, delivery);
    }

    private void resolve(Schedule schedule, Occurrence occurrence, Delivery delivery) {
        pendingRetries.remove(occurrence);
        if (delivery.kind() != Kind.AT || !isDeleteAfterCompletion(schedule)) {
            return;
        }
        try {
            schedulerService.deleteScheduleForAccount(
                    schedule.getAccountId(), schedule.getName(), schedule.getGroupName(), regionOf(schedule));
            lastFireByArn.remove(schedule.getArn());
            firedOnceByArn.remove(schedule.getArn());
        } catch (Exception e) {
            LOG.warnv("Post-completion delete failed for {0}: {1}", schedule.getArn(), e.getMessage());
        }
    }

    private void sendToDeadLetter(Schedule schedule, Occurrence occurrence, Delivery delivery, String condition) {
        Target target = schedule.getTarget();
        DeadLetterConfig deadLetterConfig = target.getDeadLetterConfig();
        if (deadLetterConfig == null || deadLetterConfig.getArn() == null
                || deadLetterConfig.getArn().isBlank()) {
            return;
        }
        String queueArn = deadLetterConfig.getArn();
        if (!isStandardSqsQueueArn(queueArn)) {
            LOG.warnv("Skipping dead-letter delivery for schedule {0}: {1} is not a standard SQS queue ARN",
                    schedule.getArn(), queueArn);
            return;
        }

        Map<String, MessageAttributeValue> attributes = new LinkedHashMap<>();
        attributes.put("ERROR_CODE", stringAttribute(delivery.errorCode()));
        attributes.put("ERROR_MESSAGE", stringAttribute(delivery.errorMessage()));
        attributes.put("EXECUTION_ID", stringAttribute(delivery.executionId()));
        attributes.put("EXHAUSTED_RETRY_CONDITION", stringAttribute(condition));
        attributes.put("IS_PAYLOAD_TRUNCATED", stringAttribute("false"));
        attributes.put("RETRY_ATTEMPTS", stringAttribute(String.valueOf(delivery.retryAttempts())));
        attributes.put("SCHEDULED_TIME", stringAttribute(
                occurrence.scheduledAt().truncatedTo(ChronoUnit.SECONDS).toString()));
        attributes.put("SCHEDULE_ARN", stringAttribute(schedule.getArn()));
        attributes.put("TARGET_ARN", stringAttribute(target.getArn()));

        String body = delivery.requestBody() != null ? delivery.requestBody() : "{}";
        try {
            sqsService.sendMessage(AwsArnUtils.arnToQueueUrl(queueArn, baseUrl), body, 0, null, null,
                    attributes, AwsArnUtils.regionOrDefault(queueArn, regionOf(schedule)));
        } catch (Exception e) {
            LOG.warnv("Dead-letter delivery failed for schedule {0}: {1}", schedule.getArn(), e.getMessage());
        }
    }

    private static boolean isStale(Schedule current, Delivery delivery) {
        return current == null
                || current.getTarget() == null
                || !"ENABLED".equalsIgnoreCase(current.getState())
                || !Objects.equals(current.getLastModificationDate(), delivery.lastModificationDate());
    }

    private static boolean hasExpired(Schedule schedule, Occurrence occurrence, Instant now) {
        return now.isAfter(occurrence.scheduledAt().plusSeconds(maximumEventAgeSeconds(schedule)));
    }

    private static int maximumEventAgeSeconds(Schedule schedule) {
        RetryPolicy retryPolicy = schedule.getTarget().getRetryPolicy();
        return retryPolicy != null && retryPolicy.getMaximumEventAgeInSeconds() != null
                ? retryPolicy.getMaximumEventAgeInSeconds()
                : DEFAULT_MAXIMUM_EVENT_AGE_SECONDS;
    }

    private static int maximumRetryAttempts(Schedule schedule) {
        RetryPolicy retryPolicy = schedule.getTarget().getRetryPolicy();
        return retryPolicy != null && retryPolicy.getMaximumRetryAttempts() != null
                ? retryPolicy.getMaximumRetryAttempts()
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

    private void recordFire(Schedule schedule, Instant now) {
        lastFireByArn.put(schedule.getArn(), now);
        firedOnceByArn.put(schedule.getArn(), Boolean.TRUE);
    }

    private static boolean isDeleteAfterCompletion(Schedule schedule) {
        return "DELETE".equalsIgnoreCase(schedule.getActionAfterCompletion());
    }

    private static String regionOf(Schedule schedule) {
        return AwsArnUtils.regionOrDefault(schedule.getArn(), "us-east-1");
    }

    private record Occurrence(String scheduleArn, Instant scheduledAt) {
    }

    private record Delivery(Kind kind, Instant lastModificationDate, String executionId,
                            int retryAttempts, String errorCode, String errorMessage,
                            String requestBody, Instant nextAttemptAt) {

        static Delivery first(Kind kind, Schedule schedule, String requestBody) {
            String executionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            return new Delivery(kind, schedule.getLastModificationDate(), executionId, 0, null, null,
                    requestBody, null);
        }

        Delivery nextRetry() {
            return new Delivery(kind, lastModificationDate, executionId, retryAttempts + 1, errorCode, errorMessage,
                    requestBody, null);
        }

        Delivery failedWith(Exception e, Instant now) {
            String code = e instanceof AwsException aws && aws.getErrorCode() != null
                    ? aws.getErrorCode()
                    : DEFAULT_ERROR_CODE;
            String message = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage()
                    : e.getClass().getSimpleName();
            long delaySeconds = Math.min(86400L, 60L << Math.min(retryAttempts, 11));
            return new Delivery(kind, lastModificationDate, executionId, retryAttempts, code, message,
                    requestBody, now.plusSeconds(delaySeconds));
        }
    }
}
