package io.github.hectorvent.floci.services.emr;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.emr.model.EmrCluster;
import io.github.hectorvent.floci.services.emr.model.EmrStep;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.services.emr.EmrServiceFixture.REGION;
import static io.github.hectorvent.floci.services.emr.EmrServiceFixture.cluster;
import static io.github.hectorvent.floci.services.emr.EmrServiceFixture.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The {@code aws.emr} events {@link EmrService} publishes, in the order it publishes them, each one
 * checked against the Management Guide's detail and its tables' severity and message. The durations
 * are 0, as by default; {@link EmrServiceTest} covers the timed lifecycle.
 */
class EmrEventPublisherTest {

    private static final DateTimeFormatter MINUTE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final EmrServiceFixture fixture = new EmrServiceFixture();
    private final EmrService service = fixture.service;
    private final EmrEventPublisher publisher = fixture.publisher;
    private final List<JsonNode> published = fixture.published;

    @Test
    void aClusterThatRunsItsStepsAndWaitsIsStartedAddsRunsAndCompletesThemAndWaits() {
        EmrCluster cluster = service.runJobFlow(cluster("nightly", true, step("load"), step("report")), REGION);

        EmrStep load = cluster.getSteps().get(0);
        EmrStep report = cluster.getSteps().get(1);
        assertEquals(List.of("STARTING", "PENDING load", "PENDING report", "RUNNING load", "COMPLETED load",
                "RUNNING report", "COMPLETED report", "WAITING"), fixture.states());
        // As the events tell it: every step was added before the first one ran.
        assertFalse(report.getCreationDateTime().isAfter(load.getStartDateTime()));
        assertCluster(published.get(0), cluster, "STARTING", "INFO", "{\"code\":\"\"}",
                "Amazon EMR cluster " + cluster.getId() + " (nightly) was requested at "
                        + minute(cluster.getCreationDateTime()) + " and is being created.");
        assertStep(published.get(1), cluster, load, "PENDING", "INFO",
                "Step " + load.getId() + " (load) was added to Amazon EMR cluster " + cluster.getId()
                        + " (nightly) at " + minute(load.getCreationDateTime()) + " and is pending execution.");
        assertStep(published.get(3), cluster, load, "RUNNING", "INFO",
                "Step " + load.getId() + " (load) in Amazon EMR cluster " + cluster.getId()
                        + " (nightly) started running at " + minute(load.getStartDateTime()) + ".");
        assertStep(published.get(4), cluster, load, "COMPLETED", "INFO",
                "Step " + load.getId() + " (load) in Amazon EMR cluster " + cluster.getId()
                        + " (nightly) completed execution at " + minute(load.getEndDateTime())
                        + ". The step started running at " + minute(load.getStartDateTime())
                        + " and took 0 minutes to complete.");
        assertEquals(report.getId(), published.get(6).at("/detail/stepId").asText());
        assertCluster(published.get(7), cluster, "WAITING", "INFO", "{\"code\":\"\"}",
                "Amazon EMR cluster " + cluster.getId() + " (nightly) finished running all pending steps at "
                        + minute(report.getEndDateTime()) + ".");
    }

    @Test
    void aClusterWithoutStepsIsReadyForUse() {
        EmrCluster cluster = service.runJobFlow(cluster("idle", true), REGION);

        assertEquals(List.of("STARTING", "WAITING"), fixture.states());
        assertEquals("Amazon EMR cluster " + cluster.getId() + " (idle) was created at "
                + minute(cluster.getReadyDateTime()) + " and is ready for use.",
                published.get(1).at("/detail/message").asText());
    }

