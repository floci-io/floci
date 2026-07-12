package io.github.hectorvent.floci.services.amazonmq.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.services.amazonmq.model.Broker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class RabbitMqManagerTest {

    private ContainerLifecycleManager lifecycleManager;
    private RabbitMqManager manager;

    @BeforeEach
    void setUp() {
        lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        manager = new RabbitMqManager(
                Mockito.mock(ContainerBuilder.class),
                lifecycleManager,
                Mockito.mock(ContainerLogStreamer.class),
                Mockito.mock(ContainerDetector.class),
                Mockito.mock(EmulatorConfig.class),
                Mockito.mock(RegionResolver.class));
    }

    private Broker broker(String brokerId) {
        return new Broker(brokerId, "arn", "name", "RABBITMQ", "3.13",
                "SINGLE_INSTANCE", "mq.t3.micro");
    }

    @Test
    void stopContainerUsesContainerIdWhenPresent() {
        Broker broker = broker("b-1");
        broker.setContainerId("container-abc");

        manager.stopContainer(broker);

        verify(lifecycleManager).stopAndRemove(Mockito.eq("container-abc"), Mockito.any());
        verifyNoMoreInteractions(lifecycleManager);
    }

    @Test
    void stopContainerFallsBackToDeterministicNameWhenIdMissing() {
        // After an emulator restart containerId is null (not persisted); teardown
        // must still remove the container by its deterministic name.
        Broker broker = broker("b-2");

        manager.stopContainer(broker);

        verify(lifecycleManager).removeIfExists("floci-amazonmq-b-2");
        verifyNoMoreInteractions(lifecycleManager);
    }
}
