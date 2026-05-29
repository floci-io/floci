package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.waiters.WaiterOverrideConfiguration;
import software.amazon.awssdk.core.waiters.WaiterResponse;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.*;
import software.amazon.awssdk.services.ecs.waiters.EcsWaiter;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.CreateRoleRequest;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies that a one-shot ECS task whose container exits naturally transitions
 * to STOPPED, populates exitCode, and unblocks aws ecs wait tasks-stopped.
 *
 * Requires Docker mode (FLOCI_ECS_MOCK=false or default) and a reachable Docker
 * socket. The test is skipped automatically when Lambda dispatch is unavailable,
 * which is a reliable proxy for "Docker-in-Docker works in this environment".
 */
@DisplayName("ECS one-shot task lifecycle (container exits naturally)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EcsOneShotTaskLifecycleTest {

    private static EcsClient ecs;
    private static String clusterName;
    private static String family;
    private static String taskArn;
    private static String clusterArn;

    @BeforeAll
    static void setup() {
        assumeTrue(TestFixtures.isLambdaDispatchAvailable(),
                "Skipping ECS one-shot lifecycle test: Docker dispatch not available in this environment");

        ecs = TestFixtures.ecsClient();

        String suffix = String.valueOf(System.currentTimeMillis() % 100000);
        clusterName = "oneshot-cluster-" + suffix;
        family = "oneshot-task-" + suffix;

        // IAM role (required by the API; not enforced in Floci)
        try (IamClient iam = TestFixtures.iamClient()) {
            try {
                iam.createRole(CreateRoleRequest.builder()
                        .roleName("ecs-oneshot-role-" + suffix)
                        .assumeRolePolicyDocument("""
                                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                                "Principal":{"Service":"ecs-tasks.amazonaws.com"},
                                "Action":"sts:AssumeRole"}]}""")
                        .build());
            } catch (Exception ignored) {
            }
        }

        // Cluster
        Cluster cluster = ecs.createCluster(CreateClusterRequest.builder()
                .clusterName(clusterName)
                .build()).cluster();
        clusterArn = cluster.clusterArn();

        // Task definition: busybox exits cleanly after ~2 s
        ecs.registerTaskDefinition(RegisterTaskDefinitionRequest.builder()
                .family(family)
                .networkMode(NetworkMode.BRIDGE)
                .cpu("256")
                .memory("64")
                .containerDefinitions(ContainerDefinition.builder()
                        .name("main")
                        .image("busybox:latest")
                        .command("sh", "-c", "echo hello && sleep 2 && echo done")
                        .essential(true)
                        .cpu(256)
                        .memory(64)
                        .build())
                .build());
    }

    @AfterAll
    static void cleanup() {
        if (ecs == null) {
            return;
        }
        try {
            List<String> running = ecs.listTasks(ListTasksRequest.builder()
                    .cluster(clusterName)
                    .desiredStatus(DesiredStatus.RUNNING)
                    .build()).taskArns();
            for (String arn : running) {
                ecs.stopTask(StopTaskRequest.builder().cluster(clusterName).task(arn).build());
            }
        } catch (Exception ignored) {}
        try {
            ecs.deleteCluster(DeleteClusterRequest.builder().cluster(clusterName).build());
        } catch (Exception ignored) {}
        ecs.close();
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("RunTask - task starts RUNNING")
    void runTask() {
        List<Task> launched = ecs.runTask(RunTaskRequest.builder()
                .cluster(clusterName)
                .taskDefinition(family)
                .launchType(LaunchType.FARGATE)
                .count(1)
                .build()).tasks();

        assertThat(launched).hasSize(1);
        taskArn = launched.get(0).taskArn();
        assertThat(launched.get(0).lastStatus()).isEqualTo("RUNNING");
    }

    @Test
    @Order(2)
    @DisplayName("WaitTasksStopped - transitions to STOPPED within 60 s")
    void waitForTaskStopped() {
        // The container runs for ~2 s; the reconciler fires every 5 s; the waiter
        // polls every 6 s. Allow 60 s total to be CI-safe.
        WaiterOverrideConfiguration overrides = WaiterOverrideConfiguration.builder()
                .waitTimeout(Duration.ofSeconds(60))
                .build();

        try (EcsWaiter waiter = EcsWaiter.builder().client(ecs).build()) {
            WaiterResponse<DescribeTasksResponse> response = waiter.waitUntilTasksStopped(
                    DescribeTasksRequest.builder()
                            .cluster(clusterName)
                            .tasks(taskArn)
                            .build(),
                    overrides);

            assertThat(response.matched().response()).isPresent();
            Task task = response.matched().response().get().tasks().get(0);
            assertThat(task.lastStatus()).isEqualTo("STOPPED");
        }
    }

    @Test
    @Order(3)
    @DisplayName("DescribeTasks - stoppedAt is populated after natural exit")
    void stoppedAtPopulated() {
        Task task = describeSingle();
        assertThat(task.stoppedAt())
                .as("stoppedAt must be set after a natural container exit")
                .isNotNull();
    }

    @Test
    @Order(4)
    @DisplayName("DescribeTasks - exitCode is 0 for clean container exit")
    void exitCodePopulated() {
        Task task = describeSingle();
        assertThat(task.containers()).isNotEmpty();
        Container main = task.containers().stream()
                .filter(c -> "main".equals(c.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("container 'main' not found"));

        assertThat(main.exitCode())
                .as("exitCode must be 0 for a container that exited cleanly")
                .isEqualTo(0);
    }

    @Test
    @Order(5)
    @DisplayName("DescribeTasks - container lastStatus is STOPPED")
    void containerStatusStopped() {
        Task task = describeSingle();
        task.containers().forEach(c ->
                assertThat(c.lastStatus())
                        .as("container %s lastStatus", c.name())
                        .isEqualTo("STOPPED"));
    }

    @Test
    @Order(6)
    @DisplayName("DescribeClusters - runningTasksCount decremented after exit")
    void runningCountDecremented() {
        Cluster cluster = ecs.describeClusters(DescribeClustersRequest.builder()
                .clusters(clusterName)
                .build()).clusters().get(0);

        assertThat(cluster.runningTasksCount())
                .as("runningTasksCount should be 0 after the one-shot task stops")
                .isEqualTo(0);
    }

    @Test
    @Order(7)
    @DisplayName("StopTask - explicit stop populates exitCode when already stopped")
    void explicitStopExitCode() {
        // Register and run a second task; stop it explicitly before it exits naturally.
        String family2 = family + "-explicit";
        ecs.registerTaskDefinition(RegisterTaskDefinitionRequest.builder()
                .family(family2)
                .networkMode(NetworkMode.BRIDGE)
                .cpu("256")
                .memory("64")
                .containerDefinitions(ContainerDefinition.builder()
                        .name("main")
                        .image("busybox:latest")
                        .command("sh", "-c", "sleep 300")
                        .essential(true)
                        .cpu(256)
                        .memory(64)
                        .build())
                .build());

        String explicitTaskArn = ecs.runTask(RunTaskRequest.builder()
                .cluster(clusterName)
                .taskDefinition(family2)
                .launchType(LaunchType.FARGATE)
                .count(1)
                .build()).tasks().get(0).taskArn();

        Task stopped = ecs.stopTask(StopTaskRequest.builder()
                .cluster(clusterName)
                .task(explicitTaskArn)
                .reason("test-explicit-stop")
                .build()).task();

        assertThat(stopped.lastStatus()).isEqualTo("STOPPED");
        assertThat(stopped.stoppedAt()).isNotNull();

        // exitCode should be present (Docker assigns 137 for SIGKILL on explicit stop)
        assertThat(stopped.containers()).isNotEmpty();
        stopped.containers().forEach(c ->
                assertThat(c.exitCode())
                        .as("explicit stopTask must populate exitCode on container %s", c.name())
                        .isNotNull());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Task describeSingle() {
        return ecs.describeTasks(DescribeTasksRequest.builder()
                .cluster(clusterName)
                .tasks(taskArn)
                .build()).tasks().get(0);
    }
}
