package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.core.util.CompressArchiveUtil;
import com.github.dockerjava.transport.DockerHttpClient;
import io.floci.aws.testing.DockerArchiveUploadFixture;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * The transport-level retry ({@code RetryingDockerHttpClient}) must refuse to replay a one-shot
 * {@code InputStream} body, the streamed tar bytes are gone after the first attempt. Every
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

    private static DockerClient dockerClient(DockerHttpClient transport) {
        return DockerClientImpl.getInstance(DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("tcp://localhost:2375")
                .withApiVersion("1.44")
                .build(), transport);
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

    @ParameterizedTest
    @ValueSource(ints = {0, 73, 8192})
    void copyArchiveReplaysTheExactBytesWithoutAStreamingPipe(int consumedBytes) {
        DockerClient docker = mock(DockerClient.class);
        DockerArchiveUploadFixture uploads = new DockerArchiveUploadFixture(docker, "c-1");
        uploads.failures = 1;
        uploads.consumedBytes = consumedBytes;
        uploads.failure = brokenPipe();
        byte[] archive = RetryingTarCopier.singleFileTar("config", new byte[16384], 0644);

        RetryingTarCopier.copyArchive(docker, "c-1", "/etc", "config-archive", archive, MAX_ATTEMPTS, 0L);

        assertEquals(2, uploads.commands.size());
        assertEquals(1, uploads.completed.size());
        assertArrayEquals(archive, uploads.completed.getFirst(), "an existing tar must not be wrapped in another tar");
        assertNotSame(uploads.streams.getFirst(), uploads.streams.get(1));
        for (int attempt = 0; attempt < uploads.commands.size(); attempt++) {
            assertInstanceOf(ByteArrayInputStream.class, uploads.streams.get(attempt));
            verify(uploads.commands.get(attempt)).withRemotePath("/etc");
        }
    }

    @Test
    void copyArchiveStopsAtTheRetryBudget() {
        DockerClient docker = mock(DockerClient.class);
        DockerArchiveUploadFixture uploads = new DockerArchiveUploadFixture(docker, "c-1");
        uploads.failures = Integer.MAX_VALUE;
        uploads.failure = brokenPipe();

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> RetryingTarCopier.copyArchive(docker, "c-1", "/etc", "config-archive",
                        new byte[1024], MAX_ATTEMPTS, 0L));

        assertSame(uploads.failure, thrown.getCause());
        assertTrue(thrown.getMessage().contains("config-archive"), thrown.getMessage());
        assertEquals(MAX_ATTEMPTS, uploads.commands.size());
        assertTrue(uploads.completed.isEmpty());
    }

    @Test
    void copyArchiveDoesNotRetryDaemonRejection() {
        DockerClient docker = mock(DockerClient.class);
        DockerArchiveUploadFixture uploads = new DockerArchiveUploadFixture(docker, "c-1");
        uploads.failures = Integer.MAX_VALUE;
        uploads.failure = new NotFoundException("container missing");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> RetryingTarCopier.copyArchive(docker, "c-1", "/etc", "config-archive",
                        new byte[1024], MAX_ATTEMPTS, 0L));

        assertSame(uploads.failure, thrown.getCause());
        assertEquals(1, uploads.commands.size());
    }

    // copyFile streams tarWriter through a pipe on its own thread; if the writer fails before
    // producing any bytes (e.g. the source file vanished), closing the pipe with nothing written
    // looks like a clean, empty tar to a reader that isn't checking for that, the daemon accepts
    // it and exec() returns normally, so the caller would otherwise see a false success instead
    // of the real failure.
    @Test
    void copyFileFailsTheAttemptWhenTheSourceFileIsMissingInsteadOfReportingSuccess(@TempDir Path tempDir) {
        DockerClient docker = mock(DockerClient.class);
        CopyArchiveToContainerCmd cmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(cmd.exec()).thenAnswer(inv -> null);
        when(docker.copyArchiveToContainerCmd(any())).thenReturn(cmd);
        Path missing = tempDir.resolve("does-not-exist");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> RetryingTarCopier.copyFile(docker, "c-1", "/etc", "f", missing, 0644, 1, 0L));

        Throwable cause = thrown.getCause();
        while (cause != null && !(cause instanceof NoSuchFileException)) {
            cause = cause.getCause();
        }
        assertEquals(NoSuchFileException.class, cause == null ? null : cause.getClass(),
                "the streamer's real failure must surface, not a phantom success");
    }

    @Test
    void copyStreamedDoesNotRetryALocalWriterFailure() {
        // Catches: a local tar-writer IOException (unreadable source file) being retried as a
        // transient daemon blip, rebuilding the copy several times with backoff.
        DockerClient docker = mock(DockerClient.class);
        CopyArchiveToContainerCmd cmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(cmd.exec()).thenAnswer(inv -> null);
        when(docker.copyArchiveToContainerCmd(any())).thenReturn(cmd);
        AtomicInteger writes = new AtomicInteger();

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> RetryingTarCopier.copyStreamed(docker, "c-1", "/var/task", "fn-code",
                        out -> {
                            writes.incrementAndGet();
                            throw new IOException("Permission denied");
                        },
                        MAX_ATTEMPTS, 0L));

        assertEquals(1, writes.get());
        assertTrue(thrown.getMessage().contains("Permission denied"), thrown.getMessage());
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
    void copyStreamedInvokesTheWriterFreshlyPerAttempt() throws InterruptedException {
        DockerClient docker = mock(DockerClient.class);
        AtomicInteger calls = new AtomicInteger();
        failingOnce(docker, calls);
        CountDownLatch writes = new CountDownLatch(2);

        RetryingTarCopier.copyStreamed(docker, "c-1", "/var/task", "fn-code",
                out -> {
                    writes.countDown();
                    out.write("x".getBytes(StandardCharsets.UTF_8));
                },
                MAX_ATTEMPTS, 0L);

        assertEquals(2, calls.get());
        // A failed attempt's writer is never joined, so it may still be starting when copyStreamed returns.
        assertTrue(writes.await(5, TimeUnit.SECONDS), "the tar content must be regenerated for every attempt");
    }

    @Test
    void copyHostResourceRetriesTransientBrokenPipe(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("kubeconfig.yaml");
        Files.writeString(file, "apiVersion: v1");
        DockerHttpClient transport = mock(DockerHttpClient.class);
        DockerHttpClient.Response ok = mock(DockerHttpClient.Response.class);
        when(ok.getStatusCode()).thenReturn(200);
        when(transport.execute(any())).thenThrow(brokenPipe()).thenReturn(ok);

        try (DockerClient docker = dockerClient(transport)) {
            RetryingTarCopier.copyHostResource(docker, "c-1", "/var/lib/rancher", file.toString(),
                    MAX_ATTEMPTS, 0L);
        }

        verify(transport, times(2)).execute(any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"Docker transport failure"})
    void copyHostResourceStillRetriesTransportClientExceptions(String message, @TempDir Path dir) throws IOException {
        Path file = dir.resolve("kubeconfig.yaml");
        Files.writeString(file, "apiVersion: v1");
        DockerHttpClient transport = mock(DockerHttpClient.class);
        DockerHttpClient.Response ok = mock(DockerHttpClient.Response.class);
        when(ok.getStatusCode()).thenReturn(200);
        when(transport.execute(any()))
                .thenThrow(new DockerClientException(message, new IOException("Broken pipe"))).thenReturn(ok);

        try (DockerClient docker = dockerClient(transport)) {
            RetryingTarCopier.copyHostResource(docker, "c-1", "/var/lib/rancher", file.toString(),
                    MAX_ATTEMPTS, 0L);
        }

        verify(transport, times(2)).execute(any());
    }

    @Test
    void copyHostResourceDoesNotRetryLocalArchiveFailure(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("kubeconfig.yaml");
        Files.writeString(file, "apiVersion: v1");
        DockerHttpClient transport = mock(DockerHttpClient.class);
        IOException archiveFailure = new IOException("No space left on device");
        AtomicInteger archiveAttempts = new AtomicInteger();

        try (DockerClient docker = dockerClient(transport);
             MockedStatic<CompressArchiveUtil> archives = mockStatic(CompressArchiveUtil.class,
                     withSettings().mockMaker(MockMakers.INLINE).defaultAnswer(CALLS_REAL_METHODS))) {
            archives.when(() -> CompressArchiveUtil.tar(any(Path.class), any(Path.class),
                    anyBoolean(), anyBoolean())).thenAnswer(invocation -> {
                        archiveAttempts.incrementAndGet();
                        throw archiveFailure;
                    });

            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> RetryingTarCopier.copyHostResource(docker, "c-1", "/var/lib/rancher",
                            file.toString(), MAX_ATTEMPTS, 0L));

            Throwable cause = thrown;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertSame(archiveFailure, cause, "the original local archive failure must survive");
            verify(transport, never()).execute(any());
            assertEquals(1, archiveAttempts.get(), "local archive failures cannot recover by retrying Docker");
        }
    }

    @Test
    void copyHostResourceDoesNotRetryLocalArchiveReadFailure(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("kubeconfig.yaml");
        Files.writeString(file, "apiVersion: v1");
        DockerHttpClient transport = mock(DockerHttpClient.class);
        AtomicInteger archiveAttempts = new AtomicInteger();
        AtomicReference<Path> archivePath = new AtomicReference<>();

        try (DockerClient docker = dockerClient(transport);
             MockedStatic<CompressArchiveUtil> archives = mockStatic(CompressArchiveUtil.class,
                     withSettings().mockMaker(MockMakers.INLINE).defaultAnswer(CALLS_REAL_METHODS))) {
            archives.when(() -> CompressArchiveUtil.tar(any(Path.class), any(Path.class),
                    anyBoolean(), anyBoolean())).thenAnswer(invocation -> {
                        archiveAttempts.incrementAndGet();
                        Path archive = invocation.getArgument(1);
                        archivePath.set(archive);
                        Files.delete(archive);
                        // A symlink loop fails when docker-java opens the archive, before HTTP.
                        Files.createSymbolicLink(archive, archive.getFileName());
                        return null;
                    });

            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> RetryingTarCopier.copyHostResource(docker, "c-1", "/var/lib/rancher",
                            file.toString(), MAX_ATTEMPTS, 0L));

            Throwable cause = thrown;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            FileSystemException readFailure = assertInstanceOf(FileSystemException.class, cause);
            assertEquals(archivePath.get().toString(), readFailure.getFile());
            verify(transport, never()).execute(any());
            assertEquals(1, archiveAttempts.get(), "opening a local archive must not spend the Docker retry budget");
        }
    }

    @Test
    void copyHostResourceDoesNotRetryDaemonRejection(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("kubeconfig.yaml");
        Files.writeString(file, "apiVersion: v1");
        DockerHttpClient transport = mock(DockerHttpClient.class);
        DockerHttpClient.Response missingContainer = mock(DockerHttpClient.Response.class);
        when(missingContainer.getStatusCode()).thenReturn(404);
        when(missingContainer.getHeaders()).thenReturn(Map.of());
        when(missingContainer.getBody()).thenAnswer(invocation -> new ByteArrayInputStream(
                "{\"message\":\"No such container: c-1\"}".getBytes(StandardCharsets.UTF_8)));
        when(transport.execute(any())).thenReturn(missingContainer);

        try (DockerClient docker = dockerClient(transport)) {
            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> RetryingTarCopier.copyHostResource(docker, "c-1", "/var/lib/rancher",
                            file.toString(), MAX_ATTEMPTS, 0L));

            assertInstanceOf(NotFoundException.class, thrown.getCause());
        }

        verify(transport, times(1)).execute(any());
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
