package io.github.hectorvent.floci.lifecycle;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmulatorInfoControllerTest {

    @Mock private ServiceRegistry serviceRegistry;
    @Mock private InitLifecycleState initLifecycleState;
    @Mock private StorageFactory storageFactory;
    @Mock private Instance<Resettable> resettables;
    @Mock private Instance<ContainerTeardown> containerTeardowns;
    @Mock private FlociCertificateAuthority certificateAuthority;
    @Mock private EmulatorConfig config;
    @Mock private ContainerTeardown sageMakerTeardown;
    @Mock private ContainerTeardown batchTeardown;
    @Mock private Resettable resettable;

    private EmulatorInfoController controller;

    @BeforeEach
    void setUp() {
        controller = new EmulatorInfoController(serviceRegistry, initLifecycleState, storageFactory,
                resettables, containerTeardowns, certificateAuthority, config);
    }

    @Test
    @DisplayName("Should stop every managed container before wiping storage on reset")
    void reset_stopsManagedContainersBeforeClearingStorage() {
        when(containerTeardowns.iterator()).thenReturn(List.of(sageMakerTeardown, batchTeardown).iterator());
        when(resettables.iterator()).thenReturn(List.of(resettable).iterator());

        controller.reset();

        InOrder order = inOrder(sageMakerTeardown, batchTeardown, storageFactory, resettable);
        order.verify(sageMakerTeardown).stopManagedContainers();
        order.verify(batchTeardown).stopManagedContainers();
        order.verify(storageFactory).clearAll();
        order.verify(resettable).clear();
    }

    @Test
    @DisplayName("Should continue tearing down other containers and still wipe storage when one teardown fails")
    void reset_continuesAfterATeardownFailure() {
        doThrow(new IllegalStateException("docker daemon unreachable"))
                .when(sageMakerTeardown).stopManagedContainers();
        when(containerTeardowns.iterator()).thenReturn(List.of(sageMakerTeardown, batchTeardown).iterator());
        when(resettables.iterator()).thenReturn(List.<Resettable>of().iterator());

        controller.reset();

        verify(batchTeardown).stopManagedContainers();
        verify(storageFactory).clearAll();
    }

    @Test
    @DisplayName("nuke should stop managed containers just like reset")
    void nuke_alsoStopsManagedContainers() {
        when(containerTeardowns.iterator()).thenReturn(List.of(sageMakerTeardown).iterator());
        when(resettables.iterator()).thenReturn(List.<Resettable>of().iterator());

        controller.nuke();

        verify(sageMakerTeardown).stopManagedContainers();
        verify(storageFactory).clearAll();
    }

    @Test
    @DisplayName("Should call beforeReset on every service before the wipe and afterReset after every clear")
    void reset_bracketsTheWipeWithBeforeAndAfterHooks() {
        Resettable second = mock(Resettable.class);
        when(containerTeardowns.iterator()).thenReturn(List.<ContainerTeardown>of().iterator());
        when(resettables.iterator()).thenReturn(List.of(resettable, second).iterator());

        controller.reset();

        InOrder order = inOrder(storageFactory, resettable, second);
        order.verify(resettable).beforeReset();
        order.verify(second).beforeReset();
        order.verify(storageFactory).clearAll();
        order.verify(resettable).clear();
        order.verify(second).clear();
        order.verify(second).afterReset();
        order.verify(resettable).afterReset();
    }

    @Test
    @DisplayName("Should still call afterReset when the storage wipe fails, then rethrow")
    void reset_runsAfterResetWhenTheWipeFails() {
        doThrow(new IllegalStateException("wipe failed")).when(storageFactory).clearAll();
        when(containerTeardowns.iterator()).thenReturn(List.<ContainerTeardown>of().iterator());
        when(resettables.iterator()).thenReturn(List.of(resettable).iterator());

        assertThrows(IllegalStateException.class, controller::reset);

        verify(resettable).beforeReset();
        verify(resettable).afterReset();
        verify(resettable, never()).clear();
    }

    @Test
    @DisplayName("Should call afterReset on every service, even those after a beforeReset that failed, then rethrow")
    void reset_runsAfterResetOnEveryServiceWhenABeforeResetFails() {
        Resettable failing = mock(Resettable.class);
        Resettable later = mock(Resettable.class);
        doThrow(new IllegalStateException("drain timed out")).when(failing).beforeReset();
        when(containerTeardowns.iterator()).thenReturn(List.of(sageMakerTeardown).iterator());
        when(resettables.iterator()).thenReturn(List.of(resettable, failing, later).iterator());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, controller::reset);

        assertEquals("drain timed out", thrown.getMessage());
        // The teardown already shut down the pools that afterReset() restores, so the service
        // whose beforeReset() never ran needs afterReset() just as much as the ones that ran.
        InOrder order = inOrder(sageMakerTeardown, resettable, failing, later);
        order.verify(sageMakerTeardown).stopManagedContainers();
        order.verify(resettable).beforeReset();
        order.verify(failing).beforeReset();
        order.verify(later).afterReset();
        order.verify(failing).afterReset();
        order.verify(resettable).afterReset();
        verify(later, never()).beforeReset();
        verify(storageFactory, never()).clearAll();
        verify(resettable, never()).clear();
        verify(later, never()).clear();
    }

    @Test
    void resetRefusedByPreflightChangesNothing() {
        Resettable refusing = mock(Resettable.class);
        IllegalStateException refusal = new IllegalStateException("backend refuses reset");
        doThrow(refusal).when(refusing).checkReset();
        lenient().when(containerTeardowns.iterator()).thenReturn(List.of(sageMakerTeardown).iterator());
        when(resettables.iterator()).thenReturn(List.of(resettable, refusing).iterator());

        assertSame(refusal, assertThrows(IllegalStateException.class, controller::reset));

        verify(resettable).checkReset();
        verify(refusing).checkReset();
        verifyNoMoreInteractions(resettable, refusing);
        verify(sageMakerTeardown, never()).stopManagedContainers();
        verifyNoInteractions(storageFactory);
    }
}
