package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.model.Container;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A create retry can hit the daemon twice for the same logical create: the first attempt's
 * response is lost to a transient socket error ({@link DockerRetry}), but the daemon already
 * created the named container, so the retry's {@code createCmd.exec()} fails with a 409 name
 * conflict instead of a transient I/O error. Without recovery this surfaces as a create
 * failure while a live, untracked container sits on the daemon.
 */
@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerCreateConflictRecoveryTest {

    @Mock
    DockerClient dockerClient;

    @Mock
    ImageCacheService imageCacheService;

    @Mock
    ContainerDetector containerDetector;

    @Mock
    EmulatorConfig config;

    @Mock
    EmulatorConfig.DockerConfig dockerConfig;

    @Mock
    EmulatorConfig.TlsConfig tlsConfig;

    @BeforeEach
    void setUp() {
        lenient().when(config.docker()).thenReturn(dockerConfig);
        lenient().when(config.tls()).thenReturn(tlsConfig);
        // create() now resolves the image through the cache before building the create command.
        lenient().when(imageCacheService.ensureImageExists("busybox:stable")).thenReturn("busybox:stable");
        lenient().when(dockerConfig.resourceNamespace()).thenReturn(Optional.empty());
    }

    @Test
    void createAdoptsContainerThatWonTheRaceOnRetryConflict() {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        // The container that actually got created on the daemon carries exactly the labels this
        // create() call applied (including the per-call attempt-id), captured here rather than
        // hardcoded, since createWithConflictRecovery only adopts a container proven to be THIS
        // call's own lost-response attempt, not merely one with matching image/spec labels.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> labelsCaptor =
                ArgumentCaptor.forClass(Map.class);
        when(createCmd.withLabels(labelsCaptor.capture())).thenReturn(createCmd);
        when(createCmd.exec()).thenThrow(new ConflictException("named container already exists"));

        Container existing = mock(Container.class);
        when(existing.getId()).thenReturn("winning-container-id");
        when(existing.getNames()).thenReturn(new String[] {"/emulator-fixed-name"});
        when(existing.getLabels()).thenAnswer(inv -> labelsCaptor.getValue());

        ListContainersCmd listCmd = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of(existing));

        ContainerSpec spec = new ContainerSpec(
                "busybox:stable", "emulator-fixed-name", List.of(), null, null, null, Map.of(),
                List.of(), null, List.of(), List.of(), List.of(), Map.of(), null, false, null,
                List.of(), null, null, List.of());

        String containerId = manager().create(spec);

        assertEquals("winning-container-id", containerId);
    }

    @Test
    void createAdoptsOwnContainerWhenReservedNameBecomesVisibleAfterConflict() {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> labelsCaptor = ArgumentCaptor.forClass(Map.class);
        when(createCmd.withLabels(labelsCaptor.capture())).thenReturn(createCmd);
        when(createCmd.exec()).thenThrow(new ConflictException("named container already exists"));
        lenient().when(dockerConfig.apiTimeoutSeconds()).thenReturn(300);

        Container existing = mock(Container.class);
        lenient().when(existing.getId()).thenReturn("slowly-created-container");
        lenient().when(existing.getNames()).thenReturn(new String[] {"/emulator-fixed-name"});
        lenient().when(existing.getLabels()).thenAnswer(inv -> labelsCaptor.getValue());

        ListContainersCmd listCmd = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        // Docker reserves the name before publishing the container to list/inspect. The
        // replay can conflict while the original create is still finishing its filesystem.
        when(listCmd.exec()).thenReturn(List.of(), List.of(existing));

        ContainerSpec spec = new ContainerSpec(
                "busybox:stable", "emulator-fixed-name", List.of(), null, null, null, Map.of(),
                List.of(), null, List.of(), List.of(), List.of(), Map.of(), null, false, null,
                List.of(), null, null, List.of());

        assertEquals("slowly-created-container", manager().create(spec));
    }

    /**
     * Image and label matching alone cannot tell "my own create's lost response" apart from a
     * second, genuinely concurrent {@code create()} call racing on the same fixed name, image,
     * and spec labels (e.g. a duplicate CreateBroker request retried by an AWS SDK client while
     * the original is still in flight), both produce a container satisfying {@code matchesSpec}.
     * Adopting the other call's container means starting, modifying, or removing a container this
     * invocation doesn't own. Each {@code create()} call tags its own createCmd with a fresh
     * per-call attempt id; a name-conflicting container missing (or mismatching) this exact id
     * belongs to a different call and must not be adopted.
     */
    @Test
    void createRethrowsConflictWhenExistingContainerIsFromADifferentConcurrentCreateCall() {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        when(createCmd.exec()).thenThrow(new ConflictException("named container already exists"));

        Container existing = mock(Container.class);
        when(existing.getNames()).thenReturn(new String[] {"/emulator-fixed-name"});
        // Same image and default/spec labels as this call would apply, but a different
        // create() invocation's attempt id, simulating a genuinely concurrent racing caller,
        // not this call's own lost-response retry.
        Map<String, String> foreignLabels = new HashMap<>(
                Map.of("floci", "true", "floci_emulator", "floci-aws"));
        foreignLabels.put(ContainerLifecycleManager.CREATE_ATTEMPT_LABEL, "some-other-callers-attempt-id");
        when(existing.getLabels()).thenReturn(foreignLabels);

        ListContainersCmd listCmd = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of(existing));

        ContainerSpec spec = new ContainerSpec(
                "busybox:stable", "emulator-fixed-name", List.of(), null, null, null, Map.of(),
                List.of(), null, List.of(), List.of(), List.of(), Map.of(), null, false, null,
                List.of(), null, null, List.of());

        assertThrows(ConflictException.class, () -> manager().create(spec));
    }

    /**
     * The list API reports {@code Container.getImage()} as an image ID/digest rather than the tag
     * whenever the tag has been re-pulled, retagged, or removed since the container was created,
     * routine on a long-lived host. Comparing it against the spec's tag would then reject the
     * container THIS call just created, rethrow the conflict, and leak that container untracked:
     * exactly the failure conflict recovery exists to prevent, and only under the daemon churn that
     * makes a lost create response likely in the first place. The per-call attempt id is already
     * proof of ownership no other call can reproduce, so adoption must not hinge on the image
     * string.
     */
    @Test
    void createAdoptsOwnContainerWhenListApiReportsImageAsADigest() {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> labelsCaptor =
                ArgumentCaptor.forClass(Map.class);
        when(createCmd.withLabels(labelsCaptor.capture())).thenReturn(createCmd);
        when(createCmd.exec()).thenThrow(new ConflictException("named container already exists"));

        Container existing = mock(Container.class);
        when(existing.getId()).thenReturn("winning-container-id");
        when(existing.getNames()).thenReturn(new String[] {"/emulator-fixed-name"});
        // lenient: matchesSpec must NOT read the image at all. Stubbing it anyway is the regression
        // guard, reintroduce a tag comparison and this digest makes the adoption fail.
        lenient().when(existing.getImage()).thenReturn(
                "sha256:1f2e3d4c5b6a798877665544332211009988776655443322110099887766554433");
        when(existing.getLabels()).thenAnswer(inv -> labelsCaptor.getValue());

        ListContainersCmd listCmd = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of(existing));

        ContainerSpec spec = new ContainerSpec(
                "busybox:stable", "emulator-fixed-name", List.of(), null, null, null, Map.of(),
                List.of(), null, List.of(), List.of(), List.of(), Map.of(), null, false, null,
                List.of(), null, null, List.of());

        assertEquals("winning-container-id", manager().create(spec));
    }

    @Test
    void createRethrowsConflictWhenExistingContainerLabelsDoNotMatch() {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        when(createCmd.exec()).thenThrow(new ConflictException("named container already exists"));

        Container existing = mock(Container.class);
        when(existing.getNames()).thenReturn(new String[] {"/emulator-fixed-name"});
        when(existing.getLabels()).thenReturn(
                Map.of("floci", "true", "floci_emulator", "floci-lambda"));

        ListContainersCmd listCmd = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of(existing));

        ContainerSpec spec = new ContainerSpec(
                "busybox:stable", "emulator-fixed-name", List.of(), null, null, null, Map.of(),
                List.of(), null, List.of(), List.of(), List.of(), Map.of(), null, false, null,
                List.of(), null, null, List.of());

        assertThrows(ConflictException.class, () -> manager().create(spec));
    }

    @Test
    void createRethrowsConflictWhenNoNamedContainerExists() {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        when(createCmd.exec()).thenThrow(new ConflictException("named container already exists"));

        ListContainersCmd listCmd = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of());

        ContainerSpec spec = new ContainerSpec(
                "busybox:stable", "emulator-fixed-name", List.of(), null, null, null, Map.of(),
                List.of(), null, List.of(), List.of(), List.of(), Map.of(), null, false, null,
                List.of(), null, null, List.of());

        assertThrows(ConflictException.class, () -> manager().create(spec));
    }

    @Test
    void createAdoptsOriginalAttemptAfterMinutesOfDelayedVisibility() {
        ConflictScenario scenario = conflictScenario();
        AtomicLong now = new AtomicLong(TimeUnit.HOURS.toNanos(2));
        long started = now.get();
        when(scenario.listCmd().exec()).thenAnswer(inv ->
                now.get() - started < TimeUnit.SECONDS.toNanos(140) ? List.of() : List.of(scenario.owned()));

        String id = manager(now, now::addAndGet).create(scenario.spec());

        assertEquals("original-container-id", id);
        assertTrue(now.get() - started >= TimeUnit.SECONDS.toNanos(140));
        assertTrue(now.get() - started < TimeUnit.SECONDS.toNanos(300));
        verify(scenario.listCmd(), atLeast(2)).exec();
        verifyOnlyOriginalCreate(scenario);
    }

    @Test
    void createPreservesOriginalConflictWhenVisibilityBudgetExpires() {
        ConflictScenario scenario = conflictScenario();
        AtomicLong now = new AtomicLong();
        when(scenario.listCmd().exec()).thenReturn(List.of());

        ConflictException failure = assertThrows(ConflictException.class,
                () -> manager(now, now::addAndGet).create(scenario.spec()));

        assertSame(scenario.conflict(), failure);
        assertEquals(TimeUnit.SECONDS.toNanos(300), now.get());
        verifyOnlyOriginalCreate(scenario);
    }

    @ParameterizedTest
    @ValueSource(longs = {300, 301})
    void createDoesNotPollAgainWhenWaitReachesOrExceedsDeadline(long elapsedSeconds) {
        ConflictScenario scenario = conflictScenario();
        AtomicLong now = new AtomicLong();
        when(scenario.listCmd().exec()).thenReturn(List.of(), List.of(scenario.owned()));

        assertSame(scenario.conflict(), assertThrows(ConflictException.class,
                () -> manager(now, nanos -> now.set(TimeUnit.SECONDS.toNanos(elapsedSeconds)))
                        .create(scenario.spec())));

        verify(scenario.listCmd()).exec();
        verifyOnlyOriginalCreate(scenario);
    }

    @Test
    void createChargesLookupTimeToVisibilityBudget() {
        ConflictScenario scenario = conflictScenario();
        AtomicLong now = new AtomicLong();
        when(scenario.listCmd().exec()).thenAnswer(inv -> {
            now.addAndGet(TimeUnit.SECONDS.toNanos(301));
            return List.of();
        });
        AtomicLong sleeps = new AtomicLong();

        assertSame(scenario.conflict(), assertThrows(ConflictException.class,
                () -> manager(now, nanos -> sleeps.incrementAndGet()).create(scenario.spec())));

        assertEquals(0, sleeps.get());
        verify(scenario.listCmd()).exec();
        verifyOnlyOriginalCreate(scenario);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createRejectsForeignContainerThatAppearsWhileWaiting(boolean missingAttemptLabel) {
        ConflictScenario scenario = conflictScenario();
        AtomicLong now = new AtomicLong();
        Container foreign = mock(Container.class);
        when(foreign.getNames()).thenReturn(new String[] {"/emulator-fixed-name"});
        when(foreign.getLabels()).thenAnswer(inv -> {
            Map<String, String> labels = new HashMap<>(scenario.owned().getLabels());
            if (missingAttemptLabel) {
                labels.remove(ContainerLifecycleManager.CREATE_ATTEMPT_LABEL);
            } else {
                labels.put(ContainerLifecycleManager.CREATE_ATTEMPT_LABEL, "different-create-attempt");
            }
            return labels;
        });
        when(scenario.listCmd().exec()).thenReturn(List.of(), List.of(foreign), List.of(scenario.owned()));

        assertSame(scenario.conflict(), assertThrows(ConflictException.class,
                () -> manager(now, now::addAndGet).create(scenario.spec())));

        verify(scenario.listCmd(), times(2)).exec();
        verifyOnlyOriginalCreate(scenario);
    }

    @Test
    void createPreservesLookupFailureInsteadOfTreatingItAsPendingCreation() {
        ConflictScenario scenario = conflictScenario();
        AtomicLong now = new AtomicLong();
        IllegalStateException lookupFailure = new IllegalStateException("Docker inventory unavailable");
        when(scenario.listCmd().exec()).thenThrow(lookupFailure);

        ConflictException failure = assertThrows(ConflictException.class,
                () -> manager(now, now::addAndGet).create(scenario.spec()));

        assertSame(scenario.conflict(), failure);
        assertEquals(1, failure.getSuppressed().length);
        assertSame(lookupFailure, failure.getSuppressed()[0]);
        assertEquals(0, now.get());
        verify(scenario.listCmd()).exec();
        verifyOnlyOriginalCreate(scenario);
    }

    @Test
    void createPreservesInterruptedSleepAndOriginalConflict() {
        ConflictScenario scenario = conflictScenario();
        AtomicLong now = new AtomicLong();
        InterruptedException interruption = new InterruptedException("caller cancelled");
        when(scenario.listCmd().exec()).thenReturn(List.of());

        try {
            ConflictException failure = assertThrows(ConflictException.class,
                    () -> manager(now, nanos -> {
                        throw interruption;
                    }).create(scenario.spec()));

            assertSame(scenario.conflict(), failure);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, failure.getSuppressed().length);
            assertSame(interruption, failure.getSuppressed()[0]);
            verify(scenario.listCmd()).exec();
            verifyOnlyOriginalCreate(scenario);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void createDoesNotPollWhenConflictRecoveryIsAlreadyInterrupted() {
        ConflictScenario scenario = conflictScenario();
        AtomicLong now = new AtomicLong();

        try {
            Thread.currentThread().interrupt();
            assertSame(scenario.conflict(), assertThrows(ConflictException.class,
                    () -> manager(now, now::addAndGet).create(scenario.spec())));

            assertTrue(Thread.currentThread().isInterrupted());
            verify(scenario.listCmd(), never()).exec();
            verifyOnlyOriginalCreate(scenario);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void createDoesNotAdoptWhenInterruptedDuringLookup() {
        ConflictScenario scenario = conflictScenario();
        AtomicLong now = new AtomicLong();
        when(scenario.listCmd().exec()).thenAnswer(inv -> {
            Thread.currentThread().interrupt();
            return List.of(scenario.owned());
        });

        try {
            assertSame(scenario.conflict(), assertThrows(ConflictException.class,
                    () -> manager(now, now::addAndGet).create(scenario.spec())));

            assertTrue(Thread.currentThread().isInterrupted());
            verify(scenario.listCmd()).exec();
            verifyOnlyOriginalCreate(scenario);
        } finally {
            Thread.interrupted();
        }
    }

    private ConflictScenario conflictScenario() {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> labelsCaptor = ArgumentCaptor.forClass(Map.class);
        when(createCmd.withLabels(labelsCaptor.capture())).thenReturn(createCmd);
        ConflictException conflict = new ConflictException("named container already exists");
        when(createCmd.exec()).thenThrow(conflict);
        when(dockerConfig.apiTimeoutSeconds()).thenReturn(300);

        Container owned = mock(Container.class);
        lenient().when(owned.getId()).thenReturn("original-container-id");
        lenient().when(owned.getNames()).thenReturn(new String[] {"/emulator-fixed-name"});
        lenient().when(owned.getLabels()).thenAnswer(inv -> labelsCaptor.getValue());
        ListContainersCmd listCmd = mock(ListContainersCmd.class, RETURNS_SELF);
        lenient().when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        ContainerSpec spec = new ContainerSpec(
                "busybox:stable", "emulator-fixed-name", List.of(), null, null, null, Map.of(),
                List.of(), null, List.of(), List.of(), List.of(), Map.of("resource_owner", "caller-a"),
                null, false, null, List.of(), null, null, List.of());
        return new ConflictScenario(createCmd, listCmd, owned, spec, conflict);
    }

    private void verifyOnlyOriginalCreate(ConflictScenario scenario) {
        verify(dockerClient).createContainerCmd("busybox:stable");
        verify(scenario.createCmd()).exec();
        verify(dockerClient, never()).startContainerCmd(anyString());
        verify(dockerClient, never()).removeContainerCmd(anyString());
    }

    private ContainerLifecycleManager manager(AtomicLong now, ContainerLifecycleManager.CreateWait wait) {
        return new ContainerLifecycleManager(dockerClient, imageCacheService, containerDetector, config,
                0, now::get, wait);
    }

    private record ConflictScenario(CreateContainerCmd createCmd, ListContainersCmd listCmd, Container owned,
                                    ContainerSpec spec, ConflictException conflict) {
    }

    private ContainerLifecycleManager manager() {
        return new ContainerLifecycleManager(
                dockerClient, imageCacheService, containerDetector, config);
    }
}
