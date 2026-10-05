package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task definitions persisted before their storage key carried the region ({@code family:revision})
 * stay in their own region: they still resolve there, are re-filed on their next write, and are not
 * dropped when another region registers the same family and revision.
 */
class EcsTaskDefinitionLegacyKeyTest {

    private static final String REGION = "us-east-1";
    private static final String OTHER = "eu-west-1";
    private static final String FAMILY = "legacy-fam";
    private static final String LEGACY_ARN =
            "arn:aws:ecs:" + REGION + ":000000000000:task-definition/" + FAMILY + ":1";

    @Test
    void anotherRegionsRegistrationKeepsALegacyDefinition() {
        InMemoryStorageFactory storage = new InMemoryStorageFactory();
        storage.taskDefinitions().put(FAMILY + ":1", legacyDefinition());
        EcsService service = newService(storage);

        TaskDefinition other = register(service, OTHER);

        assertEquals(1, other.getRevision());
        assertEquals(List.of(LEGACY_ARN), arns(service, REGION));
        assertEquals(List.of(other.getTaskDefinitionArn()), arns(service, OTHER));
    }

    @Test
    void aLegacyDefinitionIsRefiledOnItsNextWriteAndListedOnce() {
        InMemoryStorageFactory storage = new InMemoryStorageFactory();
        storage.taskDefinitions().put(FAMILY + ":1", legacyDefinition());
        EcsService service = newService(storage);

        service.deregisterTaskDefinition(FAMILY + ":1", REGION);

        assertEquals(List.of(REGION + "::" + FAMILY + ":1"), List.copyOf(storage.taskDefinitions().keys()));
        assertEquals(List.of(LEGACY_ARN), service.listTaskDefinitions(REGION, FAMILY, "INACTIVE", null, null, null)
                .arns());
        assertEquals(2, register(service, REGION).getRevision());
    }

    @Test
    void aStoredDefinitionWithoutAnArnBreaksNoLookup() {
        InMemoryStorageFactory storage = new InMemoryStorageFactory();
        TaskDefinition unreadable = legacyDefinition();
        unreadable.setFamily(FAMILY + "-unreadable");
        unreadable.setTaskDefinitionArn(null);
        storage.taskDefinitions().put(FAMILY + "-unreadable:1", unreadable);
        EcsService service = newService(storage);

        TaskDefinition registered = register(service, REGION);

        assertEquals(List.of(registered.getTaskDefinitionArn()), arns(service, REGION));
        assertEquals(registered.getTaskDefinitionArn(),
                service.describeTaskDefinition(FAMILY + ":1", REGION).getTaskDefinitionArn());
    }

    @Test
    void aStoredDefinitionWithoutAnArnStaysInTheDefaultRegion() {
        InMemoryStorageFactory storage = new InMemoryStorageFactory();
        TaskDefinition unreadable = legacyDefinition();
        unreadable.setTaskDefinitionArn(null);
        storage.taskDefinitions().put(FAMILY + ":1", unreadable);
        EcsService service = newService(storage);

        TaskDefinition other = register(service, OTHER);

        assertEquals(1, other.getRevision());
        assertEquals(other.getTaskDefinitionArn(),
                service.describeTaskDefinition(FAMILY + ":1", OTHER).getTaskDefinitionArn());
        assertEquals(List.of(other.getTaskDefinitionArn()), arns(service, OTHER));
        assertEquals(2, register(service, REGION).getRevision());
    }

    private static TaskDefinition legacyDefinition() {
        TaskDefinition td = new TaskDefinition();
        td.setFamily(FAMILY);
        td.setRevision(1);
        td.setStatus("ACTIVE");
        td.setTaskDefinitionArn(LEGACY_ARN);
        return td;
    }

    private static TaskDefinition register(EcsService service, String region) {
        ContainerDefinition cd = new ContainerDefinition();
        cd.setName("app");
        cd.setImage("app:1");
        return service.registerTaskDefinition(FAMILY, List.of(cd), null, null, null, null, null, List.of(), region);
    }

    private static List<String> arns(EcsService service, String region) {
        return service.listTaskDefinitions(region, FAMILY, null, null, null, null).arns();
    }

    private static EcsService newService(StorageFactory storage) {
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

    private static final class InMemoryStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private InMemoryStorageFactory() {
            super(null, null);
        }

        @SuppressWarnings("unchecked")
        AccountAwareStorageBackend<TaskDefinition> taskDefinitions() {
            return (AccountAwareStorageBackend<TaskDefinition>) stores.computeIfAbsent("ecs-task-definitions.json",
                    ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
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
