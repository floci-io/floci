package io.floci.aws.services.lambda.launcher;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class InvocationLogTailTest {

    @Test
    void emptyLogsHaveAnEmptyBase64Result() throws Exception {
        assertEquals("", InvocationLogTail.read(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void truncationCountsUtf8BytesRatherThanCharacters() throws Exception {
        byte[] logs = ("earlier output\n" + "界".repeat(3000) + "\nlast line\n").getBytes(StandardCharsets.UTF_8);

        byte[] actual = Base64.getDecoder().decode(InvocationLogTail.read(new ByteArrayInputStream(logs)));

        assertArrayEquals(Arrays.copyOfRange(logs, logs.length - 4096, logs.length), actual);
    }

    @Test
    void repeatedSmallWritesWrapWithoutLosingTheTail() {
        InvocationLogTail tail = new InvocationLogTail();
        byte[] logs = ("hello\n".repeat(2000) + "last\n").getBytes(StandardCharsets.UTF_8);
        for (int offset = 0; offset < logs.length; offset += 17) {
            tail.append(logs, offset, Math.min(17, logs.length - offset));
        }

        assertArrayEquals(Arrays.copyOfRange(logs, logs.length - 4096, logs.length),
                Base64.getDecoder().decode(tail.encoded()));
    }

    @Test
    void aSingleOversizedWriteKeepsTheLastFourKilobytes() {
        InvocationLogTail tail = new InvocationLogTail();
        byte[] prefix = "old".getBytes(StandardCharsets.UTF_8);
        tail.append(prefix, 0, prefix.length);
        byte[] logs = ("x".repeat(8000) + "end\n").getBytes(StandardCharsets.UTF_8);
        tail.append(logs, 0, logs.length);

        assertArrayEquals(Arrays.copyOfRange(logs, logs.length - 4096, logs.length),
                Base64.getDecoder().decode(tail.encoded()));
    }

    @Test
    void timestampFilteringExcludesAnEarlierInvocationInTheSameSecond() {
        InvocationLogTail tail = new InvocationLogTail();
        Instant start = Instant.parse("2026-11-16T19:00:00.500Z");
        tail.appendTimestamped(("2026-11-16T19:00:00.499999999Z previous invocation\n"
                + "2026-11-16T19:00:00.500000000Z current stdout\n"
                + "2026-11-16T19:00:00.501000000Z current stderr\n").getBytes(StandardCharsets.UTF_8), start);

        assertEquals("current stdout\ncurrent stderr\n", decoded(tail));
    }

    @Test
    void aLogRecordSplitAcrossFramesKeepsItsContinuationAndUnterminatedTail() {
        InvocationLogTail tail = new InvocationLogTail();
        Instant start = Instant.parse("2026-11-16T19:00:00Z");
        tail.appendTimestamped("2026-11-16T19:00:00.001Z hello ".getBytes(StandardCharsets.UTF_8), start);
        tail.appendTimestamped("world\n2026-11-16T19:00:00.002Z last".getBytes(StandardCharsets.UTF_8), start);

        assertEquals("hello world\nlast", decoded(tail));
    }

    private static String decoded(InvocationLogTail tail) {
        return new String(Base64.getDecoder().decode(tail.encoded()), StandardCharsets.UTF_8);
    }

    @Test
    void aTimestampPrefixSplitAcrossFramesIsRemovedWithoutLosingOutput() {
        InvocationLogTail tail = new InvocationLogTail();
        Instant start = Instant.parse("2026-11-16T19:00:00Z");
        tail.appendTimestamped("2026-11-16T19:".getBytes(StandardCharsets.UTF_8), start);
        tail.appendTimestamped("00:00.001Z hello\n".getBytes(StandardCharsets.UTF_8), start);

        assertEquals("hello\n", decoded(tail));
    }

    @Test
    void everySplitPointPreservesTheSameTimestampedUtf8Output() {
        byte[] record = "2026-11-16T19:00:00.001Z hello 世界\n".getBytes(StandardCharsets.UTF_8);
        for (int split = 1; split < record.length; split++) {
            InvocationLogTail tail = new InvocationLogTail();
            tail.appendTimestamped(Arrays.copyOfRange(record, 0, split), Instant.EPOCH);
            tail.appendTimestamped(Arrays.copyOfRange(record, split, record.length), Instant.EPOCH);
            assertEquals("hello 世界\n", decoded(tail), "split at byte " + split);
        }
    }
}
