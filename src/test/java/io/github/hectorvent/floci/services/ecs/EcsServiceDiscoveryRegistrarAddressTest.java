package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudmap.CloudMapService;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.NetworkBinding;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which address the registrar puts into Cloud Map. A task ENI address is not on any network the
 * task's containers joined, so registering it gives a peer a name that resolves and a connection
 * that times out.
 */
class EcsServiceDiscoveryRegistrarAddressTest {

    private static final String REGION = "us-east-1";
    private static final String REGISTRY_ARN =
            "arn:aws:servicediscovery:us-east-1:000000000000:service/srv-abc123";

    private final CloudMapService cloudMapService = mock(CloudMapService.class);
    private final EcsContainerManager containerManager = mock(EcsContainerManager.class);
    private final EcsServiceDiscoveryRegistrar registrar =
            new EcsServiceDiscoveryRegistrar(cloudMapService, containerManager, storageFactory());

    @Test
    void awsvpcTaskRegistersTheAddressItsContainerHolds() {
        EcsTask task = task("10.70.1.10");
        when(containerManager.resolvePeerAddress(any(Container.class))).thenReturn(Optional.of("172.31.0.19"));

        registrar.registerTask(task, service(), REGION);

        assertEquals("172.31.0.19", registeredAddress());
        verify(containerManager, never()).resolveContainerHost(any(Container.class));
    }

    @Test
    void awsvpcTaskKeepsItsEniAddressWhenNoContainerAnswers() {
        EcsTask task = task("10.70.1.10");
        when(containerManager.resolvePeerAddress(any(Container.class))).thenReturn(Optional.empty());

        registrar.registerTask(task, service(), REGION);

        assertEquals("10.70.1.10", registeredAddress());
    }

    @Test
    void bridgeTaskStillRegistersTheContainerHost() {
        EcsTask task = task(null);
        when(containerManager.resolveContainerHost(any(Container.class))).thenReturn("127.0.0.1");

        registrar.registerTask(task, service(), REGION);

        assertEquals("127.0.0.1", registeredAddress());
        verify(containerManager, never()).resolvePeerAddress(any(Container.class));
    }

    @Test
    void awsvpcTaskAdvertisesTheContainerPortItsAddressAnswersOn() {
        EcsTask task = task("10.70.1.10");
        task.getContainers().getFirst().setNetworkBindings(
                List.of(new NetworkBinding("0.0.0.0", 8080, 49153, "tcp")));
        when(containerManager.resolvePeerAddress(any(Container.class))).thenReturn(Optional.of("172.31.0.19"));

        registrar.registerTask(task, serviceWithContainerPort(8080), REGION);

        Map<String, String> attributes = registeredAttributes();
        assertEquals("172.31.0.19", attributes.get("AWS_INSTANCE_IPV4"));
        assertEquals("8080", attributes.get("AWS_INSTANCE_PORT"));
    }

    @Test
    void bridgeTaskAdvertisesThePublishedHostPort() {
        EcsTask task = task(null);
        task.getContainers().getFirst().setNetworkBindings(
                List.of(new NetworkBinding("0.0.0.0", 8080, 49153, "tcp")));
        when(containerManager.resolveContainerHost(any(Container.class))).thenReturn("127.0.0.1");

        registrar.registerTask(task, serviceWithContainerPort(8080), REGION);

        assertEquals("49153", registeredAttributes().get("AWS_INSTANCE_PORT"));
    }

    private String registeredAddress() {
        return registeredAttributes().get("AWS_INSTANCE_IPV4");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> registeredAttributes() {
        ArgumentCaptor<Map<String, String>> attributes = ArgumentCaptor.forClass(Map.class);
        verify(cloudMapService).registerInstance(eq("srv-abc123"), anyString(), isNull(),
                attributes.capture(), eq(REGION));
        return attributes.getValue();
    }

    private static EcsServiceModel service() {
        EcsServiceModel svc = new EcsServiceModel();
        svc.setServiceRegistries(List.of(Map.of("registryArn", REGISTRY_ARN, "containerName", "app")));
        return svc;
    }

    private static EcsServiceModel serviceWithContainerPort(int containerPort) {
        EcsServiceModel svc = new EcsServiceModel();
        svc.setServiceRegistries(List.of(Map.of("registryArn", REGISTRY_ARN, "containerName", "app",
                "containerPort", containerPort)));
        return svc;
    }

    private static EcsTask task(String eniAddress) {
        Container container = new Container();
        container.setName("app");
        container.setDockerId("docker-app");
        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:" + REGION + ":000000000000:task/c/0123456789abcdef");
        task.setPrivateIpAddress(eniAddress);
        task.setContainers(List.of(container));
        return task;
    }

    private static StorageFactory storageFactory() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"));
        return storageFactory;
    }
}
