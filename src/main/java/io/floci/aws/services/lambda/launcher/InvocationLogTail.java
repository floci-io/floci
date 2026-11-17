package io.floci.aws.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.model.Frame;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/** A byte-bounded invocation log snapshot, independent of asynchronous CloudWatch forwarding. */
public final class InvocationLogTail {

    static final int LIMIT = 4096;
    private final byte[] tail = new byte[LIMIT];
    private int position;
    private int size;
    private boolean includeLine;

    public static String readDocker(DockerClient docker, String containerId, Instant since) {
        InvocationLogTail tail = new InvocationLogTail();
        try (LogContainerCmd command = docker.logContainerCmd(containerId)
                .withStdOut(true).withStdErr(true).withFollowStream(false).withTimestamps(true)
                .withSince((int) since.getEpochSecond()).withTail(LIMIT);
                ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
                    @Override
                    public void onNext(Frame frame) {
                        tail.appendTimestamped(frame.getPayload(), since);
                    }
                }) {
            if (!command.exec(callback).awaitCompletion(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out reading Lambda invocation logs");
            }
            return tail.encoded();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted reading Lambda invocation logs", e);
        } catch (IOException e) {
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

    void appendTimestamped(byte[] bytes, Instant since) {
        int offset = 0;
        while (offset < bytes.length) {
            int end = offset;
            while (end < bytes.length && bytes[end] != '\n') {
                end++;
            }
            int content = offset;
            int separator = offset;
            while (separator < Math.min(end, offset + 40) && bytes[separator] != ' ') {
                separator++;
            }
            if (separator < end && bytes[separator] == ' ') {
                try {
                    Instant timestamp = Instant.parse(new String(bytes, offset, separator - offset, StandardCharsets.US_ASCII));
                    includeLine = !timestamp.isBefore(since);
                    content = separator + 1;
                } catch (DateTimeParseException expected) {
                    // A continuation of a Docker log record carries no timestamp prefix.
                }
            }
            int next = end < bytes.length ? end + 1 : end;
            if (includeLine) {
                append(bytes, content, next - content);
            }
            offset = next;
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

    String encoded() {
        byte[] bytes = new byte[size];
        int start = (position - size + LIMIT) % LIMIT;
        int first = Math.min(size, LIMIT - start);
        System.arraycopy(tail, start, bytes, 0, first);
        System.arraycopy(tail, 0, bytes, first, size - first);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
