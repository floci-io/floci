package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.StackEvent;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.LinuxParameters;
import software.amazon.awssdk.services.ecs.model.Tmpfs;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A container's {@code LinuxParameters} in an {@code AWS::ECS::TaskDefinition} reach the task
 * definition, as the ECS SDK reads it back, and a Fargate template asking for swap fails as
 * {@code RegisterTaskDefinition} refuses it.
 */
@DisplayName("CloudFormation AWS::ECS::TaskDefinition LinuxParameters")
class CloudFormationEcsLinuxParametersTest {

    private static final Duration DEPLOY_TIMEOUT =
            TestFixtures.isRealAws() ? Duration.ofMinutes(10) : Duration.ofSeconds(10);
    private static final String LINUX_STACK = TestFixtures.uniqueName("compat-cfn-linux");
    private static final String FARGATE_STACK = TestFixtures.uniqueName("compat-cfn-linux-fargate");

    private static CloudFormationClient cloudFormation;
    private static EcsClient ecs;

    @BeforeAll
    static void setup() {
        cloudFormation = TestFixtures.cloudFormationClient();
        ecs = TestFixtures.ecsClient();
    }

    @AfterAll
    static void cleanup() {
        if (cloudFormation != null) {
            // DeleteStack answers for a stack that is gone too, so a failure here is a real one.
            cloudFormation.deleteStack(r -> r.stackName(LINUX_STACK));
            cloudFormation.deleteStack(r -> r.stackName(FARGATE_STACK));
            cloudFormation.close();
        }
        if (ecs != null) {
            ecs.close();
        }
    }

    @Test
    void linuxParametersReachTheTaskDefinition() throws InterruptedException {
        String name = LINUX_STACK;
        cloudFormation.createStack(r -> r.stackName(name).templateBody("""
                {"Resources": {"TaskDef": {"Type": "AWS::ECS::TaskDefinition", "Properties": {
                  "Family": "%s", "RequiresCompatibilities": ["EC2"], "NetworkMode": "bridge",
                  "ContainerDefinitions": [{"Name": "worker", "Image": "busybox", "Memory": 512,
                    "LinuxParameters": {"InitProcessEnabled": true,
                      "Capabilities": {"Add": ["SYS_PTRACE"], "Drop": ["NET_RAW"]},
                      "SharedMemorySize": 256, "MaxSwap": 1024, "Swappiness": 10,
                      "Tmpfs": [{"ContainerPath": "/scratch", "Size": 64, "MountOptions": ["noexec"]}]}}]}}}}
                """.formatted(name)));
        assertThat(TestFixtures.awaitStackTerminal(cloudFormation, name, DEPLOY_TIMEOUT)).isEqualTo("CREATE_COMPLETE");

        ContainerDefinition container = ecs.describeTaskDefinition(r -> r.taskDefinition(name))
                .taskDefinition().containerDefinitions().get(0);
        LinuxParameters linux = container.linuxParameters();
        assertThat(linux.initProcessEnabled()).isTrue();
        assertThat(linux.capabilities().add()).containsExactly("SYS_PTRACE");
        assertThat(linux.capabilities().drop()).containsExactly("NET_RAW");
        assertThat(linux.sharedMemorySize()).isEqualTo(256);
        assertThat(linux.maxSwap()).isEqualTo(1024);
        assertThat(linux.swappiness()).isEqualTo(10);
        Tmpfs tmpfs = linux.tmpfs().get(0);
        assertThat(tmpfs.containerPath()).isEqualTo("/scratch");
        assertThat(tmpfs.size()).isEqualTo(64);
        assertThat(tmpfs.mountOptions()).containsExactly("noexec");
    }

    @Test
    void aFargateTemplateAskingForSwapFailsAsRegisterTaskDefinitionDoes() throws InterruptedException {
        String name = FARGATE_STACK;
        cloudFormation.createStack(r -> r.stackName(name).templateBody("""
                {"Resources": {"TaskDef": {"Type": "AWS::ECS::TaskDefinition", "Properties": {
                  "Family": "%s", "RequiresCompatibilities": ["FARGATE"], "NetworkMode": "awsvpc",
                  "Cpu": "256", "Memory": "512",
                  "ContainerDefinitions": [{"Name": "worker", "Image": "busybox",
                    "LinuxParameters": {"MaxSwap": 512, "Swappiness": 10}}]}}}}
                """.formatted(name)));
        assertThat(TestFixtures.awaitStackTerminal(cloudFormation, name, DEPLOY_TIMEOUT)).isEqualTo("ROLLBACK_COMPLETE");

        List<StackEvent> events = cloudFormation.describeStackEvents(r -> r.stackName(name)).stackEvents();
        assertThat(events).anySatisfy(event -> assertThat(event.resourceStatusReason())
                .contains("Fargate compatible task definitions do not support maxSwap"));
    }
}
