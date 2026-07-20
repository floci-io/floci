package io.github.hectorvent.floci.services.scheduler;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.scheduler.SchedulerExpressionParser.Kind;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
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
 *       {@code at(...)} schedules once fired.</li>
 *   <li>Targets: whatever {@link ScheduleInvoker} can deliver to (SQS, Lambda,
 *       SNS, EventBridge).</li>
 *   <li>Failures: logged; {@code RetryPolicy} / {@code DeadLetterConfig} are
 *       stored but not yet honored.</li>
 * </ul>
 */
@ApplicationScoped
public class ScheduleDispatcher implements Resettable {

    private static final Logger LOG = Logger.getLogger(ScheduleDispatcher.class);

    private final SchedulerService schedulerService;
    private final ScheduleInvoker invoker;
    private final long tickIntervalSeconds;
    private final boolean enabled;
    private final ScheduledExecutorService executor;
    private final ConcurrentHashMap<String, Instant> lastFireByArn = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> firedOnceByArn = new ConcurrentHashMap<>();

    @Inject
    public ScheduleDispatcher(SchedulerService schedulerService,
                              ScheduleInvoker invoker,
                              EmulatorConfig config) {
        this.schedulerService = schedulerService;
        this.invoker = invoker;
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
        for (Schedule schedule : schedules) {
            try {
                evaluate(schedule, now);
            } catch (Exception e) {
                LOG.warnv("Failed to evaluate schedule {0}: {1}", schedule.getArn(), e.getMessage());
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

        fire(schedule);
        recordFire(schedule, now);

        if (kind == Kind.AT && isDeleteAfterCompletion(schedule)) {
            try {
                schedulerService.deleteScheduleForAccount(
                        schedule.getAccountId(), schedule.getName(), schedule.getGroupName(), regionOf(schedule));
                lastFireByArn.remove(schedule.getArn());
                firedOnceByArn.remove(schedule.getArn());
            } catch (Exception e) {
                LOG.warnv("Post-completion delete failed for {0}: {1}", schedule.getArn(), e.getMessage());
            }
        }
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

    private void fire(Schedule schedule) {
        String region = regionOf(schedule);
        try {
            invoker.invoke(schedule.getTarget(), region);
            LOG.infov("Fired schedule {0} in group {1}", schedule.getName(), schedule.getGroupName());
        } catch (Exception e) {
            LOG.warnv("Schedule {0} invocation failed: {1}", schedule.getArn(), e.getMessage());
        }
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
}
