package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsRequest;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.AwsVpcConfiguration;
import software.amazon.awssdk.services.ecs.model.CapacityProviderStrategyItem;
import software.amazon.awssdk.services.ecs.model.Cluster;
import software.amazon.awssdk.services.ecs.model.ClusterField;
import software.amazon.awssdk.services.ecs.model.Compatibility;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.CreateClusterRequest;
import software.amazon.awssdk.services.ecs.model.DeleteTaskDefinitionsRequest;
import software.amazon.awssdk.services.ecs.model.DeregisterTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.DescribeClustersRequest;
import software.amazon.awssdk.services.ecs.model.DescribeClustersResponse;
import software.amazon.awssdk.services.ecs.model.DescribeTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTasksRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTasksResponse;
import software.amazon.awssdk.services.ecs.model.EcsException;
import software.amazon.awssdk.services.ecs.model.KeyValuePair;
import software.amazon.awssdk.services.ecs.model.EphemeralStorage;
import software.amazon.awssdk.services.ecs.model.LaunchType;
import software.amazon.awssdk.services.ecs.model.NetworkConfiguration;
import software.amazon.awssdk.services.ecs.model.NetworkMode;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.RunTaskRequest;
import software.amazon.awssdk.services.ecs.model.StopTaskRequest;
import software.amazon.awssdk.services.ecs.model.Task;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Fargate launch type through a real SDK client: the members the generated model carries have
 * to come back populated, not just be accepted on the way in.
 */
