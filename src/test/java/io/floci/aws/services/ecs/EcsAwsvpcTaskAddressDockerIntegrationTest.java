package io.floci.aws.services.ecs;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.HostConfig;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ecs.model.AwsVpcConfiguration;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * An awsvpc task answers at the address DescribeTasks reports for it, for a peer on its VPC's
 * Docker network: the stand-in for a Lambda function or an instance in the same VPC.
 */
@QuarkusTest
@TestProfile(EcsAwsvpcTaskAddressDockerIntegrationTest.VpcNetworksProfile.class)
class EcsAwsvpcTaskAddressDockerIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String SUBNET = "subnet-default-us-east-1-a";
    private static final int PORT = 8080;

    /**
     * Docker-backed ECS and VPC networks, which need EC2 unmocked. Security-group enforcement keeps
     * its shipped default of off, and a namespace of its own keeps the VPC network this test
     * creates apart from any other Floci on the daemon.
     */
    public static final class VpcNetworksProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ecs.mock", "false",
                    "floci.services.ec2.mock", "false",
                    "floci.services.ec2.vpc-networks.enabled", "true",
                    "floci.network.security-group-enforcement.enabled", "false",
                    "floci.docker.resource-namespace", "ecs-awsvpc-address-it");
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
        assumeTrue(DockerAvailability.isAvailable(), "Docker must be available for the awsvpc address test");
    }

    @Test
    void peerOnTheVpcNetworkReachesTheAddressDescribeTasksReports() throws InterruptedException {
        String clusterName = unique("awsvpc-address");
        ecsService.createCluster(clusterName, REGION);
        EcsTask task = ecsService.runTask(clusterName, registerWebServer().getTaskDefinitionArn(), 1,
                LaunchType.FARGATE, null, null, List.of(), awsvpcNetwork(), REGION).getFirst();
        String vpcId = ec2Service.describeSubnets(REGION, List.of(SUBNET), Map.of()).getFirst().getVpcId();
        String network = null;
        String probe = null;
        try {
            EcsTask described = ecsService.describeTasks(clusterName, List.of(task.getTaskArn()), REGION).getFirst();
            assertEquals("RUNNING", described.getLastStatus(), "the task must start: " + described.getStoppedReason());
            String address = described.getPrivateIpAddress();
            assertNotNull(address, "an awsvpc task must report its ENI address");
            network = vpcNetworkManager.networkNameFor(REGION, vpcId).orElseThrow();
            String dockerId = described.getContainers().getFirst().getDockerId();
            ContainerNetwork onVpc = dockerClient.inspectContainerCmd(dockerId).exec()
                    .getNetworkSettings().getNetworks().get(network);
            assertNotNull(onVpc, "the task container must join the VPC network " + network);
            assertEquals(address, onVpc.getIpAddress(), "the task container must hold its ENI address");

            probe = dockerClient.createContainerCmd(TestImages.BUSYBOX)
                    .withHostConfig(HostConfig.newHostConfig().withNetworkMode(network))
                    .withCmd("sh", "-c", "for i in 1 2 3 4 5 6 7 8 9 10; do "
                            + "[ \"$(wget -q -O- -T 1 http://" + address + ":" + PORT + "/)\" = ok ] && exit 0; "
                            + "sleep 0.1; done; exit 1")
                    .exec().getId();
            dockerClient.startContainerCmd(probe).exec();
            assertEquals(0, dockerClient.waitContainerCmd(probe).start().awaitStatusCode(60, TimeUnit.SECONDS),
                    "a peer on the VPC network must reach the task at " + address);

            ecsService.stopTask(clusterName, task.getTaskArn(), "awsvpc address test", REGION);
            task = null;
            assertFalse(dockerClient.inspectNetworkCmd().withNetworkId(network).exec()
                            .getContainers().containsKey(dockerId),
                    "a stopped task must leave the VPC network");
        } finally {
            if (task != null) {
                ecsService.stopTask(clusterName, task.getTaskArn(), "test teardown", REGION);
            }
            if (probe != null) {
                dockerClient.removeContainerCmd(probe).withForce(true).exec();
            }
            if (network != null) {
                // Also disconnects the endpoint Floci gives itself on the network when it runs in a container.
                vpcNetworkManager.deleteVpcNetwork(REGION, vpcId);
            }
        }
    }

    private TaskDefinition registerWebServer() {
        ContainerDefinition web = new ContainerDefinition();
        web.setName("web");
        web.setImage(TestImages.BUSYBOX);
        web.setCommand(List.of("sh", "-c",
                "mkdir -p /www && echo ok > /www/index.html && exec httpd -f -p " + PORT + " -h /www"));
        web.setPortMappings(List.of(new PortMapping(PORT)));
        return ecsService.registerTaskDefinition(unique("awsvpc-address-td"), List.of(web), NetworkMode.awsvpc,
                null, null, null, null, null, REGION);
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
