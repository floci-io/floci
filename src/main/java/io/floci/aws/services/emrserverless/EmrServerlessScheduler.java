package io.floci.aws.services.emrserverless;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.emrserverless.model.Application;
import io.floci.aws.services.emrserverless.model.SchedulerConfiguration;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * An application's job concurrency and queuing, from the EMR Serverless user guide's "Job concurrency and queuing for
 * an EMR Serverless application": at most {@code maxConcurrentRuns} of its batch and streaming job runs run at once,
 * and the rest wait QUEUED until one of them ends, or until {@code queueTimeoutMinutes} have passed. It "is supported
 * on Amazon EMR release 7.0.0 and higher" and "enabled by default on Amazon EMR release 7.3.0 and higher".
 */
final class EmrServerlessScheduler {

    static final String MAX_CONCURRENT_RUNS = "maxConcurrentRuns";
    static final String QUEUE_TIMEOUT_MINUTES = "queueTimeoutMinutes";
    // SchedulerConfiguration: "If scheduler configuration is enabled on your application, the default value is 15", and
    // 360 minutes for the queue timeout; "The valid range is 1 to 1000", and "from 15 to 720".
    static final int DEFAULT_MAX_CONCURRENT_RUNS = 15;
    static final int DEFAULT_QUEUE_TIMEOUT_MINUTES = 360;
    private static final int MAX_CONCURRENT_RUNS_LIMIT = 1000;
    private static final int MIN_QUEUE_TIMEOUT_MINUTES = 15;
    private static final int MAX_QUEUE_TIMEOUT_MINUTES = 720;

    /** How many of an application's runs may run at once, and how long one may wait QUEUED. */
    record Limits(int maxConcurrentRuns, Duration queueTimeout) {
    }

    private EmrServerlessScheduler() {
    }

    /**
     * The limits an application's runs keep to, or null when its runs never queue: on a release before 7.0.0, on one
     * before 7.3.0 whose application sets none, and on any whose application an empty configuration turned queuing
     * off. The AWS runtime for Apache Spark's releases come after 7.14.0.
     */
    static Limits limits(Application app) {
        if (Boolean.TRUE.equals(app.getSchedulerOff())) {
            return null;
        }
        String release = app.getReleaseLabel();
        boolean sparkRuntime = EmrServerlessJobRunService.sparkRuntime(release);
        if (!sparkRuntime && !EmrServerlessJobRunService.releaseAtLeast(EmrServerlessJobRunService.RELEASE_LABEL,
                release, 7, 0)) {
            return null;
        }
        SchedulerConfiguration configuration = app.getSchedulerConfiguration();
        boolean configured = configuration != null && !configuration.getProperties().isEmpty();
        if (!configured && !sparkRuntime && !EmrServerlessJobRunService.releaseAtLeast(
                EmrServerlessJobRunService.RELEASE_LABEL, release, 7, 3)) {
            return null;
        }
        Map<String, Object> properties = configured ? configuration.getProperties() : Map.of();
        return new Limits(member(properties, MAX_CONCURRENT_RUNS, DEFAULT_MAX_CONCURRENT_RUNS),
                Duration.ofMinutes(member(properties, QUEUE_TIMEOUT_MINUTES, DEFAULT_QUEUE_TIMEOUT_MINUTES)));
    }

    /**
     * The scheduler configuration an application holds once a request sets {@code requested} over {@code current}.
     * Terraform's acceptance tests, run against AWS, found that an empty one turns queuing off and leaves the
     * application without one, which this gives as null, and that a member it leaves out takes its default; here a
     * member left out keeps the value {@code current} holds, or else takes its default. No source gives AWS's refusal
     * of a value out of range, so its wording is Floci's.
     */
    static SchedulerConfiguration merged(SchedulerConfiguration current, SchedulerConfiguration requested) {
        Map<String, Object> asked = requested.getProperties();
        if (asked.isEmpty()) {
            return null;
        }
        Map<String, Object> held = current != null ? current.getProperties() : Map.of();
        SchedulerConfiguration merged = new SchedulerConfiguration();
        merged.setProperty(MAX_CONCURRENT_RUNS, checked(asked, MAX_CONCURRENT_RUNS, 1, MAX_CONCURRENT_RUNS_LIMIT,
                member(held, MAX_CONCURRENT_RUNS, DEFAULT_MAX_CONCURRENT_RUNS)));
        merged.setProperty(QUEUE_TIMEOUT_MINUTES, checked(asked, QUEUE_TIMEOUT_MINUTES, MIN_QUEUE_TIMEOUT_MINUTES,
                MAX_QUEUE_TIMEOUT_MINUTES, member(held, QUEUE_TIMEOUT_MINUTES, DEFAULT_QUEUE_TIMEOUT_MINUTES)));
        return merged;
    }

    /**
     * Sets on the application the configuration {@link #merged} gave, null for none: an empty one asked for turns
     * queuing off, as "specifying an empty SchedulerConfiguration{} (without any attributes) disables the feature".
     */
    static void apply(Application app, SchedulerConfiguration merged) {
        app.setSchedulerConfiguration(merged);
        app.setSchedulerOff(merged == null ? Boolean.TRUE : null);
    }

    /** Whether an update's scheduler configuration changes only what a STARTED application lets change. */
    static boolean startedApplicationMayTake(SchedulerConfiguration requested) {
        // "You can update maxConcurrentRuns for an application in the STARTED state."
        return requested == null || requested.getProperties().keySet().equals(Set.of(MAX_CONCURRENT_RUNS));
    }

    private static int member(Map<String, Object> properties, String name, int fallback) {
        return properties.get(name) instanceof Number number ? number.intValue() : fallback;
    }

    private static int checked(Map<String, Object> asked, String name, int min, int max, int fallback) {
        Object value = asked.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < min
                || ((Number) value).longValue() > max) {
            throw new AwsException("ValidationException", "schedulerConfiguration." + name
                    + " must be an integer from " + min + " to " + max + ".", 400);
        }
        return ((Number) value).intValue();
    }
}
