package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Compatibility;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.EcsException;
import software.amazon.awssdk.services.ecs.model.LinuxParameters;
import software.amazon.awssdk.services.ecs.model.NetworkMode;
import software.amazon.awssdk.services.ecs.model.TaskDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A container's {@code linuxParameters} through RegisterTaskDefinition, with the refusals and the
 * stored form measured on AWS (eu-west-1, 2026-10-03 and 2026-10-16).
 */
@DisplayName("ECS linuxParameters on RegisterTaskDefinition")
class EcsLinuxParametersRegistrationTest {

    private static final String IMAGE = "public.ecr.aws/docker/library/busybox:latest";

    private static final List<String> REGISTERED = new ArrayList<>();

    private static EcsClient ecs;

    @BeforeAll
    static void setup() {
        ecs = TestFixtures.ecsClient();
    }

    @AfterAll
    static void cleanup() {
        if (ecs == null) {
            return;
        }
        for (String arn : REGISTERED) {
            ecs.deregisterTaskDefinition(request -> request.taskDefinition(arn));
        }
        if (!REGISTERED.isEmpty()) {
            ecs.deleteTaskDefinitions(request -> request.taskDefinitions(REGISTERED));
        }
        ecs.close();
    }

    @Test
    @DisplayName("refuses what RegisterTaskDefinition refuses, whatever the launch type")
    void refusesWhatRegistrationRefuses() {
        assertRefused(false, linux -> linux.capabilities(caps -> caps.add("CAP_NET_ADMIN")),
                "Unrecognized Linux capabilities in add: [CAP_NET_ADMIN]");
        assertRefused(false, linux -> linux.devices(device -> device.hostPath("/dev/null")
                        .permissionsWithStrings("READ")),
                "Invalid permission: READ for device /dev/null");
        assertRefused(false, linux -> linux.tmpfs(mount -> mount.containerPath("/scratch").size(16)
                        .mountOptions("NOEXEC")),
                "The NOEXEC tmpfs mount option specified is invalid.");
        assertRefused(false, linux -> linux.maxSwap(256),
                "When a container has a swap memory, it must also specify a swappiness value.");
    }

    @Test
    @DisplayName("refuses on Fargate the parameters Fargate cannot honour")
    void refusesOnFargateWhatFargateCannotHonour() {
        assertRefused(true, linux -> linux.devices(device -> device.hostPath("/dev/null")),
                "Fargate compatible task definitions do not support devices");
        assertRefused(true, linux -> linux.capabilities(caps -> caps.add("NET_ADMIN")),
                "NET_ADMIN is not allowed on Fargate.");
    }

    @Test
    @DisplayName("stores a swappiness without swap as dropped, and both capability lists")
    void storesTheParametersAsRegistrationDoes() {
        TaskDefinition registered = register(false, linux -> linux.swappiness(10)
                .capabilities(caps -> caps.add("SYS_PTRACE")));

        LinuxParameters stored = ecs.describeTaskDefinition(request -> request
                .taskDefinition(registered.taskDefinitionArn())).taskDefinition()
                .containerDefinitions().get(0).linuxParameters();
        assertThat(stored.swappiness()).isNull();
        assertThat(stored.capabilities().add()).containsExactly("SYS_PTRACE");
        assertThat(stored.capabilities().hasDrop()).isTrue();
        assertThat(stored.capabilities().drop()).isEmpty();
    }

    private static void assertRefused(boolean fargate, Consumer<LinuxParameters.Builder> linux, String message) {
        assertThatThrownBy(() -> register(fargate, linux))
                .isInstanceOfSatisfying(EcsException.class, refusal -> {
                    assertThat(refusal.awsErrorDetails().errorCode()).isEqualTo("ClientException");
                    assertThat(refusal.awsErrorDetails().errorMessage()).isEqualTo(message);
                });
    }

    private static TaskDefinition register(boolean fargate, Consumer<LinuxParameters.Builder> linux) {
        ContainerDefinition container = ContainerDefinition.builder()
                .name("app")
                .image(IMAGE)
                .memory(128)
                .linuxParameters(linux)
                .build();
        TaskDefinition registered = ecs.registerTaskDefinition(request -> {
            request.family(TestFixtures.uniqueName("compat-ecs-linux")).containerDefinitions(container);
            if (fargate) {
                request.requiresCompatibilities(Compatibility.FARGATE).networkMode(NetworkMode.AWSVPC)
                        .cpu("256").memory("512");
            }
        }).taskDefinition();
        REGISTERED.add(registered.taskDefinitionArn());
        return registered;
    }
}