    @Test
    void aTransientClusterTerminatesAfterItsStepsWithoutWaiting() {
        EmrCluster cluster = service.runJobFlow(cluster("transient", false, step("only")), REGION);

        assertEquals(List.of("STARTING", "PENDING only", "RUNNING only", "COMPLETED only", "TERMINATED"),
                fixture.states());
        assertCluster(published.get(4), cluster, "TERMINATED", "INFO",
                "{\"code\":\"ALL_STEPS_COMPLETED\",\"message\":\"All steps completed.\"}",
                "Amazon EMR Cluster " + cluster.getId() + " (transient) has terminated at "
                        + minute(cluster.getEndDateTime()) + " with a reason of ALL_STEPS_COMPLETED.");
    }

    @Test
    void stepsAddedToAWaitingClusterRunAndLeaveItWaitingAgain() {
        EmrCluster cluster = service.runJobFlow(cluster("kept", true), REGION);
        published.clear();

        EmrStep late = step("late");
        service.addJobFlowSteps(cluster.getId(), List.of(late));

        assertEquals(List.of("PENDING late", "RUNNING late", "COMPLETED late", "WAITING"), fixture.states());
        assertEquals("Amazon EMR cluster " + cluster.getId() + " (kept) finished running all pending steps at "
                + minute(late.getEndDateTime()) + ".", published.get(3).at("/detail/message").asText());
    }

    @Test
    void stepsAddedToATransientClusterThatWaitedEndIt() {
        EmrCluster cluster = service.runJobFlow(cluster("no-steps-yet", false), REGION);
        published.clear();

        service.addJobFlowSteps(cluster.getId(), List.of(step("late")));

        assertEquals(List.of("PENDING late", "RUNNING late", "COMPLETED late", "TERMINATED"), fixture.states());
        assertEquals("{\"code\":\"ALL_STEPS_COMPLETED\",\"message\":\"All steps completed.\"}",
                published.get(3).at("/detail/stateChangeReason").asText());
    }

    @Test
    void terminatingPublishesForEveryClusterItEndsEvenWhenAProtectedOneFailsTheCall() {
        EmrCluster ended = service.runJobFlow(cluster("ended", true), REGION);
        EmrCluster gone = service.runJobFlow(cluster("gone", true), REGION);
        EmrCluster kept = cluster("kept", true);
        kept.setTerminationProtected(true);
        kept = service.runJobFlow(kept, REGION);
        service.terminateJobFlows(List.of(gone.getId()));
        published.clear();

        List<String> ids = List.of(ended.getId(), gone.getId(), kept.getId());
        assertThrows(AwsException.class, () -> service.terminateJobFlows(ids));

        assertEquals(List.of("TERMINATED"), fixture.states());
        assertCluster(published.get(0), service.describeCluster(ended.getId()), "TERMINATED", "INFO",
                "{\"code\":\"USER_REQUEST\",\"message\":\"Terminated by user request\"}",
                "Amazon EMR Cluster " + ended.getId() + " (ended) has terminated at "
                        + minute(service.describeCluster(ended.getId()).getEndDateTime())
                        + " with a reason of USER_REQUEST.");
    }

    @Test
    void aRefusedCallPublishesNothing() {
        EmrCluster cluster = service.runJobFlow(cluster("refusing", true), REGION);
        service.terminateJobFlows(List.of(cluster.getId()));
        published.clear();

        assertThrows(AwsException.class, () -> service.addJobFlowSteps(cluster.getId(), List.of(step("late"))));
        assertThrows(AwsException.class, () -> service.runJobFlow(new EmrCluster(), REGION));

        assertEquals(List.of(), fixture.states());
    }

    @Test
    void aTargetThatEndsTheClusterOnItsStartingEventHearsOfItAfterTheRestOfTheCall() {
        fixture.target = event -> {
            if ("STARTING".equals(event.at("/detail/state").asText())) {
                service.terminateJobFlows(List.of(event.at("/detail/clusterId").asText()));
            }
        };

        service.runJobFlow(cluster("answered", true), REGION);

        assertEquals(List.of("STARTING", "WAITING", "TERMINATED"), fixture.states());
    }

