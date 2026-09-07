package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.ContainerInstance;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A task's service ownership comes from the reconciler-stamped {@code owningServiceArn}, never
 * from the caller-supplied {@code group}: {@code RunTask}/{@code StartTask} let a caller pass any
 * group they like, so treating it as an ownership claim let an unrelated task be counted toward a
 * service's {@code runningCount} and stopped as a duplicate or a scale-in. Cluster scoping is
 * likewise by full ARN, not by a {@code :cluster/<name>} suffix that same-named clusters in other
 * regions or accounts also match.
 */
class EcsServiceTaskOwnershipTest {

    private static final String REGION = "us-east-1";
    private static final String OTHER_REGION = "us-west-2";

    @Test
    void spoofedGroupIsNotCountedTowardTheServiceAndIsNotStopped() {
        EcsService service = newMockModeService();
        service.createCluster("own-cluster", REGION);
        registerTaskDef(service);
        service.createService("own-cluster", "web", "own-fam", 1, LaunchType.FARGATE,
                List.of(), null, REGION);

        EcsTask intruder = service.runTask("own-cluster", "own-fam", 1, LaunchType.FARGATE,
                "web", "me", null, null, REGION).getFirst();

        // Two ticks: the first launches the service's own task, the second publishes the
        // resulting runningCount (the reconciler counts before it launches).
        service.reconcileServices();
        service.reconcileServices();

        assertEquals(2, allTasks(service).size(),
                "the service launches its own task rather than adopting the impostor as its one replica");
        List<EcsTask> owned = ownedTasks(service, "own-cluster", "web");
        assertEquals(1, owned.size(), "exactly one task is genuinely service-owned");
        assertNotEquals(intruder.getTaskArn(), owned.getFirst().getTaskArn(),
                "the service-owned task is the one the reconciler launched, not the impostor");
        assertEquals(1, service.describeServices("own-cluster", List.of("web"), REGION)
                        .getFirst().getRunningCount(),
                "runningCount counts only genuinely service-owned tasks");
        assertEquals("RUNNING", reload(service, intruder).getLastStatus(),
                "the unrelated task is left alone, not stopped as a duplicate");
    }

    @Test
    void scaleInDoesNotStopATaskThatMerelyNamesTheServiceAsItsGroup() {
        EcsService service = newMockModeService();
        service.createCluster("scale-cluster", REGION);
        registerTaskDef(service);
        service.createService("scale-cluster", "api", "own-fam", 1, LaunchType.FARGATE,
                List.of(), null, REGION);
        service.reconcileServices();

        EcsTask intruder = service.runTask("scale-cluster", "own-fam", 1, LaunchType.FARGATE,
                "api", "me", null, null, REGION).getFirst();

        service.updateService("scale-cluster", "api", null, 0, null, REGION);
        service.reconcileServices();

        assertEquals(0, ownedTasks(service, "scale-cluster", "api").size(),
                "the service drains its own task down to the new desired count");
        assertEquals("RUNNING", reload(service, intruder).getLastStatus(),
                "scale-in must not reach a task the service never launched");
    }

    @Test
    void daemonSchedulingIgnoresASpoofedGroupWhenCoveringInstances() {
        EcsService service = newMockModeService();
        service.createCluster("daemon-own", REGION);
        ContainerInstance instance = service.registerContainerInstance("daemon-own", null, List.of(), REGION);
        registerTaskDef(service);
        service.createService("daemon-own", "agent", "own-fam", 1, LaunchType.EC2,
                List.of(), null, null, "DAEMON", null, null, REGION);

        EcsTask intruder = service.startTask("daemon-own", List.of(instance.getContainerInstanceArn()),
                "own-fam", "agent", "me", REGION).getFirst();

        service.reconcileServices();

        assertEquals(2, allTasks(service).size(),
                "the daemon still places its own task on the instance the impostor sits on");
        List<EcsTask> owned = ownedTasks(service, "daemon-own", "agent");
        assertEquals(1, owned.size(), "the impostor does not count as covering the instance");
        assertNotEquals(intruder.getTaskArn(), owned.getFirst().getTaskArn(),
                "the covering task is the reconciler's own, not the impostor");
        assertEquals("RUNNING", reload(service, intruder).getLastStatus(),
                "nor is it stopped as a duplicate daemon task");
    }

