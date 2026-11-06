package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An {@code AWS::ECS::TaskDefinition} container's {@code LinuxParameters} reach the task
 * definition as RegisterTaskDefinition keeps them: DescribeTaskDefinition shows them in the API's
 * shape, and the Fargate rules RegisterTaskDefinition applies to them apply to a template too.
 */
@QuarkusTest
@TestProfile(CloudFormationEcsLinuxParametersIntegrationTest.DeviceProfile.class)
class CloudFormationEcsLinuxParametersIntegrationTest {

    /**
     * Floci refuses a host path outside {@code floci.services.ecs.host-volume-roots} by default; the
     * template's device, {@code /dev/fuse}, is let through.
     */
    public static final class DeviceProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ecs.host-volume-roots", "/dev/fuse");
        }
    }

    private static final String ECS_CT = "application/x-amz-json-1.1";
    private static final String ECS_TARGET = "AmazonEC2ContainerServiceV20141113.";

    /**
     * The stacks a test asked for, each named afresh per run so a run never meets another run's.
     * They are deleted after each test, and a delete CloudFormation does not accept fails there,
     * reported apart from the test's own failure; deleting a stack that was never created answers
     * 200 and is a no-op.
     */
    private final List<String> stacksToDelete = new ArrayList<>();

    @AfterEach
    void deleteStacks() {
        for (String name : stacksToDelete) {
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DeleteStack")
                    .formParam("StackName", name)
                    .when().post("/").then().statusCode(200);
            CfnStackWaits.awaitStackDeleted(name);
        }
        stacksToDelete.clear();
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void linuxParametersReachTheTaskDefinition() {
        String name = "cfn-linux-parameters-" + System.nanoTime();
        String template = """
                {
                  "Parameters": {"Shm": {"Type": "Number", "Default": "256"}},
                  "Resources": {
                    "TaskDef": {
                      "Type": "AWS::ECS::TaskDefinition",
                      "Properties": {
                        "Family": "%s",
                        "RequiresCompatibilities": ["EC2"],
                        "NetworkMode": "bridge",
                        "ContainerDefinitions": [
                          {
                            "Name": "worker", "Image": "busybox", "Memory": 512,
                            "LinuxParameters": {
                              "InitProcessEnabled": true,
                              "Capabilities": {"Add": ["SYS_PTRACE"], "Drop": ["NET_RAW"]},
                              "Devices": [{"HostPath": "/dev/fuse", "ContainerPath": "/dev/fuse",
                                           "Permissions": ["read", "write"]}],
                              "SharedMemorySize": {"Ref": "Shm"},
                              "MaxSwap": 1024,
                              "Swappiness": 10,
                              "Tmpfs": [{"ContainerPath": "/scratch", "Size": 64, "MountOptions": ["noexec"]}]
                            }
                          },
                          {"Name": "plain", "Image": "busybox", "Memory": 128, "Essential": false}
                        ]
                      }
                    }
                  }
                }
                """;
        stacksToDelete.add(name);
        createStack(name, template.formatted(name));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(name).status());

        given().contentType(ECS_CT)
                .header("X-Amz-Target", ECS_TARGET + "DescribeTaskDefinition")
                .body("{\"taskDefinition\":\"" + name + "\"}")
                .when().post("/").then().statusCode(200)
                .body("taskDefinition.containerDefinitions[0].name", equalTo("worker"))
                .body("taskDefinition.containerDefinitions[0].linuxParameters", equalTo(Map.of(
                        "initProcessEnabled", true,
                        "capabilities", Map.of("add", List.of("SYS_PTRACE"), "drop", List.of("NET_RAW")),
                        "devices", List.of(Map.of("hostPath", "/dev/fuse", "containerPath", "/dev/fuse",
                                "permissions", List.of("read", "write"))),
                        "sharedMemorySize", 256,
                        "maxSwap", 1024,
                        "swappiness", 10,
                        "tmpfs", List.of(Map.of("containerPath", "/scratch", "size", 64,
                                "mountOptions", List.of("noexec"))))))
                .body("taskDefinition.containerDefinitions[1].name", equalTo("plain"))
                .body("taskDefinition.containerDefinitions[1]", not(hasKey("linuxParameters")));
    }

    @Test
    void aFargateTemplateAskingForSwapFailsAsRegisterTaskDefinitionDoes() {
        String name = "cfn-linux-parameters-fargate-" + System.nanoTime();
        String template = """
                {
                  "Resources": {
                    "TaskDef": {
                      "Type": "AWS::ECS::TaskDefinition",
                      "Properties": {
                        "Family": "%s",
                        "RequiresCompatibilities": ["FARGATE"],
                        "NetworkMode": "awsvpc",
                        "Cpu": "256",
                        "Memory": "512",
                        "ContainerDefinitions": [
                          {"Name": "worker", "Image": "busybox", "LinuxParameters": {"MaxSwap": 512, "Swappiness": 10}}
                        ]
                      }
                    }
                  }
                }
                """;
        stacksToDelete.add(name);
        createStack(name, template.formatted(name));
        assertFargateSwapRefused(name, CfnStackWaits.awaitTerminal(name));
    }

    /**
     * Measured (eu-west-1, 2026-10-16): AWS::NoValue in a list leaves its entry out, and the devices
     * and tmpfs lists are registered empty when the template has none.
     */
    @Test
    void aNoValueInAListLeavesItsEntryOut() {
        String name = "cfn-linux-parameters-novalue-" + System.nanoTime();
        String template = """
                {
                  "Conditions": {"Never": {"Fn::Equals": ["a", "b"]}},
                  "Resources": {
                    "TaskDef": {
                      "Type": "AWS::ECS::TaskDefinition",
                      "Properties": {
                        "Family": "%s",
                        "RequiresCompatibilities": ["EC2"],
                        "ContainerDefinitions": [{
                          "Name": "worker", "Image": "busybox", "Memory": 512,
                          "LinuxParameters": {
                            "Capabilities": {
                              "Add": [{"Fn::If": ["Never", "SYS_PTRACE", {"Ref": "AWS::NoValue"}]}],
                              "Drop": ["NET_RAW", {"Fn::If": ["Never", "MKNOD", {"Ref": "AWS::NoValue"}]}]},
                            "Devices": [{"Fn::If": ["Never", {"HostPath": "/dev/null"}, {"Ref": "AWS::NoValue"}]}]
                          }
                        }]
                      }
                    }
                  }
                }
                """;
        stacksToDelete.add(name);
        createStack(name, template.formatted(name));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(name).status());

        given().contentType(ECS_CT)
                .header("X-Amz-Target", ECS_TARGET + "DescribeTaskDefinition")
                .body("{\"taskDefinition\":\"" + name + "\"}")
                .when().post("/").then().statusCode(200)
                .body("taskDefinition.containerDefinitions[0].linuxParameters", equalTo(Map.of(
                        "capabilities", Map.of("add", List.of(), "drop", List.of("NET_RAW")),
                        "devices", List.of(),
                        "tmpfs", List.of())));
    }

    private static void assertFargateSwapRefused(String name, CfnStackWaits.StackState state) {
        assertEquals("ROLLBACK_COMPLETE", state.status());
        List<String> reasons = given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStackEvents")
                .formParam("StackName", name)
                .when().post("/").then().statusCode(200).extract().xmlPath()
                .getList("**.findAll { it.name() == 'ResourceStatusReason' }", String.class);
        assertTrue(reasons.stream().anyMatch(reason -> reason != null
                        && reason.contains("Fargate compatible task definitions do not support maxSwap")),
                reasons.toString());
    }

    private static void createStack(String name, String template) {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", name)
                .formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200);
    }
}