    @Test
    void aTargetCallingBackFromAnotherThreadHearsOfItAfterTheRestOfTheCall() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        fixture.target = event -> {
            if ("STARTING".equals(event.at("/detail/state").asText())) {
                // As an API destination would, from the thread serving its request, while this one waits.
                String clusterId = event.at("/detail/clusterId").asText();
                Thread other = new Thread(() -> {
                    try {
                        service.terminateJobFlows(List.of(clusterId));
                    } catch (Throwable e) {
                        failure.set(e);
                    }
                });
                other.start();
                other.join();
            }
        };

        service.runJobFlow(cluster("answered", true), REGION);

        assertNull(failure.get());
        assertEquals(List.of("STARTING", "WAITING", "TERMINATED"), fixture.states());
    }

    @Test
    void aTerminationForAFailureIsCritical() throws Exception {
        EmrCluster cluster = service.runJobFlow(cluster("failing", true), REGION);
        cluster.setStateChangeReasonCode("STEP_FAILURE");
        cluster.setStateChangeMessage("Shut down as step failed");

        JsonNode detail = fixture.objectMapper.readTree(
                publisher.clusterStateChange(cluster, "TERMINATED", Instant.parse("2026-10-24T09:41:30Z")).detail());

        assertEquals("CRITICAL", detail.path("severity").asText());
        assertEquals("Amazon EMR Cluster " + cluster.getId() + " (failing) has terminated at 2026-10-24 09:41 UTC"
                + " with a reason of STEP_FAILURE.", detail.path("message").asText());
    }

    @Test
    void aCancelledStepIsAWarning() throws Exception {
        EmrCluster cluster = service.runJobFlow(cluster("cancelling", true, step("doomed")), REGION);
        EmrStep step = cluster.getSteps().get(0);
        step.setEndDateTime(Instant.parse("2026-10-24T09:41:30Z"));

        EmrEventPublisher.Event event = publisher.stepStatusChange(cluster, step, "CANCELLED");

        assertEquals("EMR Step Status Change", event.detailType());
        JsonNode detail = fixture.objectMapper.readTree(event.detail());
        assertEquals("WARN", detail.path("severity").asText());
        assertEquals("Cancellation request has succeeded for cluster step " + step.getId() + " (doomed) in Amazon EMR"
                + " cluster " + cluster.getId() + " (cancelling) at 2026-10-24 09:41 UTC, and the step is now"
                + " cancelled.", detail.path("message").asText());
    }

    private void assertCluster(JsonNode event, EmrCluster cluster, String state, String severity, String reason,
                               String message) {
        assertEquals("EMR Cluster State Change", event.path("detail-type").asText());
        JsonNode detail = event.path("detail");
        assertEquals(List.of("severity", "stateChangeReason", "name", "clusterId", "state", "message"),
                fieldNames(detail));
        assertEquals(severity, detail.path("severity").asText());
        assertEquals(reason, detail.path("stateChangeReason").asText());
        assertEquals(cluster.getName(), detail.path("name").asText());
        assertEquals(cluster.getId(), detail.path("clusterId").asText());
        assertEquals(state, detail.path("state").asText());
        assertEquals(message, detail.path("message").asText());
    }

    private void assertStep(JsonNode event, EmrCluster cluster, EmrStep step, String state, String severity,
                            String message) {
        assertEquals("EMR Step Status Change", event.path("detail-type").asText());
        JsonNode detail = event.path("detail");
        assertEquals(List.of("severity", "actionOnFailure", "stepId", "name", "clusterId", "state", "message"),
                fieldNames(detail));
        assertEquals(severity, detail.path("severity").asText());
        assertEquals("CONTINUE", detail.path("actionOnFailure").asText());
        assertEquals(step.getId(), detail.path("stepId").asText());
        assertEquals(step.getName(), detail.path("name").asText());
        assertEquals(cluster.getId(), detail.path("clusterId").asText());
        assertEquals(state, detail.path("state").asText());
        assertEquals(message, detail.path("message").asText());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String minute(Instant instant) {
        return MINUTE.format(instant);
    }
}
