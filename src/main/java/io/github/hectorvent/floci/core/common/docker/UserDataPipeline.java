package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Shared pipeline for parsing, decoding, decompressing, and executing EC2 and EKS user data
 * payloads in Docker containers.
 */
public final class UserDataPipeline {

    private static final Logger LOG = Logger.getLogger(UserDataPipeline.class);

    public static final String USER_DATA_SCRIPT_PATH = "/var/lib/user-data.sh";
    private static final Pattern MIME_BOUNDARY = Pattern.compile(
            "(?im)^content-type:\\s*multipart/[^;]+;\\s*boundary=\"?([^\";\\n\\r]+)\"?.*$");
    private static final Pattern BASE64_BODY = Pattern.compile("[A-Za-z0-9+/\\s]+={0,2}");
    public static final int MAX_USER_DATA_DECODE_ROUNDS = 3;
    public static final int MAX_DECOMPRESSED_USER_DATA_BYTES = 10 * 1024 * 1024;
    public static final int MAX_EXEC_OUTPUT_BYTES = 2048;
    public static final int MAX_CONCURRENT_USER_DATA_DECOMPRESSIONS = 4;
    public static final Duration DEFAULT_EXECUTION_TIMEOUT = Duration.ofMinutes(30);

    private static final Semaphore USER_DATA_DECOMPRESSION_BUDGET =
            new Semaphore(MAX_CONCURRENT_USER_DATA_DECOMPRESSIONS);

    public static volatile Runnable userDataDecompressionTestHook;

    private UserDataPipeline() {
    }

    public static List<String> extractShellScripts(String userData) {
        String decoded = decodeUserDataPayload(userData);
        if (decoded == null || decoded.isBlank()) {
            return List.of();
        }

        String normalized = decoded.replace("\r\n", "\n").replace('\r', '\n');
        String trimmed = normalized.stripLeading();
        if (trimmed.startsWith("#!")) {
            return List.of(normalized);
        }

        Matcher matcher = MIME_BOUNDARY.matcher(normalized);
        if (!matcher.find()) {
            return List.of();
        }

        String boundary = matcher.group(1).trim();
        if (boundary.isEmpty()) {
            return List.of();
        }

        List<String> scripts = new ArrayList<>();
        String marker = "--" + boundary;
        for (String segment : normalized.split(Pattern.quote(marker))) {
            String part = segment.stripLeading();
            if (part.isBlank() || part.startsWith("--")) {
                continue;
            }
            int headerEnd = part.indexOf("\n\n");
            if (headerEnd < 0) {
                continue;
            }
            String headers = part.substring(0, headerEnd);
            String body = part.substring(headerEnd + 2);
            if (hasShellscriptContentType(headers)) {
                scripts.add(body.stripTrailing() + "\n");
            }
        }
        return List.copyOf(scripts);
    }

