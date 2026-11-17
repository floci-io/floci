package io.floci.aws.core.common;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Appends request-log lines to the log file from a single thread of its own, so a slow or stalled file never holds the
 * request threads that produce the lines. Lines wait in a bounded queue; when it is full a line is dropped and counted
 * rather than waited for. The file is created readable by its owner only, and only a regular file is written: a
 * symbolic link, a named pipe or a directory at the path is refused, so the log cannot be redirected elsewhere.
 */
final class RequestLogWriter {

    private static final Logger LOG = Logger.getLogger(RequestLogWriter.class);

    /** Lines the writer may fall behind by before new ones are dropped. */
    static final int QUEUE_CAPACITY = 10_000;

    private final Path target;
    private final BlockingQueue<String> lines = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private Thread worker;
    private FileChannel channel;

    RequestLogWriter(Path target) {
        this.target = target;
    }

    /** Queues one line for the log, dropping it when the writer is too far behind. */
    void append(String line) {
        startIfNeeded();
        if (!lines.offer(line)) {
            long count = dropped.incrementAndGet();
            if (count == 1 || count % 1_000 == 0) {
                LOG.warnv("Request log: {0} lines dropped because {1} is not keeping up", count, target);
            }
        }
    }

    private synchronized void startIfNeeded() {
        if (worker != null) {
            return;
        }
        worker = new Thread(this::drain, "floci-request-log");
        worker.setDaemon(true);
        worker.start();
    }

    private void drain() {
        while (!Thread.currentThread().isInterrupted()) {
            String line;
            try {
                line = lines.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            write(line);
        }
    }

    private void write(String line) {
        try {
            if (channel == null) {
                channel = open(target);
            }
            ByteBuffer bytes = ByteBuffer.wrap((line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
        } catch (IOException | RuntimeException e) {
            LOG.warnv(e, "Request log: could not write {0}", target);
            closeQuietly();
        }
    }

    /**
     * Opens the log for appending, creating it owner-only first. The path must name a regular file once its symbolic
     * links are not followed, and the open itself does not follow one either, so a link swapped in between is refused.
     */
    static FileChannel open(Path target) throws IOException {
        createOwnerOnly(target);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The request log path is not a regular file: " + target);
        }
        return FileChannel.open(target, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
                LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Creates the log readable and writable by its owner alone where the file system supports POSIX permissions, since
     * request and response bodies, secrets included, are written to it as plain text. Anything already at the path is
     * left as it is.
     */
    static void createOwnerOnly(Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            if (target.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createFile(target, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(target);
            }
        } catch (FileAlreadyExistsException e) {
            // Created by someone else first; it keeps what it was created with.
        }
    }

    private void closeQuietly() {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException e) {
            LOG.debugv(e, "Request log: could not close {0}", target);
        }
        channel = null;
    }
}
