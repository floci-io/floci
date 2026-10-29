package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.testutil.LogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerLogStreamerTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void write(OutputStream stream, String text) {
        try {
            stream.write(utf8(text));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void close(OutputStream stream) {
        try {
            stream.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // The container lines that records logged, without their prefix.
    private static List<Object> loggedLines(List<LogRecord> records) {
        return records.stream().map(record -> record.getParameters()[1]).toList();
    }

    @Test
    void ensureLogGroupAndStreamUsesOwningAccount() {
        CloudWatchLogsService cloudWatchLogsService = mock(CloudWatchLogsService.class);
        ContainerLogStreamer streamer = new ContainerLogStreamer(null, cloudWatchLogsService);

        streamer.ensureLogGroupAndStreamForAccount(
                "555555555555", "/aws/lambda/example", "2026/09/17/[$LATEST]stream", "us-east-1");

        verify(cloudWatchLogsService).createLogGroupForAccount(
                "555555555555", "/aws/lambda/example", null, null, "us-east-1");
        verify(cloudWatchLogsService).createLogStreamForAccount(
                "555555555555", "/aws/lambda/example", "2026/09/17/[$LATEST]stream", "us-east-1");
    }

    @Test
    void streamToCloudWatchLogsForAccountUsesOwningAccount() {
        CloudWatchLogsService cloudWatchLogsService = mock(CloudWatchLogsService.class);
        ContainerLogStreamer streamer = new ContainerLogStreamer(null, cloudWatchLogsService);

        streamer.streamToCloudWatchLogsForAccount(
                "555555555555", "/aws/lambda/example", "stream", "us-east-1", "function output");

        verify(cloudWatchLogsService).putLogEventsForAccount(
                eq("555555555555"), eq("/aws/lambda/example"), eq("stream"), anyList(), eq("us-east-1"));
    }

    @Test
    void execLogCallbackForAccountForwardsExtensionOutputToOwningAccount() {
        CloudWatchLogsService cloudWatchLogsService = mock(CloudWatchLogsService.class);
        ContainerLogStreamer streamer = new ContainerLogStreamer(null, cloudWatchLogsService);
        ResultCallback.Adapter<Frame> callback = streamer.execLogCallbackForAccount(
                "555555555555", "/aws/lambda/example", "stream", "us-east-1", "lambda:example:extension");

        callback.onNext(new Frame(StreamType.STDOUT, utf8("extension output\n")));

        verify(cloudWatchLogsService).putLogEventsForAccount(
                eq("555555555555"), eq("/aws/lambda/example"), eq("stream"), anyList(), eq("us-east-1"));
    }

    @Test
    void attachedStreamStillReachesCloudWatchAndConsole() {
        DockerClient dockerClient = mock(DockerClient.class);
        LogContainerCmd command = mock(LogContainerCmd.class, RETURNS_SELF);
        when(dockerClient.logContainerCmd("container-1")).thenReturn(command);
        CloudWatchLogsService cloudWatchLogsService = mock(CloudWatchLogsService.class);
        ContainerLogStreamer streamer = new ContainerLogStreamer(dockerClient, cloudWatchLogsService);

        streamer.attachForAccount("555555555555", "container-1", "/aws/eks/cluster/cluster",
                "stream", "us-east-1", "eks:cluster");
        ArgumentCaptor<ContainerLogStreamer.LogReassemblyCallback> callback =
                ArgumentCaptor.forClass(ContainerLogStreamer.LogReassemblyCallback.class);
        verify(command).exec(callback.capture());

        List<LogRecord> records = LogCapture.capture(ContainerLogStreamer.class, () ->
                callback.getValue().onNext(new Frame(StreamType.STDOUT, utf8("container output\n"))));

        assertEquals(1, records.size());
        verify(cloudWatchLogsService).putLogEventsForAccount(
                eq("555555555555"), eq("/aws/eks/cluster/cluster"), eq("stream"),
                argThat(events ->
                        events.size() == 1 && "container output".equals(events.getFirst().get("message"))),
                eq("us-east-1"));
    }

    @Test
    void consoleOnlyStreamReachesTheConsoleButNotCloudWatch() {
        DockerClient dockerClient = mock(DockerClient.class);
        LogContainerCmd command = mock(LogContainerCmd.class, RETURNS_SELF);
        when(dockerClient.logContainerCmd("container-1")).thenReturn(command);
        CloudWatchLogsService cloudWatchLogsService = mock(CloudWatchLogsService.class);
        ContainerLogStreamer streamer = new ContainerLogStreamer(dockerClient, cloudWatchLogsService);

        streamer.attachConsoleOnly("container-1", "ecs:family:main");
        ArgumentCaptor<ContainerLogStreamer.LogReassemblyCallback> callback =
                ArgumentCaptor.forClass(ContainerLogStreamer.LogReassemblyCallback.class);
        verify(command).exec(callback.capture());

        List<LogRecord> records = LogCapture.capture(ContainerLogStreamer.class, () ->
                callback.getValue().onNext(new Frame(StreamType.STDOUT, utf8("container output\n"))));

        assertEquals(1, records.size());
        verify(cloudWatchLogsService, never()).createLogGroupForAccount(any(), any(), any(), any(), any());
        verify(cloudWatchLogsService, never()).putLogEventsForAccount(any(), any(), any(), anyList(), any());
    }

    @Test
    void aByStreamAttachSendsEachStreamToItsOwnLogStream() {
        DockerClient dockerClient = mock(DockerClient.class);
        LogContainerCmd command = mock(LogContainerCmd.class, RETURNS_SELF);
        when(dockerClient.logContainerCmd("container-1")).thenReturn(command);
        CloudWatchLogsService cloudWatchLogsService = mock(CloudWatchLogsService.class);
        ContainerLogStreamer streamer = new ContainerLogStreamer(dockerClient, cloudWatchLogsService);

        streamer.attachByStreamForAccount("555555555555", "container-1", "/aws/emr-serverless",
                "/jobs/1/SPARK_DRIVER/stdout", "/jobs/1/SPARK_DRIVER/stderr", "us-east-1", "emr-serverless:1");
        ArgumentCaptor<ContainerLogStreamer.LogReassemblyCallback> callback =
                ArgumentCaptor.forClass(ContainerLogStreamer.LogReassemblyCallback.class);
        verify(command).exec(callback.capture());

        List<LogRecord> records = LogCapture.capture(ContainerLogStreamer.class, () -> {
            callback.getValue().onNext(new Frame(StreamType.STDOUT, utf8("Pi is roughly 3.14\n")));
            callback.getValue().onNext(new Frame(StreamType.STDERR, utf8("INFO SparkContext: stopped\n")));
        });

        assertEquals(2, records.size());
        verify(cloudWatchLogsService).createLogStreamForAccount(
                "555555555555", "/aws/emr-serverless", "/jobs/1/SPARK_DRIVER/stdout", "us-east-1");
        verify(cloudWatchLogsService).createLogStreamForAccount(
                "555555555555", "/aws/emr-serverless", "/jobs/1/SPARK_DRIVER/stderr", "us-east-1");
        verify(cloudWatchLogsService).putLogEventsForAccount(eq("555555555555"), eq("/aws/emr-serverless"),
                eq("/jobs/1/SPARK_DRIVER/stdout"),
                argThat(events -> "Pi is roughly 3.14".equals(events.getFirst().get("message"))), eq("us-east-1"));
        verify(cloudWatchLogsService).putLogEventsForAccount(eq("555555555555"), eq("/aws/emr-serverless"),
                eq("/jobs/1/SPARK_DRIVER/stderr"),
                argThat(events -> "INFO SparkContext: stopped".equals(events.getFirst().get("message"))),
                eq("us-east-1"));
    }

    @Test
    void aByStreamAttachForwardsAStreamWithoutALogStreamOnlyToTheConsole() {
        DockerClient dockerClient = mock(DockerClient.class);
        LogContainerCmd command = mock(LogContainerCmd.class, RETURNS_SELF);
        when(dockerClient.logContainerCmd("container-1")).thenReturn(command);
        CloudWatchLogsService cloudWatchLogsService = mock(CloudWatchLogsService.class);
        ContainerLogStreamer streamer = new ContainerLogStreamer(dockerClient, cloudWatchLogsService);

        streamer.attachByStreamForAccount("555555555555", "container-1", "/aws/emr-serverless",
                "/jobs/1/SPARK_DRIVER/stdout", null, "us-east-1", "emr-serverless:1");
        ArgumentCaptor<ContainerLogStreamer.LogReassemblyCallback> callback =
                ArgumentCaptor.forClass(ContainerLogStreamer.LogReassemblyCallback.class);
        verify(command).exec(callback.capture());

        List<LogRecord> records = LogCapture.capture(ContainerLogStreamer.class, () ->
                callback.getValue().onNext(new Frame(StreamType.STDERR, utf8("INFO SparkContext: stopped\n"))));

        assertEquals(1, records.size());
        verify(cloudWatchLogsService, never()).createLogStreamForAccount(any(), any(), eq(null), any());
        verify(cloudWatchLogsService, never()).putLogEventsForAccount(any(), any(), any(), anyList(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void execStreamAlwaysReachesCloudWatchAndConsoleIsOptional(boolean logToConsole) {
        CloudWatchLogsService cloudWatchLogsService = mock(CloudWatchLogsService.class);
        ContainerLogStreamer streamer = new ContainerLogStreamer(null, cloudWatchLogsService);
        ResultCallback.Adapter<Frame> callback = logToConsole
                ? streamer.execLogCallbackForAccount("555555555555", "/aws/eks/cluster/cluster",
                        "stream", "us-east-1", "eks-audit:cluster")
                : streamer.execLogCallbackForAccount("555555555555", "/aws/eks/cluster/cluster",
                        "stream", "us-east-1", "eks-audit:cluster", false);

        List<LogRecord> records = LogCapture.capture(ContainerLogStreamer.class, () ->
                callback.onNext(new Frame(StreamType.STDOUT, utf8("audit event\n"))));

        assertEquals(logToConsole ? 1 : 0, records.size());
        verify(cloudWatchLogsService).putLogEventsForAccount(
                eq("555555555555"), eq("/aws/eks/cluster/cluster"), eq("stream"),
                argThat(events ->
                        events.size() == 1 && "audit event".equals(events.getFirst().get("message"))),
                eq("us-east-1"));
    }

    @Test
    void consoleLinesLogsEachLineAsItArrivesAndALastOneWithoutANewlineWhenClosed() {
        OutputStream console = ContainerLogStreamer.consoleLines("emr:j-1:bootstrap-action-1");

        List<LogRecord> asWritten = LogCapture.capture(ContainerLogStreamer.class, () -> {
            write(console, "Collecting pan");
            write(console, "das\n\nInstalled pandas\nDone");
        });
        List<LogRecord> asClosed = LogCapture.capture(ContainerLogStreamer.class, () -> close(console));

        // A blank line is dropped, as the attach methods drop one.
        assertEquals(List.of("Collecting pandas", "Installed pandas"), loggedLines(asWritten));
        assertEquals(List.of("Done"), loggedLines(asClosed));
        assertEquals("emr:j-1:bootstrap-action-1", asClosed.getFirst().getParameters()[0]);
    }

    @Test
    void reassemblesSingleLineSplitAcrossFrames() {
        // A ~1.5 KB stdout line that Docker delivers as multiple frames must arrive
        // as ONE CloudWatch event, not several (regression for the >1024-byte split).
        ContainerLogStreamer.LogLineBuffer buffer = new ContainerLogStreamer.LogLineBuffer();
        String longLine = "x".repeat(1500);

        assertEquals(List.of(), buffer.append(utf8(longLine.substring(0, 1024))));
        assertEquals(List.of(), buffer.append(utf8(longLine.substring(1024))));

        List<String> lines = buffer.append(utf8("\n"));
        assertEquals(1, lines.size());
        assertEquals(1500, lines.get(0).length());
        assertEquals(longLine, lines.get(0));
    }

    @Test
    void splitsMultipleLinesPackedIntoOneFrame() {
        ContainerLogStreamer.LogLineBuffer buffer = new ContainerLogStreamer.LogLineBuffer();

        assertEquals(List.of("first", "second", "third"), buffer.append(utf8("first\nsecond\nthird\n")));
    }

    @Test
    void flushReturnsTrailingUnterminatedContent() {
        ContainerLogStreamer.LogLineBuffer buffer = new ContainerLogStreamer.LogLineBuffer();

        assertEquals(List.of("complete"), buffer.append(utf8("complete\npartial")));
        assertEquals("partial", buffer.flush().orElseThrow());
        assertTrue(buffer.flush().isEmpty(), "buffer should be cleared after flush");
    }

    @Test
    void doesNotCorruptMultibyteCharacterSplitAcrossFrames() {
        // "€" is E2 82 AC in UTF-8; decoding per-frame would corrupt it, decoding the
        // reassembled line does not.
        ContainerLogStreamer.LogLineBuffer buffer = new ContainerLogStreamer.LogLineBuffer();
        byte[] euro = utf8("€");

        assertEquals(List.of(), buffer.append(new byte[]{euro[0], euro[1]}));
        assertEquals(List.of("€"), buffer.append(new byte[]{euro[2], '\n'}));
    }

    @Test
    void preservesEmptyLinesInSplitOutput() {
        ContainerLogStreamer.LogLineBuffer buffer = new ContainerLogStreamer.LogLineBuffer();

        // append() splits faithfully; the emit layer is what skips blanks.
        assertEquals(List.of("a", "", "b"), buffer.append(utf8("a\n\nb\n")));
    }

    static Stream<byte[]> completeFrames() {
        return Stream.of(
                utf8("\n"),
                utf8("\n\n"),
                utf8("first\n\nlast\n\n"),
                utf8("café\n東京\n🚀\n"),
                new byte[]{(byte) 0xe2, '\n', (byte) 0x80, '\n'},
                new byte[]{(byte) 0xf0, (byte) 0x9f, '\n'},
                utf8("a".repeat(256 * 1024 - 4) + "\n"));
    }

    @ParameterizedTest
    @MethodSource("completeFrames")
    void completeFrameMatchesBytewiseReassembly(byte[] payload) {
        ContainerLogStreamer.LogLineBuffer whole = new ContainerLogStreamer.LogLineBuffer();
        ContainerLogStreamer.LogLineBuffer fragmented = new ContainerLogStreamer.LogLineBuffer();
        List<String> expected = new ArrayList<>();
        for (byte value : payload) {
            expected.addAll(fragmented.append(new byte[]{value}));
        }

        assertEquals(expected, whole.append(payload));
        assertFalse(whole.hasPendingBytes());
        assertTrue(whole.flush().isEmpty());
        assertEquals(List.of("next"), whole.append(utf8("next\n")));
    }

    @Test
    void nearCapFrameKeepsEarlySplitBeforeMalformedLead() {
        ContainerLogStreamer.LogLineBuffer buffer = new ContainerLogStreamer.LogLineBuffer();
        String prefix = "a".repeat(256 * 1024 - 2);
        byte[] payload = Arrays.copyOf(utf8(prefix), 256 * 1024);
        payload[payload.length - 2] = (byte) 0xf0;
        payload[payload.length - 1] = '\n';

        assertEquals(List.of(prefix, "\uFFFD"), buffer.append(payload));
        assertFalse(buffer.hasPendingBytes());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void fastFramePreservesContinuationStateForNextFrameAtCap(int characterBytesInFirstFrame) {
        ContainerLogStreamer.LogLineBuffer buffer = new ContainerLogStreamer.LogLineBuffer();
        String prefix = "a".repeat(256 * 1024 - 3 - characterBytesInFirstFrame);
        byte[] payload = utf8(prefix + "🚀€\n");
        int boundary = 256 * 1024 - 3;

        assertEquals(List.of(), buffer.append(Arrays.copyOfRange(payload, 0, boundary)));
        assertEquals(List.of(prefix + "🚀", "€"),
                buffer.append(Arrays.copyOfRange(payload, boundary, payload.length)));
        assertFalse(buffer.hasPendingBytes());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 1024, 262145})
    void frameBoundariesPreserveCappedUnicodeLinesAndTrailingContent(int frameSize) {
        int cap = 256 * 1024;
        String first = "a".repeat(cap - 3) + "€";
        String rockets = "🚀".repeat(cap / 4);
        byte[] payload = utf8(first + "\nshort\n" + rockets + "🚀\ntail");
        ContainerLogStreamer.LogLineBuffer buffer = new ContainerLogStreamer.LogLineBuffer();
        List<String> lines = new ArrayList<>();

        for (int offset = 0; offset < payload.length; offset += frameSize) {
            lines.addAll(buffer.append(Arrays.copyOfRange(payload, offset,
                    Math.min(offset + frameSize, payload.length))));
        }

        assertEquals(List.of(first, "", "short", rockets, "🚀"), lines);
        assertEquals("tail", buffer.flush().orElseThrow());
        assertFalse(buffer.hasPendingBytes());
    }

    @Test
    void emittedLinesAndBufferedFragmentsSurvivePayloadReuse() {
        ContainerLogStreamer.LogLineBuffer buffer = new ContainerLogStreamer.LogLineBuffer();
        byte[] payload = utf8("first\npartial");

        List<String> lines = buffer.append(payload);
        Arrays.fill(payload, (byte) 'x');

        assertEquals(List.of("first"), lines);
        assertEquals(List.of("partial"), buffer.append(utf8("\n")));
    }

    @Test
    void boundsBufferBySplittingOverlongUnterminatedRuns() {
        // A 256 KB run with no newline must be force-emitted (bounded), not buffered until the
        // heap is exhausted — regression guard for the unbounded-growth review finding.
        ContainerLogStreamer.LogLineBuffer buffer = new ContainerLogStreamer.LogLineBuffer();
        byte[] overlong = new byte[256 * 1024];
        Arrays.fill(overlong, (byte) 'a');

        List<String> lines = buffer.append(overlong);

        assertEquals(1, lines.size());
        assertEquals(256 * 1024, lines.get(0).length());
        assertTrue(buffer.flush().isEmpty(), "buffer is drained after the forced split");
    }

    @Test
    void forcedSplitKeepsEventWithinCapAndDefersStraddlingMultibyteChar() {
        // Regression for the oversized-event finding: with the buffer one byte below the cap, a 3-byte
        // '€' would inflate the event to cap+2. The split must instead emit the buffered run WITHIN the
        // cap and defer the whole '€' to the next event — never cutting the character.
        ContainerLogStreamer.LogLineBuffer buffer = new ContainerLogStreamer.LogLineBuffer();
        int cap = 256 * 1024;
        byte[] fill = new byte[cap - 1];
        Arrays.fill(fill, (byte) 'a');
        assertEquals(List.of(), buffer.append(fill));

        List<String> lines = buffer.append(utf8("€"));

        assertEquals(1, lines.size());
        String event = lines.get(0);
        assertTrue(event.getBytes(StandardCharsets.UTF_8).length <= cap, "event must not exceed the cap");
        assertFalse(event.contains("�"), "no half-decoded character in the emitted event");
        assertTrue(event.chars().allMatch(c -> c == 'a'), "the straddling '€' was deferred, not included");
        // '€' is buffered whole and emitted intact on the next newline.
        assertEquals("€", buffer.append(utf8("\n")).get(0));
    }

    @Test
    void keepsCharacterIntactWhenItsFinalBytesArriveAtTheCapInAnotherFrame() {
        // A four-byte character that exactly fills the remaining capacity must not be decoded after its
        // first frame. The terminal continuation bytes complete the same capped event intact.
        ContainerLogStreamer.LogLineBuffer buffer = new ContainerLogStreamer.LogLineBuffer();
        int cap = 256 * 1024;
        byte[] fill = new byte[cap - 4];
        Arrays.fill(fill, (byte) 'a');
        byte[] rocket = utf8("🚀");

        assertEquals(List.of(), buffer.append(fill));
        assertEquals(List.of(), buffer.append(new byte[]{rocket[0], rocket[1]}));
        List<String> lines = buffer.append(new byte[]{rocket[2], rocket[3]});

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).endsWith("🚀"));
        assertFalse(lines.get(0).contains("�"));
    }

    @Test
    void boundsBufferOnRepeatedLeadingBytesThatNeverCompleteACharacter() {
        // Invalid UTF-8 that never reaches a character boundary (a run of lead bytes) must still be
        // force-split at the hard ceiling, not buffered without bound.
        ContainerLogStreamer.LogLineBuffer buffer = new ContainerLogStreamer.LogLineBuffer();
        byte[] leads = new byte[256 * 1024 + 64];
        Arrays.fill(leads, (byte) 0xE2); // 1110xxxx lead byte, never followed by continuations
        List<String> lines = buffer.append(leads);
        assertFalse(lines.isEmpty(), "a boundary-less lead-byte run must be force-split, not buffered unbounded");
    }

    @Test
    void boundsBufferOnStrayContinuationBytes() {
        // A run of stray continuation bytes (invalid UTF-8, no lead byte) must be force-split at the
        // cap rather than growing unbounded.
        ContainerLogStreamer.LogLineBuffer buffer = new ContainerLogStreamer.LogLineBuffer();
        byte[] continuations = new byte[256 * 1024 + 64];
        Arrays.fill(continuations, (byte) 0x80); // 10xxxxxx continuation
        List<String> lines = buffer.append(continuations);
        assertFalse(lines.isEmpty(), "a continuation-only run must be force-split, not buffered unbounded");
    }

    @Test
    void keepsLateUnterminatedFragmentsTogetherUntilTerminal() throws Exception {
        // A cancelled transport can still deliver an in-flight continuation. It must not flush the first
        // fragment then let that continuation become a second event. The terminal callback, not elapsed
        // time, is the boundary that flushes the complete logical line.
        List<String> emitted = new ArrayList<>();
        ContainerLogStreamer.LogReassemblyCallback cb =
                new ContainerLogStreamer.LogReassemblyCallback(emitted::add);

        cb.onNext(new Frame(StreamType.STDOUT, utf8("partial")));
        cb.close();
        cb.onNext(new Frame(StreamType.STDOUT, utf8("-late")));
        assertEquals(List.of(), emitted, "the partial line stays intact until the reader terminates");
        cb.onComplete();

        assertEquals(List.of("partial-late"), emitted);
    }

    @Test
    void lifecycleHandleCloseRetainsDelayedFramesUntilTerminal() throws Exception {
        // Container shutdown has completed, but the Docker reader can still deliver bytes it already read.
        // Closing the lifecycle handle must keep the reader attached through its drain period so a delayed
        // continuation reaches the terminal flush rather than being truncated immediately.
        List<String> emitted = Collections.synchronizedList(new ArrayList<>());
        ContainerLogStreamer.LogReassemblyCallback cb =
                new ContainerLogStreamer.LogReassemblyCallback(emitted::add);
        Closeable handle = new ContainerLogStreamer.ContainerLogHandle(cb);
        cb.onNext(new Frame(StreamType.STDOUT, utf8("before")));

        handle.close();
        cb.onNext(new Frame(StreamType.STDOUT, utf8("-after")));
        cb.onComplete();

        assertEquals(List.of("before-after"), emitted);
    }

    @Test
    void emitsCompletedLinesBeforeTheFinalTailDuringConcurrentLifecycleClose() throws Exception {
        // The reader begins emitting a complete line, then a lifecycle-confirmed container stop drains a
        // trailing partial line. The final tail must queue behind the already-complete line, even though
        // the consumer for that line is still running on a different thread.
        List<String> emitted = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstEmissionStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstEmission = new CountDownLatch(1);
        ContainerLogStreamer.LogReassemblyCallback cb =
                new ContainerLogStreamer.LogReassemblyCallback(line -> {
                    if ("complete".equals(line)) {
                        firstEmissionStarted.countDown();
                        try {
                            releaseFirstEmission.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                    }
                    emitted.add(line);
                });

        Thread reader = new Thread(() -> cb.onNext(new Frame(StreamType.STDOUT, utf8("complete\npartial"))));
        reader.start();
        assertTrue(firstEmissionStarted.await(1, TimeUnit.SECONDS), "the first line should begin emitting");

        Thread closer = new Thread(() -> {
            cb.closeAfterContainerStopped();
        });
        closer.start();
        Thread terminal = new Thread(cb::onComplete);
        terminal.start();
        closer.join();
        releaseFirstEmission.countDown();
        reader.join();
        terminal.join();

        assertEquals(List.of("complete", "partial"), emitted);
    }

    @Test
    void framesDeliveredAfterTransportCloseAreStillEmitted() throws Exception {
        // Cancelling the transport must not permanently stop buffering. A frame the reader delivers after
        // cancellation is still emitted; the eventual terminal drains the rest.
        List<String> emitted = new ArrayList<>();
        ContainerLogStreamer.LogReassemblyCallback cb =
                new ContainerLogStreamer.LogReassemblyCallback(emitted::add);

        cb.onNext(new Frame(StreamType.STDOUT, utf8("before-grace")));
        cb.close();
        cb.onNext(new Frame(StreamType.STDOUT, utf8("-after-close")));
        cb.onComplete(); // the reader's real terminal drains the reassembled tail

        assertEquals(List.of("before-grace-after-close"), emitted);
    }

    @Test
    void onNextAfterTerminalIsIgnored() throws Exception {
        // A straggler frame arriving after the reader's TERMINAL (the true end of the stream) must not be
        // appended into a buffer that will never be flushed — the terminated flag, checked under the
        // lock, drops it deterministically.
        List<String> emitted = new ArrayList<>();
        ContainerLogStreamer.LogReassemblyCallback cb =
                new ContainerLogStreamer.LogReassemblyCallback(emitted::add);

        cb.onNext(new Frame(StreamType.STDOUT, utf8("kept\n")));
        cb.onComplete(); // true terminal: drains and marks terminated
        cb.onNext(new Frame(StreamType.STDOUT, utf8("straggler-after-terminal")));

        assertEquals(List.of("kept"), emitted);
    }

    @Test
    void reassemblesInFlightFramesDeliveredDuringClose() throws Exception {
        // Fragments of ONE newline-free write are delivered by the transport reader AFTER close() is
        // called; the terminal callback then flushes their reassembled line. A separate thread supplies
        // the reader callbacks, mirroring the transport.
        List<String> emitted = Collections.synchronizedList(new ArrayList<>());
        ContainerLogStreamer.LogReassemblyCallback cb =
                new ContainerLogStreamer.LogReassemblyCallback(emitted::add);

        cb.onNext(new Frame(StreamType.STDOUT, utf8("frag1")));

        Thread reader = new Thread(() -> {
            cb.onNext(new Frame(StreamType.STDOUT, utf8("frag2")));
            cb.onNext(new Frame(StreamType.STDOUT, utf8("frag3\n")));
            cb.onComplete(); // terminal: drains and unblocks close()
        });
        reader.start();

        cb.close();
        reader.join();

        assertEquals(List.of("frag1frag2frag3"), emitted);
    }

    @Test
    void flushesTrailingContentOnComplete() throws Exception {
        // A clean end (onComplete) flushes any trailing unterminated line.
        List<String> emitted = new ArrayList<>();
        ContainerLogStreamer.LogReassemblyCallback cb =
                new ContainerLogStreamer.LogReassemblyCallback(emitted::add);

        cb.onNext(new Frame(StreamType.STDOUT, utf8("no-newline-tail")));
        cb.onComplete();

        assertEquals(List.of("no-newline-tail"), emitted);
    }

    @Test
    void reassembledLinesKeepTheStreamTheyCameFrom() {
        List<String> emitted = new ArrayList<>();
        ContainerLogStreamer.LogReassemblyCallback cb = new ContainerLogStreamer.LogReassemblyCallback(
                1_000, (stream, line) -> emitted.add(stream + ": " + line));

        cb.onNext(new Frame(StreamType.STDOUT, utf8("Pi is ")));
        cb.onNext(new Frame(StreamType.STDERR, utf8("INFO starting\n")));
        cb.onNext(new Frame(StreamType.STDOUT, utf8("roughly 3.14\n")));
        cb.onNext(new Frame(StreamType.STDERR, utf8("INFO stopp")));
        cb.onComplete();

        assertEquals(List.of("STDERR: INFO starting", "STDOUT: Pi is roughly 3.14", "STDERR: INFO stopp"), emitted);
    }

    @Test
    void flushesStreamTailsInTheirLastFrameArrivalOrder() {
        List<String> emitted = new ArrayList<>();
        ContainerLogStreamer.LogReassemblyCallback cb =
                new ContainerLogStreamer.LogReassemblyCallback(emitted::add);

        cb.onNext(new Frame(StreamType.STDERR, utf8("stderr-tail")));
        cb.onNext(new Frame(StreamType.STDOUT, utf8("stdout-tail")));
        cb.onComplete();

        assertEquals(List.of("stderr-tail", "stdout-tail"), emitted);
    }

    @Test
    void lifecycleHandleFlushesTailWhenDockerNeverSignalsTerminal() throws Exception {
        List<String> emitted = Collections.synchronizedList(new ArrayList<>());
        ContainerLogStreamer.LogReassemblyCallback cb =
                new ContainerLogStreamer.LogReassemblyCallback(emitted::add, 10);
        Closeable handle = new ContainerLogStreamer.ContainerLogHandle(cb);

        cb.onNext(new Frame(StreamType.STDOUT, utf8("unterminated-tail")));
        handle.close();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (emitted.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(List.of("unterminated-tail"), emitted);
    }

    private static LogContainerCmd attachWith(BiFunction<ContainerLogStreamer, String, Closeable> attach) {
        DockerClient dockerClient = mock(DockerClient.class);
        LogContainerCmd command = mock(LogContainerCmd.class, RETURNS_SELF);
        when(dockerClient.logContainerCmd("container-1")).thenReturn(command);
        ContainerLogStreamer streamer = new ContainerLogStreamer(dockerClient, mock(CloudWatchLogsService.class));
        attach.apply(streamer, "container-1");
        return command;
    }

    @Test
    void attachFollowsTheCompleteHistoryByDefault() {
        LogContainerCmd command = attachWith((streamer, id) ->
                streamer.attach(id, "/aws/lambda/fn", "stream", "us-east-1", "lambda:fn"));

        verify(command).withFollowStream(true);
        verify(command, never()).withSince(anyInt());
    }

    @Test
    void attachFromNowOnlyFollowsLinesEmittedAfterAttachment() {
        long before = Instant.now().getEpochSecond();
        LogContainerCmd command = attachWith((streamer, id) ->
                streamer.attachFromNow(id, "/aws/ecr/registry", "stream", "us-east-1", "ecr:registry"));
        long after = Instant.now().getEpochSecond();

        ArgumentCaptor<Integer> since = ArgumentCaptor.forClass(Integer.class);
        verify(command).withSince(since.capture());
        assertTrue(since.getValue() >= before && since.getValue() <= after,
                "since must be the attach time so an adopted container's history is not replayed");
        verify(command).withFollowStream(true);
    }
}
