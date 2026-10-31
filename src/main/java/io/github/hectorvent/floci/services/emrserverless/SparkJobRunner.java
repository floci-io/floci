package io.github.hectorvent.floci.services.emrserverless;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Runs a Spark application to its end, in the EMR services' {@code docker} runner mode: an EMR
 * Serverless job run, or a step of an EMR cluster. The service holds the run's state and calls this
 * off the request thread; {@link EmrServerlessSparkRunner} is the Docker implementation, and a test
 * supplies its own.
 */
public interface SparkJobRunner {

    /**
     * Runs {@code job} and returns how it ended, telling {@code progress} once its Spark driver runs and
     * when it passes its timeout. Blocks until then. A run {@code progress} reports as cancelled before
     * its driver runs never starts it.
     */
    Outcome run(Job job, Progress progress);

    /**
     * Stops the run's driver, now or as soon as it starts, so {@link #run} returns {@link Outcome#halted};
     * a run {@link #run} has already returned from is left as it is.
     */
    void stop(String runId);

    /**
     * Removes the drivers of {@code service}'s runs an earlier Floci process left running, which no thread
     * follows any more. The service calls it before any run of its own can start.
     */
    void removeLeftoverDrivers(String service);

    /** What a run tells the service, and asks it, while it goes. */
    interface Progress {

        /** The run's Spark driver runs. */
        void started();

        /** The run has passed its timeout, and its driver is about to be stopped. */
        void timedOut();

        /**
         * Whether the run was cancelled, or is gone, as after a reset: its driver must not start, and one
         * that runs is stopped.
         */
        boolean cancelled();
    }

    /**
     * What a run needs: the service it belongs to and its id there, which name its container; its account
     * and Region; its release, and the image template and Docker network to run it in (a null template is
     * the release's public EMR Serverless image, a null network the global default); {@code customImage}, when
     * not null, the image the run's application names, which it runs in place of the template's, resolved
     * again for each run; the execution role whose credentials it gets; the {@code spark-defaults} properties
     * that apply to it;
     * {@code application}, what follows Floci's own settings on {@code spark-submit}'s command line (the
     * run's options, then its application and that application's arguments); or {@code command}, when not
     * null, what the container runs in place of {@code spark-submit}, where a {@code spark-submit} it starts
     * gets Floci's settings and the {@code spark-defaults} as defaults (its {@code application} is then
     * empty); how long it may run
     * ({@link Duration#ZERO} for no limit); the S3 folder its driver's {@code stdout.gz} and
     * {@code stderr.gz} go to once it ends, ending in a slash (null for none); and where in CloudWatch Logs
     * its driver's output goes while it runs (null for nowhere but Floci's log).
     */
    record Job(String service, String runId, String accountId, String region, String releaseLabel,
               String imageTemplate, String customImage, String dockerNetwork, String executionRole,
               Map<String, String> sparkDefaults, List<String> application, List<String> command, Duration timeout,
               String logFolder, CloudWatchLogs cloudWatchLogs) {
    }

    /**
     * A log group in the run's account and Region, and the log streams the driver's standard output and
     * standard error go to in it, either null for that output to go nowhere but Floci's log.
     */
    record CloudWatchLogs(String logGroup, String stdoutStream, String stderrStream) {
    }

    /**
     * How a run ended: its driver's exit code, or that it ran out of time, was stopped, or could not
     * start ({@code failure} says why).
     */
    record Outcome(Integer exitCode, boolean timedOut, boolean stopped, String failure) {

        public static Outcome exitedWith(int exitCode) {
            return new Outcome(exitCode, false, false, null);
        }

        public static Outcome outOfTime() {
            return new Outcome(null, true, false, null);
        }

        public static Outcome halted() {
            return new Outcome(null, false, true, null);
        }

        public static Outcome couldNotStart(String failure) {
            return new Outcome(null, false, false, failure);
        }
    }
}
