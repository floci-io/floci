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
import java.util.ArrayList;
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
     * Queries the <em>evaluation range</em> — more periods than {@code evaluationPeriods} — and
     * resolves the state with CloudWatch's documented precedence ("How alarm state is evaluated
     * when data is missing"): real datapoints reaching further back are preferred, and {@code
     * TreatMissingData} only fills what real data cannot cover.
     *
     * <p>Once the range holds at least {@code evaluationPeriods} real datapoints, the most recent
     * of those decide the state and {@code TreatMissingData} is not consulted at all — AWS: "the
     * value you set for how to treat missing data is not needed and is ignored". Reaching further
     * back is precisely what makes that branch reachable, so widening the query narrows how often
     * the fallback applies without changing what any of its modes mean.</p>
     *
     * <p>Only <em>complete</em> periods are considered; the period still accumulating right now
     * is excluded, since data for it typically has not arrived yet and counting it would make it
     * look missing on nearly every tick even under perfectly healthy reporting.</p>
     */
    void evaluate(MetricAlarm alarm) {
        if (!isEvaluable(alarm)) {
            return;
        }
        String region = alarm.getRegion();
        int period = alarm.getPeriod();
        int evaluationPeriods = alarm.getEvaluationPeriods();
        int range = evaluationRange(evaluationPeriods);
        long nowBucket = (Instant.now().getEpochSecond() / period) * period;
        long lastCompleteBucket = nowBucket - period;
        long oldestBucket = lastCompleteBucket - (long) period * (range - 1);
        Instant start = Instant.ofEpochSecond(oldestBucket);
        Instant end = Instant.ofEpochSecond(lastCompleteBucket + period - 1);

        List<CloudWatchMetricsService.Datapoint> retrieved = metricsService.getMetricStatistics(
                alarm.getNamespace(), alarm.getMetricName(), alarm.getDimensions(),
                start, end, period, List.of(alarm.getStatistic()), alarm.getUnit(), region);

        Double[] buckets = new Double[range];
        for (CloudWatchMetricsService.Datapoint dp : retrieved) {
            long bucket = (dp.timestamp().getEpochSecond() / period) * period;
            int index = (int) ((bucket - oldestBucket) / period);
            if (index >= 0 && index < range) {
                buckets[index] = CloudWatchMetricsService.resolveStatValue(dp, alarm.getStatistic());
            }
        }

        List<Double> real = new ArrayList<>();
        for (Double value : buckets) {
            if (value != null) {
                real.add(value);
            }
        }

        String newState;
        String reason;
        double latestValue;
        if (real.size() >= evaluationPeriods) {
            List<Double> evaluated = real.subList(real.size() - evaluationPeriods, real.size());
            int breaching = countBreaches(evaluated, alarm);
            latestValue = latestBreachingValue(evaluated, alarm);
            newState = evaluateBreachCount(breaching, alarm, evaluationPeriods);
            reason = ("ALARM".equals(newState) ? "Threshold Crossed: " : "Threshold Not Crossed: ")
                    + breaching + " datapoint(s) breaching the threshold.";
        } else {
            int fill = evaluationPeriods - real.size();
            int breaching = countBreaches(real, alarm);
            latestValue = latestBreachingValue(real, alarm);
            String treatMissingData = alarm.getTreatMissingData();
            if ("ignore".equalsIgnoreCase(treatMissingData)) {
                newState = alarm.getStateValue();
                reason = alarm.getStateReason();
            } else if ("breaching".equalsIgnoreCase(treatMissingData)) {
                newState = evaluateBreachCount(breaching + fill, alarm, evaluationPeriods);
                reason = "Threshold Crossed (missing datapoints treated as breaching): "
                        + (breaching + fill) + " datapoint(s) breaching the threshold.";
            } else if ("notBreaching".equalsIgnoreCase(treatMissingData)) {
                newState = evaluateBreachCount(breaching, alarm, evaluationPeriods);
                reason = "Threshold evaluated with missing datapoints treated as not breaching: "
                        + breaching + " datapoint(s) breaching the threshold.";
            } else if (real.isEmpty()) {
                newState = "INSUFFICIENT_DATA";
                reason = "Insufficient Data: 0 of " + evaluationPeriods + " datapoints available";
            } else if (settledBreach(buckets, alarm, evaluationPeriods)) {
                newState = "ALARM";
                reason = "Threshold Crossed: the oldest breaching datapoint is old enough to alarm "
                        + "and every more recent datapoint is breaching or missing.";
            } else {
                newState = evaluateBreachCount(breaching, alarm, evaluationPeriods);
                reason = ("ALARM".equals(newState) ? "Threshold Crossed: " : "Threshold Not Crossed: ")
                        + breaching + " real datapoint(s) breaching the threshold.";
            }
        }

        if (!newState.equals(alarm.getStateValue())) {
            metricsService.setAlarmState(alarm.getAlarmName(), newState, reason, null, region);
        }

        if ("ALARM".equals(newState) && alarm.isActionsEnabled()) {
            dispatch(alarm, latestValue, region);
        }
    }

    /**
     * How many periods to retrieve. AWS states only that it is "a higher number of data points
     * than the number specified as Evaluation Periods", varying with period length and metric
     * resolution, without publishing the formula; its one worked example pairs
     * {@code EvaluationPeriods = 3} with an evaluation range of 5, which is what this reproduces.
     */
    private static int evaluationRange(int evaluationPeriods) {
        return evaluationPeriods + 2;
    }

    /**
     * CloudWatch's premature-transition rule, which fires when {@code TreatMissingData} is left at
     * its {@code missing} default: an alarm goes to {@code ALARM} "when the oldest available
     * breaching datapoint during the Evaluation Periods number of data points is at least as old
     * as the value of Datapoints to Alarm" and every more recent datapoint is breaching or
     * missing. A lone breach at the very end of the window ({@code - - - - X}) deliberately does
     * not qualify — the next datapoint may be non-breaching — while one that has already aged past
     * {@code DatapointsToAlarm} ({@code - - X - -}) does, even with fewer real datapoints than M.
     *
     * <p>"All other" spans the whole evaluation range, not just the most recent {@code
     * EvaluationPeriods}: a single non-breaching reading anywhere in the range disqualifies the
     * shortcut. That is what separates {@code 0 - X - -} (documented {@code OK} at
     * {@code DatapointsToAlarm = 2}) from {@code - - X - -} (documented {@code ALARM}), since the
     * two are indistinguishable across their most recent three buckets alone.</p>
     */
    private static boolean settledBreach(Double[] buckets, MetricAlarm alarm, int evaluationPeriods) {
        int datapointsToAlarm = alarm.getDatapointsToAlarm() > 0
                ? alarm.getDatapointsToAlarm() : evaluationPeriods;
        int oldestBreachingAge = 0;
        for (int age = 1; age <= buckets.length; age++) {
            Double value = buckets[buckets.length - age];
            if (value == null) {
                continue;
            }
            if (!breaches(value, alarm.getComparisonOperator(), alarm.getThreshold())) {
                return false;
            }
            oldestBreachingAge = age;
        }
        return oldestBreachingAge >= datapointsToAlarm;
    }

    private static int countBreaches(List<Double> values, MetricAlarm alarm) {
        int breaching = 0;
        for (Double value : values) {
            if (breaches(value, alarm.getComparisonOperator(), alarm.getThreshold())) {
                breaching++;
            }
        }
        return breaching;
    }

    private static double latestBreachingValue(List<Double> values, MetricAlarm alarm) {
        double latest = Double.NaN;
        for (Double value : values) {
            if (breaches(value, alarm.getComparisonOperator(), alarm.getThreshold())) {
                latest = value;
            }
        }
        return latest;
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
