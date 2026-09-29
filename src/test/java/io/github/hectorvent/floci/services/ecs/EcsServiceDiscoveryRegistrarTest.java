package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.services.cloudmap.CloudMapService;
import io.github.hectorvent.floci.services.cloudmap.model.Instance;
import io.github.hectorvent.floci.services.cloudmap.model.Operation;
import io.github.hectorvent.floci.services.cloudmap.model.Service;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.EcsTaskAddress;
import io.github.hectorvent.floci.services.ecs.model.NetworkBinding;
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

    @InjectSpy
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

    @Test
    void releaseRecordedInstancesKeepsInstancesRegisteredByHand() {
        String namespace = uniqueName("svcdisc") + ".internal";
        Service cloudMapSvc = createDnsService(namespace, "web");
        EcsServiceModel web = serviceWithRegistry(cloudMapSvc.getArn(), "web", 8080);
        web.setServiceName("web-svc");
        registrar.registerTask(taskInCluster("172.31.0.11", "payments"), web, REGION);
        registrar.registerTask(taskInCluster("172.31.0.12", "payments"), web, REGION);
        cloudMapService.registerInstance(cloudMapSvc.getId(), "registered-by-hand", null,
                Map.of("AWS_INSTANCE_IPV4", "172.31.0.15",
                        "ECS_SERVICE_NAME", "web-svc", "ECS_CLUSTER_NAME", "payments"), REGION);

        registrar.releaseRecordedInstances();

        List<String> remaining = cloudMapService.listInstances(cloudMapSvc.getId()).stream()
                .map(Instance::getInstanceId)
                .toList();
        assertEquals(List.of("registered-by-hand"), remaining);
    }

    @Test
    void releaseRecordedInstancesReleasesAnInstanceWhoseRegistrationWasCutShort() {
        String namespace = uniqueName("svcdisc") + ".internal";
        Service cloudMapSvc = createDnsService(namespace, "killed");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new ProcessKilled();
        }).when(cloudMapService).registerInstance(eq(cloudMapSvc.getId()), any(), any(), any(), any());

        assertThrows(ProcessKilled.class, () -> registrar.registerTask(task("172.31.0.13", "killed", 8080, 8080),
                serviceWithRegistry(cloudMapSvc.getArn(), "killed", 8080), REGION));
        registrar.releaseRecordedInstances();

        assertTrue(cloudMapService.listInstances(cloudMapSvc.getId()).isEmpty(),
                "an instance registered just before the process died should be released");
    }

    @Test
    void evictUnrecordedInstancesRemovesAStaleInstanceAtTheTasksAddress() {
        String namespace = uniqueName("svcdisc") + ".internal";
        Service auth = createDnsService(namespace, "auth");
        Service document = cloudMapService.createService("document", auth.getNamespaceId(),
                null, null, null, null, null, null, Map.of(), REGION);
        // Left by an auth task of a run that kept no record; the address now belongs to a document task.
        cloudMapService.registerInstance(auth.getId(), "bdf778f0", null,
                Map.of("AWS_INSTANCE_IPV4", "172.31.0.21"), REGION);
        cloudMapService.registerInstance(auth.getId(), "elsewhere", null,
                Map.of("AWS_INSTANCE_IPV4", "172.31.0.17"), REGION);
        EcsTask documentTask = task("172.31.0.21", "document", 8080, 8080);
        registrar.registerTask(documentTask, serviceWithRegistry(document.getArn(), "document", 8080), REGION);

        registrar.evictUnrecordedInstances(documentTask, List.of(auth.getId(), document.getId()),
                List.of(new EcsTaskAddress("172.31.0.21", null)), REGION);

        assertEquals(List.of("elsewhere"), cloudMapService.listInstances(auth.getId()).stream()
                .map(Instance::getInstanceId).toList());
        assertEquals(1, cloudMapService.listInstances(document.getId()).size(),
                "the task's own recorded instance should stay");
        assertEquals(List.of("172.31.0.17"), cloudMapService.resolveDnsName("auth." + namespace));
    }

    @Test
    void evictUnrecordedInstancesMatchesALoopbackAddressOnlyByItsPort() {
        String namespace = uniqueName("svcdisc") + ".internal";
        Service cloudMapSvc = createDnsService(namespace, "api");
        cloudMapService.registerInstance(cloudMapSvc.getId(), "stale", null,
                Map.of("AWS_INSTANCE_IPV4", "127.0.0.1", "AWS_INSTANCE_PORT", "32768"), REGION);
        cloudMapService.registerInstance(cloudMapSvc.getId(), "other-port", null,
                Map.of("AWS_INSTANCE_IPV4", "127.0.0.1", "AWS_INSTANCE_PORT", "32769"), REGION);

        registrar.evictUnrecordedInstances(task(null, "api", 8080, 32768), List.of(cloudMapSvc.getId()),
                List.of(EcsTaskAddress.of("127.0.0.1", 32768)), REGION);

        assertEquals(List.of("other-port"), cloudMapService.listInstances(cloudMapSvc.getId()).stream()
                .map(Instance::getInstanceId).toList());
    }

    @Test
    void evictUnrecordedInstancesMatchesAnEniAddressOnlyInItsOwnVpc() {
        String namespace = uniqueName("svcdisc") + ".internal";
        Service cloudMapSvc = createDnsService(namespace, "api");
        cloudMapService.registerInstance(cloudMapSvc.getId(), "by-hand", null,
                Map.of("AWS_INSTANCE_IPV4", "10.0.1.5"), REGION);

        registrar.evictUnrecordedInstances(task("10.0.1.5", "api", 8080, 8080), List.of(cloudMapSvc.getId()),
                List.of(new EcsTaskAddress("10.0.1.5", null, "vpc-elsewhere")), REGION);
        assertEquals(List.of("by-hand"), cloudMapService.listInstances(cloudMapSvc.getId()).stream()
                .map(Instance::getInstanceId).toList(), "an instance in another VPC's namespace should stay");

        registrar.evictUnrecordedInstances(task("10.0.1.5", "api", 8080, 8080), List.of(cloudMapSvc.getId()),
                List.of(new EcsTaskAddress("10.0.1.5", null, "vpc-svcdisc")), REGION);
        assertTrue(cloudMapService.listInstances(cloudMapSvc.getId()).isEmpty(),
                "an instance at the task's ENI address in its own VPC should be evicted");
    }

    /** Stands in for the process dying between the registration and anything after it. */
    private static final class ProcessKilled extends Error {
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

    private EcsTask taskInCluster(String privateIpAddress, String clusterName) {
        EcsTask task = task(privateIpAddress, "web", 8080, 8080);
        task.setClusterArn("arn:aws:ecs:" + REGION + ":000000000000:cluster/" + clusterName);
        return task;
    }

    private static String uniqueName(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }
}
