package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.EcsLoadBalancer;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.EcsTaskAddress;
import io.github.hectorvent.floci.services.ecs.model.NetworkBinding;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import io.github.hectorvent.floci.services.elbv2.model.TargetHealth;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * Component test for {@link EcsLoadBalancerRegistrar}: drives the register/deregister
 * logic directly with a synthetic task and a real {@link ElbV2Service}, since ECS runs
 * in mock mode (no Docker containers) under {@code @QuarkusTest}.
 */
@QuarkusTest
class EcsLoadBalancerRegistrarTest {

    private static final String REGION = "us-east-1";

    @Inject
    EcsLoadBalancerRegistrar registrar;

    @InjectSpy
    ElbV2Service elbV2Service;

    private String createTargetGroup(String name) {
        TargetGroup tg = elbV2Service.createTargetGroup(REGION, name, "HTTP", "HTTP1",
                8080, "vpc-regtest", "ip",
                null, null, false, null, null, null, null, null, null, null, Map.of());
        return tg.getTargetGroupArn();
    }

    private EcsTask taskWithContainer(String containerName, int containerPort, int hostPort) {
        Container container = new Container();
        container.setName(containerName);
        container.setNetworkBindings(List.of(
                new NetworkBinding("0.0.0.0", containerPort, hostPort, "tcp")));
        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:" + REGION + ":000000000000:task/c/" + UUID.randomUUID());
        task.setGroup("regtest-svc");
        task.setContainers(List.of(container));
        return task;
    }

    private EcsServiceModel serviceWithLb(String tgArn, String containerName, int containerPort) {
        EcsLoadBalancer lb = new EcsLoadBalancer();
        lb.setTargetGroupArn(tgArn);
        lb.setContainerName(containerName);
        lb.setContainerPort(containerPort);
        EcsServiceModel svc = new EcsServiceModel();
        svc.setServiceName("regtest-svc");
        svc.setLoadBalancers(List.of(lb));
        return svc;
    }

    @Test
    void registerThenDeregisterTaskContainer() {
        String tgArn = createTargetGroup("reg-tg-1");
        EcsTask task = taskWithContainer("web", 8080, 34567);
        EcsServiceModel svc = serviceWithLb(tgArn, "web", 8080);

        registrar.registerTask(task, svc, REGION);

        List<TargetHealth> health = elbV2Service.describeTargetHealth(REGION, tgArn, null);
        assertEquals(1, health.size(), "one target should be registered");
        // ECS containers are reached at 127.0.0.1:<hostPort> in native mode.
        assertEquals("127.0.0.1", health.get(0).getTarget().getId());
        assertEquals(34567, health.get(0).getTarget().getPort());

        registrar.deregisterTask(task, svc, REGION);
        assertTrue(elbV2Service.describeTargetHealth(REGION, tgArn, null).isEmpty(),
                "target should be deregistered");
    }

    @Test
    void awsvpcInContainerShapedBindingRegistersContainerPort() {
        // In-container mode, awsvpc tasks are expose-only: resolveNetworkBindings
        // reports hostPort == containerPort and the registrar must register that
        // port (reachable at containerIP:containerPort). The dynamic-host-port
        // shape of native mode is covered by registerThenDeregisterTaskContainer.
        String tgArn = createTargetGroup("reg-tg-awsvpc");
        EcsTask task = taskWithContainer("web", 80, 80);
        EcsServiceModel svc = serviceWithLb(tgArn, "web", 80);

        registrar.registerTask(task, svc, REGION);

        List<TargetHealth> health = elbV2Service.describeTargetHealth(REGION, tgArn, null);
        assertEquals(1, health.size(), "one target should be registered");
        assertEquals(80, health.get(0).getTarget().getPort());

        registrar.deregisterTask(task, svc, REGION);
    }

    @Test
    void serviceWithoutLoadBalancersRegistersNothing() {
        String tgArn = createTargetGroup("reg-tg-2");
        EcsTask task = taskWithContainer("web", 8080, 40000);
        EcsServiceModel svc = new EcsServiceModel();   // no loadBalancers
        svc.setServiceName("regtest-svc");

        registrar.registerTask(task, svc, REGION);
        assertTrue(elbV2Service.describeTargetHealth(REGION, tgArn, null).isEmpty(),
                "no loadBalancers block -> no target registered");
    }

