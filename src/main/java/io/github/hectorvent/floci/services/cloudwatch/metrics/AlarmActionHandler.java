package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;

/**
 * Reacts to a CloudWatch alarm entering the {@code ALARM} state, the way a real AWS action
 * target (a scaling policy, an SNS topic, ...) would when listed in an alarm's
 * {@code AlarmActions}.
 *
 * <p>{@link AlarmEvaluator} discovers every CDI bean implementing this interface and
 * dispatches to the first one whose {@link #supports(String)} matches. An action ARN with no
 * matching handler is a no-op, the same as it is today for every action type.</p>
 */
public interface AlarmActionHandler {

    boolean supports(String actionArn);

    void handle(String actionArn, MetricAlarm alarm, double metricValue, String region);
}
