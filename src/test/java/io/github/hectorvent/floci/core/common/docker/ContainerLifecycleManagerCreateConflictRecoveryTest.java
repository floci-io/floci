package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.model.Container;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A create retry can hit the daemon twice for the same logical create: the first attempt's
 * response is lost to a transient socket error ({@link DockerRetry}), but the daemon already
 * created the named container, so the retry's {@code createCmd.exec()} fails with a 409 name
 * conflict instead of a transient I/O error. Without recovery this surfaces as a create
 * failure while a live, untracked container sits on the daemon.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContainerLifecycleManager — create retry name-conflict recovery")
class ContainerLifecycleManagerCreateConflictRecoveryTest {

    @Mock
    DockerClient dockerClient;

    @Mock
    ImageCacheService imageCacheService;

    @Mock
    ContainerDetector containerDetector;

    @Mock
    PortAllocator portAllocator;

    @Mock
    EmulatorConfig config;

    @Mock
    EmulatorConfig.DockerConfig dockerConfig;

    @BeforeEach
    void setUp() {
        lenient().when(config.docker()).thenReturn(dockerConfig);
        lenient().when(dockerConfig.resourceNamespace()).thenReturn(java.util.Optional.empty());
    }

    @Test
    void createAdoptsContainerThatWonTheRaceOnRetryConflict() {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("busybox:stable")).thenReturn(createCmd);
        // The container that actually got created on the daemon carries exactly the labels this
        // create() call applied (including the per-call attempt-id) — captured here rather than
        // hardcoded, since createWithConflictRecovery only adopts a container proven to be THIS
        // call's own lost-response attempt, not merely one with matching image/spec labels.
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.Map<String, String>> labelsCaptor =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
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
                "busybox:stable", "emulator-fixed-name", List.of(), null, null, null, java.util.Map.of(),
                List.of(), null, List.of(), List.of(), List.of(), java.util.Map.of(), null, false, null,
                List.of(), null, null, List.of());

        String containerId = manager().create(spec);

        assertEquals("winning-container-id", containerId);
    }

    /**
     * Image and label matching alone cannot tell "my own create's lost response" apart from a
     * second, genuinely concurrent {@code create()} call racing on the same fixed name, image,
     * and spec labels (e.g. a duplicate CreateBroker request retried by an AWS SDK client while
     * the original is still in flight) — both produce a container satisfying {@code matchesSpec}.
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
        // create() invocation's attempt id — simulating a genuinely concurrent racing caller,
        // not this call's own lost-response retry.
        java.util.Map<String, String> foreignLabels = new java.util.HashMap<>(
                java.util.Map.of("floci", "true", "floci_emulator", "floci-aws"));
        foreignLabels.put(ContainerLifecycleManager.CREATE_ATTEMPT_LABEL, "some-other-callers-attempt-id");
        when(existing.getLabels()).thenReturn(foreignLabels);

        ListContainersCmd listCmd = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of(existing));

        ContainerSpec spec = new ContainerSpec(
                "busybox:stable", "emulator-fixed-name", List.of(), null, null, null, java.util.Map.of(),
                List.of(), null, List.of(), List.of(), List.of(), java.util.Map.of(), null, false, null,
                List.of(), null, null, List.of());

        assertThrows(ConflictException.class, () -> manager().create(spec));
    }

    /**
     * The list API reports {@code Container.getImage()} as an image ID/digest rather than the tag
     * whenever the tag has been re-pulled, retagged, or removed since the container was created —
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
        org.mockito.ArgumentCaptor<java.util.Map<String, String>> labelsCaptor =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        when(createCmd.withLabels(labelsCaptor.capture())).thenReturn(createCmd);
        when(createCmd.exec()).thenThrow(new ConflictException("named container already exists"));

        Container existing = mock(Container.class);
        when(existing.getId()).thenReturn("winning-container-id");
        when(existing.getNames()).thenReturn(new String[] {"/emulator-fixed-name"});
        // lenient: matchesSpec must NOT read the image at all. Stubbing it anyway is the regression
        // guard — reintroduce a tag comparison and this digest makes the adoption fail.
        org.mockito.Mockito.lenient().when(existing.getImage()).thenReturn(
                "sha256:1f2e3d4c5b6a798877665544332211009988776655443322110099887766554433");
        when(existing.getLabels()).thenAnswer(inv -> labelsCaptor.getValue());

        ListContainersCmd listCmd = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of(existing));

        ContainerSpec spec = new ContainerSpec(
                "busybox:stable", "emulator-fixed-name", List.of(), null, null, null, java.util.Map.of(),
                List.of(), null, List.of(), List.of(), List.of(), java.util.Map.of(), null, false, null,
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
                java.util.Map.of("floci", "true", "floci_emulator", "floci-lambda"));

        ListContainersCmd listCmd = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of(existing));

        ContainerSpec spec = new ContainerSpec(
                "busybox:stable", "emulator-fixed-name", List.of(), null, null, null, java.util.Map.of(),
                List.of(), null, List.of(), List.of(), List.of(), java.util.Map.of(), null, false, null,
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
                "busybox:stable", "emulator-fixed-name", List.of(), null, null, null, java.util.Map.of(),
                List.of(), null, List.of(), List.of(), List.of(), java.util.Map.of(), null, false, null,
                List.of(), null, null, List.of());

        assertThrows(ConflictException.class, () -> manager().create(spec));
    }

    private ContainerLifecycleManager manager() {
        return new ContainerLifecycleManager(
                dockerClient, imageCacheService, containerDetector, portAllocator, config);
    }
}
