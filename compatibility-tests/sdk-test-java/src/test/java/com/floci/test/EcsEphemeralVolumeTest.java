package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Compatibility;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.DescribeTasksRequest;
import software.amazon.awssdk.services.ecs.model.EFSVolumeConfiguration;
import software.amazon.awssdk.services.ecs.model.HostVolumeProperties;
import software.amazon.awssdk.services.ecs.model.InvalidParameterException;
import software.amazon.awssdk.services.ecs.model.LaunchType;
import software.amazon.awssdk.services.ecs.model.MountPoint;
import software.amazon.awssdk.services.ecs.model.NetworkMode;
import software.amazon.awssdk.services.ecs.model.RunTaskResponse;
import software.amazon.awssdk.services.ecs.model.Task;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;
import software.amazon.awssdk.services.ecs.model.Volume;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("ECS ephemeral volumes shared within a task")
class EcsEphemeralVolumeTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void efsVolumeRequiresFileSystemId(boolean blankId) {
        String name = "sdk-invalid-efs-" + UUID.randomUUID().toString().substring(0, 8);
        EFSVolumeConfiguration.Builder efs = EFSVolumeConfiguration.builder();
        if (blankId) {
            efs.fileSystemId("");
        }
        try (EcsClient ecs = TestFixtures.ecsClient()) {
            assertThatThrownBy(() -> ecs.registerTaskDefinition(request -> request.family(name)
                    .containerDefinitions(container("reader", true, "true"))
                    .volumes(Volume.builder().name("shared").efsVolumeConfiguration(efs.build()).build())))
                    .isInstanceOf(InvalidParameterException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nameOnlyAndEmptyHostVolumesShareFilesBetweenContainers(boolean emptyHost) throws InterruptedException {
        String name = "sdk-ephemeral-volume-" + UUID.randomUUID().toString().substring(0, 8);
        Volume.Builder volume = Volume.builder().name("shared");
        if (emptyHost) {
            volume.host(HostVolumeProperties.builder().build());
        }
        try (EcsClient ecs = TestFixtures.ecsClient(); Ec2Client ec2 = TestFixtures.ec2Client();
             Ec2TestNetwork ownedNetwork = Ec2TestNetwork.create(ec2)) {
            String subnet = ownedNetwork.subnetId();
            ecs.createCluster(request -> request.clusterName(name));
            try {
                TaskDefinition definition = ecs.registerTaskDefinition(request -> request.family(name)
                        .networkMode(NetworkMode.AWSVPC).requiresCompatibilities(Compatibility.FARGATE)
                        .cpu("256").memory("512").volumes(volume.build())
                        .containerDefinitions(
                                container("writer", false, "echo shared > /data/marker"),
                                container("reader", true, """
                                        for attempt in 1 2 3 4 5; do
                                            if [ "$(cat /data/marker 2>/dev/null)" = shared ]; then exit 0; fi
                                            sleep 1
                                        done
                                        exit 42
                                        """))).taskDefinition();
                try {
                    assertThat(definition.volumes()).hasSize(1);
                    assertThat(definition.volumes().get(0).name()).isEqualTo("shared");
                    assertThat(definition.volumes().get(0).host() != null).isEqualTo(emptyHost);
                    RunTaskResponse started = ecs.runTask(request -> request.cluster(name)
                            .taskDefinition(definition.taskDefinitionArn()).launchType(LaunchType.FARGATE)
                            .networkConfiguration(network -> network.awsvpcConfiguration(vpc -> vpc.subnets(subnet))));
                    assertThat(started.failures()).isEmpty();
                    assertThat(started.tasks()).hasSize(1);
                    Task task = started.tasks().get(0);
                    try {
                        assertThat(task.containers()).hasSize(2);
                        DescribeTasksRequest describe = DescribeTasksRequest.builder()
                                .cluster(name).tasks(task.taskArn()).build();
                        // RunTask answers before the images are pulled, so whether Docker started the
                        // containers shows only once the launch is over.
                        Task launched = awaitLaunched(ecs, describe);
                        assertThat(launched.stopCodeAsString()).as(launched.stoppedReason())
                                .isNotEqualTo("TaskFailedToStart");
                        assumeTrue(launched.containers().stream().allMatch(container -> container.runtimeId() != null),
                                "ECS Docker mode is required to check files shared by running containers");
                        assertThat(ecs.waiter().waitUntilTasksStopped(describe,
                                TestFixtures.waiterOverride(Duration.ofSeconds(30)))
                                .matched().response()).isPresent();
                        Task stopped = ecs.describeTasks(describe).tasks().get(0);
                        assertThat(stopped.containers()).hasSize(2).allSatisfy(container -> {
                            assertThat(container.lastStatus()).isEqualTo("STOPPED");
                            assertThat(container.exitCode()).isZero();
                        });
                    } finally {
                        ecs.stopTask(request -> request.cluster(name).task(task.taskArn()));
                    }
                } finally {
                    ecs.deregisterTaskDefinition(request -> request.taskDefinition(definition.taskDefinitionArn()));
                }
            } finally {
                ecs.deleteCluster(request -> request.cluster(name));
            }
        }
    }

    /**
     * Polls until the task has left PROVISIONING and PENDING. The TasksRunning waiter cannot stand
     * in: it fails on STOPPED, and this task's containers exit as soon as they have run.
     */
    private static Task awaitLaunched(EcsClient ecs, DescribeTasksRequest describe) throws InterruptedException {
        AtomicReference<Task> latest = new AtomicReference<>();
        TestFixtures.await(() -> {
            Task current = ecs.describeTasks(describe).tasks().get(0);
            latest.set(current);
            return !"PROVISIONING".equals(current.lastStatus()) && !"PENDING".equals(current.lastStatus());
        }, describe.tasks().get(0), "the launch to finish", TimeUnit.SECONDS.toNanos(60), 500);
        return latest.get();
    }

    private static ContainerDefinition container(String name, boolean readOnly, String command) {
        return ContainerDefinition.builder().name(name).image("busybox:stable").essential(true)
                .command("sh", "-c", command).stopTimeout(1)
                .mountPoints(MountPoint.builder().sourceVolume("shared").containerPath("/data")
                        .readOnly(readOnly).build()).build();
    }
}
