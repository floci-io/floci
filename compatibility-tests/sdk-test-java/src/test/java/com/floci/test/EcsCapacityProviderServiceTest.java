package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.CapacityProviderStrategyItem;
import software.amazon.awssdk.services.ecs.model.Compatibility;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.LaunchType;
import software.amazon.awssdk.services.ecs.model.NetworkMode;
import software.amazon.awssdk.services.ecs.model.Service;
import software.amazon.awssdk.services.ecs.model.Task;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ECS services scheduled through capacity providers")
class EcsCapacityProviderServiceTest {

    private static Stream<Arguments> providerStrategies() {
        return Stream.of(
                Arguments.of(List.of(CapacityProviderStrategyItem.builder()
                                .capacityProvider("FARGATE").weight(1).build()),
                        Map.of("FARGATE", 2L), Map.of("FARGATE", 4L)),
                Arguments.of(List.of(CapacityProviderStrategyItem.builder()
                                .capacityProvider("FARGATE_SPOT").weight(1).build()),
                        Map.of("FARGATE_SPOT", 2L), Map.of("FARGATE_SPOT", 4L)),
                Arguments.of(List.of(CapacityProviderStrategyItem.builder()
                                .capacityProvider("FARGATE").base(1).weight(0).build(),
                                CapacityProviderStrategyItem.builder()
                                        .capacityProvider("FARGATE_SPOT").weight(1).build()),
                        Map.of("FARGATE", 1L, "FARGATE_SPOT", 1L),
                        Map.of("FARGATE", 1L, "FARGATE_SPOT", 3L)));
    }

    @ParameterizedTest
    @MethodSource("providerStrategies")
    void capacityProviderServiceReachesDesiredCountAndScalesOut(
            List<CapacityProviderStrategyItem> strategy, Map<String, Long> initial, Map<String, Long> scaled) {
        String name = "sdk-capacity-service-" + UUID.randomUUID().toString().substring(0, 8);
        try (EcsClient ecs = TestFixtures.ecsClient(); Ec2Client ec2 = TestFixtures.ec2Client()) {
            String subnet = ec2.describeSubnets().subnets().get(0).subnetId();
            ecs.createCluster(request -> request.clusterName(name)
                    .capacityProviders(strategy.stream().map(CapacityProviderStrategyItem::capacityProvider).toList()));
            TaskDefinition definition = ecs.registerTaskDefinition(request -> request.family(name)
                    .networkMode(NetworkMode.AWSVPC).requiresCompatibilities(Compatibility.FARGATE)
                    .cpu("256").memory("512")
                    .containerDefinitions(ContainerDefinition.builder()
                            .name("app").image("alpine:3").memory(128)
                            // PID 1 gets no default SIGTERM handler, so a bare "sleep 3600" sits out
                            // the whole stopTimeout grace period for each task deleteService stops.
                            .command("sh", "-c", "trap 'exit 0' TERM; sleep 3600 & wait")
                            .essential(true).build())).taskDefinition();
            try {
                ecs.createService(request -> request.cluster(name).serviceName(name)
                        .taskDefinition(definition.taskDefinitionArn()).desiredCount(2)
                        .networkConfiguration(network -> network.awsvpcConfiguration(vpc -> vpc.subnets(subnet)))
                        .capacityProviderStrategy(strategy));
                try {
                    assertStableProviderCounts(ecs, name, strategy, initial);
                    ecs.updateService(request -> request.cluster(name).service(name).desiredCount(4));
                    assertStableProviderCounts(ecs, name, strategy, scaled);
                } finally {
                    ecs.deleteService(request -> request.cluster(name).service(name).force(true));
                }
            } finally {
                ecs.deregisterTaskDefinition(request -> request.taskDefinition(definition.taskDefinitionArn()));
                ecs.deleteCluster(request -> request.cluster(name));
            }
        }
    }

    private static void assertStableProviderCounts(EcsClient ecs, String name,
                                                   List<CapacityProviderStrategyItem> strategy,
                                                   Map<String, Long> expected) {
        DescribeServicesRequest describe = DescribeServicesRequest.builder().cluster(name).services(name).build();
        assertThat(ecs.waiter().waitUntilServicesStable(describe, TestFixtures.waiterOverride(Duration.ofSeconds(30)))
                .matched().response()).isPresent();

        int count = expected.values().stream().mapToInt(Long::intValue).sum();
        Service service = ecs.describeServices(describe).services().get(0);
        assertThat(service.runningCount()).isEqualTo(count);
        assertThat(service.pendingCount()).isZero();
        assertThat(service.launchType()).isNull();
        assertThat(service.capacityProviderStrategy()).extracting(CapacityProviderStrategyItem::capacityProvider)
                .containsExactlyElementsOf(strategy.stream().map(CapacityProviderStrategyItem::capacityProvider).toList());
        List<String> taskArns = ecs.listTasks(request -> request.cluster(name).serviceName(name)).taskArns();
        assertThat(taskArns).hasSize(count);
        List<Task> tasks = ecs.describeTasks(request -> request.cluster(name).tasks(taskArns)).tasks();
        assertThat(tasks).hasSize(count).allSatisfy(task -> {
            assertThat(task.launchType()).isEqualTo(LaunchType.FARGATE);
            assertThat(task.lastStatus()).isEqualTo("RUNNING");
        });
        assertThat(tasks.stream().collect(Collectors.groupingBy(Task::capacityProviderName, Collectors.counting())))
                .isEqualTo(expected);
    }
}
