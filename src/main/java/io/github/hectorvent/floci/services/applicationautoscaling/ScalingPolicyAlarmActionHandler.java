package io.github.hectorvent.floci.services.applicationautoscaling;

import io.github.hectorvent.floci.services.applicationautoscaling.model.ScalableTarget;
import io.github.hectorvent.floci.services.applicationautoscaling.model.ScalingPolicy;
import io.github.hectorvent.floci.services.applicationautoscaling.model.StepAdjustment;
import io.github.hectorvent.floci.services.applicationautoscaling.model.StepScalingConfiguration;
import io.github.hectorvent.floci.services.applicationautoscaling.model.TargetTrackingConfiguration;
import io.github.hectorvent.floci.services.cloudwatch.metrics.AlarmActionHandler;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * Invokes a target-tracking or step-scaling policy when the CloudWatch alarm behind it fires.
 *
 * <p>The alarm's {@code comparisonOperator} (rather than Floci's own {@code AlarmHigh}/{@code
 * AlarmLow} naming, which only its own synthesized alarms carry) selects the
 * TargetTrackingScaling formula's fallback direction when no real datapoint is available. Every
 * other decision (suspension, cooldown, and {@code DisableScaleIn}) keys off the actual computed
 * capacity delta instead: a StepScaling alarm's matched step, or clamping to {@code
 * MaxCapacity}/{@code MinCapacity}, can both move capacity opposite to the alarm's own comparison
 * direction, so checking any of those against the alarm's labeled direction rather than the
 * resulting adjustment would let a suspended or disabled direction through.</p>
 *
 * <p>Cooldown gating follows AWS's documented target-tracking cooldown semantics (see
 * "Define cooldown periods" in the Application Auto Scaling user guide), which this handler
 * applies to StepScaling too: a scale-out is blocked while its cooldown is active <em>unless
 * the newly computed capacity is larger than what the last scale-out already applied</em>, in
 * which case it proceeds immediately — cooldown exists to stop flapping on small repeated
 * moves, not to cap a scalable target's climb toward {@code MaxCapacity} while a breach
 * persists. Scale-in has no such carve-out (AWS documents none): it is simply blocked for the
 * full cooldown window. {@code ScalingPolicy.lastScaleOutCapacity} is what "larger than"
 * compares against — a dedicated field, separate from {@code lastAppliedCapacity} (which a
 * scale-in also updates), so an intervening scale-in can't corrupt the scale-out high-water
 * mark and let a later scale-out through cooldown just because it beats the scale-in's lower
 * capacity rather than the original scale-out's. Independently, if current capacity no longer
 * matches {@code lastAppliedCapacity} at all (an operator, or another mechanism, changed it
 * since either kind of action), the carve-out is treated as satisfied unconditionally: the
 * situation has changed underneath the policy, so the "avoid flapping on our own repeated
 * moves" rationale no longer applies, and a persistent breach must not stay stuck below where
 * it belongs for a full cooldown window just because of an unrelated capacity change. Cooldown
 * defaults to 300s when unset, matching
 * AWS's documented default for ECS services (the only scalable dimension this handler drives
 * today) rather than inventing a different value.</p>
 *
 * <p>{@link AlarmEvaluator} dispatches on every tick a breach persists, not only the first
 * transition into {@code ALARM}, so a cooldown-deferred action gets retried on a later tick
 * instead of being dropped.</p>
 */
@ApplicationScoped
public class ScalingPolicyAlarmActionHandler implements AlarmActionHandler {

    private static final Logger LOG = Logger.getLogger(ScalingPolicyAlarmActionHandler.class);

    /**
     * AWS's documented per-service default when a cooldown isn't explicitly configured. ECS is
     * the only scalable dimension this handler drives today, so its default (also shared by
     * most other scalable-target types) is the one that applies here.
     */
    private static final int DEFAULT_COOLDOWN_SECONDS = 300;

    private final ApplicationAutoScalingService service;
    private final Instance<CapacityAdjuster> adjusters;

    @Inject
    public ScalingPolicyAlarmActionHandler(ApplicationAutoScalingService service,
                                           @Any Instance<CapacityAdjuster> adjusters) {
        this.service = service;
        this.adjusters = adjusters;
    }

