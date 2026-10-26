package io.github.hectorvent.floci.services.emr;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.emr.model.EmrCluster;
import io.github.hectorvent.floci.services.emr.model.EmrInstanceFleet;
import io.github.hectorvent.floci.services.emr.model.EmrInstanceGroup;
import io.github.hectorvent.floci.services.emr.model.EmrStep;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.github.hectorvent.floci.services.emr.EmrServiceFixture.REGION;
import static io.github.hectorvent.floci.services.emr.EmrServiceFixture.cluster;
import static io.github.hectorvent.floci.services.emr.EmrServiceFixture.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The timed lifecycle: the durations {@code cluster-startup-delay-seconds}, {@code step-duration-seconds}
 * and {@code cluster-termination-delay-seconds} hold a cluster and its steps in each state of the
 * Management Guide's lifecycle, a call finds them as they stand when it is made, and the service sends
 * each change's event when it falls due, without anyone asking.
 */
class EmrServiceTest {

    private static final String SHUT_DOWN =
            "A job flow that is shutting down, terminated, or finished may not be modified.";

    @Test
    void aClusterIsStartingThenBootstrappingUntilItIsReady() {
        EmrServiceFixture fixture = new EmrServiceFixture(10, 0, 0);
        EmrService service = fixture.service;
        EmrCluster cluster = service.runJobFlow(cluster("slow", true), REGION);
        Instant created = cluster.getCreationDateTime();

        assertEquals("STARTING", service.describeCluster(cluster.getId()).getState());
        fixture.clock.advance(Duration.ofSeconds(5));
        assertEquals("BOOTSTRAPPING", service.describeCluster(cluster.getId()).getState());
        fixture.clock.advance(Duration.ofSeconds(5));

        EmrCluster ready = service.describeCluster(cluster.getId());
        assertEquals("WAITING", ready.getState());
        assertEquals(created.plusSeconds(10), ready.getReadyDateTime());
        assertEquals(List.of("STARTING", "WAITING"), fixture.states());
        assertTrue(fixture.published.get(1).at("/detail/message").asText().endsWith(" was created at "
                + "2026-01-01 00:00 UTC and is ready for use."));
    }

    @Test
    void aClusterNobodyReadsSendsEachEventWhenItFallsDue() {
        EmrServiceFixture fixture = new EmrServiceFixture(10, 60, 30);
        EmrCluster cluster = fixture.service.runJobFlow(cluster("unread", false, step("only")), REGION);
        Instant created = cluster.getCreationDateTime();

        assertEquals(List.of("STARTING", "PENDING only"), fixture.states());
        assertEquals(List.of(created.plusSeconds(5)), fixture.wakeupTimes());
        fixture.pass(Duration.ofSeconds(10));
        assertEquals(List.of("STARTING", "PENDING only", "RUNNING only"), fixture.states());
        assertEquals(List.of(created.plusSeconds(70)), fixture.wakeupTimes());
        fixture.pass(Duration.ofSeconds(60));
        assertEquals(List.of("STARTING", "PENDING only", "RUNNING only", "COMPLETED only"), fixture.states());
        fixture.pass(Duration.ofSeconds(30));

        assertEquals(List.of("STARTING", "PENDING only", "RUNNING only", "COMPLETED only", "TERMINATED"),
                fixture.states());
        assertEquals(List.of(), fixture.wakeupTimes());
        EmrCluster ended = fixture.service.describeCluster(cluster.getId());
        assertEquals("TERMINATED", ended.getState());
        assertEquals("ALL_STEPS_COMPLETED", ended.getStateChangeReasonCode());
        assertEquals(created.plusSeconds(100), ended.getEndDateTime());
    }

