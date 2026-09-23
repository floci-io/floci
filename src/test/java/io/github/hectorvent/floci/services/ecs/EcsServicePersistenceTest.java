package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.Attribute;
import io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsCluster;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that ECS durable resources survive a restart by backing the service maps with a
 * {@link StorageFactory} whose backends are shared between two service instances (the second
 * instance simulates a process restart reloading from the same persistent store).
 */
class EcsServicePersistenceTest {

    private static final String REGION = "us-west-2";

    @Test
    void durableResourcesAndTagsSurviveRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        EcsService first = serviceWithStorage(storage);
        EcsCluster cluster = first.createCluster("app-cluster", Map.of("owner", "platform"), REGION);
        TaskDefinition td = first.registerTaskDefinition("web", List.of(container("app", "nginx:latest")),
                NetworkMode.awsvpc, "256", "512", null, null, List.of("FARGATE"),
                Map.of("tier", "web"), REGION);
        EcsServiceModel svc = first.createService("app-cluster", "web-svc", td.getTaskDefinitionArn(),
                0, LaunchType.FARGATE, List.of(), awsvpcConfiguration(), Map.of("team", "payments"), REGION);
        // An attribute names a container instance of the cluster, as it must on AWS.
        String instanceArn = first.registerContainerInstance("app-cluster", null, List.of(), REGION)
                .getContainerInstanceArn();
        first.putAttributes("app-cluster",
                List.of(new Attribute("stack", "prod", "container-instance", instanceArn)), REGION);
        first.putAccountSetting("containerInsights", "enabled", null);

        // Simulate restart: a fresh instance reloading from the same shared store.
        EcsService reloaded = serviceWithStorage(storage);

        EcsCluster reloadedCluster = reloaded.describeClusters(List.of("app-cluster"), REGION).getFirst();
        assertEquals(cluster.getClusterArn(), reloadedCluster.getClusterArn());
        assertEquals("platform", reloadedCluster.getTags().get("owner"));

        TaskDefinition reloadedTd = reloaded.describeTaskDefinition(td.getTaskDefinitionArn(), REGION);
        assertEquals("ACTIVE", reloadedTd.getStatus());
        assertEquals("web", reloadedTd.getTags().get("tier"));

        EcsServiceModel reloadedSvc = reloaded.describeServices("app-cluster",
                List.of(svc.getServiceArn()), REGION).getFirst();
        assertEquals("payments", reloadedSvc.getTags().get("team"));

        assertEquals("prod", reloaded.listAttributes("app-cluster", "container-instance", "stack",
                null, null, null, REGION).attributes().getFirst().value());
        assertEquals("enabled", reloaded.listAccountSettings("containerInsights", null, null,
                false, null, null).settings().getFirst().value());

        // latestRevisions persisted: registering the same family again yields the next revision.
        assertEquals(2, reloaded.registerTaskDefinition("web", List.of(container("app", "nginx:latest")),
                NetworkMode.awsvpc, "256", "512", null, null, List.of("FARGATE"), null, REGION).getRevision());
    }

    @Test
    void tagAndAttributeMutationsArePersistedAfterRestart() {
        SharedStorageFactory storage = new SharedStorageFactory();

        EcsService first = serviceWithStorage(storage);
        EcsCluster cluster = first.createCluster("c1", Map.of(), REGION);
        first.tagResource(cluster.getClusterArn(), Map.of("env", "test", "owner", "qa"));
        first.untagResource(cluster.getClusterArn(), List.of("owner"));
        String instanceArn = first.registerContainerInstance("c1", null, List.of(), REGION)
                .getContainerInstanceArn();
        first.putAttributes("c1", List.of(new Attribute("a", "1", "container-instance", instanceArn)), REGION);
        first.deleteAttributes("c1", List.of(new Attribute("a", "1", "container-instance", instanceArn)), REGION);

        EcsService reloaded = serviceWithStorage(storage);

        Map<String, String> tags = reloaded.listTagsForResource(cluster.getClusterArn());
        assertEquals("test", tags.get("env"));
        assertFalse(tags.containsKey("owner"), "untagged key must not reappear after restart");
        assertTrue(reloaded.listAttributes("c1", "container-instance", "a", null, null, null, REGION)
                        .attributes().isEmpty(),
                "deleted attribute must not reappear after restart");
    }

    private static ContainerDefinition container(String name, String image) {
        ContainerDefinition cd = new ContainerDefinition();
        cd.setName(name);
        cd.setImage(image);
        return cd;
    }

    /** An awsvpc service needs the subnets its tasks get an ENI in, as it does on AWS. */
    private static NetworkConfiguration awsvpcConfiguration() {
        AwsVpcConfiguration awsvpc = new AwsVpcConfiguration();
        awsvpc.setSubnets(List.of(Ec2Service.defaultSubnetId(REGION, "a")));
        NetworkConfiguration networkConfiguration = new NetworkConfiguration();
        networkConfiguration.setAwsvpcConfiguration(awsvpc);
        return networkConfiguration;
    }

    private static EcsService serviceWithStorage(StorageFactory storage) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(true);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        EcsService service = new EcsService(
                new RegionResolver(REGION, "000000000000"),
                mock(EcsContainerManager.class),
                config,
                mock(EcsLoadBalancerRegistrar.class),
                storage,
                null);
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
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName, ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
        }
    }
}