    @Test
    void containerPortMismatchRegistersNothing() {
        String tgArn = createTargetGroup("reg-tg-3");
        // task container exposes 8080, but the loadBalancers block points at 9999
        EcsTask task = taskWithContainer("web", 8080, 41000);
        EcsServiceModel svc = serviceWithLb(tgArn, "web", 9999);

        registrar.registerTask(task, svc, REGION);
        assertTrue(elbV2Service.describeTargetHealth(REGION, tgArn, null).isEmpty(),
                "no network binding for the declared containerPort -> no target");
    }

    @Test
    void releaseRecordedTargetsKeepsATargetRegisteredByHand() {
        String tgArn = createTargetGroup("reg-tg-stale");
        registrar.registerTask(taskWithContainer("web", 8080, 34567), serviceWithLb(tgArn, "web", 8080), REGION);
        elbV2Service.registerTargets(REGION, tgArn, List.of(target("172.18.0.7", 8080)));

        registrar.releaseRecordedTargets();

        List<TargetHealth> health = elbV2Service.describeTargetHealth(REGION, tgArn, null);
        assertEquals(1, health.size(), "only the target registered by hand should survive");
        assertEquals("172.18.0.7", health.get(0).getTarget().getId());
    }

    @Test
    void deregisterTaskRemovesTheTargetItRecordedEvenAfterTheBindingChanged() {
        String tgArn = createTargetGroup("reg-tg-recorded");
        EcsTask task = taskWithContainer("web", 8080, 35000);
        EcsServiceModel svc = serviceWithLb(tgArn, "web", 8080);
        registrar.registerTask(task, svc, REGION);
        task.getContainers().getFirst().setNetworkBindings(List.of(
                new NetworkBinding("0.0.0.0", 8080, 36000, "tcp")));

        registrar.deregisterTask(task, svc, REGION);

        assertTrue(elbV2Service.describeTargetHealth(REGION, tgArn, null).isEmpty(),
                "the recorded target should be deregistered");
    }

    @Test
    void releaseRecordedTargetsToleratesADeletedTargetGroup() {
        String tgArn = createTargetGroup("reg-tg-gone");
        EcsTask task = taskWithContainer("web", 8080, 37000);
        registrar.registerTask(task, serviceWithLb(tgArn, "web", 8080), REGION);
        elbV2Service.deleteTargetGroup(REGION, tgArn);

        registrar.releaseRecordedTargets();
    }