    @Test
    void stepsRunOneAtATimeInTheOrderTheyWereAdded() {
        EmrServiceFixture fixture = new EmrServiceFixture(0, 60, 0);
        EmrService service = fixture.service;
        EmrCluster cluster = service.runJobFlow(cluster("serial", true, step("a"), step("b")), REGION);
        String id = cluster.getId();
        EmrStep a = cluster.getSteps().get(0);
        EmrStep b = cluster.getSteps().get(1);

        assertEquals("RUNNING", service.describeCluster(id).getState());
        assertEquals(List.of("PENDING", "RUNNING"), states(service.listSteps(id, null, null)));
        assertEquals(List.of(b.getId()), ids(service.listSteps(id, List.of("PENDING"), null)));
        fixture.clock.advance(Duration.ofSeconds(60));
        assertEquals(List.of("RUNNING", "COMPLETED"), states(service.listSteps(id, null, null)));
        assertEquals(a.getStartDateTime().plusSeconds(60), a.getEndDateTime());
        assertEquals(a.getEndDateTime(), b.getStartDateTime());
        fixture.clock.advance(Duration.ofSeconds(60));

        assertEquals("WAITING", service.describeCluster(id).getState());
        assertEquals(List.of("STARTING", "PENDING a", "PENDING b", "RUNNING a", "COMPLETED a", "RUNNING b",
                "COMPLETED b", "WAITING"), fixture.states());
        assertTrue(fixture.published.get(4).at("/detail/message").asText()
                .endsWith(" and took 1 minutes to complete."));
    }

    @Test
    void aClusterRunsAsManyStepsAtOnceAsItsConcurrencyLevelAllows() {
        EmrServiceFixture fixture = new EmrServiceFixture(0, 60, 0);
        EmrService service = fixture.service;
        EmrCluster parallel = cluster("parallel", true, step("a"), step("b"), step("c"));
        parallel.setStepConcurrencyLevel(2);
        String id = service.runJobFlow(parallel, REGION).getId();

        assertEquals(List.of("PENDING", "RUNNING", "RUNNING"), states(service.listSteps(id, null, null)));
        fixture.clock.advance(Duration.ofSeconds(10));
        service.modifyCluster(id, 3);
        assertEquals(List.of("RUNNING", "RUNNING", "RUNNING"), states(service.listSteps(id, null, null)));
        // Lowering the level lets the steps already running complete.
        service.modifyCluster(id, 1);
        fixture.clock.advance(Duration.ofSeconds(60));

        assertEquals(List.of("COMPLETED", "COMPLETED", "COMPLETED"), states(service.listSteps(id, null, null)));
        assertEquals("WAITING", service.describeCluster(id).getState());
    }

    @Test
    void stepsAddedWhileAClusterStartsRunOnceItIsReady() {
        EmrServiceFixture fixture = new EmrServiceFixture(10, 60, 0);
        EmrService service = fixture.service;
        EmrCluster cluster = service.runJobFlow(cluster("early", true), REGION);
        EmrStep early = step("early");

        service.addJobFlowSteps(cluster.getId(), List.of(early));
        assertEquals("PENDING", early.getState());
        fixture.clock.advance(Duration.ofSeconds(10));

        assertEquals("RUNNING", service.describeStep(cluster.getId(), early.getId()).getState());
        assertEquals(cluster.getCreationDateTime().plusSeconds(10), early.getStartDateTime());
        assertEquals("RUNNING", service.describeCluster(cluster.getId()).getState());
    }

    @Test
    void aWaitingClusterIsRunningWhileItRunsAddedStepsAndWaitsAgainAfter() {
        EmrServiceFixture fixture = new EmrServiceFixture(0, 60, 0);
        EmrService service = fixture.service;
        String id = service.runJobFlow(cluster("kept", true), REGION).getId();
        assertEquals("Cluster ready to run steps.", service.describeCluster(id).getStateChangeMessage());

        service.addJobFlowSteps(id, List.of(step("late")));
        EmrCluster running = service.describeCluster(id);
        assertEquals("RUNNING", running.getState());
        assertNull(running.getStateChangeReasonCode());
        assertNull(running.getStateChangeMessage());
        fixture.clock.advance(Duration.ofSeconds(60));

        EmrCluster waiting = service.describeCluster(id);
        assertEquals("WAITING", waiting.getState());
        assertEquals("ALL_STEPS_COMPLETED", waiting.getStateChangeReasonCode());
        assertEquals(List.of("STARTING", "WAITING", "PENDING late", "RUNNING late", "COMPLETED late", "WAITING"),
                fixture.states());
    }

