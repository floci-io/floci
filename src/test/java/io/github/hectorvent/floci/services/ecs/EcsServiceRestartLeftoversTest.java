package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskHandle;
import io.github.hectorvent.floci.services.ecs.exec.EcsExecSessionRegistry;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsLoadBalancer;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.EcsTaskAddress;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.NetworkBinding;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A restarted Floci knows no task, since task state is memory-only, so what the previous run's
 * tasks left behind has to go before the scheduler starts their replacements: containers a run
 * without a graceful shutdown left serving, and the load balancer targets and Cloud Map instances
 * that even a graceful one leaves registered.
 */
class EcsServiceRestartLeftoversTest {

    private static final String REGION = "us-east-1";
    private static final String TARGET_GROUP_ARN =
            "arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/web/0123456789abcdef";

    @Test
    void releasesTheRegistrationsThePreviousRunRecorded() {
        EcsLoadBalancerRegistrar lbRegistrar = mock(EcsLoadBalancerRegistrar.class);
        EcsServiceDiscoveryRegistrar discoveryRegistrar = mock(EcsServiceDiscoveryRegistrar.class);

        service(new SharedStorageFactory(), true, mock(EcsContainerManager.class),
                lbRegistrar, discoveryRegistrar).releasePreviousRunLeftovers();

        verify(lbRegistrar).releaseRecordedTargets();
        verify(discoveryRegistrar).releaseRecordedInstances();
    }

    @Test
    void theSchedulerWaitsUntilThePreviousRunsContainersAreRemoved() {
        SharedStorageFactory storage = new SharedStorageFactory();
        persistServiceWithLoadBalancer(storage);
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        when(containerManager.removeLeftoverContainers()).thenReturn(false, false, true);
        EcsService restarted = service(storage, false, containerManager,
                mock(EcsLoadBalancerRegistrar.class), null);

        restarted.releasePreviousRunLeftovers();
        restarted.reconcile();
        verify(containerManager, never()).startTask(any(), any(), any(), anyString());

        restarted.reconcile();
        restarted.reconcile();
        verify(containerManager, times(3)).removeLeftoverContainers();
    }

    @Test
    void theSweepIsNotRetriedWhileNoServiceNeedsATask() {
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        when(containerManager.removeLeftoverContainers()).thenReturn(false);
        EcsService restarted = service(new SharedStorageFactory(), false, containerManager,
                mock(EcsLoadBalancerRegistrar.class), null);

        restarted.releasePreviousRunLeftovers();
        restarted.reconcile();
        restarted.reconcile();

        verify(containerManager).removeLeftoverContainers();
    }

    @Test
    void runTaskRetriesTheSweepAndStartsNothingBesideAPreviousRunsContainers() {
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        when(containerManager.removeLeftoverContainers()).thenReturn(false, false, true);
        EcsService restarted = service(new SharedStorageFactory(), false, containerManager,
                mock(EcsLoadBalancerRegistrar.class), null);
        restarted.createCluster("app-cluster", Map.of(), REGION);
        ContainerDefinition container = new ContainerDefinition();
        container.setName("web");
        container.setImage("nginx:alpine");
        restarted.registerTaskDefinition("web", List.of(container), NetworkMode.bridge, null, null,
                null, null, List.of(), REGION);

        restarted.releasePreviousRunLeftovers();
        EcsTask blocked = restarted.runTask("app-cluster", "web", 1, LaunchType.EC2, null, null,
                null, null, REGION).getFirst();

        assertEquals("STOPPED", blocked.getLastStatus());
        assertThat(blocked.getStoppedReason(), containsString("previous run"));
        verify(containerManager, never()).startTask(any(), any(), any(), anyString());

        restarted.runTask("app-cluster", "web", 1, LaunchType.EC2, null, null, null, null, REGION);

        verify(containerManager, times(3)).removeLeftoverContainers();
        verify(containerManager).startTask(any(), any(), any(), anyString());
    }

    @Test
    void dockerModeRemovesTheContainersAPreviousRunLeft() {
        EcsContainerManager containerManager = mock(EcsContainerManager.class);

        service(new SharedStorageFactory(), false, containerManager,
                mock(EcsLoadBalancerRegistrar.class), null).releasePreviousRunLeftovers();

        verify(containerManager).removeLeftoverContainers();
    }

    @Test
    void mockModeHasNoContainersToRemove() {
        EcsContainerManager containerManager = mock(EcsContainerManager.class);

        service(new SharedStorageFactory(), true, containerManager,
                mock(EcsLoadBalancerRegistrar.class), null).releasePreviousRunLeftovers();

        verify(containerManager, never()).removeLeftoverContainers();
    }

