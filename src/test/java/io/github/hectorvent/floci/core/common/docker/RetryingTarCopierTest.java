package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The transport-level retry ({@code RetryingDockerHttpClient}) must refuse to replay a one-shot
 * {@code InputStream} body — the streamed tar bytes are gone after the first attempt. Every
 * tar-copy into a container therefore needs its retry at the call site, rebuilding the whole
 * operation (tar bytes or pipe, streamer thread, request) per attempt. This module is that call
 * site, shared by every service that copies content into containers; tar extract over the same
 * path is idempotent, so replay is safe.
 */
class RetryingTarCopierTest {

    private static final int MAX_ATTEMPTS = 3;

    private static RuntimeException brokenPipe() {
        return new RuntimeException(new IOException("Broken pipe"));
    }

    private static CopyArchiveToContainerCmd failingOnce(DockerClient docker, AtomicInteger calls) {
        CopyArchiveToContainerCmd cmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(cmd.exec()).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw brokenPipe();
            }
            return null;
        });
        when(docker.copyArchiveToContainerCmd(any())).thenReturn(cmd);
        return cmd;
    }

    @Test
    void copyBytesRetriesWithAFreshStreamPerAttempt() {
        DockerClient docker = mock(DockerClient.class);
        AtomicInteger calls = new AtomicInteger();
        CopyArchiveToContainerCmd cmd = failingOnce(docker, calls);

        RetryingTarCopier.copyBytes(docker, "c-1", "/etc", "registries.yaml",
                "mirrors: {}".getBytes(StandardCharsets.UTF_8), 0644, MAX_ATTEMPTS, 0L);

        assertEquals(2, calls.get(), "a broken pipe on a byte copy is a socket blip, not a failure");
        ArgumentCaptor<InputStream> streams = ArgumentCaptor.forClass(InputStream.class);
        verify(cmd, atLeastOnce()).withTarInputStream(streams.capture());
        assertEquals(2, streams.getAllValues().size(), "each attempt must send its own stream");
        assertNotSame(streams.getAllValues().get(0), streams.getAllValues().get(1),
                "replaying a consumed stream would upload zero bytes");
    }

    @Test
    void copyBytesDoesNotRetryDaemonRejection() {
        DockerClient docker = mock(DockerClient.class);
        CopyArchiveToContainerCmd cmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        RuntimeException noSuchContainer = new RuntimeException("No such container: c-1");
        AtomicInteger calls = new AtomicInteger();
        when(cmd.exec()).thenAnswer(inv -> {
            calls.incrementAndGet();
            throw noSuchContainer;
        });
        when(docker.copyArchiveToContainerCmd(any())).thenReturn(cmd);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> RetryingTarCopier.copyBytes(docker, "c-1", "/etc", "f", new byte[]{1},
                        0644, MAX_ATTEMPTS, 0L));

        assertSame(noSuchContainer, thrown.getCause(),
                "a genuine daemon rejection must surface after a single attempt");
        assertEquals(1, calls.get());
    }

    @Test
    void copyFileRebuildsThePipePerAttempt(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("bootstrap");
        Files.writeString(file, "#!/bin/sh\n");
        DockerClient docker = mock(DockerClient.class);
        AtomicInteger calls = new AtomicInteger();
        failingOnce(docker, calls);

        RetryingTarCopier.copyFile(docker, "c-1", "/var/runtime", "bootstrap", file, 0755,
                MAX_ATTEMPTS, 0L);

        assertEquals(2, calls.get());
    }

    @Test
    void copyStreamedInvokesTheWriterFreshlyPerAttempt() {
        DockerClient docker = mock(DockerClient.class);
        AtomicInteger calls = new AtomicInteger();
        failingOnce(docker, calls);
        AtomicInteger writes = new AtomicInteger();

        RetryingTarCopier.copyStreamed(docker, "c-1", "/var/task", "fn-code",
                out -> {
                    writes.incrementAndGet();
                    out.write("x".getBytes(StandardCharsets.UTF_8));
                },
                MAX_ATTEMPTS, 0L);

        assertEquals(2, calls.get());
        assertEquals(2, writes.get(), "the tar content must be regenerated for every attempt");
    }

    @Test
    void copyHostResourceRetriesTransientBrokenPipe(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("kubeconfig.yaml");
        Files.writeString(file, "apiVersion: v1");
        DockerClient docker = mock(DockerClient.class);
        AtomicInteger calls = new AtomicInteger();
        failingOnce(docker, calls);

        RetryingTarCopier.copyHostResource(docker, "c-1", "/var/lib/rancher", file.toString(),
                MAX_ATTEMPTS, 0L);

        assertEquals(2, calls.get());
    }

    @Test
    void singleFileTarPreservesNameContentAndMode() throws IOException {
        byte[] content = "ssh-ed25519 AAAA".getBytes(StandardCharsets.UTF_8);

        byte[] tar = RetryingTarCopier.singleFileTar("authorized_keys", content, 0600);

        try (TarArchiveInputStream in = new TarArchiveInputStream(new ByteArrayInputStream(tar))) {
            TarArchiveEntry entry = in.getNextEntry();
            assertEquals("authorized_keys", entry.getName());
            assertEquals(0600, entry.getMode() & 07777, "an ssh key copied world-readable is a defect");
            assertEquals(new String(content, StandardCharsets.UTF_8),
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