    @Test
    void addingNoStepsLeavesAWaitingClusterAsItIs() {
        EmrServiceFixture fixture = new EmrServiceFixture(0, 60, 0);
        EmrService service = fixture.service;
        String fresh = service.runJobFlow(cluster("fresh", true), REGION).getId();
        String done = service.runJobFlow(cluster("done", true, step("only")), REGION).getId();
        fixture.clock.advance(Duration.ofSeconds(60));
        // Set to end after its steps: a cluster that already waits ends only after the next ones it runs.
        service.setKeepJobFlowAliveWhenNoSteps(List.of(done), false);
        fixture.published.clear();

        assertEquals(List.of(), service.addJobFlowSteps(fresh, List.of()));
        assertEquals(List.of(), service.addJobFlowSteps(done, List.of()));

        assertEquals("Cluster ready to run steps.", service.describeCluster(fresh).getStateChangeMessage());
        EmrCluster waiting = service.describeCluster(done);
        assertEquals("WAITING", waiting.getState());
        assertEquals("ALL_STEPS_COMPLETED", waiting.getStateChangeReasonCode());
        assertEquals(List.of(), fixture.states());
    }

    @Test
    void cancellingAPendingStepLeavesTheRunningOneRunning() {
        EmrServiceFixture fixture = new EmrServiceFixture(0, 60, 0);
        EmrService service = fixture.service;
        EmrCluster cluster = service.runJobFlow(cluster("cancel", true, step("a"), step("b")), REGION);
        EmrStep b = cluster.getSteps().get(1);

        List<EmrService.CancelStepInfo> infos = service.cancelSteps(cluster.getId(), List.of(b.getId()));

        assertEquals(List.of(new EmrService.CancelStepInfo(b.getId(), "SUBMITTED", null)), infos);
        assertEquals(List.of("CANCELLED", "RUNNING"), states(service.listSteps(cluster.getId(), null, null)));
        assertEquals("CANCELLED b", fixture.states().get(fixture.states().size() - 1));
    }

    @Test
    void cancellingARunningStepLetsTheNextOneRun() {
        EmrServiceFixture fixture = new EmrServiceFixture(0, 60, 0);
        EmrService service = fixture.service;
        EmrCluster cluster = service.runJobFlow(cluster("cancel", true, step("a"), step("b")), REGION);
        EmrStep a = cluster.getSteps().get(0);
        fixture.clock.advance(Duration.ofSeconds(10));

        List<EmrService.CancelStepInfo> infos = service.cancelSteps(cluster.getId(), List.of(a.getId()));

        assertEquals(List.of(new EmrService.CancelStepInfo(a.getId(), "SUBMITTED", null)), infos);
        assertEquals(List.of("RUNNING", "CANCELLED"), states(service.listSteps(cluster.getId(), null, null)));
        assertEquals(a.getEndDateTime(), cluster.getSteps().get(1).getStartDateTime());
        assertEquals(List.of("CANCELLED a", "RUNNING b"),
                fixture.states().subList(fixture.states().size() - 2, fixture.states().size()));
    }

