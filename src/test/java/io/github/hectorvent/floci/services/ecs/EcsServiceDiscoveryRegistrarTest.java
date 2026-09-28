package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.services.cloudmap.CloudMapService;
import io.github.hectorvent.floci.services.cloudmap.model.Instance;
import io.github.hectorvent.floci.services.cloudmap.model.Operation;
import io.github.hectorvent.floci.services.cloudmap.model.Service;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.NetworkBinding;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Component test for {@link EcsServiceDiscoveryRegistrar}: drives register/deregister directly
 * with a synthetic task against a real {@link CloudMapService}, mirroring
 * {@link EcsLoadBalancerRegistrarTest}. Also asserts the point of the whole exercise, that a
 * registered task is what makes {@code <service>.<namespace>} resolve.
 */
@QuarkusTest
class EcsServiceDiscoveryRegistrarTest {

    private static final String REGION = "us-east-1";

    @Inject
    EcsServiceDiscoveryRegistrar registrar;

    @Inject
    CloudMapService cloudMapService;

    @Test
    void registerTaskMakesTheServiceNameResolve() {
        String namespace = uniqueName("svcdisc") + ".internal";
        Service cloudMapSvc = createDnsService(namespace, "valkey");
        EcsTask task = task("172.31.0.6", "valkey", 6379, 6379);
        EcsServiceModel svc = serviceWithRegistry(cloudMapSvc.getArn(), "valkey", 6379);

        registrar.registerTask(task, svc, REGION);

        List<Instance> instances = cloudMapService.listInstances(cloudMapSvc.getId());
        assertEquals(1, instances.size());
        assertEquals("172.31.0.6", instances.getFirst().getAttributes().get("AWS_INSTANCE_IPV4"));
        assertEquals("6379", instances.getFirst().getAttributes().get("AWS_INSTANCE_PORT"));
        assertEquals(List.of("172.31.0.6"), cloudMapService.resolveDnsName("valkey." + namespace));
    }

    @Test
    void deregisterTaskStopsTheServiceNameResolving() {
        String namespace = uniqueName("svcdisc") + ".internal";
        Service cloudMapSvc = createDnsService(namespace, "auth");
        EcsTask task = task("172.31.0.7", "auth", 8080, 8080);
        EcsServiceModel svc = serviceWithRegistry(cloudMapSvc.getArn(), "auth", 8080);
        registrar.registerTask(task, svc, REGION);

        registrar.deregisterTask(task, REGION);

        assertTrue(cloudMapService.listInstances(cloudMapSvc.getId()).isEmpty());
        assertTrue(cloudMapService.resolveDnsName("auth." + namespace).isEmpty());
    }

    @Test
    void deregisterTaskUsesItsRegisteredServiceAfterTheEcsServiceChanges() {
        String namespace = uniqueName("svcdisc") + ".internal";
        Service original = createDnsService(namespace, "original");
        Service replacement = cloudMapService.createService("replacement", original.getNamespaceId(),
                null, null, null, null, null, null, Map.of(), REGION);
        EcsTask task = task("172.31.0.7", "app", 8080, 8080);
        EcsServiceModel svc = serviceWithRegistry(original.getArn(), "app", 8080);
        registrar.registerTask(task, svc, REGION);
        assertEquals(List.of(original.getId()), task.getServiceDiscoveryServiceIds());

        svc.setServiceRegistries(List.of(Map.of("registryArn", replacement.getArn())));
        registrar.deregisterTask(task, REGION);

        assertTrue(cloudMapService.listInstances(original.getId()).isEmpty());
        assertTrue(cloudMapService.listInstances(replacement.getId()).isEmpty());
        assertTrue(task.getServiceDiscoveryServiceIds().isEmpty());
    }

    @Test
    void registerTaskAdvertisesThePublishedHostPortInBridgeMode() {
        String namespace = uniqueName("svcdisc") + ".internal";
        Service cloudMapSvc = createDnsService(namespace, "api");
        // No task-level ENI address: a bridge-mode task is reached on its published host port.
        EcsTask task = task(null, "api", 8080, 32768);
        EcsServiceModel svc = serviceWithRegistry(cloudMapSvc.getArn(), "api", 8080);

        registrar.registerTask(task, svc, REGION);

        Instance instance = cloudMapService.listInstances(cloudMapSvc.getId()).getFirst();
        assertEquals("32768", instance.getAttributes().get("AWS_INSTANCE_PORT"));
    }

    @Test
    void registerTaskRecordsTheMetadataAttributesAwsAdds() {
        String namespace = uniqueName("svcdisc") + ".internal";
        Service cloudMapSvc = createDnsService(namespace, "billing");
        EcsTask task = task("172.31.0.10", "billing", 9090, 9090);
        task.setAvailabilityZone("us-east-1a");
        task.setClusterArn("arn:aws:ecs:" + REGION + ":000000000000:cluster/payments");
        task.setTaskDefinitionArn("arn:aws:ecs:" + REGION + ":000000000000:task-definition/billing-td:7");
        EcsServiceModel svc = serviceWithRegistry(cloudMapSvc.getArn(), "billing", 9090);
        svc.setServiceName("billing-svc");

        registrar.registerTask(task, svc, REGION);

        Map<String, String> attributes = cloudMapService.listInstances(cloudMapSvc.getId())
                .getFirst().getAttributes();
        assertEquals("us-east-1a", attributes.get("AVAILABILITY_ZONE"));
        assertEquals(REGION, attributes.get("REGION"));
        assertEquals("billing-svc", attributes.get("ECS_SERVICE_NAME"));
        assertEquals("payments", attributes.get("ECS_CLUSTER_NAME"));
        assertEquals("billing-td", attributes.get("ECS_TASK_DEFINITION_FAMILY"));
    }

    @Test
    void registryWithAnUnusableArnIsIgnored() {
        EcsTask task = task("172.31.0.8", "orphan", 80, 80);
        EcsServiceModel svc = serviceWithRegistry("not-an-arn", "orphan", 80);

        registrar.registerTask(task, svc, REGION);
        registrar.deregisterTask(task, REGION);
    }

    @Test
    void hasRegistriesIsFalseForAServiceThatDeclaredNone() {
        EcsServiceModel svc = new EcsServiceModel();
        assertTrue(!registrar.hasRegistries(svc));

        svc.setServiceRegistries(List.of());
        assertTrue(!registrar.hasRegistries(svc));
    }

    private Service createDnsService(String namespaceName, String serviceName) {
        Operation operation = cloudMapService.createPrivateDnsNamespace(
                namespaceName, "vpc-svcdisc", null, null, Map.of(), REGION);
        String namespaceId = operation.getTargets().get("NAMESPACE");
        return cloudMapService.createService(serviceName, namespaceId, null, null,
                null, null, null, null, Map.of(), REGION);
    }

    private EcsServiceModel serviceWithRegistry(String registryArn, String containerName, int containerPort) {
        EcsServiceModel svc = new EcsServiceModel();
        svc.setServiceRegistries(List.of(Map.of(
                "registryArn", registryArn,
                "containerName", containerName,
                "containerPort", containerPort)));
        return svc;
    }

    private EcsTask task(String privateIpAddress, String containerName, int containerPort, int hostPort) {
        Container container = new Container();
        container.setName(containerName);
        container.setNetworkBindings(List.of(
                new NetworkBinding("0.0.0.0", containerPort, hostPort, "tcp")));
        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:" + REGION + ":000000000000:task/c/" + uniqueName("task"));
        task.setPrivateIpAddress(privateIpAddress);
        task.setContainers(List.of(container));
        return task;
    }

    private static String uniqueName(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }
}
