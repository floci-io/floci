package io.github.hectorvent.floci.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The transport-level retry ({@code RetryingDockerHttpClient}) must refuse to replay a one-shot
 * {@code InputStream} body — after the first attempt the streamed tar bytes are gone. That leaves
 * the Lambda launcher's tar-copies (function code, layers, and the TLS CA cert) exposed to
 * transient broken pipes: one blip while copying {@code floci-selfsigned.crt} failed an entire
 * LZA Accounts stage with {@code Lambda.InitError}. The copy is idempotent (tar extract over the
 * same path), and the whole operation — fresh pipe, fresh streamer thread, fresh request — can be
 * rebuilt per attempt, so the retry lives at the call site.
 */
class ContainerLauncherCopyRetryTest {

    private static final int MAX_ATTEMPTS = 3;

    private static RuntimeException brokenPipe() {
        return new RuntimeException(new IOException("Broken pipe"));
    }

    private ContainerLauncher launcher() {
        return new ContainerLauncher(null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void copyFileToContainerRetriesTransientBrokenPipe(@TempDir Path dir) throws IOException {
        Path cert = dir.resolve("floci-selfsigned.crt");
        Files.writeString(cert, "-----BEGIN CERTIFICATE-----");
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

        launcher().copyFileToContainer(docker, "container-1", cert, "/etc", "floci-ca.crt", "fn",
                MAX_ATTEMPTS, 0L);

        assertEquals(2, calls.get(),
                "a broken pipe on the cert copy is a socket blip, not a launch failure");
    }

    @Test
    void copyFileToContainerDoesNotRetryDaemonRejection(@TempDir Path dir) throws IOException {
        Path cert = dir.resolve("floci-selfsigned.crt");
        Files.writeString(cert, "-----BEGIN CERTIFICATE-----");
        DockerClient docker = mock(DockerClient.class);
        CopyArchiveToContainerCmd cmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        RuntimeException noSuchContainer = new RuntimeException("No such container: container-1");
        AtomicInteger calls = new AtomicInteger();
        when(cmd.exec()).thenAnswer(inv -> {
            calls.incrementAndGet();
            throw noSuchContainer;
        });
        when(docker.copyArchiveToContainerCmd(any())).thenReturn(cmd);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> launcher().copyFileToContainer(docker, "container-1", cert, "/etc",
                        "floci-ca.crt", "fn", MAX_ATTEMPTS, 0L));

        assertSame(noSuchContainer, thrown.getCause(),
                "a genuine daemon rejection must surface after a single attempt");
        assertEquals(1, calls.get());
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
