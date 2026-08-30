package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Evaluates every CloudWatch alarm that carries metric-math configuration
 * (namespace/metricName/period/evaluationPeriods/comparisonOperator), transitions its
 * {@code StateValue} the way real CloudWatch would, and while it is in {@code ALARM}
 * dispatches {@code AlarmActions} through {@link AlarmActionHandler} on every tick — not
 * just the first transition into that state, so an action deferred by a handler (e.g. a
 * scaling policy still in cooldown) is retried on a later tick rather than dropped for as
 * long as the breach continues.
 *
 * <p>Generic on purpose: this class has no notion of Application Auto Scaling or ECS. An
 * alarm created by hand (e.g. for a {@code StepScaling} policy, which AWS does not
 * auto-create alarms for) is evaluated identically to one Floci synthesized itself.</p>
 *
 * <p>The value handed to {@link AlarmActionHandler#handle} is always the most recent
 * <em>breaching</em> datapoint, not simply the most recent one chronologically — the two can
 * differ once {@code TreatMissingData} lets missing periods themselves cause an {@code ALARM}
 * transition, and dispatching a non-breaching real reading in that case would push a handler's
 * math in the wrong direction. When {@code TreatMissingData=breaching} reaches {@code ALARM}
 * with no populated datapoint breaching at all, {@code NaN} is dispatched instead: this class
 * has no notion of what a sound substitute value would be for any given handler's math, so
 * that decision is left to the handler, which does know its own policy's semantics.</p>
 */
@ApplicationScoped
public class AlarmEvaluator {

    private static final Logger LOG = Logger.getLogger(AlarmEvaluator.class);

    private final CloudWatchMetricsService metricsService;
    private final Instance<AlarmActionHandler> handlers;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "alarm-evaluator"));

    @Inject
    AlarmEvaluator(CloudWatchMetricsService metricsService, @Any Instance<AlarmActionHandler> handlers) {
        this.metricsService = metricsService;
        this.handlers = handlers;
    }

    @PostConstruct
    void start() {
        scheduler.scheduleAtFixedRate(this::evaluateAll, 5, 10, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    void onStart(@Observes StartupEvent event) {
        LOG.debug("Alarm evaluator initialized");
    }

    /**
     * The whole body, not just the per-alarm loop, is guarded: {@link
     * ScheduledExecutorService#scheduleAtFixedRate} silently stops all future executions
     * forever if the submitted task throws, so a transient failure fetching the alarm list
     * itself (not just evaluating one alarm) must not be allowed to kill every future tick.
     */
    void evaluateAll() {
        try {
            for (MetricAlarm alarm : metricsService.allAlarms()) {
                try {
                    evaluate(alarm);
                } catch (Exception e) {
                    LOG.warnv("Alarm evaluation failed for {0}: {1}", alarm.getAlarmName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.warnv(e, "Alarm evaluation tick failed: {0}", e.getMessage());
        }
    }

    /**
     * Evaluates the last {@code evaluationPeriods} <em>complete</em> periods, not counting
     * whichever period is still accumulating right now — matching real CloudWatch, which
     * evaluates elapsed periods only. Counting the in-progress period would make it look
     * "missing" on nearly every tick even under perfectly healthy, continuous reporting,
     * since data for it typically hasn't arrived yet.
     */
    void evaluate(MetricAlarm alarm) {
        if (!isEvaluable(alarm)) {
            return;
        }
        String region = alarm.getRegion();
        int period = alarm.getPeriod();
        int evaluationPeriods = alarm.getEvaluationPeriods();
        long nowBucket = (Instant.now().getEpochSecond() / period) * period;
        long lastCompleteBucket = nowBucket - period;
        long oldestBucket = lastCompleteBucket - (long) period * (evaluationPeriods - 1);
        Instant start = Instant.ofEpochSecond(oldestBucket);
        Instant end = Instant.ofEpochSecond(lastCompleteBucket + period - 1);

        List<CloudWatchMetricsService.Datapoint> recent = metricsService.getMetricStatistics(
                alarm.getNamespace(), alarm.getMetricName(), alarm.getDimensions(),
                start, end, period, List.of(alarm.getStatistic()), alarm.getUnit(), region);
        int missing = evaluationPeriods - recent.size();

        int breaching = 0;
        double latestValue = Double.NaN;
        for (CloudWatchMetricsService.Datapoint dp : recent) {
            double value = CloudWatchMetricsService.resolveStatValue(dp, alarm.getStatistic());
            if (breaches(value, alarm.getComparisonOperator(), alarm.getThreshold())) {
                breaching++;
                latestValue = value;
            }
        }

        String newState;
        String reason;
        if (missing > 0) {
            String treatMissingData = alarm.getTreatMissingData();
            if ("ignore".equalsIgnoreCase(treatMissingData)) {
                newState = alarm.getStateValue();
                reason = alarm.getStateReason();
            } else if ("breaching".equalsIgnoreCase(treatMissingData)) {
                newState = evaluateBreachCount(breaching + missing, alarm, evaluationPeriods);
                reason = "Threshold Crossed (missing datapoints treated as breaching): "
                        + (breaching + missing) + " datapoint(s) breaching the threshold.";
            } else if ("notBreaching".equalsIgnoreCase(treatMissingData)) {
                newState = evaluateBreachCount(breaching, alarm, evaluationPeriods);
                reason = "Threshold evaluated with missing datapoints treated as not breaching: "
                        + breaching + " datapoint(s) breaching the threshold.";
            } else {
                newState = "INSUFFICIENT_DATA";
                reason = "Insufficient Data: " + recent.size() + " of " + evaluationPeriods + " datapoints available";
            }
        } else {
            newState = evaluateBreachCount(breaching, alarm, evaluationPeriods);
            reason = ("ALARM".equals(newState) ? "Threshold Crossed: " : "Threshold Not Crossed: ")
                    + breaching + " datapoint(s) breaching the threshold.";
        }

        if (!newState.equals(alarm.getStateValue())) {
            metricsService.setAlarmState(alarm.getAlarmName(), newState, reason, null, region);
        }

        if ("ALARM".equals(newState) && alarm.isActionsEnabled()) {
            dispatch(alarm, latestValue, region);
        }
    }

    private static String evaluateBreachCount(int breaching, MetricAlarm alarm, int evaluationPeriods) {
        int datapointsToAlarm = alarm.getDatapointsToAlarm() > 0 ? alarm.getDatapointsToAlarm() : evaluationPeriods;
        return breaching >= datapointsToAlarm ? "ALARM" : "OK";
    }

    private void dispatch(MetricAlarm alarm, double metricValue, String region) {
        for (String actionArn : alarm.getAlarmActions()) {
            for (AlarmActionHandler handler : handlers) {
                if (handler.supports(actionArn)) {
                    try {
                        handler.handle(actionArn, alarm, metricValue, region);
                    } catch (Exception e) {
                        LOG.warnv("Alarm action {0} failed for {1}: {2}",
                                actionArn, alarm.getAlarmName(), e.getMessage());
                    }
                    break;
                }
            }
        }
    }

    private static boolean isEvaluable(MetricAlarm alarm) {
        return alarm.getRegion() != null
                && alarm.getNamespace() != null && !alarm.getNamespace().isBlank()
                && alarm.getMetricName() != null && !alarm.getMetricName().isBlank()
                && alarm.getPeriod() > 0
                && alarm.getEvaluationPeriods() > 0
                && alarm.getComparisonOperator() != null;
    }

    private static boolean breaches(double value, String comparisonOperator, double threshold) {
        return switch (comparisonOperator) {
            case "GreaterThanThreshold" -> value > threshold;
            case "GreaterThanOrEqualToThreshold" -> value >= threshold;
            case "LessThanThreshold" -> value < threshold;
            case "LessThanOrEqualToThreshold" -> value <= threshold;
            default -> false;
        };
    }
}