    @Test
    void releaseRecordedTargetsReleasesATargetWhoseRegistrationWasCutShort() {
        String tgArn = createTargetGroup("reg-tg-killed");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new ProcessKilled();
        }).when(elbV2Service).registerTargets(eq(REGION), eq(tgArn), any());

        assertThrows(ProcessKilled.class, () -> registrar.registerTask(
                taskWithContainer("web", 8080, 38000), serviceWithLb(tgArn, "web", 8080), REGION));
        registrar.releaseRecordedTargets();

        assertTrue(elbV2Service.describeTargetHealth(REGION, tgArn, null).isEmpty(),
                "a target registered just before the process died should be released");
    }

    @Test
    void aTargetAlreadyAtTheTasksAddressIsAdoptedAndRemovedWhenTheTaskStops() {
        // Left by a task of a run that kept no record: the next task to take the address adopts it.
        String tgArn = createTargetGroup("reg-tg-preexisting");
        elbV2Service.registerTargets(REGION, tgArn, List.of(target("127.0.0.1", 39000)));
        EcsTask task = taskWithContainer("web", 8080, 39000);
        EcsServiceModel svc = serviceWithLb(tgArn, "web", 8080);

        registrar.registerTask(task, svc, REGION);
        assertEquals(1, elbV2Service.describeTargetHealth(REGION, tgArn, null).size());
        registrar.deregisterTask(task, svc, REGION);

        assertTrue(elbV2Service.describeTargetHealth(REGION, tgArn, null).isEmpty(),
                "the target at the stopped task's address should be deregistered");
    }

    @Test
    void evictUnrecordedTargetsRemovesAStaleTargetAtTheTasksAddressFromEveryGroup() {
        String alphaTg = createTargetGroup("reg-tg-evict-alpha");
        String betaTg = createTargetGroup("reg-tg-evict-beta");
        elbV2Service.registerTargets(REGION, alphaTg, List.of(target("172.19.0.4", 8080), target("172.19.0.3", 8080)));
        elbV2Service.registerTargets(REGION, betaTg, List.of(target("172.19.0.4", 8080)));
        EcsTask betaTask = taskWithContainer("web", 8080, 8080);

        registrar.evictUnrecordedTargets(betaTask, List.of(alphaTg, betaTg),
                List.of(new EcsTaskAddress("172.19.0.4", 8080)), REGION);

        List<TargetHealth> alpha = elbV2Service.describeTargetHealth(REGION, alphaTg, null);
        assertEquals(1, alpha.size(), "only the target at another address should stay in alpha's group");
        assertEquals("172.19.0.3", alpha.get(0).getTarget().getId());
        assertTrue(elbV2Service.describeTargetHealth(REGION, betaTg, null).isEmpty(),
                "a target at the task's address and port should be evicted");
    }

    @Test
    void evictUnrecordedTargetsKeepsATargetOnAPortTheTaskDoesNotServe() {
        String tgArn = createTargetGroup("reg-tg-evict-port");
        elbV2Service.registerTargets(REGION, tgArn, List.of(target("172.19.0.5", 8080), target("172.19.0.5", 9000)));

        registrar.evictUnrecordedTargets(taskWithContainer("web", 8080, 8080), List.of(tgArn),
                List.of(new EcsTaskAddress("172.19.0.5", 8080)), REGION);

        List<TargetHealth> health = elbV2Service.describeTargetHealth(REGION, tgArn, null);
        assertEquals(1, health.size(), "the target on a port the task does not serve should stay");
        assertEquals(9000, health.get(0).getTarget().getPort());
    }

    @Test
    void evictUnrecordedTargetsKeepsATargetALiveTaskRecorded() {
        String tgArn = createTargetGroup("reg-tg-evict-recorded");
        EcsTask owner = taskWithContainer("web", 8080, 40000);
        registrar.registerTask(owner, serviceWithLb(tgArn, "web", 8080), REGION);
        EcsTask other = taskWithContainer("web", 8080, 40000);

        registrar.evictUnrecordedTargets(other, List.of(tgArn),
                List.of(EcsTaskAddress.of("127.0.0.1", 40000)), REGION);

        assertEquals(1, elbV2Service.describeTargetHealth(REGION, tgArn, null).size(),
                "a target a live task recorded should stay");
        registrar.deregisterTask(owner, serviceWithLb(tgArn, "web", 8080), REGION);
    }

    @Test
    void evictUnrecordedTargetsMatchesALoopbackAddressOnlyByItsHostPort() {
        String tgArn = createTargetGroup("reg-tg-evict-loopback");
        elbV2Service.registerTargets(REGION, tgArn, List.of(target("127.0.0.1", 41000), target("127.0.0.1", 41001)));

        registrar.evictUnrecordedTargets(taskWithContainer("web", 8080, 41000), List.of(tgArn),
                List.of(EcsTaskAddress.of("127.0.0.1", 41000)), REGION);

        List<TargetHealth> health = elbV2Service.describeTargetHealth(REGION, tgArn, null);
        assertEquals(1, health.size(), "the loopback target on another host port should stay");
        assertEquals(41001, health.get(0).getTarget().getPort());
    }

    @Test
    void evictUnrecordedTargetsToleratesADeletedTargetGroup() {
        String tgArn = createTargetGroup("reg-tg-evict-gone");
        elbV2Service.deleteTargetGroup(REGION, tgArn);

        registrar.evictUnrecordedTargets(taskWithContainer("web", 8080, 42000), List.of(tgArn),
                List.of(new EcsTaskAddress("172.19.0.9", 8080)), REGION);
    }

    /** Stands in for the process dying between the registration and anything after it. */
    private static final class ProcessKilled extends Error {
    }

    private static TargetDescription target(String id, int port) {
        TargetDescription td = new TargetDescription();
        td.setId(id);
        td.setPort(port);
        return td;
    }
}
