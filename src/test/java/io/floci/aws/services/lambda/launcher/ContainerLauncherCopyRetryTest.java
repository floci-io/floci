package io.floci.aws.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import io.floci.aws.config.EmulatorConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Directory copies retry with a fresh tar stream when the transport cannot replay its body. */
class ContainerLauncherCopyRetryTest {

    private static final int MAX_ATTEMPTS = 3;

    private static RuntimeException brokenPipe() {
        return new RuntimeException(new IOException("Broken pipe"));
    }

    private ContainerLauncher launcher() {
        // The constructor sizes its code-volume populate semaphore off the config, so a bare null
        // no longer builds; the copy paths under test read nothing else from it.
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().lambda().codeVolumePopulateConcurrency()).thenReturn(Optional.empty());
        return new ContainerLauncher(null, null, null, null, null, null, config, null, null, null, null);
    }

    @Test
    void copyDirToContainerRetriesTransientBrokenPipe(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("index.js"), "exports.handler = () => {};");
        DockerClient docker = mock(DockerClient.class);
        CopyArchiveToContainerCmd cmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        AtomicInteger calls = new AtomicInteger();
        when(cmd.exec()).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw brokenPipe();
            }
            return null;
        });
        when(docker.copyArchiveToContainerCmd(any())).thenReturn(cmd);

        launcher().copyDirToContainer(docker, "container-1", dir, "/var/task", "fn",
                MAX_ATTEMPTS, 0L);

        assertEquals(2, calls.get(),
                "each attempt rebuilds the pipe and streamer thread, so the dir copy may retry");
    }
}