    @Test
    void deletingAServiceDoesNotStopTasksItNeverLaunched() {
        EcsService service = newMockModeService();
        service.createCluster("delete-cluster", REGION);
        registerTaskDef(service);
        service.createService("delete-cluster", "worker", "own-fam", 1, LaunchType.FARGATE,
                List.of(), null, REGION);
        service.reconcileServices();

        EcsTask intruder = service.runTask("delete-cluster", "own-fam", 1, LaunchType.FARGATE,
                "worker", "me", null, null, REGION).getFirst();

        service.deleteService("delete-cluster", "worker", true, REGION);

        assertEquals("RUNNING", reload(service, intruder).getLastStatus(),
                "service teardown stops only the service's own tasks");
    }

    @Test
    void listTasksByServiceNameReturnsOnlyTasksTheServiceLaunched() {
        EcsService service = newMockModeService();
        service.createCluster("list-cluster", REGION);
        registerTaskDef(service);
        service.createService("list-cluster", "svc", "own-fam", 1, LaunchType.FARGATE,
                List.of(), null, REGION);
        service.reconcileServices();

        EcsTask intruder = service.runTask("list-cluster", "own-fam", 1, LaunchType.FARGATE,
                "svc", "me", null, null, REGION).getFirst();

        List<String> listed = service.listTasks("list-cluster", null, null, "svc", REGION);

        assertEquals(1, listed.size(), "only the service's own task is listed");
        assertTrue(listed.stream().noneMatch(arn -> arn.equals(intruder.getTaskArn())),
                "a task that merely names the service as its group is not service-owned");
    }

    @Test
    void aSameNamedClusterInAnotherRegionIsNotSweptIntoReconciliation() {
        EcsService service = newMockModeService();
        service.createCluster("shared-name", REGION);
        service.createCluster("shared-name", OTHER_REGION);
        registerTaskDef(service);
        registerTaskDef(service, OTHER_REGION);
        service.createService("shared-name", "svc", "own-fam", 1, LaunchType.FARGATE,
                List.of(), null, REGION);
        service.createService("shared-name", "svc", "own-fam", 1, LaunchType.FARGATE,
                List.of(), null, OTHER_REGION);

        service.reconcileServices();
        service.reconcileServices();

        // Each region's service owns exactly one task and neither reaches across into the
        // other's identically-named cluster: a suffix match on ":cluster/shared-name" would let
        // each service see two "running" tasks and stop the other region's as a scale-in.
        assertEquals(2, allTasks(service).size(),
                "both same-named clusters keep their own task");
        assertTrue(allTasks(service).stream().map(EcsTask::getClusterArn).distinct().count() == 2,
                "the two surviving tasks live in different cluster ARNs, one per region");
        assertEquals(1, ownedTasks(service, "shared-name", "svc").size(),
                "the us-east-1 service keeps exactly one task of its own");
    }

    @Test
    void listTasksByServiceNameWithoutAClusterDoesNotMaterializeTheDefaultCluster() {
        EcsService service = newMockModeService();

        List<String> listed = service.listTasks(null, null, null, "ghost-svc", REGION);

        assertEquals(List.of(), listed, "no cluster, so no service, so nothing to list");
        assertEquals(List.of(), service.listClusters(REGION),
                "ListTasks is a read and must not create the default cluster as a side effect");
    }

    private static List<EcsTask> allTasks(EcsService service) {
        return service.describeTasks(null, service.listTasks(null, null, null, null, REGION), REGION).stream()
                .filter(t -> "RUNNING".equals(t.getLastStatus()))
                .toList();
    }

    private static List<EcsTask> ownedTasks(EcsService service, String cluster, String serviceName) {
        return service.describeTasks(cluster,
                        service.listTasks(cluster, null, null, serviceName, REGION), REGION).stream()
                .filter(t -> "RUNNING".equals(t.getLastStatus()))
                .toList();
    }

    private static EcsTask reload(EcsService service, EcsTask task) {
        EcsTask found = service.describeTasks(null, List.of(task.getTaskArn()), REGION).getFirst();
        assertNotNull(found);
        return found;
    }

    private static void registerTaskDef(EcsService service) {
        registerTaskDef(service, REGION);
    }

    private static void registerTaskDef(EcsService service, String region) {
        ContainerDefinition cd = new ContainerDefinition();
        cd.setName("app");
        cd.setImage("app:1");
        service.registerTaskDefinition("own-fam", List.of(cd), null, null, null, null, null, List.of(), region);
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