@DisplayName("ECS Fargate launch type")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EcsFargateTest {

    private static EcsClient ecs;
    private static Ec2Client ec2;
    private static String clusterName;
    private static String family;
    private static String subnetId;
    private static String taskArn;

    @BeforeAll
    static void setup() {
        ecs = TestFixtures.ecsClient();
        ec2 = TestFixtures.ec2Client();
        String suffix = String.valueOf(System.currentTimeMillis() % 100000);
        clusterName = "sdk-fargate-cluster-" + suffix;
        family = "sdk-fargate-task-" + suffix;
        subnetId = ec2.describeSubnets(DescribeSubnetsRequest.builder().build())
                .subnets().get(0).subnetId();
        ecs.createCluster(CreateClusterRequest.builder().clusterName(clusterName).build());
    }

    @AfterAll
    static void cleanup() {
        if (ecs == null) {
            return;
        }
        try {
            if (taskArn != null) {
                ecs.stopTask(StopTaskRequest.builder().cluster(clusterName).task(taskArn).build());
            }
        } catch (EcsException ignored) {
            // The task may already have stopped; cleanup must not fail the run.
        }
        try {
            ecs.deregisterTaskDefinition(DeregisterTaskDefinitionRequest.builder()
                    .taskDefinition(family + ":1").build());
            ecs.deleteTaskDefinitions(DeleteTaskDefinitionsRequest.builder()
                    .taskDefinitions(family + ":1").build());
        } catch (EcsException ignored) {
            // Same: a failed registration leaves nothing to clean up.
        }
        ecs.close();
        ec2.close();
    }

    @Test
    @Order(1)
    @DisplayName("RegisterTaskDefinition - a Fargate definition round-trips its members")
    void registerFargateTaskDefinition() {
        TaskDefinition registered = ecs.registerTaskDefinition(RegisterTaskDefinitionRequest.builder()
                .family(family)
                .requiresCompatibilities(Compatibility.FARGATE)
                .networkMode(NetworkMode.AWSVPC)
                .cpu("512")
                .memory("1024")
                .ephemeralStorage(EphemeralStorage.builder().sizeInGiB(40).build())
                .containerDefinitions(ContainerDefinition.builder()
                        .name("app")
                        .image("nginx:alpine")
                        .essential(true)
                        .user("1000:1000")
                        .workingDirectory("/srv")
                        .readonlyRootFilesystem(true)
                        .stopTimeout(30)
                        .build())
                .build()).taskDefinition();

        assertThat(registered.compatibilitiesAsStrings()).contains("FARGATE");
        assertThat(registered.ephemeralStorage().sizeInGiB()).isEqualTo(40);
        assertThat(registered.registeredAt()).isNotNull();

        TaskDefinition described = ecs.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition(family).build()).taskDefinition();
        ContainerDefinition container = described.containerDefinitions().get(0);
        assertThat(described.ephemeralStorage().sizeInGiB()).isEqualTo(40);
        assertThat(container.user()).isEqualTo("1000:1000");
        assertThat(container.workingDirectory()).isEqualTo("/srv");
        assertThat(container.readonlyRootFilesystem()).isTrue();
        assertThat(container.stopTimeout()).isEqualTo(30);
    }

    @Test
    @Order(2)
    @DisplayName("RegisterTaskDefinition - a size Fargate does not offer is rejected")
    void rejectsAnInvalidFargateSize() {
        assertThatThrownBy(() -> ecs.registerTaskDefinition(RegisterTaskDefinitionRequest.builder()
                .family(family + "-invalid")
                .requiresCompatibilities(Compatibility.FARGATE)
                .networkMode(NetworkMode.AWSVPC)
                .cpu("256")
                .memory("4096")
                .containerDefinitions(ContainerDefinition.builder()
                        .name("app").image("nginx:alpine").build())
                .build()))
                .isInstanceOf(EcsException.class)
                .hasMessageContaining("No Fargate configuration exists");
    }

    @Test
    @Order(3)
    @DisplayName("RunTask - a Fargate task reports its platform, ENI and ephemeral storage")
    void runFargateTask() {
        List<Task> tasks = ecs.runTask(RunTaskRequest.builder()
                .cluster(clusterName)
                .taskDefinition(family)
                .launchType(LaunchType.FARGATE)
                .enableExecuteCommand(true)
                .networkConfiguration(NetworkConfiguration.builder()
                        .awsvpcConfiguration(AwsVpcConfiguration.builder()
                                .subnets(subnetId)
                                .build())
                        .build())
                .build()).tasks();

        assertThat(tasks).hasSize(1);
        Task task = tasks.get(0);
        taskArn = task.taskArn();

        assertThat(task.launchType()).isEqualTo(LaunchType.FARGATE);
        assertThat(task.platformVersion()).isNotBlank();
        assertThat(task.platformFamily()).isEqualTo("Linux");
        assertThat(task.ephemeralStorage().sizeInGiB()).isEqualTo(40);
        assertThat(task.enableExecuteCommand()).isTrue();
        assertThat(task.group()).isEqualTo("family:" + family);
        assertThat(task.attachments()).hasSize(1);
        assertThat(task.attachments().get(0).type()).isEqualTo("ElasticNetworkInterface");
        assertThat(task.attachments().get(0).details())
                .anySatisfy(detail -> assertThat(detail.name()).isEqualTo("privateIPv4Address"));

        Task described = ecs.describeTasks(DescribeTasksRequest.builder()
                .cluster(clusterName)
                .tasks(taskArn)
                .build()).tasks().get(0);
        assertThat(described.connectivityAsString()).isEqualTo("CONNECTED");
        assertThat(described.healthStatusAsString()).isNotBlank();
        assertThat(described.version()).isPositive();
    }

    @Test
    @Order(4)
    @DisplayName("RunTask - FARGATE_SPOT places through the capacity provider")
    void runTaskOnFargateSpot() {
        Task task = ecs.runTask(RunTaskRequest.builder()
                .cluster(clusterName)
                .taskDefinition(family)
                .capacityProviderStrategy(CapacityProviderStrategyItem.builder()
                        .capacityProvider("FARGATE_SPOT")
                        .weight(1)
                        .build())
                .networkConfiguration(NetworkConfiguration.builder()
                        .awsvpcConfiguration(AwsVpcConfiguration.builder()
                                .subnets(subnetId)
                                .build())
                        .build())
                .build()).tasks().get(0);

        assertThat(task.capacityProviderName()).isEqualTo("FARGATE_SPOT");
        // A task reports the launch type its capacity provider resolves to, alongside the provider.
        assertThat(task.launchType()).isEqualTo(LaunchType.FARGATE);

        ecs.stopTask(StopTaskRequest.builder()
                .cluster(clusterName).task(task.taskArn()).build());
    }

    @Test
    @Order(6)
    @DisplayName("DescribeClusters - include gates the optional members and statistics deserialize")
    void describeClustersHonoursInclude() {
        Cluster bare = ecs.describeClusters(DescribeClustersRequest.builder()
                .clusters(clusterName).build()).clusters().get(0);
        assertThat(bare.statistics()).isEmpty();
        assertThat(bare.hasStatistics()).isFalse();
        assertThat(bare.hasTags()).isFalse();

        Cluster full = ecs.describeClusters(DescribeClustersRequest.builder()
                .clusters(clusterName)
                .include(ClusterField.STATISTICS, ClusterField.SETTINGS, ClusterField.TAGS,
                        ClusterField.ATTACHMENTS, ClusterField.CONFIGURATIONS)
                .build()).clusters().get(0);
        assertThat(full.hasStatistics()).isTrue();
        assertThat(full.statistics()).extracting(KeyValuePair::name)
                .contains("runningFargateTasksCount", "runningEC2TasksCount",
                        "activeFargateServiceCount", "drainingEC2ServiceCount");
        assertThat(full.hasAttachments()).isTrue();
    }

    @Test
    @Order(7)
    @DisplayName("DescribeClusters and DescribeTasks - an unknown reference comes back as MISSING")
    void describeReportsMissingRatherThanDroppingTheReference() {
        DescribeClustersResponse clusters = ecs.describeClusters(DescribeClustersRequest.builder()
                .clusters(clusterName, "sdk-no-such-cluster").build());
        assertThat(clusters.clusters()).hasSize(1);
        assertThat(clusters.failures()).singleElement()
                .satisfies(failure -> assertThat(failure.reason()).isEqualTo("MISSING"));

        DescribeTasksResponse tasks = ecs.describeTasks(DescribeTasksRequest.builder()
                .cluster(clusterName)
                .tasks("00000000000000000000000000000000")
                .build());
        assertThat(tasks.tasks()).isEmpty();
        assertThat(tasks.failures()).singleElement()
                .satisfies(failure -> assertThat(failure.reason()).isEqualTo("MISSING"));
    }
}
