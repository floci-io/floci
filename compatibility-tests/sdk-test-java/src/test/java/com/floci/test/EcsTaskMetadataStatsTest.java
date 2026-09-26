package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.CreateClusterRequest;
import software.amazon.awssdk.services.ecs.model.DeleteClusterRequest;
import software.amazon.awssdk.services.ecs.model.LaunchType;
import software.amazon.awssdk.services.ecs.model.NetworkMode;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.RunTaskRequest;
import software.amazon.awssdk.services.ecs.model.StopTaskRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("ECS task metadata stats endpoint")
class EcsTaskMetadataStatsTest {

    private static final String STATS_MARKER = "STATS:";

    private static EcsClient ecs;
    private static CloudWatchLogsClient logs;
    private static String clusterName;
    private static String family;
    private static String taskArn;

    @BeforeAll
    static void setup() {
        assumeTrue(TestFixtures.isLambdaDispatchAvailable(),
                "Skipping ECS stats test: Docker dispatch not available in this environment");
        ecs = TestFixtures.ecsClient();
        logs = TestFixtures.cloudWatchLogsClient();
        String suffix = String.valueOf(System.currentTimeMillis() % 100000);
        clusterName = "stats-cluster-" + suffix;
        family = "stats-task-" + suffix;
        ecs.createCluster(CreateClusterRequest.builder().clusterName(clusterName).build());
    }

    @AfterAll
    static void cleanup() {
        if (ecs == null) {
            return;
        }
        if (taskArn != null) {
            try {
                ecs.stopTask(StopTaskRequest.builder().cluster(clusterName).task(taskArn).build());
            } catch (Exception ignored) {
            }
        }
        try {
            ecs.deleteCluster(DeleteClusterRequest.builder().cluster(clusterName).build());
        } catch (Exception ignored) {
        }
        ecs.close();
        logs.close();
    }

    @Test
    @DisplayName("GET ${ECS_CONTAINER_METADATA_URI_V4}/stats returns Docker's stats for the container")
    void containerReadsItsOwnStats() throws Exception {
        ecs.registerTaskDefinition(RegisterTaskDefinitionRequest.builder()
                .family(family)
                .networkMode(NetworkMode.BRIDGE)
                .containerDefinitions(ContainerDefinition.builder()
                        .name("main")
                        .image("busybox:latest")
                        .command("sh", "-c", "sleep 2; echo " + STATS_MARKER
                                + "$(wget -qO- $ECS_CONTAINER_METADATA_URI_V4/stats); sleep 60")
                        .essential(true)
                        .memory(64)
                        .build())
                .build());
        taskArn = ecs.runTask(RunTaskRequest.builder()
                .cluster(clusterName)
                .taskDefinition(family)
                .launchType(LaunchType.FARGATE)
                .count(1)
                .build()).tasks().get(0).taskArn();

        String stats = awaitStatsLine();

        JsonNode node = new ObjectMapper().readTree(stats);
        assertThat(node.path("cpu_stats").path("cpu_usage").path("total_usage").asLong()).isPositive();
        assertThat(node.path("memory_stats").path("usage").asLong()).isPositive();
    }

    private static String awaitStatsLine() throws InterruptedException {
        String logGroup = "/ecs/" + family;
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<FilteredLogEvent> events = logs.filterLogEvents(FilterLogEventsRequest.builder()
                        .logGroupName(logGroup)
                        .build()).events();
                for (FilteredLogEvent event : events) {
                    String message = event.message().strip();
                    if (message.startsWith(STATS_MARKER)) {
                        return message.substring(STATS_MARKER.length());
                    }
                }
            } catch (Exception ignored) {
                // The log group does not exist until the container writes its first line.
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("No stats line in " + logGroup + " within 60s");
    }
}
