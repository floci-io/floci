package io.github.hectorvent.floci.services.ecs;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.services.cloudmap.CloudMapService;
import io.github.hectorvent.floci.services.cloudmap.model.Operation;
import io.github.hectorvent.floci.services.cloudmap.model.Service;
import io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.CreateServiceRequest;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.ecs.model.UpdateServiceRequest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the ECS to Cloud Map wiring in docker mode, which is the only mode that registers
 * anything: {@link EcsServiceDiscoveryRegistrarTest} drives the registrar directly, so on its
 * own it stays green even if {@link EcsService} never calls it.
 *
 * <p>Nothing here touches the registrar. A service that declares {@code serviceRegistries} has a
 * real container launched for it, and the assertions are that the Cloud Map name resolves to the
 * task's address while it runs and stops resolving once the task does.
 */
@QuarkusTest
@TestProfile(EcsServiceDiscoveryDockerIntegrationTest.DockerEcsProfile.class)
class EcsServiceDiscoveryDockerIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String SUBNET = "subnet-default-us-east-1-a";
    private static final String BUSYBOX_IMAGE = "public.ecr.aws/docker/library/busybox:latest";
    private static final String CONTAINER_NAME = "app";
    private static final String CLOUD_MAP_SERVICE_NAME = "cache";
    private static final String ECS_SERVICE_NAME = "cache-svc";

    /**
     * The registration hooks only run for a task with a container behind it, so ECS runs
     * unmocked. Security-group enforcement goes back to its shipped default of off: the test
     * suite turns it on, and its helper needs rootful Docker, which is a requirement of security
     * groups rather than of service discovery.
     */
    public static final class DockerEcsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ecs.mock", "false",
                    "floci.network.security-group-enforcement.enabled", "false");
        }
    }

    @Inject
    EcsService ecsService;

    @Inject
    CloudMapService cloudMapService;

    @Inject
    DockerClient dockerClient;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker daemon must be available for the ECS service discovery test");
    }

    @Test
    void changingAServiceRegistryLeavesNoStaleDnsRecordWhenItsTaskStops() {
        String namespaceName = unique("ecsdisc") + ".internal";
        String dnsName = CLOUD_MAP_SERVICE_NAME + "." + namespaceName;
        Service cloudMapSvc = createDnsService(namespaceName);
        Service replacementRegistry = cloudMapService.createService("replacement", cloudMapSvc.getNamespaceId(),
                null, null, null, null, null, null, Map.of(), REGION);
        String clusterName = unique("disc-cluster");
        ecsService.createCluster(clusterName, REGION);
        TaskDefinition taskDef = registerTaskDefinition(unique("disc-td"));
        ecsService.createService(createServiceRequest(clusterName, taskDef, cloudMapSvc), REGION);

        String taskArn = null;
        try {
            // The reconciler is what launches a service's tasks. Driving one tick keeps the test
            // off the five second timer rather than polling for the container to show up.
            ecsService.reconcile();

            List<String> taskArns = ecsService.listTasks(clusterName, null, null, null, REGION);
            assertEquals(1, taskArns.size(), "the reconciler must launch the service's task");
            taskArn = taskArns.getFirst();
            EcsTask task = ecsService.describeTasks(clusterName, taskArns, REGION).getFirst();
            assertEquals("RUNNING", task.getLastStatus(), "the container must be up: "
                    + task.getStoppedReason());
            assertNotNull(task.getPrivateIpAddress(), "an awsvpc task must carry its ENI address");

            assertEquals(List.of(task.getPrivateIpAddress()), cloudMapService.resolveDnsName(dnsName),
                    "starting the task must register it, so the Cloud Map name resolves to it");

            // Updating the registry moves the running task to the replacement. Scaling to zero
            // keeps the reconciler from replacing it between the stop and the final assertions.
            UpdateServiceRequest update = new UpdateServiceRequest();
            update.setCluster(clusterName);
            update.setService(ECS_SERVICE_NAME);
            update.setDesiredCount(0);
            update.setServiceRegistries(List.of(Map.of(
                    "registryArn", replacementRegistry.getArn(),
                    "containerName", CONTAINER_NAME,
                    "containerPort", 6379)));
            ecsService.updateService(update, REGION);
            assertTrue(cloudMapService.listInstances(cloudMapSvc.getId()).isEmpty(),
                    "updating the registry must remove the old Cloud Map instance");
            assertEquals(List.of(task.getPrivateIpAddress()),
                    cloudMapService.resolveDnsName("replacement." + namespaceName),
                    "updating the registry must register the running task in the new service");
            ecsService.stopTask(clusterName, taskArn, "service discovery test", REGION);
            taskArn = null;

            assertTrue(cloudMapService.listInstances(cloudMapSvc.getId()).isEmpty(),
                    "stopping the task must deregister its Cloud Map instance");
            assertTrue(cloudMapService.resolveDnsName(dnsName).isEmpty(),
                    "a stopped task must not keep answering DNS");
            assertTrue(cloudMapService.listInstances(replacementRegistry.getId()).isEmpty(),
                    "stopping the task must remove its replacement registry instance");
        } finally {
            if (taskArn != null) {
                ecsService.stopTask(clusterName, taskArn, "test teardown", REGION);
            }
            ecsService.deleteService(clusterName, ECS_SERVICE_NAME, true, REGION);
        }
    }

    private CreateServiceRequest createServiceRequest(String clusterName, TaskDefinition taskDef,
                                                      Service cloudMapSvc) {
        AwsVpcConfiguration awsvpc = new AwsVpcConfiguration();
        awsvpc.setSubnets(List.of(SUBNET));
        NetworkConfiguration networkConfiguration = new NetworkConfiguration();
        networkConfiguration.setAwsvpcConfiguration(awsvpc);

        CreateServiceRequest request = new CreateServiceRequest();
        request.setCluster(clusterName);
        request.setServiceName(ECS_SERVICE_NAME);
        request.setTaskDefinition(taskDef.getTaskDefinitionArn());
        request.setDesiredCount(1);
        request.setNetworkConfiguration(networkConfiguration);
        request.setServiceRegistries(List.of(Map.of(
                "registryArn", cloudMapSvc.getArn(),
                "containerName", CONTAINER_NAME,
                "containerPort", 6379)));
        return request;
    }

    /** awsvpc, which is the mode AWS answers service discovery with A records for. */
    private TaskDefinition registerTaskDefinition(String family) {
        ContainerDefinition app = new ContainerDefinition();
        app.setName(CONTAINER_NAME);
        app.setImage(BUSYBOX_IMAGE);
        app.setCommand(List.of("sleep", "120"));
        return ecsService.registerTaskDefinition(family, List.of(app), NetworkMode.awsvpc,
                null, null, null, null, null, REGION);
    }

    private Service createDnsService(String namespaceName) {
        Operation operation = cloudMapService.createPrivateDnsNamespace(
                namespaceName, "vpc-ecsdisc", null, null, Map.of(), REGION);
        String namespaceId = operation.getTargets().get("NAMESPACE");
        return cloudMapService.createService(CLOUD_MAP_SERVICE_NAME, namespaceId, null, null,
                null, null, null, null, Map.of(), REGION);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