    @Test
    void aReleaseBefore528CancelsOnlyPendingSteps() {
        EmrServiceFixture fixture = new EmrServiceFixture(0, 60, 0);
        EmrService service = fixture.service;
        EmrCluster old = cluster("old", true, step("a"), step("b"));
        old.setReleaseLabel("emr-5.27.0");
        EmrCluster cluster = service.runJobFlow(old, REGION);
        String a = cluster.getSteps().get(0).getId();
        String b = cluster.getSteps().get(1).getId();

        List<EmrService.CancelStepInfo> infos = service.cancelSteps(cluster.getId(), List.of(a, b));

        assertEquals(List.of(
                new EmrService.CancelStepInfo(a, "FAILED", "Step is not in a cancellable state: RUNNING"),
                new EmrService.CancelStepInfo(b, "SUBMITTED", null)), infos);
        assertTrue(EmrClusterLifecycle.cancelsRunningSteps("emr-5.28.0"));
        assertTrue(EmrClusterLifecycle.cancelsRunningSteps("emr-7.5.0"));
        assertFalse(EmrClusterLifecycle.cancelsRunningSteps("emr-4.9.6"));
    }

    @Test
    void terminatingCancelsTheStepsNotYetCompletedAndEndsAfterTheDelay() {
        EmrServiceFixture fixture = new EmrServiceFixture(0, 60, 30);
        EmrService service = fixture.service;
        EmrCluster kept = cluster("ending", true, step("a"), step("b"));
        kept.getInstanceGroups().add(group("CORE", 2));
        EmrCluster cluster = service.runJobFlow(kept, REGION);
        String id = cluster.getId();
        fixture.clock.advance(Duration.ofSeconds(10));
        fixture.published.clear();

        service.terminateJobFlows(List.of(id));

        EmrCluster terminating = service.describeCluster(id);
        assertEquals("TERMINATING", terminating.getState());
        assertEquals("USER_REQUEST", terminating.getStateChangeReasonCode());
        assertNull(terminating.getEndDateTime());
        assertEquals(List.of("CANCELLED", "CANCELLED"), states(service.listSteps(id, null, null)));
        assertEquals("RUNNING", service.listInstanceGroups(id).get(0).getState());
        AwsException refused = assertThrows(AwsException.class,
                () -> service.addJobFlowSteps(id, List.of(step("late"))));
        assertEquals(SHUT_DOWN, refused.getMessage());
        service.terminateJobFlows(List.of(id));
        assertEquals(List.of("CANCELLED a", "CANCELLED b"), fixture.states());
        fixture.clock.advance(Duration.ofSeconds(30));

        EmrCluster ended = service.describeCluster(id);
        assertEquals("TERMINATED", ended.getState());
        assertEquals(terminating.getTerminatingDateTime().plusSeconds(30), ended.getEndDateTime());
        assertEquals("TERMINATED", service.listInstanceGroups(id).get(0).getState());
        assertEquals(0, service.listInstanceGroups(id).get(0).getRunningInstanceCount());
        assertEquals(List.of("CANCELLED a", "CANCELLED b", "TERMINATED"), fixture.states());
    }

    @Test
    void instanceGroupsChangeOnlyWhileTheClusterRunsOrWaits() {
        EmrServiceFixture fixture = new EmrServiceFixture(10, 0, 30);
        EmrService service = fixture.service;
        EmrCluster withGroup = cluster("groups", true);
        withGroup.getInstanceGroups().add(group("CORE", 2));
        EmrCluster cluster = service.runJobFlow(withGroup, REGION);
        String groupId = cluster.getInstanceGroups().get(0).getId();
        List<EmrService.InstanceGroupModification> resize =
                List.of(new EmrService.InstanceGroupModification(groupId, 3, null));

        AwsException starting = assertThrows(AwsException.class,
                () -> service.modifyInstanceGroups(cluster.getId(), REGION, resize));
        assertEquals("An instance group may only be modified when the cluster is running or waiting.",
                starting.getMessage());
        fixture.clock.advance(Duration.ofSeconds(10));
        service.modifyInstanceGroups(null, REGION, resize);
        assertEquals(3, service.listInstanceGroups(cluster.getId()).get(0).getRequestedInstanceCount());
        service.terminateJobFlows(List.of(cluster.getId()));

        assertThrows(AwsException.class, () -> service.modifyInstanceGroups(cluster.getId(), REGION, resize));
        AwsException adding = assertThrows(AwsException.class,
                () -> service.addInstanceGroups(cluster.getId(), List.of(group("TASK", 1))));
        assertEquals("ValidationException", adding.getErrorCode());
        assertEquals(SHUT_DOWN, adding.getMessage());
        assertEquals(1, service.listInstanceGroups(cluster.getId()).size());
    }