    @Override
    public boolean supports(String actionArn) {
        return actionArn != null && actionArn.contains(":autoscaling:") && actionArn.contains(":scalingPolicy:");
    }

    @Override
    public void handle(String actionArn, MetricAlarm alarm, double metricValue, String region) {
        ScalingPolicy policy = service.findPolicyByArn(actionArn, region).orElse(null);
        if (policy == null) {
            LOG.debugv("No scaling policy found for alarm action {0}", actionArn);
            return;
        }
        ScalableTarget target = service.findTarget(region, policy.getServiceNamespace(),
                policy.getResourceId(), policy.getScalableDimension()).orElse(null);
        if (target == null) {
            LOG.debugv("No scalable target for policy {0}", policy.getPolicyName());
            return;
        }

        CapacityAdjuster adjuster = findAdjuster(target.getScalableDimension());
        if (adjuster == null) {
            LOG.debugv("No capacity adjuster registered for dimension {0}; policy {1} stays inert",
                    target.getScalableDimension(), policy.getPolicyName());
            return;
        }

        boolean scaleOut = isScaleOutAlarm(alarm);
        int current = adjuster.getCurrentCapacity(target.getResourceId(), region);
        Integer computed = "StepScaling".equals(policy.getPolicyType())
                ? computeStepScaling(policy, current, metricValue, alarm.getThreshold())
                : computeTargetTracking(policy, current, metricValue, scaleOut);
        if (computed == null) {
            return;
        }

        int newCapacity = clamp(computed, target.getMinCapacity(), target.getMaxCapacity());
        if (newCapacity == current) {
            return;
        }
        if (newCapacity < current && isScaleInDisabled(policy)) {
            return;
        }

        boolean isScaleOutAction = newCapacity > current;
        if (isScaleOutAction) {
            if (target.getSuspendedState().isDynamicScalingOutSuspended()) {
                return;
            }
            Integer lastApplied = policy.getLastAppliedCapacity();
            boolean driftedSinceLastAction = lastApplied != null && lastApplied != current;
            Integer lastScaleOut = policy.getLastScaleOutCapacity();
            boolean largerThanLastScaleOut = lastScaleOut == null || driftedSinceLastAction || newCapacity > lastScaleOut;
            if (!largerThanLastScaleOut && withinCooldown(policy, true)) {
                return;
            }
        } else {
            if (target.getSuspendedState().isDynamicScalingInSuspended()) {
                return;
            }
            if (withinCooldown(policy, false)) {
                return;
            }
        }

        adjuster.setCapacity(target.getResourceId(), newCapacity, region);
        stampCooldown(policy, isScaleOutAction);
        policy.setLastAppliedCapacity(newCapacity);
        if (isScaleOutAction) {
            policy.setLastScaleOutCapacity(newCapacity);
        }
        service.savePolicy(policy, region);
        service.recordScalingActivity(policy,
                "Setting desired capacity to " + newCapacity,
                "monitor alarm " + alarm.getAlarmName() + " in state ALARM triggered policy "
                        + policy.getPolicyName(),
                region);
        LOG.infov("Policy {0} adjusted {1}: {2} -> {3}",
                policy.getPolicyName(), target.getResourceId(), current, newCapacity);
    }

    private CapacityAdjuster findAdjuster(String scalableDimension) {
        for (CapacityAdjuster adjuster : adjusters) {
            if (adjuster.supports(scalableDimension)) {
                return adjuster;
            }
        }
        return null;
    }

    private static boolean isScaleOutAlarm(MetricAlarm alarm) {
        String op = alarm.getComparisonOperator();
        return op != null && op.startsWith("GreaterThan");
    }

    /**
     * Mirrors the class-level note on suspension/cooldown: {@code DisableScaleIn} is checked
     * against the final, post-clamp capacity in {@link #handle}, not against the raw ratio
     * computed here. Clamping to {@code MaxCapacity} can turn a formula result that looked like
     * a scale-out (computed above {@code current}) into an actual decrease once capped — if this
     * method suppressed based on its own unclamped output, that clamp-induced decrease would
     * slip past the {@code DisableScaleIn} guard entirely.
     */
    private static Integer computeTargetTracking(ScalingPolicy policy, int current, double metricValue,
                                                  boolean scaleOut) {
        TargetTrackingConfiguration config = policy.getTargetTrackingConfiguration();
        if (config == null || config.getTargetValue() == null || config.getTargetValue() == 0) {
            return null;
        }
        return Double.isNaN(metricValue)
                ? (scaleOut ? current + 1 : current - 1)
                : (int) Math.ceil(current * metricValue / config.getTargetValue());
    }

