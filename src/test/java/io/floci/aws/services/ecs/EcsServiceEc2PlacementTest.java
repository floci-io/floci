package io.floci.aws.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ecs.container.EcsContainerManager;
import io.floci.aws.services.ecs.model.ContainerDefinition;
import io.floci.aws.services.ecs.model.ContainerInstance;
import io.floci.aws.services.ecs.model.EcsTask;
import io.floci.aws.services.ecs.model.LaunchType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** REPLICA services on the EC2 launch type run their tasks on the cluster's container instances. */
class EcsServiceEc2PlacementTest {

    private static final String REGION = "us-east-1";

    @Test
    void ec2ReplicaTasksSpreadAcrossActiveContainerInstances() {
        EcsService service = newMockModeService();
        service.createCluster("ec2-placement", REGION);
        ContainerInstance a = service.registerContainerInstance("ec2-placement", null, List.of(), REGION);
        ContainerInstance b = service.registerContainerInstance("ec2-placement", null, List.of(), REGION);
        registerTaskDef(service);
        service.createService("ec2-placement", "web", "ec2-placement-fam", 4, LaunchType.EC2,
                List.of(), null, REGION);

        service.reconcileServices();

        List<EcsTask> tasks = tasks(service, "ec2-placement");
        assertEquals(4, tasks.size());
        Map<String, Long> perInstance = tasks.stream()
                .collect(Collectors.groupingBy(EcsTask::getContainerInstanceArn, Collectors.counting()));
        assertEquals(Map.of(a.getContainerInstanceArn(), 2L, b.getContainerInstanceArn(), 2L), perInstance);
    }

    @Test
    void ec2ReplicaTasksSkipInactiveContainerInstances() {
        EcsService service = newMockModeService();
        service.createCluster("ec2-placement-drain", REGION);
        ContainerInstance draining = service.registerContainerInstance("ec2-placement-drain", null, List.of(), REGION);
        ContainerInstance active = service.registerContainerInstance("ec2-placement-drain", null, List.of(), REGION);
        service.updateContainerInstancesState("ec2-placement-drain", List.of(draining.getContainerInstanceArn()),
                "DRAINING", REGION);
        registerTaskDef(service);
        service.createService("ec2-placement-drain", "web", "ec2-placement-fam", 2, LaunchType.EC2,
                List.of(), null, REGION);

        service.reconcileServices();

        assertEquals(Set.of(active.getContainerInstanceArn()), tasks(service, "ec2-placement-drain").stream()
                .map(EcsTask::getContainerInstanceArn).collect(Collectors.toSet()));
    }

    @Test
    void fargateReplicaTasksHaveNoContainerInstance() {
        EcsService service = newMockModeService();
        service.createCluster("fargate-placement", REGION);
        service.registerContainerInstance("fargate-placement", null, List.of(), REGION);
        registerTaskDef(service);
        service.createService("fargate-placement", "web", "ec2-placement-fam", 1, LaunchType.FARGATE,
                List.of(), null, REGION);

        service.reconcileServices();

        List<EcsTask> tasks = tasks(service, "fargate-placement");
        assertEquals(1, tasks.size());
        assertNull(tasks.getFirst().getContainerInstanceArn());
    }

    @Test
    void registerContainerInstanceRecordsTheInstanceOfItsIdentityDocument() {
        EcsService service = newMockModeService();
        service.createCluster("identity-doc", REGION);

        ContainerInstance instance = service.registerContainerInstance("identity-doc",
                "{\"instanceId\":\"i-0123456789abcdef0\",\"region\":\"us-east-1\"}", List.of(), REGION);

        assertEquals("i-0123456789abcdef0", instance.getEc2InstanceId());
    }

    @Test
    void identityDocumentWithoutAnInstanceIdNamesNoInstance() {
        assertEquals(Optional.empty(), EcsService.identityDocumentInstanceId(null));
        assertEquals(Optional.empty(), EcsService.identityDocumentInstanceId("not json"));
        assertEquals(Optional.empty(), EcsService.identityDocumentInstanceId("{\"region\":\"us-east-1\"}"));
        assertTrue(EcsService.identityDocumentInstanceId("{\"instanceId\":\"i-1\"}").isPresent());
    }

    private static List<EcsTask> tasks(EcsService service, String cluster) {
        return service.describeTasks(cluster, service.listTasks(cluster, null, null, null, REGION), REGION);
    }

    private static void registerTaskDef(EcsService service) {
        ContainerDefinition cd = new ContainerDefinition();
        cd.setName("web");
        cd.setImage("web:1");
        service.registerTaskDefinition("ec2-placement-fam", List.of(cd), null, null, null, null, null, List.of(),
                REGION);
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
