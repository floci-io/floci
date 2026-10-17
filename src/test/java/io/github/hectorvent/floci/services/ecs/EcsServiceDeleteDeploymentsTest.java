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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.testing.ThreadStates.awaitBlockedOrDone;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
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

    @Test
    void anUpdateThatPassedItsStatusCheckCannotRestoreRecordsAfterDelete() throws Exception {
        EcsService service = spy(newMockModeService());
        service.createCluster(CLUSTER, REGION);
        registerTaskDef(service);
        service.createService(CLUSTER, "racy", "del-fam", 0, LaunchType.FARGATE, List.of(), null, REGION);

        CountDownLatch passedCheck = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> updater = new AtomicReference<>();
        doAnswer(inv -> {
            if (Thread.currentThread() == updater.get()) {
                passedCheck.countDown();
                release.await();
            }
            return inv.callRealMethod();
        }).when(service).currentServiceDeployment(any());

        Thread update = worker(() -> service.updateService(CLUSTER, "racy", null, null, null, null, true, REGION));
        Thread delete = worker(() -> service.deleteService(CLUSTER, "racy", true, REGION));
        updater.set(update);
        try {
            update.start();
            assertTrue(passedCheck.await(10, TimeUnit.SECONDS));
            delete.start();
            awaitBlockedOrDone(delete);
        } finally {
            release.countDown();
            joinAll(update, delete);
        }

        assertEquals(List.of(), service.listServiceDeployments("racy", CLUSTER, null, REGION),
                "an update racing a delete leaves no deployment behind");
        assertEquals("INACTIVE",
                service.describeServices(CLUSTER, List.of("racy"), REGION).getFirst().getStatus());
    }

    @Test
    void aCreateRacingADeleteKeepsItsOwnDeployment() throws Exception {
        EcsService service = newMockModeService();
        service.createCluster(CLUSTER, REGION);
        registerTaskDef(service);
        service.createService(CLUSTER, "swap", "del-fam", 1, LaunchType.FARGATE, List.of(), null, REGION);
        service.reconcileServices();

        Thread delete = worker(() -> service.deleteService(CLUSTER, "swap", true, REGION));
        Thread create = worker(() ->
                service.createService(CLUSTER, "swap", "del-fam", 0, LaunchType.FARGATE, List.of(), null, REGION));
        // Runs while the delete is between marking the service INACTIVE and cleaning up its records.
        doAnswer(inv -> {
            if (Thread.currentThread() == delete && create.getState() == Thread.State.NEW) {
                create.start();
                awaitBlockedOrDone(create);
            }
            return null;
        }).when(containerManager).releaseTaskNetwork(any(), any());
        delete.start();
        joinAll(delete, create);

        assertEquals("ACTIVE", service.describeServices(CLUSTER, List.of("swap"), REGION).getFirst().getStatus());
        assertEquals(1, service.listServiceDeployments("swap", CLUSTER, null, REGION).size(),
                "the recreated service keeps the deployment it recorded");
    }

    @Test
    void aResetDuringADeleteDoesNotLetARecreateLoseItsDeployment() throws Exception {
        EcsService service = newMockModeService();
        service.createCluster(CLUSTER, REGION);
        registerTaskDef(service);
        service.createService(CLUSTER, "reset", "del-fam", 1, LaunchType.FARGATE, List.of(), null, REGION);
        service.reconcileServices();

        CountDownLatch stopping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread delete = worker(() -> service.deleteService(CLUSTER, "reset", true, REGION));
        Thread create = worker(() ->
                service.createService(CLUSTER, "reset", "del-fam", 0, LaunchType.FARGATE, List.of(), null, REGION));
        // Parks the delete between marking the service INACTIVE and cleaning up its records.
        doAnswer(inv -> {
            if (Thread.currentThread() == delete) {
                stopping.countDown();
                release.await();
            }
            return null;
        }).when(containerManager).releaseTaskNetwork(any(), any());
        try {
            delete.start();
            assertTrue(stopping.await(10, TimeUnit.SECONDS), "delete reached task stop");
            service.clear();
            create.start();
            awaitBlockedOrDone(create);
        } finally {
            release.countDown();
            joinAll(delete, create);
        }

        assertEquals("ACTIVE", service.describeServices(CLUSTER, List.of("reset"), REGION).getFirst().getStatus());
        assertEquals(1, service.listServiceDeployments("reset", CLUSTER, null, REGION).size(),
                "a service recreated after a reset keeps its deployment when the older delete resumes");
    }

    private final ConcurrentLinkedQueue<Throwable> workerFailures = new ConcurrentLinkedQueue<>();

    /** A thread that records what it throws, so a failed worker cannot leave a passing test behind. */
    private Thread worker(Runnable body) {
        Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable e) {
                workerFailures.add(e);
            }
        });
        t.setDaemon(true);
        return t;
    }

    private void joinAll(Thread... threads) throws InterruptedException {
        for (Thread t : threads) {
            t.join(10_000);
        }
        for (Thread t : threads) {
            if (t.isAlive()) {
                t.interrupt();
            }
        }
        for (Thread t : threads) {
            assertTrue(!t.isAlive(), "worker thread finished");
        }
        assertTrue(workerFailures.isEmpty(), "workers threw: " + workerFailures);
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

    private final EcsContainerManager containerManager = mock(EcsContainerManager.class);

    private EcsService newMockModeService() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(true);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsService service = new EcsService(
                new RegionResolver(REGION, "000000000000"),
                containerManager,
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
