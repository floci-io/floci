package io.github.hectorvent.floci.services.emr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.emr.model.EmrCluster;
import io.github.hectorvent.floci.services.emr.model.EmrStep;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The {@code aws.emr} events AWS sends to the default EventBridge bus as a cluster or a step changes
 * state, "EMR Cluster State Change" and "EMR Step Status Change": the detail of the Management Guide's
 * examples, with the severity and message its "Monitoring Amazon EMR events" tables give each state.
 * {@link EmrService} queues an event while it holds the cluster and publishes the queue once it does
 * not, so a rule's target that calls EMR never waits on the call that made the change, and events go
 * out in the order their changes were made.
 */
@ApplicationScoped
public class EmrEventPublisher {

    static final String SOURCE = "aws.emr";
    static final String CLUSTER_STATE_CHANGE = "EMR Cluster State Change";
    static final String STEP_STATUS_CHANGE = "EMR Step Status Change";
    // The guide's examples, and an event recorded from AWS, write times to the minute: "at 2016-12-16 21:00 UTC".
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    // The termination reasons the table reports as INFO; it reports every other one as CRITICAL.
    private static final Set<String> ROUTINE_TERMINATIONS = Set.of("USER_REQUEST", "ALL_STEPS_COMPLETED");

    /** One event, its detail written while the cluster was held. */
    public record Event(String detailType, String detail) {}

    /** A queued event, with the Region and account of the call that made the change. */
    private record Queued(Event event, String region, String accountId) {}

    private final EventBridgeService eventBridgeService;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final Queue<Queued> queued = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean publishing = new AtomicBoolean();

    @Inject
    public EmrEventPublisher(EventBridgeService eventBridgeService, ObjectMapper objectMapper,
                             RegionResolver regionResolver) {
        this.eventBridgeService = eventBridgeService;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    /** {@code cluster} entering {@code state} at {@code at}: STARTING, WAITING or TERMINATED. */
    Event clusterStateChange(EmrCluster cluster, String state, Instant at) {
        boolean terminated = "TERMINATED".equals(state);
        String code = cluster.getStateChangeReasonCode();
        // The examples' reason is a JSON string: {"code":""} for a WAITING cluster, and the reason's code
        // and message for a TERMINATED one.
        ObjectNode reason = objectMapper.createObjectNode().put("code", terminated && code != null ? code : "");
        if (terminated && cluster.getStateChangeMessage() != null) {
            reason.put("message", cluster.getStateChangeMessage());
        }
        ObjectNode detail = objectMapper.createObjectNode();
        boolean routine = code != null && ROUTINE_TERMINATIONS.contains(code);
        detail.put("severity", terminated && !routine ? "CRITICAL" : "INFO");
        detail.put("stateChangeReason", reason.toString());
        detail.put("name", cluster.getName());
        detail.put("clusterId", cluster.getId());
        detail.put("state", state);
        detail.put("message", clusterMessage(cluster, state, at));
        return new Event(CLUSTER_STATE_CHANGE, detail.toString());
    }

    /** {@code step} of {@code cluster} entering {@code state}: PENDING, RUNNING, COMPLETED or CANCELLED. */
    Event stepStatusChange(EmrCluster cluster, EmrStep step, String state) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("severity", "CANCELLED".equals(state) ? "WARN" : "INFO");
        detail.put("actionOnFailure", step.getActionOnFailure());
        detail.put("stepId", step.getId());
        detail.put("name", step.getName());
        detail.put("clusterId", cluster.getId());
        detail.put("state", state);
        detail.put("message", stepMessage(cluster, step, state));
        return new Event(STEP_STATUS_CHANGE, detail.toString());
    }

    /**
     * Queues {@code event} for {@code region}'s default bus in the calling account. {@link EmrService}
     * queues while it holds the cluster, so the queue keeps the order the changes were made in.
     */
    void queue(Event event, String region) {
        queued.add(new Queued(event, region, regionResolver.getAccountId()));
    }

    /**
     * Publishes the queued events in order, naming no resources, as the examples do; called once the
     * caller no longer holds the cluster. One call publishes at a time: a call made while an event is
     * delivered, such as a rule's target calling EMR back, on this thread or another, leaves its events
     * queued behind the rest and returns, and the call already publishing sends them after.
     */
    void publishQueued() {
        while (!queued.isEmpty() && publishing.compareAndSet(false, true)) {
            try {
                Queued next;
                while ((next = queued.poll()) != null) {
                    publish(next);
                }
            } finally {
                publishing.set(false);
            }
        }
    }

    private void publish(Queued queued) {
        // In the account and Region of the call that queued it, whichever call's thread publishes it.
        RequestScopes.runAs(queued.accountId(), queued.region(), () -> eventBridgeService.publishServiceEvent(
                SOURCE, queued.event().detailType(), queued.event().detail(), null, queued.region()));
    }

    private static String clusterMessage(EmrCluster cluster, String state, Instant at) {
        String named = cluster.getId() + " (" + cluster.getName() + ")";
        return switch (state) {
            case "STARTING" -> "Amazon EMR cluster " + named + " was requested at " + time(at)
                    + " and is being created.";
            case "WAITING" -> cluster.getSteps().isEmpty()
                    ? "Amazon EMR cluster " + named + " was created at " + time(at) + " and is ready for use."
                    : "Amazon EMR cluster " + named + " finished running all pending steps at " + time(at) + ".";
            case "TERMINATED" -> "Amazon EMR Cluster " + named + " has terminated at " + time(at)
                    + " with a reason of " + cluster.getStateChangeReasonCode() + ".";
            default -> throw new IllegalArgumentException("No cluster event for state " + state);
        };
    }

    private static String stepMessage(EmrCluster cluster, EmrStep step, String state) {
        String stepNamed = step.getId() + " (" + step.getName() + ")";
        String clusterNamed = cluster.getId() + " (" + cluster.getName() + ")";
        return switch (state) {
            case "PENDING" -> "Step " + stepNamed + " was added to Amazon EMR cluster " + clusterNamed + " at "
                    + time(step.getCreationDateTime()) + " and is pending execution.";
            case "RUNNING" -> "Step " + stepNamed + " in Amazon EMR cluster " + clusterNamed + " started running at "
                    + time(step.getStartDateTime()) + ".";
            case "COMPLETED" -> "Step " + stepNamed + " in Amazon EMR cluster " + clusterNamed
                    + " completed execution at " + time(step.getEndDateTime()) + ". The step started running at "
                    + time(step.getStartDateTime())
                    + " and took " + Duration.between(step.getStartDateTime(), step.getEndDateTime()).toMinutes()
                    + " minutes to complete.";
            case "CANCELLED" -> "Cancellation request has succeeded for cluster step " + stepNamed
                    + " in Amazon EMR cluster " + clusterNamed + " at " + time(step.getEndDateTime())
                    + ", and the step is now cancelled.";
            default -> throw new IllegalArgumentException("No step event for state " + state);
        };
    }

    private static String time(Instant instant) {
        return TIME.format(instant);
    }
}
