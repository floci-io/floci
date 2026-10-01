package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.ServiceDeployment;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Amazon ECS deletes the service deployment when you delete a service", and likewise the service
 * revision. A deleted service must not keep them, nor hand them to a service later created under
 * the same name.
 */
class EcsServiceDeleteDeploymentsTest {

    private static final String REGION = "us-east-1";
    private static final String CLUSTER = "del-cluster";

    @Test
    void deleteServiceRemovesItsDeploymentsAndRevisions() {
        EcsService service = newMockModeService();
        service.createCluster(CLUSTER, REGION);
        registerTaskDef(service);
        service.createService(CLUSTER, "doomed", "del-fam", 1, LaunchType.FARGATE, List.of(), null, REGION);
        service.createService(CLUSTER, "kept", "del-fam", 1, LaunchType.FARGATE, List.of(), null, REGION);
        service.reconcileServices();

        ServiceDeployment doomed = onlyDeployment(service, "doomed");
        ServiceDeployment kept = onlyDeployment(service, "kept");

        service.deleteService(CLUSTER, "doomed", true, REGION);
        service.reconcileServices();

        assertEquals(List.of(), service.listServiceDeployments("doomed", CLUSTER, null, REGION),
                "a deleted service lists no deployments");
        assertEquals(List.of(), service.describeServiceDeployments(List.of(doomed.getServiceDeploymentArn())),
                "the deleted deployment's ARN no longer resolves");
        assertEquals(List.of(), service.describeServiceRevisions(List.of(doomed.getTargetServiceRevisionArn())),
                "the deleted service's revision no longer resolves");
        EcsServiceModel inactive = service.describeServices(CLUSTER, List.of("doomed"), REGION).getFirst();
        assertEquals("INACTIVE", inactive.getStatus());
        assertNull(service.currentServiceDeployment(inactive),
                "DescribeServices on the deleted service points at no deployment");

        assertEquals(List.of(kept.getServiceDeploymentArn()),
                service.listServiceDeployments("kept", CLUSTER, null, REGION),
                "another service in the cluster keeps its deployment");
        assertEquals(1, service.describeServiceRevisions(List.of(kept.getTargetServiceRevisionArn())).size(),
                "another service in the cluster keeps its revision");
    }

    @Test
    void aRecreatedServiceDoesNotInheritTheDeletedOnesDeployments() {
        EcsService service = newMockModeService();
        service.createCluster(CLUSTER, REGION);
        registerTaskDef(service);
        service.createService(CLUSTER, "again", "del-fam", 0, LaunchType.FARGATE, List.of(), null, REGION);
        ServiceDeployment first = onlyDeployment(service, "again");
        service.deleteService(CLUSTER, "again", false, REGION);

        service.createService(CLUSTER, "again", "del-fam", 0, LaunchType.FARGATE, List.of(), null, REGION);
        service.reconcileServices();

        ServiceDeployment second = onlyDeployment(service, "again");
        assertEquals(first.getServiceArn(), second.getServiceArn(), "the recreated service reuses the ARN");
        assertEquals(List.of(), second.getSourceServiceRevisionArns(),
                "the new deployment does not roll from the deleted service's revision");
    }

    private static ServiceDeployment onlyDeployment(EcsService service, String serviceName) {
        List<ServiceDeployment> deployments =
                service.listServiceDeploymentsDetailed(serviceName, CLUSTER, null, REGION);
        assertEquals(1, deployments.size(), serviceName + " has exactly one deployment");
        return deployments.getFirst();
    }

    private static void registerTaskDef(EcsService service) {
        ContainerDefinition cd = new ContainerDefinition();
        cd.setName("app");
        cd.setImage("app:1");
        service.registerTaskDefinition("del-fam", List.of(cd), null, null, null, null, null, List.of(), REGION);
    }

    private static EcsService newMockModeService() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(true);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsService service = new EcsService(
                new RegionResolver(REGION, "000000000000"),
                mock(EcsContainerManager.class),
                config,
                mock(EcsLoadBalancerRegistrar.class),
                new InMemoryStorageFactory(),
                null);
        service.initializeStorage();
        return service;
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private InMemoryStorageFactory() {
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