    @Test
    void anInstanceFleetIsResizedOnlyOnceTheClusterRunsOrWaits() {
        EmrServiceFixture fixture = new EmrServiceFixture(10, 0, 0);
        EmrService service = fixture.service;
        EmrCluster withFleet = cluster("starting", true);
        EmrInstanceFleet fleet = fleet("CORE", 2);
        withFleet.getInstanceFleets().add(fleet);
        String id = service.runJobFlow(withFleet, REGION).getId();

        AwsException starting = assertThrows(AwsException.class,
                () -> service.modifyInstanceFleet(id, fleet.getId(), 3, null));
        fixture.clock.advance(Duration.ofSeconds(5));
        AwsException bootstrapping = assertThrows(AwsException.class,
                () -> service.modifyInstanceFleet(id, fleet.getId(), 3, null));
        fixture.clock.advance(Duration.ofSeconds(5));
        service.modifyInstanceFleet(id, fleet.getId(), 3, null);

        assertNotRunningOrWaiting(starting);
        assertNotRunningOrWaiting(bootstrapping);
        assertEquals(3, service.listInstanceFleets(id).get(0).getTargetOnDemandCapacity());
    }

    @Test
    void anInstanceFleetIsNotAddedOrResizedWhileTheClusterShutsDown() {
        EmrServiceFixture fixture = new EmrServiceFixture(0, 0, 30);
        EmrService service = fixture.service;
        EmrCluster withFleet = cluster("fleets", true);
        EmrInstanceFleet fleet = fleet("CORE", 2);
        withFleet.getInstanceFleets().add(fleet);
        EmrCluster cluster = service.runJobFlow(withFleet, REGION);
        service.terminateJobFlows(List.of(cluster.getId()));

        AwsException resizing = assertThrows(AwsException.class,
                () -> service.modifyInstanceFleet(cluster.getId(), fleet.getId(), 3, null));
        AwsException adding = assertThrows(AwsException.class,
                () -> service.addInstanceFleet(cluster.getId(), new EmrInstanceFleet()));

        assertShutDown(resizing);
        assertShutDown(adding);
        assertEquals(1, service.listInstanceFleets(cluster.getId()).size());
    }

    @Test
    void listingClustersByStateFindsThemAsTheyStandNow() {
        EmrServiceFixture fixture = new EmrServiceFixture(10, 0, 0);
        EmrService service = fixture.service;
        String id = service.runJobFlow(cluster("listed", true), REGION).getId();

        assertEquals(List.of(), service.listClusters(REGION, List.of("WAITING"), null, null));
        fixture.clock.advance(Duration.ofSeconds(10));

        assertEquals(List.of(id),
                service.listClusters(REGION, List.of("WAITING"), null, null).stream().map(EmrCluster::getId).toList());
    }

    @Test
    void aClusterLeftMidChangeByARestartMovesOnWhenItFallsDue() {
        EmrServiceFixture fixture = new EmrServiceFixture(10, 0, 0);
        EmrCluster cluster = fixture.service.runJobFlow(cluster("restarted", true), REGION);

        EmrServiceFixture restarted = fixture.restart();

        assertEquals(List.of(cluster.getCreationDateTime().plusSeconds(5)), restarted.wakeupTimes());
        restarted.pass(Duration.ofSeconds(10));
        assertEquals(List.of("WAITING"), restarted.states());
    }

