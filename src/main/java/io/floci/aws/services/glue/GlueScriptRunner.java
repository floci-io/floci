package io.floci.aws.services.glue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs the script of a Glue Spark job to its end, in the {@code docker} runner mode. {@link GlueJobRunService}
 * holds the run's state and calls this off the request thread; {@link DockerGlueScriptRunner} is the Docker
 * implementation, and a test supplies its own.
 */
public interface GlueScriptRunner {

    /** Whether the runner's execution backend (such as the Docker daemon) is reachable right now. */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Runs {@code run} and returns how it ended. Blocks until then, so the caller runs it off the request
     * thread. A run {@code progress} reports as cancelled before its container starts never starts it.
     */
    Outcome run(Run run, Progress progress);

    /** Stops the run's container, now or as soon as it starts, so {@link #run} returns {@link Outcome#halted}. */
    void stop(String runId);

    /**
     * Removes the containers of runs an earlier Floci process left running, which no thread follows any more.
     * The service calls it before any run of its own can start.
     */
    void removeLeftoverContainers();

    /**
     * What a run needs: its id and its job's name; its account and Region; the job's role, whose credentials the
     * script gets; the S3 location of the script; the script's arguments, in the order they go on its command
     * line ({@code --name value} pairs, {@code --JOB_NAME} among them); the image and Docker network to run it in
     * (a null network is the global default); when the run started; and how long it may run ({@link Duration#ZERO}
     * for no limit).
     */
    record Run(String runId, String jobName, String accountId, String region, String role, String scriptLocation,
               List<String> arguments, String image, String dockerNetwork, Instant startedOn, Duration timeout,
               List<String> connections) {

        public Run {
            connections = connections == null ? List.of() : List.copyOf(connections);
        }

        public Run(String runId, String jobName, String accountId, String region, String role, String scriptLocation,
                   List<String> arguments, String image, String dockerNetwork, Instant startedOn, Duration timeout) {
            this(runId, jobName, accountId, region, role, scriptLocation, arguments, image, dockerNetwork,
                    startedOn, timeout, List.of());
        }
    }

    /** What a run tells the service, and asks it, while it goes. */
    interface Progress {

        /** Whether the run was cancelled, or is gone, as after a reset: its container must not start. */
        boolean cancelled();
    }

    /** How a run ended: its script's exit code, or that it ran out of time, was stopped, or could not start. */
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

    /** The command line {@code arguments} make, as a map is read: {@code --name value} for each entry. */
    static List<String> argumentList(Map<String, String> arguments) {
        List<String> list = new ArrayList<>();
        arguments.forEach((name, value) -> {
            list.add(name);
            list.add(value == null ? "" : value);
        });
        return list;
    }
}
