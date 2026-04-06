package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.eventbridge.model.Rule;
import io.github.hectorvent.floci.services.eventbridge.model.RuleState;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@ApplicationScoped
public class RuleScheduler {

    private static final Logger LOG = Logger.getLogger(RuleScheduler.class);

    private final Vertx vertx;
    private final ObjectMapper objectMapper;
    private final String accountId;
    private final EventBridgeInvoker invoker;
    private final ConcurrentHashMap<String, ScheduleContext> scheduleContexts = new ConcurrentHashMap<>();

    @Inject
    public RuleScheduler(Vertx vertx,
                          EmulatorConfig config,
                          ObjectMapper objectMapper,
                          EventBridgeInvoker invoker) {
        this.vertx = vertx;
        this.objectMapper = objectMapper;
        this.accountId = config.defaultAccountId();
        this.invoker = invoker;
    }

    @PreDestroy
    void shutdown() {
        scheduleContexts.values().forEach(ctx -> vertx.cancelTimer(ctx.timerId));
        scheduleContexts.clear();
        LOG.info("RuleScheduler shut down, all timers cancelled");
    }

    public void startScheduler(String ruleArn, String scheduleExpr, 
                               Supplier<ScheduleData> dataSupplier) {
        if (scheduleContexts.containsKey(ruleArn)) {
            return;
        }

        if (scheduleExpr == null || scheduleExpr.isBlank()) {
            LOG.warnv("Cannot start scheduler for rule {0}: no schedule expression", ruleArn);
            return;
        }

        try {
            if (ScheduleExpressionParser.isRateExpression(scheduleExpr)) {
                startRateScheduler(ruleArn, scheduleExpr, dataSupplier);
            } else if (ScheduleExpressionParser.isCronExpression(scheduleExpr)) {
                scheduleCronFire(ruleArn, scheduleExpr, dataSupplier);
            } else {
                LOG.warnv("Unknown schedule expression format for rule {0}: {1}", ruleArn, scheduleExpr);
            }
        } catch (Exception e) {
            LOG.warnv("Failed to parse schedule expression for rule {0}: {1}", ruleArn, e.getMessage());
        }
    }

    private void startRateScheduler(String ruleArn, String scheduleExpr,
                                    Supplier<ScheduleData> dataSupplier) {
        long intervalMs = ScheduleExpressionParser.parseRateToMillis(scheduleExpr);

        tick(dataSupplier);
        long timerId = vertx.setPeriodic(intervalMs, id -> tick(dataSupplier));
        scheduleContexts.put(ruleArn, new ScheduleContext(timerId, scheduleExpr));
        LOG.debugv("Started rate scheduler for rule {0} with interval {1}ms", ruleArn, intervalMs);
    }

    private void scheduleCronFire(String ruleArn, String scheduleExpr,
                                  Supplier<ScheduleData> dataSupplier) {
        long delayMs;
        try {
            delayMs = ScheduleExpressionParser.millisUntilNextFire(scheduleExpr, ZonedDateTime.now());
        } catch (Exception e) {
            LOG.warnv("Failed to compute next fire time for rule {0}: {1}", ruleArn, e.getMessage());
            return;
        }

        long timerId = vertx.setTimer(delayMs, id -> {
            tick(dataSupplier);
            scheduleContexts.remove(ruleArn);
            scheduleCronFire(ruleArn, scheduleExpr, dataSupplier);
        });
        scheduleContexts.put(ruleArn, new ScheduleContext(timerId, scheduleExpr));
        LOG.debugv("Scheduled cron fire for rule {0} in {1}ms", ruleArn, delayMs);
    }

    public void stopScheduler(String ruleArn) {
        ScheduleContext ctx = scheduleContexts.remove(ruleArn);
        if (ctx != null) {
            vertx.cancelTimer(ctx.timerId);
            LOG.debugv("Stopped scheduler for rule {0}", ruleArn);
        }
    }

    private void tick(Supplier<ScheduleData> dataSupplier) {
        ScheduleData data = dataSupplier.get();
        
        if (data == null || data.rule == null) {
            LOG.debugv("Rule no longer exists, stopping scheduler");
            stopScheduler(data != null && data.rule != null ? data.rule.getArn() : null);
            return;
        }

        if (data.rule.getState() != RuleState.ENABLED) {
            LOG.debugv("Rule {0} is disabled, skipping tick", data.rule.getName());
            return;
        }

        if (data.targets.isEmpty()) {
            LOG.debugv("Rule {0} has no targets, skipping tick", data.rule.getName());
            return;
        }

        String region = data.rule.getRegion() != null ? data.rule.getRegion() : "us-east-1";
        String eventJson = buildScheduledEvent(data.rule, region);
        LOG.debugv("Rule {0} firing scheduled event", data.rule.getName());

        for (Target target : data.targets) {
            try {
                invoker.invokeTarget(target, eventJson, region);
            } catch (Exception e) {
                LOG.warnv("Failed to invoke target {0} for rule {1}: {2}",
                        target.getId(), data.rule.getName(), e.getMessage());
            }
        }
    }

    private String buildScheduledEvent(Rule rule, String region) {
        try {
            ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
            ObjectNode node = objectMapper.createObjectNode();
            node.put("version", "0");
            node.put("id", UUID.randomUUID().toString());
            node.put("source", "aws.events");
            node.put("detail-type", "Scheduled Event");
            node.put("account", accountId);
            node.put("time", now.toInstant().toString());
            node.put("region", region);
            node.putArray("resources").add(rule.getArn());
            node.putObject("detail");
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            LOG.warnv("Failed to build scheduled event: {0}", e.getMessage());
            return "{\"version\":\"0\",\"source\":\"aws.events\",\"detail-type\":\"Scheduled Event\",\"detail\":{}}";
        }
    }

    public boolean isRunning(String ruleArn) {
        return scheduleContexts.containsKey(ruleArn);
    }

    public int getActiveSchedulerCount() {
        return scheduleContexts.size();
    }

    public record ScheduleData(Rule rule, List<Target> targets) {}

    private record ScheduleContext(long timerId, String scheduleExpr) {}
}
