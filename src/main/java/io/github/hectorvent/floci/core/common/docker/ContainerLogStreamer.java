package io.github.hectorvent.floci.core.common.docker;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * Streams Docker container logs to both the Floci console logger and CloudWatch Logs.
 * Consolidates the log streaming pattern used across container managers.
 */
@ApplicationScoped
public class ContainerLogStreamer {

    private static final Logger LOG = Logger.getLogger(ContainerLogStreamer.class);
    private static final DateTimeFormatter LOG_STREAM_DATE_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final DockerClient dockerClient;
    private final CloudWatchLogsService cloudWatchLogsService;

    @Inject
    public ContainerLogStreamer(DockerClient dockerClient, CloudWatchLogsService cloudWatchLogsService) {
        this.dockerClient = dockerClient;
        this.cloudWatchLogsService = cloudWatchLogsService;
    }

    /**
     * Attaches a log stream to a container and forwards logs to CloudWatch Logs.
     * Returns a lifecycle handle that should be closed after the container stops. Closing the handle
     * allows Docker's reader a bounded drain period before a non-blocking fallback flushes the tail.
     *
     * @param containerId Docker container ID
     * @param logGroup CloudWatch log group name (e.g., "/aws/lambda/myFunction")
     * @param logStream CloudWatch log stream name (e.g., "2024/01/15/[$LATEST]abc123")
     * @param region AWS region for CloudWatch Logs
     * @param logPrefix prefix for console logging (e.g., "lambda:myFunction")
     * @return Closeable lifecycle handle for the log stream
     */
    public Closeable attach(String containerId, String logGroup, String logStream,
                            String region, String logPrefix) {
        return attachForAccount(
                null, containerId, logGroup, logStream, region, logPrefix);
    }

