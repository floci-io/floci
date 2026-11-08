package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import io.floci.aws.testutil.LogCapture;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContainerReconcilerTest {

    private static final Map<String, String> FILTER = Map.of(
            ContainerStorageHelper.SERVICE_LABEL, "ecs", ContainerStorageHelper.OWNER_LABEL, "alpha/4566");
    private ContainerLifecycleManager lifecycle;
    private ListContainersCmd list;
    private ContainerReconciler reconciler;

    @BeforeEach
    void setUp() {
        lifecycle = mock(ContainerLifecycleManager.class);
        DockerClient client = mock(DockerClient.class);
        list = mock(ListContainersCmd.class, RETURNS_SELF);
        when(lifecycle.getDockerClient()).thenReturn(client);
        when(client.listContainersCmd()).thenReturn(list);
        reconciler = new ContainerReconciler(lifecycle, Logger.getLogger(ContainerReconcilerTest.class));
    }

    @Test
    void listingWarnsOnceAndReportsAgainAfterRecovery() {
        RuntimeException failure = new IllegalStateException("offline", new IllegalStateException("daemon refused"));
        when(list.exec()).thenThrow(failure);
        List<LogRecord> records = LogCapture.capture(ContainerReconcilerTest.class, () -> {
            assertFalse(reconciler.reconcile(FILTER, container -> true));
            assertFalse(reconciler.reconcile(FILTER, container -> true));
            doReturn(List.of()).when(list).exec();
            assertTrue(reconciler.reconcile(FILTER, container -> true));
            doThrow(failure).when(list).exec();
            assertFalse(reconciler.reconcile(FILTER, container -> true));
        });
        assertEquals(List.of(Level.WARNING.intValue(), Level.FINE.intValue(), Level.WARNING.intValue()),
                records.stream().map(record -> record.getLevel().intValue()).toList());
        assertTrue(records.stream().allMatch(record -> record.getThrown() == failure));
        verify(lifecycle, never()).removeIfExistsStrict(anyString(), anyBoolean());
    }

    @Test
    void removalWarnsOncePerContainerAndReportsAgainAfterSuccessfulRemoval() {
        Container alpha = container("alpha");
        Container beta = container("beta");
        when(list.exec()).thenReturn(List.of(alpha, beta));
        RuntimeException failure = new IllegalStateException("busy", new IllegalStateException("daemon refused"));
        doThrow(failure).when(lifecycle).removeIfExistsStrict(anyString(), eq(true));
        List<LogRecord> records = LogCapture.capture(ContainerReconcilerTest.class, () -> {
            assertFalse(reconciler.reconcile(FILTER, container -> true));
            assertFalse(reconciler.reconcile(FILTER, container -> true));
            doNothing().when(lifecycle).removeIfExistsStrict("alpha", true);
            assertFalse(reconciler.reconcile(FILTER, container -> true));
            doThrow(failure).when(lifecycle).removeIfExistsStrict("alpha", true);
            assertFalse(reconciler.reconcile(FILTER, container -> true));
        });
        List<LogRecord> failures = records.stream().filter(record -> record.getThrown() != null).toList();
        assertEquals(List.of(Level.WARNING.intValue(), Level.WARNING.intValue(), Level.FINE.intValue(),
                        Level.FINE.intValue(), Level.FINE.intValue(), Level.WARNING.intValue(), Level.FINE.intValue()),
                failures.stream().map(record -> record.getLevel().intValue()).toList());
        assertTrue(failures.stream().allMatch(record -> record.getThrown() == failure));
        verify(lifecycle, times(4)).removeIfExistsStrict("alpha", true);
        verify(lifecycle, times(4)).removeIfExistsStrict("beta", true);
    }

    @Test
    void filtersMustIdentifyBothServiceAndOwner() {
        assertThrows(IllegalArgumentException.class, () -> reconciler.reconcile(Map.of("io.floci.service", "ecs"),
                container -> true));
        assertThrows(IllegalArgumentException.class, () -> reconciler.reconcile(Map.of("io.floci.owner", "alpha/4566"),
                container -> true));
        verifyNoInteractions(lifecycle);
    }

    private static Container container(String id) {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(id);
        when(container.getLabels()).thenReturn(FILTER);
        return container;
    }
}
