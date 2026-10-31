package io.github.hectorvent.floci.services.ecs;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.TopContainerResponse;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Device;
import com.github.dockerjava.api.model.HostConfig;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.testing.DockerAvailability;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A task definition registered through the API with {@code linuxParameters} runs a container
 * that has them: an init process as PID 1, the capabilities added and dropped, the device, the
 * size of {@code /dev/shm} and the tmpfs mount, as ECS hands each of them to Docker.
 */
@QuarkusTest
@TestProfile(EcsLinuxParametersDockerIntegrationTest.DockerEcsProfile.class)
class EcsLinuxParametersDockerIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String ECS_CT = "application/x-amz-json-1.1";
    private static final String ECS_TARGET = "AmazonEC2ContainerServiceV20141113.";
    private static final long MIB = 1024L * 1024L;

    /**
     * Real containers, with security-group enforcement back to its shipped default of off, and
     * {@code /dev/null} the one host path a device may take, since devices are refused by default.
     */
    public static final class DockerEcsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ecs.mock", "false",
                    "floci.network.security-group-enforcement.enabled", "false",
                    "floci.services.ecs.host-volume-roots", "/dev/null",
                    "floci.services.ecs.allow-privileged-containers", "true");
        }
    }

    @Inject
    EcsService ecsService;

    @Inject
    DockerClient dockerClient;

    private String clusterName;
    private String taskArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void requireDocker() throws InterruptedException {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(),
                "Docker daemon must be available for the ECS container test");
    }

    @AfterEach
    void stopTask() {
        if (taskArn != null) {
            ecsService.stopTask(clusterName, taskArn, "test teardown", REGION);
        }
    }

    @Test
    void theContainerRunsWithItsLinuxParameters() {
        String family = "linux-params-" + UUID.randomUUID().toString().substring(0, 8);
        String taskDefinitionArn = given().contentType(ECS_CT)
                .header("X-Amz-Target", ECS_TARGET + "RegisterTaskDefinition")
                .body("""
                        {
                          "family": "%s",
                          "requiresCompatibilities": ["EC2"],
                          "networkMode": "bridge",
                          "containerDefinitions": [{
                            "name": "worker",
                            "image": "%s",
                            "memory": 256,
                            "command": ["sleep", "120"],
                            "linuxParameters": {
                              "initProcessEnabled": true,
                              "capabilities": {"add": ["SYS_PTRACE"], "drop": ["NET_RAW"]},
                              "devices": [{"hostPath": "/dev/null", "containerPath": "/dev/floci-null",
                                           "permissions": ["read"]}],
                              "sharedMemorySize": 128,
                              "tmpfs": [{"containerPath": "/scratch", "size": 16, "mountOptions": ["noexec"]}]
                            }
                          }]
                        }
                        """.formatted(family, TestImages.BUSYBOX))
                .when().post("/").then().statusCode(200)
                .extract().path("taskDefinition.taskDefinitionArn");

        clusterName = family;
        ecsService.createCluster(clusterName, REGION);
        EcsTask task = ecsService.runTask(clusterName, taskDefinitionArn, 1,
                null, null, null, null, null, REGION).getFirst();
        taskArn = task.getTaskArn();
        EcsTask described = ecsService.describeTasks(clusterName, List.of(taskArn), REGION).getFirst();
        assertEquals("RUNNING", described.getLastStatus(), "the container must be up: " + described.getStoppedReason());
        String containerId = described.getContainers().getFirst().getRuntimeId();

        InspectContainerResponse inspected = dockerClient.inspectContainerCmd(containerId).exec();
        HostConfig hostConfig = inspected.getHostConfig();
        assertEquals(Boolean.TRUE, hostConfig.getInit(), "initProcessEnabled is docker run --init");
        assertArrayEquals(new Capability[] {Capability.SYS_PTRACE}, hostConfig.getCapAdd());
        assertArrayEquals(new Capability[] {Capability.NET_RAW}, hostConfig.getCapDrop());
        assertArrayEquals(new Device[] {new Device("r", "/dev/floci-null", "/dev/null")}, hostConfig.getDevices());
        assertEquals(128 * MIB, hostConfig.getShmSize());
        assertEquals(Map.of("/scratch", "noexec,size=16m"), hostConfig.getTmpFs());

        TopContainerResponse top = dockerClient.topContainerCmd(containerId).exec();
        int command = Arrays.asList(top.getTitles()).indexOf("CMD");
        assertTrue(command >= 0, Arrays.toString(top.getTitles()));
        assertTrue(top.getProcesses()[0][command].contains("docker-init"),
                "PID 1 is the init process, with the command under it: " + Arrays.deepToString(top.getProcesses()));
    }

    @Test
    void privilegedEc2ContainerRunsWithDockerPrivilege() {
        String family = "privileged-" + UUID.randomUUID().toString().substring(0, 8);
        String taskDefinitionArn = given().contentType(ECS_CT)
                .header("X-Amz-Target", ECS_TARGET + "RegisterTaskDefinition")
                .body("""
                        {
                          "family": "%s",
                          "requiresCompatibilities": ["EC2"],
                          "networkMode": "bridge",
                          "containerDefinitions": [{
                            "name": "worker",
                            "image": "%s",
                            "memory": 256,
                            "command": ["sleep", "120"],
                            "privileged": true
                          }]
                        }
                        """.formatted(family, TestImages.BUSYBOX))
                .when().post("/").then().statusCode(200)
                .extract().path("taskDefinition.taskDefinitionArn");

        clusterName = family;
        ecsService.createCluster(clusterName, REGION);
        EcsTask task = ecsService.runTask(clusterName, taskDefinitionArn, 1,
                null, null, null, null, null, REGION).getFirst();
        taskArn = task.getTaskArn();
        EcsTask described = ecsService.describeTasks(clusterName, List.of(taskArn), REGION).getFirst();
        assertEquals("RUNNING", described.getLastStatus(), "the container must be up: " + described.getStoppedReason());

        String containerId = described.getContainers().getFirst().getRuntimeId();
        InspectContainerResponse inspected = dockerClient.inspectContainerCmd(containerId).exec();
        assertEquals(Boolean.TRUE, inspected.getHostConfig().getPrivileged());
    }
}
