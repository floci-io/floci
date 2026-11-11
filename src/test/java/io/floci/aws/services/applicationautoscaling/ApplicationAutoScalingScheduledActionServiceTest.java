package io.floci.aws.services.applicationautoscaling;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.applicationautoscaling.model.ScalableTarget;
import io.floci.aws.services.applicationautoscaling.model.ScalingActivity;
import io.floci.aws.services.applicationautoscaling.model.ScalingPolicy;
import io.floci.aws.services.applicationautoscaling.model.ScheduledAction;
import io.floci.aws.services.applicationautoscaling.model.ScheduledAction.State;
import io.floci.aws.services.applicationautoscaling.model.SuspendedState;
import io.floci.aws.services.applicationautoscaling.model.TargetTrackingConfiguration;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.floci.aws.services.cloudwatch.metrics.model.MetricAlarm;
import io.floci.aws.testing.StorageFactories;
import io.floci.aws.testing.ThreadStates;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.floci.aws.services.applicationautoscaling.ScheduledActionFixtures.schedule;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApplicationAutoScalingScheduledActionServiceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String NAMESPACE = "ecs";
    private static final String RESOURCE = "service/cluster/service";
    private static final String DIMENSION = "ecs:service:DesiredCount";
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    private ApplicationAutoScalingService service;
    private EcsCapacityAdjuster adjuster;
    private InMemoryStorage<String, ScheduledAction> actions;
    private AtomicInteger capacity;

    @BeforeEach
    void setUp() {
        actions = new InMemoryStorage<>();
        capacity = new AtomicInteger(5);
        adjuster = mock(EcsCapacityAdjuster.class);
        when(adjuster.getCurrentCapacity(anyString(), anyString())).thenAnswer(call -> capacity.get());
        doAnswer(call -> {
            capacity.set(call.getArgument(1));
            return null;
        }).when(adjuster).setCapacity(anyString(), anyInt(), anyString());
        service = new ApplicationAutoScalingService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), actions, new RegionResolver(REGION, ACCOUNT),
                mock(CloudWatchMetricsService.class), adjuster);
        register(null);
    }

    private void register(SuspendedState suspension) {
        service.registerScalableTarget(NAMESPACE, RESOURCE, DIMENSION, 1, 10, null, suspension, null, REGION);
    }

    private ScheduledAction put(String name, Instant deadline, Integer min, Integer max) {
        return service.putScheduledAction(NAMESPACE, RESOURCE, DIMENSION, name,
                deadline == null ? null : schedule(deadline), true, min, max, null, REGION, NOW);
    }

    private List<ScheduledAction> describe() {
        return service.describeScheduledActions(NAMESPACE, RESOURCE, DIMENSION, List.of(), null, null, REGION).items();
    }

    private ScheduledAction action(String name) {
        return describe().stream().filter(a -> name.equals(a.getScheduledActionName())).findFirst().orElseThrow();
    }

    private void assertBounds(int min, int max, int desired) {
        ScalableTarget target = service.describeScalableTargets(NAMESPACE, List.of(RESOURCE), DIMENSION, REGION).getFirst();
        assertEquals(min, target.getMinCapacity());
        assertEquals(max, target.getMaxCapacity());
        assertEquals(desired, capacity.get());
    }

    @Test
    void completedActionRetainsIdentityAndOnlyExplicitRescheduleRearms() {
        ScheduledAction original = put("once", NOW.plusSeconds(1), 7, 9);
        service.runScheduledActions(NOW.plusSeconds(1));
        assertBounds(7, 9, 7);
        assertEquals(State.SUCCEEDED, action("once").getState());
        put("once", null, 0, null);
        service.runScheduledActions(NOW.plusSeconds(2));
        assertBounds(7, 9, 7);
        ScheduledAction updated = service.putScheduledAction(NAMESPACE, RESOURCE, DIMENSION, "once",
                schedule(NOW.plusSeconds(3)), false, null, null, null, REGION, NOW.plusSeconds(2));
        assertEquals(original.getScheduledActionArn(), updated.getScheduledActionArn());
        assertEquals(original.getCreationTime(), updated.getCreationTime());
        service.runScheduledActions(NOW.plusSeconds(3));
        assertBounds(0, 9, 7);
        assertEquals(2, service.describeScalingActivities(NAMESPACE, RESOURCE, DIMENSION, REGION).size());
    }

    @Test
    void updatesReplaceWholeActionAndPreserveOmittedFields() {
        put("replace", NOW.plusSeconds(1), 2, 8);
        put("replace", null, 3, null);
        assertNull(action("replace").getMaxCapacity());
        service.putScheduledAction(NAMESPACE, RESOURCE, DIMENSION, "replace", null, false, null, null, null, REGION, NOW);
        assertEquals(schedule(NOW.plusSeconds(1)), action("replace").getSchedule());
        service.runScheduledActions(NOW.plusSeconds(1));
        assertBounds(3, 10, 5);
        put("replace", NOW.plusSeconds(2), null, 4);
        assertNull(action("replace").getMinCapacity());
        service.runScheduledActions(NOW.plusSeconds(2));
        assertBounds(3, 4, 4);
    }

    @Test
    void executionUsesCurrentBoundsAndOrdersByDeadlineThenName() {
        put("z", NOW.plusSeconds(1), 3, null);
        put("b", NOW.plusSeconds(2), null, 2);
        put("a", NOW.plusSeconds(2), 0, null);
        service.runScheduledActions(NOW.plusSeconds(3));
        assertBounds(0, 2, 2);
        assertTrue(describe().stream().allMatch(a -> a.getState() == State.SUCCEEDED));
        assertEquals(3, service.describeScalingActivities(NAMESPACE, RESOURCE, DIMENSION, REGION).size());
    }

    @Test
    void invalidEffectiveBoundsFailWithoutTargetMutationAndDoNotStopOtherActions() {
        put("bad", NOW.plusSeconds(1), 12, null);
        put("good", NOW.plusSeconds(2), null, 4);
        service.runScheduledActions(NOW.plusSeconds(2));
        assertEquals(State.FAILED, action("bad").getState());
        assertEquals(State.SUCCEEDED, action("good").getState());
        assertBounds(1, 4, 4);
    }

    @Test
    void unchangedBoundsStillRecordSuccessfulActivity() {
        put("unchanged", NOW, 1, 10);
        service.runScheduledActions(NOW);
        assertBounds(1, 10, 5);
        verify(adjuster, never()).setCapacity(anyString(), anyInt(), anyString());
        assertEquals("Successful", service.describeScalingActivities(NAMESPACE, RESOURCE, DIMENSION, REGION)
                .getFirst().getStatusCode());
    }

    @Test
    void pastAcceptedInactiveAndCurrentSecondEligible() {
        service.putScheduledAction(NAMESPACE, RESOURCE, DIMENSION, "past", schedule(NOW.minusSeconds(1)),
                true, 0, 0, null, REGION, NOW.plusMillis(900));
        service.putScheduledAction(NAMESPACE, RESOURCE, DIMENSION, "current", schedule(NOW),
                true, 0, 4, null, REGION, NOW.plusMillis(900));
        service.runScheduledActions(NOW.plusSeconds(1));
        assertEquals(State.SKIPPED, action("past").getState());
        assertEquals(State.SUCCEEDED, action("current").getState());
        assertBounds(0, 4, 4);
    }

    @Test
    void replacingPendingScheduleCancelsTheOriginalDeadline() {
        put("move", NOW.plusSeconds(1), 0, 0);
        put("move", NOW.plusSeconds(10), 2, 8);
        service.runScheduledActions(NOW.plusSeconds(1));
        assertBounds(1, 10, 5);
        assertEquals(State.PENDING, action("move").getState());
        service.runScheduledActions(NOW.plusSeconds(10));
        assertBounds(2, 8, 5);
        assertEquals(State.SUCCEEDED, action("move").getState());
    }

    @Test
    void cancellationAndDeregistrationRemoveOccurrences() {
        put("delete", NOW, 0, 0);
        service.deleteScheduledAction(NAMESPACE, RESOURCE, DIMENSION, "delete", REGION);
        service.runScheduledActions(NOW);
        assertBounds(1, 10, 5);
        put("target", NOW, 0, 0);
        service.deregisterScalableTarget(NAMESPACE, RESOURCE, DIMENSION, REGION);
        assertTrue(describe().isEmpty());
        assertThrows(AwsException.class, () -> service.deleteScheduledAction(NAMESPACE, RESOURCE, DIMENSION, "target", REGION));
        assertThrows(AwsException.class, () -> put("missing", NOW, 0, 0));
    }

    @Test
    void suspensionBetweenTicksSettlesPriorStateAndConsumesMissedOccurrences() {
        Instant realNow = Instant.now();
        service.putScheduledAction(NAMESPACE, RESOURCE, DIMENSION, "before-suspend", schedule(realNow.minusSeconds(10)),
                true, 2, null, null, REGION, realNow.minusSeconds(20));
        SuspendedState state = new SuspendedState();
        state.setScheduledScalingSuspended(true);
        register(state);
        assertEquals(State.SUCCEEDED, action("before-suspend").getState());
        service.putScheduledAction(NAMESPACE, RESOURCE, DIMENSION, "during-suspend", schedule(realNow.minusSeconds(5)),
                true, 0, 0, null, REGION, realNow.minusSeconds(20));
        service.registerScalableTarget(NAMESPACE, RESOURCE, DIMENSION, null, null, null, null, null, REGION);
        assertTrue(service.findTarget(REGION, NAMESPACE, RESOURCE, DIMENSION).orElseThrow()
                .getSuspendedState().isScheduledScalingSuspended());
        register(new SuspendedState());
        assertEquals(State.SKIPPED, action("during-suspend").getState());
        assertBounds(1, 10, 5);
    }

    @Test
    void dynamicSuspensionDoesNotGateScheduledScaling() {
        SuspendedState state = new SuspendedState();
        state.setDynamicScalingInSuspended(true);
        state.setDynamicScalingOutSuspended(true);
        register(state);
        put("scheduled", NOW, 7, 10);
        service.runScheduledActions(NOW);
        assertBounds(7, 10, 7);
    }

    @Test
    void ecsFailureLeavesCommittedBoundsAndIsNotRetried() {
        doThrow(new IllegalStateException("ECS failed")).when(adjuster).setCapacity(anyString(), anyInt(), anyString());
        put("failure", NOW, 7, 10);
        service.runScheduledActions(NOW);
        service.runScheduledActions(NOW.plusSeconds(1));
        assertBounds(7, 10, 5);
        assertEquals(State.FAILED, action("failure").getState());
        verify(adjuster).setCapacity(RESOURCE, 7, REGION);
        ScalingActivity activity = service.describeScalingActivities(NAMESPACE, RESOURCE, DIMENSION, REGION).getFirst();
        assertEquals("Failed", activity.getStatusCode());
        assertEquals("ECS failed", activity.getStatusMessage());
    }

    @Test
    void rescheduleBeforeExecutionReordersDueActionsUnderTargetLock() throws Exception {
        put("first", NOW.plusSeconds(1), 0, 3);
        put("second", NOW.plusSeconds(2), 0, 4);
        AtomicReference<Thread> sweepThread = new AtomicReference<>();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> sweep;
            synchronized (service.targetLock(REGION, NAMESPACE, RESOURCE, DIMENSION)) {
                sweep = executor.submit(() -> {
                    sweepThread.set(Thread.currentThread());
                    service.runScheduledActions(NOW.plusSeconds(4));
                });
                ThreadStates.awaitBlockedOrDone(sweepThread, sweep);
                assertFalse(sweep.isDone());
                put("first", NOW.plusSeconds(3), 0, 8);
            }
            sweep.get(10, TimeUnit.SECONDS);
        }
        assertBounds(0, 8, 4);
        assertTrue(describe().stream().allMatch(a -> a.getState() == State.SUCCEEDED));
    }

    @Test
    void claimWriteFailureDoesNotMutateStoredActionOrApplyEffects() {
        InMemoryStorage<String, ScheduledAction> failing = spy(actions);
        service = new ApplicationAutoScalingService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), failing, new RegionResolver(REGION, ACCOUNT),
                mock(CloudWatchMetricsService.class), adjuster);
        register(null);
        put("claim", NOW, 7, 10);
        doThrow(new IllegalStateException("claim write failed")).when(failing).putAllAtomically(anyMap());
        service.runScheduledActions(NOW);
        assertEquals(State.PENDING, action("claim").getState());
        assertBounds(1, 10, 5);
        verifyNoInteractions(adjuster);
    }

    @Test
    void mutationWaitsForClaimedExecutionAndDoesNotOverwriteNewBounds() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> mutationThread = new AtomicReference<>();
        doAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            capacity.set(call.getArgument(1));
            return null;
        }).when(adjuster).setCapacity(anyString(), anyInt(), anyString());
        put("blocked", NOW, 7, 9);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> execution = executor.submit(() -> service.runScheduledActions(NOW));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            Future<?> mutation = executor.submit(() -> {
                mutationThread.set(Thread.currentThread());
                service.tagResource(service.findTarget(REGION, NAMESPACE, RESOURCE, DIMENSION).orElseThrow()
                        .getScalableTargetArn(), Map.of("tag", "value"), REGION);
            });
            ThreadStates.awaitBlockedOrDone(mutationThread, mutation);
            assertFalse(mutation.isDone());
            release.countDown();
            execution.get(10, TimeUnit.SECONDS);
            mutation.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertBounds(7, 9, 7);
        assertEquals(Map.of("tag", "value"), service.findTarget(REGION, NAMESPACE, RESOURCE, DIMENSION).orElseThrow().getTags());
    }

    @Test
    void dynamicExecutionWaitsAndRecomputesUsingScheduledBounds() throws Exception {
        TargetTrackingConfiguration config = new TargetTrackingConfiguration();
        config.setTargetValue(50.0);
        ScalingPolicy policy = service.putScalingPolicy("dynamic", "TargetTrackingScaling", NAMESPACE, RESOURCE,
                DIMENSION, config, null, REGION);
        ScalingPolicyAlarmActionHandler handler = dynamicHandler();
        MetricAlarm alarm = new MetricAlarm();
        alarm.setComparisonOperator("GreaterThanThreshold");
        alarm.setThreshold(50.0);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> dynamicThread = new AtomicReference<>();
        doAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            capacity.set(call.getArgument(1));
            return null;
        }).when(adjuster).setCapacity(anyString(), anyInt(), anyString());
        put("scheduled", NOW, 0, 3);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> execution = executor.submit(() -> service.runScheduledActions(NOW));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            Future<?> dynamic = executor.submit(() -> {
                dynamicThread.set(Thread.currentThread());
                handler.handle(policy.getPolicyArn(), alarm, 100.0, REGION);
            });
            ThreadStates.awaitBlockedOrDone(dynamicThread, dynamic);
            assertFalse(dynamic.isDone());
            release.countDown();
            execution.get(10, TimeUnit.SECONDS);
            dynamic.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertBounds(0, 3, 3);
        verify(adjuster, times(1)).setCapacity(RESOURCE, 3, REGION);
    }

    @SuppressWarnings("unchecked")
    private ScalingPolicyAlarmActionHandler dynamicHandler() {
        Instance<CapacityAdjuster> adjusters = mock(Instance.class);
        when(adjusters.iterator()).thenAnswer(call -> List.<CapacityAdjuster>of(adjuster).iterator());
        when(adjuster.supports(DIMENSION)).thenReturn(true);
        return new ScalingPolicyAlarmActionHandler(service, adjusters);
    }

    @Test
    void scheduledMinimumReductionPermitsLaterDynamicScaleInWithoutForcingIt() {
        capacity.set(1);
        TargetTrackingConfiguration config = new TargetTrackingConfiguration();
        config.setTargetValue(50.0);
        config.setScaleInCooldown(0);
        ScalingPolicy policy = service.putScalingPolicy("idle", "TargetTrackingScaling", NAMESPACE, RESOURCE,
                DIMENSION, config, null, REGION);
        ScalingPolicyAlarmActionHandler handler = dynamicHandler();
        MetricAlarm alarm = new MetricAlarm();
        alarm.setComparisonOperator("LessThanThreshold");
        alarm.setThreshold(50.0);
        handler.handle(policy.getPolicyArn(), alarm, 0.0, REGION);
        assertBounds(1, 10, 1);
        put("lower-minimum", NOW, 0, null);
        service.runScheduledActions(NOW);
        assertBounds(0, 10, 1);
        verify(adjuster, never()).setCapacity(anyString(), anyInt(), anyString());
        handler.handle(policy.getPolicyArn(), alarm, 0.0, REGION);
        assertBounds(0, 10, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete", "reschedule", "deregister"})
    void mutationBeforeClaimPreventsStaleSweepExecution(String mutation) throws Exception {
        put("pending", NOW.plusSeconds(1), 0, 0);
        AtomicReference<Thread> sweepThread = new AtomicReference<>();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> sweep;
            synchronized (service.targetLock(REGION, NAMESPACE, RESOURCE, DIMENSION)) {
                sweep = executor.submit(() -> {
                    sweepThread.set(Thread.currentThread());
                    service.runScheduledActions(NOW.plusSeconds(2));
                });
                ThreadStates.awaitBlockedOrDone(sweepThread, sweep);
                assertFalse(sweep.isDone());
                switch (mutation) {
                    case "delete" -> service.deleteScheduledAction(NAMESPACE, RESOURCE, DIMENSION, "pending", REGION);
                    case "reschedule" -> put("pending", NOW.plusSeconds(10), 0, 0);
                    case "deregister" -> service.deregisterScalableTarget(NAMESPACE, RESOURCE, DIMENSION, REGION);
                    default -> throw new IllegalArgumentException(mutation);
                }
            }
            sweep.get(10, TimeUnit.SECONDS);
        }
        assertEquals(5, capacity.get());
        verifyNoInteractions(adjuster);
        assertTrue(service.describeScalingActivities(NAMESPACE, RESOURCE, DIMENSION, REGION).isEmpty());
    }

    @Test
    void activityWriteFailureAndTerminalWriteFailureCannotRepeatEffects() {
        InMemoryStorage<String, ScalingActivity> activityStore = spy(new InMemoryStorage<>());
        InMemoryStorage<String, ScheduledAction> actionStore = spy(actions);
        service = new ApplicationAutoScalingService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                activityStore, actionStore, new RegionResolver(REGION, ACCOUNT), mock(CloudWatchMetricsService.class), adjuster);
        register(null);
        put("activity", NOW, 7, 9);
        doThrow(new IllegalStateException("activity write failed")).when(activityStore).putAllAtomically(anyMap());
        service.runScheduledActions(NOW);
        assertEquals(State.SUCCEEDED, action("activity").getState());
        doAnswer(call -> {
            Map<String, ScheduledAction> entries = call.getArgument(0);
            if (entries.values().stream().anyMatch(a -> a.getState() == State.SUCCEEDED || a.getState() == State.FAILED)) {
                throw new IllegalStateException("terminal write failed");
            }
            return call.callRealMethod();
        }).when(actionStore).putAllAtomically(anyMap());
        put("terminal", NOW.plusSeconds(1), 8, 9);
        service.runScheduledActions(NOW.plusSeconds(1));
        assertEquals(State.CLAIMED, action("terminal").getState());
        service.runScheduledActions(NOW.plusSeconds(2));
        assertBounds(8, 9, 8);
        verify(adjuster).setCapacity(RESOURCE, 7, REGION);
        verify(adjuster).setCapacity(RESOURCE, 8, REGION);
    }

    @Test
    void selectionScopedPaginationAndValidation() {
        put("a", NOW, 0, 1);
        put("b", NOW, 0, 2);
        PaginatedResult<ScheduledAction> first = service.describeScheduledActions(NAMESPACE, RESOURCE, DIMENSION,
                List.of(), 1, null, REGION);
        assertEquals("a", first.items().getFirst().getScheduledActionName());
        assertEquals("b", service.describeScheduledActions(NAMESPACE, RESOURCE, DIMENSION,
                List.of(), 1, first.nextToken(), REGION).items().getFirst().getScheduledActionName());
        assertThrows(AwsException.class, () -> service.describeScheduledActions(NAMESPACE, "other", DIMENSION,
                List.of(), 1, first.nextToken(), REGION));
        assertThrows(AwsException.class, () -> service.describeScheduledActions(NAMESPACE, null, DIMENSION,
                List.of(), 1, null, REGION));
        assertThrows(AwsException.class, () -> service.describeScheduledActions(NAMESPACE, RESOURCE, DIMENSION,
                List.of(), 51, null, REGION));
        assertThrows(AwsException.class, () -> service.describeScheduledActions(NAMESPACE, RESOURCE, DIMENSION,
                Collections.nCopies(51, "a"), null, null, REGION));
        assertTrue(service.describeScheduledActions(NAMESPACE, "missing", null, List.of(), null, null, REGION).items().isEmpty());
        assertThrows(AwsException.class, () -> service.putScheduledAction(NAMESPACE, RESOURCE, DIMENSION, "a",
                "at(2030-02-30T00:00:00)", false, null, null, null, REGION, NOW));
        assertThrows(AwsException.class, () -> put("empty", NOW, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void restartPreservesFutureAndCompletedActionsAndSkipsOverdueAndClaimed(String mode, @TempDir Path directory) {
        RegionResolver resolver = new RegionResolver(REGION, ACCOUNT);
        CloudWatchMetricsService cloudWatch = mock(CloudWatchMetricsService.class);
        StorageFactory first = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            service = new ApplicationAutoScalingService(first, resolver, cloudWatch, adjuster);
            register(null);
            put("completed", NOW, 2, 8);
            service.runScheduledActions(NOW);
            put("overdue", NOW.plusSeconds(1), 0, 0);
            service.putScheduledAction(NAMESPACE, RESOURCE, DIMENSION, "future", schedule(NOW.plusSeconds(10)),
                    true, 0, 4, "UTC", REGION, NOW);
            put("future", null, 0, 4);
            assertEquals("UTC", action("future").getTimezone());
            put("claimed", NOW.plusSeconds(5), 0, 0);
            first.create("applicationautoscaling", "application-autoscaling-scheduled-actions.json",
                    new TypeReference<Map<String, ScheduledAction>>() {})
                    .putAllAtomically(Map.of(REGION + "::ecs::" + DIMENSION + "::" + RESOURCE + "::claimed",
                            claimed(action("claimed"))));
            first.flushAll();
        } finally {
            first.shutdownAll();
        }
        StorageFactory restored = StorageFactories.open(directory, mode, ACCOUNT);
        try {
            service = new ApplicationAutoScalingService(restored, resolver, cloudWatch, adjuster);
            restored.loadAll();
            service.recoverScheduledActions(NOW.plusSeconds(2));
            assertEquals(State.SKIPPED, action("overdue").getState());
            assertEquals(State.SKIPPED, action("claimed").getState());
            assertEquals(State.SUCCEEDED, action("completed").getState());
            assertEquals("UTC", action("future").getTimezone());
            assertNull(action("completed").getTimezone());
            service.runScheduledActions(NOW.plusSeconds(10));
            assertEquals("UTC", action("future").getTimezone());
            assertBounds(0, 4, 4);
            assertEquals(2, service.describeScalingActivities(NAMESPACE, RESOURCE, DIMENSION, REGION).size());
        } finally {
            restored.shutdownAll();
        }
    }

    private static ScheduledAction claimed(ScheduledAction action) {
        action.setState(State.CLAIMED);
        return action;
    }
}