    public Closeable attachForAccount(
            String accountId, String containerId, String logGroup, String logStream,
            String region, String logPrefix) {
        ensureLogGroupAndStreamForAccount(accountId, logGroup, logStream, region);

        // Trim trailing whitespace and drop blank lines, then fan each reassembled line out to the
        // console logger and CloudWatch Logs.
        Consumer<String> emitter = line -> {
            String trimmed = line.stripTrailing();
            if (!trimmed.isEmpty()) {
                LOG.infov("[{0}] {1}", logPrefix, trimmed);
                forwardToCloudWatchLogs(accountId, logGroup, logStream, region, trimmed);
            }
        };

        try {
            LogReassemblyCallback callback = new LogReassemblyCallback(emitter);
            dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true)
                    .withTimestamps(false)
                    .exec(callback);
            return new ContainerLogHandle(callback);
        } catch (Exception e) {
            LOG.warnv("Could not attach log stream for container {0}: {1}", containerId, e.getMessage());
            return null;
        }
    }

    /**
     * Streams the output of a {@code docker exec} to the same destinations as {@link #attach}.
     *
     * <p>Needed because {@code docker logs} (and therefore {@link #attach}, which uses
     * {@code logContainerCmd}) only covers the container's PID 1 stdout/stderr. An exec runs on a
     * separate Docker stream that never reaches that log, so a Lambda extension started via exec
     * would otherwise produce no CloudWatch output at all — the opposite of what an observability
     * extension is for.
     *
     * <p>The returned callback must be handed to {@code execStartCmd(...).exec(...)} by the caller,
     * which owns the exec's lifecycle. Draining it is required regardless of logging: an undrained
     * exec output pipe fills up and stalls the process on the other end.
     *
     * @param logPrefix console-only prefix (e.g. {@code "lambda:myFn:my-extension"}); the message
     *                  forwarded to CloudWatch is left unprefixed so the log stream matches real
     *                  AWS, which interleaves extension output with the function's own.
     */
    public ResultCallback.Adapter<Frame> execLogCallback(String logGroup, String logStream,
                                                        String region, String logPrefix) {
        return frameCallback(null, logGroup, logStream, region, logPrefix);
    }

    /**
     * Shared frame handling for both the container log stream and exec streams.
     *
     * @param accountId account that owns the destination log stream, or {@code null} for the
     *                  default account
     */
    private ResultCallback.Adapter<Frame> frameCallback(String accountId, String logGroup, String logStream,
                                                        String region, String logPrefix) {
        return new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                if (frame == null || frame.getPayload() == null) {
                    return;
                }
                String line = new String(frame.getPayload(), StandardCharsets.UTF_8).stripTrailing();
                if (!line.isEmpty()) {
                    LOG.infov("[{0}] {1}", logPrefix, line);
                    forwardToCloudWatchLogs(accountId, logGroup, logStream, region, line);
                }
            }
        };
    }

    /**
     * Creates a CloudWatch log group and stream if they don't already exist.
     */
    public void ensureLogGroupAndStream(String logGroup, String logStream, String region) {
        ensureLogGroupAndStreamForAccount(null, logGroup, logStream, region);
    }

    public void ensureLogGroupAndStreamForAccount(
            String accountId, String logGroup, String logStream, String region) {
        try {
            cloudWatchLogsService.createLogGroupForAccount(
                    accountId, logGroup, null, null, region);
        } catch (AwsException ignored) {
            // Already exists
        } catch (Exception e) {
            LOG.warnv("Could not create CloudWatch log group {0}: {1}", logGroup, e.getMessage());
        }

        try {
            cloudWatchLogsService.createLogStreamForAccount(
                    accountId, logGroup, logStream, region);
        } catch (AwsException ignored) {
            // Already exists
        } catch (Exception e) {
            LOG.warnv("Could not create CloudWatch log stream {0}/{1}: {2}", logGroup, logStream, e.getMessage());
        }
    }

    /**
     * Generates a date-prefixed log stream name in the standard AWS format.
     *
     * @param suffix the suffix to append (e.g., "[$LATEST]abc123" or "containerId")
     * @return log stream name like "2024/01/15/suffix"
     */
    public String generateLogStreamName(String suffix) {
        return LOG_STREAM_DATE_FMT.format(LocalDate.now()) + "/" + suffix;
    }

    public void streamToCloudWatchLogs(String logGroup, String logStream, String region, String line) {
        forwardToCloudWatchLogs(null, logGroup, logStream, region, line);
    }

    private void forwardToCloudWatchLogs(
            String accountId, String logGroup, String logStream, String region, String line) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("timestamp", System.currentTimeMillis());
            event.put("message", line);
            cloudWatchLogsService.putLogEventsForAccount(
                    accountId, logGroup, logStream, List.of(event), region);
        } catch (Exception e) {
            LOG.debugv("Could not forward log line to CloudWatch Logs: {0}", e.getMessage());
        }
    }

    /**
     * Docker frame callback that reassembles multiplexed stdout/stderr frames into newline-delimited
     * lines and forwards each to {@code lineConsumer}. One logical line can span several frames and one
     * frame can carry several lines, so bytes are buffered per stream and emitted one line at a time
     * (matching real AWS Lambda).
     */
    static final class LogReassemblyCallback extends ResultCallback.Adapter<Frame> {

        // The idle core thread must time out. A permanent thread would keep executing-class
        // references alive and pin the whole application classloader after shutdown, which
        // accumulates across the many app restarts of a profile-switching test run.
        private static final ScheduledExecutorService CLOSE_DRAIN_SCHEDULER = closeDrainScheduler();

        private static ScheduledExecutorService closeDrainScheduler() {
            var scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "floci-container-log-close");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.setKeepAliveTime(5, TimeUnit.SECONDS);
            scheduler.allowCoreThreadTimeOut(true);
            return scheduler;
        }
        private static final long DEFAULT_CLOSE_GRACE_MILLIS = 2_000;

        private final Map<StreamType, LogLineBuffer> buffers = new EnumMap<>(StreamType.class);
        private final Map<StreamType, Long> tailSequences = new EnumMap<>(StreamType.class);
        private final Deque<String> pendingLines = new ArrayDeque<>();
        private final Object emissionLock = new Object();
        // A fair write lock makes fallback finalization wait for callbacks already in progress, then blocks
        // any later callback until the transport has been closed and the final tail has been flushed.
        private final ReentrantReadWriteLock callbackGate = new ReentrantReadWriteLock(true);
        private final Consumer<String> lineConsumer;
        private final long closeGraceMillis;
        // Guarded by 'buffers'. Set only by the Docker transport terminal callback; afterwards onNext
        // refuses to append, so nothing is ever buffered past the final flush.
        private boolean terminated;
        // Guarded by 'buffers'. The most recent callback with an unfinished tail gets the largest value.
        private long nextTailSequence;
        // Guarded by 'buffers'. Cancels when Docker supplies its terminal callback before the fallback.
        private ScheduledFuture<?> fallbackFinalization;
        // Guarded by 'emissionLock'. Exactly one caller drains pendingLines at a time, preserving Docker
        // callback order even when a terminal callback and onNext run on different threads.
        private boolean emitting;

        LogReassemblyCallback(Consumer<String> lineConsumer) {
            this(lineConsumer, DEFAULT_CLOSE_GRACE_MILLIS);
        }

        LogReassemblyCallback(Consumer<String> lineConsumer, long closeGraceMillis) {
            this.lineConsumer = lineConsumer;
            this.closeGraceMillis = closeGraceMillis;
        }

        @Override
        public void onNext(Frame frame) {
            callbackGate.readLock().lock();
            try {
                boolean emit;
                synchronized (emissionLock) {
                    synchronized (buffers) {
                        if (terminated) {
                            return; // the final drain has run; never append into a buffer that won't be flushed
                        }
                        LogLineBuffer buffer = buffers.computeIfAbsent(frame.getStreamType(), t -> new LogLineBuffer());
                        pendingLines.addAll(buffer.append(frame.getPayload()));
                        if (buffer.hasPendingBytes()) {
                            tailSequences.put(frame.getStreamType(), nextTailSequence++);
                        } else {
                            tailSequences.remove(frame.getStreamType());
                        }
                    }
                    emit = startEmissionIfNeeded();
                }
                if (emit) {
                    emitPendingLines();
                }
            } finally {
                callbackGate.readLock().unlock();
            }
        }

        @Override
        public void onComplete() {
            callbackGate.readLock().lock();
            try {
                flush();
                super.onComplete();
            } finally {
                callbackGate.readLock().unlock();
            }
        }

        @Override
        public void onError(Throwable throwable) {
            callbackGate.readLock().lock();
            try {
                flush();
                super.onError(throwable);
            } finally {
                callbackGate.readLock().unlock();
            }
        }

        // Called only once ContainerLifecycleManager has stopped (or confirmed the absence of) the Docker
        // container. Give its follow-log reader a bounded chance to deliver any queued frames and terminal
        // callback without blocking lifecycle teardown. If no terminal arrives, close that reader before
        // flushing so frames cannot continue after the fallback finalization boundary.
        void closeAfterContainerStopped() {
            synchronized (emissionLock) {
                synchronized (buffers) {
                    if (terminated || fallbackFinalization != null) {
                        return;
                    }
                    fallbackFinalization = CLOSE_DRAIN_SCHEDULER.schedule(
                            this::closeTransportAndFlush, closeGraceMillis, TimeUnit.MILLISECONDS);
                }
            }
        }

        // Flush trailing content never terminated by a newline once Docker has signalled the definitive
        // end of its transport input.
        private void flush() {
            boolean emit;
            synchronized (emissionLock) {
                synchronized (buffers) {
                    if (terminated) {
                        return;
                    }
                    terminated = true;
                    cancelFallbackFinalization();
                    buffers.entrySet().stream()
                            .sorted(Comparator.comparingLong(entry -> tailSequences.getOrDefault(
                                    entry.getKey(), Long.MAX_VALUE)))
                            .forEach(entry -> entry.getValue().flush().ifPresent(pendingLines::addLast));
                    tailSequences.clear();
                }
                emit = startEmissionIfNeeded();
            }
            if (emit) {
                emitPendingLines();
            }
        }

        private void closeTransportAndFlush() {
            callbackGate.writeLock().lock();
            try {
                try {
                    super.close();
                } catch (IOException e) {
                    LOG.debugv("Could not close container log transport: {0}", e.getMessage());
                } finally {
                    flush();
                }
            } finally {
                callbackGate.writeLock().unlock();
            }
        }

        private void cancelFallbackFinalization() {
            if (fallbackFinalization != null) {
                fallbackFinalization.cancel(false);
                fallbackFinalization = null;
            }
        }

        private boolean startEmissionIfNeeded() {
            if (emitting) {
                return false;
            }
            synchronized (buffers) {
                if (pendingLines.isEmpty()) {
                    return false;
                }
            }
            emitting = true;
            return true;
        }

        private void emitPendingLines() {
            while (true) {
                String line;
                synchronized (emissionLock) {
                    synchronized (buffers) {
                        line = pendingLines.pollFirst();
                        if (line == null) {
                            emitting = false;
                            return;
                        }
                    }
                }
                lineConsumer.accept(line);
            }
        }
    }

    /**
     * The lifecycle-facing log handle. Closing it is valid only after the owning container has stopped;
     * it gives Docker a bounded chance to deliver its terminal callback before a non-blocking fallback
     * closes the reader and finalizes any tail.
     */
    static final class ContainerLogHandle implements Closeable {

        private final LogReassemblyCallback callback;

        ContainerLogHandle(LogReassemblyCallback callback) {
            this.callback = callback;
        }

        @Override
        public void close() throws IOException {
            callback.closeAfterContainerStopped();
        }
    }

    /**
     * Reassembles newline-delimited log lines from Docker multiplexed frame payloads.
     * A single logical line can be split across several frames (Docker chunks large
     * writes) and a single frame can carry several lines, so buffering bytes and
     * emitting exactly one line per {@code '\n'} avoids both splitting long lines and
     * merging short ones. Bytes are buffered and each complete line decoded as UTF-8,
     * so a multibyte character straddling a frame boundary is not corrupted.
     */
    static final class LogLineBuffer {

        /**
         * Cap on a single reconstructed line, matching CloudWatch Logs' maximum event size
         * (256 KB). A longer run with no newline is force-split at the cap, so a container
         * emitting unbounded output without a newline cannot grow the buffer until the heap
         * is exhausted. A valid UTF-8 event is split at the limit without cutting a character.
         */
        private static final int MAX_LINE_BYTES = 256 * 1024;

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        /** Continuation bytes still needed to complete the character at the buffer's tail; 0 means the
         *  buffer currently ends on a UTF-8 character boundary. */
        private int pendingContinuation;

        /** Appends a frame payload and returns the complete lines it produced (possibly none). */
        List<String> append(byte[] payload) {
            List<String> lines = new ArrayList<>();
            for (byte b : payload) {
                if (b == '\n') {
                    lines.add(takeLine());
                    continue;
                }
                // Force-split BEFORE a character that would push the buffer past the cap, so the emitted
                // event never exceeds MAX_LINE_BYTES and a multibyte character straddling the limit starts
                // the next event whole instead of inflating this one. Only at a character boundary, and
                // only for a real character start (not a continuation byte).
                if (pendingContinuation == 0 && (b & 0xC0) != 0x80
                        && buffer.size() > 0
                        && buffer.size() + utf8CharLength(b) > MAX_LINE_BYTES) {
                    lines.add(takeLine());
                }
                buffer.write(b);
                trackUtf8(b);
                if ((buffer.size() >= MAX_LINE_BYTES && pendingContinuation == 0)
                        || buffer.size() >= MAX_LINE_BYTES + 3) {
                    // Do not split a valid character whose continuation bytes arrive in a later frame.
                    // Malformed input may never reach a boundary, so allow at most the three continuation
                    // bytes a valid UTF-8 character could need before forcing the buffer to drain.
                    lines.add(takeLine());
                }
            }
            return lines;
        }

        /** Byte length of the UTF-8 character starting with {@code b} (1 for ASCII or an invalid lead byte). */
        private static int utf8CharLength(byte b) {
            int v = b & 0xFF;
            if ((v & 0x80) == 0x00) return 1;   // 0xxxxxxx — ASCII
            if ((v & 0xE0) == 0xC0) return 2;   // 110xxxxx
            if ((v & 0xF0) == 0xE0) return 3;   // 1110xxxx
            if ((v & 0xF8) == 0xF0) return 4;   // 11110xxx
            return 1;                            // invalid lead byte
        }

        /** Returns any buffered content not yet terminated by a newline, clearing the buffer. */
        Optional<String> flush() {
            return buffer.size() == 0 ? Optional.empty() : Optional.of(takeLine());
        }

        boolean hasPendingBytes() {
            return buffer.size() > 0;
        }

        /** Updates how many UTF-8 continuation bytes are still expected after appending byte {@code b}. */
        private void trackUtf8(byte b) {
            int v = b & 0xFF;
            if ((v & 0x80) == 0x00) {          // 0xxxxxxx — ASCII, self-contained
                pendingContinuation = 0;
            } else if ((v & 0xC0) == 0x80) {   // 10xxxxxx — continuation byte
                if (pendingContinuation > 0) {
                    pendingContinuation--;
                }
                // a stray continuation (no preceding lead) leaves the tail on a boundary
            } else if ((v & 0xE0) == 0xC0) {   // 110xxxxx — 2-byte lead
                pendingContinuation = 1;
            } else if ((v & 0xF0) == 0xE0) {   // 1110xxxx — 3-byte lead
                pendingContinuation = 2;
            } else if ((v & 0xF8) == 0xF0) {   // 11110xxx — 4-byte lead
                pendingContinuation = 3;
            } else {                            // 0xF8-0xFF — invalid lead byte
                pendingContinuation = 0;
            }
        }

        private String takeLine() {
            String line = buffer.toString(StandardCharsets.UTF_8);
            buffer.reset();
            pendingContinuation = 0;
            return line;
        }
    }
}
