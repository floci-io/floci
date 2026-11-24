package io.floci.aws.services.ecs;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.HostConfig;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ecs.model.AwsVpcConfiguration;
import io.floci.aws.services.ecs.model.Container;
import io.floci.aws.services.ecs.model.ContainerDefinition;
import io.floci.aws.services.ecs.model.EcsTask;
import io.floci.aws.services.ecs.model.LaunchType;
import io.floci.aws.services.ecs.model.NetworkConfiguration;
import io.floci.aws.services.ecs.model.NetworkMode;
import io.floci.aws.services.ecs.model.PortMapping;
import io.floci.aws.services.ecs.model.TaskDefinition;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.testing.EcsTaskWaits.awaitLastStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * With a shared task network and no security-group enforcement, the containers of an awsvpc task
 * reach each other on localhost, and every one of them answers at the task's ENI address.
 */
@QuarkusTest
@TestProfile(EcsSharedTaskNetworkDockerIntegrationTest.SharedTaskNetworkProfile.class)
class EcsSharedTaskNetworkDockerIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String SUBNET = Ec2Service.defaultSubnetId("us-east-1", "a", "000000000000");
    private static final int FRONT_PORT = 8080;
    private static final int BACKEND_PORT = 8081;

    /** Docker-backed ECS and VPC networks with a shared task network, enforcement left off. */
    public static final class SharedTaskNetworkProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ecs.mock", "false",
                    "floci.services.ec2.mock", "false",
                    "floci.services.ec2.vpc-networks.enabled", "true",
                    "floci.network.security-group-enforcement.enabled", "false",
                    "floci.services.ecs.shared-task-network", "true",
                    "floci.docker.resource-namespace", "ecs-shared-netns-it");
        }
    }

    @Inject
    EcsService ecsService;

    @Inject
    Ec2Service ec2Service;

    @Inject
    VpcNetworkManager vpcNetworkManager;

    @Inject
    DockerClient dockerClient;

    @BeforeEach
    void requireDocker() throws InterruptedException {
        assumeTrue(DockerAvailability.isAvailable(), "Docker must be available for the shared task network test");
    }

    @Test
    void containersOfATaskReachEachOtherOnLocalhostAndAllAnswerAtTheEniAddress() throws InterruptedException {
        String clusterName = unique("shared-netns");
        ecsService.createCluster(clusterName, REGION);
        EcsTask task = ecsService.runTask(clusterName, registerFrontAndBackend().getTaskDefinitionArn(), 1,
                LaunchType.FARGATE, null, null, List.of(), awsvpcNetwork(), REGION).getFirst();
        String vpcId = ec2Service.describeSubnets(REGION, List.of(SUBNET), Map.of()).getFirst().getVpcId();
        String network = null;
        String probe = null;
        try {
            String taskArn = task.getTaskArn();
            EcsTask described = awaitLastStatus(
                    () -> ecsService.describeTasks(clusterName, List.of(taskArn), REGION).getFirst(),
                    EcsTask::getLastStatus, Duration.ofMinutes(2), "RUNNING", "STOPPED");
            assertEquals("RUNNING", described.getLastStatus(), "the task must start: " + described.getStoppedReason());
            Container front = container(described, "front");
            Container backend = container(described, "backend");

            String frontMode = dockerClient.inspectContainerCmd(front.getDockerId()).exec().getHostConfig().getNetworkMode();
            String backendMode = dockerClient.inspectContainerCmd(backend.getDockerId()).exec().getHostConfig()
                    .getNetworkMode();
            assertTrue(frontMode.startsWith("container:"), "the front container joins a namespace: " + frontMode);
            assertEquals(frontMode, backendMode, "both containers join the same namespace");
            String helperId = frontMode.substring("container:".length());
            assertNotEquals(front.getDockerId(), helperId, "a helper holds the namespace, not a task container");

            ContainerExec.Result local = ContainerExec.run(dockerClient, front.getDockerId(), new String[] {"sh", "-c",
                    "for i in 1 2 3 4 5 6 7 8 9 10; do wget -q -O- -T 1 http://127.0.0.1:" + BACKEND_PORT
                            + "/ && exit 0; sleep 0.2; done; exit 1"}, 30);
            assertEquals(0, local.exitCode(), "front must reach backend on localhost: " + local.summary());
            assertEquals("backend", local.stdout().trim());

            String address = described.getPrivateIpAddress();
            assertNotNull(address, "an awsvpc task must report its ENI address");
            network = vpcNetworkManager.networkNameFor("000000000000", REGION, vpcId).orElseThrow();
            ContainerNetwork onVpc = dockerClient.inspectContainerCmd(helperId).exec()
                    .getNetworkSettings().getNetworks().get(network);
            assertNotNull(onVpc, "the helper must join the VPC network " + network);
            assertEquals(address, onVpc.getIpAddress(), "the helper must hold the task's ENI address");

            // backend maps no port, so it would not hold the ENI address with a namespace of its own.
            probe = dockerClient.createContainerCmd(TestImages.BUSYBOX)
                    .withHostConfig(HostConfig.newHostConfig().withNetworkMode(network))
                    .withCmd("sh", "-c", "for i in 1 2 3 4 5 6 7 8 9 10; do "
                            + "[ \"$(wget -q -O- -T 1 http://" + address + ":" + BACKEND_PORT + "/)\" = backend ] "
                            + "&& exit 0; sleep 0.1; done; exit 1")
                    .exec().getId();
            dockerClient.startContainerCmd(probe).exec();
            assertEquals(0, dockerClient.waitContainerCmd(probe).start().awaitStatusCode(60, TimeUnit.SECONDS),
                    "a peer on the VPC network must reach backend at " + address);

            ecsService.stopTask(clusterName, task.getTaskArn(), "shared task network test", REGION);
            task = null;
            assertThrows(NotFoundException.class, () -> dockerClient.inspectContainerCmd(helperId).exec(),
                    "a stopped task's helper must be removed");
        } finally {
            if (task != null) {
                ecsService.stopTask(clusterName, task.getTaskArn(), "test teardown", REGION);
            }
            if (probe != null) {
                dockerClient.removeContainerCmd(probe).withForce(true).exec();
            }
            if (network != null) {
                // Also disconnects the endpoint Floci gives itself on the network when it runs in a container.
                vpcNetworkManager.deleteVpcNetwork("000000000000", REGION, vpcId);
            }
        }
    }

    private TaskDefinition registerFrontAndBackend() {
        ContainerDefinition front = server("front", FRONT_PORT);
        front.setPortMappings(List.of(new PortMapping(FRONT_PORT)));
        ContainerDefinition backend = server("backend", BACKEND_PORT);
        return ecsService.registerTaskDefinition(unique("shared-netns-td"), List.of(front, backend),
                NetworkMode.awsvpc, null, null, null, null, null, REGION);
    }

    private static ContainerDefinition server(String name, int port) {
        ContainerDefinition container = new ContainerDefinition();
        container.setName(name);
        container.setImage(TestImages.BUSYBOX);
        container.setCommand(List.of("sh", "-c", "mkdir -p /www && echo " + name
                + " > /www/index.html && exec httpd -f -p " + port + " -h /www"));
        return container;
    }

    private static Container container(EcsTask task, String name) {
        return task.getContainers().stream()
                .filter(container -> name.equals(container.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no container " + name + " in " + task.getContainers()));
    }

    private static NetworkConfiguration awsvpcNetwork() {
        AwsVpcConfiguration awsvpc = new AwsVpcConfiguration();
        awsvpc.setSubnets(List.of(SUBNET));
        NetworkConfiguration networkConfiguration = new NetworkConfiguration();
        networkConfiguration.setAwsvpcConfiguration(awsvpc);
        return networkConfiguration;
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
