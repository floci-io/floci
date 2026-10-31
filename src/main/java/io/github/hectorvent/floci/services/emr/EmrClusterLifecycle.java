package io.github.hectorvent.floci.services.emr;

import io.github.hectorvent.floci.services.emr.model.EmrCluster;
import io.github.hectorvent.floci.services.emr.model.EmrInstanceFleet;
import io.github.hectorvent.floci.services.emr.model.EmrInstanceGroup;
import io.github.hectorvent.floci.services.emr.model.EmrStep;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A cluster's and its steps' lifecycle, as the Management Guide gives it in "Understanding the cluster
 * lifecycle" and "Running steps to process data". A cluster is STARTING, then BOOTSTRAPPING, then
 * RUNNING while it runs its steps, in the order they were added and at most its StepConcurrencyLevel
 * at a time, then WAITING, or TERMINATING and TERMINATED when it ends after them. Each change falls due
 * at a time worked out from three durations and the times already recorded, and {@link #advance} makes
 * every change due by a given time, in the order they fall due, queuing the event of each state the
 * guide's event tables report. With the durations at 0, every change falls due at the time of the call
 * that leads to it. Without a {@link StepExecution} nothing runs and a step completes by time; with one,
 * a step runs for real, and ends, COMPLETED or FAILED, when {@link #stepEnded} says so. Likewise, with a
 * {@link BootstrapExecution} a cluster with bootstrap actions runs them for real, and is BOOTSTRAPPING until
 * {@link #bootstrapEnded} says they are done.
 */
final class EmrClusterLifecycle {

    static final String STARTING = "STARTING";
    static final String BOOTSTRAPPING = "BOOTSTRAPPING";
    static final String RUNNING = "RUNNING";
    static final String WAITING = "WAITING";
    static final String TERMINATING = "TERMINATING";
    static final String TERMINATED = "TERMINATED";
    static final String PENDING = "PENDING";
    static final String COMPLETED = "COMPLETED";
    static final String CANCELLED = "CANCELLED";
    static final String FAILED = "FAILED";
    static final String TERMINATED_WITH_ERRORS = "TERMINATED_WITH_ERRORS";
    // FailureDetails.Reason: "In the case where the service cannot successfully determine the root cause of
    // the failure, it returns "Unknown Error" as a reason." Floci never determines it.
    private static final String UNKNOWN_ERROR = "Unknown Error";
    private static final String STEP_FAILURE = "STEP_FAILURE";
    static final String BOOTSTRAP_FAILURE = "BOOTSTRAP_FAILURE";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    // "If a cluster terminates because of a failure, ... the cluster state is set to TERMINATED_WITH_ERRORS."
    private static final Set<String> FAILURES = Set.of(STEP_FAILURE, BOOTSTRAP_FAILURE, INTERNAL_ERROR);
    // The reason a cluster a failed step ends gives: the console's for a Snowplow cluster on its forum, and
    // mrjob's mock EMR.
    private static final String SHUT_DOWN_AS_STEP_FAILED = "Shut down as step failed";
    private static final Pattern RELEASE_LABEL = Pattern.compile("emr-(\\d{1,6})\\.(\\d{1,6})(?:\\..*)?");

    private final Duration startup;
    private final Duration stepRun;
    private final Duration termination;
    private final EmrEventPublisher events;
    // Null when steps complete by time.
    private final StepExecution execution;
    // Null when a cluster's bootstrap actions are done by time.
    private final BootstrapExecution bootstrapping;

    /**
     * Runs the steps a cluster starts, in the {@code docker} runner mode. Each call only sets work going
     * and returns: a step's end comes back later through {@link #stepEnded}, never from inside the call.
     */
    interface StepExecution {

        /** {@code step} of {@code cluster} has just gone RUNNING. */
        void start(EmrCluster cluster, EmrStep step, String region);

        /** {@code step}, which was RUNNING, has just been cancelled. */
        void stop(EmrStep step);
    }

    /**
     * Runs the bootstrap actions of the clusters that have some, in the {@code docker} runner mode. Each call
     * only sets work going and returns: their end comes back later through {@link #bootstrapEnded}.
     */
    interface BootstrapExecution {

        /** {@code cluster}, which has bootstrap actions, has just gone BOOTSTRAPPING. */
        void start(EmrCluster cluster, String region);

        /** {@code cluster} is ending while its bootstrap actions still run. */
        void stop(EmrCluster cluster);
    }

    EmrClusterLifecycle(int startupDelaySeconds, int stepDurationSeconds, int terminationDelaySeconds,
                        EmrEventPublisher events, StepExecution execution, BootstrapExecution bootstrapping) {
        this.startup = seconds("cluster-startup-delay-seconds", startupDelaySeconds);
        this.stepRun = seconds("step-duration-seconds", stepDurationSeconds);
        this.termination = seconds("cluster-termination-delay-seconds", terminationDelaySeconds);
        this.events = events;
        this.execution = execution;
        this.bootstrapping = bootstrapping;
    }

    private static Duration seconds(String setting, int value) {
        if (value < 0) {
            throw new IllegalArgumentException("floci.services.emr." + setting + " must not be negative: " + value);
        }
        return Duration.ofSeconds(value);
    }

    /** A new cluster, STARTING at {@code now}, with its steps PENDING. */
    void start(EmrCluster cluster, Instant now, String region) {
        cluster.setState(STARTING);
        cluster.setCreationDateTime(now);
        events.queue(events.clusterStateChange(cluster, STARTING, now), region);
        submit(cluster, cluster.getSteps(), now, region);
    }

    /**
     * {@code steps}, already on the cluster's list, added at {@code now}: "The state of all steps is set
     * to PENDING", and a cluster that is ready starts what it has room for. Adding none changes nothing.
     */
    void submit(EmrCluster cluster, List<EmrStep> steps, Instant now, String region) {
        if (steps.isEmpty()) {
            return;
        }
        for (EmrStep step : steps) {
            step.setState(PENDING);
            step.setCreationDateTime(now);
            if (step.getActionOnFailure() == null) {
                step.setActionOnFailure("TERMINATE_CLUSTER");
            }
            events.queue(events.stepStatusChange(cluster, step, PENDING), region);
        }
        if (RUNNING.equals(cluster.getState()) || WAITING.equals(cluster.getState())) {
            runSteps(cluster, now, region);
        }
    }

    /**
     * Ends the cluster for {@code code}: TERMINATING from {@code now}, then TERMINATED. TerminateJobFlows:
     * "When a job flow is shut down, any step not yet completed is canceled".
     */
    void terminate(EmrCluster cluster, String code, String message, Instant now, String region) {
        if (BOOTSTRAPPING.equals(cluster.getState()) && !bootstrapTimed(cluster)) {
            bootstrapping.stop(cluster);
        }
        cluster.setState(TERMINATING);
        cluster.setStateChangeReasonCode(code);
        cluster.setStateChangeMessage(message);
        cluster.setTerminatingDateTime(now);
        for (EmrStep step : cluster.getSteps()) {
            if (PENDING.equals(step.getState()) || RUNNING.equals(step.getState())) {
                cancelStep(cluster, step, now, region);
            }
        }
    }

    /**
     * CancelSteps for one step: null once it is cancelled, or why it cannot be. CancelSteps: "When you
     * use Amazon EMR releases 5.28.0 and later, you can cancel steps that are in a PENDING or RUNNING
     * state." A cancelled RUNNING step makes room for the next PENDING one.
     */
    String cancel(EmrCluster cluster, EmrStep step, Instant now, String region) {
        boolean running = RUNNING.equals(step.getState()) && cancelsRunningSteps(cluster.getReleaseLabel());
        if (!running && !PENDING.equals(step.getState())) {
            return "Step is not in a cancellable state: " + step.getState();
        }
        cancelStep(cluster, step, now, region);
        if (running) {
            runSteps(cluster, now, region);
        }
        return null;
    }

    /**
     * The cluster's StepConcurrencyLevel changed: a RUNNING cluster starts what it now has room for. A
     * lower level lets the steps already running complete, as the guide says EMR does.
     */
    void concurrencyChanged(EmrCluster cluster, Instant now, String region) {
        if (RUNNING.equals(cluster.getState())) {
            runSteps(cluster, now, region);
        }
    }

    /** Makes every change due by {@code now}, in the order they fall due; true when there was one. */
    boolean advance(EmrCluster cluster, Instant now, String region) {
        boolean changed = false;
        Instant due;
        while ((due = nextChange(cluster)) != null && !due.isAfter(now)) {
            change(cluster, due, region);
            changed = true;
        }
        return changed;
    }

    /** When the cluster's next change falls due, or null while it waits for a call. */
    Instant nextChange(EmrCluster cluster) {
        return switch (cluster.getState()) {
            case STARTING -> cluster.getCreationDateTime().plus(startup.dividedBy(2));
            case BOOTSTRAPPING -> bootstrapTimed(cluster) ? cluster.getCreationDateTime().plus(startup) : null;
            case RUNNING -> cluster.getSteps().stream()
                    .filter(step -> RUNNING.equals(step.getState()) && timed(step))
                    .map(step -> step.getStartDateTime().plus(stepRun))
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            case TERMINATING -> cluster.getTerminatingDateTime().plus(termination);
            default -> null;
        };
    }

    private void change(EmrCluster cluster, Instant at, String region) {
        switch (cluster.getState()) {
            case STARTING -> {
                cluster.setState(BOOTSTRAPPING);
                if (bootstrapping != null && cluster.getBootstrapActions() != null) {
                    cluster.setBootstrapInContainer(true);
                    bootstrapping.start(cluster, region);
                }
            }
            case BOOTSTRAPPING -> ready(cluster, at, region);
            case RUNNING -> {
                for (EmrStep step : cluster.getSteps()) {
                    if (RUNNING.equals(step.getState()) && timed(step)
                            && !step.getStartDateTime().plus(stepRun).isAfter(at)) {
                        step.setState(COMPLETED);
                        step.setEndDateTime(at);
                        events.queue(events.stepStatusChange(cluster, step, COMPLETED), region);
                    }
                }
                runSteps(cluster, at, region);
            }
            case TERMINATING -> {
                String ended = FAILURES.contains(cluster.getStateChangeReasonCode())
                        ? TERMINATED_WITH_ERRORS : TERMINATED;
                cluster.setState(ended);
                cluster.setEndDateTime(at);
                releaseInstanceCapacity(cluster);
                events.queue(events.clusterStateChange(cluster, ended, at), region);
            }
            default -> throw new IllegalStateException("Nothing falls due for a " + cluster.getState() + " cluster");
        }
    }

    /**
     * The bootstrap actions a cluster ran for real have ended: when {@code failureCode} is null they all
     * succeeded and left {@code image}, which its steps run in, and the cluster is ready to run them;
     * otherwise the cluster ends, for {@code failureCode} and {@code message}.
     */
    void bootstrapEnded(EmrCluster cluster, String image, String failureCode, String message, Instant at,
                        String region) {
        cluster.setBootstrapInContainer(false);
        if (failureCode != null) {
            terminate(cluster, failureCode, message, at, region);
            return;
        }
        cluster.setBootstrapImage(image);
        ready(cluster, at, region);
    }

    // Bootstrapping is done: the cluster runs its steps.
    private void ready(EmrCluster cluster, Instant at, String region) {
        cluster.setReadyDateTime(at);
        cluster.setState(RUNNING);
        runSteps(cluster, at, region);
    }

    /**
     * Starts PENDING steps in the order they were added while the cluster has room: "pending steps in
     * queue transition to running state in the order they were submitted". With none left, the
     * cluster is done.
     */
    private void runSteps(EmrCluster cluster, Instant at, String region) {
        long running = cluster.getSteps().stream().filter(step -> RUNNING.equals(step.getState())).count();
        // The model allows 1 to 256; Floci stores any level it is given, and a lower one would never start a step.
        int level = Math.max(1, cluster.getStepConcurrencyLevel());
        List<EmrStep> started = new ArrayList<>();
        for (EmrStep step : cluster.getSteps()) {
            if (running >= level) {
                break;
            }
            if (PENDING.equals(step.getState())) {
                step.setState(RUNNING);
                step.setStartDateTime(at);
                step.setRunInContainer(execution != null);
                events.queue(events.stepStatusChange(cluster, step, RUNNING), region);
                started.add(step);
                running++;
            }
        }
        if (running > 0) {
            // A WAITING cluster's "ready to run steps" no longer holds; no source gives a RUNNING one's reason.
            cluster.setState(RUNNING);
            cluster.setStateChangeReasonCode(null);
            cluster.setStateChangeMessage(null);
        } else {
            finish(cluster, at, region);
        }
        if (execution != null) {
            started.forEach(step -> execution.start(cluster, step, region));
        }
    }

    /**
     * A step run for real has ended: COMPLETED when {@code failure} is null, otherwise FAILED, with
     * {@code failure} as its FailureDetails message, and its ActionOnFailure taken. Either way the
     * cluster goes on as it would once a step is done.
     */
    void stepEnded(EmrCluster cluster, EmrStep step, String failure, Instant at, String region) {
        step.setEndDateTime(at);
        if (failure == null) {
            step.setState(COMPLETED);
            events.queue(events.stepStatusChange(cluster, step, COMPLETED), region);
            runSteps(cluster, at, region);
            return;
        }
        step.setState(FAILED);
        step.setFailureReason(UNKNOWN_ERROR);
        step.setFailureMessage(failure);
        events.queue(events.stepStatusChange(cluster, step, FAILED), region);
        actOnFailure(cluster, step, at, region);
    }

    /**
     * A failed step's ActionOnFailure, as the API reference's Step and the Management Guide give it.
     * TERMINATE_CLUSTER (TERMINATE_JOB_FLOW before it) ends the cluster, unless it is set to wait after
     * its steps and protected from termination: then it "continues". CANCEL_AND_WAIT "Cancels any pending
     * steps and returns the cluster to the WAITING state", and on a cluster set to end after its steps
     * "the cluster terminates without executing subsequent steps". CONTINUE "Continues to the next step in
     * the queue". "If the step concurrency level of a cluster is greater than one, step ActionOnFailure
     * feature will not activate", and with level 1 but other steps still running (the level was lowered
     * while they ran) "TERMINATE_CLUSTER ActionOnFailure may activate, but CANCEL_AND_WAIT ActionOnFailure
     * will not".
     */
    private void actOnFailure(EmrCluster cluster, EmrStep step, Instant at, String region) {
        String action = step.getActionOnFailure();
        boolean activates = cluster.getStepConcurrencyLevel() <= 1;
        boolean terminates = "TERMINATE_CLUSTER".equals(action) || "TERMINATE_JOB_FLOW".equals(action);
        if (activates && terminates && (cluster.isAutoTerminate() || !cluster.isTerminationProtected())) {
            terminate(cluster, STEP_FAILURE, SHUT_DOWN_AS_STEP_FAILED, at, region);
            return;
        }
        boolean othersRunning = cluster.getSteps().stream().anyMatch(other -> RUNNING.equals(other.getState()));
        if (activates && "CANCEL_AND_WAIT".equals(action) && !othersRunning) {
            if (cluster.isAutoTerminate()) {
                terminate(cluster, STEP_FAILURE, SHUT_DOWN_AS_STEP_FAILED, at, region);
                return;
            }
            for (EmrStep pending : cluster.getSteps()) {
                if (PENDING.equals(pending.getState())) {
                    cancelStep(cluster, pending, at, region);
                }
            }
        }
        runSteps(cluster, at, region);
    }

    /**
     * The cluster has no step left to run: it waits for more, or, set to end after its steps, ends
     * without waiting. One created without steps waits either way.
     */
    private void finish(EmrCluster cluster, Instant at, String region) {
        if (cluster.isAutoTerminate() && !cluster.getSteps().isEmpty()) {
            terminate(cluster, "ALL_STEPS_COMPLETED", "All steps completed.", at, region);
            return;
        }
        cluster.setState(WAITING);
        cluster.setStateChangeReasonCode(cluster.getSteps().isEmpty() ? null : "ALL_STEPS_COMPLETED");
        cluster.setStateChangeMessage("Cluster ready to run steps.");
        events.queue(events.clusterStateChange(cluster, WAITING, at), region);
    }

    private void cancelStep(EmrCluster cluster, EmrStep step, Instant at, String region) {
        boolean running = RUNNING.equals(step.getState());
        step.setState(CANCELLED);
        step.setEndDateTime(at);
        events.queue(events.stepStatusChange(cluster, step, CANCELLED), region);
        if (running && !timed(step)) {
            execution.stop(step);
        }
    }

    /**
     * Whether a RUNNING step completes by time: every step without a {@link StepExecution}, including one
     * a {@code docker} runner mode started before a restart into the {@code immediate} one.
     */
    private boolean timed(EmrStep step) {
        return execution == null || !step.isRunInContainer();
    }

    /**
     * Whether a BOOTSTRAPPING cluster is done by time: every one without a {@link BootstrapExecution} or
     * without bootstrap actions, including one a {@code docker} runner mode started before a restart into the
     * {@code immediate} one.
     */
    private boolean bootstrapTimed(EmrCluster cluster) {
        return bootstrapping == null || !cluster.isBootstrapInContainer();
    }

    /** TERMINATED, or TERMINATED_WITH_ERRORS: the cluster has ended. */
    static boolean isTerminated(String state) {
        return TERMINATED.equals(state) || TERMINATED_WITH_ERRORS.equals(state);
    }

    private static void releaseInstanceCapacity(EmrCluster cluster) {
        for (EmrInstanceGroup group : cluster.getInstanceGroups()) {
            group.setState(TERMINATED);
            group.setTerminatedInstanceCount(group.getRunningInstanceCount());
            group.setRunningInstanceCount(0);
        }
        for (EmrInstanceFleet fleet : cluster.getInstanceFleets()) {
            fleet.setState(TERMINATED);
            fleet.setProvisionedOnDemandCapacity(0);
            fleet.setProvisionedSpotCapacity(0);
        }
    }

    /** Releases from 5.28.0 cancel RUNNING steps; a label not of the emr-x.y form is taken as current. */
    static boolean cancelsRunningSteps(String releaseLabel) {
        Matcher matcher = RELEASE_LABEL.matcher(releaseLabel == null ? "" : releaseLabel);
        if (!matcher.matches()) {
            return true;
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        return major > 5 || (major == 5 && minor >= 28);
    }
}
