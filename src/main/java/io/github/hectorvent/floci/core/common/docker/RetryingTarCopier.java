package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Retryable tar-copies into containers, shared by every service that pushes content into a
 * container over the Docker API.
 *
 * <p>The transport-level retry ({@link RetryingDockerHttpClient}) must refuse to replay a
 * one-shot {@code InputStream} body — the streamed tar bytes are gone after the first attempt —
 * so copies retry here instead, where the whole operation (tar bytes or pipe, streamer thread,
 * request) is rebuilt per attempt. Tar extract over the same path is idempotent, so a replay is
 * safe; non-transient failures (daemon rejections) surface after a single attempt, unwrapped.
 */
public final class RetryingTarCopier {

    private static final Logger LOG = Logger.getLogger(RetryingTarCopier.class);

    static final int DEFAULT_MAX_ATTEMPTS = 6;
    static final long DEFAULT_BACKOFF_MS = 500L;

    /**
     * Buffer for the tar-streaming pipe. The default {@link PipedInputStream} buffer is only 1KB,
     * which forces a writer/reader thread hand-off (wait/notify) every 1KB. Streaming a ~90MB
     * node_modules through that ran at ~0.5MB/s (≈3 min per cold start) — pure synchronization
     * thrash, not I/O. A large buffer lets the tar writer stream ahead so throughput is bound by
     * the Docker daemon, not the pipe.
     */
    private static final int TAR_PIPE_BUFFER_BYTES = 16 * 1024 * 1024;

    private RetryingTarCopier() {
    }

    /** Writes one attempt's tar content; invoked freshly per attempt. */
    @FunctionalInterface
    public interface TarWriter {
        void write(OutputStream out) throws IOException;
    }

    /** Copies an in-memory payload as a single-entry tar. */
    public static void copyBytes(DockerClient docker, String containerId, String remotePath,
                                 String entryName, byte[] content, int mode) {
        copyBytes(docker, containerId, remotePath, entryName, content, mode,
                DEFAULT_MAX_ATTEMPTS, DEFAULT_BACKOFF_MS);
    }

    public static void copyBytes(DockerClient docker, String containerId, String remotePath,
                                 String entryName, byte[] content, int mode,
                                 int maxAttempts, long backoffMillis) {
        byte[] tar = singleFileTar(entryName, content, mode);
        retry(entryName, containerId, maxAttempts, backoffMillis, () ->
                docker.copyArchiveToContainerCmd(containerId)
                        .withRemotePath(remotePath)
                        .withTarInputStream(new ByteArrayInputStream(tar))
                        .exec());
    }

    /** Streams a file from disk as a single-entry tar, without buffering it in memory. */
    public static void copyFile(DockerClient docker, String containerId, String remotePath,
                                String entryName, Path sourceFile, int mode) {
        copyFile(docker, containerId, remotePath, entryName, sourceFile, mode,
                DEFAULT_MAX_ATTEMPTS, DEFAULT_BACKOFF_MS);
    }

    public static void copyFile(DockerClient docker, String containerId, String remotePath,
                                String entryName, Path sourceFile, int mode,
                                int maxAttempts, long backoffMillis) {
        copyStreamed(docker, containerId, remotePath, sourceFile.toString(), out -> {
            try (TarArchiveOutputStream tar = newTarStream(out)) {
                TarArchiveEntry entry = new TarArchiveEntry(entryName);
                entry.setSize(Files.size(sourceFile));
                entry.setMode(mode);
                tar.putArchiveEntry(entry);
                try (var fis = Files.newInputStream(sourceFile)) {
                    fis.transferTo(tar);
                }
                tar.closeArchiveEntry();
            }
        }, maxAttempts, backoffMillis);
    }

    /**
     * Streams arbitrary tar content produced by {@code tarWriter} through a fresh pipe and
     * streamer thread per attempt. {@code label} names the payload in errors and thread names.
     */
    public static void copyStreamed(DockerClient docker, String containerId, String remotePath,
                                    String label, TarWriter tarWriter) {
        copyStreamed(docker, containerId, remotePath, label, tarWriter,
                DEFAULT_MAX_ATTEMPTS, DEFAULT_BACKOFF_MS);
    }

    public static void copyStreamed(DockerClient docker, String containerId, String remotePath,
                                    String label, TarWriter tarWriter,
                                    int maxAttempts, long backoffMillis) {
        retry(label, containerId, maxAttempts, backoffMillis, () -> {
            try (PipedOutputStream pos = new PipedOutputStream();
                 PipedInputStream pis = new PipedInputStream(pos, TAR_PIPE_BUFFER_BYTES)) {
                Thread streamer = new Thread(() -> {
                    try (pos) {
                        tarWriter.write(pos);
                    } catch (IOException e) {
                        // The reader side sees the truncated stream and fails the attempt; an
                        // abandoned pipe from a retried attempt lands here too, harmlessly.
                        LOG.debugv("Tar streamer for {0} ended early: {1}", label, e.getMessage());
                    }
                }, "tar-streamer-" + label);
                streamer.start();
                docker.copyArchiveToContainerCmd(containerId)
                        .withRemotePath(remotePath)
                        .withTarInputStream(pis)
                        .exec();
            }
        });
    }

    /** Copies a host path, letting docker-java build the tar; rebuilt per attempt. */
    public static void copyHostResource(DockerClient docker, String containerId, String remotePath,
                                        String hostPath) {
        copyHostResource(docker, containerId, remotePath, hostPath,
                DEFAULT_MAX_ATTEMPTS, DEFAULT_BACKOFF_MS);
    }

    public static void copyHostResource(DockerClient docker, String containerId, String remotePath,
                                        String hostPath, int maxAttempts, long backoffMillis) {
        retry(hostPath, containerId, maxAttempts, backoffMillis, () ->
                docker.copyArchiveToContainerCmd(containerId)
                        .withHostResource(hostPath)
                        .withRemotePath(remotePath)
                        .exec());
    }

    /** Single-entry tar bytes, reusable across attempts and callers. */
    public static byte[] singleFileTar(String entryName, byte[] content, int mode) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (TarArchiveOutputStream tar = newTarStream(out)) {
                TarArchiveEntry entry = new TarArchiveEntry(entryName);
                entry.setSize(content.length);
                entry.setMode(mode);
                tar.putArchiveEntry(entry);
                tar.write(content);
                tar.closeArchiveEntry();
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build in-memory tar for " + entryName, e);
        }
    }

    /** GNU long names + star big numbers so any path length and file size survive the archive. */
    public static TarArchiveOutputStream newTarStream(OutputStream out) {
        TarArchiveOutputStream tar = new TarArchiveOutputStream(out);
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
        tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR);
        return tar;
    }

    private interface CopyAttempt {
        void run() throws Exception;
    }

    private static void retry(String label, String containerId, int maxAttempts, long backoffMillis,
                              CopyAttempt attempt) {
        try {
            DockerRetry.run(maxAttempts, backoffMillis, attempt::run);
        } catch (Exception e) {
            throw new RuntimeException("Failed to copy " + label + " into container "
                    + containerId + ": " + e.getMessage(), e);
        }
    }
}
