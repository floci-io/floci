package io.github.hectorvent.floci.services.emr;

import io.github.hectorvent.floci.services.emr.model.EmrCluster;
import io.github.hectorvent.floci.services.emr.model.EmrInstanceFleet;
import io.github.hectorvent.floci.services.emr.model.EmrInstanceGroup;
import io.github.hectorvent.floci.services.emr.model.EmrStep;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A cluster's and its steps' lifecycle, as the Management Guide gives it in "Understanding the cluster
 * lifecycle" and "Running steps to process data". A cluster is STARTING, then BOOTSTRAPPING, then
 * RUNNING while it runs its steps, in the order they were added and at most its StepConcurrencyLevel
 * at a time, then WAITING, or TERMINATING and TERMINATED when it ends after them. Nothing runs: each
 * change falls due at a time worked out from three durations and the times already recorded, and
 * {@link #advance} makes every change due by a given time, in the order they fall due, queuing the
 * event of each state the guide's event tables report. With the durations at 0, every change falls
 * due at the time of the call that leads to it.
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
    private static final Pattern RELEASE_LABEL = Pattern.compile("emr-(\\d{1,6})\\.(\\d{1,6})(?:\\..*)?");

    private final Duration startup;
    private final Duration stepRun;
    private final Duration termination;
    private final EmrEventPublisher events;

    EmrClusterLifecycle(int startupDelaySeconds, int stepDurationSeconds, int terminationDelaySeconds,
                        EmrEventPublisher events) {
        this.startup = seconds("cluster-startup-delay-seconds", startupDelaySeconds);
        this.stepRun = seconds("step-duration-seconds", stepDurationSeconds);
        this.termination = seconds("cluster-termination-delay-seconds", terminationDelaySeconds);
        this.events = events;
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
            case BOOTSTRAPPING -> cluster.getCreationDateTime().plus(startup);
            case RUNNING -> cluster.getSteps().stream()
                    .filter(step -> RUNNING.equals(step.getState()))
                    .map(step -> step.getStartDateTime().plus(stepRun))
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            case TERMINATING -> cluster.getTerminatingDateTime().plus(termination);
            default -> null;
        };
    }

    private void change(EmrCluster cluster, Instant at, String region) {
        switch (cluster.getState()) {
            case STARTING -> cluster.setState(BOOTSTRAPPING);
            case BOOTSTRAPPING -> {
                cluster.setReadyDateTime(at);
                cluster.setState(RUNNING);
                runSteps(cluster, at, region);
            }
            case RUNNING -> {
                for (EmrStep step : cluster.getSteps()) {
                    if (RUNNING.equals(step.getState()) && !step.getStartDateTime().plus(stepRun).isAfter(at)) {
                        step.setState(COMPLETED);
                        step.setEndDateTime(at);
                        events.queue(events.stepStatusChange(cluster, step, COMPLETED), region);
                    }
                }
                runSteps(cluster, at, region);
            }
            case TERMINATING -> {
                cluster.setState(TERMINATED);
                cluster.setEndDateTime(at);
                releaseInstanceCapacity(cluster);
                events.queue(events.clusterStateChange(cluster, TERMINATED, at), region);
            }
            default -> throw new IllegalStateException("Nothing falls due for a " + cluster.getState() + " cluster");
        }
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
        for (EmrStep step : cluster.getSteps()) {
            if (running >= level) {
                break;
            }
            if (PENDING.equals(step.getState())) {
                step.setState(RUNNING);
                step.setStartDateTime(at);
                events.queue(events.stepStatusChange(cluster, step, RUNNING), region);
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
        step.setState(CANCELLED);
        step.setEndDateTime(at);
        events.queue(events.stepStatusChange(cluster, step, CANCELLED), region);
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
