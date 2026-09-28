package io.github.hectorvent.floci.services.cloudwatch.metrics;

/**
 * Lets tests outside this package run an alarm evaluation pass on demand instead of waiting
 * for the evaluator's scheduled tick.
 */
public final class AlarmEvaluatorTestAccess {

    private AlarmEvaluatorTestAccess() {
    }

    public static void evaluateNow(AlarmEvaluator evaluator) {
        evaluator.evaluateAll();
    }
}
