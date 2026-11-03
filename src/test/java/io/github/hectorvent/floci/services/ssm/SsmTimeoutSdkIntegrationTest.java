package io.github.hectorvent.floci.services.ssm;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetCommandInvocationResponse;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class SsmTimeoutSdkIntegrationTest extends SsmCommandIntegrationFixture {
    @Inject DockerClient docker;
    @Inject ContainerBuilder builder;
    @Inject ContainerLifecycleManager lifecycle;
    @Inject EmulatorConfig config;

    @ParameterizedTest
    @ValueSource(strings = {"busybox:stable", "public.ecr.aws/lambda/nodejs:20"})
    void sdkDistinguishesReservedFailuresFromExecutionDeadline(String image) {
        String instanceId = "i-timeout-" + UUID.randomUUID();
        String name = ContainerStorageHelper.resourceName(config, "ssm-timeout", null, UUID.randomUUID().toString());
        String account = "000000000000";
        try (SsmClient ssm = AwsTestClients.create(SsmClient.builder(), account, Region.US_EAST_1)) {
            ContainerInfo container = lifecycle.createAndStartEphemeral(builder.newContainer(image)
                    .withName(name).withEntrypoint(List.of("sh", "-c"))
                    .withCmd(List.of("exec sleep 600")).build());
            Ec2Service ec2 = mock(Ec2Service.class);
            Instance instance = new Instance();
            instance.setDockerContainerId(container.containerId());
            when(ec2.findInstanceById(account, instanceId)).thenReturn(instance);
            when(ec2.isInstanceContainerRunning(account, instanceId)).thenReturn(true);
            SsmDirectCommandExecutor actual = new SsmDirectCommandExecutor(docker, ec2);
            when(executor.supports(account, instanceId, "AWS-RunShellScript")).thenReturn(true);
            when(executor.executeIfSupported(eq(account), eq(instanceId), eq("AWS-RunShellScript"), any(), anyInt()))
                    .thenAnswer(call -> actual.executeIfSupported(call.getArgument(0), call.getArgument(1),
                            call.getArgument(2), call.getArgument(3), call.getArgument(4)));
            for (int code : List.of(124, 137, 143)) {
                GetCommandInvocationResponse result = invoke(ssm, instanceId,
                        "printf stdout; printf stderr >&2; exit " + code, 10);
                assertEquals("Failed", result.statusAsString());
                assertEquals(code, result.responseCode());
                assertEquals("stdout", result.standardOutputContent());
                assertEquals("stderr", result.standardErrorContent());
            }
            GetCommandInvocationResponse timed = invoke(ssm, instanceId,
                    "printf before; trap '' TERM; exec sleep 600", 1);
            assertEquals("TimedOut", timed.statusAsString());
            assertEquals(-1, timed.responseCode());
            assertEquals("before", timed.standardOutputContent());
            assertFalse(timed.standardErrorContent().contains("floci-ssm-timeout-"));
        } finally {
            assertAll("command and container cleanup",
                    () -> cleanupCommands(account, Region.US_EAST_1.id(), instanceId),
                    () -> lifecycle.removeIfExistsStrict(name, true));
        }
    }

    private GetCommandInvocationResponse invoke(SsmClient ssm, String instance, String script, int seconds) {
        String command = ssm.sendCommand(r -> r.documentName("AWS-RunShellScript").instanceIds(instance)
                .parameters(Map.of("commands", List.of(script), "executionTimeout", List.of(Integer.toString(seconds)))))
                .command().commandId();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            GetCommandInvocationResponse result = ssm.getCommandInvocation(r -> r.commandId(command).instanceId(instance));
            assertTrue(List.of("Failed", "TimedOut").contains(result.statusAsString()));
        });
        return ssm.getCommandInvocation(r -> r.commandId(command).instanceId(instance));
    }
}
