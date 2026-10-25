package io.github.hectorvent.floci.services.emrserverless;

import io.github.hectorvent.floci.services.emrserverless.model.JobDriver;

import java.time.Duration;
import java.util.Map;

/**
 * Runs a Spark job run to its end, in the {@code docker} runner mode. The job run service holds the
 * run's state and calls this off the request thread; {@link EmrServerlessSparkRunner} is the Docker
 * implementation, and a test supplies its own.
 */
interface SparkJobRunner {

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
    void stop(String jobRunId);

    /** What a run tells the job run service, and asks it, while it goes. */
    interface Progress {

        /** The run's Spark driver runs. */
        void started();

        /** The run has passed its timeout, and its driver is about to be stopped. */
        void timedOut();

        /** Whether the run was cancelled, or is gone, so that its driver must not start. */
        boolean cancelled();
    }

    /**
     * What a run needs: its identity, the application's release, the run's execution role, its Spark
     * submission, the {@code spark-defaults} properties that apply to it, and how long it may run
     * ({@link Duration#ZERO} for no limit).
     */
    record Job(String jobRunId, String applicationId, String accountId, String region, String releaseLabel,
               String executionRole, JobDriver.SparkSubmit sparkSubmit, Map<String, String> sparkDefaults,
               Duration timeout) {
    }

    /**
     * How a run ended: its driver's exit code, or that it ran out of time, was stopped, or could not
     * start ({@code failure} says why).
     */
    record Outcome(Integer exitCode, boolean timedOut, boolean stopped, String failure) {

        static Outcome exitedWith(int exitCode) {
            return new Outcome(exitCode, false, false, null);
        }

        static Outcome outOfTime() {
            return new Outcome(null, true, false, null);
        }

        static Outcome halted() {
            return new Outcome(null, false, true, null);
        }

        static Outcome couldNotStart(String failure) {
            return new Outcome(null, false, false, failure);
        }
    }
}
