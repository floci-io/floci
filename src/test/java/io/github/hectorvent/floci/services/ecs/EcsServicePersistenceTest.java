package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.Attribute;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsCluster;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
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
                0, LaunchType.FARGATE, List.of(), null, Map.of("team", "payments"), REGION);
        first.putAttributes("app-cluster",
                List.of(new Attribute("stack", "prod", "container-instance", "ci-1")), REGION);
        first.putAccountSetting("containerInsights", "enabled");

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

        assertEquals("prod", reloaded.listAttributes("app-cluster", null, "stack", null, REGION)
                .getFirst().value());
        assertEquals("enabled", reloaded.listAccountSettings("containerInsights", null)
                .getFirst().getValue());

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
        first.putAttributes("c1", List.of(new Attribute("a", "1", "container-instance", "t1")), REGION);
        first.deleteAttributes("c1", List.of(new Attribute("a", "1", "container-instance", "t1")), REGION);

        EcsService reloaded = serviceWithStorage(storage);

        Map<String, String> tags = reloaded.listTagsForResource(cluster.getClusterArn());
        assertEquals("test", tags.get("env"));
        assertFalse(tags.containsKey("owner"), "untagged key must not reappear after restart");
        assertTrue(reloaded.listAttributes("c1", null, "a", null, REGION).isEmpty(),
                "deleted attribute must not reappear after restart");
    }

    private static ContainerDefinition container(String name, String image) {
        ContainerDefinition cd = new ContainerDefinition();
        cd.setName(name);
        cd.setImage(image);
        return cd;
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
                storage);
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
        public <V> StorageBackend<String, V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (StorageBackend<String, V>) stores.computeIfAbsent(fileName, ignored -> new InMemoryStorage<>());
        }
    }
}
