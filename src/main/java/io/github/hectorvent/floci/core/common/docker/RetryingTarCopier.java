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
import java.util.concurrent.atomic.AtomicReference;

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
            AtomicReference<Throwable> writerFailure = new AtomicReference<>();
            try (PipedOutputStream pos = new PipedOutputStream();
                 PipedInputStream pis = new PipedInputStream(pos, TAR_PIPE_BUFFER_BYTES)) {
                Thread streamer = new Thread(() -> {
                    try (pos) {
                        tarWriter.write(pos);
                    } catch (Throwable e) {
                        // Recorded and rechecked below instead of just logged: closing the pipe
                        // here without producing output looks like a clean, empty tar to the
                        // reader — the daemon accepts it and exec() below returns normally,
                        // so a writer failure (e.g. the source file went missing) would otherwise
                        // report success while nothing (or a truncated file) was actually copied.
                        writerFailure.set(e);
                        LOG.debugv("Tar streamer for {0} ended early: {1}", label, e.getMessage());
                    }
                }, "tar-streamer-" + label);
                streamer.start();
                docker.copyArchiveToContainerCmd(containerId)
                        .withRemotePath(remotePath)
                        .withTarInputStream(pis)
                        .exec();
                // Joined before the try-with-resources closes pos/pis on the way out: a real
                // exec() only returns after consuming the tar to EOF, which the streamer causes
                // by closing pos once it's done, so this never blocks in production — but closing
                // pos out from under a still-writing streamer (as a mocked/short-circuited exec()
                // would) turns its own write into a spurious "Pipe closed" failure.
                streamer.join();
            }
            Throwable failure = writerFailure.get();
            if (failure instanceof Exception ex) {
                throw ex;
            } else if (failure != null) {
                throw new IOException("Tar streamer for " + label + " failed", failure);
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