    public static String decodeUserDataPayload(String userData) {
        if (userData == null || userData.isBlank()) {
            return null;
        }
        byte[] payload = userData.getBytes(StandardCharsets.UTF_8);
        // Bounded so a crafted payload cannot make this loop forever; two rounds already
        // covers base64(gzip(document)), the deepest form in practice.
        for (int round = 0; round < MAX_USER_DATA_DECODE_ROUNDS; round++) {
            byte[] next = gunzip(payload);
            if (next == null) {
                next = base64Decode(payload);
            }
            if (next == null) {
                break;
            }
            payload = next;
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static byte[] gunzip(byte[] payload) {
        if (payload.length < 2 || (payload[0] & 0xff) != 0x1f || (payload[1] & 0xff) != 0x8b) {
            return null;
        }
        try {
            USER_DATA_DECOMPRESSION_BUDGET.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOG.warnv("Interrupted while waiting for UserData decompression budget; discarding payload");
            return null;
        }
        try {
            Runnable hook = userDataDecompressionTestHook;
            if (hook != null) {
                hook.run();
            }

            // Bounded the same way AwsJsonCborController.decodeBody is: a crafted payload can
            // otherwise expand to gigabytes of image-heap while the launch worker holds it, since
            // UserData is caller-controlled and this runs in the shared emulator JVM.
            byte[] buffer = new byte[64 * 1024];
            int totalRead = 0;
            ByteArrayOutputStream decompressed = new ByteArrayOutputStream();
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(payload))) {
                int read;
                while ((read = gzip.read(buffer)) != -1) {
                    totalRead += read;
                    if (totalRead > MAX_DECOMPRESSED_USER_DATA_BYTES) {
                        LOG.warnv("UserData decompressed past {0} bytes; discarding as oversized",
                                MAX_DECOMPRESSED_USER_DATA_BYTES);
                        return null;
                    }
                    decompressed.write(buffer, 0, read);
                }
                return decompressed.toByteArray();
            } catch (IOException e) {
                LOG.warnv("UserData starts with the gzip magic bytes but could not be decompressed: {0}", e.getMessage());
                return null;
            }
        } finally {
            USER_DATA_DECOMPRESSION_BUDGET.release();
        }
    }

