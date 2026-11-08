package io.floci.aws.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.testutil.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

class LambdaContainerReconcilerTest {

    private ContainerLifecycleManager lifecycle;
    private ListContainersCmd list;
    private EmulatorConfig config;
    private LambdaContainerReconciler reconciler;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class);
        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);
        when(config.docker()).thenReturn(docker);
        when(docker.resourceNamespace()).thenReturn(Optional.of("deployment-a"));
        when(config.port()).thenReturn(4566);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.LambdaServiceConfig lambda = mock(EmulatorConfig.LambdaServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambda);
        when(lambda.reconcileContainersOnStartup()).thenReturn(true);
        lifecycle = mock(ContainerLifecycleManager.class);
        DockerClient client = mock(DockerClient.class);
        list = mock(ListContainersCmd.class, RETURNS_SELF);
        when(lifecycle.getDockerClient()).thenReturn(client);
        when(client.listContainersCmd()).thenReturn(list);
        when(list.exec()).thenReturn(List.of());
        reconciler = new LambdaContainerReconciler(config, lifecycle);
    }

    @Test
    void labelsIdentifyTheDeploymentAndDistinguishRuns() {
        Map<String, String> labels = reconciler.ownershipLabels();
        assertEquals("deployment-a/4566", labels.get(ContainerStorageHelper.OWNER_LABEL));
        assertEquals("lambda", labels.get(ContainerStorageHelper.SERVICE_LABEL));
        assertNotEquals(labels.get(LambdaContainerReconciler.RUN_LABEL),
                new LambdaContainerReconciler(config, lifecycle).ownershipLabels().get(LambdaContainerReconciler.RUN_LABEL));
    }

    @Test
    void removesOnlyOwnedMarkedLambdaContainersFromPreviousRuns() {
        Container old = container("old", Map.of());
        Container current = container("current", reconciler.ownershipLabels());
        Container sibling = container("sibling", Map.of(ContainerStorageHelper.OWNER_LABEL, "deployment-b/4566"));
        Container otherPort = container("other-port", Map.of(ContainerStorageHelper.OWNER_LABEL, "deployment-a/4567"));
        Container otherService = container("ecs", Map.of(ContainerStorageHelper.SERVICE_LABEL, "ecs"));
        Container unowned = container("unowned", Map.of());
        unowned.getLabels().remove(ContainerStorageHelper.OWNER_LABEL);
        Container unmarked = container("unmarked", Map.of());
        unmarked.getLabels().remove(LambdaContainerReconciler.RUN_LABEL);
        Container contradictory = container("conflict", Map.of("floci_owner_port", "deployment-b/4566"));
        Container legacyOwner = container("legacy-owner", Map.of("floci_owner_port", "deployment-a/4566"));
        legacyOwner.getLabels().remove(ContainerStorageHelper.OWNER_LABEL);
        when(list.exec()).thenReturn(List.of(old, current, sibling, otherPort, otherService, unowned, unmarked,
                contradictory, legacyOwner));

        reconciler.reconcile();
        reconciler.reconcile();

        verify(lifecycle).removeIfExistsStrict("old", true);
        verify(lifecycle).removeIfExistsStrict("legacy-owner", true);
        verify(lifecycle, times(2)).getDockerClient();
        verifyNoMoreInteractions(lifecycle);
        verify(list, times(2)).withShowAll(true);
        verify(list).withLabelFilter(Map.of(ContainerStorageHelper.SERVICE_LABEL, "lambda",
                ContainerStorageHelper.OWNER_LABEL, "deployment-a/4566"));
        verify(list).withLabelFilter(Map.of(ContainerStorageHelper.SERVICE_LABEL, "lambda",
                "floci_owner_port", "deployment-a/4566"));
    }

    @Test
    void failedInventoryIsRetriedWithoutRemovingAnythingFromAPartialList() {
        Container old = container("old", Map.of());
        when(list.exec()).thenReturn(List.of(old)).thenThrow(new IllegalStateException("daemon unavailable"))
                .thenReturn(List.of(old));
        reconciler.reconcile();
        verify(lifecycle, never()).removeIfExistsStrict(anyString(), anyBoolean());
        reconciler.reconcile();
        verify(lifecycle).removeIfExistsStrict("old", true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nullInventoryIsNotTreatedAsAnEmptySuccessfulSweep(boolean secondQuery) {
        Container old = container("old", Map.of());
        if (secondQuery) {
            when(list.exec()).thenReturn(List.of(old)).thenReturn(null).thenReturn(List.of(old));
        } else {
            when(list.exec()).thenReturn(null).thenReturn(List.of(old));
        }
        reconciler.reconcile();
        verify(lifecycle, never()).removeIfExistsStrict(anyString(), anyBoolean());
        reconciler.reconcile();
        verify(lifecycle).removeIfExistsStrict("old", true);
    }

    @Test
    void removalFailureDoesNotSkipOtherContainersAndIsRetried() {
        List<Container> containers = List.of(container("first", Map.of()), container("second", Map.of()),
                container("current", reconciler.ownershipLabels()));
        when(list.exec()).thenReturn(containers);
        doThrow(new IllegalStateException("busy")).doNothing().when(lifecycle).removeIfExistsStrict("first", true);
        reconciler.reconcile();
        verify(lifecycle).removeIfExistsStrict("second", true);
        reconciler.reconcile();
        verify(lifecycle, times(2)).removeIfExistsStrict("first", true);
        verify(lifecycle, never()).removeIfExistsStrict("current", true);
    }

    @Test
    void disabledCleanupNeverInventoriesOrRemovesAnotherRunsContainers() {
        when(config.services().lambda().reconcileContainersOnStartup()).thenReturn(false);
        reconciler.reconcile();
        verifyNoInteractions(lifecycle);
    }

    @Test
    void repeatedInventoryFailureWarnsOnceAndRetainsItsCause() {
        RuntimeException failure = new IllegalStateException("offline", new IllegalStateException("daemon refused"));
        when(list.exec()).thenThrow(failure);
        List<LogRecord> records = LogCapture.capture(LambdaContainerReconciler.class, () -> {
            reconciler.reconcile();
            reconciler.reconcile();
        });
        assertEquals(List.of(Level.WARNING.intValue(), Level.FINE.intValue()),
                records.stream().map(record -> record.getLevel().intValue()).toList());
        assertSame(failure, records.getFirst().getThrown());
    }

    @Test
    void repeatedRemovalFailureWarnsOnceAndRetainsItsCause() {
        Container old = container("old", Map.of());
        when(list.exec()).thenReturn(List.of(old));
        RuntimeException failure = new IllegalStateException("busy", new IllegalStateException("daemon refused"));
        doThrow(failure).when(lifecycle).removeIfExistsStrict("old", true);
        List<LogRecord> records = LogCapture.capture(LambdaContainerReconciler.class, () -> {
            reconciler.reconcile();
            reconciler.reconcile();
        });
        assertEquals(List.of(Level.WARNING.intValue(), Level.FINE.intValue()),
                records.stream().map(record -> record.getLevel().intValue()).toList());
        assertSame(failure, records.getFirst().getThrown());
    }

    private static Container container(String id, Map<String, String> overrides) {
        Container container = mock(Container.class);
        Map<String, String> labels = new HashMap<>(Map.of(
                ContainerStorageHelper.SERVICE_LABEL, "lambda",
                ContainerStorageHelper.OWNER_LABEL, "deployment-a/4566",
                LambdaContainerReconciler.RUN_LABEL, "previous-run"));
        labels.putAll(overrides);
        when(container.getId()).thenReturn(id);
        when(container.getLabels()).thenReturn(labels);
        return container;
    }
}