    @Test
    void startupReleasesLeftoversBeforeTheSchedulerRuns() {
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        EcsService service = service(new SharedStorageFactory(), false, containerManager,
                mock(EcsLoadBalancerRegistrar.class), null);

        service.init();
        try {
            verify(containerManager).removeLeftoverContainers();
            verify(containerManager, never()).startTask(any(), any(), any(), anyString());
        } finally {
            service.stopManagedContainers();
        }
    }

    @Test
    void aStartingTaskEvictsTheUnrecordedRegistrationsAtItsAddress() {
        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        when(containerManager.removeLeftoverContainers()).thenReturn(true);
        when(containerManager.startTask(any(), any(), any(), anyString())).thenAnswer(invocation -> {
            EcsTask task = invocation.getArgument(0);
            Container container = new Container();
            container.setName("web");
            container.setNetworkBindings(List.of(new NetworkBinding("0.0.0.0", 80, 80, "tcp")));
            task.setContainers(List.of(container));
            task.setPrivateIpAddress("10.0.1.5");
            return new EcsTaskHandle(task.getTaskArn(), Map.of("web", "docker-id"), Map.of());
        });
        // Floci running natively: targets reach the task on loopback, Cloud Map registers its Docker address.
        when(containerManager.resolveContainerHost(any())).thenReturn("127.0.0.1");
        when(containerManager.resolvePeerAddress(any())).thenReturn(Optional.of("172.19.0.4"));
        when(containerManager.taskVpcId(any(), anyString())).thenReturn("vpc-app");
        EcsLoadBalancerRegistrar lbRegistrar = mock(EcsLoadBalancerRegistrar.class);
        EcsServiceDiscoveryRegistrar discoveryRegistrar = mock(EcsServiceDiscoveryRegistrar.class);
        when(discoveryRegistrar.cloudMapServiceIds(any())).thenReturn(List.of("srv-alpha"));
        EcsService ecs = service(new SharedStorageFactory(), false, containerManager, lbRegistrar,
                discoveryRegistrar);
        ecs.releasePreviousRunLeftovers();
        ecs.createCluster("app-cluster", Map.of(), REGION);
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName("web");
        definition.setImage("nginx:alpine");
        ecs.registerTaskDefinition("web", List.of(definition), NetworkMode.bridge, null, null,
                null, null, List.of(), REGION);
        EcsLoadBalancer lb = new EcsLoadBalancer();
        lb.setTargetGroupArn(TARGET_GROUP_ARN);
        lb.setContainerName("web");
        lb.setContainerPort(80);
        ecs.createService("app-cluster", "alpha", "web", 0, LaunchType.EC2, List.of(lb), null, REGION);

        // A task of no service at all: any container can take the address a stale entry points at.
        EcsTask task = ecs.runTask("app-cluster", "web", 1, LaunchType.EC2, null, null, null, null, REGION)
                .getFirst();

        assertEquals("RUNNING", task.getLastStatus());
        // A target only on the ports the container serves, and never at the VPC-scoped ENI address.
        verify(lbRegistrar).evictUnrecordedTargets(task, Set.of(TARGET_GROUP_ARN),
                Set.of(new EcsTaskAddress("127.0.0.1", 80)), REGION);
        verify(discoveryRegistrar).evictUnrecordedInstances(task, Set.of("srv-alpha"),
                Set.of(new EcsTaskAddress("10.0.1.5", null, "vpc-app"), new EcsTaskAddress("172.19.0.4", null)),
                REGION);
    }

    private static void persistServiceWithLoadBalancer(StorageFactory storage) {
        EcsService first = service(storage, true, mock(EcsContainerManager.class),
                mock(EcsLoadBalancerRegistrar.class), null);
        first.createCluster("app-cluster", Map.of(), REGION);
        ContainerDefinition container = new ContainerDefinition();
        container.setName("web");
        container.setImage("nginx:alpine");
        first.registerTaskDefinition("web", List.of(container), NetworkMode.bridge, null, null,
                null, null, List.of(), REGION);
        EcsLoadBalancer lb = new EcsLoadBalancer();
        lb.setTargetGroupArn(TARGET_GROUP_ARN);
        lb.setContainerName("web");
        lb.setContainerPort(80);
        first.createService("app-cluster", "web-svc", "web", 1, LaunchType.EC2, List.of(lb), null, REGION);
    }

    private static EcsService service(StorageFactory storage, boolean mockMode,
                                      EcsContainerManager containerManager,
                                      EcsLoadBalancerRegistrar lbRegistrar,
                                      EcsServiceDiscoveryRegistrar discoveryRegistrar) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(mockMode);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsService service = new EcsService(new RegionResolver(REGION, "000000000000"), containerManager,
                config, lbRegistrar, storage, null, new EcsExecSessionRegistry(), discoveryRegistrar);
        service.initializeStorage();
        return service;
    }

    private static final class SharedStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private SharedStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName,
                    ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
        }
    }
}
