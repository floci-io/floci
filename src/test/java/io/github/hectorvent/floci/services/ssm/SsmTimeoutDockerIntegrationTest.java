package io.github.hectorvent.floci.services.ssm;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class SsmTimeoutDockerIntegrationTest {
    @Inject
    DockerClient docker;

    @Inject
    ContainerBuilder builder;

    @Inject
    ContainerLifecycleManager lifecycle;

    @Inject
    EmulatorConfig config;

    @ParameterizedTest
    @ValueSource(strings = {"busybox:stable", "public.ecr.aws/lambda/nodejs:20", "ubuntu:24.04"})
    void timeoutPreservesOutputAndTerminatesScriptsOnBothGuestToolchains(String image) {
        String name = ContainerStorageHelper.resourceName(config, "ssm-timeout", null, UUID.randomUUID().toString());
        try {
            ContainerInfo container = lifecycle.createAndStartEphemeral(builder.newContainer(image)
                    .withName(name).withEntrypoint(List.of("sh", "-c"))
                    .withCmd(List.of("exec sleep 600")).build());
            ContainerExec.Result output = execute(container.containerId(),
                    "printf '%s' \"a 'quoted' line\"; printf '%s' stderr-marker >&2; exit 7", 5);
            assertFalse(output.timedOut());
            assertEquals(7, output.exitCode(), output.summary());
            assertEquals("a 'quoted' line", output.stdout());
            assertEquals("stderr-marker", output.stderr());

            ContainerExec.Result tools = ContainerExec.run(docker, container.containerId(), new String[]{"sh", "-c",
                    "mkdir /tmp/ssm-minimal-tools && ln -s /bin/sh /tmp/ssm-minimal-tools/sh"}, 3);
            assertEquals(0, tools.exitCode(), tools.summary());
            ContainerExec.Result fallback = ContainerExec.run(docker, container.containerId(), new String[]{"sh", "-c",
                    "PATH=/tmp/ssm-minimal-tools; export PATH; " + SsmDirectCommandExecutor.timeoutWrappedScript(
                            "printf fallback; exit 143", 5)}, 3);
            assertEquals(143, fallback.exitCode(), fallback.summary());
            assertEquals("fallback", fallback.stdout());

            ContainerExec.Result withoutSetsid = ContainerExec.run(docker, container.containerId(), new String[]{"sh", "-c",
                    "for tool in mktemp mkdir rmdir rm sleep timeout; do "
                            + "ln -s \"$(command -v $tool)\" /tmp/ssm-minimal-tools/$tool || exit 1; done; "
                            + "PATH=/tmp/ssm-minimal-tools; export PATH; "
                            + SsmDirectCommandExecutor.timeoutWrappedScript("printf no-setsid; exit 137", 5)}, 3);
            assertEquals(137, withoutSetsid.exitCode(), withoutSetsid.summary());
            assertEquals("no-setsid", withoutSetsid.stdout());

            ContainerExec.Result terminated = execute(container.containerId(), "exec sleep 20", 1);
            assertFalse(terminated.timedOut(), "The guest timeout must finish before Docker attach's deadline");
            assertTrue(Set.of(124L, 137L, 143L).contains(terminated.exitCode()), terminated.summary());

            ContainerExec.Result killed = execute(container.containerId(), "trap '' TERM; exec sleep 20", 1);
            assertFalse(killed.timedOut(), "Kill escalation must finish before Docker attach's deadline");
            assertEquals(137, killed.exitCode(), killed.summary());
        } finally {
            lifecycle.removeIfExistsStrict(name, true);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"busybox:stable", "public.ecr.aws/lambda/nodejs:20", "ubuntu:24.04"})
    void executorPreservesNaturalReservedExits(String image) {
        checkExecutor(image, false, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"busybox:stable", "public.ecr.aws/lambda/nodejs:20", "ubuntu:24.04"})
    void executorKillsBackgroundDescendants(String image) {
        checkExecutor(image, true, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"busybox:stable", "public.ecr.aws/lambda/nodejs:20", "ubuntu:24.04"})
    void executorDoesNotMisreportAnEarlyParentExitAsTimeout(String image) {
        checkExecutor(image, true, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"public.ecr.aws/lambda/nodejs:20", "ubuntu:24.04"})
    void gnuFallbackKillsStartedDescendantAfterDeadline(String image) {
        String name = ContainerStorageHelper.resourceName(config, "ssm-timeout", null, UUID.randomUUID().toString());
        try {
            ContainerInfo container = lifecycle.createAndStartEphemeral(builder.newContainer(image)
                    .withName(name).withEntrypoint(List.of("sh", "-c"))
                    .withCmd(List.of("exec sleep 600")).build());
            ContainerExec.Result setup = ContainerExec.run(docker, container.containerId(), new String[]{"sh", "-c",
                    "mkdir /tmp/ssm-gnu-tools; for tool in sh mktemp mkdir rmdir rm sleep timeout mkfifo; do "
                            + "ln -s \"$(command -v $tool)\" /tmp/ssm-gnu-tools/$tool || exit 1; done"}, 3);
            assertEquals(0, setup.exitCode(), setup.summary());
            SsmCommandTimeout timeout = new SsmCommandTimeout(4096);
            String script = "mkfifo /tmp/started; sh -c 'trap \"\" TERM; echo $$ > /tmp/descendant; "
                    + "echo ready > /tmp/started; exec sleep 600' & read ready < /tmp/started; wait";
            ContainerExec.Result result = ContainerExec.run(docker, container.containerId(), new String[]{"sh", "-c",
                    "PATH=/tmp/ssm-gnu-tools; export PATH; " + timeout.wrap(script, 1)}, 8);
            timeout.writeError(result.stderr().getBytes(StandardCharsets.UTF_8));
            assertFalse(result.timedOut(), "Guest supervision must finish before the Docker attach deadline");
            assertEquals(137, result.exitCode(), result.summary());
            assertTrue(timeout.timedOut(), "GNU escalation must emit the guest deadline marker");
            assertFalse(timeout.standardError().contains("floci-ssm-timeout-"));
            ContainerExec.Result state = descendantState(container.containerId());
            assertEquals(0, state.exitCode(), "The descendant must have started: " + state.summary());
            assertTrue(Set.of("absent", "Z").contains(state.stdout()), "Descendant still running: " + state.summary());
        } finally {
            lifecycle.removeIfExistsStrict(name, true);
        }
    }

    private void checkExecutor(String image, boolean descendantsOnly, boolean parentExits) {
        String name = ContainerStorageHelper.resourceName(config, "ssm-timeout", null, UUID.randomUUID().toString());
        try {
            ContainerInfo container = lifecycle.createAndStartEphemeral(builder.newContainer(image)
                    .withName(name).withEntrypoint(List.of("sh", "-c"))
                    .withCmd(List.of("exec sleep 600")).build());
            Ec2Service ec2 = mock(Ec2Service.class);
            Instance instance = new Instance();
            instance.setDockerContainerId(container.containerId());
            when(ec2.findInstanceById("i-timeout")).thenReturn(instance);
            when(ec2.isInstanceContainerRunning("i-timeout")).thenReturn(true);
            SsmDirectCommandExecutor executor = new SsmDirectCommandExecutor(docker, ec2);
            if (!descendantsOnly) {
                for (int code : List.of(124, 137, 143)) {
                    SsmDirectCommandExecutor.ExecutionResult result = executor.executeIfSupported("i-timeout",
                            "AWS-RunShellScript", Map.of("commands", List.of(
                                    "printf 'quoted \"output\"'; printf 'natural-error' >&2; exit " + code)), 10).orElseThrow();
                    assertEquals("Failed", result.status());
                    assertEquals(code, result.responseCode());
                    assertEquals("quoted \"output\"", result.standardOutput());
                    assertEquals("natural-error", result.standardError());
                }
                return;
            }
            String script = parentExits
                    ? "mkfifo /tmp/started; sh -c 'trap \"\" TERM; echo $$ > /tmp/descendant; "
                            + "echo ready > /tmp/started; exec sleep 600' & read ready < /tmp/started; printf parent-done"
                    : "sh -c 'trap \"\" TERM; echo $$ > /tmp/descendant; exec sleep 600' & wait";
            SsmDirectCommandExecutor.ExecutionResult result = executor.executeIfSupported("i-timeout",
                    "AWS-RunShellScript", Map.of("commands", List.of(script)), 1).orElseThrow();
            ContainerExec.Result state = descendantState(container.containerId());
            assertEquals(0, state.exitCode(), "The descendant must have started: " + state.summary());
            if (parentExits) {
                assertEquals("Success", result.status(), "Early parent exit must not become a host attach timeout");
                assertEquals(0, result.responseCode());
                assertEquals("parent-done", result.standardOutput());
                assertTrue(Set.of("R", "S", "D").contains(state.stdout()), state.summary());
            } else {
                assertEquals("TimedOut", result.status());
                assertEquals(-1, result.responseCode());
                assertTrue(Set.of("absent", "Z").contains(state.stdout()), "Descendant still running: " + state.summary());
            }
            assertFalse(result.standardError().contains("floci-ssm-timeout-"));
        } finally {
            lifecycle.removeIfExistsStrict(name, true);
        }
    }

    private ContainerExec.Result descendantState(String container) {
        return ContainerExec.run(docker, container, new String[]{"sh", "-c",
                "test -s /tmp/descendant || exit 1; pid=$(cat /tmp/descendant); if [ -f /proc/$pid/stat ]; then "
                        + "read p name state rest < /proc/$pid/stat; printf '%s' \"$state\"; else printf absent; fi"}, 3);
    }

    private ContainerExec.Result execute(String container, String script, int seconds) {
        return ContainerExec.run(docker, container,
                new String[]{"sh", "-c", SsmDirectCommandExecutor.timeoutWrappedScript(script, seconds)}, 8);
    }
}