    private static byte[] base64Decode(byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8).strip();
        if (text.isEmpty() || !BASE64_BODY.matcher(text).matches()) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getMimeDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (decoded.length < 2) {
            return null;
        }
        if ((decoded[0] & 0xff) == 0x1f && (decoded[1] & 0xff) == 0x8b) {
            return decoded;
        }
        String head = new String(decoded, 0, Math.min(decoded.length, 512), StandardCharsets.UTF_8).stripLeading();
        return head.startsWith("#!") || head.toLowerCase(Locale.ROOT).startsWith("content-type:")
                || head.toLowerCase(Locale.ROOT).startsWith("mime-version:") || head.startsWith("#cloud-config")
                ? decoded
                : null;
    }

    private static boolean hasShellscriptContentType(String headers) {
        for (String line : headers.split("\n")) {
            String lower = line.toLowerCase(Locale.ROOT).strip();
            if (lower.startsWith("content-type:") && lower.contains("text/x-shellscript")) {
                return true;
            }
        }
        return false;
    }

    public static byte[] buildSingleFileTar(String filename, byte[] content, int mode) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bos)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            TarArchiveEntry entry = new TarArchiveEntry(filename);
            entry.setSize(content.length);
            entry.setMode(mode);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
        }
        return bos.toByteArray();
    }

    public static String summarizeUserDataOutput(BoundedOutput output) {
        if (output == null) {
            return "(no output)";
        }
        String text = output.utf8Tail().stripTrailing();
        if (text.isBlank()) {
            text = "(no output)";
        }
        if (output.truncated()) {
            return "(output truncated; showing last " + output.capacity() + " bytes)\n" + text;
        }
        return text;
    }

    public static ExecutionResult executeUserData(
            DockerClient dockerClient,
            String containerId,
            String contextDescription,
            String userData,
            Duration timeout,
            Consumer<String> outputLineConsumer,
            Set<ResultCallback<Frame>> activeCallbacks
    ) {
        if (dockerClient == null || containerId == null || containerId.isBlank()) {
            return ExecutionResult.skipped("No container available");
        }
        List<String> shellScripts = extractShellScripts(userData);
        if (shellScripts.isEmpty()) {
            return ExecutionResult.noScripts();
        }
        Duration effectiveTimeout = timeout != null ? timeout : DEFAULT_EXECUTION_TIMEOUT;

        for (int i = 0; i < shellScripts.size(); i++) {
            int partNumber = i + 1;
            int partCount = shellScripts.size();
            String scriptContent = shellScripts.get(i);
            ExecutionResult partResult = executeSingleScript(
                    dockerClient, containerId, contextDescription, scriptContent, partNumber, partCount,
                    effectiveTimeout, outputLineConsumer, activeCallbacks);
            if (!partResult.isSuccess()) {
                return partResult;
            }
        }
        return ExecutionResult.success(0L, "UserData execution completed successfully");
    }

    private static ExecutionResult executeSingleScript(
            DockerClient dockerClient,
            String containerId,
            String contextDescription,
            String scriptContent,
            int partNumber,
            int partCount,
            Duration timeout,
            Consumer<String> outputLineConsumer,
            Set<ResultCallback<Frame>> activeCallbacks
    ) {
        byte[] script = scriptContent.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] tar = buildSingleFileTar("user-data.sh", script, 0755);
            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withRemotePath("/var/lib")
                    .withTarInputStream(new ByteArrayInputStream(tar))
                    .exec();

            String execId = dockerClient.execCreateCmd(containerId)
                    .withCmd(USER_DATA_SCRIPT_PATH)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec()
                    .getId();

            BoundedOutput output = new BoundedOutput(MAX_EXEC_OUTPUT_BYTES);
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean cancelled = new AtomicBoolean();

            ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
                @Override
                public void onStart(Closeable stream) {
                    if (cancelled.get()) {
                        closeSilently(stream);
                        return;
                    }
                    super.onStart(stream);
                    if (cancelled.get()) {
                        closeSilently(stream);
                    }
                }

                @Override
                public void onNext(Frame frame) {
                    if (cancelled.get()) {
                        return;
                    }
                    byte[] payload = frame.getPayload();
                    if (payload == null) {
                        return;
                    }
                    try {
                        output.write(payload);
                    } catch (IOException ignored) {
                    }
                    if (outputLineConsumer != null) {
                        String line = new String(payload, StandardCharsets.UTF_8).stripTrailing();
                        if (!line.isEmpty()) {
                            outputLineConsumer.accept(line);
                        }
                    }
                }

                @Override
                public void onComplete() {
                    latch.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    latch.countDown();
                }

                @Override
                public void close() throws IOException {
                    cancelled.set(true);
                    super.close();
                }
            };

            if (activeCallbacks != null) {
                activeCallbacks.add(callback);
            }

            try {
                dockerClient.execStartCmd(execId).exec(callback);

                boolean completed = latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!completed) {
                    LOG.warnv("UserData shellscript part {0}/{1} timed out for {2}",
                            partNumber, partCount, contextDescription);
                    return ExecutionResult.timedOut(partNumber, partCount, timeout.toMinutes(),
                            summarizeUserDataOutput(output));
                }

                Long exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
                if (exitCode != null && exitCode != 0) {
                    LOG.warnv("UserData shellscript part {0}/{1} failed for {2} with exit code {3}: {4}",
                            partNumber, partCount, contextDescription, exitCode, summarizeUserDataOutput(output));
                    return ExecutionResult.failed(exitCode, partNumber, partCount,
                            summarizeUserDataOutput(output));
                }

                LOG.infov("UserData shellscript part {0}/{1} completed for {2}: {3}",
                        partNumber, partCount, contextDescription, summarizeUserDataOutput(output));
                return ExecutionResult.success(exitCode, summarizeUserDataOutput(output));
            } finally {
                if (activeCallbacks != null) {
                    activeCallbacks.remove(callback);
                }
                closeSilently(callback);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnv("UserData execution interrupted for {0}", contextDescription);
            return ExecutionResult.error("UserData execution interrupted for " + contextDescription);
        } catch (Exception e) {
            LOG.warnv("UserData execution failed for {0}: {1}", contextDescription, e.getMessage());
            return ExecutionResult.error("UserData execution failed for " + contextDescription + ": " + e.getMessage());
        }
    }

    private static void closeSilently(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static class BoundedOutput extends OutputStream {
        private final byte[] buffer;
        private int size;
        private long totalBytes;

        public BoundedOutput(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive");
            }
            this.buffer = new byte[capacity];
        }

        @Override
        public void write(int value) {
            write(new byte[]{(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] source, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, source.length);
            if (length == 0) {
                return;
            }
            totalBytes += length;
            if (length >= buffer.length) {
                System.arraycopy(source, offset + length - buffer.length, buffer, 0, buffer.length);
                size = buffer.length;
                return;
            }
            int overflow = Math.max(0, size + length - buffer.length);
            if (overflow > 0) {
                System.arraycopy(buffer, overflow, buffer, 0, size - overflow);
                size -= overflow;
            }
            System.arraycopy(source, offset, buffer, size, length);
            size += length;
        }

        public byte[] toByteArray() {
            byte[] copy = new byte[size];
            System.arraycopy(buffer, 0, copy, 0, size);
            return copy;
        }

        public String utf8Tail() {
            int start = 0;
            while (start < size) {
                int sequenceLength = utf8SequenceLength(buffer[start]);
                if (sequenceLength == 0) {
                    start++;
                    continue;
                }
                if (sequenceLength == 1) {
                    break;
                }
                if (sequenceLength <= size - start && hasContinuationBytes(start, sequenceLength)) {
                    break;
                }
                start++;
            }
            return new String(buffer, start, size - start, StandardCharsets.UTF_8);
        }

        private boolean hasContinuationBytes(int start, int sequenceLength) {
            for (int i = 1; i < sequenceLength; i++) {
                if ((buffer[start + i] & 0xC0) != 0x80) {
                    return false;
                }
            }
            return true;
        }

        private static int utf8SequenceLength(byte value) {
            int unsigned = value & 0xFF;
            if (unsigned < 0x80) {
                return 1;
            }
            if ((unsigned & 0xC0) == 0x80) {
                return 0;
            }
            if ((unsigned & 0xE0) == 0xC0) {
                return 2;
            }
            if ((unsigned & 0xF0) == 0xE0) {
                return 3;
            }
            if ((unsigned & 0xF8) == 0xF0) {
                return 4;
            }
            return 1;
        }

        public boolean truncated() {
            return totalBytes > buffer.length;
        }

        public int capacity() {
            return buffer.length;
        }

        public long totalBytes() {
            return totalBytes;
        }
    }

    public static final class ExecutionResult {
        private final boolean success;
        private final Long exitCode;
        private final boolean timedOut;
        private final String outputSummary;
        private final String failureMessage;

        private ExecutionResult(boolean success, Long exitCode, boolean timedOut,
                               String outputSummary, String failureMessage) {
            this.success = success;
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.outputSummary = outputSummary;
            this.failureMessage = failureMessage;
        }

        public static ExecutionResult success(Long exitCode, String outputSummary) {
            return new ExecutionResult(true, exitCode, false, outputSummary, null);
        }

        public static ExecutionResult noScripts() {
            return new ExecutionResult(true, 0L, false, "(no scripts)", null);
        }

        public static ExecutionResult skipped(String reason) {
            return new ExecutionResult(true, 0L, false, reason, null);
        }

        public static ExecutionResult failed(Long exitCode, int partNumber, int partCount, String outputSummary) {
            String msg = "UserData shellscript part " + partNumber + "/" + partCount
                    + " failed with exit code " + exitCode + ": " + outputSummary;
            return new ExecutionResult(false, exitCode, false, outputSummary, msg);
        }

        public static ExecutionResult timedOut(int partNumber, int partCount, long timeoutMinutes, String outputSummary) {
            String msg = "UserData shellscript part " + partNumber + "/" + partCount
                    + " timed out after " + timeoutMinutes + " minutes: " + outputSummary;
            return new ExecutionResult(false, null, true, outputSummary, msg);
        }

        public static ExecutionResult error(String message) {
            return new ExecutionResult(false, null, false, message, message);
        }

        public boolean isSuccess() {
            return success;
        }

        public Long getExitCode() {
            return exitCode;
        }

        public boolean isTimedOut() {
            return timedOut;
        }

        public String getOutputSummary() {
            return outputSummary;
        }

        public String getFailureMessage() {
            return failureMessage;
        }
    }
}
