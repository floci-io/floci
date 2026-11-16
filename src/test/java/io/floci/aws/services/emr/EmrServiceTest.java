package io.floci.aws.services.emr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.emr.model.EmrCluster;
import io.floci.aws.services.emr.model.EmrInstanceFleet;
import io.floci.aws.services.emr.model.EmrInstanceGroup;
import io.floci.aws.services.emr.model.EmrStep;
import io.floci.aws.testing.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.floci.aws.services.emr.EmrServiceFixture.REGION;
import static io.floci.aws.services.emr.EmrServiceFixture.cluster;
import static io.floci.aws.services.emr.EmrServiceFixture.step;
import static io.floci.aws.services.emr.EmrServiceFixture.stepStates;
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
    private static final String MANAGED_SCALING =
            "{\"ComputeLimits\":{\"UnitType\":\"Instances\",\"MinimumCapacityUnits\":1,\"MaximumCapacityUnits\":5}}";
    private static final String AUTO_TERMINATION = "{\"IdleTimeout\":3600}";
    private static final String AUTO_SCALING = "{\"Constraints\":{\"MinCapacity\":1,\"MaxCapacity\":4},"
            + "\"Rules\":[{\"Name\":\"scale-out\",\"Action\":{},\"Trigger\":{}}]}";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A tag call's {@code ResourceId} is the cluster the tag handlers find by it: one in the request's Region, or one
     * stored with no Region, which they find in any.
     */
    @Test
    void aTagCallsResourceIdIsTheClusterTheTagHandlersFind() {
        EmrServiceFixture fixture = new EmrServiceFixture(0, 0, 0);
        EmrService service = fixture.service;
        EmrCluster cluster = service.runJobFlow(cluster("tagged", true), REGION);

        assertEquals(List.of(cluster.getClusterArn()), service.through("ResourceId", cluster.getId(), REGION));
        assertEquals(List.of(), service.through("ResourceId", cluster.getId(), "eu-west-1"));
        assertEquals(List.of(), service.through("ResourceId", "j-UNKNOWN", REGION));

        AccountAwareStorageBackend<EmrCluster> clusters = fixture.storage.create("emr", "emr-clusters.json",
                new TypeReference<Map<String, EmrCluster>>() {});
        EmrCluster regionless = clusters.get(cluster.getId()).orElseThrow();
        regionless.setRegion(null);
        clusters.put(cluster.getId(), regionless);
        assertEquals(List.of(cluster.getClusterArn()), service.through("ResourceId", cluster.getId(), "eu-west-1"));
    }

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
        assertEquals(List.of("PENDING", "RUNNING"), stepStates(service.listSteps(id, null, null)));
        assertEquals(List.of(b.getId()), ids(service.listSteps(id, List.of("PENDING"), null)));
        fixture.clock.advance(Duration.ofSeconds(60));
        assertEquals(List.of("RUNNING", "COMPLETED"), stepStates(service.listSteps(id, null, null)));
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

        assertEquals(List.of("PENDING", "RUNNING", "RUNNING"), stepStates(service.listSteps(id, null, null)));
        fixture.clock.advance(Duration.ofSeconds(10));
        service.modifyCluster(id, 3);
        assertEquals(List.of("RUNNING", "RUNNING", "RUNNING"), stepStates(service.listSteps(id, null, null)));
        // Lowering the level lets the steps already running complete.
        service.modifyCluster(id, 1);
        fixture.clock.advance(Duration.ofSeconds(60));

        assertEquals(List.of("COMPLETED", "COMPLETED", "COMPLETED"), stepStates(service.listSteps(id, null, null)));
        assertEquals("WAITING", service.describeCluster(id).getState());
    }

    @Test
    void aStepConcurrencyLevelBelowOneOrAbove256IsRefused() {
        EmrService service = new EmrServiceFixture(0, 60, 0).service;
        EmrCluster wide = cluster("wide", true);
        wide.setStepConcurrencyLevel(257);

        // "You can specify a minimum of 1 step and a maximum of 256 steps", in each operation's own code.
        AwsException created = assertThrows(AwsException.class, () -> service.runJobFlow(wide, REGION));
        assertEquals("ValidationException", created.getErrorCode());
        assertEquals("The StepConcurrencyLevel 257 is not valid: it must be from 1 to 256.", created.getMessage());
        String id = service.runJobFlow(cluster("narrow", true), REGION).getId();
        AwsException modified = assertThrows(AwsException.class, () -> service.modifyCluster(id, 0));
        assertEquals("InvalidRequestException", modified.getErrorCode());
        assertEquals("The StepConcurrencyLevel 0 is not valid: it must be from 1 to 256.", modified.getMessage());
        assertEquals(1, service.describeCluster(id).getStepConcurrencyLevel());
        assertEquals(256, service.modifyCluster(id, 256));
    }

    @Test
    void onlyContinueMayBeAddedToAClusterThatRunsStepsInParallel() {
        EmrService service = new EmrServiceFixture(0, 60, 0).service;
        EmrCluster parallel = cluster("parallel", true);
        parallel.setStepConcurrencyLevel(2);
        String id = service.runJobFlow(parallel, REGION).getId();
        EmrStep terminating = step("terminating");
        terminating.setActionOnFailure("TERMINATE_CLUSTER");

        // "The step is not submitted and the action fails with a message that the ActionOnFailure setting is not
        // valid."
        AwsException refused = assertThrows(AwsException.class,
                () -> service.addJobFlowSteps(id, List.of(step("fine"), terminating)));
        assertEquals("ValidationException", refused.getErrorCode());
        assertEquals("The ActionOnFailure setting TERMINATE_CLUSTER is not valid for a step added to a cluster whose"
                + " StepConcurrencyLevel is greater than 1: only CONTINUE is.", refused.getMessage());
        assertEquals(List.of(), service.listSteps(id, null, null));
        // One that names none takes TERMINATE_CLUSTER, so it is refused too.
        EmrStep unnamed = step("unnamed");
        unnamed.setActionOnFailure(null);
        assertThrows(AwsException.class, () -> service.addJobFlowSteps(id, List.of(unnamed)));
        assertEquals(List.of(), service.listSteps(id, null, null));
        // At a level of 1 both are fine.
        service.modifyCluster(id, 1);
        assertEquals(2, service.addJobFlowSteps(id, List.of(terminating, unnamed)).size());
        assertEquals("TERMINATE_CLUSTER", service.describeStep(id, unnamed.getId()).getActionOnFailure());
    }

    @Test
    void onlyTwoHundredFiftySixStepsMayBePendingOrRunningAtOnce() {
        EmrService service = new EmrServiceFixture(0, 3600, 0).service;
        List<EmrStep> many = new ArrayList<>();
        for (int n = 0; n < 257; n++) {
            many.add(step("step-" + n));
        }
        EmrCluster crowded = cluster("crowded", true);
        crowded.getSteps().addAll(many);

        AwsException created = assertThrows(AwsException.class, () -> service.runJobFlow(crowded, REGION));
        assertEquals("ValidationException", created.getErrorCode());
        assertEquals("Only 256 steps can be PENDING or RUNNING at any given time, and this would make 257.",
                created.getMessage());
        EmrCluster full = cluster("full", true);
        full.getSteps().addAll(many.subList(0, 256));
        String id = service.runJobFlow(full, REGION).getId();
        AwsException added = assertThrows(AwsException.class,
                () -> service.addJobFlowSteps(id, List.of(step("one-more"))));
        assertEquals("Only 256 steps can be PENDING or RUNNING at any given time, and this would make 257.",
                added.getMessage());
        // One ended makes room again.
        service.cancelSteps(id, List.of(service.listSteps(id, List.of("PENDING"), null).get(0).getId()));
        assertEquals(1, service.addJobFlowSteps(id, List.of(step("one-more"))).size());
    }

    @Test
    void cancelStepsTakesReleasesFrom480ButNot500AndAtMost256Steps() {
        EmrService service = new EmrServiceFixture(0, 60, 0).service;
        EmrCluster old = cluster("old", true);
        old.setReleaseLabel("emr-4.7.2");
        String oldId = service.runJobFlow(old, REGION).getId();
        EmrCluster first = cluster("first", true);
        first.setReleaseLabel("emr-5.0.0");
        String firstId = service.runJobFlow(first, REGION).getId();
        EmrCluster patched = cluster("patched", true);
        patched.setReleaseLabel("emr-5.0.3");
        String patchedId = service.runJobFlow(patched, REGION).getId();

        // "Available only in Amazon EMR versions 4.8.0 and later, excluding version 5.0.0."
        AwsException older = assertThrows(AwsException.class, () -> service.cancelSteps(oldId, List.of("s-1")));
        assertEquals("InvalidRequestException", older.getErrorCode());
        assertEquals("CancelSteps is available only in Amazon EMR versions 4.8.0 and later, excluding version 5.0.0,"
                + " and cluster " + oldId + " runs emr-4.7.2.", older.getMessage());
        assertThrows(AwsException.class, () -> service.cancelSteps(firstId, List.of("s-1")));
        assertEquals(1, service.cancelSteps(patchedId, List.of("s-1")).size());
        // "A maximum of 256 steps are allowed in each CancelSteps request."
        List<String> ids = Collections.nCopies(257, "s-1");
        AwsException tooMany = assertThrows(AwsException.class, () -> service.cancelSteps(patchedId, ids));
        assertEquals("A CancelSteps request can cancel at most 256 steps, and this one names 257.",
                tooMany.getMessage());
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
        assertEquals(List.of("CANCELLED", "RUNNING"), stepStates(service.listSteps(cluster.getId(), null, null)));
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
        assertEquals(List.of("RUNNING", "CANCELLED"), stepStates(service.listSteps(cluster.getId(), null, null)));
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
    void aClustersInstanceGroupsAndFleetsFollowItsStateAndRunNothingWhileTheyAreProvisioned() {
        EmrServiceFixture fixture = new EmrServiceFixture(10, 0, 30);
        EmrService service = fixture.service;
        EmrCluster requested = cluster("states", true);
        requested.getInstanceGroups().addAll(List.of(group("MASTER", 1), group("CORE", 2)));
        String id = service.runJobFlow(requested, REGION).getId();
        EmrCluster fleets = cluster("fleets", true);
        fleets.getInstanceFleets().add(fleet("CORE", 3));
        String fleetsId = service.runJobFlow(fleets, REGION).getId();

        assertEquals(List.of("PROVISIONING", "PROVISIONING"), groupStates(service, id));
        assertEquals(0, service.listInstanceGroups(id).get(1).getRunningInstanceCount());
        assertEquals("PROVISIONING", service.listInstanceFleets(fleetsId).get(0).getState());
        assertEquals(0, service.listInstanceFleets(fleetsId).get(0).getProvisionedOnDemandCapacity());
        // One added while it starts is as its others are.
        service.addInstanceGroups(id, List.of(group("TASK", 1)));
        assertEquals(List.of("PROVISIONING", "PROVISIONING", "PROVISIONING"), groupStates(service, id));

        fixture.clock.advance(Duration.ofSeconds(5));
        assertEquals("BOOTSTRAPPING", service.describeCluster(id).getState());
        assertEquals(List.of("BOOTSTRAPPING", "BOOTSTRAPPING", "BOOTSTRAPPING"), groupStates(service, id));
        assertEquals(2, service.listInstanceGroups(id).get(1).getRunningInstanceCount());
        assertEquals(3, service.listInstanceFleets(fleetsId).get(0).getProvisionedOnDemandCapacity());

        fixture.clock.advance(Duration.ofSeconds(5));
        assertEquals("WAITING", service.describeCluster(id).getState());
        assertEquals(List.of("RUNNING", "RUNNING", "RUNNING"), groupStates(service, id));
        assertEquals("RUNNING", service.listInstanceFleets(fleetsId).get(0).getState());

        service.terminateJobFlows(List.of(id));
        assertEquals(List.of("TERMINATING", "TERMINATING", "TERMINATING"), groupStates(service, id));
        fixture.clock.advance(Duration.ofSeconds(30));
        assertEquals(List.of("TERMINATED", "TERMINATED", "TERMINATED"), groupStates(service, id));
        assertEquals(0, service.listInstanceGroups(id).get(1).getRunningInstanceCount());
    }

    @Test
    void aGroupThatEndsWhileItIsProvisionedListsTheInstancesItAskedForAsEnded() {
        EmrServiceFixture fixture = new EmrServiceFixture(10, 0, 30);
        EmrService service = fixture.service;
        EmrCluster requested = cluster("ends-early", true);
        requested.getInstanceGroups().addAll(List.of(group("MASTER", 1), group("CORE", 2)));
        String id = service.runJobFlow(requested, REGION).getId();
        assertEquals(Map.of("PROVISIONING", 3L), listedByState(service.describeCluster(id), false));

        service.terminateJobFlows(List.of(id));

        // The same instances while it ends, still being provisioned, with none of them running.
        EmrCluster ending = service.describeCluster(id);
        assertEquals("TERMINATING", ending.getState());
        assertEquals(List.of(0, 0), service.listInstanceGroups(id).stream()
                .map(EmrInstanceGroup::getRunningInstanceCount).toList());
        assertEquals(Map.of("PROVISIONING", 3L), listedByState(ending, false));
        fixture.clock.advance(Duration.ofSeconds(30));

        // Listed as provisioned, then as ended, as an ended fleet's are.
        EmrCluster ended = service.describeCluster(id);
        assertEquals("TERMINATED", ended.getState());
        assertEquals(2, service.listInstanceGroups(id).get(1).getTerminatedInstanceCount());
        assertEquals(0, service.listInstanceGroups(id).get(1).getRunningInstanceCount());
        assertEquals(Map.of("TERMINATED", 3L), listedByState(ended, true));
    }

    @Test
    void aFleetThatEndsWhileItIsProvisionedKeepsItsInstancesListedUntilTheyEnd() {
        EmrServiceFixture fixture = new EmrServiceFixture(10, 0, 30);
        EmrService service = fixture.service;
        EmrCluster requested = cluster("fleet-ends-early", true);
        requested.getInstanceFleets().addAll(List.of(fleet("MASTER", 1), fleet("CORE", 2)));
        String id = service.runJobFlow(requested, REGION).getId();
        assertEquals(Map.of("PROVISIONING", 3L), listedByState(service.describeCluster(id), false));

        service.terminateJobFlows(List.of(id));

        EmrCluster ending = service.describeCluster(id);
        assertEquals("TERMINATING", ending.getState());
        assertEquals(List.of(0, 0), ending.getInstanceFleets().stream()
                .map(EmrInstanceFleet::getProvisionedOnDemandCapacity).toList());
        assertEquals(Map.of("PROVISIONING", 3L), listedByState(ending, false));
        fixture.clock.advance(Duration.ofSeconds(30));

        EmrCluster ended = service.describeCluster(id);
        assertEquals("TERMINATED", ended.getState());
        assertEquals(Map.of("TERMINATED", 3L), listedByState(ended, true));
    }

    @Test
    void aGroupThatRanIsListedRunningWhileItsClusterEnds() {
        EmrServiceFixture fixture = new EmrServiceFixture(0, 0, 30);
        EmrService service = fixture.service;
        EmrCluster requested = cluster("ends-late", true);
        requested.getInstanceGroups().addAll(List.of(group("MASTER", 1), group("CORE", 2)));
        String id = service.runJobFlow(requested, REGION).getId();
        assertEquals(Map.of("RUNNING", 3L), listedByState(service.describeCluster(id), false));

        service.terminateJobFlows(List.of(id));

        assertEquals("TERMINATING", service.describeCluster(id).getState());
        assertEquals(Map.of("RUNNING", 3L), listedByState(service.describeCluster(id), false));
    }

    /** How many instances ListInstances lists in each state, as its InstanceStates filter keeps them. */
    private static Map<String, Long> listedByState(EmrCluster cluster, boolean terminated) {
        return EmrHandler.listedInstances(cluster, terminated).stream()
                .filter(set -> set.count() > 0)
                .collect(Collectors.groupingBy(EmrHandler.ListedInstances::state,
                        Collectors.summingLong(EmrHandler.ListedInstances::count)));
    }

    private static List<String> groupStates(EmrService service, String clusterId) {
        return service.listInstanceGroups(clusterId).stream().map(EmrInstanceGroup::getState).toList();
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
        assertEquals(List.of("CANCELLED", "CANCELLED"), stepStates(service.listSteps(id, null, null)));
        // Its instances run until they are gone.
        assertEquals("TERMINATING", service.listInstanceGroups(id).get(0).getState());
        assertEquals(2, service.listInstanceGroups(id).get(0).getRunningInstanceCount());
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
    void aClusterShuttingDownKeepsItsSettingsAndPolicies() {
        EmrServiceFixture fixture = new EmrServiceFixture(0, 0, 30);
        EmrService service = fixture.service;
        EmrCluster withGroup = cluster("settings", true);
        withGroup.getInstanceGroups().add(group("CORE", 2));
        EmrCluster cluster = service.runJobFlow(withGroup, REGION);
        String id = cluster.getId();
        String groupId = cluster.getInstanceGroups().get(0).getId();
        service.putManagedScalingPolicy(id, json(MANAGED_SCALING));
        service.putAutoTerminationPolicy(id, json(AUTO_TERMINATION));
        service.putAutoScalingPolicy(id, groupId, json(AUTO_SCALING));
        service.terminateJobFlows(List.of(id));
        EmrCluster before = service.describeCluster(id);
        List<Executable> changes = List.of(
                () -> service.setTerminationProtection(List.of(id), true),
                () -> service.setVisibleToAllUsers(List.of(id), !before.isVisibleToAllUsers()),
                () -> service.setKeepJobFlowAliveWhenNoSteps(List.of(id), false),
                () -> service.setUnhealthyNodeReplacement(List.of(id), true),
                () -> service.putManagedScalingPolicy(id, json(MANAGED_SCALING)),
                () -> service.removeManagedScalingPolicy(id),
                () -> service.putAutoTerminationPolicy(id, json(AUTO_TERMINATION)),
                () -> service.removeAutoTerminationPolicy(id),
                () -> service.putAutoScalingPolicy(id, groupId, json(AUTO_SCALING)),
                () -> service.removeAutoScalingPolicy(id, groupId));

        AwsException modifying = assertThrows(AwsException.class, () -> service.modifyCluster(id, 4));
        List<AwsException> refusals = changes.stream()
                .map(change -> assertThrows(AwsException.class, change))
                .toList();

        assertEquals("TERMINATING", before.getState());
        assertShutDown(modifying);
        for (AwsException refused : refusals) {
            assertEquals("ValidationException", refused.getErrorCode());
            assertEquals(SHUT_DOWN, refused.getMessage());
        }
        EmrCluster after = service.describeCluster(id);
        assertEquals(before.getStepConcurrencyLevel(), after.getStepConcurrencyLevel());
        assertEquals(before.isTerminationProtected(), after.isTerminationProtected());
        assertEquals(before.isVisibleToAllUsers(), after.isVisibleToAllUsers());
        assertEquals(before.isKeepJobFlowAliveWhenNoSteps(), after.isKeepJobFlowAliveWhenNoSteps());
        assertEquals(before.isAutoTerminate(), after.isAutoTerminate());
        assertEquals(before.isUnhealthyNodeReplacement(), after.isUnhealthyNodeReplacement());
        assertEquals(json(MANAGED_SCALING).toString(), after.getManagedScalingPolicy());
        assertEquals(json(AUTO_TERMINATION).toString(), after.getAutoTerminationPolicy());
        assertEquals(json(AUTO_SCALING).toString(), after.getInstanceGroups().get(0).getAutoScalingPolicy());
    }

    @Test
    void aClusterShuttingDownHasNoManagedScalingPolicyToRead() {
        EmrServiceFixture fixture = new EmrServiceFixture(0, 0, 30);
        EmrService service = fixture.service;
        String id = service.runJobFlow(cluster("scaled", true), REGION).getId();
        service.putManagedScalingPolicy(id, json(MANAGED_SCALING));
        service.terminateJobFlows(List.of(id));

        AwsException reading = assertThrows(AwsException.class, () -> service.getManagedScalingPolicy(id));

        assertEquals("TERMINATING", service.describeCluster(id).getState());
        assertEquals("ValidationException", reading.getErrorCode());
        assertEquals(SHUT_DOWN, reading.getMessage());
    }

    @Test
    void tagsChangeUntilTheClusterHasEnded() {
        EmrServiceFixture fixture = new EmrServiceFixture(0, 0, 30);
        EmrService service = fixture.service;
        String id = service.runJobFlow(cluster("tagged", true), REGION).getId();
        service.terminateJobFlows(List.of(id));
        service.addTags(id, Map.of("team", "data", "stage", "test"));
        service.removeTags(id, List.of("stage"));
        fixture.clock.advance(Duration.ofSeconds(30));

        AwsException adding = assertThrows(AwsException.class, () -> service.addTags(id, Map.of("owner", "me")));
        AwsException removing = assertThrows(AwsException.class, () -> service.removeTags(id, List.of("team")));

        assertEquals("TERMINATED", service.describeCluster(id).getState());
        for (AwsException refused : List.of(adding, removing)) {
            assertEquals("InvalidRequestException", refused.getErrorCode());
            assertEquals("Tags cannot be modified on terminated clusters.", refused.getMessage());
        }
        assertEquals(Map.of("team", "data"), service.describeCluster(id).getTags());
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
        EmrCluster created = service.runJobFlow(read, REGION);
        String id = created.getId();
        String stepId = created.getSteps().get(0).getId();
        // A cluster has instance groups or fleets, never both, so the fleet is another cluster's.
        EmrCluster fleets = cluster("fleets", true, step("only"));
        fleets.getInstanceFleets().add(fleet("TASK", 2));
        String fleetsId = service.runJobFlow(fleets, REGION).getId();

        List<EmrCluster> clusters = List.of(service.describeCluster(id),
                service.listClusters(REGION, null, null, null).get(0), service.getClusterForInstances(id));
        List<EmrStep> steps = List.of(service.describeStep(id, stepId), service.listSteps(id, null, null).get(0));
        EmrInstanceGroup group = service.listInstanceGroups(id).get(0);
        EmrInstanceFleet fleet = service.listInstanceFleets(fleetsId).get(0);
        fixture.pass(Duration.ofSeconds(60));
        service.terminateJobFlows(List.of(id, fleetsId));
        fixture.pass(Duration.ofSeconds(30));

        assertEquals("TERMINATED", service.describeCluster(id).getState());
        assertEquals(List.of("RUNNING", "RUNNING", "RUNNING"), clusters.stream().map(EmrCluster::getState).toList());
        assertEquals(List.of("RUNNING", "RUNNING"), stepStates(steps));
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

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(text, e);
        }
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

    private static List<String> ids(List<EmrStep> steps) {
        return steps.stream().map(EmrStep::getId).toList();
    }
}
