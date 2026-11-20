package io.floci.aws.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** A byte-bounded invocation log snapshot, independent of asynchronous CloudWatch forwarding. */
public final class InvocationLogTail {

    static final int LIMIT = 4096;
    private final byte[] tail = new byte[LIMIT];
    private int position;
    private int size;
    private final TimestampedParser parser = new TimestampedParser();
    private Instant lastTimestamp = Instant.EPOCH;

    public static Supplier<String> captureDocker(DockerClient docker, String containerId, boolean initialization) {
        Instant since = initialization ? Instant.EPOCH
                : dockerSnapshot(docker, containerId, Instant.EPOCH, 1).lastTimestamp().plusNanos(1);
        return () -> readDocker(docker, containerId, since);
    }

    public static String readDocker(DockerClient docker, String containerId, Instant since) {
        return dockerSnapshot(docker, containerId, since, LIMIT).encoded();
    }

    private static InvocationLogTail dockerSnapshot(DockerClient docker, String containerId, Instant since, int lines) {
        InvocationLogTail tail = new InvocationLogTail();
        Map<StreamType, TimestampedParser> parsers = new EnumMap<>(StreamType.class);
        try (LogContainerCmd command = docker.logContainerCmd(containerId)
                .withStdOut(true).withStdErr(true).withFollowStream(false).withTimestamps(true)
                .withSince((int) Math.min(Integer.MAX_VALUE, since.getEpochSecond())).withTail(lines);
                ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
                    @Override
                    public void onNext(Frame frame) {
                        parsers.computeIfAbsent(frame.getStreamType(), ignored -> tail.new TimestampedParser())
                                .acceptFrame(frame.getPayload(), since);
                    }
                }) {
            if (!command.exec(callback).awaitCompletion(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out reading Lambda invocation logs");
            }
            parsers.values().forEach(TimestampedParser::finish);
            return tail;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted reading Lambda invocation logs", e);
        } catch (IOException | DockerException e) {
            throw new IllegalStateException("Could not read Lambda invocation logs", e);
        }
    }

    public static String read(InputStream logs) throws IOException {
        InvocationLogTail tail = new InvocationLogTail();
        byte[] chunk = new byte[LIMIT];
        int count;
        while ((count = logs.read(chunk)) != -1) {
            tail.append(chunk, 0, count);
        }
        return tail.encoded();
    }

    public static InvocationLogTail readTimestamped(InputStream logs, Instant since) throws IOException {
        InvocationLogTail tail = new InvocationLogTail();
        byte[] chunk = new byte[LIMIT];
        int count;
        while ((count = logs.read(chunk)) != -1) {
            tail.parser.accept(chunk, 0, count, since);
        }
        tail.parser.finish();
        return tail;
    }

    public Instant lastTimestamp() {
        return lastTimestamp;
    }

    void appendTimestamped(byte[] bytes, Instant since) {
        parser.accept(bytes, 0, bytes.length, since);
    }

    public void appendEncoded(String logs) {
        if (logs != null) {
            byte[] bytes = Base64.getDecoder().decode(logs);
            append(bytes, 0, bytes.length);
        }
    }

    void append(byte[] bytes, int offset, int length) {
        int count = Math.min(length, LIMIT);
        int start = offset + length - count;
        int first = Math.min(count, LIMIT - position);
        System.arraycopy(bytes, start, tail, position, first);
        System.arraycopy(bytes, start + first, tail, 0, count - first);
        position = (position + count) % LIMIT;
        size = Math.min(LIMIT, size + count);
    }

    public String encoded() {
        parser.finish();
        byte[] bytes = new byte[size];
        int start = (position - size + LIMIT) % LIMIT;
        int first = Math.min(size, LIMIT - start);
        System.arraycopy(tail, start, bytes, 0, first);
        System.arraycopy(tail, 0, bytes, first, size - first);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private final class TimestampedParser {
        private final byte[] prefix = new byte[40];
        private int prefixSize;
        private boolean readingPrefix = true;
        private boolean include;

        void acceptFrame(byte[] bytes, Instant since) {
            // Docker timestamps each record, including records that continue an unterminated line.
            // A prefix already in progress belongs to a transport-split record and must be retained.
            readingPrefix = true;
            accept(bytes, 0, bytes.length, since);
        }

        void accept(byte[] bytes, int offset, int length, Instant since) {
            int end = offset + length;
            while (offset < end) {
                if (readingPrefix) {
                    byte next = bytes[offset++];
                    prefix[prefixSize++] = next;
                    if (next == ' ' || next == '\n' || prefixSize == prefix.length) {
                        try {
                            Instant timestamp = Instant.parse(new String(prefix, 0, prefixSize - 1, StandardCharsets.US_ASCII));
                            include = !timestamp.isBefore(since);
                            if (timestamp.isAfter(lastTimestamp)) {
                                lastTimestamp = timestamp;
                            }
                        } catch (DateTimeParseException expected) {
                            // A log record can continue without a timestamp prefix.
                            if (include) {
                                append(prefix, 0, prefixSize);
                            }
                        }
                        prefixSize = 0;
                        readingPrefix = next == '\n';
                    }
                } else {
                    int next = offset;
                    while (next < end && bytes[next++] != '\n') {
                        // Append payload bytes in bulk, including any terminating newline.
                    }
                    if (include) {
                        append(bytes, offset, next - offset);
                    }
                    readingPrefix = bytes[next - 1] == '\n';
                    offset = next;
                }
            }
        }

        void finish() {
            if (prefixSize > 0 && include) {
                append(prefix, 0, prefixSize);
            }
            prefixSize = 0;
        }
    }
}
