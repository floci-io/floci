package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.CapacityReservation;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.resourcegroups.ResourceGroupsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Ec2CapacityReservationAssociationServiceTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String FILE = "ec2-capacity-reservations.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"default", "open", "targeted"})
    void futureMatchingRequiresExplicitTargetedBeforePublication(String matching) {
        try (Fixture fixture = new Fixture("memory", directory)) {
            String criteria = "default".equals(matching) ? null : matching;
            if ("targeted".equals(matching)) {
                assertEquals("scheduled", fixture.future("m5.xlarge", 8, criteria).getState());
            } else {
                AwsException error = assertThrows(AwsException.class,
                        () -> fixture.future("m5.xlarge", 8, criteria));
                assertEquals("InvalidParameterValue", error.getErrorCode());
                assertTrue(error.getMessage().contains("targeted"));
                verify(fixture.store, never()).put(any(), any());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"m5.xlarge", "t3.micro"})
    void futureMinimumUsesCatalogVcpusAndRejectedTokenCanBeRetried(String type) {
        try (Fixture fixture = new Fixture("memory", directory)) {
            int minimum = "m5.xlarge".equals(type) ? 8 : 16;
            AwsException error = assertThrows(AwsException.class,
                    () -> fixture.future(type, minimum - 1, "targeted"));
            assertEquals("InvalidParameterValue", error.getErrorCode());
            assertTrue(error.getMessage().contains("32 vCPUs"));
            verify(fixture.store, never()).put(any(), any());
            assertEquals("scheduled", fixture.future(type, minimum, "targeted").getState());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"p5.48xlarge", "a1.4xlarge", "d3.8xlarge", "hpc7a.96xlarge",
            "inf2.48xlarge", "trn1.32xlarge", "mac2.metal", "z1d.12xlarge"})
    void futureUnsupportedFamiliesAreRejectedBeforePublication(String type) {
        try (Fixture fixture = new Fixture("memory", directory)) {
            AwsException error = assertThrows(AwsException.class,
                    () -> fixture.future(type, 32, "targeted"));
            assertEquals("InvalidParameterValue", error.getErrorCode());
            assertTrue(error.getMessage().contains("instance families"));
            verify(fixture.store, never()).put(any(), any());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"c5.4xlarge", "g4dn.4xlarge", "im4gn.4xlarge", "m5.4xlarge",
            "r5.4xlarge", "t3.micro", "u-6tb1.56xlarge", "x2idn.16xlarge"})
    void futureSupportedFamiliesUseCatalogIncludingAcceleratedAndHighMemory(String type) {
        try (Fixture fixture = new Fixture("memory", directory)) {
            assertEquals("scheduled", fixture.future(type, 32, "targeted").getState());
        }
    }

    @Test
    void immediateCapacityKeepsOpenAndSmallRequestBehavior() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            assertEquals("active", fixture.create(1).getState());
        }
    }

    @Test
    void scheduledReservationActivatesOnlyAtItsStartAndReplaysAfterActivation() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            Instant now = Instant.parse("2026-10-01T00:00:00Z");
            Instant start = now.plus(Duration.ofDays(7));
            doReturn(now).when(fixture.service).capacityReservationNow();
            CapacityReservation scheduled = fixture.schedule(start, 86400L, "incremental", "scheduled-token");
            assertEquals("scheduled", scheduled.getState());
            assertEquals(0, scheduled.getAvailableInstanceCount());
            assertEquals(start, scheduled.getStartDate());
            assertEquals(start.plusSeconds(86400), scheduled.getCommitmentEndDate());
            assertEquals(16, scheduled.getCommittedInstanceCount());
            Instance unreserved = fixture.launch();
            fixture.service.describeInstances(REGION, List.of(unreserved.getInstanceId()), Map.of());
            assertEquals(null, fixture.service.instanceCapacityReservationId(unreserved));
            assertThrows(AwsException.class, () -> fixture.launch(scheduled.getCapacityReservationId()));
            assertTrue(fixture.describe(scheduled).getInstanceIds().isEmpty());
            doReturn(start.minusSeconds(1)).when(fixture.service).capacityReservationNow();
            assertEquals("scheduled", fixture.describe(scheduled).getState());
            doReturn(start).when(fixture.service).capacityReservationNow();
            CapacityReservation active = fixture.describe(scheduled);
            assertEquals("active", active.getState());
            assertEquals(16, active.getAvailableInstanceCount());
            assertTrue(active.getInstanceIds().isEmpty());
            Instance reserved = fixture.launch(scheduled.getCapacityReservationId());
            assertEquals(scheduled.getCapacityReservationId(), fixture.service.instanceCapacityReservationId(reserved));
            CapacityReservation replay = fixture.schedule(start, 86400L, "incremental", "scheduled-token");
            assertEquals(scheduled.getCapacityReservationId(), replay.getCapacityReservationId());
            assertEquals("active", replay.getState());
            assertEquals(15, replay.getAvailableInstanceCount());
            assertEquals(1, fixture.service.describeCapacityReservations(REGION, List.of(), Map.of()).size());
        }
    }

    @Test
    void activatingTargetedCapacityDoesNotAdoptRunningInstances() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            Instant now = Instant.parse("2026-10-01T00:00:00Z");
            Instant start = now.plus(Duration.ofDays(7));
            doReturn(now).when(fixture.service).capacityReservationNow();
            CapacityReservation occupied = fixture.create(1);
            Instance assigned = fixture.launch();
            Instance optedOut = fixture.launchWithPreference("none");
            Instance eligible = fixture.launch();
            fixture.service.describeInstances(REGION,
                    List.of(assigned.getInstanceId(), optedOut.getInstanceId(), eligible.getInstanceId()), Map.of());
            CapacityReservation first = fixture.schedule(start, 86400L, "incremental", "first");
            CapacityReservation second = fixture.schedule(start, 86400L, "incremental", "second");
            doReturn(start).when(fixture.service).capacityReservationNow();
            Map<String, String> associations = fixture.service.capacityReservationAssociations(REGION);
            assertEquals(occupied.getCapacityReservationId(), associations.get(assigned.getInstanceId()));
            assertFalse(associations.containsKey(optedOut.getInstanceId()));
            assertFalse(associations.containsKey(eligible.getInstanceId()));
            assertEquals(32, fixture.describe(first).getAvailableInstanceCount()
                    + fixture.describe(second).getAvailableInstanceCount());
            assertEquals(associations, fixture.service.capacityReservationAssociations(REGION));
        }
    }

    @Test
    void failedTargetedActivationRollsBackStateBeforeRetry() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            Instant now = Instant.parse("2026-10-01T00:00:00Z");
            Instant start = now.plus(Duration.ofDays(7));
            doReturn(now).when(fixture.service).capacityReservationNow();
            CapacityReservation reservation = fixture.schedule(start, 86400L, "incremental", "failure");
            Instance instance = fixture.launch();
            fixture.service.describeInstances(REGION, List.of(instance.getInstanceId()), Map.of());
            doReturn(start).when(fixture.service).capacityReservationNow();
            doThrow(new IllegalStateException("activation storage failure"))
                    .when(fixture.store).putAllAtomically(any());
            assertThrows(AwsException.class, () -> fixture.describe(reservation));
            CapacityReservation stored = fixture.store.get(REGION + "::" + reservation.getCapacityReservationId())
                    .orElseThrow();
            assertEquals("scheduled", stored.getState());
            assertTrue(stored.getInstanceIds().isEmpty());
            reset(fixture.store);
            assertTrue(fixture.describe(reservation).getInstanceIds().isEmpty());
            assertEquals(16, fixture.describe(reservation).getAvailableInstanceCount());
        }
    }

    @Test
    void failedReservationPublicationDoesNotLeaveCreationTags() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            doThrow(new IllegalStateException("creation storage failure")).when(fixture.store).put(any(), any());
            assertThrows(IllegalStateException.class, () -> fixture.scheduleWithTags(
                    Instant.now().plus(Duration.ofDays(7)), List.of(new Tag("Name", "not-published"))));
            assertTrue(fixture.service.describeCapacityReservations(REGION, List.of(), Map.of()).isEmpty());
            assertTrue(fixture.service.describeTags(REGION, Map.of("value", List.of("not-published"))).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void creationTagSnapshotSurvivesEditsAndReplay(String mode) {
        Instant start = Instant.now().plus(Duration.ofDays(7));
        String id;
        try (Fixture fixture = new Fixture(mode, directory)) {
            CapacityReservation reservation = fixture.scheduleWithTags(start,
                    List.of(new Tag("Name", "original"), new Tag("team", "one")));
            id = reservation.getCapacityReservationId();
            assertEquals(Map.of("Name", "original", "team", "one"), reservation.getRequestTags());
            assertEquals(2, fixture.service.describeTags(REGION, Map.of("resource-id", List.of(id))).size());
            fixture.service.createTags(REGION, List.of(id), List.of(new Tag("Name", "edited")));
            CapacityReservation replay = fixture.scheduleWithTags(start,
                    List.of(new Tag("team", "one"), new Tag("Name", "original")));
            assertEquals(id, replay.getCapacityReservationId());
            assertTrue(replay.getTags().stream()
                    .anyMatch(tag -> "Name".equals(tag.getKey()) && "edited".equals(tag.getValue())));
            assertEquals("IdempotentParameterMismatch", assertThrows(AwsException.class,
                    () -> fixture.scheduleWithTags(start, List.of(new Tag("Name", "different")))).getErrorCode());
        }
        if (!"memory".equals(mode)) {
            try (Fixture fixture = new Fixture(mode, directory)) {
                CapacityReservation replay = fixture.scheduleWithTags(start,
                        List.of(new Tag("Name", "original"), new Tag("team", "one")));
                assertEquals(id, replay.getCapacityReservationId());
                assertTrue(replay.getTags().stream()
                        .anyMatch(tag -> "Name".equals(tag.getKey()) && "edited".equals(tag.getValue())));
                assertEquals("IdempotentParameterMismatch", assertThrows(AwsException.class,
                        () -> fixture.scheduleWithTags(start, List.of())).getErrorCode());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void scheduledMetadataAndReplaySurviveStorageReload(String mode) {
        Instant start = Instant.now().plus(Duration.ofDays(7));
        String id;
        try (Fixture fixture = new Fixture(mode, directory)) {
            id = fixture.schedule(start, 86400L, "incremental", "reload").getCapacityReservationId();
        }
        try (Fixture fixture = new Fixture(mode, directory)) {
            CapacityReservation replay = fixture.schedule(start, 86400L, "incremental", "reload");
            assertEquals(id, replay.getCapacityReservationId());
            assertEquals("scheduled", replay.getState());
            assertEquals(start, replay.getStartDate());
            assertEquals(86400L, replay.getCommitmentDuration());
            assertEquals("incremental", replay.getDeliveryPreference());
            assertEquals(16, replay.getCommittedInstanceCount());
            assertEquals(start.plusSeconds(86400), replay.getCommitmentEndDate());
            assertEquals(0, replay.getAvailableInstanceCount());
        }
    }

    @Test
    void scheduledValidationAndMismatchedReplayPreserveStoredCapacity() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            Instant now = Instant.parse("2026-10-01T00:00:00Z");
            doReturn(now).when(fixture.service).capacityReservationNow();
            Instant start = now.plus(Duration.ofDays(7));
            for (Instant invalid : List.of(now.plus(Duration.ofDays(4)), now.plus(Duration.ofDays(121)))) {
                assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                        () -> fixture.schedule(invalid, 86400L, "incremental", null)).getErrorCode());
            }
            assertEquals("MissingParameter", assertThrows(AwsException.class,
                    () -> fixture.schedule(start, null, "incremental", null)).getErrorCode());
            assertEquals("MissingParameter", assertThrows(AwsException.class,
                    () -> fixture.schedule(start, 86400L, null, null)).getErrorCode());
            for (long invalid : new long[]{0, 200000001L}) {
                assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                        () -> fixture.schedule(start, invalid, "incremental", null)).getErrorCode());
            }
            assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                    () -> fixture.schedule(start, 86400L, "fixed", null)).getErrorCode());
            assertEquals("InvalidParameterCombination", assertThrows(AwsException.class,
                    () -> fixture.schedule(null, 86400L, "incremental", null)).getErrorCode());
            assertTrue(fixture.service.describeCapacityReservations(REGION, List.of(), Map.of()).isEmpty());
            CapacityReservation scheduled = fixture.schedule(start, 86400L, "incremental", "same-token");
            assertEquals("IdempotentParameterMismatch", assertThrows(AwsException.class,
                    () -> fixture.schedule(start.plusSeconds(1), 86400L, "incremental", "same-token")).getErrorCode());
            assertEquals("IncorrectState", assertThrows(AwsException.class,
                    () -> fixture.service.modifyCapacityReservation(REGION, scheduled.getCapacityReservationId(),
                            3, null, null, null)).getErrorCode());
            assertEquals(16, fixture.describe(scheduled).getTotalInstanceCount());
            assertEquals(scheduled.getCapacityReservationId(), fixture.schedule(start, 86400L,
                    "incremental", "same-token").getCapacityReservationId());
        }
    }

    @Test
    void failedActivationRetainsScheduledCapacityForRetry() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            Instant now = Instant.parse("2026-10-01T00:00:00Z");
            Instant start = now.plus(Duration.ofDays(7));
            doReturn(now).when(fixture.service).capacityReservationNow();
            CapacityReservation reservation = fixture.schedule(start, 86400L, "incremental", "retry");
            doReturn(start).when(fixture.service).capacityReservationNow();
            doThrow(new IllegalStateException("storage failed")).when(fixture.store).putAllAtomically(any());
            assertEquals("InternalError", assertThrows(AwsException.class,
                    () -> fixture.describe(reservation)).getErrorCode());
            assertEquals("scheduled", fixture.store.get(REGION + "::" + reservation.getCapacityReservationId())
                    .orElseThrow().getState());
            reset(fixture.store);
            assertEquals("active", fixture.describe(reservation).getState());
            assertEquals(16, fixture.describe(reservation).getAvailableInstanceCount());
        }
    }

    @Test
    void cancellationCannotBeReactivatedAndActiveCommitmentRestrictsModification() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            Instant now = Instant.parse("2026-10-01T00:00:00Z");
            Instant start = now.plus(Duration.ofDays(7));
            doReturn(now).when(fixture.service).capacityReservationNow();
            CapacityReservation cancelled = fixture.schedule(start, 86400L, "incremental", "cancelled");
            fixture.service.cancelCapacityReservation(REGION, cancelled.getCapacityReservationId());
            CapacityReservation reservation = fixture.schedule(start, 86400L, "incremental", "active");
            doReturn(start).when(fixture.service).capacityReservationNow();
            assertEquals("cancelled", fixture.describe(cancelled).getState());
            assertEquals(0, fixture.describe(cancelled).getAvailableInstanceCount());
            assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                    () -> fixture.service.modifyCapacityReservation(REGION, reservation.getCapacityReservationId(),
                            1, null, null, null)).getErrorCode());
            assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                    () -> fixture.service.modifyCapacityReservation(REGION, reservation.getCapacityReservationId(),
                            null, start.plusSeconds(1), "limited", null)).getErrorCode());
            assertEquals(16, fixture.describe(reservation).getTotalInstanceCount());
            doReturn(start.plusSeconds(86400)).when(fixture.service).capacityReservationNow();
            assertEquals(1, fixture.service.modifyCapacityReservation(REGION, reservation.getCapacityReservationId(),
                    1, null, null, null).getTotalInstanceCount());
        }
    }

    @Test
    void schedulingBoundariesAndImmediateReservationsRemainDistinct() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            Instant now = Instant.parse("2026-10-01T00:00:00Z");
            doReturn(now).when(fixture.service).capacityReservationNow();
            assertEquals("scheduled", fixture.schedule(now.plus(Duration.ofDays(5)), 1L,
                    "incremental", null).getState());
            assertEquals("scheduled", fixture.schedule(now.plus(Duration.ofDays(120)), 200000000L,
                    "incremental", null).getState());
            CapacityReservation immediate = fixture.create(2);
            assertEquals("active", immediate.getState());
            assertEquals(2, immediate.getAvailableInstanceCount());
            assertEquals(null, immediate.getCommitmentDuration());
        }
    }

    @Test
    void unknownZoneIdIsRejectedBeforeReservationPublication() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            AwsException failure = assertThrows(AwsException.class, () ->
                    fixture.service.createCapacityReservation(REGION, "t3.micro", "Linux/UNIX", null,
                            "use1-az99", 1, null, null, null, null, null, null, null, null));
            assertEquals("InvalidParameterValue", failure.getErrorCode());
            assertTrue(fixture.service.describeCapacityReservations(REGION, List.of(), Map.of()).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void legacyUnknownZoneDoesNotBlockLaunchOrRestart(String mode) {
        String invalidId;
        try (Fixture fixture = new Fixture(mode, directory)) {
            CapacityReservation invalid = fixture.create(1);
            invalid.setAvailabilityZoneId("use1-az99");
            invalidId = invalid.getCapacityReservationId();
            fixture.store.put(REGION + "::" + invalidId, invalid);
        }
        try (Fixture recovered = new Fixture(mode, directory)) {
            Instance instance = recovered.launch();
            assertEquals(null, recovered.service.instanceCapacityReservationId(instance));
            recovered.service.stopInstances(REGION, List.of(instance.getInstanceId()));
            recovered.service.startInstances(REGION, List.of(instance.getInstanceId()));
            assertEquals(null, recovered.service.instanceCapacityReservationId(instance));
            CapacityReservation invalid = recovered.service.describeCapacityReservations(REGION,
                    List.of(invalidId), Map.of()).getFirst();
            assertEquals(1, invalid.getAvailableInstanceCount());
            assertTrue(invalid.getInstanceIds().isEmpty());
        }
    }

    @Test
    void newOpenReservationAdoptsOnlyEligibleUnassociatedRunningInstances() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            Instance open = fixture.launchWithPreference("open");
            Instance excluded = fixture.launchWithPreference("none");
            fixture.service.describeInstances(REGION,
                    List.of(open.getInstanceId(), excluded.getInstanceId()), Map.of());
            CapacityReservation first = fixture.create(2);
            CapacityReservation second = fixture.create(2);

            assertEquals(List.of(open.getInstanceId()), fixture.describe(first).getInstanceIds());
            assertEquals(1, fixture.describe(first).getAvailableInstanceCount());
            assertEquals(2, fixture.describe(second).getAvailableInstanceCount());
            assertEquals(first.getCapacityReservationId(), fixture.service.instanceCapacityReservationId(open));
            assertEquals(null, fixture.service.instanceCapacityReservationId(excluded));
            fixture.service.terminateInstances(REGION, List.of(open.getInstanceId(), excluded.getInstanceId()));
            assertEquals(2, fixture.describe(first).getAvailableInstanceCount());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void adoptedRunningMembershipSurvivesRestart(String mode) {
        CapacityReservation capacity;
        String instanceId;
        try (Fixture fixture = new Fixture(mode, directory)) {
            instanceId = fixture.launch().getInstanceId();
            fixture.service.describeInstances(REGION, List.of(instanceId), Map.of());
            capacity = fixture.create(2);
            assertEquals(List.of(instanceId), fixture.describe(capacity).getInstanceIds());
        }
        try (Fixture restarted = new Fixture(mode, directory)) {
            assertEquals(List.of(instanceId), restarted.describe(capacity).getInstanceIds());
            assertEquals(1, restarted.describe(capacity).getAvailableInstanceCount());
            restarted.service.stopInstances(REGION, List.of(instanceId));
            assertTrue(restarted.describe(capacity).getInstanceIds().isEmpty());
            assertEquals(2, restarted.describe(capacity).getAvailableInstanceCount());
        }
    }

    @Test
    void failedReleaseRetainsMembershipUntilRetryReconcilesPersistedStop() {
        try (Fixture fixture = new Fixture("persistent", directory)) {
            CapacityReservation source = fixture.create(1);
            Instance instance = fixture.launch();
            doThrow(new IllegalStateException("injected release failure")).when(fixture.store).putAllAtomically(any());
            assertThrows(AwsException.class,
                    () -> fixture.service.stopInstances(REGION, List.of(instance.getInstanceId())));
            assertEquals(0, fixture.store.get(REGION + "::" + source.getCapacityReservationId())
                    .orElseThrow().getAvailableInstanceCount());
            reset(fixture.store);
            assertEquals(1, fixture.describe(source).getAvailableInstanceCount());
            assertTrue(fixture.describe(source).getInstanceIds().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void admissionSerializesWithCancellation(boolean restart) throws Exception {
        try (Fixture fixture = new Fixture("memory", directory);
                ExecutorService workers = Executors.newFixedThreadPool(2)) {
            CapacityReservation source = fixture.create(1);
            CapacityReservation destination = fixture.create(1);
            Instance stopped = restart ? fixture.launch() : null;
            if (stopped != null) {
                fixture.service.stopInstances(REGION, List.of(stopped.getInstanceId()));
            }
            CountDownLatch beforePublish = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            AtomicReference<String> acquiredId = new AtomicReference<>();
            doAnswer(invocation -> {
                Map<String, CapacityReservation> batch = invocation.getArgument(0);
                acquiredId.compareAndSet(null, batch.values().iterator().next().getCapacityReservationId());
                beforePublish.countDown();
                assertTrue(resume.await(10, TimeUnit.SECONDS));
                return invocation.callRealMethod();
            }).when(fixture.store).putAllAtomically(any());
            Future<Instance> admission = workers.submit(() -> {
                if (stopped == null) {
                    return fixture.launch();
                }
                fixture.service.startInstances(REGION, List.of(stopped.getInstanceId()));
                return stopped;
            });
            try {
                assertTrue(beforePublish.await(10, TimeUnit.SECONDS));
                CapacityReservation admittedSource = source.getCapacityReservationId().equals(acquiredId.get())
                        ? source : destination;
                CapacityReservation other = admittedSource == source ? destination : source;
                AtomicReference<Thread> mutationThread = new AtomicReference<>();
                Future<?> mutation = workers.submit(() -> {
                    mutationThread.set(Thread.currentThread());
                    fixture.service.cancelCapacityReservation(REGION, admittedSource.getCapacityReservationId());
                });
                await().atMost(Duration.ofSeconds(10)).until(() -> mutation.isDone()
                        || mutationThread.get() != null && mutationThread.get().getState() == Thread.State.BLOCKED);
                assertTrue(!mutation.isDone(), "Mutation must wait for atomic admission publication");
                resume.countDown();
                Instance instance = admission.get(10, TimeUnit.SECONDS);
                mutation.get(10, TimeUnit.SECONDS);
                CapacityReservation currentSource = fixture.describe(admittedSource);
                CapacityReservation currentDestination = fixture.describe(other);
                assertTrue(currentSource.getInstanceIds().isEmpty());
                assertEquals(null, fixture.service.instanceCapacityReservationId(instance));
                assertEquals(1, currentDestination.getAvailableInstanceCount());
            } finally {
                resume.countDown();
            }
        }
    }

    @Test
    void rejectedLaunchAfterCancellationReclaimsItsPublishedResources() throws Exception {
        try (Fixture fixture = new Fixture("persistent", directory);
                ExecutorService workers = Executors.newSingleThreadExecutor()) {
            CapacityReservation source = fixture.create(1);
            CapacityReservation destination = fixture.create(1);
            CountDownLatch beforeAdmission = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            doAnswer(invocation -> {
                beforeAdmission.countDown();
                assertTrue(resume.await(10, TimeUnit.SECONDS));
                return invocation.callRealMethod();
            }).when(fixture.service).acquireCapacity(eq(REGION), any());
            Future<Instance> launch = workers.submit(() -> fixture.launch(source.getCapacityReservationId()));
            try {
                assertTrue(beforeAdmission.await(10, TimeUnit.SECONDS));
                fixture.service.cancelCapacityReservation(REGION, source.getCapacityReservationId());
                resume.countDown();
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> launch.get(10, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof AwsException);
                assertTrue(fixture.describe(source).getInstanceIds().isEmpty());
                assertTrue(fixture.describe(destination).getInstanceIds().isEmpty());
                assertTrue(fixture.service.describeInstances(REGION, List.of(), Map.of()).stream()
                        .flatMap(reservation -> reservation.getInstances().stream())
                        .allMatch(instance -> "terminated".equals(instance.getState().getName())));
                assertTrue(fixture.service.describeVolumes(REGION, List.of(), Map.of()).isEmpty());
            } finally {
                resume.countDown();
            }
        }
    }

    @Test
    void failedLaunchMembershipPublicationReleasesOwnedVolumeAndInstanceCapacity() {
        try (Fixture fixture = new Fixture("persistent", directory)) {
            CapacityReservation source = fixture.create(1);
            doThrow(new IllegalStateException("injected admission failure"))
                    .when(fixture.store).putAllAtomically(any());

            AwsException failure = assertThrows(AwsException.class, fixture::launch);

            assertEquals("InternalError", failure.getErrorCode());
            reset(fixture.store);
            assertEquals(1, fixture.describe(source).getAvailableInstanceCount());
            assertTrue(fixture.describe(source).getInstanceIds().isEmpty());
            assertTrue(fixture.service.describeInstances(REGION, List.of(), Map.of()).stream()
                    .flatMap(reservation -> reservation.getInstances().stream())
                    .allMatch(instance -> "terminated".equals(instance.getState().getName())));
            assertTrue(fixture.service.describeVolumes(REGION, List.of(), Map.of()).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void targetedAdmissionLaunchesOnlyTheAvailableCapacity(boolean enforceQuotas) {
        try (Fixture fixture = new Fixture("memory", directory)) {
            when(fixture.config.services().ec2().enforceVcpuQuotas()).thenReturn(enforceQuotas);
            when(fixture.config.services().servicequotas().standardOnDemandVcpus()).thenReturn(20);
            CapacityReservation capacity = fixture.create(2);
            LaunchTemplateData.CapacityReservationSpecification specification =
                    new LaunchTemplateData.CapacityReservationSpecification();
            LaunchTemplateData.CapacityReservationTarget target = new LaunchTemplateData.CapacityReservationTarget();
            target.setCapacityReservationId(capacity.getCapacityReservationId());
            specification.setCapacityReservationTarget(target);
            List<Instance> instances = fixture.service.runInstances(REGION, "ami-test", "t3.micro", 1, 3,
                    null, List.of(), null, null, List.of(), null, null, null, null, 0, null, null, null,
                    null, false, null, null, null, false, null, specification).getInstances();
            assertEquals(2, instances.size());
            assertEquals(0, fixture.describe(capacity).getAvailableInstanceCount());
            assertEquals(2, fixture.describe(capacity).getInstanceIds().size());
            assertTrue(fixture.service.describeInstances(REGION, List.of(), Map.of()).stream()
                    .flatMap(reservation -> reservation.getInstances().stream())
                    .noneMatch(instance -> "terminated".equals(instance.getState().getName())));
        }
    }

    @Test
    void targetedMismatchKeepsItsExistingErrorAndFreeCapacity() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            CapacityReservation capacity = fixture.service.createCapacityReservation(REGION, "t3.small",
                    "Linux/UNIX", REGION + "a", null, 1, null, null, null, null, null, null, null, null);
            AwsException failure = assertThrows(AwsException.class,
                    () -> fixture.launch(capacity.getCapacityReservationId()));
            assertEquals("InsufficientInstanceCapacity", failure.getErrorCode());
            assertEquals(1, fixture.describe(capacity).getAvailableInstanceCount());
            assertTrue(fixture.describe(capacity).getInstanceIds().isEmpty());
            assertTrue(fixture.service.describeVolumes(REGION, List.of(), Map.of()).isEmpty());
        }
    }

    @Test
    void targetedShortageRejectsLaunchWithoutPublishingResources() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            CapacityReservation capacity = fixture.create(1);
            Instance admitted = fixture.launch(capacity.getCapacityReservationId());
            AwsException failure = assertThrows(AwsException.class,
                    () -> fixture.launch(capacity.getCapacityReservationId()));
            assertEquals("ReservationCapacityExceeded", failure.getErrorCode());
            assertEquals("The targeted Capacity Reservation has insufficient available capacity.", failure.getMessage());
            assertEquals(List.of(admitted.getInstanceId()), fixture.describe(capacity).getInstanceIds());
            assertEquals(1, fixture.service.describeVolumes(REGION, List.of(), Map.of()).size());
        }
    }

    @Test
    void targetedShortageDuringAdmissionReclaimsFailedLaunch() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            CapacityReservation capacity = fixture.create(1);
            AtomicReference<Instance> winner = new AtomicReference<>();
            doAnswer(invocation -> {
                reset(fixture.service);
                winner.set(fixture.launch(capacity.getCapacityReservationId()));
                return invocation.callRealMethod();
            }).when(fixture.service).acquireCapacity(eq(REGION), any());
            AwsException failure = assertThrows(AwsException.class,
                    () -> fixture.launch(capacity.getCapacityReservationId()));
            assertEquals("ReservationCapacityExceeded", failure.getErrorCode());
            assertEquals("The targeted Capacity Reservation has insufficient available capacity.", failure.getMessage());
            assertEquals(List.of(winner.get().getInstanceId()), fixture.describe(capacity).getInstanceIds());
            assertEquals(1, fixture.service.describeVolumes(REGION, List.of(), Map.of()).size());
            assertEquals(1, fixture.service.describeInstances(REGION, List.of(), Map.of()).stream()
                    .flatMap(reservation -> reservation.getInstances().stream())
                    .filter(instance -> !"terminated".equals(instance.getState().getName())).count());
        }
    }

    @Test
    void targetedShortageOnRestartPreservesStoppedInstanceAndOccupant() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            CapacityReservation capacity = fixture.create(1);
            Instance stopped = fixture.launch(capacity.getCapacityReservationId());
            fixture.service.stopInstances(REGION, List.of(stopped.getInstanceId()));
            Instance occupant = fixture.launch(capacity.getCapacityReservationId());
            AwsException failure = assertThrows(AwsException.class,
                    () -> fixture.service.startInstances(REGION, List.of(stopped.getInstanceId())));
            assertEquals("ReservationCapacityExceeded", failure.getErrorCode());
            assertEquals("The targeted Capacity Reservation has insufficient available capacity.", failure.getMessage());
            assertEquals(List.of(occupant.getInstanceId()), fixture.describe(capacity).getInstanceIds());
            assertEquals("stopped", fixture.service.describeInstances(REGION,
                    List.of(stopped.getInstanceId()), Map.of()).getFirst().getInstances().getFirst().getState().getName());
        }
    }

    @Test
    void reservationOnlyLaunchDoesNotFallBackToOnDemand() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            AwsException failure = assertThrows(AwsException.class,
                    () -> fixture.launchWithPreference("capacity-reservations-only"));
            assertEquals("InsufficientInstanceCapacity", failure.getErrorCode());
            assertTrue(fixture.service.describeInstances(REGION, List.of(), Map.of()).isEmpty());
            assertTrue(fixture.service.describeVolumes(REGION, List.of(), Map.of()).isEmpty());
        }
    }

    @Test
    void reservationOnlyLaunchConsumesAndReleasesOpenCapacity() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            CapacityReservation capacity = fixture.create(1);
            Instance instance = fixture.launchWithPreference("capacity-reservations-only");
            assertEquals(capacity.getCapacityReservationId(),
                    fixture.service.instanceCapacityReservationId(instance));
            assertEquals(0, fixture.describe(capacity).getAvailableInstanceCount());
            fixture.service.stopInstances(REGION, List.of(instance.getInstanceId()));
            assertEquals(1, fixture.describe(capacity).getAvailableInstanceCount());
            fixture.service.startInstances(REGION, List.of(instance.getInstanceId()));
            assertEquals(0, fixture.describe(capacity).getAvailableInstanceCount());
            fixture.service.terminateInstances(REGION, List.of(instance.getInstanceId()));
            assertEquals(1, fixture.describe(capacity).getAvailableInstanceCount());
        }
    }

    @Test
    void reservationOnlyRestartCannotFallBackAfterCapacityIsCancelled() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            CapacityReservation capacity = fixture.create(1);
            Instance instance = fixture.launchWithPreference("capacity-reservations-only");
            fixture.service.stopInstances(REGION, List.of(instance.getInstanceId()));
            fixture.service.cancelCapacityReservation(REGION, capacity.getCapacityReservationId());
            AwsException failure = assertThrows(AwsException.class,
                    () -> fixture.service.startInstances(REGION, List.of(instance.getInstanceId())));
            assertEquals("InsufficientInstanceCapacity", failure.getErrorCode());
            assertEquals("stopped", fixture.service.describeInstances(REGION,
                    List.of(instance.getInstanceId()), Map.of()).getFirst().getInstances().getFirst()
                    .getState().getName());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reservationOnlyAdmissionCapsAtMatchingOpenCapacity(boolean enforceQuotas) {
        try (Fixture fixture = new Fixture("memory", directory)) {
            when(fixture.config.services().ec2().enforceVcpuQuotas()).thenReturn(enforceQuotas);
            when(fixture.config.services().servicequotas().standardOnDemandVcpus()).thenReturn(20);
            CapacityReservation first = fixture.create(1);
            CapacityReservation second = fixture.create(1);
            fixture.service.createCapacityReservation(REGION, "t3.small", "Linux/UNIX", REGION + "a",
                    null, 5, null, null, null, null, null, null, null, null);
            LaunchTemplateData.CapacityReservationSpecification specification =
                    new LaunchTemplateData.CapacityReservationSpecification();
            specification.setCapacityReservationPreference("capacity-reservations-only");
            List<Instance> instances = fixture.service.runInstances(REGION, "ami-test", "t3.micro", 1, 3,
                    null, List.of(), null, null, List.of(), null, null, null, null, 0, null, null, null,
                    null, false, null, null, null, false, null, specification).getInstances();
            assertEquals(2, instances.size());
            assertEquals(0, fixture.describe(first).getAvailableInstanceCount());
            assertEquals(0, fixture.describe(second).getAvailableInstanceCount());
        }
    }

    @Test
    void reservationOnlyRechecksCapacityAtAdmission() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            CapacityReservation capacity = fixture.create(1);
            doAnswer(invocation -> {
                fixture.service.cancelCapacityReservation(REGION, capacity.getCapacityReservationId());
                return invocation.callRealMethod();
            }).when(fixture.service).acquireCapacity(eq(REGION), any());
            AwsException failure = assertThrows(AwsException.class,
                    () -> fixture.launchWithPreference("capacity-reservations-only"));
            assertEquals("InsufficientInstanceCapacity", failure.getErrorCode());
            assertTrue(fixture.service.describeVolumes(REGION, List.of(), Map.of()).isEmpty());
            assertTrue(fixture.service.describeInstances(REGION, List.of(), Map.of()).stream()
                    .flatMap(reservation -> reservation.getInstances().stream())
                    .allMatch(instance -> "terminated".equals(instance.getState().getName())));
        }
    }

    @Test
    void reservationOnlySpotLaunchIsRejectedBeforeMutation() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            fixture.create(1);
            LaunchTemplateData.CapacityReservationSpecification specification =
                    new LaunchTemplateData.CapacityReservationSpecification();
            specification.setCapacityReservationPreference("capacity-reservations-only");
            AwsException failure = assertThrows(AwsException.class, () -> fixture.service.runInstances(
                    REGION, "ami-test", "t3.micro", 1, 1, null, List.of(), null, null, List.of(), null,
                    null, null, null, 0, null, null, null, null, false, null, null, null, true, null, specification));
            assertEquals("InvalidParameterCombination", failure.getErrorCode());
            assertTrue(fixture.service.describeInstances(REGION, List.of(), Map.of()).isEmpty());
        }
    }

    @Test
    void reservationOnlyInsufficientMinimumDoesNotConsumeCapacity() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            CapacityReservation capacity = fixture.create(1);
            LaunchTemplateData.CapacityReservationSpecification specification =
                    new LaunchTemplateData.CapacityReservationSpecification();
            specification.setCapacityReservationPreference("capacity-reservations-only");
            AwsException failure = assertThrows(AwsException.class, () -> fixture.service.runInstances(
                    REGION, "ami-test", "t3.micro", 2, 3, null, List.of(), null, null, List.of(), null,
                    null, null, null, 0, null, null, null, null, false, null, null, null, false, null, specification));
            assertEquals("InsufficientInstanceCapacity", failure.getErrorCode());
            assertEquals(1, fixture.describe(capacity).getAvailableInstanceCount());
            assertTrue(fixture.service.describeInstances(REGION, List.of(), Map.of()).isEmpty());
        }
    }

    @Test
    void repeatedAssociationReadsDoNotRescanInstancesWithoutGroupTargets() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            fixture.create(4);
            fixture.launch();
            fixture.service.capacityReservationAssociations(REGION);
            clearInvocations(fixture.instanceStore, fixture.groups);
            fixture.service.capacityReservationAssociations(REGION);
            fixture.service.capacityReservationAssociations(REGION);
            verify(fixture.instanceStore, never()).scan(any());
            verify(fixture.groups, never()).capacityReservationMembers(any(), any(), any());
        }
    }

    @Test
    void groupLaunchRegistersMembershipUnderTheReconciliationLock() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            fixture.create(2);
            String group = fixture.group();
            fixture.service.capacityReservationAssociations(REGION);
            Map<String, Set<String>> index = fixture.service.capacityGroupInstanceIdsForTest();
            Set<String> ids = spy(new HashSet<String>());
            doAnswer(invocation -> {
                assertTrue(fixture.service.holdsCapacityReservationLockForTest(),
                        "Registration must share the pruning lock");
                return invocation.callRealMethod();
            }).when(ids).add(any());
            index.put(ACCOUNT + "::" + REGION, ids);
            Instance launched = fixture.launchGroup(group, null, 1, 1).getFirst();
            assertEquals(Set.of(launched.getInstanceId()), ids);
            fixture.service.describeInstances(REGION, List.of(launched.getInstanceId()), Map.of());
            CapacityReservation added = fixture.create(1);
            fixture.groups.add(REGION, ACCOUNT, MAPPER.valueToTree(Map.of(
                    "Group", group, "ResourceArns", List.of(added.getCapacityReservationArn()))));
            assertEquals(added.getCapacityReservationId(), fixture.service.instanceCapacityReservationId(launched));
        }
    }

    @Test
    void groupIndexPrunesTerminatedFallbacksWithoutAvailableCapacity() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            CapacityReservation capacity = fixture.create(1);
            String group = fixture.group(capacity);
            fixture.service.capacityReservationAssociations(REGION);
            fixture.service.cancelCapacityReservation(REGION, capacity.getCapacityReservationId());
            Instance launched = fixture.launchGroup(group, null, 1, 1).getFirst();
            Map<String, Set<String>> index = fixture.service.capacityGroupInstanceIdsForTest();
            Set<String> ids = index.get(ACCOUNT + "::" + REGION);
            assertTrue(ids.contains(launched.getInstanceId()));
            fixture.service.terminateInstances(REGION, List.of(launched.getInstanceId()));
            clearInvocations(fixture.instanceStore, fixture.groups);
            fixture.service.capacityReservationAssociations(REGION);
            assertTrue(ids.isEmpty(), "Terminated fallback IDs must be pruned even without capacity");
            verify(fixture.instanceStore, never()).scan(any());
            verify(fixture.groups, never()).capacityReservationMembers(any(), any(), any());
        }
    }

    @Test
    void groupMembershipIsReadOncePerReconcileAndRefreshedAfterChanges() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            fixture.create(4);
            fixture.service.capacityReservationAssociations(REGION);
            String group = fixture.group();
            List<Instance> launched = fixture.launchGroup(group, null, 2, 2);
            fixture.service.describeInstances(REGION, launched.stream().map(Instance::getInstanceId).toList(), Map.of());
            clearInvocations(fixture.instanceStore, fixture.groups);
            assertTrue(fixture.service.capacityReservationAssociations(REGION).isEmpty());
            verify(fixture.groups, times(1)).capacityReservationMembers(REGION, ACCOUNT, group);
            verify(fixture.instanceStore, never()).scan(any());
            CapacityReservation added = fixture.create(2);
            fixture.groups.add(REGION, ACCOUNT, MAPPER.valueToTree(
                    Map.of("Group", group, "ResourceArns", List.of(added.getCapacityReservationArn()))));
            assertEquals(2, fixture.service.capacityReservationAssociations(REGION).size());
            assertEquals(0, fixture.describe(added).getAvailableInstanceCount());
        }
    }

    @Test
    void groupTargetUsesOnlyMatchingMembersAndReturnsReleasedCapacity() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            CapacityReservation outside = fixture.create(3);
            CapacityReservation first = fixture.create(1);
            CapacityReservation second = fixture.create(1);
            fixture.service.modifyCapacityReservation(REGION, first.getCapacityReservationId(),
                    null, null, null, "targeted");
            String group = fixture.group(first, second);
            List<Instance> launched = fixture.launchGroup(group, "capacity-reservations-only", 2, 3);
            assertEquals(2, launched.size());
            assertEquals(3, fixture.describe(outside).getAvailableInstanceCount());
            assertEquals(0, fixture.describe(first).getAvailableInstanceCount());
            assertEquals(0, fixture.describe(second).getAvailableInstanceCount());
            assertTrue(launched.stream().allMatch(instance -> group.equals(instance.getCapacityReservationGroupArn())));
            fixture.service.terminateInstances(REGION, launched.stream().map(Instance::getInstanceId).toList());
            assertEquals(1, fixture.describe(first).getAvailableInstanceCount());
            assertEquals(1, fixture.describe(second).getAvailableInstanceCount());
        }
    }

    @Test
    void groupFallbackNeverConsumesOutsideCapacityAndAdoptsNewMembers() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            CapacityReservation outside = fixture.create(4);
            String group = fixture.group();
            List<Instance> launched = fixture.launchGroup(group, null, 2, 2);
            fixture.service.describeInstances(REGION, launched.stream().map(Instance::getInstanceId).toList(), Map.of());
            assertTrue(fixture.service.capacityReservationAssociations(REGION).isEmpty());
            CapacityReservation added = fixture.create(2);
            assertEquals(2, fixture.describe(added).getAvailableInstanceCount());
            fixture.groups.add(REGION, ACCOUNT, MAPPER.valueToTree(
                    Map.of("Group", group, "ResourceArns", List.of(added.getCapacityReservationArn()))));
            assertEquals(0, fixture.describe(added).getAvailableInstanceCount());
            assertEquals(4, fixture.describe(outside).getAvailableInstanceCount());
        }
    }

    @Test
    void cancelledGroupCapacityMovesToAnotherMemberWithoutChangingTheTarget() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            CapacityReservation first = fixture.create(1);
            CapacityReservation second = fixture.create(1);
            String group = fixture.group(first, second);
            Instance instance = fixture.launchGroup(group, null, 1, 1).getFirst();
            fixture.service.describeInstances(REGION, List.of(instance.getInstanceId()), Map.of());
            String acquired = fixture.service.instanceCapacityReservationId(instance);
            fixture.service.cancelCapacityReservation(REGION, acquired);
            String other = acquired.equals(first.getCapacityReservationId())
                    ? second.getCapacityReservationId() : first.getCapacityReservationId();
            assertEquals(other, fixture.service.instanceCapacityReservationId(instance));
            assertEquals(group, instance.getCapacityReservationGroupArn());
        }
    }

    @Test
    void deletingGroupLeavesAcquiredCapacityUntilTheInstanceStops() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            CapacityReservation capacity = fixture.create(1);
            String group = fixture.group(capacity);
            Instance instance = fixture.launchGroup(group, null, 1, 1).getFirst();
            fixture.groups.delete(REGION, ACCOUNT, MAPPER.valueToTree(Map.of("Group", group)));
            assertEquals(capacity.getCapacityReservationId(), fixture.service.instanceCapacityReservationId(instance));
            fixture.service.stopInstances(REGION, List.of(instance.getInstanceId()));
            assertEquals(1, fixture.describe(capacity).getAvailableInstanceCount());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void recoveredFallbackInstancesAdoptNewGroupCapacityWithoutRepeatedScans(String mode) {
        String group;
        String instanceId;
        try (Fixture fixture = new Fixture(mode, directory)) {
            group = fixture.group();
            instanceId = fixture.launchGroup(group, null, 1, 1).getFirst().getInstanceId();
            fixture.service.describeInstances(REGION, List.of(instanceId), Map.of());
        }
        try (Fixture fixture = new Fixture(mode, directory)) {
            CapacityReservation capacity = fixture.create(1);
            fixture.groups.add(REGION, ACCOUNT, MAPPER.valueToTree(Map.of(
                    "Group", group, "ResourceArns", List.of(capacity.getCapacityReservationArn()))));
            assertEquals(Map.of(instanceId, capacity.getCapacityReservationId()),
                    fixture.service.capacityReservationAssociations(REGION));
            clearInvocations(fixture.instanceStore);
            assertEquals(Map.of(instanceId, capacity.getCapacityReservationId()),
                    fixture.service.capacityReservationAssociations(REGION));
            verify(fixture.instanceStore, never()).scan(any());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void groupTargetAndMembershipSurviveRecovery(String mode) {
        String group;
        String instanceId;
        String reservationId;
        try (Fixture fixture = new Fixture(mode, directory)) {
            CapacityReservation capacity = fixture.create(1);
            reservationId = capacity.getCapacityReservationId();
            group = fixture.group(capacity);
            instanceId = fixture.launchGroup(group, null, 1, 1).getFirst().getInstanceId();
        }
        try (Fixture fixture = new Fixture(mode, directory)) {
            Instance instance = fixture.service.describeInstances(REGION, List.of(instanceId), Map.of())
                    .getFirst().getInstances().getFirst();
            assertEquals(group, instance.getCapacityReservationGroupArn());
            assertEquals(reservationId, fixture.service.instanceCapacityReservationId(instance));
            fixture.service.stopInstances(REGION, List.of(instanceId));
            assertEquals(null, fixture.service.instanceCapacityReservationId(instance));
            fixture.service.startInstances(REGION, List.of(instanceId));
            assertEquals(reservationId, fixture.service.instanceCapacityReservationId(instance));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "foreign-account", "foreign-region", "none", "insufficient"})
    void invalidOrInsufficientGroupRequestDoesNotPublishInstances(String condition) {
        try (Fixture fixture = new Fixture("memory", directory)) {
            fixture.create(3);
            String group = fixture.group();
            group = switch (condition) {
                case "missing" -> group + "-absent";
                case "foreign-account" -> group.replace(ACCOUNT, "222222222222");
                case "foreign-region" -> group.replace(REGION, "us-west-2");
                default -> group;
            };
            String selected = group;
            assertThrows(AwsException.class, () -> fixture.launchGroup(selected,
                    "none".equals(condition) ? "none" : "capacity-reservations-only", 1, 1));
            assertTrue(fixture.service.describeInstances(REGION, List.of(), Map.of()).isEmpty());
            assertTrue(fixture.service.describeVolumes(REGION, List.of(), Map.of()).isEmpty());
        }
    }

    @Test
    void groupLaunchDoesNotWaitForTheResourceGroupsOperationMonitor() throws Exception {
        try (Fixture fixture = new Fixture("memory", directory);
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            String group = fixture.group(fixture.create(1));
            synchronized (fixture.groups) {
                Future<List<Instance>> launched = executor.submit(() -> fixture.launchGroup(group, null, 1, 1));
                assertEquals(1, launched.get(5, TimeUnit.SECONDS).size());
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final EmulatorConfig config;
        private final StorageFactory factory;
        private final AccountAwareStorageBackend<CapacityReservation> store;
        private final Ec2Service service;
        private final AccountAwareStorageBackend<Instance> instanceStore;
        private final ResourceGroupsService groups;

        private Fixture(String mode, Path path) {
            config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.defaultAccountId()).thenReturn(ACCOUNT);
            when(config.services().ec2().mock()).thenReturn(true);
            when(config.services().ec2().mockPublicAddresses())
                    .thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
            when(config.storage().persistentPath()).thenReturn(path.toString());
            when(config.storage().wal().compactionIntervalMs()).thenReturn(3600000L);
            ServiceConfigAccess access = mock(ServiceConfigAccess.class);
            when(access.storageMode("ec2")).thenReturn(mode);
            when(access.storageFlushInterval("ec2")).thenReturn(3600000L);
            when(access.storageMode("resource-groups")).thenReturn(mode);
            when(access.storageFlushInterval("resource-groups")).thenReturn(3600000L);
            factory = new StorageFactory(config, access);
            store = spy(factory.create("ec2", FILE, new TypeReference<Map<String, CapacityReservation>>() {}));
            instanceStore = spy(factory.create("ec2", "ec2-instances.json",
                    new TypeReference<Map<String, Instance>>() {}));
            StorageFactory serviceFactory = spy(factory);
            doReturn(instanceStore).when(serviceFactory).create(eq("ec2"), eq("ec2-instances.json"), any());
            doReturn(store).when(serviceFactory).create(eq("ec2"), eq(FILE), any());
            Ec2ImageCatalog catalog = new Ec2ImageCatalog();
            service = spy(new Ec2Service(config, null, null, new AmiImageResolver(catalog),
                    catalog, new Ec2InstanceTypeCatalog(), serviceFactory));
            groups = spy(new ResourceGroupsService(factory, service));
            // CDI Instance clashes with the EC2 Instance model.
            service.resourceGroupsServices = mock(jakarta.enterprise.inject.Instance.class);
            when(service.resourceGroupsServices.isResolvable()).thenReturn(true);
            when(service.resourceGroupsServices.get()).thenReturn(groups);
        }

        private String group(CapacityReservation... reservations) {
            String arn = groups.create(REGION, ACCOUNT, MAPPER.valueToTree(Map.of(
                    "Name", "capacity-pool", "Configuration", List.of(
                            Map.of("Type", "AWS::EC2::CapacityReservationPool"),
                            Map.of("Type", "AWS::ResourceGroups::Generic", "Parameters",
                                    List.of(Map.of("Name", "allowed-resource-types",
                                            "Values", List.of("AWS::EC2::CapacityReservation")))))))).arn();
            if (reservations.length > 0) {
                groups.add(REGION, ACCOUNT, MAPPER.valueToTree(Map.of("Group", arn, "ResourceArns",
                        Arrays.stream(reservations).map(CapacityReservation::getCapacityReservationArn).toList())));
            }
            return arn;
        }

        private List<Instance> launchGroup(String group, String preference, int minimum, int maximum) {
            LaunchTemplateData.CapacityReservationSpecification specification =
                    new LaunchTemplateData.CapacityReservationSpecification();
            specification.setCapacityReservationPreference(preference);
            LaunchTemplateData.CapacityReservationTarget target = new LaunchTemplateData.CapacityReservationTarget();
            target.setCapacityReservationResourceGroupArn(group);
            specification.setCapacityReservationTarget(target);
            return service.runInstances(REGION, "ami-test", "t3.micro", minimum, maximum,
                    null, List.of(), null, null, List.of(), null, null, null, null, 0, null, null,
                    null, null, false, null, null, null, false, null, specification).getInstances();
        }

        private Instance launch() {
            return service.runInstances(REGION, "ami-test", "t3.micro", 1, 1,
                    null, List.of(), null, null, List.of(), null, null).getInstances().getFirst();
        }

        private Instance launch(String targetId) {
            LaunchTemplateData.CapacityReservationSpecification specification =
                    new LaunchTemplateData.CapacityReservationSpecification();
            LaunchTemplateData.CapacityReservationTarget target = new LaunchTemplateData.CapacityReservationTarget();
            target.setCapacityReservationId(targetId);
            specification.setCapacityReservationTarget(target);
            return service.runInstances(REGION, "ami-test", "t3.micro", 1, 1, null, List.of(), null, null,
                    List.of(), null, null, null, null, 0, null, null, null, null, false, null, null, null,
                    false, null, specification).getInstances().getFirst();
        }

        private Instance launchWithPreference(String preference) {
            LaunchTemplateData.CapacityReservationSpecification specification =
                    new LaunchTemplateData.CapacityReservationSpecification();
            specification.setCapacityReservationPreference(preference);
            return service.runInstances(REGION, "ami-test", "t3.micro", 1, 1, null, List.of(), null, null,
                    List.of(), null, null, null, null, 0, null, null, null, null, false, null, null, null,
                    false, null, specification).getInstances().getFirst();
        }

        private CapacityReservation future(String type, int count, String matching) {
            return service.createCapacityReservation(REGION, type, "Linux/UNIX", REGION + "a",
                    null, count, null, null, null, null, null, matching, null, null,
                    Instant.now().plus(Duration.ofDays(7)), 1209600L, "incremental", "validation-token");
        }

        private CapacityReservation schedule(Instant start, Long duration, String delivery, String token) {
            return service.createCapacityReservation(REGION, "t3.micro", "Linux/UNIX", REGION + "a",
                    null, 16, null, null, null, null, null, "targeted", null, null,
                    start, duration, delivery, token);
        }

        private CapacityReservation scheduleWithTags(Instant start, List<Tag> tags) {
            return service.createCapacityReservation(REGION, "t3.micro", "Linux/UNIX", REGION + "a",
                    null, 16, null, null, null, null, null, "targeted", null, null,
                    start, 86400L, "incremental", "tag-token", tags);
        }

        private CapacityReservation create(int count) {
            return service.createCapacityReservation(REGION, "t3.micro", "Linux/UNIX", REGION + "a",
                    null, count, null, null, null, null, null, null, null, null);
        }

        private CapacityReservation describe(CapacityReservation reservation) {
            return service.describeCapacityReservations(REGION,
                    List.of(reservation.getCapacityReservationId()), Map.of()).getFirst();
        }

        @Override
        public void close() {
            factory.shutdownAll();
        }
    }
}
