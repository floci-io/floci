package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import io.floci.aws.services.lambda.launcher.ImageCacheService;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guest log contract against a real container: every part's merged output lands in
 * {@code /var/log/cloud-init-output.log}, the log is not capped at the 2048 bytes Floci keeps for
 * its own log line, and the exec still reports the script's exit status rather than {@code tee}'s.
 */
@QuarkusTest
@EnabledOnOs(OS.LINUX)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserDataPipelineDockerIntegrationTest {

    private static final String LOG = UserDataPipeline.CLOUD_INIT_OUTPUT_LOG_PATH;

    @Inject
    DockerClient dockerClient;

    @Inject
    ImageCacheService imageCacheService;

    private String containerId;

    @BeforeAll
    void startGuest() throws InterruptedException {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(), "Docker daemon must be available");
        imageCacheService.ensureImageExists(TestImages.PYTHON_ALPINE);
        CreateContainerResponse container = dockerClient.createContainerCmd(TestImages.PYTHON_ALPINE)
                .withName("floci-user-data-log-" + UUID.randomUUID())
                .withCmd("tail", "-f", "/dev/null")
                .exec();
        containerId = container.getId();
        dockerClient.startContainerCmd(containerId).exec();
    }

    @BeforeEach
    void clearGuestLog() {
        ContainerExec.run(dockerClient, containerId, new String[]{"rm", "-f", LOG}, 10);
    }

    @AfterAll
    void removeGuest() {
        if (containerId != null) {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        }
    }

    @Test
    void appendsEveryPartToTheGuestLogInOrder() {
        String first = "#!/bin/sh\n"
                + "i=0\n"
                + "while [ $i -lt 400 ]; do echo \"line-$i\"; i=$((i+1)); done\n"
                + "echo stderr-line >&2\n";
        String second = "#!/usr/bin/env python3\nprint('from-python')\n";
        String multipart = "Content-Type: multipart/mixed; boundary=\"//\"\n"
                + "MIME-Version: 1.0\n\n"
                + "--//\nContent-Type: text/x-shellscript; charset=\"us-ascii\"\n\n" + first
                + "--//\nContent-Type: text/x-shellscript; charset=\"us-ascii\"\n\n" + second
                + "--//--\n";

        UserDataPipeline.ExecutionResult result = UserDataPipeline.executeUserData(
                dockerClient, dockerClient, containerId, "test guest", multipart, Duration.ofSeconds(60), null, null,
                LOG);

        assertTrue(result.isSuccess(), result.getFailureMessage());
        String log = readLog();
        assertTrue(log.contains("line-0\n"), "the first part's stdout is logged");
        assertTrue(log.contains("line-399\n"), "the whole first part is logged");
        assertTrue(log.contains("stderr-line\n"), "stderr is merged into the log");
        assertTrue(log.contains("from-python\n"), "the second part runs through its own shebang");
        assertTrue(log.indexOf("line-399") < log.indexOf("from-python"), "parts append in order");
        assertTrue(log.length() > UserDataPipeline.MAX_EXEC_OUTPUT_BYTES,
                "the guest log is not capped at Floci's 2048-byte summary");
    }

    @Test
    void reportsTheScriptExitStatusAndStillLogsItsOutput() {
        String script = "#!/bin/sh\necho failing-line >&2\nexit 7\n";

        UserDataPipeline.ExecutionResult result = UserDataPipeline.executeUserData(
                dockerClient, dockerClient, containerId, "test guest", script, Duration.ofSeconds(30), null, null,
                LOG);

        assertFalse(result.isSuccess());
        assertEquals(7L, result.getExitCode(), "the wrapper must return the script's status, not tee's");
        assertTrue(readLog().contains("failing-line\n"));
    }

    @Test
    void createsTheLogForAScriptWithNoOutput() {
        ContainerExec.run(dockerClient, containerId, new String[]{"rm", "-f", LOG}, 10);
        String script = "#!/bin/sh\nexit 0\n";

        UserDataPipeline.ExecutionResult result = UserDataPipeline.executeUserData(
                dockerClient, dockerClient, containerId, "test guest", script, Duration.ofSeconds(30), null, null,
                LOG);

        assertTrue(result.isSuccess(), result.getFailureMessage());
        ContainerExec.Result size = ContainerExec.run(dockerClient, containerId, new String[]{"wc", "-c", LOG}, 10);
        assertEquals(0, size.exitCode(), size.stderr());
        assertTrue(size.stdout().trim().startsWith("0 "), size.stdout());
    }

    @Test
    void doesNotWaitForABackgroundServiceThatClosesItsStandardStreams() {
        String script = "#!/bin/sh\n"
                + "nohup sleep 300 </dev/null >/dev/null 2>&1 &\n"
                + "echo started\n"
                + "exit 0\n";

        long startedAt = System.nanoTime();
        UserDataPipeline.ExecutionResult result = UserDataPipeline.executeUserData(
                dockerClient, dockerClient, containerId, "background service", script, Duration.ofSeconds(15), null,
                null, LOG);

        assertTrue(result.isSuccess(), result.getFailureMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - startedAt).toSeconds() < 15,
                "the wrapper must not wait for a background service it started");
        assertTrue(readLog().contains("started\n"));
    }

    @Test
    void runsTheScriptOnceWhenTheGuestLogIsNotWritable(@TempDir Path readOnlyLog) {
        String guest = startGuestWithReadOnlyBind(readOnlyLog, "/var/log");
        try {
            String script = "#!/bin/sh\necho ran-once >> /var/lib/floci-ran\nexit 7\n";

            UserDataPipeline.ExecutionResult result = execute(guest, script);

            assertFalse(result.isSuccess());
            assertEquals(7L, result.getExitCode(), "the fallback must preserve the script's exit status");
            assertEquals("ran-once\n", readFile(guest, "/var/lib/floci-ran"),
                    "the script must run exactly once when the log cannot be created");
        } finally {
            removeGuest(guest);
        }
    }

    private String startGuestWithReadOnlyBind(Path hostDirectory, String containerPath) {
        Bind bind = new Bind(hostDirectory.toString(), new Volume(containerPath), AccessMode.ro);
        CreateContainerResponse container = dockerClient.createContainerCmd(TestImages.PYTHON_ALPINE)
                .withName("floci-user-data-log-" + UUID.randomUUID())
                .withCmd("tail", "-f", "/dev/null")
                .withHostConfig(HostConfig.newHostConfig().withBinds(bind))
                .exec();
        dockerClient.startContainerCmd(container.getId()).exec();
        return container.getId();
    }

    private UserDataPipeline.ExecutionResult execute(String guest, String userData) {
        return UserDataPipeline.executeUserData(dockerClient, dockerClient, guest, "test guest", userData,
                Duration.ofSeconds(30), null, null, LOG);
    }

    private String readFile(String guest, String path) {
        ContainerExec.Result result = ContainerExec.run(dockerClient, guest, new String[]{"cat", path}, 10);
        assertEquals(0, result.exitCode(), result.stderr());
        return result.stdout();
    }

    private void removeGuest(String guest) {
        dockerClient.removeContainerCmd(guest).withForce(true).exec();
    }

    private String readLog() {
        ContainerExec.Result result = ContainerExec.run(dockerClient, containerId,
                new String[]{"cat", LOG}, 10);
        assertEquals(0, result.exitCode(), result.stderr());
        return result.stdout();
    }
}
