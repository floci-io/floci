package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import io.floci.aws.services.lambda.launcher.ImageCacheService;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

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

    private String readLog() {
        ContainerExec.Result result = ContainerExec.run(dockerClient, containerId,
                new String[]{"cat", LOG}, 10);
        assertEquals(0, result.exitCode(), result.stderr());
        return result.stdout();
    }
}
