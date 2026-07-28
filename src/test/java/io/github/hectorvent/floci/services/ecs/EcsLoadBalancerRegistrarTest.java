package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.EcsLoadBalancer;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.NetworkBinding;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import io.github.hectorvent.floci.services.elbv2.model.TargetHealth;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Inject
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
        task.setTaskArn("arn:aws:ecs:" + REGION + ":000000000000:task/c/regtest");
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
}