    @Test
    void whatACallReturnsStaysAsItWasWhenTheClusterMovesOn() {
        EmrServiceFixture fixture = new EmrServiceFixture(0, 60, 30);
        EmrService service = fixture.service;
        EmrCluster read = cluster("read", true, step("only"));
        read.getInstanceGroups().add(group("CORE", 2));
        read.getInstanceFleets().add(fleet("TASK", 2));
        EmrCluster created = service.runJobFlow(read, REGION);
        String id = created.getId();
        String stepId = created.getSteps().get(0).getId();

        List<EmrCluster> clusters = List.of(service.describeCluster(id),
                service.listClusters(REGION, null, null, null).get(0), service.getClusterForInstances(id));
        List<EmrStep> steps = List.of(service.describeStep(id, stepId), service.listSteps(id, null, null).get(0));
        EmrInstanceGroup group = service.listInstanceGroups(id).get(0);
        EmrInstanceFleet fleet = service.listInstanceFleets(id).get(0);
        fixture.pass(Duration.ofSeconds(60));
        service.terminateJobFlows(List.of(id));
        fixture.pass(Duration.ofSeconds(30));

        assertEquals("TERMINATED", service.describeCluster(id).getState());
        assertEquals(List.of("RUNNING", "RUNNING", "RUNNING"), clusters.stream().map(EmrCluster::getState).toList());
        assertEquals(List.of("RUNNING", "RUNNING"), states(steps));
        assertNull(steps.get(0).getEndDateTime());
        assertEquals(2, group.getRunningInstanceCount());
        assertEquals(2, fleet.getProvisionedOnDemandCapacity());
    }

    @Test
    void aSlowDeliveryDoesNotHoldUpTheTimer() throws InterruptedException {
        EmrService.Wakeups timer = EmrService.Wakeups.timer(new MutableClock());
        CountDownLatch woken = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        try {
            // Like a rule's target that answers only once the next cluster has changed.
            timer.deliver(() -> {
                try {
                    if (woken.await(10, TimeUnit.SECONDS)) {
                        delivered.countDown();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            timer.at(Instant.EPOCH, woken::countDown);

            assertTrue(delivered.await(10, TimeUnit.SECONDS));
        } finally {
            timer.close();
        }
    }

    @Test
    void aNegativeDurationIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new EmrServiceFixture(0, -1, 0));

        assertEquals("floci.services.emr.step-duration-seconds must not be negative: -1", refused.getMessage());
    }

    /** ModifyInstanceFleet's answer for a job flow that is shutting down, which AddInstanceFleet shares. */
    private static void assertShutDown(AwsException refused) {
        assertEquals("InvalidRequestException", refused.getErrorCode());
        assertEquals(SHUT_DOWN, refused.getMessage());
        assertEquals(Map.of("ErrorCode", "INVALID_JOB_SHUT_DOWN"), refused.getExtendedData());
    }

    /** ModifyInstanceFleet's answer for a cluster that is starting or bootstrapping. */
    private static void assertNotRunningOrWaiting(AwsException refused) {
        assertEquals("InvalidRequestException", refused.getErrorCode());
        assertEquals("An instance fleet may only be modified when the cluster is running or waiting.",
                refused.getMessage());
        assertEquals(Map.of("ErrorCode", "MODIFY_INSTANCE_FLEET_OF_RUNNING_JOBFLOW_ONLY"), refused.getExtendedData());
    }

    private static EmrInstanceGroup group(String type, int count) {
        EmrInstanceGroup group = new EmrInstanceGroup();
        group.setInstanceGroupType(type);
        group.setInstanceType("m5.xlarge");
        group.setRequestedInstanceCount(count);
        return group;
    }

    private static EmrInstanceFleet fleet(String type, int onDemandCapacity) {
        EmrInstanceFleet fleet = new EmrInstanceFleet();
        fleet.setInstanceFleetType(type);
        fleet.setTargetOnDemandCapacity(onDemandCapacity);
        return fleet;
    }

    /** ListSteps' states, newest step first, as it lists them. */
    private static List<String> states(List<EmrStep> steps) {
        return steps.stream().map(EmrStep::getState).toList();
    }

    private static List<String> ids(List<EmrStep> steps) {
        return steps.stream().map(EmrStep::getId).toList();
    }
}
