package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerLogStreamerTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
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

    @Test
    void boundsBufferBySplittingOverlongUnterminatedRuns() {
        // A 256 KB run with no newline must be force-emitted (bounded), not buffered until the
        // heap is exhausted — regression guard for the unbounded-growth review finding.
        ContainerLogStreamer.LogLineBuffer buffer = new ContainerLogStreamer.LogLineBuffer();
        byte[] overlong = new byte[256 * 1024];
        java.util.Arrays.fill(overlong, (byte) 'a');

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
        java.util.Arrays.fill(fill, (byte) 'a');
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
        java.util.Arrays.fill(fill, (byte) 'a');
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
        java.util.Arrays.fill(leads, (byte) 0xE2); // 1110xxxx lead byte, never followed by continuations
        List<String> lines = buffer.append(leads);
        assertFalse(lines.isEmpty(), "a boundary-less lead-byte run must be force-split, not buffered unbounded");
    }

    @Test
    void boundsBufferOnStrayContinuationBytes() {
        // A run of stray continuation bytes (invalid UTF-8, no lead byte) must be force-split at the
        // cap rather than growing unbounded.
        ContainerLogStreamer.LogLineBuffer buffer = new ContainerLogStreamer.LogLineBuffer();
        byte[] continuations = new byte[256 * 1024 + 64];
        java.util.Arrays.fill(continuations, (byte) 0x80); // 10xxxxxx continuation
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
        List<String> emitted = java.util.Collections.synchronizedList(new ArrayList<>());
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
}
