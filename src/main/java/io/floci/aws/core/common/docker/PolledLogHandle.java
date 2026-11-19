package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.floci.aws.core.common.docker.ContainerLogStreamer.LogLineBuffer;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Reads a long-lived container's log on a timer instead of holding a follow stream for its whole life:
 * every {@code interval} one short {@code logs?since=} request returns what the container wrote since the
 * last read, and the connection goes back to the pool. Lines reach {@code sink} in the order the container
 * wrote them, each once.
 *
 * <p>Docker's {@code since} is whole seconds, so each read starts at the second of the last record seen
 * and gets some records again. The cursor is that record's timestamp and how many records carried it,
 * so a repeated record is skipped and a new one with the same timestamp is not, which a "last timestamp
 * plus a nanosecond" cursor would drop. A line split across reads is held per stream until its newline.
 *
 * <p>{@link #drainBeforeRemove} reads once more on the caller's thread, for the container's last lines
 * between its stop and its removal; {@link #close} reads a last time without blocking and then flushes
 * any unterminated line.
 */
final class PolledLogHandle implements Closeable {

    private static final Logger LOG = Logger.getLogger(PolledLogHandle.class);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);
    private static final int TIMESTAMP_MAX_BYTES = 40;

    // Idle threads time out, as the close-drain scheduler's do, so the poller pins no classloader.
    private static final ScheduledThreadPoolExecutor POLLER = poller();

    private static ScheduledThreadPoolExecutor poller() {
        ScheduledThreadPoolExecutor poller = new ScheduledThreadPoolExecutor(2, runnable -> {
            Thread thread = new Thread(runnable, "floci-container-log-poll");
            thread.setDaemon(true);
            return thread;
        });
        poller.setKeepAliveTime(5, TimeUnit.SECONDS);
        poller.allowCoreThreadTimeOut(true);
        poller.setRemoveOnCancelPolicy(true);
        return poller;
    }

    private final DockerClient docker;
    private final String containerId;
    private final BiConsumer<StreamType, String> sink;
    private final Object readLock = new Object();
    private final Object stateLock = new Object();
    private final Map<StreamType, LogLineBuffer> buffers = new EnumMap<>(StreamType.class);
    private final Map<StreamType, Boolean> includeContinuation = new EnumMap<>(StreamType.class);
    private Instant cursor;
    private int recordsAtCursor;
    private int seenAtCursor;
    private boolean finished;
    private volatile boolean stopped;
    private ScheduledFuture<?> schedule;

    /** {@code since} of null reads the container's whole log; otherwise only what it writes from then on. */
    PolledLogHandle(DockerClient docker, String containerId, Instant since, BiConsumer<StreamType, String> sink) {
        this.docker = docker;
        this.containerId = containerId;
        this.sink = sink;
        this.cursor = since == null ? Instant.EPOCH : since;
    }

    /** Starts reading every {@code interval}, the first read at once. */
    PolledLogHandle start(Duration interval) {
        schedule = POLLER.scheduleWithFixedDelay(this::readSafely, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        return this;
    }

    /** Reads what the container wrote since the last read, on the caller's thread; for between its stop and its removal. */
    void drainBeforeRemove() {
        readSafely();
    }

    @Override
    public void close() throws IOException {
        if (stopped) {
            return;
        }
        stopped = true;
        if (schedule != null) {
            schedule.cancel(false);
        }
        POLLER.execute(() -> {
            read();
            finish();
        });
    }

    private void readSafely() {
        try {
            read();
        } catch (RuntimeException e) {
            LOG.debugv("Could not read the log of container {0}: {1}", containerId, e.getMessage());
        }
    }

    /** One {@code logs?since=} read; returns quietly when the container is gone, and stops reading it. */
    void read() {
        synchronized (readLock) {
            if (!beginRead()) {
                return;
            }
            ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
                @Override
                public void onNext(Frame frame) {
                    if (frame != null && frame.getPayload() != null) {
                        accept(frame.getStreamType(), frame.getPayload());
                    }
                }
            };
            try (LogContainerCmd command = docker.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(false)
                    .withTimestamps(true)
                    .withSince(sinceSeconds())) {
                if (!command.exec(callback).awaitCompletion(READ_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    LOG.debugv("Reading the log of container {0} took over {1} s; the next read resumes it",
                            containerId, READ_TIMEOUT.toSeconds());
                }
            } catch (NotFoundException e) {
                stopReading();
                finish();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                closeQuietly(callback);
            }
        }
    }

    /** Starts counting the records at the cursor afresh for a new read; false once finished. */
    boolean beginRead() {
        synchronized (stateLock) {
            seenAtCursor = 0;
            return !finished;
        }
    }

    private int sinceSeconds() {
        synchronized (stateLock) {
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0, cursor.getEpochSecond()));
        }
    }

    /** One frame of a timestamped read: records of {@code <RFC 3339 timestamp> <content>}, each up to its newline. */
    void accept(StreamType stream, byte[] payload) {
        synchronized (stateLock) {
            if (finished) {
                return;
            }
            int offset = 0;
            while (offset < payload.length) {
                int end = offset;
                while (end < payload.length && payload[end] != '\n') {
                    end++;
                }
                if (end < payload.length) {
                    end++;
                }
                acceptRecord(stream, payload, offset, end);
                offset = end;
            }
        }
    }

    private void acceptRecord(StreamType stream, byte[] payload, int start, int end) {
        int space = -1;
        for (int i = start; i < Math.min(end, start + TIMESTAMP_MAX_BYTES); i++) {
            if (payload[i] == ' ') {
                space = i;
                break;
            }
        }
        Instant timestamp = null;
        if (space > start) {
            try {
                timestamp = Instant.parse(new String(payload, start, space - start, StandardCharsets.US_ASCII));
            } catch (DateTimeParseException expected) {
                // A record can continue without a timestamp of its own; it shares the previous one's fate.
            }
        }
        if (timestamp == null) {
            if (includeContinuation.getOrDefault(stream, false)) {
                emit(stream, payload, start, end);
            }
            return;
        }
        boolean include = isNew(timestamp);
        includeContinuation.put(stream, include);
        if (include) {
            emit(stream, payload, space + 1, end);
        }
    }

    /** Whether a record with this timestamp is past the cursor, moving the cursor when it is. */
    private boolean isNew(Instant timestamp) {
        int order = timestamp.compareTo(cursor);
        if (order < 0) {
            return false;
        }
        if (order == 0) {
            seenAtCursor++;
            if (seenAtCursor <= recordsAtCursor) {
                return false;
            }
            recordsAtCursor++;
            return true;
        }
        cursor = timestamp;
        recordsAtCursor = 1;
        seenAtCursor = 1;
        return true;
    }

    private void emit(StreamType stream, byte[] payload, int start, int end) {
        if (end <= start) {
            return;
        }
        byte[] content = new byte[end - start];
        System.arraycopy(payload, start, content, 0, content.length);
        for (String line : buffers.computeIfAbsent(stream, ignored -> new LogLineBuffer()).append(content)) {
            sink.accept(stream, line);
        }
    }

    private void stopReading() {
        stopped = true;
        if (schedule != null) {
            schedule.cancel(false);
        }
    }

    /** Hands on each stream's unterminated last line and ignores anything read after. */
    void finish() {
        synchronized (stateLock) {
            if (finished) {
                return;
            }
            finished = true;
            buffers.forEach((stream, buffer) -> buffer.flush().ifPresent(line -> sink.accept(stream, line)));
        }
    }

    boolean isReading() {
        return !stopped;
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException e) {
            LOG.debugv("Could not close a container log read: {0}", e.getMessage());
        }
    }
}
