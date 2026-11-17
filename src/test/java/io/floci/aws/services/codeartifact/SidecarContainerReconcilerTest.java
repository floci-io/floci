package io.floci.aws.services.codeartifact;

import com.github.dockerjava.api.model.Container;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.PerKeyContainerPool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class SidecarContainerReconcilerTest {

    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final PerKeyContainerPool pool = new PerKeyContainerPool(lifecycle, "/health");

    SidecarContainerReconcilerTest() {
        when(config.docker().resourceNamespace()).thenReturn(Optional.of("owned"));
    }

    @Test
    void inventoryFailurePreventsReconciliationFromPretendingCleanupSucceeded() {
        IllegalStateException failure = new IllegalStateException("Docker unavailable");
        when(lifecycle.listContainersForReconciliation()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, this::reconcile));
        verify(lifecycle, never()).removeIfExistsStrict(anyString(), anyBoolean());
    }

    @Test
    void failedRemovalStillAttemptsLaterLeftoversAndCanRetry() {
        Container first = container("first", currentName(), labels());
        Container second = container("second", currentName() + "-11111111-1111-1111-1111-111111111111", labels());
        when(lifecycle.listContainersForReconciliation()).thenReturn(List.of(first, second));
        IllegalStateException failure = new IllegalStateException("removal failed");
        doThrow(failure).doNothing().when(lifecycle).removeIfExistsStrict("first", true);
        assertSame(failure, assertThrows(IllegalStateException.class, this::reconcile));
        verify(lifecycle).removeIfExistsStrict("second", true);
        reconcile();
        verify(lifecycle, times(2)).removeIfExistsStrict("first", true);
    }

    @Test
    void legacyNamesAreReclaimedButUnownedNamesAreNot() {
        Container legacy = container("legacy", ContainerStorageHelper.legacyDockerName(config, "floci-pypiserver-repository"), labels());
        Container unlabelled = container("unlabelled", currentName(), Map.of());
        Container otherEmulator = container("other", currentName(),
                Map.of("floci", "true", "floci_emulator", "other", "floci_namespace", "owned"));
        Container otherRepository = container("other-repo", currentName() + "-other", labels());
        when(lifecycle.listContainersForReconciliation()).thenReturn(List.of(legacy, unlabelled, otherEmulator, otherRepository));
        reconcile();
        verify(lifecycle).removeIfExistsStrict("legacy", true);
        verify(lifecycle, never()).removeIfExistsStrict("unlabelled", true);
        verify(lifecycle, never()).removeIfExistsStrict("other", true);
        verify(lifecycle, never()).removeIfExistsStrict("other-repo", true);
    }

    private void reconcile() {
        SidecarContainerReconciler.reconcile(config, lifecycle, pool, "repository", "floci-pypiserver-repository");
    }

    private String currentName() {
        return ContainerStorageHelper.dockerName(config, "floci-pypiserver-repository");
    }

    private Map<String, String> labels() {
        return ContainerStorageHelper.defaultLabels(config);
    }

    private static Container container(String id, String name, Map<String, String> labels) {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(id);
        when(container.getNames()).thenReturn(new String[]{"/" + name});
        when(container.getLabels()).thenReturn(labels);
        return container;
    }
}