    private static boolean isScaleInDisabled(ScalingPolicy policy) {
        TargetTrackingConfiguration config = policy.getTargetTrackingConfiguration();
        return config != null && Boolean.TRUE.equals(config.getDisableScaleIn());
    }

    /**
     * AWS resolves step adjustments by comparing {@code metricValue - threshold} against each
     * step's interval bounds, which are relative to the alarm's threshold, not absolute. With
     * no real datapoint to compare ({@code metricValue} is {@code NaN}, see {@link
     * #computeTargetTracking}), a delta of exactly zero — sitting right at the threshold the
     * alarm itself defines — is the step scaling equivalent of that same "assume the boundary,
     * nothing more" fallback.
     */
    private static Integer computeStepScaling(ScalingPolicy policy, int current, double metricValue,
                                               double threshold) {
        StepScalingConfiguration config = policy.getStepScalingConfiguration();
        if (config == null || config.getStepAdjustments().isEmpty()) {
            return null;
        }
        double delta = Double.isNaN(metricValue) ? 0 : metricValue - threshold;
        StepAdjustment matched = null;
        for (StepAdjustment step : config.getStepAdjustments()) {
            double lower = step.getMetricIntervalLowerBound() != null
                    ? step.getMetricIntervalLowerBound() : Double.NEGATIVE_INFINITY;
            double upper = step.getMetricIntervalUpperBound() != null
                    ? step.getMetricIntervalUpperBound() : Double.POSITIVE_INFINITY;
            if (delta >= lower && delta < upper) {
                matched = step;
                break;
            }
        }
        if (matched == null || matched.getScalingAdjustment() == null) {
            return null;
        }
        int adjustment = matched.getScalingAdjustment();
        String adjustmentType = config.getAdjustmentType() != null ? config.getAdjustmentType() : "ChangeInCapacity";
        return switch (adjustmentType) {
            case "ExactCapacity" -> adjustment;
            case "PercentChangeInCapacity" -> {
                int magnitude = (int) Math.round(current * adjustment / 100.0);
                if (config.getMinAdjustmentMagnitude() != null
                        && Math.abs(magnitude) < config.getMinAdjustmentMagnitude()) {
                    magnitude = adjustment >= 0
                            ? config.getMinAdjustmentMagnitude() : -config.getMinAdjustmentMagnitude();
                }
                yield current + magnitude;
            }
            default -> current + adjustment;
        };
    }

    private static boolean withinCooldown(ScalingPolicy policy, boolean scaleOut) {
        int cooldownSeconds = cooldownSeconds(policy, scaleOut);
        double last = scaleOut ? policy.getLastScaleOutTime() : policy.getLastScaleInTime();
        return last > 0 && (Instant.now().getEpochSecond() - last) < cooldownSeconds;
    }

    private static int cooldownSeconds(ScalingPolicy policy, boolean scaleOut) {
        if ("StepScaling".equals(policy.getPolicyType())) {
            StepScalingConfiguration config = policy.getStepScalingConfiguration();
            return config != null && config.getCooldown() != null ? config.getCooldown() : DEFAULT_COOLDOWN_SECONDS;
        }
        TargetTrackingConfiguration config = policy.getTargetTrackingConfiguration();
        if (config == null) {
            return DEFAULT_COOLDOWN_SECONDS;
        }
        Integer cooldown = scaleOut ? config.getScaleOutCooldown() : config.getScaleInCooldown();
        return cooldown != null ? cooldown : DEFAULT_COOLDOWN_SECONDS;
    }

    private static void stampCooldown(ScalingPolicy policy, boolean scaleOut) {
        double now = Instant.now().getEpochSecond();
        if (scaleOut) {
            policy.setLastScaleOutTime(now);
        } else {
            policy.setLastScaleInTime(now);
        }
    }

    private static int clamp(int value, Integer min, Integer max) {
        int lo = min != null ? min : Integer.MIN_VALUE;
        int hi = max != null ? max : Integer.MAX_VALUE;
        return Math.max(lo, Math.min(hi, value));
    }
}
