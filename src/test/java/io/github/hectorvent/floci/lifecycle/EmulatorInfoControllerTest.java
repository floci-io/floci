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

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
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
}
