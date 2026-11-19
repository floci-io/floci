package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PolledLogHandleTest {

    private final List<String> lines = new ArrayList<>();

    private PolledLogHandle handle(Instant since) {
        return new PolledLogHandle(mock(DockerClient.class), "c1", since,
                (stream, line) -> lines.add(stream + ":" + line));
    }

    private static byte[] record(String timestamp, String content) {
        return (timestamp + " " + content).getBytes(StandardCharsets.UTF_8);
    }

    private static void read(PolledLogHandle handle, StreamType stream, byte[]... frames) {
        handle.beginRead();
        for (byte[] frame : frames) {
            handle.accept(stream, frame);
        }
    }

    // Catches: a whole-second since re-reading the records of the last second into CloudWatch Logs
    // again, or a "last timestamp plus a nanosecond" cursor dropping a later record that shares a timestamp.
    @Test
    void recordsAreForwardedOnceAcrossOverlappingReadsEvenWhenTheyShareATimestamp() {
        PolledLogHandle handle = handle(null);
        String t1 = "2026-11-19T10:00:00.100000000Z";
        String t2 = "2026-11-19T10:00:00.200000000Z";
        String t3 = "2026-11-19T10:00:01.000000000Z";

        read(handle, StreamType.STDOUT, record(t1, "a\n"), record(t2, "b\n"), record(t2, "c\n"));
        read(handle, StreamType.STDOUT, record(t1, "a\n"), record(t2, "b\n"), record(t2, "c\n"),
                record(t2, "d\n"), record(t3, "e\n"));

        assertEquals(List.of("STDOUT:a", "STDOUT:b", "STDOUT:c", "STDOUT:d", "STDOUT:e"), lines);
    }

    // Catches: a line the container had not finished writing at one read being split in two, or its
    // first half being forwarded twice.
    @Test
    void aLineSplitAcrossReadsIsForwardedWhole() {
        PolledLogHandle handle = handle(null);
        String t1 = "2026-11-19T10:00:00.100000000Z";
        String t2 = "2026-11-19T10:00:00.900000000Z";

        read(handle, StreamType.STDERR, record(t1, "hel"));
        read(handle, StreamType.STDERR, record(t1, "hel"), record(t2, "lo\n"));

        assertEquals(List.of("STDERR:hello"), lines);
    }

    // Catches: an attach from now replaying the history of a container that ran before Floci started.
    @Test
    void anAttachFromNowSkipsWhatTheContainerWroteBefore() {
        PolledLogHandle handle = handle(Instant.parse("2026-11-19T10:00:00.500000000Z"));

        read(handle, StreamType.STDOUT, record("2026-11-19T10:00:00.100000000Z", "old\n"),
                record("2026-11-19T10:00:00.600000000Z", "new\n"));

        assertEquals(List.of("STDOUT:new"), lines);
    }

    // Catches: an unterminated last line lost at close, or a read landing after the close duplicating it.
    @Test
    void finishFlushesTheUnterminatedTailAndIgnoresLaterReads() {
        PolledLogHandle handle = handle(null);
        read(handle, StreamType.STDOUT, record("2026-11-19T10:00:00.100000000Z", "shutting down"));

        handle.finish();
        read(handle, StreamType.STDOUT, record("2026-11-19T10:00:02.000000000Z", "late\n"));

        assertEquals(List.of("STDOUT:shutting down"), lines);
    }

    // Catches: a polled container's last lines lost, since a removed container's log can no longer be read.
    @Test
    void stopAndRemoveReadsAPolledLogBetweenTheStopAndTheRemoval() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        StopContainerCmd stop = mock(StopContainerCmd.class, Answers.RETURNS_SELF);
        when(docker.stopContainerCmd("c1")).thenReturn(stop);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, Answers.RETURNS_SELF);
        when(docker.removeContainerCmd("c1")).thenReturn(remove);
        LogContainerCmd logs = mock(LogContainerCmd.class);
        when(docker.logContainerCmd("c1")).thenReturn(logs);
        when(logs.withStdOut(anyBoolean())).thenReturn(logs);
        when(logs.withStdErr(anyBoolean())).thenReturn(logs);
        when(logs.withFollowStream(anyBoolean())).thenReturn(logs);
        when(logs.withTimestamps(anyBoolean())).thenReturn(logs);
        when(logs.withSince(anyInt())).thenReturn(logs);
        when(logs.exec(any())).thenAnswer(invocation -> {
            ResultCallback.Adapter<Frame> callback = invocation.getArgument(0);
            callback.onNext(new Frame(StreamType.STDOUT, record("2026-11-19T10:00:00.100000000Z", "bye\n")));
            callback.onComplete();
            return callback;
        });
        PolledLogHandle polled = new PolledLogHandle(docker, "c1", null, (stream, line) -> lines.add(line));
        ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, docker, null, null, null);

        manager.stopAndRemove("c1", polled);

        InOrder order = inOrder(stop, logs, remove);
        order.verify(stop).exec();
        order.verify(logs).exec(any());
        order.verify(remove).exec();
        assertEquals(List.of("bye"), lines);
    }
}
